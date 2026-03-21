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
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.DaemonErrorKind
import com.rafambn.kmpvpn.daemon.protocol.response.DeleteInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadPeerStatsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.SetInterfaceStateResponse
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.net.ServerSocket
import java.time.Duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonClientSmokeTest {

    @Test
    fun krpcClientPerformsHandshakeAndRoundTrip() = runBlocking {
        val port = randomPort()
        val engine = startServer(
            port = port,
            service = object : StubDaemonProcessApi() {
                override suspend fun ping(nonce: String): DaemonCommandResult<PingResponse> {
                    return success(PingResponse(helloToken = DAEMON_HELLO_TOKEN))
                }

                override suspend fun applyDns(
                    interfaceName: String,
                    dnsServers: List<String>,
                ): DaemonCommandResult<ApplyDnsResponse> {
                    return success(
                        ApplyDnsResponse(
                            interfaceName = interfaceName,
                            dnsServers = dnsServers,
                        ),
                    )
                }
            },
        )

        val client = DaemonProcessClient(
            port = port,
        )

        try {
            val hello = client.handshake()
            val helloSuccess = hello as DaemonCommandResult.Success<PingResponse>
            assertEquals(DAEMON_HELLO_TOKEN, helloSuccess.data.helloToken)

            val response = client.applyDns(
                interfaceName = "wg0",
                dnsServers = listOf("1.1.1.1"),
            )
            val success = response as DaemonCommandResult.Success<ApplyDnsResponse>

            assertEquals("wg0", success.data.interfaceName)
            assertEquals(listOf("1.1.1.1"), success.data.dnsServers)
        } finally {
            client.close()
            engine.stop(100, 1_000)
        }
    }

    @Test
    fun requestTimeoutIsSurfaced() = runBlocking {
        val port = randomPort()
        val engine = startServer(
            port = port,
            service = object : StubDaemonProcessApi() {
                override suspend fun ping(nonce: String): DaemonCommandResult<PingResponse> {
                    delay(300)
                    return success(PingResponse(helloToken = DAEMON_HELLO_TOKEN))
                }
            },
        )

        val client = DaemonProcessClient(
            port = port,
            timeout = Duration.ofMillis(50),
        )

        try {
            val failure = assertFailsWith<DaemonClientException.Timeout> {
                client.ping(nonce = "timeout")
            }
            assertEquals(50L, failure.timeout.toMillis())
        } finally {
            client.close()
            engine.stop(100, 1_000)
        }
    }

    @Test
    fun remoteFailureIsReturnedAsFailureResult() = runBlocking {
        val port = randomPort()
        val engine = startServer(
            port = port,
            service = object : StubDaemonProcessApi() {
                override suspend fun createInterface(
                    interfaceName: String,
                ): DaemonCommandResult<CreateInterfaceResponse> {
                    return failure(message = "forbidden")
                }
            },
        )

        val client = DaemonProcessClient(
            port = port,
        )

        try {
            val result = client.createInterface(interfaceName = "wg0")
            val failure = result as DaemonCommandResult.Failure

            assertEquals(DaemonErrorKind.VALIDATION_ERROR, failure.kind)
            assertEquals("forbidden", failure.message)
        } finally {
            client.close()
            engine.stop(100, 1_000)
        }
    }

    private fun startServer(
        port: Int,
        service: DaemonProcessApi,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = port, module = {
            install(WebSockets)
            install(Krpc) {
                serialization {
                    json()
                }
            }

            routing {
                rpc("/services") {
                    rpcConfig {
                        serialization {
                            json()
                        }
                    }
                    registerService<DaemonProcessApi> {
                        service
                    }
                }
            }
        })
        engine.start(wait = false)
        return engine
    }

    private fun <S> success(data: S): DaemonCommandResult<S> {
        return DaemonCommandResult.success(data = data)
    }

    private fun <S> unsupported(command: String): DaemonCommandResult<S> {
        return DaemonCommandResult.failure(
            kind = DaemonErrorKind.UNKNOWN_COMMAND,
            message = "Unsupported command `$command`",
        )
    }

    private fun <S> failure(message: String): DaemonCommandResult<S> {
        return DaemonCommandResult.failure(
            kind = DaemonErrorKind.VALIDATION_ERROR,
            message = message,
        )
    }

    private open inner class StubDaemonProcessApi : DaemonProcessApi {
        override suspend fun ping(nonce: String): DaemonCommandResult<PingResponse> = unsupported("PING")

        override suspend fun interfaceExists(
            interfaceName: String,
        ): DaemonCommandResult<InterfaceExistsResponse> = unsupported("INTERFACE_EXISTS")

        override suspend fun createInterface(
            interfaceName: String,
        ): DaemonCommandResult<CreateInterfaceResponse> = unsupported("CREATE_INTERFACE")

        override suspend fun deleteInterface(
            interfaceName: String,
        ): DaemonCommandResult<DeleteInterfaceResponse> = unsupported("DELETE_INTERFACE")

        override suspend fun setInterfaceState(
            interfaceName: String,
            up: Boolean,
        ): DaemonCommandResult<SetInterfaceStateResponse> = unsupported("SET_INTERFACE_STATE")

        override suspend fun applyMtu(
            interfaceName: String,
            mtu: Int?,
        ): DaemonCommandResult<ApplyMtuResponse> = unsupported("APPLY_MTU")

        override suspend fun applyAddresses(
            interfaceName: String,
            addresses: List<String>,
        ): DaemonCommandResult<ApplyAddressesResponse> = unsupported("APPLY_ADDRESSES")

        override suspend fun applyRoutes(
            interfaceName: String,
            routes: List<String>,
            table: String?,
        ): DaemonCommandResult<ApplyRoutesResponse> = unsupported("APPLY_ROUTES")

        override suspend fun applyDns(
            interfaceName: String,
            dnsServers: List<String>,
        ): DaemonCommandResult<ApplyDnsResponse> = unsupported("APPLY_DNS")

        override suspend fun applyPeerConfiguration(
            request: ApplyPeerConfigurationRequest,
        ): DaemonCommandResult<ApplyPeerConfigurationResponse> = unsupported("APPLY_PEER_CONFIGURATION")

        override suspend fun readInterfaceInformation(
            interfaceName: String,
        ): DaemonCommandResult<ReadInterfaceInformationResponse> = unsupported("READ_INTERFACE_INFORMATION")

        override suspend fun readPeerStats(
            interfaceName: String,
        ): DaemonCommandResult<ReadPeerStatsResponse> = unsupported("READ_PEER_STATS")
    }

    private fun randomPort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
