plugins {
    application
}

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://redirector.kotlinlang.org/maven/kxrpc-grpc")
}

dependencies {
    implementation("com.rafambn:wg-kotlin:0.5.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

application {
    mainClass.set("ConsumerSmokeTest")
}

tasks.named<JavaExec>("run") {
    systemProperty("verifyAllNativeRuntimes", providers.gradleProperty("verifyAllNativeRuntimes").getOrElse("false"))
}
