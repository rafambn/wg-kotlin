package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.PlatformInterfaceFactory
import com.rafambn.kmpvpn.iface.VpnInterface
import com.rafambn.kmpvpn.session.InMemorySessionManager
import com.rafambn.kmpvpn.session.SessionManager

/**
 * Core orchestrator facade for VPN lifecycle operations.
 *
 * This contract owns lifecycle transitions while delegating session and
 * interface concerns to [SessionManager] and [VpnInterface].
 */
class Vpn internal constructor(
    val vpnConfiguration: VpnConfiguration,
    private val onEvent: ((VpnEvent) -> Unit)?,
    private val sessionManager: SessionManager,
    private val vpnInterface: VpnInterface,
) : AutoCloseable {

    constructor(
        engine: Engine = Engine.BORINGTUN,
        vpnConfiguration: VpnConfiguration,
        onEvent: ((VpnEvent) -> Unit)? = null,
        vpnInterfaceFactory: (VpnConfiguration) -> VpnInterface = PlatformInterfaceFactory::create,
    ) : this(
        vpnConfiguration = vpnConfiguration,
        onEvent = onEvent,
        sessionManager = InMemorySessionManager(engine = engine),
        vpnInterface = vpnInterfaceFactory(vpnConfiguration),
    )

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
            VpnState.Running(vpnConfiguration.interfaceName)
        } else {
            VpnState.Created(vpnConfiguration.interfaceName)
        }
    }

    /**
     * Returns whether the VPN interface currently exists.
     */
    fun exists(): Boolean {
        return vpnInterface.exists(vpnConfiguration.interfaceName)
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
            if (!exists()) {
                vpnInterface.create(vpnConfiguration)
            }
        } catch (throwable: Throwable) {
            val description = "Interface operation `create` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }

        try {
            sessionManager.reconcileSessions(vpnInterface.configuration().adapter)
        } catch (throwable: Throwable) {
            val description = "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }

        return vpnInterface
    }

    /**
     * Starts the interface and ensures sessions are active.
     */
    fun start(): VpnInterface {
        val managedInterface = create()
        if (isRunning()) {
            val description = "`${vpnConfiguration.interfaceName}` already exists and is up"
            publishEvent(VpnEvent.Alert(description))
            throw IllegalStateException(description)
        }

        val currentConfiguration = try {
            managedInterface.configuration()
        } catch (throwable: Throwable) {
            val description = "Interface operation `configuration` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }
        requireValidConfiguration(currentConfiguration)

        try {
            managedInterface.reconfigure(currentConfiguration)
        } catch (throwable: Throwable) {
            val description = "Interface operation `reconfigure` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }

        try {
            sessionManager.reconcileSessions(currentConfiguration.adapter)
        } catch (throwable: Throwable) {
            val description = "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }

        try {
            managedInterface.up()
        } catch (throwable: Throwable) {
            val description = "Interface operation `up` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }

        return managedInterface
    }

    /**
     * Stops the interface. This operation is idempotent.
     */
    fun stop() {
        if (!exists()) {
            return
        }

        try {
            sessionManager.closeAll()
        } catch (throwable: Throwable) {
            val description = "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }

        try {
            vpnInterface.down()
        } catch (throwable: Throwable) {
            val description = "Interface operation `down` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }
    }

    /**
     * Deletes the interface. This operation is idempotent.
     */
    fun delete() {
        if (!exists()) {
            return
        }

        var sessionsClosed = false
        if (vpnInterface.isUp()) {
            try {
                sessionManager.closeAll()
            } catch (throwable: Throwable) {
                val description = "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}"
                publishEvent(VpnEvent.Failure(description))
                throw IllegalStateException(description, throwable)
            }
            sessionsClosed = true

            try {
                vpnInterface.down()
            } catch (throwable: Throwable) {
                val description = "Interface operation `down` failed: ${throwable.message ?: "unknown"}"
                publishEvent(VpnEvent.Failure(description))
                throw IllegalStateException(description, throwable)
            }
        }

        if (!sessionsClosed) {
            try {
                sessionManager.closeAll()
            } catch (throwable: Throwable) {
                val description = "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}"
                publishEvent(VpnEvent.Failure(description))
                throw IllegalStateException(description, throwable)
            }
        }

        try {
            vpnInterface.delete()
        } catch (throwable: Throwable) {
            val description = "Interface operation `delete` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }
    }

    /**
     * Returns current effective interface configuration.
     */
    fun configuration(): VpnConfiguration {
        return try {
            vpnInterface.configuration()
        } catch (throwable: Throwable) {
            val description = "Interface operation `configuration` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
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
            val description = "Interface operation `reconfigure` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }

        try {
            sessionManager.reconcileSessions(vpnInterface.configuration().adapter)
        } catch (throwable: Throwable) {
            val description = "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}"
            publishEvent(VpnEvent.Failure(description))
            throw IllegalStateException(description, throwable)
        }
    }

    override fun close() {
        delete()
    }

    private fun publishEvent(event: VpnEvent) {
        onEvent?.invoke(event)
    }

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }
}
