package com.aura.feature.chat

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.HeadsetMic
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.core.domain.model.Conversation
import com.aura.core.ui.component.AuraStatusChip
import com.aura.core.ui.component.AuraThinkingIndicator
import com.aura.core.ui.theme.*
import java.util.Locale

@Composable
fun ChatScreen(onOpenSettings: () -> Unit = {}, viewModel: ChatViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val appContext = LocalContext.current

    // ── Hands-free: permission, keep the screen on, stop when the app leaves the foreground ──
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startHandsFree() else viewModel.onMicPermissionDenied()
    }
    val toggleHandsFree = {
        when {
            state.handsFree != HandsFreeState.OFF -> viewModel.stopHandsFree()
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                viewModel.startHandsFree()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val handsFreeOn = state.handsFree != HandsFreeState.OFF
    val view = LocalView.current
    DisposableEffect(handsFreeOn) {
        view.keepScreenOn = handsFreeOn
        onDispose { view.keepScreenOn = false }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Android forbids background microphone capture: stop cleanly instead of failing.
            if (event == Lifecycle.Event.ON_STOP) viewModel.stopHandsFree()
            // Back from Settings: pick up a new provider / model / key.
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAiState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importModel)
    }
    val pickModel = { modelPicker.launch(arrayOf("*/*")) }

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

    // Scroll to bottom when new messages arrive
    LaunchedEffect(state.messages.size, state.isThinking) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1)
    }

    Scaffold(
        containerColor = AuraVoid,
        topBar = {
            AuraChatTopBar(
                state.modelStatus,
                activeLabel = state.activeLabel,
                conversations = state.conversations,
                currentId = state.currentConversationId,
                onNewConversation = viewModel::newConversation,
                onSelectConversation = viewModel::selectConversation,
                onRenameConversation = viewModel::renameConversation,
                onDeleteConversation = viewModel::deleteConversation,
                onImportModel = pickModel,
                onOpenSettings = onOpenSettings,
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
                if (handsFreeOn) {
                    HandsFreeBanner(
                        state = state.handsFree,
                        partial = state.partialText,
                        onStop = { viewModel.stopHandsFree() },
                        onInterrupt = viewModel::interruptSpeech,
                    )
                }
                ChatInputBar(
                    text = state.inputText,
                    onTextChange = viewModel::onInputChanged,
                    onSend = viewModel::sendMessage,
                    onMic = startVoice,
                    handsFreeActive = handsFreeOn,
                    onToggleHandsFree = toggleHandsFree,
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
                item { ModelSetupCard(loading = false, onImport = pickModel, onOpenCloud = onOpenSettings) }
            } else if (state.modelStatus == ModelStatusUi.LOADING) {
                item { ModelSetupCard(loading = true, onImport = pickModel, onOpenCloud = onOpenSettings) }
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

            state.contextInfo?.let { info ->
                item { Text(info, style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim, modifier = Modifier.padding(horizontal = 8.dp)) }
            }
            state.notice?.let { n ->
                item { Text(n, style = MaterialTheme.typography.labelSmall, color = AuraWarning, modifier = Modifier.padding(horizontal = 8.dp)) }
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
                    Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = err, color = AuraError, style = MaterialTheme.typography.bodySmall)
                        if (state.ttsProblem) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    openFirst(
                                        appContext,
                                        Intent("com.android.settings.TTS_SETTINGS"),
                                        Intent(Settings.ACTION_SETTINGS),
                                    )
                                }) { Text("Réglages voix") }
                                OutlinedButton(onClick = {
                                    openFirst(
                                        appContext,
                                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")),
                                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")),
                                    )
                                }) { Text("Installer voix Google") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuraChatTopBar(
    modelStatus: ModelStatusUi,
    activeLabel: String,
    conversations: List<Conversation>,
    currentId: String,
    onNewConversation: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onImportModel: () -> Unit,
    onOpenSettings: () -> Unit,
    speakReplies: Boolean,
    onToggleSpeak: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val current = conversations.firstOrNull { it.id == currentId }

    TopAppBar(
        title = {
            Box {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        current?.title?.takeIf { it.isNotBlank() } ?: "Aura",
                        style = MaterialTheme.typography.titleMedium, maxLines = 1,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Conversations")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("＋ Nouvelle conversation") }, onClick = { menuOpen = false; onNewConversation() })
                    conversations.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.title.ifBlank { "Conversation" }, maxLines = 1, color = if (c.id == currentId) AuraPulse else AuraOnSurface) },
                            onClick = { menuOpen = false; onSelectConversation(c.id) },
                        )
                    }
                    if (current != null) {
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Renommer") }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text("Supprimer cette conversation", color = AuraError) }, onClick = { menuOpen = false; confirmDelete = true })
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onToggleSpeak) {
                Icon(
                    if (speakReplies) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                    contentDescription = if (speakReplies) "Désactiver la lecture vocale" else "Activer la lecture vocale",
                    tint = if (speakReplies) AuraPulse else AuraOnSurfaceDim,
                )
            }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Réglages") }
            val (label, color) = when (modelStatus) {
                ModelStatusUi.READY -> "Local" to AuraSuccess
                ModelStatusUi.LOADING -> "Chargement…" to AuraWarning
                ModelStatusUi.NOT_FOUND -> "Aucun modèle" to AuraError
                ModelStatusUi.CHECKING -> "Vérification" to AuraOnSurfaceDim
                ModelStatusUi.ERROR -> "Échec du chargement" to AuraError
                ModelStatusUi.CLOUD -> activeLabel.ifBlank { "Cloud" } to AuraGlow
            }
            val canImport = modelStatus == ModelStatusUi.NOT_FOUND || modelStatus == ModelStatusUi.ERROR
            AuraStatusChip(
                label, color,
                Modifier.padding(end = 12.dp).widthIn(max = 150.dp).clickable(onClick = if (canImport) onImportModel else onOpenSettings),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AuraVoid),
    )

    if (renaming && current != null) {
        var title by remember(current.id) { mutableStateOf(current.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Renommer la conversation") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it.take(60) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { onRenameConversation(current.id, title); renaming = false }) { Text("Enregistrer") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annuler") } },
        )
    }
    if (confirmDelete && current != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer cette conversation ?") },
            text = { Text("Les messages de « ${current.title.ifBlank { "Conversation" }} » seront effacés de l'appareil. Cette action est définitive.") },
            confirmButton = { TextButton(onClick = { onDeleteConversation(current.id); confirmDelete = false }) { Text("Supprimer", color = AuraError) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun HandsFreeBanner(
    state: HandsFreeState,
    partial: String,
    onStop: () -> Unit,
    onInterrupt: () -> Unit,
) {
    Surface(color = AuraSurfaceVariant, tonalElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (state) {
                        HandsFreeState.LISTENING -> "Je vous écoute…"
                        HandsFreeState.THINKING -> "Je réfléchis…"
                        HandsFreeState.SPEAKING -> "Je parle…"
                        HandsFreeState.OFF -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = AuraOnSurface,
                )
                if (state == HandsFreeState.LISTENING && partial.isNotBlank()) {
                    Text(partial, style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim, maxLines = 2)
                }
            }
            if (state == HandsFreeState.SPEAKING) {
                OutlinedButton(onClick = onInterrupt) { Text("Interrompre") }
            }
            Button(onClick = onStop) { Text("Arrêter") }
        }
    }
}

/** Launch the first intent that some installed app can handle. */
private fun openFirst(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // try the next one
        }
    }
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
                OutlinedButton(onClick = onOpenCloud, modifier = Modifier.fillMaxWidth()) { Text("Ou configurer un fournisseur cloud (Z.ai…)") }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage, onSpeak: (String) -> Unit) {
    when (message) {
        is ChatMessage.User -> UserBubble(message.message.content)
        is ChatMessage.Agent -> AgentBubble(
            message.message.content,
            onSpeak = { onSpeak(message.message.content) },
            caption = message.message.let { m ->
                val tokens = (m.promptTokens ?: 0) + (m.completionTokens ?: 0)
                listOfNotNull(m.model, tokens.takeIf { it > 0 }?.let { "$it tokens" }, if (m.finishReason == "truncated") "réponse tronquée" else null)
                    .joinToString(" · ").ifEmpty { null }
            },
        )
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
private fun AgentBubble(text: String, isStreaming: Boolean = false, onSpeak: (() -> Unit)? = null, caption: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            color = AuraSurfaceVariant,
        ) {
            Column(modifier = Modifier.padding(12.dp, 10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = AuraOnSurface)
                caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim, modifier = Modifier.padding(top = 4.dp)) }
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
    handsFreeActive: Boolean,
    onToggleHandsFree: () -> Unit,
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
            IconButton(onClick = onToggleHandsFree, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Default.HeadsetMic,
                    contentDescription = if (handsFreeActive) "Arrêter le mode mains libres" else "Mode mains libres",
                    tint = if (handsFreeActive) AuraSuccess else AuraPulse,
                )
            }
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
