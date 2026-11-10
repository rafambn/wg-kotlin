package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InterfacePublicationTest {
    @Test
    fun failureBeforeOpenReturnsCannotPublishTheClosedBridge() {
        for (asynchronous in listOf(false, true)) {
            val cause = IllegalStateException("daemon disconnected before publication")
            val closes = AtomicInteger()
            val failures = mutableListOf<Throwable>()
            val bridge = bridge { _, onFailure ->
                if (asynchronous) {
                    val completed = CountDownLatch(1)
                    thread { try { onFailure(cause) } finally { completed.countDown() } }
                    assertTrue(completed.await(2, TimeUnit.SECONDS))
                } else {
                    onFailure(cause)
                }
                AutoCloseable { closes.incrementAndGet() }
            }
            val manager = manager(bridge)
            assertSame(cause, assertFailsWith<IllegalStateException> { manager.start(config("utun1"), failures::add) })
            assertFalse(manager.isRunning())
            assertNull(manager.information())
            assertEquals<List<Throwable>>(listOf(cause), failures)
            assertEquals(1, closes.get())
        }
    }

    @Test
    fun callbackFromAReplacedSessionDoesNotCloseTheNewSession() {
        val callbacks = mutableListOf<(Throwable) -> Unit>()
        val closed = mutableListOf<String>()
        val manager = manager(bridge { config, callback ->
            callbacks += callback
            AutoCloseable { closed += config.interfaceName }
        })
        var failures = 0
        manager.start(config("utun1")) { failures++ }
        manager.start(config("utun2")) { failures++ }
        callbacks[0](IllegalStateException("late old failure"))
        assertTrue(manager.isRunning())
        assertEquals("utun2", manager.information()!!.interfaceName)
        assertEquals(listOf("utun1"), closed)
        assertEquals(0, failures)
        callbacks[1](IllegalStateException("current failure"))
        assertFalse(manager.isRunning())
        assertNull(manager.information())
        assertEquals(listOf("utun1", "utun2"), closed)
        assertEquals(1, failures)
        manager.stop()
        assertEquals(2, closed.size)
    }

    @Test
    fun stoppingAnOpeningSessionClosesItsBridgeWhenItFinallyReturns() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val manager = manager(bridge { _, _ ->
            opening.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            AutoCloseable { closes.incrementAndGet() }
        })
        val worker = thread {
            try { manager.start(config("utun1")) {} } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(opening.await(2, TimeUnit.SECONDS))
            assertFalse(manager.isRunning())
            assertNull(manager.information())
            manager.stop()
        } finally {
            release.countDown()
            worker.join(2_000)
        }
        assertFalse(worker.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertFalse(manager.isRunning())
        assertNull(manager.information())
        assertEquals(1, closes.get())
    }

    @Test
    fun directOpenFailureKeepsItsCauseAndReportsOnlyOnce() {
        val cause = IllegalStateException("connection refused")
        val manager = manager(bridge { _, _ -> throw cause })
        val failures = mutableListOf<Throwable>()
        assertSame(cause, assertFailsWith<IllegalStateException> {
            manager.start(config("utun1"), failures::add)
        })
        assertEquals<List<Throwable>>(listOf(cause), failures)
        assertFalse(manager.isRunning())
        assertNull(manager.information())
        assertSame(cause, assertFailsWith<IllegalStateException> {
            manager.start(config("utun1")) { throw it }
        })
    }

    private fun bridge(open: (TunSessionConfig, (Throwable) -> Unit) -> AutoCloseable) = object : SessionBridge {
        override fun openSession(config: TunSessionConfig, pipe: DuplexChannelPipe<ByteArray>, onFailure: (Throwable) -> Unit): AutoCloseable =
            open(config, onFailure)
    }

    private fun manager(bridge: SessionBridge) = JvmInterfaceManager(bridge, DuplexChannelPipe.create<ByteArray>().first)
    private fun config(name: String) = TunSessionConfig { interfaceName = name }
}
