import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    kotlin("plugin.serialization") version "2.0.20"
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localProp(key: String, default: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(key) ?: default)

android {
    namespace = "com.marvin.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marvin.assistant"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Vosk model assets are large; keep arm64 only for the S24 Ultra.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField(
            "String",
            "PICOVOICE_ACCESS_KEY",
            "\"${localProp("PICOVOICE_ACCESS_KEY")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
        // Vosk JNI libs ship per-ABI; let Gradle keep them.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    // Kotlin / Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")

    // Compose UI
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Wake word: Picovoice Porcupine (free for personal use, custom "yo poto" via Console)
    implementation("ai.picovoice:porcupine-android:3.0.2")

    // Speech-to-text: Vosk (offline, French model)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    // MediaPipe LLM Inference (optional NLU fallback with Gemma 2 2B)
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // JSON for intent parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
