# Sherpa's Kotlin API is accessed by reflection and JNI. Preserve its names and members.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
# Kotlin serialization, DataStore and Compose ship their own consumer rules.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
# No editor, transcript, endpoint, credential or provider exception data in release logcat.
# Bounded local crash diagnostics contain exception types and frames only.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-dontwarn org.bouncycastle.**
