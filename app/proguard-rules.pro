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
-dontwarn io.ktor.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
-keep class io.ktor.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# --- androidx.security.crypto pulls in Tink, which references compile-only
#     com.google.errorprone annotations. They aren't on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
