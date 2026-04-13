package com.rafambn.kmpvpn.session.io

internal class BufferedTunPacketPort(
    incomingPackets: Collection<ByteArray> = emptyList(),
) : TunPacketPort {
    private val incomingPackets: ArrayDeque<ByteArray> = ArrayDeque(
        incomingPackets.map { packet -> packet.copyOf() },
    )
    private val writtenPackets: ArrayDeque<ByteArray> = ArrayDeque()

    override suspend fun readPacket(): ByteArray? {
        return synchronized(this) {
            incomingPackets.removeFirstOrNull()?.copyOf()
        }
    }

    override suspend fun writePacket(packet: ByteArray) {
        synchronized(this) {
            writtenPackets.addLast(packet.copyOf())
        }
    }
}
