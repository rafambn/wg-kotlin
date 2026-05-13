package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapter
import com.rafambn.wgkotlin.daemon.platformAdapter.PlatformAdapterFactory
import com.rafambn.wgkotlin.daemon.protocol.DaemonApi
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal const val MAX_PACKET_FRAME_SIZE: Int = 65535
private const val PACKET_FLOW_BUFFER_CAPACITY: Int = 64

class DaemonImpl internal constructor(
    private val adapter: PlatformAdapter,
) : DaemonApi {
    private val logger = org.slf4j.LoggerFactory.getLogger(DaemonImpl::class.java)
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

        val cleanupStarted = AtomicBoolean(false)

        val readerJob = launch {
            while (isActive) {
                val packet = handle.readPacket() ?: continue
                if (packet.size > MAX_PACKET_FRAME_SIZE) {
                    logger.warn("Dropping oversized packet from TUN: ${packet.size} bytes")
                    continue
                }
                if (packet.isNotEmpty()) {
                    send(packet)
                }
            }
        }

        val writerJob = launch {
            outgoingPackets.collect { packet ->
                if (packet.size > MAX_PACKET_FRAME_SIZE) {
                    logger.warn("Dropping oversized outbound packet: ${packet.size} bytes")
                    return@collect
                }
                if (packet.isNotEmpty()) {
                    handle.writePacket(packet)
                }
            }
        }

        awaitClose {
            if (!cleanupStarted.compareAndSet(false, true)) {
                return@awaitClose
            }
            runCatching { handle.close() }
                .onFailure { failure -> logger.warn("Failed to close TUN handle for ${config.interfaceName}", failure) }
            readerJob.cancel()
            writerJob.cancel()
            synchronized(activeSessionLock) {
                activeSessions.remove(config.interfaceName)
            }
        }
    }.buffer(PACKET_FLOW_BUFFER_CAPACITY)
}
