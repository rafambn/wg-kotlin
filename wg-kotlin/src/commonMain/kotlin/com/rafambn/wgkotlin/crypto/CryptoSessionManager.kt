package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.ParsedVpnConfiguration

internal interface CryptoSessionManager {

    fun reconcileSessions(config: ParsedVpnConfiguration)

    fun start(onFailure: (Throwable) -> Unit)

    /** Waits for workers before closing peer sessions. Call outside the failure callback. */
    fun stop()

    fun peerStats(): List<VpnPeerStats>

    fun hasActiveSessions(): Boolean
}
