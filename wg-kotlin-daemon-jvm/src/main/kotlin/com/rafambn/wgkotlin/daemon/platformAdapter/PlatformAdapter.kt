package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.CommandFailed
import com.rafambn.wgkotlin.daemon.command.CommonsExecProcessLauncher
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.TunHandle
import java.util.Locale

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
    protected fun runCommand(
        operationLabel: String,
        binary: CommandBinary,
        arguments: List<String> = emptyList(),
        stdin: String? = null,
        acceptedExitCodes: Set<Int> = setOf(0),
    ) {
        val output = processLauncher.run(
            ProcessInvocationModel(
                binary = binary,
                arguments = arguments,
                stdin = stdin,
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
}

internal fun extractPrimaryIpv4Address(config: TunSessionConfig): Pair<String, UByte> {
    val ipv4Address = normalizeCidrs(config.addresses)
        .map { address -> address.substringBefore("/") to address.substringAfter("/", "") }
        .firstOrNull { (ip, prefix) -> ip.isNotBlank() && !ip.contains(":") && prefix.isNotBlank() }
        ?: throw IllegalArgumentException("Tun session requires at least one IPv4 address")

    return ipv4Address.first to ipv4Address.second.toUByte()
}

internal fun normalizeCidrs(values: List<String>): List<String> {
    return values.map { value -> value.trim() }
}
