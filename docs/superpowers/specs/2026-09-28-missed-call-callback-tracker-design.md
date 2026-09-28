# Missed Call Callback Tracker — Design Spec

Date: 2026-09-28

## Problem

The shop has two mobile phones that customers call. Employees miss a
significant number of incoming calls, and today's process — manually
scrolling the phone's call log and trying to remember who's been
called back — is unreliable. Missed calls fall through the cracks:
customers never get a callback, or get called back but the callback
also goes unanswered and nobody notices it's still open.

## Goal

Every missed call on each shop phone results in an actual **answered
conversation** with the customer, and nothing is silently forgotten.
"Loyal customer" prioritization is explicitly out of scope for this
version — the app treats every missed call equally.

## Non-goals (v1)

- No cross-phone sync or shared backend. Each phone runs its own
  independent copy of the app tracking only its own call log.
- No customer/loyalty tiering or CRM features.
- No spam/telemarketer filtering.
- No Play Store distribution — sideloaded APK on two known devices.

## Architecture & data flow

- Single Kotlin Android app (Jetpack Compose UI), installed
  independently on each phone. No network calls.
- **`CallWatcherService`** — a foreground service, started at boot
  (`RECEIVE_BOOT_COMPLETED`) and on app launch, runs continuously and
  owns the persistent notification.
- The service registers a `ContentObserver` on
  `CallLog.Calls.CONTENT_URI`. Any call log write (missed call,
  answered call, outgoing call) triggers an incremental scan of rows
  newer than the last-seen timestamp.
- Each scan updates a local **Room database** of "callback threads",
  then recomputes the pending count and refreshes the one persistent
  notification.
- The UI reads the same Room DB reactively (via `Flow`) — it never
  queries the call log directly; only the service does, keeping
  detection logic in one place.
- On first install, the service does a one-time backfill scan of the
  last ~30 days of call log to seed initial state rather than starting
  empty.

**Why a foreground service instead of a plain broadcast receiver:** a
`PHONE_STATE`-broadcast-only design is lighter weight, but budget/OEM
Android phones (Xiaomi, Vivo, etc.) are known to aggressively kill
background receivers to save battery — a real risk on shop phones
nobody actively babysits. A foreground service is much harder for the
OS/OEM to silently kill, and its required persistent notification does
double duty as the pending-count display we want anyway.

## Data model

Room table `callback_threads`, one row per phone number with an open
or historical case:

| field | purpose |
|---|---|
| `phone_number` (PK, normalized) | groups all activity for a number |
| `display_name` | from Android Contacts if matched, else null |
| `first_missed_at` | timestamp of earliest unresolved missed call in this streak |
| `last_missed_at` | timestamp of most recent missed call |
| `attempt_count` | number of missed calls in this open streak |
| `status` | `PENDING` / `RESOLVED` |
| `resolved_at` | timestamp, nullable |
| `resolved_reason` | `AUTO_ANSWERED` / `MANUAL`, nullable |

Numbers are normalized (formatting/prefixes stripped) before use as
the key, so `+91 98765 43210`, `9876543210`, and `098765-43210` all
map to the same customer.

Dual-SIM phones: call log entries from either SIM are merged into one
list — a missed call is a missed call regardless of which SIM
received it.

## Detection & resolution logic

On each scan:

1. New missed call (`CallLog.Calls.TYPE = MISSED`) for number X: if no
   open (`PENDING`) thread exists for X, create one; otherwise bump
   `attempt_count` and update `last_missed_at`.
2. New call (incoming or outgoing) for number X with **duration > 1
   second**, timestamped after `first_missed_at` of an open thread for
   X, marks that thread `RESOLVED` with `resolved_reason =
   AUTO_ANSWERED`. (Covers both "customer called back and we picked
   up" and "we called back and they picked up." The >1s threshold
   filters out instant pocket-dials/immediate drops that aren't real
   conversations.)
3. Manual override: a "Mark resolved" action in the UI sets `status =
   RESOLVED`, `resolved_reason = MANUAL` directly, for cases the call
   log can't explain (customer handled in person, texted, wrong
   number).
4. A `RESOLVED` thread that receives a new missed call later reopens
   as a fresh `PENDING` thread (new `first_missed_at`, `attempt_count`
   reset to 1) — treated as a new episode, not a continuation.

## UI/UX

- **Pending Callbacks screen (default view):** Compose `LazyColumn`
  filtered to `status = PENDING`, sorted oldest `first_missed_at`
  first (the longest-waiting customer at top). Each row shows:
  - Contact name (or raw number if unknown)
  - Attempt count badge (e.g. "3 missed calls")
  - Time waiting since first missed call
  - **Call back** button → `ACTION_CALL` intent, dials directly
  - **Mark resolved** action (swipe or button) for manual override
- **History screen** (secondary tab): resolved threads, newest first,
  showing resolution reason (auto vs manual) — for spot-checking the
  system works, not a daily-use screen.
- **Persistent notification:** "X customers waiting for a callback"
  (or "All caught up" at 0), tapping opens the Pending Callbacks
  screen. Required foreground-service notification, non-dismissible —
  a running summary, not a per-event alert (the phone's own
  missed-call notification already covers that).

## Permissions

- `READ_CALL_LOG` — read call history
- `READ_PHONE_STATE` — required alongside call log APIs on some OEMs
- `CALL_PHONE` — direct dial from the callback button
- `READ_CONTACTS` — show names instead of raw numbers
- `POST_NOTIFICATIONS` (Android 13+)
- `RECEIVE_BOOT_COMPLETED` — restart service after reboot
- `FOREGROUND_SERVICE` + a declared foreground service type (Android
  14 requires this — `dataSync` or `specialUse`)

## Reliability

- First-launch flow walks staff through granting all permissions and
  **disabling battery optimization** for the app (Android's
  "unrestricted battery" setting) — the single biggest real-world
  cause of missed detections on budget/OEM phones. The app should
  detect if it's still restricted and keep prompting until fixed.
- Service uses `START_STICKY` and the boot receiver to restart itself
  if the OS kills it.

## Testing

- Unit tests for the detection/resolution logic (thread
  creation/reopening, duration threshold, number normalization) using
  fake call-log rows — no device needed.
- Manual on-device verification: place/miss real test calls between
  two phones, confirm the pending list, notification count, and
  resolution all behave as designed, including after a reboot and
  with battery optimization on vs. off.

## Open questions / future scope (explicitly deferred)

- Loyal-customer tiering/prioritization.
- Cross-phone shared list / backend sync.
- Spam number filtering.
