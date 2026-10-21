package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.FeatureKind8283
import com.lifecyclebot.engine.truth.FeatureRevision8283
import com.lifecyclebot.engine.truth.PairFlow8169
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8283 — P0 shared feature cache invalidation (FeatureRevision8283) and P4 lifecycle-aware data requirements:
 * fresh provider evidence is consumed on the next evaluation without waiting for the 5 s setup cache, and a genuine
 * early-launch setup is judged on its own launch evidence with its unavailable history recorded, while critical
 * unknowns (identity, executable price, route, liquidity, sellability, economics, wallet balance) stay binding.
 */
class Aate8283FeatureCacheLifecycleTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun mint(tag: String) = ("M8283" + tag + "x".repeat(40)).take(44)

    private fun row(mint: String, observedAt: Long, liq: Double = 30_000.0, buys: Int = 40, sells: Int = 20) = MarketSweep7297.Row(
        mint = mint, symbol = "ROW", name = "row", priceUsd = 0.002, mcapUsd = 80_000.0, liquidityUsd = liq,
        volumeH1Usd = 10_000.0, volumeH24Usd = 50_000.0, priceChangeH1Pct = 12.0, txCountH1 = 60, holders = 0,
        organicScore = 0.0, verified = false, ageHours = 2.0, providers = setOf("JUP_TRENDING"),
        priceChange5mPct = 3.0, buysH1 = buys, sellsH1 = sells, observedAtMs8169 = observedAt,
    )

    // P0 — the directive's test: a fresh pair observation is read by the very next evaluation, inside the 5 s reuse window.
    @Test fun freshPairEvidenceIsConsumedOnTheNextEvaluationWithoutWaitingForTheCache() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("pair")
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = m, symbol = "FRSH")
        ts.lastPrice = 0.001; ts.lastPriceUpdate = now; ts.lastMcap = 50_000.0; ts.lastLiquidityUsd = 20_000.0
        val a = LanePlaybook7907.classify8084(ts, "SHITCOIN", now)!!
        assertTrue(a.missing.toString(), a.missing.any { it.startsWith("bp:") })
        assertSame(a, LanePlaybook7907.classify8084(ts, "SHITCOIN", now + 500L))   // unchanged evidence: the cache serves
        val k0 = LanePlaybook7907.inputKey8084(ts)
        PairFlow8169.note8169(m, PairFlow8169.Flow8169(30, 10, -1, -1, 4.0, 12.0, 25_000.0, now + 600L))
        assertNotEquals(k0, LanePlaybook7907.inputKey8084(ts))                      // the revision is part of the identity
        val b = LanePlaybook7907.classify8084(ts, "SHITCOIN", now + 1_000L)!!     // well inside EXTERNAL_TTL_MS_8084
        assertNotSame(a, b)
        assertFalse(b.missing.toString(), b.missing.any { it.startsWith("bp:") })
        assertFalse(b.missing.toString(), b.missing.any { it.startsWith("chg:") })
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.POOL_FLOW) > 0L)
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.LIQUIDITY) > 0L)
        assertTrue(FeatureRevision8283.statusLine8283(), FeatureRevision8283.statusLine8283().contains("cacheInvalidatedAfterEvidence=1"))
    }

    // P0 — evidenceArrived8252 invalidates setup classifications too; non-feature evidence (safety) leaves the revision.
    @Test fun evidenceArrivalAdvancesTheSharedRevisionPerKind() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("arrive")
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = m, symbol = "ARRV")
        val k0 = LanePlaybook7907.inputKey8084(ts)
        CandidateAdmission8136.evidenceArrived8252(m, setOf("safety", "freeze", "authority"), now)
        assertEquals(0L, FeatureRevision8283.revision8283(m))
        assertEquals(k0, LanePlaybook7907.inputKey8084(ts))
        CandidateAdmission8136.evidenceArrived8252(m, setOf("shape"), now)
        val r1 = FeatureRevision8283.revision8283(m)
        assertTrue(r1 > 0L)
        assertEquals(r1, FeatureRevision8283.kindRevision8283(m, FeatureKind8283.SHAPE))
        assertEquals(0L, FeatureRevision8283.kindRevision8283(m, FeatureKind8283.TAPE))
        assertNotEquals(k0, LanePlaybook7907.inputKey8084(ts))
        CandidateAdmission8136.evidenceArrived8252(m, CandidateAdmission8136.TAPE_FEATURES_8252, now + 1L)
        assertTrue(FeatureRevision8283.revision8283(m) > r1)
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.TAPE) > 0L)
        // the revision is shared by every specialist: one mint, one number, whatever lane reads it
        assertEquals(setOf(FeatureKind8283.POOL_FLOW, FeatureKind8283.LIQUIDITY), FeatureRevision8283.kindsOf8283(CandidateAdmission8136.PAIR_FEATURES_8252))
    }

    // P0 — the coordination API: bump wakes the held defer that waits on it; a burst is coalesced per mint + kind.
    @Test fun bumpWakesTheHeldDeferAndCoalescesABurst() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("bump")
        val now = System.currentTimeMillis()
        CandidateAdmission8136.holdDeferForTest8252(m, "SHITCOIN", listOf("tape:NO_PRINTS", "shape:UNKNOWN"), now)
        assertFalse(CandidateAdmission8136.reevalDueForTest8252(m, "tape:NO_PRINTS", now + 100L))
        FeatureRevision8283.bump8283(m, FeatureKind8283.TAPE, now + 100L)
        assertTrue(CandidateAdmission8136.reevalDueForTest8252(m, "tape:NO_PRINTS", now + 100L))
        assertFalse(CandidateAdmission8136.reevalDueForTest8252(m, "shape:UNKNOWN", now + 100L))
        val r1 = FeatureRevision8283.revision8283(m)
        FeatureRevision8283.bump8283(m, FeatureKind8283.TAPE, now + 150L)     // same kind inside the coalesce gap
        assertTrue(FeatureRevision8283.revision8283(m) > r1)                   // still a new revision
        assertTrue(FeatureRevision8283.statusLine8283(), FeatureRevision8283.statusLine8283().contains("coalesced=1"))
        FeatureRevision8283.bump(m, FeatureKind8283.SHAPE)                     // the public signature
        assertTrue(FeatureRevision8283.kindRevision8283(m, FeatureKind8283.SHAPE) > 0L)
        assertEquals(0L, FeatureRevision8283.revision8283(""))
    }

    // P0 — a sweep row advances its mint only when it is a NEW observation carrying that family.
    @Test fun sweepRowsAdvanceOnlyOnNewObservations() {
        FeatureRevision8283.clearForTest8283()
        val m = mint("sweep")
        val t = System.currentTimeMillis()
        val first = row(m, t - 60_000L)
        assertEquals(setOf(FeatureKind8283.PRICE, FeatureKind8283.POOL_FLOW, FeatureKind8283.LIQUIDITY), FeatureRevision8283.sweepKinds8283(null, first))
        assertTrue(FeatureRevision8283.sweepKinds8283(first, first).isEmpty())          // carried row: nothing new
        assertTrue(FeatureRevision8283.sweepKinds8283(first, row(m, 0L)).isEmpty())     // undated row: not an observation
        assertEquals(1, FeatureRevision8283.noteSweepRows8283(null, listOf(first), t))
        val r1 = FeatureRevision8283.revision8283(m)
        assertEquals(0, FeatureRevision8283.noteSweepRows8283(listOf(first), listOf(first), t))
        assertEquals(r1, FeatureRevision8283.revision8283(m))
        val newer = row(m, t, liq = Double.NaN, buys = -1, sells = -1).copy(priceChange5mPct = Double.NaN)
        assertEquals(setOf(FeatureKind8283.PRICE), FeatureRevision8283.sweepKinds8283(first, newer))
        assertEquals(1, FeatureRevision8283.noteSweepRows8283(listOf(first), listOf(newer), t))
        assertTrue(FeatureRevision8283.revision8283(m) > r1)
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("FeatureRevision8283.noteSweepRows8283(previous7777?.rows, snap.rows, snap.atMs)"))
    }

    // P0 — sealed tickets are untouched: the revision never reaches a seal; the unsealed-evidence invalidation stays as it was.
    @Test fun theRevisionNeverTouchesASealedTicket() {
        val rev = src("engine/truth/FeatureRevision8283.kt")
        assertFalse(rev.contains("invalidateUnsealedEvidence8253"))
        assertFalse(rev.contains("LaneExecutionCoordinator."))
        val adm = src("engine/truth/CandidateAdmission8136.kt").substringAfter("fun evidenceArrived8252(").substringBefore("if (held8144.isEmpty()) return")
        assertTrue(adm.contains("FeatureRevision8283.advance8283(mint, kinds8283 ?: FeatureRevision8283.kindsOf8283(features), nowMs)"))
        assertTrue(adm.contains("com.lifecyclebot.engine.LaneExecutionCoordinator.invalidateUnsealedEvidence8253(mint)"))
    }
}
