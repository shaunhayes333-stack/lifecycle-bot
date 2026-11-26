package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalFinalityPersistence6486
import com.lifecyclebot.network.KeylessRpc8359
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8899 — two heap holders the 21 Nov 01:08 heap map named: the per-mint RPC cache kept every expired answer until its
 * 4,000 cap (15.3 MB), and the finality store kept one String per excluded close for a handful of reasons.
 */
class Aate8899HeapHoldersTest {
    @Test fun expiredAnswersAreSweptAndLiveOnesStillServe() {
        val ttl = KeylessRpc8359.LARGEST_TTL_MS_8359
        val c = KeylessRpc8359.MintTtlCache8359(ttl)
        val v = JSONObject().put("value", 1)
        for (i in 0 until 500) c.put8359("OLD$i", v, 1_000L)
        assertEquals(500, c.size8359())
        c.put8359("NEW", v, 1_000L + ttl)
        assertEquals("the sweep a TTL later drops every expired answer", 1, c.size8359())
        assertNotNull(c.get8359("NEW", 1_000L + ttl + 59_000L))
        assertNull(c.get8359("OLD1", 1_000L + ttl))
        // inside one TTL nothing is swept: a live answer is never dropped early
        c.put8359("B", v, 1_000L + ttl + 10_000L)
        assertEquals(2, c.size8359())
        assertFalse(KeylessRpc8359.sweepDue8899(100_000L, 100_000L + ttl - 1, ttl))
        assertTrue(KeylessRpc8359.sweepDue8899(100_000L, 100_000L + ttl, ttl))
        assertTrue("clock moved back", KeylessRpc8359.sweepDue8899(100_000L, 99_000L, ttl))
    }

    @Test fun oneStringPerExclusionReason() {
        val raw = JSONObject().put("a", org.json.JSONArray()).put("x", JSONObject()
            .put("T1", "PAPER_NOT_LIVE_COST_EVIDENCE").put("T2", "PAPER_NOT_LIVE_COST_EVIDENCE")).toString()
        val (_, ex) = CanonicalFinalityPersistence6486.decodeConsumer8094(raw)
        assertEquals("PAPER_NOT_LIVE_COST_EVIDENCE", ex["T1"])
        assertSame(ex["T1"], ex["T2"])
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalityPersistence6486.kt").readText()
        // superseded by V5.0.9029 (IdPool9029: the trade id is held once too); the reason is still interned
        assertTrue(src.contains(".put(IdPool9029.canon9029(tradeId), reason.intern())  // V5.0.8899"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 88. What held the heap (8899)"))
    }
}
