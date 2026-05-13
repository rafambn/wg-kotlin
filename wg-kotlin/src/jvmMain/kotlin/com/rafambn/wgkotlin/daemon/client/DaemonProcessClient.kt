package com.rafambn.wgkotlin.daemon.client

import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import kotlinx.coroutines.flow.Flow
import org.koin.core.module.Module
import java.time.Duration

class DaemonProcessClient(
    val service: DaemonApi,
    val resourceCloser: () -> Unit = {},
) : DaemonApi, AutoCloseable {

    companion object {
        internal fun create(
            config: DaemonClientConfig,
            overrideModules: List<Module> = emptyList(),
        ): DaemonProcessClient {
            val dependencies = DaemonClientKoinBootstrap.resolveDependencies(
                config = config,
                overrideModules = overrideModules,
            )

            return DaemonProcessClient(
                service = dependencies.service,
                resourceCloser = dependencies::close,
            )
        }
    }

    override fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray> {
        return service.startSession(config = config, outgoingPackets = outgoingPackets)
    }

    override fun close() {
        resourceCloser()
    }
}

data class DaemonClientConfig(
    val host: String = DaemonTransport.DEFAULT_DAEMON_HOST,
    val port: Int,
    val token: String? = DaemonTransport.configuredToken(),
    val timeout: Duration = Duration.ofSeconds(15),
) {
    init {
        require(host.isNotBlank()) { "Daemon host cannot be blank" }
        require(port in 1..65535) { "Daemon port must be between 1 and 65535" }
        require(token == null || token.isNotBlank()) { "Daemon token cannot be blank" }
        require(timeout.toMillis() > 0L) { "Timeout must be positive" }
    }
}
