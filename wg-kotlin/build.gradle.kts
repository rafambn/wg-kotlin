plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.atomicfu)
}

kotlin {
    jvmToolchain(17)

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":wg-kotlin-uniffi-boringtun"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.network)
            implementation(libs.ktor.io)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(project(":wg-kotlin-daemon-protocol"))
            implementation(libs.kotlinx.rpc.krpc.client)
            implementation(libs.kotlinx.rpc.krpc.serialization.protobuf)
            implementation(libs.kotlinx.rpc.krpc.ktor.client)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.koin.core)
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(libs.kotlinx.rpc.krpc.server)
            implementation(libs.kotlinx.rpc.krpc.serialization.protobuf)
            implementation(libs.kotlinx.rpc.krpc.ktor.server)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("wgkotlin.platform.interface.mode", "in-memory")
}

//Publishing your Kotlin Multiplatform library to Maven Central
//https://www.jetbrains.com/help/kotlin-multiplatform-dev/multiplatform-publish-libraries.html
mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    coordinates(groupId = "com.rafambn", artifactId = "wg-kotlin", version = "0.1.0")

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
