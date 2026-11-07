package com.aura.ai.provider

import org.junit.Assert.*
import org.junit.Test

class ZaiErrorsTest {
    private fun body(code: String, msg: String = "m") = """{"error":{"code":"$code","message":"$msg"}}"""

    @Test fun `documented business codes`() {
        mapOf(
            "1000" to ErrorKind.INVALID_KEY, "1001" to ErrorKind.INVALID_KEY, "1003" to ErrorKind.INVALID_KEY,
            "1113" to ErrorKind.INSUFFICIENT_BALANCE, "1302" to ErrorKind.RATE_LIMITED, "1305" to ErrorKind.OVERLOADED,
            "1211" to ErrorKind.MODEL_NOT_FOUND, "1301" to ErrorKind.CONTENT_FILTERED, "1261" to ErrorKind.CONTEXT_TOO_LONG,
            "1214" to ErrorKind.BAD_REQUEST, "1220" to ErrorKind.PERMISSION_DENIED, "1311" to ErrorKind.PERMISSION_DENIED,
            "1308" to ErrorKind.QUOTA_EXCEEDED, "1313" to ErrorKind.QUOTA_EXCEEDED, "1234" to ErrorKind.SERVER_ERROR,
        ).forEach { (code, kind) -> assertEquals("code $code", kind, ZaiErrors.map(429, body(code)).kind) }
    }

    @Test fun `business code wins over the http status - 1113 is a 429 that must not be retried`() {
        val e = ZaiErrors.map(429, body("1113"))
        assertEquals(ErrorKind.INSUFFICIENT_BALANCE, e.kind)
        assertFalse(e.retryable)
        assertTrue(ZaiErrors.map(429, body("1302")).retryable)
        assertTrue(ZaiErrors.map(429, body("1305")).retryable)
    }

    @Test fun `falls back to the http status when the body is unusable`() {
        assertEquals(ErrorKind.INVALID_KEY, ZaiErrors.map(401, null).kind)
        assertEquals(ErrorKind.PERMISSION_DENIED, ZaiErrors.map(403, "<html>").kind)
        assertEquals(ErrorKind.RATE_LIMITED, ZaiErrors.map(429, "").kind)
        assertEquals(ErrorKind.SERVER_ERROR, ZaiErrors.map(503, "oops").kind)
        assertEquals(ErrorKind.UNKNOWN, ZaiErrors.map(null, null).kind)
    }

    @Test fun `gateway style body with code and msg is understood`() {
        val e = ZaiErrors.map(401, """{"code":1001,"msg":"Header Authorization missing","success":false}""")
        assertEquals(ErrorKind.INVALID_KEY, e.kind)
        assertEquals("1001", e.serverCode)
    }

    @Test fun `invalid key message explains the standard vs coding plan key`() {
        val msg = ZaiErrors.map(401, body("1000")).userMessage
        assertTrue(msg, msg.contains("Standard"))
        assertTrue(msg, msg.contains("1000"))
    }

    @Test fun `server messages are redacted`() {
        val key = "0123456789abcdef0123456789abcdef.AbCdEfGhIjKlMnOp"
        val msg = ZaiErrors.map(400, body("1214", "bad value for $key")).userMessage
        assertFalse(msg.contains(key))
    }
}
