package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke


// "1.2.3.4" -> "1.2.3.4"
// "fd00::1" -> "fd00::1"
// "google.com -> "8.8.8.8"
internal expect fun resolveEndpointAddress(address: String): String

// "1.2.3.4" -> Ip
// "fd00::1" -> Ip
// "google.com -> Ip
internal expect fun resolveEndpointAddressToBytes(address: String): Ip

// Ip.Value -> "10.0.0.1" / "[fd00::1]"
internal fun Ip.Value.toAddressString(): String = when (this) {
    is Ip.Value.V4 -> value.toByteArray().joinToString(".") { (it.toInt() and 0xff).toString() }
    is Ip.Value.V6 -> {
        val raw = value.toByteArray()
        val segments = raw.toList().chunked(2).map { (hi, lo) ->
            (hi.toInt().and(0xff).shl(8) or lo.toInt().and(0xff)).toString(16)
        }
        "[${segments.joinToString(":")}]"
    }
}

// Ip.Value -> "10.0.0.1" / "fd00::1"
internal fun Ip.Value.toPlainString(): String = when (this) {
    is Ip.Value.V4 -> value.toByteArray().joinToString(".") { (it.toInt() and 0xff).toString() }
    is Ip.Value.V6 -> {
        val raw = value.toByteArray()
        raw.toList().chunked(2).map { (hi, lo) ->
            (hi.toInt().and(0xff).shl(8) or lo.toInt().and(0xff)).toString(16)
        }.joinToString(":")
    }
}

// Ip -> "10.0.0.1" / "fd00::1"
internal fun Ip.toPlainString(): String = (value ?: error("Ip without value field")).toPlainString()

// Cidr -> "10.0.0.1/24" / "fd00::1/64"
internal fun Cidr.toCidrString(): String = "${ip.toPlainString()}/$prefix"

// Ip -> Cidr (with this prefix)
internal fun Ip.toCidr(prefix: UInt): Cidr = Cidr { this.ip = this@toCidr; this.prefix = prefix }

internal fun String.toCidr(): Cidr {
    val parts = split("/", limit = 2)
    val ip = resolveEndpointAddressToBytes(parts[0])
    val prefix = if (parts.size == 2) {
        val p = parts[1].toUIntOrNull() ?: throw IllegalArgumentException("Invalid prefix: '${parts[1]}'")
        val maxPrefix = when (ip.value) {
            is Ip.Value.V4 -> 32u
            is Ip.Value.V6 -> 128u
            else -> error("Unknown IP type")
        }
        require(p in 0u..maxPrefix) { "Prefix $p out of range for ${ip.value?.let { it::class.simpleName }}" }
        p
    } else {
        when (ip.value) {
            is Ip.Value.V4 -> 32u
            is Ip.Value.V6 -> 128u
            else -> error("Unknown IP type")
        }
    }
    return Cidr {
        this.ip = ip
        this.prefix = prefix
    }
}