package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.rafambn.wgkotlin.daemon.di.DaemonKoinBootstrap
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
import io.netty.util.NetUtil
import java.io.File
import java.security.MessageDigest
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.rpc.krpc.ktor.server.Krpc
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.protobuf.protobuf

fun main(args: Array<String>) {
    DaemonCli().main(args)
}

internal const val DAEMON_VERSION = "0.1.0"

internal class DaemonCli : CliktCommand(name = "vpn-daemon") {
    init {
        versionOption(version = DAEMON_VERSION, names = setOf("--version", "-v"))
    }

    private val host: String by option(
        "--host",
        help = "Loopback host/interface to bind the daemon listener (default: 127.0.0.1).",
    ).convert { value ->
        if (value.isBlank()) {
            fail("Daemon host cannot be blank.")
        }
        value
    }.default(DaemonTransport.DEFAULT_DAEMON_HOST)

    private val port: Int by option(
        "--port",
        help = "TCP port for the daemon listener (default: 8787, valid range: 1..65535).",
    ).convert { value ->
        val parsed = value.toIntOrNull()
            ?: fail("Daemon port must be numeric, but was `$value`.")

        if (parsed !in 1..65535) {
            fail("Daemon port must be between 1 and 65535, but was `$parsed`.")
        }

        parsed
    }.default(DaemonTransport.DEFAULT_DAEMON_PORT)

    private val token: String? by option(
        "--token",
        help = "Bearer token required by daemon RPC clients. Defaults to -D${DaemonTransport.DAEMON_TOKEN_PROPERTY} or ${DaemonTransport.DAEMON_TOKEN_ENV}.",
    ).convert { value ->
        value.trim().takeIf(String::isNotEmpty)
            ?: fail("Daemon token cannot be blank.")
    }

    override fun run() {
        val address = bindAddressOrUsageError(host)

        if (!address.isLoopbackAddress) {
            throw UsageError(
                "Refusing to bind daemon to non-loopback host `$host`.",
            )
        }

        val dependencies = DaemonKoinBootstrap.resolveDependencies()
        val adapter = dependencies.adapter
        val cleanupStarted = AtomicBoolean(false)
        fun closeDependenciesOnce() {
            if (cleanupStarted.compareAndSet(false, true)) {
                DaemonKoinBootstrap.close()
            }
        }

        val shutdownHook = Thread(::closeDependenciesOnce, "wg-kotlin-daemon-koin-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        try {
            val isPrivileged = hasRequiredPrivileges()
            if (!isPrivileged) {
                throw UsageError(
                    "Daemon must run with network administration privileges for `${adapter.platformId}` commands (current user: `${System.getProperty("user.name")}`).",
                )
            }

            val missingBinaries = adapter.requiredBinaries
                .filterNot { binary -> isBinaryAvailableOnPath(binary.executable) }
                .map { binary -> binary.executable }

            if (missingBinaries.isNotEmpty()) {
                throw UsageError(
                    "Missing required privileged binaries for `${adapter.platformId}`: ${missingBinaries.joinToString(", ")}.",
                )
            }

            val authToken = token ?: DaemonTransport.configuredToken()
                ?: throw UsageError(
                    "Daemon RPC auth token is required. Pass --token, set -D${DaemonTransport.DAEMON_TOKEN_PROPERTY}, or set ${DaemonTransport.DAEMON_TOKEN_ENV}.",
                )

            adapter.cleanupStaleState()

            createDaemonServer(
                host = host,
                port = port,
                service = dependencies.service,
                authToken = authToken,
            ).start(wait = true)
        } finally {
            closeDependenciesOnce()
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
    }
}

internal fun hasRequiredPrivileges(
    osName: String = System.getProperty("os.name"),
    unixUidProvider: () -> Long = ::currentUnixUid,
    linuxEffectiveCapabilitiesProvider: () -> Long? = ::currentLinuxEffectiveCapabilities,
    commandRunner: (List<String>) -> Boolean = ::runCommandSuccessfully,
): Boolean {
    val normalizedOs = osName.lowercase(Locale.ROOT)
    return when {
        normalizedOs.contains("win") -> {
            val script =
                "${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent();" +
                    "${'$'}principal = [Security.Principal.WindowsPrincipal]::new(${'$'}identity);" +
                    "if (${'$'}identity.IsSystem -or ${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { exit 0 } else { exit 1 }"
            commandRunner(
                listOf(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    script,
                ),
            )
        }
        normalizedOs.contains("linux") -> {
            val isRoot = try {
                unixUidProvider() == 0L
            } catch (_: Exception) {
                false
            }
            isRoot || linuxEffectiveCapabilitiesProvider()?.hasLinuxCapability(CAP_NET_ADMIN) == true
        }
        else -> try {
            unixUidProvider() == 0L
        } catch (_: Exception) {
            false
        }
    }
}

internal fun runCommandSuccessfully(command: List<String>): Boolean {
    var process: Process? = null
    return try {
        process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0
    } catch (_: Exception) {
        false
    } finally {
        process?.outputStream?.close()
        process?.inputStream?.close()
        process?.errorStream?.close()
    }
}

private fun currentUnixUid(): Long {
    val process = ProcessBuilder("id", "-u")
        .redirectErrorStream(true)
        .start()

    try {
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("id -u timed out")
        }
        return process.inputStream.bufferedReader().use { reader ->
            reader.readText().trim().toLong()
        }
    } finally {
        process.outputStream.close()
        process.inputStream.close()
        process.errorStream.close()
    }
}

private fun currentLinuxEffectiveCapabilities(): Long? {
    return runCatching {
        File("/proc/self/status")
            .useLines { lines ->
                lines.firstOrNull { line -> line.startsWith("CapEff:") }
            }
            ?.substringAfter(':')
            ?.trim()
            ?.toLong(radix = 16)
    }.getOrNull()
}

private fun Long.hasLinuxCapability(capability: Int): Boolean {
    return (this and (1L shl capability)) != 0L
}

internal fun createDaemonServer(
    host: String,
    port: Int,
    service: DaemonApi,
    authToken: String,
) = embeddedServer(
    factory = Netty,
    host = host,
    port = port,
    module = { module(service = service, authToken = authToken) },
)

internal fun isBinaryAvailableOnPath(executable: String): Boolean {
    val lookupCommand = if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) {
        listOf("where", executable)
    } else {
        listOf("which", executable)
    }

    return runCommandSuccessfully(lookupCommand)
}

@OptIn(ExperimentalSerializationApi::class)
fun Application.module(
    service: DaemonApi,
    authToken: String? = null,
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
            authToken?.let(::requireDaemonBearerToken)
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

private fun Route.requireDaemonBearerToken(authToken: String) {
    (this as ApplicationCallPipeline).intercept(ApplicationCallPipeline.Plugins) {
        val header = call.request.header(DaemonTransport.DAEMON_AUTH_HEADER)
        if (!isAuthorizedDaemonRpcHeader(header = header, authToken = authToken)) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            finish()
        }
    }
}

internal fun isAuthorizedDaemonRpcHeader(header: String?, authToken: String): Boolean {
    require(authToken.isNotBlank()) { "Daemon auth token cannot be blank" }
    val expected = DaemonTransport.bearerTokenValue(authToken)
    val actualBytes = header?.toByteArray(Charsets.UTF_8) ?: return false
    val expectedBytes = expected.toByteArray(Charsets.UTF_8)
    return MessageDigest.isEqual(actualBytes, expectedBytes)
}

internal fun bindAddressOrUsageError(host: String): InetAddress {
    val normalizedHost = host.trim().removeSurrounding("[", "]")
    val addressBytes = NetUtil.createByteArrayFromIpAddressString(normalizedHost)
    return if (addressBytes == null) {
        throw UsageError("Daemon host `$host` is not a valid bind address.")
    } else {
        InetAddress.getByAddress(addressBytes)
    }
}

private const val DAEMON_WEBSOCKET_PING_PERIOD_MILLIS: Long = 30_000
private const val DAEMON_WEBSOCKET_TIMEOUT_MILLIS: Long = 15_000
private const val DAEMON_WEBSOCKET_MAX_FRAME_SIZE: Long = 128L * 1024L
private const val CAP_NET_ADMIN: Int = 12
