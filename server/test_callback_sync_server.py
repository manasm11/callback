import http.client
import json
import os
import subprocess
import sys
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
        self.assertEqual(self.post_with_content_length(-1), 400)

    def post_with_content_length(self, length):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_address[1], timeout=5)
        try:
            connection.putrequest("POST", "/events")
            connection.putheader("Content-Type", "application/json")
            connection.putheader("Content-Length", str(length))
            connection.endheaders()
            return connection.getresponse().status
        finally:
            connection.close()

    def test_unknown_paths_get_404(self):
        self.assertEqual(self.request("GET", "/nope")[0], 404)
        self.assertEqual(self.request("POST", "/nope", {})[0], 404)


class ListenAddressTest(unittest.TestCase):
    def test_a_specific_address_is_accepted(self):
        self.assertEqual(sync.listen_address_error("100.101.102.103"), None)
        self.assertEqual(sync.listen_address_error("127.0.0.1"), None)

    def test_empty_or_wildcard_addresses_are_rejected(self):
        for host in ["", "  ", "0.0.0.0", "::", "[::]"]:
            with self.subTest(host=host):
                self.assertIsNotNone(sync.listen_address_error(host))

    def test_the_server_exits_with_an_error_for_an_empty_host(self):
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "callback_sync_server.py")
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, script, "--host", "", "--port", "0", "--db", os.path.join(directory, "e.db")],
                capture_output=True, text=True, timeout=10,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("--host", result.stderr)


if __name__ == "__main__":
    unittest.main()
