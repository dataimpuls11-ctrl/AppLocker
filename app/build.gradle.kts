plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.applocker"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.applocker"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "2.4"
    }
    // Same signing key for every build, so a new APK installs OVER the old app and keeps all its data/timers
    signingConfigs {
        create("fixed") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        release { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
