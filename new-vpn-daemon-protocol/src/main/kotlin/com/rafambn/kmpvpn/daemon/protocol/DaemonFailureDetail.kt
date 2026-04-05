package com.rafambn.kmpvpn.daemon.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DaemonFailureDetail(
    val executable: String? = null,
    val exitCode: Int? = null,
)
