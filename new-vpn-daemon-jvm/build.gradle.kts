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
    mainClass.set("com.rafambn.kmpvpn.daemon.DaemonMainKt")
}

dependencies {
    implementation(project(":new-vpn-daemon-protocol"))
    implementation(libs.clikt)
    implementation(libs.commons.exec)
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.json)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.koin.core.jvm)

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.mockk)
    testImplementation(project(":new-vpn-daemon-client-jvm"))
}

tasks.test {
    useJUnitPlatform()
}
