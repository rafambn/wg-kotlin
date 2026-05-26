package com.rafambn.wgkotlin.daemon

internal object PlatformInfo {
    val os: String = normalizeOs(System.getProperty("os.name") ?: "unknown")
    val arch: String = normalizeArch(System.getProperty("os.arch") ?: "unknown")

    private fun normalizeOs(raw: String): String = when {
        raw.contains("win", ignoreCase = true) -> "windows"
        raw.contains("mac", ignoreCase = true) || raw.contains("darwin", ignoreCase = true) -> "macos"
        raw.contains("linux", ignoreCase = true) -> "linux"
        else -> raw.lowercase()
    }

    private fun normalizeArch(raw: String): String = when (raw.lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        "x86", "i386", "i686" -> "x86"
        else -> raw.lowercase()
    }
}
