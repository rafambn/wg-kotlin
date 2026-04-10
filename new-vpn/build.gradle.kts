import gobley.gradle.GobleyHost
import gobley.gradle.cargo.dsl.jvm

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
    alias(libs.plugins.atomicfu)
}

uniffi {
    generateFromLibrary {
        namespace = "new_vpn"
        build.set(GobleyHost.current.rustTarget)
    }
}

cargo {
    builds.jvm {
        embedRustLibrary.set(rustTarget == GobleyHost.current.rustTarget)
    }
}

tasks.configureEach {
    val hostOs = System.getProperty("os.name").lowercase()
    val hostArch = System.getProperty("os.arch").lowercase()
    val hostDesktopTarget = when {
        hostOs.contains("linux") && (hostArch.contains("aarch64") || hostArch.contains("arm64")) -> "LinuxArm64"
        hostOs.contains("linux") -> "LinuxX64"
        hostOs.contains("windows") -> "MinGWX64"
        hostOs.contains("mac") && (hostArch.contains("aarch64") || hostArch.contains("arm64")) -> "MacosArm64"
        hostOs.contains("mac") -> "MacosX64"
        else -> null
    }

    val desktopTargets = listOf("LinuxX64", "LinuxArm64", "MinGWX64", "MacosX64", "MacosArm64")
    val taskDesktopTarget = desktopTargets.firstOrNull { name.contains(it) }
    val isNonHostDesktopTarget = taskDesktopTarget != null &&
        (hostDesktopTarget == null || taskDesktopTarget != hostDesktopTarget)
    val isUnsupportedAppleTargetOnHost = !hostOs.contains("mac") && name.contains("Ios")
    val isRustArtifactTask = name.startsWith("cargoBuild") ||
        name.startsWith("cargoCheck") ||
        name.startsWith("findDynamicLibraries") ||
        name.startsWith("jarJvmRustRuntime") ||
        name.startsWith("rustUpTargetAdd")

    if ((isNonHostDesktopTarget || isUnsupportedAppleTargetOnHost) && isRustArtifactTask) {
        enabled = false
    }
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.network)
            implementation(libs.ktor.io)
            implementation(libs.koin.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(project(":new-vpn-daemon-protocol"))
            implementation(project(":new-vpn-daemon-client-jvm"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(libs.kotlinx.rpc.krpc.server)
            implementation(libs.kotlinx.rpc.krpc.serialization.json)
            implementation(libs.kotlinx.rpc.krpc.ktor.server)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kmpvpn.platform.interface.mode", "in-memory")
    systemProperty("kmpvpn.platform.runtime.mode", "disabled")
}

//Publishing your Kotlin Multiplatform library to Maven Central
//https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-publish-libraries.html
mavenPublishing {
    publishToMavenCentral()
    coordinates("com.rafambn", "new-vpn", "1.0.0")

    pom {
        name = "New VPN"
        description = "Kotlin Multiplatform library"
        url = "github url" //todo

        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/licenses/MIT"
            }
        }

        developers {
            developer {
                id = "" //todo
                name = "" //todo
                email = "" //todo
            }
        }

        scm {
            url = "github url" //todo
        }
    }
    if (project.hasProperty("signing.keyId")) signAllPublications()
}
