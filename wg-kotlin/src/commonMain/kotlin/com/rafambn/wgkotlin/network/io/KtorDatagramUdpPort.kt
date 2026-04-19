package com.rafambn.wgkotlin.network.io

import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray

/**
 * Ktor-based UDP adapter that can run from common code.
 */
class KtorDatagramUdpPort(
    private val socket: BoundDatagramSocket,
    private val receiveTimeoutMillis: Long? = null,
) : UdpPort {
    init {
        receiveTimeoutMillis?.let { timeout ->
            require(timeout > 0L) {
                "receiveTimeoutMillis must be greater than zero when provided"
            }
        }
    }

    override suspend fun receiveDatagram(): UdpDatagram? {
        val received = if (receiveTimeoutMillis == null) {
            socket.receive()
        } else {
            withTimeoutOrNull(receiveTimeoutMillis) {
                socket.receive()
            }
        } ?: return null

        return UdpDatagram(
            payload = received.packet.readByteArray(),
            remoteEndpoint = received.address.toUdpEndpoint(),
        )
    }

    override suspend fun sendDatagram(datagram: UdpDatagram) {
        socket.send(
            Datagram(
                packet = buildPacket {
                    writeFully(datagram.payload)
                },
                address = InetSocketAddress(datagram.remoteEndpoint.address, datagram.remoteEndpoint.port),
            ),
        )
    }
}

private fun SocketAddress.toUdpEndpoint(): UdpEndpoint {
    val inetAddress = this as? InetSocketAddress
        ?: throw IllegalStateException("Unsupported Ktor socket address type `${this::class.simpleName}`")
    return UdpEndpoint(
        address = inetAddress.hostname,
        port = inetAddress.port,
    )
}
