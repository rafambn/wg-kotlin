package com.rafambn.wgkotlin.network

import java.net.InetAddress

actual fun resolveEndpointAddress(address: String): String {
    return try {
        InetAddress.getByName(address).hostAddress
    } catch (_: Exception) {
        address
    }
}
