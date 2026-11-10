package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.crypto.CryptoSessionManagerImpl
import com.rafambn.wgkotlin.iface.PlatformInterfaceFactory
import com.rafambn.wgkotlin.network.SocketManagerImpl
import com.rafambn.wgkotlin.network.io.UdpDatagram
import com.rafambn.wgkotlin.util.DuplexChannelPipe

/** One opening owns its queues and consumers; stopped executions never feed a later one. */
internal class VpnExecution(val configuration: VpnConfiguration, engine: Engine) {
    val tunPipePair = DuplexChannelPipe.create<ByteArray>()
    val networkPipePair = DuplexChannelPipe.create<UdpDatagram>()
    val cryptoSessionManager = CryptoSessionManagerImpl(tunPipePair.second, networkPipePair.second, engine)
    val socketManager = SocketManagerImpl(networkPipePair.first)
    val interfaceManager = PlatformInterfaceFactory.create(tunPipePair.first)
}
