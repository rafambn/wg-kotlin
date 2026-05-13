package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.CommandFailed
import com.rafambn.wgkotlin.daemon.command.CommonsExecProcessLauncher
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.TunHandle
import java.util.Locale
import org.slf4j.LoggerFactory

internal interface PlatformAdapter {
    val platformId: String
    val requiredBinaries: Set<CommandBinary>

    suspend fun startSession(config: TunSessionConfig): TunHandle
}

internal object PlatformAdapterFactory {
    fun fromOs(
        osName: String = System.getProperty("os.name"),
        processLauncher: ProcessLauncher = CommonsExecProcessLauncher(),
    ): PlatformAdapter {
        val normalized = osName.lowercase(Locale.ROOT)
        return when {
            arrayOf("mac", "darwin").any { normalized.contains(it) } -> MacOsPlatformAdapter(processLauncher)
            normalized.contains("win") -> WindowsPlatformAdapter(processLauncher)
            normalized.contains("linux") -> LinuxPlatformAdapter(processLauncher)
            else -> throw IllegalStateException("Unsupported OS: $osName")
        }
    }
}

internal abstract class BasePlatformAdapter(
    protected val processLauncher: ProcessLauncher,
) : PlatformAdapter {
    protected val logger = LoggerFactory.getLogger(javaClass)

    protected fun runCommand(
        operationLabel: String,
        binary: CommandBinary,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
        acceptedExitCodes: Set<Int> = setOf(0),
    ) {
        val output = processLauncher.run(
            ProcessInvocationModel(
                binary = binary,
                arguments = arguments,
                stdin = stdin,
                environment = environment,
            ),
        )
        if (output.exitCode !in acceptedExitCodes) {
            throw CommandFailed(
                operationLabel = operationLabel,
                exitCode = output.exitCode,
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
    }

    protected fun runCleanup(
        operationLabel: String,
        primaryFailure: Throwable,
        cleanup: () -> Unit,
    ) {
        runCatching(cleanup).onFailure { cleanupFailure ->
            primaryFailure.addSuppressed(cleanupFailure)
            logger.warn("Cleanup `$operationLabel` failed", cleanupFailure)
        }
    }
}

internal enum class IpFamily {
    IPV4,
    IPV6,
}

internal data class PrimaryTunAddress(
    val address: String,
    val prefixLength: UByte,
    val family: IpFamily,
)

internal fun extractPrimaryTunAddress(config: TunSessionConfig): PrimaryTunAddress {
    return normalizeCidrs(config.addresses)
        .mapNotNull(::parsePrimaryTunAddress)
        .firstOrNull()
        ?: throw IllegalArgumentException("Tun session requires at least one IPv4 or IPv6 address")
}

private fun parsePrimaryTunAddress(cidr: String): PrimaryTunAddress? {
    val ip = cidr.substringBefore("/", "").trim()
    val prefixLiteral = cidr.substringAfter("/", "").trim()
    if (ip.isBlank() || prefixLiteral.isBlank()) {
        return null
    }

    val prefixLength = prefixLiteral.toIntOrNull() ?: return null
    return if (ip.contains(":")) {
        if (prefixLength !in 0..128) {
            null
        } else {
            PrimaryTunAddress(
                address = ip,
                prefixLength = prefixLength.toUByte(),
                family = IpFamily.IPV6,
            )
        }
    } else {
        if (prefixLength !in 0..32) {
            null
        } else {
            PrimaryTunAddress(
                address = ip,
                prefixLength = prefixLength.toUByte(),
                family = IpFamily.IPV4,
            )
        }
    }
}

internal fun normalizeCidrs(values: List<String>): List<String> {
    return values.map { value -> value.trim() }
}
