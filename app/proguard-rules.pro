# Sprich proguard
-keep class com.sprich.app.speech.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.sprich.app.models.manager.** { *; }
-keep class com.sprich.app.diagnostics.** { *; }
# keep jni
-keepclasseswithmembernames class * {
    native <methods>;
}
# Serialization keep
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keep class com.sprich.app.models.manager.ModelManifest { *; }
-keep class com.sprich.app.models.manager.ModelEntry { *; }
-keep class com.sprich.app.vocab.** { *; }
-keepclassmembers,allowobfuscation class com.sprich.app.models.manager.** {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers,allowobfuscation class com.sprich.app.vocab.** {
    @kotlinx.serialization.Serializable <fields>;
}
# Strip Log in release (R8 will remove Log calls)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
-dontwarn org.bouncycastle.**
