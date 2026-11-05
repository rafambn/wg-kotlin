package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.Vpn
import com.rafambn.wgkotlin.iface.PlatformInterfaceFactory
import com.rafambn.wgkotlin.VpnConfiguration
import java.net.DatagramSocket
import java.net.BindException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DaemonProcessTest {
    @Test
    fun launchesUseDistinctEndpointsAndNoBearerCredentials() {
        IpcTestFixture().use { first ->
            IpcTestFixture().use { second ->
                assertNotEquals(first.endpoint, second.endpoint)
                assertEquals(first.process.pid(), first.daemon.pid)
                assertEquals(ProcessHandle.current().pid().toString(), first.arguments[first.arguments.indexOf("--owner-pid") + 1])
                assertFalse(first.arguments.any { it.contains("token") || it.contains("secret") || it == "--port" })
                assertSame(first.daemon, PlatformInterfaceFactory.requireDaemon())
            }
            assertSame(first.daemon, PlatformInterfaceFactory.requireDaemon())
        }
    }

    @Test
    fun forceKillNeedsNoCooperationAndCanBeRepeated() {
        IpcTestFixture("stubborn").use { fixture ->
            fixture.daemon.kill()
            assertFalse(fixture.process.isAlive)
            fixture.daemon.kill()
            assertFailsWith<IllegalStateException> { PlatformInterfaceFactory.requireDaemon() }
        }
    }

    @Test
    fun stopFallsBackToForcedTermination() {
        IpcTestFixture("stubborn").use { fixture ->
            fixture.daemon.stop(50.milliseconds)
            assertFalse(fixture.process.isAlive)
        }
    }

    @Test
    fun readinessFailureTerminatesTheChild() {
        assertFailsWith<IllegalStateException> { IpcTestFixture("no-ready", 200.milliseconds) }
        assertFailsWith<IllegalStateException> { IpcTestFixture("wrong-pid") }
    }

    @Test
    fun validatesActualServerPidBeforeSendingAnyConfig() {
        IpcTestFixture().use { fixture ->
            assertFailsWith<IllegalStateException> { LocalConnection.connect(fixture.endpoint, ProcessHandle.current().pid()) }
            LocalConnection.connect(fixture.endpoint, fixture.daemon.pid).use { IpcProtocol.awaitReady(it, fixture.daemon.pid) }
        }
    }

    @Test
    fun daemonExitsWhenItsOwnerProcessExits() {
        val host = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", requireNotNull(System.getProperty("wgkotlin.test.classpath")),
            IpcOwnerHost::class.java.name, requireNotNull(System.getProperty("wgkotlin.test.ipcDaemonExecutable")),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        var daemon: ProcessHandle? = null
        try {
            val pid = requireNotNull(host.inputStream.bufferedReader().readLine()).toLong()
            daemon = ProcessHandle.of(pid).orElseThrow()
            host.outputStream.close()
            assertTrue(host.waitFor(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (daemon.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
            assertFalse(daemon.isAlive, "Daemon survived its owning app")
        } finally {
            host.destroyForcibly()
            daemon?.destroyForcibly()
        }
    }

    @Test
    fun openWithoutDaemonFailsAndReleasesItsUdpSocket() {
        val vpn = Vpn("utun0")
        val port = DatagramSocket(0).use { it.localPort }
        val error = assertFailsWith<IllegalStateException> {
            vpn.open(VpnConfiguration(
                interfaceName = "utun0", listenPort = port,
                privateKey = "oA8gY5Yg7R6pujISiFDUFxIr05o2IaNbS1Ry6j3TzXs=",
            ))
        }
        assertTrue(error.cause?.message?.contains("Launch DaemonProcess") == true)
        // Ktor closes its socket through the selector's coroutine context.
        runBlocking {
            withTimeout(2_000) {
                while (true) {
                    try {
                        DatagramSocket(port).use { assertEquals(port, it.localPort) }
                        break
                    } catch (_: BindException) {
                        delay(10)
                    }
                }
            }
        }
        vpn.stop()
    }
}
