package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class ApplyPeerConfigurationResponse(
    val interfaceName: String,
    val peersCount: Int,
)
