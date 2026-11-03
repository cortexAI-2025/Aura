package com.aura.feature.chat

import android.speech.SpeechRecognizer

internal sealed interface ListenOutcome {
    /** Listen again after [delayMs]. */
    data class Restart(val delayMs: Long) : ListenOutcome

    /** Leave hands-free mode and tell the user why. */
    data class Stop(val message: String) : ListenOutcome
}

/** Decides what the hands-free loop does after a recogniser error (pure, unit-tested). */
internal object HandsFreePolicy {
    /** Consecutive silent/transient restarts before giving up (reset by any recognised sentence). */
    const val MAX_RETRIES = 3

    fun onRecognizerError(code: Int, retries: Int): ListenOutcome = when (code) {
        // Nothing said: normal in a conversation, listen again quietly a few times.
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            if (retries >= MAX_RETRIES) {
                ListenOutcome.Stop("Mode mains libres arrêté : je n'entends rien. Appuyez sur le casque pour reprendre.")
            } else ListenOutcome.Restart(300)

        // Recogniser still busy / restarting too fast: back off.
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            if (retries >= MAX_RETRIES) {
                ListenOutcome.Stop("Mode mains libres arrêté : le service de reconnaissance vocale ne répond pas.")
            } else ListenOutcome.Restart(1_000)

        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            ListenOutcome.Stop("Permission micro refusée : autorisez le micro pour Aura dans les réglages Android.")

        SpeechRecognizer.ERROR_AUDIO ->
            ListenOutcome.Stop("Erreur micro : vérifiez qu'aucune autre application ne l'utilise.")

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER ->
            ListenOutcome.Stop("Reconnaissance vocale : pas de connexion au service. Mode mains libres arrêté.")

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            ListenOutcome.Stop("Langue non disponible pour la reconnaissance vocale sur cet appareil.")

        else -> ListenOutcome.Stop("Mode mains libres arrêté (erreur de reconnaissance $code).")
    }
}
