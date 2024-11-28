package com.huddle01.kotlin_client.live_data.store

import androidx.lifecycle.ViewModel
import com.huddle01.kotlin_client.live_data.SupplierMutableLiveData
import com.huddle01.kotlin_client.live_data.store.models.Me
import com.huddle01.kotlin_client.live_data.store.models.Peers
import com.huddle01.kotlin_client.live_data.store.models.RoomInfo
import com.huddle01.kotlin_client.models.enum_class.RoomStates
import io.github.crow_misia.mediasoup.Consumer
import org.json.JSONObject
import org.webrtc.MediaStreamTrack

class HuddleStore : ViewModel() {

    val roomInfo = SupplierMutableLiveData { RoomInfo() }
    val me =
        SupplierMutableLiveData { Me() }
    val peers = SupplierMutableLiveData { Peers() }

    companion object {

        private var instance: HuddleStore? = null

        fun getInstance(): HuddleStore {
            return instance ?: synchronized(this) {
                instance ?: HuddleStore().also { instance = it }
            }
        }

        fun destroy() {
            instance?.let {
                it.me.postValue { Me() }
                it.peers.postValue { Peers() }
                it.roomInfo.postValue { RoomInfo() }
                instance = null
            }
        }
    }

    fun setRoomId(roomId: String) {
        roomInfo.postValue {
            it.roomId = roomId
        }
    }

    fun setRoomState(state: RoomStates) {
        roomInfo.postValue { it.connectionState = state }
        if (RoomStates.CLOSED == state) {
            peers.postValue { it.clear() }
            me.postValue { it.clear() }
            destroy()
        }
    }

    fun setMe(
        peerId: String,
        role: String,
    ) {
        me.postValue {
            it.peerId = peerId
            it.role = role
        }
    }

    fun setMyConsumedTracks(
        peerId: String,
        mediaStreamTrack: MediaStreamTrack,
    ) {
        me.postValue {
            it.myConsumedTracks[peerId] = mediaStreamTrack
        }
    }

    fun setCamInProgress(inProgress: Boolean) {
        me.postValue { it.isCamInProgress = inProgress }
    }

    fun addPeer(peerId: String, peerInfo: JSONObject) {
        peers.postValue { it.addPeer(peerId, peerInfo) }
    }

    fun removePeer(peerId: String) {
        peers.postValue { it.removePeer(peerId) }
    }

    fun addConsumer(peerId: String, consumer: Consumer) {
        peers.postValue { it.addConsumer(peerId, consumer) }
    }

    fun removeConsumer(peerId: String) {
        peers.postValue { it.removeConsumer(peerId) }
    }

}