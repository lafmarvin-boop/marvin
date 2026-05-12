# Keep Kotlinx Serialization metadata
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keep,includedescriptorclasses class com.marvin.budget.**$$serializer { *; }
-keepclassmembers class com.marvin.budget.** {
    *** Companion;
}
-keepclasseswithmembers class com.marvin.budget.** {
    kotlinx.serialization.KSerializer serializer(...);
}
