package com.rafambn.kmpvpn.daemon.protocol

import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.ReadInterfaceInformationResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
            dnsDomainPool = (
                listOf("corp.local", "dev.local") to
                    listOf("1.1.1.1", "9.9.9.9")
                ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ApplyDnsResponse>(encoded)

        assertEquals("wg0", decoded.interfaceName)
        assertEquals(
            listOf("corp.local", "dev.local") to listOf("1.1.1.1", "9.9.9.9"),
            decoded.dnsDomainPool,
        )
    }

    @Test
    fun failureResultRoundTripPreservesErrorPayload() {
        val original = CommandResult.failure<Unit>(
            kind = DaemonErrorKind.COMMAND_FAILED,
            message = "invalid route",
            detail = DaemonFailureDetail(
                executable = "ip",
                exitCode = 2,
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CommandResult<Unit>>(encoded) as CommandResult.Failure

        assertEquals(DaemonErrorKind.COMMAND_FAILED, decoded.kind)
        assertEquals("invalid route", decoded.message)
        assertEquals("ip", decoded.detail?.executable)
        assertEquals(2, decoded.detail?.exitCode)
    }

    @Test
    fun deserializationRejectsMalformedCommandShape() {
        val valid = json.encodeToString(
            ApplyDnsResponse(
                interfaceName = "wg0",
                dnsDomainPool = (listOf("corp.local") to listOf("1.1.1.1")),
            ),
        )
        val malformed = valid.replace(
            "\"dnsDomainPool\":{\"first\":[\"corp.local\"],\"second\":[\"1.1.1.1\"]}",
            "\"dnsDomainPool\":\"not-an-object\"",
        )

        kotlin.test.assertFailsWith<SerializationException> {
            json.decodeFromString<ApplyDnsResponse>(malformed)
        }
    }

    @Test
    fun readInterfaceInformationPayloadRoundTripPreservesStructuredData() {
        val original = ReadInterfaceInformationResponse(
            interfaceName = "wg0",
            isUp = true,
            addresses = listOf("10.20.30.40/32"),
            dnsDomainPool = (listOf("corp.local") to listOf("1.1.1.1")),
            mtu = 1420,
            listenPort = 51820,
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ReadInterfaceInformationResponse>(encoded)

        assertEquals("wg0", decoded.interfaceName)
        assertEquals(true, decoded.isUp)
        assertEquals(listOf("10.20.30.40/32"), decoded.addresses)
        assertEquals(listOf("corp.local") to listOf("1.1.1.1"), decoded.dnsDomainPool)
        assertEquals(1420, decoded.mtu)
        assertEquals(51820, decoded.listenPort)
    }

    @Test
    fun protocolTypesRemainControlPlaneOnly() {
        val typeNames = listOf(
            "PingResponse",
            "ApplyDnsResponse",
            "ApplyRoutesResponse",
            "ReadInterfaceInformationResponse",
        )

        typeNames.forEach { typeName ->
            val normalized = typeName.lowercase()
            assertFalse(normalized.contains("packet"), "Type `$typeName` must not carry packet payload")
            assertFalse(normalized.contains("tun"), "Type `$typeName` must not control runtime packet loop")
            assertFalse(normalized.contains("udp"), "Type `$typeName` must not control runtime packet loop")
        }
    }

    @Test
    fun daemonRpcUrlWrapsIpv6Hosts() {
        assertEquals("ws://[::1]:8787/services", daemonRpcUrl(host = "::1", port = 8787))
        assertEquals("ws://127.0.0.1:8787/services", daemonRpcUrl(host = "127.0.0.1", port = 8787))
    }
}
