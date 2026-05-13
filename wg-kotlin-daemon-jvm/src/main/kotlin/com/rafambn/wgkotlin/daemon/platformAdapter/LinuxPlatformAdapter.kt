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
    )

    override suspend fun startSession(config: TunSessionConfig): TunHandle {
        val primaryAddress = extractPrimaryTunAddress(config)
        val handle = RealTunHandle(
            requestedInterfaceName = config.interfaceName,
            ipAddress = primaryAddress.address,
            prefixLength = primaryAddress.prefixLength,
        ).openDevice()
        val addresses = normalizeCidrs(config.addresses)
        val routes = normalizeCidrs(config.routes)
        val routingDomains = config.dns.searchDomains
            .map { domain -> domain.trim() }
            .filter { domain -> domain.isNotBlank() }
            .distinct()
            .map { domain -> "~${domain.removePrefix(".")}" }
        val dnsServers = config.dns.servers
            .map { server -> server.trim() }
            .filter { server -> server.isNotBlank() }
            .distinct()
        val hasDnsConfiguration = routingDomains.isNotEmpty() && dnsServers.isNotEmpty()
        return try {
            val interfaceName = handle.interfaceName

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
                addRoute(route = route, interfaceName = interfaceName)
            }

            if (hasDnsConfiguration) {
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
                cleanup = {
                    deleteRoutesBlocking(routes = routes, interfaceName = interfaceName)
                    if (hasDnsConfiguration) {
                        revertDnsBlocking(interfaceName)
                    }
                },
            )
        } catch (failure: Throwable) {
            routes.asReversed().forEach { route ->
                runSuspendCleanup("delete-route", failure) { deleteRoute(route = route, interfaceName = handle.interfaceName) }
            }
            if (hasDnsConfiguration) {
                runSuspendCleanup("revert-dns", failure) { revertDns(handle.interfaceName) }
            }
            runBlockingCleanup("close-tun-handle", failure) { handle.close() }
            throw failure
        }
    }

    private suspend fun addRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "add-route",
            binary = CommandBinary.IP,
            arguments = listOf("route", "replace", route, "dev", interfaceName),
        )
    }

    private suspend fun deleteRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "delete-route",
            binary = CommandBinary.IP,
            arguments = listOf("route", "delete", route, "dev", interfaceName),
            ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
        )
    }

    private fun deleteRoutesBlocking(routes: List<String>, interfaceName: String) {
        routes.asReversed().forEach { route ->
            runCommandBlocking(
                operationLabel = "delete-route",
                binary = CommandBinary.IP,
                arguments = listOf("route", "delete", route, "dev", interfaceName),
                ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
            )
        }
    }

    private suspend fun revertDns(interfaceName: String) {
        runCommand(
            operationLabel = "revert-dns",
            binary = CommandBinary.RESOLVECTL,
            arguments = listOf("revert", interfaceName),
        )
    }

    private fun revertDnsBlocking(interfaceName: String) {
        runCommandBlocking(
            operationLabel = "revert-dns",
            binary = CommandBinary.RESOLVECTL,
            arguments = listOf("revert", interfaceName),
        )
    }

    private companion object {
        val NOT_FOUND_FAILURE_PATTERNS = listOf(
            Regex("not found", RegexOption.IGNORE_CASE),
            Regex("no such process", RegexOption.IGNORE_CASE),
            Regex("cannot find", RegexOption.IGNORE_CASE),
        )
    }
}
