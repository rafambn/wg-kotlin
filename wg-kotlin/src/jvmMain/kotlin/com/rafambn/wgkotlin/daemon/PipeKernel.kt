package com.rafambn.wgkotlin.daemon

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/** Pointer buffers must stay allocated until overlapped I/O completes. */
internal interface PipeKernel : StdCallLibrary {
    fun ReadFile(handle: HANDLE, buffer: Pointer, size: Int, transferred: IntByReference?, overlapped: Pointer): Boolean
    fun WriteFile(handle: HANDLE, buffer: Pointer, size: Int, transferred: IntByReference?, overlapped: Pointer): Boolean
    fun GetOverlappedResult(handle: HANDLE, overlapped: Pointer, transferred: IntByReference, wait: Boolean): Boolean
    fun CancelIoEx(handle: HANDLE, overlapped: Pointer?): Boolean

    companion object {
        val instance: PipeKernel by lazy { Native.load("kernel32", PipeKernel::class.java) }
    }
}
