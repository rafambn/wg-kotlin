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
import java.net.BindException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SocketSessionTest {

    @Test
    fun closingTwiceReleasesTheBoundPort() = runBlocking {
        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val session = SocketManager.create(0, networkPipe.first)
        val selector = SelectorManager(Dispatchers.IO)
        val receiver = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
        try {
            val endpoint = UdpEndpoint("127.0.0.1", (receiver.localAddress as InetSocketAddress).port)
            networkPipe.second.send(UdpDatagram(byteArrayOf(1), endpoint))
            val received = withTimeout(2_000) { receiver.receive() }
            assertContentEquals(byteArrayOf(1), received.packet.readByteArray())

            session.close()
            session.close()

            withTimeout(2_000) {
                while (true) {
                    try {
                        val rebound = aSocket(selector).udp().bind(received.address)
                        rebound.close()
                        break
                    } catch (_: BindException) {
                        delay(10)
                    }
                }
            }
        } finally {
            session.close()
            receiver.close()
            selector.close()
        }
    }

    @Test
    fun failedCreateOnOccupiedPortPreservesTheExistingSession() = runBlocking {
        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        SocketManager.create(0, networkPipe.first).use {
            val selector = SelectorManager(Dispatchers.IO)
            val receiver = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
            try {
                val endpoint = UdpEndpoint("127.0.0.1", (receiver.localAddress as InetSocketAddress).port)
                networkPipe.second.send(UdpDatagram(byteArrayOf(1), endpoint))
                val received = withTimeout(2_000) { receiver.receive() }
                assertContentEquals(byteArrayOf(1), received.packet.readByteArray())
                val occupiedPort = (received.address as InetSocketAddress).port

                assertFailsWith<IllegalStateException> {
                    SocketManager.create(occupiedPort, DuplexChannelPipe.create<UdpDatagram>().first).close()
                }

                networkPipe.second.send(UdpDatagram(byteArrayOf(2), endpoint))
                val afterFailure = withTimeout(2_000) { receiver.receive() }
                assertContentEquals(byteArrayOf(2), afterFailure.packet.readByteArray())
            } finally {
                receiver.close()
                selector.close()
            }
        }
    }

    @Test
    fun closingOneSessionPreservesAnotherSessionsTraffic() = runBlocking {
        val firstPipe = DuplexChannelPipe.create<UdpDatagram>()
        val secondPipe = DuplexChannelPipe.create<UdpDatagram>()
        SocketManager.create(0, firstPipe.first).use { first ->
            SocketManager.create(0, secondPipe.first).use {
                val selector = SelectorManager(Dispatchers.IO)
                val receiver = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
                try {
                    val endpoint = UdpEndpoint("127.0.0.1", (receiver.localAddress as InetSocketAddress).port)
                    firstPipe.second.send(UdpDatagram(byteArrayOf(1), endpoint))
                    val firstDatagram = withTimeout(2_000) { receiver.receive() }
                    assertContentEquals(byteArrayOf(1), firstDatagram.packet.readByteArray())
                    secondPipe.second.send(UdpDatagram(byteArrayOf(2), endpoint))
                    val secondDatagram = withTimeout(2_000) { receiver.receive() }
                    assertNotEquals(firstDatagram.address, secondDatagram.address)
                    assertContentEquals(byteArrayOf(2), secondDatagram.packet.readByteArray())

                    first.close()
                    secondPipe.second.send(UdpDatagram(byteArrayOf(3), endpoint))
                    val afterClose = withTimeout(2_000) { receiver.receive() }
                    assertContentEquals(byteArrayOf(3), afterClose.packet.readByteArray())
                    receiver.send(Datagram(buildPacket { writeFully(byteArrayOf(4)) }, afterClose.address))
                    val inbound = withTimeout(2_000) { secondPipe.second.receive() }
                    assertContentEquals(byteArrayOf(4), inbound.payload)
                } finally {
                    receiver.close()
                    selector.close()
                }
            }
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
        val session = SocketManager.create(networkPipe = networkPipe.first, listenPort = 0, onFailure = { throwable -> throw AssertionError("Unexpected failure", throwable) })

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
            session.close()
            selector.close()
        }
    }

    @Test
    fun sendLoopTransmitsOutboundDatagramFromPipe() = runBlocking {
        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val session = SocketManager.create(networkPipe = networkPipe.first, listenPort = 0, onFailure = { throwable -> throw AssertionError("Unexpected failure", throwable) })

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
            session.close()
        }
    }

    @Test
    fun receiveLoopDeliversInboundIpv6DatagramToPipeWhenIpv6Available() = runBlocking {
        if (!isIpv6LoopbackAvailable()) {
            return@runBlocking
        }

        val selector = SelectorManager(Dispatchers.IO)
        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val session = SocketManager.create(networkPipe = networkPipe.first, listenPort = 0, onFailure = { throwable -> throw AssertionError("Unexpected failure", throwable) })

        try {
            val probeReceiver = aSocket(selector).udp().bind(InetSocketAddress("::1", 0))
            val probePort = (probeReceiver.localAddress as InetSocketAddress).port
            networkPipe.second.send(
                UdpDatagram(
                    payload = byteArrayOf(0),
                    remoteEndpoint = UdpEndpoint(address = "::1", port = probePort),
                ),
            )
            val probeResult = withTimeout(2_000) { probeReceiver.receive() }
            val managerPort = (probeResult.address as InetSocketAddress).port
            probeReceiver.close()

            val sender = aSocket(selector).udp().bind(InetSocketAddress("::1", 0))
            try {
                val payload = byteArrayOf(0x21, 0x22, 0x23)
                sender.send(Datagram(buildPacket { writeFully(payload) }, InetSocketAddress("::1", managerPort)))

                val datagram = withTimeout(2_000) { networkPipe.second.receive() }
                assertTrue(datagram.payload.contentEquals(payload))
            } finally {
                sender.close()
            }
        } finally {
            session.close()
            selector.close()
        }
    }

    @Test
    fun sendLoopTransmitsOutboundIpv6DatagramFromPipeWhenIpv6Available() = runBlocking {
        if (!isIpv6LoopbackAvailable()) {
            return@runBlocking
        }

        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        val session = SocketManager.create(networkPipe = networkPipe.first, listenPort = 0, onFailure = { throwable -> throw AssertionError("Unexpected failure", throwable) })

        try {
            val selector = SelectorManager(Dispatchers.IO)
            val receiver = aSocket(selector).udp().bind(InetSocketAddress("::1", 0))
            val receiverPort = (receiver.localAddress as InetSocketAddress).port

            try {
                val payload = byteArrayOf(0x31, 0x32)
                networkPipe.second.send(
                    UdpDatagram(
                        payload = payload,
                        remoteEndpoint = UdpEndpoint(address = "::1", port = receiverPort),
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
            session.close()
        }
    }

    @Test
    fun newSessionOnAnotherPortStillProcessesPackets() = runBlocking {
        val selector = SelectorManager(Dispatchers.IO)

        val networkPipe = DuplexChannelPipe.create<UdpDatagram>()
        var session = SocketManager.create(networkPipe = networkPipe.first, listenPort = 0, onFailure = {})
        session.close()

        // Pick a fresh OS-assigned port for the second start.
        val freshReceiver = aSocket(selector).udp().bind(InetSocketAddress("127.0.0.1", 0))
        val newPort = (freshReceiver.localAddress as InetSocketAddress).port
        freshReceiver.close()

        session = SocketManager.create(newPort, networkPipe.first)
        try {

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
            session.close()
            selector.close()
        }
    }

    private suspend fun isIpv6LoopbackAvailable(): Boolean {
        val selector = SelectorManager(Dispatchers.IO)
        return try {
            val socket = aSocket(selector).udp().bind(InetSocketAddress("::1", 0))
            socket.close()
            true
        } catch (_: Throwable) {
            false
        } finally {
            selector.close()
        }
    }
}
