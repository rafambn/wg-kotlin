package com.rafambn.wgkotlin.daemon.client

import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.DnsConfig
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
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
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class DaemonClientSmokeTest {
    @Test
    fun krpcClientPerformsStartSessionRoundTrip() = runBlocking {
        val port = randomPort()
        val engine = startServer(
            port = port,
            service = object : StubDaemonApi() {
                override fun startSession(config: TunSessionConfig, outgoingPackets: Flow<ByteArray>): Flow<ByteArray> {
                    assertEquals("utun55", config.interfaceName)
                    assertEquals(listOf("corp.local"), config.dns.searchDomains)
                    return flowOf(byteArrayOf(1, 2, 3))
                }
            },
        )

        val client = DaemonProcessClient.create(config = DaemonClientConfig(port = port, token = "test-token"))

        try {
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
    fun globalBootstrapSupportsOverridesAndMultipleClientConfigs() = runBlocking {
        val stubService = object : StubDaemonApi() {}
        val overrideModule = module {
            factory<DaemonApi> { stubService }
        }

        val first = DaemonProcessClient.create(
            config = DaemonClientConfig(port = 8787, token = "test-token"),
            overrideModules = listOf(overrideModule),
        )
        val second = DaemonProcessClient.create(
            config = DaemonClientConfig(port = 8788, token = "test-token"),
            overrideModules = listOf(overrideModule),
        )

        try {
            assertEquals(emptyFlow<ByteArray>().toList(), first.startSession(
                config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24")),
                outgoingPackets = emptyFlow(),
            ).toList())
            assertEquals(emptyFlow<ByteArray>().toList(), second.startSession(
                config = TunSessionConfig(interfaceName = "wg1", addresses = listOf("10.0.0.2/24")),
                outgoingPackets = emptyFlow(),
            ).toList())
        } finally {
            first.close()
            second.close()
        }
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
        override fun startSession(config: TunSessionConfig, outgoingPackets: Flow<ByteArray>): Flow<ByteArray> = emptyFlow()
    }

    private fun randomPort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
