# MediaPipe — keep all task classes
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Hilt
-keepclasseswithmembers class * { @dagger.hilt.android.lifecycle.HiltViewModel <init>(...); }
-keep @dagger.hilt.android.HiltAndroidApp class *

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Moshi JSON
-keepclassmembers class ** { @com.squareup.moshi.FromJson <methods>; @com.squareup.moshi.ToJson <methods>; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
