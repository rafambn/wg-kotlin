package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.InMemoryTunnelManager
import com.rafambn.kmpvpn.session.TunnelManager

internal fun testVpn(
    configuration: VpnConfiguration,
    tunnelManager: TunnelManager = InMemoryTunnelManager(),
    interfaceManager: InterfaceManager = TestInterfaceManager(configuration),
): Vpn {
    return Vpn(
        vpnConfiguration = configuration,
        tunnelManager = tunnelManager,
        interfaceManager = interfaceManager,
    )
}

internal class TestInterfaceManager(
    private var currentConfiguration: VpnConfiguration,
) : InterfaceManager {
    private var created: Boolean = false
    private var up: Boolean = false

    override fun exists(): Boolean = created

    override fun create(config: VpnConfiguration) {
        created = true
        currentConfiguration = snapshotConfiguration(config)
    }

    override fun up() {
        up = true
    }

    override fun down() {
        up = false
    }

    override fun delete() {
        created = false
        up = false
    }

    override fun isUp(): Boolean = up

    override fun configuration(): VpnConfiguration = snapshotConfiguration(currentConfiguration)

    override fun reconfigure(config: VpnConfiguration) {
        currentConfiguration = snapshotConfiguration(config)
    }

    override fun readInformation(): VpnInterfaceInformation {
        return VpnInterfaceInformation(
            interfaceName = currentConfiguration.interfaceName,
            isUp = up,
            addresses = currentConfiguration.addresses,
            dnsDomainPool = currentConfiguration.dnsDomainPool,
            mtu = currentConfiguration.mtu,
            listenPort = currentConfiguration.listenPort,
        )
    }
}

internal fun snapshotConfiguration(config: VpnConfiguration): VpnConfiguration {
    return VpnConfiguration(
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
