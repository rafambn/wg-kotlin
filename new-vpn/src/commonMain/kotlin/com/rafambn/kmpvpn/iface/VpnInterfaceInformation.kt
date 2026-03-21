package com.rafambn.kmpvpn.iface

/**
 * Read-only interface information returned by [VpnInterface.readInformation].
 */
data class VpnInterfaceInformation(
    val interfaceName: String,
    val isUp: Boolean,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val mtu: Int?,
    val peerStats: List<VpnPeerStats> = emptyList(),
)
