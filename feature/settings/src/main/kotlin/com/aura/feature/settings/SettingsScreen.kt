package com.aura.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.collectAsState
import com.aura.ai.brain.LocalVerdict
import com.aura.core.domain.model.DetailLevel
import com.aura.core.domain.model.Tone
import com.aura.core.ui.theme.*

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importModel) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() }
    }
    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Confirmer") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { action(); confirm = null }) { Text("Effacer", color = AuraError) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Annuler") } },
        )
    }

    Scaffold(containerColor = AuraVoid, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item { Text("Réglages IA", style = MaterialTheme.typography.headlineSmall, color = AuraOnSurface) }

            // ── Provider ─────────────────────────────────────────────────────
            item {
                Section("Fournisseur d'IA") {
                    Text(
                        if (state.activeProviderId == null) "Mode actuel : Local (sur l'appareil)"
                        else "Mode actuel : Cloud · ${state.providers.firstOrNull { it.id == state.activeProviderId }?.name}",
                        style = MaterialTheme.typography.bodyMedium, color = AuraOnSurface,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.providers.forEach { p ->
                            FilterChip(selected = state.editingProviderId == p.id, onClick = { viewModel.editProvider(p.id) }, label = { Text(p.name) })
                        }
                    }
                    if (state.planned.isNotEmpty()) {
                        Text(
                            "Prévus, pas encore disponibles : " + state.planned.joinToString { it.displayName } + ".",
                            style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim,
                        )
                    }
                }
            }

            item { ProviderCard(state, viewModel, viewModel.editingProvider.let { it.keyHint to it.dataNotice }) }

            // ── Consumption ──────────────────────────────────────────────────
            item {
                Section("Consommation") {
                    Text(
                        "${state.usageTotal.totalTokens} tokens · ${state.usageTotal.requests} réponses " +
                            "(${state.usageTotal.promptTokens} envoyés, ${state.usageTotal.completionTokens} reçus)",
                        style = MaterialTheme.typography.bodyMedium, color = AuraOnSurface,
                    )
                    state.usageByModel.forEach { u ->
                        Text(
                            "• ${u.model ?: "?"}${u.providerId?.let { " ($it)" } ?: ""} : ${u.totals.totalTokens} tokens, ${u.totals.requests} réponses",
                            style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim,
                        )
                    }
                    Text(
                        "Total calculé sur l'appareil à partir des compteurs renvoyés par les API. Aucun solde de compte n'est " +
                            "affiché : Z.ai ne documente pas de point d'accès public pour le solde. Consultez z.ai pour le détail officiel.",
                        style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim,
                    )
                }
            }

            // ── Personalization ──────────────────────────────────────────────
            item { PersonalizationCard(state, viewModel) }

            // ── Privacy and data ─────────────────────────────────────────────
            item {
                Section("Confidentialité et données") {
                    Text(
                        "Tout est stocké sur cet appareil. Aucune donnée n'est envoyée à un serveur AIWORKPAY. En mode Cloud, vos messages " +
                            "(et, si vous l'autorisez ci-dessus, des extraits de mémoire et vos objectifs) sont envoyés au fournisseur choisi. " +
                            "Une clé BYOK ne garantit pas à elle seule la confidentialité des données chez le fournisseur.",
                        style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim,
                    )
                    OutlinedButton(onClick = { confirm = "Supprimer toutes les conversations de l'appareil ?" to viewModel::eraseConversations }, modifier = Modifier.fillMaxWidth()) { Text("Effacer les conversations") }
                    OutlinedButton(onClick = { confirm = "Effacer toute la mémoire (souvenirs) ?" to viewModel::eraseMemory }, modifier = Modifier.fillMaxWidth()) { Text("Effacer la mémoire") }
                    OutlinedButton(onClick = { confirm = "Supprimer toutes les clés API et les choix de modèle ?" to viewModel::eraseKeys }, modifier = Modifier.fillMaxWidth()) { Text("Supprimer les clés et choix IA") }
                    Button(
                        onClick = { confirm = "Effacer conversations, mémoire, personnalisation et clés ? Action définitive." to viewModel::eraseAll },
                        colors = ButtonDefaults.buttonColors(containerColor = AuraError), modifier = Modifier.fillMaxWidth(),
                    ) { Text("Tout effacer") }
                }
            }

            // ── Local mode ───────────────────────────────────────────────────
            item {
                Section("Mode local") {
                    val local = state.local
                    Text(
                        local?.message ?: "Analyse…", style = MaterialTheme.typography.bodySmall,
                        color = when (local?.verdict) { LocalVerdict.OK -> AuraSuccess; LocalVerdict.TIGHT -> AuraWarning; LocalVerdict.INSUFFICIENT -> AuraError; else -> AuraOnSurfaceDim },
                    )
                    Text(
                        "Le modèle (fichier .bin ou .task, ≈1,4 Go) n'est jamais téléchargé automatiquement : importez-le vous-même.",
                        style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim,
                    )
                    OutlinedButton(onClick = { modelPicker.launch(arrayOf("*/*")) }, enabled = !state.localBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.localBusy) "Import en cours…" else "Importer un modèle local")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(state: SettingsUiState, vm: SettingsViewModel, notices: Pair<String?, String>) {
    val provider = vm.editingProvider
    var key by remember(state.editingProviderId) { mutableStateOf("") }
    var show by remember(state.editingProviderId) { mutableStateOf(false) }
    var modelText by remember(state.editingProviderId, state.model) { mutableStateOf(state.model) }
    var menu by remember { mutableStateOf(false) }

    Section(provider.displayName) {
        notices.first?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim) }
        if (!state.secureStorage) {
            Text(
                "Stockage sécurisé indisponible sur cet appareil : la clé ne sera gardée qu'en mémoire jusqu'à la fermeture de l'appli.",
                style = MaterialTheme.typography.bodySmall, color = AuraWarning,
            )
        }
        Text(
            if (state.keySaved) "Clé API : enregistrée (chiffrée, jamais réaffichée)" else "Clé API : aucune",
            style = MaterialTheme.typography.bodyMedium, color = if (state.keySaved) AuraSuccess else AuraOnSurfaceDim,
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it.filter { c -> !c.isWhitespace() } },
            label = { Text(if (state.keySaved) "Nouvelle clé (remplace l'actuelle)" else "Clé API") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Masquer" else "Afficher") } },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveKey(key); key = "" }, enabled = key.length >= 8) { Text("Enregistrer la clé") }
            if (state.keySaved) OutlinedButton(onClick = vm::deleteKey) { Text("Supprimer la clé", color = AuraError) }
        }

        // Model: free text + the suggestions that are really known for this provider
        Box {
            OutlinedTextField(
                value = modelText, onValueChange = { modelText = it.trim() }, label = { Text("Modèle") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { TextButton(onClick = { menu = true }) { Text("▾") } },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                provider.suggestedModels.forEach { m ->
                    DropdownMenuItem(
                        text = { Column { Text(m.displayName); m.note?.let { Text(it, style = MaterialTheme.typography.labelSmall) } } },
                        onClick = { modelText = m.id; vm.setModel(m.id); menu = false },
                    )
                }
            }
        }
        if (modelText != state.model) OutlinedButton(onClick = { vm.setModel(modelText) }) { Text("Appliquer le modèle") }

        if (provider.capabilities.thinkingToggle) {
            SwitchRow("Raisonnement approfondi (plus lent, plus de tokens)", state.thinking, vm::setThinking)
        }
        if (provider.fallbackModels(state.model).isNotEmpty()) {
            SwitchRow("Basculer sur un autre modèle gratuit si celui-ci est saturé", state.autoFallback, vm::setAutoFallback)
        }

        Button(onClick = { vm.testConnection(key) }, enabled = state.test !is TestState.Running, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.test is TestState.Running) "Test en cours…" else "Tester la connexion")
        }
        when (val t = state.test) {
            is TestState.Success -> Text(t.text, style = MaterialTheme.typography.bodySmall, color = AuraSuccess)
            is TestState.Failure -> Text(t.text, style = MaterialTheme.typography.bodySmall, color = AuraError)
            else -> Unit
        }

        HorizontalDivider()
        SwitchRow(
            "Utiliser ${provider.displayName} pour les réponses (sinon : mode local)",
            checked = state.activeProviderId == provider.id,
            onChange = vm::setCloudActive,
        )
        Text(notices.second, style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim)
    }
}

@Composable
private fun PersonalizationCard(state: SettingsUiState, vm: SettingsViewModel) {
    val p = state.profile
    var name by remember(p.assistantName) { mutableStateOf(p.assistantName) }
    var instructions by remember(p.customInstructions) { mutableStateOf(p.customInstructions) }
    Section("Personnalisation") {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(24) }, label = { Text("Nom de l'assistant") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Langue", style = MaterialTheme.typography.labelMedium, color = AuraOnSurfaceDim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Auto", "fr" to "Français", "en" to "English", "es" to "Español", "de" to "Deutsch").forEach { (tag, label) ->
                FilterChip(selected = p.language == tag, onClick = { vm.updateProfile { it.copy(language = tag) } }, label = { Text(label) })
            }
        }
        Text("Ton", style = MaterialTheme.typography.labelMedium, color = AuraOnSurfaceDim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Tone.FRIENDLY to "Amical", Tone.NEUTRAL to "Neutre", Tone.FORMAL to "Formel", Tone.CONCISE to "Concis").forEach { (t, l) ->
                FilterChip(selected = p.tone == t, onClick = { vm.updateProfile { it.copy(tone = t) } }, label = { Text(l) })
            }
        }
        Text("Niveau de détail", style = MaterialTheme.typography.labelMedium, color = AuraOnSurfaceDim)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DetailLevel.BRIEF to "Court", DetailLevel.BALANCED to "Équilibré", DetailLevel.DETAILED to "Détaillé").forEach { (d, l) ->
                FilterChip(selected = p.detail == d, onClick = { vm.updateProfile { it.copy(detail = d) } }, label = { Text(l) })
            }
        }
        OutlinedTextField(
            value = instructions, onValueChange = { instructions = it.take(500) },
            label = { Text("Vos préférences (ex. « tutoie-moi », « réponds en listes »)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
            supportingText = { Text("${instructions.length}/500 · envoyé au modèle à chaque demande") },
        )
        OutlinedButton(onClick = { vm.updateProfile { it.copy(assistantName = name, customInstructions = instructions) } }) { Text("Appliquer") }
        HorizontalDivider()
        SwitchRow("Utiliser ma mémoire pour répondre", p.useMemory) { v -> vm.updateProfile { it.copy(useMemory = v) } }
        SwitchRow("Résumer automatiquement mes échanges en mémoire (désactivé par défaut)", p.autoMemory) { v -> vm.updateProfile { it.copy(autoMemory = v) } }
        SwitchRow("Autoriser l'envoi d'extraits de mémoire au fournisseur cloud", p.shareMemoryWithCloud) { v -> vm.updateProfile { it.copy(shareMemoryWithCloud = v) } }
        SwitchRow("Autoriser l'envoi de mes objectifs au fournisseur cloud", p.shareGoalsWithCloud) { v -> vm.updateProfile { it.copy(shareGoalsWithCloud = v) } }
        TextButton(onClick = vm::resetProfile) { Text("Rétablir les valeurs par défaut") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = AuraOnSurface)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = AuraSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AuraOnSurface)
            content()
        }
    }
}
