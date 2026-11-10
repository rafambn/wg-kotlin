package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.iface.VpnInterfaceInformation

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
class Vpn(
    val interfaceName: String,
    private val engine: Engine = Engine.BORINGTUN
) {

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }

    private val execution = AtomicReference<VpnExecution?>(null)

    init {
        requireNonBlankInterfaceName(interfaceName)
        requireValidRegex(interfaceName)
    }

    fun isRunning(): Boolean {
        val current = execution.load() ?: return false
        return current.interfaceManager.isRunning() && current.cryptoSessionManager.hasActiveSessions()
    }

    fun open(configuration: VpnConfiguration) {
        requireValidConfiguration(configuration)
        require(configuration.interfaceName == interfaceName) {
            "Configuration interface name `${configuration.interfaceName}` does not match this Vpn's interface name `$interfaceName`"
        }

        val parsed = configuration.toParsedVpnConfiguration()
        stop()

        val current = VpnExecution(configuration, engine)
        check(execution.compareAndSet(null, current)) { "Another VPN execution is already opening" }
        val startupFailure = AtomicReference<Throwable?>(null)
        val onFailure: (Throwable) -> Unit = { error ->
            startupFailure.compareAndSet(null, error)
            stop(current)
        }
        fun checkOpening() {
            startupFailure.load()?.let { throw it }
            check(execution.load() === current) { "VPN stopped during startup" }
        }
        try {
            operation("reconcileSessions") {
                current.cryptoSessionManager.reconcileSessions(parsed)
            }
            checkOpening()

            operation("start") {
                current.cryptoSessionManager.start(onFailure)
            }
            checkOpening()

            operation("socketStart") {
                current.socketManager.start(
                    listenPort = configuration.listenPort ?: DEFAULT_PORT,
                    onFailure = onFailure,
                )
            }
            checkOpening()

            operation("start") {
                current.interfaceManager.start(parsed.toTunSessionConfig(), onFailure)
            }
            checkOpening()
        } catch (error: Throwable) {
            execution.compareAndSet(current, null)
            try {
                cleanup(current)
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    fun information(): VpnInterfaceInformation? {
        val current = execution.load() ?: return null
        val liveInformation = operation("information") {
            current.interfaceManager.information()
        } ?: return null

        val runtimePeerStats = current.cryptoSessionManager.peerStats()
        val informationWithPeerStats = if (runtimePeerStats.isEmpty()) {
            liveInformation
        } else {
            liveInformation.copy(peerStats = runtimePeerStats)
        }

        return informationWithPeerStats.copy(vpnConfiguration = current.configuration)
    }

    fun stop() {
        execution.load()?.let { stop(it) }
    }

    private fun stop(current: VpnExecution) {
        if (execution.compareAndSet(current, null)) cleanup(current)
    }

    private fun cleanup(current: VpnExecution) {
        var firstError: Throwable? = null
        try {
            operation("stop") { current.interfaceManager.stop() }
        } catch (error: Throwable) {
            firstError = error
        }
        try {
            operation("socketStop") { current.socketManager.stop() }
        } catch (error: Throwable) {
            if (firstError == null) firstError = error
        }
        try {
            operation("stop") { current.cryptoSessionManager.stop() }
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
