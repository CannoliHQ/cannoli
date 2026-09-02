import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

fun git(vararg args: String): String = try {
    providers.exec { commandLine(listOf("git") + args) }.standardOutput.asText.get().trim()
} catch (_: Exception) { "" }

val gitCommitHash: String = git("rev-parse", "--short", "HEAD").ifEmpty { "unknown" }

// Tracked modifications only, matching `git describe --dirty`, so untracked scratch files in a
// dev tree do not permanently brand every build.
val gitDirty: Boolean = git("status", "--porcelain", "--untracked-files=no").isNotEmpty()

val buildTimeMillis: Long = System.currentTimeMillis()

// Reads the cfg directory inside obtain() rather than at plain configuration time, so the
// configuration cache tracks these files as an input instead of freezing the digest -- mirrors
// how git() above wraps process output for the same reason.
abstract class AutoconfigDigestValueSource : ValueSource<String, AutoconfigDigestValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val cfgDir: DirectoryProperty
    }

    override fun obtain(): String {
        val dir = parameters.cfgDir.get().asFile
        val cfgs = dir.listFiles { f: java.io.File -> f.isFile && f.extension.equals("cfg", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        require(cfgs.isNotEmpty()) {
            "No bundled autoconfig cfgs found in $dir. Run scripts/fetch-autoconfig.sh first."
        }
        val md = MessageDigest.getInstance("SHA-256")
        for (f in cfgs) {
            md.update(f.name.toByteArray())
            md.update(f.readBytes())
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}

// Version code cannot invalidate the autoconfig seed: it is 1 for every debug build, so a rebuild
// with changed cfgs would keep serving the previously seeded set. Digest the content instead.
val autoconfigDigest: String = providers.of(AutoconfigDigestValueSource::class) {
    parameters.cfgDir.set(layout.projectDirectory.dir("src/main/assets/autoconfig/cannoli"))
}.get()

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

        buildConfigField("long", "BUILD_TIME", "${buildTimeMillis}L")
        buildConfigField("String", "GIT_HASH", "\"$gitCommitHash\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("String", "AUTOCONFIG_DIGEST", "\"$autoconfigDigest\"")
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
            // Its own package so a dev build sits beside an installed release instead of
            // replacing it. Both still read the same Cannoli directory on the SD card.
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "DEV_BUILD", "true")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "DEV_BUILD", "false")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debug, but not debuggable, so ART compiles it ahead of time and launch timings are real.
        // Turning that flag off also turns off BuildConfig.DEBUG, which is why the developer
        // surface asks DEV_BUILD instead: this is still a build only we run.
        create("profiling") {
            initWith(getByName("debug"))
            isDebuggable = false
            buildConfigField("boolean", "DEV_BUILD", "true")
            matchingFallbacks += listOf("debug")
        }
    }

    // initWith copies the package suffix but not the source set, so profiling would install
    // over the debug build under the release name.
    sourceSets.getByName("profiling").res.srcDir("src/debug/res")

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
    // Sideloaded APKs never get the profile handed to the installer, so it is applied at runtime.
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

// Lint-vital runs for any non-debuggable variant and is most of this one's build time.
tasks.matching { it.name.startsWith("lintVital") && it.name.endsWith("Profiling") }
    .configureEach { enabled = false }
