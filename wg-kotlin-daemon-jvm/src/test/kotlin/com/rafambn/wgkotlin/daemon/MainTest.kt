package com.rafambn.wgkotlin.daemon

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        assertTrue(hasRequiredPrivileges(osName = "Linux", unixUidProvider = { 0L }))
        assertFalse(hasRequiredPrivileges(osName = "Linux", unixUidProvider = { 1000L }))
    }
}
