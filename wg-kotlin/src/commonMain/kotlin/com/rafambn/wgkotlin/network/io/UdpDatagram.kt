package com.rafambn.wgkotlin.network.io

data class UdpDatagram(
    val payload: ByteArray,
    val remoteEndpoint: UdpEndpoint,
)
