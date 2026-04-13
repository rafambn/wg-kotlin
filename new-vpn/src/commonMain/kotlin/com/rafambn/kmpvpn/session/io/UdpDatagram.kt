package com.rafambn.kmpvpn.session.io

data class UdpDatagram(
    val payload: ByteArray,
    val remoteEndpoint: UdpEndpoint,
)
