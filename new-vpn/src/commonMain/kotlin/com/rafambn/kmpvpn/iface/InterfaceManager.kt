package com.rafambn.kmpvpn.iface

import com.rafambn.kmpvpn.VpnConfiguration
import com.rafambn.kmpvpn.session.io.TunPort

/**
 * Contract for platform-facing VPN interface ownership.
 *
 * This contract owns both interface lifecycle and interface runtime configuration.
 */
interface InterfaceManager {

    /**
     * Checks whether an interface with the provided name exists.
     */
    fun exists(): Boolean

    /**
     * Creates the interface with the provided base configuration.
     */
    fun create(config: VpnConfiguration)

    /**
     * Brings the interface up.
     */
    fun up()

    /**
     * Brings the interface down.
     */
    fun down()

    /**
     * Deletes the interface.
     */
    fun delete()

    /**
     * Returns `true` when the interface is currently up.
     */
    fun isUp(): Boolean

    /**
     * Returns current effective interface configuration.
     */
    fun configuration(): VpnConfiguration

    /**
     * Returns the live TUN port owned by this interface.
     */
    fun tunPort(): TunPort

    /**
     * Replaces current interface configuration.
     */
    fun reconfigure(config: VpnConfiguration)

    /**
     * Reads current interface information.
     */
    fun readInformation(): VpnInterfaceInformation
}
