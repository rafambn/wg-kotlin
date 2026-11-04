package com.rafambn.wgkotlin.crypto

import uniffi.wg_kotlin_uniffi_boringtun.TunnelPacketResult
import uniffi.wg_kotlin_uniffi_boringtun.TunnelSession

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
        )
    }

    override fun decryptToRawPacket(src: ByteArray, dstSize: UInt, sourceIp: String): PacketAction {
        ensureActive("decryptToRawPacket")
        return toPacketAction(
            result = tunnel.decryptToRawPacket(src = src, dstSize = dstSize, sourceIp = sourceIp),
        )
    }

    override fun runPeriodicTask(dstSize: UInt): PacketAction {
        ensureActive("runPeriodicTask")
        return toPacketAction(
            result = tunnel.runPeriodicTask(dstSize = dstSize),
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

    private fun toPacketAction(result: TunnelPacketResult): PacketAction {
        return when (result) {
            is TunnelPacketResult.Done -> PacketAction.Done
            is TunnelPacketResult.WriteToNetwork -> PacketAction.WriteToNetwork(result.packet)
            is TunnelPacketResult.Error -> PacketAction.Error(result.code)
            is TunnelPacketResult.WriteToTunnelV4 -> PacketAction.WriteToTunIpv4(result.packet)
            is TunnelPacketResult.WriteToTunnelV6 -> PacketAction.WriteToTunIpv6(result.packet)
        }
    }
}
