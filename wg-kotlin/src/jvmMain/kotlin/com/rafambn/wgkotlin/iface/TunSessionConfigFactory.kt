package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.daemon.proto.DnsConfig
import com.rafambn.wgkotlin.daemon.proto.IpAddr
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.network.resolveEndpointAddress
import kotlinx.io.bytestring.ByteString
import java.net.InetAddress

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
        addresses = this@toTunSessionConfig.addresses.map { it.toIpAddr() }
        this.peerAllowedIps = routes.map { it.toIpAddr() }
        dns = DnsConfig {
            searchDomains = this@toTunSessionConfig.dns.searchDomains
            servers = this@toTunSessionConfig.dns.servers.map { it.toIpAddr() }
        }
        this.peerEndpoints = endpoints.map { it.toIpAddr() }
    }
}

private fun String.toIpAddr(): IpAddr {
    val slash = indexOf('/')
    val ipStr = if (slash < 0) this else substring(0, slash)
    val prefixStr = if (slash < 0) null else substring(slash + 1)
    val addr = InetAddress.getByName(ipStr)
    return IpAddr {
        ip = when {
            addr.address.size == 4 -> IpAddr.Ip.V4(ByteString(addr.address))
            addr.address.size == 16 -> IpAddr.Ip.V6(ByteString(addr.address))
            else -> error("unsupported address size: ${addr.address.size}")
        }
        prefixStr?.toUIntOrNull()?.let { prefix = it }
    }
}
