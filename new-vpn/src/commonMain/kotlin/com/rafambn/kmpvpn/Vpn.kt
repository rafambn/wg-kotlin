package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.PlatformInterfaceFactory
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.TunnelManagerImpl
import com.rafambn.kmpvpn.session.TunnelManager

/**
 * Core orchestrator facade for VPN lifecycle operations.
 *
 * Manages the full lifecycle of a VPN interface (create, start, stop, delete)
 * while delegating peer-session and interface concerns to [TunnelManager] and [InterfaceManager].
 */
class Vpn internal constructor(
    private var vpnConfiguration: VpnConfiguration,
    private val tunnelManager: TunnelManager,
    private val interfaceManager: InterfaceManager,
) : AutoCloseable {
    constructor(
        configuration: VpnConfiguration,
        engine: Engine = Engine.BORINGTUN,
    ) : this(
        vpnConfiguration = configuration,
        tunnelManager = TunnelManagerImpl(engine = engine),
        interfaceManager = PlatformInterfaceFactory.create(configuration),
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
     * Returns whether the interface exists and active peer sessions are running.
     */
    fun isRunning(): Boolean {
        if (!exists()) {
            return false
        }
        if (!interfaceManager.isUp()) {
            return false
        }

        return tunnelManager.hasActiveSessions()
    }

    /**
     * Creates the interface and returns the managed interface contract.
     */
    fun create(): InterfaceManager {
        interfaceOperation("create") {
            interfaceManager.create(vpnConfiguration)
        }

        sessionOperation("reconcileSessions") {
            tunnelManager.reconcileSessions(interfaceManager.configuration())
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

        val currentConfiguration = interfaceOperation("configuration") {
            managedInterface.configuration()
        }
        requireValidConfiguration(currentConfiguration)

        interfaceOperation("reconfigure") {
            managedInterface.reconfigure(currentConfiguration)
        }

        sessionOperation("reconcileSessions") {
            tunnelManager.reconcileSessions(currentConfiguration)
        }

        try {
            interfaceOperation("up") { managedInterface.up() }
        } catch (throwable: IllegalStateException) {
            runBestEffort { tunnelManager.closeAll() }
            throw throwable
        }

        try {
            sessionOperation("startDataPlane") {
                tunnelManager.startDataPlane(configuration = currentConfiguration)
            }
        } catch (throwable: IllegalStateException) {
            runBestEffort { managedInterface.down() }
            runBestEffort { tunnelManager.closeAll() }
            throw throwable
        }

        return managedInterface
    }

    /**
     * Stops the interface. This operation is idempotent.
     */
    fun stop() {
        sessionOperation("closeAll") {
            tunnelManager.closeAll()
        }

        interfaceOperation("down") {
            interfaceManager.down()
        }
    }

    /**
     * Deletes the interface. This operation is idempotent.
     */
    fun delete() {
        sessionOperation("closeAll") {
            tunnelManager.closeAll()
        }

        interfaceOperation("delete") {
            interfaceManager.delete()
        }
    }

    /**
     * Returns current live interface information, or `null` if the interface does not exist.
     */
    fun information(): VpnInterfaceInformation? {
        if (!exists()) {
            return null
        }

        val baseInformation = interfaceOperation("readInformation") {
            interfaceManager.readInformation()
        }

        val currentDefinedConfiguration = interfaceOperation("configuration") {
            interfaceManager.configuration()
        }

        val liveInformation = baseInformation ?: VpnInterfaceInformation(
            interfaceName = currentDefinedConfiguration.interfaceName,
            isUp = interfaceManager.isUp(),
            addresses = currentDefinedConfiguration.addresses.toList(),
            dnsDomainPool = currentDefinedConfiguration.dnsDomainPool,
            mtu = currentDefinedConfiguration.mtu,
            listenPort = currentDefinedConfiguration.listenPort,
        )

        val runtimePeerStats = tunnelManager.peerStats()
        val informationWithPeerStats = if (runtimePeerStats.isEmpty()) {
            liveInformation
        } else {
            liveInformation.copy(peerStats = runtimePeerStats)
        }

        return informationWithPeerStats.copy(vpnConfiguration = currentDefinedConfiguration)
    }

    /**
     * Replaces current interface configuration and reconciles sessions.
     */
    fun reconfigure(config: VpnConfiguration) {
        requireValidConfiguration(config)
        require(config.interfaceName == vpnConfiguration.interfaceName) {
            "Cannot reconfigure interface `${vpnConfiguration.interfaceName}` using `${config.interfaceName}`"
        }

        interfaceOperation("reconfigure") {
            interfaceManager.reconfigure(config)
        }

        vpnConfiguration = config

        sessionOperation("reconcileSessions") {
            tunnelManager.reconcileSessions(interfaceManager.configuration())
        }

        if (interfaceManager.isUp()) {
            sessionOperation("startDataPlane") {
                tunnelManager.startDataPlane(configuration = interfaceManager.configuration())
            }
        }
    }

    override fun close() {
        stop()
    }

    private inline fun <T> interfaceOperation(name: String, block: () -> T): T {
        return operation(kind = "Interface", name = name, block = block)
    }

    private inline fun <T> sessionOperation(name: String, block: () -> T): T {
        return operation(kind = "Session", name = name, block = block)
    }

    private inline fun <T> operation(kind: String, name: String, block: () -> T): T {
        return try {
            block()
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "$kind operation `$name` failed: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }
    }

    private inline fun runBestEffort(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // preserve original failure during rollback
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 51820
    }
}
