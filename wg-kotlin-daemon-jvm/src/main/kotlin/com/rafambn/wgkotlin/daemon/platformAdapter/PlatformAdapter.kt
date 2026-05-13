package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.CommandFailed
import com.rafambn.wgkotlin.daemon.command.CommonsExecProcessLauncher
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.TunHandle
import io.netty.util.NetUtil
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    protected suspend fun runCommand(
        operationLabel: String,
        binary: CommandBinary,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
        acceptedExitCodes: Set<Int> = setOf(0),
        ignoredFailurePatterns: List<Regex> = emptyList(),
    ) {
        withContext(Dispatchers.IO) {
            runCommandBlocking(
                operationLabel = operationLabel,
                binary = binary,
                arguments = arguments,
                stdin = stdin,
                environment = environment,
                acceptedExitCodes = acceptedExitCodes,
                ignoredFailurePatterns = ignoredFailurePatterns,
            )
        }
    }

    protected fun runCommandBlocking(
        operationLabel: String,
        binary: CommandBinary,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
        acceptedExitCodes: Set<Int> = setOf(0),
        ignoredFailurePatterns: List<Regex> = emptyList(),
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
            val outputDetail = "${output.stdout}\n${output.stderr}"
            if (ignoredFailurePatterns.any { pattern -> pattern.containsMatchIn(outputDetail) }) {
                return
            }
            throw CommandFailed(
                operationLabel = operationLabel,
                exitCode = output.exitCode,
                stdout = output.stdout,
                stderr = output.stderr,
            )
        }
    }

    protected suspend fun runSuspendCleanup(
        operationLabel: String,
        primaryFailure: Throwable,
        cleanup: suspend () -> Unit,
    ) {
        try {
            cleanup()
        } catch (cleanupFailure: Throwable) {
            primaryFailure.addSuppressed(cleanupFailure)
            logger.warn("Cleanup `$operationLabel` failed", cleanupFailure)
        }
    }

    protected fun runBlockingCleanup(
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
    val addresses = normalizeCidrs(config.addresses)
    if (addresses.isEmpty()) {
        throw IllegalArgumentException("Tun session requires at least one IPv4 or IPv6 address")
    }
    return parsePrimaryTunAddress(addresses.first())
}

private fun parsePrimaryTunAddress(cidr: String): PrimaryTunAddress {
    val parts = cidr.split("/", limit = 2)
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        throw IllegalArgumentException("Tun session address must use CIDR format: $cidr")
    }

    val ip = parts[0].trim()
    val prefixLength = parts[1].trim().toIntOrNull()
        ?: throw IllegalArgumentException("Tun session address prefix must be numeric: $cidr")
    val isIpv4 = NetUtil.isValidIpV4Address(ip)
    val isIpv6 = NetUtil.isValidIpV6Address(ip)
    return if (isIpv6) {
        if (prefixLength !in 0..128) {
            throw IllegalArgumentException("Tun session IPv6 prefix must be between 0 and 128: $cidr")
        } else {
            PrimaryTunAddress(
                address = ip,
                prefixLength = prefixLength.toUByte(),
                family = IpFamily.IPV6,
            )
        }
    } else if (isIpv4) {
        if (prefixLength !in 0..32) {
            throw IllegalArgumentException("Tun session IPv4 prefix must be between 0 and 32: $cidr")
        } else {
            PrimaryTunAddress(
                address = ip,
                prefixLength = prefixLength.toUByte(),
                family = IpFamily.IPV4,
            )
        }
    } else {
        throw IllegalArgumentException("Tun session address has invalid IP address: $cidr")
    }
}

internal fun normalizeCidrs(values: List<String>): List<String> {
    return values.map { value -> value.trim() }
}
