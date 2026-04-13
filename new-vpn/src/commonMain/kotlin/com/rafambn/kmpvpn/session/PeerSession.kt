package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.session.io.PacketAction

interface PeerSession : AutoCloseable {
    val peerPublicKey: String

    val peerIndex: Int

    val isActive: Boolean

    fun encryptRawPacket(src: ByteArray, dstSize: UInt): PacketAction {
        return PacketAction.NotSupported("encryptRawPacket")
    }

    fun decryptToRawPacket(src: ByteArray, dstSize: UInt): PacketAction {
        return PacketAction.NotSupported("decryptToRawPacket")
    }

    fun runPeriodicTask(dstSize: UInt): PacketAction {
        return PacketAction.NotSupported("runPeriodicTask")
    }

    override fun close()
}
