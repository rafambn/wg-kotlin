package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.util.resolveToIp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VpnMultipleInterfacesTest {
    private val configuration = VpnConfiguration(
        interfaceName = "utun140",
        listenPort = 0,
        privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
        peers = listOf(VpnPeer(
            publicKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I=",
            endpointAddress = "198.51.100.1".resolveToIp(),
            endpointPort = 51820,
        )),
    )

    @Test
    fun stoppingAndRestartingOneTunPreservesTheOther() = runBlocking {
        val vpn = Vpn()
        val otherConfiguration = configuration.copy(
            interfaceName = "utun141",
            peers = listOf(configuration.peers.single().copy(endpointAddress = "198.51.100.2".resolveToIp())),
        )
        try {
            vpn.start(configuration).getOrThrow()
            vpn.start(otherConfiguration).getOrThrow()
            assertEquals("utun140", vpn.information("utun140")?.interfaceName)
            assertEquals("utun141", vpn.information("utun141")?.interfaceName)
            assertEquals("198.51.100.1", vpn.information("utun140")?.peerStats?.single()?.endpointAddress)
            assertEquals("198.51.100.2", vpn.information("utun141")?.peerStats?.single()?.endpointAddress)

            vpn.stop("utun140").getOrThrow()
            assertNull(vpn.information("utun140"))
            assertEquals(otherConfiguration, vpn.information("utun141")?.vpnConfiguration)

            val restarted = otherConfiguration.copy(interfaceName = "utun140")
            vpn.start(restarted).getOrThrow()
            assertEquals(restarted, vpn.information("utun140")?.vpnConfiguration)
            assertEquals(otherConfiguration, vpn.information("utun141")?.vpnConfiguration)
        } finally {
            vpn.stop("utun140").getOrThrow()
            vpn.stop("utun141").getOrThrow()
        }
    }

    @Test
    fun failedStartDoesNotStopAnotherTunOrReserveTheName() = runBlocking {
        val vpn = Vpn()
        val active = configuration.copy(interfaceName = "utun142")
        val another = configuration.copy(interfaceName = "utun143")
        try {
            vpn.start(active).getOrThrow()
            val result = vpn.start(another.copy(privateKey = "invalid-key"))
            assertTrue(result.isFailure)
            assertNull(vpn.information("utun143"))
            assertEquals(active, vpn.information("utun142")?.vpnConfiguration)

            vpn.start(another).getOrThrow()
            assertEquals(another, vpn.information("utun143")?.vpnConfiguration)
            assertEquals(active, vpn.information("utun142")?.vpnConfiguration)
        } finally {
            vpn.stop("utun142").getOrThrow()
            vpn.stop("utun143").getOrThrow()
        }
    }

    @Test
    fun stoppingAnUnknownTunLeavesActiveTunsUntouched() = runBlocking {
        val vpn = Vpn()
        val active = configuration.copy(interfaceName = "utun144")
        try {
            vpn.start(active).getOrThrow()
            vpn.stop("utun145").getOrThrow()
            assertNull(vpn.information("utun145"))
            assertEquals(active, vpn.information("utun144")?.vpnConfiguration)
        } finally {
            vpn.stop("utun144").getOrThrow()
        }
    }

    @Test
    fun concurrentStartsForTheSameNameStartOnlyOnce() = runBlocking {
        val vpn = Vpn()
        val active = configuration.copy(interfaceName = "utun146")
        try {
            val results = List(2) {
                async(Dispatchers.Default) { vpn.start(active) }
            }.awaitAll()
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            assertEquals(active, vpn.information("utun146")?.vpnConfiguration)
        } finally {
            vpn.stop("utun146").getOrThrow()
        }
    }
}
