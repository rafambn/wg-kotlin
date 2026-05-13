package com.rafambn.wgkotlin.daemon.planner

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.command.ProcessOutputModel
import com.rafambn.wgkotlin.daemon.platformAdapter.WindowsPlatformAdapter
import com.rafambn.wgkotlin.daemon.protocol.DnsConfig
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.RealTunHandle
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsPlatformAdapterTest {

    @Test
    fun startSessionOpensHandleBeforeRunningCommandsAndUsesOpenedInterfaceName() = runBlocking {
        var handleOpened = false
        val invocations = mutableListOf<ProcessInvocationModel>()
        val openedHandle = mockk<RealTunHandle>()

        mockkConstructor(RealTunHandle::class)
        try {
            every { openedHandle.interfaceName } returns "wintun-opened"
            coEvery { anyConstructed<RealTunHandle>().openDevice() } answers {
                handleOpened = true
                openedHandle
            }

            val adapter = WindowsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    check(handleOpened) { "Commands must execute only after TUN handle is open" }
                    invocations += invocation
                    ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                },
            )

            val handle = adapter.startSession(
                TunSessionConfig(
                    interfaceName = "requested-wg0",
                    mtu = 1400,
                    addresses = listOf("10.10.10.2/24"),
                    routes = listOf("0.0.0.0/0"),
                    dns = DnsConfig(
                        searchDomains = listOf("corp.local"),
                        servers = listOf("1.1.1.1"),
                    ),
                ),
            )

            assertTrue(handleOpened)
            assertEquals("wintun-opened", handle.interfaceName)
            assertTrue(invocations.isNotEmpty())
            assertTrue(
                invocations.any { invocation ->
                    invocation.binary == CommandBinary.NETSH && invocation.arguments.any { arg ->
                        arg.contains("wintun-opened")
                    }
                },
            )
            assertTrue(
                invocations.any { invocation ->
                    invocation.binary == CommandBinary.POWERSHELL &&
                        invocation.environment.values.any { value -> value.contains("kmpvpn-daemon:wintun-opened") }
                },
            )
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }
}
