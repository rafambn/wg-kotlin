package com.rafambn.wgkotlin.util

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume

/**
 * Duplex coroutine channel pipe connecting two parties.
 *
 * Create a paired set via [DuplexChannelPipe.create]. Each end has independent
 * send and receive channels so both sides can communicate bidirectionally.
 *
 * Use bounded capacity to apply backpressure; callers suspend rather than drop.
 */
class DuplexChannelPipe<T> internal constructor(
    private val sendChannel: Channel<T>,
    private val receiveChannel: Channel<T>,
) {
    suspend fun send(item: T) {
        sendChannel.send(item)
    }

    suspend fun receive(): T {
        return receiveChannel.receive()
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64

        fun <T> create(capacity: Int = DEFAULT_CAPACITY): Pair<DuplexChannelPipe<T>, DuplexChannelPipe<T>> {
            val aToB = Channel<T>(capacity)
            val bToA = Channel<T>(capacity)
            val endA = DuplexChannelPipe(sendChannel = aToB, receiveChannel = bToA)
            val endB = DuplexChannelPipe(sendChannel = bToA, receiveChannel = aToB)
            return endA to endB
        }
    }
}
