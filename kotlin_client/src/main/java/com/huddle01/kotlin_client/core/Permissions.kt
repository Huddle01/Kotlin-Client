package com.huddle01.kotlin_client.core

import com.huddle01.kotlin_client.models.ProduceSources
import com.huddle01.kotlin_client.utils.EventEmitter
import timber.log.Timber
import PermissionsOuterClass.Permissions as ProtoPermission

/**
 *  Permission Class of a Local Peer in a Room.
 * `NOTE Permissions are enforced by the Server. and can be set using the token or can be updated by Remote Peers having admin access of the Room Joined`
 */
class Permissions : EventEmitter() {
    companion object {
        /**
         * Get the Singleton Instance of the Permissions Class.
         */
        private val permissionsInstance: Permissions = Permissions()
        fun getInstance(): Permissions {
            return permissionsInstance
        }
    }

    /**
     * Admin Access of the Room.
     */
    private var admin = false

    /**
     * Can Consume Media Stream of the Room from RemotePeers;
     */
    private var canConsume = false

    /**
     * Can Produce Media Stream to the Room
     */
    private var canProduce = true

    /**
     * Allowed Sources to Produce Media Stream to the Room
     */
    private var canProduceSources = ProduceSources(cam = true, mic = true, screen = true)

    /**
     * Can Send Data to the Room, e.g. Chat Messages, update of avatar, name etc. to the room
     */
    private var canSendData = false

    /**
     * Can Receive Data from the Room, e.g. Chat Messages, update of avatar, name etc. from other Remote Peers.
     */
    private var canRecvData = false

    /**
     * Can Update Metadata of the Room, e.g. DisplayName, Avatar, etc.
     */
    private var canUpdateMetadata = false

    /**
     * Custom Role of the Peer in the Room.
     */
    var role: String? = null

    /**
     * Get the Access Control List (acl) of the Local Peer in the Room.
     */
    val acl: Map<String, Any>
        get() = mapOf(
            "admin" to admin,
            "canConsume" to canConsume,
            "canProduce" to canProduce,
            "canProduceSources" to canProduceSources,
            "canSendData" to canSendData,
            "canRecvData" to canRecvData,
            "canUpdateMetadata" to canUpdateMetadata
        )

    /**
     *  Update the Permissions of the Local Peer in the Room. This will emit an event `updated` with the updated permissions.
     * `NOTE: If the Peer is not an admin, then the permissions will not be updated on the server`
     */
    fun updatePermissions(permissions: ProtoPermission?) {
        Timber.i("🔔Updating Permissions $permissions")
        admin = permissions?.admin ?: admin
        canConsume = permissions?.canConsume ?: canConsume
        canProduce = permissions?.canProduce ?: canProduce
        permissions?.canProduceSources?.let {
            canProduceSources = ProduceSources(cam = it.cam, mic = it.mic, screen = it.screen)
        }
        canSendData = permissions?.canSendData ?: canSendData
        canRecvData = permissions?.canRecvData ?: canRecvData
        canUpdateMetadata = permissions?.canUpdateMetadata ?: canUpdateMetadata
    }

    fun reset() {
        admin = false
        canConsume = false
        canProduce = true
        canProduceSources = ProduceSources(cam = true, mic = true, screen = true)
        canSendData = false
        canRecvData = false
        canUpdateMetadata = false
        role = null
    }

    /**
     * Check the status of permission available
     */
    fun checkPermission(label: String): Boolean {
        val permissions = getInstance()
        return when {
            label == "video" && permissions.canProduceSources.cam -> true
            label == "audio" && permissions.canProduceSources.mic -> true
            else -> acl[label] as? Boolean ?: false
        }
    }
}
