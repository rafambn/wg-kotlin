package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.VpnAdapter
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.StartRequest
import com.rafambn.kmpvpn.address.VpnAddress

/**
 * Platform-specific VPN service
 */
interface PlatformService<T : VpnAddress> {

    @Throws(Exception::class)
    fun adapter(name: String): VpnAdapter

    @Throws(Exception::class)
    fun getByPublicKey(publicKey: String): VpnAdapter?

    @Throws(Exception::class)
    fun start(request: StartRequest): VpnAdapter

    @Throws(Exception::class)
    fun stop(configuration: VpnConfiguration, adapter: VpnAdapter)

    fun interfaceNameToNativeName(interfaceName: String): String?
}
