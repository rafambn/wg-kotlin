package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.client.DaemonProcessClient
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.PingResponse
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.server.websocket.WebSockets as ServerWebSockets

@OptIn(ExperimentalSerializationApi::class)
class VpnDataPlaneEndToEndTest {

    @Test
    fun clientSessionStreamsPacketsEndToEnd() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = port) {
            install(ServerWebSockets)
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
                        object : DaemonApi {
                            override suspend fun ping(): PingResponse = PingResponse
                            override fun startSession(config: TunSessionConfig, outgoingPackets: Flow<ByteArray>): Flow<ByteArray> = flowOf(byteArrayOf(4, 5, 6))
                        }
                    }
                }
            }
        }
        server.start(wait = false)

        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            installKrpc {
                serialization {
                    protobuf()
                }
            }
        }
        val rpcClient = httpClient.rpc(DaemonTransport.rpcUrl(host = "127.0.0.1", port = port))
        val client = DaemonProcessClient(service = rpcClient.withService<DaemonApi>(), resourceCloser = { httpClient.close() })
        try {
            val packets: List<ByteArray> = client.startSession(
                config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24")),
                outgoingPackets = flowOf(byteArrayOf(1, 2, 3)),
            ).toList()
            assertEquals("4, 5, 6", packets.single().joinToString())
        } finally {
            client.close()
            server.stop(100, 1_000)
        }
    }
}
