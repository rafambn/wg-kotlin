package com.rafambn.kmpvpn.daemon.client

import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyPeerConfigurationResponse
import com.rafambn.kmpvpn.daemon.protocol.request.ApplyPeerConfigurationRequest
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.CreateInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.DAEMON_HELLO_TOKEN
import com.rafambn.kmpvpn.daemon.protocol.DaemonCommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonControlPlaneService
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

class KtorKrpcDaemonClient(
    endpoint: DaemonClientEndpoint,
    clientFactory: (() -> HttpClient)? = null,
) : DaemonClient {
    private val ownsHttpClient: Boolean = clientFactory == null

    private val httpClient: HttpClient = clientFactory?.invoke() ?: HttpClient(CIO) {
        install(WebSockets)
        installKrpc {
            serialization {
                // TODO(vpn-rebuild): migrate kRPC serialization to Protobuf once protocol models are stable and annotated with @ProtoNumber.
                json()
            }
        }
    }

    private val rpcClient = httpClient.rpc(endpoint.wsUrl())
    private val service: DaemonControlPlaneService = rpcClient.withService()

    override suspend fun handshake(timeout: Duration): DaemonCommandResult<PingResponse> {
        val response = ping(nonce = "handshake", timeout = timeout)

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

    override suspend fun ping(
        nonce: String,
        timeout: Duration,
    ): DaemonCommandResult<PingResponse> {
        return executeCommand(timeout) {
            service.ping(nonce = nonce)
        }
    }

    override suspend fun interfaceExists(
        interfaceName: String,
        timeout: Duration,
    ): DaemonCommandResult<InterfaceExistsResponse> {
        return executeCommand(timeout) {
            service.interfaceExists(interfaceName = interfaceName)
        }
    }

    override suspend fun createInterface(
        interfaceName: String,
        timeout: Duration,
    ): DaemonCommandResult<CreateInterfaceResponse> {
        return executeCommand(timeout) {
            service.createInterface(interfaceName = interfaceName)
        }
    }

    override suspend fun deleteInterface(
        interfaceName: String,
        timeout: Duration,
    ): DaemonCommandResult<DeleteInterfaceResponse> {
        return executeCommand(timeout) {
            service.deleteInterface(interfaceName = interfaceName)
        }
    }

    override suspend fun setInterfaceState(
        interfaceName: String,
        up: Boolean,
        timeout: Duration,
    ): DaemonCommandResult<SetInterfaceStateResponse> {
        return executeCommand(timeout) {
            service.setInterfaceState(interfaceName = interfaceName, up = up)
        }
    }

    override suspend fun applyMtu(
        interfaceName: String,
        mtu: Int?,
        timeout: Duration,
    ): DaemonCommandResult<ApplyMtuResponse> {
        return executeCommand(timeout) {
            service.applyMtu(interfaceName = interfaceName, mtu = mtu)
        }
    }

    override suspend fun applyAddresses(
        interfaceName: String,
        addresses: List<String>,
        timeout: Duration,
    ): DaemonCommandResult<ApplyAddressesResponse> {
        return executeCommand(timeout) {
            service.applyAddresses(interfaceName = interfaceName, addresses = addresses)
        }
    }

    override suspend fun applyRoutes(
        interfaceName: String,
        routes: List<String>,
        table: String?,
        timeout: Duration,
    ): DaemonCommandResult<ApplyRoutesResponse> {
        return executeCommand(timeout) {
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
        timeout: Duration,
    ): DaemonCommandResult<ApplyDnsResponse> {
        return executeCommand(timeout) {
            service.applyDns(interfaceName = interfaceName, dnsServers = dnsServers)
        }
    }

    override suspend fun applyPeerConfiguration(
        request: ApplyPeerConfigurationRequest,
        timeout: Duration,
    ): DaemonCommandResult<ApplyPeerConfigurationResponse> {
        return executeCommand(timeout) {
            service.applyPeerConfiguration(request)
        }
    }

    override suspend fun readInterfaceInformation(
        interfaceName: String,
        timeout: Duration,
    ): DaemonCommandResult<ReadInterfaceInformationResponse> {
        return executeCommand(timeout) {
            service.readInterfaceInformation(interfaceName = interfaceName)
        }
    }

    override suspend fun readPeerStats(
        interfaceName: String,
        timeout: Duration,
    ): DaemonCommandResult<ReadPeerStatsResponse> {
        return executeCommand(timeout) {
            service.readPeerStats(interfaceName = interfaceName)
        }
    }

    private suspend fun <D> executeCommand(
        timeout: Duration,
        commandCall: suspend () -> DaemonCommandResult<D>,
    ): DaemonCommandResult<D> {
        require(timeout.toMillis() > 0L) { "Timeout must be positive" }

        return try {
            withTimeout(timeout.toMillis()) {
                commandCall()
            }
        } catch (timeoutFailure: TimeoutCancellationException) {
            throw DaemonClientException.Timeout(
                timeout = timeout,
                cause = timeoutFailure,
            )
        }
    }

    override fun close() {
        if (ownsHttpClient) {
            httpClient.close()
        }
    }
}
