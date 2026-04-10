package com.rafambn.kmpvpn.daemon.di

import com.rafambn.kmpvpn.daemon.DaemonProcessApiImpl
import com.rafambn.kmpvpn.daemon.command.CommonsExecProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.planner.PlatformOperationPlanner
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

internal object DaemonKoinBootstrap {
    private val lock = Any()
    private val loadedModuleIds: MutableSet<Int> = linkedSetOf()

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

    fun ensureKoinStarted(overrideModules: List<Module> = emptyList()) {
        synchronized(lock) {
            if (runCatching { GlobalContext.get() }.isFailure) {
                loadedModuleIds.clear()
            }

            val requestedModules = listOf(baseModule) + overrideModules
            val pendingModules = requestedModules.filter { candidate ->
                loadedModuleIds.add(System.identityHashCode(candidate))
            }

            if (pendingModules.isEmpty()) {
                return
            }

            val existing = runCatching { GlobalContext.get() }.getOrNull()
            if (existing == null) {
                val started = runCatching {
                    startKoin {
                        allowOverride(true)
                        modules(pendingModules)
                    }
                }

                if (started.isSuccess) {
                    return
                }

                val nowExisting = runCatching { GlobalContext.get() }.getOrNull()
                if (nowExisting == null) {
                    throw checkNotNull(started.exceptionOrNull())
                }
            }

            loadKoinModules(pendingModules)
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            runCatching { stopKoin() }
            loadedModuleIds.clear()
        }
    }
}
