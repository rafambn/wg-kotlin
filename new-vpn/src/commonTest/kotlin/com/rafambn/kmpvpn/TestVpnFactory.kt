package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.InMemoryTunnelManager
import com.rafambn.kmpvpn.session.TunnelManager
import com.rafambn.kmpvpn.session.io.InMemoryTunPort
import com.rafambn.kmpvpn.session.io.TunPort

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
    private val tun = InMemoryTunPort()

    override fun exists(): Boolean = created

    override fun create(config: VpnConfiguration) {
        created = true
        currentConfiguration = config
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

    override fun configuration(): VpnConfiguration = currentConfiguration

    override fun tunPort(): TunPort = tun

    override fun reconfigure(config: VpnConfiguration) {
        currentConfiguration = config
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
