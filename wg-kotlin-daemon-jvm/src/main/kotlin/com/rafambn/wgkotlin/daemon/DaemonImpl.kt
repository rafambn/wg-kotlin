package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapter
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapterFactory
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DaemonImpl internal constructor(
    private val adapter: PlatformAdapter,
) : DaemonApi {
    private val activeSessionLock = Any()
    private val activeSessions = mutableSetOf<String>()

    override fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray> = channelFlow {
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

        val handle = try {
            adapter.startSession(config)
        } catch (failure: Throwable) {
            synchronized(activeSessionLock) {
                activeSessions.remove(config.interfaceName)
            }
            throw failure
        }

        val readerJob = launch(Dispatchers.IO) {
            while (isActive) {
                val packet = handle.readPacket() ?: continue
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
            runCatching { handle.close() }
            readerJob.cancel()
            writerJob.cancel()
            synchronized(activeSessionLock) {
                activeSessions.remove(config.interfaceName)
            }
        }
    }.buffer(PACKET_FLOW_BUFFER_CAPACITY)
}
