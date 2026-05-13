package com.rafambn.wgkotlin.daemon.di

import com.rafambn.wgkotlin.daemon.DaemonImpl
import com.rafambn.wgkotlin.daemon.command.CommonsExecProcessLauncher
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapter
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapterFactory
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal object DaemonKoinBootstrap {
    private val lock = Any()
    private val baseModule: Module = module {
        single<ProcessLauncher> { CommonsExecProcessLauncher() }
        single<PlatformAdapter> { PlatformAdapterFactory.fromOs(processLauncher = get()) }
        single<DaemonApi> {
            DaemonImpl(adapter = get())
        }
    }

    private var koinApp: org.koin.core.KoinApplication? = null

    fun resolveDependencies(overrideModules: List<Module> = emptyList()): DaemonRuntimeDependencies {
        synchronized(lock) {
            koinApp?.close()
            val app = koinApplication {
                allowOverride(true)
                modules(listOf(baseModule) + overrideModules)
            }
            koinApp = app
            return DaemonRuntimeDependencies(
                adapter = app.koin.get(),
                service = app.koin.get(),
            )
        }
    }

    fun close() {
        synchronized(lock) {
            val app = koinApp
            koinApp = null
            app?.close()
        }
    }
}

internal data class DaemonRuntimeDependencies(
    val adapter: PlatformAdapter,
    val service: DaemonApi,
)
