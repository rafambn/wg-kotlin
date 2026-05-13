package com.rafambn.wgkotlin.daemon.protocol

object DaemonTransport {
    const val DEFAULT_DAEMON_HOST: String = "127.0.0.1"
    const val DEFAULT_DAEMON_PORT: Int = 8787
    const val DAEMON_RPC_PATH: String = "/services"

    fun rpcUrl(
        host: String = DEFAULT_DAEMON_HOST,
        port: Int = DEFAULT_DAEMON_PORT,
    ): String {
        val normalizedHost = normalizeHost(host)
        return "ws://$normalizedHost:$port$DAEMON_RPC_PATH"
    }

    private fun normalizeHost(host: String): String {
        return if (':' in host && !host.startsWith("[")) "[$host]" else host
    }
}
