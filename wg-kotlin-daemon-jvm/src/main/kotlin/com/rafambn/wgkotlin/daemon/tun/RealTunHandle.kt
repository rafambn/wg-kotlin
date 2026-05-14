package com.rafambn.wgkotlin.daemon.tun

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.wg_kotlin_uniffi_tun_rs.TunDevice
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Real TUN device handle implementation using tun-rs via Rust FFI.
 *
 * This implementation uses the Rust tun-rs library through uniffi bindings
 * to provide cross-platform TUN device support for Linux, macOS, and Windows.
 */
internal class RealTunHandle(
    private val requestedInterfaceName: String,
    private val ipAddress: String,
    private val prefixLength: UByte = 24u,
    private val onClose: () -> Unit = {},
) : TunHandle {

    private val tunDevice = AtomicReference<TunDevice?>(null)
    private val isClosed = AtomicBoolean(false)
    private var openedInterfaceName: String = requestedInterfaceName

    override val interfaceName: String
        get() = openedInterfaceName

    suspend fun openDevice(): RealTunHandle {
        // Prepare and load WinTUN DLL on Windows before attempting to open device.
        // The returned path is passed to tun-rs so it loads the exact DLL we extracted.
        val winTunDllPath = WindowsDllLoader.prepareWinTunDllPath()

        withContext(Dispatchers.IO) {
            try {
                // Create the TUN device via uniffi bindings
                tunDevice.set(TunDevice(requestedInterfaceName))

                // Open the device with the specified primary tunnel address.
                val openedDevice = tunDevice.get()
                openedDevice?.open(ipAddress, prefixLength, winTunDllPath)
                openedInterfaceName = openedDevice?.getInterfaceName() ?: requestedInterfaceName
            } catch (e: Exception) {
                isClosed.set(true)
                throw e
            }
        }
        return this
    }

    override suspend fun readPacket(): ByteArray? {
        if (isClosed.get() || tunDevice.get() == null) {
            return null
        }

        return withContext(Dispatchers.IO) {
            // Read a packet from the Rust TUN device.
            // Let real IO errors propagate so the session coroutine fails
            // gracefully instead of busy-waiting.
            tunDevice.get()?.readPacket()
        }
    }

    override suspend fun writePacket(packet: ByteArray) {
        if (isClosed.get() || tunDevice.get() == null) {
            return
        }

        withContext(Dispatchers.IO) {
            // Write packet to the Rust TUN device
            tunDevice.get()?.writePacket(packet)
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }

        val device = tunDevice.getAndSet(null)

        var failure: Throwable? = null
        fun captureCloseFailure(block: () -> Unit) {
            runCatching(block).onFailure { throwable ->
                val existingFailure = failure
                if (existingFailure == null) {
                    failure = throwable
                } else {
                    existingFailure.addSuppressed(throwable)
                }
            }
        }

        captureCloseFailure { onClose() }
        captureCloseFailure { device?.shutdown() }
        captureCloseFailure { device?.close() }
    }
}
