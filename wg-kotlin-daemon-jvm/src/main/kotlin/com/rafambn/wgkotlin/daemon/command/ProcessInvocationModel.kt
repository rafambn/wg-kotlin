package com.rafambn.wgkotlin.daemon.command

internal data class ProcessInvocationModel(
    val binary: CommandBinary,
    val arguments: List<String>,
    val stdin: String? = null,
    val environment: Map<String, String> = emptyMap(),
)
