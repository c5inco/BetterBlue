# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.betterblue.**$$serializer { *; }
-keepclassmembers class com.betterblue.** {
    *** Companion;
}
-keepclasseswithmembers class com.betterblue.** {
    kotlinx.serialization.KSerializer serializer(...);
}
