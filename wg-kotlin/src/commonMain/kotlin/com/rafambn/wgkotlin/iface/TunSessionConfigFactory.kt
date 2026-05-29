package com.rafambn.wgkotlin.iface

import com.rafambn.wgkotlin.VpnConfiguration
import com.rafambn.wgkotlin.daemon.proto.TunSessionConfig

internal expect fun VpnConfiguration.toTunSessionConfig(): TunSessionConfig
