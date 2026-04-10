package com.rafambn.kmpvpn.daemon.client

import com.rafambn.kmpvpn.daemon.protocol.DAEMON_RPC_PATH
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyAddressesResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyMtuResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyRoutesResponse
import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.DaemonErrorKind
import com.rafambn.kmpvpn.daemon.protocol.response.DeleteInterfaceResponse
import com.rafambn.kmpvpn.daemon.protocol.response.InterfaceExistsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
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
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DaemonClientSmokeTest {
    @BeforeTest
    fun setUp() {
        DaemonProcessClient.resetKoinForTests()
    }

    @AfterTest
    fun tearDown() {
        DaemonProcessClient.resetKoinForTests()
    }

    @Test
    fun krpcClientPerformsHandshakeAndRoundTrip() = runBlocking {
        val port = randomPort()
        val engine = startServer(
            port = port,
            service = object : StubDaemonProcessApi() {
                override suspend fun ping(): CommandResult<PingResponse> {
                    return success(PingResponse)
                }

                override suspend fun applyDns(
                    interfaceName: String,
                    dnsDomainPool: Pair<List<String>, List<String>>,
                ): CommandResult<ApplyDnsResponse> {
                    return success(
                        ApplyDnsResponse(
                            interfaceName = interfaceName,
                            dnsDomainPool = dnsDomainPool,
                        ),
                    )
                }
            },
        )

        val client = DaemonProcessClient.create(
            config = DaemonClientConfig(port = port),
        )

        try {
            val hello = client.handshake()
            assertTrue(hello.isSuccess)

            val response = client.applyDns(
                interfaceName = "wg0",
                dnsDomainPool = (listOf("corp.local") to listOf("1.1.1.1")),
            )
            val success = response as CommandResult.Success<ApplyDnsResponse>

            assertEquals("wg0", success.data.interfaceName)
            assertEquals(listOf("corp.local") to listOf("1.1.1.1"), success.data.dnsDomainPool)
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
                override suspend fun ping(): CommandResult<PingResponse> {
                    delay(300)
                    return success(PingResponse)
                }
            },
        )

        val client = DaemonProcessClient.create(
            config = DaemonClientConfig(
                port = port,
                timeout = Duration.ofMillis(50),
            ),
        )

        try {
            val failure = assertFailsWith<DaemonClientException.Timeout> {
                client.ping()
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
                override suspend fun applyMtu(
                    interfaceName: String,
                    mtu: Int,
                ): CommandResult<ApplyMtuResponse> {
                    return failure(message = "forbidden")
                }
            },
        )

        val client = DaemonProcessClient.create(
            config = DaemonClientConfig(port = port),
        )

        try {
            val result = client.applyMtu(interfaceName = "wg0", mtu = 1420)
            val failure = result as CommandResult.Failure

            assertEquals(DaemonErrorKind.VALIDATION_ERROR, failure.kind)
            assertEquals("forbidden", failure.message)
        } finally {
            client.close()
            engine.stop(100, 1_000)
        }
    }

    @Test
    fun globalBootstrapSupportsOverridesAndMultipleClientConfigs() = runBlocking {
        val stubService = object : StubDaemonProcessApi() {
            override suspend fun ping(): CommandResult<PingResponse> {
                return success(PingResponse)
            }
        }
        val overrideModule = module {
            single<DaemonClientServiceFactory> {
                DaemonClientServiceFactory { _, _ -> stubService }
            }
        }

        val first = DaemonProcessClient.create(
            config = DaemonClientConfig(port = 8787),
            overrideModules = listOf(overrideModule),
        )
        val second = DaemonProcessClient.create(
            config = DaemonClientConfig(port = 8788),
            overrideModules = listOf(overrideModule),
        )

        try {
            assertTrue(first.ping().isSuccess)
            assertTrue(second.ping().isSuccess)
        } finally {
            first.close()
            second.close()
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
                rpc(DAEMON_RPC_PATH) {
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

    private fun <S> success(data: S): CommandResult<S> {
        return CommandResult.success(data = data)
    }

    private fun <S> unsupported(command: String): CommandResult<S> {
        return CommandResult.failure(
            kind = DaemonErrorKind.UNKNOWN_COMMAND,
            message = "Unsupported command `$command`",
        )
    }

    private fun <S> failure(message: String): CommandResult<S> {
        return CommandResult.failure(
            kind = DaemonErrorKind.VALIDATION_ERROR,
            message = message,
        )
    }

    private open inner class StubDaemonProcessApi : DaemonProcessApi {
        override suspend fun ping(): CommandResult<PingResponse> = unsupported("PING")

        override suspend fun interfaceExists(
            interfaceName: String,
        ): CommandResult<InterfaceExistsResponse> = unsupported("INTERFACE_EXISTS")

        override suspend fun setInterfaceState(
            interfaceName: String,
            up: Boolean,
        ): CommandResult<SetInterfaceStateResponse> = unsupported("SET_INTERFACE_STATE")

        override suspend fun applyMtu(
            interfaceName: String,
            mtu: Int,
        ): CommandResult<ApplyMtuResponse> = unsupported("APPLY_MTU")

        override suspend fun applyAddresses(
            interfaceName: String,
            addresses: List<String>,
        ): CommandResult<ApplyAddressesResponse> = unsupported("APPLY_ADDRESSES")

        override suspend fun applyRoutes(
            interfaceName: String,
            routes: List<String>,
        ): CommandResult<ApplyRoutesResponse> = unsupported("APPLY_ROUTES")

        override suspend fun applyDns(
            interfaceName: String,
            dnsDomainPool: Pair<List<String>, List<String>>,
        ): CommandResult<ApplyDnsResponse> = unsupported("APPLY_DNS")

        override suspend fun readInterfaceInformation(
            interfaceName: String,
        ): CommandResult<ReadInterfaceInformationResponse> = unsupported("READ_INTERFACE_INFORMATION")

        override suspend fun deleteInterface(
            interfaceName: String,
        ): CommandResult<DeleteInterfaceResponse> = unsupported("DELETE_INTERFACE")
    }

    private fun randomPort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
