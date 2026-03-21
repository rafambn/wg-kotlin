package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.VpnInterface
import com.rafambn.kmpvpn.iface.VpnInterfaceInformation
import com.rafambn.kmpvpn.session.InMemorySessionManager
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
        val vpn = Vpn(vpnConfiguration = baseConfiguration(interfaceName = "wg0"))

        assertEquals(VpnState.NotCreated, vpn.state())

        vpn.create()
        assertEquals(VpnState.Created("wg0"), vpn.state())

        vpn.start()
        assertEquals(VpnState.Running("wg0"), vpn.state())

        vpn.stop()
        assertEquals(VpnState.Created("wg0"), vpn.state())

        vpn.delete()
        assertEquals(VpnState.NotCreated, vpn.state())
    }

    @Test
    fun stopAndDeleteAreIdempotent() {
        val vpn = Vpn(vpnConfiguration = baseConfiguration(interfaceName = "wg1"))

        vpn.stop()
        assertEquals(VpnState.NotCreated, vpn.state())

        vpn.delete()
        vpn.delete()
        assertEquals(VpnState.NotCreated, vpn.state())
    }

    @Test
    fun interfaceFailureEmitsFailureEventWithoutPersistingSyntheticState() {
        val emittedEvents: MutableList<VpnEvent> = mutableListOf()

        val vpn = Vpn(
            vpnConfiguration = baseConfiguration(interfaceName = "wg2"),
            onEvent = { event -> emittedEvents += event },
            sessionManager = InMemorySessionManager(),
            vpnInterface = FailingUpVpnInterface(),
        )

        assertFailsWith<IllegalStateException> {
            vpn.start()
        }

        assertEquals(VpnState.Created("wg2"), vpn.state())
        assertTrue(emittedEvents.any { event -> event is VpnEvent.Failure })
    }

    private fun baseConfiguration(interfaceName: String): VpnConfiguration {
        return DefaultVpnConfiguration(
            interfaceName = interfaceName,
            privateKey = privateKey,
            publicKey = publicKey,
            peers = listOf(VpnPeer(publicKey = peerKey)),
        )
    }

    private class FailingUpVpnInterface : VpnInterface {
        private var created: Boolean = false
        private var currentConfiguration: VpnConfiguration? = null

        override fun exists(interfaceName: String): Boolean = created

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

        override fun reconfigure(config: VpnConfiguration) {
            currentConfiguration = config
        }

        override fun readInformation(): VpnInterfaceInformation {
            return VpnInterfaceInformation(
                interfaceName = "wg-failing",
                isUp = false,
                addresses = emptyList(),
                dnsServers = emptyList(),
                mtu = null,
            )
        }
    }
}
