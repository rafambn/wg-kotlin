package com.rafambn.kmpvpn.daemon.planner

import com.rafambn.kmpvpn.daemon.command.CommandBinary

internal class LinuxOperationPlanner : PlatformOperationPlanner {
    override val platformId: String = "linux"
    override val requiredBinaries: Set<CommandBinary> = setOf(
        CommandBinary.IP,
        CommandBinary.RESOLVECTL,
    )

    override fun plan(operation: DaemonOperation): ExecutionPlan {
        return when (operation) {
            is InterfaceExists -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IP,
                    arguments = listOf("link", "show", "dev", operation.interfaceName),
                    acceptedExitCodes = setOf(0, 1),
                ),
            )

            is SetInterfaceState -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IP,
                    arguments = listOf("link", "set", "dev", operation.interfaceName, if (operation.up) "up" else "down"),
                ),
            )

            is ApplyMtu -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IP,
                    arguments = listOf("link", "set", "dev", operation.interfaceName, "mtu", operation.mtu.toString()),
                ),
            )

            is ApplyAddresses -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IP,
                    arguments = listOf("address", "flush", "dev", operation.interfaceName),
                ),
                *operation.addresses.map { address ->
                    executionStep(
                        binary = CommandBinary.IP,
                        arguments = listOf("address", "add", address, "dev", operation.interfaceName),
                    )
                }.toTypedArray(),
            )

            is ApplyRoutes -> operation.executionPlanOf(
                *operation.routes.map { route ->
                    executionStep(
                        binary = CommandBinary.IP,
                        arguments = listOf("route", "replace", route, "dev", operation.interfaceName),
                    )
                }.toTypedArray(),
            )

            is ApplyDns -> {
                val routingDomains = operation.dnsDomainPool.first
                    .map { domain -> domain.trim() }
                    .filter { domain -> domain.isNotBlank() }
                    .distinct()
                    .map { domain -> "~${domain.removePrefix(".")}" }
                val dnsServers = operation.dnsDomainPool.second
                    .map { server -> server.trim() }
                    .filter { server -> server.isNotBlank() }
                    .distinct()

                if (routingDomains.isEmpty() || dnsServers.isEmpty()) {
                    operation.executionPlanOf(
                        executionStep(
                            binary = CommandBinary.RESOLVECTL,
                            arguments = listOf("revert", operation.interfaceName),
                        ),
                    )
                } else {
                    operation.executionPlanOf(
                        executionStep(
                            binary = CommandBinary.RESOLVECTL,
                            arguments = listOf("dns", operation.interfaceName) + dnsServers,
                        ),
                        executionStep(
                            binary = CommandBinary.RESOLVECTL,
                            arguments = listOf("domain", operation.interfaceName) + routingDomains,
                        ),
                    )
                }
            }

            is ReadInterfaceInformation -> operation.executionPlanOf(
                executionStep(
                    binary = CommandBinary.IP,
                    arguments = listOf("-details", "link", "show", "dev", operation.interfaceName),
                ),
            )
        }
    }

}
