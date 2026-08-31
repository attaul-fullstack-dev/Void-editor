plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.voidedit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voidedit"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // Signing hanya didaftarkan bila SEMUA variabel env tersedia dan keystore benar-benar
    // ada. Versi lama selalu membuat config dengan password "" sehingga build release
    // tanpa secret menghasilkan APK yang gagal dipasang, tanpa pesan error yang jelas.
    val keystorePath = System.getenv("KEYSTORE_PATH")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keystoreAlias = System.getenv("KEY_ALIAS")
    val keystoreKeyPassword = System.getenv("KEY_PASSWORD")
    val releaseSigningReady = !keystorePath.isNullOrBlank() &&
        !keystorePassword.isNullOrBlank() &&
        !keystoreAlias.isNullOrBlank() &&
        !keystoreKeyPassword.isNullOrBlank() &&
        file(keystorePath).exists()

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    } else {
        logger.lifecycle("VoidEdit: keystore tidak lengkap — build release TIDAK ditandatangani.")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    // Fitur A: password koneksi SFTP disimpan terenkripsi (AES-256 + Android Keystore).
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.hierynomus:sshj:0.38.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15on")
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
    }
    implementation("org.bouncycastle:bcprov-jdk18on:1.75")
}
