package com.rafambn.kmpvpn.platform

/**
 * Android implementation of platform detection
 */
actual object Platform {
    actual val currentOS: OperatingSystem = OperatingSystem.ANDROID
}
