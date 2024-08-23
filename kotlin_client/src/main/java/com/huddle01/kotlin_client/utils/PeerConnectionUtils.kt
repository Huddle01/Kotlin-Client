package com.huddle01.kotlin_client.utils

import org.webrtc.EglBase

object PeerConnectionUtils {
    private val eglBase = EglBase.create()
    val eglContext: EglBase.Context = eglBase.eglBaseContext
}
