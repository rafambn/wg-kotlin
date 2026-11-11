package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSessionManagerImpl
import com.rafambn.wgkotlin.iface.InterfaceSession
import com.rafambn.wgkotlin.network.SocketManagerImpl
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

internal class VpnSession(
    val configuration: VpnConfiguration,
    engine: Engine,
) {
    private val tunPipePair = DuplexChannelPipe.create<ByteArray>()
    val tunPipe = tunPipePair.first
    private val networkPipePair = DuplexChannelPipe.create<UdpDatagram>()
    val cryptoSessionManager = CryptoSessionManagerImpl(
        tunPipe = tunPipePair.second,
        networkPipe = networkPipePair.second,
        engine = engine,
    )
    val socketManager = SocketManagerImpl(networkPipe = networkPipePair.first)
    var interfaceSession: InterfaceSession? = null
    val failureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
