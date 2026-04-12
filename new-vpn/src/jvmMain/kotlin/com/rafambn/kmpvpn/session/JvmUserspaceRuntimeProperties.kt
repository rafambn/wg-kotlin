package com.rafambn.kmpvpn.session

internal object JvmUserspaceRuntimeProperties {
    const val RUNTIME_MODE: String = "kmpvpn.platform.runtime.mode"
    const val RUNTIME_MODE_DISABLED: String = "disabled"
    const val RUNTIME_MODE_PRODUCTION: String = "production"

    const val RUNTIME_IDLE_DELAY_MILLIS: String = "kmpvpn.runtime.idleDelayMillis"
    const val RUNTIME_RECEIVE_TIMEOUT_MILLIS: String = "kmpvpn.runtime.receiveTimeoutMillis"
    const val RUNTIME_PERIODIC_INTERVAL_MILLIS: String = "kmpvpn.runtime.periodicIntervalMillis"
}
