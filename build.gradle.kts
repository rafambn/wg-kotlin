plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
    alias(libs.plugins.gobley.cargo).apply(false)
    alias(libs.plugins.graalvmNative).apply(false)
    alias(libs.plugins.protobuf).apply(false)
}

allprojects {
    group = "com.rafambn"
    version = "0.4.0"
}
