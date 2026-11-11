package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Ip
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IpResolutionTest {
    @Test
    fun resolvesIpv4LiteralsToTheirTextRepresentation() {
        for (address in listOf("0.0.0.0", "127.0.0.1", "192.168.128.255", "255.255.255.255")) {
            assertEquals(address, address.resolveToIpString())
        }
    }

    @Test
    fun resolvesIpv6LiteralsToNormalizedText() {
        assertEquals("0:0:0:0:0:0:0:1", "::1".resolveToIpString())
        assertEquals("2001:db8:0:1:0:0:0:80ff", "2001:0DB8:0000:0001::80FF".resolveToIpString())
    }

    @Test
    fun resolvesIpv4ToTheCorrectFamilyAndNetworkOrderBytes() {
        val expected = byteArrayOf(192.toByte(), 168.toByte(), 128.toByte(), 255.toByte())
        val ip = "192.168.128.255".resolveToIp()

        assertEquals(Ip.Value.V4(ByteString(expected)), ip.value)
        assertContentEquals(expected, ip.ipBytes())
    }

    @Test
    fun resolvesIpv6ToTheCorrectFamilyAndNetworkOrderBytes() {
        val expected = byteArrayOf(
            0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 1,
            0, 0, 0, 0, 0, 0, 0x80.toByte(), 0xff.toByte(),
        )
        val ip = "2001:0DB8:0000:0001::80FF".resolveToIp()

        assertEquals(Ip.Value.V6(ByteString(expected)), ip.value)
        assertContentEquals(expected, ip.ipBytes())
    }

    @Test
    fun resolvesLocalhostWithoutExternalDns() {
        val address = "localhost".resolveToIpString()
        val ip = "localhost".resolveToIp()

        assertTrue(InetAddress.getByName(address).isLoopbackAddress)
        assertTrue(InetAddress.getByAddress(requireNotNull(ip.ipBytes())).isLoopbackAddress)
    }

    @Test
    fun rejectsBlankAddressesInsteadOfResolvingLoopback() {
        for (address in listOf("", " ", "\t")) {
            assertFailsWith<IllegalArgumentException> { address.resolveToIpString() }
            assertFailsWith<IllegalArgumentException> { address.resolveToIp() }
            assertFailsWith<IllegalArgumentException> { address.toCidr() }
            assertFailsWith<IllegalArgumentException> { "$address/24".toCidr() }
        }
    }

    @Test
    fun preservesIpv4MappedIpv6FamilyAndCidrPrefix() {
        val bytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 192.toByte(), 0, 2, 1,
        )
        for (address in listOf("::ffff:192.0.2.1", "::ffff:c000:201", "[::ffff:192.0.2.1]")) {
            assertEquals(Ip.Value.V6(ByteString(bytes)), address.resolveToIp().value, address)
            for (prefix in listOf(0u, 96u, 120u, 128u)) {
                val cidr = "$address/$prefix".toCidr()
                assertEquals(Ip.Value.V6(ByteString(bytes)), cidr.ip.value)
                assertEquals(prefix, cidr.prefix)
            }
            assertEquals(128u, address.toCidr().prefix)
            assertEquals("192.0.2.1", address.resolveToIpString())
        }
    }

    @Test
    fun compatibleIpv6LiteralsDoNotBecomeMappedAddresses() {
        val bytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 192.toByte(), 0, 2, 1)

        assertEquals(Ip.Value.V6(ByteString(bytes)), "::192.0.2.1".resolveToIp().value)
        assertEquals(128u, "::192.0.2.1/128".toCidr().prefix)
    }

    @Test
    fun rejectsMalformedAddressLiteralsWithoutADnsLookup() {
        for (address in listOf("2001:db8::gggg", "[::1", "1::2::3")) {
            assertFailsWith<UnknownHostException>(address) { address.resolveToIpString() }
            assertFailsWith<UnknownHostException>(address) { address.resolveToIp() }
            assertFailsWith<UnknownHostException>(address) { "$address/64".toCidr() }
        }
    }
}
