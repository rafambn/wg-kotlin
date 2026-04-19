package com.rafambn.wgkotlin.network


/**
 * Owns the UDP socket and bridges it to a [DuplexChannelPipe].
 *
 * The crypto-side pipe end is provided at construction time. The socket manager drives two
 * internal coroutines: one reading from UDP and sending into the pipe, one reading
 * from the pipe and writing to UDP.
 */
interface SocketManager {

    /**
     * Binds to [listenPort] (0 = OS-assigned) and starts receive/send workers.
     * Stops any previously running workers and socket first.
     * [onFailure] is invoked on any unrecoverable worker error.
     */
    fun start(listenPort: Int, onFailure: (Throwable) -> Unit)

    /**
     * Closes the UDP socket and cancels worker coroutines.
     */
    fun stop()

    /**
     * Returns true while the socket is bound and workers are running.
     */
    fun isRunning(): Boolean
}
