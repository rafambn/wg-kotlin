package com.rafambn.kmpvpn.daemon.protocol.request

import kotlinx.serialization.Serializable

@Serializable
data class PeerRequest(
    val publicKey: String,
    val endpointAddress: String? = null,
    val endpointPort: Int? = null,
    val allowedIps: List<String> = emptyList(),
    val persistentKeepalive: Int? = null,
    val presharedKey: String? = null,
) {
    init {
        require(publicKey.isNotBlank()) { "Peer public key cannot be blank" }
        require(endpointAddress == null || endpointAddress.isNotBlank()) { "Peer endpoint address cannot be blank" }
        require(endpointPort == null || endpointPort in 1..65535) { "Peer endpoint port must be between 1 and 65535" }
        require(allowedIps.all { ip -> ip.isNotBlank() }) { "Allowed IP entries cannot be blank" }
        require(
            persistentKeepalive == null || persistentKeepalive in 0..65535,
        ) { "Persistent keepalive must be between 0 and 65535" }
        require(presharedKey == null || presharedKey.isNotBlank()) { "Peer preshared key cannot be blank" }
    }
}