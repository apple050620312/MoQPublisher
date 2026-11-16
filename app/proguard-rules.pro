# UniFFI calls Rust via JNA reflection. Preserve native symbols, structures and callback layouts.
-keep class uniffi.moq.** { *; }
-keep class com.sun.jna.** { *; }
# JNA includes optional desktop AWT helpers, which Android does not provide or call.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

# AndroidViewModelFactory discovers this constructor through reflection.
-keepclassmembers class com.sangege.moqpublisher.MainViewModel {
    public <init>(android.app.Application);
}

# Keep useful stack traces in exported diagnostics after optimization.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepnames class com.sangege.moqpublisher.StreamState$*
