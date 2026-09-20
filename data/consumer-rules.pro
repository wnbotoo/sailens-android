# Native methods are bound by JNI_OnLoad/RegisterNatives (data/src/main/cpp/sailens_ml_jni.cpp),
# which looks the classes up with FindClass and matches each method by name and JNI signature.
# The library exports no Java_<mangled> symbols, so an obfuscated class or method name has no
# fallback binding: registration fails and System.loadLibrary throws. Keep the names.
# (proguard-android-optimize.txt already keeps native methods globally; these rules are explicit,
# scoped insurance.)
-keepclasseswithmembernames,includedescriptorclasses class com.sailens.data.source.ml.** {
    native <methods>;
}

# NativeMlLibrary owns System.loadLibrary + the availability flag; keep it intact.
-keep class com.sailens.data.source.ml.NativeMlLibrary { *; }
