package com.rafambn.kmpvpn.daemon.protocol.request

import com.rafambn.kmpvpn.daemon.protocol.request.PeerRequest
import kotlinx.serialization.Serializable

@Serializable
data class ApplyPeerConfigurationRequest(
    val interfaceName: String,
    val listenPort: Int? = null,
    val privateKey: String? = null,
    val publicKey: String? = null,
    val fwMark: Int? = null,
    val peers: List<PeerRequest> = emptyList(),
)