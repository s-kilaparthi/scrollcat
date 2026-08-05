# ScrollCat ProGuard Rules

# Strip Log.* calls from release builds (defense in depth — Logcat must not
# retain message bodies, API key material, or provider response payloads).
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# Keep all app classes
-keep class com.skilaparthi.scrollcat.** { *; }

# Crashlytics: retain line numbers so uploaded mappings yield readable stack traces.
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Crashlytics references android.os.ProfilingTrigger (API 36) behind a runtime
# version check; it is absent from compileSdk 35 but present on API 36 devices.
-dontwarn android.os.ProfilingTrigger$Builder
-dontwarn android.os.ProfilingTrigger

# Google ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ML Kit Smart Reply
-keep class com.google.android.gms.internal.mlkit_smart_reply.** { *; }

# ML Kit Translate
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }

# ML Kit GenAI
-keep class com.google.mlkit.genai.** { *; }
-dontwarn com.google.mlkit.genai.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Security Crypto (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }

# Google Play Billing
-keep class com.android.billingclient.** { *; }
-dontwarn com.android.billingclient.**

# Google Play Review
-keep class com.google.android.play.core.review.** { *; }

# JSON
-keep class org.json.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# Android Accessibility
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

# Keep notification action classes
-keep class android.app.Notification$Action { *; }
-keep class android.app.RemoteInput { *; }

# Prevent stripping of overlay service
-keep class * extends android.app.Service { *; }

# Keep activity classes
-keep class * extends android.app.Activity { *; }

# Preserve all JNI native method entry points
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep LiteRT-LM classes and their members completely intact — this library's native
# code performs JNI lookups by exact method/class name (SamplerConfig, ThinkingConfig,
# SessionConfig, etc.) with no null-check before invoking, so R8 renaming/stripping
# anything here causes a fatal native SIGABRT crash. This package is small (mostly JNI
# wrappers) so keeping it fully adds negligible APK size.
-keep class com.google.ai.edge.litertlm.** {
    *;
}

-keepattributes Signature, InnerClasses, EnclosingMethod
