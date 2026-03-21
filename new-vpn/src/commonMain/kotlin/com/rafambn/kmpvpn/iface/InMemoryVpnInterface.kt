package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.requireValidConfiguration

internal class InMemoryVpnInterface : VpnInterface {
    private var createdInterfaceName: String? = null
    private var currentConfiguration: VpnConfiguration? = null
    private var up: Boolean = false

    override fun exists(interfaceName: String): Boolean {
        return createdInterfaceName == interfaceName
    }

    override fun create(config: VpnConfiguration) {
        requireValidConfiguration(config)

        createdInterfaceName = config.interfaceName
        currentConfiguration = config
        up = false
    }

    override fun up() {
        require(createdInterfaceName != null) {
            "Cannot bring interface up before create()"
        }
        up = true
    }

    override fun down() {
        if (createdInterfaceName == null) {
            return
        }
        up = false
    }

    override fun delete() {
        createdInterfaceName = null
        currentConfiguration = null
        up = false
    }

    override fun isUp(): Boolean {
        return up
    }

    override fun configuration(): VpnConfiguration {
        return requireCreatedConfiguration()
    }

    override fun reconfigure(config: VpnConfiguration) {
        requireValidConfiguration(config)
        require(createdInterfaceName != null) {
            "Cannot reconfigure before create()"
        }
        require(config.interfaceName == createdInterfaceName) {
            "Cannot reconfigure interface `${createdInterfaceName ?: "unknown"}` using `${config.interfaceName}`"
        }
        currentConfiguration = config
    }

    override fun readInformation(): VpnInterfaceInformation {
        val interfaceName = createdInterfaceName
            ?: throw IllegalStateException("Interface was not created")

        val vpnConfiguration = currentConfiguration

        return VpnInterfaceInformation(
            interfaceName = interfaceName,
            isUp = up,
            addresses = vpnConfiguration?.addresses?.toList() ?: emptyList(),
            dnsServers = vpnConfiguration?.dns?.toList() ?: emptyList(),
            mtu = vpnConfiguration?.mtu,
            peerStats = vpnConfiguration?.adapter?.peers.orEmpty().map { peer ->
                VpnPeerStats(
                    publicKey = peer.publicKey,
                    receivedBytes = 0L,
                    transmittedBytes = 0L,
                    lastHandshakeEpochSeconds = null,
                )
            },
        )
    }

    private fun requireCreatedConfiguration(): VpnConfiguration {
        return currentConfiguration ?: throw IllegalStateException(
            "Cannot access configuration before create()"
        )
    }

}
