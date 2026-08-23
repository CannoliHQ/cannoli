plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// kotlin.android may already be registered transitively by AGP in this setup.
// Apply it only if the kotlin extension is not already present.
if (extensions.findByName("kotlin") == null) {
    apply(plugin = "org.jetbrains.kotlin.android")
}

val retroarchDir = file("../retroarch")
val phoenixDir = file("$retroarchDir/pkg/android/phoenix")
val phoenixCommonDir = file("$retroarchDir/pkg/android/phoenix-common")

android {
    namespace = "com.retroarch"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 28
        buildConfigField("boolean", "PLAY_STORE_BUILD", "false")
        resValue("string", "app_name", "RicottaArch")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            ndkBuild { arguments("-j${Runtime.getRuntime().availableProcessors()}") }
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("$phoenixDir/AndroidManifest.xml")
            java.srcDirs(
                "$phoenixDir/src",
                "$phoenixCommonDir/src",
                "$retroarchDir/libretro-common/vfs/saf/src",
                "$retroarchDir/pkg/android/play-core-stub",
            )
            // Ricotta's own res comes last so it can add to phoenix's rather than replace it.
            res.srcDirs("$phoenixDir/res", "$phoenixCommonDir/res", "src/main/res")
            assets.srcDirs("$phoenixDir/assets")
            jniLibs.srcDir("$phoenixCommonDir/libs")
        }
    }

    buildTypes.all {
        sourceSets.getByName(name).manifest.srcFile("src/main/AndroidManifest.xml")
    }

    externalNativeBuild {
        ndkBuild { path = file("$phoenixCommonDir/jni/Android.mk") }
    }

    buildFeatures {
        buildConfig = true
        resValues = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val applyRicottaPatches by tasks.registering(Exec::class) {
    description = "Apply RetroArch patches and copy the Ricotta native bridge."
    group = "build setup"
    workingDir = rootDir
    commandLine("bash", file("../scripts/apply-patches.sh").absolutePath)
}

tasks.named("preBuild") {
    dependsOn(applyRicottaPatches)
}

dependencies {
    implementation(project(":cannoli-igm"))
    implementation(project(":cannoli-core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
}