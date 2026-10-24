plugins {
    alias(libs.plugins.android.application)
}

// Throwaway spike 1 harness (MediaStore enumeration + SHA-256). Never shipped; no third-party dependencies.
android {
    namespace = "app.recovery.placeholder.spike.mediastore"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.recovery.placeholder.spike.mediastore"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
