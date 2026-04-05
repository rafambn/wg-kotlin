package com.rafambn.kmpvpn.address

import com.rafambn.kmpvpn.NetworkInterface
import com.rafambn.kmpvpn.platform.OperatingSystem
import com.rafambn.kmpvpn.platform.Platform

interface VpnAddress {

    fun isUp(): Boolean

    fun isDefaultGateway(): Boolean

    fun setDefaultGateway(address: String)

    fun delete()

    fun down()

    fun getMac(): String?

    fun isLoopback(): Boolean = networkInterface()?.let { nif -> //TODO(Implement code)
        try {
            if (nif.isLoopback) return true

            var loopback = true
            for (addr in nif.interfaceAddresses) {
                val ipAddr = addr.address
                if (!ipAddr.isAnyLocalAddress && !ipAddr.isLinkLocalAddress && !ipAddr.isLoopbackAddress) {
                    loopback = false
                }
            }

            return loopback

        } catch (e: Exception) {
            return false
        }
    } ?: false

    fun networkInterface(): NetworkInterface? {
        /* NOTE: This is pretty much useless  to lookup the network by the
		 * name we know it as on Windows, as for some bizarre reason,
		 * net8 for example (as would show ip "ipconfig /all") comes back
		 * here as net7!
		 */
        if (Platform.currentOS == OperatingSystem.WINDOWS) throw UnsupportedOperationException("Do not use this on Windows.")
        val nifEnum = listOf<NetworkInterface>()
        return nifEnum.find { it.name == name() } //TODO(Implement code)
    }

    fun getMtu(): Int

    fun name(): String

    fun displayName(): String

    fun shortName(): String {
        return if (hasVirtualName()) "${name()} (${nativeName()})"
        else name()
    }

    fun nativeName(): String

    fun hasVirtualName(): Boolean {
        return name() != nativeName()
    }

    fun peer(): String

    fun mtu(mtu: Int)

    //	void setName(String name);
    //
    //	void setPeer(String peer);
    //
    fun up()
}
