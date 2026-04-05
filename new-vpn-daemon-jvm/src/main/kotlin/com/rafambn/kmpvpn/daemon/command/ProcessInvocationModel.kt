package com.rafambn.kmpvpn.daemon.command

internal data class ProcessInvocationModel(
    val binary: CommandBinary,
    val arguments: List<String>,
    val stdin: String? = null,
)
