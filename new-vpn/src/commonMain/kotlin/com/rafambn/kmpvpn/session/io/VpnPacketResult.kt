package com.rafambn.kmpvpn.session.io

sealed class VpnPacketResult {
    data object Done : VpnPacketResult()

    data class WriteToNetwork(val packet: ByteArray) : VpnPacketResult()

    data class WriteToTunnelIpv4(val packet: ByteArray) : VpnPacketResult()

    data class WriteToTunnelIpv6(val packet: ByteArray) : VpnPacketResult()

    data class Error(val code: UInt) : VpnPacketResult()

    data class NotSupported(val operation: String) : VpnPacketResult()
}
