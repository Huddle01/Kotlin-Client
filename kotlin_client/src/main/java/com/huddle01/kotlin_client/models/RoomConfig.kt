package com.huddle01.kotlin_client.models

data class RoomConfig(
    var roomLocked: Boolean = false,
    var allowProduce: Boolean = true,
    var allowProduceSources: ProduceSources = ProduceSources(cam = true, mic = true, screen = true),
    var allowConsume: Boolean = true,
    var allowSendData: Boolean = true,
)