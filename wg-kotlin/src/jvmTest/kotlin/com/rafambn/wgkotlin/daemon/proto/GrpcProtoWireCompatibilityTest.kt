package com.rafambn.wgkotlin.daemon.proto

import kotlinx.io.bytestring.ByteString
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(InternalRpcApi::class, ExperimentalRpcApi::class)
class GrpcProtoWireCompatibilityTest {
    @Test
    fun dnsConfigRoundTripPreservesFields() {
        val original = DnsConfig {
            servers = listOf(Ip { value = Ip.Value.V4(ByteString(byteArrayOf(1, 1, 1, 1))) })
            searchDomains = listOf("corp.example")
        }

        val encoded = DnsConfigInternal.MARSHALLER.encode(original, null)
        val decoded = DnsConfigInternal.MARSHALLER.decode(encoded, null)

        assertEquals(original.servers.size, decoded.servers.size)
        val server = decoded.servers.single()
        assertEquals(Ip.Value.V4(ByteString(byteArrayOf(1, 1, 1, 1))), server.value)
        assertEquals(listOf("corp.example"), decoded.searchDomains)
    }
}
