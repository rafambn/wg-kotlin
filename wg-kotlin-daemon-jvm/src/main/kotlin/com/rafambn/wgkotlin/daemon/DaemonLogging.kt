package com.rafambn.wgkotlin.daemon

import com.rafambn.scribe.Saver
import com.rafambn.scribe.Scribe
import com.rafambn.scribe.ScrollSaver
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

internal object DaemonLogger : Scribe() {
    private val logFile = File("/tmp/wg-daemon-latest.log")

    override val shelves: List<Saver<*>> = listOf(
        ScrollSaver {
            val line = JsonObject(it.data).toString()
            println(line)
            try {
                logFile.appendText("${Instant.now()} $line\n")
            } catch (_: Exception) {
                // ignore file write failures
            }
        }
    )

    override val imprint = mapOf(
        "version" to JsonPrimitive(DAEMON_VERSION),
        "os" to JsonPrimitive(PlatformInfo.os),
        "arch" to JsonPrimitive(PlatformInfo.arch),
    )
}
