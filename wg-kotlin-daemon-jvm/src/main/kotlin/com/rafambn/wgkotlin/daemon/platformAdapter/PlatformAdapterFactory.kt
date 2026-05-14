package com.rafambn.wgkotlin.daemon.platformAdapter

import com.rafambn.wgkotlin.daemon.command.ProcessLauncher
import com.rafambn.wgkotlin.daemon.command.CommonsExecProcessLauncher
import java.util.Locale

internal object PlatformAdapterFactory {
    fun fromOs(
        osName: String = System.getProperty("os.name"),
        processLauncher: ProcessLauncher = CommonsExecProcessLauncher(),
    ): PlatformAdapter {
        val normalized = osName.lowercase(Locale.ROOT)
        return when {
            arrayOf("mac", "darwin").any { normalized.contains(it) } -> MacOsPlatformAdapter(processLauncher)
            normalized.contains("win") -> WindowsPlatformAdapter(processLauncher)
            normalized.contains("linux") -> LinuxPlatformAdapter(processLauncher)
            else -> throw IllegalStateException("Unsupported OS: $osName")
        }
    }
}
