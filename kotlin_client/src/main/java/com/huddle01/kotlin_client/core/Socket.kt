package com.huddle01.kotlin_client.core

import RequestOuterClass.CloseConsumer
import RequestOuterClass.CloseProducer
import RequestOuterClass.CloseRoom
import RequestOuterClass.ConnectRoom
import RequestOuterClass.ConnectTransport
import RequestOuterClass.Consume
import RequestOuterClass.CreateTransport
import RequestOuterClass.DenyLobbyPeer
import RequestOuterClass.KickPeer
import RequestOuterClass.Produce
import RequestOuterClass.Request
import RequestOuterClass.RestartTransportIce
import RequestOuterClass.ResumeProducer
import RequestOuterClass.PauseProducer
import RequestOuterClass.ResumeConsumer
import RequestOuterClass.SendData
import RequestOuterClass.SyncMeetingState
import RequestOuterClass.UpdatePeerMetadata
import RequestOuterClass.UpdatePeerRole
import RequestOuterClass.UpdateRoomMetadata
import ResponseOuterClass.Response
import com.huddle01.kotlin_client.common.ProtoParsing
import com.huddle01.kotlin_client.models.GeoData
import com.huddle01.kotlin_client.models.GeoLocation
import com.huddle01.kotlin_client.models.enum_class.ConnectionState
import com.huddle01.kotlin_client.types.ESocketCloseCode
import com.huddle01.kotlin_client.utils.EventEmitter
import com.huddle01.kotlin_client.utils.JsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.java_websocket.client.WebSocketClient
import org.java_websocket.enums.ReadyState
import org.java_websocket.handshake.ServerHandshake
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.util.Timer
import kotlin.concurrent.schedule
import kotlin.math.pow

/**
 * Handles the underlying socket connection with the socket server
 */
class Socket : EventEmitter() {

    companion object {
        /**
         * Socket Instance, Singleton class
         */
        private val socketInstance: Socket = Socket()

        /**
         * Returns the instance of the socket connection
         */
        fun getInstance(): Socket {
            return socketInstance
        }

    }

    /**
     * Retry count for the socket connection, if the connection is closed abnormally, we try to reconnect 5 times
     */
    private var _retryCount: Int = 0

    /**
     * Current connection state of the socket connection
     */
    private var _connectionState: ConnectionState = ConnectionState.UNINITIALIZED

    /**
     * Underlying WebSocket connection, until we dont call Socket.connect(); this will be null
     */
    private var _ws: WebSocketClient? = null


    /**
     * Map of all the subscribed events/topics for the socket connection
     */
    private val _subscribedMap: MutableMap<String, ((Response) -> Unit)> = mutableMapOf()

    /**
     * Geo data of the current socket connection, specific to the Local Peer who joined the meeting
     */
    private var _geoData: GeoData? = null

    /**
     * Endpoint of the socket server, this is fetched from the API server
     */
    private var _endpoint: String? = null


    /**
     * Token of the current socket connection, specific to the Local Peer who joined the meeting
     */
    var token: String? = null

    /**
     * Returns the underlying WebSocket connection, throws an error if the connection is not initialized
     */
    val ws: WebSocketClient?
        get() = _ws


    /**
     * Getter for the region of the current socket connection
     */
    val region: String?
        get() = _geoData?.region

    /**
     * Getter for the geo data of the current socket connection
     */
    val geoData: GeoData?
        get() = _geoData

    /**
     * Returns the current connection state of the socket connection
     */
    var connectionState: ConnectionState
        get() = _connectionState
        set(value) {
            Timber.i("🔌 WebSocket state changed to $value")
            _connectionState = value
        }

    /**
     * Returns true if the socket connection is connected
     */
    val connected: Boolean
        get() = _ws?.readyState == ReadyState.OPEN && connectionState == ConnectionState.CONNECTED

    /**
     * Returns true if the socket connection is closed
     */
    val closed: Boolean
        get() = _ws?.readyState == ReadyState.CLOSED || _ws?.readyState == ReadyState.CLOSING


    /**
     * Update the token for this socket
     */
    fun setTokenValue(token: String) {
        if (this.token != null) {
            throw Error("🔴Token Already Set")
        }
        this.token = token
        emit("token-updated", token)
    }

    /**
     * Set a new region for the socket connection
     */
    fun setRegion(region: String) {
        if (_geoData != null) {
            _geoData?.region = region
        } else {
            _geoData = GeoData(country = "IN", region = region)
        }
        emit("region-updated", region)
    }

    private suspend fun getGeoData(): GeoData {
        return withContext(Dispatchers.IO) {
            val url = URL("https://shinigami.huddle01.com/api/get-geolocation")
            var connection: HttpURLConnection? = null

            try {
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("🔴 Error while finding the region to connect to: $responseCode")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val responseData = JsonUtils.toJsonObject(response)
                val geolocationData = GeoLocation.fromMap(responseData)
                if (geolocationData.globalRegion.isEmpty()) {
                    throw Exception("🔴 Error while finding the region to connect to")
                }
                GeoData(region = geolocationData.globalRegion, country = geolocationData.country)
            } catch (err: Throwable) {
                Timber.e("getGeoData() | Error: $err")
                throw err
            } finally {
                connection?.disconnect()
            }
        }
    }

    /**
     * Connect to the socket server using the token
     */
    suspend fun connect(token: String): Socket {
        if (this.token == null) {
            setTokenValue(token)
        }
        if (connectionState == ConnectionState.CONNECTED) {
            Timber.e("🔴Socket Already Connected")
            return socketInstance
        }
        if (connectionState == ConnectionState.CONNECTING) {
            Timber.e("🔴Socket Connecting")
            return this
        }
        if (_ws != null) {
            Timber.e("🔴Socket Already Initialized")
            return socketInstance
        }
        Timber.i("🔌Connecting to the socket server")

        if (_geoData == null) {
            val geoData = getGeoData()
            this._geoData = geoData
        }
        val url = geoData?.let { getConfigUrl(token, it.region, it.country) }

        Timber.i("Region -> $geoData | URL -> $url")
        this.connectionState = ConnectionState.CONNECTING
        emit("connecting")

        _ws = object : WebSocketClient(URI(url)) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                handleSocketOpen()
            }

            override fun onMessage(message: String?) {
                Timber.i("onMessage String: $message")
            }

            override fun onMessage(bytes: ByteBuffer?) {
                super.onMessage(bytes)
                if (bytes != null) {
                    handleIncomingMessage(bytes)
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                runBlocking {
                    handleSocketClose(code, reason)
                }
            }

            override fun onError(ex: java.lang.Exception?) {
                handleSocketError(ex)
            }

        }

        runBlocking {
            _ws?.connectBlocking()
        }
        return socketInstance
    }


    /**
     * Closes the underlying socket connection, and clears all the event listeners and subscriptions to events as well as
     */
    suspend fun close(code: Int, reason: String?, reconnect: Boolean = false) {
        if ((code in 3000..4999) || code == 1000 || (code == 1001 && !reconnect)) {
            Timber.i("🔌 Closing the connection, $code $reason")
            _ws?.close(code, reason)
            _ws = null

            token = null

            _endpoint = null

            emit("token-updated")

            connectionState = ConnectionState.CLOSED

            emit("closed", code)

            Timber.i("🔌 WebSocket Connection closed")

            return
        }
        _subscribedMap.clear()

        _ws?.close()
        _ws = null

        if (code == ESocketCloseCode.ABNORMAL_CLOSURE.value || reconnect) {
            Timber.i("🔌 Socket Connection closed abnormally, reconnecting  | code: $code | reason: $reason")

            if (_retryCount < 7) {
                val delay = 2.0.pow(_retryCount.toDouble()).toInt() * 1000

                Timer().schedule(delay.toLong()) {
                    if (token != null) {
                        try {
                            Timber.i("🔔 Trying to reconnect, Attempt: $_retryCount")
                            connectionState = ConnectionState.RECONNECTING
                            emit("reconnecting")
                            CoroutineScope(Dispatchers.Default).launch {
                                if (token != null) {
                                    try {
                                        connect(token!!)
                                        if (_retryCount > 0) {
                                            emit("reconnected")
                                        }
                                        _retryCount = 0
                                    } catch (e: Exception) {
                                        if (_retryCount == 7) {
                                            Timber.e("All Reconnection Attempt failed", e)
                                        }
                                    }
                                }
                            }
                        } catch (err: Exception) {
                            Timber.e("Reconnection Attempt $_retryCount failed | err: $err")
                        }
                    }
                }
                _retryCount++
            } else {
                Timber.e("🔴 Socket connection closed abnormally, reconnecting failed")
                close(ESocketCloseCode.CONNECTION_EXPIRED.value, null)
            }
        } else {
            Timber.i("🔌 Socket Connection closed | code: $code | reason: $reason")
            connectionState = ConnectionState.CLOSED
            emit("closed", code)
        }
    }

    /**
     * Publish a message to the server using the socket connection based on some events/topics
     */
    fun publish(event: Request.RequestCase, incomingData: Map<String, Any?>?) {
        val message = Request.newBuilder()

        when (event) {
            Request.RequestCase.CONNECT_ROOM -> message.connectRoom =
                ConnectRoom.newBuilder().setRoomId(incomingData?.get("roomId") as? String).build()

            Request.RequestCase.CLOSE_ROOM -> message.closeRoom = CloseRoom.newBuilder().build()

            Request.RequestCase.UPDATE_ROOM_METADATA -> message.updateRoomMetadata =
                UpdateRoomMetadata.newBuilder().build()

            Request.RequestCase.KICK_PEER -> message.kickPeer =
                KickPeer.newBuilder().setPeerId(incomingData?.get("peerId") as? String).build()

            Request.RequestCase.DENY_LOBBY_PEER -> message.denyLobbyPeer =
                DenyLobbyPeer.newBuilder().setPeerId(incomingData?.get("peerId") as? String).build()

            Request.RequestCase.SYNC_MEETING_STATE -> message.syncMeetingState =
                SyncMeetingState.newBuilder().build()

            Request.RequestCase.RESTART_TRANSPORT_ICE -> message.restartTransportIce =
                RestartTransportIce.newBuilder()
                    .setTransportId(incomingData?.get("transportId") as? String)
                    .setTransportType(incomingData?.get("transportType") as? String).build()

            Request.RequestCase.CREATE_TRANSPORT -> {
                val deviceSctpCapabilities = incomingData?.get("deviceSctpCapabilities").toString()
                message.createTransport = CreateTransport.newBuilder().setSctpCapabilities(
                    ProtoParsing.getProtoSctpCapabilities(
                        deviceSctpCapabilities
                    )
                ).setTransportType(incomingData?.get("transportType") as String).build()
            }

            Request.RequestCase.CLOSE_PRODUCER -> message.closeProducer =
                CloseProducer.newBuilder().setProducerId(incomingData?.get("producerId") as? String)
                    .build()

            Request.RequestCase.CONNECT_TRANSPORT -> {
                val dtlsParameters = incomingData?.get("dtlsParameters").toString()
                message.connectTransport = ConnectTransport.newBuilder()
                    .setDtlsParameters(ProtoParsing.getProtoDtlsParameters(dtlsParameters))
                    .setTransportType(incomingData?.get("transportType") as? String).build()
            }

            Request.RequestCase.PRODUCE -> {
                val rtpParameters = incomingData?.get("rtpParameters").toString()
                val appData = incomingData?.get("appData").toString()
                message.produce = Produce.newBuilder()
                    .setRtpParameters(ProtoParsing.getProtoRtpParameters(rtpParameters))
                    .setKind(incomingData?.get("kind") as? String)
                    .setLabel(incomingData?.get("label") as? String)
                    .setAppData(ProtoParsing.parseProtoAppData(appData))
                    .setPaused(incomingData?.get("paused") as Boolean).build()
            }

            Request.RequestCase.CONSUME -> {
                val appData = incomingData?.get("appData").toString()
                message.consume =
                    Consume.newBuilder()
                        .setProducerId(incomingData?.get("producerId") as? String)
                        .setProducerPeerId(incomingData?.get("producerPeerId") as? String)
                        .setAppData(ProtoParsing.parseProtoAppData(appData)).build()
            }

            Request.RequestCase.RESUME_CONSUMER -> message.resumeConsumer =
                ResumeConsumer.newBuilder()
                    .setConsumerId(incomingData?.get("consumerId") as? String)
                    .setProducerPeerId(incomingData?.get("producerPeerId") as? String).build()

            Request.RequestCase.UPDATE_PEER_METADATA -> message.updatePeerMetadata =
                UpdatePeerMetadata.newBuilder().setPeerId(incomingData?.get("peerId") as? String)
                    .setMetadata(incomingData?.get("metadata") as? String).build()

            Request.RequestCase.UPDATE_PEER_ROLE -> message.updatePeerRole =
                UpdatePeerRole.newBuilder().setPeerId(incomingData?.get("peerId") as? String)
                    .setRole(incomingData?.get("role") as? String).build()

            Request.RequestCase.CLOSE_CONSUMER -> message.closeConsumer =
                CloseConsumer.newBuilder().setConsumerId(incomingData?.get("consumerId") as? String)
                    .build()

            Request.RequestCase.RESUME_PRODUCER -> message.resumeProducer =
                ResumeProducer.newBuilder().setProducerId(incomingData?.get("producerId") as? String)
                    .build()

            Request.RequestCase.PAUSE_PRODUCER -> message.pauseProducer =
                PauseProducer.newBuilder().setProducerId(incomingData?.get("producerId") as? String)
                    .build()
            Request.RequestCase.SEND_DATA -> message.sendData =
                SendData.newBuilder().apply {
                    val toList = incomingData?.get("to") as? ArrayList<*>
                    if (toList != null && toList.isNotEmpty()) {
                        for ((index, toValue) in toList.withIndex()) {
                            while (toCount <= index) {
                                addTo("")
                            }
                            setTo(index, toValue.toString())
                        }
                    }
                    setPayload(incomingData?.get("payload") as? String ?: "")
                    setLabel(incomingData?.get("label") as? String ?: "")
                }.build()

            else -> throw IllegalArgumentException("Invalid request case: $event")
        }
        _ws?.send(message.build().toByteArray())
    }

    /**
     * Subscribe to a specific event/topic from the server
     */
    fun <K> subscribe(event: K, fn: (Response) -> Any) {
        val eventKey = event.toString()

        if (_subscribedMap.containsKey(eventKey)) {
            Timber.w("⚠️ Overriding existing event handler")
        }
        _subscribedMap[eventKey] = fn as (Any) -> Unit
    }

    /**
     * Get the config url for the socket connection based on the token and region
     */
    private suspend fun getConfigUrl(token: String, region: String, country: String): String =
        withContext(Dispatchers.IO) {
            if (_endpoint != null) return@withContext _endpoint!!

            val apiServerUrl = "https://apira.huddle01.media/api/v1/getSushiUrl"
            var connection: HttpURLConnection? = null

            try {
                val url = URL(apiServerUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("🔴 Error while fetching the configuration URL: $responseCode")
                }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val responseData = JsonUtils.toJsonObject(response)
                val urlString = responseData["url"] as String

                _endpoint = urlString.replaceFirst("https://", "wss://")
                    .replaceFirst("http://", "ws://")
                val wssAddress = "$_endpoint/ws"
                val wsAddress = "$wssAddress?${
                    listOf(
                        "token=$token", "version=2", "region=$region", "country=$country"
                    ).joinToString("&")
                }"
                _endpoint = wsAddress
                return@withContext wsAddress
            } catch (err: Throwable) {
                Timber.e("getConfigUrl() | Error: $err")
                throw err
            } finally {
                connection?.disconnect()
            }
        }

    /**
     * Handle the incoming message from the server based on the events received from the server and call the subscribed event handler
     */
    private fun handleIncomingMessage(eventData: ByteBuffer) {
        val msg = Response.parseFrom(eventData)
        val eventName = msg.responseCase.name
        Timber.i("📨 Incoming message event name: $eventName")
        _subscribedMap[eventName]?.let { fn ->
            return try {
                fn.invoke(msg)
            } catch (e: ClassCastException) {
                Timber.e("Error: Function not compatible with message type", e)
            }
        }
    }

    private fun handleSocketError(error: java.lang.Exception?) {
        Timber.e("Socket connection error: $error")
    }

    /**
     * Handle the socket close event which is sent by the server
     */
    private suspend fun handleSocketClose(code: Int, reason: String?) {
        Timber.i("🔔 Socket connection closed emitted")
        connectionState = ConnectionState.CLOSED
        this.close(code, reason)
    }

    /**
     * Handle the socket open event which is sent after the connection is established with the server
     */
    private fun handleSocketOpen() {
        Timber.i("Socket Connection Open")
        connectionState = ConnectionState.CONNECTED
        emit("connected")
    }
}
