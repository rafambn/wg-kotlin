package com.rafambn.kmpvpn

/**
 * WireGuard session/runtime configuration consumed by session and userspace runtime code.
 */
interface VpnAdapterConfiguration {

    val listenPort: Int?

    val privateKey: String

    //TODO(Create default key generator)
    //return Keys.pubkeyBase64(privateKey()).getBase64PublicKey()
    val publicKey: String

    val peers: List<VpnPeer>
}
