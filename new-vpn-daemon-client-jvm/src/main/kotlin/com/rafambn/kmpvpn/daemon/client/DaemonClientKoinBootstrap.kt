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
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

fun interface DaemonClientHttpClientFactory {
    fun create(): HttpClient
}

fun interface DaemonClientServiceFactory {
    fun create(httpClient: HttpClient, config: DaemonClientConfig): DaemonProcessApi
}

internal object DaemonClientKoinBootstrap {
    private val lock = Any()
    private val loadedModuleIds: MutableSet<Int> = linkedSetOf()

    private val baseModule: Module = module {
        single<DaemonClientHttpClientFactory> {
            DaemonClientHttpClientFactory {
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
        }

        single<DaemonClientServiceFactory> {
            DaemonClientServiceFactory { httpClient, config ->
                val rpcClient = httpClient.rpc(daemonRpcUrl(host = config.host, port = config.port))
                rpcClient.withService<DaemonProcessApi>()
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
