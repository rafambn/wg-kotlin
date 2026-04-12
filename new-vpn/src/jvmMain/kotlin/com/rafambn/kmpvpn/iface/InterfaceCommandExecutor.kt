package com.rafambn.kmpvpn.iface

/**
 * JVM boundary for privileged interface commands.
 *
 * Production implementations must proxy commands through the privileged daemon.
 * Peer/session state is intentionally excluded from this contract.
 */
interface InterfaceCommandExecutor {

    fun interfaceExists(interfaceName: String): Boolean

    fun setInterfaceUp(interfaceName: String, up: Boolean)

    fun applyMtu(interfaceName: String, mtu: Int)

    fun applyAddresses(interfaceName: String, addresses: List<String>)

    fun applyRoutes(interfaceName: String, routes: List<String>)

    fun applyDns(interfaceName: String, dnsDomainPool: Pair<List<String>, List<String>>)

    fun readInformation(interfaceName: String): VpnInterfaceInformation?

    fun deleteInterface(interfaceName: String)
}
