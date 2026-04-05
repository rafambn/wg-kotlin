package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnAdapterConfiguration

/**
 * Contract for transport session ownership used by [com.rafambn.kmpvpn.Vpn].
 */
interface SessionManager {

    /**
     * Reconciles existing sessions with the desired configuration.
     */
    fun reconcileSessions(config: VpnAdapterConfiguration)

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
     */
    fun closeAll()
}
