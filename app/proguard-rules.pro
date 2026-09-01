# Keep kotlinx.serialization generated classes
-keepclassmembers class **$Companion { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(); }
# Hilt/Room defaults are pulled in via proguard-android-optimize
