package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.info.VpnInterfaceInformation

/**
 * Represents a VPN adapter/interface
 */
interface VpnAdapter {

    fun address(): VpnAddress

    fun configuration(): VpnConfiguration

    @Throws(Exception::class)
    fun information(): VpnInterfaceInformation
}
