# kotlinx.serialization: keep generated serializers for the core DTOs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.claudeforwatch.** {
    *** Companion;
}
-keepclasseswithmembers class com.claudeforwatch.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp ships its own consumer rules; zxing is reflection-free.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
