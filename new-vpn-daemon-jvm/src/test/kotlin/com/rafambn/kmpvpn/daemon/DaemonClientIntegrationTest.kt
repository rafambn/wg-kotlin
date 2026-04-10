package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.client.DaemonProcessClient
import com.rafambn.kmpvpn.daemon.command.ProcessInvocationModel
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessOutputModel
import com.rafambn.kmpvpn.daemon.planner.LinuxOperationPlanner
import com.rafambn.kmpvpn.daemon.protocol.DaemonProcessApi
import com.rafambn.kmpvpn.daemon.protocol.DEFAULT_DAEMON_HOST
import com.rafambn.kmpvpn.daemon.protocol.daemonRpcUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.net.ServerSocket
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonClientIntegrationTest {

    @Test
    fun clientRoundTripAgainstDaemonServerUsesRealHandlers() = runBlocking {
        val launcher = RecordingLauncher()
        val service = DaemonProcessApiImpl(
            operationPlanner = LinuxOperationPlanner(),
            processLauncher = launcher,
        )
        val port = randomPort()
        val engine = createDaemonServer(
            host = DEFAULT_DAEMON_HOST,
            port = port,
            service = service,
        )

        engine.start(wait = false)
        delay(200)

        val httpClient = HttpClient(CIO) {
            install(WebSockets)
            installKrpc {
                serialization {
                    json()
                }
            }
        }
        val rpcClient = httpClient.rpc(daemonRpcUrl(host = DEFAULT_DAEMON_HOST, port = port))
        val client = DaemonProcessClient(
            timeout = java.time.Duration.ofSeconds(15),
            service = rpcClient.withService<DaemonProcessApi>(),
            resourceCloser = { httpClient.close() },
        )
        try {
            val hello = client.handshake()
            assertTrue(hello.isSuccess)

            val upResult = client.setInterfaceState(
                interfaceName = "utun0",
                up = true,
            )
            assertTrue(upResult.isSuccess)

            val mtuResult = client.applyMtu(
                interfaceName = "utun0",
                mtu = 1420,
            )
            assertTrue(mtuResult.isSuccess)
        } finally {
            client.close()
            engine.stop(100, 1_000)
        }

        assertEquals(2, launcher.invocations.size)
        assertEquals(listOf("link", "set", "dev", "utun0", "up"), launcher.invocations[0].arguments)
        assertEquals(listOf("link", "set", "dev", "utun0", "mtu", "1420"), launcher.invocations[1].arguments)
    }

    private class RecordingLauncher : ProcessLauncher {
        val invocations: MutableList<ProcessInvocationModel> = mutableListOf()

        override fun run(invocation: ProcessInvocationModel): ProcessOutputModel {
            invocations += invocation
            return ProcessOutputModel(
                exitCode = 0,
                stdout = "",
                stderr = "",
            )
        }
    }

    private fun randomPort(): Int {
        return ServerSocket(0).use { socket -> socket.localPort }
    }
}
