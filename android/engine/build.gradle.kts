// "SpoX Engine": test app to see whether Spotify's official Soloist (Linux phone build) can run on
// Android, so SpoX could play music without the Spotify app. Separate from SpoX so an experiment
// can never break the normal app.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.oatomh.spox.engine"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.oatomh.spox.engine"
        minSdk = 29
        // 28 on purpose: Android only lets apps that target 28 or lower run a program they
        // downloaded themselves (the same trick Termux uses)
        targetSdk = 28
        versionName = "0.6"
        versionCode = 6
    }

    val ks = System.getenv("SPOX_KEYSTORE_FILE")
    signingConfigs {
        if (ks != null && file(ks).exists()) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("SPOX_KEY_PASSWORD")
                keyAlias = "spox"
                keyPassword = System.getenv("SPOX_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // .deb packages are xz-compressed
    implementation("org.tukaani:xz:1.10")
}
