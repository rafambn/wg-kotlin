package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.network.io.PacketAction
import uniffi.wg_kotlin.TunnelPacketResult
import uniffi.wg_kotlin.TunnelSession

class BoringTunPeerSession(
    override val peerPublicKey: String,
    override val peerIndex: Int,
    private val tunnel: TunnelSession,
) : PeerSession {
    private var active: Boolean = true

    override val isActive: Boolean
        get() = active

    override fun encryptRawPacket(src: ByteArray, dstSize: UInt): PacketAction {
        ensureActive("encryptRawPacket")
        return toPacketAction(
            result = tunnel.encryptRawPacket(src = src, dstSize = dstSize),
            operation = "encryptRawPacket",
        )
    }

    override fun decryptToRawPacket(src: ByteArray, dstSize: UInt): PacketAction {
        ensureActive("decryptToRawPacket")
        return toPacketAction(
            result = tunnel.decryptToRawPacket(src = src, dstSize = dstSize),
            operation = "decryptToRawPacket",
        )
    }

    override fun runPeriodicTask(dstSize: UInt): PacketAction {
        ensureActive("runPeriodicTask")
        return toPacketAction(
            result = tunnel.runPeriodicTask(dstSize = dstSize),
            operation = "runPeriodicTask",
        )
    }

    override fun close() {
        if (!active) {
            return
        }

        active = false
        tunnel.destroy()
    }

    private fun ensureActive(operation: String) {
        check(active) {
            "Cannot execute `$operation` on closed session `${peerPublicKey}`"
        }
    }

    private fun toPacketAction(result: TunnelPacketResult, operation: String): PacketAction {
        val boundedSize = result.size.toInt().coerceIn(0, result.packet.size)
        val boundedPacket = result.packet.copyOfRange(0, boundedSize)

        return when (result.op.toInt()) {
            OP_DONE -> PacketAction.Done
            OP_WRITE_TO_NETWORK -> PacketAction.WriteToNetwork(boundedPacket)
            OP_ERROR -> PacketAction.Error(result.size)
            OP_WRITE_TO_TUNNEL_IPV4 -> PacketAction.WriteToTunIpv4(boundedPacket)
            OP_WRITE_TO_TUNNEL_IPV6 -> PacketAction.WriteToTunIpv6(boundedPacket)
            else -> throw IllegalStateException(
                "Unsupported tunnel op `${result.op}` from `$operation` for `${peerPublicKey}`",
            )
        }
    }

    private companion object {
        const val OP_DONE: Int = 0
        const val OP_WRITE_TO_NETWORK: Int = 1
        const val OP_ERROR: Int = 2
        const val OP_WRITE_TO_TUNNEL_IPV4: Int = 4
        const val OP_WRITE_TO_TUNNEL_IPV6: Int = 6
    }
}
