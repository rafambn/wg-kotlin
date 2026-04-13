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
     * Returns whether any managed session is currently active.
     */
    fun hasActiveSessions(): Boolean

    /**
     * Closes all known sessions.
     * Implementations should also stop any active data-plane runtime they own.
     */
    fun closeAll()

    /**
     * Starts or refreshes the owned data-plane runtime for the current interface.
     */
    fun startDataPlane(configuration: VpnConfiguration)

    /**
     * Returns current peer stats from the active data plane, or zeroed peer stats
     * when no data-plane-specific counters are available.
     */
    fun peerStats(): List<VpnPeerStats>
}
