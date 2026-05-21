import gobley.gradle.GobleyHost
import gobley.gradle.cargo.dsl.jvm

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
    alias(libs.plugins.atomicfu)
    alias(libs.plugins.maven.publish)
}

uniffi {
    generateFromLibrary {
        namespace = "wg_boringtun"
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
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    coordinates(
        groupId = "com.rafambn",
        artifactId = "wg-kotlin-uniffi-boringtun",
        version = project.version.toString(),
    )
}
