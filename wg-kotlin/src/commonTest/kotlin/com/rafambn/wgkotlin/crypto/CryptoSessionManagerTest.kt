package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.crypto.factory.PeerSessionFactory
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CryptoSessionManagerTest {

    @Test
    fun reconcileSessionsCreatesActiveSessionsForAllPeers() {
        val factory = RecordingSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))

        assertTrue(manager.hasActiveSessions())
        assertEquals(2, manager.peerStats().size)
        assertTrue(factory.createdSessions.all { session -> session.isActive })
    }

    @Test
    fun reconcileSessionsRemovesMissingPeers() {
        val factory = RecordingSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        manager.reconcileSessions(configurationWithPeers("peer-b"))

        assertNull(manager.peerStats().firstOrNull { stats -> stats.publicKey == "peer-a" })
        assertNotNull(manager.peerStats().firstOrNull { stats -> stats.publicKey == "peer-b" })
        assertEquals(1, factory.sessionByPeer("peer-a")?.closeCalls)
    }

    @Test
    fun reconcileSessionsReplacesChangedPeerConfiguration() {
        val factory = RecordingSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)

        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "10.0.0.1",
                    endpointPort = 51820,
                ),
            ),
        )

        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "10.0.0.2",
                    endpointPort = 51821,
                ),
            ),
        )

        assertEquals(2, factory.createdSessions.size)
        assertEquals(1, factory.createdSessions.first().closeCalls)
        assertEquals(0, factory.createdSessions.last().closeCalls)

        val session = manager.peerStats().singleOrNull { stats -> stats.publicKey == "peer-a" }
        assertNotNull(session)
        assertEquals("10.0.0.2", session.endpointAddress)
        assertEquals(51821, session.endpointPort)
    }

    @Test
    fun duplicatePeerKeysAreRejected() {
        val manager = CryptoSessionManagerImpl(peerSessionFactory = RecordingSessionFactory())

        val duplicated = configurationWithPeer(
            VpnPeer(publicKey = "peer-a"),
            VpnPeer(publicKey = "peer-a"),
        )

        assertFailsWith<IllegalArgumentException> {
            manager.reconcileSessions(duplicated)
        }
    }

    @Test
    fun partialCreateFailureRollsBackNewlyCreatedSessions() {
        val factory = RecordingSessionFactory(failOnPeer = "peer-b")
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)

        val throwable = assertFailsWith<IllegalStateException> {
            manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        }

        assertTrue(throwable.message?.contains("factory forced failure") == true)
        assertTrue(manager.peerStats().isEmpty())
        assertEquals(1, factory.createdSessions.size)
        assertEquals(1, factory.createdSessions.first().closeCalls)
    }

    @Test
    fun peerIndexesAreDeterministicAcrossPeerOrderChanges() {
        val factory = RecordingSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)

        manager.reconcileSessions(configurationWithPeers("peer-b", "peer-a"))
        val first = factory.createdSessions
            .take(2)
            .associate { session -> session.peerPublicKey to session.peerIndex }

        manager.reconcileSessions(configurationWithPeers("peer-a", "peer-b"))
        val second = factory.createdSessions
            .drop(2)
            .associate { session -> session.peerPublicKey to session.peerIndex }

        assertEquals(first, second)
        assertEquals(1, second["peer-a"])
        assertEquals(2, second["peer-b"])
    }

    @Test
    fun stopClearsActiveSessions() {
        val factory = RecordingSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)

        manager.reconcileSessions(configurationWithPeers("peer-a"))
        assertTrue(manager.hasActiveSessions())

        manager.stop()

        assertTrue(manager.peerStats().isEmpty())
        assertEquals(1, factory.createdSessions.first().closeCalls)
    }

    private fun configurationWithPeers(vararg peerKeys: String): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = "wg-test",
            privateKey = "private-key",
            peers = peerKeys.mapIndexed { index, key ->
                VpnPeer(
                    publicKey = key,
                    endpointAddress = "198.51.100.${index + 1}",
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

        fun sessionByPeer(peerPublicKey: String): TestPeerSession? {
            return createdSessions.find { session -> session.peerPublicKey == peerPublicKey }
        }
    }

    // ── Data-plane tests ──────────────────────────────────────────────────────

    @Test
    fun cleartextIngressEncryptsAndForwardsToNetwork() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)
        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1",
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0"),
                ),
            ),
        )

        val (testTun, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        manager.start(cryptoTun, cryptoNet, onFailure = { throw it })

        testTun.send(fakeIpv4Packet())

        val datagram = withTimeout(2_000) { testNet.receive() }
        assertEquals("198.51.100.1", datagram.remoteEndpoint.address)
        assertEquals(51820, datagram.remoteEndpoint.port)
        assertTrue(datagram.payload.contentEquals(fakeIpv4Packet() + ENCRYPT_MARKER))

        manager.stop()
    }

    @Test
    fun encryptedIngressDecryptsAndForwardsToTun() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)
        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1",
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0"),
                ),
            ),
        )

        val (testTun, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        manager.start(cryptoTun, cryptoNet, onFailure = { throw it })

        val encryptedPayload = byteArrayOf(0x01, 0x02, 0x03)
        testNet.send(
            UdpDatagram(
                payload = encryptedPayload,
                remoteEndpoint = UdpEndpoint(address = "198.51.100.1", port = 51820),
            ),
        )

        val decrypted = withTimeout(2_000) { testTun.receive() }
        assertTrue(decrypted.contentEquals(encryptedPayload + DECRYPT_MARKER))

        manager.stop()
    }

    @Test
    fun inboundStatsAccountForReceivedBytes() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)
        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1",
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0"),
                ),
            ),
        )

        val (_, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        manager.start(cryptoTun, cryptoNet, onFailure = { throw it })

        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        testNet.send(UdpDatagram(payload = payload, remoteEndpoint = UdpEndpoint("198.51.100.1", 51820)))

        // Allow the ingress worker to process the datagram.
        withTimeout(2_000) {
            while (manager.peerStats().firstOrNull { it.publicKey == "peer-a" }?.receivedBytes == 0L) {
                delay(10)
            }
        }

        val stats = manager.peerStats().single { it.publicKey == "peer-a" }
        assertEquals(payload.size.toLong(), stats.receivedBytes)

        manager.stop()
    }

    @Test
    fun periodicTaskSendsKeepaliveToNetwork() = runBlocking {
        val factory = DataPlaneSessionFactory()
        val manager = CryptoSessionManagerImpl(peerSessionFactory = factory)
        manager.reconcileSessions(
            configurationWithPeer(
                VpnPeer(
                    publicKey = "peer-a",
                    endpointAddress = "198.51.100.1",
                    endpointPort = 51820,
                    allowedIps = listOf("0.0.0.0/0"),
                ),
            ),
        )

        val (_, cryptoTun) = DuplexChannelPipe.create<ByteArray>()
        val (testNet, cryptoNet) = DuplexChannelPipe.create<UdpDatagram>()
        manager.start(cryptoTun, cryptoNet, onFailure = { throw it })

        // Periodic interval is 100 ms; wait up to 2 s for the first keepalive.
        val keepalive = withTimeout(2_000) { testNet.receive() }
        assertTrue(keepalive.payload.contentEquals(KEEPALIVE_BYTES))
        assertEquals("198.51.100.1", keepalive.remoteEndpoint.address)

        manager.stop()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fakeIpv4Packet(dst: ByteArray = byteArrayOf(8, 8, 8, 8)): ByteArray {
        // Minimal 20-byte IPv4 header with version=4, IHL=5.
        return byteArrayOf(
            0x45, 0x00, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00,
            10, 0, 0, 1,             // src: 10.0.0.1
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

        override fun decryptToRawPacket(src: ByteArray, dstSize: UInt): PacketAction =
            if (src.isEmpty()) PacketAction.Done else PacketAction.WriteToTunIpv4(src + DECRYPT_MARKER)

        override fun runPeriodicTask(dstSize: UInt): PacketAction =
            PacketAction.WriteToNetwork(KEEPALIVE_BYTES)
    }

    private class TestPeerSession(
        override val peerPublicKey: String,
        override val peerIndex: Int,
    ) : PeerSession {
        var closeCalls: Int = 0

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
