package com.rafambn.wgkotlin.daemon

import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

internal const val CAP_NET_ADMIN: Int = 12

internal fun hasRequiredPrivileges(
    osName: String = System.getProperty("os.name"),
    unixUidProvider: () -> Long = ::currentUnixUid,
    linuxEffectiveCapabilitiesProvider: () -> Long? = ::currentLinuxEffectiveCapabilities,
    commandRunner: (List<String>) -> Boolean = ::runCommandSuccessfully,
): Boolean {
    val normalizedOs = osName.lowercase(Locale.ROOT)
    return when {
        normalizedOs.contains("win") -> {
            val script =
                "${'$'}identity = [Security.Principal.WindowsIdentity]::GetCurrent();" +
                    "${'$'}principal = [Security.Principal.WindowsPrincipal]::new(${'$'}identity);" +
                    "if (${'$'}identity.IsSystem -or ${'$'}principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { exit 0 } else { exit 1 }"
            commandRunner(
                listOf(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    script,
                ),
            )
        }
        normalizedOs.contains("linux") -> {
            val isRoot = try {
                unixUidProvider() == 0L
            } catch (_: Exception) {
                false
            }
            isRoot || linuxEffectiveCapabilitiesProvider()?.hasLinuxCapability(CAP_NET_ADMIN) == true
        }
        else -> try {
            unixUidProvider() == 0L
        } catch (_: Exception) {
            false
        }
    }
}

internal fun runCommandSuccessfully(command: List<String>): Boolean {
    var process: Process? = null
    return try {
        process = ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return false
        }
        process.exitValue() == 0
    } catch (_: Exception) {
        false
    } finally {
        process?.outputStream?.close()
        process?.inputStream?.close()
        process?.errorStream?.close()
    }
}

private fun currentUnixUid(): Long {
    val process = ProcessBuilder("id", "-u")
        .redirectErrorStream(true)
        .start()

    try {
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("id -u timed out")
        }
        return process.inputStream.bufferedReader().use { reader ->
            reader.readText().trim().toLong()
        }
    } finally {
        process.outputStream.close()
        process.inputStream.close()
        process.errorStream.close()
    }
}

private fun currentLinuxEffectiveCapabilities(): Long? {
    return runCatching {
        File("/proc/self/status")
            .useLines { lines ->
                lines.firstOrNull { line -> line.startsWith("CapEff:") }
            }
            ?.substringAfter(':')
            ?.trim()
            ?.toLong(radix = 16)
    }.getOrNull()
}

private fun Long.hasLinuxCapability(capability: Int): Boolean {
    return (this and (1L shl capability)) != 0L
}

internal fun isBinaryAvailableOnPath(executable: String): Boolean {
    val lookupCommand = if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")) {
        listOf("where", executable)
    } else {
        listOf("which", executable)
    }

    return runCommandSuccessfully(lookupCommand)
}
