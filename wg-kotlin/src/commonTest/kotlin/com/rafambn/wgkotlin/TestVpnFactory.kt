package com.rafambn.wgkotlin

internal fun testVpn(
    configuration: VpnConfiguration,
    engine: Engine = Engine.BORINGTUN,
): Vpn {
    return Vpn(
        configuration = normalizedTestConfiguration(configuration),
        engine = engine,
    )
}

internal fun snapshotConfiguration(config: VpnConfiguration): VpnConfiguration {
    return VpnConfiguration(
        interfaceName = config.interfaceName,
        dns = DnsConfig(
            searchDomains = config.dns.searchDomains.toList(),
            servers = config.dns.servers.toList(),
        ),
        mtu = config.mtu,
        addresses = config.addresses.toMutableList(),
        listenPort = config.listenPort,
        privateKey = config.privateKey,
        peers = config.peers.map { peer ->
            VpnPeer(
                endpointPort = peer.endpointPort,
                endpointAddress = peer.endpointAddress,
                publicKey = peer.publicKey,
                allowedIps = peer.allowedIps.toList(),
                persistentKeepalive = peer.persistentKeepalive,
                presharedKey = peer.presharedKey,
            )
        },
    )
}

private fun normalizedTestConfiguration(config: VpnConfiguration): VpnConfiguration {
    return if (config.listenPort != null) {
        config
    } else {
        snapshotConfiguration(config).copy(listenPort = 0)
    }
}
