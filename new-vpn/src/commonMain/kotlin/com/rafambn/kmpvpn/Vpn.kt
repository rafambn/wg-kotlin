package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.TunnelManager

/**
 * Core orchestrator facade for VPN lifecycle operations.
 *
 * Manages the full lifecycle of a VPN interface (create, start, stop, delete)
 * while delegating session and interface concerns to [TunnelManager] and [InterfaceManager].
 */
class Vpn internal constructor(
    val vpnConfiguration: VpnConfiguration,
    private val tunnelManager: TunnelManager,
    private val interfaceManager: InterfaceManager,
) : AutoCloseable {
    constructor(
        configuration: VpnConfiguration,
        engine: Engine = Engine.BORINGTUN,
    ) : this(
        vpnConfiguration = configuration,
        tunnelManager = VpnKoinBootstrap.resolveDependencies(
            configuration = configuration,
            engine = engine,
        ).tunnelManager,
        interfaceManager = VpnKoinBootstrap.resolveDependencies(
            configuration = configuration,
            engine = engine,
        ).interfaceManager,
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
            VpnState.Running
        } else {
            VpnState.Created
        }
    }

    /**
     * Returns whether the VPN interface currently exists.
     */
    fun exists(): Boolean {
        return interfaceManager.exists()
    }

    /**
     * Returns whether the interface exists and active tunnel sessions are running.
     */
    fun isRunning(): Boolean {
        if (!exists()) {
            return false
        }
        if (!interfaceManager.isUp()) {
            return false
        }

        return tunnelManager.sessions().any { session -> session.isActive }
    }

    /**
     * Creates the interface and returns the managed interface contract.
     */
    fun create(): InterfaceManager {
        try {
            interfaceManager.create(vpnConfiguration)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `create` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            tunnelManager.reconcileSessions(interfaceManager.configuration())
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        return interfaceManager
    }

    /**
     * Starts the interface and ensures sessions are active.
     */
    fun start(): InterfaceManager {
        val managedInterface = if (exists()) {
            interfaceManager
        } else {
            create()
        }
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
            tunnelManager.reconcileSessions(currentConfiguration)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            managedInterface.up()
        } catch (throwable: Throwable) {
            tunnelManager.closeAll()
            throw IllegalStateException(
                "Interface operation `up` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            tunnelManager.startRuntime(
                configuration = currentConfiguration,
                interfaceManager = managedInterface,
            )
        } catch (throwable: Throwable) {
            managedInterface.down()
            tunnelManager.closeAll()
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
            tunnelManager.closeAll()
            return
        }

        try {
            tunnelManager.closeAll()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            interfaceManager.down()
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
            tunnelManager.closeAll()
            return
        }

        var sessionsClosed = false
        if (interfaceManager.isUp()) {
            try {
                tunnelManager.closeAll()
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
            sessionsClosed = true

            try {
                interfaceManager.down()
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Interface operation `down` failed: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
        }

        if (!sessionsClosed) {
            try {
                tunnelManager.closeAll()
            } catch (throwable: Throwable) {
                throw IllegalStateException(
                    "Session operation `closeAll` failed: ${throwable.message ?: "unknown"}",
                    throwable,
                )
            }
        }

        try {
            interfaceManager.delete()
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
            interfaceManager.configuration()
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
            interfaceManager.readInformation()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `readInformation` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        val runtimePeerStats = tunnelManager.peerStats()
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
            interfaceManager.reconfigure(config)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Interface operation `reconfigure` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            tunnelManager.reconcileSessions(interfaceManager.configuration())
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Session operation `reconcileSessions` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        if (interfaceManager.isUp()) {
            try {
                tunnelManager.startRuntime(
                    configuration = interfaceManager.configuration(),
                    interfaceManager = interfaceManager,
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

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }
}
