package com.rafambn.wgkotlin.network

import com.rafambn.wgkotlin.network.io.KtorDatagramUdpPort
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.network.io.UdpPort
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Owns bound UDP sockets and their workers. Close it when its TUN is stopped. */
internal class SocketSession(
    private val ipv4Socket: BoundDatagramSocket?,
    private val ipv6Socket: BoundDatagramSocket?,
    private val selectorManager: SelectorManager,
    private val scope: CoroutineScope,
    private val networkPipe: DuplexChannelPipe<UdpDatagram>,
    onFailure: (Throwable) -> Unit,
) : AutoCloseable {

    private var closed = false
    private val coroutineLabel = "kmpvpn-socket"

    init {
        val ipv4UdpPort = ipv4Socket?.let {
            val port = KtorDatagramUdpPort(socket = it)
            scope.launch(CoroutineName("$coroutineLabel-receive-ipv4")) {
                runReceiveLoop(port, onFailure)
            }
            port
        }

        val ipv6UdpPort = ipv6Socket?.let {
            val port = KtorDatagramUdpPort(socket = it)
            scope.launch(CoroutineName("$coroutineLabel-receive-ipv6")) {
                runReceiveLoop(port, onFailure)
            }
            port
        }

        scope.launch(CoroutineName("$coroutineLabel-send")) {
            runSendLoop(ipv4Port = ipv4UdpPort, ipv6Port = ipv6UdpPort, onFailure = onFailure)
        }
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        runCatching { ipv4Socket?.close() }
        runCatching { ipv6Socket?.close() }
        try {
            selectorManager.close()
        } finally {
            scope.cancel("SocketSession closed")
        }
    }

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

    private suspend fun runSendLoop(
        ipv4Port: UdpPort?,
        ipv6Port: UdpPort?,
        onFailure: (Throwable) -> Unit,
    ) {
        val defaultSocket = ipv4Port ?: ipv6Port ?: return
        try {
            while (true) {
                val datagram = networkPipe.receive()
                if (isIpv6Literal(datagram.remoteEndpoint.address)) {
                    (ipv6Port ?: defaultSocket).sendDatagram(datagram)
                } else {
                    (ipv4Port ?: defaultSocket).sendDatagram(datagram)
                }
            }
        } catch (_: CancellationException) {
            // shutdown path
        } catch (throwable: Throwable) {
            onFailure(throwable)
        }
    }

    private fun isIpv6Literal(address: String): Boolean {
        return address.contains(':')
    }
}
