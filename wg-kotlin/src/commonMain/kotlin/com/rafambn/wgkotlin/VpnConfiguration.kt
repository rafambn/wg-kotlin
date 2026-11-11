package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.util.toTransportIp
import com.rafambn.wgkotlin.daemon.proto.DnsConfig as ProtoDnsConfig

/**
 * Full VPN configuration with IP addresses and CIDRs already parsed by the caller.
 */
data class VpnConfiguration(
    val interfaceName: String,
    val dns: DnsConfig = DnsConfig(),
    val mtu: Int? = null,
    val addresses: List<Cidr> = emptyList(),
    val listenPort: Int? = null,
    val privateKey: String,
    val peers: List<VpnPeer> = emptyList(),
)

internal fun VpnConfiguration.toTunSessionConfig(): TunSessionConfig {
    val routes = peers
        .flatMap { peer -> peer.allowedIps }
        .distinct()
        .sortedBy { it.toCidrString() }

    val endpoints = peers.mapNotNull { peer -> peer.endpointAddress?.toTransportIp() }.distinct()

    return TunSessionConfig {
        interfaceName = this@toTunSessionConfig.interfaceName
        mtu = this@toTunSessionConfig.mtu ?: 0
        addresses = this@toTunSessionConfig.addresses
        this.peerAllowedIps = routes
        dns = ProtoDnsConfig {
            servers = this@toTunSessionConfig.dns.servers
            searchDomains = this@toTunSessionConfig.dns.searchDomains
        }
        this.peerEndpoints = endpoints
    }
}
