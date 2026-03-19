package com.rafambn.kmpvpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VpnFoundationWiringTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val publicKey = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun inMemoryLifecycleStillWorks() {
        val vpn = Vpn(
            vpnConfiguration = DefaultVpnConfiguration(
                interfaceName = "wg0",
                privateKey = privateKey,
                publicKey = publicKey,
                peers = listOf(VpnPeer(publicKey = peerKey))
            )
        )

        assertFalse(vpn.exists())

        vpn.create()
        assertTrue(vpn.exists())

        vpn.start()
        assertTrue(vpn.isRunning())

        vpn.stop()
        assertFalse(vpn.isRunning())

        vpn.delete()
        assertFalse(vpn.exists())
    }

    @Test
    fun createRemainsIdempotent() {
        val vpn = Vpn(
            vpnConfiguration = DefaultVpnConfiguration(
                interfaceName = "wg1",
                privateKey = privateKey,
                publicKey = publicKey,
                peers = listOf(VpnPeer(publicKey = peerKey))
            )
        )

        val first = vpn.create()
        val second = vpn.create()

        assertTrue(first === second)
    }

    @Test
    fun reconfigureAllowsFullConfigurationUpdate() {
        val vpn = Vpn(
            vpnConfiguration = DefaultVpnConfiguration(
                interfaceName = "wg2",
                dns = mutableListOf("1.1.1.1"),
                privateKey = privateKey,
                publicKey = publicKey,
                peers = listOf(VpnPeer(publicKey = peerKey)),
            ),
        )

        vpn.start()

        vpn.reconfigure(
            DefaultVpnConfiguration(
                interfaceName = "wg2",
                dns = mutableListOf("9.9.9.9"),
                addresses = mutableListOf("10.20.30.2/32"),
                privateKey = privateKey,
                publicKey = publicKey,
                peers = listOf(VpnPeer(publicKey = publicKey)),
            ),
        )

        val current = vpn.configuration()

        assertEquals(listOf("9.9.9.9"), current.dns)
        assertEquals(listOf("10.20.30.2/32"), current.addresses)
        assertEquals(listOf(publicKey), current.adapter.peers.map { peer -> peer.publicKey })
    }
}
