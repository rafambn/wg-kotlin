package com.rafambn.kmpvpn.daemon.di

import com.rafambn.kmpvpn.daemon.DaemonProcessApiImpl
import com.rafambn.kmpvpn.daemon.command.CommonsExecProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.planner.PlatformOperationPlanner
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal object DaemonKoinBootstrap {
    private val baseModule: Module = module {
        single<PlatformOperationPlanner> { PlatformOperationPlanner.fromOs() }
        single<ProcessLauncher> { CommonsExecProcessLauncher() }
        single<DaemonProcessApi> {
            DaemonProcessApiImpl(
                operationPlanner = get(),
                processLauncher = get(),
            )
        }
    }

    fun resolveDependencies(overrideModules: List<Module> = emptyList()): DaemonRuntimeDependencies {
        val app = koinApplication {
            allowOverride(true)
            modules(listOf(baseModule) + overrideModules)
        }

        return try {
            DaemonRuntimeDependencies(
                operationPlanner = app.koin.get(),
                service = app.koin.get(),
            )
        } finally {
            app.close()
        }
    }
}

internal data class DaemonRuntimeDependencies(
    val operationPlanner: PlatformOperationPlanner,
    val service: DaemonProcessApi,
)
