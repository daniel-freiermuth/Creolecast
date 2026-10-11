# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep line number information for readable crash stack traces, but hide the
# original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# ---- BouncyCastle (AirPlay 2 pairing/crypto, RAOP encryption) ----
# JCE providers and algorithms are looked up by string name via
# Security.addProvider()/Cipher.getInstance(...), not by a direct reference
# R8 can trace - keep the provider whole.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ---- Ktor / OkHttp / Okio (AudioCastService's HTTP/WebSocket client) ----
-dontwarn io.ktor.**
-keepclassmembers class io.ktor.** { volatile <fields>; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ---- kotlinx.serialization (@Serializable classes, e.g. TrackMetadata) ----
-keepattributes *Annotation*, InnerClasses
-keepclasseswithmembers class com.creolecast.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.creolecast.app.**$$serializer { *; }
-keepclassmembers class com.creolecast.app.** {
    *** Companion;
}
