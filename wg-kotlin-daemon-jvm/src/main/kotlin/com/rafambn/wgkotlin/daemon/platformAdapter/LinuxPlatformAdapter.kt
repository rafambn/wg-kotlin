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
        val addresses = normalizeCidrs(config.addresses)
        val routes = normalizeCidrs(config.routes)
        val routingDomains = config.dns.searchDomains
            .map { domain -> domain.trim() }
            .filter { domain -> domain.isNotBlank() }
            .map { domain -> domain.removePrefix(".") }
            .distinct()
            .map { domain -> "~$domain" }
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

            revertDns(interfaceName)
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
                    var cleanupFailure: Throwable? = null
                    fun recordCleanupFailure(throwable: Throwable) {
                        val existingFailure = cleanupFailure
                        if (existingFailure == null) {
                            cleanupFailure = throwable
                        } else {
                            existingFailure.addSuppressed(throwable)
                        }
                    }

                    routes.asReversed().forEach { route ->
                        runCatching {
                            runCommand(
                                operationLabel = "delete-route",
                                binary = CommandBinary.IP,
                                arguments = routeArguments(command = "delete", route = route, interfaceName = interfaceName),
                                ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
                            )
                        }.onFailure(::recordCleanupFailure)
                    }
                    runCatching { revertDns(interfaceName) }
                        .onFailure(::recordCleanupFailure)
                    cleanupFailure?.let { throw it }
                },
            )
        } catch (failure: Throwable) {
            routes.asReversed().forEach { route ->
                runCatching { deleteRoute(route = route, interfaceName = handle.interfaceName) }
                    .onFailure(failure::addSuppressed)
            }
            runCatching { revertDns(handle.interfaceName) }
                .onFailure(failure::addSuppressed)
            runCatching { handle.close() }
                .onFailure(failure::addSuppressed)
            throw failure
        }
    }

    private fun addRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "add-route",
            binary = CommandBinary.IP,
            arguments = routeArguments(command = "replace", route = route, interfaceName = interfaceName),
        )
    }

    private fun deleteRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "delete-route",
            binary = CommandBinary.IP,
            arguments = routeArguments(command = "delete", route = route, interfaceName = interfaceName),
            ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
        )
    }

    private fun routeArguments(command: String, route: String, interfaceName: String): List<String> {
        val family = if (route.substringBefore("/").contains(":")) listOf("-6") else emptyList()
        return family + listOf("route", command, route, "dev", interfaceName)
    }

    private fun revertDns(interfaceName: String) {
        runCommand(
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
