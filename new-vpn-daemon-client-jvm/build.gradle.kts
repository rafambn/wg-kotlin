plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":new-vpn-daemon-protocol"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.rpc.krpc.client)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.kotlinx.rpc.krpc.ktor.client)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.koin.core.jvm)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.rpc.krpc.server)
    testImplementation(libs.kotlinx.rpc.krpc.serialization.json)
    testImplementation(libs.kotlinx.rpc.krpc.ktor.server)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.server.websockets)
}

tasks.test {
    useJUnitPlatform()
}
