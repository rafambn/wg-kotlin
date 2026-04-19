package com.rafambn.wgkotlin.network

import com.rafambn.wgkotlin.network.io.KtorDatagramUdpPort
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpPort
import com.rafambn.wgkotlin.util.DuplexChannelPipe
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class SocketManagerImpl(
    private val networkPipe: DuplexChannelPipe<UdpDatagram>,
) : SocketManager {

    private var running = false
    private var socket: BoundDatagramSocket? = null
    private var selectorManager: SelectorManager? = null
    private var scope: CoroutineScope? = null

    override fun start(listenPort: Int, onFailure: (Throwable) -> Unit) {
        stop()

        val coroutineLabel = "kmpvpn-socket"
        val newScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName(coroutineLabel),
        )

        val newSelectorManager = SelectorManager(newScope.coroutineContext)
        val newSocket = runBlocking {
            // TODO: Support IPv6/dual-stack listening.
            aSocket(newSelectorManager).udp().bind(InetSocketAddress("0.0.0.0", listenPort))
        }
        val udpPort = KtorDatagramUdpPort(
            socket = newSocket,
            receiveTimeoutMillis = DEFAULT_RECEIVE_TIMEOUT_MILLIS,
        )

        selectorManager = newSelectorManager
        socket = newSocket
        scope = newScope
        running = true

        newScope.launch(CoroutineName("$coroutineLabel-receive")) {
            runReceiveLoop(udpPort, onFailure)
        }
        newScope.launch(CoroutineName("$coroutineLabel-send")) {
            runSendLoop(udpPort, onFailure)
        }
    }

    override fun stop() {
        if (!running) {
            return
        }
        running = false
        socket?.close()
        selectorManager?.close()
        scope?.cancel("SocketManager stopped")
        socket = null
        selectorManager = null
        scope = null
    }

    override fun isRunning(): Boolean = running

    private suspend fun runReceiveLoop(udpPort: UdpPort, onFailure: (Throwable) -> Unit) {
        try {
            while (true) {
                val datagram = udpPort.receiveDatagram() ?: continue
                networkPipe.send(datagram)
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private suspend fun runSendLoop(udpPort: UdpPort, onFailure: (Throwable) -> Unit) {
        try {
            while (true) {
                val datagram = networkPipe.receive()
                udpPort.sendDatagram(datagram)
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private companion object {
        const val DEFAULT_RECEIVE_TIMEOUT_MILLIS: Long = 50L
    }
}
