package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.iface.VpnPeerStats
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val DEFAULT_RECEIVE_TIMEOUT_MILLIS: Long = 50L
private const val DEFAULT_IDLE_DELAY_MILLIS: Long = 10L
private const val DEFAULT_PERIODIC_INTERVAL_MILLIS: Long = 100L

internal class UserspaceDataPlane(
    configuration: VpnConfiguration,
    private val onFailure: (Throwable) -> Unit,
    listenPort: Int,
    receiveTimeoutMillis: Long = DEFAULT_RECEIVE_TIMEOUT_MILLIS,
    private val idleDelayMillis: Long = DEFAULT_IDLE_DELAY_MILLIS,
    periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS,
    private val pollDataPlaneOnce: suspend (UdpPort, () -> Boolean) -> Boolean,
    private val peerStatsProvider: () -> List<VpnPeerStats>,
) : AutoCloseable {
    private val running = MutableStateFlow(true)
    private val peerStatsSnapshot = MutableStateFlow<List<VpnPeerStats>>(emptyList())
    private val scope = CoroutineScope(
        SupervisorJob() +
            Dispatchers.Default +
            CoroutineName("kmpvpn-data-plane-${configuration.interfaceName}"),
    )
    private val selectorManager = SelectorManager(scope.coroutineContext)
    private val socket: BoundDatagramSocket = runBlocking {
        aSocket(selectorManager).udp().bind(
            InetSocketAddress("0.0.0.0", listenPort),
        )
    }
    private val periodicTicker = FixedIntervalTicker(periodicIntervalMillis)
    private val udpPort = KtorDatagramUdpPort(
        socket = socket,
        receiveTimeoutMillis = receiveTimeoutMillis,
    )
    private val dataPlaneJob = scope.launch {
        runDataPlaneLoop()
    }

    init {
        peerStatsSnapshot.value = peerStatsProvider()
    }

    fun isRunning(): Boolean {
        return running.value
    }

    fun peerStats(): List<VpnPeerStats> {
        return peerStatsSnapshot.value
    }

    override fun close() {
        runBlocking {
            if (!running.value) {
                return@runBlocking
            }

            running.value = false
            socket.close()
            selectorManager.close()
            dataPlaneJob.cancelAndJoin()
            scope.cancel()
            peerStatsSnapshot.value = peerStatsProvider()
        }
    }

    private suspend fun runDataPlaneLoop() {
        try {
            while (running.value) {
                val didWork = pollDataPlaneOnce(udpPort, periodicTicker::shouldTick)
                peerStatsSnapshot.value = peerStatsProvider()
                if (!didWork) {
                    delay(idleDelayMillis.coerceAtLeast(0L))
                }
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            if (running.value) {
                running.value = false
                onFailure(throwable)
            }
        } finally {
            peerStatsSnapshot.value = peerStatsProvider()
            running.value = false
        }
    }

    private class FixedIntervalTicker(
        intervalMillis: Long,
    ) {
        private val interval: Duration = intervalMillis.coerceAtLeast(1L).milliseconds
        private var nextTick = TimeSource.Monotonic.markNow() + interval

        fun shouldTick(): Boolean {
            if (nextTick.hasNotPassedNow()) {
                return false
            }
            nextTick = TimeSource.Monotonic.markNow() + interval
            return true
        }
    }
}
