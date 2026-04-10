package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal object JvmInterfaceKoinBootstrap {
    private val baseModule: Module = module {
        factory<DaemonBackedInterfaceCommandExecutor> {
            DaemonBackedInterfaceCommandExecutor(
                clientFactory = ::createDaemonProcessClient,
            )
        }

        factory<InterfaceCommandExecutor> {
            when (
                System.getProperty(
                    JvmPlatformProperties.INTERFACE_MODE,
                    JvmPlatformProperties.INTERFACE_MODE_PRODUCTION,
                ).lowercase()
            ) {
                JvmPlatformProperties.INTERFACE_MODE_IN_MEMORY -> InMemoryInterfaceCommandExecutor()
                else -> get<DaemonBackedInterfaceCommandExecutor>()
            }
        }

        factory<TunProvider> { InMemoryTunProvider() }

        factory<InterfaceManager> { params ->
            val configuration = params.get<VpnConfiguration>()
            JvmInterfaceManager(
                interfaceName = configuration.interfaceName,
                commandExecutor = get(),
                tunProvider = get(),
            )
        }
    }

    fun createInterfaceManager(
        configuration: VpnConfiguration,
        overrideModules: List<Module> = emptyList(),
    ): InterfaceManager {
        val app = koinApplication {
            allowOverride(true)
            modules(listOf(baseModule) + overrideModules)
        }

        return try {
            app.koin.get(parameters = { parametersOf(configuration) })
        } finally {
            app.close()
        }
    }
}
