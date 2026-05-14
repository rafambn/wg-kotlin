package com.rafambn.wgkotlin.daemon

internal const val DAEMON_VERSION = "0.1.0"
internal const val DAEMON_WEBSOCKET_PING_PERIOD_MILLIS: Long = 3_000
internal const val DAEMON_WEBSOCKET_TIMEOUT_MILLIS: Long = 2_000
internal const val DAEMON_WEBSOCKET_MAX_FRAME_SIZE: Long = 128L * 1024L
internal const val MAX_PACKET_FRAME_SIZE: Int = 65535
internal const val PACKET_FLOW_BUFFER_CAPACITY: Int = 64