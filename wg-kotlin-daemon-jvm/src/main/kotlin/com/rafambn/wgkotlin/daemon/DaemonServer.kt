package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import java.security.MessageDigest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf

internal fun createDaemonServer(
    host: String,
    port: Int,
    service: DaemonApi,
) = embeddedServer(
    factory = Netty,
    host = host,
    port = port,
    module = { module(service = service) },
)

@OptIn(ExperimentalSerializationApi::class)
fun Application.module(
    service: DaemonApi,
) {
    install(WebSockets) {
        pingPeriodMillis = DAEMON_WEBSOCKET_PING_PERIOD_MILLIS
        timeoutMillis = DAEMON_WEBSOCKET_TIMEOUT_MILLIS
        maxFrameSize = DAEMON_WEBSOCKET_MAX_FRAME_SIZE
    }
    install(Krpc) {
        serialization {
            protobuf()
        }
    }

    routing {
        get("/version") {
            call.respondText(DAEMON_VERSION)
        }
        route(DaemonTransport.DAEMON_RPC_PATH) {
            rpc {
                rpcConfig {
                    serialization {
                        protobuf()
                    }
                }
                registerService<DaemonApi> {
                    service
                }
            }
        }
    }
}