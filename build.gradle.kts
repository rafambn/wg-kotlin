import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.multiplatform).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.maven.publish).apply(false)
    alias(libs.plugins.gobley.cargo).apply(false)
}

fun normalizeProjectPath(path: String): String {
    return if (path.startsWith(":")) path else ":$path"
}

fun collectResolvedProjectDependencies(projectPath: String, configurationNames: List<String>): List<String> {
    val targetProject = project(projectPath)
    val normalizedProjectPath = normalizeProjectPath(projectPath)

    return configurationNames
        .map { configurationName ->
            targetProject.configurations.findByName(configurationName)
                ?: throw GradleException(
                    "Architecture rule misconfigured: $normalizedProjectPath does not define configuration '$configurationName'."
                )
        }
        .onEach { configuration ->
            if (!configuration.isCanBeResolved) {
                throw GradleException(
                    "Architecture rule misconfigured: $normalizedProjectPath configuration '${configuration.name}' must be resolvable."
                )
            }
        }
        .flatMap { configuration ->
            configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                (component.id as? ProjectComponentIdentifier)?.projectPath
            }
        }
        .filterNot { dependencyPath -> dependencyPath == normalizedProjectPath }
        .distinct()
        .sorted()
}

abstract class ArchitectureBoundaryCheckTask : DefaultTask() {
    @get:Input
    abstract val coreProjectDependencies: ListProperty<String>

    @get:Input
    abstract val daemonJvmProjectDependencies: ListProperty<String>

    @get:Input
    abstract val daemonClientProjectDependencies: ListProperty<String>

    @TaskAction
    fun verify() {
        val coreDependencies = coreProjectDependencies.get().toSet()
        if (":new-vpn-daemon-jvm" in coreDependencies) {
            throw GradleException("Architecture rule violated: :new-vpn must not depend on :new-vpn-daemon-jvm.")
        }

        val daemonJvmDependencies = daemonJvmProjectDependencies.get().toSet()
        if (":new-vpn-daemon-protocol" !in daemonJvmDependencies) {
            throw GradleException("Architecture rule violated: :new-vpn-daemon-jvm must depend on :new-vpn-daemon-protocol.")
        }
        val daemonJvmUnexpected = daemonJvmDependencies - setOf(":new-vpn-daemon-protocol")
        if (daemonJvmUnexpected.isNotEmpty()) {
            throw GradleException(
                "Architecture rule violated: :new-vpn-daemon-jvm has unexpected project dependencies: $daemonJvmUnexpected"
            )
        }

        val daemonClientDependencies = daemonClientProjectDependencies.get().toSet()
        if (":new-vpn-daemon-protocol" !in daemonClientDependencies) {
            throw GradleException(
                "Architecture rule violated: :new-vpn-daemon-client-jvm must depend on :new-vpn-daemon-protocol."
            )
        }
        val daemonClientUnexpected = daemonClientDependencies - setOf(":new-vpn-daemon-protocol")
        if (daemonClientUnexpected.isNotEmpty()) {
            throw GradleException(
                "Architecture rule violated: :new-vpn-daemon-client-jvm has unexpected project dependencies: $daemonClientUnexpected"
            )
        }
    }
}

val checkArchitectureBoundaries = tasks.register<ArchitectureBoundaryCheckTask>("checkArchitectureBoundaries") {
    group = "verification"
    description = "Enforces phase 01 module dependency boundaries."
}

val newVpnMainClasspathConfigurations = listOf("jvmCompileClasspath", "jvmRuntimeClasspath")
val jvmMainClasspathConfigurations = listOf("compileClasspath", "runtimeClasspath")

gradle.projectsEvaluated {
    checkArchitectureBoundaries.configure {
        coreProjectDependencies.set(
            providers.provider {
                collectResolvedProjectDependencies(":new-vpn", newVpnMainClasspathConfigurations)
            }
        )
        daemonJvmProjectDependencies.set(
            providers.provider {
                collectResolvedProjectDependencies(":new-vpn-daemon-jvm", jvmMainClasspathConfigurations)
            }
        )
        daemonClientProjectDependencies.set(
            providers.provider {
                collectResolvedProjectDependencies(":new-vpn-daemon-client-jvm", jvmMainClasspathConfigurations)
            }
        )
    }
}

val ciNewVpnCore = tasks.register("ciNewVpnCore") {
    group = "verification"
    description = "CI entry task for :new-vpn."
    dependsOn(":new-vpn:check")
}

val ciNewVpnDaemonProtocol = tasks.register("ciNewVpnDaemonProtocol") {
    group = "verification"
    description = "CI entry task for :new-vpn-daemon-protocol."
    dependsOn(":new-vpn-daemon-protocol:check")
}

val ciNewVpnDaemonJvm = tasks.register("ciNewVpnDaemonJvm") {
    group = "verification"
    description = "CI entry task for :new-vpn-daemon-jvm."
    dependsOn(":new-vpn-daemon-jvm:check")
}

val ciNewVpnDaemonClientJvm = tasks.register("ciNewVpnDaemonClientJvm") {
    group = "verification"
    description = "CI entry task for :new-vpn-daemon-client-jvm."
    dependsOn(":new-vpn-daemon-client-jvm:check")
}

tasks.register("ciPhase01") {
    group = "verification"
    description = "Aggregate CI entry task for phase 01 scaffolding."
    dependsOn(
        checkArchitectureBoundaries,
        ciNewVpnCore,
        ciNewVpnDaemonProtocol,
        ciNewVpnDaemonJvm,
        ciNewVpnDaemonClientJvm
    )
}
