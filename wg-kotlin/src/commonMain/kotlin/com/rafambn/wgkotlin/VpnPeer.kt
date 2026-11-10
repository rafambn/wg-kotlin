package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip

/**
 * Peer configuration with a resolved endpoint IP and parsed allowed networks.
 */
data class VpnPeer(
    val endpointPort: Int? = null,
    val endpointAddress: Ip? = null,
    val publicKey: String,
    val allowedIps: List<Cidr> = emptyList(),
    val persistentKeepalive: Int? = null,
    val presharedKey: String? = null,
)
