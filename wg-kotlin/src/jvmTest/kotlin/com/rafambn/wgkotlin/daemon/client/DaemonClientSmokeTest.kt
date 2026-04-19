package com.rafambn.wgkotlin.daemon.client

import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.DnsConfig
import com.rafambn.wgkotlin.daemon.protocol.PingResponse
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf
import kotlinx.serialization.ExperimentalSerializationApi
import org.koin.dsl.module
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalSerializationApi::class)
class DaemonClientSmokeTest {
    @Test
    fun krpcClientPerformsHandshakeAndStartSessionRoundTrip() = runBlocking {
        val port = randomPort()
        val engine = startServer(
            port = port,
            service = object : StubDaemonApi() {
                override suspend fun ping(): PingResponse = PingResponse

                override fun startSession(config: TunSessionConfig, outgoingPackets: Flow<ByteArray>): Flow<ByteArray> {
                    assertEquals("utun55", config.interfaceName)
                    assertEquals(listOf("corp.local"), config.dns.searchDomains)
                    return flowOf(byteArrayOf(1, 2, 3))
                }
            },
        )

        val client = DaemonProcessClient.create(config = DaemonClientConfig(port = port))

        try {
            assertEquals(PingResponse, client.handshake())

            val packets: List<ByteArray> = client.startSession(
                config = TunSessionConfig(
                    interfaceName = "utun55",
                    dns = DnsConfig(searchDomains = listOf("corp.local"), servers = listOf("1.1.1.1")),
                ),
                outgoingPackets = flowOf(byteArrayOf(9)),
            ).toList()

            assertEquals(1, packets.size)
            assertEquals("1, 2, 3", packets.single().joinToString())
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
            service = object : StubDaemonApi() {
                override suspend fun ping(): PingResponse {
                    delay(300)
                    return PingResponse
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
    fun globalBootstrapSupportsOverridesAndMultipleClientConfigs() = runBlocking {
        val stubService = object : StubDaemonApi() {
            override suspend fun ping(): PingResponse = PingResponse
        }
        val overrideModule = module {
            factory<DaemonApi> { stubService }
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
            assertEquals(PingResponse, first.ping())
            assertEquals(PingResponse, second.ping())
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun handshakePropagatesRemoteException() = runBlocking {
        val client = DaemonProcessClient(
            service = object : StubDaemonApi() {
                override suspend fun ping(): PingResponse {
                    throw IllegalStateException("nope")
                }
            },
        )

        val failure = assertFailsWith<IllegalStateException> {
            client.handshake()
        }

        assertTrue(failure.message.orEmpty().contains("nope"))
    }

    private fun startServer(
        port: Int,
        service: DaemonApi,
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        val engine = embeddedServer(Netty, host = "127.0.0.1", port = port, module = {
            install(WebSockets)
            install(Krpc) {
                serialization {
                    protobuf()
                }
            }

            routing {
                rpc(DaemonTransport.DAEMON_RPC_PATH) {
                    rpcConfig {
                        serialization {
                            protobuf()
                        }
                    }
                    registerService<DaemonApi> {
                        service
                    }
                }
            }
        })
        engine.start(wait = false)
        return engine
    }

    private open class StubDaemonApi : DaemonApi {
        override suspend fun ping(): PingResponse {
            return PingResponse
        }

        override fun startSession(config: TunSessionConfig, outgoingPackets: Flow<ByteArray>): Flow<ByteArray> = emptyFlow()
    }

    private fun randomPort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
