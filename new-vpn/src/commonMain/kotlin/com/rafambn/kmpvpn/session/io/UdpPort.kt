package com.rafambn.kmpvpn.session.io

data class UdpEndpoint(
    val host: String,
    val port: Int,
)

data class UdpDatagram(
    val packet: ByteArray,
    val endpoint: UdpEndpoint,
)

interface UdpPort {
    suspend fun receiveDatagram(): UdpDatagram?

    suspend fun sendDatagram(datagram: UdpDatagram)
}
