package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class ApplyDnsResponse(
    val interfaceName: String,
    val dnsServers: List<String>,
)
