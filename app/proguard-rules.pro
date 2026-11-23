# ProGuard / R8 rules for Pace

# Preserve OkHttp optional platform/annotation warnings so R8 release builds succeed cleanly.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Strip verbose and debug logging calls in release builds to shrink DEX size.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Preserve source file and line numbers for readable stack traces if needed.
-keepattributes SourceFile,LineNumberTable
