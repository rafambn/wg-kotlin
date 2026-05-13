package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapter
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.TunHandle
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class DaemonClientIntegrationTest {

    @Test
    fun daemonApiUsesInjectedAdapterForSessionStart() = runBlocking {
        val adapter = object : PlatformAdapter {
            override val platformId: String = "test"
            override val requiredBinaries: Set<CommandBinary> = emptySet()
            override suspend fun startSession(config: TunSessionConfig): TunHandle {
                return object : TunHandle {
                    override val interfaceName: String = config.interfaceName
                    private var emitted = false
                    override suspend fun readPacket(): ByteArray? = if (emitted) { kotlinx.coroutines.delay(10); null } else byteArrayOf(7, 8, 9).also { emitted = true }
                    override suspend fun writePacket(packet: ByteArray) {}
                    override fun close() {}
                }
            }
        }
        val api = DaemonImpl(adapter = adapter)

        val packet = api.startSession(
            config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24")),
            outgoingPackets = emptyFlow(),
        ).first()

        assertEquals("7, 8, 9", packet.joinToString())
    }

    @Test
    fun daemonServerAcceptsMatchingBearerToken() = runBlocking {
        val port = randomPort()
        val api = DaemonImpl(adapter = singlePacketAdapter())
        val server = createDaemonServer(
            host = "127.0.0.1",
            port = port,
            service = api,
        ).start(wait = false)
        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            installKrpc {
                serialization {
                    protobuf()
                }
            }
        }

        try {
            val rpcClient = httpClient.rpc(DaemonTransport.rpcUrl(host = "127.0.0.1", port = port))
            val packet = rpcClient.withService<DaemonApi>().startSession(
                config = TunSessionConfig(interfaceName = "wg0", addresses = listOf("10.0.0.1/24")),
                outgoingPackets = emptyFlow(),
            ).first()

            assertEquals("7, 8, 9", packet.joinToString())
        } finally {
            httpClient.close()
            server.stop(100, 1_000)
        }
    }

    private fun singlePacketAdapter(): PlatformAdapter {
        return object : PlatformAdapter {
            override val platformId: String = "test"
            override val requiredBinaries: Set<CommandBinary> = emptySet()

            override suspend fun startSession(config: TunSessionConfig): TunHandle {
                return object : TunHandle {
                    override val interfaceName: String = config.interfaceName
                    private var emitted = false
                    override suspend fun readPacket(): ByteArray? = if (emitted) { kotlinx.coroutines.delay(10); null } else byteArrayOf(7, 8, 9).also { emitted = true }
                    override suspend fun writePacket(packet: ByteArray) {}
                    override fun close() {}
                }
            }
        }
    }

    private fun randomPort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
