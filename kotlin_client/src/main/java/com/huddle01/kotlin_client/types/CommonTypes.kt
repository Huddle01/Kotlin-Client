package com.huddle01.kotlin_client.types

import com.google.gson.Gson

// TransportType
enum class TransportType {
    SEND,
    RECV
}

// ESocketCloseCode
enum class ESocketCloseCode(val value: Int) {
    ABNORMAL_CLOSURE(1006),
    NORMAL_CLOSURE(1000),
    GOING_AWAY(4001),
    CONNECTION_EXPIRED(4002),
    CONNECTION_ERROR(4006),
    ROOM_CLOSED(4007),
    ROOM_ENTRY_DENIED(4008),
    KICKED(4009),
    MAX_PEERS_REACHED(4010),
    ROOM_EXPIRED(4011)
}

// SocketCloseReason
val socketCloseReason: Map<ESocketCloseCode, String> = mapOf(
    ESocketCloseCode.ROOM_CLOSED to "ROOM_CLOSED",
    ESocketCloseCode.ABNORMAL_CLOSURE to "ABNORMAL_CLOSURE",
    ESocketCloseCode.NORMAL_CLOSURE to "NORMAL_CLOSURE",
    ESocketCloseCode.GOING_AWAY to "GOING_AWAY",
    ESocketCloseCode.CONNECTION_ERROR to "CONNECTION_ERROR",
    ESocketCloseCode.CONNECTION_EXPIRED to "CONNECTION_EXPIRED",
    ESocketCloseCode.ROOM_ENTRY_DENIED to "ROOM_ENTRY_DENIED",
    ESocketCloseCode.KICKED to "KICKED",
    ESocketCloseCode.MAX_PEERS_REACHED to "MAX_PEERS_REACHED",
    ESocketCloseCode.ROOM_CLOSED to "ROOM_EXPIRED",
)

fun estimateSize(obj: Any): Int {
    val gson = Gson()
    val jsonString = gson.toJson(obj)
    return jsonString.toByteArray(Charsets.UTF_8).size
}