package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexCalibration7901
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexTailEv8324
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Record8323
import com.lifecyclebot.engine.cortex.LaneCheatSheet8323.Verdict8323
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8324 — the 5.0.8321 report's three findings in scope: the voter seats and calibration lost at boot (restored without a
 * reset, and the paths that lost them closed), the assessment hot path (one prediction per voter, lock wait measured), and
 * bounded reads hiding the tail (label provenance instead of size; hive prior favour confirmed locally; tail-aware veto audit).
 */
class Aate8324RestoreProvenanceTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun seasonedLedger(seats: Int, scored: Int): CortexLedger7885 = CortexLedger7885().also { l ->
        repeat(seats) { i -> l.seat("V$i", "CORE", 3).scored = scored }
    }

    // ── 1. restore without reset ──

    @Test fun aBootThatReadAFarSmallerBookRestoresFromTheKeptOne() {
        val stored = seasonedLedger(400, 120)
        val boot = seasonedLedger(10, 70).also { it.seat("V0", "CORE", 3).scored = 200 }   // V0 graded more since boot
        assertEquals(400, Cortex7885.seasoned8324(stored))
        assertEquals(10, Cortex7885.seasoned8324(boot))
        assertTrue(Cortex7885.restoreDue8324(10, 400))
        assertFalse("a healthy boot is not restored", Cortex7885.restoreDue8324(300, 400))
        assertFalse("a thin kept book proves nothing", Cortex7885.restoreDue8324(0, Cortex7885.RESTORE_MIN_SEASONED_8324 - 1))
        val taken = Cortex7885.mergeLedger8324(boot, stored)
        assertEquals(399, taken)                                     // every seat but V0
        assertEquals(200, boot.seats["V0|CORE"]!!.scored)            // newer evidence of the same seat is kept
        assertEquals(400, Cortex7885.seasoned8324(boot))
        // regime and all-lane cells are not counted as lane seats
        stored.seat("V1", "CORE@BULL", 3).scored = 500
        stored.seat("V1", CortexLedger7885.GLOBAL, 3).scored = 500
        assertEquals(400, Cortex7885.seasoned8324(stored))
    }

    @Test fun theKeptRowParsesAndCalibrationMergesByEvidence() {
        assertEquals(412 to "{\"ledger\":{}}", Cortex7885.parseBackup8324("412\n{\"ledger\":{}}"))
        assertNull(Cortex7885.parseBackup8324("not-a-count\n{}"))
        assertNull(Cortex7885.parseBackup8324(null))
        val stored = CortexCalibration7901().also { c -> repeat(150) { i -> c.learn("CORE", 1.0 + (i % 5), 0.0, (i % 7).toDouble()) } }
        val boot = CortexCalibration7901()
        assertEquals(0, boot.laneCount8324())
        assertEquals(1, boot.mergeFrom8324(stored))
        assertEquals(1, boot.laneCount8324())
        assertEquals(stored.slope("CORE"), boot.slope("CORE"), 1e-12)
        assertEquals("a lane with more grades is not replaced", 0, stored.mergeFrom8324(CortexCalibration7901()))
    }

    @Test fun theStoreCanNoLongerBeOverwrittenByUnreadBooks() {
        val cx = src("engine/cortex/Cortex7885.kt")
        val commit = cx.substringAfter("private fun durableCloseCommit8198(receipt: String): Boolean {").substringBefore("val blobs")
        assertTrue(commit.contains("if (!loaded) { inc(\"CORTEX_CLOSE_COMMIT_UNLOADED_REFUSED_8324\"); return false }"))
        assertTrue(cx.contains("if (e is VirtualMachineError) { loaded = false; inc(\"CORTEX_LOAD_VM_ERROR_RETRY_8324\") }"))
        assertTrue(cx.contains("try { afterLoad8324(raw8324) }"))
        assertTrue(cx.contains("store (§8324 restore without reset): bootSeasoned=\$bootSeasoned8324"))
        assertTrue(cx.contains("LearningPersistence.loadQuarantined8324(PERSIST_KEY)"))
        assertTrue(src("engine/LearningPersistence.kt").contains("readSliced8086(d, \"BLOBPART\", \"\$name#p\$i#corrupt_8086\")"))
    }

    // ── 2. assessment hot path ──

    @Test fun oneFusionPassMatchesFusePlusSnapshot() {
        val led = CortexLedger7885()
        val edges = doubleArrayOf(0.0, 10.0)
        // a seasoned informative voter and a noise voter
        repeat(400) { i ->
            val raw = if (i % 2 == 0) 15.0 else -5.0
            val y = if (i % 2 == 0) 8.0 else -12.0
            led.grade("SHITCOIN", listOf("GOOD", "NOISE"), listOf(edges, edges), doubleArrayOf(raw, (i % 3).toDouble() * 5.0), y, y)
        }
        val votes = listOf(CortexLedger7885.Vote("GOOD", edges, 15.0, setOf("a")), CortexLedger7885.Vote("NOISE", edges, 5.0, setOf("b")),
            CortexLedger7885.Vote("ABSENT", edges, Double.NaN, setOf("c")))
        val (fused, snap) = led.fuseWithSnapshot8324("SHITCOIN", votes)
        val ref = led.fuse("SHITCOIN", votes)
        val refSnap = led.predictionSnapshot8076("SHITCOIN", votes.map { it.voterId }, votes.map { it.edges }, doubleArrayOf(15.0, 5.0, Double.NaN))
        assertEquals(ref.edgePct, fused.edgePct, 1e-12)
        assertEquals(ref.runnerRate, fused.runnerRate, 1e-12)
        assertEquals(refSnap.base, snap.base, 1e-12)
        for (i in votes.indices) {
            if (refSnap.preds[i].isNaN()) assertTrue(snap.preds[i].isNaN()) else assertEquals(refSnap.preds[i], snap.preds[i], 1e-12)
        }
        assertEquals(led.lanes["SHITCOIN"]!!.runnerRate(), fused.laneRunnerRate8324, 1e-12)
        val cx = src("engine/cortex/Cortex7885.kt")
        // superseded by V5.0.8325: the one critical section fuses the base votes plus the cross votes
        assertTrue(cx.contains("ledger.fuseWithSnapshot8324(lane, baseVotes8325 + cross, regime)"))
        assertTrue(cx.contains("assessLockWait8324["))
    }

    // ── 3. provenance, not size ──

    @Test fun aWitnessedTailIsRealAtAnySizeAndAnUnwitnessedOneKeepsTheSizeRule() {
        val cap = CortexLedger7885.LABEL_CAP_8187
        // 5 pump runners at +8,000% among 95 losers: sum per runner ~ +7,600% > 3 x cap
        val sum = 5 * 8_000.0 - 95 * 20.0
        assertFalse(CortexLedger7885.tailPlausible8320(sum, 5.0, cap))
        assertTrue(CortexLedger7885.tailPlausible8320(sum, 5.0, cap, witnessed = 5.0))
        assertTrue(CortexLedger7885.tailPlausible8320(sum, 5.0, cap, witnessed = 4.0))                 // 80%
        assertFalse("mixed basis keeps the size rule", CortexLedger7885.tailPlausible8320(sum, 5.0, cap, witnessed = 3.0))
        assertFalse("never on fewer than 3 runners", CortexLedger7885.tailPlausible8320(2 * 8_000.0, 2.0, cap, witnessed = 2.0))
        assertEquals(0.8, CortexLedger7885.WITNESS_SHARE_8324, 0.0)
        assertTrue(CortexLedger7885.provenanceWitnessed8324(5.0, 4.0))
        // the independent label witness: the coin's own fresh curve agrees (cap follows price and is not independent)
        assertTrue(ForwardReturnLabeler7731.witnessed8324(0.0012, 0.0010, false))
        assertFalse("a mark 3x off the curve is not witnessed", ForwardReturnLabeler7731.witnessed8324(0.0030, 0.0010, false))
        assertFalse("market cap co-move cannot witness its own source mark", ForwardReturnLabeler7731.witnessed8324(0.0030, 0.0, true))
        assertFalse(ForwardReturnLabeler7731.witnessed8324(0.0030, 0.0, false))
    }

    @Test fun theWitnessCountFollowsAddsRevisionsDecayAndARestart() {
        val st = CortexLedger7885.labelStat8187()
        repeat(95) { st.add(-20.0, false) }
        repeat(5) { st.add(8_000.0, true, witnessed = true) }
        assertEquals(5.0, st.witnessed8324, 1e-9)
        assertTrue(st.tailReal8322())
        assertEquals(st.rawMean8187(), st.mean(), 1e-9)
        st.add(9_000.0, true)                                        // one unverified runner: 5/6 still >= 80%
        assertTrue(st.tailReal8322())
        st.revise(9_000.0, 9_000.0, true, true, 1.0, witnessedOld = false, witnessedNew = true)
        assertEquals(6.0, st.witnessed8324, 1e-9)
        st.revise(8_000.0, -30.0, true, false, 1.0, witnessedOld = true, witnessedNew = false)
        assertEquals(5.0, st.witnessed8324, 1e-9)
        st.scale(0.5)
        assertEquals(2.5, st.witnessed8324, 1e-9)
        val back = CortexLedger7885.labelStat8187().also { it.decode(st.encode()) }
        assertEquals(st.witnessed8324, back.witnessed8324, 1e-9)
        assertEquals(st.runners, back.runners, 1e-9)
        // a 6-field row saved before 8324 reads no witnessed runners
        assertEquals(0.0, CortexLedger7885.labelStat8187().also { it.decode("10.0,100.0,2000.0,1.0,3.0,0.0") }.witnessed8324, 0.0)
    }

    @Test fun aHiveHeavyCellIsJudgedOnItsLocalTail() {
        // local: 4 runners at +1,500% in 40 labels; the hive adds 2,000 labels with a large sum
        val localSum = 4 * 1_500.0 - 36 * 12.0
        val n = 2_040; val sum = localSum + 400_000.0; val sumSq = 4 * 1_500.0 * 1_500.0 + 36 * 144.0 + 2.0e8
        val before = ForwardReturnLabeler7731.tailRead8322("CELL|SHITCOIN|MC_LT10K", n, sum, sumSq, 300, 4)
        assertTrue("8320 divided the hive-inflated sum by the local runners", before.suspect)
        val after = ForwardReturnLabeler7731.tailRead8322("CELL|SHITCOIN|MC_LT10K", n, sum, sumSq, 300, 4, localSum, 0)
        assertFalse(after.suspect)
        assertEquals(sum / n, after.mean, 1e-9)
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("var w8324 = gross >= RUNNER_PCT_7731 && witnessNow8324(o, nowMs)"))
        assertTrue(frl.contains("if (gross >= RUNNER_PCT_7731) { t.runner60 += 1; if (witnessed8324) t.witness60 += 1 }"))
        val t = ForwardReturnLabeler7731.Tally().also { it.decode("0,0,0,10,100.0,5000.0,3,2,0,0,0,0,2") }
        assertEquals("pre-8365 cap-only witnesses are not independently proven", 0, t.witness60)
        t.witness60 = 2
        assertTrue(t.encode().endsWith("INDEPENDENT_CURVE_8365"))
        val restored = ForwardReturnLabeler7731.Tally().also { it.decode(t.encode()) }
        assertEquals(2, restored.witness60)
        assertTrue(ForwardReturnLabeler7731.Tally().decode("0,0,0,10,100.0,5000.0,3,2,0,0,0,0"))
    }

    @Test fun aTailPondReadsItsWitnessedRunnersRaw() {
        val xs = List(95) { -20.0 } + List(5) { 8_000.0 }
        val caps = List(xs.size) { CortexLedger7885.LABEL_CAP_8187 }
        val unwitnessed = CortexTailEv8324.pond8324(xs, caps)!!
        assertFalse(unwitnessed.plausible)
        assertEquals(CortexLedger7885.LABEL_CAP_8187, unwitnessed.eRun, 1e-9)
        val witnessed = CortexTailEv8324.pond8324(xs, caps, witnessed = List(95) { false } + List(5) { true })!!
        assertTrue(witnessed.plausible)
        assertEquals(8_000.0, witnessed.eRun, 1e-9)
        // superseded by V5.0.8325: the pond rows are copied in one pass and the pond computed outside the lock
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("CortexTailEv8324.pond8324(r.values, r.caps, witnessed = r.witnessed)"))
    }

    @Test fun theVetoAuditRetiresOnARealTailButNotOnBasisErrors() {
        // 50 refused coins: 6 witnessed runners at +2,500%, the rest -15%: winsorised proof fails, the raw tail proves
        val st = CortexLedger7885.labelStat8187()
        repeat(44) { st.add(-15.0, false) }
        repeat(6) { st.add(2_500.0, true, witnessed = true) }
        assertTrue(st.tailReal8322())
        // superseded by V5.0.8325: a veto no longer retires on its raw tail read alone (tail evidence never admits by itself)
        assertFalse(Cortex7885.vetoRobustProof8185(st))
        assertFalse(Cortex7885.vetoProvenWinners8006(st))
        // the same shape at +60,000% without a witness stays bounded
        val bad = CortexLedger7885.labelStat8187()
        repeat(44) { bad.add(-15.0, false) }
        repeat(6) { bad.add(60_000.0, true) }
        assertFalse(bad.tailReal8322())
        assertFalse(Cortex7885.vetoRobustProof8185(bad))
    }

    // ── 4. the cheat sheet's hive prior, confirmed locally ──

    @Test fun aStrongHivePriorFavoursOnceLocalLabelsConfirmIt() {
        val base = Record8323("LAUNCH_LADDER_PROVEN", 0.5, 15.0, 30.0, 25.0, 0.1,
            hiveN = 824.0, hiveMeanPct = 40.0, hiveSePct = 6.0, localN = 5.0, localMeanPct = 12.0)
        assertTrue(LaneCheatSheet8323.hiveFavour8324(base))
        // superseded by V5.0.8325: the hive never puts a setup into favour (5 local labels are not the 20-label local record)
        assertEquals(Verdict8323.NEUTRAL, LaneCheatSheet8323.judge8323(base, Verdict8323.NEUTRAL))
        assertFalse("no local confirmation", LaneCheatSheet8323.hiveFavour8324(base.copy(localN = 4.0)))
        assertFalse("local labels contradict", LaneCheatSheet8323.hiveFavour8324(base.copy(localMeanPct = -3.0)))
        assertFalse("a thin network record", LaneCheatSheet8323.hiveFavour8324(base.copy(hiveN = 60.0)))
        assertFalse("bounded hive read must clear the prior by one SE", LaneCheatSheet8323.hiveFavour8324(base.copy(hiveMeanPct = 5.0)))
        assertFalse("live closes losing", LaneCheatSheet8323.hiveFavour8324(base.copy(liveN = 3.0, liveMeanPct = -8.0)))
        assertEquals(100.0, LaneCheatSheet8323.HIVE_FAVOUR_MIN_N_8324, 0.0)
        assertEquals(5.0, LaneCheatSheet8323.LOCAL_CONFIRM_N_8324, 0.0)
        assertEquals(10, LaneCheatSheet8323.FAVOUR_ADMIT_MIN_N_8324)
        assertTrue(src("engine/cortex/LanePlaybook7907.kt").contains("hiveMeanPct = hiveRead8324?.mean ?: Double.NaN,"))
        // superseded by V5.0.8325: admission reads FAVOUR on 20+ labels again
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("e.n >= com.lifecyclebot.engine.cortex.LaneCheatSheet8323.FAVOUR_MIN_N_8323 &&"))
    }
}
