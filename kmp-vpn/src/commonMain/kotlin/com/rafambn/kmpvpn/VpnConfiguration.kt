package com.rafambn.kmpvpn

/**
 * Represents VPN configuration
 */
class VpnConfiguration(
    private val publicKey: String,
    private val privateKey: String = "",
    private val address: String = "",
    private val listenPort: Int = Vpn.DEFAULT_PORT,
    private val peers: List<VpnPeer> = emptyList(),
    private val additionalConfig: Map<String, String> = emptyMap()
) {

    fun publicKey(): String = publicKey

    fun privateKey(): String = privateKey

    fun address(): String = address

    fun listenPort(): Int = listenPort

    fun peers(): List<VpnPeer> = peers

    class Builder {
        private var publicKey: String = ""
        private var privateKey: String = ""
        private var address: String = ""
        private var listenPort: Int = Vpn.DEFAULT_PORT
        private var peers: MutableList<VpnPeer> = mutableListOf()
        private var additionalConfig: MutableMap<String, String> = mutableMapOf()

        fun fromContent(content: String): Builder = apply {
            // Placeholder: Parse VPN configuration from string content
            // This should parse WireGuard config format
        }

        fun fromConfiguration(other: VpnConfiguration): Builder = apply {
            this.publicKey = other.publicKey()
            this.privateKey = other.privateKey()
            this.address = other.address()
            this.listenPort = other.listenPort()
            this.peers = other.peers().toMutableList()
        }

        fun withPublicKey(key: String): Builder = apply {
            this.publicKey = key
        }

        fun withPrivateKey(key: String): Builder = apply {
            this.privateKey = key
        }

        fun withAddress(address: String): Builder = apply {
            this.address = address
        }

        fun withListenPort(port: Int): Builder = apply {
            this.listenPort = port
        }

        fun addPeer(peer: VpnPeer): Builder = apply {
            this.peers.add(peer)
        }

        fun build(): VpnConfiguration {
            return VpnConfiguration(
                publicKey = publicKey,
                privateKey = privateKey,
                address = address,
                listenPort = listenPort,
                peers = peers,
                additionalConfig = additionalConfig
            )
        }
    }
}
