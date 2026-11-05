import kotlinx.rpc.protoc.proto

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc.grpc)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.atomicfu)
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
                implementation(project(":wg-kotlin-uniffi-boringtun"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.ktor.network)
                implementation(libs.ktor.io)
                implementation(libs.kotlinx.rpc.grpc.core)
                implementation(libs.kotlinx.rpc.protobuf.core)
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
    inputs.dir(rootProject.layout.projectDirectory.dir("wg-kotlin-daemon-rust/scribe-rs/src"))
    inputs.files(
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/daemon/Cargo.toml"),
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/daemon-proto/Cargo.toml"),
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/daemon-proto/build.rs"),
        rootProject.layout.projectDirectory.file("wg-kotlin-daemon-rust/scribe-rs/Cargo.toml"),
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

//Publishing your Kotlin Multiplatform library to Maven Central
//https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-publish-libraries.html
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    coordinates(groupId = "com.rafambn", artifactId = "wg-kotlin", version = project.version.toString())

    pom {
        name = "wg-kotlin"
        description = "Kotlin Multiplatform WireGuard implementation."
        url = "https://github.com/rafambn/wg-kotlin"

//        licenses {
//            license {
//                name = "MIT"
//                url = "https://opensource.org/licenses/MIT"
//            }
//        }

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
        }
    }
    if (project.hasProperty("signing.keyId")) signAllPublications()
}
