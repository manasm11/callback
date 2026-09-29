# Multi-Phone Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pending callback disappears from every shop phone once the customer is reached from any of the 4 phones. Each phone keeps working exactly as today when offline.

**Architecture:**
- **Server:** a stdlib-only Python server on the shop's Linux PC. It stores an append-only event log (answered/outgoing calls over 1 s, plus manual resolve/un-resolve) and relays each phone's events to the others. It applies no rules.
- **Phones:** each phone keeps its own Room database, queues its events in an outbox, and pulls others' events every 30 s and after each scan. It runs the resolution rules locally.

**Tech Stack:**
- Server: Python 3 standard library (`http.server`, `sqlite3`, `unittest`), run under systemd.
- App:
  - Kotlin, Room 2.6.1 (database version 1 → 2 with a real migration), Jetpack Compose, coroutines
  - `HttpURLConnection` and `org.json` for networking
  - Robolectric 4.14 for tests, with the JDK's `com.sun.net.httpserver` for an in-process fake server

**Spec:** `docs/superpowers/specs/2026-09-30-multi-phone-sync-design.md`. Read it before starting any task.

## Global Constraints

**Dependencies**
- No new app dependencies. Networking uses `java.net.HttpURLConnection` and `org.json` only.
- The server uses the Python standard library only, with no third-party packages.

**Protocol**
- Server default port: `8787`.
- Page size is `500` on both sides: `PAGE_SIZE` in `server/callback_sync_server.py` and `SyncEngine.PULL_PAGE_SIZE` in the app.
- Upload batch size is `200`.
- JSON field names are exactly: `deviceId`, `events`, `eventId`, `type`, `number`, `timestamp`, `durationSeconds`, `direction`, `seq`, `serverId`, `latestSeq`, `accepted`, `error`.
- Event types are exactly `CALL`, `MANUAL_RESOLVE` and `UNRESOLVE`. Call directions are exactly `INCOMING` and `OUTGOING`.
- Event ID formats:
  - `"<deviceId>:call:<callLogRowId>"`
  - `"<deviceId>:manual:<random uuid>"`

**Behaviour**
- Retention: 7 days everywhere (`CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS` on the phone, `RETENTION_MS` on the server).
- A call counts only if `durationSeconds > 1`.
- Missed calls are never uploaded.
- Sync is off when the server URL is empty, and that is the default.
- Sync cadence: after every scan, plus every 30 s (`SYNC_INTERVAL_MILLIS = 30_000`).
- HTTP timeouts: 5 s connect, 10 s read.

**Running tests**
- Android tests, from the repo root `/home/manas/ddg/callback`: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`. Add `--tests '<pattern>'` to narrow.
- `local.properties` must contain `sdk.dir=/home/manas/android-sdk`; it already does and is gitignored.
- Server tests, from the repo root: `python3 -m unittest discover -s server -v`.

**Commits**
- Commit on `master`.
- Every commit message ends with a blank line and then these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
  ```
- Do not push. Pushing to `main` publishes a release APK, and the controller pushes once after the final review.

---

## File Structure

**Server (new)**

| File | Responsibility |
|---|---|
| `server/callback_sync_server.py` | `EventStore` (SQLite event log), `make_handler` (HTTP API) and `main` (CLI) |
| `server/test_callback_sync_server.py` | Store and HTTP API tests |
| `server/callback-sync.service` | systemd unit |
| `server/README.md` | Install steps and the real-phone test checklist |
| `.github/workflows/build-apk.yml` | Modify: run the server tests in CI |

**App data layer**

| File | Responsibility |
|---|---|
| `data/SyncEventEntities.kt` (new) | `SyncEventType`, `OutboxEventEntity`, `RemoteEventEntity` |
| `data/SyncEventDao.kt` (new) | Outbox and remote-event queries |
| `data/CallbackThreadEntity.kt` | Modify: `reopenedAt`, new `ResolvedReason` values, `callbackStart()` |
| `data/CallbackThreadDao.kt` | Modify: `reopen(number, reopenedAt)` |
| `data/CallbackTypeConverters.kt` | Modify: `SyncEventType` converter |
| `data/CallbackDatabase.kt` | Modify: version 2, `MIGRATION_1_2`, `syncEventDao()` |

**App sync package (new)**

| File | Responsibility |
|---|---|
| `sync/OutboxEvents.kt` | Builds outbox events from call-log entries and manual actions |
| `sync/RemoteEventApplier.kt` | Applies other phones' events to local callbacks |
| `sync/SyncSettings.kt` | SharedPreferences: device ID, server URL, server ID, cursor, last sync time |
| `sync/SyncClient.kt` | HTTP client for the three endpoints |
| `sync/SyncEngine.kt` | One upload → pull → apply pass, server-reset handling, URL change |

**App wiring and UI**

| File | Responsibility |
|---|---|
| `calllog/CallLogScanner.kt` | Modify: public `MIN_VALID_NUMBER_LENGTH`; answered rule uses `callbackStart()` |
| `service/CallWatcherService.kt` | Modify: enqueue, apply, sync loop, `ACTION_SERVER_CHANGED` |
| `ui/CallbackViewModel.kt` | Modify: `now` property; manual actions enqueue events |
| `ui/HistoryScreen.kt` | Modify: `resolvedReasonText` with the remote labels |
| `ui/SyncStatus.kt` (new) | `syncStatusText` |
| `ui/SettingsViewModel.kt` (new) | Settings state, test connection, save |
| `ui/SettingsScreen.kt` (new) | Settings tab UI |
| `MainActivity.kt` | Modify: third tab |
| `AndroidManifest.xml` | Modify: `INTERNET` permission, cleartext allowed |

All app paths are under `app/src/main/java/com/shopcallback/tracker/`. Tests mirror them under `app/src/test/java/com/shopcallback/tracker/`.

---

### Task 1: Sync server

**Files:**
- Create: `server/callback_sync_server.py`
- Create: `server/test_callback_sync_server.py`
- Create: `server/callback-sync.service`
- Create: `server/README.md`
- Modify: `.github/workflows/build-apk.yml` (add a step after `Checkout`)

**Interfaces:**
- Produces the HTTP API that `SyncClient` (Task 4) consumes:
  - **`GET /health`** returns `200 {"serverId": str}`.
  - **`POST /events`** with body `{"deviceId": str, "events": [event]}` returns `200 {"serverId": str, "accepted": int}` or `400 {"error": str}`. An `event` has:
    - `eventId`, `type`, `number` and `timestamp` (all events)
    - `durationSeconds` and `direction` (required only for `CALL`; ignored and stored as null for manual events)
  - **`GET /events?after=<int>&device=<str>`** returns `200 {"serverId": str, "latestSeq": int, "events": [...]}` or `400`.
    - Each event has `seq`, `eventId`, `deviceId`, `type`, `number`, `timestamp`, `durationSeconds` and `direction`. The last two are `null` for manual events.
    - Events are ordered by `seq`, at most 500, and exclude the caller's own device.

- [ ] **Step 1: Write the failing tests**

Create `server/test_callback_sync_server.py`:

```python
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import callback_sync_server as sync

DAY_MS = 24 * 60 * 60 * 1000
NOW = 100 * DAY_MS


def call(event_id, number="9876543210", timestamp=NOW, duration=30, direction="OUTGOING"):
    return {
        "eventId": event_id,
        "type": "CALL",
        "number": number,
        "timestamp": timestamp,
        "durationSeconds": duration,
        "direction": direction,
    }


class EventStoreTest(unittest.TestCase):
    def setUp(self):
        self.store = sync.EventStore(":memory:", clock=lambda: NOW)

    def tearDown(self):
        self.store.close()

    def test_duplicate_event_ids_are_stored_once(self):
        self.assertEqual(self.store.add("phone-a", [call("a:call:1")]), 1)
        self.assertEqual(self.store.add("phone-a", [call("a:call:1"), call("a:call:2")]), 1)
        _, events = self.store.since(0, "phone-b")
        self.assertEqual([e["eventId"] for e in events], ["a:call:1", "a:call:2"])

    def test_since_skips_the_callers_own_events_and_already_seen_ones(self):
        self.store.add("phone-a", [call("a:call:1")])
        self.store.add("phone-b", [call("b:call:1")])
        self.store.add("phone-a", [call("a:call:2")])
        latest, events = self.store.since(1, "phone-a")
        self.assertEqual(latest, 3)
        self.assertEqual([(e["seq"], e["eventId"], e["deviceId"]) for e in events], [(2, "b:call:1", "phone-b")])

    def test_since_pages(self):
        self.store.add("phone-a", [call(f"a:call:{i}") for i in range(5)])
        _, first = self.store.since(0, "phone-b", limit=3)
        _, rest = self.store.since(first[-1]["seq"], "phone-b", limit=3)
        self.assertEqual(len(first), 3)
        self.assertEqual(len(rest), 2)

    def test_manual_events_carry_no_call_fields(self):
        self.store.add("phone-a", [{
            "eventId": "a:manual:x", "type": "MANUAL_RESOLVE", "number": "9876543210",
            "timestamp": NOW, "durationSeconds": 5, "direction": "OUTGOING",
        }])
        _, events = self.store.since(0, "phone-b")
        self.assertIsNone(events[0]["durationSeconds"])
        self.assertIsNone(events[0]["direction"])

    def test_events_older_than_7_days_are_purged(self):
        self.store.add("phone-a", [call("old", timestamp=NOW - 8 * DAY_MS), call("recent", timestamp=NOW - 6 * DAY_MS)])
        _, events = self.store.since(0, "phone-b")
        self.assertEqual([e["eventId"] for e in events], ["recent"])

    def test_invalid_input_is_rejected_and_nothing_is_stored(self):
        bad_events = [
            "not an object",
            {**call("x"), "eventId": ""},
            {**call("x"), "type": "HANGUP"},
            {**call("x"), "type": ["CALL"]},
            {**call("x"), "number": ""},
            {**call("x"), "timestamp": "yesterday"},
            {**call("x"), "timestamp": True},
            {**call("x"), "durationSeconds": -1},
            {**call("x"), "durationSeconds": None},
            {**call("x"), "direction": "MISSED"},
        ]
        for event in bad_events:
            with self.subTest(event=event):
                with self.assertRaises(ValueError):
                    self.store.add("phone-a", [call("fine"), event])
        with self.assertRaises(ValueError):
            self.store.add("", [call("x")])
        with self.assertRaises(ValueError):
            self.store.add("phone-a", "not a list")
        self.assertEqual(self.store.since(0, "phone-b"), (0, []))

    def test_server_id_survives_a_restart(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "events.db")
            first = sync.EventStore(path)
            server_id = first.server_id
            first.close()
            second = sync.EventStore(path)
            self.assertEqual(second.server_id, server_id)
            second.close()


class HttpApiTest(unittest.TestCase):
    def setUp(self):
        self.store = sync.EventStore(":memory:", clock=lambda: NOW)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), sync.make_handler(self.store))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.store.close()

    def request(self, method, path, body=None):
        data = None
        if body is not None:
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
        req = urllib.request.Request(
            self.base + path, data=data, method=method, headers={"Content-Type": "application/json"}
        )
        try:
            with urllib.request.urlopen(req) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def test_health_reports_the_server_id(self):
        self.assertEqual(self.request("GET", "/health"), (200, {"serverId": self.store.server_id}))

    def test_an_upload_is_pulled_by_another_phone(self):
        status, body = self.request("POST", "/events", {"deviceId": "phone-a", "events": [call("a:call:1")]})
        self.assertEqual((status, body), (200, {"serverId": self.store.server_id, "accepted": 1}))

        status, body = self.request("GET", "/events?after=0&device=phone-b")
        self.assertEqual(status, 200)
        self.assertEqual(body["serverId"], self.store.server_id)
        self.assertEqual(body["latestSeq"], 1)
        self.assertEqual(body["events"], [{
            "seq": 1, "eventId": "a:call:1", "deviceId": "phone-a", "type": "CALL",
            "number": "9876543210", "timestamp": NOW, "durationSeconds": 30, "direction": "OUTGOING",
        }])

    def test_bad_requests_get_400(self):
        self.assertEqual(self.request("POST", "/events", b"not json")[0], 400)
        self.assertEqual(self.request("POST", "/events", b"[]")[0], 400)
        self.assertEqual(self.request("POST", "/events", {"deviceId": "phone-a", "events": [{"type": "CALL"}]})[0], 400)
        self.assertEqual(self.request("GET", "/events?after=x&device=phone-b")[0], 400)
        self.assertEqual(self.request("GET", "/events")[0], 400)

    def test_unknown_paths_get_404(self):
        self.assertEqual(self.request("GET", "/nope")[0], 404)
        self.assertEqual(self.request("POST", "/nope", {})[0], 404)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `python3 -m unittest discover -s server -v`
Expected: an error, `ModuleNotFoundError: No module named 'callback_sync_server'`.

- [ ] **Step 3: Write the server**

Create `server/callback_sync_server.py`:

```python
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 -m unittest discover -s server -v`
Expected: all 11 tests `ok`.

- [ ] **Step 5: Add the systemd unit, the README and the CI step**

Create `server/callback-sync.service`:

```ini
[Unit]
Description=Callback Tracker sync server
Wants=network-online.target
After=network-online.target tailscaled.service

[Service]
# Listen only on this PC's Tailscale address, so only tailnet devices can connect.
ExecStart=/bin/sh -c 'exec /usr/bin/python3 /opt/callback-sync/callback_sync_server.py --host "$(tailscale ip -4)" --db /var/lib/callback-sync/events.db'
DynamicUser=yes
StateDirectory=callback-sync
# Tailscale may not have an address yet right after boot; keep retrying.
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Create `server/README.md`:

````markdown
# Callback Tracker sync server

Relays "customer was reached" events between the shop phones so a callback
answered on one phone disappears from the others. Phones keep working on
their own when this server is off; they catch up when it's back.

It listens only on this PC's Tailscale address, so only devices signed in to
your tailnet can reach it. Events older than 7 days are deleted automatically.

## Install (Linux PC)

1. Install Tailscale and sign in: `tailscale ip -4` must print an address.
2. From this repo's root:

   ```sh
   sudo install -Dm644 server/callback_sync_server.py /opt/callback-sync/callback_sync_server.py
   sudo install -Dm644 server/callback-sync.service /etc/systemd/system/callback-sync.service
   sudo systemctl daemon-reload
   sudo systemctl enable --now callback-sync
   ```

3. Check it: `curl "http://$(tailscale ip -4):8787/health"` prints `{"serverId": "..."}`.
4. Note the PC's Tailscale name (first column of `tailscale status` for this PC).

To update after a code change, repeat the first `install` line and run
`sudo systemctl restart callback-sync`. Logs: `journalctl -u callback-sync`.

## Connect each phone

1. Install the Tailscale app and sign in to the same account.
2. In Callback Tracker, open **Settings**, enter `http://<pc-tailscale-name>:8787`,
   tap **Test connection** (it should say "Connected ✓"), then **Save**.

## Check it works (on the real phones)

1. Miss a call on phone A from a test number. Call that number back from phone B
   and talk for a few seconds. Within about 30 s it leaves A's Pending list, and
   History says "answered on another phone".
2. Miss calls on A and B from the same number. Tap **Mark resolved** on A. It leaves
   B's Pending list too ("marked resolved on another phone").
3. Turn off Wi-Fi and mobile data on A, then miss a call on it. It still appears in
   Pending on A.
4. Turn A's network back on. Settings on A shows "0 waiting to upload" shortly after.
5. Stop the server (`sudo systemctl stop callback-sync`) and make a call back from B.
   Start it again, and within about 30 s the callback clears on A.

## Tests

`python3 -m unittest discover -s server -v`
````

In `.github/workflows/build-apk.yml`, insert this step immediately after the `Checkout` step:

```yaml
      - name: Run sync server tests
        run: python3 -m unittest discover -s server -v
```

- [ ] **Step 6: Commit**

```bash
git add server .github/workflows/build-apk.yml
git commit -F - <<'EOF'
Add sync server relaying call events between shop phones

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```

---

### Task 2: Database version 2 (sync tables, reopenedAt, migration)

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/data/SyncEventEntities.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/data/SyncEventDao.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/data/CallbackThreadEntity.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/data/CallbackThreadDao.kt` (`reopen`)
- Modify: `app/src/main/java/com/shopcallback/tracker/data/CallbackTypeConverters.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/data/CallbackDatabase.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/ui/CallbackViewModel.kt` (`now` property; `unresolve` passes the time)
- Test: `app/src/test/java/com/shopcallback/tracker/data/CallbackDatabaseMigrationTest.kt` (new)
- Test: `app/src/test/java/com/shopcallback/tracker/data/SyncEventDaoTest.kt` (new)
- Test: `app/src/test/java/com/shopcallback/tracker/data/CallbackThreadDaoTest.kt` (update the `reopen` test)

**Interfaces:**
- Produces:
  - `enum class SyncEventType { CALL, MANUAL_RESOLVE, UNRESOLVE }`
  - `OutboxEventEntity(eventId: String, type: SyncEventType, number: String, timestamp: Long, durationSeconds: Int? = null, direction: String? = null)`
  - `RemoteEventEntity(eventId: String, type: SyncEventType, number: String, timestamp: Long, durationSeconds: Int? = null, direction: String? = null, applied: Boolean = false)`
  - `CallbackThreadEntity.reopenedAt: Long?`, defaulting to `null`
  - `ResolvedReason { AUTO_ANSWERED, MANUAL, REMOTE_ANSWERED, REMOTE_MANUAL }`
  - `CallbackThreadDao.reopen(phoneNumber: String, reopenedAt: Long)`
  - `CallbackDatabase.syncEventDao(): SyncEventDao`
  - `CallbackDatabase.MIGRATION_1_2`
  - `SyncEventDao` suspend methods:
    - `enqueue(events: List<OutboxEventEntity>)`: inserts, ignoring duplicates
    - `outboxBatch(limit: Int): List<OutboxEventEntity>`: oldest first
    - `deleteOutbox(eventIds: List<String>)`
    - `deleteOutboxBefore(cutoff: Long)`
    - `insertRemote(events: List<RemoteEventEntity>)`: inserts, ignoring duplicates
    - `unappliedManualEvents(): List<RemoteEventEntity>`: oldest first
    - `markApplied(eventIds: List<String>)`
    - `earliestRemoteCallAfter(number: String, after: Long): Long?`
    - `deleteRemoteBefore(cutoff: Long)`
  - `SyncEventDao` flow: `observeOutboxCount(): Flow<Int>`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/shopcallback/tracker/data/CallbackDatabaseMigrationTest.kt`:

```kotlin
package com.shopcallback.tracker.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallbackDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migrating from version 1 keeps existing callbacks and adds the sync tables`() = runBlocking {
        // Recreate a database exactly as version 1 of the app left it (SQL copied from Room's v1 output).
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { v1 ->
            v1.execSQL(
                "CREATE TABLE IF NOT EXISTS `callback_threads` (`phoneNumber` TEXT NOT NULL, `displayName` TEXT, " +
                    "`firstMissedAt` INTEGER NOT NULL, `lastMissedAt` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, " +
                    "`status` TEXT NOT NULL, `resolvedAt` INTEGER, `resolvedReason` TEXT, PRIMARY KEY(`phoneNumber`))"
            )
            v1.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            v1.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '72f6e56ac9aa7fd9ade168c98827483f')")
            v1.execSQL("INSERT INTO callback_threads VALUES ('9876543210', 'Priya', 100, 200, 2, 'PENDING', NULL, NULL)")
            v1.version = 1
        }

        // Room validates the migrated schema against the entities and throws if MIGRATION_1_2 is wrong.
        val db = Room.databaseBuilder(context, CallbackDatabase::class.java, dbName)
            .addMigrations(CallbackDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val thread = db.callbackThreadDao().findByNumber("9876543210")
            assertEquals("Priya", thread?.displayName)
            assertEquals(2, thread?.attemptCount)
            assertEquals(CallbackStatus.PENDING, thread?.status)
            assertNull(thread?.reopenedAt)

            db.syncEventDao().enqueue(listOf(OutboxEventEntity("d:call:1", SyncEventType.CALL, "9876543210", 300L, 40, "OUTGOING")))
            assertEquals(1, db.syncEventDao().outboxBatch(10).size)
        } finally {
            db.close()
        }
    }
}
```

Create `app/src/test/java/com/shopcallback/tracker/data/SyncEventDaoTest.kt`:

```kotlin
package com.shopcallback.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEventDaoTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: SyncEventDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.syncEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `outbox returns oldest first, ignores duplicates and deletes by id`() = runBlocking {
        dao.enqueue(listOf(outbox("b", 200L), outbox("a", 100L)))
        dao.enqueue(listOf(outbox("a", 999L)))

        assertEquals(listOf("a", "b"), dao.outboxBatch(10).map { it.eventId })
        assertEquals(100L, dao.outboxBatch(10).first().timestamp)
        assertEquals(listOf("a"), dao.outboxBatch(1).map { it.eventId })
        assertEquals(2, dao.observeOutboxCount().first())

        dao.deleteOutbox(listOf("a"))
        assertEquals(listOf("b"), dao.outboxBatch(10).map { it.eventId })
    }

    @Test
    fun `old outbox and remote events are purged`() = runBlocking {
        dao.enqueue(listOf(outbox("old", 100L), outbox("new", 500L)))
        dao.insertRemote(listOf(remoteCall("old", 100L), remoteCall("new", 500L)))

        dao.deleteOutboxBefore(300L)
        dao.deleteRemoteBefore(300L)

        assertEquals(listOf("new"), dao.outboxBatch(10).map { it.eventId })
        assertEquals(500L, dao.earliestRemoteCallAfter("9876543210", 0L))
    }

    @Test
    fun `earliestRemoteCallAfter only counts calls over 1 second after the given time`() = runBlocking {
        dao.insertRemote(
            listOf(
                remoteCall("before", 100L),
                remoteCall("short", 300L, durationSeconds = 1),
                remoteCall("other-number", 350L, number = "9123456789"),
                remoteCall("match", 400L),
                remoteCall("later", 900L),
                RemoteEventEntity("manual", SyncEventType.MANUAL_RESOLVE, "9876543210", 250L)
            )
        )

        assertEquals(400L, dao.earliestRemoteCallAfter("9876543210", 200L))
        assertNull(dao.earliestRemoteCallAfter("9876543210", 900L))
    }

    @Test
    fun `unapplied manual events come oldest first until marked applied`() = runBlocking {
        dao.insertRemote(
            listOf(
                RemoteEventEntity("u", SyncEventType.UNRESOLVE, "9876543210", 300L),
                RemoteEventEntity("m", SyncEventType.MANUAL_RESOLVE, "9876543210", 200L),
                remoteCall("c", 100L)
            )
        )
        dao.insertRemote(listOf(RemoteEventEntity("m", SyncEventType.MANUAL_RESOLVE, "9876543210", 999L)))

        assertEquals(listOf("m", "u"), dao.unappliedManualEvents().map { it.eventId })
        dao.markApplied(listOf("m"))
        assertEquals(listOf("u"), dao.unappliedManualEvents().map { it.eventId })
    }

    private fun outbox(id: String, timestamp: Long) =
        OutboxEventEntity(id, SyncEventType.CALL, "9876543210", timestamp, 30, "OUTGOING")

    private fun remoteCall(id: String, timestamp: Long, durationSeconds: Int = 30, number: String = "9876543210") =
        RemoteEventEntity(id, SyncEventType.CALL, number, timestamp, durationSeconds, "OUTGOING")
}
```

In `app/src/test/java/com/shopcallback/tracker/data/CallbackThreadDaoTest.kt`, replace the test `` `reopen moves a resolved thread back to pending and clears resolution` `` with:

```kotlin
    @Test
    fun `reopen moves a resolved thread back to pending, clears resolution and records when`() = runBlocking {
        dao.upsert(pendingThread("555").copy(attemptCount = 3))
        dao.markResolved("555", CallbackStatus.RESOLVED, 500L, ResolvedReason.MANUAL)

        dao.reopen("555", 700L)

        val reopened = dao.findByNumber("555")
        assertEquals(CallbackStatus.PENDING, reopened?.status)
        assertEquals(null, reopened?.resolvedAt)
        assertEquals(null, reopened?.resolvedReason)
        assertEquals(700L, reopened?.reopenedAt)
        assertEquals(3, reopened?.attemptCount)
        assertEquals(listOf("555"), dao.observePending().first().map { it.phoneNumber })
        assertEquals(emptyList<String>(), dao.observeHistory().first().map { it.phoneNumber })
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: compilation fails with `Unresolved reference: SyncEventDao`, `syncEventDao`, `MIGRATION_1_2`, `reopenedAt` and similar.

- [ ] **Step 3: Implement the entities, DAOs, converter and migration**

Create `app/src/main/java/com/shopcallback/tracker/data/SyncEventEntities.kt`:

```kotlin
package com.shopcallback.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class SyncEventType { CALL, MANUAL_RESOLVE, UNRESOLVE }

/** An event this phone produced that the sync server hasn't confirmed yet. */
@Entity(tableName = "outbox_events")
data class OutboxEventEntity(
    @PrimaryKey val eventId: String,
    val type: SyncEventType,
    val number: String,
    val timestamp: Long,
    val durationSeconds: Int? = null,
    /** "INCOMING" or "OUTGOING" for CALL events, otherwise null. */
    val direction: String? = null
)

/** An event another phone produced, pulled from the sync server and kept for 7 days. */
@Entity(tableName = "remote_events")
data class RemoteEventEntity(
    @PrimaryKey val eventId: String,
    val type: SyncEventType,
    val number: String,
    val timestamp: Long,
    val durationSeconds: Int? = null,
    val direction: String? = null,
    /** Manual events are applied once; CALL events are re-evaluated every time and never marked. */
    val applied: Boolean = false
)
```

Create `app/src/main/java/com/shopcallback/tracker/data/SyncEventDao.kt`:

```kotlin
package com.shopcallback.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(events: List<OutboxEventEntity>)

    @Query("SELECT * FROM outbox_events ORDER BY timestamp ASC LIMIT :limit")
    suspend fun outboxBatch(limit: Int): List<OutboxEventEntity>

    @Query("DELETE FROM outbox_events WHERE eventId IN (:eventIds)")
    suspend fun deleteOutbox(eventIds: List<String>)

    @Query("DELETE FROM outbox_events WHERE timestamp < :cutoff")
    suspend fun deleteOutboxBefore(cutoff: Long)

    @Query("SELECT COUNT(*) FROM outbox_events")
    fun observeOutboxCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRemote(events: List<RemoteEventEntity>)

    @Query("SELECT * FROM remote_events WHERE type != 'CALL' AND applied = 0 ORDER BY timestamp ASC")
    suspend fun unappliedManualEvents(): List<RemoteEventEntity>

    @Query("UPDATE remote_events SET applied = 1 WHERE eventId IN (:eventIds)")
    suspend fun markApplied(eventIds: List<String>)

    @Query(
        "SELECT MIN(timestamp) FROM remote_events " +
            "WHERE type = 'CALL' AND number = :number AND durationSeconds > 1 AND timestamp > :after"
    )
    suspend fun earliestRemoteCallAfter(number: String, after: Long): Long?

    @Query("DELETE FROM remote_events WHERE timestamp < :cutoff")
    suspend fun deleteRemoteBefore(cutoff: Long)
}
```

Replace the contents of `app/src/main/java/com/shopcallback/tracker/data/CallbackThreadEntity.kt` with:

```kotlin
package com.shopcallback.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CallbackStatus { PENDING, RESOLVED }

/** REMOTE_* mean another shop phone reached the customer (see sync). */
enum class ResolvedReason { AUTO_ANSWERED, MANUAL, REMOTE_ANSWERED, REMOTE_MANUAL }

@Entity(tableName = "callback_threads")
data class CallbackThreadEntity(
    @PrimaryKey val phoneNumber: String,
    val displayName: String?,
    val firstMissedAt: Long,
    val lastMissedAt: Long,
    val attemptCount: Int,
    val status: CallbackStatus,
    val resolvedAt: Long?,
    val resolvedReason: ResolvedReason?,
    /** When this callback was last un-resolved; calls before it no longer count as reaching the customer. */
    val reopenedAt: Long? = null
)
```

In `CallbackThreadDao.kt`, replace the `reopen` query and function with:

```kotlin
    @Query(
        "UPDATE callback_threads SET status = 'PENDING', resolvedAt = NULL, resolvedReason = NULL, " +
            "reopenedAt = :reopenedAt WHERE phoneNumber = :phoneNumber"
    )
    suspend fun reopen(phoneNumber: String, reopenedAt: Long)
```

In `CallbackTypeConverters.kt`, add these two methods inside the class:

```kotlin
    @TypeConverter
    fun syncEventTypeToString(type: SyncEventType): String = type.name

    @TypeConverter
    fun stringToSyncEventType(value: String): SyncEventType = SyncEventType.valueOf(value)
```

Replace the contents of `app/src/main/java/com/shopcallback/tracker/data/CallbackDatabase.kt` with:

```kotlin
package com.shopcallback.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CallbackThreadEntity::class, OutboxEventEntity::class, RemoteEventEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(CallbackTypeConverters::class)
abstract class CallbackDatabase : RoomDatabase() {
    abstract fun callbackThreadDao(): CallbackThreadDao
    abstract fun syncEventDao(): SyncEventDao

    companion object {
        @Volatile private var instance: CallbackDatabase? = null

        /** v2 adds multi-phone sync. Updates must keep existing callbacks, so this is a real migration. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `callback_threads` ADD COLUMN `reopenedAt` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox_events` (`eventId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`number` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `durationSeconds` INTEGER, " +
                        "`direction` TEXT, PRIMARY KEY(`eventId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `remote_events` (`eventId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`number` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `durationSeconds` INTEGER, " +
                        "`direction` TEXT, `applied` INTEGER NOT NULL, PRIMARY KEY(`eventId`))"
                )
            }
        }

        fun getInstance(context: Context): CallbackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallbackDatabase::class.java,
                    "callback_tracker.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
```

In `app/src/main/java/com/shopcallback/tracker/ui/CallbackViewModel.kt`:
- Change the constructor parameter `now: () -> Long = System::currentTimeMillis` to `private val now: () -> Long = System::currentTimeMillis`.
- Replace `unresolve` with:

```kotlin
    fun unresolve(phoneNumber: String) {
        viewModelScope.launch { dao.reopen(phoneNumber, now()) }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing, including the new `CallbackDatabaseMigrationTest` and `SyncEventDaoTest`. If the migration test fails with `Migration didn't properly handle`, the message shows the expected SQL; make `MIGRATION_1_2` match it exactly.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -F - <<'EOF'
Add sync tables and reopenedAt with a v1-to-v2 database migration

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```

---

### Task 3: Resolution rules (outbox events, remote events, reopenedAt)

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/sync/OutboxEvents.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/sync/RemoteEventApplier.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/data/CallbackThreadEntity.kt` (add `callbackStart()`)
- Modify: `app/src/main/java/com/shopcallback/tracker/calllog/CallLogScanner.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/sync/OutboxEventsTest.kt` (new)
- Test: `app/src/test/java/com/shopcallback/tracker/sync/RemoteEventApplierTest.kt` (new)
- Test: `app/src/test/java/com/shopcallback/tracker/calllog/CallLogScannerTest.kt` (add one test)

**Interfaces:**
- Consumes from Task 2:
  - `SyncEventType`, `OutboxEventEntity`, `RemoteEventEntity`
  - `SyncEventDao.unappliedManualEvents`, `markApplied` and `earliestRemoteCallAfter`
  - `CallbackThreadDao.reopen(number, reopenedAt)`
  - `ResolvedReason.REMOTE_*`
- Produces:
  - `fun CallbackThreadEntity.callbackStart(): Long`, in package `com.shopcallback.tracker.data`
  - `CallLogScanner.MIN_VALID_NUMBER_LENGTH`, now a public `const val`
  - `object OutboxEvents`:
    - `forCalls(deviceId: String, entries: List<CallLogEntry>): List<OutboxEventEntity>`
    - `manual(deviceId: String, type: SyncEventType, number: String, timestamp: Long): OutboxEventEntity`
  - `class RemoteEventApplier(threadDao: CallbackThreadDao, syncDao: SyncEventDao)` with `suspend fun apply()`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/shopcallback/tracker/sync/OutboxEventsTest.kt`:

```kotlin
package com.shopcallback.tracker.sync

import com.shopcallback.tracker.calllog.CallDirection
import com.shopcallback.tracker.calllog.CallLogEntry
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.SyncEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxEventsTest {
    @Test
    fun `only answered or outgoing calls over 1 second with a real number become events`() {
        val events = OutboxEvents.forCalls(
            "phone-a",
            listOf(
                CallLogEntry(1L, "+91 98765 43210", 1000L, 30, CallDirection.OUTGOING),
                CallLogEntry(2L, "9876543210", 2000L, 45, CallDirection.INCOMING),
                CallLogEntry(3L, "9876543210", 3000L, 0, CallDirection.MISSED),
                CallLogEntry(4L, "9876543210", 4000L, 1, CallDirection.OUTGOING),
                CallLogEntry(5L, "-1", 5000L, 60, CallDirection.INCOMING)
            )
        )

        assertEquals(
            listOf(
                OutboxEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING"),
                OutboxEventEntity("phone-a:call:2", SyncEventType.CALL, "9876543210", 2000L, 45, "INCOMING")
            ),
            events
        )
    }

    @Test
    fun `manual events get a unique id and no call fields`() {
        val first = OutboxEvents.manual("phone-a", SyncEventType.MANUAL_RESOLVE, "9876543210", 500L)
        val second = OutboxEvents.manual("phone-a", SyncEventType.MANUAL_RESOLVE, "9876543210", 500L)

        assertTrue(first.eventId.startsWith("phone-a:manual:"))
        assertNotEquals(first.eventId, second.eventId)
        assertEquals(SyncEventType.MANUAL_RESOLVE, first.type)
        assertEquals(500L, first.timestamp)
        assertNull(first.durationSeconds)
        assertNull(first.direction)
    }
}
```

Create `app/src/test/java/com/shopcallback/tracker/sync/RemoteEventApplierTest.kt`:

```kotlin
package com.shopcallback.tracker.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.RemoteEventEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventDao
import com.shopcallback.tracker.data.SyncEventType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteEventApplierTest {
    private lateinit var db: CallbackDatabase
    private lateinit var threads: CallbackThreadDao
    private lateinit var events: SyncEventDao
    private lateinit var applier: RemoteEventApplier

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        threads = db.callbackThreadDao()
        events = db.syncEventDao()
        applier = RemoteEventApplier(threads, events)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a call from another phone after the miss resolves the callback`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L))
        events.insertRemote(listOf(call("b:call:1", 2000L)))

        applier.apply()

        val thread = threads.findByNumber(NUMBER)
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.REMOTE_ANSWERED, thread?.resolvedReason)
        assertEquals(2000L, thread?.resolvedAt)
    }

    @Test
    fun `calls before the miss or of 1 second or less do not resolve it`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L))
        events.insertRemote(listOf(call("b:call:1", 500L), call("b:call:2", 2000L, durationSeconds = 1)))

        applier.apply()

        assertEquals(CallbackStatus.PENDING, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `a remote call pulled before the local miss was scanned still resolves it`() = runBlocking {
        events.insertRemote(listOf(call("b:call:1", 2000L)))
        applier.apply()

        threads.upsert(pending(firstMissedAt = 1000L))
        applier.apply()

        assertEquals(ResolvedReason.REMOTE_ANSWERED, threads.findByNumber(NUMBER)?.resolvedReason)
    }

    @Test
    fun `calls before an un-resolve do not resolve it again`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L).copy(reopenedAt = 3000L))
        events.insertRemote(listOf(call("b:call:1", 2000L)))

        applier.apply()
        assertEquals(CallbackStatus.PENDING, threads.findByNumber(NUMBER)?.status)

        events.insertRemote(listOf(call("b:call:2", 4000L)))
        applier.apply()
        assertEquals(CallbackStatus.RESOLVED, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `mark resolved on another phone resolves a callback that started before it`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 1000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.MANUAL_RESOLVE, 2000L)))

        applier.apply()

        val thread = threads.findByNumber(NUMBER)
        assertEquals(ResolvedReason.REMOTE_MANUAL, thread?.resolvedReason)
        assertEquals(2000L, thread?.resolvedAt)
    }

    @Test
    fun `mark resolved on another phone leaves a newer callback pending`() = runBlocking {
        threads.upsert(pending(firstMissedAt = 3000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.MANUAL_RESOLVE, 2000L)))

        applier.apply()

        assertEquals(CallbackStatus.PENDING, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `un-resolve on another phone reopens a callback resolved before it`() = runBlocking {
        threads.upsert(resolved(resolvedAt = 2000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.UNRESOLVE, 3000L)))

        applier.apply()

        val thread = threads.findByNumber(NUMBER)
        assertEquals(CallbackStatus.PENDING, thread?.status)
        assertEquals(3000L, thread?.reopenedAt)
    }

    @Test
    fun `un-resolve on another phone leaves a callback resolved after it`() = runBlocking {
        threads.upsert(resolved(resolvedAt = 4000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.UNRESOLVE, 3000L)))

        applier.apply()

        assertEquals(CallbackStatus.RESOLVED, threads.findByNumber(NUMBER)?.status)
    }

    @Test
    fun `manual events are applied only once`() = runBlocking {
        threads.upsert(resolved(resolvedAt = 2000L))
        events.insertRemote(listOf(manual("b:manual:1", SyncEventType.UNRESOLVE, 3000L)))
        applier.apply()

        // Resolved again locally, earlier than the remote un-resolve's time; re-applying would reopen it.
        threads.markResolved(NUMBER, CallbackStatus.RESOLVED, 2500L, ResolvedReason.MANUAL)
        applier.apply()

        assertEquals(CallbackStatus.RESOLVED, threads.findByNumber(NUMBER)?.status)
    }

    private fun pending(firstMissedAt: Long) = CallbackThreadEntity(
        phoneNumber = NUMBER, displayName = null, firstMissedAt = firstMissedAt, lastMissedAt = firstMissedAt,
        attemptCount = 1, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null
    )

    private fun resolved(resolvedAt: Long) =
        pending(firstMissedAt = 1000L).copy(status = CallbackStatus.RESOLVED, resolvedAt = resolvedAt, resolvedReason = ResolvedReason.MANUAL)

    private fun call(id: String, timestamp: Long, durationSeconds: Int = 30) =
        RemoteEventEntity(id, SyncEventType.CALL, NUMBER, timestamp, durationSeconds, "OUTGOING")

    private fun manual(id: String, type: SyncEventType, timestamp: Long) =
        RemoteEventEntity(id, type, NUMBER, timestamp)

    companion object {
        private const val NUMBER = "9876543210"
    }
}
```

In `app/src/test/java/com/shopcallback/tracker/calllog/CallLogScannerTest.kt`, add this test before the `companion object`:

```kotlin
    @Test
    fun `a call from before an un-resolve does not resolve the reopened callback`() = runBlocking {
        dao.upsert(
            com.shopcallback.tracker.data.CallbackThreadEntity(
                phoneNumber = "9876543210", displayName = null, firstMissedAt = 1000L, lastMissedAt = 1000L,
                attemptCount = 1, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null,
                reopenedAt = 3000L
            )
        )

        scanner.applyNewEntries(listOf(CallLogEntry(60L, "9876543210", 2000L, 30, CallDirection.OUTGOING)))
        assertEquals(CallbackStatus.PENDING, dao.findByNumber("9876543210")?.status)

        scanner.applyNewEntries(listOf(CallLogEntry(61L, "9876543210", 4000L, 30, CallDirection.OUTGOING)))
        assertEquals(CallbackStatus.RESOLVED, dao.findByNumber("9876543210")?.status)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: compilation fails with `Unresolved reference: OutboxEvents` and `RemoteEventApplier`.

- [ ] **Step 3: Implement**

At the end of `app/src/main/java/com/shopcallback/tracker/data/CallbackThreadEntity.kt`, add:

```kotlin
/** When the current callback began: its first missed call, or its un-resolve if that was later. */
fun CallbackThreadEntity.callbackStart(): Long = maxOf(firstMissedAt, reopenedAt ?: 0L)
```

In `app/src/main/java/com/shopcallback/tracker/calllog/CallLogScanner.kt`:
- Add the import `import com.shopcallback.tracker.data.callbackStart`.
- In `handleAnsweredCandidate`, change the condition to:

```kotlin
        if (existing.status == CallbackStatus.PENDING && entry.timestamp > existing.callbackStart()) {
```

- In the `companion object`, change `private const val MIN_VALID_NUMBER_LENGTH = 5` to `const val MIN_VALID_NUMBER_LENGTH = 5`.

Create `app/src/main/java/com/shopcallback/tracker/sync/OutboxEvents.kt`:

```kotlin
package com.shopcallback.tracker.sync

import com.shopcallback.tracker.calllog.CallDirection
import com.shopcallback.tracker.calllog.CallLogEntry
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.SyncEventType
import com.shopcallback.tracker.util.PhoneNumberNormalizer
import java.util.UUID

/** Builds the events this phone shares with the others. Missed calls are never shared. */
object OutboxEvents {
    fun forCalls(deviceId: String, entries: List<CallLogEntry>): List<OutboxEventEntity> =
        entries.mapNotNull { entry ->
            if (entry.direction == CallDirection.MISSED || entry.durationSeconds <= 1) return@mapNotNull null
            val number = PhoneNumberNormalizer.normalize(entry.rawNumber)
            if (number.length < CallLogScanner.MIN_VALID_NUMBER_LENGTH) return@mapNotNull null
            OutboxEventEntity(
                eventId = "$deviceId:call:${entry.id}",
                type = SyncEventType.CALL,
                number = number,
                timestamp = entry.timestamp,
                durationSeconds = entry.durationSeconds,
                direction = entry.direction.name
            )
        }

    fun manual(deviceId: String, type: SyncEventType, number: String, timestamp: Long): OutboxEventEntity =
        OutboxEventEntity(
            eventId = "$deviceId:manual:${UUID.randomUUID()}",
            type = type,
            number = number,
            timestamp = timestamp
        )
}
```

Create `app/src/main/java/com/shopcallback/tracker/sync/RemoteEventApplier.kt`:

```kotlin
package com.shopcallback.tracker.sync

import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventDao
import com.shopcallback.tracker.data.SyncEventType
import com.shopcallback.tracker.data.callbackStart
import kotlinx.coroutines.flow.first

/** Applies other phones' events to this phone's callbacks. Safe to run any number of times. */
class RemoteEventApplier(
    private val threadDao: CallbackThreadDao,
    private val syncDao: SyncEventDao
) {
    suspend fun apply() {
        applyManualEvents()
        resolveByRemoteCalls()
    }

    /** Mark resolved / Un-resolve from other phones: applied once each, oldest first. */
    private suspend fun applyManualEvents() {
        val events = syncDao.unappliedManualEvents()
        events.forEach { event ->
            val thread = threadDao.findByNumber(event.number) ?: return@forEach
            when (event.type) {
                SyncEventType.MANUAL_RESOLVE ->
                    if (thread.status == CallbackStatus.PENDING && thread.callbackStart() < event.timestamp) {
                        threadDao.markResolved(event.number, CallbackStatus.RESOLVED, event.timestamp, ResolvedReason.REMOTE_MANUAL)
                    }
                SyncEventType.UNRESOLVE ->
                    if (thread.status == CallbackStatus.RESOLVED && (thread.resolvedAt ?: 0L) <= event.timestamp) {
                        threadDao.reopen(event.number, event.timestamp)
                    }
                SyncEventType.CALL -> Unit
            }
        }
        if (events.isNotEmpty()) syncDao.markApplied(events.map { it.eventId })
    }

    /**
     * Calls from other phones are re-checked every time rather than applied once, so the result
     * doesn't depend on whether the remote call or the local missed call was seen first.
     */
    private suspend fun resolveByRemoteCalls() {
        threadDao.observePending().first().forEach { thread ->
            val callAt = syncDao.earliestRemoteCallAfter(thread.phoneNumber, thread.callbackStart()) ?: return@forEach
            threadDao.markResolved(thread.phoneNumber, CallbackStatus.RESOLVED, callAt, ResolvedReason.REMOTE_ANSWERED)
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -F - <<'EOF'
Add cross-phone resolution rules and reopenedAt handling

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```

---

### Task 4: Sync settings and HTTP client

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/sync/SyncSettings.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/sync/SyncClient.kt`
- Create (test helper): `app/src/test/java/com/shopcallback/tracker/sync/FakeSyncServer.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/sync/SyncSettingsTest.kt` (new)
- Test: `app/src/test/java/com/shopcallback/tracker/sync/SyncClientTest.kt` (new)

**Interfaces:**
- Consumes from Task 2: `OutboxEventEntity`, `RemoteEventEntity`, `SyncEventType`.
- Consumes from Task 1: the HTTP API, which `FakeSyncServer` mimics.
- Produces:
  - `class SyncSettings(context: Context)`:
    - `val deviceId: String`
    - `var serverUrl: String`: `""` means off
    - `var serverId: String?`
    - `var cursor: Long`
    - `var lastSyncAt: Long?`
    - `const val PREFS_NAME = "sync_settings"`
  - `class SyncClient(baseUrl: String)`:
    - `fun health(): String`, returning the server ID
    - `fun upload(deviceId: String, events: List<OutboxEventEntity>): UploadResult`
    - `fun pull(deviceId: String, after: Long): Pull`
    - All three throw `IOException` when unreachable and on non-2xx statuses other than 400. They throw `org.json.JSONException` on a malformed response.
  - Result types:
    - `sealed interface SyncClient.UploadResult { data class Accepted(val serverId: String); data object Rejected }`
    - `data class SyncClient.Pull(val serverId: String, val latestSeq: Long, val events: List<PulledEvent>)`
    - `data class SyncClient.PulledEvent(val seq: Long, val event: RemoteEventEntity)`
  - Test helper `FakeSyncServer`, used by Tasks 5 and 7:
    - `url: String`, `var serverId: String`, `var rejectUploads: Boolean`
    - `events: MutableList<JSONObject>`; synchronize on it when reading
    - `fun addFromOtherPhone(eventId, type, number, timestamp, durationSeconds: Int? = null, direction: String? = null)`
    - `close()`

- [ ] **Step 1: Write the fake server and the failing tests**

Create `app/src/test/java/com/shopcallback/tracker/sync/FakeSyncServer.kt`:

```kotlin
package com.shopcallback.tracker.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.URLDecoder

/** In-process stand-in for server/callback_sync_server.py speaking the same JSON protocol. */
class FakeSyncServer : AutoCloseable {
    @Volatile var serverId = "server-1"
    @Volatile var rejectUploads = false

    /** Stored events, each with "seq" and "deviceId" added. Synchronize on this list to read it. */
    val events = mutableListOf<JSONObject>()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/health") { exchange -> respond(exchange, 200, JSONObject().put("serverId", serverId)) }
        server.createContext("/events") { exchange ->
            if (exchange.requestMethod == "POST") handleUpload(exchange) else handlePull(exchange)
        }
        server.start()
    }

    fun addFromOtherPhone(
        eventId: String,
        type: String,
        number: String,
        timestamp: Long,
        durationSeconds: Int? = null,
        direction: String? = null
    ) {
        store(
            "other-phone",
            JSONObject()
                .put("eventId", eventId)
                .put("type", type)
                .put("number", number)
                .put("timestamp", timestamp)
                .put("durationSeconds", durationSeconds ?: JSONObject.NULL)
                .put("direction", direction ?: JSONObject.NULL)
        )
    }

    private fun handleUpload(exchange: HttpExchange) {
        if (rejectUploads) return respond(exchange, 400, JSONObject().put("error", "rejected"))
        val body = JSONObject(exchange.requestBody.bufferedReader().readText())
        val deviceId = body.getString("deviceId")
        val incoming = body.getJSONArray("events")
        val accepted = (0 until incoming.length()).count { store(deviceId, incoming.getJSONObject(it)) }
        respond(exchange, 200, JSONObject().put("serverId", serverId).put("accepted", accepted))
    }

    private fun handlePull(exchange: HttpExchange) {
        val params = exchange.requestURI.rawQuery.split("&").associate {
            it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8")
        }
        val after = params.getValue("after").toLong()
        val device = params.getValue("device")
        val (latest, page) = synchronized(events) {
            events.size.toLong() to events
                .filter { it.getLong("seq") > after && it.getString("deviceId") != device }
                .take(PAGE_SIZE)
        }
        respond(exchange, 200, JSONObject().put("serverId", serverId).put("latestSeq", latest).put("events", JSONArray(page)))
    }

    /** Returns false if an event with the same eventId is already stored. */
    private fun store(deviceId: String, event: JSONObject): Boolean = synchronized(events) {
        if (events.any { it.getString("eventId") == event.getString("eventId") }) return false
        events.add(JSONObject(event.toString()).put("deviceId", deviceId).put("seq", events.size + 1L))
        true
    }

    private fun respond(exchange: HttpExchange, status: Int, json: JSONObject) {
        val bytes = json.toString().toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        const val PAGE_SIZE = 500
    }
}
```

Create `app/src/test/java/com/shopcallback/tracker/sync/SyncSettingsTest.kt`:

```kotlin
package com.shopcallback.tracker.sync

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSettingsTest {
    private fun settings() = SyncSettings(ApplicationProvider.getApplicationContext())

    @Test
    fun `defaults mean sync is off and nothing has happened yet`() {
        val settings = settings()
        assertEquals("", settings.serverUrl)
        assertNull(settings.serverId)
        assertEquals(0L, settings.cursor)
        assertNull(settings.lastSyncAt)
    }

    @Test
    fun `device id is created once and kept`() {
        val id = settings().deviceId
        assertTrue(id.isNotBlank())
        assertEquals(id, settings().deviceId)
    }

    @Test
    fun `values persist and can be cleared`() {
        settings().apply {
            serverUrl = "http://shop-pc:8787"
            serverId = "server-1"
            cursor = 42L
            lastSyncAt = 1234L
        }
        settings().apply {
            assertEquals("http://shop-pc:8787", serverUrl)
            assertEquals("server-1", serverId)
            assertEquals(42L, cursor)
            assertEquals(1234L, lastSyncAt)
            serverId = null
            lastSyncAt = null
        }
        assertNull(settings().serverId)
        assertNull(settings().lastSyncAt)
    }
}
```

Create `app/src/test/java/com/shopcallback/tracker/sync/SyncClientTest.kt`:

```kotlin
package com.shopcallback.tracker.sync

import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.RemoteEventEntity
import com.shopcallback.tracker.data.SyncEventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class SyncClientTest {
    private val server = FakeSyncServer()
    private val client = SyncClient(server.url + "/")

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `health returns the server id`() {
        assertEquals("server-1", client.health())
    }

    @Test
    fun `uploaded events reach other phones with every field intact`() {
        val call = OutboxEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING")
        val manual = OutboxEventEntity("phone-a:manual:x", SyncEventType.UNRESOLVE, "9876543210", 2000L)

        assertEquals(SyncClient.UploadResult.Accepted("server-1"), client.upload("phone-a", listOf(call, manual)))

        val pull = client.pull("phone-b", after = 0L)
        assertEquals("server-1", pull.serverId)
        assertEquals(2L, pull.latestSeq)
        assertEquals(
            listOf(
                SyncClient.PulledEvent(1L, RemoteEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING")),
                SyncClient.PulledEvent(2L, RemoteEventEntity("phone-a:manual:x", SyncEventType.UNRESOLVE, "9876543210", 2000L))
            ),
            pull.events
        )
    }

    @Test
    fun `a phone does not pull its own events`() {
        client.upload("phone-a", listOf(OutboxEventEntity("phone-a:call:1", SyncEventType.CALL, "9876543210", 1000L, 30, "OUTGOING")))
        assertEquals(emptyList<SyncClient.PulledEvent>(), client.pull("phone-a", after = 0L).events)
    }

    @Test
    fun `a rejected upload is reported, not thrown`() {
        server.rejectUploads = true
        assertEquals(SyncClient.UploadResult.Rejected, client.upload("phone-a", emptyList()))
    }

    @Test(expected = IOException::class)
    fun `an unreachable server throws IOException`() {
        val url = FakeSyncServer().run { close(); url }
        SyncClient(url).health()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --tests '*Sync*Test*' --console=plain`
Expected: compilation fails with `Unresolved reference: SyncSettings` and `SyncClient`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/shopcallback/tracker/sync/SyncSettings.kt`:

```kotlin
package com.shopcallback.tracker.sync

import android.content.Context
import java.util.UUID

/** Sync configuration and progress, persisted in SharedPreferences. */
class SyncSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** This install's random ID, created on first use. Keeps event IDs unique across phones. */
    val deviceId: String
        get() = synchronized(LOCK) {
            prefs.getString(KEY_DEVICE_ID, null)
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).commit() }
        }

    /** e.g. "http://shop-pc:8787". Empty means sync is off. */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SERVER_URL, value).commit() }

    /** The server's database ID last seen; a different one means the server was reset. */
    var serverId: String?
        get() = prefs.getString(KEY_SERVER_ID, null)
        set(value) { prefs.edit().putString(KEY_SERVER_ID, value).commit() }

    /** Highest server `seq` already pulled. */
    var cursor: Long
        get() = prefs.getLong(KEY_CURSOR, 0L)
        set(value) { prefs.edit().putLong(KEY_CURSOR, value).commit() }

    var lastSyncAt: Long?
        get() = prefs.getLong(KEY_LAST_SYNC_AT, NEVER).takeIf { it != NEVER }
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC_AT, value ?: NEVER).commit() }

    companion object {
        const val PREFS_NAME = "sync_settings"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_ID = "server_id"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_LAST_SYNC_AT = "last_sync_at"
        private const val NEVER = -1L
        private val LOCK = Any()
    }
}
```

Create `app/src/main/java/com/shopcallback/tracker/sync/SyncClient.kt`:

```kotlin
package com.shopcallback.tracker.sync

import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.RemoteEventEntity
import com.shopcallback.tracker.data.SyncEventType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to server/callback_sync_server.py. Blocking: call from a background dispatcher.
 * Throws IOException when the server is unreachable or answers with an unexpected status.
 */
class SyncClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    sealed interface UploadResult {
        data class Accepted(val serverId: String) : UploadResult
        /** The server said the batch is malformed (HTTP 400); retrying won't help. */
        data object Rejected : UploadResult
    }

    data class PulledEvent(val seq: Long, val event: RemoteEventEntity)
    data class Pull(val serverId: String, val latestSeq: Long, val events: List<PulledEvent>)

    fun health(): String = JSONObject(request("GET", "/health").body).getString("serverId")

    fun upload(deviceId: String, events: List<OutboxEventEntity>): UploadResult {
        val body = JSONObject()
            .put("deviceId", deviceId)
            .put("events", JSONArray(events.map { it.toJson() }))
        val response = request("POST", "/events", body.toString())
        if (response.code == HttpURLConnection.HTTP_BAD_REQUEST) return UploadResult.Rejected
        return UploadResult.Accepted(JSONObject(response.body).getString("serverId"))
    }

    fun pull(deviceId: String, after: Long): Pull {
        val json = JSONObject(request("GET", "/events?after=$after&device=${URLEncoder.encode(deviceId, "UTF-8")}").body)
        val events = json.getJSONArray("events")
        return Pull(
            serverId = json.getString("serverId"),
            latestSeq = json.getLong("latestSeq"),
            events = (0 until events.length()).map { events.getJSONObject(it).toPulledEvent() }
        )
    }

    private class Response(val code: Int, val body: String)

    private fun request(method: String, path: String, body: String? = null): Response {
        val connection = URL(base + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299 && code != HttpURLConnection.HTTP_BAD_REQUEST) {
                throw IOException("HTTP $code from $method $path")
            }
            return Response(code, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun OutboxEventEntity.toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("type", type.name)
        .put("number", number)
        .put("timestamp", timestamp)
        .apply {
            durationSeconds?.let { put("durationSeconds", it) }
            direction?.let { put("direction", it) }
        }

    private fun JSONObject.toPulledEvent() = PulledEvent(
        seq = getLong("seq"),
        event = RemoteEventEntity(
            eventId = getString("eventId"),
            type = SyncEventType.valueOf(getString("type")),
            number = getString("number"),
            timestamp = getLong("timestamp"),
            durationSeconds = if (isNull("durationSeconds")) null else getInt("durationSeconds"),
            direction = if (isNull("direction")) null else getString("direction")
        )
    )

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -F - <<'EOF'
Add sync settings and HTTP client for the sync server

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```

---

### Task 5: Sync engine

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/sync/SyncEngine.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/sync/SyncEngineTest.kt` (new)

**Interfaces:**
- Consumes:
  - `SyncSettings`, `SyncClient` and `FakeSyncServer` (Task 4)
  - `RemoteEventApplier` (Task 3)
  - `SyncEventDao` (Task 2)
  - `CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS` (existing)
- Produces:
  - Constructor: `class SyncEngine(settings: SyncSettings, syncDao: SyncEventDao, applier: RemoteEventApplier, recentCallEvents: suspend () -> List<OutboxEventEntity>, clientFor: (String) -> SyncClient = ::SyncClient, now: () -> Long = System::currentTimeMillis)`
  - `suspend fun syncOnce(): Boolean`: true if the server was reached. Never throws, except for cancellation. Run it on a background dispatcher.
  - `suspend fun onServerUrlChanged(url: String)`
  - `companion object { const val PULL_PAGE_SIZE = 500; const val UPLOAD_BATCH_SIZE = 200 }`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/shopcallback/tracker/sync/SyncEngineTest.kt`:

```kotlin
package com.shopcallback.tracker.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.data.SyncEventType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: CallbackDatabase
    private lateinit var server: FakeSyncServer
    private lateinit var settings: SyncSettings
    private var recentCalls = emptyList<OutboxEventEntity>()
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, CallbackDatabase::class.java).allowMainThreadQueries().build()
        server = FakeSyncServer()
        settings = SyncSettings(context)
        engine = SyncEngine(
            settings = settings,
            syncDao = db.syncEventDao(),
            applier = RemoteEventApplier(db.callbackThreadDao(), db.syncEventDao()),
            recentCallEvents = { recentCalls },
            now = { NOW }
        )
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    @Test
    fun `with sync off nothing is sent and the outbox is kept`() = runBlocking {
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))

        assertFalse(engine.syncOnce())

        assertEquals(1, db.syncEventDao().outboxBatch(10).size)
        assertTrue(server.events.isEmpty())
    }

    @Test
    fun `turning sync on shares the past week's calls`() = runBlocking {
        recentCalls = listOf(outboxCall("mine:call:7"))

        engine.onServerUrlChanged("  ${server.url}  ")

        assertEquals(server.url, settings.serverUrl)
        assertEquals(listOf("mine:call:7"), db.syncEventDao().outboxBatch(10).map { it.eventId })
    }

    @Test
    fun `a pass uploads the outbox, clears it and records the time`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))

        assertTrue(engine.syncOnce())

        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
        synchronized(server.events) {
            assertEquals(listOf("mine:call:1"), server.events.map { it.getString("eventId") })
            assertEquals(settings.deviceId, server.events.single().getString("deviceId"))
        }
        assertEquals(NOW, settings.lastSyncAt)
        assertEquals("server-1", settings.serverId)
    }

    @Test
    fun `a call made on another phone resolves the callback here`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        db.callbackThreadDao().upsert(pending(firstMissedAt = NOW - 60_000))
        server.addFromOtherPhone("other:call:1", "CALL", NUMBER, NOW - 30_000, 40, "OUTGOING")

        assertTrue(engine.syncOnce())

        val thread = db.callbackThreadDao().findByNumber(NUMBER)
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.REMOTE_ANSWERED, thread?.resolvedReason)
        assertEquals(1L, settings.cursor)
    }

    @Test
    fun `pulls every page`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        repeat(SyncEngine.PULL_PAGE_SIZE + 1) {
            server.addFromOtherPhone("other:call:$it", "CALL", "9000000${1000 + it}", NOW - 1_000, 40, "OUTGOING")
        }

        assertTrue(engine.syncOnce())

        assertEquals(SyncEngine.PULL_PAGE_SIZE + 1L, settings.cursor)
    }

    @Test
    fun `a rejected batch is dropped so it cannot block the queue`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))
        server.rejectUploads = true

        assertTrue(engine.syncOnce())

        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
    }

    @Test
    fun `an unreachable server keeps the outbox for next time`() = runBlocking {
        val deadUrl = FakeSyncServer().run { close(); url }
        engine.onServerUrlChanged(deadUrl)
        db.syncEventDao().enqueue(listOf(outboxCall("mine:call:1")))

        assertFalse(engine.syncOnce())

        assertEquals(1, db.syncEventDao().outboxBatch(10).size)
        assertNull(settings.lastSyncAt)
    }

    @Test
    fun `a reset server gets the past week's calls again and is pulled from the start`() = runBlocking {
        engine.onServerUrlChanged(server.url)
        server.addFromOtherPhone("other:call:1", "CALL", NUMBER, NOW - 30_000, 40, "OUTGOING")
        assertTrue(engine.syncOnce())
        assertEquals(1L, settings.cursor)

        // The server's database is wiped: new ID, no events.
        synchronized(server.events) { server.events.clear() }
        server.serverId = "server-2"
        recentCalls = listOf(outboxCall("mine:call:9"))

        assertTrue(engine.syncOnce())

        assertEquals("server-2", settings.serverId)
        synchronized(server.events) {
            assertEquals(listOf("mine:call:9"), server.events.map { it.getString("eventId") })
        }
        assertEquals(0L, settings.cursor)
        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
    }

    @Test
    fun `old outbox events are purged even with sync off`() = runBlocking {
        db.syncEventDao().enqueue(listOf(outboxCall("old", timestamp = NOW - 8 * DAY)))

        engine.syncOnce()

        assertTrue(db.syncEventDao().outboxBatch(10).isEmpty())
    }

    private fun outboxCall(id: String, timestamp: Long = NOW - 1_000) =
        OutboxEventEntity(id, SyncEventType.CALL, NUMBER, timestamp, 40, "OUTGOING")

    private fun pending(firstMissedAt: Long) = CallbackThreadEntity(
        phoneNumber = NUMBER, displayName = null, firstMissedAt = firstMissedAt, lastMissedAt = firstMissedAt,
        attemptCount = 1, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null
    )

    companion object {
        private const val NUMBER = "9876543210"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val NOW = 100 * DAY
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --tests '*SyncEngineTest*' --console=plain`
Expected: compilation fails with `Unresolved reference: SyncEngine`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/shopcallback/tracker/sync/SyncEngine.kt`:

```kotlin
package com.shopcallback.tracker.sync

import android.util.Log
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.data.SyncEventDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exchanges events with the sync server: upload this phone's outbox, pull the other phones'
 * events, then apply them. Blocking network I/O: run on a background dispatcher.
 */
class SyncEngine(
    private val settings: SyncSettings,
    private val syncDao: SyncEventDao,
    private val applier: RemoteEventApplier,
    /** This phone's answered/outgoing calls from the past week, re-shared when the server changes. */
    private val recentCallEvents: suspend () -> List<OutboxEventEntity>,
    private val clientFor: (String) -> SyncClient = ::SyncClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    /** Runs one pass. Returns true if the server was reached. Never throws (except cancellation). */
    suspend fun syncOnce(): Boolean = passLock.withLock {
        val cutoff = now() - CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS
        syncDao.deleteOutboxBefore(cutoff)
        syncDao.deleteRemoteBefore(cutoff)

        val url = settings.serverUrl
        if (url.isBlank()) return@withLock false

        val client = clientFor(url)
        val reached = try {
            upload(client)
            pull(client)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync pass failed; will retry", e)
            false
        }
        applier.apply()
        if (reached) settings.lastSyncAt = now()
        reached
    }

    /** The user saved a server address: forget the old server and re-share the past week's calls. */
    suspend fun onServerUrlChanged(url: String) = passLock.withLock {
        settings.serverUrl = url.trim()
        settings.serverId = null
        settings.cursor = 0L
        settings.lastSyncAt = null
        if (settings.serverUrl.isNotBlank()) syncDao.enqueue(safeRecentCallEvents())
    }

    private suspend fun upload(client: SyncClient) {
        while (true) {
            val batch = syncDao.outboxBatch(UPLOAD_BATCH_SIZE)
            if (batch.isEmpty()) return
            when (val result = client.upload(settings.deviceId, batch)) {
                is SyncClient.UploadResult.Accepted -> noteServer(result.serverId)
                SyncClient.UploadResult.Rejected -> Log.w(TAG, "server rejected ${batch.size} events; dropping them")
            }
            syncDao.deleteOutbox(batch.map { it.eventId })
        }
    }

    private suspend fun pull(client: SyncClient) {
        while (true) {
            val page = client.pull(settings.deviceId, settings.cursor)
            if (noteServer(page.serverId)) {
                // Server was reset: send it this phone's recent calls, then pull again from the start.
                upload(client)
                continue
            }
            syncDao.insertRemote(page.events.map { it.event })
            page.events.maxOfOrNull { it.seq }?.let { settings.cursor = it }
            if (page.events.size < PULL_PAGE_SIZE) return
        }
    }

    /** Records the server's ID. If it changed (server database reset), starts over and returns true. */
    private suspend fun noteServer(serverId: String): Boolean {
        val known = settings.serverId
        settings.serverId = serverId
        if (known == null || known == serverId) return false
        settings.cursor = 0L
        syncDao.enqueue(safeRecentCallEvents())
        return true
    }

    private suspend fun safeRecentCallEvents(): List<OutboxEventEntity> =
        try {
            recentCallEvents()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not read recent calls to re-share", e)
            emptyList()
        }

    companion object {
        private const val TAG = "SyncEngine"
        /** Must match PAGE_SIZE in server/callback_sync_server.py. */
        const val PULL_PAGE_SIZE = 500
        const val UPLOAD_BATCH_SIZE = 200
        /** Shared by every instance so the service's and any other caller's passes never overlap. */
        private val passLock = Mutex()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -F - <<'EOF'
Add sync engine: upload outbox, pull other phones' events, apply them

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```

---

### Task 6: Wire sync into the service, view model, manifest and History

**Files:**
- Modify: `app/src/main/java/com/shopcallback/tracker/service/CallWatcherService.kt` (full replacement below)
- Modify: `app/src/main/java/com/shopcallback/tracker/ui/CallbackViewModel.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/ui/HistoryScreen.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/shopcallback/tracker/service/CallWatcherServiceTest.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/ui/CallbackViewModelTest.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/ui/PendingCallbacksScreenTest.kt` (pass the test `syncDao`)
- Test: `app/src/test/java/com/shopcallback/tracker/ui/ResolvedReasonTextTest.kt` (new)

**Interfaces:**
- Consumes:
  - `SyncEngine`, `SyncSettings`, `RemoteEventApplier` and `OutboxEvents` (Tasks 3 to 5)
  - `CallbackDatabase.syncEventDao()` (Task 2)
- Produces:
  - `CallWatcherService.ACTION_SERVER_CHANGED` (`"com.shopcallback.tracker.action.SERVER_CHANGED"`) and `CallWatcherService.EXTRA_SERVER_URL` (`"server_url"`), used by Task 7
  - `CallWatcherService.testDisablePolling: Boolean`, a test seam
  - `CallbackViewModel(application, dao, now, syncDao: SyncEventDao = …, syncSettings: SyncSettings = …)`
  - `internal fun resolvedReasonText(reason: ResolvedReason?): String`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/shopcallback/tracker/ui/ResolvedReasonTextTest.kt`:

```kotlin
package com.shopcallback.tracker.ui

import com.shopcallback.tracker.data.ResolvedReason
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvedReasonTextTest {
    @Test
    fun `each reason has its History label`() {
        assertEquals("answered", resolvedReasonText(ResolvedReason.AUTO_ANSWERED))
        assertEquals("marked resolved", resolvedReasonText(ResolvedReason.MANUAL))
        assertEquals("answered on another phone", resolvedReasonText(ResolvedReason.REMOTE_ANSWERED))
        assertEquals("marked resolved on another phone", resolvedReasonText(ResolvedReason.REMOTE_MANUAL))
    }
}
```

In `CallbackViewModelTest.kt`:
- Change the `viewModel = CallbackViewModel(application, dao)` line in `setUp` to `viewModel = CallbackViewModel(application, dao, syncDao = db.syncEventDao())`.
- Change the construction in `` `opening the app drops pending callbacks last missed over 7 days ago` `` to `CallbackViewModel(ApplicationProvider.getApplicationContext(), dao, now = { now }, syncDao = db.syncEventDao())`.
- Add the import `com.shopcallback.tracker.data.SyncEventType`.
- Add these tests before `private fun thread(`:

```kotlin
    @Test
    fun `manual actions are queued for the other phones`() {
        runBlocking { dao.upsert(thread("777", CallbackStatus.PENDING)) }

        viewModel.markResolvedManually("777")
        viewModel.unresolve("777")

        val queued = runBlocking { db.syncEventDao().outboxBatch(10) }
        assertEquals(
            listOf(SyncEventType.MANUAL_RESOLVE, SyncEventType.UNRESOLVE).toSet(),
            queued.map { it.type }.toSet()
        )
        assertEquals(setOf("777"), queued.map { it.number }.toSet())
    }

    @Test
    fun `unresolve records when it happened`() {
        runBlocking { dao.upsert(thread("888", CallbackStatus.RESOLVED)) }
        val fixedNow = CallbackViewModel(
            ApplicationProvider.getApplicationContext(), dao, now = { 5_000L }, syncDao = db.syncEventDao()
        )

        fixedNow.unresolve("888")

        assertEquals(5_000L, runBlocking { dao.findByNumber("888") }?.reopenedAt)
    }
```

In `PendingCallbacksScreenTest.kt`, change `viewModel = CallbackViewModel(ApplicationProvider.getApplicationContext(), dao)` to `viewModel = CallbackViewModel(ApplicationProvider.getApplicationContext(), dao, syncDao = db.syncEventDao())`.

In `CallWatcherServiceTest.kt`:
- Add these imports:
  - `com.shopcallback.tracker.data.CallbackStatus`
  - `com.shopcallback.tracker.data.RemoteEventEntity`
  - `com.shopcallback.tracker.data.ResolvedReason`
  - `com.shopcallback.tracker.data.SyncEventType`
  - `kotlinx.coroutines.runBlocking`
- In `grantCallLogPermission()` (the `@Before`), add `CallWatcherService.testDisablePolling = true` as the first line.
- In `tearDown()`, add `CallWatcherService.testDisablePolling = false`.
- In `` `onStartCommand returns START_STICKY` ``, add `CallWatcherService.testDispatcher = StandardTestDispatcher()` before `buildService`. The launched work then never runs after the database is closed.
- Add these tests:

```kotlin
    @Test
    fun `scan queues answered calls for the other phones`() = runTest {
        testDb = freshTestDatabase()
        CallWatcherService.testDatabase = testDb
        CallWatcherService.testDispatcher = StandardTestDispatcher(testScheduler)
        CallWatcherService.testCallLogSource = fakeSource(
            listOf(
                CallLogEntry(1L, "9876543210", System.currentTimeMillis() - 60_000, 0, CallDirection.MISSED),
                CallLogEntry(2L, "9123456789", System.currentTimeMillis(), 30, CallDirection.OUTGOING)
            )
        )

        Robolectric.buildService(CallWatcherService::class.java).create().get()
        advanceUntilIdle()

        val queued = testDb.syncEventDao().outboxBatch(10)
        assertEquals(1, queued.size)
        assertEquals("9123456789", queued.single().number)
        assertEquals(true, queued.single().eventId.endsWith(":call:2"))
    }

    @Test
    fun `scan resolves a callback already answered on another phone`() = runTest {
        testDb = freshTestDatabase()
        val now = System.currentTimeMillis()
        runBlocking {
            testDb.syncEventDao().insertRemote(
                listOf(RemoteEventEntity("other:call:1", SyncEventType.CALL, "9876543210", now - 1_000, 40, "OUTGOING"))
            )
        }
        CallWatcherService.testDatabase = testDb
        CallWatcherService.testDispatcher = StandardTestDispatcher(testScheduler)
        CallWatcherService.testCallLogSource =
            fakeSource(listOf(CallLogEntry(1L, "9876543210", now - 60_000, 0, CallDirection.MISSED)))

        Robolectric.buildService(CallWatcherService::class.java).create().get()
        advanceUntilIdle()

        val thread = testDb.callbackThreadDao().findByNumber("9876543210")
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.REMOTE_ANSWERED, thread?.resolvedReason)
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: compilation fails with `Unresolved reference: resolvedReasonText`, `testDisablePolling`, and `Cannot find a parameter with this name: syncDao`.

- [ ] **Step 3: Implement**

Replace the contents of `app/src/main/java/com/shopcallback/tracker/service/CallWatcherService.kt` with:

```kotlin
package com.shopcallback.tracker.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.shopcallback.tracker.calllog.AndroidCallLogSource
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.calllog.CallLogSource
import com.shopcallback.tracker.contacts.ContactLookup
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.OutboxEventEntity
import com.shopcallback.tracker.notification.NotificationHelper
import com.shopcallback.tracker.sync.OutboxEvents
import com.shopcallback.tracker.sync.RemoteEventApplier
import com.shopcallback.tracker.sync.SyncEngine
import com.shopcallback.tracker.sync.SyncSettings
import com.shopcallback.tracker.util.ScanStateStore
import com.shopcallback.tracker.util.SharedPrefsScanStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CallWatcherService : Service() {

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + (testDispatcher ?: Dispatchers.IO)) }
    private val callLogSource by lazy { testCallLogSource ?: AndroidCallLogSource(contentResolver) }
    private val contactLookup by lazy { testContactLookup ?: ContactLookup(contentResolver) }
    private val scanStateStore: ScanStateStore by lazy { SharedPrefsScanStateStore(applicationContext) }
    private val scanner by lazy { CallLogScanner(dao()) }
    private val syncSettings by lazy { SyncSettings(applicationContext) }
    private val remoteEventApplier by lazy { RemoteEventApplier(dao(), syncDao()) }
    private val syncEngine by lazy { SyncEngine(syncSettings, syncDao(), remoteEventApplier, ::recentCallEvents) }
    private val scanMutex = Mutex()
    private lateinit var observer: ContentObserver

    private fun database() = testDatabase ?: CallbackDatabase.getInstance(applicationContext)
    private fun dao() = database().callbackThreadDao()
    private fun syncDao() = database().syncEventDao()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(applicationContext)
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(applicationContext, 0))

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceScope.launch { scanAndSync() }
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)

        serviceScope.launch {
            dao().observePending().collect { pending -> updateNotification(pending.size) }
        }
        serviceScope.launch { scanAndSync() }
        if (!testDisablePolling) {
            serviceScope.launch {
                while (isActive) {
                    delay(SYNC_INTERVAL_MILLIS)
                    syncEngine.syncOnce()
                }
            }
        }
    }

    private suspend fun scanAndSync() {
        scanOnce()
        syncEngine.syncOnce()
    }

    suspend fun scanOnce() {
        scanMutex.withLock {
            try {
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    return@withLock
                }
                val lastSeenId = scanStateStore.getLastSeenId()
                val afterDate = if (lastSeenId == SharedPrefsScanStateStore.NOT_SET) {
                    System.currentTimeMillis() - CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS
                } else {
                    0L
                }
                val entries = callLogSource.queryEntries(lastSeenId, afterDate)
                if (entries.isNotEmpty()) {
                    scanner.applyNewEntries(entries)
                    syncDao().enqueue(OutboxEvents.forCalls(syncSettings.deviceId, entries))
                    scanStateStore.setLastSeenId(entries.maxOf { it.id })
                }
                scanner.dropStalePending()
                // Newly scanned missed calls may already have been answered on another phone.
                remoteEventApplier.apply()
                resolveMissingNames()
            } catch (e: Exception) {
                Log.e(TAG, "scanOnce failed", e)
            }
        }
    }

    /** The past week's answered/outgoing calls, re-shared when sync is turned on or the server is reset. */
    private suspend fun recentCallEvents(): List<OutboxEventEntity> {
        val since = System.currentTimeMillis() - CallLogScanner.MAX_MISSED_CALL_AGE_MILLIS
        return OutboxEvents.forCalls(syncSettings.deviceId, callLogSource.queryEntries(afterId = -1L, afterDateMillis = since))
    }

    private suspend fun resolveMissingNames() {
        dao().observePending().first()
            .filter { it.displayName == null }
            .forEach { thread ->
                contactLookup.lookupDisplayName(thread.phoneNumber)?.let { name ->
                    dao().updateDisplayNameIfMissing(thread.phoneNumber, name)
                }
            }
    }

    private fun updateNotification(pendingCount: Int) {
        val notification = NotificationHelper.buildNotification(applicationContext, pendingCount)
        getSystemService(NotificationManager::class.java).notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    /** Every app open (and every Settings save) lands here, so each one also triggers a sync. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newServerUrl = intent?.takeIf { it.action == ACTION_SERVER_CHANGED }?.getStringExtra(EXTRA_SERVER_URL)
        serviceScope.launch {
            if (newServerUrl != null) syncEngine.onServerUrlChanged(newServerUrl)
            syncEngine.syncOnce()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CallWatcherService"
        private const val SYNC_INTERVAL_MILLIS = 30_000L
        const val ACTION_SERVER_CHANGED = "com.shopcallback.tracker.action.SERVER_CHANGED"
        const val EXTRA_SERVER_URL = "server_url"
        var testCallLogSource: CallLogSource? = null
        var testContactLookup: ContactLookup? = null
        var testDispatcher: CoroutineDispatcher? = null
        var testDatabase: CallbackDatabase? = null
        /** Tests drive the dispatcher to idle, which an endless poll loop would never reach. */
        var testDisablePolling = false
    }
}
```

In `app/src/main/java/com/shopcallback/tracker/ui/CallbackViewModel.kt`:
- Extend the constructor to:

```kotlin
class CallbackViewModel(
    application: Application,
    private val dao: CallbackThreadDao = CallbackDatabase.getInstance(application).callbackThreadDao(),
    private val now: () -> Long = System::currentTimeMillis,
    private val syncDao: SyncEventDao = CallbackDatabase.getInstance(application).syncEventDao(),
    private val syncSettings: SyncSettings = SyncSettings(application)
) : AndroidViewModel(application) {
```

- Replace `markResolvedManually` and `unresolve` with:

```kotlin
    fun markResolvedManually(phoneNumber: String) {
        viewModelScope.launch {
            val at = now()
            dao.markResolved(phoneNumber, CallbackStatus.RESOLVED, at, ResolvedReason.MANUAL)
            share(SyncEventType.MANUAL_RESOLVE, phoneNumber, at)
        }
    }

    fun unresolve(phoneNumber: String) {
        viewModelScope.launch {
            val at = now()
            dao.reopen(phoneNumber, at)
            share(SyncEventType.UNRESOLVE, phoneNumber, at)
        }
    }

    /** Queues a manual action for the other phones; the service uploads it on its next sync. */
    private suspend fun share(type: SyncEventType, phoneNumber: String, at: Long) {
        syncDao.enqueue(listOf(OutboxEvents.manual(syncSettings.deviceId, type, phoneNumber, at)))
    }
```

- Add these imports:
  - `com.shopcallback.tracker.data.SyncEventDao`
  - `com.shopcallback.tracker.data.SyncEventType`
  - `com.shopcallback.tracker.sync.OutboxEvents`
  - `com.shopcallback.tracker.sync.SyncSettings`

In `app/src/main/java/com/shopcallback/tracker/ui/HistoryScreen.kt`:
- Replace the line `val reasonText = if (thread.resolvedReason == ResolvedReason.MANUAL) "marked resolved" else "answered"` with `val reasonText = resolvedReasonText(thread.resolvedReason)`.
- Add at the end of the file:

```kotlin
internal fun resolvedReasonText(reason: ResolvedReason?): String = when (reason) {
    ResolvedReason.MANUAL -> "marked resolved"
    ResolvedReason.REMOTE_MANUAL -> "marked resolved on another phone"
    ResolvedReason.REMOTE_ANSWERED -> "answered on another phone"
    ResolvedReason.AUTO_ANSWERED, null -> "answered"
}
```

In `app/src/main/AndroidManifest.xml`:
- Add `<uses-permission android:name="android.permission.INTERNET" />` after the last existing `<uses-permission>`.
- Add these attributes to `<application>`, after `android:allowBackup="false"`:

```xml
        android:usesCleartextTraffic="true"
```

A comment is not allowed inside the tag, so put this XML comment just above `<application`:

```xml
    <!-- Cleartext HTTP is only used to reach the shop's sync server over Tailscale, which encrypts the link. -->
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -F - <<'EOF'
Sync from the watcher service; share manual actions; label remote resolutions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```

---

### Task 7: Settings tab

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/ui/SyncStatus.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/ui/SettingsViewModel.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/ui/SettingsScreen.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/MainActivity.kt` (`MainTabs`)
- Test: `app/src/test/java/com/shopcallback/tracker/ui/SyncStatusTest.kt` (new)
- Test: `app/src/test/java/com/shopcallback/tracker/ui/SettingsViewModelTest.kt` (new)

**Interfaces:**
- Consumes:
  - `SyncSettings`, `SyncClient` and `FakeSyncServer` (Task 4)
  - `SyncEventDao.observeOutboxCount()` (Task 2)
  - `CallWatcherService.ACTION_SERVER_CHANGED` and `EXTRA_SERVER_URL` (Task 6)
- Produces:
  - `fun syncStatusText(serverUrl: String, lastSyncAt: Long?, waiting: Int, now: Long): String`
  - `class SettingsViewModel`:
    - `val savedServerUrl: String`
    - `val status: StateFlow<String>`
    - `val connection: StateFlow<String?>`
    - `fun testConnection(url: String)`
    - `fun save(url: String)`
  - `@Composable fun SettingsScreen(viewModel: SettingsViewModel)`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/shopcallback/tracker/ui/SyncStatusTest.kt`:

```kotlin
package com.shopcallback.tracker.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusTest {
    private val now = 10 * 24 * 60 * 60 * 1000L
    private val minute = 60_000L

    @Test
    fun `sync off`() {
        assertEquals("Sync is off", syncStatusText("", lastSyncAt = null, waiting = 3, now = now))
    }

    @Test
    fun `never synced`() {
        assertEquals("Never synced · 2 waiting to upload", syncStatusText("http://pc:8787", null, 2, now))
    }

    @Test
    fun `last sync time is shown in rough units`() {
        assertEquals("Last synced just now · 0 waiting to upload", syncStatusText("http://pc:8787", now - 20_000, 0, now))
        assertEquals("Last synced 2 min ago · 0 waiting to upload", syncStatusText("http://pc:8787", now - 2 * minute, 0, now))
        assertEquals("Last synced 3 h ago · 1 waiting to upload", syncStatusText("http://pc:8787", now - 185 * minute, 1, now))
        assertEquals("Last synced 2 d ago · 0 waiting to upload", syncStatusText("http://pc:8787", now - 2 * 1440 * minute, 0, now))
    }
}
```

Create `app/src/test/java/com/shopcallback/tracker/ui/SettingsViewModelTest.kt`:

```kotlin
package com.shopcallback.tracker.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.service.CallWatcherService
import com.shopcallback.tracker.sync.FakeSyncServer
import com.shopcallback.tracker.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var db: CallbackDatabase
    private lateinit var server: FakeSyncServer
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(application, CallbackDatabase::class.java).allowMainThreadQueries().build()
        server = FakeSyncServer()
        SyncSettings(application).serverUrl = "http://saved:8787"
        viewModel = SettingsViewModel(application, SyncSettings(application), db.syncEventDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.close()
        db.close()
    }

    @Test
    fun `shows the saved address`() {
        assertEquals("http://saved:8787", viewModel.savedServerUrl)
    }

    @Test
    fun `test connection reports a reachable server`() = runBlocking {
        viewModel.testConnection(server.url)
        val result = withTimeout(5_000) { viewModel.connection.first { it != null && it != "Checking…" } }
        assertEquals("Connected ✓", result)
    }

    @Test
    fun `test connection reports an unreachable server`() = runBlocking {
        val deadUrl = FakeSyncServer().run { close(); url }
        viewModel.testConnection(deadUrl)
        val result = withTimeout(15_000) { viewModel.connection.first { it != null && it != "Checking…" } }
        assertEquals("Can't reach server", result)
    }

    @Test
    fun `saving hands the trimmed address to the service`() {
        viewModel.save("  http://shop-pc:8787  ")

        val started = shadowOf(application).nextStartedService
        assertEquals(CallWatcherService.ACTION_SERVER_CHANGED, started.action)
        assertEquals("http://shop-pc:8787", started.getStringExtra(CallWatcherService.EXTRA_SERVER_URL))
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --tests '*SyncStatusTest*' --tests '*SettingsViewModelTest*' --console=plain`
Expected: compilation fails with `Unresolved reference: syncStatusText` and `SettingsViewModel`.

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/shopcallback/tracker/ui/SyncStatus.kt`:

```kotlin
package com.shopcallback.tracker.ui

/** e.g. "Last synced 2 min ago · 0 waiting to upload", or "Sync is off" with no server address. */
fun syncStatusText(serverUrl: String, lastSyncAt: Long?, waiting: Int, now: Long): String {
    if (serverUrl.isBlank()) return "Sync is off"
    val synced = if (lastSyncAt == null) "Never synced" else "Last synced ${ago(now - lastSyncAt)}"
    return "$synced · $waiting waiting to upload"
}

private fun ago(millis: Long): String {
    val minutes = millis / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 1440 -> "${minutes / 60} h ago"
        else -> "${minutes / 1440} d ago"
    }
}
```

Create `app/src/main/java/com/shopcallback/tracker/ui/SettingsViewModel.kt`:

```kotlin
package com.shopcallback.tracker.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.SyncEventDao
import com.shopcallback.tracker.service.CallWatcherService
import com.shopcallback.tracker.sync.SyncClient
import com.shopcallback.tracker.sync.SyncSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    application: Application,
    private val settings: SyncSettings = SyncSettings(application),
    syncDao: SyncEventDao = CallbackDatabase.getInstance(application).syncEventDao(),
    private val clientFor: (String) -> SyncClient = ::SyncClient,
    private val now: () -> Long = System::currentTimeMillis
) : AndroidViewModel(application) {

    val savedServerUrl: String get() = settings.serverUrl

    /** Re-read every few seconds: the service updates the settings in the background. */
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(STATUS_REFRESH_MILLIS)
        }
    }

    val status: StateFlow<String> = combine(syncDao.observeOutboxCount(), ticker) { waiting, _ ->
        syncStatusText(settings.serverUrl, settings.lastSyncAt, waiting, now())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _connection = MutableStateFlow<String?>(null)
    /** Result of the last Test connection, or null if none since the last save. */
    val connection: StateFlow<String?> = _connection

    fun testConnection(url: String) {
        _connection.value = "Checking…"
        viewModelScope.launch {
            val reachable = withContext(Dispatchers.IO) { runCatching { clientFor(url.trim()).health() }.isSuccess }
            _connection.value = if (reachable) "Connected ✓" else "Can't reach server"
        }
    }

    /** The service owns sync, so it applies the change (reset, re-share recent calls, sync). */
    fun save(url: String) {
        val app = getApplication<Application>()
        app.startForegroundService(
            Intent(app, CallWatcherService::class.java)
                .setAction(CallWatcherService.ACTION_SERVER_CHANGED)
                .putExtra(CallWatcherService.EXTRA_SERVER_URL, url.trim())
        )
        _connection.value = null
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(application) as T
    }

    private companion object {
        const val STATUS_REFRESH_MILLIS = 5_000L
    }
}
```

Create `app/src/main/java/com/shopcallback/tracker/ui/SettingsScreen.kt`:

```kotlin
package com.shopcallback.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    var url by rememberSaveable { mutableStateOf(viewModel.savedServerUrl) }
    val status by viewModel.status.collectAsState()
    val connection by viewModel.connection.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sync with other phones", style = MaterialTheme.typography.titleMedium)
        Text(
            "Callbacks answered on any shop phone clear here too. Leave empty to use this phone on its own.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Server address") },
            placeholder = { Text("http://shop-pc:8787") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.save(url) }) { Text("Save") }
            OutlinedButton(onClick = { viewModel.testConnection(url) }, enabled = url.isNotBlank()) {
                Text("Test connection")
            }
        }
        connection?.let { Text(it) }
        Text(status, style = MaterialTheme.typography.bodyMedium)
    }
}
```

In `app/src/main/java/com/shopcallback/tracker/MainActivity.kt`, in `MainTabs()`:
- After the `Tab(selected = tab == 1, …)` line, add:

```kotlin
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Settings") })
```

- Replace the `if (tab == 0) { … } else { HistoryScreen(viewModel) }` block with:

```kotlin
        when (tab) {
            0 -> PendingCallbacksScreen(viewModel) { intent -> context.startActivity(intent) }
            1 -> HistoryScreen(viewModel)
            else -> {
                // Fully qualified: the local `viewModel` above shadows the viewModel() function.
                val settingsViewModel: SettingsViewModel =
                    androidx.lifecycle.viewmodel.compose.viewModel(factory = SettingsViewModel.Factory(application))
                SettingsScreen(settingsViewModel)
            }
        }
```

- Add the imports `com.shopcallback.tracker.ui.SettingsScreen` and `com.shopcallback.tracker.ui.SettingsViewModel`.

- [ ] **Step 4: Run the tests and build to verify they pass**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -F - <<'EOF'
Add Settings tab to connect a phone to the sync server

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```
