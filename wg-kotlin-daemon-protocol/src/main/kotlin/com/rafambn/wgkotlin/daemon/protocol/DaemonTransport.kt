package com.rafambn.wgkotlin.daemon.protocol

object DaemonTransport {
    const val DEFAULT_DAEMON_HOST: String = "127.0.0.1"
    const val DEFAULT_DAEMON_PORT: Int = 8787
    const val DAEMON_RPC_PATH: String = "/services"
    const val DAEMON_AUTH_HEADER: String = "Authorization"
    const val DAEMON_TOKEN_PROPERTY: String = "wgkotlin.daemon.token"
    const val DAEMON_TOKEN_ENV: String = "WG_KOTLIN_DAEMON_TOKEN"

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

    fun bearerTokenValue(token: String): String {
        require(token.isNotBlank()) { "Daemon token cannot be blank" }
        return "Bearer $token"
    }

    fun configuredToken(
        systemPropertyProvider: (String) -> String? = System::getProperty,
        environmentProvider: (String) -> String? = System::getenv,
    ): String? {
        return sequenceOf(
            systemPropertyProvider(DAEMON_TOKEN_PROPERTY),
            environmentProvider(DAEMON_TOKEN_ENV),
        ).firstNotNullOfOrNull { value -> value?.trim()?.takeIf(String::isNotEmpty) }
    }
}
