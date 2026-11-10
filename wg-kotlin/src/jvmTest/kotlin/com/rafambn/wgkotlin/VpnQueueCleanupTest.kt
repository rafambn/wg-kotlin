package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.IpcTestFixture
import com.rafambn.wgkotlin.iface.JvmInterfaceManager
import com.rafambn.wgkotlin.iface.SessionBridge
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpEndpoint
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VpnQueueCleanupTest {
    @Test
    fun stopDiscardsBothDirectionsOfBothPipesAndReusesTheManagers() = runBlocking {
        IpcTestFixture("echo").use {
            val vpn = Vpn("utun130")
            val tun = field<Pair<DuplexChannelPipe<ByteArray>, DuplexChannelPipe<ByteArray>>>(vpn, "tunPipePair")
            val network = field<Pair<DuplexChannelPipe<UdpDatagram>, DuplexChannelPipe<UdpDatagram>>>(vpn, "networkPipePair")
            val cryptoManager = field<Any>(vpn, "cryptoSessionManager")
            val socketManager = field<Any>(vpn, "socketManager")
            val interfaceManager = field<Any>(vpn, "interfaceManager")
            try {
                vpn.open(configuration("10.0.0.1/32"))
                vpn.stop()
                // Populate every queue while the consumers are stopped.
                val packet = byteArrayOf(1, 2, 3)
                val datagram = UdpDatagram(packet, UdpEndpoint("192.0.2.1", 51820))
                tun.first.send(packet)
                tun.second.send(packet)
                network.first.send(datagram)
                network.second.send(datagram)
                vpn.stop()
                assertNull(withTimeoutOrNull(50) { tun.first.receive() })
                assertNull(withTimeoutOrNull(50) { tun.second.receive() })
                assertNull(withTimeoutOrNull(50) { network.first.receive() })
                assertNull(withTimeoutOrNull(50) { network.second.receive() })

                vpn.open(configuration("10.0.1.1/32"))
                assertSame(cryptoManager, field(vpn, "cryptoSessionManager"))
                assertSame(socketManager, field(vpn, "socketManager"))
                assertSame(interfaceManager, field(vpn, "interfaceManager"))
                assertTrue(vpn.isRunning())
                assertEquals(listOf("10.0.1.1/32"), vpn.information()!!.vpnConfiguration!!.addresses)
            } finally {
                vpn.stop()
            }
        }
    }

    @Test
    fun reopeningDiscardsBufferedDatagramsBeforeStartingTheSocket() = runBlocking {
        IpcTestFixture("echo").use {
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { receiver ->
                receiver.soTimeout = 2_000
                val vpn = Vpn("utun130")
                val network = field<Pair<DuplexChannelPipe<UdpDatagram>, DuplexChannelPipe<UdpDatagram>>>(vpn, "networkPipePair")
                val endpoint = UdpEndpoint("127.0.0.1", receiver.localPort)
                try {
                    vpn.open(configuration("10.0.0.1/32"))
                    vpn.stop()
                    network.second.send(UdpDatagram(byteArrayOf(1), endpoint))
                    vpn.open(configuration("10.0.1.1/32"))
                    network.second.send(UdpDatagram(byteArrayOf(2), endpoint))
                    val received = DatagramPacket(ByteArray(10), 10)
                    receiver.receive(received)
                    assertContentEquals(byteArrayOf(2), received.data.copyOf(received.length))
                } finally {
                    vpn.stop()
                }
            }
        }
    }

    @Test
    fun stopWaitsForAnOpeningSessionAndClosesIt() {
        val vpn = Vpn("utun130")
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        replaceBridge(vpn) { _, _, _ ->
            opening.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            AutoCloseable { closed.set(true) }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val open = executor.submit { vpn.open(configuration()) }
            assertTrue(opening.await(5, TimeUnit.SECONDS))
            val stopping = CountDownLatch(1)
            val stop = executor.submit {
                stopping.countDown()
                vpn.stop()
            }
            assertTrue(stopping.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { stop.get(100, TimeUnit.MILLISECONDS) }
            release.countDown()
            open.get(5, TimeUnit.SECONDS)
            stop.get(5, TimeUnit.SECONDS)
            assertTrue(closed.get())
            assertFalse(vpn.isRunning())
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
            vpn.stop()
        }
    }

    @Test
    fun failureDuringOpeningClosesTheSessionThatFinishesOpening() = runBlocking {
        val vpn = Vpn("utun130")
        val closed = AtomicBoolean()
        replaceBridge(vpn) { _, _, onFailure ->
            onFailure(IllegalStateException("Failure during opening"))
            AutoCloseable { closed.set(true) }
        }
        try {
            vpn.open(configuration())
            withTimeout(5_000) {
                while (!closed.get()) delay(10)
            }
            assertTrue(closed.get())
            assertFalse(vpn.isRunning())
            assertNull(vpn.information())
        } finally {
            vpn.stop()
        }
    }

    @Test
    fun lateFailureFromAnOldOpeningCannotStopItsReplacement() = runBlocking {
        val vpn = Vpn("utun130")
        val callbacks = mutableListOf<(Throwable) -> Unit>()
        replaceBridge(vpn) { _, _, onFailure ->
            callbacks += onFailure
            AutoCloseable { }
        }
        try {
            vpn.open(configuration("10.0.0.1/32"))
            vpn.open(configuration("10.0.1.1/32"))
            callbacks.first()(IllegalStateException("Late failure"))
            val lifecycleScope = field<CoroutineScope>(vpn, "lifecycleScope")
            withTimeout(5_000) { lifecycleScope.coroutineContext.job.children.toList().joinAll() }
            assertTrue(vpn.isRunning())
            assertEquals(listOf("10.0.1.1/32"), vpn.information()!!.vpnConfiguration!!.addresses)
        } finally {
            vpn.stop()
        }
    }

    @Test
    fun cryptoWorkerFailureCanStopTheVpnWithoutWaitingForItself() = runBlocking {
        val vpn = Vpn("utun130")
        replaceBridge(vpn) { _, _, _ -> AutoCloseable { } }
        try {
            vpn.open(configuration())
            val network = field<Pair<DuplexChannelPipe<UdpDatagram>, DuplexChannelPipe<UdpDatagram>>>(vpn, "networkPipePair")
            network.first.send(UdpDatagram(byteArrayOf(0), UdpEndpoint("198.51.100.1", 51820)))
            withTimeout(5_000) {
                while (vpn.isRunning()) delay(10)
            }
            assertNull(vpn.information())
            vpn.open(configuration("10.0.1.1/32"))
            assertTrue(vpn.isRunning())
        } finally {
            vpn.stop()
        }
    }

    private fun configuration(address: String = "10.0.0.1/32") = VpnConfiguration(
        interfaceName = "utun130", listenPort = 0,
        addresses = mutableListOf(address),
        privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
        peers = listOf(VpnPeer(
            publicKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I=",
            endpointAddress = "198.51.100.1", endpointPort = 51820,
        )),
    )

    private fun replaceBridge(
        vpn: Vpn,
        open: (TunSessionConfig, DuplexChannelPipe<ByteArray>, (Throwable) -> Unit) -> AutoCloseable,
    ) {
        val manager = field<JvmInterfaceManager>(vpn, "interfaceManager")
        val bridge = object : SessionBridge {
            override fun openSession(config: TunSessionConfig, pipe: DuplexChannelPipe<ByteArray>, onFailure: (Throwable) -> Unit): AutoCloseable {
                return open(config, pipe, onFailure)
            }
        }
        val bridgeField = JvmInterfaceManager::class.java.getDeclaredField("sessionBridge").apply { isAccessible = true }
        bridgeField.set(manager, bridge)
    }

    private fun <T> field(vpn: Vpn, name: String): T {
        val field = Vpn::class.java.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return field.get(vpn) as T
    }
}
