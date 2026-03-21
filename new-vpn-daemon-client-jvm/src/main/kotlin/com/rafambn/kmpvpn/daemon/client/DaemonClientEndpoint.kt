package com.rafambn.kmpvpn.daemon.client

data class DaemonClientEndpoint(
    val host: String = "127.0.0.1",
    val port: Int,
    val path: String = "/services",
) {
    init {
        require(host.isNotBlank()) { "Daemon host cannot be blank" }
        require(port in 1..65535) { "Daemon port must be between 1 and 65535" }
        require(path.startsWith('/')) { "Daemon path must start with '/'" }
    }

    fun wsUrl(): String {
        return "ws://$host:$port$path"
    }
}
