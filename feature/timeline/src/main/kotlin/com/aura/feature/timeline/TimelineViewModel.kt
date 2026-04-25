package com.aura.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.core.database.dao.ActionLogDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class TimelineEvent(
    val id: String,
    val toolName: String,
    val output: String,
    val success: Boolean,
    val formattedTime: String,
)

data class TimelineUiState(val events: List<TimelineEvent> = emptyList())

@HiltViewModel
class TimelineViewModel @Inject constructor(
    actionLogDao: ActionLogDao,
) : ViewModel() {
    private val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

    val uiState: StateFlow<TimelineUiState> = actionLogDao.observeRecent(100)
        .map { logs ->
            val events = logs.map { log ->
                TimelineEvent(
                    id = log.actionId,
                    toolName = log.tool,
                    output = log.output.take(120),
                    success = log.success,
                    formattedTime = fmt.format(Date(log.timestampMs)),
                )
            }
            TimelineUiState(events)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TimelineUiState())
}
