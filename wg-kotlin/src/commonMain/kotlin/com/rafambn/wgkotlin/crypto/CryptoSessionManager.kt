package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.ParsedVpnConfiguration

internal interface CryptoSessionManager {

    fun reconcileSessions(config: ParsedVpnConfiguration)

    fun start(onFailure: (Throwable) -> Unit)

    fun stop()

    fun peerStats(): List<VpnPeerStats>

    fun hasActiveSessions(): Boolean
}
