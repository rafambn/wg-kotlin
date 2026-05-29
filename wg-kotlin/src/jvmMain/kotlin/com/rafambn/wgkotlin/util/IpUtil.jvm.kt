package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import java.net.InetAddress
import kotlinx.io.bytestring.ByteString

internal actual fun String.resolveToIpString(): String = InetAddress.getByName(this).hostAddress

internal actual fun String.resolveToIp(): Ip {
    val bytes = InetAddress.getByName(this).address
    return Ip {
        value = when (bytes.size) {
            4 -> Ip.Value.V4(ByteString(bytes))
            16 -> Ip.Value.V6(ByteString(bytes))
            else -> error("Unexpected address length: ${bytes.size}")
        }
    }
}
