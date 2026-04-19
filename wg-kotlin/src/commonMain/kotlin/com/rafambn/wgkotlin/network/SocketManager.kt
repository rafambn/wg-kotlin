package com.rafambn.wgkotlin.network

import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.network.io.UdpDatagram

/**
 * Owns the UDP socket and bridges it to a [DuplexChannelPipe].
 *
 * Caller provides the crypto-side pipe end via [start]. The socket manager drives two internal
 * coroutines: one reading from UDP and sending into the pipe, one reading
 * from the pipe and writing to UDP.
 */
interface SocketManager {

    /**
     * Binds to [listenPort] (0 = OS-assigned) and starts receive/send workers.
     * Stops any previously running workers and socket first.
     * [networkPipe] is the crypto-side end of the duplex channel for exchanging UDP datagrams.
     * [onFailure] is invoked on any unrecoverable worker error.
     */
    fun start(listenPort: Int, networkPipe: DuplexChannelPipe<UdpDatagram>, onFailure: (Throwable) -> Unit)

    /**
     * Closes the UDP socket and cancels worker coroutines.
     */
    fun stop()

    /**
     * Returns true while the socket is bound and workers are running.
     */
    fun isRunning(): Boolean
}
