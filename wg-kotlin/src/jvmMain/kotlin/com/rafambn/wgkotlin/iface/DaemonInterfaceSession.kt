package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.DnsConfig
import com.rafambn.wgkotlin.daemon.IpcProtocol
import com.rafambn.wgkotlin.daemon.LocalConnection
import com.rafambn.wgkotlin.daemon.proto.ClientMessage
import com.rafambn.wgkotlin.daemon.proto.Packet
import com.rafambn.wgkotlin.daemon.proto.ServerMessage
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig
import com.rafambn.wgkotlin.daemon.proto.invoke
import com.rafambn.wgkotlin.util.DuplexChannelPipe
import com.rafambn.wgkotlin.util.toCidrString
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
internal class DaemonInterfaceSession(
    private val config: TunSessionConfig,
    private val connection: LocalConnection,
    pipe: DuplexChannelPipe<ByteArray>,
    private val onFailure: (Throwable) -> Unit,
) : InterfaceSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("wg-kotlin-ipc-session"))
    private val closed = AtomicBoolean()

    init {
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
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            connection.close()
            scope.cancel()
        }
    }

    override fun information(): VpnInterfaceInformation? {
        if (closed.get()) return null
        return VpnInterfaceInformation(
            interfaceName = config.interfaceName,
            isUp = true,
            addresses = config.addresses.map { it.toCidrString() },
            dns = DnsConfig(
                searchDomains = config.dns.searchDomains,
                servers = config.dns.servers,
            ),
            mtu = if (config.mtu == 0) null else config.mtu,
        )
    }

    private fun fail(error: Throwable) {
        if (closed.compareAndSet(false, true)) {
            connection.close()
            scope.cancel()
            onFailure(error)
        }
    }
}
