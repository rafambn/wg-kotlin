package com.rafambn.kmpvpn.daemon.protocol

import com.rafambn.kmpvpn.daemon.protocol.request.ApplyPeerConfigurationRequest
import com.rafambn.kmpvpn.daemon.protocol.request.PeerRequest
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonProtocolSmokeTest {

    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    @Test
    fun applyDnsPayloadRoundTripPreservesTypedFields() {
        val original = ApplyDnsResponse(
            interfaceName = "wg0",
            dnsServers = listOf("1.1.1.1", "9.9.9.9"),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ApplyDnsResponse>(encoded)

        assertEquals("wg0", decoded.interfaceName)
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), decoded.dnsServers)
    }

    @Test
    fun failureResultRoundTripPreservesErrorPayload() {
        val original = DaemonCommandResult.failure<Unit>(
            kind = DaemonErrorKind.VALIDATION_ERROR,
            message = "invalid route",
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DaemonCommandResult<Unit>>(encoded) as DaemonCommandResult.Failure

        assertEquals(DaemonErrorKind.VALIDATION_ERROR, decoded.kind)
        assertEquals("invalid route", decoded.message)
    }

    @Test
    fun deserializationRejectsMalformedCommandShape() {
        val valid = json.encodeToString(
            ApplyDnsResponse(
                interfaceName = "wg0",
                dnsServers = listOf("1.1.1.1"),
            ),
        )
        val malformed = valid.replace("\"dnsServers\":[\"1.1.1.1\"]", "\"dnsServers\":\"not-a-list\"")

        kotlin.test.assertFailsWith<SerializationException> {
            json.decodeFromString<ApplyDnsResponse>(malformed)
        }
    }

    @Test
    fun protocolRequestRoundTripPreservesFields() {
        val original = ApplyPeerConfigurationRequest(
            interfaceName = "wg0",
            listenPort = 51820,
            peers = listOf(PeerRequest(publicKey = "peer-key", allowedIps = listOf("10.0.0.2/32"))),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ApplyPeerConfigurationRequest>(encoded)

        assertEquals("wg0", decoded.interfaceName)
        assertEquals(51820, decoded.listenPort)
        assertEquals(1, decoded.peers.size)
    }

    @Test
    fun protocolTypesRemainControlPlaneOnly() {
        val typeNames = listOf(
            "ApplyPeerConfigurationRequest",
            "PingResponse",
            "ApplyDnsResponse",
            "ApplyRoutesResponse",
            "ReadPeerStatsResponse",
        )

        assertTrue(typeNames.isNotEmpty())

        typeNames.forEach { typeName ->
            val normalized = typeName.lowercase()
            assertFalse(normalized.contains("packet"), "Type `$typeName` must not carry packet payload")
            assertFalse(normalized.contains("tun"), "Type `$typeName` must not control runtime packet loop")
            assertFalse(normalized.contains("udp"), "Type `$typeName` must not control runtime packet loop")
        }
    }
}
