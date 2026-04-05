package com.rafambn.kmpvpn

internal fun requireUserspacePeerEndpoints(peers: List<VpnPeer>) {
    peers.forEachIndexed { index, peer ->
        require(!peer.endpointAddress.isNullOrBlank()) {
            "Peer `${peer.publicKey}` at index $index must define endpointAddress for BORINGTUN userspace runtime"
        }
        require(peer.endpointPort != null) {
            "Peer `${peer.publicKey}` at index $index must define endpointPort for BORINGTUN userspace runtime"
        }
    }
}

internal fun requireDistinctAllowedIpOwnership(peers: List<VpnPeer>) {
    val ownershipByNetwork: MutableMap<String, String> = linkedMapOf()

    peers.forEach { peer ->
        peer.allowedIps.forEach { allowedIp ->
            val parsed = parseCidr(allowedIp)
                ?: throw IllegalArgumentException("Peer `${peer.publicKey}` has invalid allowed IP `$allowedIp`")
            val key = parsed.normalizedKey()
            val previousOwner = ownershipByNetwork.putIfAbsent(key, peer.publicKey)
            require(previousOwner == null || previousOwner == peer.publicKey) {
                "Allowed IP `${allowedIp}` overlaps ambiguously between peers `$previousOwner` and `${peer.publicKey}`"
            }
        }
    }
}

internal fun requireNonBlankInterfaceName(interfaceName: String) {
    require(interfaceName.isNotBlank()) {
        "Interface name cannot be empty"
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
    requireUniquePeerPublicKeys(config.adapter.peers)
}
