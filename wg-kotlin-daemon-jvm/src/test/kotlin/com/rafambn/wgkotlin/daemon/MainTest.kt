package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.UsageError
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainTest {

    @Test
    fun windowsPrivilegeCheckUsesElevationProbeInsteadOfUsername() {
        var capturedCommand: List<String>? = null

        val isPrivileged = hasRequiredPrivileges(
            osName = "Windows 11",
            commandRunner = { command ->
                capturedCommand = command
                true
            },
        )

        assertTrue(isPrivileged)
        assertTrue(capturedCommand.orEmpty().isNotEmpty())
        assertTrue(capturedCommand.orEmpty().contains("powershell.exe"))
        assertTrue(capturedCommand.orEmpty().contains("-Command"))
        assertTrue(capturedCommand.orEmpty().last().contains("IsSystem"))
        assertTrue(capturedCommand.orEmpty().last().contains("Administrator"))
    }

    @Test
    fun unixPrivilegeCheckUsesUidInsteadOfUsername() {
        assertTrue(hasRequiredPrivileges(osName = "Linux", unixUidProvider = { 0L }, linuxEffectiveCapabilitiesProvider = { null }))
        assertFalse(hasRequiredPrivileges(osName = "Linux", unixUidProvider = { 1000L }, linuxEffectiveCapabilitiesProvider = { null }))
    }

    @Test
    fun linuxPrivilegeCheckAcceptsNetAdminCapabilityWithoutRootUid() {
        assertTrue(
            hasRequiredPrivileges(
                osName = "Linux",
                unixUidProvider = { 1000L },
                linuxEffectiveCapabilitiesProvider = { 1L shl 12 },
            ),
        )
    }

    @Test
    fun daemonRpcAuthRequiresMatchingBearerToken() {
        assertTrue(isAuthorizedDaemonRpcHeader("Bearer secret", "secret"))
        assertFalse(isAuthorizedDaemonRpcHeader(null, "secret"))
        assertFalse(isAuthorizedDaemonRpcHeader("Bearer wrong", "secret"))
    }

    @Test
    fun bindAddressRejectsUnknownHostsWithoutCatchingFatalErrors() {
        assertFailsWith<UsageError> {
            bindAddressOrUsageError("not a host name.invalid")
        }
    }

    @Test
    fun bindAddressAcceptsOnlyIpLiterals() {
        assertEquals("127.0.0.1", bindAddressOrUsageError("127.0.0.1").hostAddress)
        assertFailsWith<UsageError> {
            bindAddressOrUsageError("localhost")
        }
    }
}
