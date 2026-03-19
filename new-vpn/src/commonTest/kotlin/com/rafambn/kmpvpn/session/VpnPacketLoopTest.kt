package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.session.io.TunPort
import com.rafambn.kmpvpn.session.io.UdpPort
import com.rafambn.kmpvpn.session.io.VpnPacketLoop
import com.rafambn.kmpvpn.session.io.VpnPacketResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VpnPacketLoopTest {

    @Test
    fun pollOnceRoutesTunPacketsToUdp() {
        val session = QueueVpnSession(
            encryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToNetwork(byteArrayOf(9, 8, 7)),
                ),
            ),
        )
        val tunPort = FakeTunPort(incomingPackets = ArrayDeque(listOf(byteArrayOf(1, 2, 3))))
        val udpPort = FakeUdpPort()

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
        )

        loop.pollOnce()

        assertEquals(1, udpPort.sentPackets.size)
        assertContentEquals(byteArrayOf(9, 8, 7), udpPort.sentPackets.single())
        assertEquals(1, session.encryptInputs.size)
        assertContentEquals(byteArrayOf(1, 2, 3), session.encryptInputs.single())
    }

    @Test
    fun pollOnceRoutesNetworkPacketsToTunAndFlushesUntilDone() {
        val session = QueueVpnSession(
            decryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToTunnelIpv4(byteArrayOf(4, 5, 6)),
                    VpnPacketResult.Done,
                ),
            ),
        )
        val tunPort = FakeTunPort()
        val udpPort = FakeUdpPort(incomingPackets = ArrayDeque(listOf(byteArrayOf(7, 8, 9))))

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
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
    fun pollOnceRunsPeriodicTaskWhenTickerSignals() {
        val session = QueueVpnSession(
            periodicResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToNetwork(byteArrayOf(42)),
                ),
            ),
        )
        val tunPort = FakeTunPort()
        val udpPort = FakeUdpPort()
        val tickerValues = ArrayDeque(listOf(true, false))

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
            periodicTicker = { tickerValues.removeFirstOrNull() ?: false },
        )

        loop.pollOnce()

        assertEquals(1, session.periodicCalls)
        assertEquals(1, udpPort.sentPackets.size)
        assertContentEquals(byteArrayOf(42), udpPort.sentPackets.single())
    }

    @Test
    fun pollOnceThrowsOnSessionError() {
        val session = QueueVpnSession(
            encryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.Error(99u),
                ),
            ),
        )
        val tunPort = FakeTunPort(incomingPackets = ArrayDeque(listOf(byteArrayOf(1))))
        val udpPort = FakeUdpPort()

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
        )

        val throwable = assertFailsWith<IllegalStateException> {
            loop.pollOnce()
        }

        assertEquals(true, throwable.message?.contains("error code `99`") == true)
    }

    @Test
    fun pollOnceThrowsWhenDecryptFlushNeverFinishes() {
        val session = QueueVpnSession(
            decryptResults = ArrayDeque(
                listOf(
                    VpnPacketResult.WriteToNetwork(byteArrayOf(1)),
                    VpnPacketResult.WriteToNetwork(byteArrayOf(2)),
                    VpnPacketResult.WriteToNetwork(byteArrayOf(3)),
                ),
            ),
        )
        val tunPort = FakeTunPort()
        val udpPort = FakeUdpPort(incomingPackets = ArrayDeque(listOf(byteArrayOf(9))))

        val loop = VpnPacketLoop(
            session = session,
            tunPort = tunPort,
            udpPort = udpPort,
            maxFlushIterations = 2,
        )

        assertFailsWith<IllegalStateException> {
            loop.pollOnce()
        }

        assertEquals(2, udpPort.sentPackets.size)
        assertContentEquals(byteArrayOf(1), udpPort.sentPackets[0])
        assertContentEquals(byteArrayOf(2), udpPort.sentPackets[1])
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

    private class FakeTunPort(
        private val incomingPackets: ArrayDeque<ByteArray> = ArrayDeque(),
    ) : TunPort {
        val writtenPackets: MutableList<ByteArray> = mutableListOf()

        override fun readPacket(): ByteArray? {
            return incomingPackets.removeFirstOrNull()
        }

        override fun writePacket(packet: ByteArray) {
            writtenPackets += packet.copyOf()
        }
    }

    private class FakeUdpPort(
        private val incomingPackets: ArrayDeque<ByteArray> = ArrayDeque(),
    ) : UdpPort {
        val sentPackets: MutableList<ByteArray> = mutableListOf()

        override fun receivePacket(): ByteArray? {
            return incomingPackets.removeFirstOrNull()
        }

        override fun sendPacket(packet: ByteArray) {
            sentPackets += packet.copyOf()
        }
    }
}
