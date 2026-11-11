package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.crypto.factory.PeerSessionFactory
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.ipBytes
import com.rafambn.wgkotlin.util.resolveToIp
import com.rafambn.wgkotlin.util.toCidr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

class InboundSourceAuthorizationTest {
    @Test
    fun inboundSourcesBelongToTheAuthenticatedPeerByLongestPrefix() = runBlocking {
        val (tun, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (network, cryptoNetwork) = DuplexChannelPipe.create<UdpDatagram>()
        val factory = object : PeerSessionFactory {
            override fun create(config: VpnConfiguration, peer: VpnPeer, peerIndex: Int): PeerSession =
                object : PeerSession {
                    override val peerPublicKey = peer.publicKey
                    override val peerIndex = peerIndex
                    override var isActive = true
                    override fun close() { isActive = false }
                    override fun runPeriodicTask(dstSize: UInt) = PacketAction.Done
                    override fun decryptToRawPacket(src: ByteArray, dstSize: UInt, sourceIp: String): PacketAction = when {
                        src.isEmpty() -> PacketAction.Done
                        src[0].toInt() ushr 4 == 6 -> PacketAction.WriteToTunIpv6(src)
                        else -> PacketAction.WriteToTunIpv4(src)
                    }
                }
        }
        val manager = CryptoManager(peerSessionFactory = factory)
        val failure = CompletableDeferred<Throwable>()
        val session = manager.create(VpnConfiguration(
            interfaceName = "utun1",
            privateKey = "test-key",
            peers = listOf(
                VpnPeer(publicKey = "a", endpointAddress = "192.0.2.1".resolveToIp(), endpointPort = 51820,
                    allowedIps = listOf("10.0.0.0/24".toCidr(), "2001:db8::/64".toCidr())),
                VpnPeer(publicKey = "b", endpointAddress = "192.0.2.2".resolveToIp(), endpointPort = 51820,
                    allowedIps = listOf("10.0.0.3/32".toCidr(), "2001:db8::3/128".toCidr())),
            ),
        ), tunPipe = cryptoTun, networkPipe = cryptoNetwork, onFailure = { failure.complete(it) })
        try {
            suspend fun send(source: String, endpoint: String) {
                network.send(UdpDatagram(packet(source), UdpEndpoint(endpoint, 51820)))
            }
            // A's broad networks must not authorize B's more specific addresses.
            send("10.0.0.3", "192.0.2.1")
            send("2001:db8::3", "192.0.2.1")
            send("10.1.0.1", "192.0.2.1")
            send("2001:db9::1", "192.0.2.1")
            network.send(UdpDatagram(byteArrayOf(0x45), UdpEndpoint("192.0.2.1", 51820)))
            network.send(UdpDatagram(byteArrayOf(0x60), UdpEndpoint("192.0.2.1", 51820)))
            send("10.0.0.2", "192.0.2.2")
            send("2001:db8::2", "192.0.2.2")
            for ((source, endpoint) in listOf(
                "10.0.0.2" to "192.0.2.1", "2001:db8::2" to "192.0.2.1",
                "10.0.0.3" to "192.0.2.2", "2001:db8::3" to "192.0.2.2",
            )) {
                send(source, endpoint)
                assertContentEquals(packet(source), withTimeout(2_000) { tun.receive() })
            }
            assertFalse(failure.isCompleted, "Rejected packets must not terminate the VPN")
        } finally {
            session.close()
        }
    }

    private fun packet(source: String): ByteArray {
        val address = source.resolveToIp().ipBytes()!!
        val ipv4 = address.size == 4
        return ByteArray(if (ipv4) 20 else 40).also {
            it[0] = if (ipv4) 0x45 else 0x60
            address.copyInto(it, if (ipv4) 12 else 8)
        }
    }
}
