package com.rafambn.kmpvpn

interface VpnAdapterConfiguration {

    val listenPort: Int?

    val privateKey: String

    //TODO(Create default key generator)
    //return Keys.pubkeyBase64(privateKey()).getBase64PublicKey()
    val publicKey: String

    val peers: List<VpnPeer>
}

open class DefaultVpnAdapterConfiguration(
    override val listenPort: Int? = null,
    override val privateKey: String, // Keys.genkey().getBase64PrivateKey()
    override val publicKey: String,
    override val peers: List<VpnPeer> = emptyList()
) : VpnAdapterConfiguration
