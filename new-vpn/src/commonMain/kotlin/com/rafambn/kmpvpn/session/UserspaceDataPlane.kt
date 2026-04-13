package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.session.io.KtorDatagramUdpPort
import com.rafambn.kmpvpn.session.io.UdpPort
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val DEFAULT_RECEIVE_TIMEOUT_MILLIS: Long = 50L
private const val DEFAULT_IDLE_DELAY_MILLIS: Long = 10L
private const val DEFAULT_PERIODIC_INTERVAL_MILLIS: Long = 100L

internal class UserspaceDataPlane(
    configuration: VpnConfiguration,
    listenPort: Int,
    private val pollInboundPacketOnce: suspend (UdpPort) -> Boolean,
    private val pollOutboundPacketOnce: suspend (UdpPort) -> Boolean,
    private val runPeriodicWorkOnce: suspend (UdpPort) -> Boolean,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val idleDelayMillis: Long = DEFAULT_IDLE_DELAY_MILLIS
    private val periodicIntervalDelayMillis = DEFAULT_PERIODIC_INTERVAL_MILLIS
    private val coroutineLabel = "kmpvpn-data-plane-${configuration.interfaceName}"

    private val running = MutableStateFlow(true)
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineName(coroutineLabel),
    )
    private val inboundJob = scope.launch(CoroutineName("$coroutineLabel-inbound")) {
        runInboundLoop()
    }
    private val outboundJob = scope.launch(CoroutineName("$coroutineLabel-outbound")) {
        runOutboundLoop()
    }
    private val periodicJob = scope.launch(CoroutineName("$coroutineLabel-periodic")) {
        runPeriodicLoop()
    }

    private val selectorManager = SelectorManager(scope.coroutineContext)
    private val socket: BoundDatagramSocket = runBlocking {
        aSocket(selectorManager).udp().bind(
            // TODO: Support IPv6/dual-stack listening instead of binding IPv4 wildcard only.
            InetSocketAddress("0.0.0.0", listenPort),
        )
    }
    private val udpPort = KtorDatagramUdpPort(
        socket = socket,
        receiveTimeoutMillis = DEFAULT_RECEIVE_TIMEOUT_MILLIS,
    )

    fun isRunning(): Boolean {
        return running.value
    }

    override fun close() {
        runBlocking {
            if (!running.value) {
                return@runBlocking
            }

            running.value = false
            socket.close()
            selectorManager.close()
            inboundJob.cancelAndJoin()
            outboundJob.cancelAndJoin()
            periodicJob.cancelAndJoin()
            scope.cancel()
        }
    }

    private suspend fun runInboundLoop() {
        runWorkerLoop {
            pollInboundPacketOnce(udpPort)
        }
    }

    private suspend fun runOutboundLoop() {
        runWorkerLoop {
            pollOutboundPacketOnce(udpPort)
        }
    }

    private suspend fun runPeriodicLoop() {
        runWorkerLoop(
            initialDelayMillis = periodicIntervalDelayMillis,
            idleDelayMillis = periodicIntervalDelayMillis,
            delayAfterEachIteration = true,
            work = {
                runPeriodicWorkOnce(udpPort)
            },
        )
    }

    private suspend fun runWorkerLoop(
        initialDelayMillis: Long = 0L,
        idleDelayMillis: Long = this.idleDelayMillis,
        delayAfterEachIteration: Boolean = false,
        work: suspend () -> Boolean,
    ) {
        try {
            delay(initialDelayMillis.coerceAtLeast(0L))
            while (running.value) {
                val didWork = work()
                if (delayAfterEachIteration || !didWork) {
                    delay(idleDelayMillis.coerceAtLeast(0L))
                }
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            handleWorkerFailure(throwable)
        }
    }

    private suspend fun handleWorkerFailure(throwable: Throwable) {
        if (!running.compareAndSet(expect = true, update = false)) {
            return
        }

        withContext(Dispatchers.IO) {
            socket.close()
            selectorManager.close()
        }
        onFailure(throwable)
        scope.cancel("userspace data plane worker failed", throwable)
    }
}
