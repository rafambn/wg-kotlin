package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
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
        val (ipv4Address, prefixLength) = extractPrimaryIpv4Address(config)
        val handle = RealTunHandle(
            requestedInterfaceName = config.interfaceName,
            ipv4Address = ipv4Address,
            prefixLength = prefixLength,
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

            val resolverPath = "State:/Network/Service/$interfaceName/DNS"
            val resolverRootPath = "State:/Network/Service/$interfaceName"
            val dnsServers = config.dns.servers
                .map { server -> server.trim() }
                .filter { server -> server.isNotBlank() }
                .distinct()
            val domains = config.dns.searchDomains
                .map { domain -> domain.trim().removePrefix(".") }
                .filter { domain -> domain.isNotBlank() }
                .distinct()

            runCommand(
                operationLabel = "clear-dns",
                binary = CommandBinary.SCUTIL,
                stdin = buildString {
                    appendLine("remove $resolverPath")
                    appendLine("remove $resolverRootPath")
                    appendLine("quit")
                },
            )

            if (domains.isNotEmpty() && dnsServers.isNotEmpty()) {
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
            handle
        } catch (failure: Throwable) {
            runCatching { handle.close() }
            throw failure
        }
    }
}
