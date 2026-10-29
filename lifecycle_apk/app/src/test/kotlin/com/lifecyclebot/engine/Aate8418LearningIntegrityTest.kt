package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexGradeRecord8144
import com.lifecyclebot.engine.cortex.CortexLabelHygiene8144
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexOutcomeProof8395
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexShadowLedger8418
import com.lifecyclebot.engine.cortex.CrossAssetCortex7931
import com.lifecyclebot.engine.cortex.ForwardOutcomeOutbox8404
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.EvidenceShadow8344
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LabelEvidence8386
import com.lifecyclebot.engine.truth.LabelMark8386
import com.lifecyclebot.engine.truth.ShadowPersist8355
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8418 — learning and Cortex integrity: shadow isolation, extreme moves, bucket chain, evidence kept, bounded stalls. */
class Aate8418LearningIntegrityTest {
    private val strong = CortexScoreboard7885.Bucket.STRONG.ordinal

    private fun rec(id: String, lane: String = "CORE", bucket: Int = strong, version: Int = 1, at: Long = 1_000L) =
        CortexGradeRecord8144('D', id, "$id|$lane", at, lane, "S", "R", "T", "C", "COH", bucket, 5.0, false, true, false)
            .also { it.scoreVersion8372 = version }

    // ── 1. shadow outcomes never train or size live ──

    @Test fun aShadowExitLeavesBucketStatsAuthorityRowsAndRankRowsUnchanged() {
        val book = CortexGradeBook8144()
        book.addDecision(rec("fwd")); assertTrue(book.label("fwd", 4.0, 6.0, 4.0, true, 400_000L))
        val beforeStats = book.bucketStats8372("CORE")[strong]
        val beforeRows = book.scoredRows8366("CORE", false, 1).map { it.netPct }
        val beforeRank = book.rankRows8324("CORE").size
        // A lane whose only extra STRONG outcome is a shadow exit: nothing changes for authority or Kelly sizing.
        book.addDecision(rec("shadow")); assertTrue(book.shadowOutcome8372("shadow", 90.0, 95.0, 500_000L))
        val afterStats = book.bucketStats8372("CORE")[strong]
        assertEquals(beforeStats.n, afterStats.n, 0.0)
        assertEquals(beforeStats.sum, afterStats.sum, 0.0)
        assertEquals(beforeRows, book.scoredRows8366("CORE", false, 1).map { it.netPct })
        assertEquals(beforeRank, book.rankRows8324("CORE").size)
        assertEquals(1.0, book.resolvedShare("CORE", strong), 0.0)
        assertTrue(book.decision("shadow")!!.shadowExit8418)
        assertFalse(book.decision("shadow")!!.graded)
        // Only shadow outcomes: authority sees no rows at all (authority 0, stake 0).
        val only = CortexGradeBook8144()
        repeat(40) { i -> only.addDecision(rec("s$i", at = 1_000L + i)); only.shadowOutcome8372("s$i", 50.0, 55.0, 600_000L + i) }
        assertTrue(only.scoredRows8366("CORE", false, 1).isEmpty())
        assertEquals(0.0, only.bucketStats8372("CORE")[strong].n, 0.0)
        assertEquals(0.0, CortexAuthority8366.test8372(only.scoredRows8366("CORE", false, 1), 2.0, 10_000_000L).fraction, 0.0)
    }

    @Test fun theLiveCalibrationRefusesShadowLearningAndRevision() {
        val c = CortexCalibration7901()
        val stamp = c.learn("CORE", 1.0, 0.0, 3.0, 1.0)
        val before = c.encode().toString()
        assertTrue(c.learn("CORE", 1.0, 0.0, 80.0, 1.0, CortexCalibration7901.TARGET_SHADOW_NET_8372).isNaN())
        assertFalse(c.revise8322("CORE", 1.0, 0.0, 3.0, 80.0, stamp, CortexCalibration7901.TARGET_SHADOW_NET_8372))
        assertEquals(before, c.encode().toString())
    }

    @Test fun theShadowLedgerIsTheOnlyBookAShadowOutcomeTouches() {
        val book = CortexShadowLedger8418()
        assertTrue(Cortex7885.shadowOutcomeIsolated8418(book, "CORE", "STRONG", false, 12.0, 60.0))
        assertTrue(Cortex7885.shadowOutcomeIsolated8418(book, "CORE", "STRONG", true, -4.0, -3.0))
        assertFalse(Cortex7885.shadowOutcomeIsolated8418(book, "CORE", "STRONG", true, Double.NaN, 1.0))   // unknown stays unknown
        val row = book.row("CORE|STRONG")!!
        assertEquals(2L, row.n); assertEquals(4.0, row.mean(), 1e-12); assertEquals(1L, row.wins); assertEquals(1L, row.runners)
        assertEquals(1L, book.row("CORE|STRONG|NONEXEC")!!.n)
        val restored = CortexShadowLedger8418().also { it.decode(book.encode()) }
        assertEquals(2L, restored.row("CORE|STRONG")!!.n); assertEquals(2L, restored.recorded)
        assertTrue(restored.line().contains("CORE|STRONG:n2"))
        // The playbook acknowledges a shadow terminal without rewriting any setup record.
        assertTrue(LanePlaybook7907.onShadowTerminal8372("Mint8418", "CORE", 1_000L, 50.0, 55.0, 2_000L))
    }

    @Test fun shadowAndForwardContributionsPayTheSameFullOffset() {
        val fwd = CortexLedger7885.Stat().also { it.add(-5.0, false); it.readOffset8187 = 3.0 }
        val shd = CortexLedger7885.Stat().also { it.add(-5.0, false, shadowNet8404 = true); it.readOffset8187 = 3.0 }
        assertEquals(fwd.mean(), shd.mean(), 0.0)
        assertEquals(-8.0, shd.mean(), 1e-12)
        assertEquals(3.0, shd.effectiveReadOffset8404(), 0.0)
    }

    // ── 2. extreme moves: chain-provable runners train, rugs always book ──

    private fun mark(px: Double, at: Long, source: String = "TEST", independent: Boolean = false) =
        LabelMark8386("m1", "venue", px, at, at + 100, source, decimals = 6, independentBasis = independent)

    @Test fun rugsAlwaysBookAndChainProvableRunnersTrain() {
        assertEquals("VALID_8386", LabelEvidence8386.status(mark(1.0, 1_000), mark(0.02, 60_000)))   // -98 %: booked
        assertEquals("OBSERVED_ONLY_UNWITNESSED_EXTREME", LabelEvidence8386.status(mark(1.0, 1_000), mark(100.0, 60_000)))
        assertEquals("VALID_8386", LabelEvidence8386.status(mark(1.0, 1_000, "CURVE_FRAME"), mark(100.0, 60_000, "CURVE_FRAME")))
        assertEquals("VALID_8386", LabelEvidence8386.status(mark(1.0, 1_000, "HELIUS_SWAP_PRINT"), mark(100.0, 60_000, "POOL_RESERVES")))
        assertTrue(LabelEvidence8386.independentSource8418("CURVE_ACCOUNT"))
        assertFalse(LabelEvidence8386.independentSource8418("JUPITER_SWAP"))
        assertFalse(LabelEvidence8386.independentSource8418("DEXSCREENER"))
        assertFalse(LabelEvidence8386.independentSource8418("CURVE_UNKNOWN"))
        assertTrue(ForwardReturnLabeler7731.markWitnesses8418(mark(2.0, 5_000, "CURVE_FRAME"), 2.0, 5_000))
        assertFalse(ForwardReturnLabeler7731.markWitnesses8418(mark(2.0, 5_000, "OFFWATCH"), 2.0, 5_000))
        assertFalse(ForwardReturnLabeler7731.markWitnesses8418(mark(2.0, 5_000, "CURVE_FRAME"), 2.0, 6_000))
    }

    @Test fun aRestoredImplausibleValueIsHeldNotDestroyedAndAWitnessedHundredXTrains() {
        val src = CortexGradeBook8144()
        val held = rec("m1|SHITCOIN|1000", lane = "SHITCOIN").also { src.addDecision(it) }
        held.executablePct = 50_000.0   // unwitnessed, above 10x the cap: refused on restore
        val once = CortexGradeBook8144().also { it.decode(src.encode()) }
        assertFalse(once.decision(held.id)!!.executablePct.isFinite())
        assertEquals(50_000.0, once.decision(held.id)!!.heldExecutable8418, 0.0)
        val twice = CortexGradeBook8144().also { it.decode(once.encode()) }   // the stored value survives every save
        assertEquals(50_000.0, twice.decision(held.id)!!.heldExecutable8418, 0.0)
        assertEquals(50_000.0, twice.storedExecutable8418(Double.NaN, 50_000.0), 0.0)
        // The same 500x move on chain-provable marks (curve tape at both ends) trains at its full value.
        val book = CortexGradeBook8144()
        val r = rec("m1|SHITCOIN|1000", lane = "SHITCOIN").also { book.addDecision(it) }
        val proof = CortexOutcomeProof8395(r.id, 299_000L, 300_000L, 50_000.0, 50_000.0,
            mark(1.0, 1_000, "CURVE_FRAME"), mark(501.0, 300_000, "CURVE_FRAME"))
        assertTrue(proof.witnessed8395())
        r.executablePct = 50_000.0; r.firstGross8324 = 50_000.0; r.firstAvailableAtMs8372 = 300_000L
        r.firstOutcomeProof8395 = JSONObject(proof.encode8395()).put("exec", 50_000.0).toString()
        val restored = CortexGradeBook8144().also { it.decode(book.encode()) }
        assertEquals(50_000.0, restored.decision(r.id)!!.executablePct, 0.0)
        assertEquals(1.0, restored.bucketStats8372("SHITCOIN")[strong].n, 0.0)
    }

    // ── 3. bucket chain ──

    @Test fun runnerTailExemptionAndUncalibratedNeutralAreBack() {
        assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, CortexScoreboard7885.bucketOf(-6.0, 0.15, runnerLane = true))
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(-6.0, 0.05, runnerLane = true))
        assertEquals(CortexScoreboard7885.Bucket.REFUSE, CortexScoreboard7885.bucketOf(-6.0, 0.15, runnerLane = false))
        assertTrue(Cortex7885.uncalibratedNeutralDue8418(measured = true, rankable = true, calibrated = false, b = CortexScoreboard7885.Bucket.STRONG))
        assertFalse(Cortex7885.uncalibratedNeutralDue8418(measured = true, rankable = false, calibrated = false, b = CortexScoreboard7885.Bucket.STRONG))
        assertFalse(Cortex7885.uncalibratedNeutralDue8418(measured = false, rankable = true, calibrated = false, b = CortexScoreboard7885.Bucket.REFUSE))
        assertFalse(Cortex7885.uncalibratedNeutralDue8418(measured = true, rankable = true, calibrated = true, b = CortexScoreboard7885.Bucket.STRONG))
    }

    // ── 4. evidence kept, never reset ──

    @Test fun refusalsReadHistoricalCohortsUntilTheCurrentCohortIsBuilt() {
        assertTrue(Cortex7885.refusalUsesHistory8418(5.0, 12.0))
        assertFalse(Cortex7885.refusalUsesHistory8418(12.0, 12.0))
        val book = CortexGradeBook8144()
        repeat(15) { i ->
            book.addDecision(rec("old$i", version = 0, at = 1_000L + i)); book.label("old$i", -9.0, -8.0, -9.0, true, 400_000L + i)
        }
        assertEquals(0.0, book.bucketStats8372("CORE")[strong].n, 0.0)                       // current-only (8372)
        assertEquals(15.0, book.bucketStats8372("CORE", null, null)[strong].n, 0.0)          // every version (8418)
        assertTrue(Cortex7885.jointProvenDead8082(book.bucketStats8372("CORE", null, null)[strong]))
    }

    @Test fun legacyObservationsKeepTheirBasisAndEvmKeysMigrate() {
        val m = ForwardReturnLabeler7731.legacyBasisMark8418("Mint", 0.5, 10_000L)!!
        assertTrue(m.known()); assertEquals(0.5, m.usd, 0.0); assertFalse(LabelEvidence8386.independent8418(m))
        assertNull(ForwardReturnLabeler7731.legacyBasisMark8418("Mint", Double.NaN, 10_000L))
        assertTrue(ForwardReturnLabeler7731.adoptBasis8418(false, false, 20_000L, mark(1.0, 20_000)))
        assertFalse(ForwardReturnLabeler7731.adoptBasis8418(true, false, 20_000L, mark(1.0, 20_000)))
        assertFalse(ForwardReturnLabeler7731.adoptBasis8418(false, true, 20_000L, mark(1.0, 20_000)))
        assertFalse(ForwardReturnLabeler7731.adoptBasis8418(false, false, 90_000L, mark(1.0, 20_000)))
        assertEquals("ethereum|0xabc", CrossAssetCortex7931.migratedKey8418("0xabc", listOf("ethereum|0xabc", "SoMint")))
        assertEquals("0xabc", CrossAssetCortex7931.migratedKey8418("bsc|0xabc", listOf("0xabc")))
        assertNull(CrossAssetCortex7931.migratedKey8418("0xabc", listOf("ethereum|0xabc", "bsc|0xabc")))   // ambiguous
        assertNull(CrossAssetCortex7931.migratedKey8418("SoMint", listOf("solana|SoMint")))
        // Pre-8394 shadow closes are kept and reported, never silently dropped (and never a live unlock).
        assertTrue(ShadowPersist8355.legacyProofRow8418(8360.0, Long.MAX_VALUE / 4.0, 0L))
        assertFalse(ShadowPersist8355.legacyProofRow8418(8394.0, Long.MAX_VALUE / 4.0, 0L))
        assertEquals(0, EvidenceShadow8344.legacyRecord8418("NO_SUCH_LANE_8418", 0L).first)
    }

    // ── 5. DNA readers: live mode, canonical lane, never "no rows" for a veto ──

    private fun dna(lane: String, mode: String, pnl: Double, setup: String = "BREAKOUT") = LiveWinDNAStore.WinDNA(
        "M$lane$mode$pnl", "S", lane, "src", "ph", setup, "pat", 60, 1e5, 2e5, 1e4, 10, 60.0, pnl, pnl, "exit", mode, 1L)

    @Test fun dnaReadersScopeByModeAndNeverDisableTheVetoOnABlankLane() {
        val rows = listOf(dna("SHITCOIN", "PAPER", -30.0), dna("SHITCOIN", "LIVE", -20.0), dna("MOONSHOT", "LIVE", 40.0))
        assertEquals(1, LiveWinDNAStore.scoped8418(rows, "SHITCOIN", "LIVE", fallbackAll = true).size)
        assertEquals(2, LiveWinDNAStore.scoped8418(rows, "", "LIVE", fallbackAll = true).size)          // veto: all live lanes
        assertEquals(0, LiveWinDNAStore.scoped8418(rows, "", "LIVE", fallbackAll = false).size)         // size-up: nothing
        assertEquals(2, LiveWinDNAStore.scoped8418(rows, "NETWORK_SIGNAL", "LIVE", fallbackAll = true).size)
        assertEquals(3, LiveWinDNAStore.scoped8418(rows, null, null, fallbackAll = false).size)
        assertTrue(LiveWinDNAStore.scoped8418(rows, null, "LIVE", fallbackAll = false).none { it.paperOrLive == "PAPER" })
    }

    // ── 6 / 7 / 8. bounded staging, bounded restore, validated checkpoint ──

    @Test fun learnersAreIndependentOfCortexStagingAndTheOutboxIsBoundedPerObservation() {
        val v = ForwardReturnLabeler7731.stagedValue8418(4.0, 6.0, false, true, doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        assertEquals(8, v.size); assertEquals(4.0, v[0], 0.0); assertEquals(1.0, v[3], 0.0); assertEquals(4.0, v[7], 0.0)
        assertFalse(ForwardReturnLabeler7731.stageAbandoned8418(1_000L, 1_000L, 600_000L))
        assertTrue(ForwardReturnLabeler7731.stageAbandoned8418(601_000L, 1_000L, 600_000L))
        assertFalse(ForwardReturnLabeler7731.revisionStageGiveUp8418(60_000L, 60_000L, 120_000L))
        assertTrue(ForwardReturnLabeler7731.revisionStageGiveUp8418(180_000L, 60_000L, 120_000L))
        val q = ForwardOutcomeOutbox8404(10)
        val ep = "M|CORE|1000"
        assertTrue(q.stage(ep, { "first" }) { _, _ -> true })
        assertTrue(q.stage("$ep|R|1|900000", { "r15" }) { _, _ -> true })
        assertTrue(q.stage("$ep|R|2|1800000", { "r30" }) { _, _ -> true })
        assertEquals(3, q.rowsFor8418(ep))
        assertEquals(1, q.supersedeRevisions8418("$ep|R|", "$ep|R|2|1800000") { true })
        assertEquals(2, q.rowsFor8418(ep))
        assertTrue(q.contains(ep)); assertTrue(q.contains("$ep|R|2|1800000")); assertFalse(q.contains("$ep|R|1|900000"))
        assertFalse(q.contains("$ep|R|1|900000"))
    }

    @Test fun aPairedRestoreThatNeverSucceedsIsDroppedAndTheCheckpointIsValidated() {
        assertTrue(ForwardReturnLabeler7731.pairedRestoreAbandoned8418(corrupt = true, waitedMs = 0L, maxWaitMs = 300_000L))
        assertFalse(ForwardReturnLabeler7731.pairedRestoreAbandoned8418(corrupt = false, waitedMs = 10_000L, maxWaitMs = 300_000L))
        assertTrue(ForwardReturnLabeler7731.pairedRestoreAbandoned8418(corrupt = false, waitedMs = 300_000L, maxWaitMs = 300_000L))
        assertTrue(Cortex7885.completeJson8418("""{"a":[1,{"b":"x}"}],"c":"\"]"}"""))
        assertFalse(Cortex7885.completeJson8418("null"))
        assertFalse(Cortex7885.completeJson8418("""{"a":[1,2}"""))
        assertFalse(Cortex7885.completeJson8418("""{"a":1}{"b":2}"""))
        assertFalse(Cortex7885.completeJson8418(null))
        var threw = false
        try { Cortex7885.section8418("cold8372", "null") } catch (_: IllegalStateException) { threw = true }
        assertTrue("an invalid section is never committed", threw)
        assertEquals("{}", Cortex7885.section8418("p", "{}"))
    }

    // ── 9. mature combinations revise at their weight ──

    @Test fun aMatureMinerCombinationRevisesAtItsRemainingWeight() {
        val s = SpecialistMiner7972.Stat()
        repeat(300) { s.add(0.0, 0.0) }
        val stamp = s.add(10.0, 12.0)          // the window fades on this add: the label owns k of its weight
        repeat(50) { s.add(0.0, 0.0) }
        val w = SpecialistMiner7972.revisionWeight8418(stamp, s.logScale8418)!!
        assertTrue(w > 0.0 && w < 1.0)
        val before = s.sum
        s.revise(10.0, 0.0, 12.0, 0.0, w)
        assertEquals(before - 10.0 * w, s.sum, 1e-9)
        assertTrue(s.decayed8410)
        // Without a stamp the 8410 rule stands on a faded record (skip), and an unknown weight never applies at all.
        val sumNow = s.sum
        s.revise(0.0, 50.0, 0.0, 60.0)
        assertEquals(sumNow, s.sum, 0.0)
        s.revise(0.0, 50.0, 0.0, 60.0, Double.NaN)
        assertEquals(sumNow, s.sum, 0.0)
        assertNull(SpecialistMiner7972.revisionWeight8418(Double.NaN, -0.1))
        assertNull(SpecialistMiner7972.revisionWeight8418(-0.5, -0.1))   // a coordinate from another boot
        assertEquals(0.0, ForwardReturnLabeler7731.revisionWeight8410(Double.NaN, 0.0), 0.0)
    }

    // ── 11 / 12 / 13 ──

    @Test fun windowLabelsEndTheFloorAtTheLiveRelease() {
        assertEquals(500L, ForwardReturnLabeler7731.windowHoldEnd8418(1_000L, 500L))
        assertEquals(1_000L, ForwardReturnLabeler7731.windowHoldEnd8418(1_000L, 0L))
        assertEquals(1_000L, ForwardReturnLabeler7731.windowHoldEnd8418(1_000L, 2_000L))
    }

    @Test fun positiveAuthorityNeedsSeventyPercentCoverage() {
        assertEquals(0.0, CortexAuthority8366.coverageScale8399(0.69), 0.0)
        assertEquals(0.0, CortexAuthority8366.coverageScale8399(0.30), 0.0)
        assertEquals(1.0, CortexAuthority8366.coverageScale8399(0.70), 0.0)
        assertEquals(0.0, CortexAuthority8366.coverageScale8399(Double.NaN), 0.0)
    }

    @Test fun cortexTicketsKeepToTheirShareAndTheSnapshotAvoidsADoubleCopyUnderPressure() {
        assertTrue(ExploreShadow8347.cortexRoom8418(false, 5_000, 5_000))   // native: never limited by the Cortex share
        assertTrue(ExploreShadow8347.cortexRoom8418(true, ExploreShadow8347.MAX_CORTEX_TICKETS_8418 - 1, 0))
        assertFalse(ExploreShadow8347.cortexRoom8418(true, ExploreShadow8347.MAX_CORTEX_TICKETS_8418, 0))
        assertFalse(ExploreShadow8347.cortexRoom8418(true, 0, ExploreShadow8347.MAX_CORTEX_PER_LANE_8418))
        assertTrue(ExploreShadow8347.MAX_TICKETS_8347 - ExploreShadow8347.MAX_CORTEX_TICKETS_8418 >= 2_000)
        assertEquals("CORTEX_SHARE_FULL_8418", ExploreShadow8347.SKIP_CORTEX_SHARE_8418)
        assertFalse(Cortex7885.snapshotEncodesInLock8418("NORMAL"))
        assertTrue(Cortex7885.snapshotEncodesInLock8418("HARD"))
    }

    // ── (a) only executable decisions grade ──

    // V5.0.8418 — withdrawn: filing every native NO unexecutable cut Cortex grading from ~564/h to 16.7/h. A scored NO
    // teaches (8372); only an unidentified NO is unverified.
    @Test fun aScoredNativeNoStillTeachesAndAnUnidentifiedOneDoesNot() {
        assertTrue(CortexLabelHygiene8144.executableDecision8144(false, "SCORE_TOO_LOW", false))
        assertFalse(CortexLabelHygiene8144.executableDecision8144(false, null, false))
        assertTrue(CortexLabelHygiene8144.executableDecision8144(false, "TOXIC_PATTERN_X"))
        assertTrue(CortexLabelHygiene8144.executableDecision8144(true, "TOXIC_PATTERN_X"))
    }
}
