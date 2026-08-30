package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSessionManager
import com.rafambn.wgkotlin.crypto.CryptoSessionManagerImpl
import com.rafambn.wgkotlin.iface.InterfaceManager
import com.rafambn.wgkotlin.iface.PlatformInterfaceFactory
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import com.rafambn.wgkotlin.network.SocketManager
import com.rafambn.wgkotlin.network.SocketManagerImpl
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.coroutines.CancellationException

class Vpn internal constructor(
    val interfaceName: String,
    private val cryptoSessionManager: CryptoSessionManager,
    private val socketManager: SocketManager,
    private val interfaceManager: InterfaceManager,
) {

    constructor(
        interfaceName: String,
        engine: Engine = Engine.BORINGTUN,
    ) : this(interfaceName, createRuntimeComponents(engine))

    private constructor(
        interfaceName: String,
        components: VpnRuntimeComponents,
    ) : this(
        interfaceName = interfaceName,
        cryptoSessionManager = components.cryptoSessionManager,
        socketManager = components.socketManager,
        interfaceManager = components.interfaceManager,
    )

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }

    private var currentParsedConfiguration: ParsedVpnConfiguration? = null
    private var originalConfiguration: VpnConfiguration? = null

    init {
        requireNonBlankInterfaceName(interfaceName)
        requireValidRegex(interfaceName)
    }

    fun isRunning(): Boolean {
        return interfaceManager.isRunning() && cryptoSessionManager.hasActiveSessions()
    }

    /** Starts the VPN and returns only after the daemon confirms that the TUN session is ready. */
    suspend fun open(configuration: VpnConfiguration) {
        requireValidConfiguration(configuration)
        require(configuration.interfaceName == interfaceName) {
            "Configuration interface name `${configuration.interfaceName}` does not match this Vpn's interface name `$interfaceName`"
        }

        val parsed = configuration.toParsedVpnConfiguration()
        stop()

        currentParsedConfiguration = parsed
        originalConfiguration = configuration

        try {
            operation("reconcileSessions") {
                cryptoSessionManager.reconcileSessions(parsed)
            }

            operation("cryptoStart") {
                cryptoSessionManager.start { stop() }
            }

            operation("socketStart") {
                socketManager.start(
                    listenPort = configuration.listenPort ?: DEFAULT_PORT,
                    onFailure = { stop() },
                )
            }

            suspendOperation("interfaceStart") {
                interfaceManager.start(parsed.toTunSessionConfig()) { stop() }
            }
        } catch (startupFailure: Throwable) {
            runCatching { stop() }
                .exceptionOrNull()
                ?.let(startupFailure::addSuppressed)
            throw startupFailure
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

    private suspend fun <T> suspendOperation(name: String, block: suspend () -> T): T {
        return try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Operation `$name` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
    }
}

private data class VpnRuntimeComponents(
    val cryptoSessionManager: CryptoSessionManager,
    val socketManager: SocketManager,
    val interfaceManager: InterfaceManager,
)

private fun createRuntimeComponents(engine: Engine): VpnRuntimeComponents {
    val tunPipePair = DuplexChannelPipe.create<ByteArray>()
    val networkPipePair = DuplexChannelPipe.create<UdpDatagram>()
    return VpnRuntimeComponents(
        cryptoSessionManager = CryptoSessionManagerImpl(
            tunPipe = tunPipePair.second,
            networkPipe = networkPipePair.second,
            engine = engine,
        ),
        socketManager = SocketManagerImpl(networkPipe = networkPipePair.first),
        interfaceManager = PlatformInterfaceFactory.create(tunPipePair.first),
    )
}
