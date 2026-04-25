package com.aura.feature.chat

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.core.ui.component.AuraStatusChip
import com.aura.core.ui.component.AuraThinkingIndicator
import com.aura.core.ui.theme.*

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Scroll to bottom when new messages arrive
    LaunchedEffect(state.messages.size, state.isThinking) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
        containerColor = AuraVoid,
        topBar = { AuraChatTopBar(state.modelStatus) },
        bottomBar = {
            Column {
                // Pending confirmation banner
                AnimatedVisibility(visible = state.pendingAction != null) {
                    state.pendingAction?.let { action ->
                        ConfirmationBanner(
                            action = action,
                            onConfirm = { viewModel.confirmAction(action) },
                            onDismiss = viewModel::dismissPendingAction,
                        )
                    }
                }
                ChatInputBar(
                    text = state.inputText,
                    onTextChange = viewModel::onInputChanged,
                    onSend = viewModel::sendMessage,
                    enabled = !state.isThinking && state.pendingAction == null,
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            items(state.messages, key = { it.hashCode() }) { msg ->
                ChatBubble(msg)
            }

            // Streaming partial response
            if (state.streamingText.isNotBlank()) {
                item {
                    AgentBubble(text = state.streamingText, isStreaming = true)
                }
            }

            // Thinking indicator
            if (state.isThinking && state.streamingText.isBlank()) {
                item {
                    Row(modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
                        AuraThinkingIndicator()
                    }
                }
            }

            // Error
            state.error?.let { err ->
                item {
                    Text(
                        text = err,
                        color = AuraError,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuraChatTopBar(modelStatus: ModelStatusUi) {
    TopAppBar(
        title = { Text("Aura", style = MaterialTheme.typography.titleLarge) },
        actions = {
            val (label, color) = when (modelStatus) {
                ModelStatusUi.READY -> "On-device" to AuraSuccess
                ModelStatusUi.LOADING -> "Loading…" to AuraWarning
                ModelStatusUi.NOT_FOUND -> "No model" to AuraError
                ModelStatusUi.CHECKING -> "Checking" to AuraOnSurfaceDim
            }
            AuraStatusChip(label, color, Modifier.padding(end = 12.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AuraVoid),
    )
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    when (message) {
        is ChatMessage.User -> UserBubble(message.message.content)
        is ChatMessage.Agent -> AgentBubble(message.message.content)
        is ChatMessage.ActionLog -> ActionLogBubble(message.description, message.success)
        is ChatMessage.Thinking -> AuraThinkingIndicator()
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp),
            color = AuraPulse,
        ) {
            Text(text, modifier = Modifier.padding(12.dp, 10.dp), style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
    }
}

@Composable
private fun AgentBubble(text: String, isStreaming: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            color = AuraSurfaceVariant,
        ) {
            Column(modifier = Modifier.padding(12.dp, 10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = AuraOnSurface)
                if (isStreaming) {
                    Spacer(Modifier.height(4.dp))
                    AuraThinkingIndicator()
                }
            }
        }
    }
}

@Composable
private fun ActionLogBubble(description: String, success: Boolean) {
    val color = if (success) AuraSuccess else AuraError
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚡", style = MaterialTheme.typography.bodySmall)
        Text(description, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun ConfirmationBanner(
    action: com.aura.core.domain.model.AgentAction,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(color = AuraSurfaceVariant, tonalElevation = 4.dp) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Aura wants to: ${action.tool.name}", style = MaterialTheme.typography.titleMedium)
            Text(action.reasoning, style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Authorize") }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
) {
    Surface(color = AuraSurface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Aura anything…", color = AuraOnSurfaceDim) },
                enabled = enabled,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AuraPulse,
                    unfocusedBorderColor = AuraSurfaceVariant,
                    focusedTextColor = AuraOnSurface,
                    unfocusedTextColor = AuraOnSurface,
                    cursorColor = AuraPulse,
                ),
            )
            IconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", tint = if (enabled && text.isNotBlank()) AuraPulse else AuraOnSurfaceDim)
            }
        }
    }
}
