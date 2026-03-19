package com.rafambn.kmpvpn

import com.rafambn.kmpvpn.address.VpnAddress
import com.rafambn.kmpvpn.info.VpnInterfaceInformation
import com.rafambn.kmpvpn.info.VpnPeerInformation
import com.rafambn.kmpvpn.platform.PlatformService
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class VpnAdapter(
    private val service: PlatformService<*>,
    private var ip: VpnAddress? = null
) : AutoCloseable {
    private val allows: MutableList<String> = ArrayList()

    fun information(publicKey: String): VpnPeerInformation {
        for (peer in information().peers()) {
            if (peer.publicKey() == publicKey) return peer
        }
        throw IllegalArgumentException("No such peer $publicKey on interface ${address().shortName()}")
    }

    fun latestHandshake(): Instant {
        return information().lastHandshake()
    }

    fun latestHandshake(publicKey: String): Instant {
        return information(publicKey).lastHandshake()
    }

    fun configuration(): VpnAdapterConfiguration {
        return service.configuration(this)
    }

    fun reconfigure(cfg: VpnAdapterConfiguration) {
        service.reconfigure(this, cfg)
    }

    fun append(cfg: VpnAdapterConfiguration) {
        service.append(this, cfg)
    }

    fun sync(cfg: VpnAdapterConfiguration) {
        service.sync(this, cfg)
    }

    fun nat(): NATMode? {
        return service.getNat(this.address().nativeName())
    }

    fun nat(nat: NATMode?) {
        service.setNat(this.address().nativeName(), nat)
    }

    fun information(): VpnInterfaceInformation {
        return service.information(this)
    }

    fun address(): VpnAddress {
        return ip ?: throw IllegalStateException("No address")
    }

    fun allows(): MutableList<String> {
        return allows
    }

    fun attachToInterface(vpn: VpnAddress) {
        this.ip = vpn
    }

    fun isRunning(): Boolean {
        return ip?.isUp() == true
    }

    fun stop() {
        ip?.down()
    }

    fun delete() {
        ip?.delete()
    }

    override fun close() {
        try {
            stop()
        } finally {
            delete()
        }
    }

    fun remove(publicKey: String) {
        service.remove(this, publicKey)
    }
}
