package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnConfiguration

internal interface CryptoSessionManager {

    fun reconcileSessions(config: VpnConfiguration)

    fun start(onFailure: (Throwable) -> Unit)

    fun stop()

    fun peerStats(): List<VpnPeerStats>

    fun hasActiveSessions(): Boolean
}
