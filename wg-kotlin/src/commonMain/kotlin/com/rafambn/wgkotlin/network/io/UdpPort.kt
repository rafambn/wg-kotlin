package com.rafambn.wgkotlin.network.io

interface UdpPort {
    suspend fun receiveDatagram(): UdpDatagram?

    suspend fun sendDatagram(datagram: UdpDatagram)
}
