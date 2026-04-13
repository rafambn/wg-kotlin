package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.iface.VpnPeerStats

/**
 * Contract for peer-session ownership and data-plane lifecycle used by [com.rafambn.kmpvpn.Vpn].
 */
interface TunnelManager {

    /**
     * Reconciles existing sessions with the desired configuration.
     */
    fun reconcileSessions(config: VpnConfiguration)

    /**
     * Returns snapshots for all known sessions.
     */
    fun sessionSnapshots(): List<PeerSessionSnapshot>

    /**
     * Returns the currently managed live sessions.
     */
    fun sessionEntries(): List<PeerSessionEntry>

    /**
     * Returns one session snapshot by peer public key, or `null` if missing.
     */
    fun sessionSnapshot(peerKey: String): PeerSessionSnapshot?

    /**
     * Closes one session by peer public key.
     */
    fun closePeerSession(peerKey: String)

    /**
     * Closes all known sessions.
     * Implementations should also stop any active data-plane runtime they own.
     */
    fun closeAll()

    /**
     * Starts or refreshes the owned data-plane runtime for the current interface.
     */
    fun startDataPlane(
        configuration: VpnConfiguration,
        onFailure: (Throwable) -> Unit = {},
    )

    /**
     * Returns current peer stats from the active data plane, or zeroed peer stats
     * when no data-plane-specific counters are available.
     */
    fun peerStats(): List<VpnPeerStats>
}
