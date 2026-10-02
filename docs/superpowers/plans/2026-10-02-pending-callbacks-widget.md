# Pending Callbacks Widget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A resizable home-screen widget that lists every pending callback grouped by day (newest first). Tapping a row calls the customer back; tapping the title opens the app.

**Architecture:**
- **Display model:** a pure function turns the pending list into what the widget shows: title count, day sections and rows. It is unit-tested and reuses the Pending tab's grouping and text helpers.
- **Widget:** a Jetpack Glance `GlanceAppWidget` renders that model.
- **Refresh:** `CallWatcherService` refreshes every widget instance whenever the pending list changes. The widget provider also refreshes every 30 minutes, so day labels roll over.

**Tech Stack:** Kotlin 1.9.24, Jetpack Glance (`androidx.glance:glance-appwidget:1.1.1`), Room 2.6.1, Robolectric 4.14.

**Spec:** `docs/superpowers/specs/2026-10-02-pending-callbacks-widget-design.md`. Read it before starting any task.

## Global Constraints

**Content**
- Title text: `"Pending callbacks · N"`. Empty state: `"All caught up"`. Widget picker label: `"Pending callbacks"`.
- Grouping must match the Pending tab exactly:
  - group by `lastMissedAt` with the existing `groupByDayNewestFirst` (newest day first, newest caller first within a day)
  - day header text `"<label> · <count>"`
  - row line 2 `"<time> · <missedCallsSummary>"`

**Behaviour**
- Tapping a row uses `Intent.ACTION_CALL` with `tel:<number>` when `CALL_PHONE` is granted, otherwise `Intent.ACTION_DIAL`.
- Tapping the title opens `MainActivity`.
- `updatePeriodMillis` is `1800000` (30 minutes).

**Dependencies**
- The only new dependency is `androidx.glance:glance-appwidget:1.1.1`. If that version fails to resolve or compile with Kotlin 1.9.24, use `1.1.0` and say so in your report.

**Running tests**
- From the repo root `/home/manas/ddg/callback` (or your worktree): `mise exec -- ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`. Add `--tests '<pattern>'` to narrow.

**Commits**
- Every commit message ends with a blank line and then these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
  ```
- Do not push. Pushing publishes a release.

---

## File Structure

| File | Responsibility |
|---|---|
| `ui/DayGrouping.kt` | Modify: add `DayGroup.headerText()` |
| `ui/DayHeader.kt` | Modify: use `headerText()` |
| `ui/CallbackText.kt` (new) | `missedCallsSummary(thread, now, zone, locale)`, moved out of `PendingCallbacksScreen` |
| `ui/PendingCallbacksScreen.kt` | Modify: use the shared `missedCallsSummary` |
| `widget/WidgetModel.kt` (new) | Pure display model plus `buildWidgetModel(...)` and `callBackIntent(...)` |
| `widget/PendingCallbacksWidget.kt` (new) | `GlanceAppWidget`, its receiver and `refreshAll(context)` |
| `res/xml/pending_callbacks_widget_info.xml` (new) | Widget provider info |
| `res/values/strings.xml` | Modify: `widget_name`, `widget_description` |
| `AndroidManifest.xml` | Modify: register the receiver |
| `app/build.gradle.kts` | Modify: the Glance dependency |
| `service/CallWatcherService.kt` | Modify: refresh widgets when the pending list changes |

All app paths are under `app/src/main/java/com/shopcallback/tracker/` or `app/src/main/res/`. Tests mirror them under `app/src/test/java/com/shopcallback/tracker/`.

---

### Task 1: Widget display model and shared text helpers

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/ui/CallbackText.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/widget/WidgetModel.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/ui/DayGrouping.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/ui/DayHeader.kt`
- Modify: `app/src/main/java/com/shopcallback/tracker/ui/PendingCallbacksScreen.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/widget/WidgetModelTest.kt` (new)

**Interfaces:**
- Produces, used by Task 2:
  - `fun buildWidgetModel(pending: List<CallbackThreadEntity>, now: Long, formatTime: (Long) -> String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): WidgetModel`
  - `data class WidgetModel(val total: Int, val sections: List<WidgetSection>)`
  - `data class WidgetSection(val header: String, val rows: List<WidgetRow>)`
  - `data class WidgetRow(val title: String, val detail: String, val phoneNumber: String)`
  - `fun callBackIntent(phoneNumber: String, canCall: Boolean): Intent`
  - `fun DayGroup<*>.headerText(): String`
  - `fun missedCallsSummary(thread: CallbackThreadEntity, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/shopcallback/tracker/widget/WidgetModelTest.kt`:

```kotlin
package com.shopcallback.tracker.widget

import android.content.Intent
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class WidgetModelTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    // Wednesday 30 Sep 2026, 3:00 PM local time.
    private val now = at(2026, 9, 30, 15, 0)

    private val clock: (Long) -> String = { ts ->
        val t = java.time.Instant.ofEpochMilli(ts).atZone(zone)
        "%02d:%02d".format(t.hour, t.minute)
    }

    @Test
    fun `groups pending callbacks by latest missed call, newest day and caller first`() {
        val model = buildWidgetModel(
            listOf(
                thread("9000000001", "Priya", first = at(2026, 9, 28, 9, 0), last = at(2026, 9, 28, 9, 0)),
                thread("9000000002", null, first = at(2026, 9, 30, 8, 0), last = at(2026, 9, 30, 8, 0)),
                thread("9000000003", "Ravi", first = at(2026, 9, 28, 10, 0), last = at(2026, 9, 30, 14, 0), attempts = 3)
            ),
            now, clock, zone, Locale.US
        )

        assertEquals(3, model.total)
        assertEquals(listOf("Today · 2", "Monday · 1"), model.sections.map { it.header })
        assertEquals(
            listOf(
                WidgetRow("Ravi", "14:00 · 3 missed calls since Monday", "9000000003"),
                WidgetRow("9000000002", "08:00 · 1 missed call", "9000000002")
            ),
            model.sections[0].rows
        )
        assertEquals(listOf(WidgetRow("Priya", "09:00 · 1 missed call", "9000000001")), model.sections[1].rows)
    }

    @Test
    fun `no pending callbacks gives an empty model`() {
        assertEquals(WidgetModel(total = 0, sections = emptyList()), buildWidgetModel(emptyList(), now, clock, zone, Locale.US))
    }

    @Test
    fun `tapping calls directly when allowed, otherwise opens the dialer`() {
        val call = callBackIntent("9876543210", canCall = true)
        assertEquals(Intent.ACTION_CALL, call.action)
        assertEquals("tel:9876543210", call.data.toString())

        val dial = callBackIntent("9876543210", canCall = false)
        assertEquals(Intent.ACTION_DIAL, dial.action)
        assertEquals("tel:9876543210", dial.data.toString())
    }

    private fun thread(number: String, name: String?, first: Long, last: Long, attempts: Int = 1) = CallbackThreadEntity(
        phoneNumber = number, displayName = name, firstMissedAt = first, lastMissedAt = last,
        attemptCount = attempts, status = CallbackStatus.PENDING, resolvedAt = null, resolvedReason = null
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --tests '*WidgetModelTest*' --console=plain`
Expected: compilation fails with `Unresolved reference: buildWidgetModel`, `WidgetRow` and `callBackIntent`.

- [ ] **Step 3: Implement**

In `app/src/main/java/com/shopcallback/tracker/ui/DayGrouping.kt`, add after the `DayGroup` data class:

```kotlin
/** e.g. "Today · 3". Shared by the in-app day headers and the widget. */
fun DayGroup<*>.headerText(): String = "$label · ${items.size}"
```

In `app/src/main/java/com/shopcallback/tracker/ui/DayHeader.kt`, replace `"${group.label} · ${group.items.size}",` with `group.headerText(),`.

Create `app/src/main/java/com/shopcallback/tracker/ui/CallbackText.kt`:

```kotlin
package com.shopcallback.tracker.ui

import com.shopcallback.tracker.data.CallbackThreadEntity
import java.time.ZoneId
import java.util.Locale

/** e.g. "1 missed call", or "3 missed calls since Monday" when the first miss was on an earlier day. */
fun missedCallsSummary(
    thread: CallbackThreadEntity,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val count = "${thread.attemptCount} missed call${if (thread.attemptCount == 1) "" else "s"}"
    val firstDay = dayLabel(thread.firstMissedAt, now, zone, locale)
    return if (firstDay == dayLabel(thread.lastMissedAt, now, zone, locale)) count else "$count since $firstDay"
}
```

In `app/src/main/java/com/shopcallback/tracker/ui/PendingCallbacksScreen.kt`, delete the private `missedCallsSummary` function and its KDoc. The existing call `missedCallsSummary(thread, System.currentTimeMillis())` now resolves to the shared function.

Create `app/src/main/java/com/shopcallback/tracker/widget/WidgetModel.kt`:

```kotlin
package com.shopcallback.tracker.widget

import android.content.Intent
import android.net.Uri
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.ui.groupByDayNewestFirst
import com.shopcallback.tracker.ui.headerText
import com.shopcallback.tracker.ui.missedCallsSummary
import java.time.ZoneId
import java.util.Locale

/** What the home-screen widget shows; built without any Android widget machinery so it can be tested. */
data class WidgetModel(val total: Int, val sections: List<WidgetSection>)

data class WidgetSection(val header: String, val rows: List<WidgetRow>)

data class WidgetRow(val title: String, val detail: String, val phoneNumber: String)

/** Same grouping and text as the Pending tab: by latest missed call, newest day and caller first. */
fun buildWidgetModel(
    pending: List<CallbackThreadEntity>,
    now: Long,
    formatTime: (Long) -> String,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault()
): WidgetModel = WidgetModel(
    total = pending.size,
    sections = groupByDayNewestFirst(pending, { it.lastMissedAt }, now, zone, locale).map { group ->
        WidgetSection(
            header = group.headerText(),
            rows = group.items.map { thread ->
                WidgetRow(
                    title = thread.displayName ?: thread.phoneNumber,
                    detail = "${formatTime(thread.lastMissedAt)} · ${missedCallsSummary(thread, now, zone, locale)}",
                    phoneNumber = thread.phoneNumber
                )
            }
        )
    }
)

/** Calls straight away when the app may place calls; otherwise opens the dialer so the tap still helps. */
fun callBackIntent(phoneNumber: String, canCall: Boolean): Intent =
    Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber"))
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing, including the existing `PendingCallbacksScreenTest` (the header text `"Today · 1"` is unchanged).

- [ ] **Step 5: Commit**

```bash
git add app/src
git commit -F - <<'EOF'
Add widget display model sharing the Pending tab's grouping and text

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```

---

### Task 2: Glance widget, registration and live refresh

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/widget/PendingCallbacksWidget.kt`
- Create: `app/src/main/res/xml/pending_callbacks_widget_info.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/shopcallback/tracker/service/CallWatcherService.kt`

**Interfaces:**
- Consumes from Task 1: `buildWidgetModel`, `WidgetModel`, `WidgetSection`, `WidgetRow` and `callBackIntent`.
- Produces:
  - `class PendingCallbacksWidget : GlanceAppWidget`, with `companion object { suspend fun refreshAll(context: Context) }`
  - `class PendingCallbacksWidgetReceiver : GlanceAppWidgetReceiver`

This task is UI glue with no logic of its own beyond Task 1's model, so its verification is the full test suite plus `assembleDebug`, and the on-device checklist in the spec.

- [ ] **Step 1: Add the dependency**

In `app/build.gradle.kts`, in `dependencies { … }`, after the `androidx.compose.ui:ui-tooling-preview` line, add:

```kotlin
    implementation("androidx.glance:glance-appwidget:1.1.1")
```

Run: `mise exec -- ./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. If it fails to resolve or compile, switch to `1.1.0` and note it in the report.

- [ ] **Step 2: Write the widget**

Create `app/src/main/java/com/shopcallback/tracker/widget/PendingCallbacksWidget.kt`:

```kotlin
package com.shopcallback.tracker.widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.shopcallback.tracker.MainActivity
import com.shopcallback.tracker.data.CallbackDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.Date

/** Home-screen list of pending callbacks, grouped by day like the Pending tab. Tap a row to call back. */
class PendingCallbacksWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val pending = CallbackDatabase.getInstance(context).callbackThreadDao().observePending().first()
        val timeFormat = android.text.format.DateFormat.getTimeFormat(context)
        val model = buildWidgetModel(pending, System.currentTimeMillis(), { timeFormat.format(Date(it)) })
        val canCall = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

        provideContent {
            GlanceTheme {
                Content(model, canCall)
            }
        }
    }

    @Composable
    private fun Content(model: WidgetModel, canCall: Boolean) {
        Column(
            modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(8.dp)
        ) {
            Text(
                "Pending callbacks · ${model.total}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = GlanceTheme.colors.onSurface),
                modifier = GlanceModifier.fillMaxWidth().padding(4.dp).clickable(actionStartActivity<MainActivity>())
            )
            if (model.sections.isEmpty()) {
                Text(
                    "All caught up",
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
                    modifier = GlanceModifier.padding(4.dp)
                )
            } else {
                LazyColumn {
                    model.sections.forEach { section ->
                        item {
                            Text(
                                section.header,
                                style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.primary),
                                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp)
                            )
                        }
                        items(section.rows) { row ->
                            Column(
                                modifier = GlanceModifier.fillMaxWidth().padding(4.dp)
                                    .clickable(actionStartActivity(callBackIntent(row.phoneNumber, canCall)))
                            ) {
                                Text(row.title, style = TextStyle(fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface))
                                Text(row.detail, style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "PendingCallbacksWidget"

        /** Re-renders every placed instance; a no-op when none are on the home screen. Never throws. */
        suspend fun refreshAll(context: Context) {
            try {
                PendingCallbacksWidget().updateAll(context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "widget refresh failed", e)
            }
        }
    }
}

class PendingCallbacksWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PendingCallbacksWidget()
}
```

Notes on the APIs:
- `actionStartActivity<MainActivity>()` comes from `androidx.glance.action`.
- `actionStartActivity(intent: Intent)` comes from `androidx.glance.appwidget.action`.
- Both imports are needed, and Kotlin resolves the overloads.
- If an import doesn't resolve in the Glance version used, find the equivalent in that version and note it in the report.

- [ ] **Step 3: Register the widget**

Create `app/src/main/res/xml/pending_callbacks_widget_info.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- updatePeriodMillis (30 min, Android's minimum) rolls "Today"/"Yesterday" over after midnight;
     CallWatcherService refreshes immediately whenever the pending list changes. -->
<appwidget-provider xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/widget_description"
    android:initialLayout="@layout/glance_default_loading_layout"
    android:minWidth="110dp"
    android:minHeight="110dp"
    android:minResizeWidth="110dp"
    android:minResizeHeight="110dp"
    android:resizeMode="horizontal|vertical"
    android:targetCellWidth="4"
    android:targetCellHeight="3"
    android:updatePeriodMillis="1800000"
    android:widgetCategory="home_screen" />
```

In `app/src/main/res/values/strings.xml`, add inside `<resources>`:

```xml
    <string name="widget_name">Pending callbacks</string>
    <string name="widget_description">Pending callbacks by day. Tap one to call back.</string>
```

In `app/src/main/AndroidManifest.xml`, inside `<application>` after the `BootReceiver` `<receiver>`, add:

```xml
        <receiver
            android:name=".widget.PendingCallbacksWidgetReceiver"
            android:exported="true"
            android:label="@string/widget_name">
            <intent-filter>
                <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
            </intent-filter>
            <meta-data
                android:name="android.appwidget.provider"
                android:resource="@xml/pending_callbacks_widget_info" />
        </receiver>
```

- [ ] **Step 4: Refresh on every pending-list change**

In `app/src/main/java/com/shopcallback/tracker/service/CallWatcherService.kt`, in `onCreate`, replace

```kotlin
        serviceScope.launch {
            dao().observePending().collect { pending -> updateNotification(pending.size) }
        }
```

with

```kotlin
        serviceScope.launch {
            dao().observePending().collect { pending ->
                updateNotification(pending.size)
                PendingCallbacksWidget.refreshAll(applicationContext)
            }
        }
```

Add the import `com.shopcallback.tracker.widget.PendingCallbacksWidget`.

- [ ] **Step 5: Run the full suite and build**

Run: `mise exec -- ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`, with all tests passing.

If Robolectric tests now fail because Glance's WorkManager dependency initializes at app start, do not disable tests. Report what the failure is (DONE_WITH_CONCERNS or BLOCKED) so the controller can rule on it.

Confirm the receiver made it into the merged manifest:

```bash
grep -c "PendingCallbacksWidgetReceiver" app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml
```

Expected: `1` or more. If that path doesn't exist in this AGP version, find the merged manifest under `app/build/intermediates/merged_manifest*` and report the path you used.

- [ ] **Step 6: Commit**

```bash
git add app
git commit -F - <<'EOF'
Add home-screen widget listing pending callbacks by day

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01QLr98unGVaQ2kG7Qgwh1PH
EOF
```
