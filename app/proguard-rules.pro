# kotlinx.serialization — keep generated serializers and @Serializable metadata.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <methods>;
}
-if @kotlinx.serialization.Serializable class **
-keep, allowobfuscation, allowshrinking class <1>

# OkHttp / okio — platform-specific bits referenced reflectively.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
