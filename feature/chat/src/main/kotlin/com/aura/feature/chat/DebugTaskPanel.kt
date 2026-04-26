package com.aura.feature.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.agent.core.AuraAgent
import com.aura.core.domain.model.Domain
import com.aura.core.domain.model.Task
import com.aura.core.domain.model.TaskPriority

/**
 * Debug-only panel for triggering the Sophie birthday integration scenario.
 * Wire into [ChatScreen] under a BuildConfig.DEBUG flag in production builds.
 */
@Composable
fun DebugTaskPanel(
    auraAgent: AuraAgent,
    modifier: Modifier = Modifier,
) {
    var lastTaskId by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "DEBUG — Task Panel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )

            Button(
                onClick = {
                    val task = Task(
                        id = "42",
                        description = "Tomorrow is Sophie's birthday. " +
                            "Check calendar at 2 PM, if free send Happy Birthday SMS.",
                        domain = Domain.SOCIAL,
                        priority = TaskPriority.HIGH,
                        deadline = System.currentTimeMillis() + 6 * 3600 * 1000L,
                    )
                    lastTaskId = auraAgent.submitTask(task)
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Submit Sophie Birthday Task")
            }

            lastTaskId?.let { id ->
                Text(
                    text = "Enqueued → id=$id",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
