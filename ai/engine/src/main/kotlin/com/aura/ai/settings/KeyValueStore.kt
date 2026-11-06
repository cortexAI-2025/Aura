package com.aura.ai.settings

/**
 * Minimal storage port for settings and secrets. The Android implementation is encrypted with a key held in
 * the Android Keystore; [InMemoryKeyValueStore] is the last resort (nothing is ever written in clear text).
 */
interface KeyValueStore {
    /** False when values only live in memory for this session (secure storage was unavailable). */
    val isSecure: Boolean

    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun clear()
}

class InMemoryKeyValueStore(override val isSecure: Boolean = false) : KeyValueStore {
    private val map = HashMap<String, Any>()

    @Synchronized override fun getString(key: String): String? = map[key] as? String
    @Synchronized override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    @Synchronized override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    @Synchronized override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    @Synchronized override fun remove(key: String) { map.remove(key) }
    @Synchronized override fun clear() = map.clear()
}
