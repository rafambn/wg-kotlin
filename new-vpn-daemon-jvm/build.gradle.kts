plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.rafambn.kmpvpn.daemon.DaemonApplicationKt")
}

dependencies {
    implementation(project(":new-vpn-daemon-protocol"))
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
