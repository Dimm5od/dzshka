# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.ilya.dzshka.**$$serializer { *; }
-keepclassmembers class com.ilya.dzshka.** { *** Companion; }
-keepclasseswithmembers class com.ilya.dzshka.** { kotlinx.serialization.KSerializer serializer(...); }
# okhttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
