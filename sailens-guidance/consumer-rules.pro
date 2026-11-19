# Native methods are bound by JNI_OnLoad/RegisterNatives
# (sailens-guidance/src/main/cpp/sailens_guidance_jni.cpp), which looks the classes up with
# FindClass and matches each method by name and JNI signature. The library exports no
# Java_<mangled> symbols, so an obfuscated class or method name has no fallback binding:
# registration fails and System.loadLibrary throws. Keep the names.
-keepclasseswithmembernames,includedescriptorclasses class com.sailens.guidance.kernel.** {
    native <methods>;
}

# Registration also needs this class in hosts whose composition never reaches the world kernel.
-keep,includedescriptorclasses class com.sailens.guidance.kernel.NativeWorldGrid {
    native <methods>;
}

# NativeGuidanceLibrary owns System.loadLibrary + the availability flag; keep it intact.
-keep class com.sailens.guidance.kernel.NativeGuidanceLibrary { *; }
