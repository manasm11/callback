# Pending Callbacks Home-Screen Widget — Design Spec

Date: 2026-10-02

Builds on: `2026-09-28-missed-call-callback-tracker-design.md`, plus
the day grouping added after it (Pending/History grouped by day, newest
first).

## Goal

Staff can see every pending callback, grouped by day, on the phone's
home screen. Tapping one calls the customer back without opening the
app.

## Decisions made during brainstorming

- **Tap a row → call back.** Tapping the title opens the app. Mark
  resolved stays in the app, because it requires a confirmation dialog
  and widgets can't show dialogs.
- **The call starts immediately** (`ACTION_CALL`), the same as the
  app's "Call back" button.
- **Built with Jetpack Glance** (`androidx.glance:glance-appwidget`).
  This is the one new dependency. Classic RemoteViews was rejected:
  roughly three times the code (XML layouts plus a separate list
  service) for the same result.

## Non-goals

- Mark resolved or Un-resolve from the widget.
- A History widget, or any configuration screen for the widget.
- Pinned (sticky) day headers. Android widgets can't do them.

## Content

- **Title bar:** "Pending callbacks · N", where N is the total number
  of pending callbacks. Tapping it opens `MainActivity`.
- **Body:** a scrolling list grouped by day, newest day first, with
  the most recent caller first within each day. It must be identical
  to the Pending tab: grouped by `lastMissedAt` and using the same day
  labels from the existing `groupByDayNewestFirst` / `dayLabel`.
  - **Day header:** "<label> · <count>", e.g. "Today · 3".
  - **Row line 1:** display name, falling back to the number.
  - **Row line 2:** "<time of last missed call> · <missed calls
    summary>", where the summary is the same text the Pending tab
    uses: "1 missed call", or "3 missed calls since Monday".
- **Empty state:** "All caught up" instead of the list.

## Tapping a row

- If the app holds `CALL_PHONE`, start `ACTION_CALL` with
  `tel:<number>`.
- If not (the permission was revoked after onboarding), start
  `ACTION_DIAL` with the same URI, so the tap still does something
  useful rather than failing silently.
- The choice is made when the widget is rendered.

## Keeping it current

- `CallWatcherService` already collects `observePending()` to update
  its notification. On every emission it also asks Glance to update
  all instances of the widget. That covers new missed calls, local and
  remote resolutions, Mark resolved / Un-resolve, stale-callback
  cleanup and syncs from other phones.
- The widget provider's `updatePeriodMillis` is 30 minutes (Android's
  minimum), so day labels roll over after midnight even when nothing
  changes.
- Rendering reads the pending list straight from Room
  (`observePending().first()`) and computes groups with the current
  time.

## Size and look

- Resizable both ways. The default is about 4×3 cells, the minimum
  about 2×2.
- Follows the system light/dark theme via Glance's default theme.
- Widget picker label: "Pending callbacks".

## Code organisation

- Move the Pending tab's private `missedCallsSummary` into a shared,
  internal function next to `DayGrouping.kt`, so the tab and the
  widget can't drift.
- Turning the pending list into what the widget displays is a pure
  function, so it can be unit-tested without a widget host. It
  produces:
  - the title count
  - day sections with their labels and counts
  - per row: name, time line, number and whether to call or dial
- New package `widget/` with:
  - the `GlanceAppWidget`
  - its `GlanceAppWidgetReceiver`
  - the pure display-model builder
  - a small `updateAll` helper for the service
- New resources: `res/xml/pending_callbacks_widget_info.xml` (the
  provider info) and the receiver declared in the manifest.

## Testing

- **Unit tests for the display model:**
  - groups and order
  - header labels and counts
  - row line text, including "since <day>" for repeat callers
  - the empty state
  - the title count
  - call versus dial depending on the permission
- **Existing tests:** all existing tests still pass and
  `assembleDebug` succeeds.
- **On the phone** (the user checks; there's no device in the dev
  environment):
  1. Add the widget.
  2. Miss a call; it appears under "Today".
  3. Tap the row; the call starts.
  4. Mark the callback resolved in the app; it leaves the widget.
