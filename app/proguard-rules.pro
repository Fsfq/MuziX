# R8 Optimization & Minification Rules for Release

# 1. Strip all android.util.Log calls in release builds (zero logcat overhead & smaller APK)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int println(...);
}

# 2. Chaquopy & Python reflection requirements
-keep class com.chaquo.python.** { *; }
-keep interface com.example.ui.ProgressCallback { *; }
-keepclassmembers class * implements com.example.ui.ProgressCallback { *; }
-keep class com.example.ui.ProgressCallback$* { *; }

# 3. Room Database & Data Models
-keep class * extends androidx.room.RoomDatabase
-keep class com.example.data.** { *; }
-dontwarn androidx.room.**

# 4. Entry Points
-keep class com.example.MusicApplication { *; }
-keep class com.example.MainActivity { *; }
-keep class com.example.audio.MusicPlaybackService { *; }

# 5. R8 Full Mode Optimizations
-allowaccessmodification
-repackageclasses ''
