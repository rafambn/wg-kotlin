@file:OptIn(kotlinx.rpc.internal.utils.InternalRpcApi::class, kotlinx.rpc.internal.utils.ExperimentalRpcApi::class)

package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.proto.ClientMessage
import com.rafambn.wgkotlin.daemon.proto.ClientMessageInternal
import com.rafambn.wgkotlin.daemon.proto.ServerMessage
import com.rafambn.wgkotlin.daemon.proto.ServerMessageInternal
import com.rafambn.wgkotlin.daemon.proto.ServerMessage.Payload
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray

/** Protobuf frames prefixed by an unsigned four-byte big-endian byte count. */
internal object IpcProtocol {
    const val VERSION: UInt = 2u
    const val MAX_FRAME_SIZE: Int = 4 * 1024 * 1024

    fun readServer(input: InputStream): ServerMessage = ServerMessageInternal.MARSHALLER.decode(readFrame(input), null)
    fun readClient(input: InputStream): ClientMessage = ClientMessageInternal.MARSHALLER.decode(readFrame(input), null)
    fun writeClient(output: OutputStream, message: ClientMessage) = writeFrame(output, ClientMessageInternal.MARSHALLER.encode(message, null))
    fun writeServer(output: OutputStream, message: ServerMessage) = writeFrame(output, ServerMessageInternal.MARSHALLER.encode(message, null))

    fun awaitReady(connection: LocalConnection, expectedPid: Long, timeoutMillis: Int = 5_000) {
        connection.readTimeoutMillis = timeoutMillis
        val ready = readServer(connection.input).payload as? Payload.Ready ?: error("Missing daemon readiness message")
        check(ready.value.protocolVersion == VERSION && ready.value.pid.toLong() == expectedPid) { "Incompatible daemon readiness message" }
    }

    private fun readFrame(input: InputStream): Buffer {
        val stream = DataInputStream(input)
        val size = stream.readInt()
        require(size in 1..MAX_FRAME_SIZE) { "Invalid IPC frame length" }
        val bytes = ByteArray(size)
        stream.readFully(bytes)
        return Buffer().apply { write(bytes) }
    }

    private fun writeFrame(output: OutputStream, encoded: Source) {
        val bytes = encoded.use { it.readByteArray() }
        require(bytes.size in 1..MAX_FRAME_SIZE) { "Invalid IPC frame length" }
        val stream = DataOutputStream(output)
        stream.writeInt(bytes.size)
        stream.write(bytes)
        stream.flush()
    }
}
