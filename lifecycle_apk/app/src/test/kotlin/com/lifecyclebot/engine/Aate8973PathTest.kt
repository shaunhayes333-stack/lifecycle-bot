package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ResearchFollow8884
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8973 — the 4 h / 24 h reads carry the path's high and low, sampled every 15 min, not just the end price. */
class Aate8973PathTest {
    private val f = ResearchFollow8884

    @Test fun thePathIsTheHighAndLowSinceTheDecision() {
        val w = ResearchFollow8884.Wait8884(1L, "m", "QUALITY", false, "BASE_START", "NONE", 1.0, 30.0, 2e5, 5e4, 1.2, 1.00, "R", 0)
        assertTrue(f.pathPct8973(1.0, w.hiUsd8973, w.loUsd8973).first.isNaN())
        w.see8973(1.10, 10L); w.see8973(0.95, 20L); w.see8973(1.02, 30L); w.see8973(Double.NaN, 40L)
        val (hi, lo) = f.pathPct8973(w.entryUsd, w.hiUsd8973, w.loUsd8973)
        assertEquals(10.0, hi, 1e-9)
        assertEquals(-5.0, lo, 1e-9)
        assertEquals(30L, w.sampledAtMs8973)
        // the next horizon keeps the path from the decision
        val next = w.carry8973(w.copy(next = 1))
        assertEquals(1.10, next.hiUsd8973, 1e-12)
        assertEquals(0.95, next.loUsd8973, 1e-12)
        assertEquals("equality never moves with the path", w.copy(), ResearchFollow8884.Wait8884(1L, "m", "QUALITY", false, "BASE_START", "NONE", 1.0, 30.0, 2e5, 5e4, 1.2, 1.00, "R", 0))
    }

    @Test fun thePathSurvivesARestart() {
        val w = ResearchFollow8884.Wait8884(5L, "mint8973", "CRYPTO_CORE", true, "UNKNOWN", "NONE", 1.0, 50.0, 5e7, 2e6, 1.0, 2.0, "R", 1)
        w.see8973(2.4, 100L); w.see8973(1.9, 200L)
        val back = f.decode8884(f.encode8884(w))!!
        assertEquals(2.4, back.hiUsd8973, 1e-12); assertEquals(1.9, back.loUsd8973, 1e-12); assertEquals(200L, back.sampledAtMs8973)
        val old = f.decode8884("{\"t\":5,\"m\":\"x\",\"l\":\"L\",\"a\":false,\"st\":\"\",\"su\":\"\",\"e\":1.0,\"r\":\"\",\"n\":0}")!!
        assertTrue("a wait written before 8973 starts unsampled", old.hiUsd8973.isNaN() && old.sampledAtMs8973 == 0L)
    }

    @Test fun wired() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ResearchFollow8884.kt").readText()
        assertTrue(src.contains("try { samplePaths8973(nowMs) } catch (_: Throwable) { failed.incrementAndGet() }"))
        assertTrue(src.contains("if (r != null) peak8973 else Double.NaN, Double.NaN,"))
        assertTrue(src.contains("again += w.carry8973(w.copy(next = w.next + 1))"))
        assertEquals(15L * 60_000L, f.SAMPLE_GAP_MS_8973)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
            .contains("o.entryMcap, o.liqUsd8539, o.costPct, o.entryPrice, o.reason7967, o.peakPct, o.minPct7962,"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 158. The path, not just the end (8973)"))
    }
}
