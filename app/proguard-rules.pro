-keep class com.voidedit.** { *; }
-keepclassmembers class com.voidedit.AndroidBridge {
    public *;
}
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn sun.security.x509.**
-dontwarn org.bouncycastle.**
