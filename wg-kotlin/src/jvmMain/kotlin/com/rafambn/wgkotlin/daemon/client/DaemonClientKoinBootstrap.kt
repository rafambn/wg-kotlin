package com.rafambn.wgkotlin.daemon.client

import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalSerializationApi::class)
internal object DaemonClientKoinBootstrap {
    private val baseModule: Module = module {
        factory<HttpClient> {
            HttpClient(CIO) {
                install(WebSockets)
                installKrpc {
                    serialization {
                        protobuf()
                    }
                }
            }
        }

        factory<DaemonApi> { params ->
            val httpClient = params.get<HttpClient>()
            val config = params.get<DaemonClientConfig>()
            val rpcClient = httpClient.rpc(DaemonTransport.rpcUrl(host = config.host, port = config.port))
            rpcClient.withService<DaemonApi>()
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
    val service: DaemonApi,
) {
    fun close() {
        httpClient.close()
    }
}
