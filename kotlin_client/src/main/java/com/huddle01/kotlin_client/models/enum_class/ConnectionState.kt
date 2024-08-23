package com.huddle01.kotlin_client.models.enum_class

// ConnectionState
enum class ConnectionState {
    UNINITIALIZED,
    FAILED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    CLOSED,
    AWAITING_RECONNECTION
}
