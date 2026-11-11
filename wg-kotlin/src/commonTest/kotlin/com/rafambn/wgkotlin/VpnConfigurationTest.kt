package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.resolveToIp
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals

class VpnConfigurationTest {
    @Test
    fun endpointExceptionsUseTheTransportFamilyWhileTunnelNetworksKeepTheirFamily() {
        val mapped = Ip {
            value = Ip.Value.V6(ByteString(byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 192.toByte(), 0, 2, 1,
            )))
        }
        val ipv4 = "192.0.2.1".resolveToIp()
        val ipv6 = "2001:db8::1".resolveToIp()
        val network = Cidr { ip = mapped; prefix = 128u }
        val configuration = VpnConfiguration(
            interfaceName = "utun1",
            privateKey = "test-key",
            addresses = listOf(network),
            peers = listOf(
                VpnPeer(publicKey = "mapped", endpointAddress = mapped, allowedIps = listOf(network)),
                VpnPeer(publicKey = "ipv4", endpointAddress = ipv4),
                VpnPeer(publicKey = "ipv6", endpointAddress = ipv6),
            ),
        )

        val session = configuration.toTunSessionConfig()

        assertEquals("utun1", session.interfaceName)
        assertEquals(listOf(ipv4, ipv6), session.peerEndpoints)
        assertEquals(listOf(network), session.peerAllowedIps)
        assertEquals(listOf(network), session.addresses)
        assertEquals(mapped, configuration.peers.first().endpointAddress)
    }
}
