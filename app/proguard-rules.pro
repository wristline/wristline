# kotlinx.serialization
# The library ships generic R8 rules; these keep the generated serializers of this app's
# @Serializable classes, which R8 full mode would otherwise strip or rename.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses

-keep,includedescriptorclasses class dev.wristline.watch.**$$serializer { *; }
-keepclassmembers class dev.wristline.watch.** {
    *** Companion;
}
-keepclasseswithmembers class dev.wristline.watch.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep the Companion field and serializer() of every @Serializable class (full mode).
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-dontnote kotlinx.serialization.**
