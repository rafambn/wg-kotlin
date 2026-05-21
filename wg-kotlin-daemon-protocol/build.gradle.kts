plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.rpc.core)
    implementation(libs.kotlinx.serialization.protobuf)

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    coordinates(
        groupId = "com.rafambn",
        artifactId = "wg-kotlin-daemon-protocol",
        version = project.version.toString(),
    )
}
