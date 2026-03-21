package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class ReadPeerStatsResponse(
    val interfaceName: String,
    val dump: String = "",
)
