@file:OptIn(ExperimentalTime::class)

package com.rafambn.kmpvpn.info

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface VpnInterfaceInformation {

    fun interfaceName(): String

    fun transmittedBytes(): Long

    fun receivedBytes(): Long

    fun peers(): MutableList<VpnPeerInformation>

    fun lastHandshake(): Instant

    fun publicKey(): String

    fun privateKey(): String?

    /**
     * Actual listening port if it can be determined.
     *
     * @return listening port or null if it cannot be determined
     */
    fun listenPort(): Int?

    fun error(): String?

    fun peer(publicKey: String?): VpnPeerInformation? {
        for (p in peers()) {
            if (p.publicKey() == publicKey) return p
        }
        return null
    }
}
