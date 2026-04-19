package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.requireValidConfiguration
import com.rafambn.wgkotlin.util.DuplexChannelPipe

class JvmInterfaceManager(
    private val commandExecutor: InterfaceCommandExecutor,
    private val tunPipe: DuplexChannelPipe<ByteArray>,
) : InterfaceManager {
    private var currentConfig: VpnConfiguration? = null
    private var activeBridge: AutoCloseable? = null

    override fun isRunning(): Boolean = activeBridge != null

    override fun start(config: VpnConfiguration, onFailure: (Throwable) -> Unit) {
        requireValidConfiguration(config)
        stop()

        val bridge = commandExecutor.openSession(
            config = config.toTunSessionConfig(),
            pipe = tunPipe,
            onFailure = { throwable ->
                activeBridge = null
                currentConfig = null
                onFailure(throwable)
            },
        )

        activeBridge = bridge
        currentConfig = config.copy(addresses = config.addresses.toMutableList())
    }

    override fun stop() {
        runCatching { activeBridge?.close() }
        activeBridge = null
        currentConfig = null
    }

    override fun reconfigure(config: VpnConfiguration) {
        stop()
        start(config)
    }

    override fun information(): VpnInterfaceInformation? {
        val config = currentConfig ?: return null
        return VpnInterfaceInformation(
            interfaceName = config.interfaceName,
            isUp = isRunning(),
            addresses = config.addresses.toList(),
            dns = config.dns,
            mtu = config.mtu,
            listenPort = config.listenPort,
        )
    }
}
