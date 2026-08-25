package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalRawQuantityAuthority6520
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger

class RawQuantityAuthority6520Test {
    @Test fun `paper raw derives from decimal economics without ui quantity`() {
        val raw = CanonicalRawQuantityAuthority6520.paperRawFromEconomics("0.05", "200", "0.00001", 9)
        assertEquals(BigInteger("1000000000000000"), raw)
    }

    @Test fun `raw persistence preserves integers beyond Double and Long precision`() {
        val raw = BigInteger("999999999999999999999999999999999999")
        assertEquals(raw, CanonicalRawQuantityAuthority6520.parseStoredRaw(raw.toString()))
    }

    @Test fun `legacy one raw unit difference normalizes to canonical`() {
        val canonical = BigInteger("12345678901234567890")
        val v = CanonicalRawQuantityAuthority6520.normalizeLegacyJournalRaw(canonical + BigInteger.ONE, canonical)
        assertTrue(v.accepted)
        assertEquals(canonical, v.normalizedRaw)
        assertFalse(v.quarantine)
    }

    @Test fun `decimal scale errors quarantine and never normalize`() {
        val canonical = BigInteger("123456789")
        for (factor in listOf(10L, 100L, 1_000_000L, 1_000_000_000_000L)) {
            val v = CanonicalRawQuantityAuthority6520.normalizeLegacyJournalRaw(canonical * BigInteger.valueOf(factor), canonical)
            assertFalse(v.accepted)
            assertTrue(v.quarantine)
            assertEquals(canonical, v.normalizedRaw)
        }
    }
}
