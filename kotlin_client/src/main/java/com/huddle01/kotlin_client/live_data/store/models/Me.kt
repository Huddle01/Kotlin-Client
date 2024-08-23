package com.huddle01.kotlin_client.live_data.store.models

class Me : Info() {
    override var peerId: String = ""
    override var role: String = ""
    var isAudioMuted = false
    var isCamInProgress = false
    fun clear() {
        isCamInProgress = false
        isAudioMuted = false
    }
}