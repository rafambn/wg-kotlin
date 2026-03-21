package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class ApplyMtuResponse(
    val interfaceName: String,
    val mtu: Int?,
)
