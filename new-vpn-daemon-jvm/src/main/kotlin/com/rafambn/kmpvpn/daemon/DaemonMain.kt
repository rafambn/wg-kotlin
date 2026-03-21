package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json

fun main(vararg args: String) {
    val (host, port) = parseDaemonAddress(args)
    embeddedServer(
        factory = Netty,
        host = host,
        port = port,
        module = { module() },
    ).start(wait = true)
}

const val DEFAULT_DAEMON_HOST: String = "127.0.0.1"
const val DEFAULT_DAEMON_PORT: Int = 8787

private fun parseDaemonAddress(args: Array<out String>): Pair<String, Int> {
    var host = DEFAULT_DAEMON_HOST
    var port = DEFAULT_DAEMON_PORT

    args.forEachIndexed { index, value ->
        when {
            value.startsWith("--host=") -> host = value.substringAfter("=")
            value.startsWith("--port=") -> port = value.substringAfter("=").toIntOrNull() ?: port
            value == "--host" -> host = args.getOrNull(index + 1) ?: host
            value == "--port" -> port = args.getOrNull(index + 1)?.toIntOrNull() ?: port
        }
    }

    if (args.isNotEmpty() && !args[0].startsWith("--")) {
        host = args[0]
    }
    if (args.size > 1 && !args[1].startsWith("--")) {
        port = args[1].toIntOrNull() ?: port
    }

    return host to port
}

fun Application.module() {
    install(WebSockets)
    install(Krpc) {
        serialization {
            // TODO(vpn-rebuild): migrate kRPC serialization to Protobuf once protocol models are stable and annotated with @ProtoNumber.
            json()
        }
    }

    routing {
        rpc("/services") {
            rpcConfig {
                serialization {
                    // TODO(vpn-rebuild): migrate kRPC serialization to Protobuf once protocol models are stable and annotated with @ProtoNumber.
                    json()
                }
            }
            registerService<DaemonProcessApi> {
                DaemonProcessApiImpl()
            }
        }
    }
}
