package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSessionManagerImpl
import com.rafambn.wgkotlin.iface.PlatformInterfaceFactory
import com.rafambn.wgkotlin.iface.VpnInterfaceInformation
import com.rafambn.wgkotlin.network.SocketManagerImpl
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Vpn(
    val interfaceName: String,
    engine: Engine = Engine.BORINGTUN
) {

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }

    private val tunPipePair = DuplexChannelPipe.create<ByteArray>()
    private val networkPipePair = DuplexChannelPipe.create<UdpDatagram>()
    private val cryptoSessionManager = CryptoSessionManagerImpl(tunPipePair.second, networkPipePair.second, engine)
    private val socketManager = SocketManagerImpl(networkPipePair.first)
    private val interfaceManager = PlatformInterfaceFactory.create(tunPipePair.first)
    // Blocking public calls and asynchronous failures must finish cleanup before reopening.
    private val lifecycleMutex = Mutex()
    private val failureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentParsedConfiguration: ParsedVpnConfiguration? = null
    private var originalConfiguration: VpnConfiguration? = null

    init {
        requireNonBlankInterfaceName(interfaceName)
        requireValidRegex(interfaceName)
    }

    fun isRunning(): Boolean = withLifecycleLock {
        interfaceManager.isRunning() && cryptoSessionManager.hasActiveSessions()
    }

    fun open(configuration: VpnConfiguration) {
        requireValidConfiguration(configuration)
        require(configuration.interfaceName == interfaceName) {
            "Configuration interface name `${configuration.interfaceName}` does not match this Vpn's interface name `$interfaceName`"
        }

        val parsed = configuration.toParsedVpnConfiguration()
        withLifecycleLock {
            stopManagers()
            currentParsedConfiguration = parsed
            originalConfiguration = configuration
            val failure = CompletableDeferred<Throwable>()
            val onFailure: (Throwable) -> Unit = { error ->
                if (failure.complete(error)) {
                    // Cleanup joins the workers, so it cannot run inside the worker that failed.
                    failureScope.launch {
                        lifecycleMutex.withLock {
                            if (currentParsedConfiguration === parsed) stopManagers()
                        }
                    }
                }
            }
            try {
                operation("reconcileSessions") { cryptoSessionManager.reconcileSessions(parsed) }
                operation("start") { cryptoSessionManager.start(onFailure) }
                operation("socketStart") {
                    socketManager.start(configuration.listenPort ?: DEFAULT_PORT, onFailure)
                }
                operation("start") { interfaceManager.start(parsed.toTunSessionConfig(), onFailure) }
                if (failure.isCompleted) throw runBlocking { failure.await() }
            } catch (error: Throwable) {
                try {
                    stopManagers()
                } catch (cleanupError: Throwable) {
                    error.addSuppressed(cleanupError)
                }
                throw error
            }
        }
    }

    fun information(): VpnInterfaceInformation? = withLifecycleLock {
        val liveInformation = operation("information") {
            interfaceManager.information()
        } ?: return@withLifecycleLock null

        val runtimePeerStats = cryptoSessionManager.peerStats()
        val informationWithPeerStats = if (runtimePeerStats.isEmpty()) {
            liveInformation
        } else {
            liveInformation.copy(peerStats = runtimePeerStats)
        }

        informationWithPeerStats.copy(vpnConfiguration = originalConfiguration)
    }

    fun stop() {
        withLifecycleLock { stopManagers() }
    }

    private fun stopManagers() {
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
        tunPipePair.first.discardPending()
        networkPipePair.first.discardPending()
    }

    private fun <T> withLifecycleLock(block: () -> T): T = runBlocking {
        lifecycleMutex.withLock { block() }
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
