package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.ClientMessage
import com.rafambn.wgkotlin.daemon.proto.Daemon
import com.rafambn.wgkotlin.daemon.proto.Packet
import com.rafambn.wgkotlin.daemon.proto.ServerMessage
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.io.bytestring.ByteString
import kotlinx.rpc.grpc.client.GrpcClient
import kotlin.time.Duration.Companion.milliseconds

internal class DaemonProcessClient(
    private val service: Daemon,
    private val grpcClient: GrpcClient,
) : AutoCloseable {
    fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray> {
        return flow {
            val requestFlow = flow {
                emit(
                    ClientMessage {
                        payload = ClientMessage.Payload.Config(config)
                    },
                )
                outgoingPackets.collect { packet ->
                    emit(
                        ClientMessage {
                            payload = ClientMessage.Payload.OutgoingPacket(
                                Packet {
                                    data = packet.toProtoByteString()
                                },
                            )
                        },
                    )
                }
            }

            service.Session(requestFlow).collect { response ->
                when (val payload = response.payload) {
                    is ServerMessage.Payload.IncomingPacket -> {
                        emit(payload.value.data.toByteArray())
                    }

                    is ServerMessage.Payload.Error -> {
                        throw IllegalStateException(
                            "Daemon session failed (${payload.value.code}): ${payload.value.message}",
                        )
                    }

                    is ServerMessage.Payload.Started -> {
                        // startup ack; no packet payload
                    }

                    null,
                    -> {
                        throw IllegalStateException("Daemon returned empty session message payload")
                    }
                }
            }
        }
    }

    override fun close() {
        grpcClient.shutdownNow()
        runCatching {
            runBlocking {
                grpcClient.awaitTermination(CLOSE_TIMEOUT_MILLIS.milliseconds)
            }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS: Long = 5_000
    }
}

private fun ByteArray.toProtoByteString(): ByteString = ByteString(this)
