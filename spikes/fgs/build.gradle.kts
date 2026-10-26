plugins {
    alias(libs.plugins.android.application)
}

// Throwaway spike 8 harness (dataSync foreground service timeout and resume). Never shipped; no third-party dependencies.
// Permissions live in src/debug/AndroidManifest.xml (ADR-016).
android {
    namespace = "app.recovery.placeholder.spike.fgs"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.recovery.placeholder.spike.fgs"
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
