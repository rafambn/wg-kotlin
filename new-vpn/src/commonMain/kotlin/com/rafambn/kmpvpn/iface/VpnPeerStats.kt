package com.rafambn.kmpvpn.iface

/**
 * Read-only runtime counters for a configured VPN peer.
 */
data class VpnPeerStats(
    val publicKey: String,
    val receivedBytes: Long,
    val transmittedBytes: Long,
    val lastHandshakeEpochSeconds: Long?,
)
