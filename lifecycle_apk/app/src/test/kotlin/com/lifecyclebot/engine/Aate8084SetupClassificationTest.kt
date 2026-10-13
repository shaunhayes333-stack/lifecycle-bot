package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LaneEvidence8077
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LanePlaybook7907.F
import com.lifecyclebot.engine.cortex.LanePlaybook7907.Obs8084
import com.lifecyclebot.engine.cortex.LanePlaybook7907.Outcome8084
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8084 — setup classification: provenance, structured outcomes, one cache, decision-time capture, panel ranking. */
class Aate8084SetupClassificationTest {
    private val now = 1_800_000_000_000L
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun f(bp: Double = Double.NaN, obs: Map<String, Obs8084> = emptyMap()) =
        F(Double.NaN, Double.NaN, Double.NaN, Double.NaN, bp, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, Double.NaN, "", "").also { it.obs8084 = obs }
    private val allCore = LanePlaybook7907.CORE_8084.associateWith { Obs8084.OBSERVED }

    // 1 + 2 — a stamped 50/50 is a real balanced tape; an unstamped 50/50 pair is the default
    @Test fun buyPressureProvenance() {
        assertEquals(Obs8084.OBSERVED, LanePlaybook7907.bpObs8084(50.0, 50.0, now - 1_000L, now))
        assertEquals(Obs8084.DEFAULT_UNOBSERVED, LanePlaybook7907.bpObs8084(50.0, 50.0, 0L, now))
        assertEquals(Obs8084.OBSERVED, LanePlaybook7907.bpObs8084(62.0, 38.0, 0L, now))
        assertEquals(Obs8084.STALE, LanePlaybook7907.bpObs8084(60.0, 40.0, now - 60 * 60_000L, now))
        assertEquals(Obs8084.INVALID, LanePlaybook7907.bpObs8084(140.0, 0.0, now, now))
        // a real 0% move stays a reading; the unstamped 0/0 default does not
        assertEquals(Obs8084.OBSERVED, LanePlaybook7907.chgObs8084(0.0, 0.0, now - 500L, now))
        assertEquals(Obs8084.DEFAULT_UNOBSERVED, LanePlaybook7907.chgObs8084(0.0, 0.0, 0L, now))
    }

    // 3 — insufficient / stale / malformed candles, then hydrated
    @Test fun shapeHistoryProvenance() {
        fun c(t: Long, p: Double) = Candle(t, p, 0.0, 0.0, 0.0)
        assertEquals(Obs8084.UNKNOWN, LanePlaybook7907.shapeObs8084(listOf(c(now - 2_000, 1.0), c(now, 1.1)), now))
        val old = listOf(c(now - 60 * 60_000L, 1.0), c(now - 59 * 60_000L, 1.1), c(now - 58 * 60_000L, 1.2))
        assertEquals(Obs8084.STALE, LanePlaybook7907.shapeObs8084(old, now))
        assertEquals(Obs8084.INVALID, LanePlaybook7907.shapeObs8084(listOf(c(now - 30_000, 1.0), c(now - 30_000, 1.1), c(now, 1.2)), now))
        assertEquals(Obs8084.INVALID, LanePlaybook7907.shapeObs8084(listOf(c(now - 30_000, 1.0), c(now - 20_000, 5_000.0), c(now, 5_001.0)), now))
        val hydrated = old + listOf(c(now - 40_000, 1.3), c(now - 20_000, 1.4), c(now, 1.5))
        assertEquals(Obs8084.OBSERVED, LanePlaybook7907.shapeObs8084(hydrated, now))
    }

    // 4 — fresh buy-pressure evidence with an unchanged price re-classifies at once (no 15 s wait)
    @Test fun inputChangeInvalidatesCacheWithoutPriceChange() {
        val ts = TokenState(mint = "mint8084cache", symbol = "CACH")
        ts.lastPrice = 0.001; ts.lastPriceUpdate = now; ts.lastMcap = 50_000.0; ts.lastLiquidityUsd = 20_000.0
        val a = LanePlaybook7907.classify8084(ts, "SHITCOIN", now)!!
        assertSame(a, LanePlaybook7907.classify8084(ts, "SHITCOIN", now))            // one decision, one classification
        assertSame(a, LanePlaybook7907.classify8084(ts, "SHITCOIN", now + 1_000L))   // unchanged inputs reuse
        val k0 = LanePlaybook7907.inputKey8084(ts)
        ts.lastBuyPressurePct = 71.0; ts.lastSellPressurePct = 29.0; ts.buyPressureObservedAtMs8084 = now + 1_500L
        assertNotEquals(k0, LanePlaybook7907.inputKey8084(ts))
        val b = LanePlaybook7907.classify8084(ts, "SHITCOIN", now + 2_000L)!!
        assertNotEquals(a.featureVersion, b.featureVersion)
        ts.lastPriceChange5m = 6.0; ts.priceChangeObservedAtMs8084 = now + 2_500L
        assertNotEquals(b.featureVersion, LanePlaybook7907.classify8084(ts, "SHITCOIN", now + 3_000L)!!.featureVersion)
    }

    // 5 — several valid setups: the primary is losing, another is proven positive -> the proven one is chosen
    @Test fun provenAlternativeBeatsLosingPrimary() {
        val losing = LaneEvidence8077.Setup("LAUNCH_CONTINUATION", -6.0, 60.0, -6.0, 25.0)
        val proven = LaneEvidence8077.Setup("SPECIALIST_EDGE", 9.0, 40.0, 9.0, 100.0)
        assertEquals("SPECIALIST_EDGE", LaneEvidence8077.choose(listOf(losing, proven))!!.id)
        val c = LanePlaybook7907.decide8084("SHITCOIN", listOf("LAUNCH_CONTINUATION", "SPECIALIST_EDGE"), emptyList(), "SPECIALIST_EDGE",
            emptyList(), emptyList(), 1, now)
        assertEquals(Outcome8084.MATCHED, c.outcome); assertEquals(listOf("LAUNCH_CONTINUATION"), c.alternatives)
    }

    // 6 — fired setups that cannot be ranked are MATCHES_UNRANKABLE (fired list kept), never a plain no-match
    @Test fun unrankableMatchesKeepTheirSetups() {
        assertNull(LaneEvidence8077.choose(listOf(LaneEvidence8077.Setup("X", Double.NaN, 3.0, Double.NaN, Double.NaN))))
        val c = LanePlaybook7907.decide8084("SHITCOIN", listOf("X", "Y"), emptyList(), null, emptyList(), emptyList(), 1, now)
        assertEquals(Outcome8084.MATCHES_UNRANKABLE, c.outcome); assertEquals(listOf("X", "Y"), c.matched)
        // V5.0.8087 — unrankable matches are their own cohort, never pooled into NO_TRIGGER.
        assertEquals(LanePlaybook7907.UNRANKABLE_8087, c.tag); assertFalse(c.noMatchCohort); assertNull(c.consumerTag8087)
        // a "best" that never fired is not a selection
        assertEquals(Outcome8084.MATCHES_UNRANKABLE, LanePlaybook7907.decide8084("SHITCOIN", listOf("X"), emptyList(), "Z", emptyList(), emptyList(), 1, now).outcome)
    }

    // 7 — missing playbook vs genuine no-match vs incomplete data vs classifier error
    @Test fun outcomesAreDistinct() {
        assertEquals(Outcome8084.NO_PLAYBOOK, LanePlaybook7907.evaluate8084("NOT_A_LANE", f(obs = allCore), 1, now).outcome)
        assertNull(LanePlaybook7907.evaluate8084("NOT_A_LANE", f(obs = allCore), 1, now).tag)
        val noMatch = LanePlaybook7907.evaluate8084("DIP_HUNTER", f(obs = allCore), 1, now)
        assertEquals(Outcome8084.NO_MATCH, noMatch.outcome); assertEquals(LanePlaybook7907.NO_TRIGGER, noMatch.tag)
        val incomplete = LanePlaybook7907.evaluate8084("DIP_HUNTER", f(obs = allCore + ("bp" to Obs8084.DEFAULT_UNOBSERVED)), 1, now)
        assertEquals(Outcome8084.DATA_INCOMPLETE, incomplete.outcome)
        assertTrue(incomplete.reason.contains("bp:DEFAULT_UNOBSERVED")); assertFalse(incomplete.noMatchCohort)
        val err = LanePlaybook7907.decide8084("DIP_HUNTER", emptyList(), listOf("BROKEN"), null, emptyList(), emptyList(), 1, now)
        assertEquals(Outcome8084.CLASSIFIER_ERROR, err.outcome); assertFalse(err.noMatchCohort)
    }

    // 8 — the cache holds the whole classification: alternatives cannot drift from the chosen tag
    @Test fun oneAtomicCacheEntry() {
        val p = src("engine/cortex/LanePlaybook7907.kt")
        assertFalse(p.contains("private val matchCache"))
        assertTrue(p.contains("private class Cached8084(val inputKey: Long, val revision: Long, val featuresAt: Long, val pinnedAt: Long, val c: Classification8084)"))
        val m = p.substringAfter("fun matchedSetups8083(").substringBefore("private const val RUNNER_SETUP_MEAN_8056")
        assertTrue(m.contains("(listOfNotNull(c.chosen) + c.matched)"))
    }

    // 9 + 10 — decision-time capture survives the async queue and restarts; grades only what the decision saw
    @Test fun captureIsFrozenAtDecisionTime() {
        val c = src("engine/cortex/Cortex7885.kt")
        val cap = c.substringAfter("fun capture(ts: TokenState, labelLane: String, admitted: Boolean").substringBefore("private fun captureNow(")
        assertTrue(cap.indexOf("decisionSnapshot8084(ts, labelLane, nowMs)") in 0 until cap.indexOf("submit(\"CAPTURE\")"))
        assertTrue(c.contains("LanePlaybook7907.capture(ts, a.lane, labelLane, nowMs, snap8084)"))
        assertTrue(c.contains("LanePlaybook7907.onLabel(mint, labelLane, netPct, grossPct, fam8080, p.setup7961)"))
        val p = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(p.contains("\"#O=\${c.outcome.name}#V=\${c.featureVersion}\""))
        val on = p.substringAfter("fun onLabel(mint: String, labelLane: String").substringBefore("fun setupHoldsLong8080(")
        assertTrue(on.contains("val tag = full.substringBefore('#')"))     // a pre-8084 persisted tag grades as before
        assertTrue(on.contains("SETUP_DECISION_IDENTITY_MISMATCH_8084"))
        assertTrue(on.contains("Outcome8084.DATA_INCOMPLETE.name"))         // incomplete data never grades the no-match cohort
    }

    // 11 — twelve n500 NO_TRIGGER cohorts cannot hide a lower-sample proven setup
    @Test fun panelRanksTheWholeSet() {
        val rows = (1..12).map { LanePlaybook7907.SetupRow8069("L$it", LanePlaybook7907.NO_TRIGGER, 500, 1.0, 0.2, false, 0.8, "DEVELOPING", true) } +
            (1..14).map { LanePlaybook7907.SetupRow8069("CORE", "BIG_$it", 400, 0.5, 0.3, false, 0.2, "DEVELOPING", false) } +
            LanePlaybook7907.SetupRow8069("SHITCOIN", "SPECIALIST_EDGE", 40, 12.0, 3.0, true, 9.0, "PROVEN", false) +
            LanePlaybook7907.SetupRow8069("MOONSHOT", "LOSER", 80, -7.0, 1.0, false, -8.0, "NEGATIVE", false)
        val out = LanePlaybook7907.rankRows8084(rows, 14)
        assertEquals("SPECIALIST_EDGE", out.first().setup)
        assertTrue(out.none { it.noTrigger }); assertTrue(out.any { it.setup == "LOSER" })
        assertTrue(out.size <= 14)
        assertEquals("PROVEN", LanePlaybook7907.tier8084(40.0, 12.0, 3.0))
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/LearningCounterActivity.kt").readText()
        assertFalse(ui.contains("PROVEN — live money allowed"))
        assertTrue(ui.contains("PROVEN on forward labels (not realised live P&L)"))
        assertTrue(ui.contains("noTriggerRows8084(6)"))
    }

    // P0.2 — writers stamp measured inputs; a no-trade window no longer overwrites a measured tape with 50/50
    @Test fun writersStampObservations() {
        val d = src("engine/DataOrchestrator.kt")
        assertFalse(d.contains("ts.lastBuyPressurePct = if (txns5m > 0) (buys5m.toDouble() / txns5m) * 100 else 50.0"))
        assertTrue(d.contains("ts.buyPressureObservedAtMs8084 = System.currentTimeMillis()"))
        assertTrue(src("engine/BotService.kt").contains("ts.priceChangeObservedAtMs8084 = System.currentTimeMillis()"))
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(g.contains("LanePlaybook7907.liveRefusal(ts, l, nowMs)"))
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("if (c8084.outcome != Outcome8084.MATCHED && c8084.outcome != Outcome8084.NO_MATCH) return null"))
    }
}
