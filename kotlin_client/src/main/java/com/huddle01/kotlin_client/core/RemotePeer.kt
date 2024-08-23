package com.huddle01.kotlin_client.core

import android.content.Context
import com.huddle01.kotlin_client.models.Reason
import com.huddle01.kotlin_client.utils.EventEmitter
import com.huddle01.kotlin_client.utils.JsonUtils
import org.json.JSONObject
import timber.log.Timber

class RemotePeer(
    var peerId: String, metadata: String? = null, role: String? = null,
) : EventEmitter() {

    private val permissions: Permissions = Permissions()

    /**
     * Stores the Metadata for the Remote Peer.
     */
    var metadata: String = "{}"

    /**
     * Stores the Role of the Remote Peer.
     */
    private var _role: String? = null

    /**
     * Labels are the unique identifier for the media stream that the remote peer is producing
     */
    private val _labelsToProducerId: MutableMap<String, MutableMap<String, String>> = mutableMapOf()

    /**
     * Returns the list of labels that the remote peer is producing
     */
    val labels: List<String>
        get() = _labelsToProducerId.keys.toList()

    val producerIds: List<String?>
        get() = _labelsToProducerId.values.map { labelData ->
            labelData["producerId"]
        }.toList()

    /**
     * Role of the Peer.
     * @returns The Role of the Peer which if passed in the options when creating the token
     */
    var role: String?
        get() = _role
        set(value) {
            _role = value
            if (value != null) {
                emit("role-updated", value)
            }
        }

    /**
     * Checks if the remote peer is producing the label
     */
    fun hasLabel(label: String): Boolean {
        return _labelsToProducerId.containsKey(label)
    }

    /**
     * Returns the data associated to the label, this is the producerId
     */
    fun getLabelData(label: String): Map<String, String>? {
        return _labelsToProducerId[label]
    }

    /**
     * Returns the metadata associated to the RemotePeer
     */
    inline fun <reified T> getMetadata(): T {
        val data = JSONObject(this.metadata)
        return data as T
    }

    /**
     * Sets the metadata for the RemotePeer.
     */
    fun setMetadataValue(metadata: String) {
        val newMetadata = JsonUtils.toJsonObject(metadata)
        this.metadata = newMetadata.toString()
        emit("metadata-updated", mapOf("metadata" to newMetadata))
    }


    /**
     * Removes all the states of the remote peer and clears memory;
     */
    fun close() {
        Timber.i("Closing Remote Peer")
        removeAllListeners()
    }

    init {
        metadata?.let {
            this.metadata = it
        }
        role?.let {
            _role = it
        }
    }


    /**
     * Adds label data and manages consumption based on auto-consume setting
     */
    fun addLabelData(
        label: String, producerId: String, context: Context
    ) {
        _labelsToProducerId[label] = mutableMapOf("producerId" to producerId)
        try {
            val autoConsume = Room.getInstance().autoConsume
            val localPeer = LocalPeer.getInstance(context)

            if (autoConsume) {
                Timber.d("AUTO CONSUME IS ENABLED, CONSUMING THE PRODUCER'S STREAM")
                localPeer.consume("{}", label, peerId)
            } else {
                emit(
                    "stream-available", mapOf(
                        "label" to label, "labelData" to mapOf("producerId" to producerId)
                    )
                )
            }
        } catch (error: Exception) {
            Timber.e("❌ Error While Consuming | Error: $error")
            emit(
                "stream-available", mapOf(
                    "label" to label, "labelData" to mapOf("producerId" to producerId)
                )
            )
        }
    }

    /**
     * Remove a Label from the Remote Peer and emit a `stream-closed` event
     */
    fun removeLabelData(label: String, reason: Reason? = null) {
        _labelsToProducerId.remove(label)
        val reasonMap = reason?.let {
            mapOf(
                "code" to it.code, "tag" to it.tag, "message" to it.message
            )
        }
        emit("stream-closed", mapOf("label" to label, "reason" to reasonMap))
    }
}

