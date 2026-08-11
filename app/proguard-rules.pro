# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and analysis rules here.

# Keep Shizuku APIs from being obfuscated or stripped
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
