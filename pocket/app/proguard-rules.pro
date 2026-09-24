# kotlinx.serialization: keep generated serializers of @Serializable classes.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class com.pocketide.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.pocketide.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pocketide.**$$serializer { *; }

# BouncyCastle is used through its lightweight API only; its JCA provider and optional
# integrations reference classes Android does not have.
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.ec.rfc7748.** { *; }

# JGit: optional integrations (SSH, GPG, JMX, servlet) are not used and are not on Android.
-dontwarn org.eclipse.jgit.**
-dontwarn org.slf4j.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-keep class org.eclipse.jgit.** { *; }

# OkHttp ships its own consumer rules; these cover optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
