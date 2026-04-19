package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe

/**
 * Owns peer crypto sessions and the three data-plane worker coroutines.
 *
 * Cleartext ingress: reads raw IP packets from [tunPipe], encrypts per peer,
 * forwards to [networkPipe].
 *
 * Encrypted ingress: reads [UdpDatagram] from [networkPipe], decrypts per peer,
 * forwards to [tunPipe].
 *
 * Periodic: runs keepalive and rekey tasks for every active session.
 */
interface CryptoSessionManager {

    /**
     * Reconciles the set of active peer sessions against [config].
     * Reuses unchanged sessions; creates new ones for added or changed peers;
     * closes removed sessions.
     */
    fun reconcileSessions(config: VpnConfiguration)

    /**
     * Starts the three worker coroutines bridging [tunPipe] and [networkPipe].
     * Stops any previously running workers first.
     * [onFailure] is invoked on any unrecoverable worker error.
     */
    fun start(
        tunPipe: DuplexChannelPipe<ByteArray>,
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit,
    )

    /**
     * Cancels all worker coroutines and closes every peer session.
     */
    fun stop()

    /**
     * Returns current per-peer byte counters from the active runtime.
     */
    fun peerStats(): List<VpnPeerStats>

    /**
     * Returns true when at least one managed session is currently active.
     */
    fun hasActiveSessions(): Boolean
}
