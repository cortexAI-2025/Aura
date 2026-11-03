package com.aura.feature.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.ai.engine.OpenRouterModel
import com.aura.core.ui.component.AuraStatusChip
import com.aura.core.ui.component.AuraThinkingIndicator
import com.aura.core.ui.theme.*
import java.util.Locale

@Composable
fun ChatScreen(viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val pickModel = { modelPicker.launch(arrayOf("*/*")) }
    var showSettings by remember { mutableStateOf(false) }

    // Speech-to-text via the system recogniser (no RECORD_AUDIO permission needed in this app).
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) viewModel.onVoiceResult(spoken)
        } else {
            viewModel.onVoiceFailed(result.resultCode)
        }
    }
    val startVoice = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Parlez à Aura…")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.onVoiceUnavailable()
        }
    }

    LaunchedEffect(showSettings) { if (showSettings) viewModel.loadFreeModels() }

    if (showSettings) {
        CloudSettingsDialog(
            enabled = state.cloudEnabled,
            model = state.cloudModel,
            hasKey = state.hasApiKey,
            freeModels = state.freeModels,
            modelsLoading = state.modelsLoading,
            modelsError = state.modelsError,
            onRefreshModels = viewModel::loadFreeModels,
            onDismiss = { showSettings = false },
            onSave = { enabled, key, model ->
                viewModel.saveCloudSettings(enabled, key, model)
                showSettings = false
            },
        )
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(state.messages.size, state.isThinking) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
        containerColor = AuraVoid,
        topBar = {
            AuraChatTopBar(
                state.modelStatus,
                onImportModel = pickModel,
                onOpenSettings = { showSettings = true },
                speakReplies = state.speakReplies,
                onToggleSpeak = { viewModel.setSpeakReplies(!state.speakReplies) },
            )
        },
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
                    onMic = startVoice,
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
            if (state.modelStatus == ModelStatusUi.NOT_FOUND || state.modelStatus == ModelStatusUi.ERROR) {
                item { ModelSetupCard(loading = false, onImport = pickModel, onOpenCloud = { showSettings = true }) }
            } else if (state.modelStatus == ModelStatusUi.LOADING) {
                item { ModelSetupCard(loading = true, onImport = pickModel, onOpenCloud = { showSettings = true }) }
            }

            items(state.messages, key = { it.hashCode() }) { msg ->
                ChatBubble(msg, onSpeak = viewModel::speakMessage)
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
private fun AuraChatTopBar(
    modelStatus: ModelStatusUi,
    onImportModel: () -> Unit,
    onOpenSettings: () -> Unit,
    speakReplies: Boolean,
    onToggleSpeak: () -> Unit,
) {
    TopAppBar(
        title = { Text("Aura", style = MaterialTheme.typography.titleLarge) },
        actions = {
            IconButton(onClick = onToggleSpeak) {
                Icon(
                    if (speakReplies) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (speakReplies) "Désactiver la lecture vocale" else "Activer la lecture vocale",
                    tint = if (speakReplies) AuraPulse else AuraOnSurfaceDim,
                )
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Paramètres IA") }
            val (label, color) = when (modelStatus) {
                ModelStatusUi.READY -> "On-device" to AuraSuccess
                ModelStatusUi.LOADING -> "Loading…" to AuraWarning
                ModelStatusUi.NOT_FOUND -> "No model" to AuraError
                ModelStatusUi.CHECKING -> "Checking" to AuraOnSurfaceDim
                ModelStatusUi.ERROR -> "Load failed" to AuraError
                ModelStatusUi.CLOUD -> "OpenRouter" to AuraGlow
            }
            val canImport = modelStatus == ModelStatusUi.NOT_FOUND || modelStatus == ModelStatusUi.ERROR
            AuraStatusChip(
                label, color,
                Modifier.padding(end = 12.dp).clickable(enabled = canImport, onClick = onImportModel),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AuraVoid),
    )
}

@Composable
private fun CloudSettingsDialog(
    enabled: Boolean,
    model: String,
    hasKey: Boolean,
    freeModels: List<OpenRouterModel>,
    modelsLoading: Boolean,
    modelsError: String?,
    onRefreshModels: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, apiKey: String, model: String) -> Unit,
) {
    var useCloud by remember { mutableStateOf(enabled) }
    var key by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf(model) }
    var menuOpen by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AuraSurface,
        title = { Text("IA cloud (OpenRouter)") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Utiliser OpenRouter", modifier = Modifier.weight(1f))
                    Switch(checked = useCloud, onCheckedChange = { useCloud = it })
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (hasKey) "Clé API (enregistrée — vide = conserver)" else "Clé API OpenRouter") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Modèle", style = MaterialTheme.typography.labelLarge, color = AuraOnSurfaceDim)
                Box {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            (freeModels.firstOrNull { it.id == modelId }?.name ?: "Choisir un modèle gratuit") + "  ▾",
                            maxLines = 1,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        freeModels.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            m.id + (m.expires?.let { " · expire le $it" } ?: ""),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AuraOnSurfaceDim,
                                        )
                                    }
                                },
                                onClick = { modelId = m.id; menuOpen = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("ou saisir un identifiant de modèle") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onRefreshModels, enabled = !modelsLoading) { Text("↻ Actualiser la liste") }
                    Text(
                        when {
                            modelsLoading -> "Chargement…"
                            modelsError != null -> modelsError
                            else -> "${freeModels.size} modèles gratuits"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraOnSurfaceDim,
                    )
                }

                Text(
                    "Attention : en mode cloud, vos messages et des extraits de votre mémoire sont envoyés à " +
                        "OpenRouter. Désactivé, tout reste sur l'appareil. La clé est stockée chiffrée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraOnSurfaceDim,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(useCloud, key, modelId) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun ModelSetupCard(loading: Boolean, onImport: () -> Unit, onOpenCloud: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = AuraSurfaceVariant,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (loading) "Chargement du modèle…" else "Modèle IA requis",
                style = MaterialTheme.typography.titleMedium,
                color = AuraOnSurface,
            )
            Text(
                if (loading) "Le modèle est copié puis chargé en mémoire. Cela peut prendre une minute."
                else "Aura tourne 100 % sur l'appareil. Téléchargez un modèle Gemma 2B (fichier .bin ou .task, " +
                    "≈1,4 Go, licence Gemma à accepter sur Kaggle ou Hugging Face), puis importez-le ici.",
                style = MaterialTheme.typography.bodySmall,
                color = AuraOnSurfaceDim,
            )
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AuraPulse)
            } else {
                Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Importer un modèle") }
                OutlinedButton(onClick = onOpenCloud, modifier = Modifier.fillMaxWidth()) { Text("Ou utiliser OpenRouter (cloud)") }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onSpeak: (String) -> Unit) {
    when (message) {
        is ChatMessage.User -> UserBubble(message.message.content)
        is ChatMessage.Agent -> AgentBubble(message.message.content, onSpeak = { onSpeak(message.message.content) })
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
private fun AgentBubble(text: String, isStreaming: Boolean = false, onSpeak: (() -> Unit)? = null) {
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
                if (onSpeak != null) {
                    IconButton(onClick = onSpeak, modifier = Modifier.size(32.dp).align(Alignment.End)) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "Écouter cette réponse",
                            tint = AuraOnSurfaceDim,
                            modifier = Modifier.size(18.dp),
                        )
                    }
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
    onMic: () -> Unit,
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
            IconButton(onClick = onMic, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Mic, contentDescription = "Parler à Aura", tint = if (enabled) AuraPulse else AuraOnSurfaceDim)
            }
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
