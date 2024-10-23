package com.huddle01.kotlin_client.core

import RequestOuterClass.Request
import ResponseOuterClass
import com.huddle01.kotlin_client.models.LobbyPeer
import com.huddle01.kotlin_client.models.RoomConfig
import com.huddle01.kotlin_client.models.RoomStats
import com.huddle01.kotlin_client.models.enum_class.RoomControlType
import com.huddle01.kotlin_client.models.enum_class.RoomStates
import com.huddle01.kotlin_client.utils.EventEmitter
import com.huddle01.kotlin_client.utils.JsonUtils
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

class Room(
    autoConsume: Boolean = true,
) : EventEmitter() {

    companion object {
        /**
         * Room Instance, Singleton class
         */
        private val roomInstance: Room = Room()

        /** Get the Room Instance
         *  @returns - Room Instance
         */
        fun getInstance(): Room {
            return roomInstance
        }
    }

    /**
     * Socket Instance, Singleton class
     */
    private val socketInstance: Socket by lazy {
        Socket.getInstance()
    }

    /**
     * Returns the instance of the socket connection
     */
    private val socket: Socket
        get() = socketInstance

    /**
     * Room Id of the current room
     */
    private var _roomId: String? = null


    /**
     * Session Id
     */
    private var _sessionId: String? = null

    /**
     * Permissions Instance
     */
    private val permissions: Permissions = Permissions()

    /**
     * Lobby PeerIds
     */
    private val _lobbyPeers: MutableMap<String, LobbyPeer> = mutableMapOf()

    /**
     * Removed Lobby PeerId from the lobby
     */
    private fun removeLobbyPeer(peerId: String) {
        _lobbyPeers.remove(peerId)
        emit("lobby-peers-updated", lobbyPeerIds)
    }

    /** Room Config Object
     *  - `allowProduce`: Allow non-admin Peers in the Room to produce Media Streams
     *  - `allowConsume`: Allow non-admin Peers in the Room to consume Media Streams
     *  - `allowSendData`: Allow non-admin Peers in the Room to send data message
     *  - `roomLocked`: If the room is locked
     */
    private var config = RoomConfig()
    fun getConfig(): RoomConfig = config.copy()
    fun updateConfig(newConfig: RoomConfig) {
        config = newConfig.copy()
    }

    /**
     * Auto consume flag, if set to true, Peers Joining the Room will automatically consume the media streams of the remote peers
     *
     * @default true
     *
     * @remarks - This flag is used by the `useRoom` hook to automatically consume the media streams of the remote peers,
     * - if set to false, the user will have to manually consume the media streams of the remote peers
     * using the `consume` method of the `LocalPeer` instance `localPeer.consume({ peerId, label: "video", appData: {} });`
     */
    val autoConsume: Boolean = true

    /**
     * State of the Room
     */
    var state: RoomStates = RoomStates.IDLE
        set(newState) {
            if (field != newState) {
                field = newState
            }
        }

    /**
     * Get the lobby peers in the form of map
     */
    var lobbyPeersMap: MutableMap<String, LobbyPeer> = mutableMapOf()
        get() = _lobbyPeers
        set(value) {
            field = value
            emit("lobby-peers-updated", this.lobbyPeerIds)
        }

    /**
     * Get lobby peers in the form of list
     */
    val lobbyPeerIds: List<String>
        get() = _lobbyPeers.keys.toList()

    /**
     * Get lobby peers in the form of array
     */
    val lobbyPeers: MutableMap<String, LobbyPeer>
        get() = _lobbyPeers

    /**
     * Room Stats
     */
    private var stats: RoomStats = RoomStats(startTime = 0)
    fun getStats(): RoomStats = stats.copy()
    fun updateStats(newStats: RoomStats) {
        stats = newStats
    }

    fun getLobbyPeerMetadata(peerId: String): Map<String, Any> {
        val lobbyPeer = _lobbyPeers[peerId]
        val metadata: MutableMap<String, Any> = mutableMapOf()
        val lobbyMetaData = lobbyPeer?.metadata
        if (lobbyMetaData != null) {
            try {
                val jsonObject = JsonUtils.toJsonObject(lobbyMetaData)
                jsonObject.keys().forEach { key ->
                    metadata[key] = jsonObject.get(key)
                }
            } catch (e: JSONException) {
                Timber.e("Error parsing JSON metadata: ${e.message}")
            }
        }

        return mapOf(
            "peerId" to peerId, "metadata" to metadata
        )
    }

    /**
     * Set lobby peers in the form of array
     */
    fun setNewLobbyPeers(
        peers: List<ResponseOuterClass.LobbyPeers>? = null,
        newLobbyPeer: ResponseOuterClass.NewLobbyPeer? = null,
    ) {
        peers?.forEach { peerMap ->
            peerMap.peerId?.let { peerId ->
                _lobbyPeers[peerId] = LobbyPeer(peerId, peerMap.metadata)
            }
        }

        newLobbyPeer?.peerId?.let { peerId ->
            _lobbyPeers[peerId] = LobbyPeer(peerId, newLobbyPeer.metadata)
        }

        emit("lobby-peers-updated", lobbyPeerIds)
    }

    /**
     * Remote Peers Map, Stores all the remote peers
     */
    val remotePeers: MutableMap<String, RemotePeer> = mutableMapOf()

    /**
     * Metadata of the room.
     */
    private var _metadata: String? = "{}"
    var metadata: String?
        get() = _metadata ?: "{}"
        set(value) {
            val parsedMetadata = value?.let { JsonUtils.toJsonObject(it) }
            if (parsedMetadata != null) {
                emit("metadata-updated", parsedMetadata)
            }
        }

    /**
     * Get the metadata of the room
     */
    fun getMetadata(): JSONObject? {
        return _metadata?.let { JsonUtils.toJsonObject(it) }
    }

    /**
     * Update Metadata of the room
     * @throws { Error } If Request Failed to Update Metadata
     */
    fun updateMetadata(data: String) {
        try {
            if (state == RoomStates.CLOSED || state == RoomStates.FAILED || state == RoomStates.LEFT) {
                Timber.e("❌ Cannot Update Metadata, You have not joined the room yet")
                return
            }

            val metadata = JsonUtils.toJsonObject(data)

            this.metadata = metadata.toString()
            socket.publish(
                Request.RequestCase.UPDATE_ROOM_METADATA, mapOf(
                    "metadata" to metadata
                )
            )
        } catch (error: Exception) {
            Timber.e("❌ Error Updating Metadata | error: $error")
        }
    }

    var sessionId: String?
        get() = _sessionId
        set(value) {
            if (_sessionId != null) {
                Timber.w(
                    "sessionId is already set, Ignoring the new sessionId, end this room and create a new room"
                )
                return
            }
            _sessionId = value
        }

    /**
     * RoomId of the currently joined room.
     */
    var roomId: String?
        get() = _roomId
        set(value) {
            if (_roomId != null) {
                Timber.i("RoomId is already set, Ignoring the new roomId, end this room and create a new room")
                return
            }
            _roomId = value
        }

    /**
     * Returns the PeerIds of the remote peers
     */
    val peerIds: Set<String>
        get() = remotePeers.keys.toSet()

    /**
    / Update room control booleans - roomLocked, allowProduce, allowConsume, allowSendData
     */
    fun updateRoomControls(roomControlType: RoomControlType, roomControlTypeValue: Boolean) {
        if (!permissions.checkPermission("admin")) return
        Timber.i("🔔 Updating Room Controls")

        when (roomControlType) {
            RoomControlType.LOCK_ROOM -> config.roomLocked = true
            RoomControlType.UNLOCK_ROOM -> config.roomLocked = false
            RoomControlType.ENABLE_PRODUCE -> config.allowProduce = true
            RoomControlType.DISABLE_PRODUCE -> config.allowProduce = false
            RoomControlType.UPDATE_PRODUCE_SOURCES_CAM -> config.allowProduceSources.cam =
                roomControlTypeValue

            RoomControlType.UPDATE_PRODUCE_SOURCES_MIC -> config.allowProduceSources.mic =
                roomControlTypeValue

            RoomControlType.UPDATE_PRODUCE_SOURCES_SCREEN -> config.allowProduceSources.screen =
                roomControlTypeValue

            RoomControlType.ENABLE_CONSUME -> config.allowConsume = true
            RoomControlType.DISABLE_CONSUME -> config.allowConsume = false
            RoomControlType.ENABLE_SEND_DATA -> config.allowSendData = true
            RoomControlType.DISABLE_SEND_DATA -> config.allowSendData = false
        }

        emit("room-controls-updated")

        val controlType =
            if (roomControlType == RoomControlType.UPDATE_PRODUCE_SOURCES_MIC || roomControlType == RoomControlType.UPDATE_PRODUCE_SOURCES_CAM || roomControlType == RoomControlType.UPDATE_PRODUCE_SOURCES_SCREEN) {
                "produceSourcesControl"
            } else {
                "roomControl"
            }

        val controlData = mapOf(
            "control" to mapOf(
                "case" to controlType, "value" to roomControlType
            )
        )
        socket.publish(Request.RequestCase.UPDATE_ROOM_CONTROLS, controlData)
    }

    /**
     * Close a particular stream of remote peers
     */

    fun closeStreamOfLabel(label: String, peerIds: List<String>?) {
        if (!permissions.checkPermission("admin")) return
        socket.publish(
            Request.RequestCase.CLOSE_STREAM_OF_LABEL, mapOf("label" to label, "peerIds" to peerIds)
        )
    }

    /**
     * Mute everyone in the room. This will close the audio stream of all the remote peers who don't have admin permissions
     */
    fun muteEveryone(peerId: String) {
        if (!permissions.checkPermission("admin")) return
        Timber.i("🔔 Muting Everyone")
        socket.publish(Request.RequestCase.CLOSE_STREAM_OF_LABEL, mapOf("label" to "audio"))
    }


    /**
     * Returns the RemotePeer with the given peerId if present in the room.
     */
    fun remotePeerExists(peerId: String): RemotePeer? {
        return remotePeers[peerId]
    }

    /**
     * Returns the RemotePeer if present in the room.
     */
    fun getRemotePeerById(peerId: String): RemotePeer {
        return remotePeers[peerId]
            ?: throw NoSuchElementException("Remote Peer Not Found, peerId: $peerId")
    }

    /**
     * Connects to the room and returns the instance of the room
     * @throws { Error } If the socket connection is not connected, or if the socket connection is connecting
     */
    fun connect(): Room {
        Timber.i("🚪 Room | Connect")
        if (!socketInstance.connected) {
            throw Exception("Socket is Not Connected")
        }

        if (_roomId == null) {
            throw Exception("Room Id is required to connect to the room")
        }
        Timber.i("🔔 Connecting to the room")
        socket.publish(Request.RequestCase.CONNECT_ROOM, mapOf("roomId" to roomId as String))
        state = RoomStates.CONNECTING
        emit("room-connecting")
        return roomInstance
    }

    /**
     * Admit a Peer to the room who is in the lobby
     * @throws {Error} If the Peer Calling this Function is not an admin, then the permissions will not be updated
     */
    fun admitPeer(peerId: String) {
        if (!permissions.checkPermission("admin")) return
        try {
            this.removeLobbyPeer(peerId)
            socket.publish(Request.RequestCase.ACCEPT_LOBBY_PEER, mapOf("peerId" to peerId))
        } catch (error: Exception) {
            Timber.e("🔴 Error admitting peer", error)
        }
    }

    /**
     * Denies the peer from joining the room, who is in the lobby
     */
    fun denyPeer(peerId: String) {
        if (!permissions.checkPermission("admin")) return

        try {
            socket.publish(Request.RequestCase.DENY_LOBBY_PEER, mapOf("peerId" to peerId))
        } catch (error: Exception) {
            Timber.e("🔴 Error deny lobby peer", error)
        }
    }

    /**
     * kick peer from room with respective peerId
     * @throws { Error } If the Peer Calling this Function is not an admin, then the permissions will not be updated
     */
    fun kickPeer(peerId: String) {
        if (!permissions.checkPermission("admin")) return

        try {
            socket.publish(Request.RequestCase.KICK_PEER, mapOf("peerId" to peerId))
        } catch (error: Exception) {
            Timber.e("🔴 Error kicking peer", error)
        }
    }

    /**
     * closing the room for the current user, room will keep on running for the remote users
     */
    fun close(reason: String? = null) {
        try {
            Timber.i("🔴 Leaving the room")
            _roomId = null
            _sessionId = null
            remotePeers.clear()
            lobbyPeers.clear()
            metadata = "{}"
            state = RoomStates.LEFT
            emit("room-closed", mapOf("reason" to (reason ?: "LEFT")))
        } catch (error: Throwable) {
            Timber.e("Error: Leaving the Room => $error")
        }
    }
}