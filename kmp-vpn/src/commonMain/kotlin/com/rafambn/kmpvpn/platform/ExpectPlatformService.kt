package com.rafambn.kmpvpn.platform

import com.rafambn.kmpvpn.address.VpnAddress

expect fun <I : VpnAddress> createAbstractPlatformService(): AbstractPlatformService<I>