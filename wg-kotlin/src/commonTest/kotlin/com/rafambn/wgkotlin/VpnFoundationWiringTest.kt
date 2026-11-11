package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.util.toCidr
import com.rafambn.wgkotlin.util.resolveToIp
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VpnFoundationWiringTest {

    private val privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs="
    private val publicKey = "V6w5nNq2WEYLRh3SeDsICoZ6irMIXja+6JGZveHFk/Q="
    private val peerKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I="

    @Test
    fun inMemoryLifecycleStillWorks() = runBlocking {
        val vpn = testVpn()

        assertNull(vpn.information("utun120"))

        vpn.start(
            VpnConfiguration(
                interfaceName = "utun120",
                listenPort = 0,
                privateKey = privateKey,
                peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1".resolveToIp(), endpointPort = 51820)),
            ),
        ).getOrThrow()
        assertTrue(vpn.information("utun120")?.isUp == true)

        vpn.stop("utun120").getOrThrow()
        assertNull(vpn.information("utun120"))
    }

    @Test
    fun repeatedStartFailsAndKeepsTheOriginalConfiguration() = runBlocking {
        val vpn = testVpn()

        val original = VpnConfiguration(
            interfaceName = "utun121",
            listenPort = 0,
            privateKey = privateKey,
            peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1".resolveToIp(), endpointPort = 51820)),
        )
        vpn.start(original).getOrThrow()
        val result = vpn.start(original.copy(mtu = 1400))
        assertIs<IllegalStateException>(result.exceptionOrNull())
        assertTrue(vpn.information("utun121")?.isUp == true)
        assertEquals(original, vpn.information("utun121")?.vpnConfiguration)
        vpn.stop("utun121").getOrThrow()
    }

    @Test
    fun stopThenStartWithNewConfigUpdatesConfiguration() = runBlocking {
        val vpn = testVpn()

        vpn.start(
            VpnConfiguration(
                interfaceName = "utun122",
                listenPort = 0,
                dns = DnsConfig(searchDomains = listOf("corp.local"), servers = listOf("1.1.1.1".resolveToIp())),
                privateKey = privateKey,
                peers = listOf(VpnPeer(publicKey = peerKey, endpointAddress = "198.51.100.1".resolveToIp(), endpointPort = 51820)),
            ),
        ).getOrThrow()

        vpn.stop("utun122").getOrThrow()

        vpn.start(
            VpnConfiguration(
                interfaceName = "utun122",
                listenPort = 0,
                dns = DnsConfig(searchDomains = listOf("corp.local"), servers = listOf("9.9.9.9".resolveToIp())),
                addresses = listOf("10.20.30.2/32".toCidr()),
                privateKey = privateKey,
                peers = listOf(VpnPeer(publicKey = publicKey, endpointAddress = "198.51.100.2".resolveToIp(), endpointPort = 51821)),
            ),
        ).getOrThrow()

        val current = requireNotNull(vpn.information("utun122")).vpnConfiguration
        requireNotNull(current)

        assertEquals(DnsConfig(searchDomains = listOf("corp.local"), servers = listOf("9.9.9.9".resolveToIp())), current.dns)
        assertEquals(listOf("10.20.30.2/32".toCidr()), current.addresses)
        assertEquals(listOf(publicKey), current.peers.map { peer -> peer.publicKey })
        vpn.stop("utun122").getOrThrow()
    }
}
