package com.rafambn.kmpvpn


interface VpnConfiguration : VpnAdapterConfiguration {

    val interfaceName: String

    val dns: MutableList<String>

    val mtu: Int?

    val addresses: MutableList<String>

}

class DefaultVpnConfiguration(
    override val interfaceName: String,
    override val dns: MutableList<String> = mutableListOf(),
    override val mtu: Int? = null,
    override val addresses: MutableList<String> = mutableListOf(),
    override val listenPort: Int? = null,
    override val privateKey: String, // Keys.genkey().getBase64PrivateKey()
    override val publicKey: String,
    override val peers: List<VpnPeer> = emptyList()
) : DefaultVpnAdapterConfiguration(
    listenPort,
    privateKey,
    publicKey,
    peers
), VpnConfiguration
