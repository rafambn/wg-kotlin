package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.DefaultVpnConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.requireValidConfiguration
import com.rafambn.kmpvpn.session.io.TunPort

/**
 * JVM-backed [InterfaceManager] implementation using a local [TunProvider] plus
 * privileged [InterfaceCommandExecutor] operations.
 */
class JvmInterfaceManager(
    private val interfaceName: String,
    private val commandExecutor: InterfaceCommandExecutor,
    private val tunProvider: TunProvider,
) : InterfaceManager {
    private var currentConfiguration: VpnConfiguration? = null
    private var currentTunPort: OwnedTunPort? = null
    private var up: Boolean = false

    override fun exists(): Boolean {
        val observedExists = commandExecutor.interfaceExists(interfaceName)
        if (!observedExists) {
            clearState(closeTunPort = true)
        }
        return observedExists
    }

    override fun create(config: VpnConfiguration) {
        requireValidConfiguration(config)
        require(config.interfaceName == interfaceName) {
            "Cannot create interface `${config.interfaceName}` on a manager for `$interfaceName`"
        }

        if (currentTunPort != null) {
            if (currentConfiguration == null) currentConfiguration = snapshot(config)
            return
        }

        val tunPort = try {
            tunProvider.open(interfaceName)
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Failed to open tun interface `$interfaceName`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        try {
            currentTunPort = tunPort
            applyConfiguration(config)
        } catch (throwable: Throwable) {
            safeRun { tunPort.close() }
            clearState(closeTunPort = false)
            throw IllegalStateException(
                "Failed to create interface `$interfaceName`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        currentConfiguration = snapshot(config)
        up = false
    }

    override fun up() {
        val interfaceName = requireCreatedInterface()
        if (up) {
            return
        }

        commandExecutor.setInterfaceUp(interfaceName, true)
        up = true
    }

    override fun down() {
        if (currentTunPort == null) {
            return
        }
        if (!up) {
            return
        }

        commandExecutor.setInterfaceUp(interfaceName, false)
        up = false
    }

    override fun delete() {
        if (currentTunPort == null) {
            return
        }

        if (up) {
            safeRun { commandExecutor.setInterfaceUp(interfaceName, false) }
        }

        commandExecutor.deleteInterface(interfaceName)
        clearState(closeTunPort = true)
    }

    override fun isUp(): Boolean {
        if (currentTunPort == null) {
            return false
        }
        val observed = commandExecutor.readInformation(interfaceName)?.isUp
        if (observed != null) {
            up = observed
        }
        return up
    }

    override fun configuration(): VpnConfiguration {
        return snapshot(
            currentConfiguration ?: throw IllegalStateException(
                "Cannot access configuration before create()",
            ),
        )
    }

    override fun tunPort(): TunPort {
        return currentTunPort ?: throw IllegalStateException("Cannot access tunPort before create()")
    }

    override fun reconfigure(config: VpnConfiguration) {
        requireValidConfiguration(config)

        val interfaceName = requireCreatedInterface()
        require(config.interfaceName == interfaceName) {
            "Cannot reconfigure interface `$interfaceName` using `${config.interfaceName}`"
        }

        val previousConfiguration = currentConfiguration
            ?: throw IllegalStateException("Cannot reconfigure before create()")

        if (configurationsEquivalent(previousConfiguration, config)) {
            return
        }

        val rollbackActions: MutableList<() -> Unit> = mutableListOf()
        try {
            rollbackActions += { safeRun { applyMtu(previousConfiguration) } }
            applyMtu(config)

            rollbackActions += { safeRun { applyAddresses(previousConfiguration) } }
            applyAddresses(config)

            rollbackActions += { safeRun { applyRoutes(previousConfiguration) } }
            applyRoutes(config)

            rollbackActions += { safeRun { applyDns(previousConfiguration) } }
            applyDns(config)
        } catch (throwable: Throwable) {
            rollbackActions.asReversed().forEach { rollback -> rollback() }
            throw IllegalStateException(
                "Failed to reconfigure interface `$interfaceName`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        currentConfiguration = snapshot(config)
    }

    override fun readInformation(): VpnInterfaceInformation {
        val interfaceName = requireCreatedInterface()
        val configuration = currentConfiguration
            ?: throw IllegalStateException("Cannot read interface information before create()")
        val observedInformation = commandExecutor.readInformation(interfaceName)

        if (observedInformation != null) {
            up = observedInformation.isUp
            return observedInformation.copy(
                addresses = observedInformation.addresses.ifEmpty {
                    configuration.addresses.toList()
                },
                dnsDomainPool = if (observedInformation.dnsDomainPool.first.isEmpty() &&
                    observedInformation.dnsDomainPool.second.isEmpty()
                ) {
                    configuration.dnsDomainPool
                } else {
                    observedInformation.dnsDomainPool
                },
                mtu = observedInformation.mtu ?: configuration.mtu,
                peerStats = observedInformation.peerStats.ifEmpty {
                    defaultPeerStats(configuration.peers)
                },
            )
        }

        return VpnInterfaceInformation(
            interfaceName = interfaceName,
            isUp = up,
            addresses = configuration.addresses.toList(),
            dnsDomainPool = configuration.dnsDomainPool,
            mtu = configuration.mtu,
            peerStats = defaultPeerStats(configuration.peers),
        )
    }

    private fun applyConfiguration(config: VpnConfiguration) {
        applyMtu(config)
        applyAddresses(config)
        applyRoutes(config)
        applyDns(config)
    }

    private fun applyMtu(config: VpnConfiguration) {
        commandExecutor.applyMtu(config.interfaceName, config.mtu)
    }

    private fun applyAddresses(config: VpnConfiguration) {
        commandExecutor.applyAddresses(config.interfaceName, config.addresses.toList())
    }

    private fun applyRoutes(config: VpnConfiguration) {
        commandExecutor.applyRoutes(
            interfaceName = config.interfaceName,
            routes = routesFrom(config),
        )
    }

    private fun applyDns(config: VpnConfiguration) {
        commandExecutor.applyDns(config.interfaceName, config.dnsDomainPool)
    }

    private fun routesFrom(config: VpnConfiguration): List<String> {
        return config.peers
            .flatMap { peer -> peer.allowedIps }
            .filter { route -> route.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun requireCreatedInterface(): String {
        if (currentTunPort == null) {
            throw IllegalStateException("Interface `$interfaceName` was not created")
        }
        return interfaceName
    }

    private fun defaultPeerStats(peers: List<VpnPeer>): List<VpnPeerStats> {
        return peers.map { peer ->
            VpnPeerStats(
                publicKey = peer.publicKey,
                receivedBytes = 0L,
                transmittedBytes = 0L,
                lastHandshakeEpochSeconds = null,
            )
        }
    }

    private fun configurationsEquivalent(first: VpnConfiguration, second: VpnConfiguration): Boolean {
        return first.interfaceName == second.interfaceName &&
            first.dnsDomainPool == second.dnsDomainPool &&
            first.mtu == second.mtu &&
            first.addresses.toList() == second.addresses.toList() &&
            first.listenPort == second.listenPort &&
            first.privateKey == second.privateKey &&
            first.peers == second.peers
    }

    private fun snapshot(config: VpnConfiguration): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = config.interfaceName,
            dnsDomainPool = config.dnsDomainPool.first.toList() to config.dnsDomainPool.second.toList(),
            mtu = config.mtu,
            addresses = config.addresses.toMutableList(),
            listenPort = config.listenPort,
            privateKey = config.privateKey,
            peers = config.peers.map { peer ->
                VpnPeer(
                    endpointPort = peer.endpointPort,
                    endpointAddress = peer.endpointAddress,
                    publicKey = peer.publicKey,
                    allowedIps = peer.allowedIps.toList(),
                    persistentKeepalive = peer.persistentKeepalive,
                    presharedKey = peer.presharedKey,
                )
            },
        )
    }

    private fun safeRun(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // best-effort cleanup
        }
    }

    private fun clearState(closeTunPort: Boolean) {
        if (closeTunPort) {
            safeRun { currentTunPort?.close() }
        }
        currentTunPort = null
        currentConfiguration = null
        up = false
    }
}
