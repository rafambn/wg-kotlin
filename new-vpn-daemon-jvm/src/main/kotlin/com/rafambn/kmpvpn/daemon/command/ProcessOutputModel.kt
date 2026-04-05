package com.rafambn.kmpvpn.daemon.command

internal data class ProcessOutputModel(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)
