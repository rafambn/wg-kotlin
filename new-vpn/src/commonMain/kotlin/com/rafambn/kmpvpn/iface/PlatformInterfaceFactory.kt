package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration

/**
 * Platform-aware factory entrypoint for [VpnInterface] creation.
 */
expect object PlatformInterfaceFactory {
    fun create(configuration: VpnConfiguration): VpnInterface
}
