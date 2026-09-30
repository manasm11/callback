#!/usr/bin/env python3
"""Sync server for the Callback Tracker app.

Stores call and resolution events uploaded by the shop phones and hands each phone
the events the other phones produced. It applies no rules itself: every phone runs
the same resolution logic over the shared events. Standard library only.

Run: python3 callback_sync_server.py --host "$(tailscale ip -4)" --db events.db
"""

import argparse
import json
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DEFAULT_PORT = 8787
PAGE_SIZE = 500  # must match SyncEngine.PULL_PAGE_SIZE in the app
RETENTION_MS = 7 * 24 * 60 * 60 * 1000  # matches the app's 7-day rule
EVENT_TYPES = {"CALL", "MANUAL_RESOLVE", "UNRESOLVE"}
CALL_DIRECTIONS = {"INCOMING", "OUTGOING"}


def now_ms():
    return int(time.time() * 1000)


class EventStore:
    """SQLite-backed event log, safe to use from several request threads."""

    def __init__(self, path, clock=now_ms):
        self._clock = clock
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False)
        self._db.executescript(
            """
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS events (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                event_id TEXT UNIQUE NOT NULL,
                device_id TEXT NOT NULL,
                type TEXT NOT NULL,
                number TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                duration_seconds INTEGER,
                direction TEXT
            );
            """
        )
        row = self._db.execute("SELECT value FROM meta WHERE key = 'server_id'").fetchone()
        if row is None:
            # A fresh ID per database lets phones notice when the server was reset.
            self.server_id = str(uuid.uuid4())
            self._db.execute("INSERT INTO meta (key, value) VALUES ('server_id', ?)", (self.server_id,))
            self._db.commit()
        else:
            self.server_id = row[0]

    def add(self, device_id, events):
        """Stores events not seen before and returns how many were new.

        Raises ValueError, storing nothing, if any part of the input is invalid.
        """
        if not isinstance(device_id, str) or not device_id:
            raise ValueError("deviceId must be a non-empty string")
        if not isinstance(events, list):
            raise ValueError("events must be a list")
        rows = [_event_row(device_id, event) for event in events]
        with self._lock:
            before = self._db.total_changes
            self._db.executemany(
                "INSERT OR IGNORE INTO events "
                "(event_id, device_id, type, number, timestamp, duration_seconds, direction) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                rows,
            )
            accepted = self._db.total_changes - before
            self._purge_locked()
            self._db.commit()
        return accepted

    def since(self, after, device_id, limit=PAGE_SIZE):
        """Returns (latest seq, up to `limit` events after `after` from devices other than `device_id`)."""
        with self._lock:
            latest = self._db.execute("SELECT COALESCE(MAX(seq), 0) FROM events").fetchone()[0]
            rows = self._db.execute(
                "SELECT seq, event_id, device_id, type, number, timestamp, duration_seconds, direction "
                "FROM events WHERE seq > ? AND device_id != ? ORDER BY seq LIMIT ?",
                (after, device_id, limit),
            ).fetchall()
        events = [
            {
                "seq": seq,
                "eventId": event_id,
                "deviceId": event_device,
                "type": event_type,
                "number": number,
                "timestamp": timestamp,
                "durationSeconds": duration,
                "direction": direction,
            }
            for seq, event_id, event_device, event_type, number, timestamp, duration, direction in rows
        ]
        return latest, events

    def purge(self):
        """Deletes events older than the retention window."""
        with self._lock:
            self._purge_locked()
            self._db.commit()

    def close(self):
        self._db.close()

    def _purge_locked(self):
        self._db.execute("DELETE FROM events WHERE timestamp < ?", (self._clock() - RETENTION_MS,))


def _is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def _event_row(device_id, event):
    if not isinstance(event, dict):
        raise ValueError("each event must be an object")
    event_id = event.get("eventId")
    event_type = event.get("type")
    number = event.get("number")
    timestamp = event.get("timestamp")
    if not isinstance(event_id, str) or not event_id:
        raise ValueError("eventId must be a non-empty string")
    if not isinstance(event_type, str) or event_type not in EVENT_TYPES:
        raise ValueError(f"type must be one of {sorted(EVENT_TYPES)}")
    if not isinstance(number, str) or not number:
        raise ValueError("number must be a non-empty string")
    if not _is_int(timestamp):
        raise ValueError("timestamp must be an integer")
    duration = event.get("durationSeconds")
    direction = event.get("direction")
    if event_type == "CALL":
        if not _is_int(duration) or duration < 0:
            raise ValueError("CALL events need a non-negative integer durationSeconds")
        if not isinstance(direction, str) or direction not in CALL_DIRECTIONS:
            raise ValueError(f"CALL events need direction in {sorted(CALL_DIRECTIONS)}")
    else:
        duration, direction = None, None
    return (event_id, device_id, event_type, number, timestamp, duration, direction)


def make_handler(store):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/health":
                self._send(200, {"serverId": store.server_id})
            elif url.path == "/events":
                query = parse_qs(url.query)
                try:
                    after = int(query["after"][0])
                    device_id = query["device"][0]
                except (KeyError, ValueError):
                    self._send(400, {"error": "after (integer) and device are required"})
                    return
                latest, events = store.since(after, device_id)
                self._send(200, {"serverId": store.server_id, "latestSeq": latest, "events": events})
            else:
                self._send(404, {"error": "not found"})

        def do_POST(self):
            if urlparse(self.path).path != "/events":
                self._send(404, {"error": "not found"})
                return
            try:
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError("body must be a JSON object")
                accepted = store.add(body.get("deviceId"), body.get("events"))
            except ValueError as error:  # includes JSON and UTF-8 decoding errors
                self._send(400, {"error": str(error)})
                return
            self._send(200, {"serverId": store.server_id, "accepted": accepted})

        def _send(self, status, payload):
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_request(self, code="-", size="-"):
            # Every phone polls every 30 s; log only failures to keep the journal readable.
            if isinstance(code, int) and code >= 400:
                super().log_request(code, size)

    return Handler


def main():
    parser = argparse.ArgumentParser(description="Callback Tracker sync server")
    parser.add_argument("--host", required=True, help="address to listen on; use the PC's Tailscale IP")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--db", default="callback-sync.db", help="SQLite file path")
    args = parser.parse_args()

    store = EventStore(args.db)
    store.purge()

    def purge_hourly():
        while True:
            time.sleep(3600)
            store.purge()

    threading.Thread(target=purge_hourly, daemon=True).start()
    server = ThreadingHTTPServer((args.host, args.port), make_handler(store))
    print(f"Callback sync server {store.server_id} listening on {args.host}:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
