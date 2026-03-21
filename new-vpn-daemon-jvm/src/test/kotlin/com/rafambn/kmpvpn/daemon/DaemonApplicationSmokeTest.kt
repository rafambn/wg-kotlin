package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.protocol.DAEMON_HELLO_TOKEN
import com.rafambn.kmpvpn.daemon.protocol.DaemonCommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonErrorKind
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DaemonControlPlaneServiceSmokeTest {

    @Test
    fun pingReturnsHello() = runBlocking {
        val response = DaemonControlPlaneServiceImpl().ping(
            nonce = "probe",
        )
        val success = response as DaemonCommandResult.Success<PingResponse>

        assertEquals(DAEMON_HELLO_TOKEN, success.data.helloToken)
    }

    @Test
    fun knownButUnimplementedCommandReturnsPredictableFailure() = runBlocking {
        val response = DaemonControlPlaneServiceImpl().createInterface(
            interfaceName = "wg0",
        )
        val failure = response as DaemonCommandResult.Failure

        assertEquals(DaemonErrorKind.UNKNOWN_COMMAND, failure.kind)
        assertFalse(failure.message.isBlank())
    }

    @Test
    fun invalidInputReturnsValidationFailureInsteadOfThrowing() = runBlocking {
        val response = DaemonControlPlaneServiceImpl().ping(nonce = "")
        val failure = response as DaemonCommandResult.Failure

        assertEquals(DaemonErrorKind.VALIDATION_ERROR, failure.kind)
        assertEquals("Ping nonce cannot be blank", failure.message)
    }
}
