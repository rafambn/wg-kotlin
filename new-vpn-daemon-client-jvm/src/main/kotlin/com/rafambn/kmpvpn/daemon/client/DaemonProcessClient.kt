package com.rafambn.kmpvpn.daemon.client

import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DEFAULT_DAEMON_HOST
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.daemonRpcUrl
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.SetInterfaceStateResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.time.Duration
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

class DaemonProcessClient(
    host: String = DEFAULT_DAEMON_HOST,
    port: Int,
    val timeout: Duration = Duration.ofSeconds(15)
) : DaemonProcessApi, AutoCloseable {
    init {
        require(host.isNotBlank()) { "Daemon host cannot be blank" }
        require(port in 1..65535) { "Daemon port must be between 1 and 65535" }
    }

    private val httpClient: HttpClient = HttpClient(CIO) {
        install(WebSockets)
        installKrpc {
            serialization {
                // TODO(vpn-rebuild): migrate kRPC serialization to Protobuf once protocol models are stable and annotated with @ProtoNumber.
                json()
            }
        }
    }

    private val rpcClient = httpClient.rpc(daemonRpcUrl(host = host, port = port))
    private val service = rpcClient.withService<DaemonProcessApi>()

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
        httpClient.close()
    }
}
