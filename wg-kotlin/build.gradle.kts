import gobley.gradle.GobleyHost
import gobley.gradle.cargo.dsl.jvm
import kotlinx.rpc.protoc.proto
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc.grpc)
    alias(libs.plugins.gobley.cargo)
    alias(libs.plugins.gobley.uniffi)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.atomicfu)
}

val nativeRuntimeDirectory = providers.gradleProperty("nativeRuntimeDirectory")
val hostRuntimeJar = objects.fileProperty()

uniffi {
    generateFromLibrary {
        namespace = "wg_boringtun"
        build.set(GobleyHost.current.rustTarget)
    }
}

cargo {
    packageDirectory.set(rootProject.layout.projectDirectory.dir("boringtun"))
    publishJvmArtifacts.set(false)
    builds.jvm {
        embedRustLibrary.set(rustTarget == GobleyHost.current.rustTarget)
        if (rustTarget == GobleyHost.current.rustTarget) {
            hostRuntimeJar.set(release.jarTaskProvider.flatMap { it.archiveFile })
        }
    }
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain {
            proto {
                setSrcDirs(listOf(rootProject.layout.projectDirectory.dir("daemon-protocol")))
            }

            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.network)
                implementation(libs.ktor.io)
                implementation(libs.kotlinx.rpc.grpc.core)
                api(libs.kotlinx.rpc.protobuf)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.junixsocket.core)
            implementation(libs.jna.platform)
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
        }
    }

}

tasks.named<Jar>("jvmJar") {
    if (nativeRuntimeDirectory.isPresent) {
        from(nativeRuntimeDirectory.get())
    } else {
        from(zipTree(hostRuntimeJar)) {
            exclude("META-INF/**")
        }
    }
}

val verifyNativeRuntimes by tasks.registering {
    val runtimeDirectory = nativeRuntimeDirectory.orNull?.let { file(it) }
    val runtimePaths = listOf(
        "darwin-aarch64/libwg_kotlin_uniffi_boringtun.dylib",
        "darwin-x86-64/libwg_kotlin_uniffi_boringtun.dylib",
        "linux-aarch64/libwg_kotlin_uniffi_boringtun.so",
        "linux-x86-64/libwg_kotlin_uniffi_boringtun.so",
        "win32-x86-64/wg_kotlin_uniffi_boringtun.dll",
    )
    val releaseTag = providers.environmentVariable("RELEASE_TAG").orElse("")
    val publicationVersion = project.version.toString()
    inputs.property("releaseTag", releaseTag)
    inputs.property("publicationVersion", publicationVersion)
    if (runtimeDirectory != null) inputs.dir(runtimeDirectory)
    doLast {
        check(runtimeDirectory != null) {
            "Maven publishing requires -PnativeRuntimeDirectory with all supported native runtimes."
        }
        for (path in runtimePaths) {
            val runtime = runtimeDirectory.resolve(path)
            check(runtime.isFile && runtime.length() > 0) { "Missing native runtime: $runtime" }
        }
        check(releaseTag.get().isEmpty() || releaseTag.get().removePrefix("v") == publicationVersion) {
            "Release tag does not match Maven version $publicationVersion."
        }
    }
}

rpc {
    protoc()
}

val ipcFixture = rootProject.layout.projectDirectory.file(
    "wg-kotlin-daemon-rust/target/debug/examples/ipc_test_daemon" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
)
val buildIpcFixture by tasks.registering(Exec::class) {
    workingDir(rootProject.layout.projectDirectory)
    commandLine("cargo", "build", "--manifest-path", "wg-kotlin-daemon-rust/daemon/Cargo.toml", "--example", "ipc_test_daemon")
    inputs.dir(rootProject.layout.projectDirectory.dir("wg-kotlin-daemon-rust/daemon/src"))
    inputs.dir(rootProject.layout.projectDirectory.dir("wg-kotlin-daemon-rust/daemon/examples"))
    inputs.dir(rootProject.layout.projectDirectory.dir("wg-kotlin-daemon-rust/daemon-proto/src"))
    inputs.files(
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/daemon/Cargo.toml"),
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/daemon-proto/Cargo.toml"),
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/daemon-proto/build.rs"),
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/Cargo.toml"),
    )
    inputs.dir(rootProject.layout.projectDirectory.dir("daemon-protocol"))
    inputs.file(rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/Cargo.lock"))
    outputs.file(ipcFixture)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    dependsOn(buildIpcFixture)
    systemProperty("wgkotlin.test.ipcDaemonExecutable", ipcFixture.asFile.absolutePath)
    systemProperty("wgkotlin.test.classpath", classpath.asPath)
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}

tasks.withType<PublishToMavenRepository>().configureEach {
    dependsOn(verifyNativeRuntimes)
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    coordinates(groupId = "com.rafambn", artifactId = "wg-kotlin", version = project.version.toString())

    pom {
        name = "wg-kotlin"
        description = "Kotlin Multiplatform WireGuard implementation."
        url = "https://github.com/rafambn/wg-kotlin"

        licenses {
            license {
                name = "Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }

        developers {
            developer {
                id = "rafambn"
                name = "Rafael Mendonca"
                email = "rafambn@gmail.com"
                url = "https://rafambn.com"
            }
        }

        scm {
            url = "https://github.com/rafambn/wg-kotlin"
            connection = "scm:git:https://github.com/rafambn/wg-kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com/rafambn/wg-kotlin.git"
        }
    }
}
