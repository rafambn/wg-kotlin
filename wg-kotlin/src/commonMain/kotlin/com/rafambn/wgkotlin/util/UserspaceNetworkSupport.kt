package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import kotlinx.io.bytestring.ByteString

internal fun parsePacketDestination(packet: ByteArray): Ip? {
    if (packet.isEmpty()) return null

    val version = ((packet[0].toInt() ushr 4) and 0x0f)
    val value: Ip.Value? = when (version) {
        4 -> if (packet.size >= 20) {
            Ip.Value.V4(ByteString(packet.copyOfRange(16, 20)))
        } else {
            null
        }
        6 -> if (packet.size >= 40) {
            Ip.Value.V6(ByteString(packet.copyOfRange(24, 40)))
        } else {
            null
        }
        else -> null
    }
    val ipValue = value ?: return null
    return Ip { this.value = ipValue }

}

internal fun Cidr.matches(destination: Ip): Boolean {
    val cidrBytes = ip.ipBytes() ?: return false
    val prefixLen = prefix.toInt()
    val dstBytes = destination.ipBytes() ?: return false
    if (ip.value?.javaClass != destination.value?.javaClass) return false

    val fullBytes = prefixLen / 8
    val remainingBits = prefixLen % 8

    for (index in 0 until fullBytes) {
        if (cidrBytes[index] != dstBytes[index]) return false
    }
    if (remainingBits == 0) return true
    val mask = (0xff shl (8 - remainingBits)) and 0xff
    return (cidrBytes[fullBytes].toInt() and mask) == (dstBytes[fullBytes].toInt() and mask)
}

internal fun Cidr.normalizedKey(): String {
    val ipBytes = ip.ipBytes() ?: error("Cidr without ip")
    val prefixLen = prefix.toInt()
    val normalized = normalizeNetworkBytes(ipBytes, prefixLen)
    val family = when (ip.value) {
        is Ip.Value.V4 -> "v4"
        is Ip.Value.V6 -> "v6"
        else -> error("Unknown IP type")
    }
    return buildString {
        append(family)
        append('/')
        append(prefixLen)
        append(':')
        normalized.forEach { byte ->
            append(byte.toInt().and(0xff).toString(16).padStart(2, '0'))
        }
    }
}

internal fun Ip.ipBytes(): ByteArray? = when (value) {
    is Ip.Value.V4 -> (value as Ip.Value.V4).value.toByteArray()
    is Ip.Value.V6 -> (value as Ip.Value.V6).value.toByteArray()
    else -> null
}

private fun normalizeNetworkBytes(bytes: ByteArray, prefixLength: Int): ByteArray {
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
