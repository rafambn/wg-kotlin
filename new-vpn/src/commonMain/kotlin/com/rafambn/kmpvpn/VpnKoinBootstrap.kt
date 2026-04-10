package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.iface.InterfaceManager
import com.rafambn.kmpvpn.iface.PlatformInterfaceFactory
import com.rafambn.kmpvpn.session.InMemoryTunnelManager
import com.rafambn.kmpvpn.session.TunnelManager
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal object VpnKoinBootstrap {
    private val baseModule: Module = module {
        factory<TunnelManager> { params ->
            InMemoryTunnelManager(engine = params.get<Engine>())
        }

        factory<InterfaceManager> { params ->
            PlatformInterfaceFactory.create(params.get<VpnConfiguration>())
        }
    }

    fun resolveDependencies(
        configuration: VpnConfiguration,
        engine: Engine = Engine.BORINGTUN,
        overrideModules: List<Module> = emptyList(),
    ): VpnResolvedDependencies {
        val app = koinApplication {
            allowOverride(true)
            modules(listOf(baseModule) + overrideModules)
        }

        return try {
            VpnResolvedDependencies(
                tunnelManager = app.koin.get(parameters = { parametersOf(engine) }),
                interfaceManager = app.koin.get(parameters = { parametersOf(configuration) }),
            )
        } finally {
            app.close()
        }
    }
}

internal data class VpnResolvedDependencies(
    val tunnelManager: TunnelManager,
    val interfaceManager: InterfaceManager,
)
