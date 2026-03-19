package com.rafambn.kmpvpn.session.io

interface TunPort {
    fun readPacket(): ByteArray?

    fun writePacket(packet: ByteArray)
}
