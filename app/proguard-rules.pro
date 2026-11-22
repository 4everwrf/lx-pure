# QuickJS native wrapper JNI callbacks
-keep class com.whl.quickjs.** { *; }
-keep class com.lxpure.music.core.userapi.** { *; }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.lxpure.music.domain.model.**$$serializer { *; }
-keepclassmembers class com.lxpure.music.domain.model.** { *** Companion; }
-keepclasseswithmembers class com.lxpure.music.domain.model.** { kotlinx.serialization.KSerializer serializer(...); }
