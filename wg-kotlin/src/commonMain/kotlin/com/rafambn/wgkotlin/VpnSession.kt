package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSession
import com.rafambn.wgkotlin.iface.InterfaceSession
import com.rafambn.wgkotlin.network.SocketSession
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

internal class VpnSession(
    val configuration: VpnConfiguration,
) {
    private val tunPipePair = DuplexChannelPipe.create<ByteArray>()
    val tunPipe = tunPipePair.first
    val cryptoTunPipe = tunPipePair.second
    private val networkPipePair = DuplexChannelPipe.create<UdpDatagram>()
    val networkPipe = networkPipePair.first
    val cryptoNetworkPipe = networkPipePair.second
    var cryptoSession: CryptoSession? = null
    var socketSession: SocketSession? = null
    var interfaceSession: InterfaceSession? = null
    val failureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun close(): Result<Unit> = withContext(NonCancellable) {
        failureScope.cancel()
        val interfaceStop = runCatching<Unit> { interfaceSession?.close() }
        val socketStop = runCatching<Unit> { socketSession?.close() }
        val cryptoStop = runCatching<Unit> { cryptoSession?.close() }
        when {
            interfaceStop.isFailure -> interfaceStop
            socketStop.isFailure -> socketStop
            else -> cryptoStop
        }
    }
}
