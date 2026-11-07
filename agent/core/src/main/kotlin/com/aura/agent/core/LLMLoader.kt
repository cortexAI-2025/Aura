package com.aura.agent.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aura.ai.engine.LLMEngine
import com.aura.ai.settings.AiSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages model lifecycle: discovers the Gemma model on-device and loads it.
 *
 * Model lookup order:
 *   1. /data/user/0/<packageName>/files/models/  (copied via adb or download)
 *   2. /sdcard/Android/data/<packageName>/files/models/  (external storage)
 *
 * Model filename convention: gemma-2b-it-gpu-int4.bin or gemma-2b-it-cpu-int4.bin
 * Download from https://www.kaggle.com/models/google/gemma/frameworks/tfLite
 */
@Singleton
class LLMLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llmEngine: LLMEngine,
    private val settings: AiSettings,
) {
    private val modelDirs = listOf(
        File(context.filesDir, "models"),
        File(context.getExternalFilesDir(null), "models"),
    )

    suspend fun loadIfNeeded() {
        if (llmEngine.isReady) return
        val model = findModel() ?: run {
            Timber.e("No Gemma model found in ${modelDirs.map { it.absolutePath }}. " +
                    "Copy a .bin file there or run: adb push gemma-2b-it-gpu-int4.bin /sdcard/Android/data/<pkg>/files/models/")
            return
        }
        Timber.i("Found model: ${model.absolutePath} (${model.length() / 1_000_000} MB)")
        llmEngine.load(model.absolutePath)
    }

    /**
     * Copies a model file picked by the user (Storage Access Framework) into the
     * app-private models directory, then returns it. The Gemma weights are licensed
     * per user (Kaggle / Hugging Face), so they cannot be bundled in the APK.
     */
    suspend fun importModel(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        val tmp = File(dir, "import.part")
        runCatching {
            val rawName = queryDisplayName(uri) ?: "imported-model.task"
            val name = rawName.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
            require(name.substringAfterLast('.', "").lowercase() in setOf("bin", "task")) {
                "Format non supporté : un fichier .bin ou .task est attendu (reçu « $rawName »)."
            }
            val input = requireNotNull(context.contentResolver.openInputStream(uri)) {
                "Impossible d'ouvrir le fichier sélectionné."
            }
            input.use { src -> tmp.outputStream().use { dst -> src.copyTo(dst) } }
            val dest = File(dir, name)
            if (dest.exists()) dest.delete()
            check(tmp.renameTo(dest)) { "Impossible d'enregistrer le modèle." }
            Timber.i("Model imported: ${dest.absolutePath} (${dest.length() / 1_000_000} MB)")
            dest
        }.onFailure { tmp.delete() }
    }

    private fun queryDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun findModel(): File? = modelDirs
        .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile && (it.extension == "bin" || it.extension == "task") }
        .maxByOrNull { it.length() }  // prefer the largest (highest quality) model

    fun getModelStatus(): ModelStatus {
        val model = findModel()
        return when {
            settings.cloudActive -> ModelStatus.CLOUD
            llmEngine.isReady -> ModelStatus.LOADED
            model != null -> ModelStatus.FOUND_NOT_LOADED
            else -> ModelStatus.NOT_FOUND
        }
    }
}

enum class ModelStatus { NOT_FOUND, FOUND_NOT_LOADED, LOADED, CLOUD }
