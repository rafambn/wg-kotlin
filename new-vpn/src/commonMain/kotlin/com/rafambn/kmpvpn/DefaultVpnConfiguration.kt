package com.rafambn.kmpvpn

class DefaultVpnConfiguration(
    override val interfaceName: String,
    override val dnsDomainPool: Pair<List<String>, List<String>> = (emptyList<String>() to emptyList()),
    override val mtu: Int? = null,
    override val addresses: MutableList<String> = mutableListOf(),
    override val adapter: VpnAdapterConfiguration,
) : VpnConfiguration {

    constructor(
        interfaceName: String,
        dnsDomainPool: Pair<List<String>, List<String>> = (emptyList<String>() to emptyList()),
        mtu: Int? = null,
        addresses: MutableList<String> = mutableListOf(),
        listenPort: Int? = null,
        privateKey: String, // Keys.genkey().getBase64PrivateKey()
        publicKey: String,
        peers: List<VpnPeer> = emptyList(),
    ) : this(
        interfaceName = interfaceName,
        dnsDomainPool = dnsDomainPool,
        mtu = mtu,
        addresses = addresses,
        adapter = DefaultVpnAdapterConfiguration(
            listenPort = listenPort,
            privateKey = privateKey,
            publicKey = publicKey,
            peers = peers,
        ),
    )
}
