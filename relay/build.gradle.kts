plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

val kotlinxSerializationVersion = providers.gradleProperty("kotlinxSerializationVersion").get()

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("io.github.arttvad9r.hermesbridge.relay.RelayServerKt")
}

dependencies {
    implementation(project(":protocol"))
    implementation("io.ktor:ktor-server-core:3.1.2")
    implementation("io.ktor:ktor-server-netty:3.1.2")
    implementation("io.ktor:ktor-server-websockets:3.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$kotlinxSerializationVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.ktor:ktor-server-test-host:3.1.2")
    testImplementation("io.ktor:ktor-client-websockets:3.1.2")
}
