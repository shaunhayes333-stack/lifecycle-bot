package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8094ClosedPositionAndPretradeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aRuntimePositionIsRetiredOnlyWithEveryCloseProof() {
        // EMUZCh: entry 23:50, canonical close 23:56:57, wallet absent -> retire.
        assertTrue(closedRuntimeRetirable8094(entryTimeMs = 1_000L, closedAtMs = 2_000L, walletHolds = false))
        // A wallet that still holds the mint is never retired.
        assertFalse(closedRuntimeRetirable8094(1_000L, 2_000L, walletHolds = true))
        // A close of an EARLIER entry cannot retire a fresh buy.
        assertFalse(closedRuntimeRetirable8094(entryTimeMs = 3_000L, closedAtMs = 2_000L, walletHolds = false))
        // No canonical close at all.
        assertFalse(closedRuntimeRetirable8094(1_000L, 0L, walletHolds = false))
    }

    @Test fun requestSellRetiresBeforeAnySellWork() {
        val exec = src("engine/Executor.kt")
        val core = exec.substringAfter("private fun requestSellCore7948(").substringBefore("LifecycleHold8065.defer8065")
        assertTrue(core.contains("if (retireClosedRuntimePosition8094(ts, reason)) return SellResult.ALREADY_CLOSED"))
        val helper = exec.substringAfter("private fun retireClosedRuntimePosition8094").substringBefore("    /**")
        assertTrue(helper.contains("firstOpenForMint(mint)"))
        assertTrue(helper.contains("HostWalletTokenTracker.hasOpenPosition(mint)"))
    }

    @Test fun pretradeBlocksNameTheirExactCauseAndSealRelation() {
        assertEquals("TOP_HOLDER_FATAL", pretradeSubReason8094("TOP_HOLDER_FATAL: 41% > 35%"))
        assertEquals("LIQ_BELOW_HARD_FLOOR", pretradeSubReason8094("liq below hard floor"))
        assertEquals("UNSPECIFIED", pretradeSubReason8094("  "))
        assertEquals("HARD_BLOCK_LOW_LIQUIDITY", pretradeSubReason8094("HARD BLOCK: Low liquidity $0 (min=$100)"))
        assertEquals("PRESENT_AT_SEAL", pretradeSealRelation8094("SAFE", sealHardNo = true, sealRug = 80))
        assertEquals("PRESENT_AT_SEAL", pretradeSealRelation8094("HARD_BLOCK", false, 80))
        assertEquals("PRESENT_AT_SEAL", pretradeSealRelation8094("SAFE", false, 0))
        assertEquals("CHANGED_AFTER_SEAL", pretradeSealRelation8094("SAFE", false, 80))
        val exec = src("engine/Executor.kt")
        assertFalse(exec.contains("emitLiveBuyFail(ts, sol, \"PRETRADE_HARD_BLOCK\","))
        assertTrue(exec.contains("emitLiveBuyFail(ts, sol, \"PRETRADE_HARD_BLOCK_\$sub\", detail)"))
        // A named pre-trade block is still terminal, never a deferral.
        assertFalse(PipelineHealthCollector.isNonTerminalBuyDeferral7868("PRETRADE_HARD_BLOCK_TOP_HOLDER_FATAL"))
    }

    @Test fun freshTokenShapeComesFromItsObservedPrices() {
        val t0 = 1_000_000L
        val ticks = listOf(t0 to 1.0, t0 + 20_000L to 1.5, t0 + 40_000L to 2.0, t0 + 70_000L to 1.8)
        val s = com.lifecyclebot.engine.cortex.TickShape8094.of(ticks, t0 + 75_000L)!!
        assertEquals(80.0, s.runupPct, 1e-9)        // 1.8 vs low 1.0
        assertEquals(0.9, s.pxPeak, 1e-9)           // 1.8 vs high 2.0
        assertEquals(10.0, s.ddPct, 1e-9)
        assertEquals(80.0, s.chg5mPct, 1e-9)        // span < 5m: change since the first tick
        // Too few, too tight, or stale ticks vouch for nothing.
        assertEquals(null, com.lifecyclebot.engine.cortex.TickShape8094.of(ticks.take(2), t0 + 75_000L))
        assertEquals(null, com.lifecyclebot.engine.cortex.TickShape8094.of(listOf(t0 to 1.0, t0 + 3_000L to 1.1, t0 + 6_000L to 1.2), t0 + 7_000L))
        assertEquals(null, com.lifecyclebot.engine.cortex.TickShape8094.of(ticks, t0 + 30L * 60_000L))
        // Under a minute of ticks gives shape but no change.
        val short = com.lifecyclebot.engine.cortex.TickShape8094.of(listOf(t0 to 1.0, t0 + 15_000L to 1.2, t0 + 30_000L to 1.1), t0 + 31_000L)!!
        assertTrue(short.chg5mPct.isNaN())
    }

    @Test fun heliusExhaustionIsRecognisedAndMovesHeliusToTheBackOfTheLadder() {
        val h = com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
        assertTrue(h.isExhaustionAnswer8094(429, "{\"error\":\"max usage reached for this plan, upgrade your plan\"}"))
        assertTrue(h.isExhaustionAnswer8094(403, "credits exhausted"))
        assertFalse(h.isExhaustionAnswer8094(429, "Too many requests"))
        assertFalse(h.isExhaustionAnswer8094(429, "AATE_HELIUS_BUDGET_8066"))
        assertFalse(h.isExhaustionAnswer8094(500, "credits"))
        val ladder = listOf("https://mainnet.helius-rpc.com/?api-key=x", "https://solana-mainnet.g.alchemy.com/v2/y", "https://api.mainnet-beta.solana.com")
        assertEquals(ladder, RuntimeProviderAuthority6685.heliusLastWhenExhausted8094(ladder, exhausted = false))
        assertEquals(listOf(ladder[1], ladder[2], ladder[0]), RuntimeProviderAuthority6685.heliusLastWhenExhausted8094(ladder, exhausted = true))
    }

    @Test fun aLanesEvRefusalStopsBindingOnlyWhenItsRefusedCohortProvablyOutEarns() {
        // Refused cohort clearly positive and better than admitted -> refusals are inverted.
        assertTrue(refusalInverted8094(refN = 25, refMeanPct = 40.0, refSePct = 15.0, admN = 12, admMeanPct = -3.5))
        // CRYPTO_ALT today: n=9 is not evidence yet.
        assertFalse(refusalInverted8094(refN = 9, refMeanPct = 121.0, refSePct = 90.0, admN = 7, admMeanPct = -3.5))
        // A noisy refused mean inside one SE of zero is not inversion.
        assertFalse(refusalInverted8094(25, 10.0, 12.0, 12, -3.5))
        // Admitted cohort earns more: refusing is right.
        assertFalse(refusalInverted8094(25, 20.0, 5.0, 12, 30.0))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        assertTrue(gate.contains("!livePositiveEv7828 && !evRefusalInverted8094"))
        assertTrue(gate.contains("!oracleHardSafety7287"))
    }

    @Test fun sellsAreNotBlockedForeverByAnUnreadablePreviousSignature() {
        val l = com.lifecyclebot.network.SignedTxLedger8085
        val lapse = l.SELL_GUARD_LAPSE_MS_8094
        assertTrue(lapse >= 150_000L)                                  // past any blockhash lifetime
        assertTrue(l.sellGuardLapsed8094("SELL", 1_000L, 1_000L + lapse))
        assertFalse(l.sellGuardLapsed8094("SELL", 1_000L, 1_000L + lapse - 1))
        assertFalse("a buy is never doubled", l.sellGuardLapsed8094("BUY", 1_000L, 1_000L + 10 * lapse))
        // An exhausted plan is a dead endpoint (fail over), not the caller's answer.
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(200, "max usage reached"))
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(402, ""))
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(200, "You have exceeded your credits"))
        assertFalse(com.lifecyclebot.network.rpcExhaustedReply8094(200, "Transaction simulation failed"))
        val w = File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(w.contains("if (rpcExhaustedReply8094(code, errMsg)) {"))
        assertTrue(w.contains("SignedTxLedger8085.sellGuardLapsed8094(intent.side, prev.createdAtMs"))
    }

    @Test fun aSignedSellIsBroadcastEverywhereAtOnceAndSkipsAnExhaustedSender() {
        val w = File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        val fan = w.indexOf("\"fanout_8094\"")
        val sender = w.indexOf("if (senderCompatible && !heliusExhausted8094)")
        assertTrue(fan > 0 && sender > fan)                             // fan-out precedes the Sender attempt
        assertTrue(w.substring(fan, sender).contains("echoSend8009(localSig8085, signedB64, viaSender = false)"))
    }

    @Test fun rugcheckZeroConvictsOnlyWithDangerEvidence() {
        assertTrue(rugcheckConfirmsDanger8094(rugged = true, dangerRiskCount = 0))
        assertTrue(rugcheckConfirmsDanger8094(rugged = false, dangerRiskCount = 1))
        assertFalse(rugcheckConfirmsDanger8094(rugged = false, dangerRiskCount = 0))
        val auth = src("engine/TradeAuthorizer.kt")
        assertTrue(auth.contains("hardBlockReasons?.any { it.startsWith(\"Rugcheck score 0\") } != false"))
        val chk = src("engine/TokenSafetyChecker.kt")
        assertTrue(chk.contains("hard.add(\"Rugcheck score 0/100 (CONFIRMED RUG RISK)\")"))
    }

    @Test fun establishedTokensGetKeylessBarsAndJupiterBirthWhileHeliusIsOut() {
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("requestKeylessBars8094(ts, nowMs)"))
        val body = plan.substringAfter("private fun requestKeylessBars8094(").substringBefore("// ── setups (pure) ──")
        assertTrue(body.contains("if (!HeliusCreditEconomy7881.exhaustedLatched8094(nowMs)) return"))
        assertTrue(body.contains("if (age < 30L * 60_000L) return"))
        assertTrue(body.contains("mergeIntoHistory7819(ts, bars)"))
        val sweep = src("engine/market/MarketSweep7297.kt")
        assertTrue(sweep.contains("PoolCreationTime7385.record(mint, createdMs, \"JUP_FIRST_POOL_8094\")"))
    }

    @Test fun aReadySpecialistGetsTheTokenWhenThePrimaryRefusesNatively() {
        fun op(eligible: Boolean, reason: String, gradeable: Boolean = true) =
            SpecialistBrainBridge7542.Opinion("CORE", eligible, 40, 40, 0.0, reason, "S", "e", "x", gradeable = gradeable)
        val h = SpecialistOwnership7951
        // CORE yielding to the specialist is the deadlock 8093 showed (15,004 refusals, CASHGEN/QUALITY never evaluated).
        assertTrue(h.nativeRefusalHandsOff8094(op(false, "CORE_YIELD_CLEAR_SPECIALIST_OWNER"), 1_000L, 2_000L))
        assertTrue(h.nativeRefusalHandsOff8094(op(false, "LIQ_TOO_LOW: below SHITCOIN band"), 1_000L, 2_000L))
        assertFalse(h.nativeRefusalHandsOff8094(op(false, "HARD BLOCK: rug pattern"), 1_000L, 2_000L))
        assertFalse(h.nativeRefusalHandsOff8094(op(false, "DEV_SELLING"), 1_000L, 2_000L))
        assertFalse(h.nativeRefusalHandsOff8094(op(true, "OK"), 1_000L, 2_000L))
        // A lane that is busy, disabled or short of data passes the trade on (8093: CYCLIC PRICE_NOT_FRESH missed +1,185%).
        assertTrue(h.nativeRefusalHandsOff8094(op(false, "CYCLIC_RING_POSITION_ACTIVE", gradeable = false), 1_000L, 2_000L))
        assertTrue(h.nativeRefusalHandsOff8094(op(false, "PRICE_NOT_FRESH"), 1_000L, 2_000L))
        assertFalse("stale opinion", h.nativeRefusalHandsOff8094(op(false, "MCAP_TOO_LOW"), 1_000L, 1_000L + 91_000L))
        assertFalse(h.nativeRefusalHandsOff8094(null, 1_000L, 2_000L))
    }

    @Test fun liveSalesCarryTheirSolUsdSoCostIsMeasured() {
        val c = src("engine/sell/SellFinalizationCoordinator.kt")
        assertTrue(c.contains("solUsdAtExit = liveSolUsd8094(),"))
    }

    @Test fun theUniversalSweepRetiresAClosedPositionBeforeTheMissingMarkVetoHoldsIt() {
        val bot = src("engine/BotService.kt")
        val retire = bot.indexOf("executor.retireIfCanonicallyClosed8094(ts)")
        val veto = bot.indexOf("MissingMarkExitVeto6835.evaluate(", retire)
        assertTrue(retire > 0 && veto > retire)
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("MissingMarkExitVeto6835.release8094(mint)"))
    }

    @Test fun noCandidateIsDecidedOnAPriceOlderThanFifteenSeconds() {
        val f = com.lifecyclebot.engine.truth.CandidateMarkFresh8094
        assertEquals(15_000L, f.FRESH_MS_8094)
        assertFalse(f.stale8094(1.0, 100_000L, 110_000L))
        assertTrue(f.stale8094(1.0, 100_000L, 116_000L))
        assertTrue(f.stale8094(0.0, 100_000L, 100_001L))
        assertTrue(f.stale8094(1.0, 0L, 1L))
        assertEquals(20_000.0, f.rescaledMcap8094(10_000.0, 1.0, 2.0), 1e-9)   // price x supply stays one identity
        assertEquals(10_000.0, f.rescaledMcap8094(10_000.0, 0.0, 2.0), 1e-9)
        val bridge = src("engine/SpecialistBrainBridge7542.kt")
        assertTrue(bridge.indexOf("CandidateMarkFresh8094.ensureFresh8094(ts)") in 0 until bridge.indexOf("val f=fp(ts,paper)"))
        assertTrue(src("engine/CyclicTradeEngine.kt").contains("CandidateMarkFresh8094.ensureFresh8094(ts)"))
    }

    @Test fun microscopicProfitSlicesAreSkippedButEmergencyExitsNeverWait() {
        val e = com.lifecyclebot.engine.sell.PartialNetEconomics8094
        // CfU5fA partial_69pct: proceeds 0.00314, profit 0.000422, exit leg ~8% (priority fee on a tiny slice): skipped.
        assertTrue(e.uneconomic8094(0.003140, 0.003140 - 0.000422, 8.0))
        // CfU5fA partial_60pct: proceeds 0.018839, profit 0.003283, exit leg ~3%: sent.
        assertFalse(e.uneconomic8094(0.018839, 0.018839 - 0.003283, 3.0))
        // A slice at a gross loss is de-risking, never held here.
        assertFalse(e.uneconomic8094(0.010, 0.012, 8.0))
        assertTrue(e.discretionary8094("PARTIAL_TAKE_PROFIT", 0.25))
        assertFalse(e.discretionary8094("STOP_LOSS_HIT", 0.25))
        assertFalse(e.discretionary8094("CATASTROPHIC_EXIT", 0.5))
        assertFalse(e.discretionary8094("RUG_DETECTED", 0.5))
        assertFalse("a full exit is never a slice", e.discretionary8094("PARTIAL_TAKE_PROFIT", 1.0))
        val x = src("engine/Executor.kt")
        assertTrue(x.contains("if (partialUneconomic8094(ts, pct, reason)) {"))                       // shared partial door
        assertTrue(x.contains("if (partialUneconomic8094(ts, sellFraction, \"PARTIAL_TAKE_PROFIT\")) return false")) // ladder
        assertTrue(x.contains("if (partialUneconomic8094(ts, sellFraction, reason)) return  // V5.0.8094"))       // profit lock
        // The slice is a share of the REMAINING quantity and its remaining cost basis (costSol shrinks on every partial).
        assertTrue(x.contains("val proceeds = proceedsSol7029(pos.qtyToken * f, getActualPrice(ts)) ?: return false"))
        assertTrue(x.contains("val sliceCost = pos.costSol * f"))
    }

    @Test fun emergencyExitsRetryThemselvesAndSkipPreSellWalletReads() {
        val l = com.lifecyclebot.engine.sell.CloseLease
        assertEquals(250L, l.selfRetryDelayMs8094(eligibleAtMs = 1_000L, nowMs = 2_000L))     // already eligible: soon
        assertEquals(2_050L, l.selfRetryDelayMs8094(eligibleAtMs = 4_000L, nowMs = 2_000L))
        assertEquals(15_000L, l.selfRetryDelayMs8094(eligibleAtMs = 90_000L, nowMs = 0L))     // never parked long
        val lease = src("engine/sell/CloseLease.kt")
        assertTrue(lease.contains("if (waiting8094 == null && ProtectiveExitClass7807.isEmergency(leaseReason8094)) scheduleEmergencyRetry8094("))
        val x = src("engine/Executor.kt")
        assertTrue(x.contains("preSellGuard(ts.mint, ts.symbol ?: \"?\", if (emergency8094) null else wallet)"))
        assertTrue(x.contains("if (!com.lifecyclebot.engine.sell.ProtectiveExitClass7807.isEmergency(reason)) run {  // V5.0.8094"))
    }

    @Test fun aDeferredLiveBuyIsRetriedNextCycleNotAfterItsRoundRobinTurn() {
        val p = PendingLiveBuy8094
        val stamps = mapOf("A" to 1_000L, "B" to 5_000L, "OLD" to 1L - 70_000L)
        assertEquals(listOf("A", "B"), p.pendingFrom8094(stamps, nowMs = 10_000L, ttlMs = 60_000L, max = 8))
        assertEquals(listOf("A"), p.pendingFrom8094(stamps, nowMs = 10_000L, ttlMs = 60_000L, max = 1))
        p.note8094("MINT_PENDING_8094", nowMs = 1_000L)
        assertTrue(p.isPending8094("MINT_PENDING_8094", nowMs = 30_000L))
        assertFalse(p.isPending8094("MINT_PENDING_8094", nowMs = 62_000L))
        assertTrue(src("engine/ExecutionAttemptLease.kt").contains("PendingLiveBuy8094.note8094(mint)"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("if (PendingLiveBuy8094.isPending8094(mint)) return true"))
        assertTrue(src("engine/BotService.kt").contains("val mustInclude = pendingLiveBuyMints8094(otherMints).toMutableList()"))
    }
}
