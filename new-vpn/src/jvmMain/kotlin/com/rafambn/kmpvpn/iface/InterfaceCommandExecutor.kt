package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnAdapterConfiguration

/**
 * JVM boundary for privileged interface commands.
 *
 * Production implementations must proxy commands through the privileged daemon.
 * Test implementations can be in-memory.
 */
interface InterfaceCommandExecutor {

    fun interfaceExists(interfaceName: String): Boolean

    fun createInterface(interfaceName: String)

    fun deleteInterface(interfaceName: String)

    fun setInterfaceUp(interfaceName: String, up: Boolean)

    fun applyMtu(interfaceName: String, mtu: Int?)

    fun applyAddresses(interfaceName: String, addresses: List<String>)

    fun applyRoutes(interfaceName: String, routes: List<String>, table: String?)

    fun applyDns(interfaceName: String, dnsServers: List<String>)

    fun applyPeerConfiguration(interfaceName: String, adapter: VpnAdapterConfiguration)

    fun readInformation(interfaceName: String): VpnInterfaceInformation?

    fun readPeerStats(interfaceName: String): List<VpnPeerStats>
}
