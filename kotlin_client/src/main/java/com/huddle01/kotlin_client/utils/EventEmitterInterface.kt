package com.huddle01.kotlin_client.utils


interface EventEmitterInterface {

    var defaultMaxListeners: Int

    @Throws(Exception::class)
    fun emit(eventName: String, vararg args: Any): Boolean

    @Throws(Exception::class)
    fun on(eventName: String, listener: (args: Array<out Any>) -> Unit): EventEmitterInterface

    @Throws(Exception::class)
    fun once(eventName: String, listener: (args: Array<out Any>) -> Unit): EventEmitterInterface

    fun addListener(
        eventName: String, listener: (args: Array<out Any>) -> Unit, isOnce: Boolean = false,
    ): EventEmitterInterface


    fun removeListener(
        eventName: String, listener: (args: Array<out Any>) -> Unit,
    ): EventEmitterInterface

    fun removeAllListeners(eventName: String): EventEmitterInterface

    fun removeAllListeners(): EventEmitterInterface

    fun setMaxListeners(n: Int): EventEmitterInterface

    fun listeners(eventName: String): List<(args: Array<out Any>) -> Unit>

    fun listenerCount(eventName: String): Int
}