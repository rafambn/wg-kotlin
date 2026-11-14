package com.rafambn.wgkotlin.daemon

import kotlin.time.Duration

/**
 * Controls the actual elevated daemon, independently of how the application launched it.
 * [pid] must identify that daemon for OS peer authentication. Lifecycle calls are blocking.
 */
interface DaemonHandle : AutoCloseable {
    val pid: Long
    val isAlive: Boolean

    /** Waits at most [timeout] for the daemon to exit. Returns true once it has exited. */
    fun awaitExit(timeout: Duration): Boolean

    /** Requests forced OS termination, without another permission prompt or daemon cooperation. */
    fun kill()

    /**
     * Releases launcher resources without requesting termination. Must tolerate repeated calls
     * and leave lifecycle queries usable. If termination failed, retain control of the live daemon.
     */
    override fun close()
}
