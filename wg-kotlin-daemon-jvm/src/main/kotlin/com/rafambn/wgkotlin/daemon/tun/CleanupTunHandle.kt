package com.rafambn.wgkotlin.daemon.tun

internal class CleanupTunHandle(
    private val delegate: TunHandle,
    private val cleanup: () -> Unit,
) : TunHandle by delegate {
    override fun close() {
        var failure: Throwable? = null
        try {
            cleanup()
        } catch (throwable: Throwable) {
            failure = throwable
        }

        try {
            delegate.close()
        } catch (throwable: Throwable) {
            if (failure == null) {
                failure = throwable
            } else {
                failure.addSuppressed(throwable)
            }
        }

        if (failure != null) {
            throw failure
        }
    }
}
