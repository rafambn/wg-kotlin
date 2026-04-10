package com.rafambn.kmpvpn

class DefaultVpnConfiguration(
    override val interfaceName: String,
    override val dnsDomainPool: Pair<List<String>, List<String>> = (emptyList<String>() to emptyList()),
    override val mtu: Int? = null,
    override val addresses: MutableList<String> = mutableListOf(),
    override val listenPort: Int? = null,
    override val privateKey: String, // Keys.genkey().getBase64PrivateKey()
    override val peers: List<VpnPeer> = emptyList(),
) : VpnConfiguration {
}
