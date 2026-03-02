plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.gobley.cargo)
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

    androidTarget { publishLibraryVariants("release") }
    jvm()
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

    }

    //https://kotlinlang.org/docs/native-objc-interop.html#export-of-kdoc-comments-to-generated-objective-c-headers
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations["main"].compileTaskProvider.configure {
            compilerOptions {
                freeCompilerArgs.add("-Xexport-kdoc")
            }
        }
    }

}

android {
    namespace = "com.rafambn"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }
}

//Publishing your Kotlin Multiplatform library to Maven Central
//https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-publish-libraries.html
mavenPublishing {
    publishToMavenCentral()
    coordinates("com.rafambn", "kmp-vpn", "1.0.0")

    pom {
        name = "KMP VPN"
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
