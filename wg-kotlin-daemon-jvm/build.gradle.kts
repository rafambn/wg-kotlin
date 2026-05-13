import java.io.File
import java.util.Properties
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaInstallationMetadata
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JvmVendorSpec

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

val daemonMainClass = "com.rafambn.wgkotlin.daemon.DaemonMainKt"

application {
    mainClass.set(daemonMainClass)
}

fun File.releaseProperties(): Properties =
    Properties().also { properties ->
        resolve("release")
            .takeIf(File::isFile)
            ?.inputStream()
            ?.use(properties::load)
    }

fun File.javaExecutable(): File =
    resolve("bin/java.exe").takeIf(File::isFile) ?: resolve("bin/java")

fun File.nativeImageExecutable(): File? =
    resolve("bin/native-image").takeIf(File::isFile)
        ?: resolve("bin/native-image.cmd").takeIf(File::isFile)

fun File.isLocalGraalVm25Home(): Boolean =
    isDirectory &&
        name.contains("graalvm", ignoreCase = true) &&
        nativeImageExecutable() != null &&
        releaseProperties().getProperty("JAVA_VERSION")?.trim('"')?.startsWith("25") == true

fun findLocalGraalVm25Home(userHome: File): File? =
    sequenceOf("GRAALVM_HOME", "JAVA_HOME")
        .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank)?.let(::File) }
        .filter { it.isLocalGraalVm25Home() }
        .firstOrNull()
        ?: sequenceOf(userHome.resolve(".jdks"), userHome.resolve("jdks"))
            .filter(File::isDirectory)
            .flatMap { root ->
                sequenceOf(root) + root.listFiles().orEmpty().asSequence()
            }
            .filter { it.isLocalGraalVm25Home() }
            .distinctBy(File::getAbsolutePath)
            .sortedBy(File::getName)
            .firstOrNull()

fun staticJavaLauncher(javaHome: File): JavaLauncher {
    val releaseProperties = javaHome.releaseProperties()
    val installationPath: Directory = layout.dir(providers.provider { javaHome }).get()
    val executablePath: RegularFile = layout.file(providers.provider { javaHome.javaExecutable() }).get()
    val runtimeVersion = releaseProperties.getProperty("JAVA_RUNTIME_VERSION")
        ?.trim('"')
        ?: releaseProperties.getProperty("JAVA_VERSION")?.trim('"').orEmpty()
    val vendor = releaseProperties.getProperty("IMPLEMENTOR")
        ?.trim('"')
        ?: releaseProperties.getProperty("IMPLEMENTOR_VERSION")?.trim('"').orEmpty()

    val metadata = object : JavaInstallationMetadata {
        override fun getLanguageVersion(): JavaLanguageVersion = JavaLanguageVersion.of(25)

        override fun getJavaRuntimeVersion(): String = runtimeVersion

        override fun getJvmVersion(): String = runtimeVersion

        override fun getVendor(): String = vendor

        override fun getInstallationPath(): Directory = installationPath

        override fun isCurrentJvm(): Boolean = false
    }

    return object : JavaLauncher {
        override fun getMetadata(): JavaInstallationMetadata = metadata

        override fun getExecutablePath(): RegularFile = executablePath
    }
}

val localGraalVm25Home = findLocalGraalVm25Home(File(System.getProperty("user.home")))

val graalVm25Launcher = localGraalVm25Home?.let { javaHome ->
    providers.provider { staticJavaLauncher(javaHome) }
} ?: javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
    nativeImageCapable.set(true)
    vendor.set(JvmVendorSpec.GRAAL_VM)
}

graalvmNative {
    metadataRepository {
        enabled.set(false)
    }

    binaries {
        named("main") {
            imageName.set("vpn-daemon")
            mainClass.set(daemonMainClass)
            javaLauncher.set(graalVm25Launcher)
            resources.autodetect()
            buildArgs.addAll(
                "-H:+ReportExceptionStackTraces",
                "--enable-native-access=ALL-UNNAMED",
                "--initialize-at-run-time=io.netty,org.slf4j,ch.qos.logback",
            )
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
