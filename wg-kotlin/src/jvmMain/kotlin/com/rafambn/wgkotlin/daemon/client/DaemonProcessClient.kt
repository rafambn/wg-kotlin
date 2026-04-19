package com.rafambn.wgkotlin.daemon.client

import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.PingResponse
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import java.time.Duration

class DaemonProcessClient(
    val service: DaemonApi,
    val timeout: Duration = Duration.ofSeconds(15),
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
                timeout = config.timeout,
                service = dependencies.service,
                resourceCloser = dependencies::close,
            )
        }
    }

    suspend fun handshake(timeout: Duration = Duration.ofSeconds(5)): PingResponse {
        return callWithTimeout(timeout) { service.ping() }
    }

    override suspend fun ping(): PingResponse {
        return callWithTimeout(timeout) { service.ping() }
    }

    override fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray> {
        return service.startSession(config = config, outgoingPackets = outgoingPackets)
    }

    private suspend fun <D> callWithTimeout(
        timeout: Duration,
        call: suspend () -> D,
    ): D {
        if (timeout.toMillis() <= 0L) {
            throw IllegalArgumentException("Timeout must be positive")
        }

        return try {
            withTimeout(timeout.toMillis()) {
                call()
            }
        } catch (timeoutFailure: TimeoutCancellationException) {
            throw DaemonClientException.Timeout(
                timeout = timeout,
                cause = timeoutFailure,
            )
        }
    }

    override fun close() {
        resourceCloser()
    }
}

data class DaemonClientConfig(
    val host: String = DaemonTransport.DEFAULT_DAEMON_HOST,
    val port: Int,
    val timeout: Duration = Duration.ofSeconds(15),
) {
    init {
        require(host.isNotBlank()) { "Daemon host cannot be blank" }
        require(port in 1..65535) { "Daemon port must be between 1 and 65535" }
        require(timeout.toMillis() > 0L) { "Timeout must be positive" }
    }
}
