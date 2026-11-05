package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe

interface SessionBridge {
    suspend fun openSession(
        config: TunSessionConfig,
        pipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit = {},
    ): AutoCloseable
}
