package com.rafambn.kmpvpn.iface

class InMemoryOwnedTunPort internal constructor(
    override val interfaceName: String,
    private val onClose: () -> Unit,
) : OwnedTunPort {
    private val incomingPackets: ArrayDeque<ByteArray> = ArrayDeque()
    val writtenPackets: MutableList<ByteArray> = mutableListOf()
    private var closed: Boolean = false

    override suspend fun readPacket(): ByteArray? {
        ensureOpen("readPacket")
        return incomingPackets.removeFirstOrNull()?.copyOf()
    }

    override suspend fun writePacket(packet: ByteArray) {
        ensureOpen("writePacket")
        writtenPackets += packet.copyOf()
    }

    fun enqueueIncomingPacket(packet: ByteArray) {
        ensureOpen("enqueueIncomingPacket")
        incomingPackets += packet.copyOf()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        onClose()
    }

    private fun ensureOpen(operation: String) {
        check(!closed) {
            "Cannot execute `$operation` on closed tun port `$interfaceName`"
        }
    }
}
