package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.util.toCidr
import com.rafambn.wgkotlin.util.resolveToIp
import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.Vpn
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.daemon.IpcTestFixture
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonIpcIntegrationTest {
    @Test
    fun cancelledReturnFromIoClosesAnOpenedSession() = runBlocking {
        IpcTestFixture("echo").use { fixture ->
            val (pipe, peer) = DuplexChannelPipe.create<ByteArray>()
            val failure = CompletableDeferred<Throwable>()
            val exchanged = CountDownLatch(1)
            val packet = byteArrayOf(1, 2, 3)
            peer.send(packet)
            val echo = async(Dispatchers.IO) {
                try {
                    assertContentEquals(packet, withTimeout(2_000) { peer.receive() })
                } finally {
                    exchanged.countDown()
                }
            }
            val opening = async(start = CoroutineStart.UNDISPATCHED) {
                PlatformInterfaceManager.create(TunSessionConfig { interfaceName = "utun0" }, pipe) {
                    failure.complete(it)
                }
            }
            try {
                assertFalse(opening.isCompleted, "IPC creation must leave the caller's thread available")
                // Hold the caller's thread until the session has exchanged a packet on IO.
                assertTrue(exchanged.await(3, TimeUnit.SECONDS))
                opening.cancelAndJoin()
                echo.await()
                assertFailsWith<CancellationException> { opening.await() }

                fixture.daemon.kill()
                assertNull(withTimeoutOrNull(500) { failure.await() }, "A discarded session must already be closed")
            } finally {
                opening.cancelAndJoin()
                echo.cancelAndJoin()
            }
        }
    }

    @Test
    fun existingVpnApiCanBeConstructedBeforeLaunchingTheDaemon() = runBlocking {
        val first = Vpn()
        val second = Vpn()
        IpcTestFixture("echo").use { fixture ->
            try {
                fun configuration(name: String) = VpnConfiguration(
                    interfaceName = name,
                    listenPort = 0,
                    addresses = listOf("10.0.0.2/32".toCidr()),
                    privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
                    peers = listOf(VpnPeer(
                        publicKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I=",
                        endpointAddress = "198.51.100.1".resolveToIp(), endpointPort = 51820,
                    )),
                )
                first.start(configuration("utun10")).getOrThrow()
                second.start(configuration("utun11")).getOrThrow()
                assertTrue(first.information("utun10")?.isUp == true)
                assertTrue(second.information("utun11")?.isUp == true)
                first.stop("utun10").getOrThrow()
                assertNull(first.information("utun10"))
                assertTrue(second.information("utun11")?.isUp == true)
                assertTrue(fixture.daemon.isAlive)
            } finally {
                first.stop("utun10").getOrThrow()
                second.stop("utun11").getOrThrow()
            }
        }
    }

    @Test
    fun ownedClientReachesRustConfigValidationWithoutRootOrCredentials() = runBlocking {
        IpcTestFixture().use { fixture ->
            val vpn = Vpn()
            val failure = assertFailsWith<IllegalStateException> {
                vpn.start(VpnConfiguration(interfaceName = "utun1", listenPort = 0,
                    privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
                )).getOrThrow()
            }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("addresses must contain") == true })
            assertNull(vpn.information("utun1"))
        }
    }

    @Test
    fun twoSessionConnectionsAreIndependentOnTheSameEndpoint() {
        IpcTestFixture("echo").use { fixture ->
            val (first, firstPeer) = DuplexChannelPipe.create<ByteArray>()
            val (second, secondPeer) = DuplexChannelPipe.create<ByteArray>()
            val (firstSession, secondSession) = runBlocking {
                val a = async(Dispatchers.IO) { PlatformInterfaceManager.create(TunSessionConfig { interfaceName = "utun0" }, first) {} }
                val b = async(Dispatchers.IO) { PlatformInterfaceManager.create(TunSessionConfig { interfaceName = "utun1" }, second) {} }
                a.await() to b.await()
            }
            secondSession.use {
                runBlocking {
                    assertEquals("utun0", firstSession.information()?.interfaceName)
                    assertEquals("utun1", secondSession.information()?.interfaceName)
                    firstPeer.send(byteArrayOf(1, 2, 3))
                    assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(2_000) { firstPeer.receive() })
                    firstSession.close()
                    assertNull(firstSession.information())
                    assertTrue(secondSession.information()?.isUp == true)
                    secondPeer.send(byteArrayOf(4, 5, 6))
                    assertContentEquals(byteArrayOf(4, 5, 6), withTimeout(2_000) { secondPeer.receive() })
                }
            }
            assertNull(secondSession.information())
            firstSession.close()
        }
    }

    @Test
    fun anotherProcessOfTheSameUserCannotOpenASession() {
        IpcTestFixture().use { fixture ->
            val foreign = fixture.startForeignClient()
            try {
                assertTrue(foreign.waitFor(5, TimeUnit.SECONDS))
                assertEquals(0, foreign.exitValue())
            } finally {
                foreign.destroyForcibly()
            }
        }
    }
}
