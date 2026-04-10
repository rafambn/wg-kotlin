package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.command.ProcessInvocationModel
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessOutputModel
import com.rafambn.kmpvpn.daemon.di.DaemonKoinBootstrap
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonKoinBootstrapTest {

    @AfterTest
    fun tearDown() {
        DaemonKoinBootstrap.resetForTests()
    }

    @Test
    fun bootstrappingIsIdempotentAndAcceptsOverrides() = runBlocking {
        val launcher = RecordingLauncher()
        val overrideModule = module {
            single<ProcessLauncher> { launcher }
        }

        DaemonKoinBootstrap.ensureKoinStarted(overrideModules = listOf(overrideModule))
        DaemonKoinBootstrap.ensureKoinStarted(overrideModules = listOf(overrideModule))

        val service = GlobalContext.get().get<DaemonProcessApi>()
        val result = service.setInterfaceState(interfaceName = "utun0", up = true)

        assertTrue(result.isSuccess)
        assertEquals(1, launcher.invocations.size)
    }

    private class RecordingLauncher : ProcessLauncher {
        val invocations: MutableList<ProcessInvocationModel> = mutableListOf()

        override fun run(invocation: ProcessInvocationModel): ProcessOutputModel {
            invocations += invocation
            return ProcessOutputModel(
                exitCode = 0,
                stdout = "",
                stderr = "",
            )
        }
    }
}
