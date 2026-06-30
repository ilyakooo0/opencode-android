# --- kotlinx.serialization ---
# The plugin generates serializers; keep them and the $$serializer companion members.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions
-dontnote kotlinx.serialization.**
-keepclassmembers class soy.iko.opencode.** {
    *** Companion;
}
-keepclasseswithmembers class soy.iko.opencode.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep @Serializable classes themselves so their generated serializers can reference them.
-keep,includedescriptorclasses class soy.iko.opencode.**$$serializer { *; }
-keepclassmembers class soy.iko.opencode.** {
    *** Companion;
}
# Keep the serializable model classes (reflection-free serializer lookup needs the class).
-keep @kotlinx.serialization.Serializable class soy.iko.opencode.data.model.** { *; }

# --- Ktor / OkHttp (HTTP client + SSE) ---
# Don't warn about missing optional dependencies; keep only the classes that are
# accessed via reflection (Ktor engine registration, OkHttp internals that R8
# would otherwise strip). Narrow keeps instead of `-keep class io.ktor.** { *; }`
# so R8 can still eliminate dead code and shrink the APK.
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-dontwarn org.jetbrains.annotations.**
# Ktor loads engines and plugins via ServiceLoader — keep the service files.
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.client.plugins.** { *; }
-keep class io.ktor.serialization.** { *; }
-keep class io.ktor.http.** { *; }
# OkHttp uses reflection for platform detection and internal classes.
-keep class okhttp3.internal.platform.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# --- Coil (image loading) ---
# Coil uses reflection for loadable decoders and the Compose integration.
-keep class coil.** { *; }
-keep class coil3.** { *; }
-dontwarn coil.**

# --- androidx.security.crypto pulls in Tink, which references compile-only
#     com.google.errorprone annotations. They aren't on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
