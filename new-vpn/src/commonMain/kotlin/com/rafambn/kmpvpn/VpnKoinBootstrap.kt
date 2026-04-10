package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.PlatformInterfaceFactory
import com.rafambn.kmpvpn.iface.VpnInterface
import com.rafambn.kmpvpn.session.InMemorySessionManager
import com.rafambn.kmpvpn.session.SessionManager
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

fun interface SessionManagerProvider {
    fun create(engine: Engine): SessionManager
}

fun interface VpnInterfaceProvider {
    fun create(configuration: VpnConfiguration): VpnInterface
}

internal object VpnKoinBootstrap {
    private val lock = Any()
    private val loadedModuleIds: MutableSet<Int> = linkedSetOf()

    private val baseModule: Module = module {
        single<SessionManagerProvider> {
            SessionManagerProvider { engine ->
                InMemorySessionManager(engine = engine)
            }
        }

        single<VpnInterfaceProvider> {
            VpnInterfaceProvider { configuration ->
                PlatformInterfaceFactory.create(configuration)
            }
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
