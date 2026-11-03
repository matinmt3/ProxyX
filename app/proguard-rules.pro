# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# TDLib's JSONJava bridge resolves these exact class and method names through JNI.
-keep class org.drinkless.tdlib.JsonClient { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# WorkManager 2.9 creates input mergers reflectively; its consumer rules keep
# their classes but R8 full mode also needs the no-argument constructors.
-keep class * extends androidx.work.InputMerger {
    public <init>();
}

# OkHttp probes these optional JVM TLS providers reflectively. Android uses its platform TLS
# provider, so the provider-specific classes are intentionally absent from the APK.
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
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
