package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.Vpn
import com.rafambn.wgkotlin.VpnPeer
import com.rafambn.wgkotlin.daemon.IpcTestFixture
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class DaemonIpcIntegrationTest {
    @Test
    fun existingVpnApiCanBeConstructedBeforeLaunchingTheDaemon() {
        val first = Vpn()
        val second = Vpn()
        IpcTestFixture("echo").use { fixture ->
            try {
                fun configuration(name: String) = VpnConfiguration(
                    interfaceName = name,
                    listenPort = 0,
                    addresses = mutableListOf("10.0.0.2/32"),
                    privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
                    peers = listOf(VpnPeer(
                        publicKey = "6fX3drXr/7L0KleChX2NDSSSXWMQZnIcXtNCmieYw0I=",
                        endpointAddress = "198.51.100.1", endpointPort = 51820,
                    )),
                )
                first.open(configuration("utun10"))
                second.open(configuration("utun11"))
                assertTrue(first.isRunning())
                assertTrue(second.isRunning())
                first.stop()
                assertFalse(first.isRunning())
                assertTrue(second.isRunning())
                assertTrue(fixture.daemon.isAlive)
            } finally {
                first.stop()
                second.stop()
            }
        }
    }

    @Test
    fun ownedClientReachesRustConfigValidationWithoutRootOrCredentials() {
        IpcTestFixture().use { fixture ->
            val vpn = Vpn()
            val failure = assertFailsWith<IllegalStateException> {
                vpn.open(VpnConfiguration(
                    interfaceName = "utun1", listenPort = 0,
                    privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
                ))
            }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message?.contains("addresses must contain") == true })
        }
    }

    @Test
    fun twoSessionConnectionsAreIndependentOnTheSameEndpoint() {
        IpcTestFixture("echo").use { fixture ->
            val bridge = DaemonSessionBridge()
            val (first, firstPeer) = DuplexChannelPipe.create<ByteArray>()
            val (second, secondPeer) = DuplexChannelPipe.create<ByteArray>()
            val (firstSession, secondSession) = runBlocking {
                val a = async(Dispatchers.IO) { bridge.openSession(TunSessionConfig { interfaceName = "utun0" }, first) {} }
                val b = async(Dispatchers.IO) { bridge.openSession(TunSessionConfig { interfaceName = "utun1" }, second) {} }
                a.await() to b.await()
            }
            secondSession.use {
                runBlocking {
                    firstPeer.send(byteArrayOf(1, 2, 3))
                    assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(2_000) { firstPeer.receive() })
                    firstSession.close()
                    secondPeer.send(byteArrayOf(4, 5, 6))
                    assertContentEquals(byteArrayOf(4, 5, 6), withTimeout(2_000) { secondPeer.receive() })
                }
            }
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
