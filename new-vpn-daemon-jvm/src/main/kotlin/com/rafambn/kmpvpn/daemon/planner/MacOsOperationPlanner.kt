package com.rafambn.kmpvpn.daemon.planner

import com.rafambn.kmpvpn.daemon.command.CommandBinary

internal class MacOsOperationPlanner : PlatformOperationPlanner {
    override val platformId: String = "macos"
    override val requiredBinaries: Set<CommandBinary> = setOf(
        CommandBinary.IFCONFIG,
        CommandBinary.ROUTE,
        CommandBinary.SCUTIL,
    )

    override fun plan(operation: DaemonOperation): ExecutionPlan {
        return when (operation) {
            is InterfaceExists -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IFCONFIG,
                    arguments = listOf(operation.interfaceName),
                    acceptedExitCodes = setOf(0, 1),
                ),
            )

            is SetInterfaceState -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IFCONFIG,
                    arguments = listOf(operation.interfaceName, if (operation.up) "up" else "down"),
                ),
            )

            is ApplyMtu -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IFCONFIG,
                    arguments = listOf(operation.interfaceName, "mtu", operation.mtu.toString()),
                ),
            )

            is ApplyAddresses -> operation.executionPlanOf(
                *operation.addresses.map { address ->
                    if (address.substringBefore("/").contains(":")) {
                        executionStep(
                            binary = CommandBinary.IFCONFIG,
                            arguments = listOf(operation.interfaceName, "inet6", address, "add"),
                        )
                    } else {
                        executionStep(
                            binary = CommandBinary.IFCONFIG,
                            arguments = listOf(operation.interfaceName, "inet", address, "alias"),
                        )
                    }
                }.toTypedArray(),
            )

            is ApplyRoutes -> operation.executionPlanOf(
                *operation.routes.flatMap { route ->
                    listOf(
                        executionStep(
                            binary = CommandBinary.ROUTE,
                            arguments = listOf("-n", "delete", "-net", route, "-interface", operation.interfaceName),
                            acceptedExitCodes = setOf(0, 1),
                        ),
                        executionStep(
                            binary = CommandBinary.ROUTE,
                            arguments = listOf("-n", "add", "-net", route, "-interface", operation.interfaceName),
                        ),
                    )
                }.toTypedArray(),
            )

            is ApplyDns -> {
                val resolverPath = "State:/Network/Service/${operation.interfaceName}/DNS"
                val resolverRootPath = "State:/Network/Service/${operation.interfaceName}"
                val dnsServers = operation.dnsDomainPool.second
                    .map { server -> server.trim() }
                    .filter { server -> server.isNotBlank() }
                    .distinct()
                val domains = operation.dnsDomainPool.first
                    .map { domain -> domain.trim().removePrefix(".") }
                    .filter { domain -> domain.isNotBlank() }
                    .distinct()

                val steps = mutableListOf(
                    executionStep(
                        binary = CommandBinary.SCUTIL,
                        stdin = buildString {
                            appendLine("remove $resolverPath")
                            appendLine("remove $resolverRootPath")
                            appendLine("quit")
                        },
                    ),
                )

                if (domains.isNotEmpty() && dnsServers.isNotEmpty()) {
                    steps += executionStep(
                        binary = CommandBinary.SCUTIL,
                        stdin = buildString {
                            appendLine("d.init")
                            appendLine("d.add ServerAddresses ${dnsServers.joinToString(" ")}")
                            appendLine("d.add SupplementalMatchDomains ${domains.joinToString(" ")}")
                            appendLine("set $resolverPath")
                            appendLine("quit")
                        },
                    )
                    steps += executionStep(
                        binary = CommandBinary.SCUTIL,
                        stdin = buildString {
                            appendLine("d.init")
                            appendLine("d.add UserDefinedName ${operation.interfaceName}")
                            appendLine("set $resolverRootPath")
                            appendLine("quit")
                        },
                    )
                }

                operation.executionPlanOf(*steps.toTypedArray())
            }

            is ReadInterfaceInformation -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IFCONFIG,
                    arguments = listOf(operation.interfaceName),
                ),
            )
        }
            }

}
