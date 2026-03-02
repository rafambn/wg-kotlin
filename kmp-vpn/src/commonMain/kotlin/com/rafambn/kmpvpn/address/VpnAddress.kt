package com.rafambn.kmpvpn.address

/**
 * Represents a VPN address
 */
interface VpnAddress {

    fun isUp(): Boolean

    fun shortName(): String

    fun fullName(): String
}
