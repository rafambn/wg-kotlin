package com.rafambn.kmpvpn.daemon.protocol

const val DEFAULT_DAEMON_HOST: String = "127.0.0.1"
const val DEFAULT_DAEMON_PORT: Int = 8787
const val DAEMON_RPC_PATH: String = "/services"

fun daemonRpcUrl(host: String, port: Int): String {
    val normalizedHost = if (':' in host && !host.startsWith("[")) {
        "[$host]"
    } else {
        host
    }

    return "ws://$normalizedHost:$port$DAEMON_RPC_PATH"
}
