package com.rafambn.wgkotlin

import com.rafambn.wgkotlin.daemon.proto.Ip

data class DnsConfig(
    val searchDomains: List<String> = emptyList(),
    val servers: List<Ip> = emptyList(),
)
