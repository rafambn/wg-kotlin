package com.rafambn.wgkotlin.daemon.tun

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal object WindowsDllLoader {
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

            if (!dllLoaded.get()) {
                try {
                    System.load(targetPathString)
                    dllLoaded.set(true)
                } catch (e: UnsatisfiedLinkError) {
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
        val arch = archProperty.lowercase(Locale.ROOT)
        val bits = bitsProperty?.lowercase(Locale.ROOT)

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
        val resourceBytes = input.use(InputStream::readBytes)
        val resourceHash = sha256Hex(resourceBytes).take(16)

        val tempRoot = Path.of(System.getProperty("java.io.tmpdir"))
        val targetDir = tempRoot.resolve("$TEMP_DIR_PREFIX$resourceHash")
        cleanupOldTempDirs(tempRoot, targetDir)
        Files.createDirectories(targetDir)
        val targetPath = targetDir.resolve("wintun.dll")

        val existingBytes = if (Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) {
            runCatching { Files.readAllBytes(targetPath) }.getOrNull()
        } else {
            null
        }
        if (existingBytes == null || !MessageDigest.isEqual(existingBytes, resourceBytes)) {
            Files.write(
                targetPath,
                resourceBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
        }

        return targetPath
    }

    private fun cleanupOldTempDirs(tempRoot: Path, keepDir: Path) {
        val normalizedKeepDir = keepDir.toAbsolutePath().normalize()
        runCatching {
            Files.list(tempRoot).use { paths ->
                paths
                    .filter { path ->
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                            path.fileName.toString().startsWith(TEMP_DIR_PREFIX) &&
                            path.toAbsolutePath().normalize() != normalizedKeepDir
                    }
                    .forEach { path ->
                        runCatching {
                            Files.walk(path).use { entries ->
                                entries
                                    .sorted(Comparator.reverseOrder())
                                    .forEach(Files::deleteIfExists)
                            }
                        }
                    }
            }
        }
    }

    internal fun openResource(resourcePath: String): InputStream? {
        return WindowsDllLoader::class.java.getResourceAsStream(resourcePath)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isWindows(osName: String = System.getProperty("os.name")): Boolean {
        return osName.lowercase(Locale.ROOT).contains("win")
    }

    internal enum class Architecture {
        X86,
        X64,
        ARM64,
    }

    private const val TEMP_DIR_PREFIX = "wg-kotlin-wintun-"
}
