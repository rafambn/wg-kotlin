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

    private val logger = org.slf4j.LoggerFactory.getLogger(RealTunHandle::class.java)

    private val tunDevice = AtomicReference<TunDevice?>(null)
    private val isClosed = AtomicBoolean(false)
    private var openedInterfaceName: String = requestedInterfaceName

    override val interfaceName: String
        get() = openedInterfaceName

    suspend fun openDevice(): RealTunHandle {
        logger.info("Opening TUN device: $requestedInterfaceName with IP $ipAddress/$prefixLength")

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

                logger.info("TUN device opened successfully: $openedInterfaceName")
            } catch (e: Exception) {
                logger.error("Failed to open TUN device: $requestedInterfaceName", e)
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
            val packet = tunDevice.get()?.readPacket()
            logger.trace("Read ${packet?.size ?: 0} bytes from TUN device")
            packet
        }
    }

    override suspend fun writePacket(packet: ByteArray) {
        if (isClosed.get() || tunDevice.get() == null) {
            logger.warn("Attempted to write to closed TUN device")
            return
        }

        withContext(Dispatchers.IO) {
            try {
                // Write packet to the Rust TUN device
                tunDevice.get()?.writePacket(packet)
                logger.trace("Wrote ${packet.size} bytes to TUN device")
            } catch (e: Exception) {
                logger.error("Failed to write packet to TUN device", e)
                throw e
            }
        }
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }

        logger.info("Closing TUN device: $openedInterfaceName")
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

        failure?.let { throwable ->
            logger.error("Error closing TUN device", throwable)
        }
    }
}
