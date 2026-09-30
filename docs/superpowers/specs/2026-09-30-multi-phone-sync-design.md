# Multi-Phone Sync — Design Spec

Date: 2026-09-30

Builds on: `2026-09-28-missed-call-callback-tracker-design.md`, which
listed cross-phone sync as a v1 non-goal. This spec adds it.

## Problem

The shop now runs the app on 4 phones, each with its own number. A
customer who missed-called phone A is often called back (or gets
through) on phone B, but A's Pending list doesn't know that, so staff
on A call back a customer who's already been handled.

## Goal

A pending callback disappears from every phone's Pending list shortly
after that customer is reached from **any** of the 4 phones. With no
network, every phone keeps working exactly as it does today.

## Decisions made during brainstorming

- **Separate lists, shared resolution.** Each phone's Pending list
  holds only its own missed calls. Missed calls are never shared.
- **"Reached" = the existing local rule, applied across phones.**
  Customer X, pending on phone A, is resolved when any phone has an
  answered-incoming or outgoing call with X lasting over 1 second,
  after the start of A's callback for X (see "Reopened-at" below).
- **Manual actions sync.** Mark resolved and Un-resolve on one phone
  apply to that customer on every phone where they're pending (or
  resolved, for Un-resolve).
- **Server:** a Linux PC on the shop's Tailscale tailnet. It may not be
  on 24/7; phones must tolerate that.
- **Architecture:** a shared event log. The server stores and relays
  events and applies no rules; each phone applies the rules itself.
- **Sync cadence:** upload right after each scan, plus a 30-second
  poll (upload and pull). Long-polling was considered and declined:
  other phones may lag by up to ~30 s, which is acceptable.
- **Upload scope:** a phone uploads *every* answered or outgoing call
  over 1 second, including personal ones, because it can't know which
  numbers other phones have pending. Data stays on the shop PC inside
  the tailnet and is purged after 7 days.

## Non-goals

- Shared Pending lists (seeing other phones' missed calls).
- Showing *which* phone resolved a customer (only "on another phone").
- Authentication beyond tailnet membership.
- Push or long-poll delivery.
- HTTPS (Tailscale already encrypts the link).

## 1. Server

- One Python 3 file using the standard library only (`http.server`
  with threading, `sqlite3`, `json`). No third-party packages. Lives in
  `server/` in this repo with its tests and a README.
- Runs as a systemd service. It binds only to the PC's Tailscale IPv4
  address (passed as `--host`, obtained via `tailscale ip -4`). Default
  port 8787.
- **Storage:** a SQLite file with two tables:
  - `meta(key TEXT PRIMARY KEY, value TEXT)`: holds `server_id`, a
    random UUID created with the database.
  - `events` with these columns:
    - `seq INTEGER PRIMARY KEY AUTOINCREMENT`
    - `event_id TEXT UNIQUE NOT NULL`
    - `device_id TEXT NOT NULL`
    - `type TEXT NOT NULL`: `CALL`, `MANUAL_RESOLVE` or `UNRESOLVE`
    - `number TEXT NOT NULL`: already normalized by the phone
    - `timestamp INTEGER NOT NULL`: epoch ms
    - `duration_seconds INTEGER`: CALL only
    - `direction TEXT`: `INCOMING` or `OUTGOING`, CALL only
- **API** (JSON):
  - `GET /health` returns `{"serverId": "..."}`.
  - `POST /events` takes the body
    `{"deviceId": "...", "events": [{"eventId", "type", "number", "timestamp", "durationSeconds"?, "direction"?}]}`.
    - Inserts with `INSERT OR IGNORE` on `event_id`, so retries are
      idempotent.
    - Returns `{"serverId": "...", "accepted": <n inserted>}`.
    - A malformed body or event gets `400`.
  - `GET /events?after=<seq>&device=<deviceId>` returns
    `{"serverId": "...", "latestSeq": <max seq>, "events": [...]}`.
    - Events are those with `seq > after` whose `device_id` differs
      from the caller, ordered by `seq`, each including its `seq`.
    - At most 500 per response. The phone keeps paging while it
      receives a full page.
- **Retention:** each `POST /events` and an hourly timer delete events
  whose `timestamp` is older than 7 days.

## 2. Phone sync

### New local state (Room, database version 1 → 2)

- **`outbox_events`:** events waiting to upload. Columns are
  `eventId` (PK), `type`, `number`, `timestamp`, `durationSeconds?`,
  `direction?`.
- **`remote_events`:** other phones' events from the last 7 days.
  Columns are `eventId` (PK), `type`, `number`, `timestamp`,
  `durationSeconds?`, `direction?`, `applied` (bool). It is purged of
  rows older than 7 days on each pull.
- **`callback_threads`** gains `reopenedAt INTEGER NULL`.
- **`ResolvedReason`** gains `REMOTE_ANSWERED` and `REMOTE_MANUAL`.
  These are stored as strings, so no data change is needed.
- A real `Migration(1, 2)`: existing callbacks must survive an update.
  It is tested by building a v1 database from Room's own v1 SQL and
  opening it with the migration; Room validates the result. This needs
  no schema export and no new dependencies.

### Sync identity and settings (SharedPreferences)

- `deviceId`: a random UUID created on first launch.
- `serverUrl`: empty means sync is off.
- `serverId`: the last seen value, used to detect a server reset.
- `cursor`: the last pulled `seq`.
- `lastSyncAt`: the time of the last successful exchange.

### Producing events

- **The scanner** (`CallWatcherService.scanOnce`) enqueues a `CALL`
  event for every INCOMING or OUTGOING entry with duration > 1 s.
  - `eventId = "<deviceId>:call:<callLogRowId>"`.
  - The number is normalized, and short or withheld numbers are
    skipped, as the scanner already does.
  - This happens whether or not sync is on, so turning sync on later
    only needs the backfill below.
- **`markResolvedManually` / `unresolve`** enqueue `MANUAL_RESOLVE` /
  `UNRESOLVE` with `eventId = "<deviceId>:manual:<uuid>"` and
  `timestamp = now`.
- **Outbox size:** rows older than 7 days are purged, so a phone that
  never syncs doesn't grow the outbox without limit.

### Backfill

When sync is first turned on, or after a server reset (see §4), the
phone enqueues `CALL` events for the last 7 days of its call log. It
reads the call log directly, independent of the scan cursor.

### Sync loop

- It runs inside `CallWatcherService`, which is already a foreground
  service. It also runs once when the app opens.
- It is triggered right after each scan, and every 30 s while
  `serverUrl` is set.
- **One sync pass:**
  1. **Upload:** POST the outbox in batches of up to 200, deleting the
     rows each 2xx response confirms. On a 400, log and delete that
     batch so a bad event can't block the queue forever.
  2. **Pull:** GET after `cursor`, insert the events into
     `remote_events`, advance `cursor`, and page until the response is
     short.
  3. **Apply:** run the rules below.
  4. Set `lastSyncAt`.
- Any IOException or timeout (5 s connect, 10 s read) ends the pass
  silently. The next trigger retries.
- Passes are serialized with a mutex, the same pattern as `scanOnce`.
- Transport: `HttpURLConnection` plus `org.json`, so no new
  dependencies.

### Applying remote events

Both rules below run after every pull and after every local scan.

**Remote CALL events are re-evaluated every time, not applied once.**
This makes the result independent of whether the remote call or the
local missed call is processed first.
- A pending thread for number N is resolved (`REMOTE_ANSWERED`,
  `resolvedAt` = the event's timestamp) if some remote CALL for N has
  `durationSeconds > 1` and
  `timestamp > max(lastMissedAt, reopenedAt ?: 0)`.

**Remote manual events are applied once, in timestamp order, then
marked `applied`:**
- `MANUAL_RESOLVE` for N: if N's thread is PENDING and
  `max(lastMissedAt, reopenedAt ?: 0) < event.timestamp`, resolve it
  with `REMOTE_MANUAL` and `resolvedAt = event.timestamp`.
- `UNRESOLVE` for N: if N's thread is RESOLVED and
  `resolvedAt <= event.timestamp`, reopen it. Reopening sets PENDING,
  clears the resolution fields and sets `reopenedAt = event.timestamp`.

**Why `lastMissedAt`, not `firstMissedAt`.** A remote event can arrive
long after it happened (server down, phone offline). By then this phone
may have merged a newer missed call into the pending thread. Example:
A misses N at 10:00, B calls N back at 10:30, A misses N again at
11:00, and B's call only arrives at 12:00. Comparing against the
thread's latest activity, `max(lastMissedAt, reopenedAt ?: 0)`, leaves
the 11:00 callback pending — the same result as processing every event
in time order. (The local answered-call rule below keeps
`firstMissedAt`: the scanner reads the call log in time order, so it
never sees a call after a later miss was merged.)

### Reopened-at (local rule change)

- Local Un-resolve also sets `reopenedAt = now`.
- The local answered-call rule changes to
  `entry.timestamp > max(firstMissedAt, reopenedAt ?: 0)`.
- Without this, a call from before the Un-resolve would immediately
  resolve the contact again.
- A new missed call that starts a fresh callback clears `reopenedAt`.

### UI

- History shows "answered on another phone" for `REMOTE_ANSWERED` and
  "marked resolved on another phone" for `REMOTE_MANUAL`.
- Pending, the notification count and the 7-day rule are unchanged.

### Android specifics

- Add the `INTERNET` permission.
- Permit cleartext HTTP with `android:usesCleartextTraffic="true"` on
  the application. The app talks to no other host, and Tailscale
  encrypts the link.

## 3. Setup and settings

- **Outside the app:**
  - Tailscale on the PC and on every phone, all on the same account.
  - The server installed per `server/README.md`: copy one file,
    install a systemd unit, `systemctl enable --now`.
- **Settings tab**, the third tab after Pending and History:
  - **Server address** field (e.g. `http://shop-pc:8787`). Empty, the
    default, means sync is off and the app behaves exactly as before.
    Saving a new non-empty address counts as "first turned on":
    backfill, then reset `cursor` and `serverId`.
  - **Test connection** button: calls `/health` and shows
    "Connected ✓" or "Can't reach server".
  - **Status line**, e.g. "Last synced 2 min ago · 0 waiting to
    upload". Before any sync it reads "Never synced".

## 4. Failures and edge cases

- **Server unreachable** (PC off, Tailscale off, no network): local
  behaviour is unchanged. The outbox queues, and only the status line
  reflects the problem. No error dialogs.
- **Phone offline for over 7 days:** events older than 7 days are
  purged on the server and ignored by the 7-day rule anyway.
- **Server database reset:** a response whose `serverId` differs from
  the stored one makes the phone reset `cursor` to 0, store the new
  `serverId` and re-run the backfill. A changed server address
  triggers the same reset.
- **Duplicate uploads:** ignored by the server's unique `event_id`.
- **App reinstalled:** it gets a new `deviceId` and backfills again.
  The same call under two device IDs is harmless, since resolution is
  idempotent.
- **Clock differences:** phones use network time, so skew is seconds
  at most. That's negligible for "called back after the miss".
- **Late remote Un-resolve:** if an Un-resolve arrives late — after
  this phone has already called the customer back, at a time later
  than the Un-resolve — it reopens the callback anyway, because this
  phone's own call was scanned while the callback was still resolved.
  The already-reached customer then shows as pending on this phone.
  This fails safe: an extra entry, never a lost one.

## 5. Testing

- **Server** (Python `unittest`, standard library only):
  - duplicate `event_id`s are ignored
  - `after` and `device` filtering and paging
  - the 7-day purge
  - `serverId` is stable across restarts
  - malformed input gets `400`
- **App** (JUnit and Robolectric):
  - the scanner enqueues CALL events for answered and outgoing calls
    over 1 s only; manual actions enqueue their events
  - remote CALL resolves in both arrival orders
  - `reopenedAt` blocks resolution by calls from before it, for both
    local and remote calls
  - remote MANUAL_RESOLVE and UNRESOLVE rules, including a later
    missed call staying pending
  - `Migration(1, 2)` keeps existing rows
  - the sync client against an in-process JDK `HttpServer`: upload,
    delete on 2xx, drop on 400, paging, server-reset handling, silent
    failure when unreachable
- **On real phones** (manual checklist for the user, since there is no
  device or emulator in the dev environment):
  1. Miss a call on A and call back from B; it clears on A within
     about 30 s.
  2. Mark resolved on A; it clears on B too.
  3. Turn Wi-Fi off on A; A still tracks missed calls normally.
  4. Turn Wi-Fi back on; A catches up.
  5. Stop the server, make calls, then restart it; everything
     converges.
