package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import io.netty.util.NetUtil

internal data class PrimaryTunAddress(
    val address: String,
    val prefixLength: UByte,
    val family: IpFamily,
)

internal fun extractPrimaryTunAddress(config: TunSessionConfig): PrimaryTunAddress {
    val addresses = normalizeCidrs(config.addresses)
    if (addresses.isEmpty()) {
        throw IllegalArgumentException("Tun session requires at least one IPv4 or IPv6 address")
    }
    return parsePrimaryTunAddress(addresses.first())
}

private fun parsePrimaryTunAddress(cidr: String): PrimaryTunAddress {
    val parts = cidr.split("/", limit = 2)
    if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        throw IllegalArgumentException("Tun session address must use CIDR format: $cidr")
    }

    val ip = parts[0].trim()
    val prefixLength = parts[1].trim().toIntOrNull()
        ?: throw IllegalArgumentException("Tun session address prefix must be numeric: $cidr")
    val isIpv4 = NetUtil.isValidIpV4Address(ip)
    val isIpv6 = NetUtil.isValidIpV6Address(ip)
    return if (isIpv6) {
        if (prefixLength !in 0..128) {
            throw IllegalArgumentException("Tun session IPv6 prefix must be between 0 and 128: $cidr")
        } else {
            PrimaryTunAddress(
                address = ip,
                prefixLength = prefixLength.toUByte(),
                family = IpFamily.IPV6,
            )
        }
    } else if (isIpv4) {
        if (prefixLength !in 0..32) {
            throw IllegalArgumentException("Tun session IPv4 prefix must be between 0 and 32: $cidr")
        } else {
            PrimaryTunAddress(
                address = ip,
                prefixLength = prefixLength.toUByte(),
                family = IpFamily.IPV4,
            )
        }
    } else {
        throw IllegalArgumentException("Tun session address has invalid IP address: $cidr")
    }
}

internal fun normalizeCidrs(values: List<String>): List<String> {
    return values.map { value -> value.trim() }
}
