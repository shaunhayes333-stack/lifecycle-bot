package com.lifecyclebot.engine

import com.lifecyclebot.engine.SpecialistElection8136.Contender8136
import com.lifecyclebot.engine.SpecialistElection8136.RescueSlot8141
import com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141
import com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141.State8141
import com.lifecyclebot.engine.sell.FrozenHeldUnsellable8141.Transition8141
import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.PositionTerminalOnce8136
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8141 — integration gaps: tier 1 beats every hold / moonbag / lifecycle gate; an issuer-frozen holding settles
 * once as FROZEN_HELD_UNSELLABLE (slot released, never sold or zero, one terminal, a thaw re-opens the sell); at most
 * one rescue per candidate version; the recovery tranche counts confirmed legacy-fallback proceeds.
 */
class Aate8141IntegrationGapsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val t0 = 2_000_000_000L

    @Before fun setUp() {
        SpecialistElection8136.resetForTests8136()
        FrozenHeldUnsellable8141.clearForTest8141()
        RecoveryFallbackLedger8141.clearForTest8141()
    }

    @After fun tearDown() {
        SpecialistElection8136.resetForTests8136()
        FrozenHeldUnsellable8141.clearForTest8141()
        RecoveryFallbackLedger8141.clearForTest8141()
    }

    // ── 1. tier 1 beats moonbag / hold gates ──

    @Test fun tier1ExitsBypassEveryHoldOnEveryLaneIncludingMoonshot() {
        // The gap: on MOONSHOT, ProtectiveExitClass7807 let only ranks 1-2 through and did not know these spellings.
        assertFalse(ProtectiveExitClass7807.bypassesHolds("TOKEN_FROZEN", "MOONSHOT"))
        assertFalse(ProtectiveExitClass7807.bypassesHolds("LIQUIDITY_PULLED", "MOONSHOT"))
        assertTrue(Tier1Deference8141.tier1("TOKEN_FROZEN"))
        assertTrue(Tier1Deference8141.tier1("LIQUIDITY_PULLED"))
        assertTrue(Tier1Deference8141.bypassesHolds8141("TOKEN_FROZEN", "MOONSHOT"))
        assertTrue(Tier1Deference8141.bypassesHolds8141("LIQUIDITY_PULLED", "MOONSHOT"))
        assertTrue(Tier1Deference8141.bypassesHolds8141("CANNOT_SELL_ROUTE", "SHITCOIN"))
        assertFalse(Tier1Deference8141.holdMayAct8141("LIQUIDITY_PULLED"))
        // Ordinary exits keep their holds; a Moonshot runner keeps its own stop behaviour (rank 3 is not tier 1).
        assertFalse(Tier1Deference8141.bypassesHolds8141("RAPID_TRAILING_STOP", "MOONSHOT"))
        assertFalse(Tier1Deference8141.bypassesHolds8141("STRICT_SL_-12", "MOONSHOT"))
        assertTrue(Tier1Deference8141.bypassesHolds8141("STRICT_SL_-12", "SHITCOIN"))   // ProtectiveExitClass7807 unchanged
        assertTrue(Tier1Deference8141.holdMayAct8141("RAPID_TRAILING_STOP"))
    }

    @Test fun tier1BeatsTheMoonbagHoldWhileOrdinaryGivebacksStillBankAndHold() {
        // A banked moonbag still near its peak holds an ordinary trail (tier 1 does not touch ordinary give-backs)...
        assertTrue(Tier1Deference8141.holdMayAct8141("RAPID_TRAILING_STOP"))
        assertEquals(MoonbagRunner7322.Action.HOLD_MOONBAG, MoonbagRunner7322.decide("MOONSHOT", "RAPID_TRAILING_STOP", 1400.0, 1408.0, 1408.0))
        assertEquals(MoonbagRunner7322.Action.BANK_PARTIAL, MoonbagRunner7322.decide("MOONSHOT", "RAPID_TRAILING_STOP", 40.0, 60.0, null))
        // ...but a tier-1 exit that reads like a give-back is never held or banked.
        assertEquals(MoonbagRunner7322.Action.PASS, MoonbagRunner7322.decide("MOONSHOT", "LIQUIDITY_PULLED_TRAIL_STOP", 1400.0, 1408.0, 1408.0))
        assertEquals(MoonbagRunner7322.Action.PASS, MoonbagRunner7322.decide("MOONSHOT", "TOKEN_FROZEN_PROFIT_LOCK", 40.0, 60.0, null))
        // The sibling overlays agree.
        assertTrue(LifecycleHold8065.passes8065("TOKEN_FROZEN"))
        assertFalse(RunnerGrab7967.deferrable7967("LIQUIDITY_PULLED", 20.0))
        assertEquals(TailBag8034.Decision.Pass, TailBag8034.decidePure8034("MOONSHOT", "TOKEN_FROZEN", 30.0, null, t0))
    }

    @Test fun executorAndBotServiceHoldGatesDeferToTier1() {
        val e = src("engine/Executor.kt")
        val gate = e.substringAfter("private fun moonbagGate7322(").substringBefore("\n    }\n")
        val tier1 = gate.indexOf("if (Tier1Deference8141.tier1(requestReason)) return tier1PassesMoonbag8141(ts, requestReason)")
        assertTrue(tier1 >= 0)
        assertTrue(tier1 < gate.indexOf("tailBagGate8034(ts, requestReason, wallet, walletSol)"))   // before the tail bag too
        assertTrue(gate.contains("ProtectiveExitClass7807.bypassesHolds(requestReason, ts.position.tradingMode)"))   // 7807 pin intact
        val admit = e.substringAfter("private fun protectiveEmergencyAdmit7807(").substringBefore("\n    }\n")
        assertTrue(admit.contains("Tier1Deference8141.bypassesHolds8141(reason, ts.position.tradingMode)"))
        assertTrue(e.contains("val bypass7807 = protectiveEmergencyAdmit7807(ts, reason)"))
        assertTrue(e.contains("if (isLivePositionEarly && !bypass7807 && liveProfitDustExitShouldDefer(ts, reason)) {"))
        assertTrue(e.contains("if (isLivePositionEarly && !bypass7807) {"))
        assertTrue(src("engine/MoonbagRunner7322.kt").contains("if (!Tier1Deference8141.holdMayAct8141(reason)) return Action.PASS"))
        val plan = src("engine/BotService.kt").substringAfter("private fun planOwnsExit7754(").substringBefore("\n    }\n")
        assertTrue(plan.contains("if (Tier1Deference8141.tier1(exitName)) return false"))
    }

    // ── 2. frozen holdings: FROZEN_HELD_UNSELLABLE ──

    @Test fun aFrozenHoldingIsATruthfulUnsellableLossWithZeroRecoverableProceeds() {
        val total = FrozenHeldUnsellable8141.economics8141(1.0, 0.0, 0.0)!!
        assertEquals(-1.0, total.netPnlSol, 1e-12)
        assertEquals(-100.0, total.netReturnPct, 1e-9)
        assertEquals(0.0, total.recoverableProceedsSol, 0.0)
        assertEquals("LOSS", FrozenHeldUnsellable8141.outcomeOf8141(total.netReturnPct))
        // Fees on a total loss stay inside the canonical bus's trainable range (read against cost + fees).
        val withFees = FrozenHeldUnsellable8141.economics8141(1.0, 0.0, 0.01)!!
        assertEquals(-1.01, withFees.netPnlSol, 1e-12)
        assertTrue(withFees.netReturnPct >= -100.0001)
        // Proceeds already realised by confirmed slices count; the frozen remainder recovers nothing.
        val partial = FrozenHeldUnsellable8141.economics8141(1.0, 0.4, 0.0)!!
        assertEquals(-60.0, partial.netReturnPct, 1e-9)
        val paidBack = FrozenHeldUnsellable8141.economics8141(1.0, 1.5, 0.0)!!
        assertEquals("WIN", FrozenHeldUnsellable8141.outcomeOf8141(paidBack.netReturnPct))
        assertNull(FrozenHeldUnsellable8141.economics8141(0.0, 0.0, 0.0))
        // The terminal it publishes passes the bus's economics validation (it is learned, not quarantined).
        for (econ in listOf(total, withFees, partial, paidBack)) {
            val ev = CanonicalTradeFinalizedBus6450.Event(
                positionId = "p8141", mint = "m8141", outcome = CanonicalTradeFinalizedBus6450.Outcome.valueOf(FrozenHeldUnsellable8141.outcomeOf8141(econ.netReturnPct)),
                netRealizedPnlSol = econ.netPnlSol, grossRealizedPnlSol = econ.grossPnlSol, returnFraction = econ.returnFraction,
                netReturnPct = econ.netReturnPct, feesSol = econ.feesSol, entryLane = "MOONSHOT", entryStrategyPid = "",
                entryTactic = "", exitReason = FrozenHeldUnsellable8141.EXIT_REASON_8141, holdingTimeMs = 1_000L,
                dataQuality = FrozenHeldUnsellable8141.DATA_QUALITY_8141, priceIntegrity = FrozenHeldUnsellable8141.PRICE_INTEGRITY_8141,
                mode = "live", settledAtMs = 1L, economicFinality = "LIVE_RECONCILED",
            )
            assertNull(CanonicalTradeFinalizedBus6450.economicInvalidReasonForTest6495(ev))
        }
        // A distinct exit reason: a freeze loss, never a sale and never a zero balance.
        val r = FrozenHeldUnsellable8141.EXIT_REASON_8141
        assertFalse(r.contains("ZERO") || r.contains("SOLD") || r.contains("CLOSED"))
        assertTrue(Tier1Deference8141.tier1(r))
    }

    @Test fun aFrozenHoldingSettlesOnceRefusesSellsAndAThawReopensTheSell() {
        val mint = "frozen8141mint"
        assertEquals(Transition8141.SETTLE, FrozenHeldUnsellable8141.enter8141(mint, "LIVE:pos8141", "FRZ", "TOKEN_FROZEN", t0))
        assertEquals(Transition8141.NONE, FrozenHeldUnsellable8141.enter8141(mint, "LIVE:pos8141", "FRZ", "TOKEN_FROZEN", t0 + 1))   // idempotent
        assertEquals(State8141.FROZEN_HELD_UNSELLABLE, FrozenHeldUnsellable8141.stateOf8141(mint))
        assertEquals(1, FrozenHeldUnsellable8141.heldCount8141())
        assertTrue(FrozenHeldUnsellable8141.refusesSell8141(mint, frozenNow = true))    // no sell attempt while frozen
        assertFalse(FrozenHeldUnsellable8141.refusesSell8141(mint, frozenNow = false))  // a thaw is never refused
        assertFalse(FrozenHeldUnsellable8141.refusesSell8141("other8141mint", frozenNow = true))
        // Thaw: once, and the re-opened exit is tier 1 (no hold sits on it).
        val thawed = FrozenHeldUnsellable8141.thaw8141(mint)
        assertNotNull(thawed)
        assertEquals("TOKEN_FROZEN", thawed!!.desiredExitReason)
        assertEquals(State8141.THAWED_REOPENED, FrozenHeldUnsellable8141.stateOf8141(mint))
        assertNull(FrozenHeldUnsellable8141.thaw8141(mint))
        assertFalse(FrozenHeldUnsellable8141.refusesSell8141(mint, frozenNow = true))
        assertTrue(Tier1Deference8141.tier1(FrozenHeldUnsellable8141.thawExitReason8141(thawed.desiredExitReason)))
        // Frozen again after a thaw: the slot is released again, but no second terminal (only SETTLE publishes).
        assertEquals(Transition8141.RESETTLE, FrozenHeldUnsellable8141.enter8141(mint, "LIVE:pos8141", "FRZ", "TOKEN_FROZEN", t0 + 2))
        assertEquals(listOf(mint), FrozenHeldUnsellable8141.heldMints8141())
    }

    @Test fun theSettledRowIsNonProtectiveYetLearnableAndOneTerminalPerPosition() {
        assertTrue(FrozenHeldUnsellable8141.freezeLossQuarantine8141(FrozenHeldUnsellable8141.QUARANTINE_REASON_8141))
        assertTrue(FrozenHeldUnsellable8141.QUARANTINE_REASON_8141.startsWith("FROZEN_TOKEN_ACCOUNT_7253"))   // NON_PROTECTIVE prefix
        assertEquals(FrozenHeldUnsellable8141.QUARANTINE_REASON_8141, FrozenHeldUnsellable8141.quarantineReasonFor8141(""))
        assertEquals(FrozenHeldUnsellable8141.QUARANTINE_REASON_8141, FrozenHeldUnsellable8141.quarantineReasonFor8141("FROZEN_TOKEN_ACCOUNT_7253"))
        val prior = FrozenHeldUnsellable8141.quarantineReasonFor8141("BASIS_UNCERTAIN_7807:landed")
        assertTrue(prior.startsWith("FROZEN_TOKEN_ACCOUNT_7253"))
        assertFalse(FrozenHeldUnsellable8141.freezeLossQuarantine8141(prior))   // an untrusted basis stays unclean for learning
        assertFalse(FrozenHeldUnsellable8141.freezeLossQuarantine8141("EXIT_ELIGIBILITY_6570:INVALID_ENTRY_BASIS"))
        // A thawed row is protective inventory again (the exit authority manages and sells it).
        assertFalse(FrozenHeldUnsellable8141.THAW_QUARANTINE_REASON_8141.startsWith("FROZEN_TOKEN_ACCOUNT_7253"))
        assertNull(CanonicalTradeFinalizedBus6450.learningUncleanReason7807("no-such-position-8141", ""))
        // One whole-position terminal: the freeze-loss terminal stands; a later thaw sale under another id is refused.
        val once = PositionTerminalOnce8136()
        assertTrue(once.admit("LIVE:pos8141", "LIVE:pos8141"))
        assertTrue(once.admit("LIVE:pos8141", "LIVE:pos8141"))      // redelivery
        assertFalse(once.admit("LIVE:pos8141", "thaw-sale-8141"))
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        assertTrue(bus.contains("if (!positionTerminals8136.admit(env.positionId, env.tradeId)) {"))
        assertTrue(src("engine/truth/CanonicalTradeFinalizedBus6450.kt").contains("FrozenHeldUnsellable8141.freezeLossQuarantine8141(pos7776.quarantineReason)"))
    }

    @Test fun theFrozenMintIsReallyBlacklistedForBuys() {
        // 8122 blocked with FROZEN_BY_ISSUER_8122, which TokenBlacklist refuses as PENALTY_ONLY: it never blocked anything.
        assertFalse(TokenBlacklist.isTrueBlacklistReason("FROZEN_BY_ISSUER_8122"))
        assertTrue(TokenBlacklist.isTrueBlacklistReason(FrozenHeldUnsellable8141.BLACKLIST_REASON_8141))
        val mint = "frozenBlacklist8141"
        TokenBlacklist.block(mint, FrozenHeldUnsellable8141.BLACKLIST_REASON_8141)
        assertTrue(TokenBlacklist.isBlocked(mint))
        TokenBlacklist.unblock(mint)
    }

    @Test fun frozenSettlementIsWiredReleasesTheSlotAndNeverSellsOrZeroes() {
        val live = src("engine/FrozenHeldUnsellableLive8141.kt")
        assertTrue(live.contains("ts.position = Position()"))                                  // slot + exposure released
        assertTrue(live.contains("LaneExecutionCoordinator.releaseIfPrimary(ts.mint, lane, \"FROZEN_HELD_UNSELLABLE_8141\")"))
        assertTrue(live.contains("TokenBlacklist.block(mint, FrozenHeldUnsellable8141.BLACKLIST_REASON_8141)"))
        assertTrue(live.contains("CanonicalTradeFinalizedBus6450.publish("))
        assertTrue(live.contains("exitReason = FrozenHeldUnsellable8141.EXIT_REASON_8141"))
        assertTrue(live.contains("val published = transition == FrozenHeldUnsellable8141.Transition8141.SETTLE && publishTerminal8141("))
        assertTrue(live.contains("FrozenHeldUnsellable8141.quarantineReasonFor8141(canon.quarantineReason)"))
        for (forbidden in listOf("confirmZeroBalanceClose", "finalizeClosed", "ZERO_BALANCE_CONFIRMED", "recordTrade(", "partialSell(")) {
            assertFalse("frozen settlement must not $forbidden", live.contains(forbidden))
        }
        val e = src("engine/Executor.kt")
        val core = e.substringAfter("private fun requestSellCore7948(").substringBefore("\n    private fun ")
        assertTrue(core.contains("FrozenHeldUnsellableLive8141.refusesSell8141(ts, reason) } catch (_: Throwable) { false }) return SellResult.WAITING_BALANCE_PROOF"))
        val handOff = e.substringAfter("private fun frozenHandOff8122(").substringBefore("\n    }\n")
        assertTrue(handOff.contains("FrozenHeldUnsellableLive8141.settle8141(ts, reason)"))
        assertTrue(e.contains("if (frozenByIssuer8122(ts)) return frozenHandOff8122(ts, reason)"))   // 8122 pin intact
        val poller = src("engine/sell/BalanceProofPoller.kt")
        assertTrue(poller.contains("com.lifecyclebot.engine.FrozenHeldUnsellableLive8141.onThaw8141(entry.mint)"))
        assertTrue(poller.contains("com.lifecyclebot.engine.FrozenHeldUnsellableLive8141.sweepThaw8141()"))
        assertTrue(FrozenHeldUnsellableLive8141.statusLine8141().startsWith("frozenHeld=0"))
    }

    // ── 3. one rescue per candidate version ──

    @Test fun theElectionHolderWinsAndTheLotteryOnlyFillsAnEmptySlot() {
        val e = SpecialistElection8136
        // Pure verdicts.
        assertEquals(RescueSlot8141("SHITCOIN", false), e.rescueSlot8141("SHITCOIN", "EXPRESS", "EXPRESS", "MOONSHOT", false, false))
        assertEquals(RescueSlot8141("EXPRESS", true), e.rescueSlot8141("", "EXPRESS", "EXPRESS", "MOONSHOT", false, false))
        assertEquals(RescueSlot8141(null, false), e.rescueSlot8141("", "CORE", "CORE", "MOONSHOT", false, false))
        assertEquals(RescueSlot8141(null, false), e.rescueSlot8141("", "MOONSHOT", "MOONSHOT", "MOONSHOT", false, false))
        assertEquals(RescueSlot8141(null, false), e.rescueSlot8141("", "EXPRESS", "EXPRESS", "MOONSHOT", true, false))   // judgement
        assertEquals(RescueSlot8141(null, false), e.rescueSlot8141("", "EXPRESS", "EXPRESS", "MOONSHOT", false, true))   // ineligible

        // The lottery fills the empty slot through the claim; a second rescue is then refused.
        assertEquals("EXPRESS", e.boundedRescue8141("mintA8141", "EXPRESS", "MOONSHOT", "EXPRESS", t0))
        assertEquals("EXPRESS", e.rescueOf8136("mintA8141"))
        assertFalse(e.claimRescue8136("mintA8141", "SHITCOIN", "MOONSHOT", SpecialistElection8136.TRIGGER_FDG, "SCORE_LOW", false,
            listOf(Contender8136("SHITCOIN", 95.0)), null, t0 + 1))
        assertEquals("EXPRESS", e.boundedRescue8141("mintA8141", "QUALITY", "MOONSHOT", "QUALITY", t0 + 2))   // holder wins
        assertEquals("EXPRESS", e.rescueOf8136("mintA8141"))

        // The election's holder first: the lottery pick does not evaluate beside it.
        assertTrue(e.claimRescue8136("mintB8141", "SHITCOIN", "MOONSHOT", SpecialistElection8136.TRIGGER_FDG, "SCORE_LOW", false,
            listOf(Contender8136("SHITCOIN", 80.0)), null, t0))
        assertEquals("SHITCOIN", e.boundedRescue8141("mintB8141", "EXPRESS", "MOONSHOT", "EXPRESS", t0 + 1))
        assertEquals("SHITCOIN", e.rescueOf8136("mintB8141"))
        assertEquals(1L to 2L, e.lotteryCounts8141())

        // The lottery pick reaching the FDG is the rescue (RESCUE_EVALUATED), never an overflow.
        e.onFdgVerdict8136("mintA8141", "EXPRESS", -1L, false, "SCORE_LOW", false, true, 60.0, t0 + 3)
        assertEquals(SpecialistElection8136.Terminal8136.RESCUE_EVALUATED, e.terminalOf8136("mintA8141", "EXPRESS")?.first)
        assertFalse(e.statusLine8136().contains("overflow=1"))

        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("val boundedRescue6600 = SpecialistElection8136.boundedRescue8141(ts.mint, l, primaryLane, lotteryRescue8141)"))
        assertTrue(bot.contains("l.equals(primaryLane, true) || l.equals(boundedRescue6600, true)"))   // 6599 pin intact
    }

    // ── 4. recovery tranche counts legacy-fallback proceeds ──

    @Test fun theRecoveryTrancheCountsConfirmedLegacyFallbackProceeds() {
        val L = RecoveryFallbackLedger8141
        // A 0.6 SOL slice booked only by the legacy accounting: without it the tranche targets the whole 1.0 SOL cost.
        assertEquals(1.0, RunnerExitContract8136.unrecoveredSol8136(1.0, 0.0, 0.0), 1e-12)
        assertTrue(L.noteLegacyFallback8141("LIVE:pos8141", "sig8141a", 0.6, confirmed = true))
        assertFalse(L.noteLegacyFallback8141("LIVE:pos8141", "sig8141a", 0.6, confirmed = true))   // one per signature
        assertFalse(L.noteLegacyFallback8141("LIVE:pos8141", "sig8141b", 0.3, confirmed = false))  // unconfirmed never counts
        assertFalse(L.noteLegacyFallback8141("LIVE:pos8141", "", 0.3, confirmed = true))
        assertEquals(0.6, L.confirmedFallbackSol8141("LIVE:pos8141"), 1e-12)
        assertEquals(0.0, L.confirmedFallbackSol8141("LIVE:other8141"), 0.0)
        val unrecovered = L.unrecoveredSol8141(1.0, 0.0, L.confirmedFallbackSol8141("LIVE:pos8141"), 0.0)
        assertEquals(0.4, unrecovered, 1e-12)
        val withFallback = RunnerExitContract8136.recoveryFraction8136(unrecovered, 2.0, 3.0)
        val withoutFallback = RunnerExitContract8136.recoveryFraction8136(1.0, 2.0, 3.0)
        assertTrue(withFallback < withoutFallback * 0.5)
        // Canonical 0.5 + legacy 0.6 covers the 1.0 cost: recovered, no tranche at all.
        assertFalse(RunnerExitContract8136.capitalRecoveredConfirmed8136(1.0, 0.5, 0.0))
        assertTrue(RunnerExitContract8136.capitalRecoveredConfirmed8136(1.0, L.effectiveProceeds8141(0.5, 0.6), 0.0))
        assertEquals(0.5, L.effectiveProceeds8141(0.5, Double.NaN), 1e-12)

        val live = src("engine/RunnerExitLive8136.kt")
        assertTrue(live.contains("val fallback8141 = RecoveryFallbackLedger8141.confirmedFallbackSol8141(pos.positionId)"))
        assertTrue(live.contains("RunnerExitContract8136.capitalRecoveredConfirmed8136(canon.entryCostSol, proceeds8141, canon.feesSol)"))
        val slice = src("engine/Executor.kt").substringAfter("private fun profitLockSlice7884(").substringBefore("\n    }\n")
        assertTrue(slice.contains("RecoveryFallbackLedger8141.noteLegacyFallback8141(pos.positionId, proof?.sig.orEmpty(), solBack, proof?.outcome == TradeVerifier.Outcome.LANDED)"))
        assertTrue(slice.indexOf("noteLegacyFallback8141") < slice.indexOf("liveSellAccountingAuthority(ts, pos.costSol * sellFraction, solBack, reason, \"profitLock\")"))
    }
}
