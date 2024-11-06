package com.huddle01.kotlin_client.live_data.store.models

import io.github.crow_misia.mediasoup.Consumer
import org.json.JSONObject
import timber.log.Timber
import java.util.Collections

class Peers {
    private val peersInfo = Collections.synchronizedMap(LinkedHashMap<String, Peer>())

    fun addPeer(peerId: String, peerInfo: JSONObject) {
        peersInfo[peerId] = Peer(peerInfo)
    }

    fun removePeer(peerId: String) {
        peersInfo.remove(peerId)
    }

    fun addConsumer(peerId: String, consumer: Consumer) {
        val peer = getPeer(peerId) ?: run {
            Timber.e("no Peer found for new Consumer")
            return
        }
        peer.consumers[peerId] = consumer
    }

    fun removeConsumer(peerId: String) {
        val peer = getPeer(peerId) ?: return
        peer.consumers.remove(peerId)
    }

    fun getPeer(peerId: String): Peer? {
        return peersInfo[peerId]
    }

    val allPeers: List<Peer>
        get() = peersInfo.values.toList()

    val hostPeer: Peer?
        get() = peersInfo.values.firstOrNull { it.role == "host" }

    fun clear() {
        peersInfo.clear()
    }
}
