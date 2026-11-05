package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.resolveToIp
import com.rafambn.wgkotlin.util.toCidr
import com.rafambn.wgkotlin.util.toCidrString
import com.rafambn.wgkotlin.daemon.proto.DnsConfig as ProtoDnsConfig

internal data class ParsedVpnConfiguration(
    val interfaceName: String,
    val dns: ParsedDnsConfig = ParsedDnsConfig(),
    val mtu: Int? = null,
    val addresses: List<Cidr> = emptyList(),
    val listenPort: Int? = null,
    val privateKey: String,
    val peers: List<ParsedVpnPeer> = emptyList(),
)

internal data class ParsedDnsConfig(
    val searchDomains: List<String> = emptyList(),
    val servers: List<Ip> = emptyList(),
)

internal data class ParsedVpnPeer(
    val endpointPort: Int? = null,
    val endpointAddress: Ip? = null,
    val endpointHost: String? = null,
    val publicKey: String,
    val allowedIps: List<Cidr> = emptyList(),
    val persistentKeepalive: Int? = null,
    val presharedKey: String? = null,
)

internal fun VpnConfiguration.toParsedVpnConfiguration(): ParsedVpnConfiguration = ParsedVpnConfiguration(
    interfaceName = interfaceName,
    dns = dns.toParsedDnsConfig(),
    mtu = mtu,
    addresses = addresses.map { it.toCidr() },
    listenPort = listenPort,
    privateKey = privateKey,
    peers = peers.map { it.toParsedVpnPeer() },
)


internal fun DnsConfig.toParsedDnsConfig(): ParsedDnsConfig = ParsedDnsConfig(
    searchDomains = searchDomains,
    servers = servers.map { it.resolveToIp() },
)


internal fun VpnPeer.toParsedVpnPeer(): ParsedVpnPeer {
    val parsedIp = endpointAddress?.let { it.resolveToIp() }
    return ParsedVpnPeer(
        endpointPort = endpointPort,
        endpointAddress = parsedIp,
        endpointHost = null,
        publicKey = publicKey,
        allowedIps = allowedIps.map { it.toCidr() },
        persistentKeepalive = persistentKeepalive,
        presharedKey = presharedKey,
    )
}


internal fun ParsedVpnConfiguration.toTunSessionConfig(): TunSessionConfig {
    val routes = peers
        .flatMap { peer -> peer.allowedIps }
        .distinct()
        .sortedBy { it.toCidrString() }

    val endpoints = peers.mapNotNull { peer -> peer.endpointAddress }.distinct()

    return TunSessionConfig {
        interfaceName = this@toTunSessionConfig.interfaceName
        mtu = this@toTunSessionConfig.mtu ?: 0
        addresses = this@toTunSessionConfig.addresses
        this.peerAllowedIps = routes
        dns = ProtoDnsConfig {
            searchDomains = this@toTunSessionConfig.dns.searchDomains
            servers = this@toTunSessionConfig.dns.servers
        }
        this.peerEndpoints = endpoints
    }
}
