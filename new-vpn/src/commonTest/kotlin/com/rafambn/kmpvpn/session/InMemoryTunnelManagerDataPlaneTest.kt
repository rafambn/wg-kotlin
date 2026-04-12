package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.VpnPeer
import com.rafambn.kmpvpn.session.factory.VpnSessionFactory
import com.rafambn.kmpvpn.session.io.InMemoryTunnelPacketPort
import com.rafambn.kmpvpn.session.io.InMemoryUdpPort
import com.rafambn.kmpvpn.session.io.ManualPeriodicTicker
import com.rafambn.kmpvpn.session.io.TunnelPacketPort
import com.rafambn.kmpvpn.session.io.UdpDatagram
import com.rafambn.kmpvpn.session.io.UdpEndpoint
import com.rafambn.kmpvpn.session.io.VpnPacketResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InMemoryTunnelManagerDataPlaneTest {

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
        val tunnelPacketPort = InMemoryTunnelPacketPort(
            incomingPackets = ArrayDeque(listOf(ipv4Packet(destination = byteArrayOf(10, 1, 2, 3)))),
        )
        val manager = manager(peerA, peerB, tunnelPacketPort = tunnelPacketPort)
        val configuration = configuration(
            peers = listOf(
                peer(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/8"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                ),
                peer(
                    publicKey = "peer-b",
                    allowedIps = listOf("10.1.2.0/24"),
                    endpointHost = "198.51.100.2",
                    endpointPort = 51821,
                ),
            ),
        )
        val udpPort = InMemoryUdpPort()

        manager.reconcileSessions(configuration)
        manager.startRuntime(configuration)
        manager.pollRuntimeOnce(udpPort) { false }

        assertEquals(1, udpPort.sentDatagrams.size)
        assertEquals(UdpEndpoint("198.51.100.2", 51821), udpPort.sentDatagrams.single().endpoint)
        assertContentEquals(byteArrayOf(2), udpPort.sentDatagrams.single().packet)
        assertEquals(0, peerA.encryptInputs.size)
        assertEquals(1, peerB.encryptInputs.size)
    }

    @Test
    fun unknownDestinationPacketsAreDropped() = runTest {
        val tunnelPacketPort = InMemoryTunnelPacketPort(
            incomingPackets = ArrayDeque(listOf(ipv4Packet(destination = byteArrayOf(172.toByte(), 16, 0, 1)))),
        )
        val manager = manager(
            QueueVpnSession(peerPublicKey = "peer-a"),
            tunnelPacketPort = tunnelPacketPort,
        )
        val configuration = configuration(
            peers = listOf(
                peer(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/24"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                ),
            ),
        )
        val udpPort = InMemoryUdpPort()

        manager.reconcileSessions(configuration)
        manager.startRuntime(configuration)
        manager.pollRuntimeOnce(udpPort) { false }

        assertTrue(udpPort.sentDatagrams.isEmpty())
        assertEquals(0L, manager.peerStats().single().transmittedBytes)
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
        val tunnelPacketPort = InMemoryTunnelPacketPort()
        val manager = manager(peerA, peerB, tunnelPacketPort = tunnelPacketPort)
        val configuration = configuration(
            peers = listOf(
                peer(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/24"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                ),
                peer(
                    publicKey = "peer-b",
                    allowedIps = listOf("10.1.0.0/24"),
                    endpointHost = "198.51.100.2",
                    endpointPort = 51821,
                ),
            ),
        )
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

        manager.reconcileSessions(configuration)
        manager.startRuntime(configuration)
        manager.pollRuntimeOnce(udpPort) { false }

        assertEquals(0, peerA.decryptInputs.size)
        assertEquals(2, peerB.decryptInputs.size)
        assertContentEquals(byteArrayOf(7, 8, 9), peerB.decryptInputs.first())
        assertEquals(1, tunnelPacketPort.writtenPackets.size)
        assertEquals(3L, manager.peerStats().single { it.publicKey == "peer-b" }.receivedBytes)
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
        val manager = manager(peerA, peerB)
        val configuration = configuration(
            peers = listOf(
                peer(
                    publicKey = "peer-a",
                    allowedIps = listOf("10.0.0.0/24"),
                    endpointHost = "198.51.100.1",
                    endpointPort = 51820,
                ),
                peer(
                    publicKey = "peer-b",
                    allowedIps = listOf("10.1.0.0/24"),
                    endpointHost = "198.51.100.2",
                    endpointPort = 51821,
                ),
            ),
        )
        val udpPort = InMemoryUdpPort()

        manager.reconcileSessions(configuration)
        manager.startRuntime(configuration)
        manager.pollRuntimeOnce(
            udpPort = udpPort,
            periodicTicker = ManualPeriodicTicker(values = ArrayDeque(listOf(true))),
        )

        assertEquals(1, peerA.periodicCalls)
        assertEquals(1, peerB.periodicCalls)
        assertEquals(2, udpPort.sentDatagrams.size)
        assertEquals(1L, manager.peerStats().single { it.publicKey == "peer-a" }.transmittedBytes)
        assertEquals(1L, manager.peerStats().single { it.publicKey == "peer-b" }.transmittedBytes)
    }

    private fun manager(
        vararg sessions: QueueVpnSession,
        tunnelPacketPort: TunnelPacketPort = InMemoryTunnelPacketPort(),
    ): InMemoryTunnelManager {
        return InMemoryTunnelManager(
            sessionFactory = RecordingSessionFactory(sessions.associateBy { session -> session.peerPublicKey }),
            userspaceRuntimeFactory = { _, _, _, _, _ -> null },
            tunnelPacketPortProvider = { tunnelPacketPort },
        )
    }

    private fun configuration(
        peers: List<VpnPeer>,
        listenPort: Int = 51820,
    ): VpnConfiguration {
        return VpnConfiguration(
            interfaceName = "wg-test",
            listenPort = listenPort,
            privateKey = "private-key",
            peers = peers,
        )
    }

    private fun peer(
        publicKey: String,
        allowedIps: List<String>,
        endpointHost: String,
        endpointPort: Int,
    ): VpnPeer {
        return VpnPeer(
            publicKey = publicKey,
            allowedIps = allowedIps,
            endpointAddress = endpointHost,
            endpointPort = endpointPort,
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

    private class RecordingSessionFactory(
        private val sessionsByKey: Map<String, QueueVpnSession>,
    ) : VpnSessionFactory {
        override fun create(
            config: VpnConfiguration,
            peer: VpnPeer,
            sessionIndex: UInt,
        ): VpnSession {
            val session = checkNotNull(sessionsByKey[peer.publicKey]) {
                "Missing test session for `${peer.publicKey}`"
            }
            session.sessionIndex = sessionIndex
            return session
        }
    }

    private class QueueVpnSession(
        override val peerPublicKey: String,
        private val encryptResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
        private val decryptResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
        private val periodicResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
    ) : VpnSession {
        override var sessionIndex: UInt = 0u
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
