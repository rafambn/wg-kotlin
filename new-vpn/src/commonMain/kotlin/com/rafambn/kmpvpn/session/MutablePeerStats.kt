package com.rafambn.kmpvpn.session

internal data class MutablePeerStats(
    var receivedBytes: Long = 0L,
    var transmittedBytes: Long = 0L,
)
