package com.rafambn.kmpvpn.daemon.command

internal fun interface ProcessLauncher {
    fun run(invocation: ProcessInvocationModel): ProcessOutputModel
}
