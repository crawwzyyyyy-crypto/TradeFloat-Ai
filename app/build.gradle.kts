plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Keep your existing namespace, SDK settings, and other configuration.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
