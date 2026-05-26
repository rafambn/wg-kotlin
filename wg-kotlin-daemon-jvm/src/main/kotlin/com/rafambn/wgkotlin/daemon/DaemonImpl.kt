package com.rafambn.wgkotlin.daemon

import com.rafambn.scribe.seal
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapter
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

class DaemonImpl internal constructor(
    private val adapter: PlatformAdapter,
) : DaemonApi {
    private val activeSessionLock = Any()
    private val activeSessions = mutableSetOf<String>()

    override fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray> = channelFlow {
        val startedAtNanos = System.nanoTime()
        val scroll = DaemonLogger.newScroll().apply {
            this["event"] = JsonPrimitive("daemon_session")
            this["requested_interface"] = JsonPrimitive(config.interfaceName)
            this["platform"] = JsonPrimitive(adapter.platformId)
            this["address_count"] = JsonPrimitive(config.addresses.size)
            this["route_count"] = JsonPrimitive(config.routes.size)
            this["endpoint_count"] = JsonPrimitive(config.endpoints.size)
            this["dns_server_count"] = JsonPrimitive(config.dns.servers.size)
            this["dns_domain_count"] = JsonPrimitive(config.dns.searchDomains.size)
            this["mtu_configured"] = JsonPrimitive(config.mtu != null)
        }
        try {
            DaemonPayloadValidator.validate(config)
            synchronized(activeSessionLock) {
                if (activeSessions.contains(config.interfaceName)) {
                    throw IllegalStateException("Session already active for ${config.interfaceName}")
                }
                if (activeSessions.size >= MAX_ACTIVE_SESSIONS) {
                    throw IllegalStateException("Daemon session limit reached ($MAX_ACTIVE_SESSIONS)")
                }
                activeSessions.add(config.interfaceName)
            }
        } catch (failure: Throwable) {
            scroll["outcome"] = JsonPrimitive("rejected")
            scroll["error_type"] = JsonPrimitive(failure::class.simpleName ?: "Throwable")
            scroll["duration_ms"] = JsonPrimitive((System.nanoTime() - startedAtNanos) / 1_000_000)
            runCatching { scroll.seal(DaemonLogger, success = false) }
            throw failure
        }
        val handle = try {
            adapter.startSession(config)
        } catch (failure: Throwable) {
            synchronized(activeSessionLock) {
                activeSessions.remove(config.interfaceName)
            }
            scroll["outcome"] = JsonPrimitive("start_failed")
            scroll["error_type"] = JsonPrimitive(failure::class.simpleName ?: "Throwable")
            scroll["duration_ms"] = JsonPrimitive((System.nanoTime() - startedAtNanos) / 1_000_000)
            runCatching { scroll.seal(DaemonLogger, success = false) }
            throw failure
        }
        scroll["interface"] = JsonPrimitive(handle.interfaceName)

        val readerJob = launch(Dispatchers.IO) {
            while (isActive) {
                val packet = handle.readPacket() ?: break
                if (packet.size > MAX_PACKET_FRAME_SIZE) {
                    continue
                }
                if (packet.isNotEmpty()) {
                    send(packet)
                }
            }
        }

        val writerJob = launch(Dispatchers.IO) {
            outgoingPackets.collect { packet ->
                if (packet.size > MAX_PACKET_FRAME_SIZE) {
                    return@collect
                }
                if (packet.isNotEmpty()) {
                    handle.writePacket(packet)
                }
            }
        }

        awaitClose {
            val closeFailure = runCatching { handle.close() }.exceptionOrNull()
            readerJob.cancel()
            writerJob.cancel()
            synchronized(activeSessionLock) {
                activeSessions.remove(config.interfaceName)
            }
            scroll["duration_ms"] = JsonPrimitive((System.nanoTime() - startedAtNanos) / 1_000_000)
            closeFailure?.let { scroll["error_type"] = JsonPrimitive(it::class.simpleName ?: "Throwable") }
            scroll["outcome"] = JsonPrimitive(if (closeFailure == null) "closed" else "close_failed")
            runCatching { scroll.seal(DaemonLogger, success = closeFailure == null) }
        }
    }.buffer(PACKET_FLOW_BUFFER_CAPACITY)
}
