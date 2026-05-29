package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.Daemon
import com.rafambn.wgkotlin.daemon.proto.IpAddr
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.rpc.grpc.client.GrpcClient
import kotlinx.rpc.withService
import java.util.concurrent.atomic.AtomicBoolean

class DaemonSessionBridge(
    private val host: IpAddr.Ip,
    private val port: Int,
) : InterfaceCommandExecutor {

    override fun openSession(
        config: TunSessionConfig,
        pipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): AutoCloseable {
        val client = createClient()
        val outgoingPackets = Channel<ByteArray>(capacity = DuplexChannelPipe.DEFAULT_CAPACITY)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("wg-kotlin-packet-rpc-bridge"),
        )
        val bridgeReady = CompletableDeferred<Unit>()
        val bridgeTerminated = CompletableDeferred<Throwable>()
        val startupConfirmed = AtomicBoolean(false)

        fun reportTermination(throwable: Throwable) {
            if (!bridgeTerminated.isCompleted) {
                bridgeTerminated.complete(throwable)
            }
            if (startupConfirmed.get()) {
                onFailure(throwable)
            }
        }

        val sessionCollectorJob = scope.launch {
            try {
                val flow = client.startSession(
                    config = config,
                    outgoingPackets = outgoingPackets.receiveAsFlow(),
                )
                bridgeReady.complete(Unit)
                flow.collect { packet ->
                    pipe.send(packet)
                }
                reportTermination(
                    IllegalStateException("Packet bridge closed by daemon for `${config.interfaceName}`: stream completed"),
                )
            } catch (throwable: Throwable) {
                throwable.printStackTrace()
                if (!bridgeReady.isCompleted) {
                    bridgeReady.completeExceptionally(throwable)
                }
                reportTermination(throwable)
            }
        }

        val outgoingPumpJob = scope.launch {
            try {
                while (true) {
                    outgoingPackets.send(pipe.receive())
                }
            } catch (_: CancellationException) {
                // shutdown path
            } catch (throwable: Throwable) {
                reportTermination(throwable)
            }
        }

        try {
            runBlocking {
                withTimeout(CONNECT_TIMEOUT_MILLIS) {
                    bridgeReady.await()
                }
                val startupFailure = withTimeoutOrNull(STARTUP_STABILITY_MILLIS) {
                    bridgeTerminated.await()
                }
                if (startupFailure != null) {
                    throw startupFailure
                }
            }
            startupConfirmed.set(true)
            if (bridgeTerminated.isCompleted) {
                onFailure(runBlocking { bridgeTerminated.await() })
            }
        } catch (throwable: Throwable) {
            scope.cancel("DaemonSessionBridge packet bridge failed to connect")
            outgoingPackets.close()
            runCatching { client.close() }
            throw IllegalStateException(
                "Failed to open session for `${config.interfaceName}`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        return AutoCloseable {
            outgoingPackets.close()
            scope.cancel("DaemonSessionBridge packet bridge closed")
            runBlocking {
                withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) {
                    sessionCollectorJob.join()
                    outgoingPumpJob.join()
                }
            }
            client.close()
        }
    }

    private fun createClient(): DaemonProcessClient {
        val grpcClient = GrpcClient(host.toAddressString(), port) {
            credentials = plaintext()
        }
        return DaemonProcessClient(
            service = grpcClient.withService<Daemon>(),
            grpcClient = grpcClient,
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS: Long = 5_000
        const val STARTUP_STABILITY_MILLIS: Long = 200
        const val CLOSE_TIMEOUT_MILLIS: Long = 5_000
    }
}
