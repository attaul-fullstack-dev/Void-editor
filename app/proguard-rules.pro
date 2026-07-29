-keep class com.voidedit.** { *; }
-keepclassmembers class com.voidedit.AndroidBridge {
    public *;
}
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn sun.security.x509.**
-dontwarn org.bouncycastle.**
