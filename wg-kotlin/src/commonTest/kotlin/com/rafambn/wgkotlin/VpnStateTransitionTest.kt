package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.util.resolveToIp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VpnStateTransitionTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun lifecycleTransitionsFollowContract() = runBlocking {
        val vpn = testVpn()

        assertNull(vpn.information("utun130"))

        vpn.start(baseConfiguration("utun130")).getOrThrow()
        assertTrue(vpn.information("utun130")?.isUp == true)

        vpn.stop("utun130").getOrThrow()
        assertNull(vpn.information("utun130"))
    }

    @Test
    fun startWithoutPeersStillRequiresStopBeforeRestarting() = runBlocking<Unit> {
        val vpn = testVpn()
        val configuration = baseConfiguration("utun131").copy(peers = emptyList())
        try {
            vpn.start(configuration).getOrThrow()
            assertTrue(vpn.information("utun131")?.isUp == true)
            assertIs<IllegalStateException>(vpn.start(configuration).exceptionOrNull())
        } finally {
            vpn.stop("utun131").getOrThrow()
        }
    }

    private fun baseConfiguration(interfaceName: String): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = interfaceName,
            listenPort = 0,
            privateKey = privateKey,
            peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1".resolveToIp(), endpointPort = 51820)),
        )
    }
}
