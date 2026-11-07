package com.aura.feature.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.memory.logic.MemoryService
import com.aura.agent.memory.logic.RejectReason
import com.aura.agent.memory.logic.RememberResult
import com.aura.core.domain.model.Memory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MemoryUiState(
    val query: String = "",
    val memories: List<Memory> = emptyList(),
    val total: Int = 0,
    val needsReview: Int = 0,
    val message: String? = null,
    /** JSON produced by "Exporter", waiting to be handed to the share sheet. */
    val exportJson: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MemoryViewModel @Inject constructor(private val service: MemoryService) : ViewModel() {

    private val query = MutableStateFlow("")
    private val extra = MutableStateFlow(MemoryUiState())

    val state: StateFlow<MemoryUiState> = combine(
        service.observeAll(), query,
    ) { all, q -> Triple(all, q, all.size) }
        .mapLatest { (all, q, total) ->
            val shown = if (q.isBlank()) all.sortedByDescending { it.updatedAt } else service.search(q)
            MemoryUiState(query = q, memories = shown, total = total, needsReview = all.count { it.needsReview })
        }
        .combine(extra) { base, e -> base.copy(message = e.message, exportJson = e.exportJson) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MemoryUiState())

    fun setQuery(q: String) { query.value = q }

    fun add(text: String) = viewModelScope.launch { report(service.remember(text)) }

    fun edit(id: String, text: String) = viewModelScope.launch { report(service.update(id, text)) }

    fun delete(id: String) = viewModelScope.launch { service.delete(id); say("Souvenir supprimé.") }

    /** "Garder les deux": the owner checked the possible duplicate / contradiction. */
    fun keepBoth(id: String) = viewModelScope.launch { service.acceptReview(id) }

    fun resetAll() = viewModelScope.launch { service.deleteAll(); say("Mémoire entièrement effacée.") }

    fun export() = viewModelScope.launch { extra.update { it.copy(exportJson = service.exportJson()) } }
    fun exportHandled() = extra.update { it.copy(exportJson = null) }
    fun consumeMessage() = extra.update { it.copy(message = null) }

    private fun report(r: RememberResult) = say(
        when (r) {
            is RememberResult.Saved -> if (r.memory.needsReview) "Enregistré. Il ressemble à un autre souvenir : vérifiez-le (« À vérifier »)." else "Enregistré."
            is RememberResult.Duplicate -> "Ce souvenir existe déjà : « ${r.existing.content} »."
            is RememberResult.Rejected -> when (r.reason) {
                RejectReason.SECRET -> "Refusé : on ne mémorise pas les mots de passe, clés API ni données bancaires."
                RejectReason.TOO_SHORT -> "Texte trop court."
                RejectReason.TOO_LONG -> "Texte trop long."
                RejectReason.EMPTY -> "Texte vide."
            }
        },
    )

    private fun say(text: String) = extra.update { it.copy(message = text) }
}
