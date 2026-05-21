package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSessionManagerImpl
import com.rafambn.wgkotlin.iface.PlatformInterfaceFactory
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import com.rafambn.wgkotlin.network.SocketManagerImpl
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe

class Vpn(
    configuration: VpnConfiguration,
    engine: Engine = Engine.BORINGTUN
) {

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }

    private val tunPipePair = DuplexChannelPipe.create<ByteArray>()
    private val networkPipePair = DuplexChannelPipe.create<UdpDatagram>()
    private var vpnConfiguration = configuration
    private val cryptoSessionManager = CryptoSessionManagerImpl(
        tunPipe = tunPipePair.second,
        networkPipe = networkPipePair.second,
        engine = engine,
    )
    private val socketManager = SocketManagerImpl(networkPipe = networkPipePair.first)
    private val interfaceManager = PlatformInterfaceFactory.create(tunPipePair.first)

    init {
        requireValidConfiguration(vpnConfiguration)
    }

    fun isRunning(): Boolean {
        return interfaceManager.isRunning() && cryptoSessionManager.hasActiveSessions()
    }

    fun open() {
        requireValidConfiguration(vpnConfiguration)
        stop()

        operation("reconcileSessions") {
            cryptoSessionManager.reconcileSessions(vpnConfiguration)
        }

        operation("start") {
            cryptoSessionManager.start { stop() }
        }

        operation("socketStart") {
            socketManager.start(
                listenPort = vpnConfiguration.listenPort ?: DEFAULT_PORT,
                onFailure = { stop() },
            )
        }

        operation("start") {
            interfaceManager.start(vpnConfiguration) { stop() }
        }
    }

    fun information(): VpnInterfaceInformation? {
        val liveInformation = operation("information") {
            interfaceManager.information()
        } ?: return null

        val runtimePeerStats = cryptoSessionManager.peerStats()
        val informationWithPeerStats = if (runtimePeerStats.isEmpty()) {
            liveInformation
        } else {
            liveInformation.copy(peerStats = runtimePeerStats)
        }

        return informationWithPeerStats.copy(vpnConfiguration = vpnConfiguration)
    }

    fun reconfigure(config: VpnConfiguration) {
        requireValidConfiguration(config)
        require(config.interfaceName == vpnConfiguration.interfaceName) {
            "Cannot reconfigure interface `${vpnConfiguration.interfaceName}` using `${config.interfaceName}`"
        }

        val previousListenPort = vpnConfiguration.listenPort ?: DEFAULT_PORT
        vpnConfiguration = config

        operation("reconcileSessions") {
            cryptoSessionManager.reconcileSessions(config)
        }

        if (interfaceManager.isRunning()) {
            val newListenPort = config.listenPort ?: DEFAULT_PORT

            operation("reconfigure") {
                interfaceManager.reconfigure(config)
            }

            if (previousListenPort != newListenPort) {
                operation("socketRestart") {
                    socketManager.stop()
                    socketManager.start(newListenPort) { stop() }
                }
            }
        }
    }

    fun stop() {
        var firstError: Throwable? = null
        try {
            operation("stop") { interfaceManager.stop() }
        } catch (error: Throwable) {
            firstError = error
        }
        try {
            operation("socketStop") { socketManager.stop() }
        } catch (error: Throwable) {
            if (firstError == null) firstError = error
        }
        try {
            operation("stop") { cryptoSessionManager.stop() }
        } catch (error: Throwable) {
            if (firstError == null) firstError = error
        }
        if (firstError != null) throw firstError
    }

    private inline fun <T> operation(name: String, block: () -> T): T {
        return try {
            block()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Operation `$name` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
    }
}
