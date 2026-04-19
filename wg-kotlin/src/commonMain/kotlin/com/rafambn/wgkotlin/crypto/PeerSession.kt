package com.rafambn.wgkotlin.crypto

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
