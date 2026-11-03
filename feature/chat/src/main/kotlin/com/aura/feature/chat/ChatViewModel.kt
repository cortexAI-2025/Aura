package com.aura.feature.chat

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.core.*
import com.aura.ai.engine.LLMProvider
import com.aura.ai.engine.LLMSettings
import com.aura.ai.engine.OpenRouterCatalog
import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val agentEngine: AgentEngine,
    private val conversationRepository: ConversationRepository,
    private val llmLoader: LLMLoader,
    private val llmSettings: LLMSettings,
    private val catalog: OpenRouterCatalog,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val prefs = context.getSharedPreferences("aura_ui", Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializing = false
    private var ttsFailed = false
    @Volatile private var replyAloud = false
    private var pendingSpeech: String? = null
    private var watchdog: Job? = null

    init {
        _uiState.update { it.copy(speakReplies = prefs.getBoolean(KEY_SPEAK, true)) }
        loadHistory()
        observeServiceEvents()
        refreshCloudState()
        checkModelStatus()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            conversationRepository.observeMessages(30).collect { messages ->
                val chatMessages = messages.reversed().map { msg ->
                    if (msg.role == MessageRole.USER) ChatMessage.User(msg)
                    else ChatMessage.Agent(msg)
                }
                _uiState.update { it.copy(messages = chatMessages) }
            }
        }
    }

    private fun observeServiceEvents() {
        viewModelScope.launch {
            AuraAgentService.agentEvents.collect { event ->
                handleAgentEvent(event)
            }
        }
    }

    private fun checkModelStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(modelStatus = ModelStatusUi.CHECKING) }
            // The foreground service loads the model asynchronously: poll until it is ready.
            var attempts = 0
            do {
                val status = llmLoader.getModelStatus()
                _uiState.update { it.copy(modelStatus = status.toUi()) }
                if (status != ModelStatus.FOUND_NOT_LOADED) break
                delay(1_000)
            } while (++attempts < 180)
        }
    }

    private fun refreshCloudState() {
        _uiState.update {
            it.copy(
                cloudEnabled = llmSettings.provider == LLMProvider.OPENROUTER,
                cloudModel = llmSettings.model,
                hasApiKey = llmSettings.hasApiKey,
                autoFallback = llmSettings.autoFallback,
            )
        }
    }

    /** Opt in/out of OpenRouter. The key is stored encrypted on-device; blank keeps the saved one. */
    fun saveCloudSettings(enabled: Boolean, apiKey: String, model: String, autoFallback: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (apiKey.isNotBlank()) llmSettings.setApiKey(apiKey.trim())
            llmSettings.model = model.trim().ifBlank { LLMSettings.DEFAULT_MODEL }
            llmSettings.autoFallback = autoFallback
            if (enabled && !llmSettings.hasApiKey) {
                _uiState.update { it.copy(error = "Clé API OpenRouter requise pour activer le mode cloud") }
            }
            llmSettings.provider = if (enabled && llmSettings.hasApiKey) LLMProvider.OPENROUTER else LLMProvider.LOCAL
            refreshCloudState()
            if (!llmSettings.cloudActive) runCatching { llmLoader.loadIfNeeded() }
            checkModelStatus()
        }
    }

    /** Refresh the dropdown of free OpenRouter models (public endpoint, no key needed). */
    fun loadFreeModels() {
        if (_uiState.value.modelsLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(modelsLoading = true, modelsError = null) }
            catalog.fetchFreeModels()
                .onSuccess { list -> _uiState.update { it.copy(freeModels = list, modelsLoading = false) } }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(modelsLoading = false, modelsError = "Liste hors ligne (${e.message ?: "erreur réseau"})")
                    }
                }
        }
    }

    /** Copy a model file chosen by the user into the app, then load it. */
    fun importModel(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(modelStatus = ModelStatusUi.LOADING, error = null) }
            val result = llmLoader.importModel(uri).mapCatching { llmLoader.loadIfNeeded() }
            result.onFailure { e ->
                _uiState.update { it.copy(modelStatus = ModelStatusUi.ERROR, error = e.message ?: "Échec de l'import du modèle") }
            }.onSuccess {
                _uiState.update { it.copy(modelStatus = llmLoader.getModelStatus().toUi(), error = null) }
            }
        }
    }

    private fun ModelStatus.toUi() = when (this) {
        ModelStatus.LOADED -> ModelStatusUi.READY
        ModelStatus.FOUND_NOT_LOADED -> ModelStatusUi.LOADING
        ModelStatus.NOT_FOUND -> ModelStatusUi.NOT_FOUND
        ModelStatus.CLOUD -> ModelStatusUi.CLOUD
    }

    fun onInputChanged(text: String) = _uiState.update { it.copy(inputText = text) }

    fun sendMessage() = doSend(viaVoice = false)

    private fun doSend(viaVoice: Boolean) {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        replyAloud = viaVoice // a question asked by voice is answered by voice
        tts?.stop()
        _uiState.update { it.copy(inputText = "", isThinking = true, streamingText = "", error = null) }
        armWatchdog()
        AuraAgentService.processMessage(context, text)
    }

    fun confirmAction(action: AgentAction) {
        _uiState.update { it.copy(pendingAction = null) }
        // Re-process with confirmed flag — in production, pass confirmation token to service
        viewModelScope.launch(Dispatchers.IO) {
            agentEngine.process("CONFIRMED: ${action.tool} ${action.params}").collect { handleAgentEvent(it) }
        }
    }

    // ── Voice ────────────────────────────────────────────────────────────────

    /** Text recognised by the system speech recogniser: send it as a normal message. */
    fun onVoiceResult(text: String) {
        if (text.isBlank()) return
        _uiState.update { it.copy(inputText = text.trim()) }
        doSend(viaVoice = true)
    }

    fun onVoiceFailed(resultCode: Int) {
        val msg = when (resultCode) {
            RecognizerIntent.RESULT_NO_MATCH -> "Je n'ai rien compris, réessayez."
            RecognizerIntent.RESULT_AUDIO_ERROR -> "Erreur micro : vérifiez la permission et qu'aucune autre appli ne l'utilise."
            RecognizerIntent.RESULT_NETWORK_ERROR -> "Reconnaissance vocale : pas de connexion réseau."
            RecognizerIntent.RESULT_SERVER_ERROR, RecognizerIntent.RESULT_CLIENT_ERROR ->
                "Le service de reconnaissance vocale a échoué."
            else -> return // user simply cancelled
        }
        _uiState.update { it.copy(error = msg) }
    }

    fun onVoiceUnavailable() {
        _uiState.update { it.copy(error = "Reconnaissance vocale indisponible sur cet appareil (service Google requis).") }
    }

    fun setSpeakReplies(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK, enabled).apply()
        ttsFailed = false
        _uiState.update { it.copy(speakReplies = enabled, error = null) }
        if (enabled) ensureTts() else tts?.stop()
    }

    /** Installed text-to-speech engines (needs the <queries> entry in the manifest). */
    private fun installedTtsEngines(): List<String> = runCatching {
        context.packageManager
            .queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    private fun ensureTts() {
        if (tts != null || ttsInitializing) return
        ttsInitializing = true
        val engines = installedTtsEngines()
        // Default engine first, then every installed engine one by one.
        tryInitTts(listOf<String?>(null) + engines, 0, engines)
    }

    private fun tryInitTts(candidates: List<String?>, index: Int, detected: List<String>) {
        if (index >= candidates.size) {
            ttsInitializing = false
            ttsFailed = true
            val found = if (detected.isEmpty()) "aucun moteur détecté" else "moteurs détectés : ${detected.joinToString()}"
            prefs.edit().putBoolean(KEY_SPEAK, false).apply()
            _uiState.update {
                it.copy(
                    speakReplies = false,
                    error = "Synthèse vocale indisponible ($found). Installez ou activez « Synthèse vocale Google » " +
                        "(Paramètres > Gestion globale > Langue > Synthèse vocale).",
                )
            }
            return
        }
        var created: TextToSpeech? = null
        created = TextToSpeech(context, { status ->
            val engine = created
            if (status == TextToSpeech.SUCCESS && engine != null) {
                onTtsReady(engine)
            } else {
                engine?.shutdown()
                tryInitTts(candidates, index + 1, detected)
            }
        }, candidates[index])
    }

    private fun onTtsReady(engine: TextToSpeech) {
        tts = engine
        ttsInitializing = false
        var r = engine.setLanguage(Locale.getDefault())
        if (r < TextToSpeech.LANG_AVAILABLE) r = engine.setLanguage(Locale.FRENCH)
        if (r < TextToSpeech.LANG_AVAILABLE) {
            _uiState.update {
                it.copy(error = "Voix française absente : installez-la dans Paramètres > Langue > Synthèse vocale.")
            }
        }
        ttsReady = true
        pendingSpeech?.let { pending ->
            pendingSpeech = null
            engine.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "aura-reply")
        }
    }

    /** Read one reply aloud on demand (the speaker button on a bubble), whatever the global setting. */
    fun speakMessage(text: String) {
        ttsFailed = false
        speak(text, force = true)
    }

    private fun speak(text: String, force: Boolean = false) {
        if (!force && !_uiState.value.speakReplies && !replyAloud) return
        if (!force && ttsFailed) return
        val clean = SpeechText.clean(text)
        if (clean.isEmpty()) return
        ensureTts()
        val engine = tts
        if (ttsReady && engine != null) engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "aura-reply")
        else pendingSpeech = clean
    }

    /** Safety net: never leave the UI stuck on \"thinking\" (input and mic stay disabled meanwhile). */
    private fun armWatchdog() {
        watchdog?.cancel()
        watchdog = viewModelScope.launch {
            delay(WATCHDOG_MS)
            if (_uiState.value.isThinking) {
                _uiState.update { it.copy(isThinking = false, error = "Aucune réponse (délai dépassé). Réessayez.") }
            }
        }
    }

    private fun disarmWatchdog() {
        watchdog?.cancel()
        watchdog = null
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

    fun dismissPendingAction() = _uiState.update { it.copy(pendingAction = null, isThinking = false) }

    private fun handleAgentEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.Thinking -> _uiState.update { it.copy(isThinking = true) }
            is AgentEvent.Token -> _uiState.update { it.copy(streamingText = it.streamingText + event.text) }
            is AgentEvent.ActionResult -> {
                val log = ChatMessage.ActionLog("${event.result.tool.name}: ${event.result.output}", event.result.success)
                _uiState.update { it.copy(messages = it.messages + log) }
            }
            is AgentEvent.ConfirmationRequired -> {
                disarmWatchdog()
                _uiState.update { it.copy(isThinking = false, pendingAction = event.action) }
            }
            is AgentEvent.FinalAnswer -> {
                disarmWatchdog()
                speak(event.text)
                _uiState.update { it.copy(isThinking = false, streamingText = "", error = null) }
            }
            is AgentEvent.Error -> {
                disarmWatchdog()
                _uiState.update { it.copy(isThinking = false, error = event.message) }
            }
            else -> {}
        }
    }

    private companion object {
        // v2: the previous key was forced to false by an earlier init failure; default is now ON.
        const val KEY_SPEAK = "speak_replies_v2"
        const val WATCHDOG_MS = 330_000L
    }
}
