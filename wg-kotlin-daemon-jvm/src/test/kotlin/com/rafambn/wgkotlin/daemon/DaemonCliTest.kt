package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DaemonCliTest {

    @Test
    fun cliVersionFlagPrintsVersionAndExits() {
        val failure = assertFailsWith<PrintMessage> {
            DaemonCli().parse(arrayOf("--version"))
        }
        assertTrue(failure.message!!.startsWith("vpn-daemon"))
        assertTrue(failure.message!!.contains(DAEMON_VERSION))
    }
}
