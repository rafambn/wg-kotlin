package com.rafambn.kmpvpn.daemon.client

import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DEFAULT_DAEMON_HOST
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.DeleteInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.SetInterfaceStateResponse
import java.time.Duration
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module

class DaemonProcessClient private constructor(
    private val service: DaemonProcessApi,
    private val resourceCloser: () -> Unit,
    val timeout: Duration,
) : DaemonProcessApi, AutoCloseable {

    companion object {
        fun create(
            config: DaemonClientConfig,
            overrideModules: List<Module> = emptyList(),
        ): DaemonProcessClient {
            DaemonClientKoinBootstrap.ensureKoinStarted(overrideModules)
            val koin = GlobalContext.get()
            val httpClientFactory = koin.get<DaemonClientHttpClientFactory>()
            val serviceFactory = koin.get<DaemonClientServiceFactory>()
            val httpClient = httpClientFactory.create()
            val service = serviceFactory.create(httpClient = httpClient, config = config)

            return DaemonProcessClient(
                service = service,
                resourceCloser = { httpClient.close() },
                timeout = config.timeout,
            )
        }

        internal fun resetKoinForTests() {
            DaemonClientKoinBootstrap.resetForTests()
        }
    }

    suspend fun handshake(timeout: Duration = Duration.ofSeconds(5)): CommandResult<PingResponse> {
        val response = callWithTimeout(timeout) { service.ping() }

        if (response is CommandResult.Failure) {
            throw DaemonClientException.ProtocolViolation(
                message = "Handshake failed: ${response.message}",
            )
        }

        return response
    }

    override suspend fun ping(): CommandResult<PingResponse> {
        return callWithTimeout(timeout) { service.ping() }
    }

    override suspend fun interfaceExists(interfaceName: String): CommandResult<InterfaceExistsResponse> {
        return callWithTimeout(timeout) {
            service.interfaceExists(interfaceName = interfaceName)
        }
    }

    override suspend fun setInterfaceState(
        interfaceName: String,
        up: Boolean,
    ): CommandResult<SetInterfaceStateResponse> {
        return callWithTimeout(timeout) {
            service.setInterfaceState(interfaceName = interfaceName, up = up)
        }
    }

    override suspend fun applyMtu(
        interfaceName: String,
        mtu: Int,
    ): CommandResult<ApplyMtuResponse> {
        return callWithTimeout(timeout) {
            service.applyMtu(interfaceName = interfaceName, mtu = mtu)
        }
    }

    override suspend fun applyAddresses(
        interfaceName: String,
        addresses: List<String>,
    ): CommandResult<ApplyAddressesResponse> {
        return callWithTimeout(timeout) {
            service.applyAddresses(interfaceName = interfaceName, addresses = addresses)
        }
    }

    override suspend fun applyRoutes(
        interfaceName: String,
        routes: List<String>,
    ): CommandResult<ApplyRoutesResponse> {
        return callWithTimeout(timeout) {
            service.applyRoutes(
                interfaceName = interfaceName,
                routes = routes,
            )
        }
    }

    override suspend fun applyDns(
        interfaceName: String,
        dnsDomainPool: Pair<List<String>, List<String>>,
    ): CommandResult<ApplyDnsResponse> {
        return callWithTimeout(timeout) {
            service.applyDns(interfaceName = interfaceName, dnsDomainPool = dnsDomainPool)
        }
    }

    override suspend fun readInterfaceInformation(
        interfaceName: String,
    ): CommandResult<ReadInterfaceInformationResponse> {
        return callWithTimeout(timeout) {
            service.readInterfaceInformation(interfaceName = interfaceName)
        }
    }

    override suspend fun deleteInterface(
        interfaceName: String,
    ): CommandResult<DeleteInterfaceResponse> {
        return callWithTimeout(timeout) {
            service.deleteInterface(interfaceName = interfaceName)
        }
    }

    private suspend fun <D> callWithTimeout(
        timeout: Duration,
        call: suspend () -> CommandResult<D>,
    ): CommandResult<D> {
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
    val host: String = DEFAULT_DAEMON_HOST,
    val port: Int,
    val timeout: Duration = Duration.ofSeconds(15),
) {
    init {
        require(host.isNotBlank()) { "Daemon host cannot be blank" }
        require(port in 1..65535) { "Daemon port must be between 1 and 65535" }
        require(timeout.toMillis() > 0L) { "Timeout must be positive" }
    }
}
