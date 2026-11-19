# Native methods are bound by JNI_OnLoad/RegisterNatives
# (sailens-vision/src/main/cpp/sailens_vision_jni.cpp), which looks the classes up with FindClass
# and matches each method by name and JNI signature. The library exports no Java_<mangled> symbols,
# so an obfuscated class or method name has no fallback binding: registration fails and
# System.loadLibrary throws. Keep the names.
-keepclasseswithmembernames,includedescriptorclasses class com.sailens.vision.** {
    native <methods>;
}

# NativeVisionLibrary owns System.loadLibrary + the availability flag; keep it intact.
-keep class com.sailens.vision.NativeVisionLibrary { *; }

# The zero-copy vision runners look up this Kotlin JNI handle accessor by its JVM name.
-keep,allowoptimization class com.google.ai.edge.litert.JniHandle {
    long getHandle$third_party_odml_litert_litert_kotlin_litert_api_for_maven();
}
