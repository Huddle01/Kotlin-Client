package com.huddle01.kotlin_client

import RequestOuterClass.Request
import android.content.Context
import com.huddle01.kotlin_client.core.Room
import com.huddle01.kotlin_client.core.LocalPeer
import com.huddle01.kotlin_client.core.Socket
import com.huddle01.kotlin_client.models.enum_class.ConnectionState
import com.huddle01.kotlin_client.models.enum_class.RoomStates
import com.huddle01.kotlin_client.types.ESocketCloseCode
import io.github.crow_misia.mediasoup.MediasoupClient
import io.github.crow_misia.webrtc.log.LogHandler
import org.webrtc.Logging
import timber.log.Timber


class HuddleClient(projectId: String, context: Context) {

    /**
     * Connection Manager Instance, Handler socket connection and stores information about the connection
     */
    private var _socket: Socket

    /**
     * Room Instance, Handles the room and its connection
     */
    private var _room: Room

    /**
     * Local Peer Instance, Handles the local peer and its connection
     */
    private var _localPeer: LocalPeer

    /**
     * Project Id of the Huddle01 Project
     */
    var projectId: String = ""

    /**
     * Returns the underlying socket connection
     * @throws { Error } If the socket connection is not initialized
     */
    val socket: Socket
        get() {
            if (_socket.closed) {
                throw Error(
                    "Socket Is Not Initialized, You need to connect to the Huddle01 Socket Servers first"
                )
            }
            return _socket
        }

    /**
     * Returns the room instance
     */
    val room: Room
        get() = _room

    /**
     * Returns the localPeer instance
     */
    val localPeer: LocalPeer
        get() = _localPeer

    /**
     * Room Id of the current room
     */
    val roomId: String?
        get() = room.roomId

    /**
     * Set a new region for the Huddle01 Media Servers
     */
    fun setRegion(region: String) {
        Timber.i("Setting a new region, $region")
        socket.setRegion(region)
    }

    init {
        enableLogs()
        initSDKConnection(application = context.applicationContext as  android.app.Application)
        Timber.i("✅ Initializing HuddleClient")
        this.projectId = projectId
        _socket = Socket.getInstance()
        _room = Room.getInstance()
        _localPeer = LocalPeer.getInstance(context)
        _socket.on("closed") { code ->
            Timber.i("Socket Connection closed, closing the room and LocalPeer")
            when (code) {
                arrayOf(ESocketCloseCode.ROOM_CLOSED.value) -> room.close(reason = "CLOSED")
                arrayOf(ESocketCloseCode.ROOM_ENTRY_DENIED.value) -> room.close(reason = "DENIED")
                arrayOf(ESocketCloseCode.CONNECTION_EXPIRED.value) -> {
                    Timber.i("🔔 Room closed due to connection expired")
                    room.close(reason = "CONNECTION_EXPIRED")
                }

                arrayOf(ESocketCloseCode.KICKED.value) -> room.close(reason = "KICKED")
                else -> room.close()
            }
            _localPeer.close()
        }

    }

    /** Default method to connect to the Huddle01 Media Room.
     * This method connects to socket, creates a room, and then connects to the room
     */
    suspend fun joinRoom(roomId: String, token: String): Room {
        Timber.i("Join the room with roomId: $roomId")

        if (socket.connectionState == ConnectionState.CONNECTING) {
            Timber.w("Socket is already connecting, waiting for the connection to be established")
            return room
        }

        if (room.state == RoomStates.CONNECTING) {
            Timber.w("🔔 Room join already in progress")
            return room
        }

        if (localPeer.joined) {
            Timber.w("Already joined the room")
            return room
        }
        return try {
            socket.connect(token)
            Timber.i("✅ Socket Connection Established")
            room.roomId = roomId
            Timber.i("🚪 Room Id => ${room.roomId}")
            val room: Room = this.room.connect()
            Timber.i("🚪 Room Connection Established")
            room
        } catch (error: Throwable) {
            Timber.e("🔴 Error While Joining the Room => $error")
            throw error
        }
    }


    /**
     * Leave the room and disconnect from the socket
     */
    suspend fun leaveRoom() {
        Timber.i("Leaving the room")
        socket.close(ESocketCloseCode.NORMAL_CLOSURE.value, null)
    }

    /**
     * Close the room and disconnect from the socket
     */
    fun closeRoom() {
        Timber.i("Closing the room")
        socket.publish(Request.RequestCase.CLOSE_ROOM, null)
    }

    private fun enableLogs() {
        Timber.plant(Timber.DebugTree())
    }

    private fun initSDKConnection(application:  android.app.Application) {
        MediasoupClient.initialize(
            context = application,
            logHandler = object : LogHandler {
                override fun log(
                    priority: Int,
                    tag: String?,
                    t: Throwable?,
                    message: String?,
                    vararg args: Any?,
                ) {
                    tag?.also { Timber.tag(it) }
                    Timber.log(priority, t, message, *args)
                    Timber.log(priority, t, "Huddle01 Kotlin SDK", *args)
                }
            },
            loggableSeverity = Logging.Severity.LS_INFO,
        )
    }
}