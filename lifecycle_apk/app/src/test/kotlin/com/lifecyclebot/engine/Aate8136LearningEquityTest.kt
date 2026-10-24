package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexRealisedAuthority8136
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.truth.AateBrainContribution6512
import com.lifecyclebot.engine.truth.AateEntryIdentity8136
import com.lifecyclebot.engine.truth.AateEntryIdentityBook8136
import com.lifecyclebot.engine.truth.AateEntryIdentityBook8136.Attribution8136 as Attr
import com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464
import com.lifecyclebot.engine.truth.CapitalDrawdown7948
import com.lifecyclebot.engine.truth.CashEquity8136
import com.lifecyclebot.engine.truth.CashEquityBook8136
import com.lifecyclebot.engine.truth.EntryIdentityStore8136
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.LiveSizingTruth8136
import com.lifecyclebot.engine.truth.PositionTerminalOnce8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8136 — learning/reward attribution and cash equity, drawdown, compounding. */
class Aate8136LearningEquityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun inputs(
        lane: String = "QUALITY", equity: Double = 1.0, upstream: Double = 0.18, execMin: Double = 0.025,
        stop: Double? = 10.0, first: Double? = 30.0, target: Double? = 40.0, liq: Double = 200_000.0,
        dd: Double = 0.0, open: Int = 0, kelly: Double = 0.0, ceiling: Double = 0.0,
        maturity: LiveSizingTruth8136.EdgeMaturity8136? = null,
    ) = LiveRiskPolicy7807.Inputs(
        lane = lane, equitySol = equity, upstreamSol = upstream, execMinSol = execMin,
        planStopPct = stop, planFirstTargetPct = first, planTargetPct = target,
        solUsd = 200.0, liquidityUsd = liq, liveCloses = 0, liveMeanNetPct = 0.0,
        liveTotalNetSol = 0.0, liveWrPct = 0.0, drawdownFrac = dd, laneDailyLossSol = 0.0,
        governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = open,
        edgeKellyFrac8057 = kelly, compoundingCeilingSol8094 = ceiling, edgeMaturity8136 = maturity,
    )

    /** 8127's proven SHITCOIN candidate: 0.2281 SOL equity, route minimum 0.02892, quarter-Kelly share 0.20. */
    private fun proven(dd: Double = 0.0, ceiling: Double = 0.2281 * 0.25, maturity: LiveSizingTruth8136.EdgeMaturity8136? = null) =
        inputs(lane = "SHITCOIN", equity = 0.2281, upstream = 0.02892, execMin = 0.02892, dd = dd, kelly = 0.20,
            ceiling = ceiling, maturity = maturity)

    // ── equity / drawdown ──

    @Test fun phantomMarkRaisesNeitherThePeakNorTheSize() {
        // 8127: ~0.40 SOL liquid + 0.05 principal, SAC's phantom +0.6777 SOL mark.
        val e = CashEquity8136(liquidSol = 0.40, reservedTxSol = 0.012, openPrincipalSol = 0.05,
            validatedUnrealisedSol = 0.6777, settledRealisedSol = 0.0, netExternalFlowSol = 0.0)
        assertEquals(0.45, CashEquityBook8136.riskEquitySol8136(e), 1e-9)          // the gain never counts
        assertEquals(1.1277, CashEquityBook8136.markedEquitySol8136(e), 1e-9)      // display only
        assertEquals(0.388, CashEquityBook8136.tradeableSol8136(e), 1e-9)          // the reserve never funds an order
        // The peak candidate is gain-free: the phantom mark cannot set a 24h high.
        val t0 = 1_000_000L
        val peak = CapitalDrawdown7948.Peak7948(0.45, t0)
        val cand = CapitalDrawdown7948.peakCandidate7990(CapitalDrawdown7948.gainFreeEquity8130(1.1277, 0.6777), 0.45)
        assertEquals(0.45, CapitalDrawdown7948.rollPeak7948(peak, cand, t0 + 60_000L).peakSol, 1e-9)
        // Drawdown on the same basis: the phantom gain neither masks a REAL drawdown nor invents one when it vanishes.
        val realPeak = CapitalDrawdown7948.Peak7948(0.60, t0)
        val ddEq = CashEquityBook8136.drawdownEquitySol8136(1.1277, 0.6777, 0.6777)
        assertEquals(0.45, ddEq, 1e-9)
        assertEquals(25.0, CapitalDrawdown7948.drawdownPct7948(realPeak, ddEq, t0 + 60_000L), 1e-6)
        assertEquals(0.0, CapitalDrawdown7948.drawdownPct7948(realPeak, 1.1277, t0 + 60_000L), 1e-9)   // the old marked read
        assertEquals(0.0, CapitalDrawdown7948.drawdownPct7948(peak, CashEquityBook8136.drawdownEquitySol8136(0.45, 0.0, 0.6777), t0 + 60_000L), 1e-9)
        // So the size shrinks for the real drawdown, never grows for the mark.
        assertTrue(LiveRiskPolicy7807.drawdownMultiplier(0.25) < 1.0)
        // A one-read crash mark is not a drawdown; a loss that holds for a second read is.
        assertEquals(0.45, CashEquityBook8136.drawdownEquitySol8136(0.25, -0.20, 0.0), 1e-9)
        assertEquals(0.25, CashEquityBook8136.drawdownEquitySol8136(0.25, -0.20, -0.20), 1e-9)
        assertEquals(0.25, CashEquityBook8136.drawdownEquitySol8136(0.25, -0.20, Double.NaN), 1e-9)  // no history: counted
        assertEquals(-0.05, CashEquityBook8136.confirmedUnrealisedLoss8136(-0.20, -0.05), 1e-12)
        assertEquals(0.0, CashEquityBook8136.confirmedUnrealisedLoss8136(0.30, -0.05), 1e-12)        // a gain is never a loss
    }

    @Test fun aDepositIsNotProfitAndAWithdrawalIsNotALoss() {
        val afterDeposit = CashEquity8136(0.90, 0.012, 0.0, 0.0, 0.0, netExternalFlowSol = 0.50)
        assertEquals(0.0, CashEquityBook8136.tradingPnlSol8136(0.40, afterDeposit), 1e-9)
        assertEquals(0.0, CashEquityBook8136.dailyPnlPct8136(0.90, 0.40, 0.50), 1e-9)
        assertEquals(0.0, CashEquityBook8136.dailyPnlPct8136(0.20, 0.40, -0.20), 1e-9)
        assertEquals(-10.0, CashEquityBook8136.dailyPnlPct8136(0.36, 0.40, 0.0), 1e-9)   // a real loss still reads
        // The anti-reward-hack high moves with the flow (never below zero).
        assertEquals(0.6, CashEquityBook8136.shiftedHigh8136(1.0, -0.4), 1e-9)
        assertEquals(1.5, CashEquityBook8136.shiftedHigh8136(1.0, 0.5), 1e-9)
        assertEquals(0.0, CashEquityBook8136.shiftedHigh8136(0.3, -0.5), 1e-9)
        val ks = src("engine/KillSwitch.kt")
        assertTrue(ks.contains("dailyPnlPct8136(currentBalance)"))
        assertTrue(ks.contains("prefs.getBoolean(\"peak_gain_free_8130\", false)"))
        assertTrue(ks.contains("CapitalDrawdown7948.gainFreeEquity8130(currentBalance, heroUpnl8136())"))
        val cd = src("engine/truth/CapitalDrawdown7948.kt")
        assertTrue(cd.contains("peakCandidate7990(gainFreeEquity8130(equitySol, upnl8130), prevPeakObs7990)"))
        assertTrue(cd.contains("drawdownPct7948(peak7948, observedDrawdownEquity8136(equitySol, upnl8130), nowMs)"))
        assertTrue(cd.contains("noteExternalFlow8136(flow, nowMs)"))
    }

    // ── compounding: drawdown waiver needs a mature realised edge ──

    @Test fun anImmatureKellyEdgeDoesNotWaiveTheDrawdownShrink() {
        val flat = LiveRiskPolicy7807.decide(proven())
        val young = LiveRiskPolicy7807.decide(proven(dd = 0.259,
            maturity = LiveSizingTruth8136.EdgeMaturity8136(realisedN = 4, realisedMeanPct = 30.0, realisedSePct = 10.0, realisedNetSol = 0.05)))
        assertTrue(young.labels.contains("WALLET_DRAWDOWN_SHRINK_7807"))
        assertTrue(young.labels.contains("DRAWDOWN_WAIVER_DENIED_IMMATURE_EDGE_8136"))
        assertFalse(young.labels.contains("DRAWDOWN_SHRINK_WAIVED_PROVEN_EDGE_8094"))
        assertTrue("${young.sizeSol} vs ${flat.sizeSol}", young.sizeSol < flat.sizeSol)
        // Positive labels, losing real closes: no waiver either.
        val losing = LiveRiskPolicy7807.decide(proven(dd = 0.259,
            maturity = LiveSizingTruth8136.EdgeMaturity8136(25, -5.0, 2.0, -0.10)))
        assertTrue(losing.labels.contains("DRAWDOWN_WAIVER_DENIED_IMMATURE_EDGE_8136"))
        // Mean - SE not above zero: immature.
        assertFalse(LiveSizingTruth8136.edgeMature8136(LiveSizingTruth8136.EdgeMaturity8136(25, 3.0, 4.0, 0.2)))
        assertFalse(LiveSizingTruth8136.drawdownWaiver8136(0.20, 0.259, LiveSizingTruth8136.EdgeMaturity8136(25, 3.0, 4.0, 0.2)))
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("edgeMaturity8136 = laneMaturity8136(effectiveLane7811, nowMs)"))
    }

    @Test fun aMatureRealisedEdgeWaivesTheDrawdownShrink() {
        val mature = LiveSizingTruth8136.EdgeMaturity8136(25, 12.0, 3.0, 0.40)
        assertTrue(LiveSizingTruth8136.edgeMature8136(mature))
        val flat = LiveRiskPolicy7807.decide(proven())
        val d = LiveRiskPolicy7807.decide(proven(dd = 0.259, maturity = mature))
        assertTrue(d.labels.contains("DRAWDOWN_SHRINK_WAIVED_PROVEN_EDGE_8094"))
        assertEquals(flat.sizeSol, d.sizeSol, 1e-9)
        // Every hard cap still binds.
        assertTrue(d.sizeSol <= 0.2281 * LiveRiskPolicy7807.budgetFor("SHITCOIN").maxPositionEquityFrac + 1e-9)
        // No Kelly share, no waiver, however mature the record.
        assertFalse(LiveSizingTruth8136.drawdownWaiver8136(0.0, 0.259, mature))
        // The realised record's own statistics.
        val m = LiveSizingTruth8136.maturityOf8136(List(12) { if (it % 3 == 0) -4.0 else 10.0 }, List(12) { 0.01 })
        assertEquals(12, m.realisedN)
        assertEquals(5.333333, m.realisedMeanPct, 1e-5)
        assertTrue(m.realisedSePct > 0.0 && m.realisedSePct < m.realisedMeanPct)
        assertEquals(0.12, m.realisedNetSol, 1e-9)
        assertTrue(LiveSizingTruth8136.edgeMature8136(m))
        assertEquals(Double.POSITIVE_INFINITY, LiveSizingTruth8136.twoPointSe8136(1, 50.0, 10.0, -5.0, 2.5), 0.0)
        assertTrue(LiveSizingTruth8136.twoPointSe8136(20, 50.0, 10.0, -5.0, 2.5).isFinite())
    }

    @Test fun theBindingConstraintIsNamed() {
        fun b(i: LiveRiskPolicy7807.Inputs) = LiveSizingTruth8136.bindingConstraintOf8136(i, LiveRiskPolicy7807.decide(i))
        // 8127's shape: Kelly-proven, but no compounding ceiling offered -> sealed at the proposal (the route minimum).
        assertEquals("UPSTREAM_PROPOSAL_NO_COMPOUNDING_CEILING", b(proven(ceiling = 0.0)))
        assertEquals("COMPOUNDING_CEILING", b(proven(ceiling = 0.035)))
        assertTrue(b(proven()).startsWith("KELLY:"))
        // Drawdown-damped immature Kelly under the minimum, promoted to it.
        // superseded by V5.0.8188 — the drawdown-damped Kelly size (~0.0200) promoted to 0.02892 (+45%) and the 7840 shape
        // (0.02274 -> 0.0414) both break their original loss budget at the stop: refused, named (MinPromotion8188).
        assertEquals("REFUSED:" + com.lifecyclebot.engine.truth.MinPromotion8188.REFUSED_LOSS_BUDGET_8188, b(proven(dd = 0.259,
            maturity = LiveSizingTruth8136.EdgeMaturity8136(4, 30.0, 10.0, 0.05))))
        assertEquals("REFUSED:" + com.lifecyclebot.engine.truth.MinPromotion8188.REFUSED_LOSS_BUDGET_8188, b(inputs(lane = "MOONSHOT", equity = 0.2113, upstream = 0.02274,
            execMin = 0.0414, stop = 8.0, first = 30.0, target = 40.0, liq = 100_000.0)))
        assertEquals("UPSTREAM_PROPOSAL", b(inputs(equity = 10.0, upstream = 0.05)))
        assertEquals("REFUSED:LANE_SLOT_CAP_7807", b(inputs(open = LiveRiskPolicy7807.LANE_SLOTS_8098)))
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("binding8136=["))
    }

    // ── one terminal per position ──

    @Test fun aDuplicateTerminalDeliveryIsIgnored() {
        val once = PositionTerminalOnce8136()
        assertTrue(once.admit("P1", "P1"))
        assertTrue(once.admit("P1", "P1"))        // the same terminal again: the bus's own tradeId dedupe answers it
        assertFalse(once.admit("P1", "REPAIR-P1")) // a second terminal for the same position under another id
        assertTrue(once.admit("P2", "P2"))
        CanonicalFinalizedTradeBus6464.resetForTest()
        fun env(t: String, p: String) = CanonicalFinalizedTradeBus6464.Envelope(
            tradeId = t, atMs = 1L, realizedPnlSol = -0.01, realizedReturnPct = -5.0, mint = "M8136", lane = "SHITCOIN", positionId = p,
        )
        assertTrue(CanonicalFinalizedTradeBus6464.publish(env("pos8136", "pos8136")))
        assertFalse(CanonicalFinalizedTradeBus6464.publish(env("pos8136", "pos8136")))
        assertFalse(CanonicalFinalizedTradeBus6464.publish(env("pos8136-repair", "pos8136")))
        assertEquals(1, CanonicalFinalizedTradeBus6464.canonicalUnique())
        assertTrue(CanonicalFinalizedTradeBus6464.statusLine().contains("duplicatePositions8136=1"))
        CanonicalFinalizedTradeBus6464.resetForTest()
        // The sealed entry identity is delivered once, survives partials unchanged, and a closed position never re-seals.
        val store = EntryIdentityStore8136()
        val id = identity("posA")
        assertTrue(store.seal(id))
        assertTrue(store.seal(id))                                              // same decision: idempotent
        assertFalse(store.seal(id.copy(candidateVersion = 99L)))                // a different decision: refused
        store.fillBlanks("posA", "OTHER_SETUP", "v2", "DIP", "TRAIL")
        assertEquals("LAUNCH_CONTINUATION", store.get("posA")?.exactSetup)    // sealed values never overwritten
        assertEquals("TRAIL", store.get("posA")?.exitPolicy)                   // blanks filled from the entry snapshot
        assertNotNull(store.terminal("posA", 5L))
        assertNull(store.terminal("posA", 6L))
        assertFalse(store.seal(id))
        assertEquals(1L, store.sealedCount.get())
        assertTrue(AateEntryIdentityBook8136.sameDecision8136(id, id.copy(exactSetup = "X", positionId = "other")))
        assertFalse(AateEntryIdentityBook8136.sameDecision8136(id, id.copy(ownerLane = "QUALITY")))
        assertEquals(2L, store.conflicts.get())   // the different decision and the reseal after the close
    }

    private fun identity(pid: String) = AateEntryIdentity8136(
        mode = "LIVE", mint = "MintA", positionId = pid, candidateVersion = 7L, ownerLane = "SHITCOIN",
        exactSetup = "LAUNCH_CONTINUATION", strategyVariant = "v1", tactic = "SCALP", entryStyle = "BREAKOUT", exitPolicy = "",
        evidenceVersion = "rev3:LEARNING", decisionTimestampMs = 1_000L, scoreFinal = 72.0, pWin = 0.61,
        expectedPnlPct = null, rugP = 0.05,
        contributors = listOf(AateBrainContribution6512(brain = "MemeDesk:SHITCOIN", role = "MEME_SPECIALIST_DESK", weight = 0.6, effect = 0.4)),
        shadow = false,
    )

    @Test fun theSealedIdentityRoundTripsAndIsWiredAtOpenAndClose() {
        val id = identity("posB")
        val back = AateEntryIdentityBook8136.decode8136(AateEntryIdentityBook8136.encode8136(id))
        assertEquals(id, back)
        assertEquals(listOf(id, identity("posC")), AateEntryIdentityBook8136.decodeAll8136(AateEntryIdentityBook8136.encodeAll8136(listOf(id, identity("posC")))))
        assertNull(AateEntryIdentityBook8136.decode8136("garbage"))
        assertEquals(0.7, AateEntryIdentityBook8136.contributorEffect8136(id.contributors), 1e-9)
        val fabric = src("engine/truth/AateDecisionEnvelope6512.kt")
        assertTrue(fabric.contains("sealEntryIdentity8136(positionId, e, hypothesisVariant7470)"))
        assertTrue(fabric.contains("(byPosition[env.positionId] ?: restoreFromIdentity8136(env))"))
        assertTrue(fabric.contains("AateEntryIdentityBook8136.terminal8136(env.positionId)"))
        assertTrue(fabric.contains("val entryIdentity8136: AateEntryIdentity8136? = null"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("positionTerminals8136.admit(env.positionId, env.tradeId)"))
    }

    // ── calibration: authority answers to realised closes ──

    @Test fun strongWithPositiveLabelsButNegativeRealisedClosesIsDeniedAuthority() {
        assertEquals(CortexRealisedAuthority8136.Verdict8136.DENIED_REALISED_NEGATIVE, CortexRealisedAuthority8136.verdict8136(25.0, -3.0, 1.0))
        assertEquals(0.0, CortexRealisedAuthority8136.cappedFraction8136(1.0, 25.0, -3.0, 1.0), 0.0)
        assertEquals(0.0, CortexRealisedAuthority8136.cappedFraction8136(1.0, 6.0, -0.5, 4.0), 0.0)   // negative, even if noisy
        // No realised record yet: labels alone never grant FULL authority.
        assertEquals(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955, CortexRealisedAuthority8136.cappedFraction8136(1.0, 2.0, 40.0, 5.0), 1e-12)
        // Positive but unproven: partial at most.
        assertEquals(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955, CortexRealisedAuthority8136.cappedFraction8136(1.0, 8.0, 3.0, 4.0), 1e-12)
        // Proven realised: the label fraction stands — and the cap never raises it.
        assertEquals(1.0, CortexRealisedAuthority8136.cappedFraction8136(1.0, CortexRealisedAuthority8136.FULL_REALISED_N_8136, 8.0, 2.0), 1e-12)
        assertEquals(CortexScoreboard7885.GRAD_PARTIAL_CAP_7955,
            CortexRealisedAuthority8136.cappedFraction8136(1.0, CortexRealisedAuthority8136.FULL_REALISED_N_8136 - 1.0, 8.0, 2.0), 1e-12)
        assertEquals(0.4, CortexRealisedAuthority8136.cappedFraction8136(0.4, 25.0, 8.0, 2.0), 1e-12)
        assertEquals(0.0, CortexRealisedAuthority8136.cappedFraction8136(0.0, 25.0, 8.0, 2.0), 0.0)
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("realisedCapped8136(lane, f)"))
        // The legacy 8136 contract above remains available for diagnostics. Current setup authority uses the same
        // frozen-cohort proof as lane authority, with calibration and realised-loss revocation still applied.
        val cell = cx.substringAfter("private fun setupCellAuthority8083(").substringBefore("\n    }\n")
        assertTrue(cell.contains("val proof = authorityTest8366(lane, setup)"))
        assertTrue(cell.contains("val (n, mean) = realisedStrong8366(lane)"))
        assertTrue(cell.contains("CortexAuthority8366.authority8366(proof,"))
        assertTrue(cell.contains("calibration.uncalibrated8372(lane, executableOffset8187(lane)), n, mean)"))
    }

    @Test fun missingAttributionIsNeverANegativeStrategySample() {
        assertEquals(Attr.UNVERIFIABLE, AateEntryIdentityBook8136.attribution8136(false, false, null))
        assertEquals(Attr.OWNER_LEDGER_ONLY, AateEntryIdentityBook8136.attribution8136(false, false, "SHITCOIN"))
        assertEquals(Attr.SEALED_IDENTITY, AateEntryIdentityBook8136.attribution8136(false, true, null))
        assertEquals(Attr.EXACT_ENVELOPE, AateEntryIdentityBook8136.attribution8136(true, false, null))
        assertFalse(AateEntryIdentityBook8136.negativeStrategySample8136(Attr.UNVERIFIABLE, -0.05))
        assertFalse(AateEntryIdentityBook8136.negativeStrategySample8136(Attr.OWNER_LEDGER_ONLY, -0.05))
        assertFalse(AateEntryIdentityBook8136.trainsStrategy8136(Attr.UNVERIFIABLE))
        assertTrue(AateEntryIdentityBook8136.negativeStrategySample8136(Attr.SEALED_IDENTITY, -0.05))
        assertFalse(AateEntryIdentityBook8136.negativeStrategySample8136(Attr.EXACT_ENVELOPE, 0.02))
        assertTrue(AateEntryIdentityBook8136.isShadowMode8136("shadow"))
        assertFalse(AateEntryIdentityBook8136.isShadowMode8136("LIVE"))
        val fabric = src("engine/truth/AateDecisionEnvelope6512.kt")
        assertTrue(fabric.contains("if (attribution8136 == AateEntryIdentityBook8136.Attribution8136.UNVERIFIABLE) {"))
        assertTrue(fabric.contains("specialistLearningOwnerOnly8136"))
        assertTrue(fabric.contains("return observeShadowOnly8136(env)"))
    }
}
