package com.rafambn.wgkotlin.daemon

import com.rafambn.scribe.seal
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.DaemonTransport
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonPrimitive

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
internal fun Application.module(
    service: DaemonApi,
) {
    installDaemonLogger()
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

    DaemonLogger.newScroll().apply {
        this["start"] = JsonPrimitive(System.currentTimeMillis())
    }.seal(DaemonLogger)
}

internal fun Application.installDaemonLogger() {
    DaemonLogger.hire(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        channel = Channel(Channel.UNLIMITED),
        onSaver = { saver, entry, failure ->
            val scroll = DaemonLogger.newScroll().apply {
                this["event"] = JsonPrimitive("saver_failure")
                this["saver"] = JsonPrimitive(saver::class.simpleName)
                this["entry_type"] = JsonPrimitive(entry::class.simpleName)
                this["error_type"] = JsonPrimitive(failure::class.simpleName)
            }
            scroll.seal(DaemonLogger, success = false)
        },
    )
    install(DaemonHttpLogger)
}

internal val DaemonHttpLogger = createApplicationPlugin("DaemonHttpLogger") {
    application.intercept(ApplicationCallPipeline.Monitoring) {
        val startedAtNanos = System.nanoTime()
        val scroll = DaemonLogger.newScroll().apply {
            this["event"] = JsonPrimitive("daemon_http_request")
            this["method"] = JsonPrimitive(call.request.httpMethod.value)
            this["path"] = JsonPrimitive(call.request.path())
        }
        try {
            proceed()
        } catch (caught: Throwable) {
            scroll["error_type"] = JsonPrimitive(caught::class.simpleName ?: "Throwable")
            throw caught
        } finally {
            val status = call.response.status()?.value ?: 0
            scroll["status"] = JsonPrimitive(status)
            scroll["duration_ms"] = JsonPrimitive((System.nanoTime() - startedAtNanos) / 1_000_000)
            runCatching { scroll.seal(DaemonLogger, success = scroll["error_type"] == null && status < 500) }
        }
    }
}