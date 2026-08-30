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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.bytestring.ByteString
import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.withService
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DaemonSessionBridge(
    private val host: Ip,
    private val port: Int,
) : SessionBridge {

    override suspend fun openSession(
        config: TunSessionConfig,
        pipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): AutoCloseable {
        val grpcClient = GrpcClient(host.toAddressString(), port) {
            credentials = plaintext()
            keepAlive {
                time = 30.seconds
                timeout = 10.seconds
            }
        }
        val service = grpcClient.withService<Daemon>()
        val outgoingPackets = Channel<ByteArray>(capacity = DuplexChannelPipe.DEFAULT_CAPACITY)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("wg-kotlin-packet-rpc-bridge"),
        )
        val readiness = CompletableDeferred<Unit>()

        fun reportFailure(throwable: Throwable) {
            if (!readiness.completeExceptionally(throwable)) {
                onFailure(throwable)
            }
        }

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
                        is ServerMessage.Payload.Started -> readiness.complete(Unit)
                        null -> throw IllegalStateException("Daemon returned empty session message payload")
                    }
                }
                throw IllegalStateException("Daemon session ended")
            } catch (throwable: CancellationException) {
                if (scope.isActive) reportFailure(throwable)
            } catch (throwable: Throwable) {
                reportFailure(throwable)
            }
        }

        scope.launch {
            try {
                while (true) {
                    outgoingPackets.send(pipe.receive())
                }
            } catch (throwable: CancellationException) {
                if (scope.isActive) reportFailure(throwable)
            } catch (throwable: Throwable) {
                reportFailure(throwable)
            }
        }

        val session = AutoCloseable {
            outgoingPackets.close()
            scope.cancel()
            grpcClient.shutdownNow()
            runCatching { runBlocking { grpcClient.awaitTermination(CLOSE_TIMEOUT_MILLIS.milliseconds) } }
        }

        return try {
            withTimeout(START_TIMEOUT_MILLIS.milliseconds) { readiness.await() }
            session
        } catch (throwable: Throwable) {
            session.close()
            throw throwable
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS: Long = 5_000
        const val START_TIMEOUT_MILLIS: Long = 30_000
    }
}

private fun ByteArray.toProtoByteString(): ByteString = ByteString(this)
