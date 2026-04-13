package com.rafambn.kmpvpn.session.io

class InMemoryUdpPort(
    private val incomingDatagrams: ArrayDeque<UdpDatagram> = ArrayDeque(),
) : UdpPort {
    val sentDatagrams: MutableList<UdpDatagram> = mutableListOf()

    override suspend fun receiveDatagram(): UdpDatagram? {
        val datagram = incomingDatagrams.removeFirstOrNull() ?: return null
        return UdpDatagram(
            payload = datagram.payload.copyOf(),
            remoteEndpoint = datagram.remoteEndpoint,
        )
    }

    override suspend fun sendDatagram(datagram: UdpDatagram) {
        sentDatagrams += UdpDatagram(
            payload = datagram.payload.copyOf(),
            remoteEndpoint = datagram.remoteEndpoint,
        )
    }
}
