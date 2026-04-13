package com.rafambn.kmpvpn.session

/**
 * Snapshot of a managed peer session.
 */
data class PeerSessionSnapshot(
    val peerPublicKey: String,
    val endpointAddress: String?,
    val endpointPort: Int?,
    val allowedIps: List<String>,
    val peerIndex: Int,
    val isActive: Boolean,
)
