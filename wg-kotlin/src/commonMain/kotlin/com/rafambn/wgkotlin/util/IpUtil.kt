package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.IpAddr

internal fun IpAddr.Ip.toAddressString(): String = when (this) {
    is IpAddr.Ip.V4 -> value.toByteArray().joinToString(".") { (it.toInt() and 0xff).toString() }
    is IpAddr.Ip.V6 -> {
        val raw = value.toByteArray()
        val segments = raw.toList().chunked(2).map { (hi, lo) ->
            (hi.toInt().and(0xff).shl(8) or lo.toInt().and(0xff)).toString(16)
        }
        "[${segments.joinToString(":")}]"
    }
}

internal fun IpAddr.Ip.toPlainString(): String = when (this) {
    is IpAddr.Ip.V4 -> value.toByteArray().joinToString(".") { (it.toInt() and 0xff).toString() }
    is IpAddr.Ip.V6 -> {
        val raw = value.toByteArray()
        raw.toList().chunked(2).map { (hi, lo) ->
            (hi.toInt().and(0xff).shl(8) or lo.toInt().and(0xff)).toString(16)
        }.joinToString(":")
    }
}

internal fun IpAddr.toPlainString(): String = (ip ?: error("IpAddr without ip field")).toPlainString()

internal fun IpAddr.toCidrString(): String {
    val ipStr = toPlainString()
    return if (prefix != null) "$ipStr/$prefix" else ipStr
}
