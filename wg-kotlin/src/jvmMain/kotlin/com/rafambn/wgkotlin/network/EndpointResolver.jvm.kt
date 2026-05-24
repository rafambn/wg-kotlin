package com.rafambn.wgkotlin.network

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

private val endpointAddressCache = ConcurrentHashMap<String, String>()

actual fun resolveEndpointAddress(address: String): String {
    return endpointAddressCache.getOrPut(address) {
        try {
            InetAddress.getByName(address).hostAddress
        } catch (_: Exception) {
            address
        }
    }
}
