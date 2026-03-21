package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.DefaultVpnAdapterConfiguration
import com.rafambn.kmpvpn.DefaultVpnConfiguration
import com.rafambn.kmpvpn.VpnAdapterConfiguration
import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.requireValidConfiguration

/**
 * JVM-backed [VpnInterface] implementation using [InterfaceCommandExecutor].
 */
class JvmVpnInterface(
    private val commandExecutor: InterfaceCommandExecutor,
) : VpnInterface {
    private var createdInterfaceName: String? = null
    private var currentConfiguration: VpnConfiguration? = null
    private var up: Boolean = false

    override fun exists(interfaceName: String): Boolean {
        if (createdInterfaceName == interfaceName) {
            val observedExists = commandExecutor.interfaceExists(interfaceName)
            if (!observedExists) {
                createdInterfaceName = null
                currentConfiguration = null
                up = false
            }
            return observedExists
        }
        return commandExecutor.interfaceExists(interfaceName)
    }

    override fun create(config: VpnConfiguration) {
        requireValidConfiguration(config)

        if (createdInterfaceName != null && createdInterfaceName != config.interfaceName) {
            throw IllegalStateException(
                "Cannot create `${config.interfaceName}` while `${createdInterfaceName ?: "unknown"}` is active",
            )
        }

        if (exists(config.interfaceName)) {
            createdInterfaceName = config.interfaceName
            if (currentConfiguration == null) {
                currentConfiguration = snapshot(config)
            }
            return
        }

        try {
            commandExecutor.createInterface(config.interfaceName)
            applyConfiguration(config)
        } catch (throwable: Throwable) {
            safeRun { commandExecutor.deleteInterface(config.interfaceName) }
            throw IllegalStateException(
                "Failed to create interface `${config.interfaceName}`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        createdInterfaceName = config.interfaceName
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
        val interfaceName = createdInterfaceName ?: return
        if (!up) {
            return
        }

        commandExecutor.setInterfaceUp(interfaceName, false)
        up = false
    }

    override fun delete() {
        val interfaceName = createdInterfaceName ?: return

        if (up) {
            safeRun { commandExecutor.setInterfaceUp(interfaceName, false) }
        }

        safeRun { commandExecutor.deleteInterface(interfaceName) }

        createdInterfaceName = null
        currentConfiguration = null
        up = false
    }

    override fun isUp(): Boolean {
        val interfaceName = createdInterfaceName ?: return false
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
            applyMtu(config)
            rollbackActions += { safeRun { applyMtu(previousConfiguration) } }

            applyAddresses(config)
            rollbackActions += { safeRun { applyAddresses(previousConfiguration) } }

            applyRoutes(config)
            rollbackActions += { safeRun { applyRoutes(previousConfiguration) } }

            applyDns(config)
            rollbackActions += { safeRun { applyDns(previousConfiguration) } }

            applyPeerConfiguration(interfaceName, config.adapter)
            rollbackActions += { safeRun { applyPeerConfiguration(interfaceName, previousConfiguration.adapter) } }
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
        val observedPeerStats = commandExecutor.readPeerStats(interfaceName)

        if (observedInformation != null) {
            up = observedInformation.isUp
            return observedInformation.copy(
                addresses = if (observedInformation.addresses.isEmpty()) {
                    configuration.addresses.toList()
                } else {
                    observedInformation.addresses
                },
                dnsServers = if (observedInformation.dnsServers.isEmpty()) {
                    configuration.dns.toList()
                } else {
                    observedInformation.dnsServers
                },
                mtu = observedInformation.mtu ?: configuration.mtu,
                peerStats = if (observedPeerStats.isNotEmpty()) {
                    observedPeerStats
                } else if (observedInformation.peerStats.isNotEmpty()) {
                    observedInformation.peerStats
                } else {
                    defaultPeerStats(configuration.adapter)
                },
            )
        }

        return VpnInterfaceInformation(
            interfaceName = interfaceName,
            isUp = up,
            addresses = configuration.addresses.toList(),
            dnsServers = configuration.dns.toList(),
            mtu = configuration.mtu,
            peerStats = if (observedPeerStats.isNotEmpty()) {
                observedPeerStats
            } else {
                defaultPeerStats(configuration.adapter)
            },
        )
    }

    private fun applyConfiguration(config: VpnConfiguration) {
        applyMtu(config)
        applyAddresses(config)
        applyRoutes(config)
        applyDns(config)
        applyPeerConfiguration(config.interfaceName, config.adapter)
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
            table = config.table,
        )
    }

    private fun applyDns(config: VpnConfiguration) {
        commandExecutor.applyDns(config.interfaceName, config.dns.toList())
    }

    private fun applyPeerConfiguration(interfaceName: String, adapter: VpnAdapterConfiguration) {
        commandExecutor.applyPeerConfiguration(interfaceName, adapter)
    }

    private fun routesFrom(config: VpnConfiguration): List<String> {
        return config.adapter.peers
            .flatMap { peer -> peer.allowedIps }
            .filter { route -> route.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun requireCreatedInterface(): String {
        return createdInterfaceName
            ?: throw IllegalStateException("Interface was not created")
    }

    private fun defaultPeerStats(adapter: VpnAdapterConfiguration): List<VpnPeerStats> {
        return adapter.peers.map { peer ->
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
            first.dns.toList() == second.dns.toList() &&
            first.mtu == second.mtu &&
            first.addresses.toList() == second.addresses.toList() &&
            first.table == second.table &&
            first.saveConfig == second.saveConfig &&
            adapterEquivalent(first.adapter, second.adapter)
    }

    private fun adapterEquivalent(first: VpnAdapterConfiguration, second: VpnAdapterConfiguration): Boolean {
        return first.listenPort == second.listenPort &&
            first.privateKey == second.privateKey &&
            first.publicKey == second.publicKey &&
            first.fwMark == second.fwMark &&
            first.peers == second.peers
    }

    private fun snapshot(config: VpnConfiguration): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = config.interfaceName,
            dns = config.dns.toMutableList(),
            mtu = config.mtu,
            addresses = config.addresses.toMutableList(),
            table = config.table,
            saveConfig = config.saveConfig,
            adapter = DefaultVpnAdapterConfiguration(
                listenPort = config.adapter.listenPort,
                privateKey = config.adapter.privateKey,
                publicKey = config.adapter.publicKey,
                fwMark = config.adapter.fwMark,
                peers = config.adapter.peers.map { peer ->
                    VpnPeer(
                        endpointPort = peer.endpointPort,
                        endpointAddress = peer.endpointAddress,
                        publicKey = peer.publicKey,
                        allowedIps = peer.allowedIps.toList(),
                        persistentKeepalive = peer.persistentKeepalive,
                        presharedKey = peer.presharedKey,
                    )
                },
            ),
        )
    }

    private fun safeRun(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // best-effort cleanup
        }
    }
}
