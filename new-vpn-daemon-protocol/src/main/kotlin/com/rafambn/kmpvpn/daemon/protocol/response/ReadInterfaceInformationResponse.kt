package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class ReadInterfaceInformationResponse(
    val interfaceName: String,
    val dump: String = "",
)
