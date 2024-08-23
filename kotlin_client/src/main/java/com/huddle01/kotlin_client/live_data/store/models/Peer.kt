package com.huddle01.kotlin_client.live_data.store.models

import io.github.crow_misia.mediasoup.Consumer
import org.json.JSONObject

class Peer(info: JSONObject) : Info() {
    override val peerId: String = info.optString("peerId")
    override var role: String = info.optString("role")
    internal val consumers = mutableMapOf<String, Consumer>()
}
