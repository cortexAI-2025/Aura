package com.aura.ai.brain

import org.junit.Assert.*
import org.junit.Test

class LocalCompatibilityTest {
    private val gb = 1024L * 1024 * 1024
    private fun a(model: Long?, total: Long, avail: Long, bits64: Boolean = true, storage: Long? = null) =
        LocalCompatibility.assess(model, total, avail, bits64, storage)

    @Test fun `no model means local mode is not presented as working`() {
        val r = a(null, 4 * gb, 2 * gb)
        assertEquals(LocalVerdict.NO_MODEL, r.verdict)
        assertTrue(r.message.contains("pas utilisable"))
    }

    @Test fun `plenty of free memory is ok`() = assertEquals(LocalVerdict.OK, a(gb + 400_000_000, 8 * gb, 5 * gb).verdict)

    @Test fun `a galaxy a14 class phone with little free ram is tight or worse, never silently ok`() {
        val tight = a(1_400_000_000, 4 * gb, (2.2 * gb).toLong())
        assertTrue(tight.verdict == LocalVerdict.TIGHT || tight.verdict == LocalVerdict.INSUFFICIENT)
        assertEquals(LocalVerdict.INSUFFICIENT, a(1_400_000_000, 4 * gb, 1 * gb).verdict)
    }

    @Test fun `under 3 gb of total ram is insufficient`() = assertEquals(LocalVerdict.INSUFFICIENT, a(gb, 2 * gb, 2 * gb).verdict)

    @Test fun `32 bit phones and full storage are insufficient`() {
        assertEquals(LocalVerdict.INSUFFICIENT, a(gb, 6 * gb, 4 * gb, bits64 = false).verdict)
        assertEquals(LocalVerdict.INSUFFICIENT, a(gb, 6 * gb, 4 * gb, storage = 10_000_000).verdict)
    }

    @Test fun `message states the numbers behind the estimate`() {
        val m = a(gb, 6 * gb, 4 * gb).message
        assertTrue(m.contains("RAM totale")); assertTrue(m.contains("besoin estimé"))
    }
}
