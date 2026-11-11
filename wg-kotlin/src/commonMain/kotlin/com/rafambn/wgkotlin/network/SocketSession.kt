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

/** Owns bound UDP sockets and their workers. Close it when its TUN is stopped. */
internal class SocketSession(
    listenPort: Int,
    private val networkPipe: DuplexChannelPipe<UdpDatagram>,
    onFailure: (Throwable) -> Unit,
) : AutoCloseable {

    private var closed = false
    private var ipv4Socket: BoundDatagramSocket? = null
    private var ipv6Socket: BoundDatagramSocket? = null
    private val coroutineLabel = "kmpvpn-socket"
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName(coroutineLabel),
    )
    private val selectorManager = SelectorManager(scope.coroutineContext)

    init {
        try {
            runBlocking {
                var firstFailure: Throwable? = null

                runCatching {
                    aSocket(selectorManager).udp().bind(InetSocketAddress("::", listenPort))
                }.onSuccess { socket ->
                    ipv6Socket = socket
                }.onFailure { failure ->
                    firstFailure = failure
                }

                val ipv6Address = ipv6Socket?.localAddress as InetSocketAddress?
                val ipv4Port = if (listenPort == 0 && ipv6Address != null) {
                    ipv6Address.port
                } else {
                    listenPort
                }

                runCatching {
                    aSocket(selectorManager).udp().bind(InetSocketAddress("0.0.0.0", ipv4Port))
                }.onSuccess { socket ->
                    ipv4Socket = socket
                }.onFailure { failure ->
                    if (ipv6Socket == null) {
                        firstFailure = firstFailure ?: failure
                    }
                }

                if (ipv4Socket == null && ipv6Socket == null) {
                    throw IllegalStateException("Failed to bind UDP socket for IPv4 and IPv6", firstFailure)
                }
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }

        val ipv4UdpPort = ipv4Socket?.let {
            val port = KtorDatagramUdpPort(
                socket = it,
                receiveTimeoutMillis = DEFAULT_RECEIVE_TIMEOUT_MILLIS,
            )
            scope.launch(CoroutineName("$coroutineLabel-receive-ipv4")) {
                runReceiveLoop(port, onFailure)
            }
            port
        }

        val ipv6UdpPort = ipv6Socket?.let {
            val port = KtorDatagramUdpPort(
                socket = it,
                receiveTimeoutMillis = DEFAULT_RECEIVE_TIMEOUT_MILLIS,
            )
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
        selectorManager.close()
        scope.cancel("SocketSession closed")
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

    private companion object {
        const val DEFAULT_RECEIVE_TIMEOUT_MILLIS: Long = 50L
    }
}
