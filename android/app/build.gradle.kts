// SpoX for Android. Version comes from the desktop Cargo.toml, so both always match.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cargoVersion: String = rootProject.file("../Cargo.toml").readLines()
    .first { it.startsWith("version = ") }.substringAfter("\"").substringBefore("\"")
val parts = cargoVersion.split(".").map { it.toIntOrNull() ?: 0 }

android {
    namespace = "io.github.oatomh.spox"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.oatomh.spox"
        minSdk = 29
        // 28 on purpose: SpoX Engine starts Linux programs it downloads (Spotify's Soloist), which
        // Android only allows for apps targeting Android 9 or older
        targetSdk = 28
        versionName = cargoVersion
        versionCode = parts[0] * 10000 + parts[1] * 100 + parts[2]
    }

    // Release key from GitHub secrets (ANDROID_KEYSTORE / ANDROID_KEY_PASSWORD). Without it the
    // debug key is used, which is fine for testing but can't update an installed release.
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/skinAssets"))
}

// the same skin as the desktop app: every PNG + skin.json from /skin
val copySkin by tasks.registering(Copy::class) {
    from(rootProject.file("../skin")) {
        include("*.png", "skin.json")
    }
    into(layout.buildDirectory.dir("generated/skinAssets/skin"))
}
tasks.named("preBuild") { dependsOn(copySkin) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media:media:1.7.0")
    // .deb packages (the Linux libraries SpoX Engine fetches) are xz-compressed
    implementation("org.tukaani:xz:1.10")
}
