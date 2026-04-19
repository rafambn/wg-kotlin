plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    alias(libs.plugins.graalvmNative)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.rafambn.wgkotlin.daemon.DaemonMainKt")
}

graalvmNative {
    toolchainDetection.set(false)

    binaries {
        named("main") {
            imageName.set("vpn-daemon")
            mainClass.set("com.rafambn.wgkotlin.daemon.DaemonMainKt")
            resources.autodetect()
            buildArgs.add("-H:+ReportExceptionStackTraces")
        }
    }
}

dependencies {
    implementation(project(":wg-kotlin-daemon-protocol"))
    implementation(project(":wg-kotlin"))
    implementation(project(":wg-kotlin-uniffi-tun-rs"))
    implementation(libs.clikt)
    implementation(libs.commons.exec)
    implementation(libs.kotlinx.rpc.krpc.server)
    implementation(libs.kotlinx.rpc.krpc.serialization.protobuf)
    implementation(libs.kotlinx.rpc.krpc.ktor.server)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.koin.core.jvm)
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.rpc.krpc.client)
    testImplementation(libs.kotlinx.rpc.krpc.serialization.protobuf)
    testImplementation(libs.kotlinx.rpc.krpc.ktor.client)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}

tasks.test {
    useJUnitPlatform()
}
