package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonMainTest {

    @Test
    fun cliVersionFlagPrintsVersionAndExits() {
        val failure = assertFailsWith<PrintMessage> {
            DaemonCli().parse(arrayOf("--version"))
        }
        assertTrue(failure.message!!.startsWith("vpn-daemon"))
        assertTrue(failure.message!!.contains(DAEMON_VERSION))
    }

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
