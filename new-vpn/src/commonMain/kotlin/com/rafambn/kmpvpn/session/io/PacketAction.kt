package com.rafambn.kmpvpn.session.io

sealed class PacketAction {
    data object Done : PacketAction()

    data class WriteToNetwork(val packet: ByteArray) : PacketAction()

    data class WriteToTunIpv4(val packet: ByteArray) : PacketAction()

    data class WriteToTunIpv6(val packet: ByteArray) : PacketAction()

    data class Error(val code: UInt) : PacketAction()

    data class NotSupported(val operation: String) : PacketAction()
}
