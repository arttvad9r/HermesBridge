plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningVariableNames = listOf(
    "HERMES_BRIDGE_SIGNING_STORE_FILE",
    "HERMES_BRIDGE_SIGNING_STORE_PASSWORD",
    "HERMES_BRIDGE_SIGNING_KEY_ALIAS",
    "HERMES_BRIDGE_SIGNING_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariableNames.associateWith { name ->
    System.getenv(name)?.takeIf { it.isNotBlank() }
}
val configuredReleaseSigningValues = releaseSigningValues.values.count { it != null }
check(configuredReleaseSigningValues == 0 || configuredReleaseSigningValues == releaseSigningVariableNames.size) {
    "Release signing is partially configured. Set all of: ${releaseSigningVariableNames.joinToString()}"
}
val releaseSigningEnabled = configuredReleaseSigningValues == releaseSigningVariableNames.size

android {
    namespace = "io.github.arttvad9r.hermesbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.arttvad9r.hermesbridge"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val relayWsUrl = providers.gradleProperty("HERMES_BRIDGE_RELAY_WS_URL")
            .orElse("wss://bridge.invalid/ws/device")
            .get()
        buildConfigField("String", "RELAY_WS_URL", "\"$relayWsUrl\"")
    }

    signingConfigs {
        if (releaseSigningEnabled) {
            create("release") {
                storeFile = file(checkNotNull(releaseSigningValues["HERMES_BRIDGE_SIGNING_STORE_FILE"]))
                storePassword = checkNotNull(releaseSigningValues["HERMES_BRIDGE_SIGNING_STORE_PASSWORD"])
                keyAlias = checkNotNull(releaseSigningValues["HERMES_BRIDGE_SIGNING_KEY_ALIAS"])
                keyPassword = checkNotNull(releaseSigningValues["HERMES_BRIDGE_SIGNING_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/INDEX.LIST"
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.documentfile:documentfile:1.1.0")

    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    implementation("io.ktor:ktor-client-core:3.1.2")
    implementation("io.ktor:ktor-client-cio:3.1.2")
    implementation("io.ktor:ktor-client-websockets:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
