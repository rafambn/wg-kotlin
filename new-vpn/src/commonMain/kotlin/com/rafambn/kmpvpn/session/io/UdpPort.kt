package com.rafambn.kmpvpn.session.io

interface UdpPort {
    suspend fun receiveDatagram(): UdpDatagram?

    suspend fun sendDatagram(datagram: UdpDatagram)
}
