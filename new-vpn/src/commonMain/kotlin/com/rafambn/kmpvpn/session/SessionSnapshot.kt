package com.rafambn.kmpvpn.session

/**
 * Snapshot of a managed peer session.
 */
data class SessionSnapshot(
    val peerPublicKey: String,
    val endpointAddress: String?,
    val endpointPort: Int?,
    val allowedIps: List<String>,
    val sessionIndex: Int,
    val isActive: Boolean,
)
