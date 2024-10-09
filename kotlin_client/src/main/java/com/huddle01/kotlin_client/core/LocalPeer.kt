package com.huddle01.kotlin_client.core

import RequestOuterClass.Request
import ResponseOuterClass
import ResponseOuterClass.CloseConsumerSuccess
import ResponseOuterClass.CloseProducerSuccess
import ResponseOuterClass.ConnectTransportResponse
import ResponseOuterClass.ConsumeResponse
import ResponseOuterClass.CreateTransportOnClient
import ResponseOuterClass.Hello
import ResponseOuterClass.LobbyPeerLeft
import ResponseOuterClass.NewPeerJoined
import ResponseOuterClass.NewPeerRole
import ResponseOuterClass.NewPermissions
import ResponseOuterClass.NewRoomControls
import ResponseOuterClass.PeerLeft
import ResponseOuterClass.PeerMetadataUpdated
import ResponseOuterClass.ProduceResponse
import ResponseOuterClass.ReceiveData
import ResponseOuterClass.Response
import ResponseOuterClass.RestartTransportIceResponse
import ResponseOuterClass.RoomClosedProducers
import ResponseOuterClass.SyncMeetingStateResponse
import android.content.Context
import android.media.MediaRecorder.AudioSource
import android.os.Handler
import android.os.Looper
import com.huddle01.kotlin_client.common.EnhancedMap
import com.huddle01.kotlin_client.common.ProtoParsing
import com.huddle01.kotlin_client.constants.maxDataMessageSize
import com.huddle01.kotlin_client.live_data.store.RoomStore
import com.huddle01.kotlin_client.models.ProduceSources
import com.huddle01.kotlin_client.models.RoomConfig
import com.huddle01.kotlin_client.models.RoomStats
import com.huddle01.kotlin_client.models.SendData
import com.huddle01.kotlin_client.models.enum_class.RoomStates
import com.huddle01.kotlin_client.types.HandlerEvents
import com.huddle01.kotlin_client.types.TransportType
import com.huddle01.kotlin_client.types.estimateSize
import com.huddle01.kotlin_client.utils.EventEmitter
import com.huddle01.kotlin_client.utils.PeerConnectionUtils
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.crow_misia.mediasoup.Consumer
import io.github.crow_misia.mediasoup.Device
import io.github.crow_misia.mediasoup.Producer
import io.github.crow_misia.mediasoup.RecvTransport
import io.github.crow_misia.mediasoup.SendTransport
import io.github.crow_misia.mediasoup.Transport
import io.github.crow_misia.mediasoup.createDevice
import io.github.crow_misia.webrtc.RTCComponentFactory
import io.github.crow_misia.webrtc.camera.CameraCapturerFactory
import io.github.crow_misia.webrtc.option.MediaConstraintsOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.CameraVideoCapturer.CameraSwitchHandler
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoTrack
import timber.log.Timber
import java.util.Locale


/** LocalPeer is the main class which handles all the functionality of the client
 *  Where Client Means the currently Running Application.
 */
class LocalPeer(
    context: Context
) : EventEmitter() {

    companion object {
        /** LocalPeer Instance, Singleton class, only one instance of this class can be created
         */
        private var localPeerInstance: LocalPeer? = null
        fun getInstance(context: Context): LocalPeer {
            return localPeerInstance ?: synchronized(this) {
                localPeerInstance ?: LocalPeer(context).also { localPeerInstance = it }
            }
        }
    }

    /** PeerId of the current client, specific to the Local Peer who joined the meeting
     *  NOTE: Until you don't join the room, this will be null
     */
    var peerId: String? = null

    // Mediasoup Kotlin
    private val appContext: Context = context.applicationContext
    private val camCapturer: VideoCapturer? by lazy {
        CameraCapturerFactory.create(
            this.appContext, fixedResolution = false, preferenceFrontCamera = true
        )
    }
    private val rtcConfig: PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(emptyList())
    private var mediaConstraintsOption: MediaConstraintsOption = MediaConstraintsOption().also {
        it.enableAudioDownstream()
        it.enableAudioUpstream()
        it.videoEncoderFactory = DefaultVideoEncoderFactory(
            PeerConnectionUtils.eglContext,
            true,
            true,
        )
        it.videoDecoderFactory = DefaultVideoDecoderFactory(
            PeerConnectionUtils.eglContext,
        )
        it.enableVideoDownstream(PeerConnectionUtils.eglContext)
        it.audioSource = AudioSource.VOICE_COMMUNICATION
        camCapturer?.also { capturer ->
            it.enableVideoUpstream(capturer, PeerConnectionUtils.eglContext)
        }
    }
    private val componentFactory = RTCComponentFactory(mediaConstraintsOption)
    private val peerConnectionFactory: PeerConnectionFactory by lazy {
        componentFactory.createPeerConnectionFactory(context) { _, _ -> }
    }
    private val localAudioManager = componentFactory.createAudioManager()
    private val localVideoManager = componentFactory.createVideoManager()
    private val mediasoupDevice: Device by lazy {
        peerConnectionFactory.createDevice()
    }

    /**
     * SendTransport handles the sending of media from the client to the server
     */
    private var _sendTransport: SendTransport? = null

    /**
     * RecvTransport handles the receiving of media from the server to the client
     */
    private var _recvTransport: RecvTransport? = null

    /**
     * Returns the room instance
     */
    private val roomInstance: Room by lazy {
        Room.getInstance()
    }

    /** Returns the underlying socket connection
     */
    private val socketInstance: Socket by lazy {
        Socket.getInstance()
    }

    /** Remote Peers Map, Stores all the remote peers
     */
    private val remotePeers: MutableMap<String, RemotePeer>
        get() = room.remotePeers

    // Stores all the pending produce tasks which are awaiting to be processed.
    private val waitingToProduce: MutableMap<String, suspend () -> Unit> = mutableMapOf()

    // Stores all the pending consume tasks which are waiting for recv transport to be re-connected.
    private val waitingToConsume: MutableList<suspend () -> Unit> = mutableListOf()

    // ActiveTracksMap holds tracks as value and key as label.
    private val activeAudioTrack: MutableMap<String, AudioTrack> = mutableMapOf()
    private val activeVideoTrack: MutableMap<String, VideoTrack> = mutableMapOf()

    // room store
    val store: RoomStore by lazy {
        RoomStore()
    }

    /** Returns the room instance
     */
    val room: Room
        get() = roomInstance

    /** Returns the underlying socket connection
     */
    val socket: Socket
        get() = socketInstance


    /** Handle the Client Side Permission for the Local Peer.
     */
    val permissions: Permissions = Permissions.getInstance()

    /** Stores the Metadata for the Local Peer.
     */
    private var metadata: String? = null

    /** Variable to check if the user has joined the room
     */
    var joined: Boolean = false

    /**
     * Get the Role of the Local Peer.
     */
    val role: String?
        get() = permissions.role

    /** Returns the token of the current socket connection,
     * specific to the Local Peer who joined the meeting
     */
    val token: String?
        get() = socket.token

    /**
     * Returns the roomId of the current joined room.
     */
    val roomId: String?
        get() = room.roomId

    /**
     * Returns the SendTransport.
     * @throws Exception if the SendTransport is not initialized
     */
    val sendTransport: Transport
        get() {
            _sendTransport?.let { return it }
            throw Exception("Send Transport Not Initialized")
        }

    /**
     * Returns the recvTransport.
     * @throws Exception if the recvTransport is not initialized
     */
    val recvTransport: Transport
        get() {
            _recvTransport?.let { return it }
            throw Exception("Recv Transport Not Initialized")
        }

    /** Transport Data
     *  Debounce to handle concurrent request to restart Ice. Waits for some time before sending
     *  more requests to restart ice.
     */
    var iceRestartDebounce: Boolean = false

    /**
     * Map of Producers, which handles the producers (sending out Media Streams).
     */
    private val producers: MutableMap<String, Producer> = mutableMapOf()

    /**
     * Map of Consumers, which handles the consumers (receiving Media Streams).
     * If `EnhancedMap` is a custom map type, you may need to define this class or use a similar type if applicable.
     */
    private val consumers: EnhancedMap<Consumer> = EnhancedMap()

    /**
     * Map of Identifiers to Producer Ids, which handles the mapping of identifiers to producer ids.
     * `identifiers` are the unique identifiers for the stream, which is used to identify the stream.
     */
    private val labelToProducerId: MutableMap<String, String> = mutableMapOf()

    /**
     * Returns the labels of the Media Streams that the Local Peer is producing to the room.
     */
    val labels: List<String>
        get() {
            val sendTransport = _sendTransport
            return if (sendTransport != null) {
                labelToProducerId.keys.toList()
            } else {
                emptyList()
            }
        }

    /**
     * Returns the metadata associated with the LocalPeer.
     */
    fun <T> getMetadata(metadata: String?): T {
        val jsonString = metadata ?: "{}"
        val type = object : TypeToken<T>() {}.type
        return Gson().fromJson(jsonString, type)
    }

    /**
     * Returns the stream with the given label.
     * @param label The label of the MediaStream to retrieve.
     * @return The MediaStream associated with the given label, or null if no such stream exists.
     */
    fun getAudioTrack(label: String): AudioTrack? {
        return activeAudioTrack[label]
    }

    fun getVideoTrack(label: String): VideoTrack? {
        return activeVideoTrack[label]
    }

    /**
     * Updates the metadata associated with the LocalPeer and triggers the `metadata-updated` event.
     * @param metadata
     */
    private fun updateMetadata(metadata: String) {
        this.metadata = metadata
        emit(
            "metadata-updated", mapOf(
                "metadata" to Gson().fromJson(metadata, Map::class.java)
            )
        )
    }

    /**
     * Returns the producer with the given label.
     */
    fun getProducerWithLabel(label: String): Producer? {
        return try {
            // Retrieve the producer ID using the provided label
            val producerId = labelToProducerId[label]
            producerId?.let { producers[it] }
        } catch (error: Exception) {
            Timber.e("❌ Cannot find producer with identifier: $label | error: $error")
            null
        }
    }

    /**
     * Send Data Gives the functionality to send data to other remote peers or the whole room
     * @return Pair(success: Boolean, error: String?) - error will be present if failed to send data
     */
    fun sendData(data: SendData): Pair<Boolean, String?> {
        if (!permissions.checkPermission("canSendData")) {
            throw Exception("❌ Cannot Send Data, Permission Denied")
        }

        if (estimateSize(data.payload) > maxDataMessageSize) {
            Timber.e("❌ Data message exceeds 1kb in size")
            return Pair(false, "Data message exceeds 1kb in size")
        }

        val parsedTo = if (data.to.size == 1 && data.to[0] == "*") listOf("*") else data.to

        return try {
            socket.publish(
                Request.RequestCase.SEND_DATA, mapOf(
                    "to" to parsedTo,
                    "payload" to data.payload,
                    "label" to data.label
                )
            )
            Pair(true, null)
        } catch (error: Exception) {
            Timber.e(error, "❌ Error Sending Data")
            Pair(false, "Error Sending Data")
        }
    }

    /**
     * Checks which direction of the WebRTC connection is currently active for the peer.
     */
    fun transportExists(transportType: TransportType): Transport? {
        return when (transportType) {
            TransportType.RECV -> _recvTransport
            TransportType.SEND -> _sendTransport
        }
    }

    fun stopProducing(label: String) {
        waitingToProduce.remove(label)

        var closedStream = false
        val producer = getProducerWithLabel(label)

        if (producer != null) {
            Timber.i("🔔 Closing Producer", mapOf("label" to label, "producerId" to producer.id))
            producers.remove(producer.id)
            producer.close()
            closedStream = true

            socket.publish(
                Request.RequestCase.CLOSE_PRODUCER, mapOf(
                    "producerId" to producer.id
                )
            )
        }

        val audioTrack = activeAudioTrack[label]
        val videoTrack = activeVideoTrack[label]

        if (audioTrack != null) {
            activeAudioTrack.remove(label)
            closedStream = true
            audioTrack.dispose()
        }
        if (videoTrack != null) {
            activeVideoTrack.remove(label)
            closedStream = true
            camCapturer?.stopCapture()
            videoTrack.dispose()
        }
        if (closedStream) {
            emit(
                "stream-closed", mapOf(
                    "label" to label, "reason" to mapOf(
                        "code" to 1200, "tag" to "STREAM_CLOSED", "message" to "Stopped Streaming"
                    )
                )
            )
        }
    }

    /**
     * Stops the underlying producing of a camera stream, stops the local track, and closes the producer.
     * NOTE: This will notify all the RemotePeers that this producer has stopped producing and they should stop consuming it.
     */
    fun disableVideo(
        videoSurfaceViewRenderer: SurfaceViewRenderer,
    ) {
        stopProducing(label = "video")
        videoSurfaceViewRenderer.run {
            release()
            setMirror(false)
            clearImage()
        }
    }

    /**
     * Stops the underlying producing of a microphone stream, stops the local track, and closes the producer.
     * NOTE: This will notify all the RemotePeers that this producer has stopped producing and they should stop consuming it.
     */
    fun disableAudio() {
        stopProducing(label = "audio")
    }


    suspend fun produce(
        label: String, audioTrack: AudioTrack?, videoTrack: VideoTrack?, appData: String?
    ) {
        Timber.i("produce called")
        try {
            if (!permissions.checkPermission("canProduce")) {
                Timber.e("Access Denied: Cannot Produce")
                return
            }

            if (!joined) {
                val completer = CompletableDeferred<Unit>()
                val fn: suspend () -> Unit = {
                    audioTrack?.let {
                        produce(label, it, null, appData)
                    }
                    videoTrack?.let {
                        produce(label, null, it, appData)
                    }
                    completer.complete(Unit)
                }
                waitingToProduce[label] = fn
                return completer.await()
            }

            val sendTransport = _sendTransport
                ?: throw IllegalStateException("❌ Send Transport Not Initialized, Internal Error")

            Timber.i("🔔 Produce Called for label: $label")

            val track = when (label) {
                "audio" -> audioTrack
                "video" -> videoTrack
                else -> throw IllegalArgumentException("❌ Invalid Label")
            }
            track?.let { currentTrack ->
                sendTransport.produce(
                    listener = object : Producer.Listener {
                        override fun onTransportClose(producer: Producer) {
                            Timber.e("onTransportClose(), producer for label: $label")
                            stopProducing(label)
                        }
                    },
                    track = currentTrack,
                    encodings = emptyList(),
                    codecOptions = null,
                    appData = appData
                ).also { producer ->
                    producers[producer.id] = producer
                    labelToProducerId[label] = producer.id
                }
            }
        } catch (error: Exception) {
            Timber.e("❌ Error Create Producer Failed | error: $error")
            throw Exception("❌ Error Create Producer Failed")
        }
    }

    suspend fun enableAudio(customAudioTrack: AudioTrack? = null): AudioTrack? {
        try {
            if (!permissions.checkPermission("admin")) {
                throw Exception("❌ Cannot Enable Audio, Permission Denied")
            }
            val existingStream = activeAudioTrack["audio"]
            if (existingStream != null) {
                Timber.w("🔔 Mic Stream Already Enabled")
                return null
            }
            // Transport is being created here
            if (_sendTransport == null) {
                createTransportOnServer(TransportType.SEND)
            }
            localAudioManager?.initTrack(peerConnectionFactory, mediaConstraintsOption)
            val localAudioTrack = localAudioManager?.track ?: run {
                Timber.w("audio track null")
                return null
            }
            localAudioTrack.let {
                activeAudioTrack["audio"] = it
                emit(
                    "stream-fetched", mapOf(
                        "mediaKind" to "mic", "track" to it, "label" to "audio"
                    )
                )
            }
            Timber.i(
                "enableAudio | fetched track => ${localAudioTrack.id()}",
            )
            produce(
                "audio",
                localAudioTrack,
                null,
                "{\"producerPeerId\":\"$peerId\",\"label\":\"audio\"}"
            )
            return localAudioTrack
        } catch (err: Exception) {
            Timber.e("❌ Error Enabling Audio $err")
            localAudioManager?.enabled = false
            return null
        }
    }

    suspend fun enableVideo(
        videoSurfaceViewRenderer: SurfaceViewRenderer,
        customVideoTrack: VideoTrack? = null,
    ): VideoTrack? {
        try {
            if (!permissions.checkPermission("admin")) {
                throw Exception("❌ Cannot Enable Video, Permission Denied")
            }
            val existingStream = activeVideoTrack["video"]
            if (existingStream != null) {
                Timber.w("🔔 Cam Stream Already Enabled")
                return null
            }
            // Transport is being created here
            if (_sendTransport == null) {
                createTransportOnServer(TransportType.SEND)
            }
            localVideoManager?.initTrack(peerConnectionFactory, mediaConstraintsOption, appContext)
            camCapturer?.startCapture(640, 480, 30)
            val localVideoTrack = localVideoManager?.track ?: run {
                Timber.w("video track null")
                return null
            }
            localVideoTrack.let {
                activeVideoTrack["video"] = it
                emit(
                    "stream-fetched", mapOf(
                        "mediaKind" to "cam", "track" to it, "label" to "video"
                    )
                )
            }
            Timber.i(
                "enableVideo | fetched track => ${localVideoTrack.id()}",
            )

            produce(
                "video",
                null,
                localVideoTrack,
                "{\"producerPeerId\":\"$peerId\",\"label\":\"video\"}"
            )
            videoSurfaceViewRenderer.init(PeerConnectionUtils.eglContext, null)
            videoSurfaceViewRenderer.setMirror(true)
            localVideoTrack.addSink(videoSurfaceViewRenderer)
            return localVideoTrack
        } catch (err: Exception) {
            Timber.e("❌ Error Enabling Video $err")
            localVideoManager?.enabled = false
            return null
        }
    }

    fun changeCam(){
        localVideoManager?.switchCamera(
            object : CameraSwitchHandler {
                override fun onCameraSwitchDone(b: Boolean) {
                    store.setCamInProgress(false)
                }
                override fun onCameraSwitchError(s: String) {
                    Timber.w("❌ Error Enabling Video $s")
                    store.setCamInProgress(false)
                }
            })
    }


    /**
     * Consumes a stream with the producerId and peerId of the RemotePeer.
     * appData is application-level custom data that can be added to the consumer for the LocalPeer.
     * This data will be available in the consumer object and can be used only by the LocalPeer.
     */
    fun consume(appData: String?, label: String, peerId: String) {
        Timber.i("consume called")
        if (!permissions.checkPermission("canConsume")) {
            return
        }
        if (_recvTransport == null) {
            Timber.i("🔔 Recv Transport Not Initialized, Creating RecvTransport")
            runBlocking {
                createTransportOnServer(transportType = TransportType.RECV)
            }
        }
        try {
            val remotePeer =
                remotePeers[peerId] ?: throw Exception("Remote Peer Not Found with PeerId $peerId")
            val labelData = remotePeer.getLabelData(label)
                ?: throw Exception("Remote Peer is not producing with Label $label")

            Timber.i("🔔 Consuming Stream with label $label")
            socket.publish(
                Request.RequestCase.CONSUME, mapOf(
                    "producerId" to labelData["producerId"],
                    "producerPeerId" to peerId,
                    "appData" to appData
                )
            )

        } catch (error: Exception) {
            Timber.e("❌ Error Consuming Stream | error: $error")
            throw error
        }
    }

    /**
     * Get the consumer by label and peerId
     * @returns Consumer?; Returns null if consumer is not found
     */
    fun getConsumer(
        label: String, peerId: String
    ): Consumer? {
        val consumer = consumers.get(label, peerId)
        return consumer
    }

    fun closeConsumer(label: String, peerId: String) {
        try {
            val consumer = getConsumer(label, peerId)
            consumer?.close()
        } catch (error: Exception) {
            Timber.e("❌ Error closing consumer | error: $error")
        }
    }

    /**
     * Stops the underlying consuming of a stream for a particular label.
     * NOTE: This does not notify the remote peers that you are not consuming a stream.
     */
    fun stopConsuming(peerId: String, label: String) {
        val remotePeer = room.getRemotePeerById(peerId)
        if (!remotePeer.hasLabel(label)) {
            Timber.e("❌ Remote Peer is not producing anything with label: $label")
            return
        }
        val consumer: Consumer? = getConsumer(label = label, peerId = peerId)
        if (consumer == null) {
            Timber.e("❌ Consumer Not Found")
            return
        }
        socket.publish(
            Request.RequestCase.CLOSE_CONSUMER, mapOf("consumerId" to consumer.id)
        )
        remotePeer.emit(
            "stream-closed", mapOf("label" to label)
        )
        closeConsumer(label, peerId)
    }

    fun produceData() {
        Timber.i("🔔Producing Data")
    }

    fun <T> updatePeerMetadata(metadata: String) {
        try {
            if (!permissions.checkPermission("canUpdateMetadata")) {
                return
            }
            if (!joined) {
                Timber.e("❌ Cannot Update Metadata, You have not joined the room yet")
                return
            }

            val peerId = this.peerId ?: run {
                Timber.e("❌ Cannot Update Metadata, PeerId Not Found")
                return
            }

            socket.publish(
                Request.RequestCase.UPDATE_PEER_METADATA, mapOf(
                    "peerId" to peerId, "metadata" to metadata
                )
            )
        } catch (error: Exception) {
            Timber.e("🔔 Error Updating Metadata $metadata | error: $error")
        }
    }

    /**
     * Update the role of the Remote Peer in the Room.
     * This will emit an event `updated` with the updated role.
     */
    fun updateRole(updatedRole: String) {
        try {
            if (!joined) {
                throw Exception("❌ Cannot Update Role, You have not joined the room yet")
            }

            if (role == updatedRole) {
                Timber.w("🔔 Peer Role is already set to $updatedRole")
                return
            }

            if (peerId == null) {
                Timber.e("❌ Cannot Update Role, PeerId Not Found (You have not joined the room yet)")
                return
            }

            socket.publish(
                Request.RequestCase.UPDATE_PEER_ROLE, mapOf(
                    "peerId" to peerId, "role" to updatedRole
                )
            )
        } catch (error: Exception) {
            Timber.e("🔔 Error Updating Role $updatedRole | error: $error")
        }
    }

    // Map to store event handler functions
    private val eventsHandler: MutableMap<HandlerEvents, (Response) -> Any> = mutableMapOf()
    private fun registerHandlerEvents(socket: Socket) {
        handlerEvents()
        for ((key, fn) in eventsHandler) {
            try {
                socket.subscribe(key, fn)
            } catch (error: Exception) {
                Timber.e("❌ Error Registered For Event: $key", error)
            }
        }
        Timber.i("✅ LocalPeerEventHandler Registered")
    }

    init {
        registerHandlerEvents(socket)
        socket.on("reconnected") {
            registerHandlerEvents(socket)
            socket.publish(Request.RequestCase.SYNC_MEETING_STATE, null)
            Timber.i("Sent reconnection request to server")
            _sendTransport?.let { sendTransport ->
                socket.publish(
                    Request.RequestCase.RESTART_TRANSPORT_ICE, mapOf(
                        "transportId" to sendTransport.id, "transportType" to "send"
                    )
                )
            }
            _recvTransport?.let { recvTransport ->
                socket.publish(
                    Request.RequestCase.RESTART_TRANSPORT_ICE, mapOf(
                        "transportId" to recvTransport.id, "transportType" to "recv"
                    )
                )
            }
        }

    }

    /**
     * Destroys the current peer, closing all transports, producers, and consumers.
     */
    fun close() {

        waitingToProduce.clear()
        waitingToConsume.clear()

        activeAudioTrack.values.forEach { stream ->
            stream.dispose()
        }
        activeVideoTrack.values.forEach { stream ->
            stream.dispose()
        }
        joined = false

        // dispose audio manager
        localAudioManager?.dispose()
        // dispose video manager
        localVideoManager?.dispose()
        camCapturer?.stopCapture()

        _sendTransport?.close()
        _sendTransport = null

        _recvTransport?.close()
        _recvTransport = null

        permissions.reset()

        // store setRoomState
        store.setRoomState(RoomStates.CLOSED)


        emit(
            "permissions-updated", mapOf(
                "permissions" to permissions, "role" to (role ?: "")
            )
        )
    }


    private fun handlerEvents() {
        eventsHandler[HandlerEvents.ERROR] = handler@{ responseData: Response ->
            if (!responseData.hasError()) return@handler
            Timber.i("🔔 ❌ Error Event | error: ${responseData.error.error}| event: ${responseData.error.event}")
        }

        eventsHandler[HandlerEvents.HELLO] = handler@{ responseData: Response ->
            Timber.i("✅ Hello From Server, Connection Success")
            if (!responseData.hasHello()) return@handler
            try {
                val helloResponse: Hello = responseData.hello

                CoroutineScope(Dispatchers.Main).launch {
                    // store me
                    store.setMe(helloResponse.peerId, helloResponse.role)
                    // store roomId
                    store.setRoomId(helloResponse.roomId)
                }

                this.peerId = helloResponse.peerId
                this.room.sessionId = helloResponse.sessionId
                this.permissions.updatePermissions(helloResponse.acl)

                if (helloResponse.role != null) {
                    this.permissions.role = helloResponse.role
                }
                emit(
                    "permissions-updated", mapOf(
                        "permissions" to permissions.acl, "role" to permissions.role
                    )
                )
                if (helloResponse.metadata != null) {
                    updateMetadata(helloResponse.metadata.toString())
                }
            } catch (error: Exception) {
                Timber.e("❌ Error Parsing Hello: $error")
                return@handler
            }
        }

        eventsHandler[HandlerEvents.WAITINGROOM] = handler@{ responseData: Response ->
            Timber.i("🔔 Waiting Room")
            if (!responseData.hasWaitingRoom()) return@handler
            room.emit("room-waiting", responseData.waitingRoom)
        }

        eventsHandler[HandlerEvents.CONNECTROOMRESPONSE] = handler@{ responseData: Response ->
            if (!responseData.hasConnectRoomResponse()) return@handler
            Timber.i("✅ Join Success Event")
            try {
                val connectRoomResponse: ResponseOuterClass.ConnectRoomResponse =
                    responseData.connectRoomResponse
                val roomInfo = connectRoomResponse.roomInfo
                val routerRTPCapabilities = connectRoomResponse.routerRTPCapabilities
                this.room.updateConfig(
                    RoomConfig(
                        roomLocked = roomInfo.config.roomLocked,
                        allowProduce = roomInfo.config.allowProduce,
                        allowProduceSources = ProduceSources(
                            cam = roomInfo.config.allowProduceSources.cam,
                            mic = roomInfo.config.allowProduceSources.mic,
                            screen = roomInfo.config.allowProduceSources.screen
                        ),
                        allowConsume = roomInfo.config.allowConsume,
                        allowSendData = roomInfo.config.allowSendData
                    )
                )
                this.room.metadata = roomInfo.metadata ?: "{}"
                this.room.updateStats(RoomStats(startTime = roomInfo.startTime))

                val parsedRtpCapabilities = ProtoParsing.parseRtpCapabilities(
                    routerRTPCapabilities.codecsList, routerRTPCapabilities.headerExtensionsList,
                )
                if (!mediasoupDevice.loaded) {
                    mediasoupDevice.load(parsedRtpCapabilities, rtcConfig)
                }
                val isDeviceLoaded: Boolean = mediasoupDevice.loaded
                if (!isDeviceLoaded) {
                    throw Exception("❌ Cannot Load Device")
                }
                // store setRoomState
                CoroutineScope(Dispatchers.Main).launch {
                    store.setRoomState(RoomStates.CONNECTED)
                }
                emit(
                    "device-created", mapOf(
                        "device" to mediasoupDevice
                    )
                )
                runBlocking {
                    setRemotePeers(roomInfo)
                }
                setLobbyPeers(roomInfo)
                room.state = RoomStates.CONNECTED
                joined = true
                room.emit("room-joined")
            } catch (error: Exception) {
                Timber.e("❌ Error Connect Room Response | error: $error")
                return@handler
            }
        }

        eventsHandler[HandlerEvents.SYNCMEETINGSTATERESPONSE] = handler@{ responseData: Response ->
            if (!responseData.hasSyncMeetingStateResponse()) return@handler
            val syncMeetingStateResponse: SyncMeetingStateResponse =
                responseData.syncMeetingStateResponse

            try {
                Timber.i("✅ Client recovered after reconnecting => $syncMeetingStateResponse")

                val latestPeersSet = syncMeetingStateResponse.roomInfo.peersList
                    .orEmpty()
                    .mapNotNull { it.peerId }
                    .toSet()

                remotePeers.entries.toList().forEach { (peerId, peer) ->
                    if (peerId in latestPeersSet) {
                        peer.labels.forEach { label ->
                            closeRemotePeerConsumer(peerId, label)
                        }
                        peer.close()
                        remotePeers.remove(peerId)
                        room.emit("peer-left", peerId)
                    } else {
                        val latestPeerInfo = syncMeetingStateResponse.roomInfo.peersList
                            .find { it.peerId == peerId }

                        val newProducerSet = latestPeerInfo?.producersList
                            .orEmpty()
                            .mapNotNull { it.label }
                            .toSet()

                        peer.labels.forEach { label ->
                            if (label in newProducerSet) {
                                closeRemotePeerConsumer(peerId, label)
                            }
                        }

                        val currentProducerSet = peer.producerIds.toSet()

                        latestPeerInfo?.producersList?.forEach { producer ->
                            val producerId = producer.id
                            val label = producer.label

                            if (producerId in currentProducerSet) {
                                peer.addLabelData(
                                    label = label,
                                    producerId = producerId,
                                    this@LocalPeer.appContext
                                )
                            }
                        }
                    }
                }

                // Handle new peers
                syncMeetingStateResponse.roomInfo.peersList
                    .filter { it.peerId != null && !remotePeers.containsKey(it.peerId) && it.peerId != this.peerId }
                    .forEach { latestPeer ->
                        val peerId = latestPeer.peerId

                        val remotePeer = RemotePeer(
                            peerId = peerId,
                            role = latestPeer.role,
                            metadata = latestPeer.metadata
                        )

                        remotePeers[peerId] = remotePeer

                        latestPeer.producersList.forEach { producer ->
                            val producerId = producer.id
                            val label = producer.label

                            remotePeer.addLabelData(
                                label = label,
                                producerId = producerId,
                                this@LocalPeer.appContext
                            )
                        }

                        room.emit("new-peer-joined", mapOf("peer" to remotePeer))
                    }
            } catch (error: Throwable) {
                Timber.e("❌ Error Syncing Meeting State, Can't Recover | error: $error")
            }
        }

        eventsHandler[HandlerEvents.CREATETRANSPORTONCLIENT] = handler@{ responseData: Response ->
            if (!responseData.hasCreateTransportOnClient()) return@handler
            val createTransportOnClientResponse: CreateTransportOnClient =
                responseData.createTransportOnClient
            val transportType = createTransportOnClientResponse.transportType.toString()
            try {
                if (peerId == null) {
                    throw Exception("❌ Cannot Create Transport, No PeerId Found for the user.")
                }
                val transports = mapOf(
                    "send" to _sendTransport, "recv" to _recvTransport
                )
                val iceParameters =
                    ProtoParsing.getParsedIceParameters(createTransportOnClientResponse.transportSDPInfo.iceParameters)
                val iceCandidates =
                    ProtoParsing.getParsedIceCandidatesList(createTransportOnClientResponse.transportSDPInfo.iceCandidatesList)
                        .toString()
                val dtlsParameters = ProtoParsing.getParsedDtlsParameters(
                    createTransportOnClientResponse.transportSDPInfo.dtlsParameters.fingerprintsList,
                    createTransportOnClientResponse.transportSDPInfo.dtlsParameters.role
                )
                val sctpParameters =
                    ProtoParsing.getParsedSctpParameters(createTransportOnClientResponse.transportSDPInfo.sctpParameters)
                        .ifEmpty { null }

                createDeviceTransport(
                    transportType,
                    sendTransportListener,
                    recvTransportListener,
                    createTransportOnClientResponse.transportSDPInfo.id,
                    iceParameters,
                    iceCandidates,
                    dtlsParameters,
                    sctpParameters
                )
                transports[transportType]?.let { transport ->
                    emit("new-$transportType-transport", mapOf("transport" to transport))
                }
            } catch (error: Exception) {
                Timber.e("❌ Error Creating MediasoupTransport On Client, transportType -> $transportType: $error")
            }
        }

        eventsHandler[HandlerEvents.CONNECTTRANSPORTRESPONSE] = handler@{ responseData: Response ->
            if (!responseData.hasConnectTransportResponse()) return@handler
            val connectTransportResponse: ConnectTransportResponse =
                responseData.connectTransportResponse
            Timber.i("✅ Connect ${connectTransportResponse.transportType} Transport On Server Response")
            try {
                val transportType = connectTransportResponse.transportType
                val transport = when (transportType) {
                    "send" -> _sendTransport
                    "recv" -> _recvTransport
                    else -> null
                }
                if (transport == null) {
                    throw Error("$transportType Transport Not Initialized")
                }
                emit("connectTransportResponse")
            } catch (error: Exception) {
                Timber.e("❌ Error Connecting Transport On Server Response | error: $error")
            }
        }

        eventsHandler[HandlerEvents.PRODUCERESPONSE] = handler@{ responseData: Response ->
            Timber.i("✅ Produce Response")
            if (!responseData.hasProduceResponse()) return@handler
            try {
                val produceResponse: ProduceResponse = responseData.produceResponse
                val peerId = produceResponse.peerId
                val producerId = produceResponse.producerId
                val label = produceResponse.label

                if (peerId == this.peerId) {
                    Timber.i("🔔 Received Producer Response $peerId")
                    return@handler
                } else {
                    // store for addPeer
                    val peersData = JSONObject().apply {
                        put("peerId", peerId)
                        put("role", role)
                    }
                    CoroutineScope(Dispatchers.Main).launch {
                        store.addPeer(peerId, peersData)
                    }
                    val remotePeer = room.getRemotePeerById(peerId)
                    if (_recvTransport == null) {
                        remotePeer.addLabelData(
                            label = label, producerId = producerId, this@LocalPeer.appContext
                        )
                    } else {
                        remotePeer.addLabelData(
                            label = label, producerId = producerId, this@LocalPeer.appContext
                        )
                    }
                }
            } catch (error: Exception) {
                Timber.e("❌ Error Produce Response | error: ${error.message}")
            }
        }

        eventsHandler[HandlerEvents.CONSUMERESPONSE] = handler@{ responseData: Response ->
            if (!responseData.hasConsumeResponse()) return@handler
            val consumeResponse: ConsumeResponse = responseData.consumeResponse
            Timber.i("📞Consume Response => $consumeResponse")
            try {
                val producerPeerId = consumeResponse.producerPeerId
                    ?: throw IllegalArgumentException("producerPeerId not found")
                val label =
                    consumeResponse.label ?: throw IllegalArgumentException("label not found")

                val remotePeer = room.getRemotePeerById(producerPeerId)

                if (!remotePeer.hasLabel(label)) {
                    Timber.e(
                        "❌ Remote Peer is not producing this label", mapOf("label" to label)
                    )
                    throw Exception("❌ Remote Peer is not producing this label: $label")
                }
                Timber.i("🔔Consume Called for $label from remote peer -> $producerPeerId")
                val counsumerRtpParameters = ProtoParsing.parseRtpParameters(
                    consumeResponse.rtpParameters.codecsList,
                    consumeResponse.rtpParameters.headerExtensionsList,
                    consumeResponse.rtpParameters.encodingsList,
                    consumeResponse.rtpParameters.rtcp,
                    consumeResponse.rtpParameters.mid
                )
                val consumer = _recvTransport?.consume(
                    listener = object : Consumer.Listener {
                        override fun onTransportClose(consumer: Consumer) {
                            closeConsumer(consumeResponse.label, consumeResponse.producerPeerId)
                            Timber.w("onTransportClose for consume")
                            // store for removeConsumer
                            CoroutineScope(Dispatchers.Main).launch {
                                store.removeConsumer(consumeResponse.producerPeerId)
                            }
                        }
                    },
                    id = consumeResponse.consumerId,
                    producerId = consumeResponse.producerId,
                    kind = consumeResponse.kind,
                    rtpParameters = counsumerRtpParameters,
                    appData = null
                )
                // store for addConsumer
                if (consumer != null) {
                    CoroutineScope(Dispatchers.Main).launch {
                        store.addConsumer(consumeResponse.producerPeerId, consumer)
                    }
                }
                socket.publish(
                    Request.RequestCase.RESUME_CONSUMER, mapOf(
                        "consumerId" to consumeResponse.consumerId,
                        "producerPeerId" to consumeResponse.producerPeerId
                    )
                )
                if (!consumeResponse.producerPaused) {
                    consumer?.resume()
                    if (consumer != null) {
                        room.emit(
                            "stream-added", mapOf(
                                "label" to consumer.kind, "peerId" to consumeResponse.producerPeerId
                            )
                        )
                    }
                    if (consumer != null) {
                        remotePeer.emit(
                            "stream-playable", mapOf(
                                "consumer" to consumer, "label" to consumer.kind
                            )
                        )
                    }
                } else {
                    consumer?.pause()
                    remotePeer.emit(
                        "stream-available", mapOf(
                            "label" to consumeResponse.label, "labelData" to mapOf(
                                "producerId" to consumeResponse.producerId
                            )
                        )
                    )
                }
            } catch (error: Throwable) {
                Timber.e("❌ Error Consume Response: $error")
            }
        }

        eventsHandler[HandlerEvents.CLOSEPRODUCERSUCCESS] = handler@{ responseData: Response ->
            if (!responseData.hasCloseProducerSuccess()) return@handler
            val closeProducerSuccessResponse: CloseProducerSuccess =
                responseData.closeProducerSuccess
            Timber.i("✅ Producer Closed => $closeProducerSuccessResponse")
            if (this.peerId == closeProducerSuccessResponse.peerId) {
                return@handler
            }
            try {
                closeRemotePeerConsumer(
                    peerId = closeProducerSuccessResponse.peerId,
                    label = closeProducerSuccessResponse.label
                )
            } catch (error: Throwable) {
                Timber.e("❌ Error Closing Producer | error: $error")
            }
        }

        eventsHandler[HandlerEvents.CLOSECONSUMERSUCCESS] = handler@{ responseData: Response ->
            if (!responseData.hasCloseConsumerSuccess()) return@handler
            val closeConsumerSuccessResponse: CloseConsumerSuccess =
                responseData.closeConsumerSuccess
            Timber.i("✅ Consumer Closed, $closeConsumerSuccessResponse")
        }

        eventsHandler[HandlerEvents.RESTARTTRANSPORTICERESPONSE] =
            handler@{ responseData: Response ->
                if (!responseData.hasRestartTransportIceResponse()) return@handler
                val restartTransportIceResponse: RestartTransportIceResponse =
                    responseData.restartTransportIceResponse
                val transportType = restartTransportIceResponse.transportType
                val iceParameters = restartTransportIceResponse.iceParameters

                Timber.i("✅ Restart Transport Ice Response $transportType")

                val transport = when (transportType) {
                    "send" -> _sendTransport
                    "recv" -> _recvTransport
                    else -> null
                }

                if (transport == null) {
                    Timber.e("❌ $transportType Transport Not Found")
                    return@handler
                }
                try {
                    transport.restartIce(ProtoParsing.getParsedIceParameters(iceParameters))
                    Timber.i("✅ Restarted Ice for type: $transportType")
                } catch (error: Throwable) {
                    Timber.e("❌ Error Restarting Ice for type: $transportType | error: $error")
                }
            }

        eventsHandler[HandlerEvents.NEWPEERJOINED] = handler@{ responseData: Response ->
            if (!responseData.hasNewPeerJoined()) return@handler
            val newPeerJoinedResponse: NewPeerJoined = responseData.newPeerJoined
            Timber.i("✅ New Peer Joined -> $newPeerJoinedResponse")
            val newPeerId: String = newPeerJoinedResponse.peerId
            if (this.peerId == newPeerId) {
                return@handler
            }
            try {
                val peerId = newPeerJoinedResponse.peerId
                val role = newPeerJoinedResponse.role
                // store for addPeer
                val peersData = JSONObject().apply {
                    put("peerId", peerId)
                    put("role", role)
                }
                CoroutineScope(Dispatchers.Main).launch {
                    store.addPeer(newPeerId, peersData)
                }
                val remotePeer = RemotePeer(
                    peerId = peerId, role = role
                )
                remotePeers[peerId] = remotePeer
                val lobbyPeers = room.lobbyPeersMap.toMutableMap()
                if (lobbyPeers.containsKey(peerId)) {
                    lobbyPeers.remove(peerId)
                    room.lobbyPeersMap = lobbyPeers
                }
                room.emit("new-peer-joined", mapOf("peer" to remotePeer))
            } catch (error: Exception) {
                Timber.e("❌ Error New Peer Joined | error: $error")
            }
        }

        eventsHandler[HandlerEvents.NEWLOBBYPEER] = handler@{ responseData: Response ->
            if (!responseData.hasNewLobbyPeer()) return@handler
            try {
                Timber.i("✅ New Lobby Peer")
                room.setNewLobbyPeers(null, newLobbyPeer = responseData.newLobbyPeer)
            } catch (error: Exception) {
                Timber.e("❌ Error New Lobby Peer | Error: $error")
            }
        }

        eventsHandler[HandlerEvents.NEWPERMISSIONS] = handler@{ responseData: Response ->
            if (!responseData.hasNewPermissions()) return@handler
            try {
                val newPermissionsResponse: NewPermissions = responseData.newPermissions
                permissions.updatePermissions(newPermissionsResponse.acl)
                emit("permissions-updated", mapOf("permissions" to permissions))
            } catch (error: Exception) {
                Timber.e("❌ Error Updating Permissions | Error : $error")
            }
        }

        eventsHandler[HandlerEvents.NEWROOMCONTROLS] = handler@{ responseData: Response ->
            Timber.i("✅ Received New Room Controls")
            if (!responseData.hasNewRoomControls()) return@handler
            val newRoomControlsResponse: NewRoomControls = responseData.newRoomControls
            try {
                room.updateConfig(
                    RoomConfig(
                        roomLocked = newRoomControlsResponse.controls.roomLocked,
                        allowProduce = newRoomControlsResponse.controls.allowProduce,
                        allowProduceSources = ProduceSources(
                            cam = newRoomControlsResponse.controls.allowProduceSources.cam,
                            mic = newRoomControlsResponse.controls.allowProduceSources.mic,
                            screen = newRoomControlsResponse.controls.allowProduceSources.screen
                        ),
                        allowConsume = newRoomControlsResponse.controls.allowConsume,
                        allowSendData = newRoomControlsResponse.controls.allowSendData
                    )
                )
                room.emit("room-controls-updated", newRoomControlsResponse)
            } catch (err: Exception) {
                Timber.e("❌ Error Updating Room Controls | error: $err")
            }
        }

        eventsHandler[HandlerEvents.NEWPEERROLE] = handler@{ responseData: Response ->
            Timber.i("✅ Received New Peer's Role")
            if (!responseData.hasNewPeerRole()) return@handler
            try {
                val newPeerRoleResponse: NewPeerRole = responseData.newPeerRole
                val peerId = newPeerRoleResponse.peerId ?: null
                val role = newPeerRoleResponse.role ?: null

                if (peerId == this.peerId) {
                    Timber.i("✅ Updating Local Peer's Role")
                    permissions.role = role
                    emit("role-updated", mapOf("role" to role))
                    return@handler
                }

                val remotePeer = peerId?.let { room.getRemotePeerById(it) }
                val prevRole = remotePeer?.role ?: ""
                remotePeer?.role = role
                room.emit(
                    "room-role-updated", mapOf(
                        "peerId" to peerId, "newRole" to role, "prevRole" to prevRole
                    )
                )
            } catch (error: Exception) {
                Timber.e("❌ Error Updating Peer's Role | error : $error")
            }
        }

        eventsHandler[HandlerEvents.ROOMCLOSEDPRODUCERS] = handler@{ responseData: Response ->
            Timber.i("✅ Received Room's Closed Producers")
            if (!responseData.hasRoomClosedProducers()) return@handler
            try {
                val roomClosedProducersResponse: RoomClosedProducers =
                    responseData.roomClosedProducers

                val producers = roomClosedProducersResponse.producersList ?: emptyList()
                val reason = roomClosedProducersResponse.reason

                for (producer in producers) {
                    val label = producer.label ?: continue
                    val peerId = producer.peerId ?: continue

                    if (peerId == this.peerId) {
                        stopProducing(label = label)
                        continue
                    }

                    try {
                        val remotePeer = room.getRemotePeerById(peerId)
                        val consumer = getConsumer(label = label, peerId = peerId)

                        if (consumer != null) {
                            closeConsumer(label, peerId)
                            remotePeer.removeLabelData(label)

                            room.emit(
                                "stream-closed", mapOf(
                                    "label" to label, "peerId" to peerId
                                )
                            )
                        }
                    } catch (error: Exception) {
                        Timber.e("❌ Error Closing Producer | error: $error")
                    }
                }

                room.emit(
                    "room-notification", mapOf(
                        "code" to reason.code,
                        "message" to (reason.message ?: "Room Closed"),
                        "tag" to (reason.tag ?: "ROOM_CLOSED")
                    )
                )
            } catch (error: Exception) {
                Timber.e("❌ Error Updating Room's Closed Producers | error: $error")
            }
        }

        eventsHandler[HandlerEvents.RECEIVEDATA] = handler@{ responseData: Response ->
            Timber.i("✅ Received Data")
            if (!responseData.hasReceiveData()) return@handler
            try {
                val receiveDataResponse: ReceiveData = responseData.receiveData
                emit(
                    "receive-data", mapOf(
                        "from" to receiveDataResponse.from,
                        "label" to receiveDataResponse.label,
                        "payload" to receiveDataResponse.payload
                    )
                )
            } catch (error: Exception) {
                Timber.e("❌ Error Receive Data | Error: $error")
            }
        }

        eventsHandler[HandlerEvents.PEERMETADATAUPDATED] = handler@{ responseData: Response ->
            if (!responseData.hasPeerMetadataUpdated()) return@handler
            try {
                val peerMetadataUpdatedResponse: PeerMetadataUpdated =
                    responseData.peerMetadataUpdated
                Timber.i("✅ Metadata Updated")
                val peerId = peerMetadataUpdatedResponse.peerId
                val metadata = peerMetadataUpdatedResponse.metadata

                if (peerId == this.peerId) {
                    metadata?.let { updateMetadata(it) }
                    return@handler
                }

                val remotePeer = room.getRemotePeerById(peerId)
                remotePeer.setMetadataValue(metadata)
            } catch (error: Exception) {
                Timber.e("❌ Error Updating Metadata | Error: $error")
            }
        }

        eventsHandler[HandlerEvents.ROOMMETADATAUPDATED] = handler@{ responseData: Response ->
            if (!responseData.hasRoomMetadataUpdated()) return@handler
            try {
                room.metadata = responseData.roomMetadataUpdated.metadata
                Timber.i("✅ Room Metadata Updated")
            } catch (error: Exception) {
                Timber.e("❌ Error Updating Room Metadata | Error: $error")
            }
        }

        eventsHandler[HandlerEvents.PEERLEFT] = handler@{ responseData: Response ->
            Timber.i("✅ Peer Left -> peerId: ${responseData.peerLeft.peerId}")
            if (!responseData.hasPeerLeft()) return@handler
            try {
                val peerLeftResponse: PeerLeft = responseData.peerLeft
                val peerId =
                    peerLeftResponse.peerId ?: throw IllegalArgumentException("Peer ID not found")

                CoroutineScope(Dispatchers.Main).launch {
                    // store for removePeer
                    store.removePeer(peerId)
                }
                val remotePeer = room.getRemotePeerById(peerId)
                val labels = remotePeer.labels

                for (label in labels) {
                    closeRemotePeerConsumer(peerId = remotePeer.peerId, label = label)
                }
                remotePeer.close()
                remotePeers.remove(peerId)
                room.emit("peer-left", peerId)
            } catch (error: Exception) {
                Timber.e("❌ Error Peer Left | error: $error")
            }

        }

        eventsHandler[HandlerEvents.LOBBYPEERLEFT] = handler@{ responseData: Response ->
            Timber.i("✅ Lobby Peer Left -> peerId: ${responseData.lobbyPeerLeft.peerId}")
            if (!responseData.hasLobbyPeerLeft()) return@handler
            try {
                val lobbyPeerLeftResponse: LobbyPeerLeft = responseData.lobbyPeerLeft
                val peerId = lobbyPeerLeftResponse.peerId
                val lobbyPeers = room.lobbyPeersMap.toMutableMap()

                if (peerId != null && lobbyPeers.containsKey(peerId)) {
                    lobbyPeers.remove(peerId)
                    room.lobbyPeersMap = lobbyPeers
                }
            } catch (error: Exception) {
                Timber.e("❌ Error Lobby Peer's Left | error: $error")
            }
        }
    }

    private val sendTransportListener: SendTransport.Listener = object : SendTransport.Listener {
        override fun onConnect(transport: Transport, dtlsParameters: String) {
            Timber.i("sendTransportListener onConnect ")
            try {
                socket.publish(
                    Request.RequestCase.CONNECT_TRANSPORT, mapOf(
                        "dtlsParameters" to dtlsParameters, "transportType" to "send"
                    )
                )
            } catch (error: Exception) {
                Timber.e("❌ Error Transport Connect Event | error: $error")
            }

        }

        override fun onConnectionStateChange(transport: Transport, newState: String) {
            connectionStateChangeHandler(transport, newState, "send")
        }

        override fun onProduce(
            transport: Transport, kind: String, rtpParameters: String, appData: String?
        ): String {
            try {
                socket.publish(
                    Request.RequestCase.PRODUCE, mapOf(
                        "rtpParameters" to rtpParameters,
                        "kind" to kind,
                        "label" to kind,
                        "appData" to appData,
                        "paused" to false
                    )
                )
                return "Success"
            } catch (error: Throwable) {
                Timber.e("❌ Error Transport Produce Event | error: $error")
                return ""
            }
        }

        override fun onProduceData(
            transport: Transport,
            sctpStreamParameters: String,
            label: String,
            protocol: String,
            appData: String?
        ): String {
            TODO("Not yet implemented")
        }
    }

    private val recvTransportListener: RecvTransport.Listener = object : RecvTransport.Listener {
        override fun onConnect(transport: Transport, dtlsParameters: String) {
            Timber.i("recvTransportListener onConnect ")
            try {
                socket.publish(
                    Request.RequestCase.CONNECT_TRANSPORT, mapOf(
                        "dtlsParameters" to dtlsParameters, "transportType" to "recv"
                    )
                )
            } catch (error: Exception) {
                Timber.e("❌ Error Transport Connect Event | error: $error")
            }
        }

        override fun onConnectionStateChange(transport: Transport, newState: String) {
            connectionStateChangeHandler(transport, newState, "recv")
        }
    }

    /**
     * create mediasoup device and load SDP info from the server to make it ready for use
     */
    private fun createDeviceTransport(
        transportType: String,
        sendTransportListener: SendTransport.Listener?,
        recvTransportListener: RecvTransport.Listener?,
        id: String,
        iceParameters: String,
        iceCandidates: String,
        dtlsParameters: String,
        sctpParameters: String? = null,
        rtcConfig: PeerConnection.RTCConfiguration? = null,
        appData: String? = null
    ): Transport? {
        Timber.i("createDeviceTransport called for $transportType")
        val transport = when (transportType) {
            "send" -> sendTransportListener?.let { it ->
                mediasoupDevice.createSendTransport(
                    listener = it,
                    id = id,
                    iceParameters = iceParameters,
                    iceCandidates = iceCandidates,
                    dtlsParameters = dtlsParameters,
                    sctpParameters = sctpParameters,
                    appData = appData,
                    rtcConfig = rtcConfig
                ).also { _sendTransport = it }
            }

            "recv" -> recvTransportListener?.let { it ->
                mediasoupDevice.createRecvTransport(
                    listener = it,
                    id = id,
                    iceParameters = iceParameters,
                    iceCandidates = iceCandidates,
                    dtlsParameters = dtlsParameters,
                    sctpParameters = sctpParameters,
                    appData = appData,
                    rtcConfig = rtcConfig
                ).also { _recvTransport = it }
            }

            else -> null
        }
        return transport
    }

    private fun connectionStateChangeHandler(
        transport: Transport?, state: String, transportType: String
    ) {
        try {
            Timber.d("🔔 $transportType Transport Connection State Changed, state: $state")
            val handler: Map<String, suspend () -> Unit> = mapOf("connected" to {
                Timber.d("🔔 $transportType Transport Connected")
            }, "disconnected" to {
                if (iceRestartDebounce) {
                    return@to
                }
                iceRestartDebounce = true
                socket.publish(
                    Request.RequestCase.RESTART_TRANSPORT_ICE, mapOf(
                        "transportId" to transport?.id, "transportType" to transportType
                    )
                )
                Handler(Looper.getMainLooper()).postDelayed({
                    iceRestartDebounce = false
                }, 3000)

                Timber.d("🔔 $transportType Transport Disconnected")
            }, "failed" to {
                Timber.d("🔔 $transportType Transport Failed")
            }, "connecting" to {
                Timber.d("🔔 $transportType Transport Connecting")
            }, "closed" to {
                Timber.d("🔔 $transportType Transport Closed")
            }, "new" to {
                Timber.d("🔔 $transportType Transport New")
            })
            runBlocking {
                handler[state]?.invoke()
            }
        } catch (err: Exception) {
            Timber.e("❌ Error in connectionStateChangeHandler | error: $err")
        }
    }

    private suspend fun createTransportOnServer(transportType: TransportType) {
        val device = mediasoupDevice
        val transportTypeName = transportType.name.lowercase(Locale.ROOT)
        val deviceSctpCapabilities = device.sctpCapabilities
        Timber.i("🔔 Creating $transportTypeName Transport On Server")
        Timber.i("createTransportOnServer | Request for createTransport -> $transportTypeName")
        socket.publish(
            Request.RequestCase.CREATE_TRANSPORT, mapOf(
                "deviceSctpCapabilities" to deviceSctpCapabilities,
                "transportType" to transportTypeName
            )
        )
        delay(2000)
    }

    /**
     *  Sets the Remote Peers in the Room
     */
    private fun setRemotePeers(roomInfo: ResponseOuterClass.RoomInfo) {
        roomInfo.peersList.orEmpty().forEach { peer ->
            val peerId = peer.peerId
            if (peerId != this.peerId) {
                val remotePeer = RemotePeer(
                    peerId = peerId,
                    metadata = peer.metadata.orEmpty(),
                    role = peer.role
                )
                remotePeers[peerId] = remotePeer
                CoroutineScope(Dispatchers.Main).launch {
                    // store for removePeer
                    peer.producersList.orEmpty().forEach { producer ->
                        // store for addPeer
                        store.addPeer(peerId, JSONObject().apply {
                            put("peerId", peerId)
                            put("role", peer.role)
                        })
                        remotePeer.addLabelData(
                            producer.label,
                            producer.id,
                            this@LocalPeer.appContext
                        )
                    }
                }
            }
        }
    }

    /**
     *  Sets the Lobby Peers in the Room
     */
    private fun setLobbyPeers(roomInfo: ResponseOuterClass.RoomInfo) {
        val lobbyPeers = roomInfo.lobbyPeersList ?: emptyList()
        room.setNewLobbyPeers(lobbyPeers)
    }

    /**
     * Helper function to close the consumer of a remote peer
     */
    private fun closeRemotePeerConsumer(
        peerId: String, label: String
    ) {
        try {
            val remotePeer = room.getRemotePeerById(peerId)
            remotePeer.removeLabelData(label)
            val consumer = getConsumer(label, peerId)
            if (consumer != null) {
                closeConsumer(label, peerId)
                consumers.delete(label, peerId)
                CoroutineScope(Dispatchers.Main).launch {
                    // store for removePeer
                    store.removePeer(peerId)
                }
            }
            if (label == "video") camCapturer?.stopCapture()
            room.emit(
                "stream-closed", mapOf(
                    "label" to label, "peerId" to peerId
                )
            )
            Timber.i("Closed Remote Peer's Consumer")
        } catch (error: Exception) {
            Timber.e("❌ Error Closing Remote Peer's Consumer | error: $error")
        }
    }

    /**
     * Handler Function to handle the waiting to produce tasks when user is joining
     * the room with active stream, check if the user has valid permissions and based on
     * that allows the user to produce the stream
     */
    private suspend fun handleWaitingToProduce() {
        Timber.i("handleWaitingToProduce")
        try {
            val streamClosedReason = mapOf(
                "code" to 4444, "tag" to "User's Permissions Denied", "message" to "CLOSED_BY_ADMIN"
            )

            fun closeStream(label: String) {
                listOf(activeAudioTrack, activeVideoTrack).forEach { trackMap ->
                    trackMap[label]?.let { stream ->
                        stream.dispose()
                        trackMap.remove(label)
                        emit(
                            "stream-closed", mapOf("label" to label, "reason" to streamClosedReason)
                        )
                    }
                }
                waitingToProduce.remove(label)
            }

            if (permissions.checkPermission("canProduce")) {
                waitingToProduce.keys.forEach(::closeStream)
            } else {
                waitingToProduce.forEach { (label, pendingStreamTask) ->
                    val canProduce = when (label) {
                        "video" -> permissions.checkPermission("video")
                        "audio" -> permissions.checkPermission("audio")
                        else -> false
                    }

                    if (canProduce) {
                        closeStream(label)
                    } else {
                        try {
                            pendingStreamTask.invoke()
                        } catch (error: Exception) {
                            Timber.e("❌ Error Producing Stream which was waiting to be produced with label: $label | error: $error")
                            closeStream(label)
                        }
                    }
                }
            }

            waitingToProduce.clear()
        } catch (error: Exception) {
            Timber.e("❌ Error Handling Waiting To Produce | error: $error")
        }
    }

    private suspend fun handleWaitingToConsume() {
        Timber.i("handleWaitingToConsume")
        waitingToConsume.forEach { consumeTask ->
            runCatching { consumeTask.invoke() }.onFailure { Timber.e("Unable to Consume after ice restart: $it") }
        }.also { waitingToConsume.clear() }
    }
}