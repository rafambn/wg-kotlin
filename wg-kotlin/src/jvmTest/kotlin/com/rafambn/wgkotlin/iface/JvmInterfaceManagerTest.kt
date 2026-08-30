package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JvmInterfaceManagerTest {

    @Test
    fun startWaitsForDaemonSessionReadiness() = runTest {
        val sessionBridge = ControlledSessionBridge()
        val manager = manager(sessionBridge)

        val start = async(start = CoroutineStart.UNDISPATCHED) {
            manager.start(sessionConfig())
        }

        assertFalse(manager.isRunning())

        sessionBridge.completeSession()
        start.await()

        assertTrue(manager.isRunning())
        manager.stop()
        assertTrue(sessionBridge.sessionClosed)
    }

    @Test
    fun initialDaemonFailureIsPropagated() = runTest {
        val expected = IllegalStateException("daemon rejected session")
        val manager = manager(ThrowingSessionBridge(expected))

        val actual = assertFailsWith<IllegalStateException> {
            manager.start(sessionConfig())
        }

        assertSame(expected, actual)
        assertFalse(manager.isRunning())
    }

    @Test
    fun failureBeforeBridgeInstallationPreventsFalseRunningState() = runTest {
        val expected = IllegalStateException("session ended after readiness")
        val sessionBridge = FailingOnReturnSessionBridge(expected)
        val manager = manager(sessionBridge)

        val actual = assertFailsWith<IllegalStateException> {
            manager.start(sessionConfig())
        }

        assertSame(expected, actual)
        assertFalse(manager.isRunning())
        assertTrue(sessionBridge.sessionClosed)
    }

    private fun manager(sessionBridge: SessionBridge): JvmInterfaceManager {
        val (tunPipe, _) = DuplexChannelPipe.create<ByteArray>()
        return JvmInterfaceManager(sessionBridge = sessionBridge, tunPipe = tunPipe)
    }

    private fun sessionConfig(): TunSessionConfig = TunSessionConfig {
        interfaceName = "utun123"
    }

    private class ControlledSessionBridge : SessionBridge {
        private val session = CompletableDeferred<AutoCloseable>()
        var sessionClosed = false
            private set

        override suspend fun openSession(
            config: TunSessionConfig,
            pipe: DuplexChannelPipe<ByteArray>,
            onFailure: (Throwable) -> Unit,
        ): AutoCloseable = session.await()

        fun completeSession() {
            session.complete(AutoCloseable { sessionClosed = true })
        }
    }

    private class ThrowingSessionBridge(
        private val failure: Throwable,
    ) : SessionBridge {
        override suspend fun openSession(
            config: TunSessionConfig,
            pipe: DuplexChannelPipe<ByteArray>,
            onFailure: (Throwable) -> Unit,
        ): AutoCloseable = throw failure
    }

    private class FailingOnReturnSessionBridge(
        private val failure: Throwable,
    ) : SessionBridge {
        var sessionClosed = false
            private set

        override suspend fun openSession(
            config: TunSessionConfig,
            pipe: DuplexChannelPipe<ByteArray>,
            onFailure: (Throwable) -> Unit,
        ): AutoCloseable {
            onFailure(failure)
            return AutoCloseable { sessionClosed = true }
        }
    }
}
