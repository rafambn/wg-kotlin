package com.rafambn.wgkotlin.daemon

import kotlin.time.Duration.Companion.seconds

/** Separate app instance used to verify the daemon notices its owner's exit. */
object IpcOwnerHost {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val daemon = DaemonProcess.launch(startupTimeout = 3.seconds) { args ->
            ProcessBuilder(listOf(arguments[0]) + args)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        }
        println(daemon.pid)
        System.out.flush()
        System.`in`.read()
        // Deliberately exit without closing DaemonProcess.
        kotlin.system.exitProcess(0)
    }
}
