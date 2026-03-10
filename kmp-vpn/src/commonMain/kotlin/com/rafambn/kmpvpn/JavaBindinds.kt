package com.rafambn.kmpvpn

open class NetworkInterface {

    val interfaceAddresses: List<InterfaceAddress> = emptyList()

    val name: String = ""

    val isLoopback: Boolean = false

}

interface InterfaceAddress {

    val address: InetAddress
}

interface InetAddress {

    val isIpv4: Boolean
    val isAnyLocalAddress: Boolean
    val isLinkLocalAddress: Boolean
    val isLoopbackAddress: Boolean
    companion object{
        fun getLocalHost(): String = ""
    }
}

interface InetSocketAddress{

}