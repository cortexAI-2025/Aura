package com.aura.feature.goals

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.core.domain.model.Goal
import com.aura.core.domain.model.GoalCategory
import com.aura.core.ui.component.AuraCard
import com.aura.core.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(viewModel: GoalsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = AuraVoid,
        topBar = {
            TopAppBar(
                title = { Text("Life Goals", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AuraVoid),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }, containerColor = AuraPulse) {
                Icon(Icons.Default.Add, contentDescription = "Add goal")
            }
        },
    ) { padding ->
        if (state.goals.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No goals yet.\nTell Aura what you want to achieve.", color = AuraOnSurfaceDim, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.goals, key = { it.id }) { goal ->
                    GoalCard(goal = goal, onProgressUpdate = { viewModel.updateProgress(goal.id, it) })
                }
            }
        }
    }

    if (showAddSheet) {
        AddGoalSheet(onDismiss = { showAddSheet = false }, onAdd = { title, desc, cat ->
            viewModel.addGoal(title, desc, cat)
            showAddSheet = false
        })
    }
}

@Composable
private fun GoalCard(goal: Goal, onProgressUpdate: (Float) -> Unit) {
    AuraCard(modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(goal.title, style = MaterialTheme.typography.titleMedium, color = AuraOnSurface)
                Spacer(Modifier.height(2.dp))
                Text(goal.category.name, style = MaterialTheme.typography.labelSmall, color = AuraPulse)
            }
            Text("${(goal.progress * 100).toInt()}%", style = MaterialTheme.typography.titleMedium, color = AuraPulse)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { goal.progress },
            modifier = Modifier.fillMaxWidth(),
            color = AuraPulse,
            trackColor = AuraSurfaceVariant,
        )
        if (goal.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(goal.description, style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim, maxLines = 3)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddGoalSheet(onDismiss: () -> Unit, onAdd: (String, String, GoalCategory) -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(GoalCategory.PERSONAL_GROWTH) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AuraSurface) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("New Goal", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Text("Category", style = MaterialTheme.typography.labelLarge, color = AuraOnSurfaceDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GoalCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = category == cat,
                        onClick = { category = cat },
                        label = { Text(cat.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AuraPulse,
                            selectedLabelColor = AuraOnSurface,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { if (title.isNotBlank()) onAdd(title, desc, category) },
                modifier = Modifier.fillMaxWidth(),
                enabled = title.isNotBlank(),
            ) { Text("Add Goal") }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
