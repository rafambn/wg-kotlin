package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Ip
import java.net.InetAddress

internal actual fun resolveEndpointAddress(address: String): String = InetAddress.getByName(address).hostAddress

internal actual fun resolveEndpointAddressToBytes(address: String): Ip =
    parsePacketDestination(InetAddress.getByName(address).address)
