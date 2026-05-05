# Keep Vosk JNI bindings (TDB native interface)
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }

# MediaPipe (Gemma local backend) — JNI + reflective access
-keep class com.google.mediapipe.** { *; }

# OkHttp 4.x — generally safe but warn on missing classes
-dontwarn okhttp3.**
-dontwarn okio.**

# Play Services Location
-keep class com.google.android.gms.location.** { *; }
-dontwarn com.google.android.gms.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# Notre code: les sealed classes utilisées via reflection (intents, etc.)
# sont déjà keep par Kotlin metadata. On garde juste les Compose previews.
-keep class com.marvin.assistant.MarvinApplication { *; }

# Native methods globally
-keepclasseswithmembers class * {
    native <methods>;
}

# AccessibilityService manifest binding
-keep class com.marvin.assistant.service.MarvinAccessibilityService { *; }
-keep class com.marvin.assistant.service.NotificationCaptureService { *; }
-keep class com.marvin.assistant.service.AssistantService { *; }
-keep class com.marvin.assistant.service.BootReceiver { *; }

# Compose runtime: réflection sur les composables
-keep class androidx.compose.runtime.** { *; }
-keep @androidx.compose.runtime.Composable class * { *; }

# Toutes les Activities (referencées via manifest XML)
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
