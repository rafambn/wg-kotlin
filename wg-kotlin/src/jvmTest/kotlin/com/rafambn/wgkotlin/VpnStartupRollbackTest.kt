package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.IpcTestFixture
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class VpnStartupRollbackTest {
    @Test
    fun failedUdpBindRetiresTheExecutionAndAllowsAFreshOpening() = runBlocking {
        IpcTestFixture("echo").use {
            val vpn = Vpn("utun131")
            val selector = SelectorManager(Dispatchers.IO)
            val ipv6 = runCatching { aSocket(selector).udp().bind(InetSocketAddress("::", 0)) }.getOrNull()
            val ipv4 = runCatching {
                val port = (ipv6?.localAddress as InetSocketAddress?)?.port ?: 0
                aSocket(selector).udp().bind(InetSocketAddress("0.0.0.0", port))
            }.getOrNull()
            val port = ((ipv6 ?: checkNotNull(ipv4)).localAddress as InetSocketAddress).port
            try {
                val error = assertFailsWith<IllegalStateException> { vpn.open(configuration(port)) }
                assertTrue(generateSequence<Throwable>(error) { it.cause }
                    .any { it.message?.contains("Failed to bind UDP socket") == true })
                assertStopped(vpn)
                vpn.open(configuration(0))
                assertTrue(vpn.isRunning())
            } finally {
                vpn.stop()
                ipv4?.close()
                ipv6?.close()
                selector.close()
            }
        }
    }

    @Test
    fun failedPeerCreationAlsoRetiresTheExecution() {
        val vpn = Vpn("utun131", Engine.QUIC)
        val error = assertFailsWith<IllegalStateException> { vpn.open(configuration(0)) }
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is UnsupportedOperationException })
        assertStopped(vpn)
    }

    @Test
    fun rejectedInterfacePreservesTheDaemonCauseAndRetiresTheExecution() {
        IpcTestFixture().use {
            val vpn = Vpn("utun131")
            val error = assertFailsWith<IllegalStateException> {
                vpn.open(configuration(0).copy(addresses = mutableListOf()))
            }
            assertTrue(generateSequence<Throwable>(error) { it.cause }
                .any { it.message?.contains("addresses must contain") == true })
            assertStopped(vpn)
        }
    }

    private fun assertStopped(vpn: Vpn) {
        assertFalse(vpn.isRunning())
        assertNull(vpn.information())
        val field = Vpn::class.java.getDeclaredField("execution").apply { isAccessible = true }
        assertNull((field.get(vpn) as AtomicReference<*>).load(), "Failed opening must release its execution")
    }

    private fun configuration(port: Int) = VpnConfiguration(
        interfaceName = "utun131", listenPort = port,
        addresses = mutableListOf("10.0.0.1/32"),
        privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
        peers = listOf(VpnPeer(publicKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I=",
            endpointAddress = "198.51.100.1", endpointPort = 51820)),
    )
}
