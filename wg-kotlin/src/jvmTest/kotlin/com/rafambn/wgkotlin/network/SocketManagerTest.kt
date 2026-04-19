package com.rafambn.wgkotlin.network

import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocketManagerTest {

    @Test
    fun isRunningFalseBeforeStart() {
        val manager = SocketManagerImpl(DuplexChannelPipe.create<UdpDatagram>().first)

        assertFalse(manager.isRunning())
    }

    @Test
    fun stopIsIdempotentWhenNotRunning() {
        val manager = SocketManagerImpl(DuplexChannelPipe.create<UdpDatagram>().first)

        manager.stop()
        manager.stop()

        assertFalse(manager.isRunning())
    }

    @Test
    fun startAndStopTransitionsRunningState() {
        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val manager = SocketManagerImpl(networkPipe.first)

        manager.start(listenPort = 0, onFailure = { throwable ->
            throw AssertionError("Unexpected socket failure", throwable)
        })

        try {
            assertTrue(manager.isRunning())
        } finally {
            manager.stop()
        }

        assertFalse(manager.isRunning())
    }

    @Test
    fun startAfterStopRebindsSocket() {
        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val manager = SocketManagerImpl(networkPipe.first)

        manager.start(listenPort = 0, onFailure = {})
        manager.stop()
        manager.start(listenPort = 0, onFailure = {})

        try {
            assertTrue(manager.isRunning())
        } finally {
            manager.stop()
        }
    }

    @Test
    fun receiveLoopDeliversInboundDatagramToPipe() = runBlocking {
        // Start the manager on an OS-assigned port (0), then discover the actual bound
        // port by sending a probe through the manager's own send path and reading the
        // source address from the received datagram. This avoids the TOCTOU race of
        // binding, closing, and re-binding to a port that another process may have claimed.
        val selector = SelectorManager(Dispatchers.IO)

        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val manager = SocketManagerImpl(networkPipe.first)
        manager.start(listenPort = 0, onFailure = { throwable -> throw AssertionError("Unexpected failure", throwable) })

        try {
            // Create a probe receiver socket and discover the manager's OS-assigned port.
            val probeReceiver = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
            val probePort = (probeReceiver.localAddress as InetSocketAddress).port
            networkPipe.second.send(
                UdpDatagram(
                    payload = byteArrayOf(0),
                    remoteEndpoint = UdpEndpoint(address = "127.0.0.1", port = probePort),
                ),
            )
            val probeResult = withTimeout(2_000) { probeReceiver.receive() }
            val managerPort = (probeResult.address as InetSocketAddress).port
            probeReceiver.close()

            // Now send the real payload to the manager's confirmed port.
            val sender = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
            try {
                val payload = byteArrayOf(0x01, 0x02, 0x03)
                sender.send(Datagram(buildPacket { writeFully(payload) }, InetSocketAddress("127.0.0.1", managerPort)))

                val datagram = withTimeout(2_000) { networkPipe.second.receive() }
                assertTrue(datagram.payload.contentEquals(payload))
            } finally {
                sender.close()
            }
        } finally {
            manager.stop()
            selector.close()
        }
    }

    @Test
    fun sendLoopTransmitsOutboundDatagramFromPipe() = runBlocking {
        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val manager = SocketManagerImpl(networkPipe.first)
        manager.start(listenPort = 0, onFailure = { throwable -> throw AssertionError("Unexpected failure", throwable) })

        try {
            val selector = SelectorManager(Dispatchers.IO)
            val receiver = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
            val receiverPort = (receiver.localAddress as InetSocketAddress).port

            try {
                val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
                networkPipe.second.send(
                    UdpDatagram(
                        payload = payload,
                        remoteEndpoint = UdpEndpoint(address = "127.0.0.1", port = receiverPort),
                    ),
                )

                val received = withTimeout(2_000) { receiver.receive() }
                val receivedBytes = received.packet.readByteArray()
                assertTrue(receivedBytes.contentEquals(payload))
            } finally {
                receiver.close()
                selector.close()
            }
        } finally {
            manager.stop()
        }
    }

    @Test
    fun restartOnListenPortChangeRebindsAndStillProcessesPackets() = runBlocking {
        val selector = SelectorManager(Dispatchers.IO)

        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val manager = SocketManagerImpl(networkPipe.first)
        manager.start(listenPort = 0, onFailure = {})
        assertTrue(manager.isRunning())
        manager.stop()
        assertFalse(manager.isRunning())

        // Pick a fresh OS-assigned port for the second start.
        val freshReceiver = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
        val newPort = (freshReceiver.localAddress as InetSocketAddress).port
        freshReceiver.close()

        manager.start(listenPort = newPort, onFailure = {})
        try {
            assertTrue(manager.isRunning())

            val sender = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
            try {
                val payload = byteArrayOf(0x55, 0x66)
                sender.send(Datagram(buildPacket { writeFully(payload) }, InetSocketAddress("127.0.0.1", newPort)))

                val datagram = withTimeout(2_000) { networkPipe.second.receive() }
                assertTrue(datagram.payload.contentEquals(payload))
            } finally {
                sender.close()
            }
        } finally {
            manager.stop()
            selector.close()
        }
    }
}
