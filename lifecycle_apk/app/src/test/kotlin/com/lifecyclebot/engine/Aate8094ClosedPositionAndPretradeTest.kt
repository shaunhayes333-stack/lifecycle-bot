package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8094ClosedPositionAndPretradeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aRuntimePositionIsRetiredOnlyWithEveryCloseProof() {
        val dust = java.math.BigInteger.ONE
        // EMUZCh: entry 23:50, canonical close 23:56:57, a COMPLETE wallet snapshot at 23:58 without the mint -> retire.
        val zeroAt = walletZeroProvenAt8094(snapshotComplete = true, snapshotAtMs = 3_000L, heldRaw = null, dustRaw = dust)
        assertTrue(closedRuntimeRetirable8094(entryTimeMs = 1_000L, closedAtMs = 2_000L, walletZeroProvenAtMs = zeroAt))
        // A partial snapshot, no snapshot, or a snapshot that still holds the mint proves nothing.
        assertEquals(0L, walletZeroProvenAt8094(false, 3_000L, null, dust))
        assertEquals(0L, walletZeroProvenAt8094(true, 0L, null, dust))
        assertEquals(0L, walletZeroProvenAt8094(true, 3_000L, java.math.BigInteger.valueOf(5_000L), dust))
        assertFalse(closedRuntimeRetirable8094(1_000L, 2_000L, walletZeroProvenAtMs = 0L))
        // A snapshot taken BEFORE the close is not proof of the post-close wallet.
        assertFalse(closedRuntimeRetirable8094(1_000L, 2_000L, walletZeroProvenAtMs = 1_500L))
        // A close of an EARLIER entry cannot retire a fresh buy.
        assertFalse(closedRuntimeRetirable8094(entryTimeMs = 3_000L, closedAtMs = 2_000L, walletZeroProvenAtMs = 4_000L))
        // No canonical close at all.
        assertFalse(closedRuntimeRetirable8094(1_000L, 0L, walletZeroProvenAtMs = 4_000L))
        assertFalse(src("engine/Executor.kt").contains("closedRuntimeRetirable8094(pos.entryTime, closedAt, HostWalletTokenTracker"))
    }

    @Test fun requestSellRetiresBeforeAnySellWork() {
        val exec = src("engine/Executor.kt")
        val core = exec.substringAfter("private fun requestSellCore7948(").substringBefore("LifecycleHold8065.defer8065")
        assertTrue(core.contains("if (retireClosedRuntimePosition8094(ts, reason)) return SellResult.ALREADY_CLOSED"))
        val helper = exec.substringAfter("private fun retireClosedRuntimePosition8094").substringBefore("    /**")
        assertTrue(helper.contains("firstOpenForMint(mint)"))
        assertTrue(helper.contains("walletZeroProvenAt8094("))           // wallet-balance proof, not a tracker row
    }

    @Test fun pretradeBlocksNameTheirExactCauseAndSealRelation() {
        assertEquals("TOP_HOLDER_FATAL", pretradeSubReason8094("TOP_HOLDER_FATAL: 41% > 35%"))
        assertEquals("LIQ_BELOW_HARD_FLOOR", pretradeSubReason8094("liq below hard floor"))
        assertEquals("UNSPECIFIED", pretradeSubReason8094("  "))
        assertEquals("HARD_BLOCK_LOW_LIQUIDITY", pretradeSubReason8094("HARD BLOCK: Low liquidity $0 (min=$100)"))
        // A coded reason keeps its name (4abd6b13a read 13 of these as UNSPECIFIED).
        assertEquals("FREEZE_AUTHORITY_UNVERIFIED_7238", pretradeSubReason8094("FREEZE_AUTHORITY_UNVERIFIED_7238"))
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

    @Test fun entryRepairKeepsEntryQualityAt8093Strength() {
        // Unobserved shape / change stays unobserved: no tick-shape classification of fresh tokens.
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertFalse(pb.contains("TickShape8094"))
        assertTrue(pb.contains("val shapeKnown = shapeObs == Obs8084.OBSERVED"))
        // A primary's native refusal is not a hand-off; only its FDG refusal is.
        val own = src("engine/SpecialistOwnership7951.kt")
        assertTrue(own.contains("if (ready && native8094 && !receiverRecordSound8094(mint, l)) {"))
        // Aggregator prices never enter the mark bars.
        val fresh = src("engine/truth/CandidateMarkFresh8094.kt")
        assertFalse(fresh.contains("MarkBars7948.note7948"))
        // A deferred live buy is not chased past a few seconds.
        assertTrue(PendingLiveBuy8094.TTL_MS in 15_000L..30_000L)   // survives one full cycle
        // A picked setup with a losing record (12+ labels, mean + SE < 0) is refused, runner lane or not.
        val pb8094 = com.lifecyclebot.engine.cortex.LanePlaybook7907
        assertEquals(12.0, pb8094.NEGATIVE_N_8094, 1e-9)
        assertTrue(pb8094.setupRecordNegative8094(25.0, -23.6, 900.0))       // LAUNCH_CONTINUATION n25 -23.6%, sd 30
        assertFalse(pb8094.setupRecordNegative8094(11.0, -23.6, 900.0))      // too few labels
        assertFalse(pb8094.setupRecordNegative8094(25.0, -3.0, 900.0))       // inside one SE of zero
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("pickedSetupNegative8094(ts, l8059, nowMs)"))
        // Multi-strategy: every fired setup is evaluated and the best proven one is the strategy; the losing-record
        // refusal only applies when no fired setup is proven.
        assertTrue(pb.contains("val candidates = matchedSetups8083(ts, lane, nowMs)"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("if (proven8059 == null && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.pickedSetupNegative8094(ts, l8059, nowMs)"))
        assertTrue(pb.contains("val setup = selectedSetup8094(ts, lane, nowMs)"))
        // In-flight cold starts share the 3-coin budget; a mint holding a place keeps it.
        val g8094 = com.lifecyclebot.engine.truth.LiveEdgeGate7877
        val now8094 = 10_000_000L
        assertFalse(g8094.coldBudgetOpen8094(mapOf("a" to now8094), mapOf("b" to now8094, "c" to now8094), "d", now8094))
        assertTrue(g8094.coldBudgetOpen8094(mapOf("a" to now8094), mapOf("b" to now8094, "c" to now8094), "c", now8094))
        assertTrue(g8094.coldBudgetOpen8094(emptyMap(), mapOf("b" to now8094), "d", now8094))
        // Measured cost binds from the third live round trip, at the cheapest trip until the median rule takes over.
        val c = com.lifecyclebot.engine.truth.CostLedger7962
        assertEquals(3, c.EARLY_LIVE_N)
        assertEquals(null, c.earlyCostFloor7962(listOf(26.0, 12.0)))
        assertEquals(12.0, c.earlyCostFloor7962(listOf(26.0, 12.0, 30.0))!!, 1e-9)
        assertTrue(c.refuses7962(10.0, c.earlyCostFloor7962(listOf(26.0, 12.0, 30.0))))
        assertFalse(c.refuses7962(15.0, c.earlyCostFloor7962(listOf(26.0, 12.0, 30.0))))
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

    @Test fun anUnreadablePreviousSignatureStaysUnknownAndIsResolvedOnTheLadder() {
        // Elapsed time is not chain evidence: no time-based expiry of an unreadable previous signature.
        val ledger = File("src/main/kotlin/com/lifecyclebot/network/SignedTxLedger8085.kt").readText()
        assertFalse(ledger.contains("sellGuardLapsed8094"))
        // An exhausted plan is a dead endpoint (fail over), not the caller's answer.
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(200, "max usage reached"))
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(402, ""))
        assertTrue(com.lifecyclebot.network.rpcExhaustedReply8094(200, "You have exceeded your credits"))
        assertFalse(com.lifecyclebot.network.rpcExhaustedReply8094(200, "Transaction simulation failed"))
        val w = File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(w.contains("if (rpcExhaustedReply8094(code, errMsg)) {"))
        assertFalse(w.contains("sell_guard_lapsed_8094"))
        // An unresolved previous signature still blocks the second order and is re-resolved on the RPC ladder.
        val guard = w.substringAfter("private fun guardDuplicate8085(").substringBefore("private fun resolveOnce8085(")
        assertTrue(guard.contains("scheduleResolve8085(prev.signature)") && guard.contains("throw DuplicateOrderPrevented8085(prev.signature, intent.side)"))
    }

    @Test fun aSignedSellIsBroadcastEverywhereAtOnceAndSkipsAnExhaustedSender() {
        val w = File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        val fan = w.indexOf("\"fanout_8094\"")
        val sender = w.indexOf("if (senderCompatible && !heliusExhausted8094)")
        assertTrue(fan > 0 && sender > fan)                             // fan-out precedes the Sender attempt
        assertTrue(w.substring(fan, sender).contains("echoSend8009(localSig8085, signedB64, viaSender = false)"))
    }

    @Test fun rugcheckZeroIsAHardBlockAgain() {
        val auth = src("engine/TradeAuthorizer.kt")
        assertTrue(auth.contains("if (SpecialistExecution7948.isConfirmedRugForLive7948(rugcheckScore, isPaperMode)) {"))
        val chk = src("engine/TokenSafetyChecker.kt")
        assertTrue(chk.contains("hard.add(\"Rugcheck score 0/100 (CONFIRMED RUG RISK)\")"))
        assertFalse(chk.contains("RUGCHECK_ZERO_NO_DANGER_FLAGS_8094"))
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
        assertTrue(p.isPending8094("MINT_PENDING_8094", nowMs = 15_000L))
        assertFalse(p.isPending8094("MINT_PENDING_8094", nowMs = 40_000L))
        assertTrue(src("engine/ExecutionAttemptLease.kt").contains("PendingLiveBuy8094.note8094(mint)"))
        assertTrue(src("engine/DataOrchestrator.kt").contains("if (PendingLiveBuy8094.isPending8094(mint)) return true"))
        assertTrue(src("engine/BotService.kt").contains("mustInclude.addAll(pendingLiveBuyMints8094(otherMints))"))
    }

    @Test fun aSelfCorroboratingCapCannotBookA7000xOnAMillionDollarCoin() {
        val lab = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        // A $6.4k launch to $1.92M (300x) with the cap moving alike is a real run: booked.
        assertTrue(lab.runCorroborated7769(1.0e-6, 3.0e-4, 6_400.0, 1_920_000.0, 150.0))
        // RETARDIO-shape: a $2M coin "moving" 7,200x with a cap derived from the same price: a basis error.
        assertFalse(lab.runCorroborated7769(1.0e-6, 7.2e-3, 2_000_000.0, 14_400_000_000.0, 150.0))
        assertEquals(1_000_000.0, lab.CORROBORATED_RUN_MAX_ENTRY_MCAP_8094, 1e-9)
    }

    @Test fun theWalletReconcileNoLongerBlocksTheTradingLoop() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("if (!cfg.paperMode) wallet?.let { w -> reconcileWalletOffLoop8094(w) }"))
        val body = bot.substringAfter("private fun reconcileWalletOffLoop8094(").substringBefore("private fun wireEmergencyHandoff8087()")
        assertTrue(body.contains("walletReconcileInFlight8094.compareAndSet(false, true)"))      // single-flight
        assertTrue(body.contains("finally { walletReconcileInFlight8094.set(false) }"))
    }

    @Test fun aFundedPumpPortalKeyIsReprobedNotLockedOutForTheSession() {
        val ws = com.lifecyclebot.network.PumpFunWS
        val reprobe = ws.AUTH_REPROBE_MS_8094
        assertFalse(ws.authReprobeDue8094(denied = true, deniedAtMs = 1_000L, nowMs = 1_000L + reprobe - 1))
        assertTrue(ws.authReprobeDue8094(denied = true, deniedAtMs = 1_000L, nowMs = 1_000L + reprobe))
        assertFalse(ws.authReprobeDue8094(denied = false, deniedAtMs = 1_000L, nowMs = 1_000L + 10 * reprobe))
        val src = File("src/main/kotlin/com/lifecyclebot/network/PumpFunWS.kt").readText()
        assertTrue(src.contains("tradeAuthDeniedAtMs8094 = System.currentTimeMillis()"))
        assertTrue(src.substringAfter("fun syncTradeSubscriptions7278(").take(200).contains("maybeReprobeAuth8094()"))
    }

    @Test fun candidatePricesComeFromOneDexScreenerBatch() {
        val f = com.lifecyclebot.engine.truth.CandidateMarkFresh8094
        val mint = "So1anaMintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val body = """{"pairs":[
          {"chainId":"solana","baseToken":{"address":"$mint"},"priceUsd":"0.0012","txns":{"m5":{"buys":30,"sells":10}},
           "priceChange":{"m5":4.5,"h1":-2.0},"liquidity":{"usd":15000}},
          {"chainId":"solana","baseToken":{"address":"$mint"},"priceUsd":"0.0011","txns":{"m5":{"buys":1,"sells":1}},
           "priceChange":{"m5":1.0,"h1":1.0},"liquidity":{"usd":900}},
          {"chainId":"bsc","baseToken":{"address":"$mint"},"priceUsd":"9.0","liquidity":{"usd":99999}}]}"""
        val r = f.parseDex8094(body, setOf(mint))[mint]!!
        assertEquals(0.0012, r.priceUsd, 1e-12)        // the most liquid SOLANA pair, never another chain
        assertEquals(30, r.buys5m); assertEquals(10, r.sells5m)
        assertEquals(4.5, r.chg5m, 1e-9); assertEquals(15_000.0, r.liqUsd, 1e-9)
        assertTrue(f.parseDex8094("{}", setOf(mint)).isEmpty())
        // Entry repair: only the price is taken; DexScreener flow / change never becomes a classifier input.
        val src = src("engine/truth/CandidateMarkFresh8094.kt")
        assertFalse(src.contains("buyPressureObservedAtMs8084 = at"))
        assertFalse(src.contains("priceChangeObservedAtMs8084 = at"))
    }

    @Test fun tradesAreGradedAtTheExecutablePriceAndTimedAtTheFill() {
        val lab = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        // Labels re-base at the lane's measured live fill latency (5 s floor, 55 s cap).
        assertEquals(5_000L, lab.rebaseDelayMs8094(0L))
        assertEquals(40_000L, lab.rebaseDelayMs8094(40_000L))
        assertEquals(55_000L, lab.rebaseDelayMs8094(120_000L))
        assertEquals(0L, com.lifecyclebot.engine.truth.EntryChase7961.laneLatencyMs8094("NO_SUCH_LANE_8094"))
        // A coin that ran past the graded decision is not the graded trade.
        assertEquals(15.0, MOVED_MAX_PCT_8094, 1e-9)
        assertTrue(movedSinceDecision8094(1.0, 1.20, MOVED_MAX_PCT_8094))
        assertFalse(movedSinceDecision8094(1.0, 1.10, MOVED_MAX_PCT_8094))
        assertFalse(movedSinceDecision8094(0.0, 1.20, MOVED_MAX_PCT_8094))   // unknown decision never refuses
        assertTrue(src("engine/Executor.kt").contains("if (RuntimeModeAuthority.isLive() && movedSinceDecisionRefused8094(ts, lane) &&"))
        assertTrue(lab.decisionPx8094("NO_SUCH_MINT_8094") == null)
        // A positive-evidence admit exits on the stop its evidence was graded on, not a flat -15%.
        val plan = src("engine/truth/TradePlan7739.kt")
        assertTrue(plan.contains("plans[ts.mint] = Plan(Setup.LAUNCH_EARLY, -gradedStopMag8094(lane), LAUNCH_FIRST_TARGET_PCT_7742"))
        assertTrue(plan.contains("StopAuthority7887.laneStopMag8029(lane)"))
    }

    @Test fun aTacticWithALosingRealisedRecordIsNotTradedUnproven() {
        val t = com.lifecyclebot.engine.learning.TacticSwitcher
        assertTrue(t.tacticLosing8094(3, 0, -15.6))      // SHITCOIN MOMENTUM 0W/3L
        assertFalse(t.tacticLosing8094(2, 0, -15.6))     // too few closes
        assertFalse(t.tacticLosing8094(3, 1, -2.0))      // 1 of 3 won: not proven losing
        assertFalse(t.tacticLosing8094(5, 0, 1.0))       // positive mean
        assertEquals(0, t.tacticRecord8094("NO_SUCH_LANE_8094", com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.MOMENTUM).first)
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("TacticSwitcher.losingTacticFor8094(ts, l8059)"))
    }

    @Test fun eachSetupIsGradedByWhenInTheMoveItWasTaken() {
        val pb = com.lifecyclebot.engine.cortex.LanePlaybook7907
        assertEquals("PEAK", pb.timingBand8094(120.0, 0.95))
        assertEquals("EXTENDED", pb.timingBand8094(40.0, 0.95))
        assertEquals("PULLBACK", pb.timingBand8094(40.0, 0.60))
        assertEquals("MID", pb.timingBand8094(10.0, 0.85))
        assertEquals(pb.TIMING_UNKNOWN_8094, pb.timingBand8094(Double.NaN, 0.9))
        val src = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(src.contains("pendingTiming8094[\"${'$'}{ts.mint}|${'$'}{labelLane.trim().uppercase()}\"] = timingBandOf8094(ts)"))
        assertTrue(src.contains(".getOrPut(\"${'$'}setup|${'$'}timing8094\") { CortexLedger7885.Stat() }"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("LanePlaybook7907.timingNegative8094(ts, l8059, nowMs)"))
    }

    @Test fun aProvenSetupTradesAsItsOwnTacticAndStyle() {
        val t = src("engine/learning/TacticSwitcher.kt")
        assertTrue(t.contains("val setup = proven8094 ?: try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classify(ts, lane) }"))
        val r = src("engine/AgenticStyleRouter.kt")
        val provenBranch = r.indexOf("provenLead8094 && tactic == TacticSwitcher.Tactic.BREAKOUT -> Style.BREAKOUT_RUNNER")
        assertTrue(provenBranch > 0 && provenBranch < r.indexOf("toolkitStyle != null -> toolkitStyle"))
        // A post-pump token still never becomes a sniper/runner style: tooLateForSnipe stays first.
        assertTrue(r.indexOf("launch7402?.tooLateForSnipe == true -> sameLaneWeakPivotStyle") < provenBranch)
    }

    @Test fun confidenceAtTheCurrentTimingCanOnlyLowerTheSize() {
        val p = com.lifecyclebot.engine.truth.LiveRiskPolicy7807
        assertEquals(0.10, p.timingCappedFrac8094(0.10, null), 1e-12)
        assertEquals(0.10, p.timingCappedFrac8094(0.10, doubleArrayOf(-20.0, 400.0, 8.0)), 1e-12)   // too few labels
        assertEquals(0.0, p.timingCappedFrac8094(0.10, doubleArrayOf(-20.0, 400.0, 20.0)), 1e-12)   // losing band
        assertTrue(p.timingCappedFrac8094(0.10, doubleArrayOf(30.0, 400.0, 40.0)) <= 0.10)
    }

    @Test fun everyFiredSetupIsGradedAndTheTradedStrategyIsTheSelectedOne() {
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        val cold = pb.substringAfter("fun coldStartSetup8080(").substringBefore("fun coldStartPositive8080(")
        assertTrue(cold.contains("val candidates = matchedSetups8083(ts, lane, nowMs)"))
        assertTrue(cold.contains("!setupRecordNegative8094(it.n, it.mean(), it.variance())"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("Pending(a, admitted, nowMs, veto, ts.source, setup7961, fired8094)"))
        assertTrue(cx.contains("for (f in p.fired8094) {"))
        assertTrue(cx.contains("LanePlaybook7907.selectedSetup8094(ts, a.lane, System.currentTimeMillis())"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("val setup8083 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, l8059, nowMs) }"))
    }

    @Test fun readySpecialistsAreSeenAcrossTheVersionRollAndTheRefusedPrimaryDoesNotWinAgain() {
        val b = com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
        assertTrue(b.versionCurrent8094(100L, 100L))
        assertTrue(b.versionCurrent8094(99L, 100L))        // READY written in the previous 30 s bucket
        assertFalse(b.versionCurrent8094(98L, 100L))
        assertFalse(b.versionCurrent8094(101L, 100L))
        val coord = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(coord.contains("SpecialistOwnership7951.primaryRefused7960(mint, lane)"))
        // Native hand-off: lane-fit refusals hand on, safety refusals never; the receiver's own record must be sound.
        fun op(eligible: Boolean, reason: String) =
            SpecialistBrainBridge7542.Opinion("CORE", eligible, 40, 40, 0.0, reason, "S", "e", "x", gradeable = true)
        val o = SpecialistOwnership7951
        assertTrue(o.nativeRefusalHandsOff8094(op(false, "CORE_YIELD_CLEAR_SPECIALIST_OWNER"), 1_000L, 2_000L))
        assertFalse(o.nativeRefusalHandsOff8094(op(false, "HARD BLOCK: rug pattern"), 1_000L, 2_000L))
        assertFalse(o.nativeRefusalHandsOff8094(op(false, "DEV_SELLING"), 1_000L, 2_000L))
        assertFalse(o.nativeRefusalHandsOff8094(op(true, "OK"), 1_000L, 2_000L))
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("if (ready && native8094 && !receiverRecordSound8094(mint, l)) {"))
    }

    @Test fun aRefusalRuleTheAuditProvesRefusesWinnersStandsDownOnlyThere() {
        val q = com.lifecyclebot.engine.truth.SelectionQualityAuthority6829
        assertEquals(22.0, q.BASE_FLOOR_8094, 1e-9)
        assertEquals("SELECTION_QUALITY_FLOOR", com.lifecyclebot.engine.cortex.Cortex7885.vetoRuleOf(q.FLOOR_RULE_8094))
        // The FDG floor reason grades under the same rule name.
        assertEquals("SELECTION_QUALITY_FLOOR", com.lifecyclebot.engine.cortex.Cortex7885.vetoRuleOf("SELECTION_QUALITY_FLOOR_6830 lane=SHITCOIN conf=10% floor=37.0"))
        // No graded evidence: the floor is unchanged.
        assertFalse(com.lifecyclebot.engine.cortex.Cortex7885.ownLaneVetoRefusesWinners8076(q.FLOOR_RULE_8094, "NO_SUCH_LANE_8094"))
    }

    @Test fun aReadySpecialistWithAProvenCellEvaluatesAndTheTacticGateSparesProvenCells() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("SpecialistOwnership7951.readyCellProvenEvaluates8094(l, ts.mint, primaryLane))  // V5.0.8094"))
        val own = src("engine/SpecialistOwnership7951.kt")
        assertTrue(own.contains("LiveEdgeGate7877.cellOrKindProven8094(ts, l)"))
        // Not the primary, not READY, or no token state: no evaluation.
        assertFalse(SpecialistOwnership7951.readyCellProvenEvaluates8094("MANIPULATED", "NO_SUCH_MINT_8094", "MOONSHOT"))
        assertFalse(SpecialistOwnership7951.readyCellProvenEvaluates8094("MOONSHOT", "NO_SUCH_MINT_8094", "MOONSHOT"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("if (proven8059 == null && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, l8059)"))
    }

    @Test fun runnersAreEnteredOnTheirCellsProofAndHeldOnTheRunnerRules() {
        val g = com.lifecyclebot.engine.truth.LiveEdgeGate7877
        assertTrue(g.cellWinsProven8094(65, 0.75, 737.0))      // n65 wr75% +737%: proven by its win count
        assertFalse(g.cellWinsProven8094(65, 0.55, 40.0))      // a coin-flip win rate is not proof
        assertFalse(g.cellWinsProven8094(20, 0.90, 300.0))     // too few labels
        assertFalse(g.cellWinsProven8094(65, 0.80, -1.0))      // negative mean
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("!cellOrKindProven8094(ts, l8059, nowMs)) {"))   // chase stands down for a proven cell
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("C3_STOOD_DOWN_CELL_PROVEN_8094"))
        assertTrue(src("engine/Executor.kt").contains("it.startsWith(\"MANUFACTURED_CLIMB\") && freshProvenRunner8094(ts)"))
        assertTrue(src("engine/BotService.kt").contains("return designatedDeskQualified6599 && SpecialistOwnership7951.readyCellProvenEvaluates8094(l, ts.mint, primaryLane)"))
        assertTrue(src("engine/AgenticStyleRouter.kt").contains("provenRunnerCell8094 -> Style.BREAKOUT_RUNNER"))
        assertTrue(src("engine/BotService.kt").contains("if (TradeExitContract8077.longHold(ts) == true) null"))
        // The moonbag set-aside replaces a full exit: never skipped as an uneconomic slice.
        assertFalse(com.lifecyclebot.engine.sell.PartialNetEconomics8094.discretionary8094("TAIL_SET_ASIDE_8034_TICK_PROFIT_LOCK", 0.75))
        assertTrue(com.lifecyclebot.engine.sell.PartialNetEconomics8094.discretionary8094("PARTIAL_TAKE_PROFIT", 0.25))
    }

    @Test fun aPumpFunPositionIsNeverStrandedOnAnUnreadableDecimalsRpc() {
        val d = com.lifecyclebot.engine.truth.DecimalIntegrityAuthority6405
        val mint = "CHxwVbJ9B3eMRdEWzYpcKy7ops5H8PxXU79NqzQGpump"
        // The address suffix alone is not provenance.
        assertFalse(d.pumpProgramMint8094("Zz8094NoCreateEventSeenXXXXXXXXXXXXXXXXpump"))
        assertFalse(d.pumpProgramMint8094("MINT_D"))
        // Announced by the pump program's create stream (bonding-curve key recorded): provenance.
        com.lifecyclebot.network.PumpCurveKeys7269.remember(mint, "BondingCurveKey8094XXXXXXXXXXXXXXXXXXXXXXX")
        assertTrue(d.pumpProgramMint8094(mint))
        assertEquals(d.PUMP_FUN_DECIMALS_8094, d.resolveDecimalsStrict(mint, null, null, null))
        assertTrue(src("network/HeliusWebSocket.kt").contains("MAX_TOKEN_SUBSCRIPTIONS_7794 = 8"))
        // One strategy identity: refusals, sizing, cost and the admission record read the selected setup.
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertEquals(3, Regex("val setup = selectedSetup8094\\(ts, lane, nowMs\\)  // V5.0.8094 — one strategy identity").findAll(pb).count())
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("val setupAt = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, l8083, nowMs) }"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("BuildConfig.VERSION_NAME + \"@\" + com.lifecyclebot.BuildConfig.GIT_SHA"))
        assertEquals(10.0, LaneExecutionCoordinator.PROVEN_CELL_BONUS_8094, 1e-9)
        assertTrue(src("engine/LaneExecutionCoordinator.kt").contains("+ provenCellBonus8094(mint, laneUpper)"))
    }

    @Test fun theHiveNetworkBooksComeBackInFullAfterEveryRestart() {
        // The network aggregate lives in memory; lastPullMs survives the restart. The first sync pages everything in.
        val h = src("engine/truth/HiveEdge8000.kt")
        assertTrue(h.contains("if (!fullPulledThisProcess8094) {"))
        assertTrue(h.contains("HIVE_FULL_PAGE_IN_AT_BOOT_8094"))
        assertTrue(h.contains("pageIn8094(client, 0L)"))
    }

    @Test fun theSignedOrderLedgerWritesOnlyWhatARestartNeeds() {
        val L = com.lifecyclebot.network.SignedTxLedger8085
        L.resetForTest()
        val open = L.record("sigOpen8094", "b3Blbg==", "BH", com.lifecyclebot.network.SignedTxLedger8085.Intent("MINT_O", "BUY"), nowMs = 1L)
        val done = L.record("sigDone8094", "ZG9uZQ==", "BH", com.lifecyclebot.network.SignedTxLedger8085.Intent("MINT_D", "BUY"), nowMs = 2L)
        L.transition("sigDone8094", com.lifecyclebot.network.SignedTxLedger8085.State.CONFIRMED, nowMs = 3L)
        // A resolved row keeps its bytes in memory but does not write them; an unresolved one does.
        assertTrue(L.encode(open).contains("b3Blbg=="))
        assertFalse(L.encode(done).contains("ZG9uZQ=="))
        assertEquals("ZG9uZQ==", L.get("sigDone8094")!!.signedB64)
        val many = (1..200).map { com.lifecyclebot.network.SignedTxLedger8085.Entry("s$it", "x", "h", "m$it", "BUY", 0L, "", "", "", it.toLong(), com.lifecyclebot.network.SignedTxLedger8085.State.CONFIRMED, it.toLong()) } + open
        val disk = L.durableRows8094(many)
        assertEquals(L.KEEP_RESOLVED_ON_DISK_8094 + 1, disk.size)
        assertTrue(disk.contains(open))
        L.resetForTest()
    }

    @Test fun aShadowedLaneWhoseShadowingRefusesWinnersEvaluatesAndTheFreezeGateAsksTheChain() {
        val own = src("engine/SpecialistOwnership7951.kt")
        assertTrue(own.contains("val audit = !ok && ts != null && shadowRefusesWinners8094(l) && receiverRecordSound8094(mint, l)"))
        assertFalse(SpecialistOwnership7951.shadowRefusesWinners8094("NO_SUCH_LANE_8094"))
        assertTrue(src("engine/truth/FreezeAuthorityHardBlock7238.kt").contains("OnChainMintAuthorityProof7248.resolve(mint, liveGate8094 = true)"))
        assertTrue(src("engine/truth/OnChainMintAuthorityProof7248.kt").contains("MINT_AUTHORITY_PROOF_MISS_8094_"))
    }

    @Test fun drawdownNoLongerStopsAProvenEdgeFromCompounding() {
        val P = com.lifecyclebot.engine.truth.LiveRiskPolicy7807
        // A proven positive-edge candidate is sized on current equity; drawdown does not shrink it again.
        assertTrue(P.drawdownWaivedForProvenEdge8094(0.06, 0.59))
        assertFalse(P.drawdownWaivedForProvenEdge8094(0.0, 0.59))
        assertFalse(P.drawdownWaivedForProvenEdge8094(0.06, 0.0))
        // The doctrine curve itself is unchanged for unproven candidates.
        assertEquals(0.35, P.drawdownMultiplier(0.59), 1e-9)
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("val ddMult = if (ddWaived8094) 1.0 else drawdownMultiplier(i.drawdownFrac)"))
        // SmartSizer no longer applies a second live drawdown cut on free wallet SOL.
        assertFalse(src("engine/SmartSizer.kt").contains("recovery < 0.40 -> 0.30"))
        // The launch-cap rule stands down only where its own ladder replays are tail-proven.
        assertFalse(com.lifecyclebot.engine.truth.OnChainDepth8038.launchCapStoodDown8094("NO_SUCH_MINT_8094"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("OnChainDepth8038.standDownIfTailProven8094(ts, lane, nowMs)"))
        assertTrue(src("engine/truth/TailHunter7996.kt").contains("proven(\"R|\$rule|\$cell\") != null"))
    }

    @Test fun heldMarksCurveTicksAndRentReclaimNameOrRecoverTheirFailures() {
        // Held-position marks are exit traffic: a scanner lockout on the shared host can no longer starve them.
        assertTrue(src("network/DexscreenerApi.kt").contains("val body = get8095(url, exit8095 = slotWaitMs7948 > 0L)"))
        assertTrue(src("network/DexscreenerApi.kt").contains("header(ExitHttpScope7314.HEADER, \"held-marks\")"))
        // Curve ticks: refused subscribes are counted and named; unanswered ones are re-sent.
        val ct = src("network/CurveTicks7968.kt")
        assertTrue(ct.contains("CURVE_SUB_ERROR_8095_") && ct.contains("CURVE_SUB_RESENT_8095"))
        assertTrue(com.lifecyclebot.network.CurveTicks7968.statusLine7968().contains("subErrors8095="))
        // Rent reclaim keeps the end of the error, where the instruction error code is.
        assertTrue(src("engine/RentReclaimer7927.kt").contains("t.message?.takeLast(90)"))
    }

    @Test fun aLogStreamThatNeverDecodesATradeIsReleased() {
        // Only streams that decoded NOTHING are released; one decoded trade keeps a stream for good.
        assertFalse(com.lifecyclebot.network.heliusStreamUndecodable8096(39, 0))
        assertTrue(com.lifecyclebot.network.heliusStreamUndecodable8096(40, 0))
        assertFalse(com.lifecyclebot.network.heliusStreamUndecodable8096(5_000, 1))
        val ws = src("network/HeliusWebSocket.kt")
        assertTrue(ws.contains("if (undecodedStreamSpent8096(mint)) return"))
        assertTrue(ws.contains("if (undecodableNow8096(mint)) return"))
        assertTrue(ws.contains("permessage-deflate"))
    }

    @Test fun theBotNeverSellsTheOwnersUsdcAndTheHiveIsPagedInBeforeTheUpload() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("if (baseQuoteSellRefused8096(ts.mint, reason)) return SellResult.FAILED_FATAL"))
        val h = src("engine/truth/HiveEdge8000.kt")
        assertTrue(h.indexOf("if (!fullPulledThisProcess8094) {") in 0 until h.indexOf("// Upload deltas."))
        assertTrue(h.contains("if (!uploadDue8096 && fullPulledThisProcess8094) return"))
    }

    @Test fun runnerRefusalsStandDownOnTheirLaneRecordWhenTheCellIsThin() {
        val t = com.lifecyclebot.engine.truth.TailHunter7996
        assertTrue(t.laneTailBar8097(93, 93 * 69.3, 10))   // AT_LAUNCH_CAP over the lane, 8095
        assertFalse(t.laneTailBar8097(93, -100.0, 10))     // a losing total never stands down
        assertFalse(t.laneTailBar8097(20, 2000.0, 5))      // too few replays
        assertFalse(t.laneTailBar8097(93, 500.0, 1))       // one runner is luck
        val th = src("engine/truth/TailHunter7996.kt")
        assertTrue(th.contains("cellThin && laneRuleTailProven8097(rule, l)"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("!spikeRuleRefusesRunners8097(ts, l8059, nowMs)"))
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("Cortex7885.vetoRefusesWinners8006(LaneParticipation8029.SHADOW_REASON, lane7951(lane))"))
    }

    @Test fun theScannerRejectStoreIsBoundedAndWritesCoalesced() {
        val s = ScannerHardRejectStore
        val stamps = (1..5_000).map { ScannerHardRejectStore.Reject("m$it", "S", "RUG", "x", it.toLong()) }
        val evict = s.evictions8094(stamps)
        assertEquals(5_000 - ScannerHardRejectStore.MAX_REJECTS_8094, evict.size)
        assertTrue(evict.contains("m1") && !evict.contains("m5000"))
        assertTrue(s.evictions8094(stamps.take(10)).isEmpty())
        assertTrue(src("engine/ScannerHardRejectStore.kt").contains("if (!known8094) saveSoon8094()"))
    }

    @Test fun aLabelRebasedAtTheMeasuredFillLatencyIsNotChargedTheChaseTwice() {
        val f = com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
        assertFalse(f.rebaseClearsChase8094(0L))
        assertTrue(f.rebaseClearsChase8094(40_000L))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("o.costPct = (o.costPct - o.chase8094).coerceAtLeast(0.0)"))
        assertTrue(src("engine/truth/CostLedger7962.kt").contains("ForwardReturnLabeler7731.rebaseClearsChase8094(EntryChase7961.laneLatencyMs8094(l))"))
    }
}
