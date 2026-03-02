package com.rafambn.kmpvpn

/**
 * Represents a VPN peer configuration
 */
data class VpnPeer(
    val publicKey: String,
    val allowedIps: List<String> = emptyList(),
    val endpoint: String? = null,
    val persistentKeepalive: Int = 0,
    val presharedKey: String? = null
)
