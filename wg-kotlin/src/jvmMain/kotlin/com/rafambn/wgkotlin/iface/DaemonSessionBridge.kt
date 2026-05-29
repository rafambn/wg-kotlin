package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.ClientMessage
import com.rafambn.wgkotlin.daemon.proto.Daemon
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.Packet
import com.rafambn.wgkotlin.daemon.proto.ServerMessage
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.toAddressString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.withService
import kotlin.time.Duration.Companion.milliseconds

class DaemonSessionBridge(
    private val host: Ip.Value,
    private val port: Int,
) : SessionBridge {

    override fun openSession(
        config: TunSessionConfig,
        pipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): AutoCloseable {
        val grpcClient = GrpcClient(host.toAddressString(), port) {
            credentials = plaintext()
        }
        val service = grpcClient.withService<Daemon>()
        val outgoingPackets = Channel<ByteArray>(capacity = DuplexChannelPipe.DEFAULT_CAPACITY)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("wg-kotlin-packet-rpc-bridge"),
        )

        scope.launch {
            try {
                val outgoingFlow = flow {
                    emit(ClientMessage { payload = ClientMessage.Payload.Config(config) })
                    outgoingPackets.receiveAsFlow().collect { packet ->
                        emit(
                            ClientMessage {
                                payload = ClientMessage.Payload.OutgoingPacket(
                                    Packet { data = packet.toProtoByteString() },
                                )
                            },
                        )
                    }
                }

                service.Session(outgoingFlow).collect { response ->
                    when (val payload = response.payload) {
                        is ServerMessage.Payload.IncomingPacket -> pipe.send(payload.value.data.toByteArray())
                        is ServerMessage.Payload.Error -> throw IllegalStateException("Daemon session failed (${payload.value.code}): ${payload.value.message}")
                        is ServerMessage.Payload.Started -> { }
                        null -> throw IllegalStateException("Daemon returned empty session message payload")
                    }
                }
            } catch (_: CancellationException) {
            } catch (throwable: Throwable) {
                onFailure(throwable)
            }
        }

        scope.launch {
            try {
                while (true) {
                    outgoingPackets.send(pipe.receive())
                }
            } catch (_: CancellationException) {
            } catch (throwable: Throwable) {
                onFailure(throwable)
            }
        }

        return AutoCloseable {
            outgoingPackets.close()
            scope.cancel()
            grpcClient.shutdownNow()
            runCatching { runBlocking { grpcClient.awaitTermination(CLOSE_TIMEOUT_MILLIS.milliseconds) } }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS: Long = 5_000
    }
}

private fun ByteArray.toProtoByteString(): ByteString = ByteString(this)
