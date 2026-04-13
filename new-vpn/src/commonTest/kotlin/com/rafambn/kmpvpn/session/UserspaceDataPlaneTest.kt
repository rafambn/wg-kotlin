package com.rafambn.kmpvpn.session

import com.rafambn.kmpvpn.VpnConfiguration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test

class UserspaceDataPlaneTest {

    @Test
    fun inboundOutboundAndPeriodicWorkersRunSideBySide() = runBlocking {
        val inboundStarted = CompletableDeferred<Unit>()
        val outboundStarted = CompletableDeferred<Unit>()
        val periodicStarted = CompletableDeferred<Unit>()
        val packetWorkerGate = CompletableDeferred<Unit>()
        val periodicWorkerGate = CompletableDeferred<Unit>()

        val dataPlane = UserspaceDataPlane(
            configuration = VpnConfiguration(
                interfaceName = "wg-test",
                privateKey = "private-key",
            ),
            onFailure = { throwable ->
                throw AssertionError("Unexpected data plane failure", throwable)
            },
            listenPort = 0,
            idleDelayMillis = 0L,
            periodicIntervalMillis = 1L,
            pollInboundPacketOnce = {
                inboundStarted.complete(Unit)
                packetWorkerGate.await()
                false
            },
            pollOutboundPacketOnce = {
                outboundStarted.complete(Unit)
                packetWorkerGate.await()
                false
            },
            runPeriodicWorkOnce = {
                periodicStarted.complete(Unit)
                periodicWorkerGate.await()
                false
            },
            peerStatsProvider = { emptyList() },
        )

        try {
            withTimeout(1_000L) {
                inboundStarted.await()
                outboundStarted.await()
                periodicStarted.await()
            }
        } finally {
            packetWorkerGate.complete(Unit)
            periodicWorkerGate.complete(Unit)
            dataPlane.close()
        }
    }
}
