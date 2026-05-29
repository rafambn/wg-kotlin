package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.scribe.seal
import com.rafambn.wgkotlin.daemon.DaemonLogger
import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import kotlinx.serialization.json.JsonPrimitive
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
        val routes = normalizeCidrs(config.peerAllowedIps)
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
        val endpointRoutes = resolveEndpointRoutes(config.peerEndpoints)
        val endpointIps = endpointRoutes.map { (endpoint, _) -> endpoint }.toSet()
        val filteredRoutes = routes.filter { route ->
            route.substringBefore("/") !in endpointIps
        }
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
                operationLabel = "bring-interface-up",
                binary = CommandBinary.IP,
                arguments = listOf("link", "set", "dev", interfaceName, "up"),
            )

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

            endpointRoutes.forEach { (endpoint, route) ->
                addEndpointRoute(endpoint = endpoint, route = route)
            }

            filteredRoutes.forEach { route ->
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
            DaemonLogger.newScroll().apply {
                this["event"] = JsonPrimitive("linux_session_ready")
                this["interface"] = JsonPrimitive(interfaceName)
                this["address_count"] = JsonPrimitive(addresses.size)
                this["route_count"] = JsonPrimitive(filteredRoutes.size)
                this["endpoint_route_count"] = JsonPrimitive(endpointRoutes.size)
                this["has_dns"] = JsonPrimitive(hasDnsConfiguration)
            }.seal(DaemonLogger, success = true)
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

                    filteredRoutes.asReversed().forEach { route ->
                        runCatching {
                            runCommand(
                                operationLabel = "delete-route",
                                binary = CommandBinary.IP,
                                arguments = routeArguments(command = "delete", route = route, interfaceName = interfaceName),
                                ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
                            )
                        }.onFailure(::recordCleanupFailure)
                    }
                    endpointRoutes.asReversed().forEach { (endpoint, route) ->
                        runCatching {
                            deleteEndpointRoute(endpoint = endpoint, route = route)
                        }.onFailure(::recordCleanupFailure)
                    }
                    runCatching { revertDns(interfaceName) }
                        .onFailure(::recordCleanupFailure)
                    cleanupFailure?.let { throw it }
                },
            )
        } catch (failure: Throwable) {
            DaemonLogger.newScroll().apply {
                this["event"] = JsonPrimitive("linux_session_failed")
                this["error_type"] = JsonPrimitive(failure::class.simpleName ?: "Throwable")
                this["error_message"] = JsonPrimitive(failure.message ?: "unknown")
            }.seal(DaemonLogger, success = false)
            filteredRoutes.asReversed().forEach { route ->
                runCatching { deleteRoute(route = route, interfaceName = handle.interfaceName) }
                    .onFailure(failure::addSuppressed)
            }
            endpointRoutes.asReversed().forEach { (endpoint, route) ->
                runCatching { deleteEndpointRoute(endpoint = endpoint, route = route) }
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

    private fun resolveEndpointRoutes(endpoints: List<String>): List<Pair<String, EndpointRoute>> {
        return endpoints.mapNotNull { endpoint ->
            resolveEndpointRoute(endpoint)?.let { route -> endpoint to route }
        }
    }

    private fun resolveEndpointRoute(endpoint: String): EndpointRoute? {
        val output = processLauncher.run(
            ProcessInvocationModel(
                binary = CommandBinary.IP,
                arguments = listOf("route", "get", endpoint),
            ),
        )
        if (output.exitCode != 0) {
            return null
        }
        val firstLine = output.stdout.trim().lines().firstOrNull() ?: return null
        val viaMatch = VIA_REGEX.find(firstLine)
        val devMatch = DEV_REGEX.find(firstLine)
        val device = devMatch?.groupValues?.get(1) ?: return null
        return EndpointRoute(
            gateway = viaMatch?.groupValues?.get(1),
            device = device,
        )
    }

    private fun addEndpointRoute(endpoint: String, route: EndpointRoute) {
        val family = if (endpoint.contains(":")) listOf("-6") else emptyList()
        val arguments = mutableListOf<String>()
        arguments.addAll(family)
        arguments.addAll(listOf("route", "replace", "$endpoint/32"))
        if (route.gateway != null) {
            arguments.addAll(listOf("via", route.gateway))
        }
        arguments.addAll(listOf("dev", route.device))
        runCommand(
            operationLabel = "add-endpoint-route",
            binary = CommandBinary.IP,
            arguments = arguments,
        )
    }

    private fun deleteEndpointRoute(endpoint: String, route: EndpointRoute) {
        val family = if (endpoint.contains(":")) listOf("-6") else emptyList()
        val arguments = mutableListOf<String>()
        arguments.addAll(family)
        arguments.addAll(listOf("route", "delete", "$endpoint/32"))
        if (route.gateway != null) {
            arguments.addAll(listOf("via", route.gateway))
        }
        arguments.addAll(listOf("dev", route.device))
        runCommand(
            operationLabel = "delete-endpoint-route",
            binary = CommandBinary.IP,
            arguments = arguments,
            ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
        )
    }

    private data class EndpointRoute(
        val gateway: String?,
        val device: String,
    )

    private companion object {
        val NOT_FOUND_FAILURE_PATTERNS = listOf(
            Regex("not found", RegexOption.IGNORE_CASE),
            Regex("no such process", RegexOption.IGNORE_CASE),
            Regex("cannot find", RegexOption.IGNORE_CASE),
        )
        val VIA_REGEX = Regex("""via\s+(\S+)""")
        val DEV_REGEX = Regex("""dev\s+(\S+)""")
    }
}
