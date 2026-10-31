package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8094ClosedPositionAndPretradeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun chaseDecisionSource8397(): String {
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("if (snap8065 != null) chaseRefusal8397(ts, l8059, snap8065.runupFromLocalLowPct,"))
        assertTrue(gate.contains("snap8065.currentVsPeak, nowMs, admittedJudged8201)?.let { reason ->"))
        val start = gate.indexOf("internal fun chaseRefusal8397(")
        val end = gate.indexOf("private fun developingRunGrab8117(", start)
        assertTrue("the canonical chase helper must exist before its developing-run helper", start >= 0 && end > start)
        return gate.substring(start, end)
    }

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
        // superseded by V5.0.8468 — a proven lane trigger (LaneTrigger8468) also stands the picked-setup read down.
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("if (proven8059 == null && !admittedJudged8201 && !trigger8468 && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.cortex.LanePlaybook7907.pickedSetupNegative8094(ts, l8059, nowMs)"))
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
        assertTrue(body.contains("queuedAnswer8402(ts.mint, \"1m\", 30, pool, 3)"))
        assertTrue(body.contains("mergeIntoHistory7819(ts, answer.rows)"))
        assertTrue(body.contains("keylessBarsAt8094.remove(ts.mint, nowMs)"))
        assertTrue(plan.contains("ThreadPoolExecutor.AbortPolicy()"))
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
        // 8168: the fingerprint also carries the resident scope (fp(ts,paper,residents)); the fresh mark still precedes it.
        assertTrue(bridge.indexOf("CandidateMarkFresh8094.ensureFresh8094(ts)") in 0 until bridge.indexOf("val f=fp(ts,paper,"))
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
        // superseded by V5.0.8337 — the door now runs BEFORE the share budget on the requested fraction (owner's fix block 9a).
        assertTrue(x.contains("if (partialUneconomic8094(ts, requested8337, reason)) {"))             // shared partial door
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
        assertTrue(t.tacticLosing8094(12, 0, -15.6))     // 0W/12L
        assertFalse(t.tacticLosing8094(3, 0, -15.6))     // V5.0.8164 — 0W/3L is too few closes to veto a lane's base
        assertFalse(t.tacticLosing8094(11, 0, -15.6))    // too few closes
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
        // V5.0.8175 — superseded by V5.0.8174: the band is frozen at the decision (Cortex7885.capture) and only falls back
        // to the token's own band when none was passed; it is still graded per setup x timing below.
        assertTrue(src.contains("pendingTiming8094[\"${'$'}{ts.mint}|${'$'}{labelLane.trim().uppercase()}\"] = timingAtDecision8174 ?: timingBandOf8094(ts)"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("val timing8174 = try { LanePlaybook7907.timingBandOf8094(ts) }"))
        // superseded by V5.0.8187: the timing book is a robust label book (read through the label cap).
        assertTrue(src.contains(".getOrPut(\"${'$'}setup|${'$'}timing8094\") { CortexLedger7885.labelStat8187() }"))
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
        // V5.0.8175 — superseded by V5.0.8174: the source is frozen at the decision (ts.source read before enqueue).
        assertTrue(cx.contains("Pending(a, admitted, nowMs, veto, frozenSource8174, setup7961, fired8094)"))
        assertTrue(cx.contains("val source8174 = ts.source"))
        // Primary and alternatives now share one deduplicated grading loop. Each original contribution retains
        // its own cell generation and decay stamp, so progressive labels revise all of them without double-counting.
        val grade = cx.substringAfter("private fun gradeModelsLocked8372(").substringBefore("// ── V5.0.8144")
        assertTrue(grade.contains("for (setup in (listOf(p.setup7961) + p.fired8094).filter { it.isNotBlank() }.distinct()) {"))
        assertTrue(grade.contains("jointKey7961(p.a.lane, p.a.bucket, setup)"))
        assertTrue(grade.contains("jointTrace.add(JointContribution8372(jk, jointGenerations8372.getValue(jk), stamp))"))
        assertTrue(grade.contains("?.jointTrace8372 = jointTrace"))
        assertTrue(cx.contains("LanePlaybook7907.selectedSetup8094(ts, a.lane, System.currentTimeMillis())"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("val setup8083 = admittedSetup(admission8136) { try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, l8059, nowMs) }"))
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
        assertTrue(bot.contains("SpecialistOwnership7951.readyCellProvenEvaluates8094(l, ts.mint, primaryLane) ||  // V5.0.8094"))  // 8214: native-claim slot follows
        val own = src("engine/SpecialistOwnership7951.kt")
        assertTrue(own.contains("LiveEdgeGate7877.cellOrKindProven8094(ts, l)"))
        // Not the primary, not READY, or no token state: no evaluation.
        assertFalse(SpecialistOwnership7951.readyCellProvenEvaluates8094("MANIPULATED", "NO_SUCH_MINT_8094", "MOONSHOT"))
        assertFalse(SpecialistOwnership7951.readyCellProvenEvaluates8094("MOONSHOT", "NO_SUCH_MINT_8094", "MOONSHOT"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("if (proven8059 == null && !admittedJudged8201 && !cellOrKindProven8094(ts, l8059, nowMs)) (try { com.lifecyclebot.engine.learning.TacticSwitcher.losingTacticFor8094(ts, l8059)"))
    }

    @Test fun runnersAreEnteredOnTheirCellsProofAndHeldOnTheRunnerRules() {
        val g = com.lifecyclebot.engine.truth.LiveEdgeGate7877
        assertTrue(g.cellWinsProven8094(65, 0.75, 737.0))      // n65 wr75% +737%: proven by its win count
        assertFalse(g.cellWinsProven8094(65, 0.55, 40.0))      // a coin-flip win rate is not proof
        assertFalse(g.cellWinsProven8094(20, 0.90, 300.0))     // too few labels
        assertFalse(g.cellWinsProven8094(65, 0.80, -1.0))      // negative mean
        val chase = chaseDecisionSource8397()
        // superseded (8480): label-record chase stand-downs removed (they read survivors with labels lost: LOSS_BOUGHT_TOP=13)
        assertFalse(chase.contains("val provenCell = cellOrKindProven8094(ts, lane, nowMs)"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("C3_STOOD_DOWN_CELL_PROVEN_8094"))
        assertTrue(src("engine/Executor.kt").contains("it.startsWith(\"MANUFACTURED_CLIMB\") && freshProvenRunner8094(ts)"))
        // 8435: the proven-cell pass is the first of two outside-ring returns (the second is the lane's own READY proposal on its native slot)
        assertTrue(src("engine/BotService.kt").contains("if (designatedDeskQualified6599 && SpecialistOwnership7951.readyCellProvenEvaluates8094(l, ts.mint, primaryLane)) return true"))
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
        assertTrue(pb.contains("provenLosingNamedSetupRefusal(laneRaw, selectedSetup8094(ts, canon(laneRaw), nowMs))"))
        assertEquals(2, Regex("val setup = selectedSetup8094\\(ts, lane, nowMs\\)  // V5.0.8094 — one strategy identity").findAll(pb).count())
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("val setupAt = admittedSetup(resolvedAdmission) { try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, l8083, nowMs) }"))
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
        // superseded (8480): label-record chase stand-downs removed (they read survivors with labels lost: LOSS_BOUGHT_TOP=13)
        assertFalse(chaseDecisionSource8397().contains("spikeRuleRefusesRunners8097"))
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("Cortex7885.vetoRefusesWinners8006(LaneParticipation8029.SHADOW_REASON, lane7951(lane))"))
    }

    @Test fun aProvenTradeFreesTheLanesPoorestSlot() {
        val r = com.lifecyclebot.engine.truth.OpportunityRotation8098
        assertEquals(10, com.lifecyclebot.engine.truth.LiveRiskPolicy7807.budgetFor("MOONSHOT").maxConcurrentLive)
        // Eligible: red / flat, not a runner, past 3 minutes, verified mark, not the moonbag.
        assertTrue(r.victimEligible8098(-6.0, 4.0, 10 * 60_000L, isTail = false, markFresh = true))
        assertFalse(r.victimEligible8098(-6.0, r.RUNNER_PEAK_PCT, 10 * 60_000L, isTail = false, markFresh = true))   // a runner
        assertFalse(r.victimEligible8098(r.MAX_VICTIM_PNL_PCT, 0.0, 10 * 60_000L, isTail = false, markFresh = true))
        assertFalse(r.victimEligible8098(12.0, 12.0, 10 * 60_000L, isTail = false, markFresh = true))   // winning
        assertFalse(r.victimEligible8098(-6.0, 0.0, 60_000L, isTail = false, markFresh = true))         // too young
        assertFalse(r.victimEligible8098(-6.0, 0.0, 10 * 60_000L, isTail = true, markFresh = true))     // moonbag
        assertFalse(r.victimEligible8098(-6.0, 0.0, 10 * 60_000L, isTail = false, markFresh = false))   // no verified mark
        assertEquals("B", r.poorest8098(listOf(com.lifecyclebot.engine.truth.OpportunityRotation8098.Held("A", -2.0), com.lifecyclebot.engine.truth.OpportunityRotation8098.Held("B", -9.0), com.lifecyclebot.engine.truth.OpportunityRotation8098.Held("C", 3.0))))
        // No demand, no rotation; an unproven candidate never creates demand.
        r.noteDemand8098("SHITCOIN", "CANDIDATE_8098", 0.0)
        assertFalse(r.hasDemand8098("SHITCOIN"))
        r.noteDemand8098("SHITCOIN", "CANDIDATE_8098", 0.05)
        assertTrue(r.shouldRotate8098("B", "SHITCOIN", listOf(com.lifecyclebot.engine.truth.OpportunityRotation8098.Held("A", -2.0), com.lifecyclebot.engine.truth.OpportunityRotation8098.Held("B", -9.0))))
        assertTrue(com.lifecyclebot.engine.LifecycleHold8065.passes8065(r.REASON))
        assertTrue(src("engine/Executor.kt").contains("if (opportunityRotation8098(ts, wallet, walletSol)) return true"))
    }

    @Test fun aDegradedProviderReadNoLongerBlocksATokenWithAFreshPrice() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("if (dexSr < 0.70 && jupQSr < 0.60) {"))
        assertTrue(ex.contains("if (!providerDegradedBuyAllowed8099(ts, layerTag, ec?.entrySetup, ec?.chartPattern, dexSr, jupQSr)) {"))
        val f = com.lifecyclebot.engine.truth.CandidateMarkFresh8094
        assertFalse(f.stale8094(0.0001, 1_000_000L, 1_010_000L))   // 10 s old: fresh, buy may proceed
        assertTrue(f.stale8094(0.0001, 1_000_000L, 1_020_000L))    // 20 s old: still blocked while both providers are down
    }

    @Test fun theExecutedTradeTapeFillsOnlyWhatTheProvidersLeftUnobserved() {
        val t = com.lifecyclebot.engine.truth.TradeTape8100
        t.resetForTest8100()
        assertEquals(null, t.buyPressure8100(listOf(true to 1.0, false to 1.0)))          // too few trades
        assertEquals(75.0, t.buyPressure8100(List(6) { it to 1.0 }.map { (i, v) -> (i < 4) to (if (i < 4) 1.5 else 1.0) }) ?: 0.0, 0.01)
        val sh = t.shape8100(listOf(1.0, 2.0, 1.5))!!
        assertEquals(50.0, sh.runupPct, 1e-9); assertEquals(0.75, sh.vsPeak, 1e-9); assertEquals(25.0, sh.ddPct, 1e-9)
        assertEquals(null, t.shape8100(listOf(1.0, 2.0)))
        val now = 10_000_000L
        for (m in 0 until 8) for (k in 0 until 2) t.note8100("TAPE_8100", 1.0 + m * 0.1, 0.5, k == 0, now - (7 - m) * 60_000L + k * 1_000L)
        val r = t.read8100("TAPE_8100", now + 2_000L)!!
        assertTrue(r.shape != null && r.bp != null)
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("val tapeBp8100 = if (!bpKnown) tape8100?.bp else null"))
        // superseded by V5.0.8285 — the PumpPortal frame reaches the tape with its provenance (signature, venue, token size)
        assertTrue(src("network/PumpFunWS.kt").contains("tapeFrame8285(j, mint, priceSol, sol7743, tok7743, txType == \"buy\")"))
        t.resetForTest8100()
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

    @Test fun aWebsocketEndpointWithoutAccountSubscribeIsBenchedNotRetried() {
        val c = com.lifecyclebot.network.CurveTicks7968
        val all = listOf("wss://a", "wss://b")
        assertEquals(all, c.usableEndpoints8102(all, emptyMap(), 10_000L))
        assertEquals(listOf("wss://b"), c.usableEndpoints8102(all, mapOf("wss://a" to 9_000L), 10_000L, benchMs = 5_000L))
        // The bench expires: the endpoint is tried again later.
        assertEquals(all, c.usableEndpoints8102(all, mapOf("wss://a" to 1_000L), 10_000L, benchMs = 5_000L))
        assertTrue(src("network/CurveTicks7968.kt").contains("if (code == -32601) benchCurrent8102()"))
    }

    @Test fun aToken2022AccountWithWithheldFeesIsSkippedNotRetriedEveryPass() {
        assertTrue(RentReclaimer7927.withheldFeesRefusal8102("Transaction simulation failed: Error processing Instruction 0: custom program error: 0x23"))
        assertFalse(RentReclaimer7927.withheldFeesRefusal8102("custom program error: 0x1"))
        assertFalse(RentReclaimer7927.withheldFeesRefusal8102(null))
    }

    @Test fun aLosingLiveBookIsSeenAfterFiveClosesNotEight() {
        val t = com.lifecyclebot.engine.truth.LiveTruth8064
        // 5.0.8098 CASHGEN: six closes, one win, mean -18.5% -> proven losing now.
        assertTrue(t.provenLosing8064(listOf(-97.0, -8.0, 6.0, -12.0, -5.0, -9.0)))
        assertFalse(t.provenLosing8064(listOf(-12.0, -5.0, -20.0, -3.0)))            // four closes: still too few
        assertFalse(t.provenLosing8064(listOf(-12.0, 140.0, -20.0, -3.0, -7.0)))      // a runner pays for the losers
    }

    @Test fun everyLiveMemeBuyRidesTheRunnerLadderFromEntry() {
        assertTrue(TradeExitContract8077.runnerFromEntryLane8104("MOONSHOT", paper = false))
        assertTrue(TradeExitContract8077.runnerFromEntryLane8104("SHITCOIN", paper = false))
        assertTrue(TradeExitContract8077.runnerFromEntryLane8104("EXPRESS", paper = false))
        assertTrue(TradeExitContract8077.runnerFromEntryLane8104("PROJECT_SNIPER", paper = false))
        assertFalse(TradeExitContract8077.runnerFromEntryLane8104("CASHGEN", paper = false))   // the scalp lane keeps its ladder
        assertFalse(TradeExitContract8077.runnerFromEntryLane8104("MOONSHOT", paper = true))
        // A +40..+199% peak keeps half its gain; the 7980 capture owns +200% and above.
        assertTrue(RunnerGrab7967.fromEntryGiveback8104(176.0, 80.0))
        assertFalse(RunnerGrab7967.fromEntryGiveback8104(176.0, 100.0))
        assertFalse(RunnerGrab7967.fromEntryGiveback8104(30.0, 0.0))
        assertFalse(RunnerGrab7967.fromEntryGiveback8104(400.0, 150.0))
        val rg = src("engine/RunnerGrab7967.kt")
        assertTrue(rg.contains("if (TradeExitContract8077.runnerFromEntry8104(ts)) return nowMs - ts.position.entryTime <= HOLD_WINDOW_MS"))
        assertTrue(src("engine/BotService.kt").contains("else if (TradeExitContract8077.runnerFromEntry8104(ts)) null"))
        assertTrue(src("engine/TailBag8034.kt").contains("if (!TradeExitContract8077.runnerFromEntry8104(ts) && TradeExitContract8077.longHold(ts) == false) return Decision.Pass"))
    }

    @Test fun aFullLiveBookRotatesThePoorestPositionOfAnyLane() {
        val r = com.lifecyclebot.engine.truth.OpportunityRotation8098
        // Clear of any rotation an earlier test stamped on the shared cooldown clock.
        val now = System.currentTimeMillis() + 3_600_000L
        assertFalse(r.bookDemand8103(now))
        r.noteBookDemand8103("NEWMINT", edgeKellyFrac = 0.0, nowMs = now)      // unproven: no demand
        assertFalse(r.bookDemand8103(now))
        r.noteBookDemand8103("NEWMINT", edgeKellyFrac = 0.05, nowMs = now)
        assertTrue(r.bookDemand8103(now))
        val held = listOf(com.lifecyclebot.engine.truth.OpportunityRotation8098.Held("A", -4.0), com.lifecyclebot.engine.truth.OpportunityRotation8098.Held("B", -9.0))
        assertFalse(r.shouldRotate8098("A", r.BOOK_8103, held, now))
        assertTrue(r.shouldRotate8098("B", r.BOOK_8103, held, now))
        assertFalse(r.bookDemand8103(now))                                    // one rotation per demand
        assertTrue(src("engine/truth/LiveRiskPolicy7807.kt").contains("OpportunityRotation8098.noteBookDemand8103(mint, i.edgeKellyFrac8057)"))
    }

    @Test fun aRetiredNativeVetoIsHonouredAtTheLaneBuyDoor() {
        val b = SpecialistBrainBridge7542
        val proven = { rule: String, lane: String -> rule == "NATIVE_MCAP_TOO_LOW" && lane == "MANIPULATED" }
        // 5.0.8098: NATIVE_MCAP_TOO_LOW@MANIPULATED retired 1,685 times in the desk while the buy branch refused every one.
        assertTrue(b.doorRetires8105("MANIPULATED", eligible = false, reason = "MCAP_TOO_LOW", audit = proven))
        assertFalse(b.doorRetires8105("EXPRESS", eligible = false, reason = "MCAP_TOO_LOW", audit = proven))       // not proven in this lane
        assertFalse(b.doorRetires8105("MANIPULATED", eligible = true, reason = "MCAP_TOO_LOW", audit = proven))   // nothing to retire
        // Safety / rug / liquidity refusals are never retired, whatever the audit says.
        val always = { _: String, _: String -> true }
        assertFalse(b.doorRetires8105("MOONSHOT", false, "liq_too_low_0K", always))
        assertFalse(b.doorRetires8105("SHITCOIN", false, "RUG_RISK dev sold", always))
        assertFalse(b.doorRetires8105("EXPRESS", false, "", always))
        assertTrue(b.doorRetires8105("EXPRESS", false, "CHASE_EXTENDED: mom=1.0% chg5m=34.5%", always))
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("var moonshotScore = laneDoor8105(com.lifecyclebot.v3.scoring.MoonshotTraderAI.scoreToken("))
        assertTrue(bs.contains("val manipSignal = laneDoor8105(com.lifecyclebot.v3.scoring.ManipulatedTraderAI.evaluate("))
        assertTrue(bs.contains("val expressSignal = laneDoor8105(com.lifecyclebot.v3.scoring.ShitCoinExpress.evaluate("))
        assertTrue(bs.contains("var shitCoinSignal = laneDoor8105(com.lifecyclebot.v3.scoring.ShitCoinTraderAI.evaluate("))
    }

    @Test fun eachCryptoDeskLaneLearnsInItsOwnBook() {
        val x = com.lifecyclebot.engine.cortex.CrossAssetCortex7931
        val crypto = com.lifecyclebot.engine.truth.AssetClass.CRYPTO_ALT
        assertEquals("CRYPTO_MOONSHOT", x.laneFor8107(crypto, "MOONSHOT"))
        assertEquals("CRYPTO_QUALITY", x.laneFor8107(crypto, "quality"))
        assertEquals("CRYPTO_ALT", x.laneFor8107(crypto, "NONE"))          // no desk election: the pooled book
        assertEquals("CRYPTO_ALT", x.laneFor8107(crypto, null))
        assertEquals("MKT_STOCK", x.laneFor8107(com.lifecyclebot.engine.truth.AssetClass.STOCK, "MOONSHOT"))
        assertTrue(x.isCrossAssetLane("CRYPTO_MOONSHOT"))
        assertTrue(x.isCrossAssetLane("CRYPTO_ALT"))
        assertFalse(x.isCrossAssetLane("MOONSHOT"))                       // the meme lane stays the meme lane
        assertFalse(x.isCrossAssetLane("CRYPTO_LEV"))
    }

    @Test fun aHeldLoserThatNeverRanOrWhoseMetricsCollapsedIsReleased() {
        val h = LifecycleHold8065
        val min = 60_000L
        assertTrue(h.unprovenLoser8109(peakPct = 8.0, grossPct = -6.0, ageMs = 50 * min))
        assertTrue(h.unprovenLoser8109(peakPct = 3.0, grossPct = -22.0, ageMs = 5 * min))
        assertFalse(h.unprovenLoser8109(peakPct = 3.0, grossPct = -6.0, ageMs = 10 * min))
        assertFalse(h.unprovenLoser8109(peakPct = 60.0, grossPct = -6.0, ageMs = 90 * min))
        // Metrics collapse releases any red hold: liquidity under half its entry depth, or sellers own the tape.
        assertTrue(h.metricsCollapsed8109(-5.0, entryLiqUsd = 40_000.0, liqNowUsd = 15_000.0, buySharePct = 50.0, buyShareFresh = false))
        assertTrue(h.metricsCollapsed8109(-5.0, 40_000.0, 38_000.0, buySharePct = 18.0, buyShareFresh = true))
        assertFalse(h.metricsCollapsed8109(-5.0, 40_000.0, 38_000.0, buySharePct = 18.0, buyShareFresh = false))  // stale read
        assertFalse(h.metricsCollapsed8109(+5.0, 40_000.0, 10_000.0, 10.0, true))                                 // green: not a loser
        assertFalse(h.metricsCollapsed8109(-5.0, 0.0, 10_000.0, 50.0, true))                                       // entry depth unknown
    }

    @Test fun aCryptoTakeProfitFollowsItsDeskLaneAndNeverSitsUnderCost() {
        val c = com.lifecyclebot.perps.CryptoAltTrader
        // 8098: a +3% target against a 3.6% round trip netted nothing. Every lane is floored at cost + buffer.
        val floor = com.lifecyclebot.engine.truth.NetEdge8030.exitFloorPct8030(3.6)
        assertEquals(floor, c.laneTpPct8110("QUALITY", baseTpPct = 3.0, costPct = 3.6, isSpot = true), 1e-9)
        assertEquals(12.0, c.laneTpPct8110("QUALITY", baseTpPct = 12.0, costPct = 3.6, isSpot = true), 1e-9)
        // A runner desk lane takes nothing before the runner ladder's first rung.
        assertEquals(100.0, c.laneTpPct8110("MOONSHOT", 3.0, 3.6, isSpot = true), 1e-9)
        assertEquals(100.0, c.laneTpPct8110("SHITCOIN", 3.0, 3.6, isSpot = true), 1e-9)
        // Leverage keeps its own target (floored), never the runner rung.
        assertEquals(floor, c.laneTpPct8110("MOONSHOT", 3.0, 3.6, isSpot = false), 1e-9)
    }

    @Test fun aRetiredVetoPopulationIsNotHeldBackByTheGenericScoreFloor() {
        assertFalse(SpecialistBrainBridge7542.nativeRetiredFor8112("noSnapshot8112", "MANIPULATED"))   // no snapshot: floor stands
        val f = src("engine/FinalDecisionGate.kt")
        val helper = f.substringAfter("private fun playbookClearsFloor7937(").substringBefore("private fun cortexPaperChoice7915(")
        assertTrue(helper.contains("SpecialistBrainBridge7542.nativeRetiredFor8112(ts.mint, lane)"))
        // The floor helper receives the same candidate generation and admission as the live gate.
        assertTrue(f.contains("!playbookClearsFloor7937(ts, floorLane7266, waitFloor7266, candidateVersion7623, candidateAdmission8136)"))
    }

    @Test fun aConfirmedRunIsItsOwnProofAtTheProvenOnlyDoorAndTheChaseRule() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        // superseded (8480, owner directive "no evidence is not proven bad"): the proven-only door is gone; a forming run still releases watch-first (StrategyWatch8153)
        assertTrue(g.contains("!kind && runnerForming8113(ts, nowMs), !kind && shadowAuditAdmits8113(ts, lane)"))
        // A forming run alone does not clear the chase rule. The restored, independently guarded active ticket does.
        val chase = chaseDecisionSource8397()
        assertFalse(chase.contains("runnerForming8113"))
        assertTrue(chase.contains("RunnerPlay8018.ticketActive8018(ts.mint, nowMs)"))
        val helper = g.substringAfter("private fun runnerForming8113(").substringBefore("private fun shadowAuditAdmits8113(")
        assertTrue(helper.contains("RunnerPlay8018.ticketActive8018(ts.mint, nowMs)") && helper.contains("RunnerGrab7967.grab7967(ts.mint, nowMs)"))
    }

    @Test fun aRugWithNoRouteIsConfirmedNotHeldAsAPhantom() {
        val d = com.lifecyclebot.engine.truth.DeepRedConfirm8116
        // Pool drained to under 30% of entry depth: confirmed at once.
        assertTrue(d.confirms(false, firstSeenMs = 1_000L, nowMs = 1_500L, markAgeMs = 2_000L, entryLiqUsd = 40_000.0, liqNowUsd = 5_000.0))
        // Depth unknown but the deep read persisted 45 s on a fresh mark: confirmed.
        assertTrue(d.confirms(false, 1_000L, 46_500L, 5_000L, 0.0, 0.0))
        // Fresh read, depth fine: still the phantom guard.
        assertFalse(d.confirms(false, 1_000L, 10_000L, 5_000L, 40_000.0, 38_000.0))
        // Persisted but on a stale mark: not confirmed.
        assertFalse(d.confirms(false, 1_000L, 90_000L, 120_000L, 40_000.0, 38_000.0))
        // An uncertain basis never confirms here.
        assertFalse(d.confirms(true, 1_000L, 90_000L, 1_000L, 40_000.0, 1_000.0))
        assertTrue(src("engine/BotService.kt").contains("if (execPx == null) return deepRedWithoutRoute8116(ts, pnlPct)"))
    }

    @Test fun theLossStreakCooldownIsASettingOffByDefault() {
        assertFalse(com.lifecyclebot.data.BotConfig().lossStreakCooldownEnabled)
        assertTrue(src("engine/KillSwitch.kt").contains("config7835.lossStreakCooldownEnabled && verifiedLossCooldown8081("))
        assertTrue(src("ui/SettingsBottomSheet.kt").contains("lossStreakCooldownEnabled = switchLossStreakCooldown.isChecked"))
    }

    @Test fun aRunIsBoughtWhileItDevelopsNotAtItsPeak() {
        val g = com.lifecyclebot.engine.truth.LiveEdgeGate7877
        assertTrue(g.earlyRun8117(runupFromLowPct = 85.0, chg5mPct = 40.0))      // developing: buy the grab
        assertFalse(g.earlyRun8117(runupFromLowPct = 1_238.0, chg5mPct = 30.0))  // 34fph3: the peak, not the run
        assertFalse(g.earlyRun8117(runupFromLowPct = 90.0, chg5mPct = 140.0))    // a vertical 5-minute spike
        assertFalse(g.earlyRun8117(Double.NaN, 10.0))                            // unknown run-up: the chase rule decides
        val chase = chaseDecisionSource8397()
        // superseded (8480): label-record chase stand-downs removed (they read survivors with labels lost: LOSS_BOUGHT_TOP=13)
        assertTrue(chase.contains("if (developingRunGrab8117(ts, runupFromLowPct, nowMs)) return null"))
        assertFalse(chase.contains("runnerForming8113")) // broad forming-run status is not an exemption
    }

    @Test fun aPumpLaunchIsAdmittedWhenItEntersTheProvenBand() {
        val b = com.lifecyclebot.engine.truth.PumpBandEntry8118
        val min = 60_000L
        assertTrue(b.inBand8118(capUsd = 12_000.0, ageMs = 4 * min))
        assertFalse(b.inBand8118(capUsd = 6_000.0, ageMs = 4 * min))        // still under $10k: the losing band
        assertFalse(b.inBand8118(capUsd = 140_000.0, ageMs = 4 * min))      // past the band
        assertFalse(b.inBand8118(capUsd = 30_000.0, ageMs = 20 * min))      // too old for the proven cell
        b.noteCreate8118("band8118mint", "BAND", "Band Coin")
        assertNull(b.claim8118("band8118mint", 6_000.0, 2 * min))            // not in band yet: nothing claimed
        assertNotNull(b.claim8118("band8118mint", 15_000.0, 3 * min))        // enters the band: admitted once
        assertNull(b.claim8118("band8118mint", 20_000.0, 4 * min))           // never twice
        assertNull(b.claim8118("unknownMint8118", 15_000.0, 3 * min))        // not a PumpPortal launch
        assertTrue(src("engine/BotService.kt").contains("try { bandEntry8118(mint, priceSol, mcapSol, cfg, streamGeneration) } catch (_: Throwable) {}"))
    }

    @Test fun theSniperAcceptsIgnitionOnlyWhenItsSetupIsProven() {
        val s = com.lifecyclebot.v3.scoring.ProjectSniperAI
        assertTrue(s.ignitionAdmissible8118(com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION, provenSetup = true))
        assertFalse(s.ignitionAdmissible8118(com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.IGNITION, provenSetup = false))   // 7794's -23% case stays closed
        assertFalse(s.ignitionAdmissible8118(com.lifecyclebot.engine.truth.LaunchPhaseAuthority7401.Phase.EXPANDING, provenSetup = true))
    }

    @Test fun coresNamedOwnerEvaluatesWhenCoreYields() {
        val o = src("engine/SpecialistOwnership7951.kt")
        assertTrue(o.contains("(native8094 && coreYieldedTo8119(mint, primaryLane, l))"))
        assertTrue(o.contains("laneFitRefusal8132(ops[p]?.reason)"))
        assertTrue(o.contains("if (ready && native8094 && !receiverRecordSound8094(mint, l))"))   // the receiver's record still guards it
    }

    @Test fun theUiThreadNeverScansTheLeaderboardOrSortsEveryRow() {
        assertTrue(src("engine/LiveLayerGateRelaxer.kt").contains("android.os.Looper.myLooper() == android.os.Looper.getMainLooper()"))
        val m = src("ui/MainActivity.kt")
        assertTrue(m.contains(".let { newestTokens8119(it, 8) }"))
        assertTrue(src("engine/MemoryGuard7977.kt").contains("c.flushNow(); c.evictToMemoryCap7979("))
    }

    @Test fun aCoinFrozenByItsIssuerIsBlacklistedAndHandedToThePoller() {
        val a = com.lifecyclebot.engine.truth.WalletTokenAccountStateAuthority7253
        a.applyParsedSnapshot(setOf("frozen8122mint", "fine8122mint"), setOf("frozen8122mint"))
        assertTrue(a.isFrozen("frozen8122mint"))
        assertFalse(a.isFrozen("fine8122mint"))
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("if (frozenByIssuer8122(ts)) return frozenHandOff8122(ts, reason)"))
        assertTrue(e.contains("if (absent7736 != null && absent7736.count >= 2) {"))                     // 7736 pin intact
        assertTrue(e.contains("TokenBlacklist.block(ts.mint, \"FROZEN_BY_ISSUER_8122\")"))
        a.applyParsedSnapshot(setOf("frozen8122mint"), emptySet())
    }

    @Test fun aToken2022MintThatCanTrapAHolderIsNeverBought() {
        val P = com.lifecyclebot.engine.truth.OnChainMintAuthorityProof7248
        fun ext(json: String) = P.hostileExtension8123(org.json.JSONArray(json))
        assertNull(P.hostileExtension8123(null))                                                         // classic SPL mint
        assertNull(ext("""[{"extension":"metadataPointer","state":{"authority":null}}]"""))               // harmless
        assertNotNull(ext("""[{"extension":"permanentDelegate","state":{"delegate":"Dg8122"}}]"""))       // can take/burn tokens
        assertNotNull(ext("""[{"extension":"pausableConfig","state":{"authority":"Pa8122","paused":false}}]"""))
        assertNotNull(ext("""[{"extension":"transferHook","state":{"authority":null,"programId":"Hk8122"}}]"""))
        assertNull(ext("""[{"extension":"transferHook","state":{"authority":null,"programId":null}}]"""))
        assertNotNull(ext("""[{"extension":"defaultAccountState","state":{"accountState":"frozen"}}]"""))
        assertNotNull(ext("""[{"extension":"nonTransferable"}]"""))
        assertNotNull(ext("""[{"extension":"transferFeeConfig","state":{"newerTransferFee":{"transferFeeBasisPoints":2500}}}]"""))
        assertNull(ext("""[{"extension":"transferFeeConfig","state":{"newerTransferFee":{"transferFeeBasisPoints":100}}}]"""))
        val gate = src("engine/truth/FreezeAuthorityHardBlock7238.kt")
        assertTrue(gate.contains("if (freeze == true && proof == null) {"))                                // cached true is re-proved on chain
        assertTrue(gate.contains("TOKEN2022_HOSTILE_EXTENSION_\$hostile"))
        assertTrue(src("perps/crypto/CryptoUniverseExecutor.kt").contains("freezableRefusal8123(mint, direction, resolution)?.let"))
    }

    @Test fun aFailedFeedServesItsLastGoodRowsAndListsRefreshOnTheirOwnClock() {
        val m = com.lifecyclebot.engine.market.MarketSweep7297
        assertEquals(10L * 60_000L, m.refreshMs8124("JUP_TRADED_24H"))
        assertEquals(60_000L, m.refreshMs8124("JUP_TRENDING_5M"))
        assertEquals(3L * 60_000L, m.refreshMs8124("JUP_TRENDING"))
        assertEquals(0L, m.refreshMs8124("RAYDIUM_VOLUME"))                         // non-Jupiter feeds keep the sweep cadence
        val row = com.lifecyclebot.engine.market.MarketSweep7297.Row("Feed8124Mint1111111111111111111111111", "F", "F",
            1.0, 50_000.0, 10_000.0, 1_000.0, 10_000.0, 5.0, 10, 100, 50.0, false, 1.0, setOf("FEED_8124"))
        val t0 = 1_000_000L
        assertEquals(1, m.carryOrStore8124("FEED_8124", listOf(row), t0).size)
        assertEquals(1, m.carryOrStore8124("FEED_8124", emptyList(), t0 + 5 * 60_000L).size)    // failed: last good rows
        assertTrue(m.carryOrStore8124("FEED_8124", emptyList(), t0 + 25 * 60_000L).isEmpty())   // too old to stand in
    }

    @Test fun aRateLimitOnDiscoveryOrChartsNeverLocksOutPriceLookups() {
        val h = com.lifecyclebot.network.HostCircuitInterceptor
        assertEquals("dexscreener", h.labelFor8125("api.dexscreener.com", "/latest/dex/tokens/abc", null))
        assertEquals("dexscreener", h.labelFor8125("api.dexscreener.com", "/tokens/v1/solana/abc", null))
        assertEquals("dexscreener_discovery", h.labelFor8125("api.dexscreener.com", "/token-boosts/latest/v1", null))
        assertEquals("dexscreener_discovery", h.labelFor8125("api.dexscreener.com", "/latest/dex/search", null))
        assertEquals("dexscreener_chart", h.labelFor8125("api.dexscreener.com", "/tokens/v1/solana/abc", "chart"))
        assertEquals("jupiter_price", h.labelFor8125("lite-api.jup.ag", "/price/v3", null))
        assertEquals("jupiter", h.labelFor8125("lite-api.jup.ag", "/swap/v1/quote", null))
        assertEquals("jupiter_tokens", h.labelFor8125("lite-api.jup.ag", "/tokens/v2/recent", null))
        assertEquals("", h.labelFor8125("example.org", "/x", "chart"))                       // unmapped hosts stay unmapped
        assertTrue(src("engine/chart/ChartSources7955.kt").contains("HostCircuitInterceptor.LANE_HEADER_8125, \"chart\""))
        assertTrue(src("engine/sell/SellReconciler.kt").contains("ExitHttpScope7314.run { com.lifecyclebot.network.DexscreenerApi().batchPriceFetch(openMints) }"))
    }

    @Test fun aPeakThatJumpsOnOneUncorroboratedMarkIsNotRatcheted() {
        val d = com.lifecyclebot.engine.truth.PositionDisplayTruth8078
        assertTrue(d.jumpNeedsCorroboration8128(0.0, 1987.0))          // SAC: ~0% to +1987% in one tick
        assertFalse(d.jumpNeedsCorroboration8128(60.0, 150.0))         // a run climbing: 1.6x -> 2.5x, under the bar
        assertFalse(d.jumpNeedsCorroboration8128(0.0, 90.0))           // under +100% never needs it
        assertTrue(d.jumpNeedsCorroboration8128(150.0, 500.0))         // 2.5x -> 6x in one step
        assertFalse(d.markAgrees8128(8.06e-5, null))                   // no corroborated price: refuse the jump
        assertFalse(d.markAgrees8128(8.06e-5, 3.44e-6))                // 23x off the agreed price
        assertTrue(d.markAgrees8128(4.0e-5, 3.5e-5))                   // within 25%
        assertTrue(d.peakStepCorroborated8128("noJump8128Mint", 60.0, 150.0, 1.0))
        assertFalse(d.peakStepCorroborated8128("phantom8128Mint", 0.0, 1987.0, 8.06e-5))
    }

    // ── V5.0.8128 — single-source data fallbacks ──

    @Test fun aCandidateWithoutADexScreenerPairTakesRaydiumsPoolAndTvl() {
        val r = com.lifecyclebot.network.RaydiumMintPools8128
        val mint = "Ray8128Mint".padEnd(44, 'A')
        val pool = "Pool8128Id".padEnd(44, 'B')
        val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val sol = "So11111111111111111111111111111111111111112"
        val p = r.parsePool8128("""{"success":true,"data":{"count":1,"data":[{"id":"$pool","mintA":{"address":"$mint"},"mintB":{"address":"$usdc"},"tvl":42000.5,"price":0.0025}]}}""", mint)!!
        assertEquals(pool, p.poolId)
        assertEquals(42_000.5, p.tvlUsd, 1e-9)
        assertEquals(0.0025, p.priceUsd!!, 1e-12)
        // SOL-quoted: Raydium's price is a pool ratio (SOL per token), not USD -> no price.
        assertNull(r.parsePool8128("""{"data":{"data":[{"id":"$pool","mintA":{"address":"$sol"},"mintB":{"address":"$mint"},"tvl":900,"price":12.0}]}}""", mint)!!.priceUsd)
        // A pool of another mint, an empty page, garbage: nothing.
        assertNull(r.parsePool8128("""{"data":{"data":[{"id":"$pool","mintA":{"address":"$sol"},"mintB":{"address":"$usdc"},"tvl":9}]}}""", mint))
        assertNull(r.parsePool8128("""{"success":true,"data":{"count":0,"data":[]}}""", mint))
        assertNull(r.parsePool8128("not json", mint))
        // Known liquidity wins (a Raydium-only TVL must not read as a drain); TVL fills only an unknown one.
        assertEquals(15_000.0, r.standInLiquidity8128(15_000.0, p), 1e-9)
        assertEquals(42_000.5, r.standInLiquidity8128(0.0, p), 1e-9)
        assertEquals(0.0, r.standInLiquidity8128(0.0, null), 1e-9)
        // Market cap: known wins; else price x on-chain supply; else unknown.
        assertEquals(50_000.0, r.standInMcap8128(50_000.0, 1.0, 9.0), 1e-9)
        assertEquals(20_000.0, r.standInMcap8128(0.0, 0.00002, 1.0e9), 1e-6)
        assertEquals(0.0, r.standInMcap8128(0.0, 0.00002, 0.0), 1e-9)
        // The pool id fills only a blank pair, never a pump.fun mint / source (bonding-curve route).
        assertEquals(pool, r.pairAddressFill8128("", "DEX_TRENDING", false, p))
        assertNull(r.pairAddressFill8128("ExistingPair8128", "DEX_TRENDING", false, p))
        assertNull(r.pairAddressFill8128("", "PUMP_FUN_NEW", false, p))
        assertNull(r.pairAddressFill8128("", "DEX_TRENDING", true, p))
        assertNull(r.pairAddressFill8128("", "DEX_TRENDING", false, null))
        // The stand-in pair itself stays SYNTHETIC (blank pairAddress) so 7271 never relabels a seed price.
        val synth = src("engine/BotService.kt").substringAfter("private fun synthesizeFallbackPair(").substringBefore("private fun refreshExitMarkFromVenue7948(")
        assertTrue(synth.contains("pairAddress = \"\","))
        assertTrue(synth.contains("RaydiumMintPools8128.standInLiquidity8128(ts.lastLiquidityUsd, ray8128)"))
        assertTrue(synth.contains("RaydiumMintPools8128.fillPairAddress8128(ts, ray8128)"))
    }

    @Test fun theRaydiumScannerAsksRaydiumAndAnEmptyScanFallsBackToTheSweep() {
        val r = com.lifecyclebot.network.RaydiumMintPools8128
        val sol = "So11111111111111111111111111111111111111112"
        val usdc = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        val now = 2_000_000_000L
        fun pool(id: String, a: String, b: String, open: Long) =
            """{"id":"${id.padEnd(44, '1')}","mintA":{"address":"$a","symbol":"A","name":"Alpha"},"mintB":{"address":"$b","symbol":"B","name":"Beta"},"tvl":5000,"day":{"volume":800},"openTime":"$open"}"""
        val newA = "NewA8128".padEnd(44, 'A')
        val newB = "NewB8128".padEnd(44, 'B')
        val body = """{"success":true,"data":{"data":[
            ${pool("P1", newA, sol, now - 3_600)},
            ${pool("P2", usdc, newB, now - 600)},
            ${pool("P3", sol, usdc, now - 60)},
            ${pool("P4", "Old8128".padEnd(44, 'O'), sol, now - 30L * 86_400L)},
            ${pool("P5", "OthX8128".padEnd(44, 'X'), "OthY8128".padEnd(44, 'Y'), now - 60)},
            ${pool("P6", "NoTime8128".padEnd(44, 'T'), sol, 0L)}]}}"""
        val pools = r.parseNewPools8128(body, now)
        // Newest first; quote/quote, unquoted, old and undated pools left out.
        assertEquals(listOf(newB, newA), pools.map { it.mint })
        assertEquals("Beta", pools[0].name)
        assertEquals(5_000.0, pools[0].tvlUsd, 1e-9)
        assertEquals(800.0, pools[0].volume24hUsd, 1e-9)
        assertTrue(r.parseNewPools8128("""{"data":{"data":[]}}""", now).isEmpty())
        assertTrue(r.parseNewPools8128(null, now).isEmpty())
        val scanner = src("engine/SolanaMarketScanner.kt")
        assertFalse(scanner.contains("if (scanRaydiumApiNewPools8128()) return"))
        assertTrue(scanner.contains("scanRaydiumApiNewPools8128()  // V5.0.8163"))
        assertTrue(scanner.contains("if (scanEmergencySweepRows8128() > 0) return"))
        // The sweep stands in only while it is young, and never offers a quote mint.
        val m = com.lifecyclebot.engine.market.MarketSweep7297
        val row = com.lifecyclebot.engine.market.MarketSweep7297.Row("Swp8128Mint".padEnd(44, 'S'), "S", "S",
            1.0, 50_000.0, 10_000.0, 1_000.0, 10_000.0, 5.0, 10, 321, 50.0, false, 1.0, setOf("JUP_TRENDING"))
        val snap = com.lifecyclebot.engine.market.MarketSweep7297.Snapshot(listOf(row, row.copy(mint = sol)), emptyMap(), emptyMap(), 1_000_000L)
        assertEquals(listOf(row.mint), m.emergencyRowsOf8128(snap, 1_000_000L + 60_000L, 100).map { it.mint })
        assertTrue(m.emergencyRowsOf8128(snap, 1_000_000L + 30L * 60_000L, 100).isEmpty())
        assertTrue(m.emergencyRowsOf8128(null, 1_000_000L, 100).isEmpty())
    }

    @Test fun aShortChartIsBackfilledFromTheBotsOwnTapesWithoutInventingBars() {
        val L = com.lifecyclebot.engine.chart.LocalTapeBars8128
        val t0 = 60_000L * 1_000L
        fun prt(min: Int, sec: Int, px: Double, sol: Double, buy: Boolean) =
            com.lifecyclebot.engine.truth.TradeTape8100.Print8128(t0 + min * 60_000L + sec * 1_000L, px, sol, buy)
        val prints = (0 until 12).flatMap { m -> listOf(prt(m, 5, 1e-6 * (1 + m * 0.01), 2.0, true), prt(m, 40, 1.2e-6, 1.0, false)) }
        val bars = L.fromPrints8128(prints, 150.0)!!
        assertEquals(12, bars.size)
        val b0 = bars.first()
        assertEquals(t0, b0.t)
        assertEquals(1e-6 * 150.0, b0.o, 1e-15)
        assertEquals(1.2e-6 * 150.0, b0.h, 1e-15)
        assertEquals(1.2e-6 * 150.0, b0.c, 1e-15)
        assertEquals(3.0 * 150.0, b0.v, 1e-9)        // USD here; the backfill loop turns it back into SOL
        assertEquals(2.0 * 150.0, b0.buyV, 1e-9)
        // Nine traded minutes is not enough, and gaps are never filled to reach ten.
        assertNull(L.fromPrints8128((0 until 9).map { prt(it * 3, 1, 1e-6, 1.0, true) }, 150.0))
        assertNull(L.fromPrints8128(prints, 0.0))      // no SOL price -> no USD bars
        val marks = (0 until 10).map { com.lifecyclebot.engine.truth.TradePlan7739.Bar(t0 + it * 60_000L, 1.0, 1.1, 0.9, 1.05) }
        assertEquals(10, L.fromMarks8128(marks)!!.size)
        assertEquals(0.0, L.fromMarks8128(marks)!!.first().v, 1e-12)   // marks carry no volume; none invented
        assertNull(L.fromMarks8128(marks.take(9)))
        assertTrue(src("engine/chart/ChartSources7955.kt").contains("\"localTape8128\" to { LocalTapeBars8128.bars8128(mint, now, BACKFILL_MINUTES * 60_000L, solUsd()) }"))
    }

    @Test fun holderCountsComeFromJupiterAndAreNeverInvented() {
        val J = com.lifecyclebot.network.JupiterHolders8128
        val mint = "Hold8128Mint".padEnd(44, 'H')
        assertEquals(1234, J.parseHolders8128("""[{"id":"Other8128","holderCount":9},{"id":"$mint","holderCount":1234}]""", mint))
        assertNull(J.parseHolders8128("""[{"id":"$mint"}]""", mint))
        assertNull(J.parseHolders8128("""[{"id":"$mint","holderCount":0}]""", mint))
        assertNull(J.parseHolders8128("[]", mint))
        assertNull(J.parseHolders8128("{\"error\":1}", mint))
        // One ask per mint per 10 minutes, and the shared Jupiter host at most every 3 s.
        assertTrue(J.askDue8128(null, 0L, 10_000L))
        assertFalse(J.askDue8128(null, 9_000L, 10_000L))
        assertFalse(J.askDue8128(1_000L, 0L, 1_000L + J.REFRESH_MS_8128 - 1))
        assertTrue(J.askDue8128(1_000L, 0L, 1_000L + J.REFRESH_MS_8128))
        val m = com.lifecyclebot.engine.market.MarketSweep7297
        val row = com.lifecyclebot.engine.market.MarketSweep7297.Row(mint, "H", "H",
            1.0, 50_000.0, 10_000.0, 1_000.0, 10_000.0, 5.0, 10, 777, 50.0, false, 1.0, setOf("JUP_TRENDING"))
        assertEquals(mapOf(mint to 777), m.holderIndexOf8128(listOf(row, row.copy(mint = "NoHolders8128".padEnd(44, 'N'), holders = 0))))
        assertTrue(src("network/DexscreenerApi.kt").contains("if (chainId == \"solana\") result = result?.let { withHolders8128(it, tokenAddress) }"))
        assertTrue(src("engine/BotService.kt").contains("holderCount = com.lifecyclebot.network.JupiterHolders8128.holders8128(ts.mint) ?: 0,"))
    }

    @Test fun anUnsoldMarkNeverRaisesTheDrawdownPeakAndLiveBuysAskHeliusFirst() {
        val c = com.lifecyclebot.engine.truth.CapitalDrawdown7948
        assertEquals(0.40, c.gainFreeEquity8130(1.0777, 0.6777), 1e-9)   // 8127: SAC's phantom gain stays out of the peak
        assertEquals(0.30, c.gainFreeEquity8130(0.30, -0.05), 1e-9)      // unrealized losses stay in
        assertEquals(0.35, c.gainFreeEquity8130(0.35, Double.NaN), 1e-9)
        assertTrue(src("engine/KillSwitch.kt").contains("prefs.getBoolean(\"peak_gain_free_8130\", false)"))
        assertTrue(src("engine/truth/OnChainMintAuthorityProof7248.kt").contains("val ordered8130 = if (liveGate8094) (heliusEps7365.take(1) + publicAll7369).distinct() else endpoints"))
    }

    @Test fun aCoinThatLostOnceOrIsFallingIsNotBoughtAgain() {
        val r = RebuyLockout8019
        val now = 10_000_000_000L
        assertTrue(r.bigLoss8130(-22.43))                                     // DA's first close
        assertFalse(r.bigLoss8130(-4.0))                                      // a scratch is not a real loss
        assertTrue(r.bigLossWindow8130(now - 50 * 60_000L, now, ticket = false))
        assertFalse(r.bigLossWindow8130(now - 25L * 3_600_000L, now, ticket = false))
        assertFalse(r.bigLossWindow8130(now - 50 * 60_000L, now, ticket = true))   // a confirmed run reopens it
        assertTrue(r.fallingKnife8130(-45.0, ticket = false))                 // DA at 2:30
        assertFalse(r.fallingKnife8130(-20.0, ticket = false))
        assertFalse(r.fallingKnife8130(null, ticket = false))
        assertFalse(r.fallingKnife8130(-60.0, ticket = true))
        assertEquals(2, r.MAX_LOSSES_8022)                                    // the 8022 rule is unchanged
    }

    @Test fun anyLaneRefusingACoinAsTheWrongShapeHandsItToTheLaneThatWantsIt() {
        val o = SpecialistOwnership7951
        for (fit in listOf("TREASURY_ROLE_FLOOR_8031", "PHASE_HANDOFF_EXPANDING", "MCAP_TOO_LOW: \$599K < \$1M",
                "MCAP too low: \$2953 < \$75000", "MCAP too high for Quality (use BlueChip): \$39040191",
                "TOO_OLD: 893h > 72h", "TOKEN_TOO_OLD", "NOT_A_DIP: 12.1% < 15%", "TOO_YOUNG: 0.2h", "CORE_YIELD_CLEAR_SPECIALIST_OWNER"))
            assertTrue(fit, o.laneFitRefusal8132(fit))
        for (judgement in listOf("LIQ_TOO_LOW: \$52 < \$309", "NATIVE_THRESHOLD_FAIL_SCORE", "WEAK_MOMENTUM", "SELLABILITY_REJECT",
                "Top holder too dominant: 32% > 30%", "RUG_RISK", "MCAP_TOO_LOW_BUT_HONEYPOT", null))
            assertFalse(judgement.toString(), o.laneFitRefusal8132(judgement))
        val src = src("engine/SpecialistOwnership7951.kt")
        assertTrue(src.contains("val yielded = ops != null && laneFitRefusal8132(ops[p]?.reason)"))
        assertTrue(src.contains("if (ready && native8094 && !receiverRecordSound8094(mint, l))"))   // receiver record still guards it
    }

    // V5.0.8133 — the 8128 two-feed rule on every other high/peak writer, not just the canonical peak.
    @Test fun everyPeakWriterRefusesAOneFeedJump() {
        val t = com.lifecyclebot.engine.truth.PositionDisplayTruth8078
        assertTrue(t.highStepOk8133("m", 1.0, 1.5, 1.8, "T"))     // +50% -> +80%: ordinary step
        assertTrue(t.highStepOk8133("m", 1.0, 3.0, 5.0, "T"))     // 3x -> 5x: under double
        assertTrue(t.highStepOk8133("m", 1.0, 3.0, 2.0, "T"))     // not a new high
        assertEquals(1.8, t.acceptedHigh8133("m", 1.0, 1.5, 1.8, "T"), 1e-12)
        assertTrue(t.jumpNeedsCorroboration8128(0.0, 1888.0))       // SAC 1x -> 19.9x needs a second feed
        val writers = mapOf(
            "engine/Executor.kt" to listOf("\"EXEC_MANAGE\")", "\"EXEC_RISK\")", "\"EXEC_TICK\")"),
            "engine/ExitIntelligence.kt" to listOf("\"EXIT_INTEL\")"),
            "engine/TradeIdentity.kt" to listOf("\"TRADE_IDENTITY\")"),
            "engine/ShadowLearningEngine.kt" to listOf("\"SHADOW_BLOCKED\")"),
            "v3/scoring/ProjectSniperAI.kt" to listOf("\"PROJECT_SNIPER\")"),
            "engine/truth/LaneShadowProof7307.kt" to listOf("\"LANE_SHADOW\")"),
            "engine/truth/ForwardReturnLabeler7731.kt" to listOf("\"FORWARD_LABEL\")) continue"),
            "engine/truth/TailHunter7996.kt" to listOf("\"TAIL_HUNTER\")"),
            "engine/market/TelegramCallSweeper7830.kt" to listOf("\"CALL_SWEEP\")"),
            "engine/BotService.kt" to listOf("\"UNIVERSAL_SWEEP\")"),
        )
        for ((f, tags) in writers) for (tag in tags) assertTrue("$f $tag", src(f).contains(tag))
        val ex = src("engine/Executor.kt")
        assertFalse(ex.contains("ts.position.highestPrice = maxOf(ts.position.highestPrice, currentPrice)"))
        assertFalse(ex.contains("pos.highestPrice = maxOf(pos.highestPrice, price)"))
        assertFalse(ex.contains("ts.position.highestPrice = maxOf(ts.position.highestPrice, _unconditionalPrice)"))
    }

    // V5.0.8134 — the rebuy / loser / falling-knife lockout sits at the meme buy door, so a chart BUY or any other
    // upstream admit cannot re-buy a coin that just lost or is dumping.
    @Test fun rebuyLockoutGuardsTheBuyDoorNotOnlyTheEdgeGate() {
        val door = src("engine/PreTradeHardGate.kt")
        assertTrue(door.contains("(try { RebuyLockout8019.refusal8019(mint, now) } catch (_: Throwable) { null })?.let { return block(ts, it, \"site=\$callSite\") }"))
        assertTrue(door.indexOf("RebuyLockout8019.refusal8019") < door.indexOf("FreezeAuthorityHardBlock7238.evaluateLive"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("com.lifecyclebot.engine.RebuyLockout8019.refusal8019(ts.mint, nowMs)?.let { return it }"))
    }
}
