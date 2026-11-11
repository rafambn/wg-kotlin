package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.crypto.factory.PeerSessionFactory
import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.resolveToIp
import com.rafambn.wgkotlin.util.toCidr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CryptoSessionTest {

    @Test
    fun createSessionsCreatesActiveSessionsForAllPeers() = runBlocking {
        val factory = RecordingSessionFactory()
        val manager = CryptoManager(peerSessionFactory = factory)

        val session = manager.createSession(configurationWithPeers("peer-a", "peer-b"))

        try {
            assertTrue(session.hasActiveSessions())
            assertEquals(2, session.peerStats().size)
            assertTrue(factory.createdSessions.all { peer -> peer.isActive })
        } finally {
            session.close()
        }
    }

    @Test
    fun managerCreatesIndependentSessions() = runBlocking {
        val factory = RecordingSessionFactory()
        val manager = CryptoManager(peerSessionFactory = factory)
        val first = manager.createSession(configurationWithPeers("peer-a", "peer-b"))
        try {
            val second = manager.createSession(configurationWithPeers("peer-b"))
            try {
                assertEquals(2, first.peerStats().size)
                assertEquals(1, second.peerStats().size)
            } finally {
                second.close()
            }
            assertTrue(first.hasActiveSessions())
            assertTrue(factory.createdSessions.take(2).all { it.closeCalls == 0 })
            assertEquals(1, factory.createdSessions.last().closeCalls)
        } finally {
            first.close()
        }
    }

    @Test
    fun createAfterClosingUsesTheNewPeerConfiguration() = runBlocking {
        val factory = RecordingSessionFactory()
        val manager = CryptoManager(peerSessionFactory = factory)

        var session = manager.createSession(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "10.0.0.1".resolveToIp(),
                    endpointPort = 51820,
                ),
            ),
        )

        session.close()
        session = manager.createSession(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "10.0.0.2".resolveToIp(),
                    endpointPort = 51821,
                ),
            ),
        )

        assertEquals(2, factory.createdSessions.size)
        assertEquals(1, factory.createdSessions.first().closeCalls)
        assertEquals(0, factory.createdSessions.last().closeCalls)

        val stats = session.peerStats().singleOrNull { stats -> stats.publicKey == "peer-a" }
        assertNotNull(stats)
        assertEquals("10.0.0.2", stats.endpointAddress)
        assertEquals(51821, stats.endpointPort)
        session.close()
    }

    @Test
    fun duplicatePeerKeysAreRejected() {
        val manager = CryptoManager(peerSessionFactory = RecordingSessionFactory())

        val duplicated = configurationWithPeer(
            VpnPeer(publicKey = "peer-a"),
            VpnPeer(publicKey = "peer-a"),
        )

        assertFailsWith<IllegalArgumentException> {
            manager.createSession(duplicated)
        }
    }

    @Test
    fun partialCreateFailureRollsBackOnlyNewlyCreatedPeers() = runBlocking {
        val factory = RecordingSessionFactory(failOnPeer = "peer-b")
        val manager = CryptoManager(peerSessionFactory = factory)
        val active = manager.createSession(configurationWithPeers("peer-a"))
        try {
            val throwable = assertFailsWith<IllegalStateException> {
                manager.createSession(configurationWithPeers("peer-a", "peer-b"))
            }

            assertTrue(throwable.message?.contains("factory forced failure") == true)
            assertTrue(active.hasActiveSessions())
            assertEquals(2, factory.createdSessions.size)
            assertEquals(0, factory.createdSessions.first().closeCalls)
            assertEquals(1, factory.createdSessions.last().closeCalls)
            val next = manager.createSession(configurationWithPeers("peer-c"))
            try {
                assertTrue(next.hasActiveSessions())
            } finally {
                next.close()
            }
        } finally {
            active.close()
        }
    }

    @Test
    fun invalidDirectAllowedNetworksFailBeforeCreatingSessions() {
        for ((address, prefix) in listOf(
            "192.0.2.1" to 33u, "2001:db8::1" to 129u,
            "192.0.2.1" to 4294967288u, "2001:db8::1" to UInt.MAX_VALUE,
        )) {
            val factory = RecordingSessionFactory()
            val manager = CryptoManager(peerSessionFactory = factory)
            val route = Cidr { ip = address.resolveToIp(); this.prefix = prefix }
            val peer = VpnPeer(
                publicKey = "peer-a", endpointAddress = "192.0.2.1".resolveToIp(), endpointPort = 51820,
                allowedIps = listOf(route),
            )

            assertFailsWith<IllegalArgumentException>("$address/$prefix") {
                manager.createSession(configurationWithPeer(peer))
            }
            assertTrue(factory.createdSessions.isEmpty())
        }
    }

    @Test
    fun malformedEndpointIpsFailBeforeCreatingSessions() {
        val addresses = listOf(
            Ip { },
            Ip { value = Ip.Value.V4(ByteString(byteArrayOf(192.toByte(), 0, 2))) },
            Ip { value = Ip.Value.V6(ByteString(ByteArray(15))) },
        )
        for (address in addresses) {
            val factory = RecordingSessionFactory()
            val manager = CryptoManager(peerSessionFactory = factory)
            val peer = VpnPeer(publicKey = "peer-a", endpointAddress = address, endpointPort = 51820)

            assertFailsWith<IllegalArgumentException> { manager.createSession(configurationWithPeer(peer)) }
            assertTrue(factory.createdSessions.isEmpty())
        }
    }

    @Test
    fun mappedIpv6EndpointsExchangePacketsUsingTheirIpv4TransportAddress() = runBlocking {
        val (testTun, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        val manager = CryptoManager(peerSessionFactory = DataPlaneSessionFactory())
        val mapped = Ip {
            value = Ip.Value.V6(ByteString(byteArrayOf(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 192.toByte(), 0, 2, 1,
            )))
        }
        val peer = VpnPeer(
            publicKey = "peer-a", endpointAddress = mapped, endpointPort = 51820,
            allowedIps = listOf("0.0.0.0/0".toCidr()),
        )
        val failure = CompletableDeferred<Throwable>()
        val session = manager.create(
            configurationWithPeer(peer),
            tunPipe = cryptoTun, networkPipe = cryptoNet, onFailure = { failure.complete(it) },
        )
        try {
            val packet = fakeIpv4Packet()
            val expectedEndpoint = UdpEndpoint("192.0.2.1", 51820)
            testTun.send(packet)
            withTimeout(2_000) {
                while (true) {
                    val outgoing = testNet.receive()
                    assertEquals(expectedEndpoint, outgoing.remoteEndpoint)
                    if (outgoing.payload.contentEquals(packet + ENCRYPT_MARKER)) break
                }
            }

            testNet.send(UdpDatagram(packet, expectedEndpoint))
            assertContentEquals(packet + DECRYPT_MARKER, withTimeout(2_000) { testTun.receive() })
            assertFalse(failure.isCompleted)
        } finally {
            session.close()
        }
    }

    @Test
    fun peerIndexesAreDeterministicAcrossPeerOrderChanges() = runBlocking {
        val factory = RecordingSessionFactory()
        val manager = CryptoManager(peerSessionFactory = factory)

        var session = manager.createSession(configurationWithPeers("peer-b", "peer-a"))
        val first = factory.createdSessions
            .take(2)
            .associate { session -> session.peerPublicKey to session.peerIndex }

        session.close()
        session = manager.createSession(configurationWithPeers("peer-a", "peer-b"))
        val second = factory.createdSessions
            .drop(2)
            .associate { session -> session.peerPublicKey to session.peerIndex }

        assertEquals(first, second)
        assertEquals(1, second["peer-a"])
        assertEquals(2, second["peer-b"])
        session.close()
    }

    @Test
    fun closeClearsActiveSessions() = runBlocking {
        val factory = RecordingSessionFactory()
        val manager = CryptoManager(peerSessionFactory = factory)

        val session = manager.createSession(configurationWithPeers("peer-a"))
        assertTrue(session.hasActiveSessions())

        session.close()

        assertTrue(session.peerStats().isEmpty())
        assertEquals(1, factory.createdSessions.first().closeCalls)
    }

    private fun configurationWithPeers(vararg peerKeys: String): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = peerKeys.mapIndexed { index, key ->
                VpnPeer(
                    publicKey = key,
                    endpointAddress = "198.51.100.${index + 1}".resolveToIp(),
                    endpointPort = 51820 + index,
                )
            },
        )
    }

    private fun configurationWithPeer(vararg peers: VpnPeer): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = peers.toList(),
        )
    }

    private fun CryptoManager.createSession(config: VpnConfiguration): CryptoSession {
        val (_, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (_, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        return create(config, cryptoTun, cryptoNet)
    }

    private class RecordingSessionFactory(
        private val failOnPeer: String? = null,
    ) : PeerSessionFactory {
        val createdSessions: MutableList<TestPeerSession> = mutableListOf()

        override fun create(
            config: VpnConfiguration,
            peer: VpnPeer,
            peerIndex: Int,
        ): PeerSession {
            if (peer.publicKey == failOnPeer) {
                throw IllegalStateException("factory forced failure for `${peer.publicKey}`")
            }

            val session = TestPeerSession(
                peerPublicKey = peer.publicKey,
                peerIndex = peerIndex,
            )
            createdSessions += session
            return session
        }

    }

    // ── Data-plane tests ──────────────────────────────────────────────────────

    @Test
    fun cleartextIngressEncryptsAndForwardsToNetwork() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val (testTun, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        val manager = CryptoManager(peerSessionFactory = factory)
        val session = manager.create(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1".resolveToIp(),
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0".toCidr()),
                ),
            ),
            tunPipe = cryptoTun, networkPipe = cryptoNet, onFailure = { throw it },
        )

        testTun.send(fakeIpv4Packet())

        val datagram = withTimeout(2_000) { testNet.receive() }
        assertEquals("198.51.100.1", datagram.remoteEndpoint.address)
        assertEquals(51820, datagram.remoteEndpoint.port)
        assertTrue(datagram.payload.contentEquals(fakeIpv4Packet() + ENCRYPT_MARKER))

        session.close()
    }

    @Test
    fun encryptedIngressDecryptsAndForwardsToTun() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val (testTun, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        val manager = CryptoManager(peerSessionFactory = factory)
        val session = manager.create(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1".resolveToIp(),
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0".toCidr()),
                ),
            ),
            tunPipe = cryptoTun, networkPipe = cryptoNet, onFailure = { throw it },
        )

        val encryptedPayload = fakeIpv4Packet()
        testNet.send(
            UdpDatagram(
                payload = encryptedPayload,
                remoteEndpoint = UdpEndpoint(address = "198.51.100.1", port = 51820),
            ),
        )

        val decrypted = withTimeout(2_000) { testTun.receive() }
        assertTrue(decrypted.contentEquals(encryptedPayload + DECRYPT_MARKER))

        session.close()
    }

    @Test
    fun inboundStatsAccountForReceivedBytes() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val (_, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        val manager = CryptoManager(peerSessionFactory = factory)
        val session = manager.create(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1".resolveToIp(),
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0".toCidr()),
                ),
            ),
            tunPipe = cryptoTun, networkPipe = cryptoNet, onFailure = { throw it },
        )

        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        testNet.send(UdpDatagram(payload = payload, remoteEndpoint = UdpEndpoint("198.51.100.1", 51820)))

        withTimeout(2_000) {
            while (session.peerStats().firstOrNull { it.publicKey == "peer-a" }?.receivedBytes == 0L) {
                delay(10)
            }
        }

        val stats = session.peerStats().single { it.publicKey == "peer-a" }
        assertEquals(payload.size.toLong(), stats.receivedBytes)

        session.close()
    }

    @Test
    fun periodicTaskSendsKeepaliveToNetwork() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val (_, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        val manager = CryptoManager(peerSessionFactory = factory)
        val session = manager.create(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1".resolveToIp(),
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0".toCidr()),
                ),
            ),
            tunPipe = cryptoTun, networkPipe = cryptoNet, onFailure = { throw it },
        )

        val keepalive = withTimeout(2_000) { testNet.receive() }
        assertTrue(keepalive.payload.contentEquals(KEEPALIVE_BYTES))
        assertEquals("198.51.100.1", keepalive.remoteEndpoint.address)

        session.close()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fakeIpv4Packet(dst: ByteArray = byteArrayOf(8, 8, 8, 8)): ByteArray {
        return byteArrayOf(
            0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00,
            10, 0, 0, 1,
            dst[0], dst[1], dst[2], dst[3],
        )
    }

    private class DataPlaneSessionFactory : PeerSessionFactory {
        override fun create(config: VpnConfiguration, peer: VpnPeer, peerIndex: Int): PeerSession {
            return DataPlanePeerSession(peerPublicKey = peer.publicKey, peerIndex = peerIndex)
        }
    }

    private class DataPlanePeerSession(
        override val peerPublicKey: String,
        override val peerIndex: Int,
    ) : PeerSession {
        private var closed = false
        override val isActive: Boolean get() = !closed
        override fun close() { closed = true }

        override fun encryptRawPacket(src: ByteArray, dstSize: UInt): PacketAction =
            PacketAction.WriteToNetwork(src + ENCRYPT_MARKER)

        override fun decryptToRawPacket(src: ByteArray, dstSize: UInt, sourceIp: String): PacketAction =
            if (src.isEmpty()) PacketAction.Done else PacketAction.WriteToTunIpv4(src + DECRYPT_MARKER)

        override fun runPeriodicTask(dstSize: UInt): PacketAction =
            PacketAction.WriteToNetwork(KEEPALIVE_BYTES)
    }

    private class TestPeerSession(
        override val peerPublicKey: String,
        override val peerIndex: Int,
    ) : PeerSession {
        var closeCalls: Int = 0

        override fun runPeriodicTask(dstSize: UInt): PacketAction = PacketAction.Done

        override val isActive: Boolean
            get() = closeCalls == 0

        override fun close() {
            closeCalls += 1
        }
    }

    private companion object {
        val ENCRYPT_MARKER = byteArrayOf(0xEE.toByte())
        val DECRYPT_MARKER = byteArrayOf(0xDD.toByte())
        val KEEPALIVE_BYTES = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
    }
}
