package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.IpAddr
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.io.bytestring.ByteString
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.net.InetAddress

internal object JvmInterfaceKoinBootstrap {
    private val baseModule: Module = module {
        factory<InterfaceCommandExecutor> {
            val hostStr = System.getProperty(JvmInterfaceProperties.DAEMON_HOST, JvmInterfaceProperties.DEFAULT_DAEMON_HOST)
            val addr = InetAddress.getByName(hostStr)
            DaemonSessionBridge(
                host = when (addr.address.size) {
                    4 -> IpAddr.Ip.V4(ByteString(addr.address))
                    16 -> IpAddr.Ip.V6(ByteString(addr.address))
                    else -> error("unexpected address size: ${addr.address.size}")
                },
                port = System.getProperty(JvmInterfaceProperties.DAEMON_PORT)?.toIntOrNull() ?: JvmInterfaceProperties.DEFAULT_DAEMON_PORT,
            )
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
