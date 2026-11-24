package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BaseBreakout8995
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8995 — past a coin's first hour the base breakout is proven forward: a fire is a virtual position on the runner line,
 * its closes are kept per venue class and setting, and only a proven record's fire is evidence.
 */
class Aate8995BaseBreakoutForwardTest {
    private val b = BaseBreakout8995
    private val now = 1_800_000_000_000L

    @Test fun theRunnerLine() {
        assertEquals(0.80, b.line8995(1.0, 1.0), 1e-9)          // the stop
        assertEquals(0.80, b.line8995(1.0, 1.09), 1e-9)         // under +10 %: still the stop
        assertEquals(1.05, b.line8995(1.0, 1.10), 1e-9)         // +10 %: never under +5 %
        assertEquals(1.10, b.line8995(1.0, 1.30), 1e-9)         // +30 %: the peak less the stop distance (+10) under half the gain (+15)
        assertEquals(1.30, b.line8995(1.0, 1.60), 1e-9)         // +60 %: half the gain (+30) under the peak less the stop (+40)
        assertEquals(1.55, b.line8995(1.0, 2.00), 1e-9)         // 2x: 45 % given back
        assertEquals("CURVE", b.cohort8995(pumpMint = true, graduated = false))
        assertEquals("PUMPSWAP", b.cohort8995(pumpMint = true, graduated = true))
        assertEquals("OTHER", b.cohort8995(pumpMint = false, graduated = false))
    }

    @Test fun aVirtualPositionEntersAfterTheLatencyAndLeavesOnTheLine() {
        val v = BaseBreakout8995.Virtual8995("B8994W", "PUMPSWAP", enterAfterMs = now + 10_000L)
        assertNull("before the latency a print is only watched", b.step8995(v, 1.00, now + 2_000L))
        assertEquals(0.0, v.entry, 0.0)
        assertNull(b.step8995(v, 1.04, now + 11_000L))            // the entry
        assertEquals(1.04, v.entry, 1e-12)
        assertNull(b.step8995(v, 1.30, now + 20_000L))            // +25 %
        assertNull(b.step8995(v, 1.20, now + 30_000L))            // above the line (+5 % = 1.092)
        val out = b.step8995(v, 1.09, now + 40_000L)!!            // through the line: filled at the print
        assertEquals((1.09 / 1.04 - 1.0) * 100.0, out, 1e-9)
        val s = BaseBreakout8995.Virtual8995("B8994W", "OTHER", enterAfterMs = now)
        b.step8995(s, 1.0, now)
        assertEquals("the stop, at the print that crossed it", -25.0, b.step8995(s, 0.75, now + 5_000L)!!, 1e-9)
        val t = BaseBreakout8995.Virtual8995("B8994T", "OTHER", enterAfterMs = now)
        b.step8995(t, 1.0, now)
        assertNotNull("an hour held: closed at the print", b.step8995(t, 1.02, now + BaseBreakout8995.MAX_HOLD_MS_8995))
    }

    @Test fun onlyAProvenForwardRecordArms() {
        b.resetForTest8995()
        val key = "PUMPSWAP|B8994W"
        for (i in 0 until 60) b.fileForTest8995(key, now + i, 12.0 + (i % 5))
        assertFalse("60 closes: not yet", b.armForTest8995("MintA", key, now))
        assertNull(b.evidence8995("MintA", now))
        for (i in 60 until 120) b.fileForTest8995(key, now + i, 12.0 + (i % 5))
        assertTrue(b.armForTest8995("MintA", key, now))
        val e = b.evidence8995("MintA", now + 1_000L)!!
        assertEquals(BaseBreakout8995.KIND_8995, e.kind); assertEquals(key, e.id); assertEquals(120, e.n)
        assertNull("the fire is evidence for two minutes", b.evidence8995("MintA", now + BaseBreakout8995.EVIDENCE_TTL_MS_8995 + 1L))
        // a losing record never arms
        val bad = "OTHER|B8994T"
        for (i in 0 until 120) b.fileForTest8995(bad, now + i, -6.0 + (i % 5))
        assertFalse(b.armForTest8995("MintB", bad, now))
        // the record survives a restart
        val raw = b.encode8995()
        b.resetForTest8995(); b.decode8995(raw)
        assertEquals(raw, b.encode8995())
        assertTrue(b.statusLine8995().contains("PUMPSWAP|B8994W n120"))
        assertTrue(b.statusLine8995().contains(" ARMED"))
        b.resetForTest8995()
    }

    @Test fun wired() {
        fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/engine/$p").readText()
        assertTrue(src("truth/TradeTape8100.kt").contains("BaseBreakout8995.onPrint8995(mint, eventMs, recvMs, priceSol, isBuy, sol)"))
        assertTrue(src("truth/CandidateAdmission8136.kt").contains("yield(BaseBreakout8995.evidence8995(ts.mint, nowMs))"))
        assertTrue(LaneAuthority8577.answersPriors8874(BaseBreakout8995.KIND_8995))
        assertTrue(src("DiagnosisBrief8438.kt").contains("BaseBreakout8995.statusLine8995()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 180. The base breakout past the first hour (8995)"))
    }
}
