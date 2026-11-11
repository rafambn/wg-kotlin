package com.rafambn.wgkotlin.network.io

import com.rafambn.wgkotlin.util.resolveToIpString
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.SocketAddress
import io.ktor.utils.io.core.buildPacket
import io.ktor.utils.io.core.writeFully
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ktor-based UDP adapter that can run from common code.
 * A zero [receiveTimeoutMillis] waits without a timeout; positive values return null on timeout.
 */
class KtorDatagramUdpPort(
    private val socket: BoundDatagramSocket,
    private val receiveTimeoutMillis: Long = 0L,
) : UdpPort {
    init {
        require(receiveTimeoutMillis >= 0L) {
            "receiveTimeoutMillis must be non-negative"
        }
    }

    override suspend fun receiveDatagram(): UdpDatagram? {
        val received = if (receiveTimeoutMillis == 0L) {
            socket.receive()
        } else {
            withTimeoutOrNull(receiveTimeoutMillis.milliseconds) { socket.receive() } ?: return null
        }

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
    val raw = inetAddress.hostname
    val address = if (raw.isIpLiteral()) {
        raw
    } else {
        raw.resolveToIpString()
    }
    return UdpEndpoint(
        address = address,
        port = inetAddress.port,
    )
}

private fun String.isIpLiteral(): Boolean {
    return contains(':') || all { it.isDigit() || it == '.' }
}
