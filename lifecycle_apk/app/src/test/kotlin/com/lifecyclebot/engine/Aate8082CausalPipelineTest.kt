package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.ExactStrategyPerformance7429
import com.lifecyclebot.engine.truth.PositionExcursion8082
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8082 — blockchain finality → exact strategy learning; observed excursion; per-lane Cortex entry reads. */
class Aate8082CausalPipelineTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun fin(m: String, dq: String, pi: String = "") = CanonicalTradeFinalizedBus6450.economicFinality8082(m, dq, pi)

    @Test fun typedFinalityFromRealProducerVocabulary() {
        assertEquals("LIVE_FINALIZED", fin("live", "confirmed_signature", "confirmed_signature"))
        assertEquals("LIVE_FINALIZED", fin("live", "DLN_REVERSE_FINALIZED_SOURCE_SOL_DELTA", "ROUTE_VERIFIED_SELLABLE"))
        assertEquals("LIVE_RECONCILED", fin("live", "CONFIRMED_WALLET_RETURN_DELTA", "ROUTE_VERIFIED_SELLABLE"))
        assertEquals("PAPER_SIMULATED", fin("paper", "canonical_paper_fill", "canonical_paper_fill"))
        // legacy compound durable rows are read, not upgraded
        assertEquals("LIVE_FINALIZED", fin("live", "confirmed_signature:confirmed_signature"))
        // false / missing proofs stay out
        assertEquals("LIVE_UNVERIFIED", fin("live", "", ""))
        assertEquals("LIVE_UNVERIFIED", fin("live", "canonical_paper_fill"))
        assertEquals("PAPER_UNVERIFIED", fin("paper", "confirmed_signature"))
        assertEquals("UNVERIFIED", fin("unknown", "confirmed_signature"))
        assertTrue(ExactStrategyPerformance7429.terminalEconomicsEligible8081("LIVE", fin("live", "confirmed_signature")))
        assertFalse(ExactStrategyPerformance7429.terminalEconomicsEligible8081("LIVE", fin("live", "")))
    }

    @Test fun publisherReconcilerAndPersistenceShareTheTypedFinality() {
        val b = src("engine/truth/CanonicalTradeFinalizedBus6450.kt")
        assertTrue(b.contains("proofState = event.economicFinality,"))
        assertFalse(b.contains("proofState = \"\${event.dataQuality}:\${event.priceIntegrity}\""))
        assertTrue(src("engine/truth/FinalizedLearningReconciler7423.kt").contains("proofState = rich?.economicFinality"))
        assertTrue(src("engine/truth/CanonicalFinalityPersistence6486.kt").contains("finality8082"))
        val s = src("engine/sell/SellFinalizationCoordinator.kt")
        assertTrue(s.contains("economicFinality = if (sellSig.isNotBlank() && actualConsumedRaw.signum() > 0) \"LIVE_FINALIZED\" else \"LIVE_UNVERIFIED\""))
        assertTrue(s.contains("economicEventId = idKey,"))
    }

    @Test fun incompleteExactIdentityDoesNotConsumeTerminalCredit() {
        val e = com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope(
            "trade-incomplete", System.currentTimeMillis(), 0.01, 10.0, "mint", "SHITCOIN",
            positionId = "missing-stamp", mode = "live", proofState = "LIVE_FINALIZED")
        assertFalse(ExactStrategyPerformance7429.record(e))
        assertFalse(ExactStrategyPerformance7429.record(e))
        // An old timestamp is not evidence of missing history while the durable store is unavailable.
        assertFalse(ExactStrategyPerformance7429.record(e.copy(tradeId = "trade-expired8091", atMs = System.currentTimeMillis() - 181_000L)))
        assertFalse(com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.isExcluded("ExactStrategyPerformance7429", "trade-expired8091"))
        val source = src("engine/truth/ExactStrategyPerformance7429.kt").substringAfter("fun record(")
        assertTrue(source.indexOf("EXACT_STRATEGY_ATTRIBUTION_INCOMPLETE_7429") < source.indexOf("commitOnce8090(receipt, writes)"))
        assertFalse(source.contains("claimOnce8082"))
        assertTrue(source.contains("Commit8090.ALREADY_COMMITTED -> return true"))
    }

    @Test fun excursionIsObservedOrUnknownNeverFabricated() {
        PositionExcursion8082.clearForTest()
        assertTrue(PositionExcursion8082.mfeFor("never").isNaN())
        PositionExcursion8082.observe("p", 10.0, "T", 1_000L)
        PositionExcursion8082.observe("p", 250.0, "T", 2_000L)
        PositionExcursion8082.observe("p", -12.0, "T", 3_000L)
        val e = PositionExcursion8082.forClose("p")!!
        assertEquals(250.0, e.mfePct, 1e-9); assertEquals(-12.0, e.maePct, 1e-9); assertEquals(2_000L, e.mfeAtMs)
        val n = src("engine/truth/EntryStrategySnapshot6450.kt")
        assertFalse(n.contains("env.mfePct.takeIf { it != 0.0 } ?: maxOf(0.0, env.realizedReturnPct)"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("val mfePct: Double = Double.NaN,"))
        assertTrue(src("engine/truth/PositionDisplayTruth8078.kt").contains("PositionExcursion8082.observe(pos.positionId, v.pnlPct"))
    }

    @Test fun cortexEntryReadsAreKeyedPerLane() {
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("entryReads[\"\$normalized|\$m|\$laneKey8082\"]"))
        assertTrue(c.contains("entryReads[\"\$normalized8082|\${if (paper) \"PAPER\" else \"LIVE\"}|\${canon(a.lane)}\"] = a"))
        assertTrue(c.contains("ENTRY_BIND_OWNER_LANE_UNASSESSED_8082"))
    }

    @Test fun closeRecordsActualEconomicsAndPreservesFutureHoldSamples() {
        val x = src("engine/cortex/CortexExit7897.kt")
        val f = x.substringAfter("fun onCanonicalClose8082(").substringBefore("private fun grade8082(")
        assertTrue(f.contains("terminalEconomicsEligible8081(env.mode, env.proofState)"))
        assertTrue(f.contains("ACTUAL_WHOLE_POSITION_REALIZED"))
        assertTrue(f.contains("commitOnce8090"))
        assertFalse(f.contains("pending.remove"))
        assertFalse(f.contains("grade8082(r,"))
        assertFalse(f.contains("atSample / 100.0"))
        assertTrue(x.substringAfter("fun tick(").substringBefore("fun onCanonicalClose8082(").contains("grade8082(r, fwd)"))
        assertTrue(src("engine/truth/FinalizedBusConsumerBridge6465.kt").contains("CortexExit7897.onCanonicalClose8082(env)"))
    }

    @Test fun cortexRealisedBooksLearnOnlyFromVerifiedFinality() {
        val c = src("engine/cortex/Cortex7885.kt").substringAfter("fun onCanonicalClose(").substringBefore("// ── persistence")
        assertTrue(c.contains("terminalEconomicsEligible8081(env.mode, env.proofState)"))
        assertTrue(c.contains("env.learningEligible && env.realizedReturnPct.isFinite() && verified8082"))
    }

    @Test fun cortexEarnsAuthorityAndAbandonsDeadStrategiesSooner() {
        val S = com.lifecyclebot.engine.cortex.CortexScoreboard7885
        assertTrue(S.matureAuthority8025(1.0, 100.0) >= 0.5)            // proven lane can overrule soft refusals at ~100 grades
        assertEquals(1.0, S.matureAuthority8025(1.0, 300.0), 0.0)       // full authority at 300 on fully proven evidence
        assertTrue(S.matureAuthority8025(0.99, 1e9) < 1.0)              // never without full proof
        assertEquals(0.99, S.BOOK_DECAY, 0.0)
        assertEquals(12, S.INVERSION_MIN_STRONG_7948)
        assertEquals(0.995, com.lifecyclebot.engine.cortex.CortexLedger7885.DECAY, 0.0)
        val x = src("engine/cortex/CortexExit7897.kt")
        assertTrue(x.contains("private const val SAMPLE_EVERY_MS = 60_000L"))
        assertTrue(x.contains("private const val MIN_N = 30"))
    }

    @Test fun cryptoAltRowsHaveOneExitOwner() {
        val b = src("engine/BotService.kt").substringAfter("private fun solanaExitScope7819(").substringBefore("private fun canonicalExitTokenSnapshot6512(")
        assertTrue(b.contains("isCryptoAltTraderOwned8082(it)"))
        assertTrue(b.contains("return kept8082"))
        assertTrue(src("engine/truth/ExecutorCanonicalMirror6442.kt").contains("!CanonicalPositionAuthority6441.isCryptoAltTraderOwned8082(it)"))
        val p = src("engine/truth/CanonicalPositionAuthority6441.kt").substringAfter("fun isCryptoAltTraderOwned8082(").substringBefore("internal fun exitScopeOf7809(")
        assertTrue(p.contains("AssetClass.fromPositionIdPrefix(p.positionId) == AssetClass.CRYPTO_ALT"))
    }

    @Test fun aDeadSetupIsDroppedEarlyAndCanComeBack() {
        val C = com.lifecyclebot.engine.cortex.Cortex7885
        fun cell(n: Int, mean: Double) = com.lifecyclebot.engine.cortex.CortexLedger7885.Stat().also { s ->
            repeat(n) { i -> s.add(mean + if (i % 2 == 0) 2.0 else -2.0, false) }
        }
        assertFalse(C.jointProvenDead8082(null))
        assertFalse(C.jointProvenDead8082(cell(8, -10.0)))     // too few reads to call it
        assertTrue(C.jointProvenDead8082(cell(12, -10.0)))     // dead on its own short record
        assertFalse(C.jointProvenDead8082(cell(40, -1.0)))     // a small loss inside the margin is not dead
        val s = cell(12, -10.0)
        repeat(150) { i -> s.scale(com.lifecyclebot.engine.cortex.CortexScoreboard7885.BOOK_DECAY); s.add(8.0 + if (i % 2 == 0) 2.0 else -2.0, false) }
        assertFalse(C.jointProvenDead8082(s))                  // it starts paying: the refusal lifts
        val src = src("engine/cortex/Cortex7885.kt")
        assertTrue(src.contains("return \"C6_SETUP_DEAD_8082\""))
        // 8372: the same dead-setup bar reads actual frozen current-version lane x setup outcomes. The legacy
        // decayed book remains historical telemetry, not a second predictor's authority over today's verdict.
        val constitution = src.substringAfter("private fun constitutionRefusal(").substringBefore("// ── Cortex v9")
        assertTrue(constitution.contains("cohort8372(a.lane, it)[a.bucket.ordinal]"))
        assertTrue(constitution.contains("if (jointProvenDead8082(st8082)) return \"C6_SETUP_DEAD_8082\""))
        assertFalse(constitution.contains("jointBook7961["))
    }

    @Test fun deadSetupUsesItsCurrentFrozenCohortAndRecoversOnNewOutcomes() {
        val book = com.lifecyclebot.engine.cortex.CortexGradeBook8144()
        var next = 0
        fun add(net: Double, lane: String = "EXPRESS", setup: String = "BREAKOUT", version: Int = 1) {
            val id = "dead-setup-8082-${next++}"
            val at = 1_000_000L + next * 60_000L
            val r = com.lifecyclebot.engine.cortex.CortexGradeRecord8144('D', id, "$id|$lane", at,
                lane, setup, "R", "T", "C", "COH", 2, 8.0, false, true, false)
                .also { it.scoreVersion8372 = version }
            assertTrue(book.addDecision(r))
            book.label(id, net, net + 2.0, net, true, at + 300_000L)
        }
        repeat(12) { add(-10.0) }
        repeat(20) { add(100.0, version = 0); add(100.0, lane = "MOONSHOT"); add(100.0, setup = "OTHER_SETUP") }
        val dead = book.bucketStats8372("EXPRESS", "BREAKOUT")[2]
        assertEquals(12.0, dead.n, 0.0)
        assertEquals(-10.0, dead.mean(), 1e-12)
        assertTrue(com.lifecyclebot.engine.cortex.Cortex7885.jointProvenDead8082(dead))
        repeat(150) { add(8.0) }
        val recovered = book.bucketStats8372("EXPRESS", "BREAKOUT")[2]
        assertEquals(162.0, recovered.n, 0.0)
        assertTrue(recovered.mean() > 0.0)
        assertFalse(com.lifecyclebot.engine.cortex.Cortex7885.jointProvenDead8082(recovered))
    }
}
