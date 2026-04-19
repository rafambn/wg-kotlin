import java.io.File
import java.util.Properties
import org.gradle.api.file.DuplicatesStrategy
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

application {
    mainClass.set("com.rafambn.wgkotlin.daemon.DaemonMainKt")
}

fun File.isJavaHome(): Boolean = resolve("bin/java").isFile || resolve("bin/java.exe").isFile

fun File.nativeImageExecutable(): File? {
    val unixBinary = resolve("bin/native-image")
    if (unixBinary.isFile) return unixBinary

    val windowsBinary = resolve("bin/native-image.cmd")
    if (windowsBinary.isFile) return windowsBinary

    return null
}

fun File.releaseProperties(): Properties {
    val properties = Properties()
    val releaseFile = resolve("release")
    if (!releaseFile.isFile) return properties

    releaseFile.inputStream().use(properties::load)
    return properties
}

fun File.isNativeImageCapableJava25Home(): Boolean {
    if (!isJavaHome()) return false
    if (nativeImageExecutable() == null) return false

    val javaVersion = releaseProperties().getProperty("JAVA_VERSION")
        ?.trim('"')
        ?: return false

    return javaVersion.startsWith("25")
}

fun findCandidateJavaInstallations(root: File): List<File> {
    if (!root.exists()) return emptyList()

    return buildList {
        if (root.isDirectory && root.isJavaHome()) {
            add(root)
        }

        root.listFiles()
            ?.filter(File::isDirectory)
            ?.filter { it.isJavaHome() }
            ?.sortedBy(File::getName)
            ?.let(::addAll)
    }
}

fun findPreferredGraalVm25Installations(userHome: File): List<File> {
    val userPreferredCandidates = findCandidateJavaInstallations(userHome.resolve("jdks"))
    val userLegacyCandidates = findCandidateJavaInstallations(userHome.resolve(".jdks"))

    return (userPreferredCandidates + userLegacyCandidates)
        .filter { it.isNativeImageCapableJava25Home() }
        .distinctBy(File::getAbsolutePath)
}

fun selectedWintunResourceName(osName: String, archName: String): String? {
    val normalizedOs = osName.lowercase()
    if (!normalizedOs.contains("win")) return null

    val normalizedArch = archName.lowercase()
    return when {
        normalizedArch.contains("amd64") || normalizedArch.contains("x86_64") -> "wintun-x64.dll"
        normalizedArch.contains("aarch64") || normalizedArch.contains("arm64") -> "wintun-arm64.dll"
        normalizedArch.contains("x86") || normalizedArch.contains("i386") || normalizedArch.contains("i486") ||
            normalizedArch.contains("i586") || normalizedArch.contains("i686") -> "wintun-x86.dll"
        else -> error("Unsupported Windows architecture for WinTUN resource packaging: $archName")
    }
}

val preferredGraalVm25Installations = findPreferredGraalVm25Installations(
    userHome = File(System.getProperty("user.home")),
)

fun majorJavaVersion(javaHome: File): Int {
    val javaVersion = javaHome.releaseProperties().getProperty("JAVA_VERSION")
        ?.trim('"')
        ?.takeWhile { it.isDigit() || it == '.' }
        ?.substringBefore('.')
        ?: return 25

    return javaVersion.toIntOrNull() ?: 25
}

fun preferredJavaExecutable(javaHome: File): File {
    val windowsExecutable = javaHome.resolve("bin/java.exe")
    if (windowsExecutable.isFile) return windowsExecutable

    return javaHome.resolve("bin/java")
}

fun staticJavaLauncher(javaHome: File): JavaLauncher {
    val releaseProperties = javaHome.releaseProperties()
    val installationPath: Directory = layout.dir(providers.provider { javaHome }).get()
    val executablePath: RegularFile = layout.file(providers.provider { preferredJavaExecutable(javaHome) }).get()
    val languageVersion = JavaLanguageVersion.of(majorJavaVersion(javaHome))
    val runtimeVersion = releaseProperties.getProperty("JAVA_RUNTIME_VERSION")
        ?.trim('"')
        ?: releaseProperties.getProperty("JAVA_VERSION")?.trim('"').orEmpty()
    val vendor = releaseProperties.getProperty("IMPLEMENTOR")
        ?.trim('"')
        ?: releaseProperties.getProperty("IMPLEMENTOR_VERSION")?.trim('"').orEmpty()

    val metadata = object : JavaInstallationMetadata {
        override fun getLanguageVersion(): JavaLanguageVersion = languageVersion

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

val fallbackGraalVm25Launcher = javaToolchains.launcherFor {
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
            mainClass.set("com.rafambn.wgkotlin.daemon.DaemonMainKt")
            if (preferredGraalVm25Installations.isNotEmpty()) {
                javaLauncher.set(providers.provider { staticJavaLauncher(preferredGraalVm25Installations.first()) })
            } else {
                javaLauncher.set(fallbackGraalVm25Launcher)
            }
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

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    val selectedWintunResource = selectedWintunResourceName(
        osName = System.getProperty("os.name"),
        archName = System.getProperty("os.arch"),
    )

    filesMatching("wintun/*.dll") {
        if (selectedWintunResource == null || name != selectedWintunResource) {
            exclude()
        }
    }
}
