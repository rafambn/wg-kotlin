package com.rafambn.wgkotlin.daemon

import com.rafambn.wgkotlin.daemon.protocol.DnsConfig
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DaemonPayloadValidatorTest {

    @Test
    fun validateDnsAcceptsMaxEntries() {
        val dns = DnsConfig(
            searchDomains = List(64) { index -> "corp$index.local" },
            servers = List(64) { "1.1.1.1" },
        )
        DaemonPayloadValidator.validateDns(dns)
    }

    @Test
    fun validateDnsRejectsAboveMaxEntries() {
        val dns = DnsConfig(
            searchDomains = List(65) { index -> "corp$index.local" },
            servers = listOf("1.1.1.1"),
        )
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validateDns(dns)
        }
    }

    @Test
    fun validateAddressesRejectsAboveMaxEntries() {
        val addresses = List(65) { index -> "10.0.0.${index % 256}/32" }
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validateAddresses(addresses)
        }
    }

    @Test
    fun validateAddressesAndRoutesAcceptIpv6Cidrs() {
        DaemonPayloadValidator.validateAddresses(listOf("fd00::1/128"))
        DaemonPayloadValidator.validateRoutes(listOf("::/0"))
    }

    @Test
    fun validateRoutesRejectsAboveMaxEntries() {
        val routes = List(257) { index -> "10.10.${index / 256}.${index % 256}/32" }
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validateRoutes(routes)
        }
    }

    @Test
    fun validateInterfaceNameAcceptsCrossPlatformSafeNames() {
        DaemonPayloadValidator.validateInterfaceName("wg0")
        DaemonPayloadValidator.validateInterfaceName("utun7")
        DaemonPayloadValidator.validateInterfaceName("vpn-prod_1")
    }

    @Test
    fun validateInterfaceNameRejectsUnsafeCharacters() {
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validateInterfaceName("wg invalid")
        }
    }

    @Test
    fun validateSessionRejectsIncompleteDns() {
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validate(
                TunSessionConfig(
                    interfaceName = "wg0",
                    addresses = listOf("10.0.0.1/24"),
                    dns = DnsConfig(searchDomains = listOf("corp.local")),
                ),
            )
        }
    }

    @Test
    fun validateDnsAcceptsIpv6DnsServers() {
        DaemonPayloadValidator.validateDns(
            DnsConfig(
                searchDomains = listOf("corp.local"),
                servers = listOf("2001:4860:4860::8888"),
            ),
        )
    }

    @Test
    fun validateSessionRejectsIpv6MtuBelowProtocolMinimum() {
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validate(
                TunSessionConfig(
                    interfaceName = "wg0",
                    mtu = 1000,
                    addresses = listOf("fd00::1/64"),
                ),
            )
        }
    }

    @Test
    fun validateSessionAllowsIpv4MtuBelowIpv6Minimum() {
        DaemonPayloadValidator.validate(
            TunSessionConfig(
                interfaceName = "wg0",
                mtu = 1000,
                addresses = listOf("10.0.0.1/24"),
            ),
        )
    }
}
