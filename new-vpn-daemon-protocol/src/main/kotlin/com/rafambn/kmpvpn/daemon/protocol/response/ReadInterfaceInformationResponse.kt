package com.rafambn.kmpvpn.daemon.protocol.response

import kotlinx.serialization.Serializable

@Serializable
data class ReadInterfaceInformationResponse(
    val interfaceName: String,
    val isUp: Boolean,
    val addresses: List<String> = emptyList(),
    val dnsDomainPool: Pair<List<String>, List<String>> = Pair(emptyList(), emptyList()),
    val mtu: Int? = null,
    val listenPort: Int? = null,
)
