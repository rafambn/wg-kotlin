package com.rafambn.wgkotlin.daemon.tun

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean

internal object WindowsDllLoader {
    private val logger = org.slf4j.LoggerFactory.getLogger(WindowsDllLoader::class.java)
    private val lock = Any()

    @Volatile
    private var cachedWinTunDllPath: String? = null
    private val dllLoaded = AtomicBoolean(false)

    /**
     * Ensures a Windows-compatible `wintun.dll` exists on disk and is loaded.
     *
     * Returns an absolute path to `wintun.dll` on Windows so native code (tun-rs/wintun)
     * can load that exact file. Returns null on non-Windows hosts.
     */
    fun prepareWinTunDllPath(): String? {
        if (!isWindows()) {
            logger.debug("Not running on Windows, skipping WinTUN DLL preparation")
            return null
        }

        cachedWinTunDllPath?.let { return it }

        synchronized(lock) {
            cachedWinTunDllPath?.let { return it }

            val architecture = detectArchitecture()
            val sourceResource = when (architecture) {
                Architecture.X86 -> "wintun-x86.dll"
                Architecture.X64 -> "wintun-x64.dll"
                Architecture.ARM64 -> "wintun-arm64.dll"
            }

            val targetPath = extractAsCanonicalWinTunDll(sourceResource)
            val targetPathString = targetPath.toAbsolutePath().toString()

            if (dllLoaded.get()) {
                logger.debug("WinTUN DLL already loaded")
            } else {
                try {
                    System.load(targetPathString)
                    dllLoaded.set(true)
                    logger.info("Loaded WinTUN DLL from: $targetPathString")
                } catch (e: UnsatisfiedLinkError) {
                    logger.error("Failed to load WinTUN DLL from: $targetPathString", e)
                    throw e
                }
            }

            cachedWinTunDllPath = targetPathString
            return targetPathString
        }
    }

    internal fun detectArchitecture(
        archProperty: String = System.getProperty("os.arch"),
        bitsProperty: String? = System.getProperty("sun.arch.data.model"),
    ): Architecture {
        val arch = archProperty.lowercase()
        val bits = bitsProperty?.lowercase()

        return when {
            arch.contains("amd64") || arch.contains("x86_64") -> Architecture.X64
            arch.contains("x86") || arch.contains("i386") || arch.contains("i486") || arch.contains("i586") || arch.contains("i686") -> Architecture.X86
            arch.contains("aarch64") || arch.contains("arm64") -> Architecture.ARM64
            bits == "64" && (arch.contains("arm") || arch.contains("aarch")) -> Architecture.ARM64
            else -> throw IllegalStateException("Unsupported Windows architecture: $arch (bits: $bits)")
        }
    }

    private fun extractAsCanonicalWinTunDll(sourceDllName: String): Path {
        val resourcePath = "/wintun/$sourceDllName"
        val input = openResource(resourcePath)
            ?: throw IllegalStateException("WinTUN DLL not found in resources: $resourcePath")

        val tempRoot = Path.of(System.getProperty("java.io.tmpdir"))
        cleanupOldTempDirs(tempRoot)
        val targetDir = Files.createTempDirectory(tempRoot, TEMP_DIR_PREFIX)
        val targetPath = targetDir.resolve("wintun.dll")

        input.use { stream ->
            Files.copy(stream, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }

        targetDir.toFile().deleteOnExit()
        targetPath.toFile().deleteOnExit()
        logger.debug("Extracted WinTUN resource `$sourceDllName` to `$targetPath`")
        return targetPath
    }

    private fun cleanupOldTempDirs(tempRoot: Path) {
        runCatching {
            Files.list(tempRoot).use { paths ->
                paths
                    .filter { path -> Files.isDirectory(path) && path.fileName.toString().startsWith(TEMP_DIR_PREFIX) }
                    .forEach { path ->
                        runCatching {
                            Files.walk(path).use { entries ->
                                entries
                                    .sorted(Comparator.reverseOrder())
                                    .forEach(Files::deleteIfExists)
                            }
                        }.onFailure { failure ->
                            logger.debug("Failed to remove old WinTUN temp directory `$path`", failure)
                        }
                    }
            }
        }.onFailure { failure ->
            logger.debug("Failed to scan WinTUN temp directory root `$tempRoot`", failure)
        }
    }

    internal fun openResource(resourcePath: String): InputStream? {
        return WindowsDllLoader::class.java.getResourceAsStream(resourcePath)
    }

    private fun isWindows(osName: String = System.getProperty("os.name")): Boolean {
        return osName.lowercase().contains("win")
    }

    internal enum class Architecture {
        X86,
        X64,
        ARM64,
    }

    private const val TEMP_DIR_PREFIX = "wg-kotlin-wintun-"
}
