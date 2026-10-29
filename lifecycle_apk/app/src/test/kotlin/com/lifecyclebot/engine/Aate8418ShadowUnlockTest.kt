package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.EvidenceShadow8344
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import com.lifecyclebot.engine.truth.ShadowPersist8355
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 — owner: "Shadow proof can unlock, not size" (DIRECTIVE_LEDGER_8367 #36). In LIVE the shadow traders' proof
 * (paper bar: 30 closes, mean - SE > 2 %, PF >= 1.3 net after cost) unlocks a held lane for ADMISSION; live size reads
 * realised live closes only, so the unlocked lane trades at the small live stake; its own realised live losses re-lock it.
 */
class Aate8418ShadowUnlockTest {
    @After fun back() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = false)
        EvidenceHold8336.enabled8336 = true
        EvidenceShadow8344.resetForTests8345()
    }

    private fun dna(mode: String) = LiveWinDNAStore.WinDNA(
        mint = "m8418$mode", symbol = "S", lane = "MOONSHOT", source = "SCANNER", phase = "LAUNCH",
        entrySetup = "BASE_BREAKOUT", chartPattern = "HIGHER_LOW", entryScore = 70, entryMcap = 50_000.0, exitMcap = 90_000.0,
        entryLiquidity = 20_000.0, holdTimeMinutes = 30, buyPercent = 60.0, pnlPct = 80.0, peakPnl = 120.0,
        exitReason = "TERMINAL", paperOrLive = mode, ts = 1_900_000_000_000L, positionId = "p8418$mode", economicEventId = "e8418$mode")

    @Test fun aLaneUnlockedByShadowProofTradesAtTheSmallLiveStake() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        EvidenceHold8336.enabled8336 = true
        EvidenceShadow8344.resetForTests8345()
        val lane = "ZZ_SHADOW_8418"
        assertEquals("held before any proof", "EVIDENCE_HOLD_8336_$lane", EvidenceHold8336.refusal8336(lane, paper = false))
        val at = EvidenceHold8336.startMs8336 + 1
        repeat(40) { EvidenceShadow8344.noteCloseForTests8345(lane, if (it % 4 == 0) -8.0 else 15.0, at + it) }
        val shadow = EvidenceShadow8344.proof8344(lane, EvidenceHold8336.startMs8336)
        assertTrue("40 shadow closes net after cost on the paper bar", shadow.proven && shadow.n == 40 && shadow.pf >= EvidenceHold8336.MIN_PF_8336)
        // ADMISSION: the shadow proof unlocks the hold in LIVE
        assertTrue(EvidenceHold8336.shadowProven8344(lane))
        assertNull(EvidenceHold8336.refusal8336(lane, paper = false))
        // V5.0.8420 — the shadow book proves the lane lane-wide again (niche veto / timing wait), but it is still no size input:
        assertTrue(EvidenceHold8336.laneWideProven8392(lane))
        // no realised live closes -> no realised proof stake -> any label share is capped to 0 -> the route-minimum small stake
        val realised = LiveRiskPolicy7807.realisedProofKellyFrac8188(emptyList(), emptyList())
        assertEquals(0.0, realised, 0.0)
        assertEquals(0.0, LiveRiskPolicy7807.validatedKellyFrac8188(0.25, realised, LiveRiskPolicy7807.liveOnlyProof8418(0.20)), 0.0)
        // once the lane's own live closes prove, they (and only they) lift the stake
        val liveRets = List(12) { if (it % 3 == 0) -5.0 else 20.0 }
        val liveFrac = LiveRiskPolicy7807.realisedProofKellyFrac8188(liveRets, liveRets.map { it / 1_000.0 })
        assertTrue(LiveRiskPolicy7807.validatedKellyFrac8188(0.25, liveFrac, 0.0) == minOf(0.25, liveFrac))
    }

    @Test fun realisedLiveLossesReLockAShadowUnlockedLane() {
        val proven = EvidenceHold8336.Proof8336(40, 9.0, 1.5, 2.1, true)
        val live = EvidenceHold8336.proof8336(listOf(-6.0, -4.0, -9.0), listOf(-0.002, -0.001, -0.003))
        val open = EvidenceHold8336.laneUnlock8418("MOONSHOT", proven, false, false, live, null)
        assertTrue(open.unlocked); assertFalse(open.relocked)
        val shut = EvidenceHold8336.laneUnlock8418("MOONSHOT", proven, false, false, live, "ENTRY8114_LANE_REALISED_LOSING_BINDS_8329_MOONSHOT")
        assertFalse("its own realised live losses re-lock it", shut.unlocked); assertTrue(shut.relocked)
        val unproven = EvidenceHold8336.laneUnlock8418("MOONSHOT", proven.copy(proven = false), false, false, live, null)
        assertFalse(unproven.unlocked); assertFalse(unproven.relocked)
        assertTrue(EvidenceHold8336.unlockLine8418().contains("MOONSHOT shadow=n"))
    }

    @Test fun liveSizeBoostReadsLiveClosesOnly() {
        assertTrue(LiveWinDNAStore.liveSizingRow8418(dna("LIVE")))
        assertFalse("a paper win never sizes live", LiveWinDNAStore.liveSizingRow8418(dna("PAPER")))
    }

    @Test fun onlyFullExecutableCostShadowRowsCountTowardTheProof() {
        val at = 1_950_000_000_000.0
        assertTrue(ShadowPersist8355.proofEligible8394(ShadowPersist8355.TRADE_CONTRACT_8394.toDouble(), at, 0L))
        assertFalse("a pre-8394 row (not graded at the full executable cost) does not count",
            ShadowPersist8355.proofEligible8394(8393.0, at, 0L))
        assertFalse(ShadowPersist8355.proofEligible8394(null, at, 0L))
    }
}
