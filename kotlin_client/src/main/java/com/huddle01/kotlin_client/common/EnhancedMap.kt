package com.huddle01.kotlin_client.common

class EnhancedMap<T>(
    private val compareFn: (String, String) -> Boolean = ::defaultCompareFn,
) {
    private val map: MutableMap<String, T> = mutableMapOf()

    val size: Int
        get() = map.size

    fun get(a: String, b: String): T? {
        val key = getKey(a, b)
        return map[key]
    }

    fun set(a: String, b: String, value: T): T {
        val key = getKey(a, b)
        map[key] = value
        return value
    }

    fun delete(a: String, b: String): Boolean {
        val key = getKey(a, b)
        return map.remove(key) != null
    }

    fun clear() {
        map.clear()
    }

    private fun getKey(a: String, b: String): String {
        return if (compareFn(a, b)) {
            "${a}_$b"
        } else {
            "${b}_$a"
        }
    }

    companion object {
        private fun defaultCompareFn(a: String, b: String): Boolean {
            return a > b
        }
    }
}
