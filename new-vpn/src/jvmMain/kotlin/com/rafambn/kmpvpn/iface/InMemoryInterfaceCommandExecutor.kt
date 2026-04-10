package com.rafambn.kmpvpn.iface

/**
 * Non-privileged JVM command executor used by tests.
 */
class InMemoryInterfaceCommandExecutor : InterfaceCommandExecutor {
    private val interfaces: LinkedHashMap<String, InterfaceState> = linkedMapOf()
    private val observedInterfaces: MutableSet<String> = linkedSetOf()

    val callLog: MutableList<String> = mutableListOf()

    override fun interfaceExists(interfaceName: String): Boolean {
        callLog += "interfaceExists:$interfaceName"
        return observedInterfaces.contains(interfaceName)
    }

    override fun setInterfaceUp(interfaceName: String, up: Boolean) {
        callLog += "setInterfaceUp:$interfaceName:$up"
        val state = stateFor(interfaceName)
        state.isUp = up
    }

    override fun applyMtu(interfaceName: String, mtu: Int?) {
        callLog += "applyMtu:$interfaceName:${mtu ?: "none"}"
        val state = stateFor(interfaceName)
        state.mtu = mtu
    }

    override fun applyAddresses(interfaceName: String, addresses: List<String>) {
        callLog += "applyAddresses:$interfaceName:${addresses.joinToString(",")}"
        val state = stateFor(interfaceName)
        state.addresses = addresses.toList()
    }

    override fun applyRoutes(interfaceName: String, routes: List<String>) {
        callLog += "applyRoutes:$interfaceName:${routes.joinToString(",")}"
        val state = stateFor(interfaceName)
        state.routes = routes.toList()
    }

    override fun applyDns(interfaceName: String, dnsDomainPool: Pair<List<String>, List<String>>) {
        callLog += "applyDns:$interfaceName:domains=${dnsDomainPool.first.joinToString(",")};dns=${dnsDomainPool.second.joinToString(",")}"
        val state = stateFor(interfaceName)
        state.dnsDomainPool = dnsDomainPool.first.toList() to dnsDomainPool.second.toList()
    }

    override fun readInformation(interfaceName: String): VpnInterfaceInformation? {
        callLog += "readInformation:$interfaceName"
        val state = interfaces[interfaceName] ?: return null
        return VpnInterfaceInformation(
            interfaceName = interfaceName,
            isUp = state.isUp,
            addresses = state.addresses.toList(),
            dnsDomainPool = state.dnsDomainPool.first.toList() to state.dnsDomainPool.second.toList(),
            mtu = state.mtu,
            peerStats = state.peerStats.toList(),
        )
    }

    override fun deleteInterface(interfaceName: String) {
        callLog += "deleteInterface:$interfaceName"
        interfaces.remove(interfaceName)
        observedInterfaces.remove(interfaceName)
    }

    fun setPeerStats(interfaceName: String, peerStats: List<VpnPeerStats>) {
        val state = stateFor(interfaceName)
        state.peerStats = peerStats.toList()
    }

    private fun stateFor(interfaceName: String): InterfaceState {
        observedInterfaces += interfaceName
        return interfaces.getOrPut(interfaceName) { InterfaceState() }
    }

    private class InterfaceState {
        var isUp: Boolean = false
        var mtu: Int? = null
        var addresses: List<String> = emptyList()
        var routes: List<String> = emptyList()
        var dnsDomainPool: Pair<List<String>, List<String>> = (emptyList<String>() to emptyList())
        var peerStats: List<VpnPeerStats> = emptyList()
    }
}
