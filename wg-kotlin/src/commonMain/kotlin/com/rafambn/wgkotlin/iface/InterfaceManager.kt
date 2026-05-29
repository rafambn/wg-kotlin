package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.VpnConfiguration

interface InterfaceManager {
    fun isRunning(): Boolean

    fun start(config: VpnConfiguration, onFailure: (Throwable) -> Unit = {})

    fun stop()

    fun information(): VpnInterfaceInformation?
}
