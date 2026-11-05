package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.daemon.IpcProtocol
import com.rafambn.wgkotlin.daemon.LocalConnection
import com.rafambn.wgkotlin.daemon.proto.ClientMessage
import com.rafambn.wgkotlin.daemon.proto.Packet
import com.rafambn.wgkotlin.daemon.proto.ServerMessage
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString

/** One authenticated IPC connection owns one TUN session. */
internal class DaemonSessionBridge : SessionBridge {
    override fun openSession(config: TunSessionConfig, pipe: DuplexChannelPipe<ByteArray>, onFailure: (Throwable) -> Unit): AutoCloseable {
        val activeDaemon = PlatformInterfaceFactory.requireDaemon()
        check(activeDaemon.isAlive) { "Owned daemon has exited" }
        val daemonPid = activeDaemon.pid
        val endpoint = activeDaemon.endpoint
        val connection = LocalConnection.connect(endpoint, daemonPid)
        try {
            IpcProtocol.awaitReady(connection, daemonPid)
            connection.readTimeoutMillis = 30_000
            IpcProtocol.writeClient(connection.output, ClientMessage { payload = ClientMessage.Payload.Config(config) })
            when (val payload = IpcProtocol.readServer(connection.input).payload) {
                is ServerMessage.Payload.Started -> check(payload.value.interfaceName.isNotBlank()) { "Missing started interface name" }
                is ServerMessage.Payload.Error -> error("Daemon session failed (${payload.value.code}): ${payload.value.message}")
                else -> error("Daemon did not accept the session")
            }
            connection.readTimeoutMillis = 0
        } catch (error: Throwable) {
            connection.close()
            throw IllegalStateException("Daemon did not accept the IPC session", error)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("wg-kotlin-ipc-session"))
        val closed = AtomicBoolean()
        fun close() {
            if (closed.compareAndSet(false, true)) {
                connection.close()
                scope.cancel()
            }
        }
        fun fail(error: Throwable) {
            if (closed.compareAndSet(false, true)) {
                connection.close()
                scope.cancel()
                onFailure(error)
            }
        }
        scope.launch {
            try {
                while (true) {
                    when (val payload = IpcProtocol.readServer(connection.input).payload) {
                        is ServerMessage.Payload.IncomingPacket -> pipe.send(payload.value.data.toByteArray())
                        is ServerMessage.Payload.Error -> error("Daemon session failed (${payload.value.code}): ${payload.value.message}")
                        else -> error("Unexpected daemon session message")
                    }
                }
            } catch (_: CancellationException) {
            } catch (error: Throwable) { fail(error) }
        }
        scope.launch {
            try {
                while (true) {
                    val bytes = pipe.receive()
                    require(bytes.size in 1..65_535) { "Invalid outgoing packet length" }
                    IpcProtocol.writeClient(connection.output, ClientMessage {
                        payload = ClientMessage.Payload.OutgoingPacket(Packet { data = ByteString(bytes) })
                    })
                }
            } catch (_: CancellationException) {
            } catch (error: Throwable) { fail(error) }
        }
        return AutoCloseable { close() }
    }
}
