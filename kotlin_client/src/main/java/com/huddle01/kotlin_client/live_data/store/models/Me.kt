package com.huddle01.kotlin_client.live_data.store.models

import org.webrtc.MediaStreamTrack

class Me : Info() {
    override var peerId: String = ""
    override var role: String = ""
    var isAudioMuted = false
    var isCamInProgress = false
    var myConsumedTracks = HashMap<String, MediaStreamTrack>()

    fun clear() {
        isCamInProgress = false
        isAudioMuted = false
        myConsumedTracks.clear()
    }
}