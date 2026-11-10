package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.engine.truth.TokenFacts.Field
import com.lifecyclebot.engine.truth.TokenFacts.Rank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8553 — owner: "build out the remaining data fixes now as a bundle. ensure its wired out correctly". DATA_MAP phases
 * 3-9 in one store: buy / sell pressure, 5 m / 1 h change, market cap, FDV, top-holder share and transaction velocity are
 * snapshot facts of TokenFacts (one writer, one rule, own observation time); graduation is one sticky marker for both of its
 * readers (D8); age has one rule (D4); the edge dataset's dead features (txVel 559/559 NaN, volAccel 362 default zeros,
 * phase "" for 418) read real values or say what they are.
 */
class Aate8553DataBundleTest {
    private val root = File("src/main/kotlin/com/lifecyclebot")
    private fun src(p: String) = File(root, p).readText()

    @Test fun eachFieldKnowsWhatAReadingIs() {
        assertTrue(TokenFacts.validFor(Field.CHANGE_5M_PCT, 0.0)); assertTrue(TokenFacts.validFor(Field.CHANGE_5M_PCT, -42.0))
        assertFalse(TokenFacts.validFor(Field.CHANGE_5M_PCT, Double.NaN)); assertFalse(TokenFacts.validFor(Field.CHANGE_1H_PCT, -100.0))
        assertTrue(TokenFacts.validFor(Field.BUY_PRESSURE_PCT, 0.0)); assertTrue(TokenFacts.validFor(Field.BUY_PRESSURE_PCT, 100.0))
        assertFalse(TokenFacts.validFor(Field.BUY_PRESSURE_PCT, 100.5))
        assertFalse("a 0 cap is no reading", TokenFacts.validFor(Field.MCAP_USD, 0.0))
        // superseded (8557): a measured 0 % top-holder share is a reading (both slots are nullable; Aate8527 witness)
        assertTrue(TokenFacts.validFor(Field.TOP_HOLDER_PCT, 0.0)); assertTrue(TokenFacts.validFor(Field.TOP_HOLDER_PCT, 12.5))
        assertFalse(TokenFacts.validFor(Field.TOP_HOLDER_PCT, -1.0)); assertFalse(TokenFacts.validFor(Field.TOP_HOLDER_PCT, 100.5))
        assertTrue(TokenFacts.validFor(Field.TX_PER_MIN, 0.0))
    }

    @Test fun snapshotFactsFollowTheRuleAndMirrorTheirStamps() {
        val ts = TokenState(mint = "mint8553", symbol = "SNAP")
        assertNull("an unstamped 50/50 default is not a reading", TokenFacts.snapshotFact(ts, Field.BUY_PRESSURE_PCT))
        assertTrue(TokenFacts.writeSnapshot(ts, Field.BUY_PRESSURE_PCT, 70.0, "TAPE_FLOW_7401", Rank.CHAIN, 100_000L, 100_000L))
        assertEquals(70.0, ts.lastBuyPressurePct, 0.0); assertEquals(100_000L, ts.buyPressureObservedAtMs8084)
        assertFalse("a cached pair poll older than the tape is refused",
            TokenFacts.writeSnapshot(ts, Field.BUY_PRESSURE_PCT, 40.0, "DEX_PAIR_POLL_M5", Rank.PROVIDER, 90_000L, 101_000L))
        assertFalse("a newer poll waits out the tape's hold",
            TokenFacts.writeSnapshot(ts, Field.BUY_PRESSURE_PCT, 40.0, "DEX_PAIR_POLL_M5", Rank.PROVIDER, 130_000L, 130_000L))
        assertEquals(100_000L, ts.buyPressureObservedAtMs8084)
        assertTrue(TokenFacts.writeSnapshot(ts, Field.CHANGE_5M_PCT, -3.0, "DEX_PAIR_POLL", Rank.PROVIDER, 120_000L, 125_000L))
        assertEquals("the stamp is the pair's fetch time, not now", 120_000L, ts.priceChangeObservedAtMs8084)
        assertTrue(TokenFacts.writeSnapshot(ts, Field.MCAP_USD, 5.0e6, "DEX_PAIR_POLL", Rank.PROVIDER, 120_000L, 120_000L))
        assertEquals(5.0e6, ts.lastMcap, 0.0); assertEquals(5.0e6, ts.tokenMap.marketCap!!, 0.0)
        assertFalse("an archive never replaces an observed cap", TokenFacts.restoreMcap(ts, 9.0e6))
        assertFalse(TokenFacts.writeSnapshot(ts, Field.MCAP_USD, 0.0, "DEXSCREENER_WS", Rank.PROVIDER, 200_000L, 200_000L))
        assertTrue(TokenFacts.writeSnapshot(ts, Field.TOP_HOLDER_PCT, 22.0, "SAFETY_CHECK", Rank.PROVIDER, 120_000L, 120_000L))
        assertEquals(22.0, ts.topHolderPct!!, 0.0); assertEquals(22.0, ts.tokenMap.topHolderConcentrationPct!!, 0.0)
        assertEquals(22.0, TokenFacts.topHolderPct(ts)!!, 0.0)
        val fresh = TokenState(mint = "mint8553b")
        assertTrue(TokenFacts.restoreMcap(fresh, 1.0e6)); assertTrue(TokenFacts.restoreFdv(fresh, 2.0e6))
        assertTrue(TokenFacts.statusLine().contains("snapshot{"))
    }

    @Test fun txVelocityHasARealSource() {
        val ts = TokenState(mint = "mint8553tx")
        val now = System.currentTimeMillis()
        assertTrue(TokenFacts.writeSnapshot(ts, Field.TX_PER_MIN, 4.2, "DEX_PAIR_POLL_M5", Rank.PROVIDER, now, now))
        assertEquals(4.2, TokenFacts.txPerMin(ts, now), 1e-9)
        assertTrue("a stale count is not velocity", TokenFacts.txPerMin(ts, now + 6L * 60_000L).isNaN())
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("txVelocity = try { com.lifecyclebot.engine.truth.TokenFacts.txPerMin(ts, nowMs) }"))
        assertTrue(src("engine/cortex/CortexVoters7885.kt").contains("com.lifecyclebot.engine.truth.TokenFacts.txPerMin(ts).takeIf { it.isFinite() }?.let { fin(it) }"))
        assertTrue(src("engine/BotService.kt").contains("TokenFacts.writeSnapshot(ts, TokenFacts.Field.TX_PER_MIN, m5 / 5.0, \"DEX_PAIR_POLL_M5\", TokenFacts.Rank.PROVIDER, evidenceAt8553)"))
    }

    @Test fun aSingleObservedSwapDoesNotOutrankTheCanonicalTransactionCount() {
        val ts = TokenState(mint = "velocity-source-order")
        val now = System.currentTimeMillis()
        com.lifecyclebot.engine.market.BuyerFlow8666.note(ts.mint, "buyer", 1.0, true, now)
        assertEquals(1.0, TokenFacts.txPerMin(ts, now), 0.0)
        assertTrue(TokenFacts.writeSnapshot(ts, Field.TX_PER_MIN, 9.0, "DEX_PAIR_POLL_M5", Rank.PROVIDER, now, now))
        assertEquals(9.0, TokenFacts.txPerMin(ts, now), 0.0)
    }

    @Test fun theDatasetSaysWhatItDoesNotKnow() {
        assertEquals("MATURE", EdgeModel8537.phaseOf8553(240.0))
        assertEquals("UNKNOWN_AGE", EdgeModel8537.phaseOf8553(Double.NaN))
        assertEquals("UNKNOWN_AGE", EdgeModel8537.phaseOf8553(12.0))
        val lp = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(lp.contains("getMomentum(ts.mint)?.takeIf { it.volumeAccelObserved8553 }?.volumeAccelerationScore"))
        assertTrue(src("engine/MomentumPredictorAI.kt").contains("momentum.volumeAccelObserved8553 = earlierVol > 0"))
    }

    // D8 — graduation is one sticky marker for the curve readers and the router
    @Test fun graduationIsOneTruth() {
        val ts = TokenState(mint = "Grad8553AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump")
        assertTrue(TokenFacts.markGraduated(ts.mint, "CURVE_ACCOUNT_COMPLETE", ts))
        assertTrue(ts.tokenMap.migratedOrGraduated)
        assertTrue(com.lifecyclebot.network.PumpCurveKeys7269.isGraduated7392(ts.mint))
        assertFalse("sticky", TokenFacts.markGraduated(ts.mint, "CURVE_ACCOUNT_COMPLETE", ts))
        assertTrue(src("engine/sell/SellRouteVenueProbe8144.kt").contains("TokenFacts.markGraduated(mint, \"CURVE_ACCOUNT_COMPLETE\")"))
        assertTrue(src("network/ParallelMarkFanout7088.kt").contains("TokenFacts.markGraduated(mint, \"CURVE_ACCOUNT_COMPLETE\")"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("TokenFacts.markGraduated(mint, \"DEX_WS_MIGRATION\", ts)"))
        // the only direct writers left: the store, and the router's venue inference (which does not tell the curve readers)
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            f.readLines().forEachIndexed { i, l ->
                val c = l.substringBefore("//").let { x -> if (x.trimStart().startsWith("*") || x.trimStart().startsWith("/*")) "" else x }
                if (Regex("""migratedOrGraduated\s*=(?!=)""").containsMatchIn(c) && rel !in setOf("engine/truth/TokenFacts.kt", "engine/RouteTruthHydrator.kt")) bad += "$rel:${i + 1}"
                if (c.contains("markGraduated7392(") && !c.contains("fun markGraduated7392") && rel != "engine/truth/TokenFacts.kt") bad += "$rel:${i + 1}"
            }
        }
        assertTrue("graduation written around the store: $bad", bad.isEmpty())
    }

    // D4 — one age
    @Test fun everyAgeReaderUsesTheSameRule() {
        val b = src("engine/truth/CanonicalTokenBirthTime7440.kt")
        assertTrue(b.contains("fun resolvedAgeMinutes(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double? =\n        launchAgeMs7767(ts, nowMs)?.div(60_000.0)"))
        assertTrue(src("engine/truth/LaunchPhaseAuthority7401.kt").contains("fun resolvedAgeMs(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Long? =\n        CanonicalTokenBirthTime7440.launchAgeMs7767(ts, nowMs)"))
    }

    // D3 — one top-holder reader
    @Test fun oneTopHolderReader() {
        assertTrue(src("engine/Executor.kt").contains("val topHolderPct6326 = (com.lifecyclebot.engine.truth.TokenFacts.topHolderPct(ts) ?: 0.0)"))
        assertTrue(src("engine/Executor.kt").contains("topHolderConcentrationPct = com.lifecyclebot.engine.truth.TokenFacts.topHolderPct(ts),"))
        assertTrue(src("engine/truth/AdaptiveEntryWitness8527.kt").contains("put(\"holderConc\", TokenFacts.topHolderPct(ts)) { it in 0.0..100.0 }"))
        assertTrue(src("engine/CanonicalFeaturesBuilder.kt").contains("com.lifecyclebot.engine.truth.TokenFacts.topHolderPct(ts) ?: ts.safety.topHolderPct.takeIf { it >= 0.0 }"))
    }

    /** The guard: none of the snapshot fields is written around the store. */
    @Test fun noDirectSnapshotWritesOutsideTheStore() {
        val fields = listOf("lastBuyPressurePct", "lastSellPressurePct", "lastPriceChange5m", "lastPriceChange1h", "buyPressureObservedAtMs8084",
            "priceChangeObservedAtMs8084", "hourChangeObservedAtMs8506", "lastMcap", "lastFdv", "topHolderPct")
        val rx = Regex("""\.(${fields.joinToString("|")})\s*=(?!=)""")
        val map = Regex("""(tokenMap|\btm)\.(marketCap|fdv|topHolderConcentrationPct)\s*=(?!=)""")
        val allowed = setOf("engine/truth/TokenFacts.kt", "perps/CryptoLaneDesk7391.kt", "perps/PerpsUnifiedScorerBridge.kt",
            "engine/TokenMetaCache.kt",                    // its cache entry
            "engine/cortex/CortexDecisionSnapshot8522.kt") // a frozen copy of a decision's row
        val mapAllowed = setOf("engine/truth/TokenFacts.kt", "data/Models.kt", "engine/TokenMapAuthority.kt", "engine/RouteTruthHydrator.kt")
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            if (rel.startsWith("ui/")) return@forEach
            f.readLines().forEachIndexed { i, l ->
                val c = l.substringBefore("//").let { x -> if (x.trimStart().startsWith("*") || x.trimStart().startsWith("/*")) "" else x }
                if (rx.containsMatchIn(c) && rel !in allowed) bad += "$rel:${i + 1}"
                if (map.containsMatchIn(c) && rel !in mapAllowed) bad += "$rel:${i + 1}"
            }
        }
        assertTrue("snapshot fields written around TokenFacts: $bad", bad.isEmpty())
    }

    @Test fun theMapRecordsTheBundle() {
        val d = File("../../docs/DATA_MAP.md").readText()
        for (s in listOf("8553", "TX_PER_MIN", "markGraduated", "D9")) assertTrue(s, d.contains(s))
    }
}
