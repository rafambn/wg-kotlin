package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.daemon.protocol.DnsConfig
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.network.resolveEndpointAddress

internal fun VpnConfiguration.toTunSessionConfig(): TunSessionConfig {
    val routes = peers
        .flatMap { peer -> peer.allowedIps }
        .filter { route -> route.isNotBlank() }
        .distinct()
        .sorted()

    val endpoints = peers
        .mapNotNull { peer -> peer.endpointAddress }
        .map { address -> resolveEndpointAddress(address) }
        .distinct()
        .sorted()

    return TunSessionConfig(
        interfaceName = interfaceName,
        mtu = mtu,
        addresses = addresses.toList(),
        routes = routes,
        dns = DnsConfig(
            searchDomains = dns.searchDomains,
            servers = dns.servers,
        ),
        endpoints = endpoints,
    )
}
