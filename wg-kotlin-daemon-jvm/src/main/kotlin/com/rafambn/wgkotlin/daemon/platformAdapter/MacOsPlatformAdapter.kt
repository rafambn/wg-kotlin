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
        val addresses = normalizeCidrs(config.addresses)
        val routes = normalizeCidrs(config.routes)
        return try {
            val interfaceName = handle.interfaceName

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
                deleteRoute(route = route, interfaceName = interfaceName)
                addRoute(route = route, interfaceName = interfaceName)
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
                cleanup = {
                    deleteRoutesBlocking(routes = routes, interfaceName = interfaceName)
                    deleteAddressesBlocking(addresses = addresses, interfaceName = interfaceName)
                    clearDnsEntriesBlocking(interfaceName)
                },
            )
        } catch (failure: Throwable) {
            routes.asReversed().forEach { route ->
                runSuspendCleanup("delete-route", failure) { deleteRoute(route = route, interfaceName = handle.interfaceName) }
            }
            addresses.asReversed().forEach { address ->
                runSuspendCleanup("delete-address", failure) { deleteAddress(address = address, interfaceName = handle.interfaceName) }
            }
            runBlockingCleanup("close-tun-handle", failure) { handle.close() }
            runSuspendCleanup("clear-dns", failure) { clearDnsEntries(handle.interfaceName) }
            throw failure
        }
    }

    private suspend fun addRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "add-route",
            binary = CommandBinary.ROUTE,
            arguments = routeArguments(command = "add", route = route, interfaceName = interfaceName),
        )
    }

    private suspend fun deleteRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "delete-route",
            binary = CommandBinary.ROUTE,
            arguments = routeArguments(command = "delete", route = route, interfaceName = interfaceName),
            ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
        )
    }

    private fun deleteRoutesBlocking(routes: List<String>, interfaceName: String) {
        routes.asReversed().forEach { route ->
            runCommandBlocking(
                operationLabel = "delete-route",
                binary = CommandBinary.ROUTE,
                arguments = routeArguments(command = "delete", route = route, interfaceName = interfaceName),
                ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
            )
        }
    }

    private suspend fun deleteAddress(address: String, interfaceName: String) {
        runCommand(
            operationLabel = "delete-address",
            binary = CommandBinary.IFCONFIG,
            arguments = deleteAddressArguments(address = address, interfaceName = interfaceName),
            ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
        )
    }

    private fun deleteAddressesBlocking(addresses: List<String>, interfaceName: String) {
        addresses.asReversed().forEach { address ->
            runCommandBlocking(
                operationLabel = "delete-address",
                binary = CommandBinary.IFCONFIG,
                arguments = deleteAddressArguments(address = address, interfaceName = interfaceName),
                ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
            )
        }
    }

    private fun routeArguments(command: String, route: String, interfaceName: String): List<String> {
        val addressFamily = if (route.substringBefore("/").contains(":")) "-inet6" else "-inet"
        return listOf("-n", addressFamily, command, "-net", route, "-interface", interfaceName)
    }

    private fun deleteAddressArguments(address: String, interfaceName: String): List<String> {
        val addressLiteral = address.substringBefore("/")
        return if (addressLiteral.contains(":")) {
            listOf(interfaceName, "inet6", addressLiteral, "-alias")
        } else {
            listOf(interfaceName, "inet", addressLiteral, "-alias")
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

    private companion object {
        val NOT_FOUND_FAILURE_PATTERNS = listOf(
            Regex("not in table", RegexOption.IGNORE_CASE),
            Regex("not found", RegexOption.IGNORE_CASE),
            Regex("no such process", RegexOption.IGNORE_CASE),
            Regex("can't assign requested address", RegexOption.IGNORE_CASE),
        )
    }
}
