package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import java.net.InetAddress
import kotlinx.io.bytestring.ByteString

internal actual fun String.resolveToIpString(): String {
    require(isNotBlank()) { "Address cannot be blank" }
    return InetAddress.getByName(this).hostAddress
}

internal actual fun String.resolveToIp(): Ip {
    require(isNotBlank()) { "Address cannot be blank" }
    val resolvedBytes = InetAddress.getByName(this).address
    // InetAddress collapses IPv4-mapped literals. Preserve the IPv6 family for IPs and CIDRs.
    val bytes = if (contains(':') && resolvedBytes.size == 4) {
        val mappedBytes = ByteArray(16)
        mappedBytes[10] = 0xff.toByte()
        mappedBytes[11] = 0xff.toByte()
        resolvedBytes.copyInto(mappedBytes, 12)
        mappedBytes
    } else {
        resolvedBytes
    }
    return Ip {
        value = when (bytes.size) {
            4 -> Ip.Value.V4(ByteString(bytes))
            16 -> Ip.Value.V6(ByteString(bytes))
            else -> error("Unexpected address length: ${bytes.size}")
        }
    }
}
