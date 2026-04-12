package com.rafambn.kmpvpn.iface

/**
 * Non-privileged JVM command executor used by tests.
 */
class InMemoryInterfaceCommandExecutor : InterfaceCommandExecutor {
    private val interfaces: LinkedHashMap<String, InterfaceState> = linkedMapOf()

    override fun interfaceExists(interfaceName: String): Boolean {
        return interfaces.contains(interfaceName)
    }

    override fun setInterfaceUp(interfaceName: String, up: Boolean) {
        val state = stateFor(interfaceName)
        state.isUp = up
    }

    override fun applyMtu(interfaceName: String, mtu: Int) {
        val state = stateFor(interfaceName)
        state.mtu = mtu
    }

    override fun applyAddresses(interfaceName: String, addresses: List<String>) {
        val state = stateFor(interfaceName)
        state.addresses = addresses.toList()
    }

    override fun applyRoutes(interfaceName: String, routes: List<String>) {
        val state = stateFor(interfaceName)
        state.routes = routes.toList()
    }

    override fun applyDns(interfaceName: String, dnsDomainPool: Pair<List<String>, List<String>>) {
        val state = stateFor(interfaceName)
        state.dnsDomainPool = dnsDomainPool.first.toList() to dnsDomainPool.second.toList()
    }

    override fun readInformation(interfaceName: String): VpnInterfaceInformation? {
        val state = interfaces[interfaceName] ?: return null
        return VpnInterfaceInformation(
            interfaceName = interfaceName,
            isUp = state.isUp,
            addresses = state.addresses.toList(),
            dnsDomainPool = state.dnsDomainPool.first.toList() to state.dnsDomainPool.second.toList(),
            mtu = state.mtu,
            listenPort = null,
            peerStats = state.peerStats.toList(),
        )
    }

    override fun deleteInterface(interfaceName: String) {
        interfaces.remove(interfaceName)
    }

    fun setPeerStats(interfaceName: String, peerStats: List<VpnPeerStats>) {
        val state = stateFor(interfaceName)
        state.peerStats = peerStats.toList()
    }

    private fun stateFor(interfaceName: String): InterfaceState {
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
