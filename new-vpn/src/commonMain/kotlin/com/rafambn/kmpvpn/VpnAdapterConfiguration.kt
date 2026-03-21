package com.rafambn.kmpvpn

/**
 * WireGuard runtime configuration owned by [com.rafambn.kmpvpn.iface.VpnInterface].
 */
interface VpnAdapterConfiguration {

    val listenPort: Int?

    val privateKey: String

    //TODO(Create default key generator)
    //return Keys.pubkeyBase64(privateKey()).getBase64PublicKey()
    val publicKey: String

    val fwMark: Int?

    val peers: List<VpnPeer>
}
