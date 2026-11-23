plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fergusonconsultancy.foldmotion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fergusonconsultancy.foldmotion"
        minSdk = 31  // the Kit needs Android 12; the Fold runs far newer
        targetSdk = 35
        // Each GitHub build gets a higher number so it installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // One fixed key for every build, so new APKs install over the old one (new key 23 Nov 2026).
    // The key file is encrypted; its password lives only in the cerniera-studio org secret
    // CERNIERA_SIGNING_PASSWORD, which the build workflow passes in. Without it (a local build)
    // Android's own debug key is used, and that APK will not install over the phone's copy.
    val signingPassword = System.getenv("CERNIERA_SIGNING_PASSWORD")
    signingConfigs {
        if (!signingPassword.isNullOrBlank()) getByName("debug") {
            storeFile = rootProject.file("signing/fold-motion-2026.p12")
            storeType = "pkcs12"
            storePassword = signingPassword
            keyAlias = "foldmotion"
            keyPassword = signingPassword
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
    // Fold posture (flat / half-opened): an earlier, finer closing signal than the hinge sensor
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-java:1.3.0")
    implementation("androidx.core:core:1.13.1")
    // Cerniera's Kit: palette, type, controls, the doctrine and crash reports (git submodule at kit/)
    implementation(project(":kit"))
}
