package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.TunHandle

internal interface PlatformAdapter {
    val platformId: String
    val requiredBinaries: Set<CommandBinary>

    suspend fun startSession(config: TunSessionConfig): TunHandle
}
