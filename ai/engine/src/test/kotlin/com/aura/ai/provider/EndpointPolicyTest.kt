package com.aura.ai.provider

import org.junit.Assert.*
import org.junit.Test

class EndpointPolicyTest {
    private fun refused(url: String, loopback: Boolean = false): ProviderException =
        try { EndpointPolicy.validate(url, loopback); fail("should refuse $url"); throw IllegalStateException() }
        catch (e: ProviderException) { e }

    @Test fun `https is accepted and normalised`() {
        assertEquals("https://api.z.ai/api/paas/v4", EndpointPolicy.validate(" https://api.z.ai/api/paas/v4/ "))
    }

    @Test fun `plain http is refused`() {
        assertEquals(ErrorKind.INSECURE_ENDPOINT, refused("http://api.z.ai/api/paas/v4").error.kind)
    }

    @Test fun `http to loopback only with the test flag`() {
        refused("http://127.0.0.1:8080/v1")
        assertEquals("http://127.0.0.1:8080/v1", EndpointPolicy.validate("http://127.0.0.1:8080/v1", allowInsecureLoopback = true))
        refused("http://evil.example.com/v1", loopback = true)
    }

    @Test fun `credentials, query and garbage are refused`() {
        refused("https://user:pass@api.z.ai/v4")
        refused("https://api.z.ai/v4?key=1")
        refused("not a url")
        refused("ftp://api.z.ai/v4")
        refused("")
    }
}
