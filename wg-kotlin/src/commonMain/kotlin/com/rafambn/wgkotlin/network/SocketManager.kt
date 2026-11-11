package com.rafambn.wgkotlin.network

import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe

internal object SocketManager {
    fun create(
        listenPort: Int,
        networkPipe: DuplexChannelPipe<UdpDatagram>,
        onFailure: (Throwable) -> Unit = {},
    ): SocketSession = SocketSession(listenPort, networkPipe, onFailure)
}
