package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.CommandBinary
import com.rafambn.wgkotlin.daemon.command.CommandFailed
import com.rafambn.wgkotlin.daemon.command.ProcessInvocationModel
import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.command.ProcessOutputModel
import com.rafambn.wgkotlin.daemon.protocol.DnsConfig
import com.rafambn.wgkotlin.daemon.protocol.TunSessionConfig
import com.rafambn.wgkotlin.daemon.tun.RealTunHandle
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsPlatformAdapterTest {

    @Test
    fun constructionDoesNotRunPrivilegedCommands() {
        val invocations = mutableListOf<ProcessInvocationModel>()

        WindowsPlatformAdapter(
            processLauncher = ProcessLauncher { invocation ->
                invocations += invocation
                ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
            },
        )

        assertTrue(invocations.isEmpty())
    }

    @Test
    fun startSessionCleansUpStaleNrptRulesOnceBeforeOpeningHandle() = runBlocking {
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
                    if (invocation.environment.containsKey("WG_KOTLIN_NRPT_COMMENT_PREFIX")) {
                        assertFalse(handleOpened)
                    }
                    invocations += invocation
                    ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                },
            )

            val config = TunSessionConfig(
                interfaceName = "requested-wg0",
                addresses = listOf("10.10.10.2/24"),
            )
            adapter.startSession(config)
            adapter.startSession(config)

            val staleCleanupInvocations = invocations.filter { invocation ->
                invocation.binary == CommandBinary.POWERSHELL &&
                    invocation.environment["WG_KOTLIN_NRPT_COMMENT_PREFIX"] == "kmpvpn-daemon:"
            }

            assertEquals(1, staleCleanupInvocations.size)
            assertTrue(staleCleanupInvocations.single().arguments.last().contains("Remove-DnsClientNrptRule"))
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

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
                    // Stale NRPT cleanup runs before opening the handle;
                    // NETSH commands require the TUN handle to be open.
                    if (invocation.binary == CommandBinary.NETSH) {
                        check(handleOpened) { "NETSH commands must execute only after TUN handle is open" }
                    }
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
            val routeCommands = invocations
                .filter { invocation -> invocation.binary == CommandBinary.NETSH && invocation.arguments.contains("route") }
                .map { invocation -> invocation.arguments[2] }
            assertEquals(listOf("delete", "add"), routeCommands)
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

    @Test
    fun startSessionAppliesMtuForIpv6Addresses() = runBlocking {
        val invocations = mutableListOf<ProcessInvocationModel>()
        val openedHandle = mockk<RealTunHandle>()

        mockkConstructor(RealTunHandle::class)
        try {
            every { openedHandle.interfaceName } returns "wintun-opened"
            coEvery { anyConstructed<RealTunHandle>().openDevice() } returns openedHandle

            val adapter = WindowsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    invocations += invocation
                    ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                },
            )

            adapter.startSession(
                TunSessionConfig(
                    interfaceName = "requested-wg0",
                    mtu = 1400,
                    addresses = listOf("10.10.10.2/24", "fd00::2/64"),
                ),
            )

            assertTrue(invocations.any { invocation -> invocation.arguments.take(3) == listOf("interface", "ipv4", "set") })
            assertTrue(invocations.any { invocation -> invocation.arguments.take(3) == listOf("interface", "ipv6", "set") })
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

    @Test
    fun startSessionUsesActiveStoreForWindowsAddressesAndRoutes() = runBlocking {
        val invocations = mutableListOf<ProcessInvocationModel>()
        val openedHandle = mockk<RealTunHandle>()

        mockkConstructor(RealTunHandle::class)
        try {
            every { openedHandle.interfaceName } returns "wintun-opened"
            coEvery { anyConstructed<RealTunHandle>().openDevice() } returns openedHandle

            val adapter = WindowsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    invocations += invocation
                    ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                },
            )

            adapter.startSession(
                TunSessionConfig(
                    interfaceName = "requested-wg0",
                    addresses = listOf("10.10.10.2/24", "fd00::2/64"),
                    routes = listOf("10.20.0.0/16", "fd01::/64"),
                ),
            )

            val addAddressCommands = invocations.filter { invocation ->
                invocation.binary == CommandBinary.NETSH &&
                    invocation.arguments[2] == "add" &&
                    invocation.arguments[3] == "address"
            }
            val addRouteCommands = invocations.filter { invocation ->
                invocation.binary == CommandBinary.NETSH &&
                    invocation.arguments[2] == "add" &&
                    invocation.arguments[3] == "route"
            }

            assertEquals(2, addAddressCommands.size)
            assertEquals(2, addRouteCommands.size)
            assertTrue(addAddressCommands.all { invocation -> invocation.arguments.contains("store=active") })
            assertTrue(addRouteCommands.all { invocation -> invocation.arguments.contains("store=active") })
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }

    @Test
    fun closeDeletesWindowsAddressesAndClearsNrptRulesWhenRouteCleanupFails() = runBlocking {
        var failCleanupRouteDelete = false
        val cleanupInvocations = mutableListOf<ProcessInvocationModel>()
        val openedHandle = mockk<RealTunHandle>()

        mockkConstructor(RealTunHandle::class)
        try {
            every { openedHandle.interfaceName } returns "wintun-opened"
            every { openedHandle.close() } just runs
            coEvery { anyConstructed<RealTunHandle>().openDevice() } returns openedHandle

            val adapter = WindowsPlatformAdapter(
                processLauncher = ProcessLauncher { invocation ->
                    if (failCleanupRouteDelete) {
                        cleanupInvocations += invocation
                    }
                    if (
                        failCleanupRouteDelete &&
                        invocation.binary == CommandBinary.NETSH &&
                        (
                            invocation.arguments.take(4) == listOf("interface", "ipv4", "delete", "route") ||
                                (
                                    invocation.arguments.take(4) == listOf("interface", "ipv4", "delete", "address") &&
                                        invocation.arguments.contains("store=active")
                                    )
                            )
                    ) {
                        ProcessOutputModel(exitCode = 1, stdout = "", stderr = "cleanup failed")
                    } else {
                        ProcessOutputModel(exitCode = 0, stdout = "", stderr = "")
                    }
                },
            )

            val handle = adapter.startSession(
                TunSessionConfig(
                    interfaceName = "requested-wg0",
                    addresses = listOf("10.10.10.2/24", "fd00::2/64"),
                    routes = listOf("10.20.0.0/16"),
                    dns = DnsConfig(
                        searchDomains = listOf("corp.local"),
                        servers = listOf("1.1.1.1"),
                    ),
                ),
            )

            failCleanupRouteDelete = true
            assertFailsWith<CommandFailed> {
                handle.close()
            }

            verify(exactly = 1) { openedHandle.close() }
            assertTrue(
                cleanupInvocations.any { invocation ->
                    invocation.binary == CommandBinary.NETSH &&
                        invocation.arguments.take(4) == listOf("interface", "ipv4", "delete", "address") &&
                        invocation.arguments.contains("address=10.10.10.2") &&
                        invocation.arguments.contains("store=active")
                },
            )
            assertTrue(
                cleanupInvocations.any { invocation ->
                    invocation.binary == CommandBinary.NETSH &&
                        invocation.arguments.take(4) == listOf("interface", "ipv4", "delete", "address") &&
                        invocation.arguments.contains("address=10.10.10.2") &&
                        invocation.arguments.contains("store=persistent")
                },
            )
            assertTrue(
                cleanupInvocations.any { invocation ->
                    invocation.binary == CommandBinary.NETSH &&
                        invocation.arguments.take(4) == listOf("interface", "ipv6", "delete", "address") &&
                        invocation.arguments.contains("address=fd00::2") &&
                        invocation.arguments.contains("store=active")
                },
            )
            assertTrue(
                cleanupInvocations.any { invocation ->
                    invocation.binary == CommandBinary.POWERSHELL &&
                        invocation.arguments.last().contains("Get-DnsClientNrptRule")
                },
            )
        } finally {
            unmockkConstructor(RealTunHandle::class)
        }
    }
}
