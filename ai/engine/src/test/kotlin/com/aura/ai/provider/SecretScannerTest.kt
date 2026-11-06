package com.aura.ai.provider

import org.junit.Assert.*
import org.junit.Test

class SecretScannerTest {
    private val zaiKey = "0123456789abcdef0123456789abcdef.AbCdEfGhIjKlMnOp"

    @Test fun `detects the common key shapes`() {
        listOf(
            "sk-proj-abcdefghijklmnop1234",
            zaiKey,
            "Authorization: Bearer abcdefghijkl12345678",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r",
            "AKIAABCDEFGHIJKLMNOP",
            "ghp_abcdefghijklmnopqrstuvwxyz0123",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----",
        ).forEach { assertTrue("should flag: $it", SecretScanner.containsSecret(it)) }
    }

    @Test fun `detects passwords stated in french and english`() {
        assertTrue(SecretScanner.containsSecret("mon mot de passe est Tr0ub4dor&3"))
        assertTrue(SecretScanner.containsSecret("password: hunter2hunter2"))
        assertTrue(SecretScanner.containsSecret("api_key=abc123def456"))
    }

    @Test fun `detects bank cards with luhn and valid ibans only`() {
        assertTrue(SecretScanner.containsSecret("carte 4111 1111 1111 1111 expire 12/30"))
        assertFalse("not luhn-valid", SecretScanner.containsSecret("numéro 4111 1111 1111 1112"))
        assertTrue(SecretScanner.containsSecret("IBAN FR76 3000 6000 0112 3456 7890 189"))
        assertFalse("bad checksum", SecretScanner.containsSecret("IBAN FR76 3000 6000 0112 3456 7890 180"))
    }

    @Test fun `ordinary preferences are not secrets`() {
        listOf(
            "J'aime le café sans sucre",
            "Mon anniversaire est le 12 mars",
            "I prefer short answers in French",
            "Je travaille chez Acme depuis 2019, appel le lundi à 14h",
            "Le code postal de ma ville est 69003",
        ).forEach { assertFalse("false positive: $it", SecretScanner.containsSecret(it)) }
    }

    @Test fun `redact masks the secret and keeps the rest`() {
        val out = SecretScanner.redact("Erreur pour la clé $zaiKey, réessayez")
        assertFalse(out.contains(zaiKey))
        assertTrue(out.startsWith("Erreur pour la clé "))
        assertTrue(out.endsWith(", réessayez"))
    }

    @Test fun `redactor masks a known secret even if it matches no pattern`() {
        assertEquals("clé *** invalide", Redactor.redact("clé secretvalue invalide", listOf("secretvalue")))
        assertEquals("", Redactor.redact(null))
    }

    @Test fun `provider config never prints the key`() {
        val cfg = ProviderConfig(zaiKey, "https://api.z.ai/api/paas/v4", "glm-4.7-flash")
        assertFalse(cfg.toString().contains(zaiKey))
        assertTrue(cfg.toString().contains("***"))
    }
}
