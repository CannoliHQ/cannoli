import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

fun gitCommitHash(): String = try {
    val process = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
    process.inputStream.bufferedReader().readText().trim()
} catch (_: Exception) { "unknown" }

fun buildDate(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(keystorePropertiesFile.inputStream())
}

android {
    namespace = "dev.cannoli.scorza"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "dev.cannoli.scorza"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        buildConfigField("String", "BUILD_DATE", "\"${buildDate()}\"")
        buildConfigField("String", "GIT_HASH", "\"${gitCommitHash()}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debug, minus the one flag that decides whether ART compiles the app ahead of time.
        //
        // A debuggable app is never AOT compiled, because the debugger needs code it can always
        // deoptimize, so it runs straight from the APK. Startup pays that in full: launch code runs
        // once per launch, which is too few times for the JIT to help, and a launch measured about
        // three times slower on debug than on release. Nothing else here differs from debug, so a
        // launch can be timed honestly without building a release.
        //
        // Not debuggable, so no debugger and no run-as. Libraries fall back to their debug variants
        // because debuggability is an application flag and theirs does not matter.
        create("profiling") {
            initWith(getByName("debug"))
            isDebuggable = false
            matchingFallbacks += listOf("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // The Compose/Robolectric tests never reach idle once enough other classes have run in
            // the same JVM, and every one of them then burns Espresso's full 60s timeout. No single
            // test is responsible: any half of the suite passes, unions fail. Restarting the JVM
            // periodically keeps that accumulation bounded. Full suite: 9m18s red without this, 1m
            // green with it. Raising the value trades reliability for a few seconds.
            it.forkEvery = 10
        }
    }
    lint {
        abortOnError = true
        checkDependencies = true
        fatal += "NewApi"
    }
}

dependencies {
    implementation(project(":cannoli-igm"))
    implementation(project(":cannoli-core"))
    implementation(project(":ricotta"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // Installs baseline-prof.txt at runtime. Cannoli is sideloaded, so the profile never reaches
    // the installer the way a Play install would, and without this the embedded profile is inert.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("io.legere:pdfiumandroid:1.0.35")
    implementation(libs.nanohttpd)
    implementation(libs.commons.fileupload.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("io.mockk:mockk:1.13.11")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.5.0")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// AGP runs lint-vital for any non-debuggable variant, which is most of what assembleProfiling
// costs. Release still gets it; this variant only exists to time launches.
tasks.matching { it.name.startsWith("lintVital") && it.name.endsWith("Profiling") }
    .configureEach { enabled = false }
