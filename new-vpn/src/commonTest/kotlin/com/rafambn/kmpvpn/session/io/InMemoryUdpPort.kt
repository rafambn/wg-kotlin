package com.rafambn.kmpvpn.session.io

class InMemoryUdpPort(
    private val incomingDatagrams: ArrayDeque<UdpDatagram> = ArrayDeque(),
) : UdpPort {
    val sentDatagrams: MutableList<UdpDatagram> = mutableListOf()

    override suspend fun receiveDatagram(): UdpDatagram? {
        val datagram = incomingDatagrams.removeFirstOrNull() ?: return null
        return UdpDatagram(
            packet = datagram.packet.copyOf(),
            endpoint = datagram.endpoint,
        )
    }

    override suspend fun sendDatagram(datagram: UdpDatagram) {
        sentDatagrams += UdpDatagram(
            packet = datagram.packet.copyOf(),
            endpoint = datagram.endpoint,
        )
    }
}
