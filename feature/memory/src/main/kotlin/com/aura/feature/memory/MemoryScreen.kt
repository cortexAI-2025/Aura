package com.aura.feature.memory

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.core.domain.model.Memory
import com.aura.core.domain.model.MemorySource
import com.aura.core.ui.theme.*

@Composable
fun MemoryScreen(viewModel: MemoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Memory?>(null) }

    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); viewModel.consumeMessage() } }
    LaunchedEffect(state.exportJson) {
        state.exportJson?.let { json ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, "Mémoire Aura (export)"); putExtra(Intent.EXTRA_TEXT, json)
                    },
                    "Exporter la mémoire",
                ),
            )
            viewModel.exportHandled()
        }
    }

    Scaffold(
        containerColor = AuraVoid,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Text("＋") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Spacer(Modifier.height(8.dp))
            Text("Mémoire", style = MaterialTheme.typography.headlineSmall, color = AuraOnSurface)
            Text(
                "${state.total} souvenir(s) stocké(s) sur cet appareil. Aura n'en ajoute que si vous le demandez (« retiens que… ») " +
                    "ou si vous activez le résumé automatique dans les réglages. Les mots de passe et clés ne sont jamais mémorisés.",
                style = MaterialTheme.typography.bodySmall, color = AuraOnSurfaceDim,
            )
            if (state.needsReview > 0) {
                Text("${state.needsReview} souvenir(s) à vérifier (doublon ou contradiction possible).", style = MaterialTheme.typography.bodySmall, color = AuraWarning)
            }
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::setQuery, label = { Text("Rechercher") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::export, enabled = state.total > 0) { Text("Exporter (JSON)") }
                OutlinedButton(onClick = { confirmReset = true }, enabled = state.total > 0) { Text("Réinitialiser", color = AuraError) }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 88.dp)) {
                if (state.memories.isEmpty()) {
                    item {
                        Text(
                            if (state.query.isBlank()) "Aucun souvenir pour l'instant. Dites « retiens que… » dans le chat, ou ajoutez-en un avec ＋."
                            else "Aucun résultat.",
                            style = MaterialTheme.typography.bodyMedium, color = AuraOnSurfaceDim,
                        )
                    }
                }
                items(state.memories, key = { it.id }) { m ->
                    Surface(shape = RoundedCornerShape(14.dp), color = AuraSurfaceVariant, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(m.content, style = MaterialTheme.typography.bodyMedium, color = AuraOnSurface)
                            Text(
                                (if (m.source == MemorySource.AUTO) "Résumé automatique" else "Ajouté par vous") + " · ${m.type.name.lowercase()} · ${m.updatedAt.toString().take(10)}",
                                style = MaterialTheme.typography.labelSmall, color = AuraOnSurfaceDim,
                            )
                            if (m.needsReview) {
                                Text("À vérifier : ressemble à un autre souvenir.", style = MaterialTheme.typography.labelSmall, color = AuraWarning)
                            }
                            Row {
                                TextButton(onClick = { editing = m }) { Text("Modifier") }
                                if (m.needsReview) TextButton(onClick = { viewModel.keepBoth(m.id) }) { Text("Garder") }
                                TextButton(onClick = { toDelete = m }) { Text("Supprimer", color = AuraError) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) TextDialog("Nouveau souvenir", "", { adding = false }) { viewModel.add(it); adding = false }
    editing?.let { m -> TextDialog("Modifier le souvenir", m.content, { editing = null }) { viewModel.edit(m.id, it); editing = null } }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null }, title = { Text("Supprimer ce souvenir ?") }, text = { Text(m.content) },
            confirmButton = { TextButton(onClick = { viewModel.delete(m.id); toDelete = null }) { Text("Supprimer", color = AuraError) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annuler") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false }, title = { Text("Effacer toute la mémoire ?") },
            text = { Text("Les ${state.total} souvenirs seront supprimés de l'appareil. Pensez à exporter d'abord si besoin. Action définitive.") },
            confirmButton = { TextButton(onClick = { viewModel.resetAll(); confirmReset = false }) { Text("Tout effacer", color = AuraError) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(1000) }, minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onOk(text) }, enabled = text.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
