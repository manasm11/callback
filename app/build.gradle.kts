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
        // CI (see .github/workflows/build-apk.yml) overrides these via -PappVersionCode/-PappVersionName
        // so every built APK is distinguishable; local builds fall back to these defaults.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0"
    }

    // CI signs every APK with one persistent key (from repo secrets) so each build
    // installs as an update over the previous one. Without it, each CI runner would
    // generate a fresh random debug key and Android would refuse the update.
    // Local builds, which don't set these variables, keep the default debug key.
    val sharedKeystore = System.getenv("SIGNING_KEYSTORE_PATH")?.let { file(it) }
    signingConfigs {
        if (sharedKeystore != null) {
            create("shared") {
                storeFile = sharedKeystore
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "callback"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (sharedKeystore != null) signingConfig = signingConfigs.getByName("shared")
        }
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

// FakeSyncServer (test-only) uses com.sun.net.httpserver, a standard JDK class the Android
// Gradle plugin's unit-test Kotlin compile classpath doesn't see: that classpath is restricted
// to android.jar (kotlinc runs with -no-jdk) so test code can't accidentally rely on APIs a real
// device wouldn't have, but android.jar never shipped this JDK-only package. Extract its classes
// straight from this build's own JDK (jdk.httpserver, present in every JDK 9+) into a small
// compile-only stub jar so it type-checks; the real classes are used at test run time regardless.
val jdkHttpServerStubJar = tasks.register<Jar>("jdkHttpServerStubJar") {
    val javaHome = System.getProperty("java.home")
    val extractDir = layout.buildDirectory.dir("jdkHttpServerStubExtract")
    doFirst {
        val dir = extractDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        exec {
            commandLine(
                "$javaHome/bin/jmod", "extract",
                "--dir", dir.absolutePath,
                "$javaHome/jmods/jdk.httpserver.jmod"
            )
        }
    }
    from(extractDir.map { it.dir("classes") }) {
        exclude("module-info.class")
    }
    archiveFileName.set("jdk-httpserver-stub.jar")
    destinationDirectory.set(layout.buildDirectory.dir("jdkHttpServerStub"))
}

dependencies {
    testCompileOnly(files(jdkHttpServerStubJar))

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
    testImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
