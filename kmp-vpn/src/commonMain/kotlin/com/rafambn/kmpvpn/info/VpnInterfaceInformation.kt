@file:OptIn(ExperimentalTime::class)

package com.rafambn.kmpvpn.info

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Information about a VPN interface
 */
interface VpnInterfaceInformation {

    fun interfaceName(): String

    fun tx(): Long

    fun rx(): Long

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

    fun fwmark(): Int?

    fun error(): String?

    fun peer(publicKey: String?): VpnPeerInformation? {
        for (p in peers()) {
            if (p.publicKey() == publicKey) return p
        }
        return null
    }

    companion object {
        val EMPTY: VpnInterfaceInformation = object : VpnInterfaceInformation {
            override fun rx(): Long = 0

            override fun tx(): Long = 0

            override fun interfaceName(): String = ""

            override fun peers(): MutableList<VpnPeerInformation> = mutableListOf()

            override fun lastHandshake(): Instant = Instant.fromEpochSeconds(0)

            override fun error(): String? = null

            override fun listenPort(): Int? = null

            override fun fwmark(): Int? = null

            override fun publicKey(): String = ""

            override fun privateKey(): String? = null
        }
    }
}

/**
 * Information about a VPN peer
 */
interface VpnPeerInformation {

    fun publicKey(): String

    fun endpoint(): String?

    fun allowedIps(): List<String>

    fun lastHandshake(): Instant

    fun rx(): Long

    fun tx(): Long

    fun persistentKeepalive(): Int?

    fun protocolVersion(): Int?
}
