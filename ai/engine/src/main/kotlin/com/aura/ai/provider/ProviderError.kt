package com.aura.ai.provider

import java.io.IOException

enum class ErrorKind(val retryable: Boolean) {
    INVALID_KEY(false),
    INSUFFICIENT_BALANCE(false),
    QUOTA_EXCEEDED(false),
    RATE_LIMITED(true),
    OVERLOADED(true),
    MODEL_NOT_FOUND(false),
    PERMISSION_DENIED(false),
    CONTENT_FILTERED(false),
    CONTEXT_TOO_LONG(false),
    BAD_REQUEST(false),
    SERVER_ERROR(true),
    NETWORK(true),
    TIMEOUT(true),
    INSECURE_ENDPOINT(false),
    UNKNOWN(false),
}

/**
 * A provider failure, already classified. [detail] is the (redacted) server message and [hint] a
 * provider-specific suggestion. Nothing in here can contain an API key: see [Redactor].
 */
data class ProviderError(
    val kind: ErrorKind,
    val httpStatus: Int? = null,
    val serverCode: String? = null,
    val detail: String? = null,
    val hint: String? = null,
    val retryAfterMs: Long? = null,
) {
    val retryable: Boolean get() = kind.retryable

    /** French, user-facing, safe to display and to log. */
    val userMessage: String
        get() {
            val base = when (kind) {
                ErrorKind.INVALID_KEY -> "Clé API refusée par le fournisseur (invalide, expirée ou mal copiée)."
                ErrorKind.INSUFFICIENT_BALANCE -> "Solde ou forfait insuffisant chez le fournisseur."
                ErrorKind.QUOTA_EXCEEDED -> "Quota d'utilisation atteint chez le fournisseur."
                ErrorKind.RATE_LIMITED -> "Limite de requêtes atteinte : réessayez dans un instant."
                ErrorKind.OVERLOADED -> "Le service est surchargé : réessayez dans un instant."
                ErrorKind.MODEL_NOT_FOUND -> "Modèle inconnu ou indisponible : vérifiez son identifiant."
                ErrorKind.PERMISSION_DENIED -> "Accès refusé à ce modèle avec cette clé."
                ErrorKind.CONTENT_FILTERED -> "Réponse bloquée par le filtre de sécurité du fournisseur."
                ErrorKind.CONTEXT_TOO_LONG -> "La conversation est trop longue pour ce modèle."
                ErrorKind.BAD_REQUEST -> "Requête refusée par le fournisseur."
                ErrorKind.SERVER_ERROR -> "Erreur côté fournisseur : réessayez plus tard."
                ErrorKind.NETWORK -> "Pas de connexion au fournisseur (réseau)."
                ErrorKind.TIMEOUT -> "Le fournisseur n'a pas répondu à temps."
                ErrorKind.INSECURE_ENDPOINT -> "Adresse refusée : HTTPS est obligatoire."
                ErrorKind.UNKNOWN -> "Erreur inattendue du fournisseur."
            }
            val code = listOfNotNull(httpStatus?.let { "HTTP $it" }, serverCode?.let { "code $it" })
                .joinToString(", ").takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty()
            val extra = detail?.let { Redactor.redact(it) }?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { " Détail : ${it.take(MAX_DETAIL)}" }.orEmpty()
            val tip = hint?.let { " $it" }.orEmpty()
            return base + code + tip + extra
        }

    companion object { private const val MAX_DETAIL = 220 }
}

/** Thrown by providers; extends [IOException] so generic network-error handling still applies. */
class ProviderException(val error: ProviderError, cause: Throwable? = null) :
    IOException(error.userMessage, cause)
