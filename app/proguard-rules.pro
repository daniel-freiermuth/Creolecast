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
# ---- BouncyCastle (AirPlay 2 pairing/crypto, FairPlay SAP) ----
# Only the lightweight API (org.bouncycastle.crypto.*) is used, by direct reference;
# nothing registers or looks up bcprov as a JCA provider (RAOP uses the platform
# providers). This whole-package keep is conservative: narrowing it lets R8 shrink
# bcprov, but must be checked with an AirPlay 2 pairing on a release build.
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

# ---- JmDNS (RaopDiscovery.kt) ----
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
