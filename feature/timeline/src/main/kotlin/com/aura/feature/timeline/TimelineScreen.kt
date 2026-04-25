package com.aura.feature.timeline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.core.ui.component.AuraCard
import com.aura.core.ui.component.AuraStatusChip
import com.aura.core.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineScreen(viewModel: TimelineViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = AuraVoid,
        topBar = {
            TopAppBar(
                title = { Text("Activity", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AuraVoid),
            )
        }
    ) { padding ->
        if (state.events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No activity yet.\nAura will log actions here.", color = AuraOnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.events) { event ->
                    TimelineEventCard(event)
                }
            }
        }
    }
}

@Composable
private fun TimelineEventCard(event: TimelineEvent) {
    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(event.toolName, style = MaterialTheme.typography.titleMedium, color = AuraOnSurface)
                Spacer(Modifier.height(4.dp))
                Text(event.output, style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            AuraStatusChip(
                label = if (event.success) "Done" else "Failed",
                color = if (event.success) AuraSuccess else AuraError,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(event.formattedTime, style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim)
    }
}
