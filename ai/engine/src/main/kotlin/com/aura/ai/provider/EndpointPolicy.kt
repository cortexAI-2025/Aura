package com.aura.ai.provider

import java.net.URI

/** Only HTTPS endpoints may receive an API key (plain HTTP to loopback is allowed in tests only). */
object EndpointPolicy {
    /** @return the normalised base URL without trailing slash; throws [ProviderException] if unsafe. */
    fun validate(baseUrl: String, allowInsecureLoopback: Boolean = false): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val uri = try { URI(trimmed) } catch (e: Exception) { throw refuse("adresse invalide") }
        val scheme = uri.scheme?.lowercase() ?: throw refuse("adresse invalide")
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: throw refuse("adresse invalide")
        if (uri.userInfo != null) throw refuse("identifiants dans l'adresse interdits")
        if (uri.query != null || uri.fragment != null) throw refuse("paramètres dans l'adresse interdits")
        val loopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        when {
            scheme == "https" -> Unit
            scheme == "http" && loopback && allowInsecureLoopback -> Unit
            else -> throw refuse("HTTPS obligatoire")
        }
        return trimmed
    }

    private fun refuse(why: String) = ProviderException(ProviderError(ErrorKind.INSECURE_ENDPOINT, detail = why))
}
