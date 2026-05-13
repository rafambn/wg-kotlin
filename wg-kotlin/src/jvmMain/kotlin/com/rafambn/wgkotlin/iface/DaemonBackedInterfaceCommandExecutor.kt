package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.client.DaemonProcessClient
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
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
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf
import kotlinx.rpc.withService
import kotlinx.serialization.ExperimentalSerializationApi
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalSerializationApi::class)
class DaemonBackedInterfaceCommandExecutor(
    private val host: String,
    private val port: Int,
) : InterfaceCommandExecutor {
    private val client: DaemonProcessClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            installKrpc {
                serialization {
                    protobuf()
                }
            }
        }
        val rpcClient = httpClient.rpc(DaemonTransport.rpcUrl(host = host, port = port))
        DaemonProcessClient(
            service = rpcClient.withService<DaemonApi>(),
            resourceCloser = { httpClient.close() },
        )
    }

    override fun openSession(
        config: TunSessionConfig,
        pipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): AutoCloseable {
        val outgoingPackets = Channel<ByteArray>(capacity = DuplexChannelPipe.DEFAULT_CAPACITY)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("kmpvpn-packet-rpc-bridge"),
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
                bridgeReady.complete(Unit)
                client.startSession(
                    config = config,
                    outgoingPackets = outgoingPackets.receiveAsFlow(),
                ).collect { packet ->
                    pipe.send(packet)
                }
                reportTermination(
                    IllegalStateException("Packet bridge closed by daemon for `${config.interfaceName}`: stream completed"),
                )
            } catch (_: CancellationException) {
                // shutdown path
            } catch (throwable: Throwable) {
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
            scope.cancel("DaemonBackedInterfaceCommandExecutor packet bridge failed to connect")
            outgoingPackets.close()
            throw IllegalStateException(
                "Failed to open session for `${config.interfaceName}`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        return AutoCloseable {
            outgoingPackets.close()
            scope.cancel("DaemonBackedInterfaceCommandExecutor packet bridge closed")
            runBlocking {
                withTimeoutOrNull(CLOSE_TIMEOUT_MILLIS) {
                    sessionCollectorJob.join()
                    outgoingPumpJob.join()
                }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS: Long = 5_000
        const val STARTUP_STABILITY_MILLIS: Long = 200
        const val CLOSE_TIMEOUT_MILLIS: Long = 5_000
    }
}
