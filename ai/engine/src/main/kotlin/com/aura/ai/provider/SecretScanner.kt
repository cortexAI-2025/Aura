package com.aura.ai.provider

enum class SecretKind { API_KEY, BEARER_TOKEN, JWT, PRIVATE_KEY, PASSWORD_ASSIGNMENT, CARD_NUMBER, IBAN, OPAQUE_TOKEN }

data class SecretFinding(val kind: SecretKind, val range: IntRange)

/**
 * Finds things that must never be logged, shown in an error, or stored in memory: API keys, bearer
 * tokens, passwords, bank cards, IBANs. Heuristic by nature: it favours false positives.
 */
object SecretScanner {
    private data class Rule(val kind: SecretKind, val regex: Regex, val group: Int = 0)

    private val rules = listOf(
        Rule(SecretKind.PRIVATE_KEY, Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)")),
        Rule(SecretKind.BEARER_TOKEN, Regex("(?i)\\bbearer\\s+([A-Za-z0-9._~+/=-]{8,})"), 1),
        Rule(SecretKind.JWT, Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}")),
        Rule(SecretKind.API_KEY, Regex("\\bsk-[A-Za-z0-9_-]{12,}")),
        Rule(SecretKind.API_KEY, Regex("\\b[A-Fa-f0-9]{32}\\.[A-Za-z0-9]{8,}\\b")), // Z.ai key shape: <32 hex>.<secret>
        Rule(SecretKind.API_KEY, Regex("\\bAKIA[0-9A-Z]{16}\\b")),
        Rule(SecretKind.API_KEY, Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}")),
        Rule(
            SecretKind.PASSWORD_ASSIGNMENT,
            Regex("(?i)\\b(?:api[_ -]?key|token|secret|password|passwd|mot de passe|mdp|code pin|pin code|passphrase)\\b[\"']?\\s*(?:[:=]|est|is|=>)\\s*[\"']?([^\\s\"',;}]{4,})"),
            1,
        ),
        Rule(SecretKind.OPAQUE_TOKEN, Regex("\\b[A-Za-z0-9_-]{40,}\\b")),
    )

    private val cardCandidate = Regex("\\b(?:\\d[ -]?){13,19}\\b")
    private val ibanCandidate = Regex("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{2,4}){3,8}\\b")

    fun scan(text: String): List<SecretFinding> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<SecretFinding>()
        for (r in rules) for (m in r.regex.findAll(text)) {
            val g = if (r.group == 0) m.range else (m.groups[r.group]?.range ?: m.range)
            out += SecretFinding(r.kind, g)
        }
        for (m in cardCandidate.findAll(text)) {
            val digits = m.value.filter { it.isDigit() }
            if (digits.length in 13..19 && luhn(digits)) out += SecretFinding(SecretKind.CARD_NUMBER, m.range)
        }
        for (m in ibanCandidate.findAll(text)) {
            if (ibanValid(m.value.replace(" ", ""))) out += SecretFinding(SecretKind.IBAN, m.range)
        }
        return out
    }

    fun containsSecret(text: String): Boolean = scan(text).isNotEmpty()

    fun redact(text: String): String {
        val findings = scan(text).sortedBy { it.range.first }
        if (findings.isEmpty()) return text
        val sb = StringBuilder()
        var cursor = 0
        for (f in findings) {
            if (f.range.first < cursor) continue // overlapping finding already redacted
            sb.append(text, cursor, f.range.first).append("***")
            cursor = f.range.last + 1
        }
        sb.append(text, cursor, text.length)
        return sb.toString()
    }

    private fun luhn(digits: String): Boolean {
        var sum = 0
        var dbl = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (dbl) { d *= 2; if (d > 9) d -= 9 }
            sum += d
            dbl = !dbl
        }
        return sum % 10 == 0 && digits.any { it != '0' }
    }

    private fun ibanValid(iban: String): Boolean {
        if (iban.length !in 15..34) return false
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        var rem = 0
        for (c in rearranged) {
            val v = when {
                c.isDigit() -> c - '0'
                c in 'A'..'Z' -> c - 'A' + 10
                else -> return false
            }
            rem = if (v >= 10) (rem * 100 + v) % 97 else (rem * 10 + v) % 97
        }
        return rem == 1
    }
}

/** Removes secrets from any text that could reach a log, a toast or an error message. */
object Redactor {
    fun redact(text: String?, known: Collection<String> = emptyList()): String {
        if (text.isNullOrEmpty()) return ""
        var out: String = text
        for (k in known) if (k.length >= 6) out = out.replace(k, "***")
        return SecretScanner.redact(out)
    }
}
