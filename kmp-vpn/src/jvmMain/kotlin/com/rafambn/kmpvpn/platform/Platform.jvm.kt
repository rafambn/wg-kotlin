package com.rafambn.kmpvpn.platform

/**
 * JVM implementation of platform detection
 */
actual object Platform {
    actual val currentOS: OperatingSystem
        get() = detectOS()

    private fun detectOS(): OperatingSystem {
        val osName = getOSName().lowercase()

        return when {
            osName.contains("linux") -> OperatingSystem.LINUX
            osName.contains("mac") || osName.contains("darwin") -> OperatingSystem.MACOS
            osName.contains("win") -> OperatingSystem.WINDOWS
            else -> OperatingSystem.UNKNOWN
        }
    }

    private fun getOSName(): String {
        return try {
            System.getProperty("os.name", "unknown")
        } catch (e: Exception) {
            "unknown"
        }
    }
}
