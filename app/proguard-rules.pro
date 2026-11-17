# JNI: the Rust engine looks these up by name.
-keep class com.gitpush.app.core.NativeBridge { *; }
-keep interface com.gitpush.app.core.ProgressListener { *; }
-keepclasseswithmembernames class * { native <methods>; }
# kotlinx.serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.gitpush.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.gitpush.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
