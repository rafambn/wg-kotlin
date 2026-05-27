package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.client.DaemonProcessClient
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
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

    override fun openSession(
        config: TunSessionConfig,
        pipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): AutoCloseable {
        println("[CLIENT] openSession called for ${config.interfaceName}")
        val client = createClient()
        println("[CLIENT] KRPC client created, connecting to $host:$port")
        val outgoingPackets = Channel<ByteArray>(capacity = DuplexChannelPipe.DEFAULT_CAPACITY)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineName("kmpvpn-packet-rpc-bridge"),
        )
        val bridgeReady = CompletableDeferred<Unit>()
        val bridgeTerminated = CompletableDeferred<Throwable>()
        val startupConfirmed = AtomicBoolean(false)

        fun reportTermination(throwable: Throwable) {
            println("[CLIENT] reportTermination: ${throwable::class.simpleName}: ${throwable.message}")
            if (!bridgeTerminated.isCompleted) {
                bridgeTerminated.complete(throwable)
            }
            if (startupConfirmed.get()) {
                onFailure(throwable)
            }
        }

        val sessionCollectorJob = scope.launch {
            println("[CLIENT] sessionCollectorJob started, calling client.startSession...")
            try {
                val flow = client.startSession(
                    config = config,
                    outgoingPackets = outgoingPackets.receiveAsFlow(),
                )
                println("[CLIENT] client.startSession returned flow")
                bridgeReady.complete(Unit)
                println("[CLIENT] bridgeReady completed, collecting flow...")
                flow.collect { packet ->
                    pipe.send(packet)
                }
                println("[CLIENT] flow collection completed")
                reportTermination(
                    IllegalStateException("Packet bridge closed by daemon for `${config.interfaceName}`: stream completed"),
                )
            } catch (_: CancellationException) {
                println("[CLIENT] sessionCollectorJob cancelled")
            } catch (throwable: Throwable) {
                println("[CLIENT] sessionCollectorJob error: ${throwable::class.simpleName}: ${throwable.message}")
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
            println("[CLIENT] waiting for bridgeReady...")
            runBlocking {
                withTimeout(CONNECT_TIMEOUT_MILLIS) {
                    bridgeReady.await()
                }
                println("[CLIENT] bridgeReady received")
                val startupFailure = withTimeoutOrNull(STARTUP_STABILITY_MILLIS) {
                    bridgeTerminated.await()
                }
                if (startupFailure != null) {
                    println("[CLIENT] startupFailure detected during stability window")
                    throw startupFailure
                }
                println("[CLIENT] startup stability check passed")
            }
            startupConfirmed.set(true)
            println("[CLIENT] startupConfirmed=true")
            if (bridgeTerminated.isCompleted) {
                println("[CLIENT] bridgeTerminated already completed, reporting failure")
                onFailure(runBlocking { bridgeTerminated.await() })
            }
        } catch (throwable: Throwable) {
            println("[CLIENT] openSession failed: ${throwable::class.simpleName}: ${throwable.message}")
            scope.cancel("DaemonBackedInterfaceCommandExecutor packet bridge failed to connect")
            outgoingPackets.close()
            runCatching { client.close() }
            throw IllegalStateException(
                "Failed to open session for `${config.interfaceName}`: ${throwable.message ?: "unknown"}",
                throwable,
            )
        }

        println("[CLIENT] openSession returning AutoCloseable bridge")
        return AutoCloseable {
            outgoingPackets.close()
            scope.cancel("DaemonBackedInterfaceCommandExecutor packet bridge closed")
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
        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            installKrpc {
                serialization {
                    protobuf()
                }
            }
        }
        val rpcClient = httpClient.rpc(DaemonTransport.rpcUrl(host = host, port = port))
        return DaemonProcessClient(
            service = rpcClient.withService<DaemonApi>(),
            resourceCloser = { httpClient.close() },
        )
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS: Long = 5_000
        const val STARTUP_STABILITY_MILLIS: Long = 200
        const val CLOSE_TIMEOUT_MILLIS: Long = 5_000
    }
}
