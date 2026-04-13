package com.rafambn.kmpvpn.session.io

internal data object DiscardingTunPacketPort : TunPacketPort {
    override suspend fun readPacket(): ByteArray? = null

    override suspend fun writePacket(packet: ByteArray) = Unit
}
