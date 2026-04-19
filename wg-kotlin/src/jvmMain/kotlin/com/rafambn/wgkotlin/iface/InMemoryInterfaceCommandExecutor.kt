package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.util.DuplexChannelPipe

class InMemoryInterfaceCommandExecutor : InterfaceCommandExecutor {
    private val sessions = linkedMapOf<String, SessionState>()

    override fun openSession(
        config: TunSessionConfig,
        pipe: DuplexChannelPipe<ByteArray>,
        onFailure: (Throwable) -> Unit,
    ): AutoCloseable {
        sessions[config.interfaceName] = SessionState(
            config = config,
            onFailure = onFailure,
        )
        return AutoCloseable {
            sessions.remove(config.interfaceName)
        }
    }

    fun isRunning(interfaceName: String): Boolean = sessions.containsKey(interfaceName)

    fun getConfig(interfaceName: String): TunSessionConfig? = sessions[interfaceName]?.config

    fun failSession(interfaceName: String, throwable: Throwable) {
        val state = sessions.remove(interfaceName) ?: return
        state.onFailure(throwable)
    }

    private class SessionState(
        val config: TunSessionConfig,
        val onFailure: (Throwable) -> Unit,
    )
}
