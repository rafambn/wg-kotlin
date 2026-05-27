package com.rafambn.wgkotlin.daemon.protocol.grpc

import com.rafambn.wgkotlin.daemon.proto.DnsConfig
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GrpcProtoWireCompatibilityTest {
    @Test
    fun dnsFixtureDecodesAndReencodes() {
        val fixture = byteArrayOf(
            0x0A,
            0x07,
            0x31,
            0x2E,
            0x31,
            0x2E,
            0x31,
            0x2E,
            0x31,
            0x12,
            0x0C,
            0x63,
            0x6F,
            0x72,
            0x70,
            0x2E,
            0x65,
            0x78,
            0x61,
            0x6D,
            0x70,
            0x6C,
            0x65,
        )

        val decoded = DnsConfig.parseFrom(fixture)

        assertEquals(listOf("1.1.1.1"), decoded.serversList)
        assertEquals(listOf("corp.example"), decoded.searchDomainsList)
        assertContentEquals(fixture, decoded.toByteArray())
    }
}
