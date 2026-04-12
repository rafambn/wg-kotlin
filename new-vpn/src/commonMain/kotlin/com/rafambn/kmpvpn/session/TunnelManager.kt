package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.iface.VpnPeerStats

/**
 * Contract for transport session ownership used by [com.rafambn.kmpvpn.Vpn].
 */
interface TunnelManager {

    /**
     * Reconciles existing sessions with the desired configuration.
     */
    fun reconcileSessions(config: VpnConfiguration)

    /**
     * Returns snapshots for all known sessions.
     */
    fun sessions(): List<SessionSnapshot>

    /**
     * Returns the currently managed live sessions.
     */
    fun managedSessions(): List<ManagedSession>

    /**
     * Returns one session by peer public key, or `null` if missing.
     */
    fun session(peerKey: String): SessionSnapshot?

    /**
     * Closes one session by peer public key.
     */
    fun closeSession(peerKey: String)

    /**
     * Closes all known sessions.
     * Implementations should also stop any active data-plane runtime they own.
     */
    fun closeAll()

    /**
     * Starts or refreshes the owned data-plane runtime for the current interface.
     */
    fun startRuntime(
        configuration: VpnConfiguration,
        onFailure: (Throwable) -> Unit = {},
    )

    /**
     * Returns current peer stats from the active data plane, or zeroed peer stats
     * when no runtime-specific counters are available.
     */
    fun peerStats(): List<VpnPeerStats>
}
