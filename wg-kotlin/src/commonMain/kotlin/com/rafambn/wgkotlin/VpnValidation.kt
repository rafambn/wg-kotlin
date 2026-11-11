package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.ipBytes
import com.rafambn.wgkotlin.util.normalizedKey
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.util.toPlainString
import com.rafambn.wgkotlin.util.toTransportIp

internal fun requireUserspacePeerEndpoints(peers: List<VpnPeer>) {
    val seen = mutableMapOf<UdpEndpoint, String>()
    peers.forEachIndexed { index, peer ->
        val endpoint = requireNotNull(peer.endpointAddress) {
            "Peer `${peer.publicKey}` at index $index must define endpointAddress for BORINGTUN userspace runtime"
        }
        requireNotNull(endpoint.ipBytes()) { "Peer `${peer.publicKey}` has an unset endpoint IP" }
        val port = requireNotNull(peer.endpointPort) {
            "Peer `${peer.publicKey}` at index $index must define endpointPort for BORINGTUN userspace runtime"
        }
        val transportEndpoint = UdpEndpoint(endpoint.toTransportIp().toPlainString(), port)
        val previous = seen.put(transportEndpoint, peer.publicKey)
        require(previous == null) {
            "Endpoint `${transportEndpoint.address}` port `$port` is shared by peers `$previous` and `${peer.publicKey}`"
        }
    }
}

internal fun requireDistinctAllowedIpOwnership(peers: List<VpnPeer>) {
    val seen = mutableMapOf<String, String>()
    for (peer in peers) {
        for (allowedIp in peer.allowedIps) {
            val key = allowedIp.normalizedKey()
            val previous = seen.put(key, peer.publicKey)
            if (previous != null && previous != peer.publicKey) {
                error(
                    "Allowed IP `${allowedIp.toCidrString()}` overlaps between " +
                        "`$previous` and `${peer.publicKey}`"
                )
            }
        }
    }
}

internal fun requireNonBlankInterfaceName(interfaceName: String) {
    require(interfaceName.isNotBlank()) {
        "Interface name cannot be empty"
    }
}

internal fun requireValidInterfaceName(interfaceName: String) {
    val interfaceNameRegex = Regex("utun[0-9]+")
    require(interfaceNameRegex.matches(interfaceName)) {
        "Interface name must match `${interfaceNameRegex.pattern}`."
    }
}

internal fun requireUniquePeerPublicKeys(peers: List<VpnPeer>) {
    val duplicatedKeys = peers
        .groupingBy { peer -> peer.publicKey }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys

    require(duplicatedKeys.isEmpty()) {
        "Peer public keys must be unique. Duplicated keys: ${duplicatedKeys.joinToString()}"
    }
}
