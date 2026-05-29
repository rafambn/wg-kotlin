package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSessionManagerImpl
import com.rafambn.wgkotlin.iface.PlatformInterfaceFactory
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import com.rafambn.wgkotlin.network.SocketManagerImpl
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe

class Vpn(
    val interfaceName: String,
    engine: Engine = Engine.BORINGTUN
) {

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }

    private val tunPipePair = DuplexChannelPipe.create<ByteArray>()
    private val networkPipePair = DuplexChannelPipe.create<UdpDatagram>()
    private val cryptoSessionManager = CryptoSessionManagerImpl(
        tunPipe = tunPipePair.second,
        networkPipe = networkPipePair.second,
        engine = engine,
    )
    private val socketManager = SocketManagerImpl(networkPipe = networkPipePair.first)
    private val interfaceManager = PlatformInterfaceFactory.create(tunPipePair.first)
    private var currentParsedConfiguration: ParsedVpnConfiguration? = null
    private var originalConfiguration: VpnConfiguration? = null

    init {
        requireNonBlankInterfaceName(interfaceName)
        requireValidRegex(interfaceName)
    }

    fun isRunning(): Boolean {
        return interfaceManager.isRunning() && cryptoSessionManager.hasActiveSessions()
    }

    fun open(configuration: VpnConfiguration) {
        requireValidConfiguration(configuration)
        require(configuration.interfaceName == interfaceName) {
            "Configuration interface name `${configuration.interfaceName}` does not match this Vpn's interface name `$interfaceName`"
        }

        val parsed = configuration.toParsedVpnConfiguration()
        stop()

        currentParsedConfiguration = parsed
        originalConfiguration = configuration

        operation("reconcileSessions") {
            cryptoSessionManager.reconcileSessions(parsed)
        }

        operation("start") {
            cryptoSessionManager.start { stop() }
        }

        operation("socketStart") {
            socketManager.start(
                listenPort = configuration.listenPort ?: DEFAULT_PORT,
                onFailure = { stop() },
            )
        }

        operation("start") {
            interfaceManager.start(parsed.toTunSessionConfig()) { stop() }
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

        return informationWithPeerStats.copy(vpnConfiguration = originalConfiguration)
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
        currentParsedConfiguration = null
        originalConfiguration = null
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
