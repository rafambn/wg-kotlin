package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnConfiguration

internal interface CryptoSessionManager {

    fun createSessions(config: VpnConfiguration)

    fun start(onFailure: (Throwable) -> Unit)

    suspend fun stop()

    fun peerStats(): List<VpnPeerStats>

    fun hasActiveSessions(): Boolean
}
