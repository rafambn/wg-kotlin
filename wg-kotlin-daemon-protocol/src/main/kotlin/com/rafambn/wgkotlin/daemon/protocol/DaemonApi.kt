package com.rafambn.wgkotlin.daemon.protocol

import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * kRPC control-plane contract shared by daemon server and JVM client.
 *
 * Commands are intentionally split into dedicated RPC methods.
 * Primitive parameters are preferred, except for high-cardinality commands.
 *
 * Interface up/down is implicit: the daemon brings the interface up when the
 * packet stream connects and down when it disconnects.
 */
@Rpc
interface DaemonApi {
    fun startSession(
        config: TunSessionConfig,
        outgoingPackets: Flow<ByteArray>,
    ): Flow<ByteArray>
}
