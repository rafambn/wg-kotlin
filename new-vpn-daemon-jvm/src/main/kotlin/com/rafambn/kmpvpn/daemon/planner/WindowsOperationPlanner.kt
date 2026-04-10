package com.rafambn.kmpvpn.daemon.planner

import com.rafambn.kmpvpn.daemon.command.CommandBinary

internal class WindowsOperationPlanner : PlatformOperationPlanner {
    override val platformId: String = "windows"
    override val requiredBinaries: Set<CommandBinary> = setOf(
        CommandBinary.NETSH,
        CommandBinary.POWERSHELL,
    )

    override fun plan(operation: DaemonOperation): ExecutionPlan {
        return when (operation) {
            is InterfaceExists -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.NETSH,
                    arguments = listOf("interface", "show", "interface", "name=${operation.interfaceName}"),
                    acceptedExitCodes = setOf(0, 1),
                ),
            )

            is SetInterfaceState -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.NETSH,
                    arguments = listOf(
                        "interface",
                        "set",
                        "interface",
                        "name=${operation.interfaceName}",
                        "admin=${if (operation.up) "ENABLED" else "DISABLED"}",
                    ),
                ),
            )

            is ApplyMtu -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.NETSH,
                    arguments = listOf(
                        "interface",
                        "ipv4",
                        "set",
                        "subinterface",
                        operation.interfaceName,
                        "mtu=${operation.mtu}",
                        "store=active",
                    ),
                ),
            )

            is ApplyAddresses -> operation.executionPlanOf(
                *operation.addresses.flatMap { address ->
                    if (isIpv6AddressLiteral(address)) {
                        listOf(
                            executionStep(
                                binary = CommandBinary.NETSH,
                                arguments = listOf(
                                    "interface",
                                    "ipv6",
                                    "delete",
                                    "address",
                                    "interface=${operation.interfaceName}",
                                    "address=$address",
                                ),
                                acceptedExitCodes = setOf(0, 1),
                            ),
                            executionStep(
                                binary = CommandBinary.NETSH,
                                arguments = listOf(
                                    "interface",
                                    "ipv6",
                                    "add",
                                    "address",
                                    "interface=${operation.interfaceName}",
                                    "address=$address",
                                ),
                            ),
                        )
                    } else {
                        val (ip, prefix) = splitCidr(address)
                        listOf(
                            executionStep(
                                binary = CommandBinary.NETSH,
                                arguments = listOf(
                                    "interface",
                                    "ipv4",
                                    "delete",
                                    "address",
                                    "name=${operation.interfaceName}",
                                    "address=$ip",
                                    "gateway=all",
                                ),
                                acceptedExitCodes = setOf(0, 1),
                            ),
                            executionStep(
                                binary = CommandBinary.NETSH,
                                arguments = listOf(
                                    "interface",
                                    "ipv4",
                                    "add",
                                    "address",
                                    "name=${operation.interfaceName}",
                                    "address=$ip",
                                    "mask=${prefixToMask(prefix)}",
                                ),
                            ),
                        )
                    }
                }.toTypedArray(),
            )

            is ApplyRoutes -> operation.executionPlanOf(
                *operation.routes.map { route ->
                    executionStep(
                        binary = CommandBinary.NETSH,
                        arguments = listOf(
                            "interface",
                            if (route.substringBefore("/").contains(":")) "ipv6" else "ipv4",
                            "add",
                            "route",
                            "prefix=$route",
                            "interface=${operation.interfaceName}",
                        ),
                    )
                }.toTypedArray(),
            )

            is ApplyDns -> {
                val steps = mutableListOf(clearNrptRulesStep(interfaceName = operation.interfaceName))

                val namespaces = operation.dnsDomainPool.first
                    .map { domain -> domain.trim() }
                    .filter { domain -> domain.isNotBlank() }
                    .map { domain -> ".${domain.removePrefix(".")}" }
                    .distinct()
                val dnsServers = operation.dnsDomainPool.second
                    .map { server -> server.trim() }
                    .filter { server -> server.isNotBlank() }
                    .distinct()

                if (namespaces.isNotEmpty() && dnsServers.isNotEmpty()) {
                    namespaces.forEach { namespace ->
                        val script = "Add-DnsClientNrptRule -Namespace '${escapePowerShellSingleQuoted(namespace)}' " +
                            "-NameServers ${toPowerShellArrayLiteral(dnsServers)} -Comment '${escapePowerShellSingleQuoted(ruleComment(operation.interfaceName))}'"
                        steps += executionStep(
                            binary = CommandBinary.POWERSHELL,
                            arguments = listOf("-NoProfile", "-NonInteractive", "-Command", script),
                        )
                    }
                }

                operation.executionPlanOf(*steps.toTypedArray())
            }

            is ReadInterfaceInformation -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.NETSH,
                    arguments = listOf("interface", "show", "interface", "name=${operation.interfaceName}"),
                ),
            )

            is DeleteInterface -> operation.executionPlanOf(
                clearNrptRulesStep(interfaceName = operation.interfaceName),
                executionStep(
                    binary = CommandBinary.NETSH,
                    arguments = listOf("interface", "delete", "interface", "name=${operation.interfaceName}"),
                    acceptedExitCodes = setOf(0, 1),
                ),
            )
        }
    }

    private fun clearNrptRulesStep(interfaceName: String): ExecutionStep {
        val script = "Get-DnsClientNrptRule | Where-Object { \$_.Comment -eq '${escapePowerShellSingleQuoted(ruleComment(interfaceName))}' } | Remove-DnsClientNrptRule -Force"
        return executionStep(
            binary = CommandBinary.POWERSHELL,
            arguments = listOf("-NoProfile", "-NonInteractive", "-Command", script),
        )
    }

    private fun ruleComment(interfaceName: String): String {
        return "kmpvpn-daemon:$interfaceName"
    }

    private fun splitCidr(cidr: String): Pair<String, Int> {
        val parts = cidr.split("/")
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
            .joinToString(".") { shift ->
                ((mask shr shift) and 0xff).toString()
            }
    }
}
