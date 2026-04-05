package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class ApplyRoutesResponse(
    val interfaceName: String,
    val routes: List<String>,
)
