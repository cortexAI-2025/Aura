package com.aura.ai.engine

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class LLMProvider { LOCAL, OPENROUTER }

/**
 * User-controlled LLM backend selection. The OpenRouter API key is stored in
 * EncryptedSharedPreferences (Android Keystore-backed). If secure storage is
 * unavailable the key is kept in memory for the session only — never in clear text.
 */
@Singleton
class LLMSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val DEFAULT_MODEL = "google/gemma-4-31b-it:free"
        private const val FILE = "aura_llm_settings"
        private const val K_API = "openrouter_api_key"
        private const val K_MODEL = "openrouter_model"
        private const val K_PROVIDER = "provider"
        private const val K_FALLBACK = "auto_fallback"
    }

    @Volatile private var memoryKey: String? = null

    private val prefs: SharedPreferences? by lazy {
        runCatching {
            val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context, FILE, master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.onFailure { Timber.e(it, "Secure storage unavailable — API key kept in memory only") }.getOrNull()
    }

    val apiKey: String? get() = prefs?.getString(K_API, null)?.takeIf { it.isNotBlank() } ?: memoryKey
    val hasApiKey: Boolean get() = !apiKey.isNullOrBlank()

    fun setApiKey(key: String) {
        memoryKey = key
        prefs?.edit()?.putString(K_API, key)?.apply()
    }

    var model: String
        get() = prefs?.getString(K_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
        set(value) { prefs?.edit()?.putString(K_MODEL, value)?.apply() }

    var provider: LLMProvider
        get() = runCatching { LLMProvider.valueOf(prefs?.getString(K_PROVIDER, null) ?: "LOCAL") }
            .getOrDefault(LLMProvider.LOCAL)
        set(value) { prefs?.edit()?.putString(K_PROVIDER, value.name)?.apply() }

    /** Try other free models when the chosen free model is saturated (default on). */
    var autoFallback: Boolean
        get() = prefs?.getBoolean(K_FALLBACK, true) ?: memFallback
        set(value) { memFallback = value; prefs?.edit()?.putBoolean(K_FALLBACK, value)?.apply() }

    @Volatile private var memFallback: Boolean = true

    /** Model that actually answered the last request (differs from [model] after a fallback). */
    @Volatile var lastModelUsed: String? = null

    /** True when requests must go to OpenRouter (opt-in, key present). */
    val cloudActive: Boolean get() = provider == LLMProvider.OPENROUTER && hasApiKey
}
