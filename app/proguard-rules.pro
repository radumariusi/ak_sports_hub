# R8 pentru viteza interfeței (Compose fără R8 rulează mult mai încet pe TV). Fără obfuscare: jurnalul și
# erorile rămân lizibile. Bibliotecile (media3, Room, WorkManager, OkHttp, kotlinx.serialization) își aduc regulile.
-dontobfuscate

# Decodorul FFmpeg (Jellyfin) e încărcat prin reflecție de DefaultRenderersFactory și are metode native.
-keep class androidx.media3.decoder.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Dependențe opționale ale bibliotecilor, care nu sunt în APK.
-dontwarn com.google.re2j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
