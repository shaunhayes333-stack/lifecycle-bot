package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.sell.FrozenHoldingGuard8136
import com.lifecyclebot.engine.truth.AdmissionHandoff8144
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.RobustLabelRead8185
import com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253
import com.lifecyclebot.perps.crypto.CryptoFinalBuyCandidate.PreFdgVerdict
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8185 — "fix the rest" after 8179 / 8180. Behaviour tests for each remaining gap: outlier labels no longer prove a
 * cell or retire a veto (the stored aggregates are kept, only the read is robust); a missing-holders deferral asks the
 * holder client; an open hand-off target is protected from pruning and re-hydrated; frozen holds survive a restart and a
 * thaw is proven from a parsed account read; a WATCH-score crypto candidate is refused under its true name; the hand-off
 * status line counts the 8180 routing.
 */
class Aate8185RemainingGapsTest {
    private val tk = HandoffReceiverTasks8153
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val meme = "So1MemeMint8185aaaaaaaaaaaaaaaaaaaaaaaaapump"

    @Before fun setUp() { tk.resetForTests8153(); HandoffRetention8185.resetForTests8185(); WalletTokenAccountStateAuthority7253.resetForTests8185() }
    @After fun tearDown() { tk.resetForTests8153(); HandoffRetention8185.resetForTests8185(); WalletTokenAccountStateAuthority7253.resetForTests8185() }

    /** 141 refused candidates, ~15% "labelled" at a basis-error +5,000%, the rest small losers (5.0.8175 NATIVE_MCAP_TOO_HIGH shape). */
    private fun poisoned(): CortexLedger7885.Stat = CortexLedger7885.Stat().also { s ->
        repeat(141) { i -> s.add(if (i % 7 == 0) 5_000.0 else -10.0, i % 7 == 0) }
    }

    // ── 1. robust label read ──

    @Test fun outlierLabelsNoLongerRetireAVeto() {
        val st = poisoned()
        assertTrue("raw mean is the +700% the audit printed", st.mean() > 500.0)
        assertTrue("the old test alone would retire it", st.mean() - kotlin.math.sqrt(st.variance() / st.n) > 2.0)
        assertFalse(Cortex7885.vetoRobustProof8185(st))
        assertFalse(Cortex7885.vetoProvenWinners8006(st))
        assertTrue(RobustLabelRead8185.outlierSuspect8185(st.n, st.sum, st.wins, RobustLabelRead8185.CAP_VETO_PCT_8185))
    }

    @Test fun genuineRefusedWinnersStillRetireTheVeto() {
        val win = CortexLedger7885.Stat().also { s -> repeat(50) { s.add(if (it % 5 == 0) -4.0 else 9.0, false) } }
        assertTrue(Cortex7885.vetoRobustProof8185(win))
        assertTrue(Cortex7885.vetoProvenWinners8006(win))
        // A real launch runner inside the cap still counts toward the proof.
        val runners = CortexLedger7885.Stat().also { s -> repeat(60) { s.add(if (it % 4 == 0) 120.0 else 2.0, it % 4 == 0) } }
        assertTrue(Cortex7885.vetoProvenWinners8006(runners))
    }

    @Test fun winsorisedBoundsHoldAndPlausibleDataReadsUnchanged() {
        val r = RobustLabelRead8185
        // Bounds: lower <= winsorised mean <= upper for a known sample.
        val ys = listOf(-20.0, 5.0, 10.0, 400.0, 2_000.0)
        val cap = 300.0
        val w = ys.map { minOf(it, cap) }.average()
        val n = ys.size.toDouble(); val sum = ys.sum(); val sq = ys.sumOf { it * it }; val wins = ys.count { it > 0 }.toDouble()
        assertTrue(r.lowerMean8185(n, sum, sq, cap) <= w + 1e-9)
        assertTrue(r.upperMean8185(n, sum, wins, cap) >= w - 1e-9)
        // Within the cap the read is the raw mean and standard error.
        val plain = r.read8185("CELL|X|MC_LT10K", 4, 40.0, 1_000.0, 3)
        assertFalse(plain.suspect)
        assertEquals(10.0, plain.mean, 1e-9)
        assertEquals(kotlin.math.sqrt((1_000.0 / 4 - 100.0) / 4), plain.stderr, 1e-9)
        // A label confined to [-100, cap] cannot have a variance above (cap + 100)^2 / 4.
        assertEquals(kotlin.math.sqrt(10_000.0 / 4), r.cappedStderr8185(4.0, 1e9, 100.0), 1e-9)
        assertEquals(RobustLabelRead8185.CAP_LARGE_CAP_PCT_8185, r.capForKey8185("BAND|MOONSHOT|MC_GT1M"), 0.0)
        assertEquals(RobustLabelRead8185.CAP_LABEL_PCT_8185, r.capForKey8185("LANE|MOONSHOT"), 0.0)
    }

    @Test fun suspectCellReadsNeutralNeitherEdgeNorLoss() {
        // BAND|MOONSHOT|MC_GT1M n=67 net=+1687.9% wr=13%: 9 winners averaging ~+12,600%.
        val n = 67; val wins = 9
        val sum = 1687.9 * n
        val sumSq = wins * (sum / wins + 0.0).let { it * it } + (n - wins) * 100.0
        val read = RobustLabelRead8185.read8185("BAND|MOONSHOT|MC_GT1M", n, sum, sumSq, wins)
        assertTrue(read.suspect)
        assertTrue(read.stderr.isFinite())
        assertTrue("no proven edge", read.mean - read.stderr <= 0.0)
        assertTrue("no manufactured loss", read.mean + read.stderr > 0.0)
        assertTrue(read.mean <= wins * RobustLabelRead8185.CAP_LARGE_CAP_PCT_8185 / n + 1e-9)
        val cs = ForwardReturnLabeler7731.CellStat("k", 10, 1.0, 0.5, 0.1, 1.0, 0)
        assertFalse(cs.outlierSuspect8185)
    }

    @Test fun readsAreWiredWhereTheAuditAndLanesAggregate() {
        val c = src("engine/cortex/Cortex7885.kt")
        assertTrue(c.contains("val retired = st.mean() - se > 2.0 && vetoRobustProof8185(st)"))
        assertTrue(c.contains("OUTLIER_SUSPECT_8185"))
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("RobustLabelRead8185.read8185(key, n, sum, sumSq, wins)"))
        assertTrue(f.contains("RobustLabelRead8185.read8185(key, n, t.sum60, t.sumSq60, t.win60)"))
        assertTrue(f.contains("suspectReads=\${RobustLabelRead8185.suspectReads8185()}"))
    }

    // ── 2. holders ──

    @Test fun missingHoldersAskTheHolderClient() {
        assertTrue(HolderFetch8185.holderInput8185(listOf("holders")))
        assertFalse(HolderFetch8185.holderInput8185(listOf("bp", "liq")))
        // The deferral's "holders:UNKNOWN" survives the 8180 mapping and its key is a holder input.
        val inputs = DataDeferFetch8180.inputsFor8180(listOf("holders:UNKNOWN"))
        assertTrue(HolderFetch8185.holderInput8185(inputs.map { it.substringBefore(':').trim().lowercase() }))
        assertNull(HolderFetch8185.request8185("solana|$meme"))   // a desk identity is never asked
        assertNull(HolderFetch8185.request8185("short"))          // the client answers nothing for a non-mint (no network)
        assertTrue(HolderFetch8185.statusLine8185().contains("deskSkipped=1"))
        val d = src("engine/DataOrchestrator.kt")
        assertTrue(d.contains("if (HolderFetch8185.holderInput8185(keys)) { HolderFetch8185.request8185(mint); forcePoll8085.add(mint); lbl(\"holders\") }"))
        assertTrue(d.contains("- \"shape\" - HolderFetch8185.HOLDER_INPUTS_8185"))
    }

    // ── 3. hand-off targets ──

    @Test fun openHandoffTargetIsProtectedFromPruning() {
        val now = System.currentTimeMillis()
        assertFalse(HandoffRetention8185.protects8185(meme, now))
        assertTrue(tk.openTask8153(meme, "MOONSHOT", "PROJECT_SNIPER", "TOO_OLD_8144", 10L, now, now)?.pending == true)
        assertTrue(HandoffRetention8185.protects8185(meme, now))
        // The protection lapses with the task.
        assertFalse(HandoffRetention8185.protects8185(meme, now + HandoffReceiverTasks8153.TASK_MAX_AGE_MS_8153 + 1))
        assertTrue(HandoffRetention8185.protectedByHandoff8185(taskPending = false, handoffOpen = true))
        assertFalse(HandoffRetention8185.protectedByHandoff8185(taskPending = false, handoffOpen = false))
        val g = src("engine/MemoryGuard7977.kt")
        assertTrue(g.contains("|| HandoffRetention8185.protects8185(m, now), now - last)"))
        assertTrue(g.contains("HandoffRetention8185.protects8185(m, now),  // V5.0.8185 — an open hand-off target"))
    }

    @Test fun rehydrationIsBoundedAndOnlyForOpenTargets() {
        val h = HandoffRetention8185
        assertTrue(h.rehydrateDue8185(null, 1_000L, 0))
        assertFalse(h.rehydrateDue8185(1_000L, 1_000L + h.REHYDRATE_GAP_MS_8185 - 1, 0))
        assertTrue(h.rehydrateDue8185(1_000L, 1_000L + h.REHYDRATE_GAP_MS_8185, 0))
        assertFalse(h.rehydrateDue8185(null, 1_000L, h.REHYDRATE_PER_MIN_8185))
        // No open hand-off: nothing is re-added; a desk identity never is.
        assertFalse(h.rehydrate8185(meme))
        assertFalse(h.rehydrate8185("solana|$meme".replace("solana|", "eth|")))
        assertEquals("DEFERRED_NO_TOKEN_STATE", h.noTokenState8185("eth|0xabc"))
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("ts == null -> HandoffRetention8185.noTokenState8185(mint)"))
        assertTrue(src("engine/truth/AdmissionHandoff8144.kt").contains("HandoffRetention8185.afterEmit8185(mint, nowMs)"))
    }

    // ── 4. frozen holdings across a restart ──

    @Test fun frozenHoldsRoundTripThroughTheirFile() {
        val g = FrozenHoldingGuard8136
        val held = mapOf("MintA8185" to 111L, "MintB8185" to 222L)
        assertEquals(held, g.decode8185(g.encode8185(held)))
        assertTrue(g.decode8185("{not json").isEmpty())
        assertTrue(g.decode8185("""{"v":1,"held":{"X":0,"":5}}""").isEmpty())
    }

    @Test fun thawIsProvenOnlyByAParsedReadListingTheAccountUnfrozen() {
        val a = WalletTokenAccountStateAuthority7253
        assertFalse("no read yet: not frozen proves nothing", FrozenHoldingGuard8136.thawObserved8185("ThawMint8185"))
        a.applyParsedSnapshot(setOf("ThawMint8185", "StillFrozen8185"), setOf("StillFrozen8185"))
        assertTrue(FrozenHoldingGuard8136.thawObserved8185("ThawMint8185"))
        assertFalse(FrozenHoldingGuard8136.thawObserved8185("StillFrozen8185"))
        assertTrue(a.isFrozen("StillFrozen8185"))
        // Seen frozen again: the earlier unfrozen read no longer counts.
        a.applyParsedSnapshot(setOf("ThawMint8185"), setOf("ThawMint8185"))
        assertFalse(FrozenHoldingGuard8136.thawObserved8185("ThawMint8185"))
        val live = src("engine/FrozenHeldUnsellableLive8141.kt")
        assertTrue(live.contains("if (FrozenHoldingGuard8136.thawObserved8185(mint)) {"))
        assertTrue(src("engine/sell/FrozenHoldingGuard8136.kt").contains("frozen_holding_guard_8185.json"))
    }

    // ── 5. crypto pre-FDG cause ──

    @Test fun watchScoreCandidateIsRefusedUnderItsTrueName() {
        val c = CryptoPreFdgReason8185
        assertEquals(c.WATCH_8185, c.reason8185(false, PreFdgVerdict.WATCH, emptyList()))
        assertEquals("CRYPTO_WATCH_SCORE_BELOW_ENTRY_8185", c.WATCH_8185)
        assertEquals(c.NO_BUY_8185, c.reason8185(false, PreFdgVerdict.NO_BUY, emptyList()))
        assertEquals(c.HARD_NO_8185, c.reason8185(false, PreFdgVerdict.BUY, listOf("RUG")))
        assertEquals(c.DISCIPLINE_7835, c.reason8185(false, PreFdgVerdict.BUY, emptyList()))   // rug blacklist
        // Same trade decision: a reason exactly when the discipline check failed.
        for (v in PreFdgVerdict.values()) assertNull(c.reason8185(true, v, emptyList()))
        assertTrue(src("perps/CryptoAltTrader.kt").contains("CryptoPreFdgReason8185.reasonFor8185(disciplinePassed6647, candidate)"))
    }

    // ── 6. hand-off status line ──

    @Test fun handoffStatusCountsThe8180Routing() {
        val s = HandoffStatus8185
        assertEquals(s.BY_FLOORS, s.routeCounter8185("MOONSHOT", "SHITCOIN"))
        assertEquals(s.AS_NAMED, s.routeCounter8185("moonshot", "MOONSHOT"))
        val line = s.line8185(mapOf(s.DESK_SKIPPED to 3L, s.WITHHELD to 2L, s.BY_FLOORS to 4L, s.AS_NAMED to 9L), mapOf("protected" to 5L, "rehydrated" to 1L))
        assertTrue(line, line.contains("route8180[deskSkipped=3 withheldNoLaneFits=2 receiverByFloors=4 receiverAsNamed=9]"))
        assertTrue(line, line.contains("rehydrate8185[protected=5 rehydrated=1 refused=0 throttled=0]"))
        val status = AdmissionHandoff8144.statusLine8144()
        assertTrue(status, status.contains("route8180[deskSkipped="))
        assertTrue(status, status.contains("taskDeferred="))
    }
}
