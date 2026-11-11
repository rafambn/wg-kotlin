package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe

expect object PlatformInterfaceManager {
    suspend fun create(
        config: TunSessionConfig,
        tunPipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit = {},
    ): InterfaceSession
}
