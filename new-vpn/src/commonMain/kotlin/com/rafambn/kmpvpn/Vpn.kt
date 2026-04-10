package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.VpnInterface
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.SessionManager
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module

/**
 * Core orchestrator facade for VPN lifecycle operations.
 *
 * Manages the full lifecycle of a VPN interface (create, start, stop, delete)
 * while delegating session and interface concerns to [SessionManager] and [VpnInterface].
 */
class Vpn internal constructor(
    val vpnConfiguration: VpnConfiguration,
    private val sessionManager: SessionManager,
    private val vpnInterface: VpnInterface,
) : AutoCloseable {

    init {
        requireValidConfiguration(vpnConfiguration)
    }

    /**
     * Returns current lifecycle state derived from current system observations.
     */
    fun state(): VpnState {
        if (!exists()) {
            return VpnState.NotCreated
        }

        return if (isRunning()) {
            VpnState.Running
        } else {
            VpnState.Created
        }
    }

    /**
     * Returns whether the VPN interface currently exists.
     */
    fun exists(): Boolean {
        return vpnInterface.exists()
    }

    /**
     * Returns whether the interface exists and active tunnel sessions are running.
     */
    fun isRunning(): Boolean {
        if (!exists()) {
            return false
        }
        if (!vpnInterface.isUp()) {
            return false
        }

        return sessionManager.sessions().any { session -> session.isActive }
    }

    /**
     * Creates the interface and returns the managed interface contract.
     */
    fun create(): VpnInterface {
        try {
            vpnInterface.create(vpnConfiguration)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `create` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            sessionManager.reconcileSessions(vpnInterface.configuration().adapter)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        return vpnInterface
    }

    /**
     * Starts the interface and ensures sessions are active.
     */
    fun start(): VpnInterface {
        val managedInterface = create()
        if (isRunning()) {
            throw IllegalStateException(
                "`${vpnConfiguration.interfaceName}` already exists and is up"
            )
        }

        val currentConfiguration = try {
            managedInterface.configuration()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `configuration` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
        requireValidConfiguration(currentConfiguration)

        try {
            managedInterface.reconfigure(currentConfiguration)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `reconfigure` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            sessionManager.reconcileSessions(currentConfiguration.adapter)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            managedInterface.up()
        } catch (throwable: Throwable) {
            safeCloseSessions()
            throw IllegalStateException(
                "Interface operation `up` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            sessionManager.startRuntime(
                configuration = currentConfiguration,
                vpnInterface = managedInterface,
            )
        } catch (throwable: Throwable) {
            safeRun { managedInterface.down() }
            safeCloseSessions()
            throw IllegalStateException(
                "Session operation `startRuntime` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        return managedInterface
    }

    /**
     * Stops the interface. This operation is idempotent.
     */
    fun stop() {
        if (!exists()) {
            safeCloseSessions()
            return
        }

        try {
            sessionManager.closeAll()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            vpnInterface.down()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `down` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
    }

    /**
     * Deletes the interface. This operation is idempotent.
     */
    fun delete() {
        if (!exists()) {
            safeCloseSessions()
            return
        }

        var sessionsClosed = false
        if (vpnInterface.isUp()) {
            try {
                sessionManager.closeAll()
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
            sessionsClosed = true

            try {
                vpnInterface.down()
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Interface operation `down` failed: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
        }

        if (!sessionsClosed) {
            try {
                sessionManager.closeAll()
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
        }

        try {
            vpnInterface.delete()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `delete` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
    }

    /**
     * Returns current effective interface configuration.
     */
    fun configuration(): VpnConfiguration {
        return try {
            vpnInterface.configuration()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `configuration` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
    }

    /**
     * Returns current live interface information, or `null` if the interface does not exist.
     */
    fun information(): VpnInterfaceInformation? {
        if (!exists()) {
            return null
        }

        val baseInformation = try {
            vpnInterface.readInformation()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `readInformation` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        val runtimePeerStats = sessionManager.peerStats()
        return if (runtimePeerStats.isEmpty()) {
            baseInformation
        } else {
            baseInformation.copy(peerStats = runtimePeerStats)
        }
    }

    /**
     * Replaces current interface configuration and reconciles sessions.
     */
    fun reconfigure(config: VpnConfiguration) {
        requireValidConfiguration(config)
        require(config.interfaceName == vpnConfiguration.interfaceName) {
            "Cannot reconfigure interface `${vpnConfiguration.interfaceName}` using `${config.interfaceName}`"
        }

        try {
            vpnInterface.reconfigure(config)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `reconfigure` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            sessionManager.reconcileSessions(vpnInterface.configuration().adapter)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        if (vpnInterface.isUp()) {
            try {
                sessionManager.startRuntime(
                    configuration = vpnInterface.configuration(),
                    vpnInterface = vpnInterface,
                )
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Session operation `startRuntime` failed: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
        }
    }

    override fun close() {
        stop()
    }

    private fun safeCloseSessions() {
        try {
            sessionManager.closeAll()
        } catch (_: Throwable) {
            // best-effort rollback and cleanup
        }
    }

    private fun safeRun(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // best-effort rollback and cleanup
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 51820

        fun create(
            engine: Engine = Engine.BORINGTUN,
            vpnConfiguration: VpnConfiguration,
            overrideModules: List<Module> = emptyList(),
        ): Vpn {
            VpnKoinBootstrap.ensureKoinStarted(overrideModules)
            val koin = GlobalContext.get()
            val sessionManagerProvider = koin.get<SessionManagerProvider>()
            val vpnInterfaceProvider = koin.get<VpnInterfaceProvider>()

            return Vpn(
                vpnConfiguration = vpnConfiguration,
                sessionManager = sessionManagerProvider.create(engine),
                vpnInterface = vpnInterfaceProvider.create(vpnConfiguration),
            )
        }

        internal fun resetKoinForTests() {
            VpnKoinBootstrap.resetForTests()
        }
    }
}
