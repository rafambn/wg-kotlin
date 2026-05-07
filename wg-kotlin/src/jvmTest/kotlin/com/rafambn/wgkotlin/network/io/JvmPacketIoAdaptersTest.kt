package com.rafambn.wgkotlin.network.io

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JvmPacketIoAdaptersTest {

    @Test
    fun ktorDatagramUdpPortRoutesPacketsOverSocket() = runTest {
        val selectorManager = SelectorManager(Dispatchers.IO)
        val socketA = aSocket(selectorManager).udp().bind(InetSocketAddress("127.0.0.1", 0))
        val socketB = aSocket(selectorManager).udp().bind(InetSocketAddress("127.0.0.1", 0))

        try {
            val portA = KtorDatagramUdpPort(
                socket = socketA,
                receiveTimeoutMillis = 1_000L,
            )
            val portB = KtorDatagramUdpPort(
                socket = socketB,
                receiveTimeoutMillis = 1_000L,
            )

            portA.sendDatagram(
                UdpDatagram(
                    payload = byteArrayOf(7, 8, 9),
                    remoteEndpoint = UdpEndpoint(address = "127.0.0.1", port = (socketB.localAddress as InetSocketAddress).port),
                ),
            )
            val received = portB.receiveDatagram()

            assertNotNull(received)
            assertContentEquals(byteArrayOf(7, 8, 9), received.payload)
            assertEquals((socketA.localAddress as InetSocketAddress).port, received.remoteEndpoint.port)
        } finally {
            socketA.close()
            socketB.close()
            selectorManager.close()
        }
    }

    @Test
    fun ktorDatagramUdpPortRoutesIpv6PacketsOverSocketWhenAvailable() = runTest {
        if (!isIpv6LoopbackAvailable()) {
            return@runTest
        }

        val selectorManager = SelectorManager(Dispatchers.IO)
        val socketA = aSocket(selectorManager).udp().bind(InetSocketAddress("::1", 0))
        val socketB = aSocket(selectorManager).udp().bind(InetSocketAddress("::1", 0))

        try {
            val portA = KtorDatagramUdpPort(
                socket = socketA,
                receiveTimeoutMillis = 1_000L,
            )
            val portB = KtorDatagramUdpPort(
                socket = socketB,
                receiveTimeoutMillis = 1_000L,
            )

            portA.sendDatagram(
                UdpDatagram(
                    payload = byteArrayOf(3, 4, 5),
                    remoteEndpoint = UdpEndpoint(address = "::1", port = (socketB.localAddress as InetSocketAddress).port),
                ),
            )
            val received = portB.receiveDatagram()

            assertNotNull(received)
            assertContentEquals(byteArrayOf(3, 4, 5), received.payload)
            assertEquals((socketA.localAddress as InetSocketAddress).port, received.remoteEndpoint.port)
        } finally {
            socketA.close()
            socketB.close()
            selectorManager.close()
        }
    }

    private suspend fun isIpv6LoopbackAvailable(): Boolean {
        val selectorManager = SelectorManager(Dispatchers.IO)
        return try {
            val socket = aSocket(selectorManager).udp().bind(InetSocketAddress("::1", 0))
            socket.close()
            true
        } catch (_: Throwable) {
            false
        } finally {
            selectorManager.close()
        }
    }
}
