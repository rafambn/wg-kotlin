package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import java.util.Locale
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal object JvmInterfaceKoinBootstrap {
    private val baseModule: Module = module {

        factory<InterfaceCommandExecutor> {
            when (
                System.getProperty(
                    JvmInterfaceProperties.INTERFACE_MODE,
                    JvmInterfaceProperties.INTERFACE_MODE_PRODUCTION,
                ).lowercase(Locale.ROOT)
            ) {
                JvmInterfaceProperties.INTERFACE_MODE_IN_MEMORY -> InMemoryInterfaceCommandExecutor()
                else -> DaemonBackedInterfaceCommandExecutor(
                    host = System.getProperty(JvmInterfaceProperties.DAEMON_HOST, DaemonTransport.DEFAULT_DAEMON_HOST),
                    port = System.getProperty(JvmInterfaceProperties.DAEMON_PORT)?.toIntOrNull() ?: DaemonTransport.DEFAULT_DAEMON_PORT,
                    token = DaemonTransport.configuredToken()
                        ?: error(
                            "Daemon auth token is required. Set -D${DaemonTransport.DAEMON_TOKEN_PROPERTY} or ${DaemonTransport.DAEMON_TOKEN_ENV}.",
                        ),
                )
            }
        }

        factory<InterfaceManager> { params ->
            val tunPipe = params.get<DuplexChannelPipe<ByteArray>>()
            JvmInterfaceManager(commandExecutor = get(), tunPipe = tunPipe)
        }
    }

    fun createInterfaceManager(
        tunPipe: DuplexChannelPipe<ByteArray>,
        overrideModules: List<Module> = emptyList(),
    ): InterfaceManager {
        val app = koinApplication {
            allowOverride(true)
            modules(listOf(baseModule) + overrideModules)
        }

        return try {
            app.koin.get(parameters = { parametersOf(tunPipe) })
        } finally {
            app.close()
        }
    }
}
