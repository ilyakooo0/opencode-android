# Keep generated Crux types (serialization reflection)
-keep class soy.iko.opencode.core.** { *; }
-keep class com.novi.serde.** { *; }
-keep class com.novi.bincode.** { *; }

# Ktor
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
