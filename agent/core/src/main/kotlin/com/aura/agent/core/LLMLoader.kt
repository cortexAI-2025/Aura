package com.aura.agent.core

import android.content.Context
import com.aura.ai.engine.LLMEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
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

    fun findModel(): File? = modelDirs
        .flatMap { dir -> dir.listFiles()?.toList() ?: emptyList() }
        .filter { it.isFile && (it.extension == "bin" || it.extension == "task") }
        .maxByOrNull { it.length() }  // prefer the largest (highest quality) model

    fun getModelStatus(): ModelStatus {
        val model = findModel()
        return when {
            llmEngine.isReady -> ModelStatus.LOADED
            model != null -> ModelStatus.FOUND_NOT_LOADED
            else -> ModelStatus.NOT_FOUND
        }
    }
}

enum class ModelStatus { NOT_FOUND, FOUND_NOT_LOADED, LOADED }
