package com.aura.feature.settings

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.core.LLMLoader
import com.aura.agent.core.PersonalizationStore
import com.aura.agent.core.UserDataWiper
import com.aura.ai.brain.LocalAssessment
import com.aura.ai.brain.LocalCompatibility
import com.aura.ai.provider.AIProvider
import com.aura.ai.provider.ConnectionResult
import com.aura.ai.provider.PlannedProvider
import com.aura.ai.provider.ProviderRegistry
import com.aura.ai.settings.AiSettings
import com.aura.core.domain.model.DetailLevel
import com.aura.core.domain.model.ModelUsage
import com.aura.core.domain.model.PersonalizationProfile
import com.aura.core.domain.model.Tone
import com.aura.core.domain.model.UsageTotals
import com.aura.core.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Success(val text: String) : TestState
    data class Failure(val text: String) : TestState
}

data class ProviderUi(val id: String, val name: String)

data class SettingsUiState(
    val providers: List<ProviderUi> = emptyList(),
    val planned: List<PlannedProvider> = emptyList(),
    /** Null = local mode. */
    val activeProviderId: String? = null,
    /** The provider whose card is shown (can differ from the active one while editing). */
    val editingProviderId: String = "zai",
    val keySaved: Boolean = false,
    val secureStorage: Boolean = true,
    val model: String = "",
    val thinking: Boolean = false,
    val autoFallback: Boolean = true,
    val test: TestState = TestState.Idle,
    val message: String? = null,
    val usageTotal: UsageTotals = UsageTotals(),
    val usageByModel: List<ModelUsage> = emptyList(),
    val profile: PersonalizationProfile = PersonalizationProfile.DEFAULT,
    val local: LocalAssessment? = null,
    val localBusy: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: AiSettings,
    private val registry: ProviderRegistry,
    private val personalization: PersonalizationStore,
    private val wiper: UserDataWiper,
    private val conversations: ConversationRepository,
    private val llmLoader: LLMLoader,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { personalization.profile.collect { p -> _state.update { it.copy(profile = p) } } }
        viewModelScope.launch { conversations.observeUsageByModel().collect { l -> _state.update { it.copy(usageByModel = l) } } }
        refresh()
    }

    val editingProvider: AIProvider get() = registry.require(_state.value.editingProviderId)

    fun refresh() {
        val editing = settings.selectedProviderId ?: _state.value.editingProviderId.takeIf { registry.get(it) != null }
            ?: registry.all().first().id
        loadProvider(editing)
        viewModelScope.launch {
            val totals = conversations.usageTotals(null)
            val local = withContext(Dispatchers.IO) { assessLocal() }
            _state.update { it.copy(usageTotal = totals, local = local) }
        }
    }

    private fun loadProvider(id: String) {
        _state.update {
            it.copy(
                providers = registry.all().map { p -> ProviderUi(p.id, p.displayName) },
                planned = registry.planned(),
                activeProviderId = settings.selectedProviderId?.takeIf { _ -> settings.cloudActive },
                editingProviderId = id,
                keySaved = settings.hasKey(id),
                secureStorage = settings.secureStorage,
                model = settings.model(id),
                thinking = settings.thinking,
                autoFallback = settings.autoFallback,
                test = TestState.Idle,
            )
        }
    }

    fun editProvider(id: String) = loadProvider(id)

    /** Switch between local mode and the edited cloud provider. Cloud needs a saved key. */
    fun setCloudActive(active: Boolean) {
        val id = _state.value.editingProviderId
        if (active && !settings.hasKey(id)) { toast("Enregistrez d'abord une clé API pour ce fournisseur."); return }
        settings.selectedProviderId = if (active) id else null
        loadProvider(id)
    }

    fun saveKey(key: String) {
        val id = _state.value.editingProviderId
        runCatching { settings.setApiKey(id, key) }
            .onSuccess {
                loadProvider(id)
                toast(if (settings.secureStorage) "Clé enregistrée (chiffrée sur l'appareil)." else "Clé gardée en mémoire pour cette session seulement : stockage sécurisé indisponible.")
            }
            .onFailure { toast(it.message ?: "Clé invalide") }
    }

    fun deleteKey() {
        val id = _state.value.editingProviderId
        settings.clearApiKey(id)
        loadProvider(id)
        toast("Clé supprimée de l'appareil.")
    }

    fun setModel(model: String) {
        val id = _state.value.editingProviderId
        settings.setModel(id, model)
        _state.update { it.copy(model = settings.model(id), test = TestState.Idle) }
    }

    fun setThinking(v: Boolean) { settings.thinking = v; _state.update { it.copy(thinking = v) } }
    fun setAutoFallback(v: Boolean) { settings.autoFallback = v; _state.update { it.copy(autoFallback = v) } }

    /** A REAL request to the provider with the saved key (or the key typed in the field). Nothing is faked. */
    fun testConnection(typedKey: String) {
        val id = _state.value.editingProviderId
        val provider = registry.require(id)
        val key = typedKey.trim().ifEmpty { settings.apiKey(id) }
        if (key == null) { _state.update { it.copy(test = TestState.Failure("Aucune clé : saisissez-en une ou enregistrez-la d'abord.")) }; return }
        _state.update { it.copy(test = TestState.Running) }
        viewModelScope.launch(Dispatchers.IO) {
            val config = com.aura.ai.provider.ProviderConfig(key, settings.baseUrl(id), settings.model(id))
            val result = provider.testConnection(config)
            val ui = when (result) {
                is ConnectionResult.Success -> TestState.Success(
                    "Connexion réussie · modèle ${result.model ?: config.model} · ${result.latencyMs} ms" +
                        (result.usage?.let { " · ${it.totalTokens} tokens" } ?: "") +
                        if (result.replyPreview.isNotBlank()) "\nRéponse : « ${result.replyPreview.take(80)} »" else "",
                )
                is ConnectionResult.Failure -> TestState.Failure(result.error.userMessage)
            }
            _state.update { it.copy(test = ui) }
        }
    }

    // ── Personalization ──
    fun updateProfile(transform: (PersonalizationProfile) -> PersonalizationProfile) = personalization.update(transform)
    fun resetProfile() { personalization.reset(); toast("Personnalisation réinitialisée.") }

    // ── Data erasure ──
    fun eraseConversations() = viewModelScope.launch { wiper.eraseConversations(); toast("Conversations supprimées."); refresh() }
    fun eraseMemory() = viewModelScope.launch { wiper.eraseMemory(); toast("Mémoire effacée.") }
    fun eraseKeys() { wiper.eraseKeysAndAiSettings(); refresh(); toast("Clés et choix IA supprimés.") }
    fun eraseAll() = viewModelScope.launch { wiper.eraseAll(); refresh(); toast("Toutes les données locales ont été effacées.") }

    // ── Local mode ──
    fun importModel(uri: Uri) {
        _state.update { it.copy(localBusy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            llmLoader.importModel(uri).mapCatching { llmLoader.loadIfNeeded() }
                .onFailure { e -> toast(e.message ?: "Échec de l'import du modèle") }
                .onSuccess { toast("Modèle importé.") }
            val local = assessLocal()
            _state.update { it.copy(localBusy = false, local = local) }
        }
    }

    private fun assessLocal(): LocalAssessment {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val free = runCatching { StatFs(context.filesDir.path).availableBytes }.getOrNull()
        return LocalCompatibility.assess(
            modelBytes = llmLoader.findModel()?.length(),
            totalRamBytes = info.totalMem, availRamBytes = info.availMem,
            supports64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(), freeStorageBytes = free,
        )
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    private fun toast(text: String) = _state.update { it.copy(message = text) }
}
