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
        versionCode = 3
        versionName = "1.2-ai-fix"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
