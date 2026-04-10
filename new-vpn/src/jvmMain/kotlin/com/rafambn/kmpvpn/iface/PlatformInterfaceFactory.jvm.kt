package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration

actual object PlatformInterfaceFactory {
    actual fun create(configuration: VpnConfiguration): InterfaceManager {
        return JvmInterfaceKoinBootstrap.createInterfaceManager(configuration)
    }
}
