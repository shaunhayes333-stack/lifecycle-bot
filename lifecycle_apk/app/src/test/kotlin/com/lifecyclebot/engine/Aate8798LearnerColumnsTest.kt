package com.lifecyclebot.engine

import com.lifecyclebot.engine.market.LaunchTape7921
import com.lifecyclebot.engine.truth.LearnerColumns8798
import com.lifecyclebot.engine.truth.SolTrend8798
import com.lifecyclebot.engine.truth.TokenFacts
import com.lifecyclebot.engine.truth.TreeRanker8737
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * V5.0.8798 — owner: "we need to capture every piece of data that the bot already is served, score, use, implement
 * correctly". Every fact the playbook frame computes, the evidence no column carried and each source's age reach the
 * learners as named dataset columns; holders are a fact with their own time; unwatched launches build their launch tape.
 */
class Aate8798LearnerColumnsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun mint(tag: String) = (tag + "x".repeat(44)).take(40) + "pump"

    @Test fun oneNamedColumnListTravelsThroughTheDataset() {
        assertEquals(LearnerColumns8798.NAMES.size, LearnerColumns8798.NAMES.toSet().size)
        assertTrue(LearnerColumns8798.SIZE >= 60)
        val x = DoubleArray(LearnerColumns8798.SIZE) { Double.NaN }
        val iHolders = LearnerColumns8798.NAMES.indexOf("holders"); val iSmart = LearnerColumns8798.NAMES.indexOf("smartBuyers5m")
        x[iHolders] = 120.0; x[iSmart] = 0.0                           // 0 is a reading, NaN is not
        val row = LearnerColumns8798.toRow(x)
        assertTrue(row.contains("\"holders\":120.00") && row.contains("\"smartBuyers5m\":0.0000"))
        assertFalse("an unobserved column is left out", row.contains("\"venue\""))
        val back = LearnerColumns8798.fromRow(org.json.JSONObject("{\"t\":1$row}"))
        assertEquals(120.0, back[iHolders], 1e-9); assertEquals(0.0, back[iSmart], 0.0)
        assertTrue(back[LearnerColumns8798.NAMES.indexOf("venue")].isNaN())
        val snap = LearnerColumns8798.fromJson(LearnerColumns8798.toJson(x))
        assertEquals(120.0, snap[iHolders], 0.0); assertTrue(snap[0].isNaN())
        assertEquals(0, LearnerColumns8798.fromJson(null).size)
    }

    @Test fun venueAgeAndSolTrend() {
        assertEquals(0.0, LearnerColumns8798.venueOf(mint("a"), "UNKNOWN", "UNKNOWN", "", false), 0.0)      // on the curve
        assertEquals(1.0, LearnerColumns8798.venueOf(mint("a"), "PUMPSWAP", "pumpswap", "", true), 0.0)
        assertEquals(3.0, LearnerColumns8798.venueOf("So1abc", "RAYDIUM_CPMM", "raydium", "", false), 0.0)
        assertEquals(4.0, LearnerColumns8798.venueOf("So1abc", "METEORA_DBC", "", "", false), 0.0)
        assertTrue(LearnerColumns8798.venueOf("So1abc", "UNKNOWN", "UNKNOWN", "", false).isNaN())
        assertEquals(30.0, LearnerColumns8798.ageSec(70_000L, 100_000L), 1e-9)
        assertTrue(LearnerColumns8798.ageSec(0L, 100_000L).isNaN())
        val t0 = 1_800_000_000_000L
        SolTrend8798.note(100.0, t0)
        assertTrue(SolTrend8798.changePct(60, t0).isNaN())          // no sample an hour old yet
        SolTrend8798.note(110.0, t0 + 60 * 60_000L)
        assertEquals(10.0, SolTrend8798.changePct(60, t0 + 60 * 60_000L), 1e-9)
    }

    @Test fun holdersAreAFactWithTheirOwnTime() {
        val s = TokenFacts.HolderSeries8798()
        val a = TokenFacts.Fact(100.0, "JUPITER_SEARCH_8128", TokenFacts.Rank.PROVIDER, 1_000_000L)
        assertTrue(TokenFacts.nextHolders8798(s, a))
        assertFalse("an older answer changes nothing", TokenFacts.nextHolders8798(s, a.copy(value = 90.0, observedAtMs = 999_000L)))
        assertTrue(TokenFacts.nextHolders8798(s, a.copy(value = 104.0, observedAtMs = 1_030_000L)))   // 30 s later: replaces, no velocity yet
        assertNull(s.prev)
        assertTrue(TokenFacts.nextHolders8798(s, a.copy(value = 130.0, observedAtMs = 1_150_000L)))   // 2 min after the first
        assertEquals(104.0, s.prev!!.value, 0.0)
        assertEquals(13.0, TokenFacts.holderVelocity8798(s.prev, s.last, 1_150_000L), 1e-9)        // 26 holders in 2 minutes
        assertTrue(TokenFacts.holderVelocity8798(s.prev, s.last, 1_150_000L + TokenFacts.HOLDER_MAX_AGE_MS_8798).isNaN())
        val m = mint("holders")
        val now = System.currentTimeMillis()
        assertTrue(TokenFacts.writeHolders8798(m, 50, "TEST", now - 180_000L, now))
        assertTrue(TokenFacts.writeHolders8798(m, 80, "TEST", now, now))
        assertEquals(80.0, TokenFacts.holders8798(m, now)!!.value, 0.0)
        assertEquals(10.0, TokenFacts.holderVelocity8798(m, now), 1e-9)
        assertFalse(TokenFacts.writeHolders8798(m, 0, "TEST", now, now))
    }

    @Test fun anUnwatchedLaunchBuildsItsTapeFromBirth() {
        val now = System.currentTimeMillis()
        val m = mint("program")
        assertTrue(LaunchTape7921.recoverBirth8167(m, now - 60_000L, now))
        val px = listOf(1.0e-8, 0.8e-8, 1.6e-8, 1.2e-8)
        for ((i, p) in px.withIndex()) LaunchTape7921.onProgramPrint8798(m, "w$i", 0.5, i != 3, p, now)
        val f = LaunchTape7921.features(m, now)!!
        assertEquals(4, f.trades8287)
        assertEquals(50.0, f.runupFromLowPct8798, 1e-6)                 // 1.2 from a low of 0.8
        assertEquals(-25.0, f.fromPeakPct, 1e-6)                        // 1.2 under a peak of 1.6
        assertTrue(LaunchTape7921.birthsSince8798(10L * 60_000L, now) >= 1)
    }

    @Test fun theTreeReadsEveryColumn() {
        assertEquals(19 + LearnerColumns8798.SIZE, TreeRanker8737.FEATURES.size)
        val line = """{"t":1000,"mint":"AbcPUMPpump","lane":"CORE","fill":true,"horizonMs":${com.lifecyclebot.engine.truth.PocketLedger8610.targetHorizon("CORE")},"liq":20000,"mcap":40000,"age":3,"cost":2,"net":5,"holderVel":4.5,"holders":120,"smartBuyers5m":2}"""
        val rows = TreeRanker8737.rowsOf8737(sequenceOf(line))
        assertEquals(1, rows.size)
        val x = rows[0].first
        assertEquals(TreeRanker8737.FEATURES.size, x.size)
        assertEquals(4.5, x[TreeRanker8737.FEATURES.indexOf("holderVel")], 0.0)
        assertEquals(120.0, x[TreeRanker8737.FEATURES.indexOf("holders")], 0.0)
        assertEquals(2.0, x[TreeRanker8737.FEATURES.indexOf("smartBuyers5m")], 0.0)
        assertTrue(x[TreeRanker8737.FEATURES.indexOf("venue")].isNaN())
        // stochastic boosting still finds an interaction no single column shows
        val r = Random(11)
        val xs = List(6_000) { DoubleArray(17) { r.nextDouble() } }
        val ys = xs.map { v -> (if (v[0] > 0.6 && v[2] < 0.3) 25.0 else -8.0) + r.nextDouble() * 6 - 3 }
        val model = TreeRanker8737.train8737(xs, ys, List(xs.size) { 1.0 }, 0L, trees = 200, rowFrac = 0.5, colFrac = 0.5)
        val good = DoubleArray(17) { 0.5 }.also { it[0] = 0.9; it[2] = 0.1 }
        val bad = DoubleArray(17) { 0.5 }.also { it[0] = 0.9; it[2] = 0.9 }
        assertTrue("${model.predict(good)} vs ${model.predict(bad)}", model.predict(good) - model.predict(bad) > 10.0)
    }

    @Test fun everyProducerAndReaderIsWired() {
        val tape = src("network/HeliusCurveTape8354.kt")
        val notCared = tape.substringAfter("if (!cares8354(mint, nowMs)) {").substringBefore("replayPrecare8751(mint, nowMs)")
        assertTrue(notCared.contains("LaunchTape7921.onProgramPrint8798("))
        assertTrue(src("network/JupiterHolders8128.kt").contains("TokenFacts.writeHolders8798(mint, h, \"JUPITER_SEARCH_8128\", at)"))
        assertTrue(src("network/BirdeyeApi.kt").contains("TokenFacts.writeHolders8798(mint, h, \"BIRDEYE_OVERVIEW\""))
        assertTrue(src("engine/market/MarketSweep7297.kt").contains("TokenFacts.writeHolders8798(mint, h, \"JUP_SWEEP_8798\", now)"))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("holders = holderFact8798?.value ?:") && pb.contains("holderVel7978 = if (holderVel8798.isFinite()) holderVel8798 else"))
        assertTrue(pb.contains("tapeShape8100?.runupPct ?: launchShape8798?.runupFromLowPct8798"))
        val edge = src("engine/truth/EdgeModel8537.kt")
        assertTrue(edge.contains("ext = try { LearnerColumns8798.extract(ts, f, nowMs) }"))
        assertTrue(edge.contains("LearnerColumns8798.toRow(r.ext) + \"}\""))
        assertTrue(edge.contains("raw.put(\"ext8798\", LearnerColumns8798.toJson(r.ext))"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("LearnerColumns8798.statusLine8798()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 10. The learner columns (8798)"))
    }
}
