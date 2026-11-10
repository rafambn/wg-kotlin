package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.IpcTestFixture
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalAtomicApi::class)
class VpnExecutionIsolationTest {
    @Test
    fun reopeningKeepsAllOldQueuesSeparateFromTheNewConsumers() = runBlocking {
        IpcTestFixture("echo").use {
            val vpn = Vpn("utun130")
            fun configuration(address: String) = VpnConfiguration(
                interfaceName = "utun130", listenPort = 0,
                addresses = mutableListOf(address),
                privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
                peers = listOf(VpnPeer(publicKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I=",
                    endpointAddress = "198.51.100.1", endpointPort = 51820)),
            )
            try {
                vpn.open(configuration("10.0.0.1/32"))
                val first = execution(vpn)
                vpn.stop()
                // Simulate both backlog and late producers from the stopped execution.
                val oldTun = byteArrayOf(1, 2, 3)
                val oldDatagram = UdpDatagram(byteArrayOf(4, 5), UdpEndpoint("192.0.2.1", 51820))
                first.tunPipePair.first.send(oldTun)
                first.tunPipePair.second.send(oldTun)
                first.networkPipePair.first.send(oldDatagram)
                first.networkPipePair.second.send(oldDatagram)

                vpn.open(configuration("10.0.1.1/32"))
                val second = execution(vpn)
                assertNotSame(first, second)
                assertNotSame(first.cryptoSessionManager, second.cryptoSessionManager)
                assertNotSame(first.socketManager, second.socketManager)
                assertNotSame(first.interfaceManager, second.interfaceManager)
                assertTrue(vpn.isRunning())
                // New consumers must leave every old queued item untouched.
                assertContentEquals(oldTun, withTimeout(2_000) { first.tunPipePair.first.receive() })
                assertContentEquals(oldTun, withTimeout(2_000) { first.tunPipePair.second.receive() })
                assertEquals(oldDatagram, withTimeout(2_000) { first.networkPipePair.first.receive() })
                assertEquals(oldDatagram, withTimeout(2_000) { first.networkPipePair.second.receive() })
                assertEquals(listOf("10.0.1.1/32"), vpn.information()!!.vpnConfiguration!!.addresses)
            } finally {
                vpn.stop()
            }
        }
    }

    private fun execution(vpn: Vpn): VpnExecution {
        val field = Vpn::class.java.getDeclaredField("execution").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(vpn) as AtomicReference<VpnExecution?>).load()!!
    }
}
