package com.huddle01.kotlin_client.utils

import java.util.*

open class EventEmitter : EventEmitterInterface {
    override var defaultMaxListeners: Int = 10
    private val events: HashMap<String, LinkedList<Listener>> = HashMap()


    @Throws(Exception::class)
    override fun emit(eventName: String, vararg args: Any): Boolean {
        if (events.containsKey(eventName)) {
            val removeListeners: LinkedList<Listener> = LinkedList()
            for (listener in events[eventName]!!) {
                listener.listener.invoke(args)
                if (listener.isOnce) {
                    removeListeners.add(listener)
                }
            }

            for (eventListener in removeListeners) {
                events[eventName]!!.remove(eventListener)
            }
            return true
        } else {
            return false
        }
    }


    @Throws(Exception::class)
    override fun on(
        eventName: String, listener: (args: Array<out Any>) -> Unit
    ): EventEmitterInterface {
        return addListener(eventName, listener)
    }


    @Throws(Exception::class)
    override fun once(
        eventName: String, listener: (args: Array<out Any>) -> Unit
    ): EventEmitterInterface {
        return addListener(eventName, listener, true)
    }


    override fun addListener(
        eventName: String, listener: (args: Array<out Any>) -> Unit, isOnce: Boolean
    ): EventEmitterInterface {
        if (defaultMaxListeners != 0 && listenerCount(eventName) >= defaultMaxListeners) {
            //maxListeners exception
        } else {
            if (!events.containsKey(eventName)) {
                events[eventName] = LinkedList()
            }
            events[eventName]?.add(Listener(listener, isOnce))
        }
        return this
    }


    override fun removeListener(
        eventName: String, listener: (args: Array<out Any>) -> Unit
    ): EventEmitterInterface {
        if (events.containsKey(eventName)) {
            val removeListeners: LinkedList<Listener> = LinkedList()
            for (eventListener in events[eventName]!!) {
                if (eventListener.listener == listener) {
                    removeListeners.add(eventListener)
                }
            }
            for (eventListener in removeListeners) {
                events[eventName]!!.remove(eventListener)
            }
        }
        return this
    }


    override fun removeAllListeners(eventName: String): EventEmitterInterface {
        events[eventName]!!.clear()
        return this
    }


    override fun removeAllListeners(): EventEmitterInterface {
        events.clear()
        return this
    }


    override fun setMaxListeners(n: Int): EventEmitterInterface {
        defaultMaxListeners = n
        return this
    }


    override fun listeners(eventName: String): LinkedList<(args: Array<out Any>) -> Unit> {
        return if (events.containsKey(eventName)) {
            val listeners: LinkedList<Listener> = events[eventName]!!
            val listenersRt: LinkedList<(args: Array<out Any>) -> Unit> = LinkedList()
            for (listener in listeners) {
                listenersRt.add(listener.listener)
            }
            listenersRt
        } else {
            LinkedList()
        }
    }


    override fun listenerCount(eventName: String): Int {
        return if (events.containsKey(eventName)) {
            events[eventName]!!.size
        } else {
            0
        }
    }


    data class Listener(val listener: (args: Array<out Any>) -> Unit, val isOnce: Boolean = false)
}