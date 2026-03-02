package com.rafambn.kmpvpn

/**
 * Request to start a VPN connection
 */
class StartRequest(
    val configuration: VpnConfiguration,
    val nativeInterfaceName: String,
    val peer: VpnPeer? = null
) {

    class Builder(val configuration: VpnConfiguration) {
        private var nativeInterfaceName: String? = null
        private var peer: VpnPeer? = null

        fun withNativeInterfaceName(name: String?): Builder = apply {
            this.nativeInterfaceName = name
        }

        fun withPeer(peer: VpnPeer?): Builder = apply {
            this.peer = peer
        }

        fun build(): StartRequest {
            return StartRequest(
                configuration = configuration,
                nativeInterfaceName = nativeInterfaceName ?: throw IllegalArgumentException("nativeInterfaceName is required"),
                peer = peer
            )
        }
    }
}
