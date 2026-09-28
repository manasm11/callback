# Missed Call Callback Tracker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android app described in the spec — a per-phone app that watches the call log, tracks unresolved missed calls until an actual conversation happens, and keeps staff notified via a persistent notification with one-tap callback.

**Architecture:** A foreground service (`CallWatcherService`) observes the system Call Log via `ContentObserver`, feeds new entries into a pure detection/resolution engine (`CallLogScanner`), which updates a local Room database of "callback threads." Compose UI reads that database reactively through a `ViewModel`. No network, no backend — each phone is fully self-contained.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room 2.6.1, Kotlin Coroutines/Flow, Robolectric 4.14 for JVM unit tests, Gradle/AGP 8.5.2, Kotlin 1.9.24, JDK 17.

**Spec:** `docs/superpowers/specs/2026-09-28-missed-call-callback-tracker-design.md`

## Global Constraints

- Package name / applicationId: `com.shopcallback.tracker`
- `minSdk = 31` (Android 12), `targetSdk = 35`, `compileSdk = 35`
- Sideloaded APK only — no Play Store, so requesting `CALL_PHONE` etc. directly is fine
- No cross-phone sync, no backend, no network calls anywhere in this app
- An answered call only resolves an open thread if `durationSeconds > 1` (spec's explicit tweak — filters instant pocket-dials/drops)
- Each phone number is normalized (digits only, last 10 digits) before being used as the DB key, so formatting differences don't create duplicate customers
- "Loyal customer" tiering, spam filtering, and Play Store distribution are explicitly out of scope (see spec's Non-goals)

---

## File Structure

```
settings.gradle.kts
build.gradle.kts
gradle.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/values/strings.xml
app/src/main/res/values/styles.xml
app/src/main/java/com/shopcallback/tracker/
  CallbackTrackerApp.kt
  MainActivity.kt
  util/PhoneNumberNormalizer.kt
  util/ScanStateStore.kt
  data/CallbackThreadEntity.kt
  data/CallbackTypeConverters.kt
  data/CallbackThreadDao.kt
  data/CallbackDatabase.kt
  calllog/CallLogEntry.kt
  calllog/CallLogScanner.kt
  calllog/CallLogSource.kt
  calllog/AndroidCallLogSource.kt
  contacts/ContactLookup.kt
  notification/NotificationHelper.kt
  service/CallWatcherService.kt
  service/BootReceiver.kt
  ui/CallbackViewModel.kt
  ui/OnboardingScreen.kt
  ui/PendingCallbacksScreen.kt
  ui/HistoryScreen.kt
app/src/test/java/com/shopcallback/tracker/
  util/PhoneNumberNormalizerTest.kt
  util/ScanStateStoreTest.kt
  data/CallbackThreadDaoTest.kt
  calllog/CallLogScannerTest.kt
  notification/NotificationHelperTest.kt
  service/CallWatcherServiceTest.kt
  service/BootReceiverTest.kt
  ui/CallbackViewModelTest.kt
```

`AndroidCallLogSource` and `ContactLookup` are thin wrappers directly over Android's system Call Log and Contacts content providers. Robolectric does not reliably emulate those system-app-backed providers, so — consistent with the spec's own testing section — those two classes are verified manually on-device rather than with JVM unit tests. Everything else in the list has automated coverage.

---

### Task 1: Project scaffolding

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/styles.xml`
- Create: `app/src/main/java/com/shopcallback/tracker/CallbackTrackerApp.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/MainActivity.kt`
- Create: `.gitignore`
- Test: `app/src/test/java/com/shopcallback/tracker/SmokeTest.kt`

**Interfaces:**
- Produces: a buildable Android app module with Compose + Room + Robolectric wired up, that every later task adds files into.

**Prerequisites:** Android SDK and JDK 17 installed locally, `ANDROID_HOME` set.

- [ ] **Step 1: Create Gradle project files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "CallbackTracker"
include(":app")
```

`build.gradle.kts` (root):
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

- [ ] **Step 2: Create `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.shopcallback.tracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shopcallback.tracker"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
```

- [ ] **Step 3: Create the manifest, resources, Application class and MainActivity**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:name=".CallbackTrackerApp"
        android:allowBackup="true"
        android:icon="@android:drawable/sym_call_missed"
        android:label="@string/app_name"
        android:theme="@style/Theme.CallbackTracker">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/res/values/strings.xml`:
```xml
<resources>
    <string name="app_name">Callback Tracker</string>
</resources>
```

`app/src/main/res/values/styles.xml`:
```xml
<resources>
    <style name="Theme.CallbackTracker" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`app/src/main/java/com/shopcallback/tracker/CallbackTrackerApp.kt`:
```kotlin
package com.shopcallback.tracker

import android.app.Application

class CallbackTrackerApp : Application()
```

`app/src/main/java/com/shopcallback/tracker/MainActivity.kt`:
```kotlin
package com.shopcallback.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Text("Callback Tracker")
                }
            }
        }
    }
}
```

- [ ] **Step 4: Add `.gitignore`**

`.gitignore`:
```
build/
.gradle/
local.properties
.idea/
*.iml
captures/
.cxx/
```

- [ ] **Step 5: Write and run a smoke test**

`app/src/test/java/com/shopcallback/tracker/SmokeTest.kt`:
```kotlin
package com.shopcallback.tracker

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @Test
    fun `application context is available`() {
        val app = ApplicationProvider.getApplicationContext<CallbackTrackerApp>()
        assertNotNull(app)
    }
}
```

Run: `./gradlew :app:testDebugUnitTest --tests "com.shopcallback.tracker.SmokeTest"`
Expected: BUILD SUCCESSFUL, 1 test passed. (Run `gradle wrapper --gradle-version 8.7` first if `gradlew` doesn't exist yet.)

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties .gitignore app/
git commit -m "Scaffold Android project with Compose, Room and Robolectric"
```

---

### Task 2: Phone number normalization

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/util/PhoneNumberNormalizer.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/util/PhoneNumberNormalizerTest.kt`

**Interfaces:**
- Produces: `PhoneNumberNormalizer.normalize(rawNumber: String): String` — used by `CallLogScanner` (Task 5) and `CallWatcherService` (Task 9) as the DB key for a caller.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shopcallback.tracker.util

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberNormalizerTest {
    @Test
    fun `strips spaces and dashes`() {
        assertEquals("9876543210", PhoneNumberNormalizer.normalize("98765-43210"))
    }

    @Test
    fun `strips plus and country code`() {
        assertEquals("9876543210", PhoneNumberNormalizer.normalize("+91 98765 43210"))
    }

    @Test
    fun `formatted and plain numbers normalize the same`() {
        assertEquals(
            PhoneNumberNormalizer.normalize("+91 98765 43210"),
            PhoneNumberNormalizer.normalize("9876543210")
        )
    }

    @Test
    fun `short numbers are kept as-is`() {
        assertEquals("12345", PhoneNumberNormalizer.normalize("123-45"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PhoneNumberNormalizerTest"`
Expected: FAIL — `PhoneNumberNormalizer` unresolved reference.

- [ ] **Step 3: Implement**

```kotlin
package com.shopcallback.tracker.util

object PhoneNumberNormalizer {
    fun normalize(rawNumber: String): String {
        val digitsOnly = rawNumber.filter { it.isDigit() }
        return if (digitsOnly.length > 10) digitsOnly.takeLast(10) else digitsOnly
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.PhoneNumberNormalizerTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/util/PhoneNumberNormalizer.kt \
        app/src/test/java/com/shopcallback/tracker/util/PhoneNumberNormalizerTest.kt
git commit -m "Add phone number normalization"
```

---

### Task 3: Room data layer

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/data/CallbackThreadEntity.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/data/CallbackTypeConverters.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/data/CallbackThreadDao.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/data/CallbackDatabase.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/data/CallbackThreadDaoTest.kt`

**Interfaces:**
- Produces: `CallbackThreadEntity`, `CallbackStatus`, `ResolvedReason`, and `CallbackThreadDao` with `findByNumber`, `upsert`, `observePending`, `observeHistory`, `markResolved`, `updateDisplayNameIfMissing` — consumed by `CallLogScanner` (Task 5), `CallWatcherService` (Task 9), `CallbackViewModel` (Task 11).
- Produces: `CallbackDatabase.getInstance(context)` singleton accessor.

- [ ] **Step 1: Write the failing DAO tests**

```kotlin
package com.shopcallback.tracker.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallbackThreadDaoTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: CallbackThreadDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.callbackThreadDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and find by number`() = runBlocking {
        val thread = pendingThread("9876543210")
        dao.upsert(thread)
        assertEquals(thread, dao.findByNumber("9876543210"))
    }

    @Test
    fun `observePending excludes resolved threads`() = runBlocking {
        dao.upsert(pendingThread("111"))
        dao.upsert(
            pendingThread("222").copy(
                status = CallbackStatus.RESOLVED,
                resolvedAt = 200L,
                resolvedReason = ResolvedReason.MANUAL
            )
        )

        val pending = dao.observePending().first()
        assertEquals(listOf("111"), pending.map { it.phoneNumber })
    }

    @Test
    fun `observeHistory returns only resolved threads newest first`() = runBlocking {
        dao.upsert(pendingThread("111"))
        dao.upsert(
            pendingThread("222").copy(
                status = CallbackStatus.RESOLVED, resolvedAt = 500L, resolvedReason = ResolvedReason.MANUAL
            )
        )
        dao.upsert(
            pendingThread("333").copy(
                status = CallbackStatus.RESOLVED, resolvedAt = 900L, resolvedReason = ResolvedReason.AUTO_ANSWERED
            )
        )

        val history = dao.observeHistory().first()
        assertEquals(listOf("333", "222"), history.map { it.phoneNumber })
    }

    @Test
    fun `markResolved updates status, timestamp and reason`() = runBlocking {
        dao.upsert(pendingThread("333"))
        dao.markResolved("333", CallbackStatus.RESOLVED, 500L, ResolvedReason.AUTO_ANSWERED)

        val updated = dao.findByNumber("333")
        assertEquals(CallbackStatus.RESOLVED, updated?.status)
        assertEquals(500L, updated?.resolvedAt)
        assertEquals(ResolvedReason.AUTO_ANSWERED, updated?.resolvedReason)
    }

    @Test
    fun `updateDisplayNameIfMissing only fills a null name`() = runBlocking {
        dao.upsert(pendingThread("444"))
        dao.updateDisplayNameIfMissing("444", "Priya")
        assertEquals("Priya", dao.findByNumber("444")?.displayName)

        dao.updateDisplayNameIfMissing("444", "Someone Else")
        assertEquals("Priya", dao.findByNumber("444")?.displayName)
    }

    private fun pendingThread(number: String) = CallbackThreadEntity(
        phoneNumber = number,
        displayName = null,
        firstMissedAt = 100L,
        lastMissedAt = 100L,
        attemptCount = 1,
        status = CallbackStatus.PENDING,
        resolvedAt = null,
        resolvedReason = null
    )
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallbackThreadDaoTest"`
Expected: FAIL — unresolved references (types don't exist yet).

- [ ] **Step 3: Implement the entity, converters, DAO and database**

```kotlin
// data/CallbackThreadEntity.kt
package com.shopcallback.tracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class CallbackStatus { PENDING, RESOLVED }
enum class ResolvedReason { AUTO_ANSWERED, MANUAL }

@Entity(tableName = "callback_threads")
data class CallbackThreadEntity(
    @PrimaryKey val phoneNumber: String,
    val displayName: String?,
    val firstMissedAt: Long,
    val lastMissedAt: Long,
    val attemptCount: Int,
    val status: CallbackStatus,
    val resolvedAt: Long?,
    val resolvedReason: ResolvedReason?
)
```

```kotlin
// data/CallbackTypeConverters.kt
package com.shopcallback.tracker.data

import androidx.room.TypeConverter

class CallbackTypeConverters {
    @TypeConverter
    fun statusToString(status: CallbackStatus): String = status.name

    @TypeConverter
    fun stringToStatus(value: String): CallbackStatus = CallbackStatus.valueOf(value)

    @TypeConverter
    fun reasonToString(reason: ResolvedReason?): String? = reason?.name

    @TypeConverter
    fun stringToReason(value: String?): ResolvedReason? = value?.let { ResolvedReason.valueOf(it) }
}
```

```kotlin
// data/CallbackThreadDao.kt
package com.shopcallback.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallbackThreadDao {
    @Query("SELECT * FROM callback_threads WHERE phoneNumber = :phoneNumber")
    suspend fun findByNumber(phoneNumber: String): CallbackThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(thread: CallbackThreadEntity)

    @Query("SELECT * FROM callback_threads WHERE status = 'PENDING' ORDER BY firstMissedAt ASC")
    fun observePending(): Flow<List<CallbackThreadEntity>>

    @Query("SELECT * FROM callback_threads WHERE status = 'RESOLVED' ORDER BY resolvedAt DESC")
    fun observeHistory(): Flow<List<CallbackThreadEntity>>

    @Query(
        "UPDATE callback_threads SET status = :status, resolvedAt = :resolvedAt, resolvedReason = :reason " +
            "WHERE phoneNumber = :phoneNumber"
    )
    suspend fun markResolved(phoneNumber: String, status: CallbackStatus, resolvedAt: Long, reason: ResolvedReason)

    @Query(
        "UPDATE callback_threads SET displayName = :displayName " +
            "WHERE phoneNumber = :phoneNumber AND displayName IS NULL"
    )
    suspend fun updateDisplayNameIfMissing(phoneNumber: String, displayName: String)
}
```

```kotlin
// data/CallbackDatabase.kt
package com.shopcallback.tracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [CallbackThreadEntity::class], version = 1, exportSchema = false)
@TypeConverters(CallbackTypeConverters::class)
abstract class CallbackDatabase : RoomDatabase() {
    abstract fun callbackThreadDao(): CallbackThreadDao

    companion object {
        @Volatile private var instance: CallbackDatabase? = null

        fun getInstance(context: Context): CallbackDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallbackDatabase::class.java,
                    "callback_tracker.db"
                ).build().also { instance = it }
            }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallbackThreadDaoTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/data/ \
        app/src/test/java/com/shopcallback/tracker/data/
git commit -m "Add Room data layer for callback threads"
```

---

### Task 4: Scan state store (last-scanned timestamp)

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/util/ScanStateStore.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/util/ScanStateStoreTest.kt`

**Interfaces:**
- Produces: `ScanStateStore` interface (`getLastScannedAt`, `setLastScannedAt`) and `SharedPrefsScanStateStore` impl — consumed by `CallWatcherService` (Task 9) to know how far back to query the call log, defaulting to 30 days ago on first run (spec's backfill requirement).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shopcallback.tracker.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ScanStateStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `defaults to about 30 days ago when never set`() {
        val store = SharedPrefsScanStateStore(context)
        val expectedFloor = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30) - 5_000
        val expectedCeiling = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30) + 5_000
        val value = store.getLastScannedAt()
        assertTrue(value in expectedFloor..expectedCeiling)
    }

    @Test
    fun `returns the stored value after being set`() {
        val store = SharedPrefsScanStateStore(context)
        store.setLastScannedAt(12345L)
        assertEquals(12345L, store.getLastScannedAt())
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ScanStateStoreTest"`
Expected: FAIL — unresolved reference `SharedPrefsScanStateStore`.

- [ ] **Step 3: Implement**

```kotlin
package com.shopcallback.tracker.util

import android.content.Context
import java.util.concurrent.TimeUnit

interface ScanStateStore {
    fun getLastScannedAt(): Long
    fun setLastScannedAt(timestamp: Long)
}

class SharedPrefsScanStateStore(context: Context) : ScanStateStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getLastScannedAt(): Long {
        val stored = prefs.getLong(KEY_LAST_SCANNED_AT, NOT_SET)
        return if (stored == NOT_SET) {
            System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        } else {
            stored
        }
    }

    override fun setLastScannedAt(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SCANNED_AT, timestamp).apply()
    }

    companion object {
        private const val PREFS_NAME = "scan_state"
        private const val KEY_LAST_SCANNED_AT = "last_scanned_at"
        private const val NOT_SET = -1L
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.ScanStateStoreTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/util/ScanStateStore.kt \
        app/src/test/java/com/shopcallback/tracker/util/ScanStateStoreTest.kt
git commit -m "Add scan state store for incremental call log scanning"
```

---

### Task 5: Call log detection & resolution engine

This is the core business logic from the spec — implement it precisely against the spec's four rules.

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/calllog/CallLogEntry.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/calllog/CallLogScanner.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/calllog/CallLogScannerTest.kt`

**Interfaces:**
- Consumes: `CallbackThreadDao` (Task 3), `PhoneNumberNormalizer` (Task 2).
- Produces: `CallLogEntry`, `CallDirection`, and `CallLogScanner.applyNewEntries(entries: List<CallLogEntry>): Unit` (suspend) — consumed by `CallWatcherService` (Task 9).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shopcallback.tracker.calllog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.ResolvedReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallLogScannerTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: CallbackThreadDao
    private lateinit var scanner: CallLogScanner

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.callbackThreadDao()
        scanner = CallLogScanner(dao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `single missed call creates a pending thread`() = runBlocking {
        scanner.applyNewEntries(
            listOf(CallLogEntry("9876543210", timestamp = 1000L, durationSeconds = 0, direction = CallDirection.MISSED))
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
        assertEquals(1, thread?.attemptCount)
        assertEquals(1000L, thread?.firstMissedAt)
    }

    @Test
    fun `second missed call from same number bumps attempt count`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry("9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry("9876543210", 2000L, 0, CallDirection.MISSED)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(2, thread?.attemptCount)
        assertEquals(1000L, thread?.firstMissedAt)
        assertEquals(2000L, thread?.lastMissedAt)
    }

    @Test
    fun `answered call over 1 second after the missed call resolves it`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry("9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry("9876543210", 2000L, 15, CallDirection.OUTGOING)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
        assertEquals(ResolvedReason.AUTO_ANSWERED, thread?.resolvedReason)
        assertEquals(2000L, thread?.resolvedAt)
    }

    @Test
    fun `answered call of 1 second or less does not resolve`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry("9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry("9876543210", 2000L, 1, CallDirection.INCOMING)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
    }

    @Test
    fun `resolved thread reopens as a new episode on a later missed call`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry("9876543210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry("9876543210", 2000L, 15, CallDirection.OUTGOING),
                CallLogEntry("9876543210", 5000L, 0, CallDirection.MISSED)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
        assertEquals(1, thread?.attemptCount)
        assertEquals(5000L, thread?.firstMissedAt)
    }

    @Test
    fun `an answered call before the missed call does not resolve it`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry("9876543210", 500L, 20, CallDirection.INCOMING),
                CallLogEntry("9876543210", 1000L, 0, CallDirection.MISSED)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.PENDING, thread?.status)
    }

    @Test
    fun `numbers are normalized so formatting differences match`() = runBlocking {
        scanner.applyNewEntries(
            listOf(
                CallLogEntry("+91 98765 43210", 1000L, 0, CallDirection.MISSED),
                CallLogEntry("9876543210", 2000L, 10, CallDirection.OUTGOING)
            )
        )

        val thread = dao.findByNumber("9876543210")
        assertEquals(CallbackStatus.RESOLVED, thread?.status)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallLogScannerTest"`
Expected: FAIL — unresolved references (`CallLogEntry`, `CallDirection`, `CallLogScanner` don't exist yet).

- [ ] **Step 3: Implement**

```kotlin
// calllog/CallLogEntry.kt
package com.shopcallback.tracker.calllog

enum class CallDirection { INCOMING, OUTGOING, MISSED }

data class CallLogEntry(
    val rawNumber: String,
    val timestamp: Long,
    val durationSeconds: Int,
    val direction: CallDirection
)
```

```kotlin
// calllog/CallLogScanner.kt
package com.shopcallback.tracker.calllog

import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.ResolvedReason
import com.shopcallback.tracker.util.PhoneNumberNormalizer

class CallLogScanner(private val dao: CallbackThreadDao) {

    suspend fun applyNewEntries(entries: List<CallLogEntry>) {
        entries.sortedBy { it.timestamp }.forEach { entry ->
            when (entry.direction) {
                CallDirection.MISSED -> handleMissed(entry)
                CallDirection.INCOMING, CallDirection.OUTGOING -> handleAnsweredCandidate(entry)
            }
        }
    }

    private suspend fun handleMissed(entry: CallLogEntry) {
        val number = PhoneNumberNormalizer.normalize(entry.rawNumber)
        val existing = dao.findByNumber(number)

        val updated = if (existing != null && existing.status == CallbackStatus.PENDING) {
            existing.copy(lastMissedAt = entry.timestamp, attemptCount = existing.attemptCount + 1)
        } else {
            CallbackThreadEntity(
                phoneNumber = number,
                displayName = existing?.displayName,
                firstMissedAt = entry.timestamp,
                lastMissedAt = entry.timestamp,
                attemptCount = 1,
                status = CallbackStatus.PENDING,
                resolvedAt = null,
                resolvedReason = null
            )
        }
        dao.upsert(updated)
    }

    private suspend fun handleAnsweredCandidate(entry: CallLogEntry) {
        if (entry.durationSeconds <= 1) return

        val number = PhoneNumberNormalizer.normalize(entry.rawNumber)
        val existing = dao.findByNumber(number) ?: return

        if (existing.status == CallbackStatus.PENDING && entry.timestamp > existing.firstMissedAt) {
            dao.markResolved(
                phoneNumber = number,
                status = CallbackStatus.RESOLVED,
                resolvedAt = entry.timestamp,
                reason = ResolvedReason.AUTO_ANSWERED
            )
        }
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallLogScannerTest"`
Expected: BUILD SUCCESSFUL, 7 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/calllog/CallLogEntry.kt \
        app/src/main/java/com/shopcallback/tracker/calllog/CallLogScanner.kt \
        app/src/test/java/com/shopcallback/tracker/calllog/CallLogScannerTest.kt
git commit -m "Add call log detection and resolution engine"
```

---

### Task 6: Android call log reader

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/calllog/CallLogSource.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/calllog/AndroidCallLogSource.kt`

**Interfaces:**
- Produces: `CallLogSource` interface (`queryEntriesSince`) and `AndroidCallLogSource` impl — consumed by `CallWatcherService` (Task 9). The interface exists specifically so Task 9's tests can substitute a fake.

**Testing note:** this class is a thin wrapper directly over `android.provider.CallLog`, a content provider backed by a system app rather than AOSP framework code. Robolectric doesn't reliably emulate it, so there's no JVM unit test here — it's exercised through Task 9's fake-backed service tests for wiring, and verified for real in Task 14's on-device checklist.

- [ ] **Step 1: Implement**

```kotlin
// calllog/CallLogSource.kt
package com.shopcallback.tracker.calllog

interface CallLogSource {
    fun queryEntriesSince(timestampMillis: Long): List<CallLogEntry>
}
```

```kotlin
// calllog/AndroidCallLogSource.kt
package com.shopcallback.tracker.calllog

import android.content.ContentResolver
import android.provider.CallLog

class AndroidCallLogSource(private val contentResolver: ContentResolver) : CallLogSource {

    override fun queryEntriesSince(timestampMillis: Long): List<CallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.TYPE
        )
        val entries = mutableListOf<CallLogEntry>()

        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(timestampMillis.toString()),
            "${CallLog.Calls.DATE} ASC"
        )?.use { cursor ->
            val numberIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val dateIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val typeIdx = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)

            while (cursor.moveToNext()) {
                val direction = when (cursor.getInt(typeIdx)) {
                    CallLog.Calls.MISSED_TYPE -> CallDirection.MISSED
                    CallLog.Calls.OUTGOING_TYPE -> CallDirection.OUTGOING
                    CallLog.Calls.INCOMING_TYPE -> CallDirection.INCOMING
                    else -> null
                } ?: continue
                val number = cursor.getString(numberIdx) ?: continue

                entries.add(
                    CallLogEntry(
                        rawNumber = number,
                        timestamp = cursor.getLong(dateIdx),
                        durationSeconds = cursor.getInt(durationIdx),
                        direction = direction
                    )
                )
            }
        }
        return entries
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/calllog/CallLogSource.kt \
        app/src/main/java/com/shopcallback/tracker/calllog/AndroidCallLogSource.kt
git commit -m "Add Android call log reader"
```

---

### Task 7: Contact name lookup

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/contacts/ContactLookup.kt`

**Interfaces:**
- Produces: `ContactLookup.lookupDisplayName(normalizedNumber: String): String?` — consumed by `CallWatcherService` (Task 9) to fill in a customer's name after a missed call.

**Testing note:** same reasoning as Task 6 — `ContactsContract.PhoneLookup` depends on the real Contacts provider's phone-matching implementation, which Robolectric doesn't fully reimplement. Verified manually on-device in Task 14.

- [ ] **Step 1: Implement**

```kotlin
package com.shopcallback.tracker.contacts

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract

class ContactLookup(private val contentResolver: ContentResolver) {

    fun lookupDisplayName(normalizedNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalizedNumber)
        )
        contentResolver.query(
            uri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME)
                return cursor.getString(nameIdx)
            }
        }
        return null
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/contacts/ContactLookup.kt
git commit -m "Add contact name lookup"
```

---

### Task 8: Notification helper

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/notification/NotificationHelper.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/notification/NotificationHelperTest.kt`

**Interfaces:**
- Consumes: `MainActivity` (Task 1, already exists) for the tap target.
- Produces: `NotificationHelper.CHANNEL_ID`, `NOTIFICATION_ID`, `pendingCountText(count)`, `ensureChannel(context)`, `buildNotification(context, pendingCount)` — consumed by `CallWatcherService` (Task 9).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shopcallback.tracker.notification

import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NotificationHelperTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `zero pending shows all caught up`() {
        assertEquals("All caught up", NotificationHelper.pendingCountText(0))
    }

    @Test
    fun `singular vs plural wording`() {
        assertEquals("1 customer waiting for a callback", NotificationHelper.pendingCountText(1))
        assertEquals("3 customers waiting for a callback", NotificationHelper.pendingCountText(3))
    }

    @Test
    fun `channel is created with low importance`() {
        NotificationHelper.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun `notification is ongoing and shows the pending text`() {
        val notification = NotificationHelper.buildNotification(context, 2)
        val shadow = shadowOf(notification)
        assertEquals("2 customers waiting for a callback", shadow.contentText)
        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.NotificationHelperTest"`
Expected: FAIL — unresolved reference `NotificationHelper`.

- [ ] **Step 3: Implement**

```kotlin
package com.shopcallback.tracker.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.shopcallback.tracker.MainActivity

object NotificationHelper {
    const val CHANNEL_ID = "callback_watcher"
    const val NOTIFICATION_ID = 1

    fun pendingCountText(count: Int): String =
        if (count == 0) {
            "All caught up"
        } else {
            "$count customer${if (count == 1) "" else "s"} waiting for a callback"
        }

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Callback watcher", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun buildNotification(context: Context, pendingCount: Int): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Missed Call Callback Tracker")
            .setContentText(pendingCountText(pendingCount))
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.NotificationHelperTest"`
Expected: BUILD SUCCESSFUL, 4 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/notification/NotificationHelper.kt \
        app/src/test/java/com/shopcallback/tracker/notification/NotificationHelperTest.kt
git commit -m "Add persistent notification helper"
```

---

### Task 9: Call watcher foreground service

This wires everything built so far into the always-on watcher.

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/service/CallWatcherService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/shopcallback/tracker/service/CallWatcherServiceTest.kt`

**Interfaces:**
- Consumes: `AndroidCallLogSource`/`CallLogSource` (Task 6), `ContactLookup` (Task 7), `CallLogScanner` (Task 5), `SharedPrefsScanStateStore`/`ScanStateStore` (Task 4), `CallbackDatabase` (Task 3), `NotificationHelper` (Task 8).
- Produces: `CallWatcherService` with test seams `testCallLogSource`, `testContactLookup`, `testDispatcher`, `testDatabase` (companion vars, set before `Robolectric.buildService(...).create()` in tests) and a public `suspend fun scanOnce()` — consumed by `BootReceiver` (Task 10) which starts this service, and exercised end-to-end in Task 14's manual checklist. `testDatabase` exists because the production `CallbackDatabase.getInstance()` singleton (Task 3) has no test-friendly executors — tests substitute an in-memory Room database built with `.allowMainThreadQueries()` and a direct (same-thread) query/transaction executor, matching the pattern already used by Task 3's own DAO test and Task 11's view-model test, so Room's suspend calls stay on the same `TestCoroutineScheduler` the test drives with `advanceUntilIdle()` instead of racing against Room's real background executor.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shopcallback.tracker.service

import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.calllog.CallDirection
import com.shopcallback.tracker.calllog.CallLogEntry
import com.shopcallback.tracker.calllog.CallLogSource
import com.shopcallback.tracker.contacts.ContactLookup
import com.shopcallback.tracker.data.CallbackDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallWatcherServiceTest {

    private lateinit var testDb: CallbackDatabase

    private fun freshTestDatabase(): CallbackDatabase {
        val directExecutor = java.util.concurrent.Executor { it.run() }
        return Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
    }

    @After
    fun tearDown() {
        CallWatcherService.testCallLogSource = null
        CallWatcherService.testDispatcher = null
        CallWatcherService.testDatabase = null
        if (::testDb.isInitialized) testDb.close()
    }

    private fun fakeSource(entries: List<CallLogEntry>) = object : CallLogSource {
        override fun queryEntriesSince(timestampMillis: Long) = entries
    }

    @Test
    fun `startup scan creates a pending thread from the fake call log source`() = runTest {
        CallWatcherService.testDispatcher = StandardTestDispatcher(testScheduler)
        CallWatcherService.testCallLogSource =
            fakeSource(listOf(CallLogEntry("9876543210", System.currentTimeMillis(), 0, CallDirection.MISSED)))
        testDb = freshTestDatabase()
        CallWatcherService.testDatabase = testDb

        val service = Robolectric.buildService(CallWatcherService::class.java).create().get()
        advanceUntilIdle()

        val pending = testDb.callbackThreadDao().observePending().first()
        assertEquals(1, pending.size)
        assertEquals("9876543210", pending[0].phoneNumber)
        assertEquals(service.javaClass, CallWatcherService::class.java)
    }

    @Test
    fun `onStartCommand returns START_STICKY`() {
        CallWatcherService.testCallLogSource = fakeSource(emptyList())
        testDb = freshTestDatabase()
        CallWatcherService.testDatabase = testDb
        val service = Robolectric.buildService(CallWatcherService::class.java).create().get()

        val result = service.onStartCommand(null, 0, 0)
        assertEquals(android.app.Service.START_STICKY, result)
    }
}
```

Add `import androidx.room.Room` and `import java.util.concurrent.Executor` (used qualified above, either form is fine) to this test file's imports.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallWatcherServiceTest"`
Expected: FAIL — unresolved reference `CallWatcherService`.

- [ ] **Step 3: Implement the service**

```kotlin
package com.shopcallback.tracker.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import com.shopcallback.tracker.calllog.AndroidCallLogSource
import com.shopcallback.tracker.calllog.CallLogScanner
import com.shopcallback.tracker.calllog.CallLogSource
import com.shopcallback.tracker.contacts.ContactLookup
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.notification.NotificationHelper
import com.shopcallback.tracker.util.ScanStateStore
import com.shopcallback.tracker.util.SharedPrefsScanStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CallWatcherService : Service() {

    private val serviceScope by lazy { CoroutineScope(SupervisorJob() + (testDispatcher ?: Dispatchers.IO)) }
    private val callLogSource by lazy { testCallLogSource ?: AndroidCallLogSource(contentResolver) }
    private val contactLookup by lazy { testContactLookup ?: ContactLookup(contentResolver) }
    private val scanStateStore: ScanStateStore by lazy { SharedPrefsScanStateStore(applicationContext) }
    private val scanner by lazy { CallLogScanner(dao()) }
    private lateinit var observer: ContentObserver

    private fun database() = testDatabase ?: CallbackDatabase.getInstance(applicationContext)
    private fun dao() = database().callbackThreadDao()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(applicationContext)
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(applicationContext, 0))

        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                serviceScope.launch { scanOnce() }
            }
        }
        contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)

        serviceScope.launch { scanOnce() }
    }

    suspend fun scanOnce() {
        val since = scanStateStore.getLastScannedAt()
        val now = System.currentTimeMillis()

        val entries = callLogSource.queryEntriesSince(since)
        if (entries.isNotEmpty()) {
            scanner.applyNewEntries(entries)
        }
        scanStateStore.setLastScannedAt(now)

        resolveMissingNames()
        updateNotification()
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

    private suspend fun updateNotification() {
        val pendingCount = dao().observePending().first().size
        val notification = NotificationHelper.buildNotification(applicationContext, pendingCount)
        getSystemService(NotificationManager::class.java).notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var testCallLogSource: CallLogSource? = null
        var testContactLookup: ContactLookup? = null
        var testDispatcher: CoroutineDispatcher? = null
        var testDatabase: CallbackDatabase? = null
    }
}
```

- [ ] **Step 4: Add manifest permissions, service declaration and property**

Add inside `<manifest>`, above `<application>`:
```xml
<uses-permission android:name="android.permission.READ_CALL_LOG" />
<uses-permission android:name="android.permission.READ_PHONE_STATE" />
<uses-permission android:name="android.permission.READ_CONTACTS" />
<uses-permission android:name="android.permission.CALL_PHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

Add inside `<application>`, after the `<activity>` block:
```xml
<service
    android:name=".service.CallWatcherService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="missed-call-callback-tracking" />
</service>
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallWatcherServiceTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/service/CallWatcherService.kt \
        app/src/main/AndroidManifest.xml \
        app/src/test/java/com/shopcallback/tracker/service/CallWatcherServiceTest.kt
git commit -m "Add call watcher foreground service"
```

---

### Task 10: Boot receiver

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/service/BootReceiver.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/shopcallback/tracker/service/BootReceiverTest.kt`

**Interfaces:**
- Consumes: `CallWatcherService` (Task 9).
- Produces: `BootReceiver` — restarts the service after a reboot per the spec's reliability requirement.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shopcallback.tracker.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `boot completed starts the watcher service`() {
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val nextService = shadowOf(context).nextStartedService
        assertEquals(CallWatcherService::class.java.name, nextService?.component?.className)
    }

    @Test
    fun `other actions are ignored`() {
        BootReceiver().onReceive(context, Intent("some.other.action"))

        assertNull(shadowOf(context).nextStartedService)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BootReceiverTest"`
Expected: FAIL — unresolved reference `BootReceiver`.

- [ ] **Step 3: Implement**

```kotlin
package com.shopcallback.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, CallWatcherService::class.java))
        }
    }
}
```

- [ ] **Step 4: Add manifest permission and receiver**

Add inside `<manifest>`, alongside the other `<uses-permission>` tags:
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

Add inside `<application>`, after the `<service>` block:
```xml
<receiver
    android:name=".service.BootReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
    </intent-filter>
</receiver>
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.BootReceiverTest"`
Expected: BUILD SUCCESSFUL, 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/service/BootReceiver.kt \
        app/src/main/AndroidManifest.xml \
        app/src/test/java/com/shopcallback/tracker/service/BootReceiverTest.kt
git commit -m "Restart call watcher service after reboot"
```

---

### Task 11: Callback view model

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/ui/CallbackViewModel.kt`
- Test: `app/src/test/java/com/shopcallback/tracker/ui/CallbackViewModelTest.kt`

**Interfaces:**
- Consumes: `CallbackThreadDao` (Task 3).
- Produces: `CallbackViewModel(application, dao)`, `pendingThreads: StateFlow<List<CallbackThreadEntity>>`, `historyThreads: StateFlow<List<CallbackThreadEntity>>`, `markResolvedManually(phoneNumber)`, `callBackIntent(phoneNumber): Intent`, and `CallbackViewModel.Factory` — consumed by the UI screens (Tasks 12–13).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shopcallback.tracker.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.ResolvedReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class CallbackViewModelTest {
    private lateinit var db: CallbackDatabase
    private lateinit var dao: CallbackThreadDao
    private lateinit var viewModel: CallbackViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val directExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CallbackDatabase::class.java
        ).allowMainThreadQueries()
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .build()
        dao = db.callbackThreadDao()
        val application = ApplicationProvider.getApplicationContext<android.app.Application>()
        viewModel = CallbackViewModel(application, dao)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `pendingThreads reflects dao state`() {
        runBlocking {
            dao.upsert(thread("111", CallbackStatus.PENDING))
            dao.upsert(thread("222", CallbackStatus.RESOLVED))
        }

        val collected = mutableListOf<List<CallbackThreadEntity>>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.pendingThreads.collect { collected.add(it) }
        }

        assertEquals(listOf("111"), collected.last().map { it.phoneNumber })
        job.cancel()
    }

    @Test
    fun `callBackIntent builds ACTION_CALL with a tel uri`() {
        val intent = viewModel.callBackIntent("9876543210")
        assertEquals(android.content.Intent.ACTION_CALL, intent.action)
        assertEquals("tel:9876543210", intent.data.toString())
    }

    @Test
    fun `markResolvedManually updates the dao`() {
        runBlocking { dao.upsert(thread("333", CallbackStatus.PENDING)) }
        viewModel.markResolvedManually("333")
        val updated = runBlocking { dao.findByNumber("333") }
        assertEquals(CallbackStatus.RESOLVED, updated?.status)
        assertEquals(ResolvedReason.MANUAL, updated?.resolvedReason)
    }

    private fun thread(number: String, status: CallbackStatus) = CallbackThreadEntity(
        phoneNumber = number, displayName = null, firstMissedAt = 1L, lastMissedAt = 1L,
        attemptCount = 1, status = status,
        resolvedAt = if (status == CallbackStatus.RESOLVED) 2L else null,
        resolvedReason = if (status == CallbackStatus.RESOLVED) ResolvedReason.MANUAL else null
    )
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallbackViewModelTest"`
Expected: FAIL — unresolved reference `CallbackViewModel`.

- [ ] **Step 3: Implement**

```kotlin
package com.shopcallback.tracker.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.shopcallback.tracker.data.CallbackDatabase
import com.shopcallback.tracker.data.CallbackStatus
import com.shopcallback.tracker.data.CallbackThreadDao
import com.shopcallback.tracker.data.CallbackThreadEntity
import com.shopcallback.tracker.data.ResolvedReason
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallbackViewModel(
    application: Application,
    private val dao: CallbackThreadDao = CallbackDatabase.getInstance(application).callbackThreadDao()
) : AndroidViewModel(application) {

    val pendingThreads: StateFlow<List<CallbackThreadEntity>> = dao.observePending()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val historyThreads: StateFlow<List<CallbackThreadEntity>> = dao.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markResolvedManually(phoneNumber: String) {
        viewModelScope.launch {
            dao.markResolved(phoneNumber, CallbackStatus.RESOLVED, System.currentTimeMillis(), ResolvedReason.MANUAL)
        }
    }

    fun callBackIntent(phoneNumber: String): Intent =
        Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber"))

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CallbackViewModel(application) as T
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*.CallbackViewModelTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/ui/CallbackViewModel.kt \
        app/src/test/java/com/shopcallback/tracker/ui/CallbackViewModelTest.kt
git commit -m "Add callback view model"
```

---

### Task 12: Permissions & battery optimization onboarding screen

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/ui/OnboardingScreen.kt`

**Interfaces:**
- Produces: `@Composable fun OnboardingScreen(onAllGranted: () -> Unit)` and `fun isIgnoringBatteryOptimizations(context: Context): Boolean` — consumed by `MainActivity` (Task 14).

**Testing:** manual — this screen exercises real runtime permission dialogs and system settings, which need a device/emulator. Verified in Task 14's on-device checklist.

- [ ] **Step 1: Implement**

```kotlin
package com.shopcallback.tracker.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val REQUIRED_PERMISSIONS = buildList {
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_CONTACTS)
    add(Manifest.permission.CALL_PHONE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}

fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
fun OnboardingScreen(onAllGranted: () -> Unit) {
    val context = LocalContext.current
    var permissionsGranted by remember {
        mutableStateOf(
            REQUIRED_PERMISSIONS.all {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var batteryExempted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryExempted = isIgnoringBatteryOptimizations(context)
    }

    LaunchedEffect(permissionsGranted, batteryExempted) {
        if (permissionsGranted && batteryExempted) {
            onAllGranted()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Callback Tracker needs a few permissions to watch for missed calls.", style = MaterialTheme.typography.bodyLarge)

        if (!permissionsGranted) {
            Button(onClick = { permissionLauncher.launch(REQUIRED_PERMISSIONS.toTypedArray()) }) {
                Text("Grant call & contacts permissions")
            }
        }

        if (permissionsGranted && !batteryExempted) {
            Text("One more step: allow this app to run in the background without battery restrictions.")
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
                batteryLauncher.launch(intent)
            }) {
                Text("Disable battery optimization")
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/ui/OnboardingScreen.kt
git commit -m "Add permissions and battery optimization onboarding screen"
```

---

### Task 13: Pending callbacks & history screens

**Files:**
- Create: `app/src/main/java/com/shopcallback/tracker/ui/PendingCallbacksScreen.kt`
- Create: `app/src/main/java/com/shopcallback/tracker/ui/HistoryScreen.kt`

**Interfaces:**
- Consumes: `CallbackViewModel` (Task 11).
- Produces: `@Composable fun PendingCallbacksScreen(viewModel, onCallBack: (Intent) -> Unit)`, `@Composable fun HistoryScreen(viewModel)` — consumed by `MainActivity` (Task 14).

**Testing:** manual — verified visually in Task 14's on-device checklist, per the spec's own testing plan (no automated Compose UI tests for this internal 2-device tool).

- [ ] **Step 1: Implement the pending list**

```kotlin
package com.shopcallback.tracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shopcallback.tracker.data.CallbackThreadEntity
import java.util.concurrent.TimeUnit

@Composable
fun PendingCallbacksScreen(viewModel: CallbackViewModel, onCallBack: (android.content.Intent) -> Unit) {
    val pending by viewModel.pendingThreads.collectAsState()

    if (pending.isEmpty()) {
        Text("All caught up", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.headlineSmall)
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(pending, key = { it.phoneNumber }) { thread ->
            PendingRow(
                thread = thread,
                onCallBack = { onCallBack(viewModel.callBackIntent(thread.phoneNumber)) },
                onMarkResolved = { viewModel.markResolvedManually(thread.phoneNumber) }
            )
        }
    }
}

@Composable
private fun PendingRow(thread: CallbackThreadEntity, onCallBack: () -> Unit, onMarkResolved: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(thread.displayName ?: thread.phoneNumber, style = MaterialTheme.typography.titleMedium)
        Text("${thread.attemptCount} missed call${if (thread.attemptCount == 1) "" else "s"} · waiting ${waitingSince(thread.firstMissedAt)}")

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onCallBack) { Text("Call back") }
            OutlinedButton(onClick = onMarkResolved) { Text("Mark resolved") }
        }
    }
}

private fun waitingSince(firstMissedAt: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(System.currentTimeMillis() - firstMissedAt)
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / 1440}d"
    }
}
```

- [ ] **Step 2: Implement the history screen**

```kotlin
package com.shopcallback.tracker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shopcallback.tracker.data.ResolvedReason

@Composable
fun HistoryScreen(viewModel: CallbackViewModel) {
    val history by viewModel.historyThreads.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(history, key = { it.phoneNumber + it.resolvedAt }) { thread ->
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(thread.displayName ?: thread.phoneNumber, style = MaterialTheme.typography.titleMedium)
                val reasonText = if (thread.resolvedReason == ResolvedReason.MANUAL) "marked resolved" else "answered"
                Text("Resolved · $reasonText")
            }
        }
    }
}
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/ui/PendingCallbacksScreen.kt \
        app/src/main/java/com/shopcallback/tracker/ui/HistoryScreen.kt
git commit -m "Add pending callbacks and history screens"
```

---

### Task 14: Final wiring & on-device verification

**Files:**
- Modify: `app/src/main/java/com/shopcallback/tracker/MainActivity.kt`

**Interfaces:**
- Consumes: `OnboardingScreen` (Task 12), `PendingCallbacksScreen`/`HistoryScreen` (Task 13), `CallbackViewModel.Factory` (Task 11), `CallWatcherService` (Task 9).

- [ ] **Step 1: Wire onboarding, tabs and service startup into `MainActivity`**

```kotlin
package com.shopcallback.tracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shopcallback.tracker.service.CallWatcherService
import com.shopcallback.tracker.ui.CallbackViewModel
import com.shopcallback.tracker.ui.HistoryScreen
import com.shopcallback.tracker.ui.OnboardingScreen
import com.shopcallback.tracker.ui.PendingCallbacksScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var ready by remember { mutableIntStateOf(0) }

                    if (ready == 0) {
                        OnboardingScreen(onAllGranted = {
                            ContextCompat.startForegroundService(
                                this, Intent(this, CallWatcherService::class.java)
                            )
                            ready = 1
                        })
                    } else {
                        MainTabs()
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MainTabs() {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
    val viewModel: CallbackViewModel = viewModel(factory = CallbackViewModel.Factory(application))
    var tab by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Pending") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("History") })
        }
        if (tab == 0) {
            PendingCallbacksScreen(viewModel) { intent -> context.startActivity(intent) }
        } else {
            HistoryScreen(viewModel)
        }
    }
}
```

- [ ] **Step 2: Build the full app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK produced at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests from Tasks 2–11 pass.

- [ ] **Step 4: Manual on-device verification checklist**

Install on a real device or emulator: `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Using a second phone to place test calls, verify:

- Onboarding requests all permissions, then the battery-optimization prompt, then lands on the Pending tab.
- Miss a call from the second phone → within a few seconds, the persistent notification updates to "1 customer waiting for a callback" and the Pending tab shows one row with attempt count 1.
- Miss a second call from the same number → attempt count becomes 2, `firstMissedAt` (and thus "waiting since") unchanged.
- Call that number back from the app's **Call back** button, let the call connect and talk for a couple of seconds, hang up → the thread disappears from Pending and appears in History as "answered"; notification count drops.
- Miss a call, then immediately hang up on it in under 1 second when calling back → thread stays Pending (not resolved).
- Miss a call, tap **Mark resolved** manually → thread moves to History as "marked resolved" without any callback happening.
- Reboot the device → the persistent notification reappears without opening the app, and a call missed while rebooting is picked up on the next scan.
- Toggle battery optimization back on for the app in system settings → confirm notification and detection still keep working for at least a few minutes (documents real-world risk rather than gating release, per the spec's reliability section).

Fix anything that deviates from the spec before considering the app done for shop use.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/shopcallback/tracker/MainActivity.kt
git commit -m "Wire onboarding, tabs and service startup into MainActivity"
```
