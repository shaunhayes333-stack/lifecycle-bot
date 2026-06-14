package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.9.1562 — Golden-tape blocker taxonomy harness.
 *
 * This is the first CI regression net for the exact choke/unchoke oscillation
 * the operator has been fighting:
 *   UNKNOWN/PENDING          => penalty / size reduction, never blacklist
 *   LOW BUT EXITABLE         => reduced-size preflight, not hard block
 *   CONFIRMED FATAL / NO EXIT=> hard block
 *   UNPROFITABLE AFTER COSTS => cost reject, not safety blacklist
 *
 * The tape is deliberately tiny and pure-JVM. It is not trying to model all
 * markets yet; it pins the behavioral contract so future leaf patches cannot
 * silently collapse soft states back into WATCHLIST_PROTECT_BLACKLISTED_TOKEN.
 */
class GoldenTapeRegressionTest {

    private val softMint = "SoftPendingMint111111111111111111111111111111"
    private val trueMint = "TrueFatalMint1111111111111111111111111111111"

    @After
    fun cleanup() {
        TokenBlacklist.clear()
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = false)
    }

    private fun token(
        symbol: String,
        liq: Double,
        score: Double,
        phase: String = "MOMENTUM",
        tp: Double = 25.0,
        safety: SafetyReport = SafetyReport(tier = SafetyTier.CAUTION),
    ): TokenState {
        return TokenState(
            mint = symbol.padEnd(36, 'A'),
            symbol = symbol,
        ).also { ts ->
            ts.lastLiquidityUsd = liq
            ts.entryScore = score
            ts.phase = phase
            ts.safety = safety
            ts.position = Position(
                treasuryTakeProfit = tp,
                blueChipTakeProfit = tp,
                shitCoinTakeProfit = tp,
                isPaperPosition = false,
            )
        }
    }

    @Test
    fun golden_tape_has_distinct_intake_phases() {
        val phases = LiveTradeLogStore.Phase.values().map { it.name }.toSet()
        assertTrue(phases.contains("INTAKE_RISK_PENALTY"))
        assertTrue(phases.contains("INTAKE_SIZE_REDUCED"))
        assertTrue(phases.contains("INTAKE_PENDING_RUGCHECK"))
        assertTrue(phases.contains("INTAKE_TRUE_HARD_BLOCK"))
        assertTrue(phases.contains("INTAKE_COST_REJECT"))
    }

    @Test
    fun legacy_false_blacklist_reasons_self_rehabilitate() {
        val falseReasons = listOf(
            "Safety: Rugcheck pending — live mode, no high-score override",
            "Safety: Rugcheck API timeout (live: PENDING_REVIEW)",
            "Safety: SAFETY_RUN_FAILED_PARTIAL_DATA: timeout",
            "Safety: LOW_LIQUIDITY: \$900 < \$1200",
            "Safety: Liquidity \$900 < \$1,200 live exit-safety floor — un-exitable",
        )

        for ((i, reason) in falseReasons.withIndex()) {
            val mint = softMint + i
            TokenBlacklist.block(mint, reason)
            assertFalse("false safety blacklist must rehabilitate: $reason", TokenBlacklist.isBlocked(mint))
        }
    }

    @Test
    fun true_blacklist_reasons_remain_blocked() {
        TokenBlacklist.block(trueMint, "Known malicious dev / verified blacklist")
        assertTrue(TokenBlacklist.isBlocked(trueMint))

        TokenBlacklist.block(trueMint + "B", "Honeypot / cannot sell / sell simulation fails")
        assertTrue(TokenBlacklist.isBlocked(trueMint + "B"))
    }

    @Test
    fun rugcheck_pending_caution_is_not_hard_blocked_by_live_admission_boundary() {
        val pending = SafetyReport(
            tier = SafetyTier.CAUTION,
            hardBlockReasons = emptyList(),
            softPenalties = listOf(
                "Rugcheck pending (live risk penalty, no hard block)" to 12,
                "RUGCHECK_UNKNOWN_MAX_SIZE_MULT=0.35" to 0,
            ),
            entryScorePenalty = 12,
            rugcheckStatus = "PENDING_REVIEW",
            checkedAt = 1_700_000_000_000L,
        )

        assertFalse("pending Rugcheck must not be SafetyReport.isBlocked", pending.isBlocked)
        assertTrue(pending.hardBlockReasons.isEmpty())
        assertEquals(SafetyTier.CAUTION, pending.tier)
    }

    @Test
    fun low_but_exitable_liquidity_reduces_size_and_can_pass_cost_preflight() {
        val ts = token(symbol = "LOWLIQ", liq = 900.0, score = 90.0, tp = 45.0)
        val penalty = LiveRestoreExecutionPolicy.Penalty(
            scorePenalty = -10,
            sizeMultiplier = 0.35,
            reason = "LOW_LIQUIDITY_SIZE_REDUCED",
            liquidityOverrideUsd = 900.0,
        )

        val be = LiveRestoreExecutionPolicy.breakEvenCheck(
            ts = ts,
            requestedSizeSol = 0.05,
            penalty = penalty,
            walletSol = 1.0,
        )

        assertTrue("low-but-exitable liquidity should pass as reduced size; got ${be.decision}", be.allowed)
        assertTrue("size should be reduced", be.sizeSol < 0.05)
        assertTrue("all-in cost should include slippage/fees/giveback", be.allInCostPct > 10.0)
    }

    @Test
    fun dust_no_exit_depth_hard_rejects_as_route_failure_not_blacklist() {
        val ts = token(symbol = "DUST", liq = 80.0, score = 95.0, tp = 80.0)
        val be = LiveRestoreExecutionPolicy.breakEvenCheck(
            ts = ts,
            requestedSizeSol = 0.02,
            penalty = LiveRestoreExecutionPolicy.NONE,
            walletSol = 1.0,
        )

        assertFalse(be.allowed)
        assertEquals("NO_VALID_SELL_ROUTE", be.decision)
    }

    @Test
    fun weak_edge_rejects_as_not_profitable_after_costs() {
        val ts = token(symbol = "NOEDGE", liq = 900.0, score = 15.0, phase = "IDLE", tp = 0.0)
        val penalty = LiveRestoreExecutionPolicy.Penalty(
            scorePenalty = -10,
            sizeMultiplier = 0.35,
            reason = "LOW_LIQUIDITY_SIZE_REDUCED",
            liquidityOverrideUsd = 900.0,
        )
        val be = LiveRestoreExecutionPolicy.breakEvenCheck(
            ts = ts,
            requestedSizeSol = 0.05,
            penalty = penalty,
            walletSol = 1.0,
        )

        assertFalse(be.allowed)
        assertEquals("NOT_PROFITABLE_AFTER_COSTS", be.decision)
    }

    @Test
    fun live_runtime_canonical_open_uses_wallet_truth_not_stale_local_max() {
        // This pins the V5.9.1561 doctrine in source text because the full runtime
        // snapshot depends on Android/global services. If this text disappears, the
        // behavioral contract was likely reverted and the test should fail loudly.
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        assertTrue(source.contains("canonical LIVE truth is wallet-held balance"))
        assertTrue(source.contains("walletHeld"))
    }

    @Test
    fun paper_fdg_circuit_blocks_soft_allow_instead_of_hard_veto() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(source.contains("PAPER_CIRCUIT_SOFT_ALLOW"))
        assertTrue(source.contains("circuitPaperMode && globalPause?.active != true"))
    }

    @Test
    fun forced_open_reaper_evicts_all_subtrader_stores() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("CashGenerationAI.evictGhost"))
        assertTrue(bot.contains("MoonshotTraderAI.evictGhost"))
        assertTrue(bot.contains("ShitCoinTraderAI.evictGhost"))
        assertTrue(bot.contains("QualityTraderAI.evictGhost"))
        assertTrue(bot.contains("ManipulatedTraderAI.evictGhost"))
    }

    @Test
    fun paper_model_rug_fatal_does_not_early_return_before_subtraders() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("V3_PAPER_MODEL_RUG_FATAL_SOFTENED"))
        assertTrue(bot.contains("paperModelRugFatal"))
    }

    @Test
    fun executable_open_gate_bypasses_learnable_paper_v3_fatals_all_lanes() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("paperLearnableV3Fatal"))
        assertFalse("RC_PENDING bypass must not be CYCLIC-only", gate.contains("requestedLane == \"CYCLIC\" && rug == 1"))
        assertTrue(gate.contains("PAPER_API_BUDGET_LOCKDOWN_BYPASSED"))
    }

    @Test
    fun paper_slot_health_forced_open_fail_open() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        assertTrue(source.contains("PAPER_FORCED_OPEN_FAIL_OPEN"))
        assertTrue(source.contains("RuntimeModeAuthority.isPaper()"))
    }

    @Test
    fun express_records_fdg_before_authorizer_finality() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val recordIdx = source.indexOf("V5.9.1570 — Express FDG verdict")
        val authIdx = source.indexOf("TradeAuthorizer.authorize", recordIdx)
        assertTrue(recordIdx >= 0)
        assertTrue(authIdx > recordIdx)
        assertTrue(source.substring(recordIdx, authIdx).contains("ExecutableOpenGate.recordFdg"))
    }

    @Test
    fun wr_recovery_tuning_uses_learned_bucket_multiplier() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("learnedBucketMult"))
        assertTrue(fdg.contains("LosingPatternMemory.recommendedSizeMult"))
        assertTrue(fdg.contains("minOf(genericPressure, learnedBucketMult)"))
    }

    @Test
    fun wr_recovery_tuning_tightens_shitcoin_never_green_and_express_floor() {
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        val exp = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        assertTrue(shit.contains("ageSec >= 30L"))
        assertTrue(shit.contains("pnlPct < -3.5"))
        assertTrue(exp.contains("EXPRESS_SCORE_BOOTSTRAP = 10"))
        assertTrue(exp.contains("coerceIn(0.01, MAX_POSITION_SOL)"))
    }

    @Test
    fun forced_open_supervisor_is_bounded_under_timeout_pressure() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("forcedOpenForSupervisor"))
        assertTrue(bot.contains("FORCED_OPEN_SUPERVISOR_ROUND_ROBIN"))
        assertTrue(bot.contains("forcedSupervisor="))
        assertFalse("forcedOpen must not remain an unbounded mandatory supervisor prefix", bot.contains("val mustInclude = forcedOpenMints.toMutableList()"))
    }

    @Test
    fun open_mint_supervisor_timeouts_cooldown_without_touching_exits() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("open mints no longer bypass supervisor timeout cooldown"))
        assertTrue(bot.contains("val cooldownMs = if (open) 20_000L else SUPERVISOR_TIMEOUT_COOLDOWN_MS"))
        assertFalse("open mints must not bypass timeout cooldown and monopolise supervisor", bot.contains("if (supervisorMintIsOpen(mint)) return false"))
    }

    @Test
    fun drawdown_circuit_reads_canonical_journal_truth() {
        val dd = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/DrawdownCircuitAI.kt").readText()
        assertTrue(dd.contains("TradeHistoryStore.getAllSells"))
        assertTrue(dd.contains("minOf(balanceAgg, journalAgg)"))
        assertTrue(dd.contains("diagnosticLine"))
        assertTrue(dd.contains("lossStreak"))
        assertTrue(dd.contains("profitFactor"))
    }

    @Test
    fun sentient_diagnostic_does_not_call_drawdown_normal_without_journal_context() {
        val sent = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SentientPersonality.kt").readText()
        assertTrue(sent.contains("DrawdownCircuitAI.diagnosticLine"))
        assertTrue(sent.contains("DRAWDOWN CIRCUIT: 🛡️ DEFENSIVE"))
        assertTrue(sent.contains("Trust map is partially blind during defensive drawdown"))
    }

    @Test
    fun strategy_trust_is_damped_by_drawdown_circuit_softly() {
        val trust = java.io.File("src/main/kotlin/com/lifecyclebot/v4/meta/StrategyTrustAI.kt").readText()
        assertTrue(trust.contains("V5.9.1573"))
        assertTrue(trust.contains("DrawdownCircuitAI.getAggression"))
        assertTrue(trust.contains("base * symFactor * ddFactor"))
        assertTrue(trust.contains("coerceIn(0.15, 1.25)"))
    }

    @Test
    fun express_execution_uses_fdg_final_size() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("val expressFinalSize = (expressFdg?.sizeSol ?: expressSignal.positionSizeSol)"))
        assertTrue(bot.contains("sizeSol = expressFinalSize"))
        assertTrue(bot.contains("entrySol = expressFinalSize"))
        val start = bot.indexOf("val expressFinalSize")
        val end = bot.indexOf("addLog(\"💩🚂 EXPRESS:", start)
        assertTrue(start >= 0 && end > start)
        val executionBlock = bot.substring(start, end)
        assertFalse("Express must not execute/board using raw signal size after FDG", executionBlock.contains("sizeSol = expressSignal.positionSizeSol"))
        assertFalse("Express must not board using raw signal size after FDG", executionBlock.contains("entrySol = expressSignal.positionSizeSol"))
    }

    @Test
    fun express_is_drawdown_sized_before_cap() {
        val exp = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        assertTrue(exp.contains("V5.9.1574"))
        assertTrue(exp.contains("DrawdownCircuitAI.getAggression"))
        assertTrue(exp.contains("EXPRESS_DRAWDOWN_SIZE"))
        assertTrue(exp.contains("positionSol = positionSol.coerceIn(0.01, MAX_POSITION_SOL)"))
    }


    @Test
    fun agentic_style_router_expands_trade_styles() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        assertTrue(router.contains("MICRO_SNIPE"))
        assertTrue(router.contains("BREAKOUT_RUNNER"))
        assertTrue(router.contains("SWING_HOLD"))
        assertTrue(router.contains("PULLBACK_RECLAIM"))
        assertTrue(router.contains("WHALE_FOLLOW"))
        assertTrue(router.contains("TacticSwitcher.currentTactic"))
    }

    @Test
    fun character_route_uses_agentic_style_fanout() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("AgenticStyleRouter.decide"))
        assertTrue(bot.contains("AGENTIC_STYLE_ROUTE"))
        assertTrue(bot.contains("AgenticStyleRouter.lanesFor"))
        assertTrue(bot.contains("AgenticStyleRouter.toolsFor"))
    }

    @Test
    fun moonshot_uses_agentic_style_and_final_effective_size() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("AGENTIC_STYLE_APPLIED"))
        assertTrue(bot.contains("val msEffectiveSize = (moonshotFdgDecision?.sizeSol ?: legacyMoonshotSize)"))
        assertTrue(bot.contains("entrySol = msEffectiveSize"))
        assertTrue(bot.contains("raw="))
        val start = bot.indexOf("val msEffectiveSize")
        val end = bot.indexOf("MOONSHOT BUY", start)
        assertTrue(start >= 0 && end > start)
        val executionBlock = bot.substring(start, end)
        assertFalse("Moonshot must not register raw size after final effective size", executionBlock.contains("entrySol = moonshotScore.suggestedSizeSol"))
    }


    @Test
    fun agentic_style_router_is_bounded_not_union_fanout() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        assertTrue(router.contains("bounded style fanout"))
        assertTrue(router.contains("stablePick"))
        assertTrue(router.contains("return boundedLanes"))
        assertFalse("1575 regression: style routing must not union every style lane onto every token", router.contains("return (base + d.lanes)"))
    }

    @Test
    fun source_birth_affinity_is_seed_not_full_toolbox() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("source-only birth affinity must be a seed"))
        assertFalse("Pump source must not birth every meme lane before style classification", bot.contains("SHITCOIN\", \"MOONSHOT\", \"MANIPULATED\", \"PROJECT_SNIPER"))
        assertFalse("Raydium source must not birth every meme lane before style classification", bot.contains("MOONSHOT\", \"SHITCOIN\", \"MANIPULATED\", \"DIP_HUNTER"))
    }

    @Test
    fun express_board_ride_does_not_duplicate_buy_journal() {
        val exp = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        assertTrue(exp.contains("EXPRESS_BOARD_STATE_ONLY"))
        val start = exp.indexOf("fun boardRide")
        val end = exp.indexOf("fun checkExit", start)
        assertTrue(start >= 0 && end > start)
        val board = exp.substring(start, end)
        assertFalse("boardRide must not write a duplicate BUY row after executor.shitCoinBuy", board.contains("V3JournalRecorder.recordOpen"))
        assertFalse("boardRide must not write directly to TradeHistoryStore", board.contains("TradeHistoryStore.recordTrade"))
    }


    @Test
    fun live_exposure_pct_is_not_v3_eligibility_veto() {
        val elig = java.io.File("src/main/kotlin/com/lifecyclebot/v3/eligibility/EligibilityGate.kt").readText()
        assertTrue(elig.contains("exposure PCT is a sizing/risk signal, not an"))
        assertTrue(elig.contains("return openMints.size >= maxOpenPositions"))
        assertFalse("Global exposure percentage must not terminally block V3 eligibility", elig.contains("currentExposurePct >= maxExposurePct"))
        val adapter = java.io.File("src/main/kotlin/com/lifecyclebot/v3/bridge/V3Adapter.kt").readText()
        assertTrue(adapter.contains("val fraction = if (exposurePct > 1.0) exposurePct / 100.0 else exposurePct"))
        assertTrue(adapter.contains("coerceIn(0.0, 1.0)"))
    }

    @Test
    fun live_pending_rc_one_is_not_hard_finality_block() {
        val openGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(openGate.contains("score 1 is RC_PENDING"))
        assertTrue(openGate.contains("rugScore in 2..10"))
        assertTrue(openGate.contains("rug in 2..10"))
        assertFalse("RC_SCORE_1 must not be reintroduced as a live hardNo in recordFdg", openGate.contains("rugScore in 1..10"))
        assertFalse("RC_SCORE_1 must not be reintroduced as a live hardNo in final open", openGate.contains("rug in 1..10"))
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(moon.contains("RC=1 is PENDING"))
        assertTrue(moon.contains("val pendingRc = rugcheckScore == 1"))
    }

    @Test
    fun live_mode_freeze_is_soft_allow_not_terminal_fdg_veto() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("LIVE_CIRCUIT_SOFT_ALLOW"))
        assertTrue(fdg.contains("liveLocalModeFreeze"))
        val softIdx = fdg.indexOf("LIVE_CIRCUIT_SOFT_ALLOW")
        val blockIdx = fdg.indexOf("return FinalDecision", softIdx)
        assertTrue("live local mode freeze should be handled before the hard return path", softIdx >= 0 && blockIdx > softIdx)
    }


    @Test
    fun fanout_suppression_never_suppresses_standard_v3_or_core() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("fanout collapse must NEVER suppress Standard V3/Core"))
        assertTrue(bot.contains("l == \"STANDARD\" || l == \"CORE\" || l == \"V3\""))
        val v3ExecuteIdx = bot.indexOf("V3 EXECUTE: Clean logging + trade execution")
        assertTrue("Standard V3 execute path must remain present", v3ExecuteIdx > 0)
        val v3Area = bot.substring(v3ExecuteIdx, kotlin.math.min(bot.length, v3ExecuteIdx + 2500))
        assertFalse("Standard V3/Core path must not call specialist fanout suppression", v3Area.contains("shouldRunBuyLaneForCycle"))
    }


    @Test
    fun approved_live_handoff_survives_candidate_version_churn() {
        val openGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(openGate.contains("LIVE_RESTORE_STALE_CANDIDATE_SOFT_ALLOW"))
        assertTrue(openGate.contains("EXEC_GATE_ALLOW>0 but EXEC_LIVE_ATTEMPT=0"))
        assertTrue(openGate.contains("latestAllows && safetyOk && effectiveLiq >= 1200.0"))
    }

    @Test
    fun rc_pending_live_must_not_be_v3_block_fatal() {
        val fatal = java.io.File("src/main/kotlin/com/lifecyclebot/v3/risk/FatalRiskChecker.kt").readText()
        assertTrue(fatal.contains("score=1 is RC_PENDING sentinel"))
        assertFalse("FatalRiskChecker must not emit the stale live RC pending fatal", fatal.contains("EXTREME_RUG_CRITICAL_score=1_RC_PENDING_LIVE"))
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("BotService needs downstream fuse for stale deployed/cached V3 fatal strings", bot.contains("V3_LIVE_RC_PENDING_FATAL_SOFTENED"))
    }

    @Test
    fun operator_reports_route_through_reporting_hub_not_scattered_ui_threads() {
        val hub = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        assertTrue(hub.contains("object ReportingHub"))
        assertTrue(hub.contains("UNIFIED_HEALTH"))
        assertTrue(hub.contains("Trade Journal: EXCLUDED by design"))
        assertTrue(hub.contains("buildMutex"))
        val pipelineUi = java.io.File("src/main/kotlin/com/lifecyclebot/ui/PipelineHealthActivity.kt").readText()
        val errorUi = java.io.File("src/main/kotlin/com/lifecyclebot/ui/ErrorLogActivity.kt").readText()
        val forensicUi = java.io.File("src/main/kotlin/com/lifecyclebot/ui/LiveTradeLogActivity.kt").readText()
        val pipelineCopy = pipelineUi.substring(pipelineUi.indexOf("private fun copyToClipboardAsync"))
        val errorExport = errorUi.substring(errorUi.indexOf("private fun exportLogs"), errorUi.indexOf("private fun confirmClear"))
        val forensicClick = forensicUi.substring(forensicUi.indexOf("setOnClickListener"), forensicUi.indexOf("header.addView"))
        assertTrue(pipelineCopy.contains("ReportingHub.Kind.UNIFIED_HEALTH"))
        assertTrue(errorExport.contains("ReportingHub.Kind.UNIFIED_HEALTH"))
        assertTrue(forensicClick.contains("ReportingHub.exportForensicFileAsync"))
        assertFalse("Pipeline copy must not directly build the massive dump", pipelineCopy.contains("PipelineHealthCollector.dumpText()"))
        assertFalse("Error export must not spawn its own raw Thread", errorExport.contains("Thread {"))
        assertFalse("Forensic export must not build JSON on UI click thread", forensicClick.contains("ForensicReportExporter.dumpToFile(applicationContext)"))
    }


    @Test
    fun partial_sell_alerts_and_reports_include_precise_realized_pnl() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("PARTIAL_SELL_ACCOUNTING"))
        assertTrue(exec.contains("fmtPctPrecise()"))
        assertTrue(exec.contains("fmtSignedSol()"))
        assertTrue(exec.contains("Partial Profit (PAPER)"))
        assertTrue(exec.contains("PnL \\${pnlPct.fmtPctPrecise()} (\\${profitSol.fmtSignedSol()} SOL)"))
        assertFalse("Partial paper alert must not round percent to Int", exec.contains("Sold \\${(pct * 100).toInt()}% @ +\\${pnlPct.toInt()}%"))
        assertFalse("Partial paper alert must not hide small SOL PnL at 4 decimals", exec.contains("String.format(\"%.4f\", profitSol)"))
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.contains("PARTIAL_SELL"))
        assertTrue(phc.contains("%+.6f"))
    }

}
