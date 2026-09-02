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
# Keep sherpa reflection (Class.forName / getMethod) — do not obfuscate JNI bridge
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.k2fsa.sherpa.onnx.SherpaOnnx { *; }
# Keep DataStore / Preferences keys
-keep class androidx.datastore.** { *; }
# Strip Log in release (all levels). Keep w/e for crash but strip d/v/i.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
# Optional: also strip verbose info in release to reduce noise (keep w/e for triage)
# -assumenosideeffects class android.util.Log {
#     public static *** i(...);
# }
-dontwarn org.bouncycastle.**
