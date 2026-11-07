package com.aura.ai.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aura.ai.settings.InMemoryKeyValueStore
import com.aura.ai.settings.KeyValueStore
import timber.log.Timber

/**
 * [KeyValueStore] on EncryptedSharedPreferences (AES-256-GCM values, AES-256-SIV keys, master key in the
 * Android Keystore). If the Keystore is unusable the store degrades to memory only — never to clear text —
 * and [isSecure] tells the UI so it can warn that keys will not survive a restart.
 *
 * The file lives in `aura_llm_settings` (same name as v0.1, so the existing OpenRouter key is kept); it is
 * excluded from Android backups (see backup rules in the app module).
 */
class SecureKeyValueStore private constructor(private val prefs: SharedPreferences?) : KeyValueStore {
    private val fallback = InMemoryKeyValueStore()

    override val isSecure: Boolean get() = prefs != null

    override fun getString(key: String): String? = prefs?.getString(key, null) ?: if (prefs == null) fallback.getString(key) else null
    override fun putString(key: String, value: String?) {
        if (prefs == null) { fallback.putString(key, value); return }
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs?.getBoolean(key, default) ?: fallback.getBoolean(key, default)
    override fun putBoolean(key: String, value: Boolean) {
        if (prefs == null) fallback.putBoolean(key, value) else prefs.edit().putBoolean(key, value).apply()
    }
    override fun remove(key: String) { if (prefs == null) fallback.remove(key) else prefs.edit().remove(key).apply() }
    override fun clear() { fallback.clear(); prefs?.edit()?.clear()?.apply() }

    companion object {
        private const val FILE = "aura_llm_settings"

        fun create(context: Context): SecureKeyValueStore {
            val prefs = runCatching {
                val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                EncryptedSharedPreferences.create(
                    context, FILE, master,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.onFailure { Timber.e("Secure storage unavailable — keys kept in memory only (${it.javaClass.simpleName})") }.getOrNull()
            return SecureKeyValueStore(prefs)
        }
    }
}
