package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.InMemoryTunnelManager
import com.rafambn.kmpvpn.session.io.InMemoryTunPort
import com.rafambn.kmpvpn.session.io.TunPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VpnStateTransitionTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val publicKey = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun lifecycleTransitionsFollowContract() {
        val vpn = testVpn(configuration = baseConfiguration(interfaceName = "wg0"))

        assertEquals(VpnState.NotCreated, vpn.state())

        vpn.create()
        assertEquals(VpnState.Created, vpn.state())

        vpn.start()
        assertEquals(VpnState.Running, vpn.state())

        vpn.stop()
        assertEquals(VpnState.Created, vpn.state())

        vpn.delete()
        assertEquals(VpnState.NotCreated, vpn.state())
    }

    @Test
    fun stopAndDeleteAreIdempotent() {
        val vpn = testVpn(configuration = baseConfiguration(interfaceName = "wg1"))

        vpn.stop()
        assertEquals(VpnState.NotCreated, vpn.state())

        vpn.delete()
        vpn.delete()
        assertEquals(VpnState.NotCreated, vpn.state())
    }

    @Test
    fun interfaceFailureDoesNotPersistSyntheticState() {
        val vpn = Vpn(
            vpnConfiguration = baseConfiguration(interfaceName = "wg2"),
            tunnelManager = InMemoryTunnelManager(),
            interfaceManager = FailingUpInterfaceManager(),
        )

        assertFailsWith<IllegalStateException> {
            vpn.start()
        }

        assertEquals(VpnState.Created, vpn.state())
    }

    @Test
    fun startSkipsCreateWhenInterfaceAlreadyExists() {
        val configuration = baseConfiguration(interfaceName = "wg3")
        val interfaceManager = ExistingInterfaceManager(configuration)
        val vpn = Vpn(
            vpnConfiguration = configuration,
            tunnelManager = InMemoryTunnelManager(),
            interfaceManager = interfaceManager,
        )

        vpn.start()

        assertTrue(interfaceManager.isUp())
        assertEquals(VpnState.Running, vpn.state())
    }

    private fun baseConfiguration(interfaceName: String): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            privateKey = privateKey,
            publicKey = publicKey,
            peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1", endpointPort = 51820)),
        )
    }

    private class FailingUpInterfaceManager : InterfaceManager {
        private var created: Boolean = false
        private var currentConfiguration: VpnConfiguration? = null

        override fun exists(): Boolean = created

        override fun create(config: VpnConfiguration) {
            created = true
            currentConfiguration = config
        }

        override fun up() {
            error("boom")
        }

        override fun down() {
            // no-op
        }

        override fun delete() {
            created = false
            currentConfiguration = null
        }

        override fun isUp(): Boolean = false

        override fun configuration(): VpnConfiguration {
            return checkNotNull(currentConfiguration)
        }

        override fun tunPort(): TunPort {
            return object : TunPort {
                override suspend fun readPacket(): ByteArray? = null

                override suspend fun writePacket(packet: ByteArray) = Unit
            }
        }

        override fun reconfigure(config: VpnConfiguration) {
            currentConfiguration = config
        }

        override fun readInformation(): VpnInterfaceInformation {
            return VpnInterfaceInformation(
                interfaceName = "wg-failing",
                isUp = false,
                addresses = emptyList(),
                dnsDomainPool = (emptyList<String>() to emptyList()),
                mtu = null,
            )
        }
    }

    private class ExistingInterfaceManager(
        private var currentConfiguration: VpnConfiguration,
    ) : InterfaceManager {
        private var up: Boolean = false
        private val tun = InMemoryTunPort()

        override fun exists(): Boolean = true

        override fun create(config: VpnConfiguration) {
            error("create should not be called when the interface already exists")
        }

        override fun up() {
            up = true
        }

        override fun down() {
            up = false
        }

        override fun delete() {
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
            )
        }
    }
}
