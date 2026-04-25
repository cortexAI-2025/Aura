package com.aura.feature.goals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.GoalCategory
import com.aura.core.domain.repository.GoalRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class GoalsUiState(val goals: List<Goal> = emptyList())

@HiltViewModel
class GoalsViewModel @Inject constructor(
    private val goalRepository: GoalRepository,
) : ViewModel() {
    val uiState: StateFlow<GoalsUiState> = goalRepository.observeActive()
        .map { GoalsUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GoalsUiState())

    fun addGoal(title: String, description: String, category: GoalCategory) {
        viewModelScope.launch(Dispatchers.IO) {
            goalRepository.upsert(
                Goal(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    description = description,
                    category = category,
                    priority = 5,
                )
            )
        }
    }

    fun updateProgress(id: String, progress: Float) {
        viewModelScope.launch(Dispatchers.IO) { goalRepository.updateProgress(id, progress) }
    }

    fun archiveGoal(id: String) {
        viewModelScope.launch(Dispatchers.IO) { goalRepository.archive(id) }
    }
}
