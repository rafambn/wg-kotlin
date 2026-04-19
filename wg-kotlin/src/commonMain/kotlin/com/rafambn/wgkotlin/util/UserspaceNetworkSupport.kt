package com.rafambn.wgkotlin.util

internal sealed interface ParsedIpAddress {
    val bytes: ByteArray
    val maxPrefixLength: Int

    data class V4(
        override val bytes: ByteArray,
    ) : ParsedIpAddress {
        override val maxPrefixLength: Int = 32
    }

    data class V6(
        override val bytes: ByteArray,
    ) : ParsedIpAddress {
        override val maxPrefixLength: Int = 128
    }
}

internal data class ParsedCidr(
    val address: ParsedIpAddress,
    val prefixLength: Int,
) {
    val normalizedBytes: ByteArray = normalizeNetworkBytes(
        bytes = address.bytes,
        prefixLength = prefixLength,
    )
}

internal fun parsePacketDestination(packet: ByteArray): ParsedIpAddress? {
    if (packet.isEmpty()) {
        return null
    }

    val version = ((packet[0].toInt() ushr 4) and 0x0f)
    return when (version) {
        4 -> if (packet.size >= 20) {
            ParsedIpAddress.V4(packet.copyOfRange(16, 20))
        } else {
            null
        }
        6 -> if (packet.size >= 40) {
            ParsedIpAddress.V6(packet.copyOfRange(24, 40))
        } else {
            null
        }
        else -> null
    }
}

internal fun parseIpAddress(value: String): ParsedIpAddress? {
    val normalizedValue = value.trim()
    return when {
        normalizedValue.contains(':') -> parseIpv6Address(normalizedValue)
        normalizedValue.contains('.') -> parseIpv4Address(normalizedValue)
        else -> null
    }
}

internal fun parseCidr(value: String): ParsedCidr? {
    val parts = value.trim().split("/", limit = 2)
    if (parts.size != 2) {
        return null
    }

    val address = parseIpAddress(parts[0]) ?: return null
    val prefixLength = parts[1].toIntOrNull() ?: return null
    if (prefixLength !in 0..address.maxPrefixLength) {
        return null
    }

    return ParsedCidr(
        address = address,
        prefixLength = prefixLength,
    )
}

internal fun ParsedCidr.matches(address: ParsedIpAddress): Boolean {
    if (this.address::class != address::class) {
        return false
    }

    val fullBytes = prefixLength / 8
    val remainingBits = prefixLength % 8

    for (index in 0 until fullBytes) {
        if (normalizedBytes[index] != address.bytes[index]) {
            return false
        }
    }

    if (remainingBits == 0) {
        return true
    }

    val mask = (0xff shl (8 - remainingBits)) and 0xff
    return (normalizedBytes[fullBytes].toInt() and mask) == (address.bytes[fullBytes].toInt() and mask)
}

internal fun ParsedCidr.normalizedKey(): String {
    val family = when (address) {
        is ParsedIpAddress.V4 -> "v4"
        is ParsedIpAddress.V6 -> "v6"
    }
    return buildString {
        append(family)
        append('/')
        append(prefixLength)
        append(':')
        normalizedBytes.forEach { byte ->
            append(byte.toInt().and(0xff).toString(16).padStart(2, '0'))
        }
    }
}

private fun parseIpv4Address(value: String): ParsedIpAddress.V4? {
    val segments = value.split('.')
    if (segments.size != 4) {
        return null
    }

    val bytes = ByteArray(4)
    segments.forEachIndexed { index, segment ->
        val number = segment.toIntOrNull() ?: return null
        if (number !in 0..255) {
            return null
        }
        bytes[index] = number.toByte()
    }
    return ParsedIpAddress.V4(bytes)
}

private fun parseIpv6Address(value: String): ParsedIpAddress.V6? {
    val doubleColonCount = value.windowed(size = 2, step = 1, partialWindows = false).count { token -> token == "::" }
    if (doubleColonCount > 1) {
        return null
    }

    val parts = value.split("::", limit = 2)
    val headSegments = if (parts[0].isBlank()) {
        emptyList()
    } else {
        parts[0].split(':')
    }
    val tailSegments = if (parts.size == 1 || parts[1].isBlank()) {
        emptyList()
    } else {
        parts[1].split(':')
    }

    val expandedHead = expandIpv6Segments(headSegments) ?: return null
    val expandedTail = expandIpv6Segments(tailSegments) ?: return null
    val hasCompression = parts.size == 2

    val totalSegments = expandedHead.size + expandedTail.size
    if ((!hasCompression && totalSegments != 8) || (hasCompression && totalSegments > 8)) {
        return null
    }

    val missingSegments = if (hasCompression) {
        8 - totalSegments
    } else {
        0
    }

    val allSegments = buildList(8) {
        addAll(expandedHead)
        repeat(missingSegments) {
            add(0)
        }
        addAll(expandedTail)
    }

    if (allSegments.size != 8) {
        return null
    }

    val bytes = ByteArray(16)
    allSegments.forEachIndexed { index, segment ->
        bytes[index * 2] = ((segment ushr 8) and 0xff).toByte()
        bytes[index * 2 + 1] = (segment and 0xff).toByte()
    }
    return ParsedIpAddress.V6(bytes)
}

private fun expandIpv6Segments(segments: List<String>): List<Int>? {
    val expanded = mutableListOf<Int>()
    segments.forEach { segment ->
        if (segment.isBlank()) {
            return null
        }

        if (segment.contains('.')) {
            val ipv4 = parseIpv4Address(segment) ?: return null
            expanded += ((ipv4.bytes[0].toInt() and 0xff) shl 8) or (ipv4.bytes[1].toInt() and 0xff)
            expanded += ((ipv4.bytes[2].toInt() and 0xff) shl 8) or (ipv4.bytes[3].toInt() and 0xff)
        } else {
            val number = segment.toIntOrNull(radix = 16) ?: return null
            if (number !in 0..0xffff) {
                return null
            }
            expanded += number
        }
    }
    return expanded
}

private fun normalizeNetworkBytes(
    bytes: ByteArray,
    prefixLength: Int,
): ByteArray {
    val normalized = bytes.copyOf()
    val fullBytes = prefixLength / 8
    val remainingBits = prefixLength % 8

    if (fullBytes < normalized.size) {
        if (remainingBits > 0) {
            val mask = (0xff shl (8 - remainingBits)) and 0xff
            normalized[fullBytes] = (normalized[fullBytes].toInt() and mask).toByte()
        }

        val zeroFrom = if (remainingBits == 0) fullBytes else fullBytes + 1
        for (index in zeroFrom until normalized.size) {
            normalized[index] = 0
        }
    }

    return normalized
}
