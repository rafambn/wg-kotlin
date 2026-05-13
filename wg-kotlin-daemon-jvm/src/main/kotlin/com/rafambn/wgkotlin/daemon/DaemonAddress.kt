package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.UsageError
import io.netty.util.NetUtil
import java.net.InetAddress

internal fun bindAddressOrUsageError(host: String): InetAddress {
    val normalizedHost = host.trim().removeSurrounding("[", "]")
    val addressBytes = NetUtil.createByteArrayFromIpAddressString(normalizedHost)
    return if (addressBytes == null) {
        throw UsageError("Daemon host `$host` is not a valid bind address.")
    } else {
        InetAddress.getByAddress(addressBytes)
    }
}
