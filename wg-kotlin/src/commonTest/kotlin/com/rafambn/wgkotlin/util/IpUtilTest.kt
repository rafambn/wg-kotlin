package com.rafambn.wgkotlin.util

import com.rafambn.wgkotlin.daemon.proto.Cidr
import com.rafambn.wgkotlin.daemon.proto.Ip
import com.rafambn.wgkotlin.daemon.proto.invoke
import kotlinx.io.bytestring.ByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IpUtilTest {
    private val ipv4Bytes = byteArrayOf(192.toByte(), 168.toByte(), 128.toByte(), 255.toByte())
    private val ipv6Bytes = byteArrayOf(
        0x20, 0x01, 0x0d, 0xb8.toByte(), 0xab.toByte(), 0xcd.toByte(), 0x80.toByte(), 0x01,
        0xff.toByte(), 0x80.toByte(), 0xff.toByte(), 0x00, 0x00, 0x7f, 0xff.toByte(), 0xff.toByte(),
    )
    private val ipv4 = Ip { value = Ip.Value.V4(ByteString(ipv4Bytes)) }
    private val ipv6 = Ip { value = Ip.Value.V6(ByteString(ipv6Bytes)) }

    @Test
    fun parsesIpv4CidrWithoutDiscardingHostBits() {
        val cidr = "192.168.128.255/25".toCidr()

        assertEquals(ipv4, cidr.ip)
        assertEquals(25u, cidr.prefix)
    }

    @Test
    fun parsesIpv6CidrWithoutDiscardingHostBits() {
        val cidr = "2001:db8:abcd:8001:ff80:ff00:7f:ffff/73".toCidr()

        assertEquals(ipv6, cidr.ip)
        assertEquals(73u, cidr.prefix)
    }

    @Test
    fun cidrWithoutPrefixDefaultsToASingleHost() {
        val v4 = "192.168.128.255".toCidr()
        val v6 = "2001:db8:abcd:8001:ff80:ff00:7f:ffff".toCidr()

        assertEquals(ipv4, v4.ip)
        assertEquals(32u, v4.prefix)
        assertEquals(ipv6, v6.ip)
        assertEquals(128u, v6.prefix)
    }

    @Test
    fun cidrAcceptsZeroAndMaximumPrefixesForEachFamily() {
        for (prefix in listOf(0u, 32u)) {
            assertEquals(prefix, "192.0.2.1/$prefix".toCidr().prefix)
        }
        for (prefix in listOf(0u, 128u)) {
            assertEquals(prefix, "2001:db8::1/$prefix".toCidr().prefix)
        }
    }

    @Test
    fun cidrRejectsMalformedAndOutOfRangePrefixes() {
        val invalid = listOf(
            "192.0.2.1/33", "2001:db8::1/129",
            "192.0.2.1/-1", "2001:db8::1/-1",
            "192.0.2.1/", "2001:db8::1/",
            "192.0.2.1/abc", "2001:db8::1/64/1",
            "192.0.2.1/4294967295", "2001:db8::1/4294967296",
        )

        for (address in invalid) {
            assertFailsWith<IllegalArgumentException>(address) { address.toCidr() }
        }
    }

    @Test
    fun formatsIpv4OctetsAsUnsignedNumbers() {
        assertEquals("192.168.128.255", ipv4.toAddressString())
        assertEquals("192.168.128.255", ipv4.toPlainString())
    }

    @Test
    fun formatsIpv6WithBracketsOnlyForSocketAddresses() {
        assertEquals("[2001:db8:abcd:8001:ff80:ff00:7f:ffff]", ipv6.toAddressString())
        assertEquals("2001:db8:abcd:8001:ff80:ff00:7f:ffff", ipv6.toPlainString())
        val unspecified = Ip { value = Ip.Value.V6(ByteString(ByteArray(16))) }
        assertEquals("[0:0:0:0:0:0:0:0]", unspecified.toAddressString())
        assertEquals("0:0:0:0:0:0:0:0", unspecified.toPlainString())
    }

    @Test
    fun formatsCidrWithItsOriginalAddressAndPrefix() {
        assertEquals("192.168.128.255/25", Cidr { ip = ipv4; prefix = 25u }.toCidrString())
        assertEquals(
            "2001:db8:abcd:8001:ff80:ff00:7f:ffff/73",
            Cidr { ip = ipv6; prefix = 73u }.toCidrString(),
        )
    }

    @Test
    fun formattingRejectsAnIpWithoutAValue() {
        val empty = Ip { }

        assertFailsWith<IllegalStateException> { empty.toAddressString() }
        assertFailsWith<IllegalStateException> { empty.toPlainString() }
        assertFailsWith<IllegalStateException> { Cidr { ip = empty }.toCidrString() }
    }

    @Test
    fun mappedIpv6UsesIpv4OnlyForTransport() {
        val bytes = byteArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 192.toByte(), 0, 2, 1,
        )
        val mapped = Ip { value = Ip.Value.V6(ByteString(bytes)) }

        assertEquals(Ip.Value.V4(ByteString(byteArrayOf(192.toByte(), 0, 2, 1))), mapped.toTransportIp().value)
        assertEquals("0:0:0:0:0:ffff:c000:201", mapped.toPlainString())
        assertEquals("[0:0:0:0:0:ffff:c000:201]", mapped.toAddressString())
        assertContentEquals(bytes, mapped.ipBytes())
        assertTrue(Cidr { ip = mapped; prefix = 128u }.matches(mapped))
    }

    @Test
    fun ordinaryAndCompatibleIpv6AddressesKeepTheirTransportFamily() {
        assertSame(ipv4, ipv4.toTransportIp())
        assertSame(ipv6, ipv6.toTransportIp())
        val compatible = Ip {
            value = Ip.Value.V6(ByteString(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 192.toByte(), 0, 2, 1)))
        }
        val similar = Ip {
            value = Ip.Value.V6(ByteString(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff.toByte(), 0xff.toByte(), 192.toByte(), 0, 2, 1)))
        }
        assertSame(compatible, compatible.toTransportIp())
        assertSame(similar, similar.toTransportIp())
        assertFailsWith<IllegalStateException> { Ip { }.toTransportIp() }
    }

    @Test
    fun extractsIpv4SourceAndDestinationFromTheirHeaderOffsets() {
        val source = byteArrayOf(192.toByte(), 0, 2, 17)
        val destination = byteArrayOf(203.toByte(), 0, 113, 254.toByte())
        for (size in listOf(20, 48)) {
            val packet = ByteArray(size) { 0x55 }
            packet[0] = if (size == 20) 0x45 else 0x46 // Include IPv4 options in the longer packet.
            source.copyInto(packet, 12)
            destination.copyInto(packet, 16)
            val original = packet.copyOf()

            assertEquals(Ip.Value.V4(ByteString(source)), parsePacketSource(packet).value)
            assertEquals(Ip.Value.V4(ByteString(destination)), parsePacketDestination(packet).value)
            assertContentEquals(original, packet)
        }
    }

    @Test
    fun extractsIpv6SourceAndDestinationFromTheirHeaderOffsets() {
        val source = ipv6Bytes
        val destination = byteArrayOf(
            0xfd.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1,
        )
        for (size in listOf(40, 64)) {
            val packet = ByteArray(size) { 0x55 }
            packet[0] = 0x6f
            source.copyInto(packet, 8)
            destination.copyInto(packet, 24)
            val original = packet.copyOf()

            assertEquals(Ip.Value.V6(ByteString(source)), parsePacketSource(packet).value)
            assertEquals(Ip.Value.V6(ByteString(destination)), parsePacketDestination(packet).value)
            assertContentEquals(original, packet)
        }
    }

    @Test
    fun packetReadersRejectEmptyPackets() {
        assertFailsWith<IllegalStateException> { parsePacketSource(byteArrayOf()) }
        assertFailsWith<IllegalStateException> { parsePacketDestination(byteArrayOf()) }
    }

    @Test
    fun packetReadersRejectEveryTruncatedHeaderLength() {
        for ((header, minimumSize) in listOf(0x45 to 20, 0x60 to 40)) {
            for (size in 1 until minimumSize) {
                val packet = ByteArray(size)
                packet[0] = header.toByte()

                assertFailsWith<IllegalArgumentException>("source, header $header, size $size") {
                    parsePacketSource(packet)
                }
                assertFailsWith<IllegalArgumentException>("destination, header $header, size $size") {
                    parsePacketDestination(packet)
                }
            }
        }
    }

    @Test
    fun packetReadersRejectUnknownIpVersions() {
        for (version in (0..15).filter { it != 4 && it != 6 }) {
            val packet = ByteArray(40)
            packet[0] = ((version shl 4) or 0x0f).toByte()

            assertFailsWith<IllegalStateException>("source, version $version") { parsePacketSource(packet) }
            assertFailsWith<IllegalStateException>("destination, version $version") { parsePacketDestination(packet) }
        }
    }

    @Test
    fun matchesIpv4NetworksAcrossByteAndBitBoundaries() {
        val cases = listOf(
            Triple("192.0.2.129/24", "192.0.2.0", true),
            Triple("192.0.2.129/24", "192.0.2.255", true),
            Triple("192.0.2.129/24", "192.0.3.1", false),
            Triple("192.0.2.255/25", "192.0.2.128", true),
            Triple("192.0.2.255/25", "192.0.2.255", true),
            Triple("192.0.2.255/25", "192.0.2.127", false),
            Triple("192.0.2.3/31", "192.0.2.2", true),
            Triple("192.0.2.3/31", "192.0.2.3", true),
            Triple("192.0.2.3/31", "192.0.2.4", false),
            Triple("255.0.0.0/1", "128.0.0.1", true),
            Triple("255.0.0.0/1", "127.255.255.255", false),
        )

        for ((network, address, expected) in cases) {
            assertEquals(expected, network.toCidr().matches(address.resolveToIp()), "$network matches $address")
        }
    }

    @Test
    fun matchesIpv6NetworksAcrossByteAndBitBoundaries() {
        val cases = listOf(
            Triple("2001:db8:abcd:1234::ffff/64", "2001:db8:abcd:1234::", true),
            Triple("2001:db8:abcd:1234::ffff/64", "2001:db8:abcd:1235::1", false),
            Triple("2001:db8:abcd:1234:ffff::/65", "2001:db8:abcd:1234:8000::", true),
            Triple("2001:db8:abcd:1234:ffff::/65", "2001:db8:abcd:1234:7fff::", false),
            Triple("2001:db8:abcd:1234:ffff::/73", "2001:db8:abcd:1234:ff80::", true),
            Triple("2001:db8:abcd:1234:ffff::/73", "2001:db8:abcd:1234:ff7f::", false),
            Triple("2001:db8::3/127", "2001:db8::2", true),
            Triple("2001:db8::3/127", "2001:db8::3", true),
            Triple("2001:db8::3/127", "2001:db8::4", false),
        )

        for ((network, address, expected) in cases) {
            assertEquals(expected, network.toCidr().matches(address.resolveToIp()), "$network matches $address")
        }
    }

    @Test
    fun zeroPrefixesMatchAllAddressesOfTheSameFamily() {
        val v4 = "192.0.2.1/0".toCidr()
        val v6 = "2001:db8::1/0".toCidr()

        assertTrue(v4.matches("0.0.0.0".resolveToIp()))
        assertTrue(v4.matches("255.255.255.255".resolveToIp()))
        assertTrue(v6.matches("::".resolveToIp()))
        assertTrue(v6.matches("ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff".resolveToIp()))
        assertFalse(v4.matches(ipv6))
        assertFalse(v6.matches(ipv4))
    }

    @Test
    fun maximumPrefixesMatchOnlyTheExactHost() {
        val v4 = "192.0.2.1/32".toCidr()
        val v6 = "2001:db8::1/128".toCidr()

        assertTrue(v4.matches("192.0.2.1".resolveToIp()))
        assertFalse(v4.matches("192.0.2.0".resolveToIp()))
        assertTrue(v6.matches("2001:db8::1".resolveToIp()))
        assertFalse(v6.matches("2001:db8::2".resolveToIp()))
    }

    @Test
    fun matchingReturnsFalseWhenEitherIpIsUnset() {
        val empty = Ip { }

        assertFalse(Cidr { ip = empty; prefix = 24u }.matches(ipv4))
        assertFalse("192.0.2.0/24".toCidr().matches(empty))
    }

    @Test
    fun directCidrsRejectInvalidPrefixesBeforeIndexingOrIntConversion() {
        for (ip in listOf(ipv4, ipv6)) {
            val firstInvalid = if (ip.value is Ip.Value.V4) 33u else 129u
            for (invalid in listOf(firstInvalid, Int.MAX_VALUE.toUInt(), 0x80000000u, 4294967288u, UInt.MAX_VALUE)) {
                val cidr = Cidr { this.ip = ip; prefix = invalid }

                assertFailsWith<IllegalArgumentException>("matches $invalid") { cidr.matches(ip) }
                assertFailsWith<IllegalArgumentException>("normalizedKey $invalid") { cidr.normalizedKey() }
                assertFailsWith<IllegalArgumentException>("toCidrString $invalid") { cidr.toCidrString() }
            }
        }
    }

    @Test
    fun rejectsIncorrectByteLengthsInDirectIpValues() {
        val invalid = listOf(0, 1, 3, 5, 16).map { size ->
            Ip { value = Ip.Value.V4(ByteString(ByteArray(size))) }
        } + listOf(0, 4, 15, 17).map { size ->
            Ip { value = Ip.Value.V6(ByteString(ByteArray(size))) }
        }
        for (ip in invalid) {
            val cidr = Cidr { this.ip = ip; prefix = 0u }
            assertFailsWith<IllegalArgumentException> { ip.ipBytes() }
            assertFailsWith<IllegalArgumentException> { ip.toPlainString() }
            assertFailsWith<IllegalArgumentException> { ip.toAddressString() }
            assertFailsWith<IllegalArgumentException> { ip.toTransportIp() }
            assertFailsWith<IllegalArgumentException> { cidr.matches(ipv4) }
            assertFailsWith<IllegalArgumentException> { "0.0.0.0/0".toCidr().matches(ip) }
            assertFailsWith<IllegalArgumentException> { cidr.normalizedKey() }
        }
    }

    @Test
    fun normalizedIpv4KeysRemoveHostBitsAcrossPrefixBoundaries() {
        val cases = listOf(
            "192.168.5.255/0" to "v4/0:00000000",
            "255.255.255.255/1" to "v4/1:80000000",
            "192.168.5.255/24" to "v4/24:c0a80500",
            "192.168.5.255/25" to "v4/25:c0a80580",
            "192.0.2.3/31" to "v4/31:c0000202",
            "192.168.5.255/32" to "v4/32:c0a805ff",
        )

        for ((network, expected) in cases) {
            assertEquals(expected, network.toCidr().normalizedKey(), network)
        }
    }

    @Test
    fun normalizedIpv6KeysRemoveHostBitsAcrossPrefixBoundaries() {
        val cases = listOf(
            "2001:db8:abcd:1234:ffff:ffff:ffff:ffff/0" to "v6/0:00000000000000000000000000000000",
            "2001:db8:abcd:1234:ffff:ffff:ffff:ffff/64" to "v6/64:20010db8abcd12340000000000000000",
            "2001:db8:abcd:1234:ffff:ffff:ffff:ffff/65" to "v6/65:20010db8abcd12348000000000000000",
            "2001:db8:abcd:1234:ffff:ffff:ffff:ffff/73" to "v6/73:20010db8abcd1234ff80000000000000",
            "2001:db8:abcd:1234:ffff:ffff:ffff:ffff/127" to "v6/127:20010db8abcd1234fffffffffffffffe",
            "2001:db8:abcd:1234:ffff:ffff:ffff:ffff/128" to "v6/128:20010db8abcd1234ffffffffffffffff",
        )

        for ((network, expected) in cases) {
            assertEquals(expected, network.toCidr().normalizedKey(), network)
        }
    }

    @Test
    fun normalizedKeysIdentifyEquivalentNetworksAndDistinguishDifferentPrefixes() {
        assertEquals("192.0.2.0/24".toCidr().normalizedKey(), "192.0.2.255/24".toCidr().normalizedKey())
        assertEquals("2001:db8::/64".toCidr().normalizedKey(), "2001:db8::ffff/64".toCidr().normalizedKey())
        assertNotEquals("192.0.2.0/24".toCidr().normalizedKey(), "192.0.2.0/25".toCidr().normalizedKey())
        assertNotEquals("192.0.2.0/24".toCidr().normalizedKey(), "192.0.3.0/24".toCidr().normalizedKey())
    }

    @Test
    fun normalizingDoesNotMutateTheOriginalAddress() {
        val network = Cidr { ip = ipv6; prefix = 65u }

        network.normalizedKey()

        assertContentEquals(ipv6Bytes, network.ip.ipBytes())
    }

    @Test
    fun normalizationRejectsAnIpWithoutAValue() {
        assertFailsWith<IllegalStateException> { Cidr { ip = Ip { }; prefix = 24u }.normalizedKey() }
    }

    @Test
    fun extractsRawBytesForBothFamiliesAndNullForAnUnsetIp() {
        assertContentEquals(ipv4Bytes, ipv4.ipBytes())
        assertContentEquals(ipv6Bytes, ipv6.ipBytes())
        assertNull(Ip { }.ipBytes())
    }

    @Test
    fun extractedByteArraysDoNotExposeMutableIpStorage() {
        for ((ip, expected) in listOf(ipv4 to ipv4Bytes, ipv6 to ipv6Bytes)) {
            val bytes = requireNotNull(ip.ipBytes())
            bytes[0] = 0

            assertContentEquals(expected, ip.ipBytes())
        }
    }
}
