plugins {
    alias(libs.plugins.android.application)
}

// Throwaway spike 2 harness (SAF tree traversal + persisted grants). Never shipped; no permissions, no dependencies.
android {
    namespace = "app.recovery.placeholder.spike.saf"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.recovery.placeholder.spike.saf"
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
