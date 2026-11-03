package com.aura.feature.chat

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.core.*
import com.aura.ai.engine.LLMProvider
import com.aura.ai.engine.LLMSettings
import com.aura.core.domain.model.AgentAction
import com.aura.core.domain.model.Message
import com.aura.core.domain.model.MessageRole
import com.aura.core.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val prefs = context.getSharedPreferences("aura_ui", Context.MODE_PRIVATE)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    init {
        _uiState.update { it.copy(speakReplies = prefs.getBoolean(KEY_SPEAK, false)) }
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
            )
        }
    }

    /** Opt in/out of OpenRouter. The key is stored encrypted on-device; blank keeps the saved one. */
    fun saveCloudSettings(enabled: Boolean, apiKey: String, model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (apiKey.isNotBlank()) llmSettings.setApiKey(apiKey.trim())
            llmSettings.model = model.trim().ifBlank { LLMSettings.DEFAULT_MODEL }
            if (enabled && !llmSettings.hasApiKey) {
                _uiState.update { it.copy(error = "Clé API OpenRouter requise pour activer le mode cloud") }
            }
            llmSettings.provider = if (enabled && llmSettings.hasApiKey) LLMProvider.OPENROUTER else LLMProvider.LOCAL
            refreshCloudState()
            if (!llmSettings.cloudActive) runCatching { llmLoader.loadIfNeeded() }
            checkModelStatus()
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

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        tts?.stop()
        _uiState.update { it.copy(inputText = "", isThinking = true, streamingText = "") }
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
        sendMessage()
    }

    fun onVoiceUnavailable() {
        _uiState.update { it.copy(error = "Reconnaissance vocale indisponible sur cet appareil (service Google requis).") }
    }

    fun setSpeakReplies(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPEAK, enabled).apply()
        _uiState.update { it.copy(speakReplies = enabled) }
        if (enabled) ensureTts() else tts?.stop()
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            val engine = tts
            if (status == TextToSpeech.SUCCESS && engine != null) {
                val r = engine.setLanguage(Locale.getDefault())
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.FRENCH)
                }
                ttsReady = true
                pendingSpeech?.let { pending -> pendingSpeech = null; engine.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "aura-reply") }
            } else {
                _uiState.update { it.copy(error = "Synthèse vocale indisponible sur cet appareil.", speakReplies = false) }
            }
        }
    }

    private fun speak(text: String) {
        if (!_uiState.value.speakReplies) return
        val clean = SpeechText.clean(text)
        if (clean.isEmpty()) return
        ensureTts()
        val engine = tts
        if (ttsReady && engine != null) engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "aura-reply")
        else pendingSpeech = clean
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
            is AgentEvent.ConfirmationRequired -> _uiState.update {
                it.copy(isThinking = false, pendingAction = event.action)
            }
            is AgentEvent.FinalAnswer -> {
                speak(event.text)
                _uiState.update { it.copy(isThinking = false, streamingText = "", error = null) }
            }
            is AgentEvent.Error -> _uiState.update { it.copy(isThinking = false, error = event.message) }
            else -> {}
        }
    }

    private companion object {
        const val KEY_SPEAK = "speak_replies"
    }
}
