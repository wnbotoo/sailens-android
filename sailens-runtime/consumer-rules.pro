# Native methods are bound by JNI_OnLoad/RegisterNatives
# (sailens-runtime/src/main/cpp/sailens_runtime_jni.cpp), which looks the class up with FindClass
# and matches each method by name and JNI signature. The library exports no Java_<mangled> symbols,
# so an obfuscated class or method name has no fallback binding: registration fails and
# System.loadLibrary throws. Keep the names.
-keepclasseswithmembernames,includedescriptorclasses class com.sailens.runtime.** {
    native <methods>;
}

# NativeRuntimeLibrary owns System.loadLibrary + the availability flag; keep it intact.
-keep class com.sailens.runtime.NativeRuntimeLibrary { *; }
