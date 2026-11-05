package com.rafambn.wgkotlin.daemon

import java.io.InputStream
import java.io.OutputStream
import org.newsclub.net.unix.AFUNIXSocket
import org.newsclub.net.unix.AFUNIXSocketAddress

internal interface LocalConnection : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    var readTimeoutMillis: Int

    companion object {
        fun connect(endpoint: String, expectedPid: Long): LocalConnection {
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                return WindowsPipeConnection.connect(endpoint, expectedPid)
            }
            val socket = AFUNIXSocket.newInstance()
            try {
                socket.connect(AFUNIXSocketAddress.of(java.io.File(endpoint)), 1_000)
                check(socket.peerCredentials.pid == expectedPid) { "IPC server is not the launched daemon" }
                return object : LocalConnection {
                    override val input: InputStream = socket.inputStream
                    override val output: OutputStream = socket.outputStream
                    override var readTimeoutMillis: Int
                        get() = socket.soTimeout
                        set(value) { socket.soTimeout = value }
                    override fun close() = socket.close()
                }
            } catch (error: Throwable) {
                socket.close()
                throw error
            }
        }
    }
}
