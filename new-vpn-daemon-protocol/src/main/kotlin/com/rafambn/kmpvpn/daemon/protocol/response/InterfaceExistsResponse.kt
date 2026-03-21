package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class InterfaceExistsResponse(
    val interfaceName: String,
    val exists: Boolean,
)
