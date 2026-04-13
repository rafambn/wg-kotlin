package com.rafambn.kmpvpn.session.io

internal interface TunPacketPort {
    suspend fun readPacket(): ByteArray?

    suspend fun writePacket(packet: ByteArray)
}
