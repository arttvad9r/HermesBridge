plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.arttvad9r.hermesbridge.fixture"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.arttvad9r.hermesbridge.fixture"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
