plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
    alias(libs.plugins.gobley.cargo).apply(false)
    alias(libs.plugins.graalvmNative).apply(false)
}

val ciWgKotlinCore = tasks.register("ciWgKotlinCore") {
    group = "verification"
    description = "CI entry task for :wg-kotlin."
    dependsOn(":wg-kotlin:check")
}

val ciWgKotlinDaemonProtocol = tasks.register("ciWgKotlinDaemonProtocol") {
    group = "verification"
    description = "CI entry task for :wg-kotlin-daemon-protocol."
    dependsOn(":wg-kotlin-daemon-protocol:check")
}

val ciWgKotlinDaemonJvm = tasks.register("ciWgKotlinDaemonJvm") {
    group = "verification"
    description = "CI entry task for :wg-kotlin-daemon-jvm."
    dependsOn(":wg-kotlin-daemon-jvm:check")
}
