# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.kevinushey.quell.**$$serializer { *; }
-keepclassmembers class com.kevinushey.quell.** { *** Companion; }
-keepclasseswithmembers class com.kevinushey.quell.** { kotlinx.serialization.KSerializer serializer(...); }
