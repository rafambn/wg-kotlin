package com.rafambn.wgkotlin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class VpnConstructorWiringTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun secondaryConstructorBuildsIndependentInstances() {
        val first = Vpn(interfaceName = "utun101")
        val second = Vpn(interfaceName = "utun102")

        assertNotSame(first, second)
        assertFalse(first.isRunning())
        assertFalse(second.isRunning())

        first.open(
            VpnConfiguration(
                interfaceName = "utun101",
                listenPort = 52101,
                privateKey = privateKey,
                peers = listOf(
                    VpnPeer(
                        publicKey = peerKey,
                        endpointAddress = "198.51.100.1",
                        endpointPort = 51820,
                    ),
                ),
            ),
        )

        assertTrue(first.isRunning())
        assertFalse(second.isRunning())
    }

    @Test
    fun explicitEngineStillSupportsLifecycle() {
        val vpn = Vpn(
            interfaceName = "utun103",
            engine = Engine.BORINGTUN,
        )

        vpn.open(
            VpnConfiguration(
                interfaceName = "utun103",
                listenPort = 52103,
                privateKey = privateKey,
                peers = listOf(
                    VpnPeer(
                        publicKey = peerKey,
                        endpointAddress = "198.51.100.1",
                        endpointPort = 51820,
                    ),
                ),
            ),
        )
        assertTrue(vpn.isRunning())

        vpn.stop()
        assertFalse(vpn.isRunning())
    }
}
