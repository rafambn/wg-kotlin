package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.CleanupTunHandle
import com.rafambn.wgkotlin.daemon.tun.RealTunHandle
import com.rafambn.wgkotlin.daemon.tun.TunHandle

internal class MacOsPlatformAdapter(
    processLauncher: ProcessLauncher,
) : BasePlatformAdapter(processLauncher) {
    override val platformId: String = "macos"
    override val requiredBinaries: Set<CommandBinary> = setOf(
        CommandBinary.IFCONFIG,
        CommandBinary.ROUTE,
        CommandBinary.SCUTIL,
    )

    override suspend fun startSession(config: TunSessionConfig): TunHandle {
        val primaryAddress = extractPrimaryTunAddress(config)
        validateInterfaceNameForScutil(config.interfaceName)
        val handle = RealTunHandle(
            requestedInterfaceName = config.interfaceName,
            ipAddress = primaryAddress.address,
            prefixLength = primaryAddress.prefixLength,
        ).openDevice()
        return try {
            val interfaceName = handle.interfaceName
            val addresses = normalizeCidrs(config.addresses)
            val routes = normalizeCidrs(config.routes)

            config.mtu?.let { mtu ->
                runCommand(
                    operationLabel = "apply-mtu",
                    binary = CommandBinary.IFCONFIG,
                    arguments = listOf(interfaceName, "mtu", mtu.toString()),
                )
            }

            addresses.forEach { address ->
                val arguments = if (address.substringBefore("/").contains(":")) {
                    listOf(interfaceName, "inet6", address, "add")
                } else {
                    listOf(interfaceName, "inet", address, "alias")
                }
                runCommand(
                    operationLabel = "add-address",
                    binary = CommandBinary.IFCONFIG,
                    arguments = arguments,
                )
            }

            routes.forEach { route ->
                runCommand(
                    operationLabel = "delete-route",
                    binary = CommandBinary.ROUTE,
                    arguments = listOf("-n", "delete", "-net", route, "-interface", interfaceName),
                    acceptedExitCodes = setOf(0, 1),
                )
                runCommand(
                    operationLabel = "add-route",
                    binary = CommandBinary.ROUTE,
                    arguments = listOf("-n", "add", "-net", route, "-interface", interfaceName),
                )
            }

            clearDnsEntries(interfaceName)
            val dnsServers = config.dns.servers
                .map { server -> server.trim() }
                .filter { server -> server.isNotBlank() }
                .distinct()
            val domains = config.dns.searchDomains
                .map { domain -> domain.trim().removePrefix(".") }
                .filter { domain -> domain.isNotBlank() }
                .distinct()

            if (domains.isNotEmpty() && dnsServers.isNotEmpty()) {
                val resolverPath = resolverPath(interfaceName)
                val resolverRootPath = resolverRootPath(interfaceName)
                runCommand(
                    operationLabel = "set-dns",
                    binary = CommandBinary.SCUTIL,
                    stdin = buildString {
                        appendLine("d.init")
                        appendLine("d.add ServerAddresses ${dnsServers.joinToString(" ")}")
                        appendLine("d.add SupplementalMatchDomains ${domains.joinToString(" ")}")
                        appendLine("set $resolverPath")
                        appendLine("quit")
                    },
                )
                runCommand(
                    operationLabel = "set-dns-root",
                    binary = CommandBinary.SCUTIL,
                    stdin = buildString {
                        appendLine("d.init")
                        appendLine("d.add UserDefinedName $interfaceName")
                        appendLine("set $resolverRootPath")
                        appendLine("quit")
                    },
                )
            }
            CleanupTunHandle(
                delegate = handle,
                cleanup = { clearDnsEntriesBlocking(interfaceName) },
            )
        } catch (failure: Throwable) {
            runBlockingCleanup("close-tun-handle", failure) { handle.close() }
            runSuspendCleanup("clear-dns", failure) { clearDnsEntries(handle.interfaceName) }
            throw failure
        }
    }

    private suspend fun clearDnsEntries(interfaceName: String) {
        runCommand(
            operationLabel = "clear-dns",
            binary = CommandBinary.SCUTIL,
            stdin = buildString {
                appendLine("remove ${resolverPath(interfaceName)}")
                appendLine("remove ${resolverRootPath(interfaceName)}")
                appendLine("quit")
            },
        )
    }

    private fun clearDnsEntriesBlocking(interfaceName: String) {
        runCommandBlocking(
            operationLabel = "clear-dns",
            binary = CommandBinary.SCUTIL,
            stdin = buildString {
                appendLine("remove ${resolverPath(interfaceName)}")
                appendLine("remove ${resolverRootPath(interfaceName)}")
                appendLine("quit")
            },
        )
    }

    private fun validateInterfaceNameForScutil(interfaceName: String) {
        require(Regex("^[A-Za-z][A-Za-z0-9_.-]{0,31}$").matches(interfaceName)) {
            "Interface name contains unsafe characters for scutil: $interfaceName"
        }
    }

    private fun resolverPath(interfaceName: String): String {
        return "State:/Network/Interface/$interfaceName/DNS"
    }

    private fun resolverRootPath(interfaceName: String): String {
        return "State:/Network/Interface/$interfaceName"
    }
}
