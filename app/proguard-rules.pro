# Keep kotlinx.serialization generated classes
-keepclassmembers class **$Companion { *; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(); }
# Hilt/Room defaults are pulled in via proguard-android-optimize

# Stage 9 (v1.1.2 fix): the vendored AARs ship no usable consumer rules —
# sherpa-onnx's proguard.txt is empty, tesseract4android has none. R8 renaming
# these JNI classes makes the native libs fail to bind (RegisterNatives /
# FindClass by hardcoded name) → hard crash on first synthesis/OCR in RELEASE
# builds only (debug is unminified — this is why the crash never showed in tests).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.googlecode.tesseract.** { *; }
-keep class com.googlecode.leptonica.** { *; }
