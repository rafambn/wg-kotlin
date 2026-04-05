package com.rafambn.kmpvpn.daemon.planner

import com.rafambn.kmpvpn.daemon.command.CommandBinary
import java.util.Locale

internal interface PlatformOperationPlanner {
    val platformId: String
    val requiredBinaries: Set<CommandBinary>

    fun plan(operation: DaemonOperation): ExecutionPlan

    companion object {
        fun fromOs(
            osName: String = System.getProperty("os.name"),
        ): PlatformOperationPlanner {
            val normalized = osName.lowercase(Locale.ROOT)
            return when {
                arrayOf("mac", "darwin").any { token -> normalized.contains(token) } -> MacOsOperationPlanner()
                normalized.contains("win") -> WindowsOperationPlanner()
                normalized.contains("linux") -> LinuxOperationPlanner()
                else -> throw IllegalStateException("Unsupported operating system for privileged daemon operation planner: `$osName`")
            }
        }
    }
}
