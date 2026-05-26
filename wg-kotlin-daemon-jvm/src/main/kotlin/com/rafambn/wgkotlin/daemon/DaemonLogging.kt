package com.rafambn.wgkotlin.daemon

import com.rafambn.scribe.EntrySaver
import com.rafambn.scribe.Saver
import com.rafambn.scribe.Scribe
import kotlinx.serialization.json.JsonPrimitive

internal object DaemonLogger : Scribe() {
    override val shelves: List<Saver<*>> = listOf(
        EntrySaver {
            println(it)
        }
    )

    override val imprint = mapOf(
        "version" to JsonPrimitive(DAEMON_VERSION),
        "os" to JsonPrimitive(PlatformInfo.os),
        "arch" to JsonPrimitive(PlatformInfo.arch),
    )
}
