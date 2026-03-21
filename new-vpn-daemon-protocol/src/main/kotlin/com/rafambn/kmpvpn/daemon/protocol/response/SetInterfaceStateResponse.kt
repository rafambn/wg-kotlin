package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class SetInterfaceStateResponse(
    val interfaceName: String,
    val up: Boolean,
)
