plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
}

kotlin {
    jvmToolchain(17)
    explicitApiWarning()
}

dependencies {
    implementation(libs.kotlinx.rpc.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}
