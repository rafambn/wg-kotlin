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
        val first = Vpn(configuration(interfaceName = "utun101"))
        val second = Vpn(configuration(interfaceName = "utun102"))

        assertNotSame(first, second)
        assertFalse(first.isRunning())
        assertFalse(second.isRunning())

        first.open()

        assertTrue(first.isRunning())
        assertFalse(second.isRunning())
    }

    @Test
    fun explicitEngineStillSupportsLifecycle() {
        val vpn = Vpn(
            configuration = configuration(interfaceName = "utun103"),
            engine = Engine.BORINGTUN,
        )

        vpn.open()
        assertTrue(vpn.isRunning())

        vpn.stop()
        assertFalse(vpn.isRunning())
    }

    private fun configuration(interfaceName: String): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = interfaceName,
            listenPort = when (interfaceName) {
                "utun101" -> 52101
                "utun102" -> 52102
                "utun103" -> 52103
                else -> 52100
            },
            privateKey = privateKey,
            peers = listOf(
                VpnPeer(
                    publicKey = peerKey,
                    endpointAddress = "198.51.100.1",
                    endpointPort = 51820,
                ),
            ),
        )
    }
}
