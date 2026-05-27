package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RustDaemonGrpcIntegrationTest {
    @Test
    fun kotlinClientTalksToRustDaemonProcess() {
        if (System.getProperty(RUN_RUST_DAEMON_TEST_PROPERTY) != "true") {
            return
        }

        if (!System.getProperty("os.name").lowercase().contains("linux")) {
            return
        }

        val logPath = Files.createTempFile("wgkotlin-rust-daemon", ".log")
        val port = randomPort()
        val daemonProcess = ProcessBuilder(
            "cargo",
            "run",
            "--manifest-path",
            "wg-kotlin-daemon-rust/daemon/Cargo.toml",
            "--",
            "--host",
            "127.0.0.1",
            "--port",
            port.toString(),
            "--allow-non-root",
            "--log-path",
            logPath.toString(),
        )
            .directory(Path.of("").toAbsolutePath().toFile())
            .redirectErrorStream(true)
            .start()

        try {
            waitForPort("127.0.0.1", port)

            val (clientPipe, _) = DuplexChannelPipe.create<ByteArray>()
            val executor = DaemonBackedInterfaceCommandExecutor(host = "127.0.0.1", port = port)

            val failure = assertFailsWith<IllegalStateException> {
                executor.openSession(
                    config = TunSessionConfig {
                        interfaceName = "wg0"
                        addresses = listOf("10.10.0.2/32")
                    },
                    pipe = clientPipe,
                    onFailure = {},
                )
            }

            assertTrue(
                failure.message?.contains("unsupported interface name", ignoreCase = true) == true,
                "Expected server-side validation error in message, got: ${failure.message}",
            )
        } finally {
            daemonProcess.destroy()
            daemonProcess.waitFor()
            Files.deleteIfExists(logPath)
        }
    }

    private fun waitForPort(host: String, port: Int) {
        repeat(200) {
            runCatching {
                Socket(host, port).use { }
                return
            }
            Thread.sleep(50)
        }
        error("Timed out waiting for daemon port $host:$port")
    }

    private fun randomPort(): Int = ServerSocket(0).use { socket -> socket.localPort }

    private companion object {
        const val RUN_RUST_DAEMON_TEST_PROPERTY: String = "wgkotlin.test.rustDaemonE2E"
    }
}
