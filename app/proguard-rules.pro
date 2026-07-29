-keep class com.voidedit.** { *; }

# AndroidBridge adalah inner class MainActivity, jadi nama "com.voidedit.AndroidBridge"
# yang dipakai sebelumnya tidak pernah cocok dengan apa pun. Aturan berbasis anotasi
# di bawah aman untuk semua nama kelas dan tidak akan basi kalau bridge dipindah.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.voidedit.MainActivity$AndroidBridge { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn sun.security.x509.**
-dontwarn org.bouncycastle.**

# androidx.security-crypto (Tink) — dipakai EncryptedSharedPreferences
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
