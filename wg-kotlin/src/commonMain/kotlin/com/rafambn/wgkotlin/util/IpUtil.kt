package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import kotlinx.io.bytestring.ByteString

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

// "1.2.3.4" -> Ip
// "fd00::1" -> Ip
internal fun String.toBareIp(): Ip {
    val addr = internalParseIpAddress(this)
        ?: throw IllegalArgumentException("Invalid IP address: '$this'")
    return Ip {
        value = when (addr) {
            is InternalParsedIpAddress.V4 -> Ip.Value.V4(ByteString(addr.bytes))
            is InternalParsedIpAddress.V6 -> Ip.Value.V6(ByteString(addr.bytes))
        }
    }
}

// "10.0.0.1/24" -> Cidr
// "fd00::1/64" -> Cidr
// "1.2.3.4" -> Cidr (defaults to /32)
internal fun String.toCidr(): Cidr {
    if (contains('/')) {
        val cidr = internalParseCidr(this)
            ?: throw IllegalArgumentException("Invalid CIDR: '$this'")
        return Cidr {
            ip = Ip {
                value = when (val addr = cidr.address) {
                    is InternalParsedIpAddress.V4 -> Ip.Value.V4(ByteString(addr.bytes))
                    is InternalParsedIpAddress.V6 -> Ip.Value.V6(ByteString(addr.bytes))
                }
            }
            prefix = cidr.prefixLength.toUInt()
        }
    }
    val addr = internalParseIpAddress(this)
        ?: throw IllegalArgumentException("Invalid IP address: '$this'")
    val defaultPrefix = when (addr) {
        is InternalParsedIpAddress.V4 -> 32u
        is InternalParsedIpAddress.V6 -> 128u
    }
    return Cidr {
        ip = Ip {
            value = when (addr) {
                is InternalParsedIpAddress.V4 -> Ip.Value.V4(ByteString(addr.bytes))
                is InternalParsedIpAddress.V6 -> Ip.Value.V6(ByteString(addr.bytes))
            }
        }
        prefix = defaultPrefix
    }
}

// ── Internal parsing helpers ───────────────────────────────────────────────────

private sealed interface InternalParsedIpAddress {
    val bytes: ByteArray
    val maxPrefixLength: Int

    data class V4(override val bytes: ByteArray) : InternalParsedIpAddress {
        override val maxPrefixLength: Int = 32
    }

    data class V6(override val bytes: ByteArray) : InternalParsedIpAddress {
        override val maxPrefixLength: Int = 128
    }
}

private data class InternalParsedCidr(
    val address: InternalParsedIpAddress,
    val prefixLength: Int,
)

private fun internalParseIpAddress(value: String): InternalParsedIpAddress? {
    val normalizedValue = value.trim()
    return when {
        normalizedValue.contains(':') -> internalParseIpv6Address(normalizedValue)
        normalizedValue.contains('.') -> internalParseIpv4Address(normalizedValue)
        else -> null
    }
}

private fun internalParseCidr(value: String): InternalParsedCidr? {
    val parts = value.trim().split("/", limit = 2)
    if (parts.size != 2) return null
    val address = internalParseIpAddress(parts[0]) ?: return null
    val prefixLength = parts[1].toIntOrNull() ?: return null
    if (prefixLength !in 0..address.maxPrefixLength) return null
    return InternalParsedCidr(address = address, prefixLength = prefixLength)
}

private fun internalParseIpv4Address(value: String): InternalParsedIpAddress.V4? {
    val segments = value.split('.')
    if (segments.size != 4) return null
    val bytes = ByteArray(4)
    segments.forEachIndexed { index, segment ->
        val number = segment.toIntOrNull() ?: return null
        if (number !in 0..255) return null
        bytes[index] = number.toByte()
    }
    return InternalParsedIpAddress.V4(bytes)
}

private fun internalParseIpv6Address(value: String): InternalParsedIpAddress.V6? {
    val doubleColonCount = value.windowed(size = 2, step = 1, partialWindows = false).count { it == "::" }
    if (doubleColonCount > 1) return null

    val parts = value.split("::", limit = 2)
    val headSegments = if (parts[0].isBlank()) emptyList() else parts[0].split(':')
    val tailSegments = if (parts.size == 1 || parts[1].isBlank()) emptyList() else parts[1].split(':')

    val expandedHead = internalExpandIpv6Segments(headSegments) ?: return null
    val expandedTail = internalExpandIpv6Segments(tailSegments) ?: return null
    val hasCompression = parts.size == 2

    val totalSegments = expandedHead.size + expandedTail.size
    if ((!hasCompression && totalSegments != 8) || (hasCompression && totalSegments > 8)) return null

    val missingSegments = if (hasCompression) 8 - totalSegments else 0

    val allSegments = buildList(8) {
        addAll(expandedHead)
        repeat(missingSegments) { add(0) }
        addAll(expandedTail)
    }

    if (allSegments.size != 8) return null

    val bytes = ByteArray(16)
    allSegments.forEachIndexed { index, segment ->
        bytes[index * 2] = ((segment ushr 8) and 0xff).toByte()
        bytes[index * 2 + 1] = (segment and 0xff).toByte()
    }
    return InternalParsedIpAddress.V6(bytes)
}

private fun internalExpandIpv6Segments(segments: List<String>): List<Int>? {
    val expanded = mutableListOf<Int>()
    segments.forEach { segment ->
        if (segment.isBlank()) return null
        if (segment.contains('.')) {
            val ipv4 = internalParseIpv4Address(segment) ?: return null
            expanded += ((ipv4.bytes[0].toInt() and 0xff) shl 8) or (ipv4.bytes[1].toInt() and 0xff)
            expanded += ((ipv4.bytes[2].toInt() and 0xff) shl 8) or (ipv4.bytes[3].toInt() and 0xff)
        } else {
            val number = segment.toIntOrNull(radix = 16) ?: return null
            if (number !in 0..0xffff) return null
            expanded += number
        }
    }
    return expanded
}
