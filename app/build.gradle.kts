plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.driveone.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.driveone.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }}