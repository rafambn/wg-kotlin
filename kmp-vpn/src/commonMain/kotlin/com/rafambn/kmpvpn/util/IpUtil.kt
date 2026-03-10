package com.rafambn.kmpvpn.util

object IpUtil {
    fun parse(ip: String): String {
        //TODO parse IP
        return ""
    }

    fun rangeFrom(range: String): Pair<*, *> {
        //TODO Parse Range
        return Pair(range, range)
    }

    fun toIEEE802(mac: ByteArray?): String? {
        val hexChars = "0123456789abcdef"
        return mac?.joinToString(":") { byte ->
            val i = byte.toInt() and 0xFF
            "${hexChars[i shr 4]}${hexChars[i and 0x0F]}"
        }
    }

    fun filterIpV4Addresses(address: List<String>): List<String> {
        //TODO add filter
        return emptyList()
    }

    fun filterIpV6Addresses(address: List<String>): List<String> {
        //TODO add filter
        return emptyList()
    }


    fun filterAddresses(address: List<String>): List<String> {
        //TODO add filter
        return emptyList()
    }

    fun filterNames(address: List<String>): List<String> {
        //TODO add filter
        return emptyList()
    }

    fun normalizeMasked(address: String): String {
        //TODO fix this
//        return if (address.contains("/")) {
//            address
//        } else {
//            val a = parse(address)
//            "${a}/" + a.bitSize()
//        }
        return ""
    }
}
