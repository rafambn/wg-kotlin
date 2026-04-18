package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.CommandFailed
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.command.ProcessOutputModel
import com.rafambn.wgkotlin.daemon.protocol.DnsConfig
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.RealTunHandle
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlatformAdapterFailureCleanupTest {

    @Test
    fun linuxClosesTunHandleWhenConfigurationFails() = runBlocking {
        mockkConstructor(RealTunHandle::class)
        try {
            val openedHandle = mockOpenedTunHandle("linux-opened")
            val adapter = LinuxPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    if (invocation.binary == CommandBinary.IP && invocation.arguments.take(2) == listOf("address", "flush")) {
                        ProcessOutputModel(exitCode = 1, stdout = "", stderr = "flush failed")
                    } else {
                        ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                    }
                },
            )

            assertFailsWith<CommandFailed> {
                adapter.startSession(
                    TunSessionConfig(
                        interfaceName = "wg0",
                        addresses = listOf("10.10.10.2/24"),
                    ),
                )
            }

            verify(exactly = 1) { openedHandle.close() }
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

    @Test
    fun macOsClosesTunHandleWhenConfigurationFails() = runBlocking {
        mockkConstructor(RealTunHandle::class)
        try {
            val openedHandle = mockOpenedTunHandle("utun7")
            val adapter = MacOsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    if (invocation.binary == CommandBinary.ROUTE && invocation.arguments.contains("add")) {
                        ProcessOutputModel(exitCode = 1, stdout = "", stderr = "route failed")
                    } else {
                        ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                    }
                },
            )

            assertFailsWith<CommandFailed> {
                adapter.startSession(
                    TunSessionConfig(
                        interfaceName = "utun7",
                        addresses = listOf("10.10.10.2/24"),
                        routes = listOf("0.0.0.0/0"),
                    ),
                )
            }

            verify(exactly = 1) { openedHandle.close() }
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

    @Test
    fun windowsClosesTunHandleAndClearsNrptRulesWhenNrptSetupFails() = runBlocking {
        val invocations = mutableListOf<ProcessInvocationModel>()

        mockkConstructor(RealTunHandle::class)
        try {
            val openedHandle = mockOpenedTunHandle("wintun-opened")
            val adapter = WindowsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    invocations += invocation
                    if (invocation.binary == CommandBinary.POWERSHELL &&
                        invocation.arguments.last().contains("Add-DnsClientNrptRule")
                    ) {
                        ProcessOutputModel(exitCode = 1, stdout = "", stderr = "nrpt failed")
                    } else {
                        ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                    }
                },
            )

            assertFailsWith<CommandFailed> {
                adapter.startSession(
                    TunSessionConfig(
                        interfaceName = "requested-wg0",
                        addresses = listOf("10.10.10.2/24"),
                        dns = DnsConfig(
                            searchDomains = listOf("corp.local"),
                            servers = listOf("1.1.1.1"),
                        ),
                    ),
                )
            }

            verify(exactly = 1) { openedHandle.close() }
            assertEquals(
                2,
                invocations.count { invocation ->
                    invocation.binary == CommandBinary.POWERSHELL &&
                        invocation.arguments.last().contains("Get-DnsClientNrptRule")
                },
            )
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

    @Test
    fun extractPrimaryIpv4AddressTrimsWhitespace() {
        assertEquals(
            "10.10.10.2" to 24u.toUByte(),
            extractPrimaryIpv4Address(
                TunSessionConfig(
                    interfaceName = "wg0",
                    addresses = listOf(" 10.10.10.2/24 "),
                ),
            ),
        )
    }

    private fun mockOpenedTunHandle(interfaceName: String): RealTunHandle {
        val openedHandle = mockk<RealTunHandle>()
        every { openedHandle.interfaceName } returns interfaceName
        every { openedHandle.close() } just runs
        coEvery { anyConstructed<RealTunHandle>().openDevice() } returns openedHandle
        return openedHandle
    }
}
