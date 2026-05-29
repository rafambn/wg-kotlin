package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Ip
import java.net.InetAddress

internal actual fun String.resolveToIpString(): String = InetAddress.getByName(this).hostAddress

internal actual fun String.resolveToIp(): Ip =
    parsePacketDestination(InetAddress.getByName(this).address)
