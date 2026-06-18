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
    fun live_runtime_canonical_open_includes_confirmed_pending_balance_not_stale_local_only() {
        // V5.0.3760 ASTRO fix: physical walletHeld remains proof-only, but a
        // confirmed buy signature is canonical open/sell-managed while token
        // account indexing catches up.
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        assertTrue(source.contains("canonical LIVE truth includes confirmed-pending-balance"))
        assertTrue(source.contains("maxOf(walletHeld, hostOpen, localLiveOpen, lifecyclePendingConfirmed)"))
        assertTrue(source.contains("val heldMints = try { HostWalletTokenTracker.getActuallyHeldMints()"))
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
        assertTrue(router.contains("DIAMOND_HANDS_RUNNER"))
        assertTrue(router.contains("DEGEN_MICRO_SNIPE"))
        assertTrue(router.contains("CHART_BREAKOUT"))
        assertTrue(router.contains("MAINSTREAM_CRYPTO_SWING"))
        assertTrue(router.contains("ToolkitSignalSheet.snapshot"))
        assertTrue(router.contains("TacticSwitcher.currentTactic"))
    }

    @Test
    fun toolkit_signal_sheet_integrates_full_toolkit_without_new_fanout_or_executor_path() {
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val internet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InternetEdgeDesk.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        assertTrue("Toolkit sheet must be read-only and hot-path safe", sheet.contains("Read-only, hot-path-safe") && sheet.contains("does NOT call FDG") && sheet.contains("does NOT call network/LLM APIs") && sheet.contains("silent coroutine") && sheet.contains("single-flight per mint"))
        assertTrue("Toolkit sheet must expose dormant degen/chart/diamond/mainstream/full-stack setups", listOf("DIAMOND_HANDS_RUNNER", "DEGEN_MICRO_SNIPE", "PUMP_GRADUATION_SNIPE", "CHART_BREAKOUT", "CHART_PULLBACK_RECLAIM", "MAINSTREAM_CRYPTO_SWING", "VOLUME_IGNITION_SCALP", "SMART_WALLET_COPY_FOLLOW", "NARRATIVE_SOCIAL_IGNITION", "LIQUIDITY_DEPTH_QUALITY", "PANIC_REVERSION_BOUNCE", "ARB_FLOW_IMBALANCE", "MEV_PROTECTED_ENTRY", "REENTRY_RECOVERY", "REGIME_DEFENSIVE_PROBE").all { sheet.contains(it) && router.contains(it) })
        assertTrue("Agentic router must consume the cached helper sheet before style election", router.contains("val sheet = try { ToolkitSignalSheet.snapshot") && router.contains("styleForToolkit(sheet)") && router.contains("toolkit=${'$'}{sheet.setup}"))
        assertTrue("Toolkit votes must pass only through existing bounded style fanout", router.contains("base + d.toolkit.laneVotes") && router.contains("base + d.toolkit.toolVotes") && router.contains("return boundedLanes") && router.contains("return boundedTools"))
        assertFalse("Toolkit upgrade must not add a new FDG/evaluator fanout in BotService", bot.contains("ToolkitSignalSheet.build(ts") || bot.contains("ToolkitSignalSheet.build("))
        assertFalse("Toolkit upgrade must not create a new executor buy/sell path", executor.contains("ToolkitSignalSheet") || executor.contains("DIAMOND_HANDS_RUNNER"))
        assertTrue("Toolkit sheet must refresh silently without bot-loop blocking", sheet.contains("GlobalScope.launch(AppDispatchers.sideEffect)") && sheet.contains("inFlight.add(mint)") && sheet.contains("fallbackSheet"))
        assertTrue("Internet LLM edge must be background-only and feed cached soft setup bias", internet.contains("GlobalScope.launch(AppDispatchers.sideEffect)") && internet.contains("GeminiCopilot.rawText") && internet.contains("setupScoreBias") && sheet.contains("InternetEdgeDesk.setupScoreBias") && sheet.contains("InternetEdgeDesk.refreshAsync"))
        assertFalse("Toolkit sheet must not perform network or LLM calls directly", listOf("http", "OkHttp", "Retrofit", "Groq", "GeminiCopilot.rawText", "Thread.sleep", "runBlocking").any { sheet.contains(it) })
    }




    @Test
    fun unified_report_budget_prioritizes_toolkit_and_prevents_tail_truncation() {
        val hub = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()

        assertTrue("Unified report must include a first-class toolkit section near the top", hub.contains("TOOLKIT SIGNAL SHEET") && hub.contains("buildToolkitSignalSummary"))
        assertTrue("Unified report budgets must fit under chat cap before hard truncation", hub.contains("REPORT BUDGET RECOMPILE") && hub.contains("priority-budgeted before truncation"))
        assertTrue("Pipeline block must be core-only so learning/tuning is not duplicated", hub.contains("PIPELINE HEALTH — CORE") && !hub.contains("PIPELINE HEALTH — CONDENSED", ignoreCase = false))
        assertTrue("Error logs must be bounded tightly to avoid eating the report tail", hub.contains("ErrorLogger.exportToText(limit = 25)"))
        assertTrue("Toolkit setup/chart counters must feed report visibility", sheet.contains("TOOLKIT_SETUP_${'$'}{built.setup.name}") && sheet.contains("TOOLKIT_CHART_${'$'}{built.chartPattern.uppercase().take(48)}"))
        assertTrue("ANR evidence must remain visible in compact report", hub.contains("===== ANR / main-thread health") && hub.contains("===== ANR top blocking call sites") && hub.contains("ANR top:"))
        assertTrue("Internet edge desk must be visible in toolkit report section", hub.contains("InternetEdgeDesk.summaryLine") && hub.contains("INTERNET_EDGE_REFRESHED"))
        assertTrue("Learning-heavy PHC sections must not be duplicated inside core pipeline block", !hub.contains("\"===== Strategy Hypothesis Engine\"") && !hub.contains("\"===== Lane Exit Tuner\"") && !hub.contains("\"===== Autonomous Meta-Policy\"") && !hub.contains("\"===== Unified Policy Head\""))
    }









    @Test
    fun live_stale_restore_cannot_resurrect_old_fdg_approval() {
        val openGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue("LIVE stale-WATCH restore must require current candidate version and fdgCan=true", openGate.contains("currentStateVersion") && openGate.contains("state?.fdgCan == true") && openGate.contains("candidateVersion == currentVersion"))
        assertTrue("LIVE stale-candidate restore must be disabled; current FDG must re-approve", openGate.contains("stale candidate restore disabled for LIVE; current FDG must re-approve"))
    }
    @Test
    fun internet_edge_text_fallback_is_not_mislabeled_as_parsed_internet_json() {
        val internet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InternetEdgeDesk.kt").readText()
        assertTrue("Text-only LLM fallback must keep llm_text source instead of being overwritten as llm_internet", internet.contains("""val src = if (brief.source == "llm_text") "llm_text" else "llm_internet""".trimIndent()) && internet.contains("""source = "llm_text""".trimIndent()))
        assertTrue("Internet edge must still mark parsed JSON briefs as llm_internet", internet.contains("cached = brief.copy(atMs = System.currentTimeMillis(), source = src)"))
    }

    @Test
    fun fdg_fanout_diagnosis_uses_decision_outcomes_not_forensic_rows() {
        val guardian = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val hub = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()

        assertTrue("FDG fanout fault must use allow+block decision outcomes, not raw FDG forensic rows", guardian.contains("fdgDecisions") && guardian.contains("phaseAllow[\"FDG\"]") && guardian.contains("phaseBlock[\"FDG\"]") && guardian.contains("rawFdgRows"))
        assertTrue("Pipeline report must display FDG decision outcomes and separate raw rows", phc.contains("FinalDecisionGate decision outcomes") && phc.contains("FDG_RAW_ROWS") && phc.contains("forensic FDG rows; not unique evaluations") && phc.contains("throughputFdgDecisions") && phc.contains("raw FDG forensic rows"))
        assertTrue("Executive snapshot must use decision outcomes for FDG count", hub.contains("pipe.phaseAllow[\"FDG\"]") && hub.contains("pipe.phaseBlock[\"FDG\"]"))
    }





    @Test
    fun memetrader_lanes_rotate_full_surface_without_all_lane_fanout() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("MEME-only should rotate ownership across the full MemeTrader surface", bot.contains("MEMETRADER_CONTRIBUTION_ROTATION") && bot.contains("fullMemeTraderRing") && bot.contains("MEMETRADER_OWNER_LANE"))
        assertTrue("Rotation must include internal lanes that were previously idle", listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "QUALITY", "DIP_HUNTER", "TREASURY", "BLUECHIP").all { bot.contains(it) })
        assertTrue("Contribution fix must remain bounded to one owner lane, not all-lane fanout", bot.contains("val allowed = l == ownerLane") && bot.contains("return allowed"))
    }






    @Test
    fun ui_and_runtime_diagnostics_do_not_copy_full_trade_journal_on_hot_paths() {
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeDoctor.kt").readText()
        val losing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LosingPatternMemory.kt").readText()
        val regime = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RegimeDetector.kt").readText()
        val macro = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MacroPollers.kt").readText()
        val strategy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StrategyTelemetry.kt").readText()
        val journalActivity = java.io.File("src/main/kotlin/com/lifecyclebot/ui/JournalActivity.kt").readText()
        val learningCounter = java.io.File("src/main/kotlin/com/lifecyclebot/ui/LearningCounterActivity.kt").readText()
        assertTrue("TradeHistoryStore must expose bounded snapshots for UI/reporting", store.contains("fun getRecentValidTrades") && store.contains("fun getRecentValidClosedTrades") && store.contains("fun getLatestBuyByMintSnapshot") && store.contains("fun getRecentTradeFingerprints"))
        assertTrue("MainActivity open-position recovery must not call getAllTrades", main.contains("getLatestBuyByMintSnapshot") && !main.contains("TradeHistoryStore.getAllTrades()"))
        assertTrue("RuntimeDoctor must not materialize the full journal for recent fingerprints", doctor.contains("getRecentTradeFingerprints(50)") && !doctor.contains("TradeHistoryStore.getAllTrades()"))
        assertTrue("Hot diagnostic/learning readers must use bounded closed-trade snapshots", losing.contains("getRecentValidClosedTrades") && regime.contains("getRecentValidClosedTrades") && strategy.contains("getRecentValidClosedTrades") && macro.contains("getRecentValidTrades"))
        assertTrue("Journal/Learning UI screens must not copy the full in-memory trade journal", journalActivity.contains("getRecentValidTrades(5_000)") && learningCounter.contains("getStatsCached().totalStoredTrades") && !journalActivity.contains("TradeHistoryStore.getAllTrades()") && !learningCounter.contains("TradeHistoryStore.getAllTrades()"))
    }
    @Test
    fun paper_to_live_transfer_uses_executable_net_edge_not_gross_paper_pct() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Terminal paper sells must charge live-like round-trip friction plus learned route slip", exec.contains("executable-live paper friction") && exec.contains("expectedExtraSlipPct(ts.lastLiquidityUsd)") && exec.contains("val simulatedFeePct = (1.6 + expectedRouteSlipPct"))
        assertTrue("Paper terminal SELL rows must carry feeSol/netPnlSol into journal and learning", exec.contains("val simulatedFeeSol") && exec.contains("feeSol = simulatedFeeSol") && exec.contains("netPnlSol = pnl"))
        assertTrue("Legacy journal consumers must receive net-normalized pnlPct before TradeHistoryStore", exec.contains("paper→live transfer authority") && exec.contains("PAPER_LIVE_TRANSFER_NET_PCT_NORMALIZED") && exec.indexOf("paper→live transfer authority") < exec.indexOf("TradeHistoryStore.recordTrade(tradeWithMint)"))
        assertTrue("Partial net pct must use sold-leg basis, not full position cost", exec.contains("val isPartialClose = tradeWithMint.side.equals(\"PARTIAL_SELL\", true)") && exec.contains("Partial SELL rows use sol as the sold-leg cost basis"))
        assertTrue("Canonical rich publish must agree with the net-normalized legacy row", exec.contains("tradeWithMint has already been normalized") && exec.contains("realizedPnlPct = pnl"))
        assertFalse("Live SmartSizer must not learn wins from gross pre-fee PnL", exec.contains("SmartSizer.recordTrade(pnlSol > 0, isPaperMode = false)") || exec.contains("SmartSizer.recordTrade(livePnl > 0, isPaperMode = false)") || exec.contains("SmartSizer.recordTrade(pnl > 0, isPaperMode = false)"))
        assertTrue("Live SmartSizer must learn from realized netPnl", exec.contains("SmartSizer.recordTrade(netPnl > 0, isPaperMode = false)"))
    }
    @Test
    fun mainactivity_debug_tiles_do_not_block_oncreate_or_read_registries_on_main() {
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue("Floating debug tiles must be deferred beyond onCreate startup", main.contains("do not construct floating debug tiles inside onCreate") && main.contains("}, 2_000L)"))
        assertTrue("Universe registry counts must run on IO, not inside the main handler frame", main.contains("lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO)") && main.contains("DynamicAltTokenRegistry.getTokenCount()") && main.contains("withContext(kotlinx.coroutines.Dispatchers.Main)"))
        assertTrue("Universe updater must not fire immediately during first startup frame", main.contains("handler.postDelayed(updater, 3_000L)"))
    }
    @Test
    fun strategy_hypothesis_does_not_mutate_bleeder_lanes_in_hostile_regimes() {
        val hyp = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue("Hypothesis engine must suppress DUMP/CHOP variants for known bleeder lanes", hyp.contains("suppressVariantForContext") && hyp.contains("HYPOTHESIS_HOSTILE_BLEEDER_VARIANT_SUPPRESSED") && hyp.contains("r.contains(\"DUMP\") || r.contains(\"CHOP\")") && hyp.contains("MOONSHOT") && hyp.contains("SHITCOIN") && hyp.contains("LaneToxicityGuard.isNetNegativeDanger") && hyp.contains("return 1.0"))
    }
    @Test
    fun weak_chop_pivots_toolkit_away_from_degen_express_scalps_without_blocking() {
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue("Toolkit must inspect cached RegimeDetector state only inside async sheet build", sheet.contains("val regime = try { RegimeDetector.current()"))
        assertTrue("Weak CHOP/DUMP must penalize degen and volume/express-like scalps", sheet.contains("Setup.DEGEN_MICRO_SNIPE -> -18.0") && sheet.contains("Setup.VOLUME_IGNITION_SCALP -> -10.0") && sheet.contains("Setup.PUMP_GRADUATION_SNIPE -> -12.0"))
        assertTrue("Weak CHOP/DUMP must prefer pullback/quality/defensive structures", sheet.contains("Setup.CHART_PULLBACK_RECLAIM -> 10.0") && sheet.contains("Setup.LIQUIDITY_DEPTH_QUALITY -> 8.0") && sheet.contains("Setup.REGIME_DEFENSIVE_PROBE -> 6.0"))
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        assertTrue("Regime pivot must remain a soft score bias and visible in toolkit reasons", sheet.contains("regimeSetupBias(it.setup, regime)") && sheet.contains("regimeBias="))
        assertTrue("Router must remap weak-CHOP/DUMP degen/fresh styles before primary lane election even on fallback sheets", router.contains("weakChopStylePivot") && router.contains("isWeakRuntimeRegime") && router.contains("RegimeDetector.Regime.DUMP") && router.contains("Style.DEGEN_MICRO_SNIPE") && router.contains("Style.DEFENSIVE_PROBE") && router.contains("Style.DIAMOND_HANDS_RUNNER") && router.contains("Style.LIQUIDITY_DEPTH_QUALITY") && router.contains("weakChopSheet && classification.tradeType == ModeRouter.TradeType.FRESH_LAUNCH"))
        assertTrue("Fallback toolkit sheet must not emit degen fresh-launch style in weak runtime regime", sheet.contains("weakRegime") && sheet.contains("Setup.REGIME_DEFENSIVE_PROBE") && sheet.contains("regime=weak_runtime"))
        assertFalse("Regime toolkit pivot must not hard-block or disable lanes", sheet.contains("disableLane") || sheet.contains("shouldTrade = false") || sheet.contains("BLOCK_"))
    }

    @Test
    fun net_negative_danger_bucket_reroutes_lane_exposure_without_trade_block() {
        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneToxicityGuard.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

        assertTrue("Guard must key toxicity on matured net-negative danger buckets, not raw WR alone", guard.contains("s.isDangerous && s.meanPnl <= -8.0"))
        assertTrue("Guard must reroute only when alternatives exist", guard.contains("chooseNonToxicLane") && guard.contains("filterNonToxic") && guard.contains("return lanes.firstOrNull"))
        assertTrue("Agentic style primary/alternate lane election must avoid toxic buckets when possible", router.contains("LaneToxicityGuard.chooseNonToxicLane") && router.contains("LaneToxicityGuard.filterNonToxic") && router.contains("boundedLanes(ts.mint, base + d.toolkit.laneVotes, d.style, score)"))
        assertTrue("MemeTrader owner rotation must avoid toxic lanes when possible", bot.contains("scoreForToxicity") && bot.contains("LaneToxicityGuard.filterNonToxic(rawOwnerPool") && bot.contains("ownerPool"))
        assertTrue("FDG train-first micro/size shaping remains the downstream fallback, not a hard strategy block", fdg.contains("TRAIN_FIRST_MICRO") && fdg.contains("LosingPatternMemory.recommendedSizeMult"))
        assertFalse("Toxicity guard must not disable lanes or hard-block trades", guard.contains("BLOCK") || guard.contains("disableLane") || guard.contains("shouldTrade = false"))
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
        assertTrue(hub.contains("TRADE JOURNAL SUMMARY"))
        assertTrue(hub.contains("raw journal rows excluded"))
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
    fun live_buy_signature_confirmation_must_wait_for_authoritative_balance_proof() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("BUY_CONFIRMED_AWAITING_BALANCE_PROOF"))
        assertTrue(exec.contains("completeVerifiedLiveBuyWithProof"))
        assertTrue(exec.contains("SellAmountAuthority.recordTxParseBalance"))
        assertTrue(exec.contains("HostWalletTokenTracker.recordBuyConfirmedWithProof(ts, proof, verifySig)"))
        assertTrue(exec.contains("TOKEN_TRACKER_BUY_CONFIRMED_WITH_PROOF"))
        assertTrue(exec.contains("SELL_AMOUNT_AUTHORITY_SEEDED"))
        assertTrue(exec.contains("BALANCE_PROOF_START"))
        assertTrue(exec.contains("BALANCE_PROOF_OK"))
        assertTrue(java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText().contains("countStaleBuyPendingBalanceProof"))
        assertFalse("Live buy must not become sellable from signature alone", exec.contains("provisional.copy(pendingVerify = false)"))
        assertFalse("Live buy final success path must not call legacy pending-only tracker", exec.contains("HostWalletTokenTracker.recordBuyConfirmed(ts, sig)"))
        assertFalse("Late rescue must not use legacy tracker", exec.contains("HostWalletTokenTracker.recordBuyConfirmed(ts, verifySig)"))
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
        assertTrue(exec.contains("SELL_ABORT_TRACKER_CLOSED_NO_CURRENT_HELD_PROOF_TERMINAL"))
        assertTrue(exec.contains("PositionCloseLedger.isClosed(ts.mint)"))
        assertTrue(exec.contains("CLOSED_SOLD_BY_AATE"))
        assertTrue(exec.contains("CLOSED_EXTERNALLY_MANUAL_SWAP"))
        assertTrue(exec.contains("PendingSellQueue.remove(ts.mint)"))
        assertTrue(exec.contains("return SellResult.ALREADY_CLOSED"))
        assertTrue(exec.contains("SELL_PAUSED_TRACKER_CLOSED_NO_CURRENT_HELD_PROOF"))
    }


    @Test
    fun meme_only_mode_keeps_internal_style_toolkit_alive_but_bounded() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("INTERNAL_TOOLKIT_STARVATION_FIX"))
        assertTrue(bot.contains("BOUNDED_INTERNAL_TOOLKIT_RESCUE"))
        assertTrue(bot.contains("PAPER_WR_DILUTION_FIX"))
        assertTrue(bot.contains("MEMETRADER_CONTRIBUTION_ROTATION"))
        assertTrue(bot.contains("val fullMemeTraderRing = listOf"))
        assertTrue("Full MemeTrader ring must include previously idle internal lanes", listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "QUALITY", "DIP_HUNTER", "TREASURY", "BLUECHIP").all { bot.contains(it) })
        assertTrue("Owner rotation must be affinity-first and toxicity-filtered", bot.contains("affinityRanked") && bot.contains("rawOwnerPool") && bot.contains("LaneToxicityGuard.filterNonToxic(rawOwnerPool"))
        assertTrue("EXPRESS must use the same bounded lane gate and emit LANE_EVAL", bot.contains("expressLaneAllowedThisCycle") && bot.contains("lane=EXPRESS paper="))
        assertTrue(bot.contains("MEMETRADER_OWNER_LANE"))
        assertFalse("MEME-only must not blanket-mute all non-meme specialist lanes", bot.contains("return memeFamily"))
        assertFalse("toolkit alive must not mean all meme-family siblings execute", bot.contains("if (memeFamily) return true"))
    }



    @Test
    fun live_sell_rejects_txparse_and_recalculates_at_every_processor_boundary() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val amountPlanningSurface = exec + "\n" + planner

        assertTrue(exec.contains("SELL_QTY_SOURCE=BALANCE_UNKNOWN") || exec.contains("WALLET_TOKEN_READ_INDETERMINATE"))
        assertTrue(amountPlanningSurface.contains("PROCESSOR_AMOUNT_RECALCULATED"))
        assertTrue("Executor must delegate sell planning to ProcessorAmountPlanner", exec.contains("ProcessorAmountPlanner.planSell("))
        assertTrue("ProcessorAmountPlanner must own confirmed sell formatting", planner.contains("fun planSellFromConfirmed"))
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
    fun meme_only_internal_toolkit_is_bounded_to_one_owner_lane() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("MEMETRADER_CONTRIBUTION_ROTATION"))
        assertTrue(bot.contains("exactly ONE owner"))
        assertTrue(bot.contains("val allowed = l == ownerLane"))
        assertTrue(bot.contains("return allowed"))
        assertTrue(bot.contains("val fullMemeTraderRing = listOf"))
        assertFalse("owner rotation must not require pre-existing affinity", bot.contains("nonMemeSpecialist && affinity.contains(l)"))
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
        assertTrue("Router may read memoized RegimeDetector.current() for direct DUMP authority, but must document it as memoized", router.contains("RegimeDetector.current()") && router.contains("memoized by RegimeDetector"))
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
    fun keepalive_rebound_must_not_reset_session_counters_or_fake_throughput() {
        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val reboundIdx = service.indexOf("LIFECYCLE_RUNTIME_JOB_ALREADY_EXISTS")
        val reboundChunk = service.substring(maxOf(0, reboundIdx - 900), minOf(service.length, reboundIdx + 500))
        assertFalse("already-running keepalive repair must not reset mode counters", reboundChunk.contains("resetModeCountersForRuntime"))
        assertTrue("already-running keepalive repair may refresh mode snapshot only", reboundChunk.contains("PipelineHealthCollector.modeSnapshot"))
        assertTrue("throughput projection must use accepted journal rows, not capped recent exec ring", collector.contains("acceptedJournalRows") && collector.contains("not 30-row ring"))
        assertFalse("projection must not divide the 30-row recent ring by uptime", collector.contains("val execsPerHour = recentExecCount / uptimeHr"))
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
        assertTrue("selector should tolerate timeout noise below the planner pressure band", service.contains("val lowTimeoutNoise = supervisorTimeoutsForPlanning < 30") && service.contains("selectorHealthy = lowTimeoutNoise"))
        assertTrue("degraded selector must cap forced-open supervisor prefix", service.contains("pressure == \"healthy\" && !selectorHealthy -> maxOf(6, PER_CYCLE_CAP / 2)"))
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
        assertTrue(store.contains("LearningPnlSanitizer.inspectTrade(t, \"TradeHistoryStore.isValidAccountingTrade\", emit = false)"))
        assertFalse("canonical accounting must not keep the obsolete +100000% poison ceiling", store.contains("t.pnlPct > 100_000.0"))
        assertTrue(store.contains("TRADE_ACCOUNTING_QUARANTINED"))
        assertTrue(store.contains("return synchronized(lock) { trades.filter { isValidAccountingTrade(it) }.toList() }"))
        assertTrue(store.contains("TRADE_ACCOUNTING_LEGACY_ROW_FILTERED"))
        assertTrue(store.contains("TRADE_ACCOUNTING_BULK_QUARANTINED"))
        assertTrue(store.contains("TRADE_ACCOUNTING_DB_INIT_FILTERED"))
        assertTrue(store.contains("TRADE_ACCOUNTING_PREFS_MIGRATION_FILTERED"))
        assertTrue(store.contains("trades.filter { it.ts >= midnight && isValidAccountingTrade(it) }"))
        assertFalse("invalid accounting rows must not still be persisted after warning", store.contains("PARTIAL_SELL_INVALID_ACCOUNTING mint="))

        val journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeJournal.kt").readText()
        val journalActivity = java.io.File("src/main/kotlin/com/lifecyclebot/ui/JournalActivity.kt").readText()
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
        assertTrue(snapshot.contains("HostWalletTokenTracker.getActuallyHeldMints()"))
        assertTrue(snapshot.contains("val liveOpen = maxOf(localLiveOpen, hostOpen, lifecyclePendingConfirmed)"))
        assertTrue(snapshot.contains("confirmed buy signature with"))

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
        assertTrue(auth.contains("WALLET_TOKEN_READ_INDETERMINATE"))
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

        assertFalse("Executor must not keep duplicate buy plan wrapper", exec.contains("data class ProcessorBuyPlan"))
        assertTrue(planner.contains("data class BuyPlan"))
        assertTrue(exec.contains(": ProcessorAmountPlanner.BuyPlan?"))
        assertTrue(amountPlanningSurface.contains("BUY_PROCESSOR_AMOUNT_RECALCULATED"))
        assertTrue(exec.contains("ProcessorAmountPlanner.planBuy"))
        listOf(
            "PUMPPORTAL_BUY", "PUMPPORTAL_BUY_INTERNAL", "JUPITER_ULTRA_METIS_BUY",
            "PUMPPORTAL_TOP_UP", "JUPITER_ULTRA_METIS_TOP_UP"
        ).forEach { label -> assertTrue("missing buy processor recalc label: $label", amountPlanningSurface.contains(label)) }

        assertTrue(amountPlanningSurface.contains("PumpPortal") || amountPlanningSurface.contains("PUMPPORTAL"))
        assertTrue(amountPlanningSurface.contains("Jupiter Ultra") || amountPlanningSurface.contains("JUPITER_ULTRA"))
        assertTrue("ProcessorAmountPlanner must refresh wallet SOL before senders quote/build", planner.contains("wallet.getSolBalance()"))
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
        assertTrue(sellAuth.contains("WALLET_TOKEN_READ_INDETERMINATE"))
        assertFalse("RPC empty must not fall back to TX_PARSE confirmed balance", sellAuth.contains("return tryFreshTxParseFallback(mint) ?: Resolution.Unknown"))
        assertFalse("TX_PARSE must not be broadcast bypass", sellAuth.contains("TX_PARSE_BROADCAST_BYPASS"))

        assertTrue(tracker.contains("STALE_RECOVERY_UNPROVEN"))
        assertTrue(tracker.contains("NO_CURRENT_HELD_PROOF"))
        assertTrue(tracker.contains("recordBuyConfirmedWithProof"))
        assertTrue(tracker.contains("CLOSED_REJECTED_NO_SIGNATURE_NO_ZERO_PROOF"))
        assertFalse("No-signature txparse must not stamp CLOSED", tracker.contains("CLOSED_BY_TX_PARSE_NO_SIGNATURE"))

        assertTrue(exec.contains("OWNER_DELTA_PROOF"))
        assertFalse(exec.contains("OPEN_SELL_FAILED_NO_SIGNATURE_RETRYING"))
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
        assertTrue(exec.contains("ProcessorAmountPlanner.planSell("))
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
        assertTrue(sellAuthority.contains("WALLET_TOKEN_READ_INDETERMINATE"))
        assertTrue(sellAuthority.contains("BALANCE_UNKNOWN reason=MINT_ABSENT_FROM_ONE_PROVIDER"))
        assertTrue(sellAuthority.contains("TRUSTED_WALLET_ZERO"))
        assertTrue(sellAuthority.contains("BALANCE_RPC_CONFIRMED_DUST_ZERO"))
        assertFalse("trusted current wallet zero must not stay unknown", sellAuthority.contains("BALANCE_UNKNOWN reason=ONE_PROVIDER_ZERO"))
        assertTrue(sellAuthority.contains("STALE_TRACKER_RAW_NOT_CURRENT_WALLET_AUTHORITY"))
        assertTrue(sellAuthority.contains("HostWalletTokenTracker.getEntry"))
        assertFalse("one provider missing mint must not be zero", sellAuthority.contains("mint NOT in the map AND map is non-empty → genuine zero"))

        assertTrue(tracker.contains("markNoCurrentHeldProof"))
        assertTrue(tracker.contains("RPC_EMPTY_MAP_MINT_ABSENT"))
        assertTrue(tracker.contains("NO_CURRENT_HELD_PROOF"))
        assertTrue(tracker.contains("HISTORICAL_RAW_NOT_CURRENT_HELD_PROOF"))
        assertTrue(tracker.contains("STALE_RECOVERY_UNPROVEN"))
        assertFalse("no current proof must not become open recovery", tracker.contains("OPEN_BALANCE_UNKNOWN_RECOVERY_REQUIRED"))
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

        assertFalse(exec.contains("OPEN_SELL_FAILED_NO_SIGNATURE_RETRYING"))
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
        assertTrue(auth.contains("val laneSet = set - Trader.CRYPTO_ALT - Trader.CYCLIC"))
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
    fun live_sell_does_not_use_buy_tied_owner_delta_as_current_wallet_authority() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        assertTrue(authority.contains("BUY_TX_META_NOT_CURRENT_WALLET_AUTHORITY"))
        assertTrue(authority.contains("Source.TX_META_OWNER_DELTA -> BalanceSource.UNKNOWN"))
        assertFalse("buy-tied tx-meta must not be returned as confirmed sell balance",
            authority.contains("Resolution.Confirmed(cached.rawAmount, cached.decimals, Source.TX_META_OWNER_DELTA)"))
        assertFalse("tx-meta must not be wallet-scan confirmed",
            authority.contains("Source.TX_META_OWNER_DELTA -> BalanceSource.WALLET_SCAN_CONFIRMED"))
        assertTrue(exec.contains("SellAmountAuthority.resolveForExit(ts.mint, wallet, reason)"))
        assertTrue(exec.contains("SellAmountAuthority.canBroadcastLiveOrEmergency"))
        assertTrue(exec.contains("exitReason: String = processor"))
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
        assertTrue(exec.contains("LIVESELL_WALLET_READ_INDETERMINATE"))
        assertTrue(exec.contains("REQUEST_SELL_BALANCE_WAIT_MERGE"))
        assertTrue(exec.contains("REQUEST_SELL_BALANCE_WAIT_PROOF_READY"))
        assertTrue(exec.contains("BalanceProofWaitState.clear(ts.mint, \"PROOF_READY_REQUESTSELL\")"))
        assertTrue(exec.indexOf("LIVESELL_RPC_EMPTY_OWNER_DELTA_RECOVERED") < exec.indexOf("LIVESELL_WALLET_READ_INDETERMINATE"))
    }



    @Test
    fun runtime_doctor_does_not_call_sell_path_dead_when_live_sells_are_journaling() {
        val inv = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()
        assertTrue("SELL_RECONCILER_DEAD must be proof-aware, not a stale flag only", inv.contains("liveSellPathHasProof") && inv.contains("TRADEJRNL_REC_LIVE") && inv.contains("!liveSellPathHasProof"))
    }
    @Test
    fun live_balance_authority_and_reconciler_contracts_are_wired() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val inv = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()

        assertTrue("empty wallet map must stay UNKNOWN", authority.contains("if (balances.isEmpty())"))
        assertTrue(authority.contains("return Resolution.Unknown"))
        assertTrue("non-empty map mint miss is the zero path", authority.contains("MINT_ABSENT_FROM_ONE_PROVIDER"))
        assertTrue(exec.contains("SELL_QTY_SOURCE=BALANCE_UNKNOWN"))
        assertTrue(exec.contains("CloseLease.release(ts.mint, \"BALANCE_UNKNOWN_NO_SIGNATURE\")"))
        assertTrue(bot.contains("SellReconciler.start"))
        assertTrue(bot.contains("sellTrigger = { mint, symbol, balance ->"))
        assertTrue(bot.contains("LiveWalletReconciler.start { WalletManager.getWallet() }"))
        assertTrue(inv.contains("reconciler.totalChecked=0 while canonicalOpen"))
        assertTrue(inv.contains("BUY_PENDING_BALANCE_PROOF_STALE"))
        assertTrue(inv.contains("staleBuyPendingBalanceProof > 0"))
    }


    @Test
    fun balance_proof_zero_finality_sets_tracker_independent_zero_before_close() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val poller = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofPoller.kt").readText()
        val reconciler = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveWalletReconciler.kt").readText()

        assertTrue(poller.contains("ZERO_BALANCE_CONFIRMED"))
        assertTrue(bot.contains("recordIndependentZeroBalanceProof"))
        val zeroCallback = bot.substring(bot.indexOf("recordIndependentZeroBalanceProof")).take(900)
        assertTrue(zeroCallback.contains("recordIndependentZeroBalanceProof"))
        assertTrue(zeroCallback.contains("confirmZeroBalanceClose"))
        assertTrue(zeroCallback.indexOf("recordIndependentZeroBalanceProof") < zeroCallback.indexOf("confirmZeroBalanceClose"))
        assertTrue(tracker.contains("fun recordIndependentZeroBalanceProof"))
        assertTrue(tracker.contains("zeroBalanceConfirmedByTwoProviders = true"))
        assertTrue(tracker.contains("INDEPENDENT_ZERO_BALANCE_PROOF"))
        assertTrue(reconciler.contains("RECONCILER_ABSENT_TRACKED_CHECKED"))
    }


    @Test
    fun live_buy_clamps_to_wallet_and_min_executable_before_insufficient_balance() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("LIVE_BUY_SIZE_CLAMPED_TO_WALLET"))
        assertTrue(exec.contains("LIVE_BUY_SIZE_RAISED_TO_MIN_EXECUTABLE"))
        assertTrue(exec.contains("liveMinExecutableBuySol = 0.005"))
        assertTrue(exec.contains("liveRentReserveSol = 0.012"))
        assertFalse("Live buy must not reject walletSol < sol before rent-reserve clamp", exec.contains("if (walletSol < sol)"))
        assertTrue(exec.indexOf("maxSpendableSol") < exec.indexOf("val lamports = (effectiveSol"))
    }


    @Test
    fun confirmed_buy_pending_wallet_proof_stays_visible_and_sell_managed() {
        val models = java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()
        val host = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val lifecycle = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenLifecycleTracker.kt").readText()
        val snap = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()

        assertTrue(models.contains("confirmed live buys must be visible/sell-managed"))
        assertTrue(models.contains("return true"))
        assertTrue(host.contains("CONFIRMED_PENDING_BALANCE"))
        assertTrue(host.contains("qtySource=ESTIMATED_PENDING_WALLET_PROOF"))
        assertTrue(host.contains("hasFreshBuyLiability"))
        assertTrue(host.contains("CAP_FRESH_BUY_LIABILITY_MS"))
        assertTrue(host.contains("isCapCountable(p)"))
        assertFalse("pending visibility must not depend on stale raw as cap truth", host.contains("isOpenForAccounting(it) && hasLastPositiveRaw(it)"))
        assertTrue(lifecycle.contains("CONFIRMED_PENDING_BALANCE"))
        assertFalse("liveMemeOpenCount must not require positive wallet qty only", lifecycle.contains("r.currentWalletTokenQty > DUST_UI_THRESHOLD &&\n                r.status != Status.RECONCILE_FAILED"))
        assertTrue(snap.contains("maxOf(localLiveOpen, hostOpen, lifecyclePendingConfirmed)"))
        assertTrue(snap.contains("maxOf(walletHeld, hostOpen, localLiveOpen, lifecyclePendingConfirmed)"))
        assertTrue(doctor.contains("RECONCILER_BLIND_CRITICAL"))
        assertTrue(doctor.contains("LIVE_BUY_CONFIRMED_NOT_VISIBLE_CRITICAL"))
    }


    @Test
    fun wallet_token_rpc_uses_valid_getTokenAccountsByOwner_shape_and_never_timeout_empty() {
        val wallet = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(wallet.contains("getTokenAccountsWithDecimalsStrict"))
        assertTrue(wallet.contains("WALLET_TOKEN_READ_INDETERMINATE"))
        assertTrue(wallet.contains("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"))
        assertFalse("do not resurrect the fake Token-2022 program id", wallet.contains("TokenzQdBNbequivDy2Cv5VhM9xAZWQ8HHv2Q3ZUVV1"))
        assertTrue(wallet.contains(".put(JSONObject().put(\"programId\", programId))"))
        assertTrue(wallet.contains(".put(\"encoding\", \"jsonParsed\")"))
        assertFalse("bounded wallet token reads must never manufacture empty wallet on timeout", wallet.contains("returning empty map (RPC-EMPTY rescue path)"))
        assertFalse("bounded timeout must throw indeterminate, not emptyMap", wallet.contains("catch (_: java.util.concurrent.TimeoutException)"))
        assertTrue(wallet.contains("throw RuntimeException(\"wallet token snapshot timeout"))
    }


    @Test
    fun live_transaction_fee_authority_uses_dynamic_sender_floor_everywhere() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val helperIdx = exec.indexOf("effectiveJitoTipLamports")
        assertTrue("fee authority helper missing", helperIdx >= 0)
        val helperEnd = exec.indexOf("private fun recalcBuyPlanForProcessor", helperIdx).takeIf { it > helperIdx } ?: exec.length
        val withoutHelper = exec.removeRange(helperIdx, helperEnd)
        assertFalse("live builders must not pass raw config jito tip", withoutHelper.contains("jitoTipLamports = c.jitoTipLamports"))
        assertFalse("live builders must not use static 200k without dynamic tip", withoutHelper.contains("maxOf(c.jitoTipLamports, 200_000L)"))
        assertFalse("live builders must not call dynamic tip ad hoc", withoutHelper.contains("getDynamicTip(c.jitoTipLamports)"))
        assertTrue(exec.contains("maxOf(dynamic, c.jitoTipLamports, 200_000L)"))
        assertTrue(exec.contains("effectiveJitoTipLamports(c, urgent = isDrainExit)"))
        assertTrue(exec.contains("effectiveJitoTipLamports(c, urgent = true)"))
    }


    @Test
    fun mux_report_recent_exec_rows_expose_lifecycle_entry_snapshot() {
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue("recent exec rows must carry canonical positionId", collector.contains("val positionId: String = \"\""))
        assertTrue("recent exec rows must carry lane/mode-local attribution", collector.contains("val lane: String = \"\""))
        assertTrue("recent exec rows must carry entry snapshot", collector.contains("val entryPriceSnapshot: Double = 0.0"))
        assertTrue("recent exec rows must render pid", collector.contains(" pid=") && collector.contains("positionId.takeLast"))
        assertTrue("recent exec rows must render entry cost/qty/source", collector.contains(" cost=") && collector.contains(" qty=") && collector.contains(" src="))
        assertTrue("TradeHistoryStore must pass canonical positionId into mux report", store.contains("positionId = tradeToStore.positionId"))
        assertTrue("TradeHistoryStore must pass buy snapshot into mux report", store.contains("entryPriceSnapshot = tradeToStore.entryPriceSnapshot") && store.contains("entryCostSol = tradeToStore.entryCostSol"))
        assertFalse("mux report must not infer lifecycle only from mint/time", collector.contains("positionId = \"${'$'}{trade.ts}_${'$'}{trade.mint}\""))
    }

    @Test
    fun failed_tactic_pivots_seed_paper_lab_without_live_authority() {
        val switcher = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/lab/LlmLabEngine.kt").readText()
        assertTrue(switcher.contains("AUTONOMOUS_LAB_PIVOT_SEED"))
        assertTrue(switcher.contains("seedFromTacticFailure"))
        assertTrue(lab.contains("AUTONOMOUS_LAB_PIVOT_SEED"))
        assertTrue(lab.contains("fun seedFromTacticFailure"))
        assertTrue(lab.contains("status = LabStrategyStatus.ACTIVE"))
        assertTrue(lab.contains("sizingSol = 0.05"))
        assertTrue(lab.contains("ACTIVE lab paper experiment only; not promoted/live-authorized"))
        val seedStart = lab.indexOf("fun seedFromTacticFailure")
        val seedEnd = lab.indexOf("/** Permanently delete all archived strategies. */", seedStart).takeIf { it > seedStart } ?: lab.length
        val seedFn = lab.substring(seedStart, seedEnd)
        assertTrue("autopivot lab seed must create an ACTIVE paper experiment", seedFn.contains("status = LabStrategyStatus.ACTIVE"))
        assertFalse("autopivot lab seed must not auto-promote", seedFn.contains("LabStrategyStatus.PROMOTED"))
        assertFalse("autopivot lab seed must not request live approval", seedFn.contains("requestSingleLiveTrade") || seedFn.contains("addApproval") || seedFn.contains("grantLiveAuthority"))
        assertFalse("autopivot lab seed must not call an LLM", seedFn.contains("GeminiCopilot") || seedFn.contains("rawText"))
        assertFalse("autopivot lab seed must not call the main executor", seedFn.contains("executor.") || seedFn.contains("shitCoinBuy") || seedFn.contains("blueChipBuy"))
    }


    @Test
    fun tactic_switcher_pivots_strategies_never_disables_lanes() {
        val switcher = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(switcher.contains("POST-PIVOT FAIL-FAST"))
        assertTrue(switcher.contains("POST_PIVOT_FAST_MIN_SAMPLES = 4"))
        assertTrue(switcher.contains("post-pivot-fast"))
        assertTrue(switcher.contains("Rotate again; never"))
        assertFalse("tactic switcher must not disable lanes", switcher.contains("DISABLE_LANE") || switcher.contains("enabled = false") || switcher.contains("return false"))
    }


    @Test
    fun lane_exit_tuner_tightens_low_wr_no_runner_bleeders() {
        val tuner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/LaneExitTuner.kt").readText()
        assertTrue(tuner.contains("low-WR/no-runner bleed fix"))
        assertTrue(tuner.contains("wr < 0.20 && avgReal < 0.0 && avgPeak < 15.0 -> sl -= STEP * 2.0"))
        assertTrue(tuner.contains("val slCap = if (wr < 0.20 && avgReal < 0.0 && avgPeak < 15.0) 1.0 else SL_MAX"))
        assertFalse("low-WR no-runner lanes must not widen stops", tuner.contains("slHitRate >= 0.50 && avgPeak < 8.0 -> sl += STEP"))
    }


    @Test
    fun host_tracker_closes_absent_mint_after_two_nonempty_wallet_snapshots() {
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        assertTrue(tracker.contains("absent-mint zero proof ladder"))
        assertTrue(tracker.contains("if (walletMints.isNotEmpty())"))
        assertTrue(tracker.contains("ABSENT_MINT_ZERO_CONFIRM"))
        assertTrue(tracker.contains("p.zeroBalanceConfirmedByTwoProviders = true"))
        assertTrue(tracker.contains("CLOSED_BY_NONEMPTY_WALLET_MINT_ABSENT"))
        assertTrue(tracker.contains("CloseLease.release(mint"))
        assertTrue(tracker.contains("ZERO_BALANCE_CLOSE:"))
        assertTrue(tracker.contains("SellExecutionLocks.release(mint)"))
        assertTrue(tracker.contains("NONEMPTY_WALLET_MINT_ABSENT_ZERO_PENDING"))
        assertTrue(tracker.contains("FRESH_BUY_ABSENT_RECONCILE_DEFERRED"))
        assertFalse("non-empty absent snapshot must not be treated as RPC_EMPTY_MAP forever",
            tracker.contains("REAP_SKIPPED_BALANCE_UNKNOWN mint absent from one wallet snapshot — keeping open"))
    }


    @Test
    fun sell_reconciler_never_treats_indeterminate_wallet_read_as_empty() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellReconciler.kt").readText()
        assertTrue(src.contains("INDETERMINATE IS NOT EMPTY"))
        assertTrue(src.contains("RECONCILER_WALLET_READ_INDETERMINATE_SKIP"))
        assertTrue(src.contains("wallet read indeterminate; skipping zero-close pass"))
        assertFalse("wallet read failure must not synthesize an empty token map",
            src.contains("try { w.getTokenAccountsWithDecimalsBounded() } catch (_: Throwable) { emptyMap() }"))
    }


    @Test
    fun wallet_rpc_has_scoped_tls_fallback_for_android_trust_anchor_failures() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(src.contains("WALLET_RPC_TLS_FALLBACK_USED"))
        assertTrue(src.contains("isTlsTrustFailure"))
        assertTrue(src.contains("Trust anchor"))
        assertTrue(src.contains("unsafeWalletRpcClient"))
    }

    @Test
    fun sell_reconciler_debounces_real_absent_mint_zero_and_records_independent_proof() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellReconciler.kt").readText()
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        assertTrue(src.contains("RECONCILER_ZERO_OBSERVED"))
        assertTrue(src.contains("source=nonempty_wallet_absent"))
        assertTrue(src.contains("SELL_RECONCILER_NONEMPTY_SNAPSHOT"))
        assertTrue(src.contains("MINT_ABSENT_FROM_TOKEN_ACCOUNTS"))
        assertTrue(src.contains("pos.consecutiveZeroConfirms < 2"))
        assertTrue(tracker.contains("trustedTerminalZero"))
        assertTrue(tracker.contains("SELL_RECONCILER_NONEMPTY_SNAPSHOT"))
        assertTrue(tracker.contains("LIVE_POSITION_CLOSE_AUTHORITY"))
        assertTrue(tracker.contains("SELL_SIGNATURE_OR_META"))
    }


    @Test
    fun live_position_close_authority_blocks_duplicate_resell_after_broadcast() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LivePositionCloseAuthority.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val jobs = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellJobRegistry.kt").readText()
        val queue = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PendingSellQueue.kt").readText()
        assertTrue(authority.contains("enum class State { OPEN_CONFIRMED, CLOSING_PENDING_SIG, CLOSING_UNKNOWN, CLOSING_CONFIRMED, CLOSED }"))
        assertTrue(authority.contains("fun preSellGuard"))
        assertTrue(authority.contains("fun markBroadcast"))
        assertTrue(authority.contains("fun finalizeClosed"))
        assertTrue(authority.contains("SELL_FINALIZED_ONCE"))
        assertTrue(executor.contains("REQUEST_SELL_SUPPRESSED_CLOSE_AUTHORITY"))
        assertTrue(executor.contains("LivePositionCloseAuthority.markBroadcast"))
        assertTrue(executor.contains("SELL_RETRY_SUPPRESSED_BROADCAST_PENDING_PROOF"))
        assertTrue(jobs.contains("CLOSING_UNKNOWN"))
        assertTrue(jobs.contains("STALE_SELL_LOCK_PROOF_REQUIRED"))
        assertTrue(queue.contains("PENDING_SELL_SUPPRESSED_CLOSING"))
        assertTrue(queue.contains("PENDING_SELL_PURGED_CLOSING_OR_CLOSED"))
    }

    @Test
    fun sell_lock_release_does_not_make_broadcasted_mint_retryable() {
        val jobs = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellJobRegistry.kt").readText()
        assertTrue(jobs.contains("LivePositionCloseAuthority.markClosingUnknown"))
        assertTrue(jobs.contains("BROADCASTING, SellJobStatus.CONFIRMING, SellJobStatus.VERIFYING, SellJobStatus.CLOSING_UNKNOWN"))
        assertTrue(jobs.contains("Only pre-broadcast BUILDING jobs may become retryable"))
    }


    @Test
    fun android_network_security_config_is_wired_for_wallet_rpc_trust() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        val net = java.io.File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(net.contains("<certificates src=\"system\""))
        assertTrue(net.contains("<certificates src=\"user\""))
        assertTrue(net.contains("helius-rpc.com"))
        assertTrue(net.contains("solana.com"))
    }

    @Test
    fun sell_reconciler_zero_close_flows_through_live_position_close_authority() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellReconciler.kt").readText()
        assertTrue(src.contains("LivePositionCloseAuthority.finalizeClosed"))
        assertTrue(src.contains("RECONCILER_SELL_SIG_ZERO"))
        assertTrue(src.contains("RECONCILER_WALLET_ZERO"))
    }


    @Test
    fun balance_unknown_sell_wait_does_not_emit_no_signature_retrying() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        assertTrue(executor.contains("markSellWaitingBalanceProof"))
        assertFalse("balance unknown must not reopen tracker as no-signature retry",
            executor.contains("markSellNoSignatureUnlocked(ts.mint, ts.symbol, \"BALANCE_UNKNOWN"))
        assertTrue(tracker.contains("fun markSellWaitingBalanceProof"))
        assertTrue(tracker.contains("SELL_WAITING_BALANCE_PROOF_TRACKER"))
        assertTrue(tracker.contains("no_signature_counter=false"))
    }

    @Test
    fun balance_proof_ready_is_cached_for_next_sell_attempt() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val poller = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofPoller.kt").readText()
        assertTrue(authority.contains("PROOF_READY_CACHE_MS"))
        assertTrue(authority.contains("fun recordProofReady"))
        assertTrue(authority.contains("consumeProofReady"))
        assertTrue(authority.contains("BALANCE_PROOF_READY_CONSUMED"))
        assertTrue(poller.contains("SellAmountAuthority.recordProofReady"))
    }

    @Test
    fun wallet_authority_skips_cert_broken_public_rpc_endpoint() {
        val wallet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/WalletManager.kt").readText()
        assertTrue(wallet.contains("sanitizeWalletRpcUrl"))
        assertTrue(wallet.contains("WALLET_RPC_ENDPOINT_SKIPPED_BAD_TLS"))
        assertFalse("cert-broken public-rpc must not remain in fallback authority rotation",
            wallet.contains("\"https://solana.public-rpc.com\",                    // Public RPC"))
    }


    @Test
    fun wallet_snapshot_requires_spl_but_token2022_is_additive() {
        val wallet = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(wallet.contains("rpcTokenAccountsByOwnerFast"))
        assertTrue(wallet.contains("walletRpcEndpointsForTokenSnapshot"))
        assertTrue(wallet.contains("if (!splProgramOk)"))
        assertTrue(wallet.contains("WALLET_TOKEN_2022_OPTIONAL_FAILED"))
        assertTrue(wallet.contains("action=continue_with_spl"))
        assertFalse("Token-2022 failure must not poison normal SPL wallet proof",
            wallet.contains("if (successCount < 2)"))
    }


    @Test
    fun wallet_snapshot_has_das_fallback_when_tokenkeg_rpc_fails() {
        val wallet = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(wallet.contains("heliusDasFungibleTokensByOwner"))
        assertTrue(wallet.contains("getAssetsByOwner"))
        assertTrue(wallet.contains("showFungible"))
        assertTrue(wallet.contains("WALLET_TOKEN_READ_DAS_FALLBACK_USED"))
        assertTrue(wallet.contains("out.putAll(das)"))
        assertTrue(wallet.contains("splProgramOk = true"))
    }

    @Test
    fun legacy_wallet_token_readers_delegate_to_strict_authority() {
        val wallet = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        val getTokensBody = Regex("""fun getTokenAccounts\(\): Map<String, Double> \{([\s\S]*?)\n    \}""").find(wallet)?.groupValues?.get(1) ?: ""
        val checkedBody = Regex("""fun getTokenAccountsChecked\(\): WalletTokenSnapshot \{([\s\S]*?)\n    \}""").find(wallet)?.groupValues?.get(1) ?: ""
        assertTrue(getTokensBody.contains("getTokenAccountsWithDecimalsStrict"))
        assertTrue(checkedBody.contains("getTokenAccountsWithDecimalsStrict"))
        assertFalse("legacy getTokenAccounts must not keep its own getTokenAccountsByOwner duplicate", getTokensBody.contains("getTokenAccountsByOwner"))
        assertFalse("checked wallet snapshot must not keep its own getTokenAccountsByOwner duplicate", checkedBody.contains("getTokenAccountsByOwner"))
    }


    @Test
    fun live_entry_price_uses_proof_cost_basis_not_guide_price() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("LIVE_ENTRY_PRICE_FROM_PROOF"))
        assertTrue(exec.contains("(sol / qtyUi) * solUsdForBasis"))
        assertTrue(exec.contains("entryPriceSource = \"LIVE_PROOF_COST_BASIS\""))
        assertTrue(exec.contains("entrySupplyAssumed = 0.0"))
        assertTrue(exec.contains("priceBasisRescaled = true"))
        assertTrue(exec.contains("entryPrice = ts.position.entryPrice.takeIf { it > 0.0 && it.isFinite() } ?: price"))
    }

    @Test
    fun wallet_rehydrate_does_not_synthesize_sol_cost_from_usd_price() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertFalse("wallet recovery must not convert USD token price into SOL cost", exec.contains("val entrySol = if (entryPrice > 0.0) qty * entryPrice else 0.0"))
        assertTrue(exec.contains("WALLET_REHYDRATE_BASIS_UNKNOWN"))
        assertTrue(exec.contains("costSol        = ts.position.costSol.takeIf { it > 0.0 } ?: 0.0"))
    }

    @Test
    fun advanced_exit_invalid_price_holds_not_forced_sell() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/AdvancedExitManager.kt").readText()
        assertTrue(src.contains("ADV_EXIT_INVALID_PRICE_HOLD"))
        assertTrue(src.contains("Invalid price input — hold until trustworthy price"))
        assertFalse("invalid guide/basis price must not force a sell", src.contains("Invalid price input — forced exit"))
        assertFalse("invalid price decision must not return shouldExit=true", src.contains("return ExitDecision(true, 100, ExitReason.INVALID_INPUT"))
    }


    @Test
    fun stale_tracker_raw_is_not_sell_authority() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        assertTrue(authority.contains("STALE_TRACKER_RAW_NOT_CURRENT_WALLET_AUTHORITY"))
        assertTrue(authority.contains("stale tracker raw is visibility only, never sell authority"))
        assertFalse("tracked raw must not be returned as sell authority",
            authority.contains("return Resolution.Confirmed(trackedRaw, tracked.decimals, Source.TX_META_OWNER_DELTA)"))
    }

    @Test
    fun tx_meta_owner_delta_is_not_current_wallet_broadcast_authority() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        assertTrue(authority.contains("Source.TX_META_OWNER_DELTA -> BalanceSource.UNKNOWN"))
        assertTrue(authority.contains("BUY_TX_META_NOT_CURRENT_WALLET_AUTHORITY"))
        assertFalse("buy tx-meta cache must not return confirmed sell authority",
            authority.contains("return Resolution.Confirmed(cached.rawAmount, cached.decimals, Source.TX_META_OWNER_DELTA)"))
    }

    @Test
    fun wallet_read_exception_does_not_become_rpc_empty_sell_rescue() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("SELL_WALLET_READ_INDETERMINATE_NO_RESCUE"))
        assertTrue(exec.contains("SELL_RPC_EMPTY_RESCUE_BLOCKED_INDETERMINATE"))
        assertTrue(exec.contains("walletReadIndeterminate = true"))
        assertFalse("wallet timeout must not be described as proceeding via RPC-empty rescue",
            exec.contains("SELL RPC EMPTY/TIMEOUT: getTokenAccountsWithDecimals — proceeding via RPC-EMPTY rescue"))
    }


    @Test
    fun position_caps_use_current_wallet_truth_not_stale_tracker_raw() {
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val lifecycle = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenLifecycleTracker.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        assertTrue(tracker.contains("CAP TRUTH SPLIT"))
        assertTrue(tracker.contains("fun isCapCountable(mint: String)"))
        assertTrue(tracker.contains("hasCurrentWalletPositiveProof"))
        assertTrue(tracker.contains("hasFreshBuyLiability"))
        assertTrue(tracker.contains("hasLiveSellInFlightForCap"))
        assertTrue(tracker.contains("getActuallyHeldCount(): Int = positions.values.count { hasCurrentWalletPositiveProof(it) || hasBotBoughtPositiveLiability(it) }"))
        assertFalse("stale raw alone must not make a row open/cap-countable",
            tracker.contains("hasLastPositiveRaw(p) ||\n            p.status in SELL_IN_FLIGHT_STATUSES"))

        assertTrue(lifecycle.contains("HostWalletTokenTracker.isCapCountable(r.mint)"))
        assertTrue(bot.contains("isCapCountableLiveToken"))
        assertTrue(bot.contains("status.tokens.values.filter { isCapCountableLiveToken(it.mint, it) }"))
    }

    @Test
    fun processor_amount_plans_are_owned_by_helper_not_executor_wrappers() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        assertTrue(planner.contains("object ProcessorAmountPlanner"))
        assertTrue(exec.contains(": ProcessorAmountPlanner.BuyPlan?"))
        assertTrue(exec.contains(": ProcessorAmountPlanner.SellPlan?"))
        assertFalse("Executor must not keep duplicate buy plan wrapper", exec.contains("private data class ProcessorBuyPlan"))
        assertFalse("Executor must not keep duplicate sell plan wrapper", exec.contains("private data class ProcessorSellPlan"))
    }


    @Test
    fun wallet_authority_has_no_unknown_or_open_recovery_state() {
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val snapshot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/WalletAuthoritySnapshot.kt").readText()

        assertTrue(snapshot.contains("sealed class WalletAuthoritySnapshot"))
        assertTrue(snapshot.contains("data class HELD"))
        assertTrue(snapshot.contains("data class ABSENT_CONFIRMED"))
        assertTrue(snapshot.contains("data class NO_CURRENT_HELD_PROOF"))
        assertFalse("wallet authority must not expose UNKNOWN state", snapshot.contains("data class UNKNOWN"))
        assertFalse("wallet authority must not assign UNKNOWN snapshots", tracker.contains("WalletAuthoritySnapshot.UNKNOWN"))

        val openStatusesStart = tracker.indexOf("internal val OPEN_STATUSES")
        val openStatusesEnd = tracker.indexOf("private val SELL_IN_FLIGHT_STATUSES", openStatusesStart)
        val openStatuses = tracker.substring(openStatusesStart, openStatusesEnd)
        assertFalse("legacy unknown recovery must not be open", openStatuses.contains("OPEN_BALANCE_UNKNOWN_RECOVERY_REQUIRED"))
        assertFalse("legacy unknown must not be open", openStatuses.contains("OPEN_BALANCE_UNKNOWN"))
        assertFalse("no-signature retry must not be open", openStatuses.contains("OPEN_SELL_FAILED_NO_SIGNATURE_RETRYING"))
        assertFalse("balance-proof wait must not be open", openStatuses.contains("SELL_WAITING_BALANCE_PROOF"))
        assertTrue(openStatuses.contains("OPEN_BALANCE_PROOF_PENDING"))

        assertTrue(tracker.contains("PositionStatus.STALE_RECOVERY_UNPROVEN"))
        assertTrue(tracker.contains("CLOSED_STALE_RECOVERY_UNHELD"))
        assertTrue(tracker.contains("HISTORICAL_RAW_NOT_CURRENT_HELD_PROOF"))
        assertFalse("historical raw must not protect a row from stale-unproven conversion",
            tracker.contains("REAP_SKIPPED_LAST_POSITIVE_HELD"))
        assertFalse("no-signature failures must not create open retry state",
            tracker.contains("retry_required=true"))
        assertFalse("no-signature failures must not say open retry",
            tracker.contains("open retry"))
    }

    @Test
    fun tx_meta_buy_is_proof_pending_until_current_wallet_held_snapshot() {
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        assertTrue(tracker.contains("proof.source == BalanceProofSource.TX_META_OWNER_DELTA) PositionStatus.OPEN_BALANCE_PROOF_PENDING"))
        assertTrue(tracker.contains("BUY_TX_META_AWAITING_CURRENT_WALLET_HELD"))
        assertTrue(tracker.contains("WalletAuthoritySnapshot.HELD"))
        assertTrue(tracker.contains("currentHeldSnapshot"))
        assertTrue(tracker.contains("getActuallyHeldCount(): Int = positions.values.count { hasCurrentWalletPositiveProof(it) || hasBotBoughtPositiveLiability(it) }"))
        assertTrue(tracker.contains("getActuallyHeldMints(): Set<String>"))
        assertFalse("TX-meta owner delta must not promote directly to open tracking",
            tracker.contains("p.status = PositionStatus.OPEN_TRACKING\n        p.source = when (proof.source)"))
    }


    @Test
    fun orphan_wallet_token_recovery_is_disabled_and_purged() {
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        assertTrue(tracker.contains("RECOVER_ORPHAN_WALLET_TOKENS: Boolean = false"))
        assertTrue(tracker.contains("if (!RECOVER_ORPHAN_WALLET_TOKENS) {"))
        assertTrue(tracker.contains("ORPHAN_WALLET_TOKEN_IGNORED"))
        assertTrue(tracker.contains("fun purgeOrphanRecoveredRows"))
        assertTrue(tracker.contains("ORPHAN_WALLET_TOKEN_PURGED"))
        assertTrue(tracker.contains("purgeOrphanRecoveredRows(\"INIT\")"))
        assertTrue(tracker.contains("purgeOrphanRecoveredRows(\"WALLET_SNAPSHOT\")"))

        // The orphan-adoption row builder must be unreachable while the flag is off:
        // the ignore-guard `continue` must appear before the recovered TrackedTokenPosition.
        val guardIdx = tracker.indexOf("ORPHAN_WALLET_TOKEN_IGNORED")
        val adoptIdx = tracker.indexOf("symbol = \"RECOVERED_")
        assertTrue("orphan ignore guard must precede orphan adoption builder", guardIdx in 1 until adoptIdx)
    }


    @Test
    fun runtime_state_header_reads_canonical_authority_and_stop_does_not_resave() {
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val svc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        // Fault #6 — header Execution state must derive from BotRuntimeController, not a hardcoded ACTIVE.
        assertTrue(pipe.contains("BotRuntimeController.snapshot()"))
        assertTrue(pipe.contains("POST_STOP_SNAPSHOT"))
        assertTrue(pipe.contains("RuntimeState.STOPPED"))
        assertTrue(pipe.contains("RuntimeState.STOPPING"))
        assertTrue(pipe.contains("RuntimeState.STARTING"))
        assertFalse("header must not unconditionally guess ACTIVE without consulting runtime authority",
            pipe.contains("val state = if (blockedMs > 0 && ageSec in 0..120) {"))

        // Fault #1 — manual stop finalizes persistence and onDestroy must not re-save stale rows.
        assertTrue(svc.contains("var persistenceFinalizedByStop = false"))
        assertTrue(svc.contains("persistenceFinalizedByStop = true"))
        assertTrue(svc.contains("ONDESTROY_SAVE_SUPPRESSED"))
        // The unconditional crash-recovery save must now be guarded.
        assertTrue(svc.contains("if (!persistenceFinalizedByStop && !isManualStopRequested(applicationContext)) {"))
        // Latch released on a fresh start so normal saves resume.
        assertTrue(svc.contains("persistenceFinalizedByStop = false"))
    }


    @Test
    fun drawdown_circuit_does_not_park_bot_during_bootstrap() {
        val dd = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/DrawdownCircuitAI.kt").readText()
        // Bootstrap clamp must exist so paper-dominated drawdown can't veto live entries.
        assertTrue(dd.contains("DD_CIRCUIT_BOOTSTRAP_LIFETIME"))
        assertTrue(dd.contains("DD_CIRCUIT_BOOTSTRAP_AGG_FLOOR"))
        assertTrue(dd.contains("getLifetimeStats().totalSells"))
        assertTrue(dd.contains("coerceAtLeast(DD_CIRCUIT_BOOTSTRAP_AGG_FLOOR)"))
        // Floor must keep aggression in the -4-penalty band (>=0.70), never -10/-20.
        assertTrue(dd.contains("DD_CIRCUIT_BOOTSTRAP_AGG_FLOOR = 0.70"))
    }


    @Test
    fun dust_positions_finalize_closed_and_do_not_latch_sell_only_safe_mode() {
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        val recon = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveWalletReconciler.kt").readText()

        // SellAmountAuthority: a trusted confirmed wallet read at zero/dust must resolve
        // Zero (finalize-close), never Confirmed (which retries the sell forever).
        assertTrue(auth.contains("SELL_DUST_RAW"))
        assertTrue(auth.contains("BALANCE_RPC_CONFIRMED_DUST_ZERO"))
        assertTrue(auth.contains("TRUSTED_WALLET_ZERO"))
        // The ui<=0 branch must now return Zero, not Unknown.
        assertFalse("trusted wallet-zero must finalize, not return Unknown",
            auth.contains("BALANCE_UNKNOWN reason=ONE_PROVIDER_ZERO"))

        // LiveWalletReconciler: a healthy (non-empty) wallet read that shows a tracked
        // OPEN_TRACKING mint at dust must reap it — not keep it open and latch
        // SELL_ONLY_SAFE_MODE via openCountMismatch / pendingSellQueue.
        assertTrue(recon.contains("DUST_RAW_REAP"))
        assertTrue(recon.contains("DUST_ZOMBIE_POSITION_REAPED"))
        assertTrue(recon.contains("if (rawApprox <= DUST_RAW_REAP) continue"))
    }


    @Test
    fun host_wallet_is_source_of_truth_ghost_positions_cannot_inflate_open_count() {
        val snap = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val recon = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveWalletReconciler.kt").readText()

        // localLiveOpen must be gated by the host tracker's accounting-open set so a
        // stale live TokenState cannot inflate canonicalOpen and latch SELL_ONLY_SAFE_MODE.
        assertTrue(snap.contains("getOpenForAccountingMints"))
        assertTrue(snap.contains("accountingOpenMints"))
        // An EMPTY accounting set must gate ghosts out (only a null/tracker-error falls back).
        assertTrue(snap.contains("accountingOpenMints == null) true"))
        assertTrue(tracker.contains("fun getOpenForAccountingMints"))

        // Ghost live TokenStates must be purgeable so wallet == dashboard == accounting.
        assertTrue(bot.contains("fun purgeGhostLivePosition"))
        assertTrue(recon.contains("purgeGhostLivePosition"))
        assertTrue(recon.contains("GHOST_TOKENSTATE_REAPED"))
        assertFalse("reconciler must not fake sell signatures to force-close ghosts", recon.contains("hasConfirmedSellSig = true"))
        assertFalse("BotService zombie force must not fake a confirmed sell signature", bot.contains("ZOMBIE_FORCE_TERMINATE") && bot.contains("hasConfirmedSellSig = true"))
    }


    @Test
    fun live_sell_no_signature_releases_close_lease_and_does_not_queue_blocking_retry() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val cls = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellRouteErrorClassifier.kt").readText()

        assertTrue(cls.contains("Class.NO_SIGNATURE"))
        assertTrue(cls.contains("no signature — clear lock unless retry scheduled"))

        // A sell route that exhausts every provider before producing a broadcast
        // signature must be classified as NO_SIGNATURE, not generic BROADCAST_FAILED.
        assertTrue(exec.contains("all providers exhausted without broadcast signature"))
        assertTrue(exec.contains("SellRouteErrorClassifier.classify"))
        assertTrue(exec.contains("SellResult.ROUTE_FAILED_NO_SIGNATURE"))

        // This outcome is deliberately non-closing but non-blocking: tokens stay open,
        // yet the close lease / sell-in-flight / pending retry queue are cleared so
        // the next fresh exit tick can try again and buys are not held in safe mode.
        assertTrue(exec.contains("CloseLease.release(ts.mint, r.name)"))
        assertTrue(exec.contains("PendingSellQueue.remove(ts.mint)"))
        assertTrue(exec.contains("ROUTE_FAILED_NO_SIGNATURE_NO_BLOCKING_RETRY"))
        assertTrue(exec.contains("return SellResult.ROUTE_FAILED_NO_SIGNATURE"))
        assertFalse("no-signature route failure must not be auto-queued as FAILED_RETRYABLE", exec.contains("ROUTE_FAILED_NO_SIGNATURE -> {\n                        com.lifecyclebot.engine.sell.CloseLease.recordRetry"))
    }


    @Test
    fun lifecycle_confirmed_pending_count_is_host_tracker_backed_not_unbounded() {
        val lifecycle = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenLifecycleTracker.kt").readText()
        val snapshot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()

        assertTrue(snapshot.contains("TokenLifecycleTracker.confirmedPendingCount()"))
        assertTrue(lifecycle.contains("fun confirmedPendingCount()"))
        assertTrue("confirmed pending lifecycle rows must be backed by host wallet accounting", lifecycle.contains("HostWalletTokenTracker.isCapCountable(r.mint)"))
        assertFalse("confirmedPendingCount must not count raw CONFIRMED_PENDING_BALANCE forever", lifecycle.contains("records.values.count { it.status == Status.CONFIRMED_PENDING_BALANCE"))
        assertTrue("canonical open must still include host-backed fresh buy liabilities", snapshot.contains("lifecyclePendingConfirmed"))
    }


    @Test
    fun runtime_open_pressure_uses_wallet_truth_filtered_lifecycle_not_raw_open_count() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val anti = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AntiChokeManager.kt").readText()

        assertTrue("BotService rescue/open pressure must use filtered lifecycle count", bot.contains("TokenLifecycleTracker.liveMemeOpenCount()"))
        assertTrue("AntiChoke open pressure must use filtered lifecycle count", anti.contains("TokenLifecycleTracker.liveMemeOpenCount()"))
        assertFalse("AntiChoke must not use raw lifecycle openCount for pressure", anti.contains("val lifecycleOpen = try { TokenLifecycleTracker.openCount()"))
    }


    @Test
    fun paper_restore_exit_churn_is_blocked_at_source_before_trace_and_slot_inflation() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persist = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        val budget = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperExitSweepBudget.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val exits = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ModeSpecificExits.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

        val reqGuard = exec.indexOf("V5.0.3801 — PAPER source guard before any executor activity")
        val lifecyclePending = exec.indexOf("TokenLifecycleTracker.onSellPending")
        val trace = exec.indexOf("ExecutionRootCauseTrace.sell(\"DO_SELL_ENTRY\"")
        assertTrue("paper requestSell guard must run before lifecycle pending", reqGuard >= 0 && lifecyclePending > reqGuard)
        assertTrue("paper doSell guard must remain before EXEC_TRACE_SELL", exec.indexOf("PaperPositionCloseAuthority.preSellGuard(\"PAPER\"") in 0 until trace)

        assertTrue("paper restore must use a bounded freshness window", persist.contains("PAPER_RESTORE_WINDOW_MS"))
        assertTrue("stale paper restore rows must be quarantined/dropped", persist.contains("PAPER_STALE_RESTORE_DROPPED"))
        assertTrue("restore must not use noisy loadPositions()", persist.contains("val persisted = loadPositionsInternal()"))
        assertFalse("paper restore doctrine must not say paper positions never go stale", persist.contains("paper positions NEVER go stale"))

        assertTrue("paper exit sweep budget helper must cap checks at 5", budget.contains("minOf(5, openPaperPositions"))
        assertTrue("main loop must skip already CLOSED paper active rows", bot.contains("PAPER_CLOSED_ACTIVE_ROW_DROPPED"))
        assertTrue("main loop must budget paper exit maintenance", bot.contains("PaperExitSweepBudget.allow"))
        assertTrue("fresh-timeout must consult paper close authority", exits.contains("PaperPositionCloseAuthority.stateOf(\"PAPER\", mint)"))

        assertTrue("paper journal row alias must be event-attributed", collector.contains("PAPER_JOURNAL_ROWS"))
        assertTrue("paper quarantine alias must be report-visible", collector.contains("PAPER_QUARANTINED_ROWS"))
    }


    @Test
    fun paper_finality_slot_truth_and_counter_parity_are_ledger_authoritative() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val paperClose = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()

        val partialStart = exec.indexOf("fun checkPartialSell")
        val partialChunk = exec.substring(partialStart, minOf(exec.length, partialStart + 8000))
        val paperIdx = partialChunk.indexOf("if (pos.isPaperPosition)")
        val liveProofIdx = partialChunk.indexOf("SellAmountAuthority.resolveForExit")
        val liveWaitIdx = partialChunk.indexOf("SELL_WAITING_BALANCE_PROOF")
        val liveUnknownIdx = partialChunk.indexOf("PARTIAL_BALANCE_UNKNOWN")
        assertTrue("paper partial branch must precede live SellAmountAuthority", paperIdx >= 0 && liveProofIdx > paperIdx)
        assertTrue("paper partial must bypass wallet proof with ledger authority", partialChunk.contains("PAPER_BALANCE_PROOF_BYPASSED_LEDGER_AUTHORITY") && partialChunk.contains("pos.qtyToken * sellFraction"))
        assertTrue("paper partial must expose requested/done/rejected labels", exec.contains("PAPER_PARTIAL_CLOSE_REQUESTED") && exec.contains("PAPER_PARTIAL_CLOSE_DONE") && exec.contains("PAPER_PARTIAL_CLOSE_REJECTED_NO_LEDGER_POSITION"))
        assertTrue("balance-proof wait labels must stay in the live branch after SellAmountAuthority", liveWaitIdx > liveProofIdx && liveUnknownIdx > liveProofIdx)

        assertTrue("paper close authority must mark ledger-only finality", paperClose.contains("PAPER_CLOSE_CONFIRMED_LEDGER_ONLY"))
        assertTrue("paper slot health must rebuild from paper ledger", bot.contains("rebuildPaperForcedOpenFromLedger") && bot.contains("PAPER_SLOT_HEALTH_REBUILT_FROM_LEDGER"))
        assertTrue("paper forced rows must clear stale closed/dust states", bot.contains("PAPER_FORCED_ROW_CLEARED_NOT_OPEN") && bot.contains("PAPER_FORCED_ROW_CLEARED_CLOSED_LEDGER") && bot.contains("PAPER_FORCED_ROW_CLEARED_DUST"))
        assertTrue("paper slot health publish must use paper open count", bot.contains("forcedOpen = if (paperRuntime) paperOpenNow else forcedOpenCount") && bot.contains("openPositions = if (paperRuntime) paperOpenNow else forcedOpenCount"))

        assertTrue("paper counters must increment from accepted journal rows", collector.contains("PAPER_COUNTER_INCREMENTED_FROM_JOURNAL") && collector.contains("PAPER_COUNTER_SIDE_MAPPED"))
        assertTrue("paper quarantine must skip OK counters", store.contains("PAPER_COUNTER_SKIPPED_QUARANTINED_ROW"))
        assertTrue("paper journal/counter parity must be reported", collector.contains("TRADEJRNL_COUNTER_PARITY_OK") && collector.contains("TRADEJRNL_COUNTER_PARITY_FAIL"))
        assertTrue("mode reset must clear journal-derived paper labels with paper OK atomics", collector.contains("PAPER_JOURNAL_ROWS") && collector.contains("labelCounts.remove(it)"))

        assertFalse("paper finality fix must not touch FDG", java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText().contains("PAPER_BALANCE_PROOF_BYPASSED_LEDGER_AUTHORITY"))
    }


    @Test
    fun trade_journal_links_buy_partials_and_terminal_sell_with_entry_snapshot() {
        val models = java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeJournal.kt").readText()
        val journalActivity = java.io.File("src/main/kotlin/com/lifecyclebot/ui/JournalActivity.kt").readText()

        assertTrue("Trade row must carry canonical lifecycle positionId", models.contains("val positionId: String"))
        assertTrue("Trade row must persist entry timestamp snapshot", models.contains("val entryTsMs: Long"))
        assertTrue("Trade row must persist entry price snapshot", models.contains("val entryPriceSnapshot: Double"))
        assertTrue("Trade row must persist entry market cap", models.contains("val entryMcapUsd: Double"))
        assertTrue("Trade row must persist token quantity basis", models.contains("val entryQtyToken: Double"))
        assertTrue("Trade row must persist cost basis snapshot", models.contains("val entryCostSol: Double"))
        assertTrue("Trade row must persist partial accounting quantities", models.contains("val soldQtyToken: Double") && models.contains("val remainingQtyToken: Double"))

        assertTrue("SQLite schema must version linkage columns", store.contains("const val DB_VERSION = 6"))
        assertTrue("SQLite schema must store position_id", store.contains("position_id   TEXT"))
        assertTrue("SQLite schema must store entry price snapshot", store.contains("entry_price_snapshot"))
        assertTrue("TradeHistoryStore must enrich missing sell linkage from prior BUY", store.contains("fun enrichJournalLinkage") && store.contains("TRADE_JOURNAL_LINKAGE_ENRICHED"))
        assertTrue("DB reload must sequence-relink legacy BUY/SELL rows", store.contains("fun enrichRowsBySequence") && store.contains("val enrichedLoaded = enrichRowsBySequence(loaded)"))
        assertTrue("Bulk record path must also enrich linkage", store.contains("val enriched = enrichJournalLinkage(normalized)"))

        assertTrue("Executor must stamp journal positionId from TradeOutcomeLedger", executor.contains("TradeOutcomeLedger.positionId(ts, trade)"))
        assertTrue("Executor must stamp entry price from Position snapshot", executor.contains("entryPriceSnapshot = trade.entryPriceSnapshot") && executor.contains("ts.position.entryPrice"))
        assertTrue("Executor must stamp entry mcap from Position snapshot without sell-side current-mcap fallback", executor.contains("val entryMcapForJournal: Double") && executor.contains("trade.entryMcapUsd.takeIf") && executor.contains("ts.position.entryMcap"))
        assertTrue("Executor must stamp partial sold/remaining qty", executor.contains("soldQtyForJournal") && executor.contains("remainingQtyToken"))

        assertTrue("Journal rows must expose lifecycle position id", journal.contains("val positionId: String"))
        assertTrue("Journal must build rows through canonical trade conversion", journal.contains("journalEntryFromTrade"))
        assertTrue("Journal sell entryPrice must use entryPriceSnapshot not row-local sell price", journal.contains("val entryPx = trade.entryPriceSnapshot"))
        assertTrue("CSV must expose entry snapshot and exit execution price separately", journal.contains("Entry Price Snapshot (SOL)") && journal.contains("Exit Price (SOL)"))
        assertTrue("CSV must expose partial quantity accounting", journal.contains("Sold Token Qty") && journal.contains("Remaining Token Qty"))
        assertTrue("JournalActivity UI mapper must use stored entry snapshot", journalActivity.contains("t.entryPriceSnapshot.takeIf"))
        assertTrue("JournalActivity UI mapper must expose lifecycle linkage fields", journalActivity.contains("positionId      = t.positionId") && journalActivity.contains("remainingQtyToken = t.remainingQtyToken"))
        assertFalse("Journal linkage must not be only mint/time inference", journal.contains("positionId = \"${'$'}{trade.ts}_${'$'}{trade.mint}\""))
    }


    @Test
    fun partial_rows_count_as_real_outcomes_but_use_leg_accounting_and_clean_mcap_basis() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeJournal.kt").readText()
        val journalActivity = java.io.File("src/main/kotlin/com/lifecyclebot/ui/JournalActivity.kt").readText()
        val scorer = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

        assertTrue("TradeJournal must treat PARTIAL_SELL as sell-like", journal.contains("side.equals(\"SELL\", ignoreCase = true) || side.equals(\"PARTIAL_SELL\", ignoreCase = true)"))
        assertTrue("partial exits must drive journal WR", journal.contains("partial exits are real realized outcomes and must drive WR") && journal.contains("val decisiveTrades = sells.filter { isDecisive(it.pnlPct) }"))
        assertTrue("partial exits must drive exported WR/count/avg", journal.contains("partial exits are real realized exits") && journal.contains("val decisiveSells = sells.filter { isDecisive(it.entry.pnlPct) }"))
        assertTrue("JournalActivity count must include partial sell rows", journalActivity.contains("tvJournalCount.text = sellEntries.size.toString()"))
        assertFalse("partials must not be demoted to terminal-only stats", journal.contains("terminalSells") || journalActivity.contains("isTerminalSell"))

        assertTrue("capital recovery partial must store realized leg pct, not full-position gainPct", executor.contains("val paperCRLegPct = pct(paperCRCostBasis, sellSol)") && executor.contains("pnlSol, paperCRLegPct"))
        assertTrue("profit lock partial must store realized leg pct, not full-position gainPct", executor.contains("val paperPLLegPct = pct(paperPLCostBasis, sellSol)") && executor.contains("pnlSol, paperPLLegPct"))
        assertFalse("capital/profit-lock canonical row must not store raw gainPct as partial pnlPct", executor.contains("""pnlSol, gainPct,
                    feeSol = paperCRFee""") || executor.contains("""pnlSol, gainPct,
                    feeSol = paperPLFee"""))

        assertTrue("sell/partial journal mcap must not fall back to current/discovery ts.lastMcap", executor.contains("entryMcapForJournal: Double") && executor.contains("if (trade.side.equals(\"BUY\", true)) ts.lastMcap"))
        assertTrue("UI must show unknown mcap as n/a instead of a fake current mcap", journalActivity.contains("mcap=n/a"))

        assertFalse("safe rebuild must not include 3807 runtime telemetry scorer hook", scorer.contains("AIStackSnapshot"))
        assertFalse("safe rebuild must not include 3807 runtime telemetry FDG hook", fdg.contains("EffectiveSizeShapeTrace"))
        assertFalse("safe rebuild must not include 3807 runtime telemetry collector hook", collector.contains("AIStackSnapshot.formatForPipelineDump") || collector.contains("EffectiveSizeShapeTrace.formatForPipelineDump"))
    }


    @Test
    fun open_position_pnl_must_use_price_basis_authority() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OpenPnlSanity.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val persist = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        val quality = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/QualityTraderAI.kt").readText()
        assertTrue("OpenPnlSanity must reject unknown/synthetic extreme basis ratios", authority.contains("PRICE_BASIS_UNTRUSTED_EXTREME_RATIO") && authority.contains("SYNTHETIC_PRICE_BASIS_EXTREME_PNL") && authority.contains("OPEN_PNL_BASIS_REJECTED"))
        assertTrue("Unified Open Positions must show basis wait instead of fake mega PnL", main.contains("MainActivity.renderRow") && main.contains("basis wait") && main.contains("basisTrusted"))
        assertTrue("ShitCoin Degen card must also use basis authority", main.contains("MainActivity.shitcoinFast") && main.contains("MainActivity.shitcoinBuild"))
        assertTrue("Core lane exits must not update peak/lock/exit from raw current-entry ratios", shit.contains("OpenPnlSanity.inspect") && moon.contains("OpenPnlSanity.inspect") && quality.contains("OpenPnlSanity.inspect"))
        assertTrue("BotService rapid stop / stale / promotion paths must use basis authority", bot.contains("BotService.rapidStop") && bot.contains("BotService.rapidSubTraderFloor") && bot.contains("BotService.heldPivot") && bot.contains("qualityPromotionPnl"))
        assertTrue("Position persistence must preserve price-basis metadata", persist.contains("entryPriceSource") && persist.contains("entryPoolAddress") && persist.contains("lastPriceSource") && persist.contains("priceBasisRescaled"))
    }


    @Test
    fun learning_pnl_sanitizer_blocks_poisoned_training_fanout() {
        val sanitizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPnlSanitizer.kt").readText()
        val tokenWin = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenWinMemory.kt").readText()
        val strategy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StrategyTelemetry.kt").readText()
        val history = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val canonical = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalLearning.kt").readText()
        val collective = java.io.File("src/main/kotlin/com/lifecyclebot/collective/CollectiveLearning.kt").readText()
        val sanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TradeRowSanityCheck.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        assertTrue("Learning sanitizer must define finite trainable PnL bounds", sanitizer.contains("MAX_TRAINABLE_PNL_PCT = 5_000.0") && sanitizer.contains("PNL_PCT_ABOVE_TRAINABLE_MAX") && sanitizer.contains("PNL_PCT_SOL_BASIS_MISMATCH") && sanitizer.contains("emit: Boolean = true"))
        assertTrue("TokenWinMemory must reject impossible PnL before recording", tokenWin.contains("LearningPnlSanitizer.inspectPct") && tokenWin.contains("return") && tokenWin.contains("quarantinedLegacy"))
        assertTrue("TokenWinMemory exports must filter already-poisoned persisted aggregates", tokenWin.contains("sanePatternStats") && tokenWin.contains("saneTokenStats") && tokenWin.contains("saneWinner") && tokenWin.contains("exportPatternAggregates"))
        assertTrue("StrategyTelemetry must include partial closes and use the same sanitizer", strategy.contains("PARTIAL_SELL") && strategy.contains("LearningPnlSanitizer.inspectTrade") && strategy.contains("SELL+PARTIAL_SELL"))
        assertTrue("Canonical learning bus must suppress poisoned rows without deleting journal rows", canonical.contains("LearningPnlSanitizer.inspectTrade") && canonical.contains("only strategy-learning fanout is suppressed"))
        assertTrue("Hive trade/pattern side doors must be guarded", collective.contains("suspend fun uploadTrade") && collective.contains("uploadWhaleEffectiveness") && collective.contains("broadcastHotToken") && collective.contains("LearningPnlSanitizer.inspectPct"))
        assertTrue("TradeRowSanityCheck must cover partial sells too", sanity.contains("PARTIAL_SELL") && sanity.contains("LearningPnlSanitizer.inspectTrade"))
        assertTrue("Unified report must surface learning quarantine counts", report.contains("learningQuarantineLine") && report.contains("LEARNING_PNL_QUARANTINED"))
    }


    @Test
    fun unified_report_is_compact_and_includes_learning_tuning_journal() {
        val hub = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        assertTrue("Unified report must have a hard chat-size budget", hub.contains("MAX_UNIFIED_REPORT_CHARS = 42_000") && hub.contains("REPORT_TRUNCATED"))
        assertTrue("Unified report scope must include learning/tuning/journal", hub.contains("learning / tuning / journal") && hub.contains("LEARNING + TUNING STATE") && hub.contains("TRADE JOURNAL SUMMARY"))
        assertTrue("Unified report must use compact core pipeline, not raw full dump only", hub.contains("compactPipelineDump(PipelineHealthCollector.dumpText())") && hub.contains("PIPELINE HEALTH — CORE"))
        assertTrue("Learning section must include local and collective memory", hub.contains("TokenWinMemory.getPatternSummary") && hub.contains("LosingPatternMemory.formatForPipelineDump") && hub.contains("CollectiveLearning.getInsightsSummary"))
        assertTrue("Tuning section must include active tuners", hub.contains("PatternAutoTuner.getStatus") && hub.contains("LaneExitTuner.formatForPipelineDump") && hub.contains("StrategyHypothesisEngine.formatForPipelineDump") && hub.contains("UnifiedPolicyHead.formatForPipelineDump"))
        assertTrue("Journal section must use canonical bounded store summaries", hub.contains("TradeHistoryStore.getCanonicalTotals") && hub.contains("TradeHistoryStore.getLifetimeStats") && hub.contains("TradeHistoryStore.getRecentValidClosedTrades"))
        assertTrue("Unified report must have timeout/degraded fallback instead of freezing", hub.contains("REPORT_BUILD_TIMEOUT_MS") && hub.contains("buildEmergencyText") && hub.contains("AATE REPORT DEGRADED") && hub.contains("withTimeout"))
        assertFalse("Unified report must not dump raw journal CSV/export rows", hub.contains("TradeJournal.export") || hub.contains("CSV") || hub.contains("buildJournal(tokens)"))
    }


    @Test
    fun hive_pattern_edges_are_consumed_by_collective_ai() {
        val collective = java.io.File("src/main/kotlin/com/lifecyclebot/collective/CollectiveLearning.kt").readText()
        val collectiveAi = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CollectiveIntelligenceAI.kt").readText()
        val scorer = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        assertTrue("CollectiveLearning must expose candidate-matched hive pattern edges", collective.contains("data class HivePatternEdge") && collective.contains("fun getPatternEdgesForCandidate"))
        assertTrue("Hive pattern edge must match TokenWinMemory aggregate dimensions", collective.contains("mcap_bucket") && collective.contains("liq_ratio") && collective.contains("buy_pressure") && collective.contains("symbol_pattern"))
        assertTrue("CollectiveAI must consume hive pattern edges", collectiveAi.contains("HIVE_PATTERN_EDGE") && collectiveAi.contains("getPatternEdgesForCandidate"))
        assertTrue("Hive pattern edge must be bounded", collectiveAi.contains("coerceIn(-14, 14)") && collectiveAi.contains("coerceIn(-5, 5)"))
        assertTrue("UnifiedScorer must pass candidate mcap/buy-pressure into CollectiveAI", scorer.contains("marketCapUsd = candidate.marketCapUsd") && scorer.contains("buyPressurePct = candidate.buyPressurePct"))
        assertFalse("Hive pattern edge must not become a hard veto", collectiveAi.contains("fatal = true") || collectiveAi.contains("score = -100") || collectiveAi.contains("return emptyList"))
    }


    @Test
    fun hive_sync_uploads_journal_rows_and_local_pattern_aggregates() {
        val collective = java.io.File("src/main/kotlin/com/lifecyclebot/collective/CollectiveLearning.kt").readText()
        val tokenWin = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenWinMemory.kt").readText()
        val history = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val ui = java.io.File("src/main/kotlin/com/lifecyclebot/ui/CollectiveBrainActivity.kt").readText()
        val forceSync = collective.substring(collective.indexOf("suspend fun forceSyncNow"))
        assertTrue("TokenWinMemory must export aggregate pattern payloads", tokenWin.contains("fun exportPatternAggregates") && tokenWin.contains("ExportedPatternAggregate"))
        assertTrue("CollectiveLearning must bulk upload local pattern aggregates", collective.contains("uploadLocalPatternAggregates") && collective.contains("LOCAL_PATTERN|") && collective.contains("patternHash"))
        assertTrue("Pattern aggregate upload must be idempotent", collective.contains("ON CONFLICT(pattern_hash) DO UPDATE SET") && collective.contains("excluded.total_trades"))
        assertTrue("manual sync must upload patterns before download", forceSync.contains("val uploadedPatterns = uploadLocalPatternAggregates()") && forceSync.indexOf("val uploadedPatterns = uploadLocalPatternAggregates()") < forceSync.indexOf("downloadAll()"))
        assertTrue("background sync must upload patterns before download", collective.contains("uploadLocalPatternAggregates()") && collective.contains("downloadAll()"))
        assertTrue("canonical journal rows must upload to hive", history.contains("uploadCollectiveJournalRow") && history.contains("CollectiveLearning.uploadJournalTradeRow"))
        assertTrue("journal upload must use deterministic key", collective.contains("sha256(\"JOURNAL|"))
        assertFalse("hive sync must not depend on UI activity to upload patterns", ui.contains("uploadLocalPatternAggregates"))
        assertFalse("journal hive upload must not be blocked by scratch BUY filter", history.contains("uploadTrade(") && history.contains("side = side"))
    }


    @Test
    fun specialist_moe_gate_weights_components_without_veto_or_zeroing() {
        val moe = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/SpecialistMoEGate.kt").readText()
        val scorer = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("MoE gate must exist", moe.contains("object SpecialistMoEGate"))
        assertTrue("MoE must consume Education evidence", moe.contains("EducationSubLayerAI.getLayerAccuracy") && moe.contains("EducationSubLayerAI.getLayerMaturity"))
        assertTrue("MoE must consume MetaCog trust", moe.contains("MetaCognitionAI.getTrustMultiplier"))
        assertTrue("MoE must be bounded", moe.contains("FLOOR = 0.75") && moe.contains("CAP = 1.25"))
        assertTrue("MoE must preserve non-zero votes", moe.contains("nonZeroRounded"))
        assertTrue("UnifiedScorer must apply MoE before final ScoreCard", scorer.contains("SpecialistMoEGate.apply(gatedComponents, candidate, ctx)") && scorer.contains("ScoreCard(moeComponents)"))
        assertTrue("MoE telemetry must be report-visible", collector.contains("SpecialistMoEGate.formatForPipelineDump"))
        assertFalse("MoE must not introduce hard veto", moe.contains("fatal = true") || moe.contains("return emptyList") || moe.contains("return false"))
        assertFalse("MoE must not call an LLM/API on scorer hot path", moe.contains("GeminiCopilot") || moe.contains("Groq") || moe.contains("rawText") || moe.contains("http"))
    }


    @Test
    fun runtime_doctor_classifies_strategy_bleed_and_mechanical_degradation() {
        val dbg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StateDebuggerAI.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeDoctor.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("doctor diagnosis must expose state", dbg.contains("val state: String = faultCode"))
        assertTrue("doctor diagnosis must expose subsystem owner", dbg.contains("val subsystem: String = \"runtime\""))
        assertTrue("strategy bleed must be classified separately from invariant faults", dbg.contains("STRATEGY_BLEED") && dbg.contains("wr < 20.0"))
        assertTrue("mechanical degradation must catch ANR hints", dbg.contains("MECHANICAL_FAULT") && dbg.contains("anrHints >= 3"))
        assertTrue("doctor must expose latest diagnosis", doctor.contains("fun currentDiagnosis()"))
        assertTrue("forensic report must print state/subsystem", report.contains("state=${'$'}{doctor.diagnosis.state}") && report.contains("subsystem=${'$'}{doctor.diagnosis.subsystem}"))
        assertTrue("pipeline root cause must consume doctor diagnosis", collector.contains("RuntimeDoctor.currentDiagnosis()"))
        assertFalse("autonomy diagnosis must not self-edit code", dbg.contains("PatchWriterAI") || dbg.contains("deploy") || dbg.contains("git commit"))
        assertFalse("autonomy diagnosis must not weaken live safety", dbg.contains("disableTerminal") || dbg.contains("ignoreFinality") || dbg.contains("forceLive"))
    }


    @Test
    fun event_triggered_sentience_is_safe_and_non_mutating() {
        val sentience = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SentienceOrchestrator.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeDoctor.kt").readText()
        assertTrue(sentience.contains("EVENT_TRIGGERED_SENTIENCE_SAFE"))
        assertTrue(sentience.contains("fun noteRuntimeEvent"))
        assertTrue(sentience.contains("event_only:no_mutation"))
        assertTrue(sentience.contains("no Gemini/Groq/LLM call"))
        assertTrue(doctor.contains("publishSentienceEventReflections"))
        assertTrue(doctor.contains("SentienceOrchestrator.noteRuntimeEvent"))
        assertFalse("safe autonomy event reflection must not reintroduce 3807 scorer telemetry hook", java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText().contains("AIStackSnapshot"))
    }


    @Test
    fun losing_pattern_memory_soft_sizes_emerging_bootstrap_bleeders_before_maturity() {
        val losing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LosingPatternMemory.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

        assertTrue("LosingPatternMemory must define emerging danger before mature sample=20", losing.contains("val isEmergingDanger"))
        assertTrue("emerging danger must be sample gated at 8..19", losing.contains("sample in 8..19"))
        assertTrue("emerging danger must require net-negative mean", losing.contains("meanPnl <= -4.0"))
        assertTrue("recommendedSizeMult must consume emerging danger", losing.contains("!s.isDangerous && !s.isEmergingDanger"))
        assertTrue("emerging danger must soft-size, not veto", losing.contains("s.losses >= 10 -> 0.25") && losing.contains("else           -> 0.45"))
        assertTrue("emerging danger telemetry must be visible", losing.contains("LOSING_PATTERN_EMERGING_DANGER"))
        assertTrue("FDG must already consume LosingPatternMemory recommended sizing", fdg.contains("LosingPatternMemory.recommendedSizeMult"))
        assertFalse("emerging danger must not introduce a hard reject", losing.contains("if (s.isEmergingDanger) return 0.0"))
    }


    @Test
    fun bleed_auto_pivot_cap_overrides_normal_route_floor_without_veto() {
        val lanePolicy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/LanePolicy.kt").readText()
        val fdgRoute = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/FdgRouteVerdict.kt").readText()

        assertTrue("LanePolicy must expose bucket WR for source-level bleed detection", lanePolicy.contains("fun rollingWrForBucket("))
        assertTrue("LanePolicy must expose persistent-bleed execution cap", lanePolicy.contains("fun bleedExecutionCap("))
        assertTrue("bleed cap must consider hostile DUMP regime", lanePolicy.contains("RegimeDetector.Regime.DUMP"))
        assertTrue("bleed cap must be report-visible", lanePolicy.contains("LANE_BLEED_EXECUTION_CAP"))

        assertTrue("FDG route sizing must consume LanePolicy bleed cap", fdgRoute.contains("LanePolicy.bleedExecutionCap"))
        assertTrue("NORMAL route must keep throughput floor only when no bleed cap exists", fdgRoute.contains("else base.coerceAtLeast(0.85)"))
        assertTrue("NORMAL route must let learned bleed cap override the 85% floor", fdgRoute.contains("minOf(base, bleedCap).coerceIn(0.05, 0.85)"))
        assertTrue("reduced route must not re-floor capped bleeders to 30%", fdgRoute.contains("minOf(base, bleedCap).coerceIn(0.05, 0.70)"))
        assertFalse("persistent bleed response must not become a hard route veto", fdgRoute.contains("bleedCap != null) return Verdict.ROUTE_SHADOW_TRACK") || fdgRoute.contains("bleedCap != null) return Verdict.ROUTE_TRAIN_ONLY"))
    }


    @Test
    fun downstream_coroutine_split_only_moves_post_proof_reconcile_retry_work() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val queue = java.io.File("src/main/kotlin/com/lifecyclebot/engine/DownstreamWorkQueue.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()

        assertTrue("downstream queue must exist", queue.contains("object DownstreamWorkQueue"))
        assertTrue("downstream queue must expose verification lane", queue.contains("fun verification("))
        assertTrue("downstream queue must expose reconciliation lane", queue.contains("fun reconciliation("))
        assertTrue("downstream queue must expose retry lane", queue.contains("fun retry("))

        assertTrue("reconciler sell trigger must be downstream async", bot.contains("DownstreamWorkQueue.reconciliation(\"reconciler_sell_trigger\""))
        assertTrue("reconciler zero close finality must be downstream async", bot.contains("DownstreamWorkQueue.reconciliation(\"reconciler_zero_close\""))
        assertTrue("proof-ready retry enqueue must be downstream async", bot.contains("DownstreamWorkQueue.retry(\"balance_proof_ready_enqueue\""))
        assertTrue("zero-confirmed finality must be downstream async", bot.contains("DownstreamWorkQueue.verification(\"balance_proof_zero_confirmed\""))

        assertFalse("live sell amount authority must not be moved into downstream queue", queue.contains("ProcessorAmountPlanner.planSell"))
        assertFalse("live buy amount authority must not be moved into downstream queue", queue.contains("ProcessorAmountPlanner.planBuy"))
        assertTrue("Executor must still invoke processor-bound sell planning synchronously", exec.contains("ProcessorAmountPlanner.planSell("))
        assertTrue("ProcessorAmountPlanner must still read owner-filtered token accounts synchronously", planner.contains("wallet.getTokenAccountsWithDecimalsBounded()"))
    }


    @Test
    fun processor_amount_planner_owns_buy_sell_amount_authority_executor_only_orchestrates() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val planner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()

        assertTrue("planner must expose synchronous buy amount authority", planner.contains("fun planBuy("))
        assertTrue("planner must expose synchronous sell amount authority", planner.contains("fun planSell("))
        assertTrue("sell planner must refresh owner-filtered token accounts", planner.contains("wallet.getTokenAccountsWithDecimalsBounded()"))
        assertTrue("sell planner must keep owner-delta rescue for critical exits", planner.contains("SellAmountAuthority.resolveForExit"))
        assertTrue("buy planner must refresh wallet SOL before processor quote/build", planner.contains("wallet.getSolBalance()"))

        assertTrue("Executor full-sell route ladder must still call the same wrapper", exec.contains("private fun recalcSellPlanForProcessor"))
        assertTrue("Executor buy route ladder must still call the same wrapper", exec.contains("private fun recalcBuyPlanForProcessor"))
        assertTrue("Executor wrapper must delegate sell authority to ProcessorAmountPlanner", exec.contains("ProcessorAmountPlanner.planSell("))
        assertTrue("Executor wrapper must delegate buy authority to ProcessorAmountPlanner", exec.contains("ProcessorAmountPlanner.planBuy("))
        assertFalse("Executor must not keep duplicate sell-balance authority after extraction", exec.contains("private fun resolveConfirmedSellAmountOrNull"))
        assertFalse("Executor must not keep duplicate ConfirmedSellAmount model after extraction", exec.contains("private data class ConfirmedSellAmount"))

        val partialIdx = exec.indexOf("processor = \"JupiterPartial\"")
        val partialQuoteIdx = exec.indexOf("val manualJupiterPlan = recalcSellPlanForProcessor", partialIdx.coerceAtLeast(0))
        assertTrue("live partial sells must obtain a processor-bound plan before quote/build", partialIdx >= 0 && partialQuoteIdx > partialIdx)
    }


    @Test
    fun paper_simulator_close_authority_size_clamp_and_quarantine_are_source_guarded() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val paperClose = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt").readText()
        val paperSanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperLearningSanity.kt").readText()
        val tradeStore = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val rowSanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TradeRowSanityCheck.kt").readText()
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

        assertTrue("paper close authority must exist", paperClose.contains("object PaperPositionCloseAuthority"))
        assertTrue("paper authority must expose CLOSE_REQUESTED", paperClose.contains("CLOSE_REQUESTED"))
        assertTrue("paper authority must low-rate duplicate close telemetry", paperClose.contains("PAPER_CLOSE_ALREADY_PENDING"))

        val guardIdx = exec.indexOf("PaperPositionCloseAuthority.preSellGuard")
        val traceIdx = exec.indexOf("ExecutionRootCauseTrace.sell(\"DO_SELL_ENTRY\"")
        assertTrue("paper close guard must run before DO_SELL_ENTRY / EXEC_TRACE_SELL", guardIdx >= 0 && traceIdx >= 0 && guardIdx < traceIdx)
        assertTrue("first paper close must mark requested before trace", exec.contains("PaperPositionCloseAuthority.markCloseRequested"))
        assertTrue("paperSell must finalize the paper authority when ledger closes", exec.contains("PaperPositionCloseAuthority.markClosed(\"PAPER\", tradeId.mint"))

        assertTrue("paper buy must clamp before position and journal mutation", exec.contains("clampPaperTradeSol(finalSol"))
        assertTrue("paper buy max must be config-backed", exec.contains("maxOf(c.smallBuySol, c.maxPositionSol)"))
        assertTrue("paper buy clamp telemetry must exist", exec.contains("PAPER_BUY_SIZE_CLAMPED"))

        assertTrue("paper sanity must quarantine rows above configured max", paperSanity.contains("PAPER_SOL_ABOVE_CONFIG_MAX"))
        assertTrue("paper sanity must emit required quarantine label", paperSanity.contains("PAPER_LEARNING_ROW_QUARANTINED"))
        assertTrue("TradeHistoryStore must filter corrupted historical rows", tradeStore.contains("PaperLearningSanity.inspect(t)"))
        assertTrue("TradeRowSanityCheck must quarantine paper corrupt rows", rowSanity.contains("PAPER_ROW_CORRUPT"))

        assertTrue("paper telemetry must include partial ok", collector.contains("EXEC_PAPER_PARTIAL_OK"))
        assertTrue("journal split must expose live/paper/partial/quarantine rows", collector.contains("TRADEJRNL_SPLIT liveRows="))
        assertFalse("PAPER_BUY exec attempt labels must not directly increment OK counters", collector.contains("action.startsWith(\"PAPER_BUY\")        -> execPaperBuyOk.incrementAndGet()"))
    }


    @Test
    fun mixed_mode_report_uses_event_mode_and_splits_live_paper_recent_executions() {
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val models = java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()

        assertTrue("FDG per-mode must parse mode from event payload", collector.contains("val eventMode = extractModeFromText(reason)"))
        assertTrue("report must explain event attribution", collector.contains("event-attributed"))
        assertTrue("live execution section must be separate", collector.contains("LIVE execution telemetry (event-attributed)"))
        assertTrue("paper execution section must be separate", collector.contains("PAPER execution telemetry (event-attributed)"))
        assertTrue("recent live list must be separate", collector.contains("Recent LIVE executions"))
        assertTrue("recent paper list must be separate", collector.contains("Recent PAPER executions"))
        assertTrue("recent execution rows must carry proof state", collector.contains("val proofState: String = \"\"") )
        assertTrue("live lifecycle execution labels must feed live attempt counter", collector.contains("\"MEME_LIVE_EXEC_ENTRY\" -> execLiveAttempt.incrementAndGet()"))
        assertTrue("live finality labels must feed live sell-ok counter", collector.contains("\"SELL_FINALIZED_ONCE\", \"SELL_FINALIZED\", \"EXEC_LIVE_SELL_ZERO_BALANCE_CONFIRMED\", \"SELL_SIG_CONFIRMED\" -> execLiveSellOk.incrementAndGet()"))

        assertTrue("Trade model must include proofState", models.contains("val proofState: String = \"\"") )
        assertTrue("Trade DB must persist proof_state", store.contains("proof_state"))
        assertTrue("TradeHistoryStore must default paper proof", store.contains("PAPER_SIMULATED"))
        assertTrue("TradeHistoryStore must default live sig proof", store.contains("LIVE_SIG_CONFIRMED"))
        assertTrue("TradeHistoryStore must send proof state to report ring", store.contains("proofState = tradeToStore.proofState"))
    }


    @Test
    fun canonical_wr_must_exclude_learning_quarantined_pnl_rows() {
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue("Canonical accounting must use the same poison ceiling as LearningPnlSanitizer", store.contains("LearningPnlSanitizer.inspectTrade(t, \"TradeHistoryStore.isValidAccountingTrade\"") && !store.contains("t.pnlPct > 100_000.0"))
        assertTrue("Threshold version must force lifetime WR/PnL backfill when canonical accounting rules change", store.contains("CURRENT_THRESHOLD_VER = 729"))
        assertTrue("Rolling WR must filter invalid accounting rows", store.contains("filter { isJournalSellLike(it.side) && isValidAccountingTrade(it) }") && store.contains("computeRollingWinRatePct"))
        assertTrue("Lane WR must use canonical scratch-aware win/loss thresholds", store.contains("val losses = modeTrades.count { isLoss(it) }") && store.contains("wins * 100.0 / decisive"))
    }


    @Test
    fun short_fix_block_3837_contracts() {
        val keyValidator = java.io.File("src/main/kotlin/com/lifecyclebot/engine/KeyValidator.kt").readText()
        assertTrue("Helius validator must probe real RPC health", keyValidator.contains("getHealth") && keyValidator.contains("getLatestBlockhash") && keyValidator.contains("getBalance") && keyValidator.contains("getTokenAccountsByOwner"))
        assertTrue("Helius statuses must be exact", listOf("HELIUS_KEY_MISSING", "HELIUS_AUTH_FAILED_401", "HELIUS_FORBIDDEN_403", "HELIUS_RATE_LIMIT_429", "HELIUS_TIMEOUT", "HELIUS_RPC_ERROR", "HELIUS_HEALTHY").all { keyValidator.contains(it) })
        assertTrue("Groq validator must test configured model path and expose rate-limit degradation", keyValidator.contains("https://api.groq.com/openai/v1/chat/completions") && keyValidator.contains("GROQ_RATE_LIMIT_429_NARRATIVE_DEGRADED"))

        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue("FDG must cache verdicts by generation/mint/candidate/lane/side", fdg.contains("fdgVerdictCache") && fdg.contains("candidateVersionOf") && fdg.contains("currentGeneration()") && fdg.contains("FDG_VERDICT_CACHE_HIT"))
        assertTrue("Helius unhealthy must be degraded-route softshape, not global live-buy hard block", fdg.contains("""mode == TradeMode.LIVE && !KeyValidator.isLive("helius")""".trimIndent()) && fdg.contains("FDG_LIVE_HELIUS_DEGRADED_SOFTSHAPE") && !fdg.contains("HELIUS_UNHEALTHY_LIVE_SAFE_MODE"))

        val slot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        assertTrue("Paper slot health must rebuild from canonical paper active positions", slot.contains("canonicalPaperOpenCount") && slot.contains("PAPER_SLOT_HEALTH_REBUILT_FROM_LEDGER") && slot.contains("coerceAtMost(canonicalPaperOpen)"))

        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("BUY_NOT_OPENED must be separated from BUY opened", exec.contains("PAPER_BUY_ATTEMPT") && exec.contains("PAPER_BUY_OPENED") && exec.contains("PAPER_BUY_NOT_OPENED"))
        assertTrue("BUY_NOT_OPENED must release execution permit and lane primary", exec.contains("FinalExecutionPermit.releaseExecution(ts.mint)") && exec.contains("LaneExecutionCoordinator.releaseIfPrimary"))

        val learning = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalLearning.kt").readText()
        assertTrue("Stop-loss label conflicting with positive signed PnL must not train", learning.contains("LEARNING_LABEL_SIGN_CONFLICT_QUARANTINED") && learning.contains("labelLooksStopLoss && learningPnlVerdict.pnlPct > 0.5"))
    }


    @Test
    fun main_ui_money_and_pricing_surfaces_use_display_authority() {
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue("Hero balance must preserve the original BotService.status rolling wallet balance contract", main.contains("hero balance — BotService.status is the single source of truth") && main.contains("if (balSol > 0.001)") && main.contains("tvBalanceLarge.setTextIfChanged(currency.format(balSol))"))
        assertTrue("Paper hero chip must remain the old balance chip, not realized-PnL replacement", main.contains("📝 PAPER MODE  ◎") && !main.contains("realized P&L ◎") && !main.contains("displayBankrollSol"))
        assertTrue("Paper hero must not sanitize/delete the headline balance", !main.contains("PAPER_HERO_BANKROLL_DISPLAY_SANITIZED") && !main.contains("rawBankrollSol > sanePaperCeiling"))
        assertTrue("Open-position UI must recover missing entry/current pricing from journal/token sources", main.contains("recoverRenderablePricing") && main.contains("journalEntryPrice") && main.contains("OPEN_POSITION_PRICE_RECOVERED_FOR_UI"))
        assertTrue("Main UI panels must use shared current-price authority", main.contains("mainUiCurrentPrice") && main.contains("shared Main UI current-price authority"))
        assertTrue("Main UI must show pricing wait instead of fake zero entry", main.contains("pricing wait") && main.contains("basis wait") && !main.contains("if (ref > 0.0) ref else pos.entryPrice") && !main.contains("ts.lastPrice - pos.entryPrice"))
        assertTrue("CYCLIC panel must display engine-published price/PnL authority, not raw token fallback", main.contains("cyclicStatusDisplay") && main.contains("engine.entryPriceSol") && main.contains("engine.currentPriceSol") && main.contains("engine.priceState") && main.contains("px=") && main.contains("priceTxt") && !main.contains("cyclicToken?.history?.lastOrNull()?.priceUsd"))
    }


    @Test
    fun report_and_stale_feed_authority_use_canonical_bounded_sources() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        val cyclic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

        assertTrue("Executor stale-feed eviction must never use Long.MAX_VALUE as real feed age", executor.contains("val feedAnchorMs = ts.lastPriceUpdate.takeIf") && executor.contains("feedAgeMs != null && feedAgeMs >=") && !executor.contains("feedAgeMs = if (ts.lastPriceUpdate > 0L)"))
        assertTrue("Restored persisted prices must restore a bounded price timestamp", persistence.contains("restoredPriceUpdateMs") && persistence.contains("existing.lastPriceUpdate = restoredPriceUpdateMs") && persistence.contains("lastPriceUpdate = restoredPriceUpdateMs"))
        assertTrue("CYCLIC must wait on unknown timestamp instead of force-closing Long.MAX stale", cyclic.contains("CYCLIC_PRICE_TS_UNKNOWN_WAIT") && cyclic.contains("ageText") && !cyclic.contains("priceAgeMs = if (ts.lastPriceUpdate > 0L)"))
        assertTrue("Pipeline PerformanceAnalytics must read bounded canonical TradeHistoryStore rows, not legacy TradeDatabase or full journal copies", phc.contains("canonicalPerformanceTrades") && phc.contains("TradeHistoryStore.getRecentValidClosedTrades") && !phc.contains("BotService.instance?.tradeDb"))
    }


    @Test
    fun live_buy_gate_common_sense_hard_blocks_only() {
        val tokenBlacklist = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenBlacklist.kt").readText()
        val bannedTokens = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BannedTokens.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val safety = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenSafetyChecker.kt").readText()
        val liveGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveBuyAdmissionGate.kt").readText()
        val preTrade = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        TokenBlacklist.block(softMint + "loss", "2+ losses on SAME")
        assertFalse("2+ historical losses must be PENALTY_ONLY, never hard blacklist", TokenBlacklist.isBlocked(softMint + "loss"))
        TokenBlacklist.block(trueMint + "lp0", "LP 0% locked / unlocked liquidity")
        assertTrue("LP 0% locked remains true hard blacklist", TokenBlacklist.isBlocked(trueMint + "lp0"))

        assertTrue("Blacklist taxonomy must expose true vs penalty-only reasons", tokenBlacklist.contains("isTrueBlacklistReason") && tokenBlacklist.contains("isSoftPenaltyOnlyReason"))
        assertTrue("BannedTokens must refuse to persist repeated-loss bans", bannedTokens.contains("PENALTY_ONLY: not banning") && bannedTokens.contains("isTrueBlacklistReason"))
        assertFalse("Executor must not write repeated-losses into TokenBlacklist", executor.contains("TokenBlacklist.block(ts.mint, \"2+ losses"))
        assertFalse("Executor must not write repeated-losses into BannedTokens", executor.contains("BannedTokens.ban(ts.mint, \"2+ losses"))
        assertTrue("Repeated-loss learning must emit penalty-only proof", executor.contains("decision=PENALTY_ONLY reason=2+_losses"))

        assertTrue("Zero liquidity remains hard block", safety.contains("ZERO LIQUIDITY") && preTrade.contains("ZERO_LIQUIDITY"))
        assertTrue("Low but nonzero liquidity must be quote/size penalty, not static hard block", safety.contains("LOW_LIQUIDITY_SIZE_REDUCED") && preTrade.contains("LOW_LIQUIDITY_SIZE_REDUCED"))
        assertFalse("Static liquidity min must not hard-block live buys", preTrade.contains("return block(ts, \"LIQUIDITY_BELOW_LIVE_MIN"))
        assertFalse("Missing/stale safety must not hard-block by itself", preTrade.contains("return block(ts, \"SAFETY_PROOF_STALE_OR_MISSING"))
        assertTrue("LiveBuyAdmissionGate must convert safety shadow to penalty-only unless true hard", liveGate.contains("SAFETY_SHADOW_PENALTY_ONLY") && liveGate.contains("BUY_GATE_PENALTY_ONLY_SAFETY_SHADOW"))
        assertTrue("BotService SAFETY_SHADOW must continue only for true hard reasons", bot.contains("!TokenBlacklist.isSoftPenaltyOnlyReason(reason)") && bot.contains("source=BotService.SAFETY_SHADOW"))
        assertTrue("Every taxonomy decision should surface forensic proof", listOf(tokenBlacklist, executor, safety, liveGate, preTrade, bot).all { it.contains("BUY_GATE_DECISION") })
    }


    @Test
    fun live_micro_probe_entry_bypasses_expectancy_and_break_even_sizing() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val scoreExpectancy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ScoreExpectancyTracker.kt").readText()
        val liveRestore = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveRestoreExecutionPolicy.kt").readText()

        assertTrue("Live entry must bypass LaneExpectancyDamper sizing", executor.contains("LIVE_EXPECTANCY_SIZE_BYPASSED") && executor.contains("if (RuntimeModeAuthority.isLive())") && executor.contains("applied=1.0"))
        assertTrue("Live entry must bypass break-even economics and defer them to sell side", executor.contains("LIVE_ENTRY_BREAK_EVEN_BYPASSED_TO_SELL") && executor.contains("sellSideBreakEvenOk"))
        assertFalse("liveBuy entry must not call breakEvenCheck before route quote", executor.contains("val breakEven = LiveRestoreExecutionPolicy.breakEvenCheck(ts, sol, restorePenalty"))
        assertTrue("Score expectancy reject must be neutral in live", scoreExpectancy.contains("LIVE_EXPECTANCY_REJECT_BYPASSED") && scoreExpectancy.contains("RuntimeModeAuthority.isLive()") && scoreExpectancy.contains("return false"))
        assertTrue("Score expectancy size multiplier must be neutral in live", scoreExpectancy.contains("do not dust-size live probes") && scoreExpectancy.contains("return 1.0"))
        assertTrue("Break-even logic remains available for sell-side profit discipline", liveRestore.contains("sellSideBreakEvenOk") && liveRestore.contains("breakEvenCheck(ts, ts.position.costSol"))
    }


    @Test
    fun live_realistic_sizing_and_bot_bought_wallet_liability_are_authoritative() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val host = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()

        assertTrue("Live sizing must be centralized after lane math", executor.contains("realisticLiveEntrySize") && executor.contains("LIVE_REALISTIC_SIZE_AUTHORITY"))
        assertTrue("doBuy final size must pass through realistic live sizing", executor.contains("source=doBuy.final") || executor.contains("\"doBuy.final\""))
        assertTrue("liveBuy final chokepoint must also pass through realistic sizing", executor.contains("\"liveBuy.final\"") && executor.contains("LIVE_REALISTIC_SIZE_CLAMPED_TO_SPENDABLE"))
        assertTrue("Realistic sizing must use wallet, liquidity, score, and lane", listOf("walletTarget", "liquidityCapSol", "laneMult", "walletPct").all { executor.contains(it) })

        assertTrue("Host tracker must keep bot-bought positive raw rows visible through RPC indeterminate windows", host.contains("hasBotBoughtPositiveLiability") && host.contains("bot-bought positive liability"))
        assertTrue("Bot-bought positive liability must count in open/cap accounting", host.contains("hasBotBoughtPositiveLiability(p, now) ||") && host.contains("capCountable=\${freshBotBuy || botPositiveLiability}"))
        assertTrue("Actually-held UI set must include bot-bought positive liabilities", host.contains("getActuallyHeldMints") && host.contains("hasCurrentWalletPositiveProof(it) || hasBotBoughtPositiveLiability(it)"))
        assertTrue("Main UI must still intersect visible positions with HostWallet held set", main.contains("getActuallyHeldMints()") && main.contains("buildUnifiedOpenPositions(state)"))
    }


    @Test
    fun live_sell_accounting_uses_proceeds_not_cost_plus_proceeds_and_repairs_sign_conflicts() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Live sell accounting authority must exist", exec.contains("LIVE SELL ACCOUNTING AUTHORITY") && exec.contains("LIVE_SELL_ACCOUNTING_REPAIRED"))
        assertFalse("Verified sell proceeds must not add cost basis into solBack", exec.contains("pos.costSol + verifiedSol"))
        assertFalse("Wallet SOL delta must not be treated as cost+delta proceeds", exec.contains("pos.costSol + delta  // costSol + delta"))
        assertTrue("Terminal live sell must use accounting authority", exec.contains("liveSellAccountingAuthority(ts, pos.costSol, solBack, reason, \"liveSell.terminal\")"))
        assertTrue("Profit-lock/partial live sells must use accounting authority", exec.contains("liveSellAccountingAuthority(ts, pos.costSol * sellFraction, solBack, reason, \"profitLock\")") && exec.contains("partial.jupiter"))
        assertTrue("Stop-like positive sign conflicts must be repaired before journal/learning", exec.contains("stopLike && pnlPct > 0.5") && exec.contains("OpenPnlSanity.inspect(ts, \"SELL_ACCOUNTING:${'$'}context\""))
    }


    @Test
    fun cyclic_uses_price_authority_not_raw_last_price_or_entry_fallback() {
        val cyclic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue("CYCLIC must have a dedicated price authority", cyclic.contains("CYCLIC PRICING AUTHORITY") && cyclic.contains("resolveCyclicPrice"))
        assertTrue("CYCLIC held PnL must be guarded by OpenPnlSanity", cyclic.contains("OpenPnlSanity.inspect") && cyclic.contains("CYCLIC_PRICE_AUTHORITY_WAIT"))
        assertFalse("CYCLIC held path must not calculate pnl from raw lastPrice", cyclic.contains("val currentPrice = rawPrice"))
        assertTrue("CYCLIC entry stamp must use authority price", cyclic.contains("entryPriceSol = entryPriceVerdict.price") && cyclic.contains("entryPrice = entryPriceVerdict.price"))
        assertFalse("CYCLIC UI must not read raw token price sources", main.contains("cyclicToken?.lastPrice") || main.contains("cyclicToken?.ref") || main.contains("cyclicToken?.history?.lastOrNull()?.priceUsd"))
        assertTrue("CYCLIC UI must read engine-published price state", main.contains("engine.currentPriceSol") && main.contains("engine.priceState"))
    }


}
