package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.daemon.proto.DnsConfig
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
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

    return TunSessionConfig {
        interfaceName = this@toTunSessionConfig.interfaceName
        mtu = this@toTunSessionConfig.mtu ?: 0
        addresses = this@toTunSessionConfig.addresses.toList()
        this.routes = routes
        dns = DnsConfig {
            searchDomains = this@toTunSessionConfig.dns.searchDomains
            servers = this@toTunSessionConfig.dns.servers
        }
        this.endpoints = endpoints
    }
}
