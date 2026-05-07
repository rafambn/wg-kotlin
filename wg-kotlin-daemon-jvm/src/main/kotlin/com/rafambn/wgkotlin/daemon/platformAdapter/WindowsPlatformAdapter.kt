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

    override suspend fun startSession(config: TunSessionConfig): TunHandle {
        val primaryAddress = extractPrimaryTunAddress(config)
        val baseHandle = RealTunHandle(
            requestedInterfaceName = config.interfaceName,
            ipAddress = primaryAddress.address,
            prefixLength = primaryAddress.prefixLength,
        ).openDevice()
        return try {
            val interfaceName = baseHandle.interfaceName
            val addresses = normalizeCidrs(config.addresses)
            val routes = normalizeCidrs(config.routes)

            config.mtu?.let { mtu ->
                runCommand(
                    operationLabel = "apply-mtu",
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
                        acceptedExitCodes = setOf(0, 1),
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
                        acceptedExitCodes = setOf(0, 1),
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
                runCommand(
                    operationLabel = "add-route",
                    binary = CommandBinary.NETSH,
                    arguments = listOf(
                        "interface",
                        if (route.substringBefore("/").contains(":")) "ipv6" else "ipv4",
                        "add",
                        "route",
                        "prefix=$route",
                        "interface=$interfaceName",
                    ),
                )
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
                    val script = "Add-DnsClientNrptRule -Namespace '${escapePowerShellSingleQuoted(namespace)}' " +
                        "-NameServers ${toPowerShellArrayLiteral(dnsServers)} -Comment '${escapePowerShellSingleQuoted(ruleComment(interfaceName))}'"
                    runCommand(
                        operationLabel = "set-nrpt-rule",
                        binary = CommandBinary.POWERSHELL,
                        arguments = listOf("-NoProfile", "-NonInteractive", "-Command", script),
                    )
                }
            }
            CleanupTunHandle(
                delegate = baseHandle,
                cleanup = { clearNrptRules(interfaceName) },
            )
        } catch (failure: Throwable) {
            runCatching { baseHandle.close() }
            runCatching { clearNrptRules(baseHandle.interfaceName) }
            throw failure
        }
    }

    private fun clearNrptRules(interfaceName: String) {
        val script = "Get-DnsClientNrptRule | Where-Object { \$_.Comment -eq '${escapePowerShellSingleQuoted(ruleComment(interfaceName))}' } | Remove-DnsClientNrptRule -Force"
        runCommand(
            operationLabel = "clear-nrpt-rules",
            binary = CommandBinary.POWERSHELL,
            arguments = listOf("-NoProfile", "-NonInteractive", "-Command", script),
        )
    }

    private fun ruleComment(interfaceName: String): String {
        return "kmpvpn-daemon:$interfaceName"
    }

    private fun splitCidr(cidr: String): Pair<String, Int> {
        val parts = cidr.split("/", limit = 2)
        return parts[0] to parts[1].toInt()
    }

    private fun isIpv6AddressLiteral(value: String): Boolean {
        return value.substringBefore("/").contains(":")
    }

    private fun escapePowerShellSingleQuoted(value: String): String {
        return value.replace("'", "''")
    }

    private fun toPowerShellArrayLiteral(values: List<String>): String {
        val quotedValues = values.joinToString(",") { value -> "'${escapePowerShellSingleQuoted(value)}'" }
        return "@($quotedValues)"
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
}
