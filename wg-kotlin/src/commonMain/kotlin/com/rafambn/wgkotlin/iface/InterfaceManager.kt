package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig

interface InterfaceManager {
    fun isRunning(): Boolean

    suspend fun start(config: TunSessionConfig, onFailure: (Throwable) -> Unit = {})

    fun stop()

    fun information(): VpnInterfaceInformation?
}
