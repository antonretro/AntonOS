import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: the key lives outside the repo. Point RL_SIGNING at a keystore.properties
// (storeFile, storePassword, keyAlias, keyPassword); without it the release build is debug-signed.
val signingFile = file(System.getenv("RL_SIGNING") ?: "D:/Moved from C/RetroLauncher-signing/keystore.properties")
val signingProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }

android {
    namespace = "com.antonretro.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.antonretro.launcher"
        minSdk = 25   // Android 7.1 (the box); the native visualizer library needs 25
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = file(signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { checkReleaseBuilds = false }
}

dependencies {
    // the built-in player for TV, radio and videos (HLS streams from the channel list)
    // Anthony's Retro Visualizer engine, native (C++ via JNI; armeabi-v7a + arm64-v8a), drawn in Now Playing
    implementation(files("libs/retro-visualizer-0.1.1.aar"))
    implementation("com.google.zxing:core:3.5.3")   // the phone remote's QR code
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")   // some channels in the list are DASH (.mpd)
}
