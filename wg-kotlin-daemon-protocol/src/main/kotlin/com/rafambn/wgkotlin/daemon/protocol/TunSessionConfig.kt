package com.rafambn.wgkotlin.daemon.protocol

import kotlinx.serialization.Serializable

@Serializable
data class TunSessionConfig(
    val interfaceName: String,
    val mtu: Int? = null,
    val addresses: List<String> = emptyList(),
    val routes: List<String> = emptyList(),
    val dns: DnsConfig = DnsConfig(),
    val endpoints: List<String> = emptyList(),
)
