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
        assertTrue(bot.contains("SUPERVISOR_TIMEOUT_COOLDOWN_MS: Long = 90_000L"))
        assertTrue(bot.contains("val cooldownMs = if (open) 45_000L else SUPERVISOR_TIMEOUT_COOLDOWN_MS"))
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
        assertTrue(exec.contains("PnL " + "$" + "{pnlPct.fmtPctPrecise()} (" + "$" + "{profitSol.fmtSignedSol()} SOL)"))
        assertFalse("Partial paper alert must not round percent to Int", exec.contains("Sold " + "$" + "{(pct * 100).toInt()}% @ +" + "$" + "{pnlPct.toInt()}%"))
        assertFalse("Partial paper alert must not hide small SOL PnL at 4 decimals", exec.contains("String.format(\"%.4f\", profitSol)"))
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.contains("PARTIAL_SELL"))
        assertTrue(phc.contains("%+.6f"))
    }


    @Test
    fun runtime_report_faults_from_3700_are_guarded() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("Meme runtime must treat legacy BOTH mode as MEME-only authority", bot.contains("cfg.tradingMode == 2 && memeOn"))
        assertTrue("Cached FDG reuse must not be counted as a fresh FDG phase", bot.contains("FDG_CACHED_REUSE"))
        assertTrue(bot.contains("if (!fdgWasCached)"))
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StateDebuggerAI.kt").readText()
        assertTrue("Doctor must not report stale scanner inactive while scannerActive=true", doctor.contains("SCANNER_INACTIVE && ctx.snapshot.scannerActive"))
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val preLock = exec.substring(exec.indexOf("stage=pre_sell_lock"), exec.indexOf("// Atomic guard: only ONE sell"))
        assertTrue(preLock.contains("PositionCloseLedger.closeIdOf"))
        assertTrue(preLock.contains("stage=pre_sell_lock"))
    }


    @Test
    fun score_too_low_terminal_v3_reject_cannot_be_fdg_probe_override() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("result.reason.contains(\"SCORE_TOO_LOW\", ignoreCase = true)"))
        assertTrue(bot.contains("V3_REJECT_EXEC_SUPPRESSED"))
        assertTrue(bot.contains("!isTerminalV3Reject"))
        assertFalse("SCORE_TOO_LOW must not become FDG green tiny probe", bot.contains("V3 REJECT→PROBE"))
        assertFalse("SCORE_TOO_LOW must not set v3SizeSol from fdgDecision", bot.contains("V3-REJECT-PROBE"))
    }


    @Test
    fun live_buy_signature_confirmation_must_create_managed_position_before_rpc_indexing() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("LIVE_BUY_MANAGED_FROM_SIGNATURE"))
        assertTrue(exec.contains("provisional.copy(pendingVerify = false)"))
        assertTrue(exec.contains("HostWalletTokenTracker.recordBuyConfirmed(ts, sig)"))
        assertTrue(exec.contains("GlobalTradeRegistry.registerPosition"))
        assertTrue(exec.contains("ts.position.pendingVerify || signatureManagedAtEntry"))
        assertFalse("Inconclusive post-buy verification must not leave live buys unmanaged until startup", exec.contains("Will reconcile on next startup"))
        assertFalse("Empty-map post-buy verification must not leave live buys pending/unmanaged", exec.contains("position kept pending, no wipe"))
    }


    @Test
    fun live_pre_broadcast_rug_defense_gate_is_present_and_blocks_holder_risk() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        assertTrue(gate.contains("object PreTradeHardGate"))
        assertTrue(gate.contains("HOLDER_DATA_PENDING"))
        assertTrue(gate.contains("TOP_HOLDER_FATAL_CONCENTRATION"))
        assertTrue(gate.contains("MINT_AUTHORITY_ACTIVE"))
        assertTrue(gate.contains("FREEZE_AUTHORITY_ACTIVE"))
        assertTrue(gate.contains("RUGCHECK_PENDING_OR_UNKNOWN"))
        assertTrue(gate.contains("PRETRADE_HARD_BLOCK"))
        assertTrue(gate.contains("PRETRADE_PENDING_PROOF_SOFT_ALLOW"))
        assertFalse("Pending holder data alone must not hard-block every live buy", gate.contains("return block(ts, \"HOLDER_DATA_PENDING\""))
        assertFalse("RugCheck pending alone must not recreate RC=1 live choke", gate.contains("return block(ts, \"RUGCHECK_PENDING_OR_UNKNOWN\""))
        assertTrue(gate.contains("SINGLE HOLDER"))
        assertTrue(gate.contains("UNVERIFIED TOKEN"))
        assertTrue(gate.contains("TOP 10"))
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val liveGateIdx = exec.indexOf("LiveBuyAdmissionGate.requireApprovedLiveBuy")
        val preTradeIdx = exec.indexOf("PreTradeHardGate.requireLiveBuyAllowed", liveGateIdx.coerceAtLeast(0))
        val walletIdx = exec.indexOf("if (walletSol <= 0)", liveGateIdx.coerceAtLeast(0))
        assertTrue("PreTradeHardGate must be wired after admission and before wallet/broadcast checks", liveGateIdx >= 0 && preTradeIdx > liveGateIdx && walletIdx > preTradeIdx)
        assertTrue(exec.contains("reason=PRETRADE:"))
    }


    @Test
    fun strict_source_balance_prevents_pumpfun_majority_hot_watchlist() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        assertTrue(registry.contains("MAX_PUMP_HOT_FRACTION = 0.35"))
        assertTrue(registry.contains("MIN_NON_PUMP_RESERVED_HOT_SLOTS = 80"))
        assertTrue(registry.contains("MAX_PUMP_PORTAL_CONCURRENT = 175"))
        assertFalse("Pump must not be allowed as 65% majority again", registry.contains("MAX_PUMP_HOT_FRACTION = 0.65"))
        assertFalse("Sparse non-pump bench must not allow unlimited Pump admission", registry.contains("nonPumpCount < MIN_NON_PUMP_RESERVED_HOT_SLOTS / 2"))
        assertTrue(registry.contains("tags.contains(\"METEORA\")"))
        assertTrue(registry.contains("tags.contains(\"BIRDEYE\")"))
        assertTrue(registry.contains("tags.contains(\"ORCA\")"))
        assertTrue(registry.contains("tags.contains(\"JUPITER\")"))
        assertTrue(registry.contains("tags.contains(\"SOLANA\")"))

        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertFalse(service.contains("total * 0.65"))
        assertTrue(service.contains("total * 0.35"))
        assertTrue(service.contains("SOURCE_BALANCE_PUMP_DOMINANCE"))
    }

    @Test
    fun token_merge_queue_ranks_dex_and_raydium_above_pumpportal() {
        val queue = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMergeQueue.kt").readText()
        assertTrue(queue.contains("\"DEX_BOOSTED\" to 72"))
        assertTrue(queue.contains("\"DEX_TRENDING\" to 64"))
        assertTrue(queue.contains("\"RAYDIUM_NEW_POOL\" to 62"))
        assertTrue(queue.contains("\"PUMP_PORTAL_WS\" to 38"))
        assertTrue(queue.contains("\"PUMP_PORTAL\" to 40"))
        assertFalse("PumpPortal must not outrank DEX/Raydium again", queue.contains("\"PUMP_PORTAL_WS\" to 70"))
    }


    @Test
    fun closed_tracker_with_authoritative_proof_must_not_requeue_forever_on_rpc_unknown() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("SELL_ABORT_TRACKER_CLOSED_WALLET_UNKNOWN_TERMINAL"))
        assertTrue(exec.contains("PositionCloseLedger.isClosed(ts.mint)"))
        assertTrue(exec.contains("CLOSED_SOLD_BY_AATE"))
        assertTrue(exec.contains("CLOSED_EXTERNALLY_MANUAL_SWAP"))
        assertTrue(exec.contains("PendingSellQueue.remove(ts.mint)"))
        assertTrue(exec.contains("return SellResult.ALREADY_CLOSED"))
        assertTrue(exec.contains("SELL_PAUSED_TRACKER_CLOSED_WALLET_UNKNOWN"))
    }


    @Test
    fun meme_only_mode_keeps_internal_style_toolkit_alive_but_bounded() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("INTERNAL_TOOLKIT_STARVATION_FIX"))
        assertTrue(bot.contains("BOUNDED_INTERNAL_TOOLKIT_RESCUE"))
        assertTrue(bot.contains("PAPER_WR_DILUTION_FIX"))
        assertTrue(bot.contains("l == \"SHITCOIN\" || l == \"MOONSHOT\" || l == \"EXPRESS\""))
        assertTrue(bot.contains("val memeRescue = affinity"))
        assertTrue(bot.contains("if (memeFamily) return l == memeRescue"))
        assertTrue(bot.contains("nonMemeSpecialist && affinity.contains(l)"))
        assertFalse("MEME-only must not blanket-mute all non-meme specialist lanes", bot.contains("return memeFamily"))
        assertFalse("toolkit alive must not mean all meme-family siblings execute", bot.contains("if (memeFamily) return true"))
        assertTrue("External trader isolation authority must keep MEME as the lane root", bot.contains("mutableSetOf(com.lifecyclebot.engine.EnabledTraderAuthority.Trader.MEME)"))
        assertTrue("Crypto sidecar may be explicitly enabled without reopening meme fanout", bot.contains("if (cryptoSidecarOn) add(com.lifecyclebot.engine.EnabledTraderAuthority.Trader.CRYPTO_ALT)"))
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/EnabledTraderAuthority.kt").readText()
        assertTrue("CRYPTO_ALT must be ignored by meme-lane isolation predicate", auth.contains("val laneSet = set - Trader.CRYPTO_ALT"))
    }


    @Test
    fun live_sell_rejects_txparse_and_recalculates_at_every_processor_boundary() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val amountPlanningSurface = exec + "\n" + planner

        assertTrue(exec.contains("SELL_QTY_SOURCE=BALANCE_UNKNOWN reason=RPC_EMPTY_MAP"))
        assertTrue(amountPlanningSurface.contains("PROCESSOR_AMOUNT_RECALCULATED"))
        assertTrue(exec.contains("ProcessorAmountPlanner.planSellFromConfirmed"))
        listOf(
            "PUMPPORTAL", "JUPITER_ULTRA_METIS", "JUPITER_ULTRA_METIS_LADDER",
            "PUMPPORTAL_EXIT", "PUMPPORTAL_EXIT_RESCUE", "JUPITER_DUST_BUSTER",
            "JUPITER_SHUTDOWN_SWEEP", "PUMPPORTAL_ORPHAN_SWEEP"
        ).forEach { label -> assertTrue("missing sell processor recalc label: $label", amountPlanningSurface.contains(label)) }

        assertTrue(exec.contains("canBroadcastLiveOrEmergency"))
        assertTrue(authority.contains("BALANCE_PROOF_REJECTED reason=GENERIC_TX_PARSE_NOT_OWNER_FILTERED"))
        assertFalse("Generic TX_PARSE must never be emergency broadcast authority", exec.contains("EMERGENCY_TX_PARSE_SELL_RESCUE"))
        assertFalse(exec.contains("SELL_QTY_SOURCE_FRESH_TX_PARSE_EMERGENCY"))
        assertFalse(authority.contains("return tryFreshTxParseFallback"))
    }


    @Test
    fun meme_only_internal_toolkit_is_bounded_to_one_specialist_rescue() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("BOUNDED_INTERNAL_TOOLKIT_RESCUE"))
        assertTrue(bot.contains("at most ONE"))
        assertTrue(bot.contains("return l == rescue"))
        assertTrue(bot.contains("l == \"SHITCOIN\" || l == \"MOONSHOT\" || l == \"EXPRESS\""))
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(report.contains("TradeHistoryStore journal rows; NOT on-chain proof"))
        assertTrue(report.contains("SELL_FINALIZED for landed on-chain truth"))
    }


    @Test
    fun all_live_meme_execution_entrypoints_emit_shared_route_stack() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        listOf("callSite = \"liveBuy\"", "callSite = \"liveTopUp\"", "callSite = \"liveSell\"", "callSite = \"executeProfitLockSell\"", "callSite = \"checkPartialSell\"").forEach {
            assertTrue("missing stack entrypoint marker: $it", exec.contains(it))
        }
        val stack = java.io.File("src/main/kotlin/com/lifecyclebot/engine/execution/MemeExecutionRouteStack.kt").readText()
        listOf("PumpFunDirect", "PumpPortal", "PumpSwapDirect", "RaydiumDirect", "MeteoraDirect", "OrcaDirect", "JupiterUltra", "JupiterMetis").forEach {
            assertTrue("missing execution provider: $it", stack.contains(it))
        }
        listOf("standardRpc", "HeliusSender", "Jito").forEach {
            assertTrue("missing sender provider: $it", stack.contains(it))
        }
        assertTrue(stack.contains("EXEC_STACK_EXHAUSTED"))
        assertTrue(stack.contains("EXEC_PROVIDER_TRY"))
        assertTrue(stack.contains("EXEC_SENDER_TRY"))
    }



    @Test
    fun catastrophic_bootstrap_bleed_engages_brake_and_blocks_low_score_specialist_primary() {
        val brake = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeWREmergencyBrake.kt").readText()
        assertTrue(brake.contains("CATASTROPHIC_BOOTSTRAP_MIN"))
        assertTrue(brake.contains("CATASTROPHIC_BOOTSTRAP_WR_PCT"))
        assertTrue(brake.contains("lifetime >= CATASTROPHIC_BOOTSTRAP_MIN && lifetime < MIN_LIFETIME_TRADES"))
        assertTrue(brake.contains("wrPct < CATASTROPHIC_BOOTSTRAP_WR_PCT"))

        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CatastrophicPaperBleedGuard.kt").readText()
        assertTrue(guard.contains("stale-while-revalidate"))
        assertTrue(guard.contains("catastrophic-paper-bleed-refresh"))
        assertTrue(guard.contains("fun isActive(): Boolean"))

        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("CATASTROPHIC_PAPER_SPECIALIST_BLEED_GUARD"))
        assertTrue(bot.contains("LANE_PRIMARY_SUPPRESSED_CATASTROPHIC_PAPER_BLEED"))
        assertTrue(bot.contains("score > 10"))
        assertTrue(bot.contains("CatastrophicPaperBleedGuard.isActive()"))
        assertFalse("lane hot path must not synchronously refresh RegimeDetector", bot.contains("val r = com.lifecyclebot.engine.RegimeDetector.current()"))
    }

    @Test
    fun low_score_bad_regime_routes_to_defensive_probe_not_dip_primary() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        assertTrue(router.contains("lowScoreBleedContext"))
        assertTrue(router.contains("lowScoreBleedContext -> Style.DEFENSIVE_PROBE"))
        assertTrue(router.contains("score <= 10"))
        assertTrue(router.contains("CatastrophicPaperBleedGuard.isActive()"))
        assertFalse("router hot path must not synchronously refresh RegimeDetector", router.contains("RegimeDetector.current()"))
    }


    @Test
    fun ci_apk_version_name_matches_operator_patch_sequence() {
        val activeRootWorkflow = java.io.File("../../.github/workflows/build.yml").readText()
        val nestedWorkflow = java.io.File("../.github/workflows/build.yml").readText()
        for (workflow in listOf(activeRootWorkflow, nestedWorkflow)) {
            assertTrue(workflow.contains("id: aate_build"))
            assertTrue(workflow.contains("BUILD_NUMBER=$((GITHUB_RUN_NUMBER + 1))"))
            assertTrue(workflow.contains("version_name=\$VERSION_NAME"))
            assertTrue(workflow.contains("-PbuildNumber=\$AATE_BUILD_NUMBER"))
            assertTrue(workflow.contains("AATE_v\${{ steps.aate_build.outputs.version_name }}"))
            assertFalse("APK version must not lag one behind operator patch number", workflow.contains("AATE_v5.0.\${{ github.run_number }}"))
            assertFalse("Gradle buildNumber must not use raw GitHub run number", workflow.contains("-PbuildNumber=\${{ github.run_number }}"))
        }
    }


    @Test
    fun parked_supervisor_timeout_band_must_throttle_before_fifty() {
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SupervisorAdmissionPlanner.kt").readText()
        assertTrue(planner.contains("timeoutCount10m >= 30"))
        assertTrue(planner.contains("\"moderate_timeout_pressure\" -> minOf(maxCap, 12)"))
        assertFalse("Report 3717 had workerTimeout=50 and was still treated healthy", planner.contains("timeoutCount10m > 50"))

        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("SUPERVISOR_TIMEOUT_COOLDOWN_MS: Long = 90_000L"))
        assertTrue(bot.contains("timeouts >= 30"))
        assertTrue(bot.contains("val healthy = scannerAlive && ageSec in 0..90L"))
    }


    @Test
    fun paper_direct_executor_missing_state_is_synthetic_only_not_live() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("PAPER_EXEC_OPEN_SYNTHETIC_FINAL_CANDIDATE"))
        assertTrue(gate.contains("modeUpper == \"PAPER\""))
        assertTrue(gate.contains("isRealExecutionLane(requestedLaneForSynth)"))
        assertTrue(gate.contains("liveLiquidityUsd > 0.0"))
        assertTrue(gate.contains("rug != 0"))
        assertFalse("LIVE must not synthesize missing final candidates", gate.contains("modeUpper == \"LIVE\" &&\n            isRealExecutionLane(requestedLaneForSynth)"))
    }


    @Test
    fun meme_hot_pool_source_fix_preserves_balance_but_samples_healthy_runtime() {
        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(service.contains("SUPERVISOR_HEALTHY_MEME_MAX_INFLIGHT: Int = 48"))
        assertTrue(service.contains("supervisorTimeoutsForPlanning = if ((nowMs - supervisorTimeoutWindowStartMs) < 600_000L) supervisorTimeoutWindowCount else 0"))
        assertTrue(service.contains("selectorHealthy = supervisorTimeoutsForPlanning == 0"))
        assertTrue(service.contains("selectorMaxInFlight = if (selectorHealthy) SUPERVISOR_HEALTHY_MEME_MAX_INFLIGHT else SUPERVISOR_MAX_INFLIGHT"))
        assertTrue(service.contains("val demoteProcessFloor = if (memeFresh) 6 else 3"))
        assertTrue(service.contains("val demoteAgeFloorMs = if (memeFresh) 5L * 60_000L else 120_000L"))
        assertTrue(service.contains("isHighConvictionUnseen"))
        assertTrue(service.contains("filterNot { (mint, _) -> isHighConvictionUnseen(mint, entriesByMint[mint]) }"))

        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        assertTrue(registry.contains("MAX_PUMP_HOT_FRACTION = 0.35"))
        assertTrue(registry.contains("strongPumpHotException"))
        assertTrue(registry.contains("SOURCE_BALANCE_PUMP_STRONG_HOT_ADMIT"))
        assertTrue(registry.contains("if (strongPumpHotException(addedBy, source, initialMcap, laneAffinity, toolAffinity)) return false"))
        assertFalse("Do not weaken source-balance by reverting to 65% Pump", registry.contains("MAX_PUMP_HOT_FRACTION = 0.65"))
    }


    @Test
    fun impossible_accounting_rows_are_quarantined_before_journal_display() {
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(store.contains("fun isValidAccountingTrade(t: Trade): Boolean"))
        assertTrue(store.contains("t.pnlPct < -100.0001 || t.pnlPct > 100_000.0"))
        assertTrue(store.contains("TRADE_ACCOUNTING_QUARANTINED"))
        assertTrue(store.contains("return synchronized(lock) { trades.filter { isValidAccountingTrade(it) }.toList() }"))
        assertTrue(store.contains("TRADE_ACCOUNTING_LEGACY_ROW_FILTERED"))
        assertTrue(store.contains("TRADE_ACCOUNTING_BULK_QUARANTINED"))
        assertTrue(store.contains("TRADE_ACCOUNTING_DB_INIT_FILTERED"))
        assertTrue(store.contains("TRADE_ACCOUNTING_PREFS_MIGRATION_FILTERED"))
        assertTrue(store.contains("trades.filter { it.ts >= midnight && isValidAccountingTrade(it) }"))
        assertFalse("invalid accounting rows must not still be persisted after warning", store.contains("PARTIAL_SELL_INVALID_ACCOUNTING mint="))

        val journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeJournal.kt").readText()
        assertTrue(journal.contains("TradeHistoryStore.isValidAccountingTrade(t)"))
        assertFalse("avg win must not mask impossible rows with a 100000% cap", journal.contains("coerceAtMost(100000.0)"))

        val activity = java.io.File("src/main/kotlin/com/lifecyclebot/ui/JournalActivity.kt").readText()
        assertTrue(activity.contains("isValidJournalAccounting"))
        assertTrue(activity.contains("filter { com.lifecyclebot.engine.TradeHistoryStore.isValidAccountingTrade(it) }"))
        assertTrue(activity.contains("val validEntries = allEntries.filter { isValidJournalAccounting(it) }"))
    }


    @Test
    fun live_deadness_must_not_hide_behind_no_open_committed_or_paper_shadow() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("emitLiveBuyFail"))
        assertTrue(exec.contains("NO_OPEN_COMMITTED_AFTER_LIVEBUY"))
        assertTrue(exec.contains("LIVE_BUY_FAIL_"))
        assertTrue(exec.contains("emitLiveBuyFail(ts, liveSol, \"NO_OPEN_COMMITTED_AFTER_LIVEBUY\")"))

        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionRouteGuard.kt").readText()
        assertTrue(guard.contains("PAPER_ROUTE_BLOCKED_IN_LIVE_USE_SHADOW_PATH"))
        assertFalse("paperBuy must not be allowed in LIVE just because shadowPaperEnabled is true", guard.contains("SHADOW_ALLOWED_IN_LIVE"))
        assertTrue(guard.contains("runShadowPaperBuy"))

        val stack = java.io.File("src/main/kotlin/com/lifecyclebot/engine/execution/MemeExecutionRouteStack.kt").readText()
        assertTrue(stack.contains("sideEffectLight: Boolean = true"))
        assertTrue(stack.contains("if (!context.sideEffectLight && !s.supported)"))
        assertTrue(stack.contains("if (!context.sideEffectLight) senders.forEach"))

        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(report.contains("MODE CONTAMINATION CHECK"))
        assertFalse("Report must not claim paper is firing live from cumulative stale counters", report.contains("paper trades are firing during live"))
    }


    @Test
    fun live_buy_and_jupiter_fee_contracts_do_not_recreate_sell_only_deadlock() {
        val jupiter = java.io.File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        assertTrue(jupiter.contains("Compute unit price and prioritization fee are mutually exclusive"))
        assertTrue(jupiter.contains("put(\"prioritizationFeeLamports\", JSONObject().put(\"jitoTipLamports\", senderTipLamports))"))
        assertFalse("Jito-tip Jupiter builds must not also send computeUnitPriceMicroLamports", jupiter.contains("put(\"computeUnitPriceMicroLamports\", senderComputeUnitPriceMicroLamports.coerceAtLeast(1L))"))

        val host = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        assertTrue(host.contains("live buy handoff must not depend on Position.isOpen"))
        assertTrue(host.contains("if (pos.qtyToken <= 0.0)"))
        assertFalse("recordBuyConfirmed must not early-return on !isOpen; pendingVerify live buys must be tracked", host.contains("if (!ts.position.isOpen) return"))
        assertTrue(host.contains("pendingVerify=${'$'}{pos.pendingVerify}"))
    }


    @Test
    fun sell_only_safe_mode_uses_blocking_close_leases_not_idle_backoff_leases() {
        val closeLease = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/CloseLease.kt").readText()
        assertTrue(closeLease.contains("fun activeBlockingLeaseCount()"))
        assertTrue(closeLease.contains("l.inFlight || now >= l.nextEligibleMs"))
        assertTrue(closeLease.contains("activeLeaseCount() is diagnostic"))

        val snapshot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        assertTrue(snapshot.contains("CloseLease.activeBlockingLeaseCount()"))
        assertFalse("SellOnlySafeMode must not use diagnostic activeLeaseCount as pendingSellQueue", snapshot.contains("val pendingSell = try { com.lifecyclebot.engine.sell.CloseLease.activeLeaseCount()"))
    }


    @Test
    fun live_transfer_audit_and_snapshot_do_not_report_stale_live_deadness() {
        val snapshot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        assertTrue(snapshot.contains("live-open truth must be wallet/host-backed"))
        assertTrue(snapshot.contains("HostWalletTokenTracker.getActuallyHeldMints()"))
        assertTrue(snapshot.contains("!it.position.isPaperPosition && it.mint in heldMints"))

        val audit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveTransferAudit.kt").readText()
        assertTrue(audit.contains("execLiveBuyOk"))
        assertTrue(audit.contains("execLiveSellOk"))
        assertTrue(audit.contains("do not emit the impossible blocker after live execution"))
        assertTrue(audit.contains("fdgLiveAllow <= 0L && execLiveAttempt <= 0L && execLiveBuyOk <= 0L && execLiveSellOk <= 0L"))
    }


    @Test
    fun scanner_active_truth_comes_from_recent_raw_callbacks_not_heartbeat_only() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("scanner-active source truth"))
        assertTrue(bot.contains("BotRuntimeController.markScannerActive(startBotScannerGen, true)"))

        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(pipe.contains("fun scannerRecentlyActive"))
        assertTrue(pipe.contains("PHASE/SCAN_CB"))
        assertTrue(pipe.contains("PHASE/INTAKE"))

        val guardian = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()
        assertTrue(guardian.contains("scannerRecentlyFed"))
        assertTrue(guardian.contains("no recent SCAN_CB/INTAKE pulse"))
    }

    @Test
    fun runtime_root_cause_uses_current_faults_not_stale_recent_faults() {
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeDoctor.kt").readText()
        assertTrue(doctor.contains("current faults only"))
        assertTrue(doctor.contains("invariantFaults = faults"))
        assertTrue(doctor.contains("fun currentFaults()"))

        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(report.contains("RuntimeDoctor.currentFaults()"))
        assertFalse("Root cause line must not print stale recent RuntimeDoctor history", report.contains("RuntimeDoctor.recentFaults()"))
    }


    @Test
    fun v3_live_handoff_reuses_any_recent_lane_approved_attempt() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(gate.contains("fun recentAllowedAttemptIdAnyLane"))
        assertTrue(gate.contains("NO_FINAL_BUY_CANDIDATE"))
        assertTrue(gate.contains("MOONSHOT"))

        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("recentAllowedAttemptIdAnyLane(ts.mint)"))
        assertTrue(bot.contains("authResult.attemptId.ifBlank"))
        assertTrue(bot.contains("recentAllowedAttemptId(ts.mint, ts.position.tradingMode.ifBlank"))

        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("effectiveAttemptId"))
        assertTrue(exec.contains("effectiveFinalityPrechecked"))
        assertTrue(exec.contains("recentAllowedAttemptIdAnyLane(ts.mint)"))
        assertTrue(exec.contains("NO_FINAL_BUY_CANDIDATE"))
    }


    @Test
    fun source_balance_demotion_preserves_intake_liquidity_metadata() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        assertTrue(registry.contains("var initialLiquidityUsd"))
        assertTrue(registry.contains("var initialConfidence"))
        assertTrue(registry.contains("if (initialLiquidityUsd > existing.initialLiquidityUsd)"))

        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(service.contains("initialLiquidityUsd = liquidityUsd"))
        assertTrue(service.contains("confidence = confidence"))
        assertTrue(service.contains("do not fabricate zero-liquidity probation rows"))
        assertTrue(service.contains("val demoteLiq"))
        assertFalse(
            "Source-balance demotion must not hardcode liq=0 for real-liq intake",
            service.contains("liquidityUsd = 0.0") &&
                service.contains("confidence = 0") &&
                service.contains("isEstimatedLiquidity = true") &&
                service.contains("SOURCE_BALANCE_PUMP_DOMINANCE_")
        )
    }


    @Test
    fun live_profit_sells_reject_generic_txparse_during_rpc_indexing_gap() {
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(auth.contains("fun resolveForExit"))
        assertTrue(auth.contains("isProfitProtectExitReason"))
        assertTrue(auth.contains("PARTIAL_TAKE_PROFIT"))
        assertTrue(auth.contains("CAPITAL_RECOVERY"))
        assertTrue(auth.contains("BALANCE_PROOF_REJECTED reason=GENERIC_TX_PARSE_NOT_OWNER_FILTERED"))
        assertTrue(auth.contains("BALANCE_UNKNOWN reason=RPC_EMPTY_MAP"))
        assertFalse("Generic TX_PARSE must not bypass sell broadcast authority", auth.contains("TX_PARSE_BROADCAST_BYPASS"))
        assertTrue(exec.contains("resolveForExit(ts.mint, wallet, reason)"))
        assertTrue(exec.contains("SELL_WAITING_BALANCE_PROOF"))
        assertTrue(exec.contains("CloseLease.release(ts.mint"))
    }


    @Test
    fun paper_stale_price_timeout_closes_scratch_instead_of_zombie_holding() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(bot.contains("PAPER_STALE_ZOMBIE_SCRATCH_EXIT"))
        assertTrue(bot.contains("PAPER_STALE_PRICE_TIMEOUT_SCRATCH"))
        assertTrue(bot.contains("staleLivePriceThreshMs + 60_000L"))
        assertTrue(bot.contains("cfg.paperMode && livePriceAgeMs > paperStaleTimeoutMs"))
        assertTrue(bot.contains("Live keeps the existing"))
        assertTrue(exec.contains("SCRATCH"))
        assertTrue(exec.contains("return Pair(-3.0, +3.0)"))
    }




    @Test
    fun live_buy_recalculates_sol_spend_at_processor_boundaries_and_senders_do_not_size() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        val amountPlanningSurface = exec + "\n" + planner

        assertTrue(exec.contains("data class ProcessorBuyPlan"))
        assertTrue(planner.contains("data class BuyPlan"))
        assertTrue(amountPlanningSurface.contains("BUY_PROCESSOR_AMOUNT_RECALCULATED"))
        assertTrue(exec.contains("ProcessorAmountPlanner.planBuy"))
        listOf(
            "PUMPPORTAL_BUY", "PUMPPORTAL_BUY_INTERNAL", "JUPITER_ULTRA_METIS_BUY",
            "PUMPPORTAL_TOP_UP", "JUPITER_ULTRA_METIS_TOP_UP"
        ).forEach { label -> assertTrue("missing buy processor recalc label: $label", amountPlanningSurface.contains(label)) }

        assertTrue(amountPlanningSurface.contains("PumpPortal") || amountPlanningSurface.contains("PUMPPORTAL"))
        assertTrue(amountPlanningSurface.contains("Jupiter Ultra") || amountPlanningSurface.contains("JUPITER_ULTRA"))
        assertTrue(amountPlanningSurface.contains("Helius/Jito/RPC remain senders") || planner.contains("Helius/Jito/RPC are senders"))
        assertFalse("PumpPortal buy builder must not receive stale caller solAmount", exec.contains("solAmount       = solAmount"))
        assertFalse("Jupiter live-buy quote must not use stale liveBuy lamports after PumpPortal fallback", exec.contains("JupiterApi.SOL_MINT, ts.mint, lamports"))
    }

    @Test
    fun live_sell_balance_authority_rejects_generic_txparse_and_false_closed() {
        val sellAuth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()

        assertTrue(sellAuth.contains("data class BalanceProof") || java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProof.kt").readText().contains("data class BalanceProof"))
        assertTrue(sellAuth.contains("BALANCE_PROOF_REJECTED reason=GENERIC_TX_PARSE_NOT_OWNER_FILTERED"))
        assertTrue(sellAuth.contains("BALANCE_UNKNOWN reason=RPC_EMPTY_MAP"))
        assertFalse("RPC empty must not fall back to TX_PARSE confirmed balance", sellAuth.contains("return tryFreshTxParseFallback(mint) ?: Resolution.Unknown"))
        assertFalse("TX_PARSE must not be broadcast bypass", sellAuth.contains("TX_PARSE_BROADCAST_BYPASS"))

        assertTrue(tracker.contains("OPEN_BALANCE_UNKNOWN_RECOVERY_REQUIRED"))
        assertTrue(tracker.contains("OPEN_SELL_FAILED_NO_SIGNATURE_RETRYING"))
        assertTrue(tracker.contains("recordBuyConfirmedWithProof"))
        assertTrue(tracker.contains("CLOSED_REJECTED_NO_SIGNATURE_NO_ZERO_PROOF"))
        assertFalse("No-signature txparse must not stamp CLOSED", tracker.contains("CLOSED_BY_TX_PARSE_NO_SIGNATURE"))

        assertTrue(exec.contains("OWNER_DELTA_PROOF"))
        assertTrue(exec.contains("OPEN_SELL_FAILED_NO_SIGNATURE_RETRYING"))
        assertTrue(exec.contains("PUMPPORTAL_PARTIAL") || exec.contains("JUPITER_ULTRA_METIS_PARTIAL"))
        assertFalse("PumpPortal partial skip must not be a no-signature failure", exec.contains("SEV_PUMPPORTAL_PARTIAL_BLOCKED"))
        assertFalse("Host tracker must not be sell quantity authority", exec.contains("SELL_QTY_SOURCE=HOST_TRACKER"))

        assertTrue(doctor.contains("LIVE_SELL_NO_FINALITY"))
        assertTrue(doctor.contains("falseTxParseClosed"))
    }


    @Test
    fun live_buy_committed_open_result_drives_wallet_lock() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()

        assertTrue(exec.contains("private fun liveBuy"))
        assertTrue(exec.contains("): Boolean {    // V5.9.386"))
        assertTrue(exec.contains("committed live-open source truth"))
        assertTrue(exec.contains("val liveOpened = liveBuy(ts, liveSol"))
        assertTrue(exec.contains("LIVE_OPEN_COMMITTED_LOCK_RECORDED"))
        assertTrue(exec.contains("if (liveOpened || positionDidOpen(ts))"))
        assertTrue(exec.contains("return true"))
        assertTrue(exec.contains("return false"))

        assertTrue(planner.contains("object ProcessorAmountPlanner"))
        assertTrue(planner.contains("data class BuyPlan"))
        assertTrue(planner.contains("data class SellPlan"))
        assertTrue(planner.contains("fun planBuy"))
        assertTrue(planner.contains("fun planSellFromConfirmed"))
        assertTrue(exec.contains("ProcessorAmountPlanner.planBuy"))
        assertTrue(exec.contains("ProcessorAmountPlanner.planSellFromConfirmed"))
    }


    @Test
    fun balance_unknown_does_not_requeue_or_hold_blocking_lease() {
        // V5.0.3746 — operator spec items 1, 4, 5, 7, 9, 11.
        // BALANCE_UNKNOWN must hand the mint to BalanceProofPoller via the
        // WAITING_BALANCE_PROOF state — it MUST NOT enter PendingSellQueue
        // (which emits SELL_RETRY_TEMPORARY_ONLY) and MUST NOT re-acquire a
        // close lease on the next exit tick.
        val waitState = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofWaitState.kt").readText()
        val poller    = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofPoller.kt").readText()
        val exec      = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val lease     = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/CloseLease.kt").readText()
        val service   = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val forensics = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellForensics.kt").readText()
        val doctor    = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()

        // The wait registry exists and exposes the required surface.
        assertTrue(waitState.contains("object BalanceProofWaitState"))
        assertTrue(waitState.contains("fun markWaiting"))
        assertTrue(waitState.contains("fun isWaiting"))
        assertTrue(waitState.contains("fun scheduleNextPoll"))
        assertTrue(waitState.contains("fun recordZeroRead"))
        assertTrue(waitState.contains("BALANCE_WAIT_MERGE"))

        // The poller is wired and uses the SellAmountAuthority + waitState pipeline.
        assertTrue(poller.contains("object BalanceProofPoller"))
        assertTrue(poller.contains("SellAmountAuthority.resolve"))
        assertTrue(poller.contains("BALANCE_PROOF_READY"))
        assertTrue(poller.contains("ZERO_BALANCE_CONFIRMED"))
        assertTrue(poller.contains("BalanceProofWaitState.clear"))

        // Forensic counter constants exist.
        assertTrue(forensics.contains("SELL_WAITING_BALANCE_PROOF"))
        assertTrue(forensics.contains("BALANCE_PROOF_POLL_SCHEDULED"))
        assertTrue(forensics.contains("BALANCE_PROOF_STILL_UNKNOWN"))
        assertTrue(forensics.contains("BALANCE_PROOF_READY"))
        assertTrue(forensics.contains("ZERO_BALANCE_CONFIRMED"))
        assertTrue(forensics.contains("EXEC_LIVE_SELL_WAITING_BALANCE_PROOF"))
        assertTrue(forensics.contains("EXEC_LIVE_SELL_FINALIZED"))
        assertTrue(forensics.contains("EXEC_LIVE_SELL_ROUTE_FAILED_NO_SIGNATURE"))

        // SellResult.WAITING_BALANCE_PROOF must exist and be handled.
        assertTrue(exec.contains("WAITING_BALANCE_PROOF,"))
        assertTrue(exec.contains("SellResult.WAITING_BALANCE_PROOF ->"))
        // requestSell short-circuits on the wait state, no lease re-acquired.
        assertTrue(exec.contains("BalanceProofWaitState.isWaiting(ts.mint)"))
        assertTrue(exec.contains("return SellResult.WAITING_BALANCE_PROOF"))

        // CloseLease.acquire must short-circuit when the mint is in proof wait.
        assertTrue(lease.contains("BalanceProofWaitState.isWaiting(mint)"))
        assertTrue(lease.contains("SELL_LEASE_DEFERRED_PROOF_WAIT"))

        // BotService wires the poller with both proof-ready and zero-confirmed callbacks.
        assertTrue(service.contains("BalanceProofPoller.start"))
        assertTrue(service.contains("onProofReady"))
        assertTrue(service.contains("onZeroConfirmed"))

        // Doctor knows about both subfaults.
        assertTrue(doctor.contains("BALANCE_UNKNOWN_REQUEUE_LOOP"))
        assertTrue(doctor.contains("CLOSE_LEASE_LEAK_AFTER_NO_SIGNATURE"))
        assertTrue(doctor.contains("waitingBalanceProof"))
    }


    @Test
    fun live_position_finality_state_machine_never_closes_unknown_or_no_signature() {
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val ledger = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionCloseLedger.kt").readText()
        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val proofState = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofState.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        assertTrue(proofState.contains("data class PositiveBalanceProof"))
        assertTrue(proofState.contains("data class ZeroBalanceProof"))
        assertTrue(proofState.contains("data class UnknownBalanceProof"))
        assertTrue(proofState.contains("data class StalePositiveBalanceProof"))
        assertTrue(proofState.contains("UNKNOWN is intentionally not ZERO"))

        val sellAuthority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        assertTrue(sellAuthority.contains("BALANCE_UNKNOWN reason=RPC_EMPTY_MAP"))
        assertTrue(sellAuthority.contains("BALANCE_UNKNOWN reason=MINT_ABSENT_FROM_ONE_PROVIDER"))
        assertTrue(sellAuthority.contains("BALANCE_UNKNOWN reason=ONE_PROVIDER_ZERO"))
        assertTrue(sellAuthority.contains("using lastPositiveRaw recovery amount"))
        assertTrue(sellAuthority.contains("HostWalletTokenTracker.getEntry"))
        assertFalse("one provider missing mint must not be zero", sellAuthority.contains("mint NOT in the map AND map is non-empty → genuine zero"))

        assertTrue(tracker.contains("markOpenBalanceUnknown"))
        assertTrue(tracker.contains("RPC_EMPTY_MAP_MINT_ABSENT"))
        assertTrue(tracker.contains("REAP_SKIPPED_BALANCE_UNKNOWN"))
        assertTrue(tracker.contains("REAP_SKIPPED_LAST_POSITIVE_HELD"))
        assertTrue(tracker.contains("OPEN_SELL_FAILED_NO_SIGNATURE_RETRYING"))
        assertTrue(tracker.contains("OPEN_BALANCE_UNKNOWN_RECOVERY_REQUIRED"))
        assertTrue(tracker.contains("hasLastPositiveRaw(p)"))
        assertTrue(tracker.contains("isOpenForAccounting"))
        assertTrue(tracker.contains("p.zeroBalanceConfirmedByTwoProviders"))
        assertFalse("ghost reaper must not emit the old unverified close marker", tracker.contains("GHOST_REAPED zero-balance open row → CLOSED"))
        assertFalse("startup reconcile must not close live rows from a bare wallet=0", tracker.contains("STARTUP_GHOST_RECONCILE wallet=0 → CLOSED"))

        assertTrue(ledger.contains("POSITION_CLOSE_LEDGER_REJECTED"))
        assertTrue(ledger.contains("RPC_EMPTY_MAP"))
        assertTrue(ledger.contains("NO_SIGNATURE_UNLOCKED"))
        assertTrue(ledger.contains("STARTUP_GHOST_RECONCILE"))
        assertTrue(ledger.contains("GHOST_REAP_ZERO_BALANCE"))

        assertTrue(service.contains("confirmZeroBalanceClose"))
        assertTrue(service.contains("REAP_CLOSED_CONFIRMED_ZERO"))
        assertTrue(service.contains("REAP_SKIPPED_BALANCE_UNKNOWN"))
        assertFalse("poller must not close no-broadcast zero directly", service.contains("ZERO_BALANCE_CLOSED_NO_BROADCAST"))

        assertTrue(exec.contains("OPEN_SELL_FAILED_NO_SIGNATURE_RETRYING"))
        assertFalse("new runtime must not emit the old no-signature finality marker", exec.contains("\"SELL_ROUTE_FAILED_NO_SIGNATURE_UNLOCKED\""))
    }


    @Test
    fun crypto_alt_sidecar_does_not_break_meme_lane_isolation() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/EnabledTraderAuthority.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()

        assertTrue(bot.contains("cryptoSidecarOn"))
        assertTrue(bot.contains("Trader.CRYPTO_ALT"))
        assertTrue(bot.contains("cryptoSidecarOn) add(com.lifecyclebot.engine.EnabledTraderAuthority.Trader.CRYPTO_ALT)"))
        assertTrue(bot.contains("CryptoAltTrader.start"))
        assertTrue(bot.contains("CryptoAltTrader.setEnabled(cryptoUniverseOn"))
        assertTrue(auth.contains("val laneSet = set - Trader.CRYPTO_ALT"))
        assertTrue(auth.contains("return laneSet.size == 1 && Trader.MEME in laneSet"))
        assertTrue(crypto.contains("operatorExplicitlyEnabled"))
        assertTrue(crypto.contains("cfg.cryptoAltsEnabled && cfg.marketsTraderEnabled"))
    }


    @Test
    fun runtime_start_resets_mode_desync_writers() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeModeAuthority.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        assertTrue(authority.contains("fun publishRuntimeStart"))
        assertTrue(authority.contains("executor = m"))
        assertTrue(authority.contains("pipeline = m"))
        assertTrue(collector.contains("fun resetModeCountersForRuntime"))
        assertTrue(collector.contains("fdgPaperAllow.set(0L)"))
        assertTrue(collector.contains("execPaperBuyOk.set(0L)"))
        assertTrue(bot.contains("publishRuntimeStart(startPaper, startAuto)"))
        assertTrue(bot.contains("resetModeCountersForRuntime(if (startPaper) \"PAPER\" else \"LIVE\")"))
        assertTrue(bot.contains("modeSynced=true"))
    }


    @Test
    fun live_sell_uses_buy_tied_owner_delta_during_rpc_empty_gap() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        assertTrue(authority.contains("BUY_TIED_OWNER_DELTA"))
        assertTrue(authority.contains("SELL_BALANCE_PROOF_OWNER_DELTA_RECOVERED"))
        assertTrue(authority.contains("isEmergencyExitReason(reason) || isProfitProtectExitReason(reason)"))
        assertTrue(authority.contains("Resolution.Confirmed(cached.rawAmount, cached.decimals, Source.TX_META_OWNER_DELTA)"))
        assertTrue(authority.contains("Source.TX_META_OWNER_DELTA -> BalanceSource.WALLET_SCAN_CONFIRMED"))
        assertTrue(exec.contains("SellAmountAuthority.resolveForExit(ts.mint, wallet, reason)"))
        assertTrue(exec.contains("SellAmountAuthority.canBroadcastLiveOrEmergency"))
        assertTrue(exec.contains("exitReason: String = processor"))
        assertTrue(exec.contains("PROCESSOR_AMOUNT_OWNER_DELTA_RECOVERED"))
        assertTrue(exec.contains("SellAmountAuthority.resolveForExit(ts.mint, wallet, reason)"))
        assertFalse("generic tx parse must not be blindly accepted", authority.contains("return tryFreshTxParseFallback(mint"))
    }


    @Test
    fun buy_sell_root_cause_trace_labels_are_wired() {
        val tracer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionRootCauseTrace.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()

        listOf("EXEC_TRACE_BUY", "EXEC_TRACE_SELL", "EXEC_TRACE_AUTHORITY", "EXEC_TRACE_ROUTE", "EXEC_TRACE_FINALITY").forEach { label ->
            assertTrue("missing trace label $label", tracer.contains(label) || exec.contains(label) || planner.contains(label) || authority.contains(label))
        }
        assertTrue(exec.contains("DO_EXECUTE_BUY_DECISION"))
        assertTrue(exec.contains("LIVE_BUY_ENTRY"))
        assertTrue(exec.contains("DO_SELL_ENTRY"))
        assertTrue(exec.contains("LIVE_SELL_ENTRY"))
        assertTrue(planner.contains("BUY_PLAN_START"))
        assertTrue(planner.contains("SELL_PLAN_OK"))
        assertTrue(authority.contains("OWNER_DELTA_CACHE_RECORD"))
        assertTrue(authority.contains("BROADCAST_AUTH_ALLOW"))
        assertTrue(authority.contains("BROADCAST_AUTH_BLOCK"))
    }


    @Test
    fun live_sell_rpc_empty_precheck_uses_owner_delta_before_wait() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("LIVESELL_RPC_EMPTY_OWNER_DELTA_RECOVERED"))
        assertTrue(exec.contains("SellAmountAuthority.resolveForExit(ts.mint, wallet, reason)"))
        assertTrue(exec.contains("LIVESELL_RPC_EMPTY_BALANCE_UNKNOWN"))
        assertTrue(exec.contains("REQUEST_SELL_BALANCE_WAIT_MERGE"))
        assertTrue(exec.contains("REQUEST_SELL_BALANCE_WAIT_PROOF_READY"))
        assertTrue(exec.contains("BalanceProofWaitState.clear(ts.mint, \"PROOF_READY_REQUESTSELL\")"))
        assertTrue(exec.indexOf("LIVESELL_RPC_EMPTY_OWNER_DELTA_RECOVERED") < exec.indexOf("LIVESELL_RPC_EMPTY_BALANCE_UNKNOWN"))
    }

}
