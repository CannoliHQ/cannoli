plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

if (extensions.findByName("kotlin") == null) {
    apply(plugin = "org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.cannoli.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    buildFeatures {
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
}

// Crowdin can export an untranslated entry as an empty <string></string>. Android treats an
// empty value as a real (blank) value and does NOT fall back to the base language, so the UI
// shows a blank. Strip empty string resources before the build so those keys fall back to English.
val stripEmptyStringResources = tasks.register("stripEmptyStringResources") {
    val resDir = file("src/main/res")
    doLast {
        val emptyString = Regex("""[ \t]*<string name="[^"]+"(?:></string>|\s*/>)[ \t]*\r?\n""")
        resDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("values-") }?.forEach { dir ->
            val xml = dir.resolve("strings.xml")
            if (xml.exists()) {
                val original = xml.readText()
                val cleaned = original.replace(emptyString, "")
                if (cleaned != original) xml.writeText(cleaned)
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(stripEmptyStringResources) }
