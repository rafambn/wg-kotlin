package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class CreateInterfaceResponse(
    val interfaceName: String,
)
