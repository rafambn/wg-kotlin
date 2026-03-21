package com.rafambn.kmpvpn.daemon.protocol

import kotlinx.serialization.Serializable

@Serializable
enum class DaemonErrorKind {
    UNKNOWN_COMMAND,
    MALFORMED_PAYLOAD,
    VERSION_MISMATCH,
    VALIDATION_ERROR,
    INTERNAL_ERROR,
}
