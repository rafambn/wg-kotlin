package com.rafambn.wgkotlin.daemon

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef.ULONGByReference
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException

internal class WindowsPipeConnection private constructor(private val handle: HANDLE) : LocalConnection {
    // Full-duplex I/O and close run on different threads. This lock protects
    // native handle lifetime only; it is never held while waiting for I/O.
    private val lifecycle = Any()
    private var closed = false
    private var activeIo = 0
    override var readTimeoutMillis: Int = 0

    override val input: InputStream = BufferedInputStream(object : InputStream() {
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
            if (length == 0) return 0
            return transfer(bytes, offset, minOf(length, 65_536), writing = false)
        }
    }, 65_536)

    override val output: OutputStream = BufferedOutputStream(object : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()))
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
            var done = 0
            while (done < length) {
                done += transfer(bytes, offset + done, minOf(length - done, 65_536), writing = true)
            }
        }
    }, 65_536)

    private fun transfer(bytes: ByteArray, offset: Int, length: Int, writing: Boolean): Int {
        val kernel = Kernel32.INSTANCE
        val native = PipeKernel.instance
        val event = kernel.CreateEvent(null, true, false, null) ?: throw IOException("CreateEvent failed")
        val overlapped = WinBase.OVERLAPPED()
        overlapped.hEvent = event
        overlapped.write()
        var registered = false
        try {
            Memory(length.toLong()).use { buffer ->
                if (writing) buffer.write(0, bytes, offset, length)
                val error = synchronized(lifecycle) {
                    if (closed) throw IOException("IPC connection is closed")
                    activeIo++
                    registered = true
                    val ok = if (writing) native.WriteFile(handle, buffer, length, null, overlapped.pointer)
                        else native.ReadFile(handle, buffer, length, null, overlapped.pointer)
                    if (ok) 0 else Native.getLastError()
                }
                if (error == 109 && !writing) return -1
                if (error != 0 && error != 997) throw IOException("Named pipe I/O failed: $error")
                val timeout = if (writing) 5_000 else readTimeoutMillis.takeIf { it > 0 } ?: WinBase.INFINITE
                val waited = kernel.WaitForSingleObject(event, timeout)
                val count = IntByReference()
                if (waited != 0) {
                    native.CancelIoEx(handle, overlapped.pointer)
                    native.GetOverlappedResult(handle, overlapped.pointer, count, true)
                    if (waited == 258) throw SocketTimeoutException("Named pipe I/O timed out")
                    throw IOException("Named pipe wait failed")
                }
                if (!native.GetOverlappedResult(handle, overlapped.pointer, count, false)) {
                    val result = Native.getLastError()
                    if (result == 109 && !writing) return -1
                    throw IOException("Named pipe I/O failed: $result")
                }
                if (count.value == 0) {
                    if (!writing) return -1
                    throw IOException("Named pipe write made no progress")
                }
                if (!writing) buffer.read(0, bytes, offset, count.value)
                return count.value
            }
        } finally {
            kernel.CloseHandle(event)
            if (registered) synchronized(lifecycle) {
                activeIo--
                if (closed && activeIo == 0) kernel.CloseHandle(handle)
            }
        }
    }

    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true
            PipeKernel.instance.CancelIoEx(handle, null)
            if (activeIo == 0) Kernel32.INSTANCE.CloseHandle(handle)
        }
    }

    companion object {
        fun connect(endpoint: String, expectedPid: Long): LocalConnection {
            val kernel = Kernel32.INSTANCE
            val started = System.nanoTime()
            var handle: HANDLE
            while (true) {
                handle = kernel.CreateFile(endpoint, 0xc0000000.toInt(), 0, null, 3, 0x40000000, null)
                if (handle != WinBase.INVALID_HANDLE_VALUE) break
                val error = Native.getLastError()
                val remaining = 1_000 - (System.nanoTime() - started) / 1_000_000
                if (error != 231 || remaining <= 0 || !kernel.WaitNamedPipe(endpoint, remaining.toInt())) {
                    throw IOException("Cannot open named pipe: $error")
                }
            }
            try {
                val pid = ULONGByReference()
                check(kernel.GetNamedPipeServerProcessId(handle, pid) && pid.value.toLong() == expectedPid) { "IPC server is not the launched daemon" }
                return WindowsPipeConnection(handle)
            } catch (error: Throwable) {
                kernel.CloseHandle(handle)
                throw error
            }
        }
    }
}
