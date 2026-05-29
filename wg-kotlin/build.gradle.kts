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
        commonMain.dependencies {
            implementation(project(":wg-kotlin-uniffi-boringtun"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.network)
            implementation(libs.ktor.io)
            implementation(libs.kotlinx.rpc.grpc.core)
            implementation(libs.kotlinx.rpc.protobuf.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmMain.dependencies {
            implementation(libs.kotlinx.rpc.grpc.client)
            implementation(libs.kotlinx.rpc.grpc.krpc.client)
            implementation(libs.kotlinx.rpc.grpc.krpc.serialization.protobuf)
            implementation(libs.kotlinx.rpc.grpc.krpc.ktor.client)
            implementation(libs.ktor.client.cio)
            implementation(libs.ktor.client.websockets)
            implementation(libs.grpc.netty.shaded)
            implementation(libs.koin.core)
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(libs.kotlinx.rpc.grpc.krpc.server)
            implementation(libs.kotlinx.rpc.grpc.krpc.serialization.protobuf)
            implementation(libs.kotlinx.rpc.grpc.krpc.ktor.server)
            implementation(libs.ktor.server.netty)
            implementation(libs.ktor.server.websockets)
        }
    }

}

rpc {
    protoc()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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
