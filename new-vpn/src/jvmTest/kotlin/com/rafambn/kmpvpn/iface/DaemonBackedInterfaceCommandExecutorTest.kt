package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DAEMON_RPC_PATH
import com.rafambn.kmpvpn.daemon.protocol.DaemonErrorKind
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
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.net.ServerSocket
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DaemonBackedInterfaceCommandExecutorTest {

    @Test
    fun executorBridgesSuccessfulDaemonCallsAndParsesInformation() = runBlocking {
        val port = randomPort()
        val engine = startServer(
            port = port,
            service = object : StubDaemonProcessApi() {
                override suspend fun interfaceExists(interfaceName: String): CommandResult<InterfaceExistsResponse> {
                    return success(InterfaceExistsResponse(interfaceName = interfaceName, exists = true))
                }

                override suspend fun setInterfaceState(
                    interfaceName: String,
                    up: Boolean,
                ): CommandResult<SetInterfaceStateResponse> {
                    return success(SetInterfaceStateResponse(interfaceName = interfaceName, up = up))
                }

                override suspend fun applyMtu(
                    interfaceName: String,
                    mtu: Int,
                ): CommandResult<ApplyMtuResponse> {
                    return success(ApplyMtuResponse(interfaceName = interfaceName, mtu = mtu))
                }

                override suspend fun applyAddresses(
                    interfaceName: String,
                    addresses: List<String>,
                ): CommandResult<ApplyAddressesResponse> {
                    return success(ApplyAddressesResponse(interfaceName = interfaceName, addresses = addresses))
                }

                override suspend fun applyRoutes(
                    interfaceName: String,
                    routes: List<String>,
                ): CommandResult<ApplyRoutesResponse> {
                    return success(ApplyRoutesResponse(interfaceName = interfaceName, routes = routes))
                }

                override suspend fun applyDns(
                    interfaceName: String,
                    dnsDomainPool: Pair<List<String>, List<String>>,
                ): CommandResult<ApplyDnsResponse> {
                    return success(ApplyDnsResponse(interfaceName = interfaceName, dnsDomainPool = dnsDomainPool))
                }

                override suspend fun readInterfaceInformation(
                    interfaceName: String,
                ): CommandResult<ReadInterfaceInformationResponse> {
                    return success(
                        ReadInterfaceInformationResponse(
                            interfaceName = interfaceName,
                            dump = """
                                7: $interfaceName: <POINTOPOINT,MULTICAST,NOARP,UP,LOWER_UP> mtu 1420 qdisc fq_codel state UNKNOWN mode DEFAULT group default qlen 500
                                    inet 10.20.30.40/32 scope global $interfaceName
                            """.trimIndent(),
                        ),
                    )
                }
            },
        )

        try {
            val executor = DaemonBackedInterfaceCommandExecutor(
                host = "127.0.0.1",
                port = port,
                timeout = Duration.ofSeconds(5),
            )

            assertTrue(executor.interfaceExists("wg0"))
            executor.setInterfaceUp("wg0", true)
            executor.applyMtu("wg0", 1420)
            executor.applyAddresses("wg0", listOf("10.20.30.40/32"))
            executor.applyRoutes("wg0", listOf("0.0.0.0/0"))
            executor.applyDns("wg0", listOf("corp.local") to listOf("1.1.1.1"))

            val information = executor.readInformation("wg0")

            assertNotNull(information)
            assertTrue(information.isUp)
            assertEquals(1420, information.mtu)
            assertEquals(listOf("10.20.30.40/32"), information.addresses)
        } finally {
            engine.stop(100, 1_000)
        }
    }

    @Test
    fun executorMapsFailureResultsToIllegalStateException() = runBlocking {
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

        try {
            val executor = DaemonBackedInterfaceCommandExecutor(
                host = "127.0.0.1",
                port = port,
                timeout = Duration.ofSeconds(5),
            )

            val failure = assertFailsWith<IllegalStateException> {
                executor.applyMtu("wg0", 1280)
            }

            assertTrue(failure.message.orEmpty().contains("forbidden"))
        } finally {
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
        return CommandResult.success(data)
    }

    private fun <S> failure(message: String): CommandResult<S> {
        return CommandResult.failure(
            kind = DaemonErrorKind.VALIDATION_ERROR,
            message = message,
        )
    }

    private open inner class StubDaemonProcessApi : DaemonProcessApi {
        override suspend fun ping(): CommandResult<PingResponse> = failure("unsupported")

        override suspend fun interfaceExists(interfaceName: String): CommandResult<InterfaceExistsResponse> = failure("unsupported")

        override suspend fun setInterfaceState(
            interfaceName: String,
            up: Boolean,
        ): CommandResult<SetInterfaceStateResponse> = failure("unsupported")

        override suspend fun applyMtu(
            interfaceName: String,
            mtu: Int,
        ): CommandResult<ApplyMtuResponse> = failure("unsupported")

        override suspend fun applyAddresses(
            interfaceName: String,
            addresses: List<String>,
        ): CommandResult<ApplyAddressesResponse> = failure("unsupported")

        override suspend fun applyRoutes(
            interfaceName: String,
            routes: List<String>,
        ): CommandResult<ApplyRoutesResponse> = failure("unsupported")

        override suspend fun applyDns(
            interfaceName: String,
            dnsDomainPool: Pair<List<String>, List<String>>,
        ): CommandResult<ApplyDnsResponse> = failure("unsupported")

        override suspend fun readInterfaceInformation(
            interfaceName: String,
        ): CommandResult<ReadInterfaceInformationResponse> = failure("unsupported")

        override suspend fun deleteInterface(
            interfaceName: String,
        ): CommandResult<DeleteInterfaceResponse> = failure("unsupported")
    }

    private fun randomPort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
