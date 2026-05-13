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
import kotlin.test.assertTrue

class PlatformAdapterFailureCleanupTest {

    @Test
    fun linuxRequiresResolvectlAtDaemonStartup() {
        assertEquals(
            setOf(CommandBinary.IP, CommandBinary.RESOLVECTL),
            LinuxPlatformAdapter(processLauncher = ProcessLauncher { error("unused") }).requiredBinaries,
        )
    }

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
    fun macOsClosesTunHandleAndClearsDnsEntriesWhenConfigurationFails() = runBlocking {
        val invocations = mutableListOf<ProcessInvocationModel>()

        mockkConstructor(RealTunHandle::class)
        try {
            val openedHandle = mockOpenedTunHandle("utun7")
            val adapter = MacOsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    invocations += invocation
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
            assertEquals(
                1,
                invocations.count { invocation ->
                    invocation.binary == CommandBinary.SCUTIL &&
                        invocation.stdin?.contains("remove State:/Network/Interface/utun7") == true
                },
            )
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

    @Test
    fun macOsUsesIpv6AddressFamilyForIpv6Routes() = runBlocking {
        val invocations = mutableListOf<ProcessInvocationModel>()

        mockkConstructor(RealTunHandle::class)
        try {
            mockOpenedTunHandle("utun7")
            val adapter = MacOsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    invocations += invocation
                    ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                },
            )

            adapter.startSession(
                TunSessionConfig(
                    interfaceName = "utun7",
                    addresses = listOf("fd00::2/64"),
                    routes = listOf("::/0"),
                ),
            )

            assertTrue(
                invocations.any { invocation ->
                    invocation.binary == CommandBinary.ROUTE &&
                        invocation.arguments == listOf("-n", "-inet6", "add", "-net", "::/0", "-interface", "utun7")
                },
            )
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
    fun extractPrimaryTunAddressTrimsWhitespace() {
        assertEquals(
            PrimaryTunAddress(
                address = "10.10.10.2",
                prefixLength = 24u.toUByte(),
                family = IpFamily.IPV4,
            ),
            extractPrimaryTunAddress(
                TunSessionConfig(
                    interfaceName = "wg0",
                    addresses = listOf(" 10.10.10.2/24 "),
                ),
            ),
        )
    }

    @Test
    fun extractPrimaryTunAddressSupportsIpv6OnlyConfigurations() {
        assertEquals(
            PrimaryTunAddress(
                address = "fd00::2",
                prefixLength = 64u.toUByte(),
                family = IpFamily.IPV6,
            ),
            extractPrimaryTunAddress(
                TunSessionConfig(
                    interfaceName = "wg0",
                    addresses = listOf("fd00::2/64"),
                ),
            ),
        )
    }

    @Test
    fun extractPrimaryTunAddressRejectsInvalidFirstAddress() {
        assertFailsWith<IllegalArgumentException> {
            extractPrimaryTunAddress(
                TunSessionConfig(
                    interfaceName = "wg0",
                    addresses = listOf("not-a-cidr", "10.10.10.2/24"),
                ),
            )
        }
    }

    private fun mockOpenedTunHandle(interfaceName: String): RealTunHandle {
        val openedHandle = mockk<RealTunHandle>()
        every { openedHandle.interfaceName } returns interfaceName
        every { openedHandle.close() } just runs
        coEvery { anyConstructed<RealTunHandle>().openDevice() } returns openedHandle
        return openedHandle
    }
}
