package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.command.ProcessInvocationModel
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessOutputModel
import com.rafambn.kmpvpn.daemon.di.DaemonKoinBootstrap
import kotlinx.coroutines.runBlocking
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DaemonKoinBootstrapTest {

    @Test
    fun resolvingDependenciesAcceptsOverridesWithoutGlobalState() = runBlocking {
        val firstLauncher = RecordingLauncher()
        val secondLauncher = RecordingLauncher()
        val overrideModule = module {
            single<ProcessLauncher> { firstLauncher }
        }
        val secondOverrideModule = module {
            single<ProcessLauncher> { secondLauncher }
        }

        val firstDependencies = DaemonKoinBootstrap.resolveDependencies(overrideModules = listOf(overrideModule))
        val secondDependencies = DaemonKoinBootstrap.resolveDependencies(overrideModules = listOf(secondOverrideModule))

        val firstResult = firstDependencies.service.setInterfaceState(interfaceName = "utun0", up = true)
        val secondResult = secondDependencies.service.setInterfaceState(interfaceName = "utun1", up = true)

        assertTrue(firstResult.isSuccess)
        assertTrue(secondResult.isSuccess)
        assertEquals(1, firstLauncher.invocations.size)
        assertEquals(1, secondLauncher.invocations.size)
        assertNotSame(firstDependencies.service, secondDependencies.service)
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
