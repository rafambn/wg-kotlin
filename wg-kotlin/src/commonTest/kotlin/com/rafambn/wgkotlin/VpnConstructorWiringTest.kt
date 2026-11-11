package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.util.resolveToIp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class VpnConstructorWiringTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun constructorBuildsIndependentInstances() = runBlocking {
        val first = Vpn()
        val second = Vpn()

        assertNotSame(first, second)
        assertNull(first.information("utun101"))
        assertNull(second.information("utun101"))

        first.start(
            VpnConfiguration(
                interfaceName = "utun101",
                listenPort = 52101,
                privateKey = privateKey,
                peers = listOf(
                    VpnPeer(
                        publicKey = peerKey,
                        endpointAddress = "198.51.100.1".resolveToIp(),
                        endpointPort = 51820,
                    ),
                ),
            ),
        ).getOrThrow()

        assertTrue(first.information("utun101")?.isUp == true)
        assertNull(second.information("utun101"))
        first.stop("utun101").getOrThrow()
    }

    @Test
    fun explicitEngineStillSupportsLifecycle() = runBlocking {
        val vpn = Vpn(
            engine = Engine.BORINGTUN,
        )

        vpn.start(
            VpnConfiguration(
                interfaceName = "utun103",
                listenPort = 52103,
                privateKey = privateKey,
                peers = listOf(
                    VpnPeer(
                        publicKey = peerKey,
                        endpointAddress = "198.51.100.1".resolveToIp(),
                        endpointPort = 51820,
                    ),
                ),
            ),
        ).getOrThrow()
        assertTrue(vpn.information("utun103")?.isUp == true)

        vpn.stop("utun103").getOrThrow()
        assertNull(vpn.information("utun103"))
    }
}
