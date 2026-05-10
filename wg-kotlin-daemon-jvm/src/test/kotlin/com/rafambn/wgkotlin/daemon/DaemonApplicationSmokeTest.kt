package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapter
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.TunHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonApplicationSmokeTest {

    @Test
    fun startSessionStreamsPacketsAndClosesHandle() = runBlocking {
        val adapter = RecordingAdapter()
        val api = DaemonImpl(adapter = adapter)

        val packet = api.startSession(
            config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24")),
            outgoingPackets = emptyFlow(),
        ).first()

        assertEquals("1, 2, 3", packet.joinToString())
        assertEquals(1, adapter.startCalls)
        assertEquals(1, adapter.handle.closeCalls)
    }

    @Test
    fun cancellingSessionClosesBlockedHandleAndReleasesActiveSession() = runBlocking {
        val firstHandle = BlockingReadHandle(interfaceName = "wg0")
        val secondHandle = RecordingHandle()
        val adapter = QueuedAdapter(firstHandle, secondHandle)
        val api = DaemonImpl(adapter = adapter)
        val config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24"))

        val firstSession = launch {
            api.startSession(config = config, outgoingPackets = emptyFlow()).collect()
        }

        firstHandle.awaitReadStarted()

        firstSession.cancel()

        firstHandle.awaitClosed()
        val packet = withTimeout(1_000) {
            api.startSession(config = config, outgoingPackets = emptyFlow()).first()
        }

        assertEquals("1, 2, 3", packet.joinToString())
        assertEquals(2, adapter.startCalls)
        assertEquals(1, firstHandle.closeCalls)
        assertEquals(1, secondHandle.closeCalls)

        firstSession.cancelAndJoin()
    }

    @Test
    fun duplicateSessionReportsActiveInterfaceName() = runBlocking {
        val handle = BlockingReadHandle(interfaceName = "wg0")
        val api = DaemonImpl(adapter = QueuedAdapter(handle))
        val config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24"))

        val firstSession = launch {
            api.startSession(config = config, outgoingPackets = emptyFlow()).collect()
        }

        handle.awaitReadStarted()

        val failure = assertFailsWith<IllegalStateException> {
            api.startSession(config = config, outgoingPackets = emptyFlow()).first()
        }

        assertEquals("Session already active for wg0", failure.message)

        firstSession.cancelAndJoin()
    }

    private class RecordingAdapter : PlatformAdapter {
        val handle = RecordingHandle()
        var startCalls: Int = 0
        override val platformId: String = "test"
        override val requiredBinaries: Set<CommandBinary> = emptySet()

        override suspend fun startSession(config: TunSessionConfig): TunHandle {
            startCalls++
            return handle
        }
    }

    private class RecordingHandle : TunHandle {
        override val interfaceName: String = "wg0"
        var closeCalls: Int = 0
        private var emitted = false

        override suspend fun readPacket(): ByteArray? {
            return if (emitted) { kotlinx.coroutines.delay(10); null } else byteArrayOf(1, 2, 3).also { emitted = true }
        }

        override suspend fun writePacket(packet: ByteArray) {}

        override fun close() {
            closeCalls++
        }
    }

    private class QueuedAdapter(
        vararg handles: TunHandle,
    ) : PlatformAdapter {
        private val handles = ArrayDeque(handles.toList())
        var startCalls: Int = 0
        override val platformId: String = "test"
        override val requiredBinaries: Set<CommandBinary> = emptySet()

        override suspend fun startSession(config: TunSessionConfig): TunHandle {
            startCalls++
            return handles.pollFirst() ?: error("No queued handle for ${config.interfaceName}")
        }
    }

    private class BlockingReadHandle(
        override val interfaceName: String,
    ) : TunHandle {
        private val readStarted = CompletableDeferred<Unit>()
        private val closed = CompletableDeferred<Unit>()
        private val readBlocker = CountDownLatch(1)
        var closeCalls: Int = 0

        override suspend fun readPacket(): ByteArray? {
            readStarted.complete(Unit)
            return withContext(Dispatchers.IO) {
                readBlocker.await(5, TimeUnit.SECONDS)
                null
            }
        }

        override suspend fun writePacket(packet: ByteArray) {}

        override fun close() {
            closeCalls++
            closed.complete(Unit)
            readBlocker.countDown()
        }

        suspend fun awaitReadStarted() {
            withTimeout(1_000) {
                readStarted.await()
            }
        }

        suspend fun awaitClosed() {
            withTimeout(1_000) {
                closed.await()
            }
        }
    }
}
