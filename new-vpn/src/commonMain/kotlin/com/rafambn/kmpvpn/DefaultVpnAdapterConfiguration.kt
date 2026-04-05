package com.rafambn.kmpvpn

open class DefaultVpnAdapterConfiguration(
    override val listenPort: Int? = null,
    override val privateKey: String, // Keys.genkey().getBase64PrivateKey()
    override val publicKey: String,
    override val peers: List<VpnPeer> = emptyList(),
) : VpnAdapterConfiguration
