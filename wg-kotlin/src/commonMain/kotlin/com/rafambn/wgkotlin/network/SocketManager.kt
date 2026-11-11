package com.rafambn.wgkotlin.network

import com.rafambn.wgkotlin.network.io.UdpDatagram
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal object SocketManager {
    suspend fun create(
        listenPort: Int,
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit = {},
    ): SocketSession {
        currentCoroutineContext().ensureActive()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("kmpvpn-socket"))
        val selectorManager = SelectorManager(scope.coroutineContext)
        var ipv4Socket: BoundDatagramSocket? = null
        var ipv6Socket: BoundDatagramSocket? = null
        try {
            var firstFailure: Throwable? = null
            try {
                ipv6Socket = aSocket(selectorManager).udp().bind(InetSocketAddress("::", listenPort))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                firstFailure = error
            }

            val ipv6Address = ipv6Socket?.localAddress as InetSocketAddress?
            val ipv4Port = if (listenPort == 0 && ipv6Address != null) ipv6Address.port else listenPort
            try {
                ipv4Socket = aSocket(selectorManager).udp().bind(InetSocketAddress("0.0.0.0", ipv4Port))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (ipv6Socket == null) firstFailure = firstFailure ?: error
            }

            if (ipv4Socket == null && ipv6Socket == null) {
                throw IllegalStateException("Failed to bind UDP socket for IPv4 and IPv6", firstFailure)
            }
            currentCoroutineContext().ensureActive()
            return SocketSession(ipv4Socket, ipv6Socket, selectorManager, scope, networkPipe, onFailure)
        } catch (error: Throwable) {
            runCatching { ipv4Socket?.close() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { ipv6Socket?.close() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { selectorManager.close() }.exceptionOrNull()?.let(error::addSuppressed)
            scope.cancel()
            throw error
        }
    }
}
