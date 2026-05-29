package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.util.normalizedKey
import com.rafambn.wgkotlin.util.toCidrString

internal fun requireUserspacePeerEndpoints(peers: List<ParsedVpnPeer>) {
    peers.forEachIndexed { index, peer ->
        require(peer.endpointAddress != null || peer.endpointHost != null) {
            "Peer `${peer.publicKey}` at index $index must define endpointAddress or endpointHost for BORINGTUN userspace runtime"
        }
        require(peer.endpointPort != null) {
            "Peer `${peer.publicKey}` at index $index must define endpointPort for BORINGTUN userspace runtime"
        }
    }
}

internal fun requireDistinctAllowedIpOwnership(peers: List<ParsedVpnPeer>) {
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

internal fun requireValidRegex(interfaceName: String) {
    val interfaceNameRegex = Regex("utun[0-9]+")
    require(interfaceNameRegex.matches(interfaceName)) {
        "Interface name must match `${interfaceNameRegex.pattern}`."
    }
}

internal fun requireUniqueParsedPeerPublicKeys(peers: List<ParsedVpnPeer>) {
    val duplicatedKeys = peers
        .groupingBy { peer -> peer.publicKey }
        .eachCount()
        .filterValues { count -> count > 1 }
        .keys

    require(duplicatedKeys.isEmpty()) {
        "Peer public keys must be unique. Duplicated keys: ${duplicatedKeys.joinToString()}"
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

internal fun requireValidConfiguration(config: VpnConfiguration) {
    requireNonBlankInterfaceName(config.interfaceName)
    requireValidRegex(config.interfaceName)
    requireUniquePeerPublicKeys(config.peers)
}
