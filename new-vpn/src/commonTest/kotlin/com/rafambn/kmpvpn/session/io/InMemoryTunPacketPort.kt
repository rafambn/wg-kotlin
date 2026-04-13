package com.rafambn.kmpvpn.session.io

class InMemoryTunPacketPort(
    private val incomingPackets: ArrayDeque<ByteArray> = ArrayDeque(),
) : TunPacketPort {
    val writtenPackets: MutableList<ByteArray> = mutableListOf()

    override suspend fun readPacket(): ByteArray? {
        return incomingPackets.removeFirstOrNull()?.copyOf()
    }

    override suspend fun writePacket(packet: ByteArray) {
        writtenPackets += packet.copyOf()
    }
}
