plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.tradefloat"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.tradefloat"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0-ai"
    }
    kotlinOptions { jvmTarget = "17" }
}
