package com.rafambn.wgkotlin

import kotlin.test.Test
import kotlin.test.assertEquals

class ParsedVpnConfigurationTest {

    @Test
    fun tunSessionConfigPreservesDnsFields() {
        val parsed = VpnConfiguration(
            interfaceName = "utun123",
            dns = DnsConfig(
                searchDomains = listOf("corp.example", "internal.example"),
                servers = listOf("1.1.1.1", "2001:4860:4860::8888"),
            ),
            privateKey = "private-key",
        ).toParsedVpnConfiguration()

        val sessionConfig = parsed.toTunSessionConfig()

        assertEquals(parsed.dns.searchDomains, sessionConfig.dns.searchDomains)
        assertEquals(parsed.dns.servers, sessionConfig.dns.servers)
    }
}
