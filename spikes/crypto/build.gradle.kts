plugins {
    alias(libs.plugins.android.application)
}

// Throwaway spike 3 harness (Tink streaming AEAD + Argon2id). Never shipped; see docs/PHASE0.md.
android {
    namespace = "app.recovery.placeholder.spike.crypto"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.recovery.placeholder.spike.crypto"
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

dependencies {
    implementation(libs.tink.android)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.argon2kt)
}
