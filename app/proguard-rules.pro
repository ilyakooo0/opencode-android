# kotlinx.serialization keeps generated serializers via the plugin; standard rules below.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class soy.iko.opencode.** {
    *** Companion;
}
-keepclasseswithmembers class soy.iko.opencode.** {
    kotlinx.serialization.KSerializer serializer(...);
}
