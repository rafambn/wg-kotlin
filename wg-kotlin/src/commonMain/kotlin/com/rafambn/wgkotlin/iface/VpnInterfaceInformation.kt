package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.crypto.VpnPeerStats

/**
 * Read-only interface information returned by [InterfaceManager.information].
 */
data class VpnInterfaceInformation(
    val interfaceName: String,
    val isUp: Boolean,
    val addresses: List<String>,
    val dns: DnsConfig,
    val mtu: Int?,
    val listenPort: Int? = null,
    val peerStats: List<VpnPeerStats> = emptyList(),
    val vpnConfiguration: VpnConfiguration? = null,
)
