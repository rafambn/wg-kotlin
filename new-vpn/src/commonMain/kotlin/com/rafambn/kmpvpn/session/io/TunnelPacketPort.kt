package com.rafambn.kmpvpn.session.io

internal interface TunnelPacketPort {
    suspend fun readPacket(): ByteArray?

    suspend fun writePacket(packet: ByteArray)
}
