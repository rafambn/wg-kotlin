package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.io.InMemoryTunPort
import com.rafambn.kmpvpn.session.io.InMemoryUdpPort
import com.rafambn.kmpvpn.session.io.ManualPeriodicTicker
import com.rafambn.kmpvpn.session.io.UdpDatagram
import com.rafambn.kmpvpn.session.io.UdpEndpoint
import com.rafambn.kmpvpn.session.io.VpnPacketResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserspaceVpnRuntimeTest {

    @Test
    fun longestPrefixMatchRoutesTunPacketsToTheCorrectPeer() = runTest {
        val peerA = QueueVpnSession(
            peerPublicKey = "peer-a",
            encryptResults = ArrayDeque(listOf(VpnPacketResult.WriteToNetwork(byteArrayOf(1)))),
        )
        val peerB = QueueVpnSession(
            peerPublicKey = "peer-b",
            encryptResults = ArrayDeque(listOf(VpnPacketResult.WriteToNetwork(byteArrayOf(2)))),
        )
        val manager = FakeSessionManager(
            managed = listOf(
                managedSession(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/8"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                    session = peerA,
                ),
                managedSession(
                    publicKey = "peer-b",
                    allowedIps = listOf("10.1.2.0/24"),
                    endpointHost = "198.51.100.2",
                    endpointPort = 51821,
                    session = peerB,
                ),
            ),
        )
        val tunPort = InMemoryTunPort(
            incomingPackets = ArrayDeque(listOf(ipv4Packet(destination = byteArrayOf(10, 1, 2, 3)))),
        )
        val udpPort = InMemoryUdpPort()

        UserspaceVpnRuntime(
            sessionManager = manager,
            tunPort = tunPort,
            udpPort = udpPort,
        ).pollOnce()

        assertEquals(1, udpPort.sentDatagrams.size)
        assertEquals(UdpEndpoint("198.51.100.2", 51821), udpPort.sentDatagrams.single().endpoint)
        assertContentEquals(byteArrayOf(2), udpPort.sentDatagrams.single().packet)
        assertEquals(0, peerA.encryptInputs.size)
        assertEquals(1, peerB.encryptInputs.size)
    }

    @Test
    fun unknownDestinationPacketsAreDropped() = runTest {
        val manager = FakeSessionManager(
            managed = listOf(
                managedSession(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/24"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                    session = QueueVpnSession(peerPublicKey = "peer-a"),
                ),
            ),
        )
        val tunPort = InMemoryTunPort(
            incomingPackets = ArrayDeque(listOf(ipv4Packet(destination = byteArrayOf(172.toByte(), 16, 0, 1)))),
        )
        val udpPort = InMemoryUdpPort()

        UserspaceVpnRuntime(
            sessionManager = manager,
            tunPort = tunPort,
            udpPort = udpPort,
        ).pollOnce()

        assertTrue(udpPort.sentDatagrams.isEmpty())
    }

    @Test
    fun incomingDatagramsAreDemultiplexedByPeerEndpoint() = runTest {
        val peerA = QueueVpnSession(
            peerPublicKey = "peer-a",
            decryptResults = ArrayDeque(listOf(VpnPacketResult.Done)),
        )
        val peerB = QueueVpnSession(
            peerPublicKey = "peer-b",
            decryptResults = ArrayDeque(listOf(VpnPacketResult.WriteToTunnelIpv4(byteArrayOf(9, 9, 9)), VpnPacketResult.Done)),
        )
        val manager = FakeSessionManager(
            managed = listOf(
                managedSession(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/24"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                    session = peerA,
                ),
                managedSession(
                    publicKey = "peer-b",
                    allowedIps = listOf("10.1.0.0/24"),
                    endpointHost = "198.51.100.2",
                    endpointPort = 51821,
                    session = peerB,
                ),
            ),
        )
        val tunPort = InMemoryTunPort()
        val udpPort = InMemoryUdpPort(
            incomingDatagrams = ArrayDeque(
                listOf(
                    UdpDatagram(
                        packet = byteArrayOf(7, 8, 9),
                        endpoint = UdpEndpoint("198.51.100.2", 51821),
                    ),
                ),
            ),
        )

        val runtime = UserspaceVpnRuntime(
            sessionManager = manager,
            tunPort = tunPort,
            udpPort = udpPort,
        )

        runtime.pollOnce()

        assertEquals(0, peerA.decryptInputs.size)
        assertEquals(2, peerB.decryptInputs.size)
        assertContentEquals(byteArrayOf(7, 8, 9), peerB.decryptInputs.first())
        assertEquals(1, tunPort.writtenPackets.size)
        assertEquals(3L, runtime.peerStats().single { it.publicKey == "peer-b" }.receivedBytes)
    }

    @Test
    fun periodicTasksRunAcrossAllManagedSessions() = runTest {
        val peerA = QueueVpnSession(
            peerPublicKey = "peer-a",
            periodicResults = ArrayDeque(listOf(VpnPacketResult.WriteToNetwork(byteArrayOf(1)))),
        )
        val peerB = QueueVpnSession(
            peerPublicKey = "peer-b",
            periodicResults = ArrayDeque(listOf(VpnPacketResult.WriteToNetwork(byteArrayOf(2)))),
        )
        val manager = FakeSessionManager(
            managed = listOf(
                managedSession(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/24"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                    session = peerA,
                ),
                managedSession(
                    publicKey = "peer-b",
                    allowedIps = listOf("10.1.0.0/24"),
                    endpointHost = "198.51.100.2",
                    endpointPort = 51821,
                    session = peerB,
                ),
            ),
        )
        val udpPort = InMemoryUdpPort()
        val runtime = UserspaceVpnRuntime(
            sessionManager = manager,
            tunPort = InMemoryTunPort(),
            udpPort = udpPort,
            periodicTicker = ManualPeriodicTicker(values = ArrayDeque(listOf(true))),
        )

        runtime.pollOnce()

        assertEquals(1, peerA.periodicCalls)
        assertEquals(1, peerB.periodicCalls)
        assertEquals(2, udpPort.sentDatagrams.size)
        assertEquals(1L, runtime.peerStats().single { it.publicKey == "peer-a" }.transmittedBytes)
        assertEquals(1L, runtime.peerStats().single { it.publicKey == "peer-b" }.transmittedBytes)
    }

    private fun managedSession(
        publicKey: String,
        allowedIps: List<String>,
        endpointHost: String,
        endpointPort: Int,
        session: VpnSession,
    ): ManagedSession {
        return ManagedSession(
            peer = VpnPeer(
                publicKey = publicKey,
                allowedIps = allowedIps,
                endpointAddress = endpointHost,
                endpointPort = endpointPort,
            ),
            session = session,
        )
    }

    private fun ipv4Packet(destination: ByteArray): ByteArray {
        return byteArrayOf(
            0x45,
            0x00,
            0x00,
            0x14,
            0x00,
            0x00,
            0x00,
            0x00,
            0x40,
            0x11,
            0x00,
            0x00,
            10,
            0,
            0,
            1,
            destination[0],
            destination[1],
            destination[2],
            destination[3],
        )
    }

    private class FakeSessionManager(
        private val managed: List<ManagedSession>,
    ) : SessionManager {
        override fun reconcileSessions(config: com.rafambn.kmpvpn.VpnAdapterConfiguration) = Unit

        override fun sessions(): List<SessionSnapshot> = emptyList()

        override fun managedSessions(): List<ManagedSession> = managed

        override fun session(peerKey: String): SessionSnapshot? = null

        override fun closeSession(peerKey: String) = Unit

        override fun closeAll() = Unit
    }

    private class QueueVpnSession(
        override val peerPublicKey: String,
        private val encryptResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
        private val decryptResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
        private val periodicResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
    ) : VpnSession {
        override val sessionIndex: UInt = 1u
        override val isActive: Boolean = true

        val encryptInputs: MutableList<ByteArray> = mutableListOf()
        val decryptInputs: MutableList<ByteArray> = mutableListOf()
        var periodicCalls: Int = 0

        override fun encryptRawPacket(src: ByteArray, dstSize: UInt): VpnPacketResult {
            encryptInputs += src.copyOf()
            return encryptResults.removeFirstOrNull() ?: VpnPacketResult.Done
        }

        override fun decryptToRawPacket(src: ByteArray, dstSize: UInt): VpnPacketResult {
            decryptInputs += src.copyOf()
            return decryptResults.removeFirstOrNull() ?: VpnPacketResult.Done
        }

        override fun runPeriodicTask(dstSize: UInt): VpnPacketResult {
            periodicCalls += 1
            return periodicResults.removeFirstOrNull() ?: VpnPacketResult.Done
        }

        override fun close() = Unit
    }
}
