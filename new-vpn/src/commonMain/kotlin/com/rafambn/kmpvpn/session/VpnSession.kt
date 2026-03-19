package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.session.io.VpnPacketResult

interface VpnSession : AutoCloseable {
    val peerPublicKey: String

    val sessionIndex: UInt

    val isActive: Boolean

    fun encryptRawPacket(src: ByteArray, dstSize: UInt): VpnPacketResult {
        return VpnPacketResult.NotSupported("encryptRawPacket")
    }

    fun decryptToRawPacket(src: ByteArray, dstSize: UInt): VpnPacketResult {
        return VpnPacketResult.NotSupported("decryptToRawPacket")
    }

    fun runPeriodicTask(dstSize: UInt): VpnPacketResult {
        return VpnPacketResult.NotSupported("runPeriodicTask")
    }

    override fun close()
}
