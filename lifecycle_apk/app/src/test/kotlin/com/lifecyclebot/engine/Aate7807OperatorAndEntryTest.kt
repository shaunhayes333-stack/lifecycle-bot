package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450
import com.lifecyclebot.engine.truth.OpenPositionPanel7807
import com.lifecyclebot.engine.truth.OracleEdgeProof7263
import com.lifecyclebot.engine.truth.ProviderEvidence7807
import com.lifecyclebot.engine.truth.SlowCycleDiagnostic6437
import java.io.File
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7807 — operator panel, hot-loop latency, provider evidence, entry validity, learning integrity. */
class Aate7807OperatorAndEntryTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun canon(mint: String, lifecycle: CanonicalPositionAuthority6441.Lifecycle, qty: Long) =
        CanonicalPositionAuthority6441.Position(
            positionId = "pid-$mint-${lifecycle.name}",
            mode = "live",
            mint = mint,
            symbol = "SYM",
            lane = "MOONSHOT",
            runId = "run",
            openedAtMs = 1L,
            entryCostSol = 0.1,
            remainingQtyRaw = BigInteger.valueOf(qty),
            originalQtyRaw = BigInteger.valueOf(qty),
            soldCostBasisSol = 0.0,
            realizedPnlSol = 0.0,
            realizedProceedsSol = 0.0,
            feesSol = 0.0,
            tokenDecimals = 6,
            lifecycle = lifecycle,
            lastMutationMs = 1L,
            quarantineReason = if (lifecycle == CanonicalPositionAuthority6441.Lifecycle.QUARANTINED) "BASIS_UNCERTAIN_7807" else "",
        )

    // ── UI 32-39 ────────────────────────────────────────────────────────────

    @Test fun basisStateIsNamedForEveryManagedRow() {
        val p = OpenPositionPanel7807
        assertEquals(OpenPositionPanel7807.BasisState7807.QUARANTINED_ACCOUNTING, p.classifyBasis7807(true, false, true, 1.0, 0.1))
        assertEquals(OpenPositionPanel7807.BasisState7807.BASIS_UNCERTAIN, p.classifyBasis7807(false, false, false, 1.0, 0.1))
        assertEquals(OpenPositionPanel7807.BasisState7807.BASIS_UNCERTAIN, p.classifyBasis7807(false, true, true, 0.0, 0.1))
        assertEquals(OpenPositionPanel7807.BasisState7807.RECOVERED, p.classifyBasis7807(false, true, true, 1.0, 0.1))
        assertEquals(OpenPositionPanel7807.BasisState7807.VERIFIED_BASIS, p.classifyBasis7807(false, false, true, 1.0, 0.1))
        assertEquals("QUARANTINED-ACCOUNTING · STILL PROTECTED", OpenPositionPanel7807.BasisState7807.QUARANTINED_ACCOUNTING.label)
        assertTrue(p.isRecoveredTag7807("", "WALLET_RECOVERY_X"))
        assertFalse(p.isRecoveredTag7807("BOT_BUY", "DEXSCREENER"))
    }

    @Test fun markFreshnessAndManagementStateAreHonest() {
        val p = OpenPositionPanel7807
        assertEquals("mark n/a", p.markFreshness7807(100_000L, 0L))
        assertEquals("mark 3s", p.markFreshness7807(100_000L, 97_000L))
        assertEquals("mark STALE 90s", p.markFreshness7807(100_000L, 10_000L))
        assertEquals("VERIFYING FILL", p.managementState7807(true, 1L, 2L))
        assertEquals("SETTLING", p.managementState7807(false, 90_000L, 100_000L))
        assertEquals("MANAGED", p.managementState7807(false, 1L, 100_000L))
        assertTrue(p.statusLine7807("moonshot", OpenPositionPanel7807.BasisState7807.VERIFIED_BASIS, "MANAGED")
            .startsWith("MOONSHOT · VERIFIED BASIS"))
    }

    @Test fun oneMintIsOneBagAndShownMintsAreNotRepeated() {
        val rows = listOf(
            canon("MINTA", CanonicalPositionAuthority6441.Lifecycle.QUARANTINED, 5_000),
            canon("MINTA", CanonicalPositionAuthority6441.Lifecycle.OPEN, 1_000),
            canon("MINTB", CanonicalPositionAuthority6441.Lifecycle.QUARANTINED, 9_000),
            canon("MINTC", CanonicalPositionAuthority6441.Lifecycle.OPEN, 9_000),
        )
        val picked = OpenPositionPanel7807.pickOnePerMint7807(rows, setOf("MINTC"))
        assertEquals(2, picked.size)
        assertEquals(CanonicalPositionAuthority6441.Lifecycle.OPEN, picked.first { it.mint == "MINTA" }.lifecycle)
        assertTrue(picked.any { it.mint == "MINTB" })
        assertFalse(picked.any { it.mint == "MINTC" })
    }

    @Test fun mainPanelShowsProtectiveInventoryWithoutHidingFooter() {
        val ui = src("ui/MainActivity.kt")
        assertTrue(ui.contains("OPENPOS_ROW_CAP: Int = 20"))
        assertTrue(ui.contains("OpenPositionPanel7807.protectiveRowsNotShown7807("))
        assertTrue(ui.contains("val canonical7253 = ts.mint in protectiveMints7807 ||"))
        assertTrue(ui.contains("openPanelFooter7807(positions, laneHeld)"))
        assertFalse(ui.contains("\"Showing \${positions.size}; managed total"))
        assertTrue(ui.contains("cached.markTv7807?.text = markLine7807"))
        assertTrue(ui.contains("basis7807.ordinal * 101 +"))
    }

    // ── Loop perf 40-44 ─────────────────────────────────────────────────────

    @Test fun heldMarkLoopNoLongerRunsTheRescueChainInline() {
        val bot = src("engine/BotService.kt")
        val a = bot.indexOf("private suspend fun openPositionTickLoop(gen7283: Long)")
        val b = bot.indexOf("V5.9.495z54c — extracted from botLoop()", a)
        assertTrue(a > 0 && b > a)
        val loop = bot.substring(a, b)
        assertTrue(loop.contains("applyMarkRescue7807(openMints, solanaMints6970, missingBeforeKeyless6946Raw, priceMap, markSource6999)"))
        assertFalse(loop.contains("ParallelMarkFanout7088"))
        assertFalse(loop.contains("KeylessPriceSources6996"))
        assertFalse(loop.contains("PriceResolverFallback.resolve("))
        assertFalse(loop.contains("getTokenPriceEmergency"))
        // The chain still exists, verbatim, on its own worker.
        assertTrue(bot.contains("private fun runMarkRescue7807("))
        assertTrue(bot.contains("MARK_KEYLESS_CHAIN_BUDGET_DEFERRED_7283"))
        assertTrue(bot.contains("MARK_CONTESTED_NOT_APPLIED_7273"))
        assertTrue(bot.contains("scope.launch(markRescueDispatcher7807 + CoroutineName(\"mark-rescue-7807\"))"))
        // Held-position loops no longer share Dispatchers.IO with intake.
        assertTrue(bot.contains("scope.launch(hotPathDispatcher7807 + CoroutineName(\"open-mark-6647\"))"))
        assertTrue(bot.contains("scope.launch(hotPathDispatcher7807 + CoroutineName(\"rapid-stop-6647\"))"))
        assertTrue(bot.contains("scope.launch(hotPathDispatcher7807 + CoroutineName(\"open-mark-7283\"))"))
    }

    @Test fun scannerCallbacksNoLongerOwnTheBotCyclePhase() {
        val bot = src("engine/BotService.kt")
        assertTrue(bot.contains("try { markScannerProgress7807(\"INTAKE\") } catch (_: Throwable) {}"))
        assertTrue(bot.contains("try { markScannerProgress7807(\"SCAN_CB\") } catch (_: Throwable) {}"))
        assertFalse(bot.contains("try { markProgress(\"INTAKE\") }"))
        // No cycle open on the test thread: an off-cycle beacon cannot rename the phase.
        assertFalse(SlowCycleDiagnostic6437.isCycleThread7807())
    }

    // ── Providers 47-51 ─────────────────────────────────────────────────────

    @Test fun providerFailureIsNeverBearishEvidence() {
        val e = ProviderEvidence7807
        assertEquals(ProviderEvidence7807.Evidence7807.PROVIDER_FAILURE, e.classify7807(0.0, false, 0L, 60_000L))
        assertEquals(ProviderEvidence7807.Evidence7807.DATA_UNKNOWN, e.classify7807(0.0, true, 0L, 60_000L))
        assertEquals(ProviderEvidence7807.Evidence7807.DATA_UNKNOWN, e.classify7807(Double.NaN, true, 0L, 60_000L))
        assertEquals(ProviderEvidence7807.Evidence7807.STALE_DATA, e.classify7807(5_000.0, true, 120_000L, 60_000L))
        assertEquals(ProviderEvidence7807.Evidence7807.NEGATIVE_MARKET_EVIDENCE, e.classify7807(0.0, true, 0L, 60_000L, confirmedZero = true))
        assertEquals(ProviderEvidence7807.Evidence7807.OBSERVED, e.classify7807(5_000.0, true, 0L, 60_000L))
        // Unknown liquidity names the missing dependency, stays non-trainable hard safety.
        val unknown = e.zeroLiquidityBlockReason7807("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP")
        assertTrue(unknown.contains("WAIT_MISSING_EVIDENCE_7807:EXITABILITY_LIQUIDITY_UNKNOWN"))
        assertEquals("HARD_BLOCK_ZERO_LIQUIDITY", e.zeroLiquidityBlockReason7807("TRUE_ZERO_LIQUIDITY"))
        val tax = RejectTaxonomy.classify(unknown)
        assertEquals(RejectTaxonomy.Category.HARD_SAFETY, tax.category)
        assertFalse(tax.trainable)
    }

    @Test fun unknownLiquidityNeverReadsAsCollapseOnAnOpenPosition() {
        val mint = "LIQ7807TESTMINT000000000000000000001"
        assertFalse(LiquidityDepthAI.isLiquidityEvidence7807(0.0))
        assertFalse(LiquidityDepthAI.isLiquidityEvidence7807(Double.NaN))
        assertTrue(LiquidityDepthAI.isLiquidityEvidence7807(12_000.0))
        LiquidityDepthAI.recordEntryLiquidity(mint, 50_000.0)
        // A failed read (0.0) is not recorded, so the open position has no current reading.
        LiquidityDepthAI.recordSnapshot(mint, 0.0)
        val sig = LiquidityDepthAI.getSignal(mint, "T", isOpenPosition = true)
        assertNotEquals(LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE, sig.signal)
        assertFalse(sig.shouldBlock)
        LiquidityDepthAI.clearEntryLiquidity(mint)
        assertTrue(src("engine/DataOrchestrator.kt").contains("if (LiquidityDepthAI.isLiquidityEvidence7807(liquidity)) ts.lastLiquidityUsd = liquidity"))
    }

    // ── Entry late failures 52-55 ───────────────────────────────────────────

    @Test fun sniperLaunchIdentityIsOneSharedPredicate() {
        val ts = TokenState(mint = "SNIPER7807MINT")
        ts.lastMcap = 2_000_000.0
        assertEquals("MCAP_ABOVE_LAUNCH_CAP", LaneEntryContract6342.sniperLaunchIdentityRefusal7807(ts))
        ts.tokenMap.migratedOrGraduated = true
        assertEquals("GRADUATED", LaneEntryContract6342.sniperLaunchIdentityRefusal7807(ts, mcapUsd = 10_000.0))
        assertFalse(LaneEntryContract6342.isSniperLaunch7393(ts))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("LaneEntryContract6342.sniperLaunchIdentityRefusal7807(ts, mcap7385)"))
        val lec = src("engine/LaneExecutionCoordinator.kt")
        assertTrue(lec.contains("ownExecutorRefusal7807(mint, laneUpper)?.let"))
        assertTrue(lec.contains(".filter { ownExecutorRefusal7807(mint, it) == null }"))
        assertTrue(lec.contains("LaneEntryContract6342.sniperLaunchIdentityRefusal7807(ts)"))
    }

    @Test fun poorRiskRewardIsAskedBeforeTheTicket() {
        val ex = src("engine/Executor.kt")
        val lrp = ex.indexOf("if (liveRiskPolicyPreTicketRefused7807(ts, layerTag, sol, walletSol)) return false")
        val rr = ex.indexOf("if (commonSenseRiskRewardPreTicketRefused7807(ts, layerTag, score, sol)) return false")
        assertTrue(lrp > 0 && rr > lrp)
        // Same thresholds: the late check is still there.
        assertTrue(ex.contains("CommonSenseTradePlaybook.assessPreBuy("))
        val cs = src("engine/CommonSenseTradePlaybook.kt")
        assertTrue(cs.contains("fun preTicketRiskRewardPoor7807("))
        assertTrue(cs.contains("if (rr >= PLAN_MIN_RR_7783) return false"))
    }

    // ── Learning integrity 56-61 ────────────────────────────────────────────

    @Test fun learnersReadCleanTerminalTruthOnly() {
        assertNull(CanonicalTradeFinalizedBus6450.learningUncleanReason7807("no-such-position-7807", ""))
        assertTrue(src("engine/truth/OracleEdgeProof7263.kt").contains("CanonicalTradeFinalizedBus6450.isCleanForLearning7807(event)"))
        assertTrue(src("engine/truth/SignalSourceProof7291.kt").contains("CanonicalTradeFinalizedBus6450.isCleanForLearning7807(event)"))
        assertTrue(src("engine/truth/ExecutableEntryAuthority6450.kt").contains("CanonicalTradeFinalizedBus6450.isCleanForLearning7807(e)"))
        assertTrue(src("engine/market/LaneHunter7297.kt").contains("CanonicalTradeFinalizedBus6450.isCleanForLearning7807(e)"))
        val bus = src("engine/truth/CanonicalTradeFinalizedBus6450.kt")
        assertTrue(bus.contains("\"QUARANTINED_ACCOUNTING_7807\""))
        assertTrue(bus.contains("val inferredBasis7722: String? = learningUncleanReason7807(event.positionId, event.mint)"))
    }

    @Test fun oracleNeedsLiveProofToGuideLive() {
        val o = OracleEdgeProof7263
        assertFalse(o.provenFrom7807(0L, 0.5, 0L, 0.0, 0.1))
        assertFalse(o.provenFrom7807(25L, 0.10, 12L, 0.09, 0.1)) // margin < 2pp
        assertTrue(o.provenFrom7807(25L, 0.10, 12L, 0.05, 0.2))
        assertFalse(o.provenFrom7807(25L, 0.10, 12L, 0.05, 0.3)) // Brier
        val s = src("engine/truth/OracleEdgeProof7263.kt")
        assertTrue(s.contains("fun tier(): Tier = if (try { com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }) tier else liveTier7807"))
        assertTrue(s.contains("admitLive7807.clear7535()"))
        assertNotNull(o.allModesTier7807())
    }
}
