package com.rafambn.kmpvpn.daemon

import com.rafambn.kmpvpn.daemon.command.CommandBinary
import com.rafambn.kmpvpn.daemon.command.ProcessInvocationModel
import com.rafambn.kmpvpn.daemon.command.ProcessLauncher
import com.rafambn.kmpvpn.daemon.command.ProcessOutputModel
import com.rafambn.kmpvpn.daemon.command.StartFailure
import com.rafambn.kmpvpn.daemon.command.TimeoutFailure
import com.rafambn.kmpvpn.daemon.planner.LinuxOperationPlanner
import com.rafambn.kmpvpn.daemon.planner.PlatformOperationPlanner
import com.rafambn.kmpvpn.daemon.planner.WindowsOperationPlanner
import com.rafambn.kmpvpn.daemon.protocol.CommandResult
import com.rafambn.kmpvpn.daemon.protocol.DaemonErrorKind
import com.rafambn.kmpvpn.daemon.protocol.response.ApplyDnsResponse
import com.rafambn.kmpvpn.daemon.protocol.response.PingResponse
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DaemonProcessApiSmokeTest {

    @Test
    fun pingReturnsSuccess() = runBlocking {
        val response = daemonApi(
            launcher = RecordingLauncher(),
        ).ping()

        assertTrue(response.isSuccess)
        assertEquals(PingResponse, (response as CommandResult.Success).data)
    }

    @Test
    fun applyMtuUsesAllowlistedCommand() = runBlocking {
        val launcher = RecordingLauncher()
        val response = daemonApi(
            launcher = launcher,
        ).applyMtu(interfaceName = "utun0", mtu = 1420)

        assertTrue(response.isSuccess)
        assertEquals(
            1420,
            (response as CommandResult.Success).data.mtu,
            "Unexpected MTU returned from daemon response",
        )

        assertEquals(1, launcher.invocations.size)
        assertEquals(CommandBinary.IP, launcher.invocations.single().binary)
        assertEquals(
            listOf("link", "set", "dev", "utun0", "mtu", "1420"),
            launcher.invocations.single().arguments,
        )
    }

    @Test
    fun invalidPayloadFailsBeforeExecution() = runBlocking {
        val launcher = RecordingLauncher()
        val response = daemonApi(
            launcher = launcher,
        ).applyMtu(
            interfaceName = "wg invalid",
            mtu = 1500,
        )

        val failure = response as CommandResult.Failure
        assertEquals(DaemonErrorKind.VALIDATION_ERROR, failure.kind)
        assertTrue(failure.message.isNotBlank())
        assertTrue(launcher.invocations.isEmpty())
    }

    @Test
    fun interfaceExistsTreatsNonZeroExitAsNotFound() = runBlocking {
        val launcher = RecordingLauncher(
            outputs = ArrayDeque(
                listOf(
                    ProcessOutputModel(
                        exitCode = 1,
                        stdout = "",
                        stderr = "Device does not exist",
                    ),
                ),
            ),
        )
        val response = daemonApi(
            launcher = launcher,
        ).interfaceExists(interfaceName = "utun9")

        assertTrue(response.isSuccess)
        val payload = (response as CommandResult.Success).data
        assertFalse(payload.exists)
        assertEquals("utun9", payload.interfaceName)
    }

    @Test
    fun windowsApplyDnsUsesPowershellNrptCommands() = runBlocking {
        val launcher = RecordingLauncher()
        val response = daemonApi(
            launcher = launcher,
            operationPlanner = WindowsOperationPlanner(),
        ).applyDns(
            interfaceName = "utun0",
            dnsDomainPool = (listOf("corp.local") to listOf("1.1.1.1")),
        )

        val success = response as CommandResult.Success<ApplyDnsResponse>
        assertEquals("utun0", success.data.interfaceName)
        assertEquals(2, launcher.invocations.size)
        assertEquals(CommandBinary.POWERSHELL, launcher.invocations.first().binary)
        assertTrue(launcher.invocations.last().arguments.last().contains("Add-DnsClientNrptRule"))
    }

    @Test
    fun launcherStartFailureReturnsTypedFailure() = runBlocking {
        val response = daemonApi(
            launcher = StartFailingLauncher(),
        ).setInterfaceState(interfaceName = "utun0", up = false)

        val failure = response as CommandResult.Failure
        assertEquals(DaemonErrorKind.PROCESS_START_FAILURE, failure.kind)
        assertEquals("ip", failure.detail?.executable)
        assertTrue(failure.message.contains("Failed to start"))
    }

    @Test
    fun launcherTimeoutReturnsTypedFailure() = runBlocking {
        val response = daemonApi(
            launcher = TimeoutFailingLauncher(),
        ).setInterfaceState(interfaceName = "utun0", up = false)

        val failure = response as CommandResult.Failure
        assertEquals(DaemonErrorKind.PROCESS_TIMEOUT, failure.kind)
        assertEquals("ip", failure.detail?.executable)
        assertTrue(failure.message.contains("250ms"))
    }

    @Test
    fun nonAcceptedExitCodeReturnsCommandFailedWithFullOutput() = runBlocking {
        val response = daemonApi(
            launcher = RecordingLauncher(
                outputs = ArrayDeque(
                    listOf(
                        ProcessOutputModel(
                            exitCode = 5,
                            stdout = "",
                            stderr = "first line\nsecond line",
                        ),
                    ),
                ),
            ),
        ).setInterfaceState(interfaceName = "utun0", up = false)

        val failure = response as CommandResult.Failure
        assertEquals(DaemonErrorKind.COMMAND_FAILED, failure.kind)
        assertEquals("ip", failure.detail?.executable)
        assertEquals(5, failure.detail?.exitCode)
        assertTrue(failure.message.contains("first line\nsecond line"))
    }

    private fun daemonApi(
        launcher: ProcessLauncher,
        operationPlanner: PlatformOperationPlanner = LinuxOperationPlanner(),
    ): DaemonProcessApiImpl {
        return DaemonProcessApiImpl(
            operationPlanner = operationPlanner,
            processLauncher = launcher,
        )
    }

    private class RecordingLauncher(
        private val outputs: ArrayDeque<ProcessOutputModel> = ArrayDeque(),
    ) : ProcessLauncher {
        val invocations: MutableList<ProcessInvocationModel> = mutableListOf()

        override fun run(invocation: ProcessInvocationModel): ProcessOutputModel {
            invocations += invocation
            return outputs.removeFirstOrNull() ?: ProcessOutputModel(
                exitCode = 0,
                stdout = "",
                stderr = "",
            )
        }
    }

    private class StartFailingLauncher : ProcessLauncher {
        override fun run(invocation: ProcessInvocationModel): ProcessOutputModel {
            throw StartFailure(
                executable = invocation.binary.executable,
                message = "Failed to start `${invocation.binary.executable}`",
            )
        }
    }

    private class TimeoutFailingLauncher : ProcessLauncher {
        override fun run(invocation: ProcessInvocationModel): ProcessOutputModel {
            throw TimeoutFailure(
                executable = invocation.binary.executable,
                timeout = Duration.ofMillis(250),
                cause = IllegalStateException("launcher exploded"),
            )
        }
    }
}
