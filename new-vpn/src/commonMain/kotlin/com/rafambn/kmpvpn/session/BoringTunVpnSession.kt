package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.session.io.VpnPacketResult
import uniffi.new_vpn.TunnelPacketResult
import uniffi.new_vpn.TunnelSession

class BoringTunVpnSession(
    override val peerPublicKey: String,
    override val sessionIndex: Int,
    private val tunnel: TunnelSession,
) : VpnSession {
    private var active: Boolean = true

    override val isActive: Boolean
        get() = active

    override fun encryptRawPacket(src: ByteArray, dstSize: UInt): VpnPacketResult {
        ensureActive("encryptRawPacket")
        return toVpnPacketResult(
            result = tunnel.encryptRawPacket(src = src, dstSize = dstSize),
            operation = "encryptRawPacket",
        )
    }

    override fun decryptToRawPacket(src: ByteArray, dstSize: UInt): VpnPacketResult {
        ensureActive("decryptToRawPacket")
        return toVpnPacketResult(
            result = tunnel.decryptToRawPacket(src = src, dstSize = dstSize),
            operation = "decryptToRawPacket",
        )
    }

    override fun runPeriodicTask(dstSize: UInt): VpnPacketResult {
        ensureActive("runPeriodicTask")
        return toVpnPacketResult(
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

    private fun toVpnPacketResult(result: TunnelPacketResult, operation: String): VpnPacketResult {
        val boundedSize = result.size.toInt().coerceIn(0, result.packet.size)
        val boundedPacket = result.packet.copyOfRange(0, boundedSize)

        return when (result.op.toInt()) {
            OP_DONE -> VpnPacketResult.Done
            OP_WRITE_TO_NETWORK -> VpnPacketResult.WriteToNetwork(boundedPacket)
            OP_ERROR -> VpnPacketResult.Error(result.size)
            OP_WRITE_TO_TUNNEL_IPV4 -> VpnPacketResult.WriteToTunnelIpv4(boundedPacket)
            OP_WRITE_TO_TUNNEL_IPV6 -> VpnPacketResult.WriteToTunnelIpv6(boundedPacket)
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
