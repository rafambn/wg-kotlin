package com.rafambn.kmpvpn.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DaemonInterfaceInformationParserTest {

    @Test
    fun parseLinuxInterfaceDumpExtractsStateMtuAndAddresses() {
        val parsed = DaemonInterfaceInformationParser.parse(
            platformId = "linux",
            interfaceName = "utun7",
            dump = """
                7: utun7: <POINTOPOINT,MULTICAST,NOARP,UP,LOWER_UP> mtu 1420 qdisc fq_codel state UNKNOWN mode DEFAULT group default qlen 500
                    link/none
                    inet 10.20.30.40/32 scope global utun7
                       valid_lft forever preferred_lft forever
                    inet6 fd00::40/128 scope global
                       valid_lft forever preferred_lft forever
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals("utun7", parsed.interfaceName)
        assertEquals(true, parsed.isUp)
        assertEquals(1420, parsed.mtu)
        assertEquals(listOf("10.20.30.40/32", "fd00::40/128"), parsed.addresses)
    }

    @Test
    fun parseMacOsInterfaceDumpConvertsNetmaskAndPrefixlenToCidr() {
        val parsed = DaemonInterfaceInformationParser.parse(
            platformId = "macos",
            interfaceName = "utun7",
            dump = """
                utun7: flags=8051<UP,POINTOPOINT,RUNNING,MULTICAST> mtu 1380
                    inet 10.20.30.40 --> 10.20.30.40 netmask 0xffffffff
                    inet6 fe80::aede:48ff:fe00:1122%utun7 prefixlen 64 scopeid 0x19
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(true, parsed.isUp)
        assertEquals(1380, parsed.mtu)
        assertEquals(
            listOf(
                "10.20.30.40/32",
                "fe80::aede:48ff:fe00:1122/64",
            ),
            parsed.addresses,
        )
    }

    @Test
    fun parseWindowsNetshDumpDetectsUpState() {
        val parsed = DaemonInterfaceInformationParser.parse(
            platformId = "windows",
            interfaceName = "utun7",
            dump = """
                Admin State    State          Type             Interface Name
                -------------------------------------------------------------------------
                Enabled        Connected      Dedicated        utun7
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(true, parsed.isUp)
        assertEquals(emptyList(), parsed.addresses)
        assertNull(parsed.mtu)
    }

    @Test
    fun parseWindowsNetshDumpDetectsDisabledState() {
        val parsed = DaemonInterfaceInformationParser.parse(
            platformId = "windows",
            interfaceName = "utun7",
            dump = """
                Admin State    State          Type             Interface Name
                -------------------------------------------------------------------------
                Disabled       Disconnected   Dedicated        utun7
            """.trimIndent(),
        )

        assertNotNull(parsed)
        assertEquals(false, parsed.isUp)
    }
}
