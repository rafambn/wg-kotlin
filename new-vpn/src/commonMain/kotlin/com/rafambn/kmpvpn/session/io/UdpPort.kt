package com.rafambn.kmpvpn.session.io

interface UdpPort {
    fun receivePacket(): ByteArray?

    fun sendPacket(packet: ByteArray)
}
