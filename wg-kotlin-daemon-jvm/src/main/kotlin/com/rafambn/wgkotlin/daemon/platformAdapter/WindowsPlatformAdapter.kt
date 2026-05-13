package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.CleanupTunHandle
import com.rafambn.wgkotlin.daemon.tun.RealTunHandle
import com.rafambn.wgkotlin.daemon.tun.TunHandle

internal class WindowsPlatformAdapter(
    processLauncher: ProcessLauncher,
) : BasePlatformAdapter(processLauncher) {
    override val platformId: String = "windows"
    override val requiredBinaries: Set<CommandBinary> = setOf(
        CommandBinary.NETSH,
        CommandBinary.POWERSHELL,
    )

    override fun cleanupStaleState() {
        clearAllManagedNrptRulesBlocking()
    }

    override suspend fun startSession(config: TunSessionConfig): TunHandle {
        val primaryAddress = extractPrimaryTunAddress(config)
        val baseHandle = RealTunHandle(
            requestedInterfaceName = config.interfaceName,
            ipAddress = primaryAddress.address,
            prefixLength = primaryAddress.prefixLength,
        ).openDevice()
        val addresses = normalizeCidrs(config.addresses)
        val routes = normalizeCidrs(config.routes)
        return try {
            val interfaceName = baseHandle.interfaceName
            val hasIpv4Address = addresses.any { address -> !isIpv6AddressLiteral(address) }
            val hasIpv6Address = addresses.any(::isIpv6AddressLiteral)

            config.mtu?.let { mtu ->
                if (hasIpv4Address) {
                    runCommand(
                        operationLabel = "apply-ipv4-mtu",
                        binary = CommandBinary.NETSH,
                        arguments = listOf(
                            "interface",
                            "ipv4",
                            "set",
                            "subinterface",
                            interfaceName,
                            "mtu=$mtu",
                            "store=active",
                        ),
                    )
                }
                if (hasIpv6Address) {
                    runCommand(
                        operationLabel = "apply-ipv6-mtu",
                        binary = CommandBinary.NETSH,
                        arguments = listOf(
                            "interface",
                            "ipv6",
                            "set",
                            "subinterface",
                            interfaceName,
                            "mtu=$mtu",
                            "store=active",
                        ),
                    )
                }
            }

            addresses.forEach { address ->
                if (isIpv6AddressLiteral(address)) {
                    runCommand(
                        operationLabel = "delete-address",
                        binary = CommandBinary.NETSH,
                        arguments = listOf(
                            "interface",
                            "ipv6",
                            "delete",
                            "address",
                            "interface=$interfaceName",
                            "address=$address",
                        ),
                        ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
                    )
                    runCommand(
                        operationLabel = "add-address",
                        binary = CommandBinary.NETSH,
                        arguments = listOf(
                            "interface",
                            "ipv6",
                            "add",
                            "address",
                            "interface=$interfaceName",
                            "address=$address",
                        ),
                    )
                } else {
                    val (ip, prefix) = splitCidr(address)
                    runCommand(
                        operationLabel = "delete-address",
                        binary = CommandBinary.NETSH,
                        arguments = listOf(
                            "interface",
                            "ipv4",
                            "delete",
                            "address",
                            "name=$interfaceName",
                            "address=$ip",
                            "gateway=all",
                        ),
                        ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
                    )
                    runCommand(
                        operationLabel = "add-address",
                        binary = CommandBinary.NETSH,
                        arguments = listOf(
                            "interface",
                            "ipv4",
                            "add",
                            "address",
                            "name=$interfaceName",
                            "address=$ip",
                            "mask=${prefixToMask(prefix)}",
                        ),
                    )
                }
            }

            routes.forEach { route ->
                deleteRoute(route = route, interfaceName = interfaceName)
                addRoute(route = route, interfaceName = interfaceName)
            }

            clearNrptRules(interfaceName)
            val namespaces = config.dns.searchDomains
                .map { domain -> domain.trim() }
                .filter { domain -> domain.isNotBlank() }
                .map { domain -> ".${domain.removePrefix(".")}" }
                .distinct()
            val dnsServers = config.dns.servers
                .map { server -> server.trim() }
                .filter { server -> server.isNotBlank() }
                .distinct()
            if (namespaces.isNotEmpty() && dnsServers.isNotEmpty()) {
                namespaces.forEach { namespace ->
                    runCommand(
                        operationLabel = "set-nrpt-rule",
                        binary = CommandBinary.POWERSHELL,
                        arguments = listOf("-NoProfile", "-NonInteractive", "-Command", SET_NRPT_RULE_SCRIPT),
                        environment = mapOf(
                            ENV_DNS_NAMESPACE to namespace,
                            ENV_DNS_SERVERS to dnsServers.joinToString("\n"),
                            ENV_NRPT_COMMENT to ruleComment(interfaceName),
                        ),
                    )
                }
            }
            CleanupTunHandle(
                delegate = baseHandle,
                cleanup = {
                    deleteRoutesBlocking(routes = routes, interfaceName = interfaceName)
                    clearNrptRulesBlocking(interfaceName)
                },
            )
        } catch (failure: Throwable) {
            routes.asReversed().forEach { route ->
                runSuspendCleanup("delete-route", failure) { deleteRoute(route = route, interfaceName = baseHandle.interfaceName) }
            }
            runBlockingCleanup("close-tun-handle", failure) { baseHandle.close() }
            runSuspendCleanup("clear-nrpt-rules", failure) { clearNrptRules(baseHandle.interfaceName) }
            throw failure
        }
    }

    private suspend fun addRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "add-route",
            binary = CommandBinary.NETSH,
            arguments = routeArguments(command = "add", route = route, interfaceName = interfaceName),
        )
    }

    private suspend fun deleteRoute(route: String, interfaceName: String) {
        runCommand(
            operationLabel = "delete-route",
            binary = CommandBinary.NETSH,
            arguments = routeArguments(command = "delete", route = route, interfaceName = interfaceName),
            ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
        )
    }

    private fun deleteRoutesBlocking(routes: List<String>, interfaceName: String) {
        routes.asReversed().forEach { route ->
            runCommandBlocking(
                operationLabel = "delete-route",
                binary = CommandBinary.NETSH,
                arguments = routeArguments(command = "delete", route = route, interfaceName = interfaceName),
                ignoredFailurePatterns = NOT_FOUND_FAILURE_PATTERNS,
            )
        }
    }

    private suspend fun clearNrptRules(interfaceName: String) {
        runCommand(
            operationLabel = "clear-nrpt-rules",
            binary = CommandBinary.POWERSHELL,
            arguments = listOf("-NoProfile", "-NonInteractive", "-Command", CLEAR_NRPT_RULES_SCRIPT),
            environment = mapOf(ENV_NRPT_COMMENT to ruleComment(interfaceName)),
        )
    }

    private fun clearNrptRulesBlocking(interfaceName: String) {
        runCommandBlocking(
            operationLabel = "clear-nrpt-rules",
            binary = CommandBinary.POWERSHELL,
            arguments = listOf("-NoProfile", "-NonInteractive", "-Command", CLEAR_NRPT_RULES_SCRIPT),
            environment = mapOf(ENV_NRPT_COMMENT to ruleComment(interfaceName)),
        )
    }

    private fun clearAllManagedNrptRulesBlocking() {
        runCommandBlocking(
            operationLabel = "clear-stale-nrpt-rules",
            binary = CommandBinary.POWERSHELL,
            arguments = listOf("-NoProfile", "-NonInteractive", "-Command", CLEAR_ALL_NRPT_RULES_SCRIPT),
            environment = mapOf(ENV_NRPT_COMMENT_PREFIX to NRPT_COMMENT_PREFIX),
        )
    }

    private fun ruleComment(interfaceName: String): String {
        return "$NRPT_COMMENT_PREFIX$interfaceName"
    }

    private fun splitCidr(cidr: String): Pair<String, Int> {
        val parts = cidr.split("/", limit = 2)
        return parts[0] to parts[1].toInt()
    }

    private fun isIpv6AddressLiteral(value: String): Boolean {
        return value.substringBefore("/").contains(":")
    }

    private fun routeArguments(command: String, route: String, interfaceName: String): List<String> {
        return listOf(
            "interface",
            if (isIpv6AddressLiteral(route)) "ipv6" else "ipv4",
            command,
            "route",
            "prefix=$route",
            "interface=$interfaceName",
        )
    }

    private fun prefixToMask(prefix: Int): String {
        val mask = if (prefix == 0) {
            0L
        } else {
            (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        }
        return listOf(24, 16, 8, 0)
            .joinToString(".") { shift -> ((mask shr shift) and 0xff).toString() }
    }

    private companion object {
        const val ENV_DNS_NAMESPACE = "WG_KOTLIN_DNS_NAMESPACE"
        const val ENV_DNS_SERVERS = "WG_KOTLIN_DNS_SERVERS"
        const val ENV_NRPT_COMMENT = "WG_KOTLIN_NRPT_COMMENT"
        const val ENV_NRPT_COMMENT_PREFIX = "WG_KOTLIN_NRPT_COMMENT_PREFIX"
        const val NRPT_COMMENT_PREFIX = "kmpvpn-daemon:"

        val SET_NRPT_RULE_SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}nameServers = (${'$'}env:$ENV_DNS_SERVERS -split "`n") | Where-Object { ${'$'}_ -ne '' }
            Add-DnsClientNrptRule -Namespace ${'$'}env:$ENV_DNS_NAMESPACE -NameServers ${'$'}nameServers -Comment ${'$'}env:$ENV_NRPT_COMMENT
        """.trimIndent()

        val CLEAR_NRPT_RULES_SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            Get-DnsClientNrptRule | Where-Object { ${'$'}_.Comment -eq ${'$'}env:$ENV_NRPT_COMMENT } | Remove-DnsClientNrptRule -Force
        """.trimIndent()

        val CLEAR_ALL_NRPT_RULES_SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            Get-DnsClientNrptRule | Where-Object { ${'$'}_.Comment -like "${'$'}env:$ENV_NRPT_COMMENT_PREFIX*" } | Remove-DnsClientNrptRule -Force
        """.trimIndent()

        val NOT_FOUND_FAILURE_PATTERNS = listOf(
            Regex("not found", RegexOption.IGNORE_CASE),
            Regex("cannot find", RegexOption.IGNORE_CASE),
            Regex("does not exist", RegexOption.IGNORE_CASE),
            Regex("element not found", RegexOption.IGNORE_CASE),
            Regex("object was not found", RegexOption.IGNORE_CASE),
        )
    }
}
