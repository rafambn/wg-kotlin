package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig

interface InterfaceManager {
    fun isRunning(): Boolean

    fun start(config: TunSessionConfig, onFailure: (Throwable) -> Unit = {})

    /** Closes the session and waits for its workers. Call outside the failure callback. */
    fun stop()

    fun information(): VpnInterfaceInformation?
}
