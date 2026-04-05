package com.rafambn.kmpvpn.daemon

import kotlin.test.Test
import kotlin.test.assertFailsWith

class DaemonPayloadValidatorTest {

    @Test
    fun validateDnsDomainPoolAcceptsMaxEntries() {
        val dnsDomainPool = (
            List(64) { index -> "corp$index.local" } to
                List(64) { "1.1.1.1" }
            )
        DaemonPayloadValidator.validateDnsDomainPool(dnsDomainPool)
    }

    @Test
    fun validateDnsDomainPoolRejectsAboveMaxEntries() {
        val dnsDomainPool = (
            List(65) { index -> "corp$index.local" } to
                listOf("1.1.1.1")
            )
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validateDnsDomainPool(dnsDomainPool)
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
    fun validateAddressesRejectsCidrAboveMaxLength() {
        val longCidr = "1".repeat(65)
        assertFailsWith<PayloadValidationException> {
            DaemonPayloadValidator.validateAddresses(listOf(longCidr))
        }
    }
}
