package com.rafambn.kmpvpn

/**
 * Full VPN configuration consumed by the orchestrator.
 */
interface VpnConfiguration {

    val interfaceName: String

    val dnsDomainPool: Pair<List<String>, List<String>>

    val mtu: Int?

    val addresses: MutableList<String>

    val listenPort: Int?

    val privateKey: String

    val peers: List<VpnPeer>

}
