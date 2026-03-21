package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnAdapterConfiguration

/**
 * Non-privileged JVM executor used by tests.
 */
class InMemoryInterfaceCommandExecutor : InterfaceCommandExecutor {
    private val interfaces: LinkedHashMap<String, InterfaceState> = linkedMapOf()

    val callLog: MutableList<String> = mutableListOf()

    override fun interfaceExists(interfaceName: String): Boolean {
        callLog += "interfaceExists:$interfaceName"
        return interfaces.containsKey(interfaceName)
    }

    override fun createInterface(interfaceName: String) {
        callLog += "createInterface:$interfaceName"
        interfaces.putIfAbsent(interfaceName, InterfaceState())
    }

    override fun deleteInterface(interfaceName: String) {
        callLog += "deleteInterface:$interfaceName"
        interfaces.remove(interfaceName)
    }

    override fun setInterfaceUp(interfaceName: String, up: Boolean) {
        callLog += "setInterfaceUp:$interfaceName:$up"
        val state = requireState(interfaceName)
        state.isUp = up
    }

    override fun applyMtu(interfaceName: String, mtu: Int?) {
        callLog += "applyMtu:$interfaceName:${mtu ?: "none"}"
        val state = requireState(interfaceName)
        state.mtu = mtu
    }

    override fun applyAddresses(interfaceName: String, addresses: List<String>) {
        callLog += "applyAddresses:$interfaceName:${addresses.joinToString(",")}"
        val state = requireState(interfaceName)
        state.addresses = addresses.toList()
    }

    override fun applyRoutes(interfaceName: String, routes: List<String>, table: String?) {
        callLog += "applyRoutes:$interfaceName:${routes.joinToString(",")}:${table ?: "default"}"
        val state = requireState(interfaceName)
        state.routes = routes.toList()
        state.table = table
    }

    override fun applyDns(interfaceName: String, dnsServers: List<String>) {
        callLog += "applyDns:$interfaceName:${dnsServers.joinToString(",")}"
        val state = requireState(interfaceName)
        state.dnsServers = dnsServers.toList()
    }

    override fun applyPeerConfiguration(interfaceName: String, adapter: VpnAdapterConfiguration) {
        callLog += "applyPeerConfiguration:$interfaceName:${adapter.peers.size}"
        val state = requireState(interfaceName)
        state.peerStats = adapter.peers.map { peer ->
            VpnPeerStats(
                publicKey = peer.publicKey,
                receivedBytes = state.peerStatsByPublicKey[peer.publicKey]?.receivedBytes ?: 0L,
                transmittedBytes = state.peerStatsByPublicKey[peer.publicKey]?.transmittedBytes ?: 0L,
                lastHandshakeEpochSeconds = state.peerStatsByPublicKey[peer.publicKey]?.lastHandshakeEpochSeconds,
            )
        }
        state.peerStatsByPublicKey = state.peerStats.associateBy { stats -> stats.publicKey }.toMutableMap()
    }

    override fun readInformation(interfaceName: String): VpnInterfaceInformation? {
        callLog += "readInformation:$interfaceName"
        val state = interfaces[interfaceName] ?: return null
        return VpnInterfaceInformation(
            interfaceName = interfaceName,
            isUp = state.isUp,
            addresses = state.addresses.toList(),
            dnsServers = state.dnsServers.toList(),
            mtu = state.mtu,
            peerStats = state.peerStats.toList(),
        )
    }

    override fun readPeerStats(interfaceName: String): List<VpnPeerStats> {
        callLog += "readPeerStats:$interfaceName"
        return interfaces[interfaceName]?.peerStats?.toList().orEmpty()
    }

    fun setPeerStats(interfaceName: String, peerStats: List<VpnPeerStats>) {
        val state = requireState(interfaceName)
        state.peerStats = peerStats.toList()
        state.peerStatsByPublicKey = peerStats.associateBy { stats -> stats.publicKey }.toMutableMap()
    }

    private fun requireState(interfaceName: String): InterfaceState {
        return interfaces[interfaceName]
            ?: throw IllegalStateException("Interface `$interfaceName` was not created")
    }

    private class InterfaceState {
        var isUp: Boolean = false
        var mtu: Int? = null
        var addresses: List<String> = emptyList()
        var routes: List<String> = emptyList()
        var table: String? = null
        var dnsServers: List<String> = emptyList()
        var peerStats: List<VpnPeerStats> = emptyList()
        var peerStatsByPublicKey: MutableMap<String, VpnPeerStats> = linkedMapOf()
    }
}
