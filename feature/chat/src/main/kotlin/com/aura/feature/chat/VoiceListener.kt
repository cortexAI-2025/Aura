package com.aura.feature.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Dialog-free speech recognition for the hands-free conversation loop.
 * Must be used from the main thread. One utterance per [start]; the caller restarts it.
 */
internal class VoiceListener(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(code: Int)
    }

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        stop()
        val rec = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: RuntimeException) {
            callbacks.onError(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        recognizer = rec
        rec.setRecognitionListener(object : RecognitionListener {
            // Ignore late callbacks from a recogniser that stop() already replaced.
            private fun current() = recognizer === rec

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (!current()) return
                val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) callbacks.onPartial(t)
            }

            override fun onResults(results: Bundle?) {
                if (!current()) return
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) callbacks.onError(SpeechRecognizer.ERROR_NO_MATCH) else callbacks.onFinal(t)
            }

            override fun onError(error: Int) {
                if (current()) callbacks.onError(error)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // A bit of patience for natural pauses, but not so much that the conversation drags.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
        }
        try {
            rec.startListening(intent)
        } catch (e: RuntimeException) {
            callbacks.onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stop() {
        val rec = recognizer ?: return
        recognizer = null // first: late callbacks are ignored
        runCatching { rec.cancel() }
        runCatching { rec.destroy() }
    }
}
