# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# WorkManager (pulled in by Play Feature Delivery) creates its Room database reflectively:
# Room 2.5 calls WorkDatabase_Impl's no-arg constructor through Class.newInstance(). Room's own
# consumer rule keeps the class but, under R8 full mode (the AGP 9 default), not the constructor,
# so the release build crashed at startup in androidx.startup.InitializationProvider with
# "Failed to create an instance of class androidx.work.impl.WorkDatabase.canonicalName".
-keep class * extends androidx.room.RoomDatabase { <init>(); }
