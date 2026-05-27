plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlinx.rpc)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(17)
    sourceSets.named("main") {
        kotlin.srcDir("build/generated/source/proto/main/grpckt")
    }
}

sourceSets {
    named("main") {
        proto {
            srcDir("../daemon-protocol")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.75.0"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().configureEach {
            plugins {
                create("grpc")
                create("grpckt")
            }
        }
    }
}

dependencies {
    implementation(libs.kotlinx.rpc.core)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(libs.protobuf.kotlin)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.kotlin.stub)
    compileOnly(libs.javax.annotation.api)

    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = false)
    coordinates(
        groupId = "com.rafambn",
        artifactId = "wg-kotlin-daemon-protocol",
        version = project.version.toString(),
    )
}
