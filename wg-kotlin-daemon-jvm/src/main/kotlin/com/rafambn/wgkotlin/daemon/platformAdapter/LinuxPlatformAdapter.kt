package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.CleanupTunHandle
import com.rafambn.wgkotlin.daemon.tun.RealTunHandle
import com.rafambn.wgkotlin.daemon.tun.TunHandle

internal class LinuxPlatformAdapter(
    processLauncher: ProcessLauncher,
) : BasePlatformAdapter(processLauncher) {
    override val platformId: String = "linux"
    override val requiredBinaries: Set<CommandBinary> = setOf(
        CommandBinary.IP,
        CommandBinary.RESOLVECTL,
    )

    override suspend fun startSession(config: TunSessionConfig): TunHandle {
        val primaryAddress = extractPrimaryTunAddress(config)
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
                    binary = CommandBinary.IP,
                    arguments = listOf("link", "set", "dev", interfaceName, "mtu", mtu.toString()),
                )
            }

            runCommand(
                operationLabel = "flush-addresses",
                binary = CommandBinary.IP,
                arguments = listOf("address", "flush", "dev", interfaceName),
            )
            addresses.forEach { address ->
                runCommand(
                    operationLabel = "add-address",
                    binary = CommandBinary.IP,
                    arguments = listOf("address", "add", address, "dev", interfaceName),
                )
            }

            routes.forEach { route ->
                runCommand(
                    operationLabel = "add-route",
                    binary = CommandBinary.IP,
                    arguments = listOf("route", "replace", route, "dev", interfaceName),
                )
            }

            val routingDomains = config.dns.searchDomains
                .map { domain -> domain.trim() }
                .filter { domain -> domain.isNotBlank() }
                .distinct()
                .map { domain -> "~${domain.removePrefix(".")}" }
            val dnsServers = config.dns.servers
                .map { server -> server.trim() }
                .filter { server -> server.isNotBlank() }
                .distinct()

            if (routingDomains.isEmpty() || dnsServers.isEmpty()) {
                runCommand(
                    operationLabel = "revert-dns",
                    binary = CommandBinary.RESOLVECTL,
                    arguments = listOf("revert", interfaceName),
                )
            } else {
                runCommand(
                    operationLabel = "set-dns",
                    binary = CommandBinary.RESOLVECTL,
                    arguments = listOf("dns", interfaceName) + dnsServers,
                )
                runCommand(
                    operationLabel = "set-domains",
                    binary = CommandBinary.RESOLVECTL,
                    arguments = listOf("domain", interfaceName) + routingDomains,
                )
            }
            CleanupTunHandle(
                delegate = handle,
                cleanup = { revertDns(interfaceName) },
            )
        } catch (failure: Throwable) {
            runCleanup("revert-dns", failure) { revertDns(handle.interfaceName) }
            runCleanup("close-tun-handle", failure) { handle.close() }
            throw failure
        }
    }

    private fun revertDns(interfaceName: String) {
        runCommand(
            operationLabel = "revert-dns",
            binary = CommandBinary.RESOLVECTL,
            arguments = listOf("revert", interfaceName),
        )
    }
}
