package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.requireValidConfiguration

/**
 * JVM-backed [InterfaceManager] implementation using privileged
 * [InterfaceCommandExecutor] operations.
 */
class JvmInterfaceManager(
    private val interfaceName: String,
    private val commandExecutor: InterfaceCommandExecutor,
) : InterfaceManager {
    private var currentConfiguration: VpnConfiguration? = null
    private var up: Boolean = false

    override fun exists(): Boolean {
        val observedExists = commandExecutor.interfaceExists(interfaceName)
        if (!observedExists) {
            currentConfiguration = null
            up = false
        }
        return observedExists
    }

    override fun create(config: VpnConfiguration) {
        requireValidConfiguration(config)
        require(config.interfaceName == interfaceName) {
            "Cannot create interface `${config.interfaceName}` on a manager for `$interfaceName`"
        }

        if (currentConfiguration == config && commandExecutor.interfaceExists(interfaceName)) {
            return
        }

        try {
            applyMtu(config)
            applyAddresses(config)
            applyRoutes(config)
            applyDns(config)
        } catch (throwable: Throwable) {
            currentConfiguration = null
            up = false
            throw IllegalStateException(
                "Failed to create interface `$interfaceName`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        currentConfiguration = config.copy()
        up = false
    }

    override fun up() {
        if (up) {
            return
        }

        commandExecutor.setInterfaceUp(interfaceName, true)
        up = true
    }

    override fun down() {
        if (!up) {
            return
        }

        commandExecutor.setInterfaceUp(interfaceName, false)
        up = false
    }

    override fun delete() {
        commandExecutor.deleteInterface(interfaceName)
        currentConfiguration = null
        up = false
    }

    override fun isUp(): Boolean {
        val observed = commandExecutor.readInformation(interfaceName)?.isUp
        if (observed != null) {
            up = observed
        }
        return up
    }

    override fun configuration(): VpnConfiguration {
        val configuration = currentConfiguration ?: throw IllegalStateException(
            "Cannot access configuration before create()",
        )

        return configuration.copy()
    }

    override fun reconfigure(config: VpnConfiguration) {
        val previousConfiguration = currentConfiguration
            ?: throw IllegalStateException("Cannot reconfigure before create()")

        if (previousConfiguration == config) {
            return
        }

        require(config.interfaceName == interfaceName) {
            "Cannot reconfigure interface `$interfaceName` using `${config.interfaceName}`"
        }

        requireValidConfiguration(config)

        try {
            applyMtu(config)
            applyAddresses(config)
            applyRoutes(config)
            applyDns(config)
        } catch (throwable: Throwable) {
            restoreConfiguration(previousConfiguration)
            throw IllegalStateException(
                "Failed to reconfigure interface `$interfaceName`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        currentConfiguration = config.copy()
    }

    override fun readInformation(): VpnInterfaceInformation? {
        val observedInformation = commandExecutor.readInformation(interfaceName)

        if (observedInformation != null) {
            up = observedInformation.isUp
        }
        return observedInformation
    }

    private fun applyMtu(config: VpnConfiguration) {
        val mtu = config.mtu ?: return
        commandExecutor.applyMtu(config.interfaceName, mtu)
    }

    private fun applyAddresses(config: VpnConfiguration) {
        commandExecutor.applyAddresses(config.interfaceName, config.addresses.toList())
    }

    private fun applyRoutes(config: VpnConfiguration) {
        commandExecutor.applyRoutes(
            interfaceName = config.interfaceName,
            routes = config.peers
                .flatMap { peer -> peer.allowedIps }
                .filter { route -> route.isNotBlank() }
                .distinct()
                .sorted(),
        )
    }

    private fun applyDns(config: VpnConfiguration) {
        commandExecutor.applyDns(config.interfaceName, config.dnsDomainPool)
    }

    private fun restoreConfiguration(config: VpnConfiguration) {
        try {
            applyMtu(config)
            applyAddresses(config)
            applyRoutes(config)
            applyDns(config)
        } catch (_: Throwable) {
            // rollback is best-effort; original failure remains primary
        }
    }
}
