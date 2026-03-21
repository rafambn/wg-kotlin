package com.rafambn.kmpvpn.daemon.client

import com.rafambn.kmpvpn.daemon.protocol.DAEMON_HELLO_TOKEN
import com.rafambn.kmpvpn.daemon.protocol.DaemonCommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.request.ApplyPeerConfigurationRequest
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyPeerConfigurationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.CreateInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.DeleteInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadPeerStatsResponse
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
    host: String = "127.0.0.1",
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

    private val rpcClient = httpClient.rpc("ws://$host:$port/services")
    private val service = rpcClient.withService<DaemonProcessApi>()

    suspend fun handshake(timeout: Duration = Duration.ofSeconds(5)): DaemonCommandResult<PingResponse> {
        val response = callWithTimeout(timeout) {
            service.ping(nonce = "handshake")
        }

        val success = response as? DaemonCommandResult.Success<PingResponse>
            ?: throw DaemonClientException.ProtocolViolation(
                message = "Handshake failed: ${(response as DaemonCommandResult.Failure).message}",
            )

        if (success.data.helloToken.trim() != DAEMON_HELLO_TOKEN) {
            throw DaemonClientException.ProtocolViolation(
                message = "Invalid handshake response. Expected `$DAEMON_HELLO_TOKEN`, got `${success.data.helloToken}`",
            )
        }

        return success
    }

    override suspend fun ping(nonce: String): DaemonCommandResult<PingResponse> {
        return callWithTimeout(timeout) {
            service.ping(nonce = nonce)
        }
    }

    override suspend fun interfaceExists(interfaceName: String): DaemonCommandResult<InterfaceExistsResponse> {
        return callWithTimeout(timeout) {
            service.interfaceExists(interfaceName = interfaceName)
        }
    }

    override suspend fun createInterface(interfaceName: String): DaemonCommandResult<CreateInterfaceResponse> {
        return callWithTimeout(timeout) {
            service.createInterface(interfaceName = interfaceName)
        }
    }

    override suspend fun deleteInterface(interfaceName: String): DaemonCommandResult<DeleteInterfaceResponse> {
        return callWithTimeout(timeout) {
            service.deleteInterface(interfaceName = interfaceName)
        }
    }

    override suspend fun setInterfaceState(
        interfaceName: String,
        up: Boolean,
    ): DaemonCommandResult<SetInterfaceStateResponse> {
        return callWithTimeout(timeout) {
            service.setInterfaceState(interfaceName = interfaceName, up = up)
        }
    }

    override suspend fun applyMtu(
        interfaceName: String,
        mtu: Int?,
    ): DaemonCommandResult<ApplyMtuResponse> {
        return callWithTimeout(timeout) {
            service.applyMtu(interfaceName = interfaceName, mtu = mtu)
        }
    }

    override suspend fun applyAddresses(
        interfaceName: String,
        addresses: List<String>,
    ): DaemonCommandResult<ApplyAddressesResponse> {
        return callWithTimeout(timeout) {
            service.applyAddresses(interfaceName = interfaceName, addresses = addresses)
        }
    }

    override suspend fun applyRoutes(
        interfaceName: String,
        routes: List<String>,
        table: String?,
    ): DaemonCommandResult<ApplyRoutesResponse> {
        return callWithTimeout(timeout) {
            service.applyRoutes(
                interfaceName = interfaceName,
                routes = routes,
                table = table,
            )
        }
    }

    override suspend fun applyDns(
        interfaceName: String,
        dnsServers: List<String>,
    ): DaemonCommandResult<ApplyDnsResponse> {
        return callWithTimeout(timeout) {
            service.applyDns(interfaceName = interfaceName, dnsServers = dnsServers)
        }
    }

    override suspend fun applyPeerConfiguration(
        request: ApplyPeerConfigurationRequest,
    ): DaemonCommandResult<ApplyPeerConfigurationResponse> {
        return callWithTimeout(timeout) {
            service.applyPeerConfiguration(request)
        }
    }

    override suspend fun readInterfaceInformation(
        interfaceName: String,
    ): DaemonCommandResult<ReadInterfaceInformationResponse> {
        return callWithTimeout(timeout) {
            service.readInterfaceInformation(interfaceName = interfaceName)
        }
    }

    override suspend fun readPeerStats(interfaceName: String): DaemonCommandResult<ReadPeerStatsResponse> {
        return callWithTimeout(timeout) {
            service.readPeerStats(interfaceName = interfaceName)
        }
    }

    private suspend fun <D> callWithTimeout(
        timeout: Duration,
        call: suspend () -> DaemonCommandResult<D>,
    ): DaemonCommandResult<D> {
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
