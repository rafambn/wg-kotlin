package com.rafambn.wgkotlin.daemon.protocol

import kotlinx.serialization.Serializable

@Serializable
data class TunSessionConfig(
    val interfaceName: String,
    val mtu: Int? = null,
    val addresses: List<String> = emptyList(),
    val peerAllowedIps: List<String> = emptyList(),
    val dns: DnsConfig = DnsConfig(),
    val peerEndpoints: List<String> = emptyList(),
)
