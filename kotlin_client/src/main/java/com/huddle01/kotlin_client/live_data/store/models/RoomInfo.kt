package com.huddle01.kotlin_client.live_data.store.models

import com.huddle01.kotlin_client.models.enum_class.RoomStates

class RoomInfo(
    var roomId: String = "",
    var connectionState: RoomStates = RoomStates.IDLE,
)
