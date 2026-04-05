package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.session.io.InMemoryTunPort
import com.rafambn.kmpvpn.session.io.InMemoryUdpPort
import com.rafambn.kmpvpn.session.io.ManualPeriodicTicker
import com.rafambn.kmpvpn.session.io.UdpDatagram
import com.rafambn.kmpvpn.session.io.UdpEndpoint
import com.rafambn.kmpvpn.session.io.VpnPacketLoop
import com.rafambn.kmpvpn.session.io.VpnPacketResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VpnPacketLoopTest {
    private val peerEndpoint = UdpEndpoint(host = "198.51.100.10", port = 51820)

    @Test
    fun pollOnceRoutesTunPacketsToUdp() = runTest {
        val session = QueueVpnSession(
            encryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToNetwork(byteArrayOf(9, 8, 7)),
                ),
            ),
        )
        val tunPort = InMemoryTunPort(incomingPackets = ArrayDeque(listOf(byteArrayOf(1, 2, 3))))
        val udpPort = InMemoryUdpPort()

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
            peerEndpoint = peerEndpoint,
        )

        loop.pollOnce()

        assertEquals(1, udpPort.sentDatagrams.size)
        assertContentEquals(byteArrayOf(9, 8, 7), udpPort.sentDatagrams.single().packet)
        assertEquals(peerEndpoint, udpPort.sentDatagrams.single().endpoint)
        assertEquals(1, session.encryptInputs.size)
        assertContentEquals(byteArrayOf(1, 2, 3), session.encryptInputs.single())
    }

    @Test
    fun pollOnceRoutesNetworkPacketsToTunAndFlushesUntilDone() = runTest {
        val session = QueueVpnSession(
            decryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToTunnelIpv4(byteArrayOf(4, 5, 6)),
                    VpnPacketResult.Done,
                ),
            ),
        )
        val tunPort = InMemoryTunPort()
        val udpPort = InMemoryUdpPort(
            incomingDatagrams = ArrayDeque(
                listOf(
                    UdpDatagram(
                        packet = byteArrayOf(7, 8, 9),
                        endpoint = peerEndpoint,
                    ),
                ),
            ),
        )

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
            peerEndpoint = peerEndpoint,
            maxFlushIterations = 4,
        )

        loop.pollOnce()

        assertEquals(1, tunPort.writtenPackets.size)
        assertContentEquals(byteArrayOf(4, 5, 6), tunPort.writtenPackets.single())
        assertEquals(2, session.decryptInputs.size)
        assertContentEquals(byteArrayOf(7, 8, 9), session.decryptInputs[0])
        assertContentEquals(byteArrayOf(), session.decryptInputs[1])
    }

    @Test
    fun pollOnceRunsPeriodicTaskWhenTickerSignals() = runTest {
        val session = QueueVpnSession(
            periodicResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToNetwork(byteArrayOf(42)),
                ),
            ),
        )
        val tunPort = InMemoryTunPort()
        val udpPort = InMemoryUdpPort()
        val ticker = ManualPeriodicTicker(values = ArrayDeque(listOf(true, false)))

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
            peerEndpoint = peerEndpoint,
            periodicTicker = ticker,
        )

        loop.pollOnce()

        assertEquals(1, session.periodicCalls)
        assertEquals(1, udpPort.sentDatagrams.size)
        assertContentEquals(byteArrayOf(42), udpPort.sentDatagrams.single().packet)
    }

    @Test
    fun pollOnceThrowsOnSessionError() = runTest {
        val session = QueueVpnSession(
            encryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.Error(99u),
                ),
            ),
        )
        val tunPort = InMemoryTunPort(incomingPackets = ArrayDeque(listOf(byteArrayOf(1))))
        val udpPort = InMemoryUdpPort()

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
            peerEndpoint = peerEndpoint,
        )

        val throwable = assertFailsWith<IllegalStateException> {
            loop.pollOnce()
        }

        assertEquals(true, throwable.message?.contains("error code `99`") == true)
    }

    @Test
    fun pollOnceThrowsWhenDecryptFlushNeverFinishes() = runTest {
        val session = QueueVpnSession(
            decryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToNetwork(byteArrayOf(1)),
                    VpnPacketResult.WriteToNetwork(byteArrayOf(2)),
                    VpnPacketResult.WriteToNetwork(byteArrayOf(3)),
                ),
            ),
        )
        val tunPort = InMemoryTunPort()
        val udpPort = InMemoryUdpPort(
            incomingDatagrams = ArrayDeque(
                listOf(
                    UdpDatagram(
                        packet = byteArrayOf(9),
                        endpoint = peerEndpoint,
                    ),
                ),
            ),
        )

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
            peerEndpoint = peerEndpoint,
            maxFlushIterations = 2,
        )

        assertFailsWith<IllegalStateException> {
            loop.pollOnce()
        }

        assertEquals(2, udpPort.sentDatagrams.size)
        assertContentEquals(byteArrayOf(1), udpPort.sentDatagrams[0].packet)
        assertContentEquals(byteArrayOf(2), udpPort.sentDatagrams[1].packet)
    }

    private class QueueVpnSession(
        private val encryptResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
        private val decryptResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
        private val periodicResults: ArrayDeque<VpnPacketResult> = ArrayDeque(),
    ) : VpnSession {
        override val peerPublicKey: String = "peer-key"
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

        override fun close() {
            // no-op for tests
        }
    }
}
