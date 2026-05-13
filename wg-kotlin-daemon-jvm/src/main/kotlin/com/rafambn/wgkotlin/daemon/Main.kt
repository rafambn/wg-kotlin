package com.rafambn.wgkotlin.daemon

import com.github.ajalt.clikt.core.main

internal const val DAEMON_VERSION = "0.1.0"

fun main(args: Array<String>) {
    DaemonCli().main(args)
}
