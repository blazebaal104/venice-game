plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blazebaal.venice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blazebaal.venice"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Fixed key committed to the repo so every build can be installed over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("venice-debug.keystore")
            storePassword = "venice"
            keyAlias = "venice"
            keyPassword = "venice"
        }
    }

    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // keep the map/mesh assets uncompressed-friendly
    androidResources { noCompress += listOf("map", "mesh") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

// No dependencies: the game uses only the Android platform and the Kotlin standard library.
