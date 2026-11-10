package com.rafambn.wgkotlin.crypto

import com.rafambn.wgkotlin.ParsedVpnConfiguration
import com.rafambn.wgkotlin.ParsedVpnPeer
import com.rafambn.wgkotlin.crypto.factory.PeerSessionFactory
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.resolveToIp
import com.rafambn.wgkotlin.util.toCidr
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoSessionManagerShutdownTest {
    @Test
    fun stopWaitsForInFlightCryptoBeforeClosingTheSession() = runBlocking {
        val processing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = AtomicBoolean()
        val session = object : PeerSession {
            override val peerPublicKey: String = "peer-a"
            override val peerIndex: Int = 1
            override val isActive: Boolean get() = !closed.get()
            override fun encryptRawPacket(src: ByteArray, dstSize: UInt): PacketAction {
                processing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                check(!closed.get()) { "Session was closed while crypto was still running" }
                return PacketAction.WriteToNetwork(src)
            }
            override fun decryptToRawPacket(src: ByteArray, dstSize: UInt, sourceIp: String): PacketAction = PacketAction.Done
            override fun runPeriodicTask(dstSize: UInt): PacketAction = PacketAction.Done
            override fun close() { closed.set(true) }
        }
        val factory = object : PeerSessionFactory {
            override fun create(config: ParsedVpnConfiguration, peer: ParsedVpnPeer, peerIndex: Int): PeerSession = session
        }
        val tun = DuplexChannelPipe.create<ByteArray>()
        val network = DuplexChannelPipe.create<UdpDatagram>()
        val manager = CryptoSessionManagerImpl(tun.second, network.second, peerSessionFactory = factory)
        manager.reconcileSessions(ParsedVpnConfiguration(
            interfaceName = "utun130", privateKey = "private-key",
            peers = listOf(ParsedVpnPeer(
                publicKey = "peer-a", endpointAddress = "198.51.100.1".resolveToIp(), endpointPort = 51820,
                allowedIps = listOf("0.0.0.0/0".toCidr()),
            )),
        ))
        manager.start { throw AssertionError("Unexpected worker failure", it) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            tun.first.send(byteArrayOf(
                0x45, 0, 0, 20, 0, 0, 0, 0, 64, 0, 0, 0,
                10, 0, 0, 1, 8, 8, 8, 8,
            ))
            assertTrue(processing.await(5, TimeUnit.SECONDS))
            val stopping = CountDownLatch(1)
            val stop = executor.submit {
                stopping.countDown()
                manager.stop()
            }
            assertTrue(stopping.await(5, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> { stop.get(100, TimeUnit.MILLISECONDS) }
            assertFalse(closed.get())
            release.countDown()
            stop.get(5, TimeUnit.SECONDS)
            assertTrue(closed.get())
        } finally {
            release.countDown()
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
            manager.stop()
        }
    }
}
