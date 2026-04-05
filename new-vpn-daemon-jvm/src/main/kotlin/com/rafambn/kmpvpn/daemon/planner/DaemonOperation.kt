package com.rafambn.kmpvpn.daemon.planner

internal sealed interface DaemonOperation {
    val label: String
    val interfaceName: String
}

internal data class InterfaceExists(
    override val interfaceName: String,
) : DaemonOperation {
    override val label: String = "INTERFACE_EXISTS"
}

internal data class SetInterfaceState(
    override val interfaceName: String,
    val up: Boolean,
) : DaemonOperation {
    override val label: String = "SET_INTERFACE_STATE"
}

internal data class ApplyMtu(
    override val interfaceName: String,
    val mtu: Int,
) : DaemonOperation {
    override val label: String = "APPLY_MTU"
}

internal data class ApplyAddresses(
    override val interfaceName: String,
    val addresses: List<String>,
) : DaemonOperation {
    override val label: String = "APPLY_ADDRESSES"
}

internal data class ApplyRoutes(
    override val interfaceName: String,
    val routes: List<String>,
) : DaemonOperation {
    override val label: String = "APPLY_ROUTES"
}

internal data class ApplyDns(
    override val interfaceName: String,
    val dnsDomainPool: Pair<List<String>, List<String>>,
) : DaemonOperation {
    override val label: String = "APPLY_DNS"
}

internal data class ReadInterfaceInformation(
    override val interfaceName: String,
) : DaemonOperation {
    override val label: String = "READ_INTERFACE_INFORMATION"
}
