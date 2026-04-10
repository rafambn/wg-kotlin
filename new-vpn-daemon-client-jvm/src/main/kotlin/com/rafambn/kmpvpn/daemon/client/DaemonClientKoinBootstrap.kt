package com.rafambn.kmpvpn.daemon.client

import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.daemonRpcUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal object DaemonClientKoinBootstrap {
    private val baseModule: Module = module {
        factory<HttpClient> {
            HttpClient(CIO) {
                install(WebSockets)
                installKrpc {
                    serialization {
                        // TODO(vpn-rebuild): migrate kRPC serialization to Protobuf once protocol models are stable and annotated with @ProtoNumber.
                        json()
                    }
                }
            }
        }

        factory<DaemonProcessApi> { params ->
            val httpClient = params.get<HttpClient>()
            val config = params.get<DaemonClientConfig>()
            val rpcClient = httpClient.rpc(daemonRpcUrl(host = config.host, port = config.port))
            rpcClient.withService<DaemonProcessApi>()
        }
    }

    fun resolveDependencies(
        config: DaemonClientConfig,
        overrideModules: List<Module> = emptyList(),
    ): DaemonClientDependencies {
        val app = koinApplication {
            allowOverride(true)
            modules(listOf(baseModule) + overrideModules)
        }

        return try {
            val httpClient: HttpClient = app.koin.get()
            DaemonClientDependencies(
                httpClient = httpClient,
                service = app.koin.get(parameters = { parametersOf(httpClient, config) }),
            )
        } catch (failure: Throwable) {
            app.close()
            throw failure
        }
    }
}

internal data class DaemonClientDependencies(
    val httpClient: HttpClient,
    val service: DaemonProcessApi,
) {
    fun close() {
        httpClient.close()
    }
}
