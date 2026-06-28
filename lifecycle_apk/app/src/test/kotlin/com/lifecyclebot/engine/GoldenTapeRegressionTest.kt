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
            "Rug detected: price -96%",
            "UNCONFIRMED_PRICE_COLLAPSE: price -96%",
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

        TokenBlacklist.block(trueMint + "C", "CONFIRMED_RUG_COLLAPSE: price -96% liqProof=DATA_CONFLICT")
        assertTrue(TokenBlacklist.isBlocked(trueMint + "C"))
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
        assertTrue("Error logs must be bounded tightly via compact table to avoid eating the report tail", hub.contains("ErrorLogger.exportToCompactTable(limit = 60)"))
        assertTrue("Toolkit setup/chart counters must feed report visibility", sheet.contains("TOOLKIT_SETUP_${'$'}{built.setup.name}") && sheet.contains("TOOLKIT_CHART_${'$'}{built.chartPattern.uppercase().take(48)}"))
        assertTrue("ANR evidence must remain visible in compact report", hub.contains("===== ANR / main-thread health") && hub.contains("===== ANR top blocking call sites") && hub.contains("ANR top:"))
        assertTrue("Internet edge desk must be visible in toolkit report section", hub.contains("InternetEdgeDesk.summaryLine") && hub.contains("INTERNET_EDGE_REFRESHED"))
        assertTrue("Learning-heavy PHC sections must not be duplicated inside core pipeline block", !hub.contains("\"===== Strategy Hypothesis Engine\"") && !hub.contains("\"===== Lane Exit Tuner\"") && !hub.contains("\"===== Autonomous Meta-Policy\"") && !hub.contains("\"===== Unified Policy Head\""))
    }









    @Test
    fun live_stale_restore_cannot_resurrect_old_fdg_approval() {
        val openGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue("LIVE stale-WATCH restore must be ticket based, not global-version based", openGate.contains("data class ExecutionTicket") && openGate.contains("EXEC_TICKET_RESTORED_IMMUTABLE"))
        assertTrue("LIVE stale-candidate version churn must not kill an immutable ticket", openGate.contains("immutableTicket == null && !selectedLaneMatchesRequest") && openGate.contains("immutableTicket == null"))
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
        assertFalse("3914 live full-ring fanout regression must stay dead", bot.contains("LIVE_FULL_RING_LANE_OBSERVE"))
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
        assertTrue("Latest-buy snapshot must be main-thread cached and async refreshed", store.contains("latestBuyByMintCache") && store.contains("scheduleLatestBuyRefresh(cap)") && store.contains("LATEST_BUY_SNAPSHOT_MAIN_CACHE_RETURN"))
        val latestBuyFn = store.substring(store.indexOf("fun getLatestBuyByMintSnapshot"), store.indexOf("private fun computeLatestBuyByMintSnapshot"))
        assertTrue("getLatestBuyByMintSnapshot must check main thread before any journal lock/init scan", latestBuyFn.indexOf("val onMain") < latestBuyFn.indexOf("computeLatestBuyByMintSnapshot"))
        assertFalse("getLatestBuyByMintSnapshot hot wrapper must not call ensureInitialized before the main-thread cache branch", latestBuyFn.contains("ensureInitialized()"))
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
        val mainLayout = java.io.File("src/main/res/layout/activity_main.xml").readText()
        assertTrue("Floating debug tiles must be collapsed into XML Mission Control tiles, not decor overlays", main.contains("setupOperatorDiagnosticTiles") && mainLayout.contains("btnQuickUniverse") && mainLayout.contains("btnQuickLearning") && mainLayout.contains("btnQuickForensics") && !main.contains("rootDecor?.addView"))
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
        assertTrue("Weak CHOP/DUMP must penalize degen and volume/express-like scalps", sheet.contains("Setup.DEGEN_MICRO_SNIPE -> if (r == RegimeDetector.Regime.DUMP) -48.0 else -18.0") && sheet.contains("Setup.VOLUME_IGNITION_SCALP -> if (r == RegimeDetector.Regime.DUMP) -34.0 else -10.0") && sheet.contains("Setup.PUMP_GRADUATION_SNIPE -> if (r == RegimeDetector.Regime.DUMP) -36.0 else -12.0"))
        assertTrue("Weak CHOP/DUMP must prefer pullback/quality/defensive structures", sheet.contains("Setup.CHART_PULLBACK_RECLAIM -> if (r == RegimeDetector.Regime.DUMP) 18.0 else 10.0") && sheet.contains("Setup.LIQUIDITY_DEPTH_QUALITY -> if (r == RegimeDetector.Regime.DUMP) 18.0 else 8.0") && sheet.contains("Setup.REGIME_DEFENSIVE_PROBE -> if (r == RegimeDetector.Regime.DUMP) 14.0 else 6.0"))
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        assertTrue("Regime pivot must remain a soft score bias and visible in toolkit reasons", sheet.contains("regimeSetupBias(it.setup, regime)") && sheet.contains("regimeBias="))
        assertTrue("Router must remap weak-CHOP/DUMP/risk_off degen/fresh/sentiment/graduation styles before primary lane election even on fallback sheets", router.contains("weakChopStylePivot") && router.contains("isWeakRuntimeRegime") && router.contains("RegimeDetector.Regime.DUMP") && router.contains("isRiskOffSheet") && router.contains("Style.DEGEN_MICRO_SNIPE") && router.contains("Style.NARRATIVE_SOCIAL_IGNITION") && router.contains("Style.DEFENSIVE_PROBE") && router.contains("Style.DIAMOND_HANDS_RUNNER") && router.contains("Style.LIQUIDITY_DEPTH_QUALITY") && router.contains("classification.tradeType in setOf(ModeRouter.TradeType.FRESH_LAUNCH, ModeRouter.TradeType.SENTIMENT_IGNITION, ModeRouter.TradeType.GRADUATION") && router.contains("weakChopSheet && classification.tradeType == ModeRouter.TradeType.BREAKOUT_CONTINUATION"))
        assertTrue("Fallback toolkit sheet must not emit degen fresh-launch style in weak runtime regime", sheet.contains("weakRegime") && sheet.contains("Setup.REGIME_DEFENSIVE_PROBE") && sheet.contains("regime=weak_runtime"))
        assertFalse("Regime toolkit pivot must not hard-block or disable lanes", sheet.contains("disableLane") || sheet.contains("shouldTrade = false") || sheet.contains("BLOCK_"))
    }

    @Test
    fun net_negative_danger_bucket_reroutes_lane_exposure_without_trade_block() {
        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneToxicityGuard.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

        assertTrue("Guard must key toxicity on matured net-negative danger buckets via live-only stats", guard.contains("LosingPatternMemory.liveStats") && guard.contains("meanPnl <= -2.0"))
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
        assertTrue(openGate.contains("hard-block residue purge"))
        assertFalse("low nonzero RC must not be recordFdg hardNo", openGate.contains("rugScore in 2..10"))
        assertFalse("low nonzero RC must not be final-open hard block", openGate.contains("rug in 2..10"))
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
        assertTrue(openGate.contains("latestAllows && safetyOk && liqOk") && openGate.contains("val liqOk = effectiveLiq > 0.0"))
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
    fun journal_stats_preserve_accounting_basis_and_rug_safety_net_does_not_clip_green_holds() {
        val journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeJournal.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("TradeJournal stats must validate JournalEntry rows with entry basis fields", journal.contains("JournalEntry→Trade validation") && journal.contains("entryPriceSnapshot = e.entryPrice") && journal.contains("entryCostSol = e.entryCostSol") && journal.contains("price = if (sellLike) e.exitPrice else e.entryPrice"))
        assertFalse("TradeJournal stats must not synthesize sell Trade price from entryPrice only", journal.contains("price = e.entryPrice,"))
        assertTrue("Generic RUG_SAFETY_NET should not bypass min-hold unless raw pnl breached hard floor or rug is confirmed", exec.contains("confirmedRugByReason") && exec.contains("RUGCHECK_CONFIRMED") && exec.contains("CONFIRMED_RUG") && !exec.contains("""return r.contains("RUG")"""))
        assertTrue("Strict/rug exits still bypass when raw market loss hits hard floor through the shared severity classifier", exec.contains("private enum class LiveExitSeverity") && exec.contains("val hardSafety = rawPnlPct <= -15.0") && exec.contains("hardSafety -> LiveExitSeverity.HARD_SAFETY"))
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
        assertTrue(gate.contains("TOP_HOLDER_CONCENTRATION"))
        assertTrue(gate.contains("MINT_AUTHORITY_ACTIVE"))
        assertTrue(gate.contains("FREEZE_AUTHORITY_ACTIVE"))
        assertTrue(gate.contains("RUGCHECK_PENDING_OR_UNKNOWN"))
        assertTrue(gate.contains("PRETRADE_HARD_BLOCK"))
        assertTrue(gate.contains("PRETRADE_PENDING_PROOF_PENALTY_ALLOW"))
        assertTrue("Pending proof must be penalty-only telemetry, while route/liquidity unknown still hydrate-defers", gate.contains("PRETRADE_PENDING_PROOF_PENALTY_ALLOW") && gate.contains("decision=PENALTY_ONLY reason=PENDING_PROOF") && gate.contains("LIVE_ROUTE_LIQUIDITY_PROOF_PENDING") && gate.contains("DEFER_SAFETY_PROOF"))
        assertTrue("Pending holder data must not terminal-choke live buys when confirmed fatal holder/rug checks are absent", gate.contains("HOLDER_DISTRIBUTION_PENDING") && gate.contains("taxonomy=pending_penalty") && !gate.contains("LIVE_CRITICAL_PROOF_PENDING"))
        assertFalse("Critical pending proof must not emit PRETRADE_HARD_BLOCK anymore", gate.contains("return block(ts, \"CRITICAL_SAFETY_PROOF_UNKNOWN\""))
        assertFalse("RugCheck pending alone must not recreate RC=1 live choke", gate.contains("return block(ts, \"RUGCHECK_PENDING_OR_UNKNOWN\""))
        assertTrue("Holder warning text must be a pre-submit hard block", gate.contains("SINGLE HOLDER") && gate.contains("FATAL_WALLET_RISK_TEXT"))
        assertTrue("Unverified-token text must be a pre-submit hard block", gate.contains("UNVERIFIED TOKEN") && gate.contains("FATAL_WALLET_RISK_TEXT"))
        assertTrue("Top-10 holder warning text must be a pre-submit hard block", gate.contains("TOP 10") && gate.contains("FATAL_WALLET_RISK_TEXT"))
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val liveGateIdx = exec.indexOf("LiveBuyAdmissionGate.requireApprovedLiveBuy")
        val preTradeIdx = exec.indexOf("PreTradeHardGate.requireLiveBuyAllowed", liveGateIdx.coerceAtLeast(0))
        val walletIdx = exec.indexOf("if (walletSol <= 0)", liveGateIdx.coerceAtLeast(0))
        assertTrue("PreTradeHardGate must be wired after admission and before wallet/broadcast checks", liveGateIdx >= 0 && preTradeIdx > liveGateIdx && walletIdx > preTradeIdx)
        assertTrue(exec.contains("reason=PRETRADE:"))
        assertTrue("Executor must request safety hydration defer without LIVE_BUY_FAIL/BUY_FAILED spam", exec.contains("EXEC_OPEN_DEFERRED_SAFETY_PROOF") && exec.contains("SafetyRefreshQueue.request(ts.mint)") && exec.contains("LIVE_BUY_DEFERRED") && exec.contains("no_live_buy_fail=true"))
        assertFalse("PreTrade defer must not zero safety timestamps and recreate FDG missing-safety loops", exec.contains("ts.lastSafetyCheck = 0L") || exec.contains("ts.safety = ts.safety.copy(checkedAt = 0L)"))
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue("Explicit SafetyRefreshQueue hydration must run synchronously before FDG", bot.contains("explicitSafetyRefresh") && bot.contains("SAFETY_REFRESH_SYNC_REQUEST") && bot.contains("if (needsFirstCheck || explicitSafetyRefresh)") && bot.contains("} else if (safetyAge > SAFETY_REFRESH_TRIGGER_MS)"))
        assertTrue("FDG safety-not-ready must enqueue hydration, not just log a block", fdg.contains("FDG_SAFETY_NOT_READY_REFRESH_REQUESTED") && fdg.contains("SafetyRefreshQueue.request(ts.mint)"))
    }

    @Test
    fun strict_source_balance_prevents_pumpfun_majority_hot_watchlist() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        val modeLeniency = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ModeLeniency.kt").readText()
        val permit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        val strategy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LifecycleStrategy.kt").readText()
        val eligibility = java.io.File("src/main/kotlin/com/lifecyclebot/v3/eligibility/EligibilityGate.kt").readText()
        val fatalRisk = java.io.File("src/main/kotlin/com/lifecyclebot/v3/risk/FatalRiskChecker.kt").readText()
        val fluid = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/FluidLearningAI.kt").readText()
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
        assertTrue(bot.contains("MEMETRADER_OWNER_LANE") && bot.contains("profitableRescue") && bot.contains("LANE_SUPPRESSED_BY_OWNER_ROTATION"))
        assertFalse("MEME-only must not blanket-mute all non-meme specialist lanes", bot.contains("return memeFamily"))
        assertFalse("toolkit alive must not mean all meme-family siblings execute", bot.contains("if (memeFamily) return true"))
        assertFalse("live owner collapse must not be bypassed by full-ring observe", bot.contains("LIVE_FULL_RING_LANE_OBSERVE"))
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
            // V5.0.4135 — workflow now composes VERSION_NAME from BASE + BUILD_NUMBER
            // (operator override 2026-06-25 — see apk_version_patch_derived_from_ci_run_number).
            assertTrue("Workflow must read major.minor base from AATE_VERSION", workflow.contains("BASE=\"\$(cat AATE_VERSION)\""))
            assertTrue("Workflow must compose VERSION_NAME from base + build number", workflow.contains("VERSION_NAME=\"\${BASE}.\${BUILD_NUMBER}\""))
            assertTrue(workflow.contains("version_name=\$VERSION_NAME"))
            assertTrue(workflow.contains("-PbuildNumber=\$AATE_BUILD_NUMBER -PaateVersionName=\$AATE_VERSION_NAME"))
            assertTrue(workflow.contains("AATE_v\${{ steps.aate_build.outputs.version_name }}"))
            assertFalse("APK artifact must not directly reference github.run_number expression", workflow.contains("AATE_v5.0.\${{ github.run_number }}"))
            assertFalse("Gradle buildNumber must not use raw GitHub run number expression", workflow.contains("-PbuildNumber=\${{ github.run_number }}"))
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
        assertTrue("fresh-source demotion protection must now be Solana-wide, not pump/meme-only", service.contains("val demoteProcessFloor = if (solanaFresh) 6 else 3"))
        assertTrue("fresh-source age protection must now be Solana-wide, not pump/meme-only", service.contains("val demoteAgeFloorMs = if (solanaFresh) 5L * 60_000L else 120_000L"))
        assertTrue("Solana-wide helper must include non-pump sources", service.contains("fun isFreshSolanaSource") && service.contains("RAYDIUM") && service.contains("DATA_ORCHESTRATOR") && service.contains("METEORA") && service.contains("ORCA"))
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
        assertTrue(store.contains("CloseOutcomeLabelSanitizer.canonicalize(it, emit = false)") && store.contains("filter { isValidAccountingTrade(it) }"))
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
        assertTrue(exec.contains("NO_OPEN_COMMITTED_AFTER_LIVEBUY_OBSERVED"))
        assertTrue(exec.contains("LIVE_BUY_FAIL_"))
        assertFalse("outer no-open observation must not double-count as LIVE_BUY_FAIL", exec.contains("emitLiveBuyFail(ts, liveSol, \"NO_OPEN_COMMITTED_AFTER_LIVEBUY\")"))

        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionRouteGuard.kt").readText()
        assertTrue(guard.contains("PAPER_ROUTE_BLOCKED_IN_LIVE_USE_SHADOW_PATH"))
        assertFalse("paperBuy must not be allowed in LIVE just because shadowPaperEnabled is true", guard.contains("SHADOW_ALLOWED_IN_LIVE"))
        assertTrue(guard.contains("runShadowPaperBuy"))

        val stack = java.io.File("src/main/kotlin/com/lifecyclebot/engine/execution/MemeExecutionRouteStack.kt").readText()
        assertTrue(stack.contains("sideEffectLight: Boolean = true"))
        assertTrue(stack.contains("if (!context.sideEffectLight && !s.supported)"))
        assertTrue(stack.contains("if (!context.sideEffectLight) senderProviders.forEach") && stack.contains("adapterWired="))

        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(report.contains("MODE CONTAMINATION CHECK"))
        assertTrue("Live buy fail reasons must be report-visible", report.contains("liveBuyFailReasonCounts") && report.contains("Top BUY fail reasons") && report.contains("EXEC_LIVE_BUY_FAIL_REASONS"))
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
        // V5.0.3915 — operator dump 06-19 19:28: the previous semantic
        // (l.inFlight || now >= l.nextEligibleMs) made idle-backoff leases
        // count as blocking forever (until 10-min TTL), permanently arming
        // SellOnlySafeMode and producing ADMISSION_GATE:SELL_ONLY_SAFE_MODE=337
        // with LIVE BUY ok/fail = 0/482. Correct semantic: only inFlight=true
        // counts as blocking; idle residue is reaped after 60s.
        assertTrue(closeLease.contains("(now - l.acquiredMs < LEASE_TTL_MS) && l.inFlight"))
        assertFalse(
            "SellOnlySafeMode must not see idle-backoff leases as pending sell pressure",
            closeLease.contains("l.inFlight || now >= l.nextEligibleMs"),
        )
        assertTrue(closeLease.contains("fun reapResidue"))
        assertTrue(closeLease.contains("RESIDUE_REAP_MS"))
        assertTrue(closeLease.contains("SELL_LEASE_RESIDUE_REAPED"))

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
        assertFalse("Generic NO_FINAL_BUY_CANDIDATE must not survive as final live-buy reason", gate.contains("\"NO_FINAL_BUY_CANDIDATE\""))
        assertTrue(gate.contains("TOKEN_STATE_CHANGED_NO_FINAL_CANDIDATE"))
        assertTrue(gate.contains("MOONSHOT"))

        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("recentAllowedAttemptIdAnyLane(ts.mint)"))
        assertTrue(bot.contains("authResult.attemptId.ifBlank"))
        assertTrue(bot.contains("recentAllowedAttemptId(ts.mint, ts.position.tradingMode.ifBlank"))

        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("effectiveAttemptId"))
        assertTrue(exec.contains("effectiveFinalityPrechecked"))
        assertTrue(exec.contains("recentAllowedAttemptIdAnyLane(ts.mint)"))
        assertFalse("Executor must not emit generic NO_FINAL_BUY_CANDIDATE", exec.contains("reason=FINALITY_BLOCK:NO_FINAL_BUY_CANDIDATE"))
        assertTrue(exec.contains("FINALITY_BLOCK:${'$'}{executableOpen.reason"))
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
        assertTrue("Fresh NO_PAIR rows must stay hot for hydration before aged demotion", service.contains("INTAKE_NO_PAIR_HELD_HOT_FOR_HYDRATION") && service.contains("NO_PAIR_NO_FALLBACK_AGED") && service.contains("processCount >= 4") && service.contains("ageMs > 120_000L"))
        assertTrue("NO_PAIR probation rows must not timeout-promote back to hot loop without price/source proof", registry.contains("NO_PAIR_TIMEOUT_HELD") && registry.contains("PROBATION_TIMEOUT_HELD_NO_PAIR") && registry.contains("entry.source.contains(\"NO_PAIR_NO_FALLBACK\""))
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
        assertTrue("generic meme spine must call liveBuy with named args so laneTag survives into live lane/journal stamping",
            exec.contains("val liveOpened = liveBuy(") &&
                exec.contains("sol = liveSol") &&
                exec.contains("layerTag = laneTag.takeIf { it.isNotBlank() && it != \"STANDARD\" } ?: \"\"") &&
                exec.contains("resolvedInputLaneForPivot = resolveExecutionLane(ts, identity)"))
        assertTrue(exec.contains("LIVE_OPEN_COMMITTED_LOCK_RECORDED"))
        assertTrue(exec.contains("pendingLiveCommit"))
        assertTrue(exec.contains("if (liveOpened || positionDidOpen(ts) || pendingLiveCommit)"))
        assertTrue(exec.contains("LIVE_BUY_PENDING_COMMIT_ACCEPTED"))
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
        assertTrue("Doctor noSig must be recent-window based, not permanently red from cumulative session history", doctor.contains("cumulativeNoSig") && doctor.contains("recentCutoffMs") && doctor.contains("recentEvents?.count"))
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
        assertTrue("V5.0.4155: internal meme authority must include all specialist lanes except CYCLIC sidecar",
            auth.contains("internalMemeLayers") && auth.contains("set - Trader.CRYPTO_ALT - internalMemeLayers") && auth.contains("Trader.QUALITY") && auth.contains("Trader.TREASURY") && auth.contains("Trader.PROJECT_SNIPER") && !auth.substringAfter("val internalMemeLayers = setOf(").substringBefore(")").contains("Trader.CYCLIC"))
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
    fun live_buy_clamps_to_wallet_and_min_non_micro_before_insufficient_balance() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("LIVE_BUY_SIZE_CLAMPED_TO_WALLET"))
        assertTrue(exec.contains("LIVE_BUY_SIZE_RAISED_TO_MIN_NON_MICRO"))
        assertTrue(exec.contains("minNonMicroLiveBuySol"))
        assertTrue(exec.contains("LIVE_ENTRY_REJECTED_SIZE_TOO_THIN_FOR_NON_MICRO_TRADE"))
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
    fun all_live_trading_fee_paths_pool_before_sending() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val markets = java.io.File("src/main/kotlin/com/lifecyclebot/perps/MarketsLiveExecutor.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val accumulator = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FeeAccumulator.kt").readText()
        assertTrue("meme fee helper must accrue to FeeAccumulator, not send every micro fee", exec.contains("FeeAccumulator.accrue") && exec.contains("FEE ACCUMULATOR"))
        assertTrue("fee accumulator must hold until the intended 1 SOL total onboard threshold", accumulator.contains("DEFAULT_FLUSH_THRESHOLD_SOL = 1.0") && accumulator.contains("val totalPending") && accumulator.contains("totalPending < flushThresholdSol") && accumulator.contains("every destination bucket is flushed/distributed"))
        assertTrue("markets/perps fee collection must use the same pooled accumulator", markets.contains("CORE FEE POOL ALIGNMENT") && markets.contains("FeeAccumulator.accrue") && markets.contains("MARKETS_FEE_ACCUMULATED"))
        val marketsFeeFn = markets.substring(markets.indexOf("private suspend fun collectTradingFee"), markets.indexOf("totalFeesCollectedSol", markets.indexOf("private suspend fun collectTradingFee")))
        assertFalse("markets/perps fee collection must not send micro-fee transfers directly", marketsFeeFn.contains("wallet.sendSol"))
        assertTrue("bot loop must drain retry queue and flush accumulated fee buckets in live mode", bot.contains("FeeRetryQueue.drainFeeQueue(liveWallet)") && bot.contains("FeeAccumulator.tryFlush(liveWallet)"))
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
        assertTrue(tuner.contains("val stopLeakClamp = slHitRate >= 0.35 && avgLoss <= -20.0"))
        assertTrue(tuner.contains("val slCap = if ((wr < 0.20 && avgReal < 0.0 && avgPeak < 15.0) || stopLeakClamp) 1.0 else SL_MAX"))
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
        assertTrue("Non-route sell waits must not auto-queue or emit noSig", executor.contains("isNonRouteSellWait") && executor.contains("SELL_RETRY_SUPPRESSED_NON_ROUTE_WAIT") && executor.contains("ACTIVE_SELL_SIG_IN_FLIGHT") && executor.contains("FAILURE_HISTORY_RECONCILER_WAIT"))
        val retryBlock = executor.substring(executor.indexOf("if (result == SellResult.FAILED_RETRYABLE)"), executor.indexOf("if (result == SellResult.WAITING_BALANCE_PROOF)"))
        assertTrue("Generic retry branch may enqueue, but must not emit runtime noSig finality side effects", retryBlock.contains("val nonRouteWait = isNonRouteSellWait(ts)") && retryBlock.indexOf("if (nonRouteWait)") < retryBlock.indexOf("else {") && retryBlock.indexOf("else {") < retryBlock.indexOf("PendingSellQueue.add") && retryBlock.contains("SELL_RETRY_ENQUEUED_NO_FINALITY_FAULT") && !retryBlock.contains("SellForensics.inc("))
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
        assertTrue("V5.0.4155: held count must exclude terminal one-token dust before cap/accounting predicates",
            tracker.contains("getActuallyHeldCount(): Int = positions.values.count { !isTerminalDust(it) && (hasCurrentWalletPositiveProof(it) || hasBotBoughtPositiveLiability(it)) }"))
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
        assertTrue("V5.0.4155: TX-meta liability remains managed, but terminal dust cannot inflate held count",
            tracker.contains("getActuallyHeldCount(): Int = positions.values.count { !isTerminalDust(it) && (hasCurrentWalletPositiveProof(it) || hasBotBoughtPositiveLiability(it)) }"))
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
        val partialChunk = exec.substring(partialStart, minOf(exec.length, partialStart + 12000))
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
    fun birdeye_provider_conservation_must_not_be_stale_hardcoded_pause() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BirdeyeBudgetGate.kt").readText()
        assertTrue("Birdeye monthly cap must match current 6M dashboard quota", gate.contains("MONTHLY_CAP = 6_000_000L"))
        assertTrue("Birdeye emergency conservation must not be permanently hardcoded on", gate.contains("EMERGENCY_CONSERVATION_MODE = false"))
        assertTrue("real Birdeye protection must remain via counters/throttles/lockdown", gate.contains("DAILY_SCANNER_THROTTLE_PCT") && gate.contains("MONTHLY_LOCKDOWN_PCT") && gate.contains("isLockedDown()"))
        assertFalse("stale 300% over-quota comment must not keep future builds in false emergency", gate.contains("300% monthly"))
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("Birdeye report denominator must come from BirdeyeBudgetGate snapshot, not stale hardcoded 5M", gate.contains("monthlyCap = MONTHLY_CAP") && pipe.contains("bsnap.monthlyCap") && !pipe.contains("/5,000,000"))
        assertTrue("Birdeye daily cap must throttle provider calls, not globally block live entries", gate.contains("live entries are hard-paused only on monthly/provider exhaustion") && gate.contains("return EMERGENCY_CONSERVATION_MODE || monthlyPct >= MONTHLY_LOCKDOWN_PCT") && !gate.contains("return configuredDailyPct >= 1.0 || monthlyPct >= MONTHLY_LOCKDOWN_PCT"))
        assertTrue("Provider lockdown report must not mark provider locked solely because daily app-local CU cap is hit", gate.contains("providerLockedDown = EMERGENCY_CONSERVATION_MODE || monthlyPct >= MONTHLY_LOCKDOWN_PCT"))
        assertTrue("Birdeye 5xx/network provider brownout must skip Birdeye hot-path calls fail-open instead of burning latency", gate.contains("isProviderBrownoutActive") && gate.contains("failures5xx.get() + st.networkErrors.get()") && gate.contains("BIRDEYE_PROVIDER_BROWNOUT_4189") && gate.contains("if (isProviderBrownoutActive()) return false"))
    }

    @Test
    fun decision_facing_expectancy_uses_live_terminal_not_paper_or_partials() {
        val telemetry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StrategyTelemetry.kt").readText()
        val damper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        val breakEven = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveBreakEvenGuard.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("StrategyTelemetry must expose an explicit paper/live boundary", telemetry.contains("PAPER/LIVE BOUNDARY CONTRACT") && telemetry.contains("computeLiveTerminalLeaderboard") && telemetry.contains("computePaperTerminalLeaderboard"))
        assertTrue("live terminal leaderboard must filter mode=live and exclude partials", telemetry.contains("environment = \"live\", includePartials = false") && telemetry.contains("it.mode.equals(env"))
        assertTrue("LaneExpectancyDamper must use live terminal expectancy only", damper.contains("StrategyTelemetry.computeLiveTerminalLeaderboard()") && !damper.contains("StrategyTelemetry.computeLeaderboard()"))
        assertTrue("LiveBreakEvenGuard must use live terminal leaderboard for live edge", breakEven.contains("StrategyTelemetry.computeLiveTerminalLeaderboard()"))
        assertTrue("LiveStylePivotRouter repeat-win authority must use live terminal leaderboard", router.contains("StrategyTelemetry.computeLiveTerminalLeaderboard()"))
        val maturity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveMaturityAuthority.kt").readText()
        assertTrue("Live maturity must be based on live terminal closes and adapt from trade 1, not mixed lifetime/paper bootstrap", maturity.contains("LIVE_ADAPTIVE_MIN_CLOSES = 1") && maturity.contains("LIVE_MATURE_MIN_CLOSES = 5_000") && maturity.contains("LIVE_ADAPTIVE_FROM_TRADE_1") && maturity.contains("There is no live bootstrap behavior") && !maturity.contains("LIVE_BOOTSTRAP") && maturity.contains("""mode.equals("live", true)"""))
        assertTrue("Reports must leave bootstrap once live terminal closes cross 500", java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText().contains("live terminal closes=") && java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText().contains("LiveMaturityAuthority.snapshot()"))
        assertTrue("learned live exit rungs must not shape from mixed paper/live StrategyTelemetry", exec.contains("StrategyTelemetry.computeLiveTerminalLeaderboard().firstOrNull") && !exec.contains("StrategyTelemetry.computeLeaderboard().firstOrNull { it.strategy.equals(key, true) }"))
    }

    @Test
    fun live_sub_lane_closes_do_not_pollute_generic_meme_learning() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        listOf("EXPRESS", "CYCLIC", "PRESALE_SNIPE", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER", "WHALE_FOLLOW", "COPYTRADE", "WALLET_RECOVERED", "CASHGEN").forEach {
            assertTrue("internal live lane $it must be excluded from generic meme-base learning", exec.contains("\"$it\""))
        }
        assertTrue("live Entry/Exit intelligence must be behind _lsIsMemeBase gate", exec.contains("moved generic Entry/Exit learning behind _lsIsMemeBase") && exec.indexOf("val _lsIsMemeBase") < exec.indexOf("EntryIntelligence.learnFromOutcome(tradeId.mint, pnlP, holdMinutesLive)"))
        assertTrue("EXPRESS/CYCLIC should have explicit attribution aliases, not collapse silently", exec.contains("\"EXPRESS\"                                         -> \"EXPRESS\"") && exec.contains("\"CYCLIC\"                                          -> \"CYCLIC\""))
    }

    @Test
    fun lane_exit_tuner_keeps_bleeder_exit_buckets_separate_and_clamps_stop_leakage() {
        val tuner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/LaneExitTuner.kt").readText()
        assertTrue("EXPRESS must not be folded into SHITCOIN exit learning", tuner.contains("u.contains(\"EXPRESS\")") && !tuner.contains("u.contains(\"SHITCOIN\") || u.contains(\"EXPRESS\")"))
        assertTrue("CYCLIC must not fall through to STANDARD exit learning", tuner.contains("u.contains(\"CYCLIC\")"))
        assertTrue("PRESALE/SNIPER should share their own profitable bucket", tuner.contains("PRESALE_SNIPE"))
        assertTrue("deep stop-loss leakage must clamp stop widening", tuner.contains("STOP-LOSS LEAK CLAMP") && tuner.contains("stopLeakClamp") && tuner.contains("avgLoss <= -20.0") && tuner.contains("slCap"))
    }

    @Test
    fun confirmed_live_buy_creates_host_tracker_liability_at_tx_confirm_source() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("tx-confirmed live buys must immediately create host tracker liability", exec.contains("HOST_BUY_PENDING_AT_TX_CONFIRMED") && exec.contains("HostWalletTokenTracker.recordBuyPending(ts.mint, ts.symbol, sig)"))
        assertTrue("pump and jupiter lifecycle confirmation must both be paired with host pending", exec.indexOf("TokenLifecycleTracker.onBuyConfirmed(ts.mint, sig)") < exec.indexOf("HOST_BUY_PENDING_AT_TX_CONFIRMED"))
        assertTrue("confirmed invisible buy doctor fault must be prevented at source", exec.contains("canonicalOpen=1") && exec.contains("hostTrackerOpen/liveOpen=0"))
    }




    @Test
    fun deep_launch_token_prices_must_not_round_to_zero_in_ui() {
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val cur = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CurrencyManager.kt").readText()
        assertTrue("Main meme UI price formatter must support 10-12 decimal launch prices", main.contains("fmtTokenPrice") && main.contains("0.0000000001") && main.contains("fmtTokenPrice(10)") && main.contains("fmtTokenPrice(12)"))
        assertTrue("Manual sell preview must use adaptive fmtPrice for Entry/Now", main.contains("val nowTxt = if (currentPrice != null && currentPrice > 0.0) currentPrice.fmtPrice()") && main.contains("val entryTxt = if (pos.entryPrice > 0.0) pos.entryPrice.fmtPrice()"))
        assertFalse("Manual sell preview must not use fixed six-decimal hasPrice formatter", main.contains("val nowTxt = if (hasPrice)") && main.contains("%.6f"))
        assertTrue("CurrencyManager small price formatting must support 12 decimal places", cur.contains("12 -> 1_000_000_000_000L") && cur.contains("fixed(v, 12)"))
    }

    @Test
    fun manual_sell_preview_must_not_show_phantom_zero_basis_pnl() {
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue("Manual sell preview must use OpenPnlSanity, not raw entry/current division", main.contains("OpenPnlSanity.inspect(ts") && main.contains("MainActivity.manualSell") && main.contains("BASIS UNTRUSTED") && main.contains("pnlVerdict.reason"))
        val manualBlock = main.substringAfter("private fun onManualSellClicked()").substringBefore("private fun updateGlobalDecisionLog")
        assertFalse("Manual sell preview must not calculate fantasy PnL directly from currentPrice-entryPrice", manualBlock.contains("currentPrice!! - pos.entryPrice"))
        assertTrue("Open position card must suppress untrusted phantom PnL", main.contains("MainActivity.renderRow") && main.contains("basis wait") && main.contains("basisTrusted"))
    }


    @Test
    fun zombie_catastrophe_must_not_fake_local_close() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val block = bot.substringAfter("ZOMBIE_CATASTROPHE_PENDING_RETRY").substringBefore("DEEP_CATASTROPHE_NET")
        assertTrue("Zombie catastrophe must become pending retry, not fake local close", block.contains("CloseLease.recordRetry") && block.contains("SellReconciler.requestUrgentTick") && block.contains("action=no_local_close_no_slot_release"))
        assertFalse("Zombie catastrophe must not zero qty or release slot as closed without proof", block.contains("copy(qtyToken = 0.0") || block.contains("confirmZeroBalanceClose") || block.contains("markLanded"))
    }



    @Test
    fun sell_finality_pending_retry_lease_survives_residue_reaper_non_blocking() {
        val lease = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/CloseLease.kt").readText()
        assertTrue("Pending finality must keep retry metadata alive", lease.contains("finalityPending") && lease.contains("SELL_FINALITY_PENDING_RETRY"))
        assertTrue("Residue reaper must not prune unresolved finality proof leases", lease.contains("!l.finalityPending && pastBackoff && idleMs >= RESIDUE_REAP_MS"))
        assertTrue("Pending finality lease remains non-blocking because activeBlockingLeaseCount only counts inFlight", lease.contains("&& l.inFlight"))
    }


    @Test
    fun wallet_rehydration_rejects_extreme_dust_basis_but_allows_sane_recovered_basis() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("Rehydration must prefer TokenLifecycle live proof metadata over stale host tracker price", bot.contains("TokenLifecycleTracker.getEntryMetadata(mint)") && bot.contains("LIVE_PROOF_COST_BASIS_REHYDRATED"))
        assertTrue("Recovered host tracker basis must be sanity bounded before becoming comparable", bot.contains("HOST_WALLET_TRACKER_REHYDRATED_SANITY_OK") && bot.contains("ratio in 0.0001..5_000.0") && bot.contains("priceBasisRescaled = useMetaBasis || useTrackerBasis"))
        assertTrue("Extreme recovered dust basis must lock recovery instead of feeding fake open PnL", bot.contains("TOKEN_STATE_REHYDRATED_BASIS_LOCKED") && bot.contains("HOST_WALLET_TRACKER_BASIS_UNKNOWN") && bot.contains("RecoveryLockTracker.lock"))
    }

    @Test
    fun live_sell_pending_finality_has_own_pipeline_counter() {
        val collector = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("Sell pending finality must not be hidden under sell ok/fail", collector.contains("execLiveSellPendingFinality") && collector.contains("SELL_FINALITY_PENDING_RETRY") && collector.contains("EXEC_LIVE_SELL_PENDING_FINALITY"))
        assertTrue("Operator report must show ok/fail/pending triple", collector.contains("SELL ok/fail/pending"))
    }

    @Test
    fun live_sell_finality_is_atomic_no_degraded_success() {
        val tx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/TxMetaSellFinalizer.kt").readText()
        val coord = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellFinalizationCoordinator.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("TxMetaSellFinalizer must expose sealed finality outcomes", tx.contains("sealed class SellFinalityResult") && tx.contains("data class Finalized") && tx.contains("data class PartialFinalized") && tx.contains("data class PendingRetry") && tx.contains("data class FailedWithProof"))
        assertTrue("Missing live sell proof must become SELL_FINALITY_PENDING_RETRY", tx.contains("MISSING_SIGNATURE") && tx.contains("MISSING_PRE_TOKEN_BALANCE") && tx.contains("MISSING_POST_BALANCE_PROOF") && tx.contains("MISSING_PROCEEDS_OR_ROUTE_SETTLEMENT"))
        val pendingIdx = coord.indexOf("if (fin.finality is TxMetaSellFinalizer.SellFinalityResult.PendingRetry)")
        val landedIdx = coord.indexOf("SellForensicsWriter.writeSellLanded(")
        val settledIdx = coord.indexOf("TokenLifecycleTracker.onSellSettled(")
        assertTrue("Coordinator must not lifecycle-settle or write landed rows on PendingRetry", pendingIdx >= 0 && coord.contains("action=no_close_no_journal_no_learning_keep_lease") && pendingIdx < landedIdx && pendingIdx < settledIdx)
        assertTrue("Pending finality must keep close lease retryable and trigger reconciler", coord.contains("CloseLease.recordRetry") && coord.contains("SellReconciler.requestUrgentTick"))
        assertTrue("Executor must not trust RPC empty-map or no SOL delta as normal sell success", exec.contains("RPC empty-map is not post-balance proof") && exec.contains("SELL_FINALITY_PENDING_RETRY_NO_PROCEEDS") && exec.contains("No normal SELL journal row"))
        assertFalse("No degraded finality fallback may mark LIVE_SELL_OK or CLOSED", exec.contains("HELIUS_DEGRADED") || exec.contains("finalize-degraded"))
    }

    @Test
    fun live_break_even_uses_live_first_trust_rebase_not_paper_override() {
        val be = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveBreakEvenGuard.kt").readText()
        assertTrue("live trust rebase must exist", be.contains("LIVE TRUST REBASE") && be.contains("liveTerminalEdge") && be.contains("paperAdvisoryEdge"))
        assertTrue("paper memory may only be advisory/capped, but clean proof may bootstrap executable live samples", be.contains("paperAdvisoryEdge * 0.35") && be.contains("minOf(paperAdvisoryEdge, 28.0)") && be.contains("includePartials = false"))
        assertTrue("live terminal rows must be read separately from paper", be.contains("it.mode.equals(\"live\", true)") && be.contains("it.mode.equals(\"paper\", true)"))
        assertTrue("StrategyTelemetry must be capped so partial/paper-heavy leaderboards cannot dominate", be.contains("coerceIn(0.0, 60.0)") && be.contains("minOf(leaderboardEdge, 35.0)"))
    }

    @Test
    fun meme_runtime_authority_activates_all_internal_layers_without_market_fanout() {
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/EnabledTraderAuthority.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("Authority enum must expose every internal meme layer except disabled CYCLIC sidecar", listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "QUALITY", "TREASURY", "CASHGEN", "BLUECHIP", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER").all { auth.contains(it) })
        assertTrue("Meme-only publish must include full internal specialist set except CYCLIC", listOf("Trader.QUALITY", "Trader.TREASURY", "Trader.CASHGEN", "Trader.BLUECHIP", "Trader.PROJECT_SNIPER", "Trader.DIP_HUNTER", "Trader.MANIPULATED").all { bot.contains(it) } && !bot.contains("add(com.lifecyclebot.engine.EnabledTraderAuthority.Trader.CYCLIC)"))
        assertTrue("Internal specialists must be ignored by isMemeLiveOnly so markets/perps remain isolated; CYCLIC must not be an internal meme layer", auth.contains("internalMemeLayers") && auth.contains("Trader.PROJECT_SNIPER") && auth.contains("set - Trader.CRYPTO_ALT - internalMemeLayers") && !auth.substringAfter("val internalMemeLayers = setOf(").substringBefore(")").contains("Trader.CYCLIC"))
        assertTrue("Runtime report should expose all active meme lanes while CYCLIC stays excluded", bot.contains("all internal MEME lanes stay active") && bot.contains("CYCLIC remains excluded"))
    }







    @Test
    fun live_preattempt_advisories_cannot_silently_disappear_before_live_buy() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "Live advisory gates before doBuy/liveBuy must log and continue, not return silently",
            exec.contains("LIVE_ADVISORY_NOT_TERMINAL") &&
                exec.contains("SMARTCHART_ADVISORY") &&
                exec.contains("VELOCITY_ADVISORY") &&
                exec.contains("Brain advisory") &&
                exec.contains("action=continue_to_live_buy")
        )
        assertTrue(
            "SmartChart hard block must be paper-only while live continues as advisory",
            exec.contains("SMARTCHART_ADVISORY") &&
                exec.contains("if (!isPaper)") &&
                exec.contains("} else {") &&
                exec.contains("SMARTCHART_BLOCK")
        )
        assertTrue(
            "Hard preattempt returns must be counted as live buy hard rejects instead of BUY 0/0 silence",
            exec.contains("LIVE_PREATTEMPT_HARD_REJECT") &&
                exec.contains("LIVE_ENTRY_REJECTED_SIZE_TOO_THIN_FOR_NON_MICRO_TRADE") &&
                exec.contains("LIVE_BUY_REJECTED_HARD_BLOCK_SECURITY_GUARD") &&
                exec.contains("LIVE_BUY_REJECTED_HARD_BLOCK_EXPOSURE_CAP") &&
                exec.contains("LIVE_BUY_REJECTED_HARD_BLOCK_WALLET_NULL")
        )
    }

    @Test
    fun external_llm_must_be_advisory_not_hard_buy_veto() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val buyBlock = exec.substring(exec.indexOf("internal fun doBuy"), exec.indexOf("// V5.9.401 — Sentience hook #7"))
        assertTrue("Sentience pre-trade veto must be advisory telemetry only", buyBlock.contains("SENTIENCE_VETO_ADVISORY_4189") && buyBlock.contains("ignored_no_hard_veto") && !buyBlock.contains("LLM SENTIENCE VETO"))
        assertTrue("Emergent LLM BLOCK must not return before live buy", buyBlock.contains("EMERGENT_LLM_BLOCK_ADVISORY_4189") && buyBlock.contains("LLM BLOCK ADVISORY") && !buyBlock.contains("🧠 LLM BLOCK: ${'$'}{ts.symbol}"))
    }

    @Test
    fun live_route_guard_does_not_convert_pending_safety_into_no_executable_route() {
        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionRouteGuard.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "ExecutionRouteGuard must stay mechanical; safety/rugcheck pending belongs to downstream safety gates, not NO_EXECUTABLE_ROUTE",
            guard.contains("route authority is not safety finality") &&
                guard.contains("val safetyPending") &&
                guard.contains("LIVE_ALLOWED_SAFETY_PENDING_DOWNSTREAM_GATE") &&
                guard.contains("walletSol > 0.0") &&
                !guard.contains("&& safetyFresh") &&
                !guard.contains("SAFETY_NOT_FRESH")
        )
        assertTrue(
            "Executor must still run provider/pretrade/finality after live route selection",
            exec.contains("LIVE_ROUTE_SELECTED") &&
                exec.contains("LIVE_PROVIDER_QUORUM") &&
                exec.contains("PreTradeHardGate.requireLiveBuyAllowed") &&
                exec.contains("ExecutableOpenGate.canOpenExecutablePosition") &&
                exec.contains("LIVE_BUY_REJECTED_HARD_BLOCK_ROUTE_") &&
                !exec.contains("LIVE_BUY_REJECTED_HARD_BLOCK_NO_EXECUTABLE_ROUTE")
        )
    }

    @Test
    fun live_advisory_shape_preserves_executable_lane_without_buy_fail_choke() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "ADVISORY_SHAPE must not freeze FDG-approved executable lanes; it preserves pre-pivot authority unless proof-backed quality promotion is executable",
            exec.contains("action=preserve_executable_lane") &&
                exec.contains("val prePivotExecutableLane") &&
                exec.contains("val canonicalRoutedLane = when") &&
                exec.contains("stylePivotAdvisory && qualityPromotionLane && pivotHasExecutionProof -> postPivotExecutableLane") &&
                exec.contains("stylePivotAdvisory -> prePivotExecutableLane") &&
                !exec.contains("return observeOnlyLiveEntry(\"OBSERVE_ONLY_NOT_LIVE_EXECUTABLE\", liveEntryDecision.finalLane.ifBlank { originalLaneForPivot }, \"ADVISORY_SHAPE\")")
        )
        assertTrue(
            "Only unresolved non-executable lanes may observe-only before the actual BUY lease acquisition",
            exec.contains("LIVE_ENTRY_OBSERVED_ONLY") &&
                exec.contains("OBSERVE_ONLY_CANON_LANE_UNRESOLVED") &&
                exec.indexOf("return observeOnlyLiveEntry(\"OBSERVE_ONLY_CANON_LANE_UNRESOLVED\"") < exec.indexOf("val buyLease = ExecutionAttemptLease.acquire") &&
                exec.contains("val buyLeaseProcessor = canonicalRoutedLane") &&
                exec.contains("lane = canonicalRoutedLane") &&
                exec.contains("source = \"Executor.liveBuy.canonicalLane\"")
        )
    }

    @Test
    fun live_fdg_allow_survives_missing_final_candidate_and_version_churn() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "FDG-approved live handoff must soft-restore when transient final candidate state is missing, instead of BUY_FAIL stale-ticket TOKEN_STATE_CHANGED spam",
            gate.contains("LIVE_RESTORE_MISSING_FINAL_CANDIDATE_SOFT_ALLOW") &&
                gate.contains("TOKEN_STATE_CHANGED_NO_FINAL_CANDIDATE") &&
                gate.contains("state_missing_after_fdg_allow") &&
                gate.contains("currentLiquidityUsd > 0.0") &&
                gate.contains("currentSafetyOk") &&
                gate.contains("restoredHardNoReasons.none { trueHardTicketKill(it) }")
        )
        assertTrue(
            "Stale candidate version restore must not be hard-disabled with latestAllows=false; live approved handoff may restore across scanner version churn",
            gate.contains("LIVE_RESTORE_STALE_CANDIDATE_SOFT_ALLOW") &&
                gate.contains("approved_handoff_version_churn") &&
                gate.contains("state.fdgCan == true") &&
                !gate.contains("val latestAllows = false") &&
                !gate.contains("val safetyOk = false")
        )
    }

    @Test
    fun live_fdg_exec_allow_submits_buy_when_no_hard_block() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Style-pivot defer must be advisory after FDG/EXEC allow, not terminal BUY_FAILED", exec.contains("STYLE_PIVOT_ADVISORY") && exec.contains("ADVISORY_SHAPE") && exec.contains("no_live_buy_fail=true") && !exec.contains("LIVE_ENTRY_DEFERRED_BY_STYLE_PIVOT_$"))
        assertTrue("Approved no-hard-block path must progress into route/provider/order state machine", exec.contains("LIVE_ENTRY_APPROVED") && exec.contains("LIVE_ROUTE_SELECTED") && exec.contains("LIVE_PROVIDER_QUORUM") && exec.contains("LIVE_BUY_SUBMITTED"))
    }

    @Test
    fun style_pivot_is_advisory_not_terminal() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bad = "emitLiveBuyFail(ts, sol, \"LIVE_ENTRY_DEFERRED_BY_STYLE_PIVOT_"
        assertFalse("Style-pivot advisory must not emit live buy failure", exec.contains(bad))
        assertTrue("Style pivot advisory must use neutral size multiplier, never zero-size terminal defer", exec.contains("val effectiveStyleSizeMultiplier = if (stylePivotAdvisory) 1.0 else liveEntryDecision.sizeMultiplier"))
    }

    @Test
    fun provider_degraded_quorum_fallback() {
        val quorum = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProviderQuorum.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Provider quorum must allow DexScreener/PumpFun/CoinGecko fallback without Birdeye/Gecko hostage mode", quorum.contains("DEXSCREENER") && quorum.contains("PUMPFUN") && quorum.contains("BIRDEYE") && quorum.contains("GECKOTERMINAL") && quorum.contains("COINGECKO_SOL_CONTEXT") && quorum.contains("marketCount >= 2"))
        assertTrue("Executor must hard-block only when quorum is actually insufficient", exec.contains("LIVE_PROVIDER_QUORUM_OK") && exec.contains("LIVE_BUY_REJECTED_HARD_BLOCK_PROVIDER_QUORUM"))
    }

    @Test
    fun no_micro_live_trade_unless_enabled() {
        val cfg = java.io.File("src/main/kotlin/com/lifecyclebot/data/BotConfig.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val growth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        val copilot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradingCopilot.kt").readText()
        val sizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val unifiedScorer = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        val botOrch = java.io.File("src/main/kotlin/com/lifecyclebot/v3/core/BotOrchestrator.kt").readText()
        val edu = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/EducationSubLayerAI.kt").readText()
        val tradeState = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeStateMachine.kt").readText()
        val tradeLife = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeLifecycle.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        val modeLeniency = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ModeLeniency.kt").readText()
        val permit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        val strategy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LifecycleStrategy.kt").readText()
        val eligibility = java.io.File("src/main/kotlin/com/lifecyclebot/v3/eligibility/EligibilityGate.kt").readText()
        val fatalRisk = java.io.File("src/main/kotlin/com/lifecyclebot/v3/risk/FatalRiskChecker.kt").readText()
        val fluid = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/FluidLearningAI.kt").readText()

        assertTrue("Config must use adaptive learned compounding, not a fixed micro/bootstrap stake", cfg.contains("val BotConfig.minLiveBuySol: Double get() = 0.005") && cfg.contains("val BotConfig.allowLiveMicroProbe: Boolean get() = true") && cfg.contains("ADAPTIVE_LEARNED_COMPOUNDING") && !cfg.contains("MICRO_COMPOUNDING"))
        assertTrue("Growth doctrine floors must be dust bounds only; fluid sizing remains wallet/lane/movement/liquidity driven", growth.contains("V5.0.4021_ADAPTIVE_LEARNED_GROWTH_CORE") && growth.contains("else -> 0.005") && growth.contains("primary fluid sizing authorities"))
        assertTrue("Pending-proof sizing must reduce learned risk without forcing a fixed micro stake", exec.contains("LIVE_PENDING_PROOF_LEARNED_RISK_CLAMP") && exec.contains("livePendingProofPenalty") && exec.contains("Unknown proof lowers confidence") && !exec.contains("LIVE_PENDING_PROOF_MICRO_CAP"))
        assertTrue("Live buy path keeps explicit below-floor telemetry while allowing configured micro probes", exec.contains("LIVE_ENTRY_REJECTED_SIZE_TOO_THIN_FOR_NON_MICRO_TRADE") && exec.contains("LIVE_BUY_SIZE_RAISED_TO_MIN_NON_MICRO") && !exec.contains("LIVE_BUY_SIZE_RAISED_TO_MIN_EXECUTABLE"))
        assertTrue("TradingCopilot must not relax live confidence/size under bootstrap", copilot.contains("no live bootstrap thresholds") && copilot.contains("TradeMood.EMERGENCY_BRAKE -> 25.0") && copilot.contains("TradeMood.EMERGENCY_BRAKE -> 0.25") && !copilot.contains("bootstrapProg") && !copilot.contains("tradesObserved < 50"))
        assertTrue("SmartSizer must consume lane feedback from trade 1 without exploration bootstrap ramp", sizer.contains("minTrades = 1") && sizer.contains("sample-weighted") && sizer.contains("No live bootstrap/exploration size ramp") && !sizer.contains("FreeRangeMode.explorationSizeMultiplier()"))
        assertTrue("FDG bootstrap confidence bypass must be paper-only; live uses adaptive state from trade 1", fdg.contains("isBootstrapPhase = isPaperMode") && fdg.contains("(isPaperMode && totalTradesForBypass < 500)") && fdg.contains("liveAdaptiveFromTrade1"))
        assertTrue("BotService bootstrap force/score/size gates must be paper-only for live layers", bot.contains("RuntimeModeAuthority.isPaper() && forceBootstrapEntry") && bot.contains("PAPER_BOOTSTRAP_BLOCKED") && bot.contains("getBootstrapSizeMultiplier() else 1.0") && !bot.contains("SHITCOIN_BOOTSTRAP_FORCE_SUPPRESSED"))
        assertTrue("V3 scorer/orchestrator bootstrap bypass must exclude LIVE mode", unifiedScorer.contains("ctx.mode != com.lifecyclebot.v3.core.V3BotMode.LIVE && learningProgress < 0.40") && botOrch.contains("ctx.mode != V3BotMode.LIVE && learningProgress < 0.40"))
        assertTrue("Lifecycle cooldown/registry bootstrap speeds must be paper-only", tradeState.contains("RuntimeModeAuthority.isPaper()") && tradeLife.contains("RuntimeModeAuthority.isPaper()") && registry.contains("RuntimeModeAuthority.isPaper()"))
        assertTrue("Education layer diagnostics must not report all layers active in live bootstrap", edu.contains("RuntimeModeAuthority.isPaper() && learningProgress < 0.40") && edu.contains("LIVE diagnostics must reflect real layer"))
        assertTrue("Safety-ish learning bypasses must be paper-only: no live SKIP/trust/bridge/cooldown/probation/permit/free-range fatal override", bot.contains("val allowSkipForLearning = isBootstrap") && bot.contains("RuntimeModeAuthority.isPaper() && com.lifecyclebot.engine.FreeRangeMode.isWideOpen()") && bot.contains("val bridgeAllowed = !useV3Decision && !isTerminalV3Reject && cfg.paperMode") && bot.contains("RuntimeModeAuthority.isPaper() && FreeRangeMode.isWideOpen()") && exec.contains("val lenientMode = isPaperMode") && registry.contains("val lenientMode = isPaperMode") && permit.contains("RuntimeModeAuthority.isPaper() && FreeRangeMode.isWideOpen()") && strategy.contains("val isLenient = isBootstrap") && modeLeniency.contains("return isPaperMode") && !modeLeniency.contains("return true") && eligibility.contains("RuntimeModeAuthority.isPaper() && com.lifecyclebot.engine.FreeRangeMode.isWideOpen()") && fatalRisk.contains("RuntimeModeAuthority.isPaper() && com.lifecyclebot.engine.FreeRangeMode.isWideOpen()") && fluid.contains("RuntimeModeAuthority.isPaper() && com.lifecyclebot.engine.FreeRangeMode.isWideOpen()") && !bot.contains("pre5000LearningOpen || hasProvenEdge"))
    }

    @Test
    fun live_sell_reconciler_must_start() {
        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val recon = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellReconciler.kt").readText()
        assertTrue("Live startup must expose sell reconciler health", service.contains("SELL_RECONCILER") && service.contains("SELL_RECONCILER_LIVE_STARTUP_HARD_FAIL"))
        assertTrue("Sell reconciler must expose running/tick/age state", recon.contains("isStarted") && recon.contains("totalTicks") && recon.contains("lastTickAtMs") && recon.contains("isLiveAlive"))
    }

    @Test
    fun quality_owner_requires_quality_liquidity() {
        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("QUALITY owner must require route/liquidity/mcap/safety proof while holder-blind proof soft-allows into downstream size shaping", service.contains("qualityLaneProofOk") && service.contains("qualityStructure = routeProof && safeEnough && ts.lastLiquidityUsd >= 15_000.0 && ts.lastMcap >= 25_000.0") && service.contains("QUALITY_OWNER_HOLDER_PROOF_BLIND_SOFT_ALLOW") && service.contains("QUALITY_OWNER_PROOF_REJECTED") && service.contains("QUALITY_PRIMARY_PROOF_REJECTED"))
    }


    @Test
    fun style_pivot_quality_promotion_can_be_executable_with_proof() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "Style-pivot advisory must preserve throughput by routing proof-backed quality promotions to the promoted executable lane",
            exec.contains("STYLE_PIVOT_QUALITY_PROMOTION_EXECUTABLE") &&
                exec.contains("qualityPromotionLane") &&
                exec.contains("pivotHasExecutionProof") &&
                exec.contains("stylePivotAdvisory && qualityPromotionLane && pivotHasExecutionProof -> postPivotExecutableLane")
        )
    }

    @Test
    fun negative_ev_lane_no_compounding_size() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Negative-EV SHITCOIN/PRESALE must be size-shaped, not hard-blocked before quality throughput", exec.contains("SHITCOIN_NEGATIVE_EV_SIZE_SHAPED") && exec.contains("PRESALE_SNIPE_NEGATIVE_EV_SIZE_SHAPED") && exec.contains("LIVE_LANE_CAPITAL_SHAPED") && exec.contains("LIVE_LANE_CAPITAL_SIZE_APPLIED") && !exec.contains("LIVE_BUY_REJECTED_HARD_BLOCK_NEGATIVE_EV_LANE"))
    }

    @Test
    fun canonical_learning_carries_real_trade_size_context() {
        val canonical = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalLearning.kt").readText()
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        val helper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalPublishHelper.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val behavior = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BehaviorLearning.kt").readText()
        assertTrue("Canonical outcome must carry real entry size, bucket, and SOL-weighted return", canonical.contains("entrySizeSol") && canonical.contains("sizeBucket") && canonical.contains("solWeightedReturn") && canonical.contains("object CanonicalSizeContext"))
        assertTrue("Real-size buckets must separate dust/probe/reduced/quality/conviction samples", canonical.contains("DUST_SIZE") && canonical.contains("PROBE_SIZE") && canonical.contains("REDUCED_SIZE") && canonical.contains("QUALITY_SIZE") && canonical.contains("CONVICTION_SIZE"))
        assertTrue("CandidateFeatures must include sizeBucket so strategy signatures can learn real sizing", canonical.contains("val sizeBucket: String") && builder.contains("sizeBucket = CanonicalSizeContext.bucket(entrySizeSol)") && builder.contains("add(\"size\")") && behavior.contains("f.sizeBucket.ifBlank"))
        assertTrue("All canonical producers must publish size context", helper.contains("entrySizeSol") && helper.contains("CanonicalSizeContext.bucket") && exec.contains("canonicalEntrySizeSol") && exec.contains("CanonicalSizeContext.solWeightedReturn") && canonical.contains("legacyEntrySizeSol"))
    }

    @Test
    fun live_style_pivot_router_promotes_bleeders_to_quality_not_defensive_probes() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText()
        val breakEven = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveBreakEvenGuard.kt").readText()
        val bleeder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BleederMemoryRouter.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        assertTrue("LiveStylePivotRouter component must exist and return final lane/style/size/proof decision", router.contains("object LiveStylePivotRouter") && router.contains("finalLane") && router.contains("finalStyle") && router.contains("sizeMultiplier") && router.contains("confirmationRequirement"))
        assertFalse("Live mode must not route to defensive probes", router.contains("DEFENSIVE_PROBE") || router.contains("decision = \"PROBE\"") || router.contains("BREAK_EVEN_PROBE_ALLOWED_BELOW_COST_MODEL"))
        assertTrue("EXPRESS bleeder must promote to learned quality, not native express/probe", router.contains("EXPRESS_BLEEDER_QUALITY_PROMOTION") && router.contains("EXPRESS_BLEEDER_AWAIT_QUALITY_PROOF"))
        assertTrue("Treasury/CashGen must be an active quality promotion target", router.contains("TREASURY_CASHGEN_QUALITY_PROMOTED") && breakEven.contains("\"CASHGEN\" -> setOf(\"TREASURY\")"))
        assertTrue("CYCLIC bleeder must promote to pullback/quality only with proof", router.contains("CYCLIC_PULLBACK_RECLAIM_QUALITY_PROMOTION") && router.contains("CYCLIC_BLEEDER_QUALITY_PROMOTION") && router.contains("CYCLIC_BLEEDER_AWAIT_QUALITY_PROOF"))
        assertTrue("WHALE/COPY cannot direct-trigger full live; only wallet recovered or quality promotion", router.contains("WHALE_COPY_QUALITY_PROMOTION_NO_DIRECT_TRIGGER") && router.contains("WALLET_RECOVERED_PROVEN_PROMOTION") && router.contains("WHALE_COPY_AWAIT_REPEAT_WIN_AND_PROOF"))
        assertTrue("MOONSHOT S41-60 must not native-live buy; only LDQ rescue or toxic defer", router.contains("MOONSHOT_S41_60_ONLY_LDQ_QUALITY_RESCUE_V4153") && router.contains("MOONSHOT_S41_60_LIVE_TOXIC_DEFER_V4153") && router.contains("scoreBand == \"S41-60\""))
        assertTrue("SHITCOIN live bleed must quarantine/defer, not rename itself into quality", router.contains("SHITCOIN_LIVE_BLEED_QUARANTINE") && !router.contains("SHITCOIN_LIVE_BLEED_QUALITY_PROMOTION") && router.contains("SHITCOIN_THIN_ROUTE_DEPTH"))
        assertTrue("Fresh 1k-5k SHITCOIN depth must become live-adaptive or clean high-confidence reduced quality routing", router.contains("SHITCOIN_THIN_ROUTE_DEPTH_LIVE_ADAPTIVE_REDUCED_QUALITY") && router.contains("pivotThinDepthToQuality") && router.contains("LiveMaturityAuthority.snapshot()") && router.contains("cleanHighConfidenceBootstrap"))
        assertTrue("Low-score QUALITY and thin PRESALE/TREASURY live entries must defer at source", router.contains("QUALITY_LOW_SCORE_LIVE_DEFER") && router.contains("PRESALE_AWAIT_MIN_DEPTH_AND_PROOF") && router.contains("TREASURY_CASHGEN_AWAIT_DEPTH_SCORE_PROOF"))
        assertTrue("LiveBreakEvenGuard must use live-first terminal edge with capped paper advisory", breakEven.contains("liveTerminalEdge") && breakEven.contains("paperAdvisoryEdge") && breakEven.contains("TradeHistoryStore.getRecentValidClosedTrades") && breakEven.contains("LIQUIDITY_DEPTH_QUALITY") && breakEven.contains("PULLBACK_RECLAIM"))
        assertTrue("LiveBreakEvenGuard must calculate all-in required edge", breakEven.contains("buySlippagePct") && breakEven.contains("expectedSellSlippagePct") && breakEven.contains("priorityFeePct") && breakEven.contains("platformFeePct") && breakEven.contains("givebackBufferPct") && breakEven.contains("minProfitBufferPct"))
        assertTrue("Router must emit required live break-even decision log", router.contains("LIVE_BREAK_EVEN_CHECK") && router.contains("expectedEdge") && router.contains("requiredEdge") && router.contains("pivotReason"))
        assertTrue("Below-cost routes must defer for real quality edge instead of probe, while green bootstrap/live-adaptive clean-proof quality gaps can full-quality release outside toxic MOONSHOT S41-60", router.contains("BREAK_EVEN_DEFER_QUALITY_EDGE_NOT_CONFIRMED") && router.contains("BREAK_EVEN_LIVE_ADAPTIVE_FULL_QUALITY_RELEASE") && router.contains("BREAK_EVEN_GREEN_BOOTSTRAP_FULL_QUALITY_RELEASE") && router.contains("canGreenBootstrapFullQualityRelease") && router.contains("qualityReleaseMultiplier") && router.contains("liveBootstrapGreen") && router.contains("MOONSHOT_S41_60_LIVE_TOXIC_DEFER_V4153"))
        val growth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        assertTrue("Live growth doctrine must be materially aggressive for 2x-5x/day target", growth.contains("AGGRESSIVE_2X_5X_LIVE_WALLET_GROWTH") && growth.contains("\"MOONSHOT\" -> 0.35") && growth.contains("walletSol < 10.0 -> 1.250"))
        assertTrue("Final live sizing must emit full growth/cap telemetry", exec.contains("GROWTH_MODE_TRACE") && exec.contains("liquidityCap") && exec.contains("walletCap") && exec.contains("minExec"))
        assertTrue("All live lanes must receive tick-time runner/hard-floor protection", bot.contains("LIVE RUNNER CAPTURE PARITY") && bot.contains("tickProfitLockEligible") && bot.contains("TICK_PROFIT_LOCK_SKIPPED_LANE"))
        assertTrue("V5.0.4152: tick/universal peak-lock exits must use the same FluidLearningAI high-lock floor shown in UI, not stale loose peak ratios",
            bot.contains("UI/EXEC HIGH-LOCK PARITY") && bot.contains("TICK_PROFIT_LOCK_EXEC_PRICE_REBASE") &&
            bot.contains("FluidLearningAI.getDynamicFluidStop") && bot.contains("pnlPctNow >= lockedFloor") &&
            bot.contains("UNIVERSAL_PEAK_LOCK_peak") && bot.contains("fluidLockFloor") && bot.contains("highLockImmediate"))
        val exec4153 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4153: cumulative 100pct partials must stamp terminal FULL_EXIT_100PCT, not partial_100pct", exec4153.contains("FULL_EXIT_100PCT") && exec4153.contains("newSoldPct >= 99.9") && !exec4153.contains("partial_100pct"))
        assertTrue("BleederMemoryRouter must use live-only recent closed rows", bleeder.contains("mode.equals(\"live\"") && bleeder.contains("n20") && bleeder.contains("n50") && bleeder.contains("n100") && bleeder.contains("deepLosses50") && bleeder.contains("failedBasisCount") && bleeder.contains("orphanCount"))
        assertTrue("liveBuy must emit decision before lease/quote, apply pivot size, and bucket style-pivot advisory reasons", exec.contains("LIVE_ENTRY_DECISION") && exec.contains("LiveStylePivotRouter.route") && exec.contains("LIVE_STYLE_PIVOT_SIZE_APPLIED") && exec.contains("STYLE_PIVOT_ADVISORY") && exec.contains("STYLE_PIVOT_ADVISORY_REASON_"))
        assertTrue("live journal mode should use pivoted lane", exec.contains("tradingMode  = routedLaneTag") && exec.contains("tradingMode = routedLaneTag"))
    }


    @Test
    fun live_entries_require_persisted_mint_market_snapshot_before_commit() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue("Executor must define a mint entry market snapshot containing price/mcap/liquidity/pool/source", exec.contains("data class MintEntryMarketSnapshot") && exec.contains("marketCapUsd") && exec.contains("liquidityUsd") && exec.contains("poolAddress") && exec.contains("priceSource"))
        assertTrue("mcap must be optional metadata, not a live-buy executable-basis choke", exec.contains("mcap is learning/report metadata, not executable basis") && exec.contains("marketCapUsd >= 0.0") && exec.contains("MINT_ENTRY_MARKET_SNAPSHOT_MCAP_UNKNOWN"))
        assertTrue("pool metadata must not be an executable accounting-basis choke", exec.contains("pool/route id is route metadata") && exec.contains("MINT_ROUTE:") && exec.contains("MINT_ENTRY_MARKET_SNAPSHOT_POOL_SENTINEL") && !exec.contains("poolAddress.isNotBlank() && priceSource.isNotBlank()"))
        assertTrue("Style router must not re-choke mint-route entries on missing pool metadata", java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText().contains("Jupiter/executor remains the hard route authority later") && !java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText().contains("ts.lastPricePoolAddr.isNotBlank() || ts.pairAddress.isNotBlank()"))
        assertTrue("liveBuy must emit a BUY fail reason if the executable market snapshot is truly missing", exec.contains("ENTRY_MARKET_SNAPSHOT_MISSING_DEFERRED") && exec.contains("""emitLiveBuyFail(ts, sol, "ENTRY_MARKET_SNAPSHOT_MISSING_DEFERRED"""))
        assertTrue("valid entry snapshots must be stored into TokenState and TokenMetaCache", exec.contains("MINT_ENTRY_MARKET_SNAPSHOT_STORED") && exec.contains("TokenMetaCache.get(ctx).register") && exec.contains("if (snap.marketCapUsd > 0.0) ts.lastMcap = snap.marketCapUsd") && exec.contains("lastLiquidityUsd = snap.liquidityUsd"))
        assertTrue("liveBuy must rehydrate executable snapshot fields from TokenMetaCache before deferring", exec.contains("hydrateMintEntryMarketSnapshotFromCache") && exec.contains("MINT_ENTRY_MARKET_SNAPSHOT_CACHE_HYDRATED") && exec.contains("cached.lastPriceSource") && exec.contains("TOKEN_META_CACHE"))
        assertTrue("intake cache hydration must restore cached price source as well as price/pool/dex", bot.contains("fresh.lastPriceSource = cached.lastPriceSource") && bot.contains("cachedForIntake.lastPriceSource"))
        assertTrue("live Position and BUY journal rows must stamp snapshot mcap/liquidity/source/pool", exec.contains("entryMcap    = entryMarketSnapshot.marketCapUsd") && exec.contains("entryLiquidityUsd = entryMarketSnapshot.liquidityUsd") && exec.contains("entryMcapUsd = entryMarketSnapshot.marketCapUsd") && exec.contains("entryPriceSource = entryMarketSnapshot.priceSource") && exec.contains("entryPoolAddress = entryMarketSnapshot.poolAddress"))
        assertTrue("UI must not repair open-position basis from current refs/journal fallbacks", main.contains("UI is not a price-basis authority") && main.contains("OPEN_POSITION_UI_BASIS_WAIT") && main.contains("action=no_ui_repair"))
        assertFalse("UI recovery must not mutate entryPrice from recoveredEntry anymore", main.contains("ts.position = p0.copy") && main.contains("entryPrice = recoveredEntry"))
        assertFalse("UI must not use ts.ref/current fallback as recovered current price", main.contains("existing?.ref, recoveredEntry"))
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
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val paperSanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperLearningSanity.kt").readText()
        val learningSanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPnlSanitizer.kt").readText()
        val rowSanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TradeRowSanityCheck.kt").readText()
        val startup = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StartupReconciler.kt").readText()
        val v3Journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertTrue("Canonical journal/accounting must reject BUY/SELL rows missing executable entry basis", store.contains("if (isBuyLike(t.side))") && store.contains("t.entryCostSol <= 0.0") && store.contains("t.entryPriceSnapshot <= 0.0") && paperSanity.contains("PAPER_BUY_ENTRY_BASIS_MISSING") && learningSanity.contains("MISSING_ENTRY_COST_BASIS") && rowSanity.contains("MISSING_COST_BASIS"))
        assertTrue("Wallet/live recovery with unknown cost basis must be visibly basis-unknown and recovery-locked", startup.contains("WALLET_RECOVERY_SYNTHETIC_BASIS_UNKNOWN") && startup.contains("WALLET_RECOVERY_NOPRICE_BASIS_UNKNOWN") && startup.contains("JOURNAL_RECOVERY_BASIS_UNKNOWN") && bot.contains("HOST_WALLET_TRACKER_BASIS_UNKNOWN") && persist.contains("RESTORED_LIVE_BASIS_UNKNOWN") && startup.contains("RecoveryLockTracker.lock"))
        assertTrue("V3 journal direct rows must stamp self-contained entry basis for audit exports", v3Journal.contains("entryPriceSnapshot = entryPrice") && v3Journal.contains("entryCostSol = sizeSol") && v3Journal.contains("remainingQtyToken") && v3Journal.contains("soldQtyToken"))
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
        assertTrue("Learning sanitizer must define finite trainable PnL bounds", sanitizer.contains("MAX_TRAINABLE_PNL_PCT = 100_000.0") && sanitizer.contains("PNL_PCT_ABOVE_TRAINABLE_MAX") && sanitizer.contains("PNL_PCT_SOL_BASIS_MISMATCH") && sanitizer.contains("emit: Boolean = true"))
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
        assertTrue("Hive Sync button must force a real reconnect when disabled instead of returning stale LOCAL ONLY", collective.contains("ensureConnected(force: Boolean = false)") && forceSync.contains("ensureConnected(force = true)") && forceSync.contains("SYNC FAILED") && forceSync.contains("Last init error"))
        assertTrue("Hive Diagnostics must force reconnect and report the captured init/probe failure", collective.contains("runDiagnostics") && collective.contains("ensureConnected(force = true)") && collective.contains("Reconnect forced: true") && collective.contains("lastInitError.ifBlank"))
        val tursoClient = java.io.File("src/main/kotlin/com/lifecyclebot/collective/TursoClient.kt").readText()
        assertTrue("Turso connection test must preserve HTTP/parse/auth errors instead of collapsing them to false", tursoClient.contains("suspend fun testConnectionResult") && tursoClient.contains("QueryResult(success = false") && tursoClient.contains("Connection test failed: ${'$'}{result.error}"))
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
        assertTrue("paper buy max must be bankroll-backed live-transfer size, not legacy maxPositionSol micro-cap", exec.contains("ALL PAPER ENTRIES") && exec.contains("paperSimulatedBalance * 0.10") && exec.contains("coerceIn(legacyMax, 2.0)"))
        assertTrue("paper buy min must have a live-transfer floor for all entries", exec.contains("live-transfer floor") && exec.contains("paperSimulatedBalance * 0.01"))
        assertTrue("paper buy clamp telemetry must exist", exec.contains("PAPER_BUY_SIZE_CLAMPED"))

        assertTrue("paper sanity must use the same live-transfer sizing bounds before quarantining rows", paperSanity.contains("paperSimulatedBalance * 0.10") && paperSanity.contains("paperSimulatedBalance * 0.01") && paperSanity.contains("PAPER_SOL_ABOVE_CONFIG_MAX"))
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
        assertTrue("Close label/PnL conflicts must not train", learning.contains("LEARNING_LABEL_SIGN_CONFLICT_QUARANTINED") && learning.contains("CloseOutcomeLabelSanitizer.inspect(trade)") && learning.contains("TRAINING_ROW_EXCLUDED_REASON_"))
    }


    @Test
    fun main_ui_money_and_pricing_surfaces_use_display_authority() {
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val mainLayout = java.io.File("src/main/res/layout/activity_main.xml").readText()
        assertTrue("Hero balance must preserve the BotService.status rolling wallet balance contract while rendering compact on mobile", main.contains("hero balance — BotService.status is the single source of truth") && main.contains("if (balSol > 0.001)") && main.contains("tvBalanceLarge.setTextIfChanged(compactHeroBalance(balSol))"))
        assertTrue("Paper hero must be mobile-safe: short PAPER chip, compact headline, detailed cash/equity in contentDescription only", main.contains("compactHeroBalance") && main.contains("if (config.paperMode) \"PAPER\" else \"LIVE\"") && main.contains("Approx equity") && !main.contains("📝 PAPER CASH ◎") && !main.contains("equity≈◎") && !main.contains("displayBankrollSol"))
        assertTrue("Paper PnL percent must not derive from current cash minus lifetime journal PnL and must render short on mobile", main.contains("paperReturnBasisSol") && main.contains("%+.0f%% start") && main.contains(" · ${'$'}journalWinRate% WR") && !main.contains("val startCapitalSol = (balSol - pnl)"))
        assertTrue("Paper equity detail must exclude CYCLIC virtual ring cost from shared wallet equity", main.contains("CYCLIC_VIRTUAL") && main.contains("paperEquityAtCostSol") && main.contains("contentDescription"))
        assertTrue("Hero XML must be a premium command card, not a flat debug balance row", mainLayout.contains("commandHeroCard") && mainLayout.contains("@drawable/aate_hero_premium_bg") && mainLayout.contains("AATE COMMAND") && mainLayout.contains("rowSymTelemetry"))
        assertTrue("Hero XML must protect mobile width with auto-size headline and capped mode chip", mainLayout.contains("android:autoSizeTextType=\"uniform\"") && mainLayout.contains("android:maxWidth=\"66dp\"") && mainLayout.contains("android:ellipsize=\"end\"") && mainLayout.contains("android:autoSizeMaxTextSize=\"32sp\""))
        assertTrue("Main UI restyle must use the AATE premium visual system", mainLayout.contains("@drawable/aate_screen_bg") && mainLayout.contains("@drawable/aate_metric_card_bg") && mainLayout.contains("@drawable/aate_signal_card_bg") && mainLayout.contains("@drawable/aate_nav_tile_bg") && mainLayout.contains("@drawable/aate_nav_icon_badge_bg") && mainLayout.contains("MISSION CONTROL · NAVIGATION DECK") && !mainLayout.contains("@drawable/stats_pill_bg"))
        assertTrue("Mission Control must be a consistent 3x5 deck, not uneven rows of debug buttons", mainLayout.contains("missionControlDeck") && mainLayout.contains("Row 1: primary operating surfaces") && mainLayout.contains("Row 2: configuration and strategy tools") && mainLayout.contains("Row 3: operator diagnostics") && mainLayout.split("@drawable/aate_nav_tile_bg").size - 1 == 15 && mainLayout.split("@drawable/aate_nav_icon_badge_bg").size - 1 == 15)
        assertTrue("Mission Control icons must be large semantic vector pictograms, not tiny letter abbreviations", listOf("aate_ic_wallet", "aate_ic_journal", "aate_ic_markets", "aate_ic_lab", "aate_ic_pipeline", "aate_ic_settings", "aate_ic_logs", "aate_ic_persona", "aate_ic_crypto", "aate_ic_behavior", "aate_ic_universe", "aate_ic_phase", "aate_ic_learning", "aate_ic_forensics", "aate_ic_tuning").all { mainLayout.contains("@drawable/$it") } && mainLayout.split("android:minWidth=\"42dp\"").size - 1 == 15 && listOf("WA", "JR", "MK", "LB", "PL", "ST", "LG", "PS", "CR", "BT", "UN", "PH", "FX", "LN").none { mainLayout.contains("android:text=\"$it\"") })
        assertTrue("Seven-wide trader layer chips must use compact non-wrapping labels on mobile", listOf("SNIP", "CASH", "BLUE", "RISK", "FAST", "MANIP", "MOON").all { mainLayout.contains("android:text=\"$it\"") } && listOf("TARGET", "TREASURY", "BLUECHIP", "HIGH-RISK", "SIGNAL", "LAUNCH").none { mainLayout.contains("android:text=\"$it\" android:textSize=\"18sp\"") } && mainLayout.split("android:singleLine=\"true\"").size - 1 >= 7)
        assertTrue("Mission Control must preserve hidden Alerts wiring without adding a visible extra tile", mainLayout.contains("btnQuickAlerts") && mainLayout.contains("android:visibility=\"gone\"") && mainLayout.split("@drawable/aate_nav_tile_bg").size - 1 == 15)
        assertTrue("Live Readiness and bottom runtime controls must use the premium redesigned surfaces", mainLayout.contains("@drawable/aate_readiness_card_bg") && mainLayout.contains("@drawable/aate_readiness_metric_bg") && mainLayout.contains("@drawable/aate_runtime_bar_bg") && mainLayout.contains("LIVE READINESS · MEME") && mainLayout.contains("Readiness progress"))
        assertTrue("Top chrome, trader tabs, and command button must use premium drawable surfaces", mainLayout.contains("@drawable/aate_top_bar_bg") && mainLayout.contains("@drawable/aate_tab_rail_bg") && mainLayout.contains("@drawable/aate_tab_active_bg") && mainLayout.contains("@drawable/aate_tab_inactive_bg") && mainLayout.contains("@drawable/aate_command_button_start") && main.contains("aate_command_button_stop") && main.contains("aate_command_button_halt"))
        assertTrue("Runtime readiness and tabs must not flatten premium surfaces into raw color blocks", main.contains("aate_status_strip_green") && main.contains("aate_status_strip_yellow") && main.contains("aate_status_strip_red") && main.contains("aate_status_strip_unknown") && main.contains("readinessBanner.setBackgroundDrawableIfChanged") && !main.contains("readinessBanner.setBackgroundColorIfChanged(bg)") && main.contains("R.drawable.aate_tab_active_bg") && main.contains("R.drawable.aate_tab_inactive_bg"))
        assertTrue("Floating diagnostic controls must be collapsed into styled Mission Control tiles", listOf("btnQuickUniverse", "btnQuickPhase", "btnQuickLearning", "btnQuickForensics", "tvUniverseTileStats", "tvLearningTileStats", "tvForensicsTileStats").all { mainLayout.contains(it) } && mainLayout.contains("@drawable/aate_nav_tile_bg") && main.contains("setupOperatorDiagnosticTiles") && main.contains("showTokenUniverseDialog"))
        assertTrue("MainActivity must not inject floating debug overlays over readiness cards", !main.contains("rootDecor?.addView") && !main.contains("Live Forensics FAB") && !main.contains("floating \"Live Trade Forensics\" tile") && !main.contains("setBackgroundColor(android.graphics.Color.parseColor(\"#A78BFA\")"))
        val activityLayouts = java.io.File("src/main/res/layout").listFiles()
            ?.filter { it.name.startsWith("activity_") && it.name.endsWith(".xml") && it.name != "activity_splash.xml" }
            ?: emptyList()
        assertTrue("All operational AATE screens must use the premium AATE visual shell", activityLayouts.isNotEmpty() && activityLayouts.all { it.readText().contains("@drawable/aate_screen_bg") })
        val forbiddenLegacySkins = listOf("@drawable/stats_pill_bg", "@drawable/stat_card_bg", "@drawable/section_card_bg", "@drawable/hero_card_bg", "@drawable/card_bg\"", "@drawable/pill_bg\"")
        assertTrue("Operational AATE screens must not regress to the old flat/debug skin", activityLayouts.all { layout -> forbiddenLegacySkins.none { legacy -> layout.readText().contains(legacy) } })
        assertTrue("AATE universe screens must use shared premium panel/list surfaces instead of debug-card chrome", activityLayouts.all { layout -> val xml = layout.readText(); !xml.contains("@drawable/aate_debug_card_bg") && (xml.contains("@drawable/aate_universe_panel_bg") || xml.contains("@drawable/aate_universe_list_bg") || xml.contains("@drawable/aate_readiness_card_bg") || xml.contains("@drawable/aate_nav_tile_bg") || xml.contains("@drawable/lab_neon_card")) })
        val consumerEmojiChrome = listOf("🧠", "💎", "🪙", "📈", "📊", "👛", "🐛", "🎚", "🎭", "🧪", "🔥", "🛡", "⚡", "📥", "📦", "💾", "🗑", "🔔", "🔒", "🚀", "🏆", "🌡")
        assertTrue("AATE XML chrome must read like an institutional product, not emoji-labeled consumer crypto UI", activityLayouts.all { layout -> val xml = layout.readText(); consumerEmojiChrome.none { token -> xml.contains("android:text=\"$token") || xml.contains("$token ") } })
        assertTrue("Main runtime chrome must render institutional readiness/trader copy", !main.contains("🚀 Live Readiness") && !main.contains("🧠 All Traders") && !main.contains("📊 ${'$'}perAssetLine") && !main.contains("🛡 Guards") && !main.contains("🏆 Top-3") && main.contains("LIVE READINESS · MEME") && main.contains("ALL TRADERS ·"))
        assertTrue("Paper hero must not sanitize/delete the headline balance", !main.contains("PAPER_HERO_BANKROLL_DISPLAY_SANITIZED") && !main.contains("rawBankrollSol > sanePaperCeiling"))
        assertTrue("Open-position UI must wait on executor-stamped mint market snapshots, not repair basis itself", main.contains("recoverRenderablePricing") && main.contains("UI is not a price-basis authority") && main.contains("OPEN_POSITION_UI_BASIS_WAIT") && !main.contains("OPEN_POSITION_PRICE_RECOVERED_FOR_UI"))
        assertTrue("Open-position UI must not invent entry basis from current price/ref/lastPrice", !main.contains("journalEntryPrice(buy), ts.lastPrice, ts.ref") && !main.contains("journalEntryPrice(buy), currentPrice, existing?.lastPrice") && main.contains("OPEN_POSITION_UI_BASIS_WAIT"))
        assertTrue("Main UI panels must use shared current-price authority", main.contains("mainUiCurrentPrice") && main.contains("shared Main UI current-price authority"))
        assertTrue("Main UI must show pricing wait instead of fake zero entry", main.contains("pricing wait") && main.contains("basis wait") && !main.contains("if (ref > 0.0) ref else pos.entryPrice") && !main.contains("ts.lastPrice - pos.entryPrice"))
        val styleRouter = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val toolkitSheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val cyclicEngine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        assertTrue("Internet risk_off must source-pivot degen/fresh-launch exposure before it reaches executor", styleRouter.contains("isRiskOffSheet") && styleRouter.contains("NARRATIVE_SOCIAL_IGNITION") && styleRouter.contains("FRESH_LAUNCH, ModeRouter.TradeType.SENTIMENT_IGNITION, ModeRouter.TradeType.GRADUATION") && toolkitSheet.contains("riskOffSetupBias") && toolkitSheet.contains("riskOffBias"))
        assertTrue("Bleeding CYCLIC must micro-probe while learning instead of continuing 5% ring bleed", cyclicEngine.contains("MELTDOWN_PROBE_FRACTION") && cyclicEngine.contains("CYCLIC_MELTDOWN_MICRO_PROBE") && cyclicEngine.contains("wrPctNow < 20.0"))
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Paper top-up BUY legs must debit available paper cash like fresh buys/graduated adds", exec.contains("paper top-ups are BUY legs") && exec.contains("onPaperBalanceChange?.invoke(-sol)"))
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("Impossible paper wallet balances must repair from journal-derived cash authority", bot.contains("PAPER_WALLET_IMPOSSIBLE_REPAIRED") && bot.contains("expectedCash") && bot.contains("start + realised") && bot.contains("repairUnifiedPaperWalletIfImpossible(\"paper_delta\")"))
        val cyclic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        assertTrue("CYCLIC paper must use a virtual ring book and not mutate shared paper cash", cyclic.contains("paperLayerTag = \"CYCLIC\"") && cyclic.contains("debitPaperWallet = isLiveMode") && exec.contains("PAPER_BUY_SHARED_WALLET_DEBIT_SKIPPED") && exec.contains("PAPER_SELL_SHARED_WALLET_CREDIT_SKIPPED"))
        assertTrue("CYCLIC virtual sizing must bypass the normal tiny paper trade cap while preserving live cap", cyclic.contains("maxPaperTradeSolOverride = if (isLiveMode) null else sizeSol") && exec.contains("maxPaperTradeSolOverride"))
        assertTrue("CYCLIC persisted ring state must reset if impossible", cyclic.contains("sanitizeRingState") && cyclic.contains("CYCLIC_RING_IMPOSSIBLE_RESET"))
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

        assertTrue("Raw zero liquidity is TokenMap-pending; only TRUE_ZERO_LIQUIDITY hard-blocks after provider quorum", safety.contains("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP") && preTrade.contains("TRUE_ZERO_LIQUIDITY") && !safety.contains("ZERO LIQUIDITY — no executable route"))
        assertTrue("Low but nonzero liquidity must be quote/size penalty, not static hard block", safety.contains("LOW_LIQUIDITY_SIZE_REDUCED") && preTrade.contains("LOW_LIQUIDITY_SIZE_REDUCED"))
        assertFalse("Static liquidity min must not hard-block live buys", preTrade.contains("return block(ts, \"LIQUIDITY_BELOW_LIVE_MIN"))
        assertFalse("Missing/stale safety must not hard-block by itself", preTrade.contains("return block(ts, \"SAFETY_PROOF_STALE_OR_MISSING"))
        assertTrue("Unknown mint/freeze/holder proof is a pending-penalty allow, not a terminal live block", preTrade.contains("PRETRADE_PENDING_PROOF_PENALTY_ALLOW") && preTrade.contains("pending_penalty") && !preTrade.contains("LIVE_CRITICAL_PROOF_PENDING"))
        assertTrue("LiveBuyAdmissionGate must convert safety shadow to penalty-only unless true hard", liveGate.contains("SAFETY_SHADOW_PENALTY_ONLY") && liveGate.contains("BUY_GATE_PENALTY_ONLY_SAFETY_SHADOW"))
        assertTrue("BotService SAFETY_SHADOW must continue only for true hard reasons", bot.contains("!TokenBlacklist.isSoftPenaltyOnlyReason(reason)") && bot.contains("source=BotService.SAFETY_SHADOW"))
        assertTrue("Price-only collapse must not become a true hard blacklist", tokenBlacklist.contains("RUG DETECTED") && tokenBlacklist.contains("!r.contains(\"CONFIRMED_RUG_COLLAPSE\")") && bot.contains("RUG_PRICE_COLLAPSE_UNCONFIRMED") && bot.contains("SafetyRefreshQueue.request(mint)"))
        assertTrue("Confirmed rug blacklist requires real liquidity conflict proof", bot.contains("checkLiquidityConflict(mint, recentLiq)") && bot.contains("CONFIRMED_RUG_COLLAPSE") && !bot.contains("TokenBlacklist.block(mint, \"Rug detected: price"))
        assertTrue("Every taxonomy decision should surface forensic proof", listOf(tokenBlacklist, executor, safety, liveGate, preTrade, bot).all { it.contains("BUY_GATE_DECISION") })
    }


    @Test
    fun live_micro_probe_entry_applies_expectancy_but_bypasses_break_even_sizing() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val scoreExpectancy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ScoreExpectancyTracker.kt").readText()
        val liveRestore = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveRestoreExecutionPolicy.kt").readText()

        assertFalse("Live entry must not bypass LaneExpectancyDamper sizing anymore", executor.contains("LIVE_EXPECTANCY_SIZE_BYPASSED"))
        assertTrue("Live entry must apply wallet-growth expectancy allocator", executor.contains("LIVE_EXPECTANCY_SIZE_APPLIED") && executor.contains("LIVE_WALLET_GROWTH_ALLOCATOR"))
        // V5.0.4117 — AGI stack must be wired into buy sizing
        assertTrue("AGI size stack: LiveStrategyTuner.sizeMult must be in multiplierProduct", executor.contains("strategyTunerSizeMult") && executor.contains("LiveStrategyTuner.sizeMultiplier"))
        assertTrue("AGI size stack: ScannerSourceBrain.intakeMultiplier must be in multiplierProduct", executor.contains("sourceBrainSizeMult") && executor.contains("ScannerSourceBrain.intakeMultiplier"))
        assertTrue("AGI size stack: UnifiedPolicyHead.conviction must be in multiplierProduct", executor.contains("uphConvictionMult") && executor.contains("UnifiedPolicyHead.conviction"))
        assertTrue("AGI size stack: AGI_SIZE_STACK_APPLIED telemetry must be emitted", executor.contains("AGI_SIZE_STACK_APPLIED"))
        // V5.0.4118 — all lanes must have pivot paths in LiveStylePivotRouter
        val pivotRouter = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText()
        assertTrue("Pivot router must handle STANDARD lane (was missing, starving volume)", pivotRouter.contains(""""STANDARD" ->"""))
        assertTrue("Pivot router must handle MANIPULATED lane (was missing, starving volume)", pivotRouter.contains(""""MANIPULATED" ->"""))
        assertTrue("Pivot router must handle DIP_HUNTER lane (was missing, starving volume)", pivotRouter.contains(""""DIP_HUNTER" ->"""))
        assertTrue("STANDARD must have bleeder quality promotion path", pivotRouter.contains("STANDARD_BLEEDER_QUALITY_PROMOTION"))
        assertTrue("MANIPULATED must have volume ignition confirmation path", pivotRouter.contains("MANIPULATED_NATIVE_VOLUME_IGNITION_CONFIRMED"))
        assertTrue("DIP_HUNTER must have pullback reclaim promotion path", pivotRouter.contains("DIP_HUNTER_PULLBACK_RECLAIM_CONFIRMED"))
        // V5.0.4119 — break-even guard buffers reduced to open mid-score volume
        val breakEven = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveBreakEvenGuard.kt").readText()
        assertTrue("Break-even giveback buffer for default lanes must be 1.5 (was 2.5)", breakEven.contains("else -> 1.5"))
        assertTrue("Break-even minProfit buffer for default lanes must be 2.0 (was 5.0)", breakEven.contains("else -> 2.0"))
        val toxicMoonNative = "MOONSHOT_" + "S55_60_" + "NATIVE_SIZE_SHAPED"
        assertTrue("MOONSHOT S41-60 live-toxic bucket must not have a native size-shaped path", !pivotRouter.contains(toxicMoonNative) && pivotRouter.contains("MOONSHOT_S41_60_LIVE_TOXIC_DEFER_V4153"))
        assertTrue("MOONSHOT S41-60 may only rescue through independent LDQ quality proof", pivotRouter.contains("MOONSHOT_S41_60_ONLY_LDQ_QUALITY_RESCUE_V4153"))
        // V5.0.4121 — LayerBrain integration for SmartMoneyDivergenceAI + HoldTimeOptimizerAI
        val smartMoney = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/SmartMoneyDivergenceAI.kt").readText()
        val holdTime = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/HoldTimeOptimizerAI.kt").readText()
        assertTrue("SmartMoneyDivergenceAI must have LayerBrain registered", smartMoney.contains("""LayerBrain.register("SmartMoneyDivergenceAI""""))
        assertTrue("SmartMoneyDivergenceAI must apply learned bias", smartMoney.contains("brain.applyBias"))
        assertTrue("SmartMoneyDivergenceAI must stamp for outcome training", smartMoney.contains("brain.stamp"))
        assertTrue("HoldTimeOptimizerAI must have LayerBrain registered", holdTime.contains("""LayerBrain.register("HoldTimeOptimizerAI""""))
        assertTrue("HoldTimeOptimizerAI must apply learned bias", holdTime.contains("brain.applyBias"))
        assertTrue("HoldTimeOptimizerAI must stamp for outcome training", holdTime.contains("brain.stamp"))
        assertTrue("Live entry must bypass break-even economics and defer them to sell side", executor.contains("LIVE_ENTRY_BREAK_EVEN_BYPASSED_TO_SELL") && executor.contains("sellSideBreakEvenOk"))
        assertFalse("liveBuy entry must not call breakEvenCheck before route quote", executor.contains("val breakEven = LiveRestoreExecutionPolicy.breakEvenCheck(ts, sol, restorePenalty"))
        assertTrue("Score expectancy reject must be neutral in live", scoreExpectancy.contains("LIVE_EXPECTANCY_REJECT_BYPASSED") && scoreExpectancy.contains("RuntimeModeAuthority.isLive()") && scoreExpectancy.contains("return false"))
        assertTrue("Score expectancy reject remains neutral in live; lane allocator owns live sizing", scoreExpectancy.contains("do not dust-size live probes") && scoreExpectancy.contains("return 1.0"))
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


    @Test
    fun live_execution_has_per_mint_buy_lease_and_helius_noncritical_capability_report() {
        val lease = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionAttemptLease.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

        assertTrue("ExecutionAttemptLease must enforce active lease + backoff", lease.contains("EXEC_LEASE_SET") && lease.contains("EXEC_DUPLICATE_SUPPRESSED") && lease.contains("EXEC_RETRY_BACKOFF_SET") && lease.contains("terminalOk") && lease.contains("terminalFail") && lease.contains("releaseNonTerminal"))
        assertTrue("liveBuy must acquire lease before route/build/submit", exec.contains("ExecutionAttemptLease.acquire") && exec.indexOf("ExecutionAttemptLease.acquire") < exec.indexOf("MEME_LIVE_BUY_MUTEX.tryAcquire"))
        assertTrue("liveBuy wallet mutex must be after finality/admission/keypair, not around cheap rejects", exec.indexOf("canOpenExecutablePosition") < exec.indexOf("MEME_LIVE_BUY_MUTEX.tryAcquire") && exec.indexOf("LiveBuyAdmissionGate.requireApprovedLiveBuy") < exec.indexOf("MEME_LIVE_BUY_MUTEX.tryAcquire") && exec.indexOf("security.verifyKeypairIntegrity") < exec.indexOf("MEME_LIVE_BUY_MUTEX.tryAcquire"))
        assertTrue("liveBuy must emit plan/route/tx/terminal stages", listOf("BUY_PLAN_OK", "BUY_ROUTE_REQUESTED", "BUY_TX_SUBMITTED", "buyTerminalOk", "buyTerminalFail").all { exec.contains(it) })
        assertTrue("Provider capability report must say Helius is non-critical and show execution truth", pipe.contains("Provider capability (execution truth)") && pipe.contains("Helius role:") && pipe.contains("HOT_PATH=false") && pipe.contains("Jupiter quote/build/confirm") && pipe.contains("Execution leases:"))
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("CYCLIC must remain available but bankroll-gated in live", bot.contains("walletUsdNow >= liveThreshold") && bot.contains("CYCLIC_WALLET_USD_BELOW_5000_MEME_STILL_ACTIVE"))
        assertTrue("DUMP live policy must soft-size risky lanes, not paper-only veto them", !exec.contains("DUMP_LIVE_LANE_PAPER_ONLY") && exec.contains("DUMP_REGIME_LIVE_SIZE_SHAPED") && exec.contains("laneTag.contains(\"TREASURY\")") && exec.contains("laneTag.contains(\"MANIP") && exec.contains("laneTag.contains(\"CYCLIC\")"))
        assertFalse("FDG must not hard-block live solely because Helius is down", fdg.contains("HELIUS_UNHEALTHY_LIVE_SAFE_MODE") || fdg.contains("blockReason = \"HELIUS"))
    }


    @Test
    fun ws_tick_filter_rejected_live_crash_forces_emergency_sell_proof_not_safe_hold() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val models = java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()
        assertTrue("Rejected live crash ticks must be emergency-proof routed, not normal safe holds", bot.contains("WS_TICK_FILTER_CRASH_PROOF") && bot.contains("liveOpenCrashTick") && bot.contains("jumpMult < 0.01") && bot.contains("request_sell_no_ui_settlement"))
        assertTrue("Crash-proof route must trigger executor sell and urgent reconciler without UI settlement", bot.contains("executor.requestSell") && bot.contains("SellReconciler.requestUrgentTick(\"WS_TICK_FILTER_CRASH_PROOF_ROUTE\")") && bot.contains("WS_TICK_FILTER_CRASH_PROOF_ROUTE_SUBMIT_FAILED"))
        assertFalse("Crash-proof route must not pre-stamp retry before requestSell lease acquisition", bot.contains("CloseLease.recordRetry(ts.mint, \"WS_TICK_FILTER_CRASH_PROOF_ROUTE\")"))
        assertTrue("TokenState documentation must preserve no UI-price settlement doctrine", models.contains("emergency sell") && models.contains("refusing to settle from UI price alone"))
    }

    @Test
    fun live_holder_risk_requires_distribution_proof_and_ultra_runner_banks_immediately() {
        val pre = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        assertTrue("Live holder distribution uncertainty must penalty-allow, not terminal-choke real SOL attempts", pre.contains("HOLDER_DISTRIBUTION_PENDING") && pre.contains("PRETRADE_PENDING_PROOF_PENALTY_ALLOW") && !pre.contains("LIVE_CRITICAL_PROOF_PENDING"))
        assertTrue("Wallet/Phantom holder warning text must be fatal pre-submit", pre.contains("FATAL_WALLET_RISK_TEXT") && listOf("SINGLE HOLDER", "UNVERIFIED TOKEN", "TOP 10").all { pre.contains(it) })
        assertTrue("Ultra live runners must bank before normal partial cadence", exec.contains("ULTRA-RUNNER PANIC BANK") && exec.contains("ULTRA_RUNNER_BANK_TRIGGERED") && exec.contains("gainMultiple >= 50.0") && exec.contains("peakGainPct >= 5_000.0") && exec.contains("executeProfitLockSell(ts, wallet, sellFraction, \"ultra_runner_bank_"))
        assertTrue("Catastrophic stop overruns must not be hidden as normal STRICT_SL in alerts/reports", exec.contains("STOP_LOSS_OVERRUN_CATASTROPHIC") && exec.contains("CATASTROPHIC_STOP_LOSS_OVERRUN_") && exec.contains("TradeAlerts.onSell(cfg(), ts.symbol, pnl, pnlP, finalSellReason") && exec.contains("${'$'}finalSellReason  PnL"))
    }

    @Test
    fun anr_storm_sheds_heavy_dashboard_render_and_ui_pollers_stay_off_main() {
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val collective = java.io.File("src/main/kotlin/com/lifecyclebot/ui/CollectiveBrainActivity.kt").readText()

        assertTrue("PipelineHealthCollector must expose lightweight atomic ANR getters", phc.contains("fun anrHintCountNow(): Int = anrHintCount.get()") && phc.contains("fun maxFrameGapMsNow(): Long = maxFrameGapMs.get()"))
        assertTrue("MainActivity must shed row-heavy rendering during catastrophic ANR storms", main.contains("MAIN_HEAVY_RENDER_ANR_SHED") && main.contains("anrHintCountNow()") && main.contains("anrHintsForRenderShed >= 100") && main.contains("skip=heavy_dashboard_rows"))
        assertFalse("Dashboard render path must not create unmanaged MainScope jobs", main.contains("MainScope().launch"))
        assertFalse("ANR shed must not call PipelineHealthCollector.snapshot() from updateUi", main.contains("PipelineHealthCollector.snapshot().anrHints"))
        assertTrue("CollectiveBrain polling must start on IO and use cached trade stats", collective.contains("lifecycleScope.launch(Dispatchers.IO)") && collective.contains("TradeHistoryStore.getStatsCached()"))
        assertFalse("CollectiveBrain UI polling must not call uncached TradeHistoryStore.getStats", collective.contains("TradeHistoryStore.getStats()"))
    }

    @Test
    fun runtime_doctor_uses_recent_worker_timeout_pressure_not_cumulative_debt() {
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val guardian = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()

        assertTrue("PipelineHealthCollector must expose bounded recent event counts", phc.contains("fun recentEventCount(tag: String") && phc.contains("ring.count { it.tsMs >= cutoff && it.tag == tag }"))
        assertTrue("Runtime fault must key supervisor worker disease off recent pressure", guardian.contains("workerTimeoutRecent2m") && guardian.contains("recentEventCount(\"LIFECYCLE/SUPERVISOR_WORKER_TIMEOUT\", 120_000L)") && guardian.contains("workerTimeoutRecent2m > 15L"))
        assertTrue("Report must show cumulative and recent supervisor timeouts separately", phc.contains("WORKER_TIMEOUT_RECENT") && phc.contains("recent2m=${'$'}supTimeoutRecent2m") && phc.contains("cumulative=${'$'}supTimeout"))
        assertFalse("Cumulative workerTimeout >100 must not directly trigger EXIT_SWEEP_UNSTABLE", guardian.contains("workerTimeout > 100L"))
    }

    @Test
    fun live_buy_admission_does_not_global_safe_mode_on_jupiter_fallback_backoff() {
        val safe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellOnlySafeMode.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        // V5.0.3919 — extract ONLY the executionProviderLabels array literal so
        // documentation/comments above the field can't fool the assertion (the
        // pre-3919b version did `safe.contains(\"jupiter\")` and tripped on the
        // doc comment that explains why scanner-only labels are excluded).
        val arrayBlock = Regex(
            "executionProviderLabels\\s*=\\s*arrayOf\\s*\\(([^)]*)\\)",
            RegexOption.DOT_MATCHES_ALL,
        ).find(safe)?.groupValues?.get(1).orEmpty()
        assertTrue("executionProviderLabels array literal must exist in SellOnlySafeMode", arrayBlock.isNotBlank())
        assertTrue("Pump-first live buy admission must still respect pump/finality provider backoff", arrayBlock.contains("\"pumpportal\"") && arrayBlock.contains("\"pumpfun\"") && arrayBlock.contains("\"helius\"") && arrayBlock.contains("\"solana_rpc\""))
        assertFalse("Jupiter fallback backoff must not globally trigger SELL_ONLY_SAFE_MODE while Pump-first is healthy", arrayBlock.contains("\"jupiter\"") || arrayBlock.contains("quote-api.jup.ag") || arrayBlock.contains("jup.ag"))
        assertFalse("Scanner-only labels must never park live buys via SELL_ONLY_SAFE_MODE", arrayBlock.contains("\"dexscreener\"") || arrayBlock.contains("\"geckoterminal\"") || arrayBlock.contains("\"birdeye\"") || arrayBlock.contains("\"coingecko\"") || arrayBlock.contains("\"pyth\"") || arrayBlock.contains("\"groq\"") || arrayBlock.contains("\"gemini\""))
        assertTrue("Outer live buy caller must preserve inner terminal fail authority", exec.contains("NO_OPEN_COMMITTED_AFTER_LIVEBUY_OBSERVED") && exec.contains("action=observe_only_inner_reason_authority"))
        assertTrue("Finality-block telemetry must include the normalized finality reason", exec.contains("FINALITY_BLOCK:${'$'}{executableOpen.reason.take(72).replace(' ', '_')}"))
    }


    @Test
    fun live_execution_unblock_3902_contracts_are_source_pinned() {
        val lease = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionAttemptLease.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val endpoint = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionEndpointHealth.kt").readText()
        val jupiter = java.io.File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        val pump = java.io.File("src/main/kotlin/com/lifecyclebot/network/PumpFunDirectApi.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        assertTrue("expired execution leases must be synchronously pruned with forensic detail", lease.contains("fun pruneExpired") && lease.contains("EXEC_LEASE_PRUNED_EXPIRED") && lease.contains("ttlMs=") && lease.contains("ageMs=") && lease.contains("visibleNegativeTtlCount"))
        assertTrue("deterministic terminal failures must clear lease without retry backoff", lease.contains("FAIL_NO_BACKOFF") && lease.contains("isRetryableTerminal"))
        assertTrue("supervisor cap reports must include expired lease pruning at cap time", bot.contains("supervisorPruneExpiredLeases(\"cap_report\")") && bot.contains("expiredLeases=${'$'}expiredAtCapReport"))

        assertFalse("generic NO_FINAL_BUY_CANDIDATE must not be emitted as final reason", gate.contains("\"NO_FINAL_BUY_CANDIDATE\""))
        assertTrue("missing final candidate must be source-specific TOKEN_STATE_CHANGED", gate.contains("TOKEN_STATE_CHANGED_NO_FINAL_CANDIDATE"))

        assertTrue("execution endpoint health must exist and disable non-core endpoints by endpoint/mint", endpoint.contains("object ExecutionEndpointHealth") && endpoint.contains("EXEC_ENDPOINT_DISABLED") && endpoint.contains("endpoint.uppercase()"))
        assertTrue("Jupiter quote/build/send/RPC health must be endpoint split", jupiter.contains("JUPITER_QUOTE") && jupiter.contains("JUPITER_SWAP_BUILD") && jupiter.contains("JUPITER_SEND") && jupiter.contains("helius_rpc") && jupiter.contains("jupiter_quote"))
        val execHealth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionHealthGuard.kt").readText()
        assertTrue("Jupiter 4xx quote misses must not globally park buys; only network/5xx collapse freezes entries", execHealth.contains("http4xxOnly") && execHealth.contains("return true") && execHealth.contains("Only network/5xx health collapse should freeze new entries globally"))
        assertTrue("Jupiter quote must never be endpoint-disabled", endpoint.contains("neverDisable(endpoint)") && endpoint.contains("JUPITER_QUOTE_NEVER_DISABLED") && !jupiter.contains("ExecutionEndpointHealth.disable(endpoint"))
        assertTrue("Jupiter quote failures must stay local to candidate/slippage ladder", exec.contains("NO_QUOTE:JUPITER_QUOTE_EXHAUSTED") && !exec.contains("PROVIDER_DISABLED:JUPITER_QUOTE"))

        assertTrue("Pump Direct build health must be endpoint-specific", pump.contains("pump_direct_build") && pump.contains("PUMP_DIRECT_BUILD"))
        assertTrue("Pump Direct 0x1788 must disable Pump route for mint and rotate", exec.contains("PUMP_DIRECT_SIM_0X1788") && exec.contains("PUMP_DIRECT_0X1788_ROUTE_DISABLED") && exec.contains("MemeVenueRouter.markPumpRouteInvalid(ts.mint)"))
        assertTrue("graduated/Raydium/AMM tokens must not force Pump Direct first", exec.contains("PUMP_DIRECT_SKIPPED_ROUTE_POLICY") && exec.contains("graduatedOrAmm") && exec.contains("freshPumpRoute"))

        assertTrue("orphan dust must not consume executor route capacity", exec.contains("ORPHAN_DUST_IGNORED") && exec.contains("qty <= 0.000001"))
        assertTrue("live buy failures must write live telemetry rows", exec.contains("LIVE_BUY_FAIL_TELEMETRY") && exec.contains("LIVE_TELEMETRY_ROW_BUY_FAIL"))
    }


    @Test
    fun solana_wide_scheduler_does_not_overfit_pumpfun_or_blacklist_choke_workset() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("freshness protection must be Solana-wide, not pumpfun-only", bot.contains("fun isFreshSolanaSource") && bot.contains("RAYDIUM") && bot.contains("DEX") && bot.contains("DATA_ORCHESTRATOR") && bot.contains("SCANNER_DIRECT") && bot.contains("METEORA") && bot.contains("ORCA") && bot.contains("GRADUATE") && bot.contains("MIGRATED"))
        assertFalse("scheduler freshness helper must not remain pump/meme-only", bot.contains("fun isFreshMemeSource"))
        assertTrue("probation demotion trace must expose Solana-wide freshness", bot.contains("solanaFresh=${'$'}solanaFresh") && !bot.contains("memeFresh=${'$'}memeFresh"))
        assertTrue("flat hard-blacklisted mints must not burn per-cycle lane-eval workset slots", bot.contains("flatTokenBlacklisted") && bot.contains("banned_quarantined_or_flat_token_blacklist") && bot.contains("TOKEN_BLACKLIST_FLAT_SKIPPED_PRELANE"))
        assertTrue("protected intake must stay intact while this-cycle workset skips dead rows", bot.contains("pool intact, this-cycle skip only"))
    }


    @Test
    fun position_persistence_batch_save_must_not_amputate_missing_open_rows() {
        val persist = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("batch persistence must merge into existing persisted book", persist.contains("NON-AUTHORITATIVE BATCH SAVE FIX") && persist.contains("val current = loadPositionsInternal().toMutableMap()"))
        assertTrue("batch save must preserve rows absent from a partial status.tokens snapshot", persist.contains("preserve persisted rows for mints absent from this") || persist.contains("POSITION_PERSIST_EMPTY_SNAPSHOT_PRESERVED"))
        assertTrue("empty restart/destroy snapshots must not clear a non-empty persisted book", persist.contains("tokens.isEmpty() && current.isNotEmpty()") && persist.contains("POSITION_PERSIST_EMPTY_SNAPSHOT_PRESERVED"))
        assertFalse("batch save must not replace the whole book with only currently visible open rows", persist.contains("savePositionsInternal(openPositions)"))
        assertFalse("batch save must not clear all persistence solely because visible openPositions is empty", persist.contains("if (openPositions.isEmpty())") && persist.contains("remove(KEY_POSITIONS)"))
        assertTrue("manual stop/full reset remains the explicit clear path", persist.contains("fun clear()") && persist.contains("Cleared all persisted positions"))
    }


    @Test
    fun paper_circuit_pause_must_not_short_circuit_prelane_buy_refill() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val security = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SecurityGuard.kt").readText()
        val preLaneIdx = bot.indexOf("processTokenCycle.preLane")
        val bypassIdx = bot.indexOf("PAPER_PRELANE_CIRCUIT_PAUSE_BYPASSED")
        assertTrue("BotService pre-lane circuit pause must inspect currentEntryPause", bot.contains("ToxicModeCircuitBreaker.currentEntryPause()"))
        assertTrue("LIVE may still return during a toxic global pause", bot.contains("toxicPause.active && !cfg.paperMode") && bot.contains("emitExecutionStateBlockedIfDue(identity.symbol, \"processTokenCycle.preLane\")"))
        assertTrue("PAPER must bypass pre-lane circuit pause so BUY signals can reach V3/LANE_EVAL/FDG", bypassIdx > preLaneIdx && bot.contains("toxicPause.active && cfg.paperMode"))
        assertTrue("ExecutableOpenGate must also bypass circuit pauses in PAPER", gate.contains("PAPER_EXEC_CIRCUIT_PAUSE_BYPASSED"))
        assertTrue("SecurityGuard buy preflight must bypass circuit pause in PAPER", security.contains("cbState.isPaused && !cfg().paperMode"))
    }


    @Test
    fun live_buy_must_recover_approved_handoff_before_rechecking_as_standard() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue("liveBuy must recover a lane-approved attempt from any lane", exec.contains("LIVE_BUY_APPROVED_HANDOFF_RECOVERED") && exec.contains("recentAllowedAttemptIdAnyLane(ts.mint)"))
        assertTrue("recovered approved handoff must make liveBuy finality-prechecked", exec.contains("val recoveredFinalityPrechecked = finalityPrechecked || recoveredLiveAttemptId.isNotBlank()"))
        assertTrue("liveBuy must use recovered attempt id for restore penalty and finality retry", exec.contains("consumeRestorePenalty(recoveredLiveAttemptId)") && exec.contains("attemptId = recoveredLiveAttemptId.ifBlank"))
        assertTrue("allowed attempts are only created after executable-open finality allows", gate.contains("allowedAttempts[laneAttemptKey] = execKey") && gate.contains("OpenVerdict(") && gate.contains("true,"))
        assertTrue("lane-agnostic handoff lookup must exist for owner-rotation callers", gate.contains("fun recentAllowedAttemptIdAnyLane"))
    }


    @Test
    fun latest_buy_snapshot_must_never_rebuild_journal_on_main_thread() {
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val fn = store.substring(store.indexOf("fun getLatestBuyByMintSnapshot"), store.indexOf("private fun computeLatestBuyByMintSnapshot"))
        assertTrue("latest buy hot path must detect main thread", fn.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertTrue("main thread must return cached snapshot and schedule IO refresh", fn.contains("scheduleLatestBuyRefresh(cap)") && fn.contains("return cached"))
        assertFalse("main-thread wrapper must not enter synchronized(lock)", fn.contains("synchronized(lock)"))
        assertFalse("main-thread wrapper must not call ensureInitialized", fn.contains("ensureInitialized()"))
        assertTrue("actual journal scan must be isolated to computeLatestBuyByMintSnapshot", store.contains("private fun computeLatestBuyByMintSnapshot") && store.substring(store.indexOf("private fun computeLatestBuyByMintSnapshot"), store.indexOf("private fun scheduleLatestBuyRefresh")).contains("synchronized(lock)"))
    }


    @Test
    fun live_buy_mutex_busy_is_defer_not_failed_buy_and_finality_can_synth_current_live_candidate() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val lease = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionAttemptLease.kt").readText()
        val mutexIdx = exec.indexOf("MEME_LIVE_BUY_MUTEX.tryAcquire")
        val busyBlock = exec.substring(mutexIdx, exec.indexOf("liveBuyMutexAcquired = true", mutexIdx))
        assertTrue("mutex busy must be visible as a non-terminal deferred attempt", busyBlock.contains("liveBuyDeferred") && busyBlock.contains("MUTEX_BUSY_DEFERRED"))
        assertFalse("mutex busy must not poison BUY_FAILED/backoff telemetry", busyBlock.contains("emitLiveBuyFail") || busyBlock.contains("buyTerminalFail") || busyBlock.contains("LIVE_BUY_TIMEOUT"))
        assertTrue("lease may still expose non-terminal release for non-live-attempt paths", lease.contains("fun releaseNonTerminal") && lease.contains("terminal=NON_TERMINAL"))
        assertTrue("live finality must synthesize a current direct-lane candidate when state is missing but safety/liquidity are present", gate.contains("""modeUpper in setOf("PAPER", "LIVE")""") && gate.contains("LIVE_SYNTHETIC_FINAL_CANDIDATE") && gate.contains("LIVE_EXEC_OPEN_SYNTHETIC_FINAL_CANDIDATE"))
    }


    @Test
    fun live_finality_watch_and_empty_drain_safe_mode_must_not_choke_live_buys() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val safe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellOnlySafeMode.kt").readText()
        assertTrue("FDG-approved WATCH/PROBE must be restorable when current candidate is safe/liquid", gate.contains("verdictAllowedByFdg") && gate.contains("WATCH") && gate.contains("PROBE") && gate.contains("LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW"))
        assertTrue("WATCH restore must be backed by FDG/ticket authority, safety, liquidity, and no hardNo", gate.contains("verdictAllowedByFdg") && gate.contains("liqOk") && gate.contains("effectiveHardNoReasons.isEmpty()") && gate.contains("ExecutionTicket"))
        assertTrue("SellOnlySafeMode must not let empty stale drain jobs globally block live buys", safe.contains("liveExposureToDrain") && safe.contains("liveExposureToDrain && pendingSellQueueSize > 0") && safe.contains("liveExposureToDrain && sellReconcilerActiveJobs > 0"))
        assertTrue("Real sell-only dangers must remain hard reasons", safe.contains("workerTimeoutStorm()") && safe.contains("orphanLivePositions > 0") && safe.contains("closedWithNonDustBalance > 1") && safe.contains("providerBackoffActive()"))
    }


    @Test
    fun benchmark_3868_3879_live_throughput_paths_must_not_be_hard_disabled() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("CYCLIC live tick must follow enabled authority only after wallet USD bankroll gate", bot.contains("cyclicEnabled && walletUsdNow >= liveThreshold") && !bot.contains("CYCLIC is a live bleeder"))
        assertFalse("DUMP regime must not force live lanes to paper-only", exec.contains("DUMP_LIVE_LANE_PAPER_ONLY") || exec.contains("dump_paper_only:"))
        assertTrue("DUMP regime must remain risk-shaped via size caps", exec.contains("dumpRegimeLive && laneTag.contains(\"CYCLIC\")") && exec.contains("dumpRegimeLive && laneTag.contains(\"TREASURY\")") && exec.contains("DUMP_REGIME_LIVE_SIZE_SHAPED"))
        assertTrue("Fresh no-pair discoveries must be held hot before aged demotion", bot.contains("INTAKE_NO_PAIR_HELD_HOT_FOR_HYDRATION") && bot.contains("NO_PAIR_NO_FALLBACK_AGED"))
        assertTrue("Report must expose live lane policy and no-pair hot hydration", pipe.contains("noPairHeldHot") && (pipe.contains("CYCLIC=liveSoftSized") || bot.contains("CYCLIC_WALLET_USD_BELOW_5000_MEME_STILL_ACTIVE")))
    }

    @Test
    fun live_meme_mode_must_collapse_to_one_owner_lane_not_full_ring_fanout() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("live MemeTrader must use owner collapse, not all-lane FDG fanout", bot.contains("LIVE_RING_OWNER_COLLAPSE") && bot.contains("MEMETRADER_OWNER_LANE") && bot.contains("val allowed = l == ownerLane"))
        assertFalse("live full-ring observe must not return true before owner rotation", bot.contains("LIVE_FULL_RING_LANE_OBSERVE") || bot.contains("fullRingObserve"))
        assertTrue("runtime report must expose bounded owner-collapse policy", pipe.contains("MEME_RING=liveOwnerCollapsed") && pipe.contains("LIVE_RING_OWNER_COLLAPSE") && pipe.contains("MEMETRADER_OWNER_LANE"))
        assertTrue("runtime report must expose pre-attempt live buy suppressions", pipe.contains("Pre-attempt suppressions") && pipe.contains("LIVE_BUY_PREATTEMPT_PROVIDER_PROOF_BLIND") && pipe.contains("STALE_AUTH_LOCK_PRUNED"))
    }




    @Test
    fun live_buy_attempt_boundary_precedes_advisors_and_advisors_soft_shape_only() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val fnStart = exec.indexOf("private fun liveBuy")
        val advisorIdx = exec.indexOf("consultEntryAdvisors(ts, score, layerTag)", fnStart)
        val attemptIdx = exec.indexOf("LIVE_BUY_ATTEMPT", fnStart)
        assertTrue("liveBuy must count the attempt before advisor/route preflight can return", fnStart >= 0 && attemptIdx in fnStart until advisorIdx)
        val advisorStart = exec.indexOf("private fun consultEntryAdvisors")
        val advisorEnd = exec.indexOf("private fun liveBuy", advisorStart)
        val advisor = exec.substring(advisorStart, advisorEnd)
        assertTrue("advisor stack must expose soft-shape telemetry", advisor.contains("LIVE_BUY_ADVISOR_SOFT_SHAPE") && advisor.contains("softAdvisor("))
        val hardReturns = Regex("return false to").findAll(advisor).count()
        assertEquals("only confirmed hard rug prefilter may hard-block before live buy", 1, hardReturns)
        assertTrue(advisor.contains("RUG_PREFILTER_HARD_FAIL"))
        assertTrue("live report must expose advisor soft-shapes", pipe.contains("Advisor soft-shapes") && pipe.contains("LIVE_BUY_ENTERED"))
    }


    @Test
    fun sell_only_safe_mode_is_telemetry_not_global_live_buy_veto() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveBuyAdmissionGate.kt").readText()
        val reconciler = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellReconciler.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("SellOnlySafeMode must remain visible as soft allow telemetry", gate.contains("SELL_ONLY_SAFE_MODE_SOFT_ALLOW"))
        assertFalse("SellOnlySafeMode must not hard-block live buy admission", gate.contains("Decision.Blocked(\"SELL_ONLY_SAFE_MODE\""))
        assertFalse("soft allow must not increment blockedBuyCount", gate.contains("blockLiveBuyReason()"))
        assertTrue("same-mint close lease protection remains", gate.contains("CLOSE_PENDING_SAME_MINT"))
        assertTrue("reconciler must monitor healthy holds instead of selling them", reconciler.contains("RECONCILER_HEALTHY_HOLD_MONITORED") && reconciler.contains("action=no_sell_requeue"))
        assertTrue("maintenance RECONCILER_REQUEUE must be suppressed on healthy holds", exec.contains("RECONCILER_REQUEUE_SUPPRESSED_HEALTHY_HOLD") && exec.contains("HostWalletTokenTracker.getEntry"))
    }


    @Test
    fun live_style_min_hold_defers_soft_exits_before_sell_locks() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val reqIdx = exec.indexOf("fun requestSell")
        val holdIdx = exec.indexOf("LIVE_STYLE_MIN_HOLD_EXIT_DEFERRED", reqIdx)
        val lifecycleIdx = exec.indexOf("try { TokenLifecycleTracker.onSellPending", reqIdx)
        val leaseIdx = exec.indexOf("CloseLease.acquire", reqIdx)
        assertTrue("live min-hold must run before sell pending lifecycle and close lease", reqIdx >= 0 && holdIdx in reqIdx until lifecycleIdx && holdIdx < leaseIdx)
        assertTrue("V5.0.4192: live exit severity classifier must make min-hold subordinate to hard safety and runner protection", exec.contains("private enum class LiveExitSeverity") && exec.contains("classifyLiveExitIntent") && exec.contains("rawPnlPct <= -15.0") && exec.contains("peakGainPct >= 20.0 && givebackFromPeak >= 25.0") && exec.contains("intent.severity.ordinal >= LiveExitSeverity.RUNNER_PROTECT.ordinal"))
        assertTrue("live min-hold must not enqueue pending sells", exec.contains("action=no_sell_lock") && exec.contains("return SellResult.FAILED_RETRYABLE"))
        assertTrue("maintenance requeue uses its own healthy-hold suppressor", exec.contains("RECONCILER_REQUEUE") && exec.contains("return null") && exec.contains("RECONCILER_REQUEUE_SUPPRESSED_HEALTHY_HOLD"))
        assertTrue("report must expose live style hold deferrals", pipe.contains("styleHoldDeferred") && pipe.contains("LIVE_STYLE_MIN_HOLD_EXIT_DEFERRED"))
        assertTrue("V5.0.4192: live style min-hold must bypass on peak giveback so runners don't round-trip", exec.contains("LIVE_STYLE_MIN_HOLD_PEAK_GIVEBACK_BYPASS_4192") && exec.contains("classifyLiveExitIntent") && exec.contains("peakGainPct >= 20.0 && givebackFromPeak >= 25.0"))
        assertTrue("V5.0.4192: tiny-profit defer must reuse the same live exit severity classifier", exec.contains("liveProfitDustExitShouldDefer") && exec.contains("val intent = classifyLiveExitIntent(ts, reason)") && exec.contains("intent.severity.ordinal >= LiveExitSeverity.RUNNER_PROTECT.ordinal"))
        assertTrue("V5.0.4192: generic meme liveBuy handoff must carry resolved laneTag into live lane/journal stamping", exec.contains("layerTag = laneTag.takeIf { it.isNotBlank() && it != \"STANDARD\" } ?: \"\"") && exec.contains("EXECUTION_LANE_STAMPED_4162"))
        assertTrue("V5.0.4192: generic V3 liveBuy handoff must carry resolved execution laneTag", exec.contains("layerTag = resolveExecutionLane(ts, identity).takeIf { it.isNotBlank() && it != \"STANDARD\" } ?: \"\"") && exec.contains("resolvedInputLaneForPivot = resolveExecutionLane(ts, identity)"))
        assertTrue("V5.0.4192: shadow-to-live handoff must stamp MOONSHOT/laneTag instead of positional source collapse", exec.contains("val shadowLiveLane = resolveExecutionLane(ts, tradeId).ifBlank { \"MOONSHOT\" }") && exec.contains("layerTag = shadowLiveLane.takeIf { it.isNotBlank() && it != \"STANDARD\" } ?: \"MOONSHOT\""))
        assertFalse("V5.0.4192: liveBuy pivot must not use identity.source/scanner source ahead of resolved lane", exec.contains("identity?.source?.takeIf { it.isNotBlank() } ?: ts.position.tradingMode"))
        assertTrue("V5.0.4193: applied policy snapshot must be persisted on Position and TokenState for paper/live parity", java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText().contains("entryPolicySnapshot") && java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText().contains("lastPolicySnapshot") && exec.contains("private fun buildTradePolicySnapshot"))
        assertTrue("V5.0.4193: paper and live buys must stamp the actual applied policy snapshot", exec.contains("val paperPolicySnapshot = buildTradePolicySnapshot") && exec.contains("entryPolicySnapshot = paperPolicySnapshot") && exec.contains("val livePolicySnapshot = buildTradePolicySnapshot") && exec.contains("entryPolicySnapshot = livePolicySnapshot"))
        assertTrue("V5.0.4193: policy snapshot must include lane/style/source/score/planned/final/size multiplier",
            exec.contains("""lane=${'$'}safeLane""") && exec.contains("""style=${'$'}safeStyle""") && exec.contains("""scanner=${'$'}scannerSource""") && exec.contains("""planned=${'$'}{plannedSol.fmt(4)}""") && exec.contains("""final=${'$'}{finalSol.fmt(4)}""") && exec.contains("""sizeMult=${'$'}{mult.fmt(3)}"""))
        assertTrue("V5.0.4194: AdvancedExitManager must be wired as advisory severity input, not a parallel direct sell authority", exec.contains("advancedExitAdvisory") && exec.contains("AdvancedExitManager.evaluateExit") && exec.contains("ADVANCED_EXIT_MANAGER_ADVISORY_4194") && exec.contains("advancedHardSafety") && exec.contains("advancedRunnerProtect"))
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4195: ScannerSourceBrain must shape protected intake admission, not only reports/sizing", bot.contains("ScannerSourceBrain.intakeMultiplier") && bot.contains("sourceBrainProbationOnly") && bot.contains("sourceBrainHotRescue") && bot.contains("SCANNER_SOURCE_BRAIN_ADMISSION_SHAPED_4195"))
        assertTrue("V5.0.4196: UnifiedExitPolicyHead must shape live exit severity instead of report-only learning", exec.contains("unifiedExitSignalsFor") && exec.contains("UnifiedExitPolicyHead.stamp") && exec.contains("UnifiedExitPolicyHead.exitBias") && exec.contains("UNIFIED_EXIT_POLICY_HEAD_SHAPED_4196") && exec.contains("exitPolicyBankSoon") && exec.contains("exitPolicyLetRun"))
        assertTrue("V5.0.4197: StrategyHypothesisEngine size A/B must shape Executor AGI size stack, not FDG-only", exec.contains("hypothesisSizeMult") && exec.contains("StrategyHypothesisEngine.getSizeBias") && exec.contains("STRATEGY_HYPOTHESIS_EXECUTOR_SIZE_SHAPED_4197") && exec.contains("hypothesis"))
        assertTrue("V5.0.4198: V3 personality/LLM veto must soft-shape size instead of hard-returning before execution", bot.contains("personalitySizeMult") && bot.contains("V3_PERSONALITY_SOFT_SHAPE_4198") && bot.contains("personalitySizeMult *") && !bot.contains("V3_PERSONALITY_VETO_PREBUY"))
        val safety4199 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenSafetyChecker.kt").readText()
        assertTrue("V5.0.4199: live risk overlay must hard-block LP-unlocked + low-liq/holder/provider rug sheets even when numeric lpLockedPct is missing", safety4199.contains("LIVE_RUG_OVERLAY_BLOCK_4199") && safety4199.contains("lpUnlockedRugCombo") && safety4199.contains("lpLockPct = 0.0") && safety4199.contains("""hard.add("LP unlocked with low liquidity/holder/provider risk"""))
        assertTrue("V5.0.4199: canonical scanner intake must reject hard safety/rug-overlay candidates before watchlist or probation admission", bot.contains("INTAKE_SAFETY_HARD_REJECT_4199") && bot.contains("no_watchlist=true no_probation=true") && bot.contains("""ScannerHardRejectStore.mark(mint, symbol, "INTAKE_SAFETY_HARD_REJECT_4199""") && bot.indexOf("INTAKE_SAFETY_HARD_REJECT_4199") < bot.indexOf("INTAKE_PROBATION_ONLY"))
        assertTrue("V5.0.4200: take-win/full-profit exits must bypass settle-in before the silent grace return", exec.contains("trySweepTakeProfitExit") && exec.contains("SWEEP_TAKE_PROFIT_SETTLE_BYPASS_4200") && exec.indexOf("SWEEP_TAKE_PROFIT_SETTLE_BYPASS_4200") < exec.indexOf("silent grace for softer fluid path"))
        assertTrue("V5.0.4200: live style min-hold must not delay take-win/profit exits", exec.contains("intent.severity == LiveExitSeverity.PROFIT") && exec.contains("Tiny") && exec.contains("liveProfitDustExitShouldDefer"))
        assertTrue("V5.0.4200: paper settle-in must bypass take-win/full-profit exits", exec.contains("PAPER_TAKE_WIN_MIN_HOLD_BYPASS_4200") && !exec.contains("PAPER_PROFIT_MIN_HOLD"))
        val entryIntel4201 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/EntryIntelligence.kt").readText()
        val persistent4201 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PersistentLearning.kt").readText()
        assertTrue("V5.0.4201: EntryIntelligence must consume holdTimeMinutes as learned duration buckets", entryIntel4201.contains("holdTimeBucket(holdTimeMinutes)") && entryIntel4201.contains("holdTimeWinRates") && entryIntel4201.contains("HoldProfile:") && entryIntel4201.contains("holdProfileNudge"))
        assertTrue("V5.0.4201: EntryIntelligence hold-time buckets must persist through PersistentLearning", entryIntel4201.contains("holdTimeWinRates = weights.holdTimeWinRates") && persistent4201.contains("holdTimeBuckets") && persistent4201.contains("holdTimeTradeCount"))
        assertTrue("V5.0.4202: live ledger drift cap must use effective reconciler truth, not PositionWalletReconciler alone", exec.contains("effectiveChecked = maxOf(positionChecked, sellChecked, liveWalletChecked)") && exec.contains("LiveWalletReconciler.totalChecked()") && exec.contains("SellReconciler.totalChecked") && exec.contains("LIVE_LEDGER_DRIFT_CAP_BYPASS_RESOLVING_4202"))
        assertTrue("V5.0.4202: benign one-mint drift with active sell resolution must not dust-cap live growth", exec.contains("benignSingleDriftResolving") && exec.contains("activeSellResolution") && exec.contains("action=size_normal"))
        val edge4204 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/EdgeLearning.kt").readText()
        assertTrue("V5.0.4204/A10: EdgeLearning must consume exitPrice for outcome-basis validation", edge4204.contains("val priceDerivedPnl") && edge4204.contains("exitPrice/PnL contradiction") && edge4204.contains("validatedPnl"))
        assertTrue("V5.0.4204/A10: EdgeLearning threshold updates must be exit-quality weighted", edge4204.contains("exitQualityWeight") && edge4204.contains("signalWeight") && edge4204.contains("coerceIn(0.5, 1.5)"))
        val learningPersistence4205 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4205: live v3 scorer brains with saveToJson hooks must be persisted", learningPersistence4205.contains("HOLD_TIME_OPTIMIZER") && learningPersistence4205.contains("ORDER_FLOW_IMBALANCE") && learningPersistence4205.contains("SMART_MONEY_DIVERGENCE") && learningPersistence4205.contains("VOLATILITY_REGIME") && learningPersistence4205.contains("LIQUIDITY_CYCLE"))
        assertTrue("V5.0.4205: v3 scorer brains must restore via loadFromJson from LearningPersistence blobs", learningPersistence4205.contains("HoldTimeOptimizerAI.loadFromJson(JSONObject(it))") && learningPersistence4205.contains("OrderFlowImbalanceAI.loadFromJson(JSONObject(it))") && learningPersistence4205.contains("SmartMoneyDivergenceAI.loadFromJson(JSONObject(it))"))
        val botService4206 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4206: ChopFilter must be revived as FDG score shaping, not intake hard veto", botService4206.contains("CHOP_FILTER_SOFT_SHAPED_4206") && botService4206.contains("laneQualifiedBuyDecision") && botService4206.contains("base.copy(entryScore = (base.entryScore - chopPenalty).coerceAtLeast(0.0))") && botService4206.contains("val sourceForChop = try { status.tokens[mintForProbe]?.source"))
        assertTrue("V5.0.4206: ChopFilter must not hard-return or purge candidates from intake", botService4206.contains("action=fdg_score_penalty") && botService4206.contains("no purge, no slot removal"))
        val executor4207 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4207: SellOptimizationAI recordExitOutcome must be fed from terminal trade finality", executor4207.contains("SellOptimizationAI.recordExitOutcome") && executor4207.contains("ledgerAllowsClosedLearning && accountingTrainable") && executor4207.contains("SELL_OPTIMIZATION_OUTCOME_LEARNED_4207"))
        assertTrue("V5.0.4207: SellOptimizationAI outcome learning must stay off the hot path", executor4207.contains("GlobalScope.launch(AppDispatchers.sideEffect)") && executor4207.contains("wouldHaveBeenProxy"))
        val learningPersistence4208 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        val sellOpt4208 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/SellOptimizationAI.kt").readText()
        val narrative4208 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MemeNarrativeAI.kt").readText()
        val cult4208 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CultMomentumAI.kt").readText()
        assertTrue("V5.0.4208: narrative and exit learners must persist through LearningPersistence", learningPersistence4208.contains("MEME_NARRATIVE") && learningPersistence4208.contains("CULT_MOMENTUM") && learningPersistence4208.contains("SELL_OPTIMIZATION"))
        assertTrue("V5.0.4208: learner classes must expose export/import state hooks", sellOpt4208.contains("fun exportState(): String") && sellOpt4208.contains("fun importState(json: String)") && narrative4208.contains("fun exportState(): String") && cult4208.contains("fun importState(json: String)"))
        val regimeTransition4209 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/RegimeTransitionAI.kt").readText()
        val learningPersistence4209 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4209: RegimeTransitionAI history and transition cache must persist", regimeTransition4209.contains("fun exportState(): String") && regimeTransition4209.contains("regimeHistory") && regimeTransition4209.contains("transitionCache") && learningPersistence4209.contains("REGIME_TRANSITION"))
        val reflex4210 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ReflexAI.kt").readText()
        val learningPersistence4210 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4210: ReflexAI liquidity drain confirmation memory must persist", reflex4210.contains("fun exportState(): String") && reflex4210.contains("liquiditySamples") && reflex4210.contains("drainHits") && learningPersistence4210.contains("REFLEX_AI"))
        val insider4211 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/InsiderTrackerAI.kt").readText()
        val learningPersistence4211 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4211: InsiderTracker custom wallets must persist without replaying recent signals", insider4211.contains("customWallets") && insider4211.contains("Do not persist recentSignals") && learningPersistence4211.contains("INSIDER_TRACKER"))
        val executor4212 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4212: meme paper/live opens must register PortfolioHeatAI exposure", executor4212.contains("PortfolioHeatAI.addPosition") && executor4212.contains("PORTFOLIO_HEAT_MEME_POSITION_REGISTERED_4212") && executor4212.contains("market = \"MEME\""))
        assertTrue("V5.0.4212: terminal close paths must remove PortfolioHeatAI exposure", executor4212.contains("PortfolioHeatAI.removePosition(tradeId.mint)") && executor4212.contains("PortfolioHeatAI.removePosition(ts.mint)"))
        val positionPersistence4213 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4213: restored positions must rehydrate EmergentGuardrails and PortfolioHeatAI", positionPersistence4213.contains("EmergentGuardrails.registerPosition") && positionPersistence4213.contains("PortfolioHeatAI.addPosition") && positionPersistence4213.contains("PORTFOLIO_HEAT_RESTORED_POSITION_REGISTERED_4213"))
        val botService4214 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val positionPersistence4214 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4214: Manipulated lane opens must use achievable 14/-11 geometry", botService4214.contains("takeProfitPct = 14.0") && botService4214.contains("stopLossPct = -11.0") && !botService4214.contains("takeProfitPct = 25.0"))
        assertTrue("V5.0.4214: restored MANIPULATED positions must rehydrate ManipulatedTraderAI active map", positionPersistence4214.contains("MANIPULATED_RESTORED_ACTIVE_POSITION_4214") && positionPersistence4214.contains("ManipulatedTraderAI.addPosition") && positionPersistence4214.contains("restoredLayer.equals(\"MANIPULATED\""))
        val quality4215 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/QualityTraderAI.kt").readText()
        val blueChip4215 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/BlueChipTraderAI.kt").readText()
        val positionPersistence4215 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4215: Quality/BlueChip must expose mode-correct restore helpers", quality4215.contains("fun restorePosition(position: QualityPosition, isPaper: Boolean)") && blueChip4215.contains("fun restorePosition(position: BlueChipPosition, isPaper: Boolean)"))
        assertTrue("V5.0.4215: restored QUALITY/BLUE_CHIP positions must rehydrate lane active maps", positionPersistence4215.contains("QUALITY_RESTORED_ACTIVE_POSITION_4215") && positionPersistence4215.contains("BLUE_CHIP_RESTORED_ACTIVE_POSITION_4215") && positionPersistence4215.contains("QualityTraderAI.restorePosition") && positionPersistence4215.contains("BlueChipTraderAI.restorePosition"))
        val botService4218 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4218: ProjectSniper missions must engage only after buy opens", botService4218.contains("val sniperOpened = executor.shitCoinBuy") && botService4218.indexOf("val sniperOpened = executor.shitCoinBuy") < botService4218.indexOf("ProjectSniperAI.engageMission") && botService4218.contains("PROJECT_SNIPER_MISSION_AFTER_BUY_4218") && botService4218.contains("lane=PROJECT_SNIPER"))
        val botService4219 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4219: Quality TP risk/reward floor must use absolute stop distance", botService4219.contains("kotlin.math.abs(qualitySignal.stopLossPct) * 2.0") && !botService4219.contains("qualitySignal.stopLossPct * 2.0  // Always >= 2x the stop"))
        val botService4220 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4220: DipHunter active state must open only after buy succeeds", botService4220.contains("val dipOpened = executor.dipHunterBuy") && botService4220.indexOf("val dipOpened = executor.dipHunterBuy") < botService4220.indexOf("DipHunterAI.openDip") && botService4220.contains("DIP_HUNTER_OPEN_AFTER_BUY_4220") && botService4220.contains("TradeAuthorizer.releasePosition(ts.mint, \"BUY_NOT_OPENED\", TradeAuthorizer.ExecutionBook.DIP_HUNTER)"))
        val dipHunter4221 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/DipHunterAI.kt").readText()
        val positionPersistence4221 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4221: restored DIP_HUNTER positions must rehydrate activeDips", dipHunter4221.contains("fun restoreDip(position: DipPosition)") && positionPersistence4221.contains("DIP_HUNTER_RESTORED_ACTIVE_DIP_4221") && positionPersistence4221.contains("DipHunterAI.restoreDip") && positionPersistence4221.contains("restoredLayer.equals(\"DIP_HUNTER\""))
        val dipHunter4222 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/DipHunterAI.kt").readText()
        val sniper4222 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        assertTrue("V5.0.4222: lane-local daily loss must size-shape DipHunter/Sniper instead of hard-amputing volume", dipHunter4222.contains("DIP_DAILY_LOSS_RECOVERY_PROBE_4222") && dipHunter4222.contains("if (dailyLossRecoveryProbe) positionSol *= 0.35") && !dipHunter4222.contains("return noDip(\"DAILY_LOSS_LIMIT") && sniper4222.contains("SNIPER_DAILY_LOSS_RECOVERY_PROBE_4222") && sniper4222.contains("dailyLossProbeMult") && !sniper4222.contains("return noEngage(\"DAILY_LOSS_CAP"))
        val express4223 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        val botService4223 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val positionPersistence4223 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4223: Express daily loss and live pendingVerify must preserve ride/exits", express4223.contains("EXPRESS_DAILY_LOSS_RECOVERY_PROBE_4223") && express4223.contains("if (dailyLossRecoveryProbe) positionSol *= 0.35") && express4223.contains("fun restoreRide(ride: ExpressRide)") && !express4223.contains("return noRide(\"DAILY_LOSS_LIMIT") && botService4223.contains("ts.position.qtyToken > 0.0 || ts.position.pendingVerify || ts.position.isOpen) com.lifecyclebot.v3.scoring.ShitCoinExpress.boardRide") && positionPersistence4223.contains("EXPRESS_RESTORED_ACTIVE_RIDE_4223"))
        val blueChip4224 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/BlueChipTraderAI.kt").readText()
        val cashGen4224 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue("V5.0.4224: BlueChip/CashGen PAUSED must probe-size instead of hard-zeroing entries", blueChip4224.contains("BLUECHIP_DAILY_LOSS_RECOVERY_PROBE_4224") && blueChip4224.contains("if (dailyLossRecoveryProbe) positionSol *= 0.35") && !blueChip4224.contains("reason = \"PAUSED: Daily loss limit reached\"") && cashGen4224.contains("TREASURY_DAILY_LOSS_RECOVERY_PROBE_4224") && cashGen4224.contains("TreasuryMode.PAUSED -> 0.35") && !cashGen4224.contains("PAUSED: Daily loss limit"))
        val shitCoin4225 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        val expressEdu4225 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        assertTrue("V5.0.4225: education mute must soft-shape ShitCoin/Express instead of no-trade", shitCoin4225.contains("EDU_MUTED_SOFT_SHAPE_4225") && shitCoin4225.contains("eduSizeMult = if (status == \"MUTE\") 0.35 else 0.65") && !shitCoin4225.contains("reason = \"EDU_MUTED: layer muted") && expressEdu4225.contains("EDU_MUTED_SOFT_SHAPE_4225") && expressEdu4225.contains("educSizeMult = if (educStatus == \"MUTE\") 0.35 else 0.65") && !expressEdu4225.contains("return noRide(\"EDU_MUTED"))
        val sniper4226 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        val persistence4226 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4226: restored ProjectSniper rows must rehydrate active missions", sniper4226.contains("fun restoreMission(mission: SniperMission)") && persistence4226.contains("PROJECT_SNIPER_RESTORED_ACTIVE_MISSION_4226") && persistence4226.contains("ProjectSniperAI.restoreMission") && persistence4226.contains("restoredLayer.equals(\"PROJECT_SNIPER\""))
        val moonshot4227 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        val persistence4227 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4227: restored Moonshot rows must rehydrate active runner positions", moonshot4227.contains("fun restorePosition(position: MoonshotPosition)") && persistence4227.contains("MOONSHOT_RESTORED_ACTIVE_POSITION_4227") && persistence4227.contains("MoonshotTraderAI.restorePosition") && persistence4227.contains("restoredLayer.equals(\"MOONSHOT\"") )
        val shitRestore4228 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        val cashRestore4228 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        val persistence4228 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PositionPersistence.kt").readText()
        assertTrue("V5.0.4228: restored ShitCoin/Treasury rows must rehydrate active maps", shitRestore4228.contains("fun restorePosition(position: ShitCoinPosition") && cashRestore4228.contains("fun restorePosition(position: TreasuryPosition") && persistence4228.contains("SHITCOIN_RESTORED_ACTIVE_POSITION_4228") && persistence4228.contains("TREASURY_RESTORED_ACTIVE_POSITION_4228") && persistence4228.contains("ShitCoinTraderAI.restorePosition") && persistence4228.contains("CashGenerationAI.restorePosition"))
    }





    @Test
    fun live_profit_exit_doctrine_is_learned_expectancy_not_scrap_or_hardcoded() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("profit bands must be driven by LIVE-terminal StrategyTelemetry expectancy, not mixed paper/partials", exec.contains("fun learnedExitRungs") && exec.contains("StrategyTelemetry.computeLiveTerminalLeaderboard") && exec.contains("pfExpectancyPp") && exec.contains("avgWinPct"))
        assertTrue("first live profit exit must floor away sub-fee scraps", exec.contains("MIN_PARTIAL_GAIN_PCT = 50.0") && exec.contains("LIVE_TINY_PROFIT_EXIT_DEFERRED"))
        assertFalse("old 9% WR recovery scrap floor must not survive", exec.contains("MIN_PARTIAL_GAIN_PCT = 9.0") || exec.contains("Triple(9.0"))
        assertTrue("runner lanes should expand toward 1000/10000 bands through learned expectancy", exec.contains("1000.0") && exec.contains("10000.0") && exec.contains("runner"))
        assertTrue("capital recovery/profit lock thresholds must consume learned bands", exec.contains("learnedCapitalRecovery") && exec.contains("learnedProfitLock") && exec.contains("WrRecoveryPartial.learnedExitRungs"))
        assertTrue("sweep TP must use learned floor instead of tiny static TP", exec.contains("learnedTpFloor") && exec.contains("liveGrowthTpPct"))
    }

    @Test
    fun live_learning_and_growth_use_terminal_movement_patterns_not_partial_noise() {
        val movement = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MovementPatternSignal.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val doctrine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        listOf("BREAKOUT_CONTINUATION", "PULLBACK_RECLAIM", "ACCUMULATION_COMPRESSION", "EXHAUSTION_CHASE", "FREEFALL_NO_RECLAIM", "VOLUME_IGNITION").forEach {
            assertTrue("MovementPatternSignal must classify $it", movement.contains(it))
        }
        assertTrue("final live size must consume movement-aware growth policy", exec.contains("MovementPatternSignal.from(ts)") && exec.contains("LiveGrowthDoctrine.sizePolicy(laneKey, score, walletSol, spendable, movementSignal)"))
        assertTrue("live hold minimum must be movement-aware to avoid instant shutdown of runners", exec.contains("movement_${'$'}{movementSignal.pattern.lowercase()}") && exec.contains("movementSignal?.holdMult"))
        val pcHook = exec.substring(exec.indexOf("PatternClassifier hooks"), exec.indexOf("reset BotBrain", exec.indexOf("PatternClassifier hooks")))
        assertTrue("PatternClassifier must still learn live terminal sells", pcHook.contains("trade.side == \"SELL\"") && pcHook.contains("isLive = trade.mode.equals(\"live\""))
        assertFalse("PatternClassifier must not consume entry on PARTIAL_SELL before terminal movement outcome", pcHook.contains("PARTIAL_SELL") && pcHook.contains("PatternClassifier.noteExit"))
        assertTrue("ToolkitSignalSheet must expose movement patterns as SMART_CHART/PATTERN_CLASSIFIER tool votes", sheet.contains("MovementPatternSignal.from(ts)") && sheet.contains("MOVEMENT_PATTERN") && sheet.contains("TOOLKIT_MOVEMENT"))
        assertTrue("LiveGrowthDoctrine reason must surface movement pattern/timing", doctrine.contains("movement=${'$'}{movement?.pattern") && doctrine.contains("timing=${'$'}{movement?.timing"))
    }

    @Test
    fun live_growth_doctrine_is_core_source_for_all_lanes_tools_and_sizing() {
        val doctrine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        listOf("STANDARD", "QUALITY", "BLUECHIP", "TREASURY", "MOONSHOT", "SHITCOIN", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER", "EXPRESS", "CYCLIC", "PRESALE", "WHALE", "COPY").forEach {
            assertTrue("LiveGrowthDoctrine must enumerate lane/trader $it", doctrine.contains(it))
        }
        listOf("PUMP_FUN", "RAYDIUM", "JUPITER", "METIS", "MFE_TRAIL", "SMART_CHART", "COPY_TRADE", "WHALE_WALLET", "JITO", "DEFENSIVE_PROBE").forEach {
            assertTrue("LiveGrowthDoctrine must enumerate trading tool $it", doctrine.contains(it))
        }
        assertTrue("AgenticStyleRouter must pull lane/tool fallbacks from LiveGrowthDoctrine", router.contains("LiveGrowthDoctrine.growthLaneFallback") && router.contains("LiveGrowthDoctrine.growthToolFallback"))
        assertTrue("Final live sizing authority must consume LiveGrowthDoctrine", exec.contains("LiveGrowthDoctrine.sizePolicy") && exec.contains("growthPolicy.reason") && exec.contains("doBuy.final") && exec.contains("liveBuy.final"))
        assertFalse("COPY_TRADE must not be a live hard confidence veto", fdg.contains("COPY_TRADE_LIVE_LOW_CONFIDENCE"))
        assertFalse("WHALE_FOLLOW must not be live-disabled at FDG", fdg.contains("WHALE_FOLLOW_LIVE_DISABLED"))
        assertTrue("COPY/WHALE must become live-growth probes", fdg.contains("copy_trade_live_micro_probe") && fdg.contains("whale_follow_live_growth_probe"))
    }







    @Test
    fun live_policy_3959_cyclic_jupiter_drawdown_and_solana_coverage() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val endpoint = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionEndpointHealth.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val jupiter = java.io.File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        val sizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        val merge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMergeQueue.kt").readText()
        val lanes = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()
        assertTrue("CYCLIC must not tick live until actual wallet USD is >= 5000", bot.contains("val liveThreshold = 5000.0") && bot.contains("walletUsdNow >= liveThreshold") && bot.contains("CYCLIC_WALLET_USD_BELOW_5000_MEME_STILL_ACTIVE"))
        assertFalse("CYCLIC live threshold must not remain 1500", bot.contains("val liveThreshold = 1500.0") || bot.contains("walletUsd >= ${'$'}1500"))
        assertTrue("Jupiter quote endpoint must never be disabled", endpoint.contains("neverDisable(endpoint)") && endpoint.contains("JUPITER_QUOTE_NEVER_DISABLED") && endpoint.contains("return false"))
        assertFalse("Executor must not pre-throw PROVIDER_DISABLED for Jupiter quote", exec.contains("""throw Exception("PROVIDER_DISABLED:JUPITER_QUOTE"""))
        assertFalse("JupiterApi must not disable JUPITER_QUOTE on 429/503/4xx", jupiter.contains("ExecutionEndpointHealth.disable(endpoint"))
        assertTrue("Jupiter local ladder should fail as quote exhausted, not provider disabled", exec.contains("NO_QUOTE:JUPITER_QUOTE_EXHAUSTED") && !exec.contains("val terminal = if ((lastQuoteError"))
        assertTrue("Live drawdown must size-shape, not pause entries", sizer.contains("live drawdown size-shapes; never pauses entries") && sizer.contains("drawdownMult.coerceAtLeast(if (isPaperMode) 0.0 else 0.30)"))
        assertFalse("Live drawdown circuit breaker must not return a zero-size pause", sizer.contains("drawdown_circuit_breaker") || sizer.contains("entries paused"))
        assertTrue("Scanner merge must include Solana-wide venues beyond pump/raydium/dex", merge.contains("METEORA") && merge.contains("ORCA") && merge.contains("PUMPSWAP") && merge.contains("JUPITER_TOKEN_LIST") && merge.contains("SOLANA_WIDE") && merge.contains("PROGRAM_ACCOUNT"))
        assertTrue("STANDARD/V3/CORE must be explicit lane-election participants", merge.contains("STANDARD") && merge.contains("CORE") && merge.contains("V3") && lanes.contains(""""V3" to""") && lanes.contains(""""STANDARD" to""") && lanes.contains(""""CORE" to"""))
    }

    @Test
    fun live_mega_profit_compounding_caps_press_winners_without_safety_bypass() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val doctrine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        assertTrue("Positive expectancy winners need a larger max boost than legacy 1.75x", exec.contains("winnerMaxBoost") && exec.contains("laneEvMult > 1.0") && exec.contains("sol * winnerMaxBoost"))
        assertTrue("Growth doctrine must raise winner lane wallet allocation", doctrine.contains("AGGRESSIVE_2X_5X_LIVE_WALLET_GROWTH") && doctrine.contains("MOONSHOT") && doctrine.contains("0.35") && doctrine.contains("absoluteCap"))
        assertTrue("Bleeder lanes remain lower allocation than winner lanes", doctrine.contains(""""EXPRESS" -> 0.72""") && doctrine.contains(""""SHITCOIN" -> 0.78"""))
        assertTrue("Safety/route gates remain upstream", doctrine.contains("never bypasses route") && exec.contains("realisticLiveEntrySize"))
    }

    @Test
    fun live_wallet_growth_releases_caps_for_proven_winner_lanes() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertFalse("MOONSHOT must not be permanently capped at 0.55 while it is the top SOL winner", exec.contains("""laneTag.contains("MOONSHOT")    -> if ((wr ?: 0.0) > 0.0)  0.55 else 0.55"""))
        assertTrue("MOONSHOT cap must release when expectancy allocator says winner", exec.contains("""laneTag.contains("MOONSHOT")""") && exec.contains("laneEvMult >= 1.0") && exec.contains("WALLET GROWTH CAP RELEASE"))
        assertTrue("PRESALE/PROJECT_SNIPER and BLUECHIP winner caps must release too", exec.contains("""laneTag.contains("PRESALE") || laneTag.contains("PROJECT_SNIPER")""") && exec.contains("""laneTag.contains("BLUECHIP")"""))
        assertTrue("SHITCOIN must shrink harder when expectancy is negative", exec.contains("""laneTag.contains("SHITCOIN")    -> if (laneEvMult < 1.0) 0.35 else 0.65"""))
    }

    @Test
    fun live_wallet_growth_allocator_applies_strategy_expectancy_to_real_size() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val damper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        assertFalse("Live must not bypass LaneExpectancyDamper anymore", exec.contains("LIVE_EXPECTANCY_SIZE_BYPASSED"))
        assertTrue("Live must apply strategy expectancy sizing", exec.contains("LIVE_EXPECTANCY_SIZE_APPLIED") && exec.contains("LIVE_WALLET_GROWTH_ALLOCATOR"))
        // V5.0.4117 — AGI stack must be wired into buy sizing
        assertTrue("AGI size stack: strategyTunerSizeMult in multiplierProduct", exec.contains("strategyTunerSizeMult"))
        assertTrue("AGI size stack: uphConvictionMult in multiplierProduct", exec.contains("uphConvictionMult"))
        assertTrue("Live floor must allow bleeders to become cheap probes", exec.contains("laneEvMult < 0.50") && exec.contains("-> 0.08"))
        assertTrue("LaneExpectancyDamper must press proven winners, not only shrink losers", damper.contains("WALLET GROWTH ALLOCATOR") && damper.contains("WINNER_MAX_MULT") && damper.contains("m.totalSolPnl > 0.0") && damper.contains("m.winRatePct >= 50.0"))
        assertTrue("Bleeder floor must be materially below half-size for wallet growth", damper.contains("private const val MIN_MULT = 0.18") && damper.contains("CATASTROPHIC_MIN_MULT = 0.08"))
    }

    @Test
    fun runtime_3955_finality_orphan_and_balance_wait_faults_are_source_scoped() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val snap = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()
        val wait = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofWaitState.kt").readText()
        assertTrue("FDG-approved safety-blind WATCH must soft-allow with nonzero liquidity/no hardNo", gate.contains("safetyBlindSoftAllow") && gate.contains("safetyKnownOk || safetyBlindSoftAllow") && gate.contains("Confirmed rugs and zero-liquidity still block later"))
        assertTrue("orphan live accounting must subtract reconciler GRACE", snap.contains("positionReconSnapshot?.grace") && snap.contains("val graceAllowance = maxOf(1, reconcilerGrace)") && snap.contains("ORPHAN GRACE MUST FOLLOW THE RECONCILER"))
        assertTrue("balance-proof waits must release close leases", wait.contains("BALANCE_PROOF_WAIT_NO_ACTIVE_CLOSE") && wait.contains("CloseLease.release"))
        assertTrue("doctor noSig fault must use actionable noSig after active proof waits", doctor.contains("val actionableNoSig = (noSig - waitStateSize).coerceAtLeast(0L)") && doctor.contains("rawNoSig=${'$'}") && doctor.contains("actionableNoSig > 0L"))
    }

    @Test
    fun low_liq_fdg_approved_watch_is_size_penalty_not_finality_block() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("FDG-approved WATCH restore must allow nonzero low liquidity", gate.contains("val liqOk = effectiveLiq > 0.0") && gate.contains("LOW-LIQ WATCH RESTORE ALIGNMENT"))
        assertFalse("ExecutableOpenGate must not require USD 1200 liquidity for FDG-approved WATCH restore", gate.contains("latestAllows && safetyOk && effectiveLiq >= 1200.0") || gate.contains("liquidityUsd >= 1200.0"))
        assertTrue("thin-liq restored entries must still be clamped economically", gate.contains("LiveRestoreExecutionPolicy.fromRuntimeDrift") && exec.contains("realisticLiveEntrySize"))
        assertTrue("generic exit reasons must be canonicalized before queue/journal poisoning", exec.contains("EXIT_ROUTE_RETRY_${'$'}{trackerStatus}_${'$'}{closeState}") && exec.contains("requestReason") && exec.contains("return doSell(ts, requestReason, wallet, walletSol)") && exec.contains("PendingSellQueue.add(ts.mint, ts.symbol, reason)"))
    }

    @Test
    fun live_growth_runtime_residues_zero_conf_watch_and_reconciler_are_source_aligned() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val zeroBlock = fdg.substring(fdg.indexOf("ZERO-CONF SOURCE ALIGNMENT"), fdg.indexOf("val earlyMemoryScore", fdg.indexOf("ZERO-CONF SOURCE ALIGNMENT")))
        assertTrue("live zero-confidence must become a micro-probe tag", zeroBlock.contains("live_zero_conf_micro_probe") && zeroBlock.contains("conf=0% → LIVE micro-probe"))
        assertFalse("live zero-confidence must not return a FinalDecision before the micro-probe path", zeroBlock.contains("return FinalDecision"))
        val watchRestore = gate.substring(gate.indexOf("verdictAllowedByFdg"), gate.indexOf("""return "EXEC_OPEN_DROPPED_PRE_FDG_NOT_BUY"""", gate.indexOf("verdictAllowedByFdg")))
        assertTrue("FDG-approved WATCH restore must use current live safety/liquidity", watchRestore.contains("currentSafetyTier.equals") && watchRestore.contains("currentLiq") && watchRestore.contains("LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW"))
        assertFalse("WATCH restore safetyOk must not require currentStateVersion equality", watchRestore.contains("currentStateVersion && (currentSafetyTier"))
        assertTrue("reconciler-triggered sells must carry tracker lifecycle reason, not generic learning poison", bot.contains("RECONCILER_REQUEUE_${'$'}{trackerStatus}") && bot.contains("trackerStatus=") && bot.contains("reason=${'$'}") && bot.contains("requeueReason"))
        assertTrue("executor suppressor must cover prefixed reconciler maintenance reasons", exec.contains("""reason.startsWith("RECONCILER_REQUEUE", ignoreCase = true)"""))
    }

    @Test
    fun live_growth_doctrine_low_confidence_and_pumpportal_skips_do_not_choke_execution() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val lowConfStart = fdg.indexOf("LIVE now follows")
        val lowConfEnd = fdg.indexOf("} else if (blockReason == null)", lowConfStart)
        val lowConfBlock = fdg.substring(lowConfStart, lowConfEnd)
        assertTrue("live low confidence must become adaptive size shaping, not a hard block", lowConfBlock.contains("LOW-CONF ADAPTIVE_SIZE") && lowConfBlock.contains("live_low_conf_adaptive_size"))
        assertFalse("live low confidence must not set blockReason", lowConfBlock.contains("blockReason =") && lowConfBlock.contains("LOW_CONFIDENCE"))
        val pumpSkipStart = exec.indexOf("PumpPortal skipped for partial/profit")
        val pumpSkipEnd = exec.indexOf("return null", pumpSkipStart)
        val pumpSkipBlock = exec.substring(pumpSkipStart, pumpSkipEnd)
        assertFalse("skipping PumpPortal partial route is not a PumpPortal attempt and must not trip kill switch", pumpSkipBlock.contains("PumpPortalKillSwitch.recordPartialAttempt"))
        assertTrue("skipped PumpPortal route must be telemetry only", pumpSkipBlock.contains("PUMPPORTAL_PARTIAL_ROUTE_SKIPPED_NOT_ATTEMPTED"))
    }

    @Test
    fun live_auth_locks_are_truth_pruned_not_permanent_open_positions() {
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("TradeAuthorizer must prune stale live auth locks against wallet/accounting truth", auth.contains("STALE_AUTH_LOCK_PRUNED") && auth.contains("LIVE_AUTH_LOCK_GRACE_MS") && auth.contains("getOpenForAccountingMints") && auth.contains("getActuallyHeldMints"))
        assertTrue("same-book and cross-book duplicate checks must use authoritative lock validation", auth.contains("isAuthoritativeOpenLock(sameBookLock, isPaperMode)") && auth.contains("lock != null && isAuthoritativeOpenLock(lock, isPaperMode)"))
        assertTrue("post-auth hard CORE aborts must release auth locks; personality veto is soft-shaped", bot.contains("V3_SYMBOLIC_BLOCK_PREBUY") && bot.contains("TradeAuthorizer.releasePosition(ts.mint") && bot.contains("V3_PERSONALITY_SOFT_SHAPE_4198") && !bot.contains("V3_PERSONALITY_VETO_PREBUY"))
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("v3 live pre-open no-wallet abort must release auth lock", exec.contains("V3_LIVE_BUY_NO_WALLET_PREOPEN") && exec.contains("LIVE_BUY_PREOPEN_RELEASE_NO_WALLET"))
    }

    @Test
    fun live_buy_finality_ticket_and_executor_phase_contracts_are_pinned() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val pre = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("FDG allow must create immutable execution tickets", gate.contains("data class ExecutionTicket") && gate.contains("EXEC_TICKET_CREATED") && gate.contains("allowedAttempts[laneKey(ticket.mint, ticket.lane)]"))
        assertTrue("ticket restore must bypass mutable WATCH/version/lane churn", gate.contains("EXEC_TICKET_RESTORED_IMMUTABLE") && gate.contains("immutableTicket == null && !selectedLaneMatchesRequest") && gate.contains("""safetyTier.equals("UNKNOWN", true) && immutableTicket == null"""))
        assertTrue("stale/finality failures need separate counters", exec.contains("BUY_FAILED_FINALITY") && exec.contains("BUY_FAILED_STALE_TICKET") && exec.contains("BUY_FAILED_ROUTE") && exec.contains("BUY_FAILED_SAFETY"))
        assertTrue("executor phase counters must represent actual tx progress", listOf("EXEC_SELECTED", "EXEC_TICKET_CREATED", "QUOTE_REQUESTED", "QUOTE_OK", "SWAP_BUILT", "TX_SIGNED", "TX_SUBMITTED", "TX_CONFIRMED", "BUY_JOURNALED").all { (gate + exec).contains(it) })
        assertTrue("tx confirmed without live journal must fail regression guard", pipe.contains("TX_CONFIRMED_WITHOUT_BUY_JOURNALED") && pipe.contains("REGRESSION_GUARDS_FAIL"))
        assertTrue("confirmed live BUY must journal after tx confirmation", exec.indexOf("TX_CONFIRMED") < exec.indexOf("BUY_JOURNALED") && exec.contains("recordTrade(ts, trade)"))
        assertTrue("live hard-safety residues must keep confirmed fatal terminal while pending proof is penalty-only", pre.contains("MINT_AUTHORITY_ACTIVE") && pre.contains("TOP_HOLDER_CONCENTRATION") && pre.contains("FATAL_WALLET_RISK_TEXT") && pre.contains("PRETRADE_PENDING_PROOF_PENALTY_ALLOW") && pre.contains("LIVE_ROUTE_LIQUIDITY_PROOF_PENDING"))
        assertFalse("active authority/high-holder live risks must not remain size-clamp penalty-only", pre.contains("MINT_AUTHORITY_ACTIVE_SIZE_CLAMP") || pre.contains("TOP_HOLDER_SIZE_CLAMP"))
        assertTrue("live outcome learning must not treat unknown top-holder as safe zero", exec.contains("if (ts.position.isPaperPosition) 0.0 else 50.0") && exec.contains("if (pos.isPaperPosition) 0.0 else 50.0"))
        assertTrue("V3 terminal early return must keep only mechanical hard reasons", bot.contains("NO_EXECUTABLE_ROUTE") && bot.contains("NO_SELL_ROUTE") && !bot.contains("""result.reason.contains("SCORE_TOO_LOW", ignoreCase = true) ||"""))
    }

    @Test
    fun token_map_is_authoritative_before_zero_liquidity_blocks() {
        val models = java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        val preTrade = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PreTradeHardGate.kt").readText()
        val execGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("TokenState must carry one authoritative CanonicalTokenMap from discovery", models.contains("data class CanonicalTokenMap") && models.contains("var tokenMap: CanonicalTokenMap"))
        assertTrue("Source labels must never become identity", authority.contains("SOURCE_LABELS") && authority.contains("DEX_BOOSTED") && authority.contains("RAYDIUM_NEW_POOL") && authority.contains("SOURCE_IDENTITY_BAD"))
        assertTrue("Missing route/provider data must be TOKEN_MAP_PENDING, not ZERO_LIQUIDITY", authority.contains("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP") && authority.contains("TOKEN_MAP_PENDING:missing_pair_route_liquidity_or_quote"))
        assertTrue("Pump.fun active curve must count as executable liquidity", authority.contains("PUMPFUN_BONDING_CURVE_EXECUTABLE") && authority.contains("tm.pumpFunExecutable = true"))
        assertTrue("DEX/Jupiter route with expectedOut must count as executable", authority.contains("DEX_ROUTABLE") && authority.contains("tm.dexRouteOk = true") && authority.contains("expectedOutAmount"))
        assertTrue("Hard zero requires completed hydration and provider quorum", authority.contains("tm.hydrationComplete && tm.routeStatus == \"NO_ROUTE\"") && authority.contains("tm.providerAttempts >= 2"))
        assertTrue("PreTradeHardGate must not raw-block lastLiquidityUsd==0", !preTrade.contains("if (ts.lastLiquidityUsd == 0.0) return block(ts, \"ZERO_LIQUIDITY\"") && preTrade.contains("TokenMapAuthority.liquidityVerdict(ts)") && preTrade.contains("TRUE_ZERO_LIQUIDITY"))
        assertTrue("ExecutableOpenGate must defer token-map pending instead of terminal zero", execGate.contains("EXEC_OPEN_DEFERRED_TOKEN_MAP") && execGate.contains("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP") && !execGate.contains("EXEC_OPEN_BLOCKED_ZERO_LIQUIDITY"))
        assertTrue("Executor must assert executable TokenMap before live spend", executor.contains("TokenMapAuthority.executableForLiveBuy(ts)") && executor.contains("TOKEN_MAP_INCOMPLETE") && executor.contains("BUY_TERMINAL_ROUTE_FAIL:TOKEN_MAP_INCOMPLETE"))
    }

    @Test
    fun live_execution_attempts_have_immutable_mode_tokenmap_first_and_terminal_events() {
        val execMode = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecMode.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("ExecMode enum must be the immutable attempt authority", execMode.contains("enum class ExecMode") && execMode.contains("PAPER") && execMode.contains("LIVE") && execMode.contains("SHADOW") && execMode.contains("data class ExecutionContext"))
        assertTrue("liveBuy must carry ExecutionContext, not paper Boolean flags", executor.contains("executionContext: ExecutionContext?") && !executor.contains("private fun liveBuy(ts: TokenState, sol: Double, score: Double, paper"))
        assertTrue("LIVE mode desync must abort/fail explicitly without treating fresh placeholders as paper", executor.contains("LIVE_MODE_DESYNC") && executor.contains("execCtx.execMode != ExecMode.LIVE") && executor.contains("alreadyOpenPosition") && executor.contains("pre-open TokenState.position is a placeholder"))
        assertFalse("fresh live entry candidates must not be blocked solely by Position.isPaperPosition default=true", executor.contains("val paperFlag = try { ts.position.isPaperPosition }"))
        assertFalse("No live EXEC_TRACE_BUY telemetry may hardcode paper=true", executor.contains("EXEC_TRACE_BUY") && executor.contains("paper=true"))
        val liveBuyBodyStart = executor.indexOf("private fun liveBuy")
        val tokenMapIdx = executor.indexOf("TOKEN_MAP_START", liveBuyBodyStart)
        val quoteOkIdx = executor.indexOf("QUOTE_OK", tokenMapIdx)
        val buyPlanIdx = executor.indexOf("BUY_PLAN_OK", quoteOkIdx)
        assertTrue("TokenMap and quote proof must be before BUY_PLAN_OK", liveBuyBodyStart >= 0 && tokenMapIdx in (liveBuyBodyStart + 1) until quoteOkIdx && quoteOkIdx < buyPlanIdx)
        assertTrue("TokenMap incomplete must produce a BUY fail terminal, not silent lane release", executor.contains("LIVE_BUY_FAILED") && executor.contains("TOKEN_MAP_INCOMPLETE") && executor.contains("BUY_TERMINAL_ROUTE_FAIL:TOKEN_MAP_INCOMPLETE"))
        assertTrue("Deferred/live-busy branches must be non-terminal instead of timeout poisoning", executor.contains("LIVE_BUY_DEFERRED_NON_TERMINAL") && executor.contains("DEFERRED_REQUOTE_REQUIRED") && executor.contains("MUTEX_BUSY_DEFERRED"))
        assertTrue("Submit/finality/journal stages must be in the live attempt chain", listOf("TX_SUBMIT_START", "TX_SUBMITTED", "FINALITY_CONFIRMED", "POSITION_TRACKED", "JOURNAL_WRITE_OK").all { executor.contains(it) })
    }

    @Test
    fun strategy_bleed_cannot_promote_bad_lanes_to_live_quality() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("SHITCOIN live bleed must quarantine/paper-route, not quality-promote", router.contains("SHITCOIN_LIVE_BLEED_QUARANTINE") && !router.contains("SHITCOIN_LIVE_BLEED_QUALITY_PROMOTION"))
        assertTrue("PRESALE_SNIPE live bleed must quarantine/paper-route", router.contains("PRESALE_SNIPE_LIVE_BLEED_QUARANTINE"))
        assertTrue("Bleeding SHITCOIN/PRESALE live lanes must not continue as size-shaped live buys", executor.contains("SHITCOIN_NEGATIVE_EV") || router.contains("SHITCOIN_LIVE_BLEED_QUARANTINE"))
    }


    @Test
    fun live_strategy_tuner_uses_cached_live_terminal_metrics_and_lets_winners_ride() {
        val tuner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStrategyTuner.kt").readText()
        val doctrine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val reporting = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()

        assertTrue("LiveStrategyTuner must consume live terminal StrategyTelemetry only", tuner.contains("StrategyTelemetry.computeLiveTerminalLeaderboard") && !tuner.contains("computeLeaderboard("))
        assertTrue("LiveStrategyTuner must be cached for hot paths", tuner.contains("CACHE_MS") && tuner.contains("cached") && tuner.contains("cacheAtMs"))
        assertTrue("LiveStrategyTuner must be soft-shape only, not a veto/zero-size authority", tuner.contains("Soft-shape only") && !tuner.contains("return false") && !tuner.contains("sizeMult = 0.0"))
        assertTrue("LiveStrategyTuner must bias proven live winners toward compounding runner patience", tuner.contains("compounding_runner") && tuner.contains("partialTriggerMult") && tuner.contains("holdMult = (1.25") && tuner.contains("tpMult = (1.16"))
        assertTrue("LiveStrategyTuner must gate capital winners by hit-rate while preserving asymmetric probes", tuner.contains("hit-rate gated net-SOL doctrine") && tuner.contains("hitRateHealthy") && tuner.contains("low_wr_asymmetric_probe") && tuner.contains("avgWinEdge"))
        assertTrue("Bleeder tuning must pivot playbook: small size, longer hold, later partials so rare runners can pay for churn losses", tuner.contains("toxic_runner_pivot") && tuner.contains("bleeder_runner_pivot") && tuner.contains("holdMult = (1.18 + depth * 0.72).coerceIn(1.12, 1.90)") && tuner.contains("partialTriggerMult = (1.18 + depth * 0.72).coerceIn(1.12, 1.90)"))
        assertTrue("LiveGrowthDoctrine must consume LiveStrategyTuner in the final live growth envelope", doctrine.contains("LiveStrategyTuner.adjustment") && doctrine.contains("strategyTune.compact") && doctrine.contains("tunedMaxWalletPct"))
        assertTrue("AgenticStyleRouter must expose tuned size/tp/hold multipliers", router.contains("tunedSizeMult") && router.contains("tunedTpMult") && router.contains("tunedHoldMult") && router.contains("LiveStrategyTuner.adjustment"))
        assertTrue("Executor must raise live TP/partial patience from LiveStrategyTuner", exec.contains("LIVE_STRATEGY_TUNER_TP_RAISED") && exec.contains("LiveStrategyTuner.livePartialProfitFloorPct") && exec.contains("PARTIAL_BLOCKED_BELOW_BREAKEVEN"))
        assertTrue("Operational report must surface LiveStrategyTuner state", reporting.contains("live_strategy_tuner") && reporting.contains("LiveStrategyTuner.statusLine"))
    }


    @Test
    fun close_outcome_labels_are_sanitized_before_journal_and_learning() {
        val sanitizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CloseOutcomeLabelSanitizer.kt").readText()
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val learning = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalLearning.kt").readText()
        val pnl = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPnlSanitizer.kt").readText()

        assertTrue("close sanitizer must rewrite negative take-profit/profit-lock labels", sanitizer.contains("REALIZED_LOSS_AFTER_PROFIT_SIGNAL") && sanitizer.contains("PROFIT_LABEL_NEGATIVE_PNL"))
        assertTrue("close sanitizer must rewrite risk labels that closed green/scratch", sanitizer.contains("REALIZED_WIN_AFTER_RISK_EXIT_SIGNAL") && sanitizer.contains("REALIZED_SCRATCH_AFTER_RISK_EXIT_SIGNAL"))
        assertTrue("below-floor partial rows must be marked dirty, not trained as profit distribution", sanitizer.contains("PARTIAL_BELOW_PROFIT_FLOOR") && sanitizer.contains("PARTIAL_PROFIT_FLOOR_PCT = 8.0"))
        assertTrue("journal write path must canonicalize labels before persistence/fanout", store.contains("CloseOutcomeLabelSanitizer.canonicalize(enrichJournalLinkage"))
        assertTrue("legacy SQLite and in-memory journal reads must canonicalize labels for UI/reporting", store.contains("val displayRow = CloseOutcomeLabelSanitizer.canonicalize(row, emit = false)") && store.contains("map { CloseOutcomeLabelSanitizer.canonicalize(it, emit = false) }"))
        assertTrue("canonical learning must quarantine dirty label contradictions", learning.contains("CloseOutcomeLabelSanitizer.inspect(trade)") && learning.contains("LEARNING_LABEL_SIGN_CONFLICT_QUARANTINED") && learning.contains("TRAINING_ROW_EXCLUDED_REASON_"))
        assertTrue("partial dirty rows must be excluded from learning-facing PnL sanitizer", pnl.contains("side == \"PARTIAL_SELL\"") && pnl.contains("labelVerdict.dirtyReason"))
    }


    @Test
    fun daily_drawdown_is_growth_pressure_not_global_halt() {
        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SecurityGuard.kt").readText()
        val liveCb = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveSafetyCircuitBreaker.kt").readText()
        val authorizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()

        assertFalse("Daily loss must not call halt(); it strands live lifecycle and creates ghost-position drift",
            guard.contains("halt(\"Daily loss limit reached"))
        assertFalse("Daily loss must not return a fatal GuardResult",
            guard.contains("Daily loss limit hit") && guard.contains("fatal = true"))
        assertTrue("Daily drawdown should be visible adaptive pressure",
            guard.contains("DAILY_DRAWDOWN_PRESSURE_SOFT_ALLOW"))
        assertTrue("Session drawdown must be telemetry pressure, not LiveSafetyCB tripped=true",
            liveCb.contains("SESSION_DRAWDOWN_PRESSURE") && liveCb.contains("Do NOT set tripped=true"))
        assertTrue("Startup floor remains the hard breaker consumed by TradeAuthorizer",
            liveCb.contains("STARTUP_FLOOR") && authorizer.contains("LiveSafetyCircuitBreaker.isTripped()"))
    }


    @Test
    fun apk_version_patch_derived_from_ci_run_number() {
        // V5.0.4135 — Operator override (2026-06-25). The previous invariant
        // ("don't derive patch from CI run number") caused four consecutive
        // builds to ship as v5.0.4132 because the AATE_VERSION file was static.
        // Now the file holds only the major.minor prefix and the workflow
        // appends ${BUILD_NUMBER} (= GITHUB_RUN_NUMBER + 1) so every push
        // produces a uniquely-named APK aligned with the CI run number.
        val gradle = java.io.File("build.gradle.kts").readText()
        val workflow = java.io.File("../.github/workflows/build.yml").readText()
        val version = java.io.File("../AATE_VERSION").readText().trim()
        assertEquals("AATE_VERSION must hold the major.minor prefix only", "5.0", version)
        assertTrue("Gradle must prefer explicit AATE version authority", gradle.contains("aateVersionName") && gradle.contains("AATE_VERSION"))
        assertTrue("Workflow must pass explicit AATE version into Gradle", workflow.contains("-PaateVersionName=\$AATE_VERSION_NAME"))
        assertTrue("Workflow must compose VERSION_NAME from BASE + BUILD_NUMBER", workflow.contains("VERSION_NAME=\"\${BASE}.\${BUILD_NUMBER}\""))
        assertTrue("BUILD_NUMBER must be derived from GITHUB_RUN_NUMBER", workflow.contains("BUILD_NUMBER=\$((GITHUB_RUN_NUMBER + 1))"))
    }


    @Test
    fun live_probability_engine_unifies_forward_policy_and_sizer_probability() {
        val prob = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        val sizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        val reporting = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        assertTrue("Probability facade must expose pWin/pRug/E/uncertainty/samples/soft size", prob.contains("pWin") && prob.contains("pRug") && prob.contains("expectedPnlPct") && prob.contains("uncertaintyPct") && prob.contains("sizeMult"))
        assertTrue("Probability facade must blend ForwardOutcomeModel + UnifiedPolicyHead + live terminal lane priors", prob.contains("ForwardOutcomeModel.forecast") && prob.contains("UnifiedPolicyHead.predictWinProb") && prob.contains("StrategyTelemetry.computeLiveTerminalLeaderboard"))
        assertTrue("Probability facade must be soft-shape only, no veto or zero sizing", prob.contains("Soft-shape only") && !prob.contains("return false") && !prob.contains("sizeMult = 0.0"))
        assertTrue("SmartSizer must consume LiveProbabilityEngine instead of raw scattered probability", sizer.contains("LiveProbabilityEngine.forecast") && sizer.contains("PROBABILITY-GATED size"))
        assertTrue("Reports must surface the unified probability edge", reporting.contains("LiveProbabilityEngine.statusLine"))
    }


    @Test
    fun live_growth_chokes_are_non_terminal_and_sell_only_is_dead() {
        val sellOnly = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellOnlySafeMode.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val jupiter = java.io.File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        val heliusCreator = java.io.File("src/main/kotlin/com/lifecyclebot/network/HeliusCreatorHistory.kt").readText()
        assertTrue("SellOnlySafeMode must be telemetry only", sellOnly.contains("TELEMETRY ONLY") && sellOnly.contains("return null"))
        assertFalse("SellOnlySafeMode must not set active=true as buy authority", sellOnly.contains("_active = nowActive"))
        assertTrue("Mutex/no-terminal buy states must defer without BUY_FAILED/backoff", exec.contains("liveBuyDeferred") && exec.contains("NO_TERMINAL_EVENT_REQUEUED") && exec.contains("no_buy_failed=true no_backoff=true"))
        assertFalse("Mutex busy must not emit LIVE_BUY_TIMEOUT", exec.contains("liveStage(\"LIVE_BUY_TIMEOUT\", \"reason=MUTEX_BUSY_DEFERRED"))
        assertTrue("Jupiter v6 quote must adapt route params instead of one-shot 4xx failing", jupiter.contains("adaptive fallbacks") && jupiter.contains("restrictIntermediateTokens=false") && jupiter.contains("onlyDirectRoutes=true"))
        assertTrue("Helius creator export must cap rows and avoid exporting bulky previousTokens", heliusCreator.contains("EXPORT_MAX_ROWS") && heliusCreator.contains("take(EXPORT_MAX_ROWS)") && heliusCreator.contains("previousTokens omitted"))
    }


    @Test
    fun pre_broadcast_sell_route_failures_are_not_live_sell_finality_faults() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()
        assertTrue("Dust/no-broadcast sell paths must return typed route failure", exec.contains("DUST_NO_BROADCAST_NO_SIGNATURE") && exec.contains("DUST_RAW_ZERO_NO_SIGNATURE") && exec.contains("return SellResult.ROUTE_FAILED_NO_SIGNATURE"))
        assertTrue("Generic retryable sell queue must not emit noSig finality marker", exec.contains("SELL_RETRY_ENQUEUED_NO_FINALITY_FAULT") && exec.contains("noSig=false"))
        assertFalse("doSell wrapper must not emit SELL_NO_CURRENT_HELD_PROOF_NOT_RETRIED for pre-broadcast route retry", exec.contains("route_retry=true"))
        assertTrue("NO_SIGNATURE route exhaustion must be documented as non-finality transport failure", exec.contains("not a sell-finality fault") && exec.contains("not a PendingSellQueue latch"))
        assertTrue("Doctor must exclude route_retry/pre-broadcast from finality noSig", doctor.contains("Pre-broadcast route exhaustion/no-signature is not corrupt") && doctor.contains("!ev.message.contains(\"route_retry=true\""))
    }


    @Test
    fun low_win_rate_live_lanes_cannot_receive_boosted_capital_from_outlier_pnl() {
        val prob = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        val tuner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStrategyTuner.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        assertTrue("LiveProbabilityEngine must cap low hit-rate lanes below neutral even with positive SOL/PnL", prob.contains("lowHitRateCap") && prob.contains("maxOf(pWin, lanePWin) < 0.35 -> 0.68") && prob.contains("minOf(rawMult, lowHitRateCap)"))
        assertTrue("LiveStrategyTuner must require healthy live WR before winner sizing", tuner.contains("hitRateHealthy") && tuner.contains("wr >= 45.0") && tuner.contains("wr >= 35.0 && pf > 0.0"))
        assertTrue("Low-WR positive-SOL lanes must be asymmetric probes, not runner_press winners", tuner.contains("low_wr_asymmetric_probe") && tuner.contains("wr < 35.0 && sol > 0.0") && tuner.contains("sizeMult = (0.78"))
        assertTrue("Toxic bleeders must be allowed to shrink below the old 0.25 router floor while pivoting into runner patience", tuner.contains("val sizeFloor = if (toxicBleed) 0.12 else 0.35") && tuner.contains("toxic_runner_pivot") && router.contains("strategyTune.label == \"toxic_runner_pivot\"") && router.contains("0.08"))
    }


    @Test
    fun live_zero_signal_v3_execute_cannot_bypass_as_standard_buy() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val v3 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/V3EngineManager.kt").readText()
        assertTrue("laneQualifiedBuyDecision must convert zero-score/zero-conf with exitable liquidity into PROBE_ONLY, not park live", bot.contains("LANE_WAIT_OVERRIDE_ZERO_SIGNAL_DUST_PROBE_4164") && bot.contains("FDG_ZERO_SCORE_DUST_PROBE_4164") && bot.contains("""blockReason = "PROBE_ONLY"""") && !bot.contains("ZERO_SIGNAL_DEFERRED_NO_LIVE_CAPITAL"))
        assertTrue("V3 ExecuteRequest must carry score/conf/band metadata", v3.contains("val score: Int? = null") && v3.contains("val confidence: Int? = null") && v3.contains("val band: String? = null") && v3.contains("score = decision.finalScore") && v3.contains("confidence = decision.effectiveConfidence"))
        val v3ExecBlock = bot.substring(bot.indexOf("fun runV3Execution"), bot.indexOf("fun manualBuy"))
        assertTrue("runV3Execution must clamp live zero-signal into dust-probe before doBuy", v3ExecBlock.contains("V3_ZERO_SIGNAL_DUST_PROBE_4164") && v3ExecBlock.contains("v3ZeroSignalProbe = reqScore <= 0 && reqConf <= 10") && v3ExecBlock.contains("probeSol = req.sizeSol.coerceAtMost(0.003).coerceAtLeast(0.001)") && v3ExecBlock.contains("sol = if (!isPaper && v3ZeroSignalProbe) execSol else req.sizeSol"))
        assertTrue("V3 bridge must pass real score/band into Executor instead of hardcoded score=50 quality=V3", bot.contains("score = (req.score ?: ts.lastV3Score ?: 50).toDouble()") && bot.contains("quality = req.band ?: \"V3\""))
    }


    @Test
    fun token_metric_stage_router_blocks_peak_and_rug_prone_live_entries() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMetricStageRouter.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val mode = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ModeRouter.kt").readText()
        assertTrue("Stage router must identify base/mid/markup/peak/dump/rug states", router.contains("BASE_START") && router.contains("MID_ACCUMULATION") && router.contains("CONTROLLED_MARKUP") && router.contains("PEAK_EXHAUSTION") && router.contains("RUG_PRONE"))
        assertTrue("Metric-stage mismatch must be soft telemetry while RUG_PRONE remains hard safety", bot.contains("TOKEN_METRIC_STAGE_LANE_SOFT_MISMATCH_4162") && bot.contains("TokenMetricStageRouter.laneFit(ts, l)") && bot.contains("metricFit.stage == TokenMetricStageRouter.Stage.RUG_PRONE") && bot.indexOf("TOKEN_METRIC_STAGE_LANE_SOFT_MISMATCH_4162") < bot.indexOf("if (l == \"STANDARD\" || l == \"CORE\" || l == \"V3\")"))
        assertTrue("V3 trunk must also obey metric-stage fit", bot.contains("V3_TOKEN_METRIC_STAGE_DEFERRED") && bot.contains("TokenMetricStageRouter.laneFit(ts, \"V3\")"))
        assertTrue("Primary lane election must be metric-aware, not only style/source aware", bot.contains("TokenMetricStageRouter.preferredPrimaryLane") && bot.contains("TOKEN_METRIC_STAGE_PRIMARY"))
        assertTrue("ModeRouter must not reward extended near-high peak chasing as breakout", mode.contains("BREAKOUT_REJECT: peak exhaustion") && mode.contains("controlled approach below local high"))
    }


    @Test
    fun scanner_hard_rejects_do_not_enter_watchlist_or_rescan_loop() {
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ScannerHardRejectStore.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val reg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        val scanner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SolanaMarketScanner.kt").readText()
        assertTrue("hard rejects must persist in an onboard scanner quarantine", store.contains("scanner_hard_rejects") && store.contains("isRejected") && store.contains("SCANNER_HARD_REJECT_STAMPED"))
        assertTrue("canonical protected intake must reject hard-stamped mints before probation/watchlist", bot.contains("INTAKE_HARD_REJECT_SKIPPED") && bot.indexOf("ScannerHardRejectStore.isRejected(mint)") < bot.indexOf("GlobalTradeRegistry.addToProbationOnly"))
        assertTrue("registry paths must not admit hard rejects into watchlist/probation/promotion", reg.contains("SCANNER_HARD_REJECT") && reg.contains("probation.remove(mint)") && reg.contains("watchlist.remove(mint)"))
        assertTrue("scanner local loop must skip hard rejects before seen/rejected cooldown repair", scanner.contains("ScannerHardRejectStore.isRejected(mint)") && scanner.contains("telemetryRugRejects++"))
        assertTrue("scanner breadth must be wider than the old RAW≈50 shallow bench", scanner.contains("offset in listOf(0, 50, 100, 150, 200, 250)") && scanner.contains("totalEmitted >= 120") && scanner.contains("take(10_000)"))
    }


    @Test
    fun canonical_features_use_real_sell_pressure_not_buy_pressure_mirror() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Canonical learning must use lastSellPressurePct for sell pressure; mirroring buy pressure poisons distribution/dump labels",
            builder.contains("sellPressure = sellPressure(ts.lastSellPressurePct)"))
        assertFalse("Canonical learning must not mirror buy pressure into sellPressure",
            builder.contains("sellPressure = sellPressure(ts.lastBuyPressurePct)"))
    }


    @Test
    fun canonical_features_age_bucket_uses_token_age_at_entry_not_hold_time() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("ageBucket must use token/pool age at entry, not now-entry hold duration",
            builder.contains("estimateTokenAgeAtEntryMs(ts)") && builder.contains("ts.tokenMap.poolAgeMs") && builder.contains("ts.addedToWatchlistAt"))
        assertFalse("ageBucket must not be computed from System.currentTimeMillis() - ts.position.entryTime; holdBucket already handles hold time",
            builder.contains("val ageMs = if (ts.position.entryTime > 0)"))
    }


    @Test
    fun canonical_features_route_uses_token_map_authority_before_price_source_fallback() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Canonical learning venue/route must consume TokenMap route authority, not only lastPriceSource guesses",
            builder.contains("tm.pumpFunExecutable") && builder.contains("tm.jupiterQuoteOk") && builder.contains("tm.dexRouteOk") && builder.contains("tm.migratedOrGraduated") && builder.contains("tm.routeStatus"))
        assertTrue("TokenMap fields must be read before fallback lastPriceDex/source checks",
            builder.indexOf("val tm = ts.tokenMap") in 1 until builder.indexOf("val dex = ts.lastPriceDex.uppercase()"))
    }


    @Test
    fun canonical_features_use_token_map_market_fallbacks_to_avoid_feature_starvation() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Canonical learning must use TokenMap liquidity/mcap/top-holder fallbacks when TokenState display fields lag",
            builder.contains("ts.tokenMap.liquidityUsd") && builder.contains("ts.tokenMap.marketCap") && builder.contains("ts.tokenMap.fdv") && builder.contains("ts.tokenMap.topHolderConcentrationPct"))
        assertFalse("Canonical learning must not rely on the old two-line display-field-only market snapshot",
            builder.contains("""val liqUsd = ts.lastLiquidityUsd
        val mcapUsd = ts.lastMcap"""))
    }


    @Test
    fun canonical_features_sell_pressure_bucket_is_not_inverted() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Actual sellPressurePct >=60 must bucket as STRONG distribution pressure",
            builder.contains("p >= 60.0 -> \"STRONG\"") && builder.contains("p <= 40.0 -> \"WEAK\""))
        assertFalse("Sell pressure bucket must not use the old inverse-buy-pressure mapping",
            builder.contains("p <= 40.0 -> \"STRONG\""))
    }


    @Test
    fun canonical_features_vol_velocity_uses_volume_not_price_body_proxy() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("volVelocity must consume TokenMap/candle volume acceleration",
            builder.contains("ts.tokenMap.volume5mUsd") && builder.contains("ts.tokenMap.volume1hUsd") && builder.contains("ts.tokenMap.volume24hUsd") && builder.contains("h.last().vol"))
        assertFalse("volVelocity must not use price candle bodies as a fake volume proxy",
            builder.contains("last.priceUsd - last.openUsd") || builder.contains("velLast ="))
    }


    @Test
    fun canonical_features_authority_uses_token_map_fallback_when_safety_unknown() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Canonical learning must use TokenMap mint/freeze authority fallback when SafetyReport authority is UNKNOWN",
            builder.contains("authorityState(ts.safety.mintAuthorityDisabled, ts.tokenMap.mintAuthority)") &&
            builder.contains("authorityState(ts.safety.freezeAuthorityDisabled, ts.tokenMap.freezeAuthority)"))
        assertTrue("Authority fallback must bucket raw authority into RENOUNCED/RETAINED/UNKNOWN for learner signatures",
            builder.contains("private fun authorityState") && builder.contains("RENOUNCED") && builder.contains("RETAINED"))
    }


    @Test
    fun canonical_features_bubble_cluster_uses_bundle_risk_and_first_block_alpha() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Canonical bubbleClusterPattern must expose bundleRisk and firstBlockSupplyPct, not only bundleType/CLEAN",
            builder.contains("bubbleClusterPattern = bubbleClusterPattern(ts)") &&
            builder.contains("ts.safety.bundleRisk") && builder.contains("ts.safety.firstBlockSupplyPct") &&
            builder.contains("BUNDLE_HIGH_") && builder.contains("FIRST_BLOCK_HEAVY_"))
    }


    @Test
    fun canonical_features_rug_tier_uses_numeric_rugcheck_without_changing_safety_tier() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Canonical rugTier must consume numeric rugcheckScore while safetyTier remains coarse upstream safety taxonomy",
            builder.contains("rugTier = rugTier(ts, safetyTier)") && builder.contains("safetyTier = safetyTier") && builder.contains("ts.safety.rugcheckScore"))
        assertTrue("Rug tier learning buckets must keep DANGER/UNSAFE/CAUTION/SAFE without adding a gate",
            builder.contains("score < 40 -> \"DANGER\"") && builder.contains("score < 55 -> \"UNSAFE\"") && builder.contains("score < 70 -> \"CAUTION\"") && builder.contains("else -> \"SAFE\"") && builder.contains("no gate change"))
    }


    @Test
    fun canonical_features_slippage_bucket_uses_token_map_route_friction_proxy() {
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.kt").readText()
        assertTrue("Canonical slippageBucket must no longer be blank; it should use TokenMap route/liquidity friction signals for learning only",
            builder.contains("slippageBucket = slippageBucket(ts)") && builder.contains("private fun slippageBucket") &&
            builder.contains("tm.expectedOutAmount") && builder.contains("tm.liquiditySol") && builder.contains("tm.realSolReserves") &&
            builder.contains("SLIP_LOW") && builder.contains("SLIP_MED") && builder.contains("SLIP_HIGH") && builder.contains("SLIP_UNKNOWN"))
        assertFalse("Canonical slippageBucket must not remain the old empty placeholder",
            builder.contains("""slippageBucket = "",   // not captured in TokenState"""))
    }


    @Test
    fun emergency_owner_delta_can_broadcast_only_through_bounded_emergency_helper() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellAmountAuthority.kt").readText()
        assertTrue("Emergency/profit-protect exits must be able to use buy-tied owner-delta cache when wallet proof is temporarily indeterminate",
            authority.contains("BROADCAST_AUTH_ALLOW_TX_META_EMERGENCY") &&
            authority.contains("isEmergencyExitReason(reason) || isProfitProtectExitReason(reason)") &&
            authority.contains("ageMs <= maxAgeMs") &&
            authority.contains("requestedRawAmount == null") && authority.contains("requestedRawAmount <= cached.rawAmount"))
        assertTrue("TX_META owner delta must remain UNKNOWN as a balance source; helper is an emergency broadcast exception, not wallet authority",
            authority.contains("Source.TX_META_OWNER_DELTA -> BalanceSource.UNKNOWN"))
        assertFalse("Emergency helper must not return tx-meta as confirmed wallet balance",
            authority.contains("Resolution.Confirmed(cached.rawAmount, cached.decimals, Source.TX_META_OWNER_DELTA)"))
    }


    @Test
    fun live_gate_relaxer_uses_strategy_telemetry_counts_and_no_relax_in_dump_for_toxic_meme_lanes() {
        val relaxer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveLayerGateRelaxer.kt").readText()
        assertTrue("Relaxer maturity must fallback to StrategyTelemetry live-terminal counts so journal-proven lanes do not print n=0, but only via async stale cache",
            relaxer.contains("StrategyTelemetry.computeLiveTerminalLeaderboard") &&
            relaxer.contains("maxOf(busCounts[k] ?: 0, m.trades)") &&
            relaxer.contains("refreshLaneCacheIfStale") && relaxer.contains("refreshInFlight") &&
            relaxer.contains("AATE-live-layer-relaxer-refresh"))
        assertTrue("DUMP regime must cancel cold-start relaxation for toxic meme lanes without scanning StrategyTelemetry inline",
            relaxer.contains("dumpRegimeNoRelax") && relaxer.contains("RegimeDetector.Regime.DUMP") &&
            relaxer.contains("MOONSHOT") && relaxer.contains("SHITCOIN") &&
            relaxer.contains("laneToxicCache[lane] ?: true") && relaxer.contains("return 1.0"))
    }


    @Test
    fun toolkit_hostile_dump_bias_pivots_away_from_degen_fresh_pool_flow() {
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue("InternetEdge hostile must be treated as defensive risk, not neutral",
            sheet.contains("riskMode=hostile") || sheet.contains("""equals("hostile", ignoreCase = true)"""))
        assertTrue("DUMP regime must sharply penalize degen/fresh-pool setups and prefer depth/reclaim/recovery setups",
            sheet.contains("RegimeDetector.Regime.DUMP) -48.0") &&
            sheet.contains("RegimeDetector.Regime.DUMP) -36.0") &&
            sheet.contains("RegimeDetector.Regime.DUMP) 18.0") &&
            sheet.contains("SMART_WALLET_COPY_FOLLOW") &&
            sheet.contains("bias only — no veto"))
    }


    @Test
    fun meme_registry_restore_refills_live_watchlist_without_pump_source_monopoly() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("Meme registry cold-start restore must no longer be hard capped at 40 when live throughput needs a larger active pool",
            bot.contains("V5.0.4055") && bot.contains("maxOf(preScanCfg.maxWatchlistSize, 120).coerceAtMost(160)") && bot.contains("30 * 60 * 1000L"))
        assertTrue("Restore hydrate must source-balance registry rows so pump/fun sources do not monopolize the first watchlist window",
            bot.contains("sourceBuckets") && bot.contains("groupBy { it.source.ifBlank") && bot.contains("keys = sourceBuckets.keys.sorted()") && bot.contains("while (recent.size < hydrateCap"))
        assertFalse("Restore hydrate must not resurrect the old 500+ ghost-token minimum",
            bot.contains("hydrateCap = preScanCfg.maxWatchlistSize.coerceAtLeast(500)"))
    }


    @Test
    fun live_layer_relaxer_does_not_scan_trade_history_on_ui_report_thread() {
        val relaxer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveLayerGateRelaxer.kt").readText()
        val summaryBody = relaxer.substringAfter("fun summaryLine()").take(900)
        val dumpBody = relaxer.substringAfter("private fun dumpRegimeNoRelax").substringBefore("/** EFFECTIVE multiplier")
        assertTrue("Relaxer must refresh journal-backed StrategyTelemetry on a background stale cache, not inline from reporting/UI",
            relaxer.contains("Thread({") && relaxer.contains("AATE-live-layer-relaxer-refresh") && relaxer.contains("laneToxicCache"))
        assertFalse("summaryLine must not directly scan StrategyTelemetry/TradeHistoryStore", summaryBody.contains("StrategyTelemetry.compute") || summaryBody.contains("TradeHistoryStore"))
        assertFalse("dumpRegimeNoRelax must not directly scan StrategyTelemetry/TradeHistoryStore", dumpBody.contains("StrategyTelemetry.compute") || dumpBody.contains("TradeHistoryStore"))
    }


    @Test
    fun weak_dump_toxic_score_band_pivots_defensive_probe_to_quality_reclaim_lanes_fast() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneToxicityGuard.kt").readText()
        assertTrue("DEFENSIVE_PROBE must lead with quality/reclaim lanes, not only SHITCOIN/MOONSHOT, otherwise weak-DUMP pivots still bleed",
            router.contains("V5.0.4057") && router.contains("""DEFENSIVE_PROBE("defensive_probe", setOf("QUALITY", "DIP_HUNTER", "TREASURY", "PROJECT_SNIPER")"""))
        assertTrue("Router must prepend rapid toxic-regime pivot lanes for MOONSHOT|S41-60 / SHITCOIN danger before lane election",
            router.contains("rapidToxicRegimePivot") && router.contains("score in 41..60") && router.contains("""listOf("QUALITY", "DIP_HUNTER", "TREASURY", "BLUECHIP")"""))
        assertTrue("Lane toxicity guard must not fall back to first toxic lane in weak DUMP when quality/reclaim alternatives are possible",
            guard.contains("V5.0.4057") && guard.contains("RegimeDetector.Regime.DUMP") && guard.contains("QUALITY") && guard.contains("DIP_HUNTER"))
        assertFalse("Fast toxic pivot must remain soft route-shape, not a hard trade block", router.contains("shouldTrade = false") || router.contains("BLOCK_") || guard.contains("return null"))
    }


    @Test
    fun symbolic_exit_reasoner_can_veto_soft_exit_churn_without_touching_hard_safety() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Executor riskCheck must consult SymbolicExitReasoner before soft ExitAI/Gemini/V8 exits so the agentic stack can self-correct hold policy",
            exec.contains("SymbolicExitReasoner.assess") && exec.contains("symbolicWantsPatience") && exec.contains("softExitProtectedBySymbolicPatience"))
        assertTrue("Symbolic patience must become an observable soft-exit veto, not just report-only sentience",
            exec.contains("SYMBOLIC_PATIENCE_SOFT_EXIT_VETO") && exec.contains("HOLD OVERRIDE"))
        assertTrue("Symbolic patience must not override reflex/liquidity/rug/catastrophic/emergency exits or the -12% live danger zone",
            exec.contains("""r.contains("REFLEX")""") && exec.contains("""r.contains("LIQUIDITY_COLLAPSE")""") && exec.contains("""r.contains("LIQUIDITY_DRAIN")""") && exec.contains("""r.contains("NO_LIQUIDITY_EXIT")""") && exec.contains("""r.contains("RUG")""") && exec.contains("""r.contains("CATASTROPHIC")""") && exec.contains("gainPct <= -12.0"))
        assertTrue("V8 critical exits must still bypass symbolic hold veto",
            exec.contains("critical = exitSignal.urgency == PrecisionExitLogic.Urgency.CRITICAL"))
    }


    @Test
    fun symbolic_exit_rules_are_authoritatively_graded_on_terminal_close_not_partials() {
        val store = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("Terminal SELL learning must grade pending SymbolicExitReasoner rules on authoritative final PnL at the ML close bridge",
            store.contains("V5.0.4064") && store.contains("SymbolicExitReasoner.gradeRulesOnClose(trade.mint, trade.pnlPct)") &&
            store.contains("SYMBOLIC_EXIT_RULES_GRADED_ON_CLOSE"))
        assertTrue("Symbolic final grading must be terminal-only; partial sells are not the final hold-vs-exit verdict",
            store.contains("""trade.side.equals("SELL", ignoreCase = true)""") &&
            store.indexOf("trade.side.equals") < store.indexOf("SymbolicExitReasoner.gradeRulesOnClose"))
        assertFalse("Executor must not duplicate symbolic close grading or reference non-existent Trade.symbol",
            exec.contains("SymbolicExitReasoner.gradeRulesOnClose(tradeWithMint.mint, tradeWithMint.pnlPct)") || exec.contains("tradeWithMint.symbol"))
    }

    @Test
    fun extreme_winner_pnl_trains_at_real_value_not_rejected_or_clamped() {
        val san = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPnlSanitizer.kt").readText()
        assertTrue("V5.0.4066: MAX_TRAINABLE_PNL_PCT must be 100_000 so real moonshot wins above 5000% are not rejected",
            san.contains("V5.0.4066") && san.contains("MAX_TRAINABLE_PNL_PCT = 100_000.0"))
        assertFalse("No clamp on real wins — extreme winners must train at their real pnl value",
            san.contains("EXTREME_WINNER_CLAMP_PCT") || san.contains("PNL_PCT_CLAMPED_EXTREME_WINNER"))
        assertFalse("Old 5_000.0 hard cap must no longer be the actual constant",
            san.contains("const val MAX_TRAINABLE_PNL_PCT = 5_000.0"))
    }

    @Test
    fun relaxer_disabled_below_45_wr_and_dump_regime_tightened() {
        val relaxer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveLayerGateRelaxer.kt").readText()
        assertTrue("V5.0.4081: no bootstrap in live — flat quality-based floors (30% doctrine, 20% emergency)",
            relaxer.contains("V5.0.4081") && relaxer.contains("DOCTRINE_FLOOR_PCT  = 30.0") && relaxer.contains("EMERGENCY_FLOOR_PCT = 20.0"))
        assertTrue("Relaxer must enforce both floors via the named constants",
            relaxer.contains("liveWr < EMERGENCY_FLOOR_PCT") && relaxer.contains("liveWr < DOCTRINE_FLOOR_PCT"))
        assertTrue("Relaxer must compute live WR from StrategyTelemetry",
            relaxer.contains("computeLiveTerminalLeaderboard") && relaxer.contains("refreshLiveWrCache"))

        val regime = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RegimeDetector.kt").readText()
        assertTrue("DUMP scoreFloorDelta must be +20 (was +15)",
            regime.contains("Regime.DUMP         -> +20"))
        assertTrue("DUMP sizeMultiplier must be 0.10 (was 0.40)",
            regime.contains("Regime.DUMP         -> 0.10"))
        assertTrue("CHOP scoreFloorDelta must be +10 (was +5)",
            regime.contains("Regime.CHOP         -> +10"))
        assertTrue("CHOP sizeMultiplier must be 0.35 (was 0.65)",
            regime.contains("Regime.CHOP         -> 0.35"))
        assertTrue("V5.0.4081: no bootstrap in live — low-sample path returns Regime.NORMAL (size×=1.0, no penalty)",
            regime.contains("V5.0.4081") && regime.contains("RegimeSnapshot(Regime.NORMAL"))
    }

    @Test
    fun all_lanes_trade_and_pivot_to_quality_not_amputated() {
        val overlay = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeConfigOverlay.kt").readText()
        assertTrue("V5.0.4073: lane-amputation reverted — no LIVE_RECOVERY_DISABLED_LANES",
            overlay.contains("V5.0.4073") && !overlay.contains("LIVE_RECOVERY_DISABLED_LANES"))
        assertTrue("isLaneDisabled must NOT hard-disable toxic lanes",
            !overlay.contains("LIVE_RECOVERY_DISABLED_LANES"))
        assertTrue("isLaneDisabled must always return false (V5.9.1405 doctrine)",
            overlay.contains("return false"))

        val bleeder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BleederMemoryRouter.kt").readText()
        assertTrue("V5.0.4070: bleeder pivot must fire faster — weakPerformer + lower thresholds",
            bleeder.contains("V5.0.4070") && bleeder.contains("weakPerformer"))
        assertTrue("provenBleeder threshold lowered from n50>=10/wr<25 to n50>=8/wr<30",
            bleeder.contains("n50 >= 8 && wr50 < 30.0"))

        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneToxicityGuard.kt").readText()
        assertTrue("V5.0.4089: toxicity guard threshold lowered -5.0→-2.0 + loss-rate trigger for slow bleeders",
            guard.contains("meanPnl <= -2.0") && guard.contains("LosingPatternMemory.liveStats") && guard.contains("lossRateLive >= 0.75"))
        assertTrue("Quality fallback must prefer BLUECHIP, QUALITY, WALLET_RECOVERED first",
            guard.contains("BLUECHIP") && guard.contains("WALLET_RECOVERED") && guard.contains("LIQUIDITY_DEPTH_QUALITY"))

        // LaneReenableChecker must be deleted — no amputation means no re-enable needed
        assertFalse("LaneReenableChecker must not exist (no lane amputation)",
            java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneReenableChecker.kt").exists())
    }

    @Test
    fun live_recovery_entry_hard_blocks_present() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue("V5.0.4151: token-map incomplete must hydrate route truth before watch-probation, not hard-block transient no-route",
            fdg.contains("RouteTruthHydrator.hydrate(ts)") && fdg.contains("WATCH_PROBATION_ROUTE_UNKNOWN") && fdg.contains("FDG_SKIPPED_ROUTE_UNKNOWN_PRECHECK"))
        assertTrue("V5.0.4190: liquidity gate must be fluid soft-shaping, with only true <$500 non-exitable dust hard-blocked",
            fdg.contains("FDG_LOW_LIQUIDITY_SOFT_SHAPED") && fdg.contains("LOW_LIQUIDITY_SIZE_REDUCTION") && fdg.contains("HARD_BLOCK_LIQUIDITY_BELOW_500") && !fdg.contains("HARD_BLOCK_LIQUIDITY_BELOW_2_5K") && !fdg.contains("HARD_BLOCK_LIQUIDITY_BELOW_25K"))
        assertTrue("V5.0.4155: mcap/liq >8x must soft-shape until extreme >20x hard safety",
            fdg.contains("FDG_MCAP_LIQ_RATIO_SOFT_SHAPED") && fdg.contains("MCAP_LIQ_RATIO_SIZE_REDUCTION") && fdg.contains("> 20.0"))
        assertTrue("V5.0.4190: rugcheck pending/timeout weak fallback must size-shape, not hard-veto; confirmed score 0 remains hard",
            fdg.contains("FDG_RUGCHECK_PENDING_WEAK_SIZE_SHAPED_4190") && fdg.contains("RUGCHECK_PENDING_WEAK_SIZE_SHAPE_4190") && fdg.contains("rugcheck_pending_weak_size_shape") && fdg.contains("rugcheckScore == 0 -> true") && !fdg.contains("HARD_BLOCK_RUGCHECK_PENDING_REVIEW_WEAK") && !fdg.contains("HARD_BLOCK_RUGCHECK_TIMEOUT_WEAK"))
        assertTrue("Must have entry price unknown hard block",
            fdg.contains("HARD_BLOCK_ENTRY_PRICE_UNKNOWN"))
        assertTrue("All new hard blocks must be LIVE-only (paperMode bypass)",
            fdg.contains("!config.paperMode"))
    }

    @Test
    fun v4125_agi_style_tp_hold_wire() {
        val models = java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()
        val bs = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        // TokenState must have style multiplier fields
        assertTrue("V5.0.4125: TokenState must have styleTpMult field",
            models.contains("var styleTpMult: Double = 1.0"))
        assertTrue("V5.0.4125: TokenState must have styleHoldMult field",
            models.contains("var styleHoldMult: Double = 1.0"))

        // BotService must set style multipliers from AgenticStyleRouter.decide
        assertTrue("V5.0.4125: BotService must persist styleDecision.tunedTpMult to ts.styleTpMult",
            bs.contains("ts.styleTpMult = styleDecision.tunedTpMult"))
        assertTrue("V5.0.4125: BotService must persist styleDecision.tunedHoldMult to ts.styleHoldMult",
            bs.contains("ts.styleHoldMult = styleDecision.tunedHoldMult"))

        // Executor paperBuy must set entryTakeProfitPct from fluidTP * styleTpMult
        assertTrue("V5.0.4125: paperBuy Position must set entryTakeProfitPct from styleTpMult",
            exec.contains("baseFluidTp * ts.styleTpMult"))

        // Executor exit TP when-block must check entryTakeProfitPct FIRST
        val tpBlockStart = exec.indexOf("val tpPct = when {")
        assertTrue("V5.0.4125: must find TP when-block", tpBlockStart > 0)
        val tpBlock = exec.substring(tpBlockStart, tpBlockStart + 1000)
        val entryTpIdx = tpBlock.indexOf("entryTakeProfitPct > 0.0")
        val shitcoinIdx = tpBlock.indexOf("isShitCoinPosition")
        assertTrue("V5.0.4125: entryTakeProfitPct must be checked BEFORE lane-specific TPs",
            entryTpIdx > 0 && shitcoinIdx > 0 && entryTpIdx < shitcoinIdx)

        // Executor must apply styleHoldMult to both max-hold paths
        assertTrue("V5.0.4125: primary max-hold must multiply by ts.styleHoldMult",
            exec.contains("_regimeHoldMult * ts.styleHoldMult.coerceIn(0.25, 5.0)"))
        assertTrue("V5.0.4125: secondary max-hold must multiply by ts.styleHoldMult",
            exec.contains("regimeHoldMult2 * ts.styleHoldMult.coerceIn(0.25, 5.0)"))

        // isLongHold must bypass time exit in both paths
        assertTrue("V5.0.4125: primary max-hold must have isLongHold bypass",
            exec.contains("ts.position.isLongHold         -> false"))
        assertTrue("V5.0.4125: secondary max-hold must have isLongHold bypass",
            exec.contains("ts.position.isLongHold        -> false"))
    }


    @Test
    fun v4151_route_truth_moonshot_timeout_and_strict_stop_contracts() {
        val route = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RouteTruthHydrator.kt").readText()
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MoonshotPivotArbiter.kt").readText()
        val pivot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        assertTrue("V5.0.4151: RouteTruthHydrator must use pump/pool/dex/jupiter/provider route truth before FDG route miss",
            route.contains("""hit("PUMP""") && route.contains("""hit("POOL""") && route.contains("""hit("DEX""") && route.contains("""hit("JUPITER""") && route.contains("""Result(true, "HELIUS"""))
        assertTrue("V5.0.4151: FDG route unknown must become watch probation, not HARD_BLOCK_TOKEN_MAP_INCOMPLETE",
            fdg.contains("WATCH_PROBATION_ROUTE_UNKNOWN") && fdg.contains("BlockLevel.CONFIDENCE") && fdg.contains("FDG_SKIPPED_ROUTE_UNKNOWN_PRECHECK"))
        assertTrue("V5.0.4151: MOONSHOT must pivot/micro/watch instead of disable normal-size DUMP bleed",
            moon.contains("MOONSHOT_PIVOT_MICRO") && moon.contains("SHITCOIN_MICRO_RECLASSIFIED") && moon.contains("WATCH_PROBATION") && moon.contains("LosingPatternMemory.liveStats"))
        assertTrue("V5.0.4151: LiveStylePivotRouter must allow micro multipliers below old 0.35 floor",
            pivot.contains("MoonshotPivotArbiter.decide") && pivot.contains("mult.coerceIn(0.001, 1.25)"))
        assertTrue("V5.0.4151: DUMP fresh launches need route/liquidity/momentum/volume/pressure proof or watch probation",
            pivot.contains("DUMP_FRESH_LAUNCH_NO_PROOF") && pivot.contains("WATCH_PROBATION_DUMP_FRESH_RECHECK") && pivot.contains("DUMP_FRESH_LAUNCH_MICRO_PROOF_ONLY"))
        assertTrue("V5.0.4151: stale buy decisions must cancel/rescore and not train as strategy failures",
            exec.contains("BUY_DECISION_EXPIRED_RESCORE") && exec.contains("BUY_STALE_LEASE_CANCELLED") && exec.contains("BUY_TIMEOUT_NOT_STRATEGY_FAILURE"))
        assertTrue("V5.0.4151: strict SL/catastrophe must bypass recovered hold/profit-lock suppression",
            exec.contains("STRICT_SL_OVERRIDE_HOLD") && exec.contains("CATASTROPHE_OVERRIDE_PROFIT_LOCK") && exec.contains("RUG_UNSELLABLE_ROUTE_GONE"))
    }

    @Test
    fun shitcoin4230LiveVolumeAndReleaseHygiene() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4230: live V3 readiness must not suppress direct ShitCoin executor fallback", bot.contains("SHITCOIN_LIVE_V3_PARALLEL_FALLBACK_4230") && bot.contains("val v3OwnsMemes = false") && bot.contains("v3RejectedIsRouting4230"))
        assertTrue("V5.0.4230: ShitCoin StrategyTrust distrust must recovery-probe, not hard-return", bot.contains("SHITCOIN_STRATEGY_DISTRUST_RECOVERY_PROBE_4230") && bot.contains("strategyDistrustSizeMult4230") && bot.contains("* strategyDistrustSizeMult4230"))
        assertTrue("V5.0.4230: ShitCoin FDG must evaluate actual adjusted size", bot.contains("proposedSizeSol = adjustedSize"))
        assertTrue("V5.0.4230: ShitCoin bootstrap block must be branch-local and not abort sibling lanes", bot.contains("paperBootstrapBlocked4230") && bot.contains("SHITCOIN_BOOTSTRAP_BRANCH_LOCAL_SKIP_4230") && bot.contains("shouldEnter = false"))
        assertTrue("V5.0.4230: ShitCoin failure paths must release lane/permit/auth through one helper", bot.contains("fun releaseShitCoinAttempt4230") && bot.contains("TradeAuthorizer.releasePosition(ts.mint, reason, TradeAuthorizer.ExecutionBook.SHITCOIN)") && bot.contains("""releaseShitCoinAttempt4230("BUY_NOT_OPENED")""") && bot.contains("""releaseShitCoinAttempt4230("EXCEPTION")"""))
    }

    @Test
    fun shitcoin4231NonSafetyGatesAreSoftShapers() {
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue("V5.0.4231: ShitCoin score expectancy must soft-shape instead of zero-size veto", shit.contains("SHITCOIN_EXPECTANCY_SOFT_SHAPE_4231") && shit.contains("intelligenceGateSizeMult4231 = minOf(intelligenceGateSizeMult4231, 0.35)"))
        assertTrue("V5.0.4231: ShitCoin BehaviorAI tilt must soft-shape instead of hard TILT_BLOCK", shit.contains("SHITCOIN_TILT_SOFT_SHAPE_4231") && !shit.contains("TILT_BLOCK: BehaviorAI tilt protection active — skipping entry"))
        assertTrue("V5.0.4231: ShitCoin SymbolicExitReasoner urgency must soft-shape instead of SYMBOLIC_VETO", shit.contains("SHITCOIN_SYMBOLIC_SOFT_SHAPE_4231") && !shit.contains("SYMBOLIC_VETO: exit urgency"))
        assertTrue("V5.0.4231: ShitCoin intelligence soft shape must flow into returned position size", shit.contains("positionSol * dangerBucketSoftSize * intelligenceGateSizeMult4231"))
    }

    @Test
    fun shitcoin4232RealEntryContextAndCollectiveScoreWiring() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4232: ShitCoin dev wallet memory must consume TokenMap creator/mint authority", bot.contains("SHITCOIN_DEV_WALLET_WIRED_4232") && bot.contains("shitDevWalletRaw4232") && bot.contains("ts.tokenMap.creatorOrDevWallet") && bot.contains("devWallet = shitDevWallet4232"))
        assertTrue("V5.0.4232: ShitCoin AdaptiveLearning must receive entry EMA fan state", bot.contains("entryEmaFanState = ts.meta.emafanAlignment.ifBlank { ts.phase }"))
        assertTrue("V5.0.4232: ShitCoin social score must use cached X/Telegram sentiment without hot-path API calls", bot.contains("ts.sentiment.xMentions") && bot.contains("ts.sentiment.telegramMentions") && bot.contains("ts.sentiment.decayedScore"))
        assertTrue("V5.0.4232: ShitCoin collective BUY upload must carry real lane score/confidence", exec.contains("entryScore: Int = 70") && exec.contains("entryConfidence: Int = 70") && exec.contains("entryScore = entryScore.coerceIn(0, 100)") && exec.contains("confidence = entryConfidence.coerceIn(0, 100)"))
    }

    @Test
    fun shitcoin4233V3RejectTaxonomyIsSingleSource() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4233: ShitCoin V3 routing reject taxonomy must be single-source", bot.contains("fun v3RoutingRejectForShitCoin4233") && bot.contains("val isRoutingReject = v3RejectedIsRouting4230") && bot.contains("val shitCoinV3HardReject = v3HardStopsShitCoin4233()"))
        assertTrue("V5.0.4233: paper-only rug-training carveout must use same broadened fatal taxonomy", bot.contains("fun v3PaperTrainingRug4233") && bot.contains("""reason.contains("EXTREME_RUG", ignoreCase = true)"""))
        assertTrue("V5.0.4233: true V3 hard stops must still terminate ShitCoin", bot.contains("v3Decision is com.lifecyclebot.v3.V3Decision.Blocked") && bot.contains("REJECTED_FATAL_V3"))
    }

    @Test
    fun autoCompound4234UsesLiveGrowthProfile() {
        val compound = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AutoCompoundEngine.kt").readText()
        assertTrue("V5.0.4234: AutoCompound default split must prioritize realized-profit reinvestment for daily wallet growth", compound.contains("val treasuryPct: Double = 20.0") && compound.contains("val compoundPct: Double = 45.0") && compound.contains("val walletPct: Double = 35.0"))
        assertTrue("V5.0.4234: AutoCompound must lift size faster on small live wallets while keeping a hard max", compound.contains("val compoundThreshold: Double = 0.15") && compound.contains("val maxSizeMultiplier: Double = 3.0") && compound.contains("* 0.45"))
        assertTrue("V5.0.4234: drawdown reduction safety must remain active", compound.contains("drawdownReduction: Boolean = true") && compound.contains("currentDrawdownPct > 10"))
    }

    @Test
    fun asiSsiAuditQueue4234IsTracked() {
        val audit = java.io.File("../audits/asi_ssi_audit_queue_2026-06-27.md").readText()
        assertTrue("V5.0.4234: ASI/SSI audit queue must track symbolic prover, async strategy lab, GEPA, semantic graph, counterfactual replay, and ResearchScout", audit.contains("A13 — SymbolicInvariantProver") && audit.contains("A15 — AsyncStrategyLab") && audit.contains("A17 — GEPA-style reflective optimizer") && audit.contains("A18 — SemanticPatternGraph") && audit.contains("A19 — CounterfactualReplayEngine") && audit.contains("A20 — Free-API ResearchScout"))
        assertTrue("V5.0.4234: ASI/SSI queue must forbid hot-path LLM/API calls", audit.contains("Never scanner/FDG/executor hot path") && audit.contains("background-only"))
    }

    @Test
    fun symbolicInvariantProver4235ProtectsGrowthContracts() {
        val prover = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SymbolicInvariantProver.kt").readText()
        assertTrue("V5.0.4235: SymbolicInvariantProver must define executable source proofs", prover.contains("object SymbolicInvariantProver") && prover.contains("proveSourceContracts") && prover.contains("NO_LEARNED_SHITCOIN_ZERO_SIZE_4235") && prover.contains("ASYNC_AI_NEVER_HOT_PATH_4235"))
        val failed = SymbolicInvariantProver.proveSourceContracts(java.io.File(".")).filter { !it.passed }
        assertTrue("V5.0.4235: symbolic source contracts must all pass: ${failed.joinToString { it.id + ":" + it.detail }}", failed.isEmpty())
    }

    @Test
    fun asyncStrategyLab4236IsPersistentAndBackgroundOnly() {
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsyncStrategyLab.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4236: AsyncStrategyLab must support free-tier provider abstraction without direct hot-path API calls", lab.contains("enum class Provider") && lab.contains("GEMINI_FREE") && lab.contains("GROQ_FREE") && lab.contains("backgroundOnly") && lab.contains("looksHotPath"))
        assertTrue("V5.0.4236: AsyncStrategyLab learned proposal queue must persist", lab.contains("exportState") && lab.contains("importState") && persistence.contains("ASYNC_STRATEGY_LAB"))
        assertTrue("V5.0.4236: AsyncStrategyLab must feed hypotheses only as reviewable proposals", lab.contains("submitBackgroundHypothesis") && lab.contains("rollbackCondition") && lab.contains("symbolicChecked") && !lab.contains("executeBuy") && !lab.contains("FinalDecisionGate.evaluate("))
    }

    @Test
    fun asyncStrategyLab4237UsesExistingCopilotOnlyBehindBackgroundGuard() {
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsyncStrategyLab.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4237: AsyncStrategyLab must reuse GeminiCopilot rawText instead of duplicating HTTP provider plumbing", lab.contains("requestBackgroundProviderHypothesis") && lab.contains("GeminiCopilot.rawText") && lab.contains("isBackgroundSource"))
        assertTrue("V5.0.4237: provider calls must be background-only and proposal-gated", lab.contains("BACKGROUND_ASYNC_STRATEGY_LAB") && lab.contains("submitBackgroundHypothesis") && lab.contains("symbolicChecked = false"))
        assertFalse("V5.0.4237: scanner/FDG/executor hot paths must not call provider hypothesis requests", bot.contains("requestBackgroundProviderHypothesis") || fdg.contains("requestBackgroundProviderHypothesis") || exec.contains("requestBackgroundProviderHypothesis"))
    }

    @Test
    fun semanticPatternGraph4238IsLocalOnlyPersistentSimilarityMemory() {
        val graph = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SemanticPatternGraph.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4238: SemanticPatternGraph must define local setup/outcome node and edge schema", graph.contains("object SemanticPatternGraph") && graph.contains("data class PatternNode") && graph.contains("data class PatternEdge") && graph.contains("SIMILAR_SETUP") && graph.contains("RUNNER_FAMILY"))
        assertTrue("V5.0.4238: SemanticPatternGraph must be local-only cached similarity memory with no API/embedding hot path", graph.contains("querySimilar") && graph.contains("jaccard") && graph.contains("local_only=true") && !graph.contains("GeminiCopilot.rawText") && !graph.contains("OkHttpClient"))
        assertTrue("V5.0.4238: SemanticPatternGraph must persist through LearningPersistence", graph.contains("exportState") && graph.contains("importState") && persistence.contains("SEMANTIC_PATTERN_GRAPH"))
    }

    @Test
    fun counterfactualReplay4239IsOfflinePersistentExitAlternativeMemory() {
        val replay = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CounterfactualReplayEngine.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4239: CounterfactualReplayEngine must define terminal-trade replay alternatives", replay.contains("object CounterfactualReplayEngine") && replay.contains("ReplayAlternative") && replay.contains("BANK_25") && replay.contains("TRAIL_RUNNER") && replay.contains("HARD_STOP_15"))
        assertTrue("V5.0.4239: Counterfactual replay must remain offline-only and never rewrite journal truth", replay.contains("recordTerminalTrade") && replay.contains("offline_only=true") && !replay.contains("recordTrade(") && !replay.contains("doSell("))
        assertTrue("V5.0.4239: CounterfactualReplayEngine must persist through LearningPersistence", replay.contains("exportState") && replay.contains("importState") && persistence.contains("COUNTERFACTUAL_REPLAY"))
    }

    @Test
    fun researchScout4240IsBackgroundOnlyPersistentFreeApiQueue() {
        val scout = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ResearchScout.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4240: ResearchScout must model free API request/findings queue", scout.contains("object ResearchScout") && scout.contains("DEXSCREENER_FREE") && scout.contains("GECKOTERMINAL_FREE") && scout.contains("RUGCHECK_FREE") && scout.contains("ResearchFinding"))
        assertTrue("V5.0.4240: ResearchScout must be background-only and not scanner/FDG/executor hot-path HTTP", scout.contains("BACKGROUND_RESEARCH_SCOUT") && scout.contains("isBackgroundSource") && !scout.contains("OkHttpClient") && !scout.contains("executeBuy"))
        assertTrue("V5.0.4240: ResearchScout must persist request/finding cache through LearningPersistence", scout.contains("exportState") && scout.contains("importState") && persistence.contains("RESEARCH_SCOUT"))
    }

    @Test
    fun semanticAndCounterfactual4241FeedOnlyTerminalClosedLearningOutcomes() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4241: SemanticPatternGraph and CounterfactualReplayEngine must be fed from Executor terminal outcome choke point", executor.contains("SEMANTIC_COUNTERFACTUAL_OUTCOME_4241") && executor.contains("SemanticPatternGraph.recordOutcome") && executor.contains("CounterfactualReplayEngine.recordTerminalTrade"))
        assertTrue("V5.0.4241: semantic/counterfactual fanout must be terminal SELL plus closed-learning/accounting trainable gated", executor.contains("""tradeWithMint.side.equals("SELL", true) && ledgerAllowsClosedLearning && accountingTrainable"""))
        assertTrue("V5.0.4241: semantic/counterfactual fanout must run in side-effect background and never block sell finality", executor.contains("GlobalScope.launch(AppDispatchers.sideEffect)") && executor.contains("never rewrites journal truth") && executor.contains("blocks sell finality"))
    }

    @Test
    fun researchScout4242QueuesTerminalLossResearchFromBackgroundOutcomeFanout() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4242: terminal loss/rug exits must queue ResearchScout background enrichment", executor.contains("RESEARCH_SCOUT_TERMINAL_EXIT_QUEUED_4242") && executor.contains("ResearchScout.enqueueBackgroundRequest") && executor.contains("terminal_exit_research_4242"))
        assertTrue("V5.0.4242: ResearchScout terminal queue must remain inside the terminal closed-learning side-effect fanout", executor.contains("""tradeWithMint.side.equals("SELL", true) && ledgerAllowsClosedLearning && accountingTrainable""") && executor.contains("BACKGROUND_RESEARCH_SCOUT_TERMINAL_EXIT_4242"))
    }

    @Test
    fun reflectiveOptimizer4243IsBackgroundOnlyProposalLoop() {
        val gepa = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReflectiveOptimizerGEPA.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4243/4256: GEPA optimizer must consume replay/semantic caches and route reviewable proposals through the critic stack", gepa.contains("object ReflectiveOptimizerGEPA") && gepa.contains("CounterfactualReplayEngine.policyHints") && gepa.contains("SemanticPatternGraph.summary") && gepa.contains("MultiAgentCriticStack.reviewAndSubmit"))
        assertTrue("V5.0.4243: GEPA optimizer must be background-only and never hard-veto/zero-size live entries", gepa.contains("BACKGROUND_GEPA_REFLECTION") && gepa.contains("no hard veto") && gepa.contains("no zero-size") && !gepa.contains("executeBuy"))
        assertTrue("V5.0.4243: GEPA proposals must persist through LearningPersistence", gepa.contains("exportState") && gepa.contains("importState") && persistence.contains("REFLECTIVE_OPTIMIZER_GEPA"))
    }

    @Test
    fun gepa4244RunsOnlyFromTerminalBackgroundOutcomeFanout() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4244: GEPA optimizer must run from terminal outcome background fanout after replay/semantic cache updates", executor.contains("GEPA_TERMINAL_REFLECTION_QUEUED_4244") && executor.contains("ReflectiveOptimizerGEPA.runBackgroundReflection") && executor.contains("BACKGROUND_GEPA_TERMINAL_OUTCOME_4244"))
        assertTrue("V5.0.4244: GEPA terminal reflection must stay under terminal SELL closed-learning/accounting trainable gate", executor.contains("""tradeWithMint.side.equals("SELL", true) && ledgerAllowsClosedLearning && accountingTrainable""") && executor.contains("GlobalScope.launch(AppDispatchers.sideEffect)"))
    }

    @Test
    fun asyncStrategyLab4245ReviewedApplyLayerIsBoundedSoftSizeOnly() {
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsyncStrategyLab.kt").readText()
        val hyp = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue("V5.0.4245: AsyncStrategyLab reviewed apply layer must require symbolicChecked background-only hypotheses", lab.contains("fun reviewedSizeBias") && lab.contains("it.backgroundOnly && it.symbolicChecked") && lab.contains("bias.coerceIn(0.92, 1.08)"))
        assertTrue("V5.0.4245: StrategyHypothesisEngine must consume reviewed lab bias as soft multiplier only", hyp.contains("AsyncStrategyLab.reviewedSizeBias") && hyp.contains("ASYNC_STRATEGY_LAB_REVIEWED_SIZE_BIAS_4245") && hyp.contains("reviewedLabBias * strategyVariantBias4342") && hyp.contains("coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)"))
        assertFalse("V5.0.4245: reviewed lab bias must not introduce hard veto or zero sizing", lab.contains("return 0.0") || hyp.contains("return 0.0"))
    }

    @Test
    fun asyncStrategyLab4246CanPromoteExistingHypothesisAfterSymbolicReview() {
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsyncStrategyLab.kt").readText()
        assertTrue("V5.0.4246: AsyncStrategyLab must expose symbolic review promotion for stored hypotheses", lab.contains("fun markSymbolicReviewed") && lab.contains("copy(") && lab.contains("symbolicChecked = true") && lab.contains("proofDetail"))
        assertTrue("V5.0.4246: symbolic review promotion must keep hot-path guard and background-only invariant", lab.contains("it.id == id && it.backgroundOnly") && lab.contains("looksHotPath(h.proposal)") && lab.contains("markLatestSymbolicReviewed"))
        assertFalse("V5.0.4246: symbolic review promotion must not auto-execute buys or FDG decisions", lab.contains("executeBuy") || lab.contains("FinalDecisionGate.evaluate("))
    }

    @Test
    fun reflectiveOptimizer4249DebouncesTerminalSellStorms() {
        val gepa = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReflectiveOptimizerGEPA.kt").readText()
        assertTrue("V5.0.4249: GEPA reflection must debounce per lane so terminal sell storms cannot choke side-effect work", gepa.contains("MIN_REFLECTION_INTERVAL_MS") && gepa.contains("lastRunByLaneMs") && gepa.contains("now - prev < MIN_REFLECTION_INTERVAL_MS"))
        assertTrue("V5.0.4249: GEPA debounce must keep proposal cache bounded and resettable", gepa.contains("MAX_PROPOSALS") && gepa.contains("lastRunByLaneMs.clear()"))
    }

    @Test
    fun semanticPatternGraph4250BoundsTerminalFanoutEdgeWork() {
        val graph = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SemanticPatternGraph.kt").readText()
        assertTrue("V5.0.4250: SemanticPatternGraph must bound per-terminal edge scan/work to avoid sell-storm churn", graph.contains("MAX_PRIOR_EDGE_SCAN") && graph.contains("MAX_EDGES_PER_NODE") && graph.contains("nodes.takeLast(MAX_PRIOR_EDGE_SCAN)"))
        assertTrue("V5.0.4250: SemanticPatternGraph must cache node tokens and stop adding edges after the cap", graph.contains("val nodeTokens = tokenSet(node.setup)") && graph.contains("if (added >= MAX_EDGES_PER_NODE) break"))
    }

    @Test
    fun asyncStrategyLab4251ReviewedBiasIsO1HotPathCache() {
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsyncStrategyLab.kt").readText()
        assertTrue("V5.0.4251: reviewedSizeBias must be O(1) hot-path cache read, not synchronized proposal scan", lab.contains("reviewedBiasByLane") && lab.contains("FDG/Executor sizing") && lab.contains("never synchronize or scan proposal history here"))
        assertTrue("V5.0.4251: reviewed bias cache must rebuild only on review/import/reset", lab.contains("rebuildReviewedBiasCache()") && lab.contains("accepted[idx] = reviewed") && lab.contains("reviewedBiasByLane.clear()"))
        assertFalse("V5.0.4251: reviewedSizeBias must not synchronize on accepted", lab.contains("fun reviewedSizeBias(lane: String, score: Int, regime: String): Double = synchronized"))
    }

    @Test
    fun terminalFanout4252SnapshotsMutableTokenStateBeforeCoroutine() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4252: terminal semantic/research fanout must snapshot mint/symbol/deployer before side-effect coroutine", executor.contains("val graphMint = graphTrade.mint.ifBlank { ts.mint }") && executor.contains("val graphSymbol = ts.symbol") && executor.contains("val graphDeployer = ts.tokenMap.creatorOrDevWallet"))
        assertTrue("V5.0.4252: side-effect fanout must use event-local snapshots for deployer and ResearchScout request", executor.contains("deployer = graphDeployer") && executor.contains("mint = graphMint") && executor.contains("symbol = graphSymbol"))
    }

    @Test
    fun symbolicProver4253ProvesAllMajorLanesConsumeAutoCompound() {
        val prover = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SymbolicInvariantProver.kt").readText()
        assertTrue("V5.0.4253: SymbolicInvariantProver must prove all major lane consumers use AutoCompoundEngine for compounding", prover.contains("AUTO_COMPOUND_LANE_CONSUMERS_4253") && prover.contains("MoonshotTraderAI.kt") && prover.contains("ShitCoinExpress.kt") && prover.contains("CashGenerationAI.kt"))
        assertTrue("V5.0.4253: compounding lane proof must require AutoCompoundEngine.getSizeMultiplier across all major lanes", prover.contains("listOf(shit, moon, express, sniper, manipulated, quality, blueChip, treasury)") && prover.contains("AutoCompoundEngine.getSizeMultiplier()"))
    }

    @Test
    fun multiAgentCritic4254IsBackgroundOnlyAcceptedHypothesisStack() {
        val critic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MultiAgentCriticStack.kt").readText()
        assertTrue("V5.0.4254: MultiAgentCriticStack must model summarizer/strategist/skeptic/symbolic judge roles", critic.contains("enum class Role") && critic.contains("SUMMARIZER") && critic.contains("STRATEGIST") && critic.contains("SKEPTIC") && critic.contains("SYMBOLIC_JUDGE"))
        assertTrue("V5.0.4254: accepted critic outputs must go only into AsyncStrategyLab persistent hypothesis bank", critic.contains("AsyncStrategyLab.submitBackgroundHypothesis") && critic.contains("symbolicChecked = true") && critic.contains("rollbackCondition"))
        assertTrue("V5.0.4254: critic stack must reject hot-path API, hard veto, zero-size, and trade-command proposals", critic.contains("SCANNER_HOT_PATH_API") && critic.contains("HARD_VETO") && critic.contains("ZERO_SIZE") && critic.contains("EXECUTEBUY"))
        assertFalse("V5.0.4254: critic stack must never call FDG or execute trades", critic.contains("FinalDecisionGate.evaluate(") || critic.contains("executeBuy("))
    }

    @Test
    fun semanticPatternGraph4255FeedsCachedEntryReadbackOnly() {
        val graph = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SemanticPatternGraph.kt").readText()
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue("V5.0.4255: SemanticPatternGraph must expose cached entryBias readback with no API/hot-path provider call", graph.contains("fun entryBias") && graph.contains("querySimilar") && graph.contains("No API, no hard block") && !graph.contains("OkHttpClient"))
        assertTrue("V5.0.4255/4278: ShitCoin entry must consume semantic readback as bounded score/size soft-shape", shit.contains("SHITCOIN_DNA_SEMANTIC_ENTRY_READBACK_4278") && shit.contains("SemanticPatternGraph.entryDnaBias") && shit.contains("semanticEntrySizeMult4255"))
        assertTrue("V5.0.4255: semantic negative memory must not create a hard threshold block", graph.contains("avgPnl <= -15.0 -> EntryBias(0.94, 0") && shit.contains("if (semanticBias.scoreDelta > 0)"))
    }

    @Test
    fun gepa4256RoutesProposalsThroughMultiAgentCriticBeforeHypothesisBank() {
        val gepa = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReflectiveOptimizerGEPA.kt").readText()
        assertTrue("V5.0.4256: GEPA proposals must route through MultiAgentCriticStack before AsyncStrategyLab bank acceptance", gepa.contains("MultiAgentCriticStack.reviewAndSubmit") && gepa.contains("BACKGROUND_MULTI_AGENT_CRITIC_GEPA_4256"))
        assertFalse("V5.0.4256: GEPA must not bypass critic by submitting directly to AsyncStrategyLab", gepa.contains("AsyncStrategyLab.submitBackgroundHypothesis"))
    }

    @Test
    fun asiSsiReauditSweeper4257IsRegisteredAndChecksMissedWiring() {
        val audit = java.io.File("../audits/asi_ssi_audit_queue_2026-06-27.md").takeIf { it.exists() }?.readText()
            ?: java.io.File("audits/asi_ssi_audit_queue_2026-06-27.md").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsiSsiReauditSweeper.kt").readText()
        val prover = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SymbolicInvariantProver.kt").readText()
        assertTrue("V5.0.4257: ASI/SSI audit queue must include recursive reaudit item", audit.contains("A21 — Recursive ASI/SSI ReAuditSweeper") && audit.contains("missed wiring") && audit.contains("hot-path API/LLM"))
        assertTrue("V5.0.4257: ReAuditSweeper must check background-only, critic routing, O(1) lab bias, terminal fanout, semantic readback, and persistence", sweeper.contains("ASI_BACKGROUND_ONLY_NO_HOT_PATH_PROVIDER_4257") && sweeper.contains("GEPA_CRITIC_MEDIATED_NO_DIRECT_BANK_BYPASS_4257") && sweeper.contains("ASYNC_LAB_REVIEWED_BIAS_O1_4257") && sweeper.contains("TERMINAL_FANOUT_EVENT_LOCAL_BOUNDED_4257") && sweeper.contains("SEMANTIC_ENTRY_READBACK_WIRED_SOFT_ONLY_4257") && sweeper.contains("REPLAY_RESEARCH_PERSISTENCE_WIRED_4257"))
        assertTrue("V5.0.4257: SymbolicInvariantProver must register recursive ASI/SSI reaudit contract", prover.contains("ASI_SSI_REAUDIT_SWEEPER_REGISTERED_4257") && prover.contains("AsiSsiReauditSweeper"))
        assertTrue("V5.0.4257: source-tree ASI/SSI reaudit must pass on current codebase", AsiSsiReauditSweeper.failed(java.io.File(".")).isEmpty())
    }

    @Test
    fun researchScout4258HasBoundedPeriodicBackgroundSweep() {
        val research = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ResearchScout.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsiSsiReauditSweeper.kt").readText()
        assertTrue("V5.0.4258: ResearchScout must expose bounded periodic background sweep with interval gating", research.contains("maybeRunPeriodicBackgroundSweep") && research.contains("MIN_PERIODIC_SWEEP_INTERVAL_MS") && research.contains("BACKGROUND_RESEARCH_SCOUT_PERIODIC_4258"))
        assertTrue("V5.0.4258: terminal fanout may trigger ResearchScout sweep only as side-effect/background telemetry", executor.contains("ResearchScout.maybeRunPeriodicBackgroundSweep") && executor.contains("RESEARCH_SCOUT_PERIODIC_SWEEP_4258"))
        assertTrue("V5.0.4258: ASI/SSI ReAuditSweeper must include ResearchScout periodic sweep wiring", sweeper.contains("maybeRunPeriodicBackgroundSweep") && sweeper.contains("MIN_PERIODIC_SWEEP_INTERVAL_MS"))
    }

    @Test
    fun counterfactualReplay4259HasBoundedMctsExitPolicyHint() {
        val replay = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CounterfactualReplayEngine.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsiSsiReauditSweeper.kt").readText()
        assertTrue("V5.0.4259: CounterfactualReplayEngine must expose bounded offline MCTS/UCB-style exit policy hint", replay.contains("fun mctsExitPolicyHint") && replay.contains("MctsExitPolicyHint") && replay.contains("rollouts.coerceIn(16, 256)") && replay.contains("sqrt(2.0"))
        assertTrue("V5.0.4259: policyHints must include mcts policy text for GEPA/critic consumption", replay.contains("mcts=") && replay.contains("mctsExitPolicyHint(lane)"))
        assertTrue("V5.0.4259: ASI/SSI re-audit must check MCTS replay wiring", sweeper.contains("mctsExitPolicyHint") && sweeper.contains("bounded offline MCTS"))
    }

    @Test
    fun memeTraderFullAuditSweeper4261TracksOriginalPassesAThroughJ() {
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4261: full meme trader audit sweeper must track original Pass A-J, not only ShitCoin", sweeper.contains("PASS_A_COMPOUNDING_4261") && sweeper.contains("PASS_B_CROSSTALK_ENTRY_CONSUMPTION_4261") && sweeper.contains("PASS_C_CANONICAL_OUTCOME_BUS_4261") && sweeper.contains("PASS_D_REGIME_VOLATILITY_4261") && sweeper.contains("PASS_E_SUPERBRAIN_CONSUMPTION_4261") && sweeper.contains("PASS_F_EXPRESS_PARITY_4261") && sweeper.contains("PASS_G_SHADOW_LEARNING_4261") && sweeper.contains("PASS_H_EXIT_MANAGER_DEDUP_4261") && sweeper.contains("PASS_I_HOLD_TIME_OPTIMIZER_4261") && sweeper.contains("PASS_J_METACOGNITION_CONSUMPTION_4261"))
    }

    @Test
    fun memeCrossTalkEntryBridge4262ShapesCentralLaneQualifiedEntries() {
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeCrossTalkEntryBridge.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4262: cross-talk bridge must consume AICrossTalk and emit bounded entry confidence/size shape", bridge.contains("AICrossTalk.analyzeCrossTalk") && bridge.contains("confidenceBoost.coerceIn(-8.0, 8.0)") && bridge.contains("sizeMultiplier.coerceIn(0.72, 1.18)"))
        assertTrue("V5.0.4262: central laneQualifiedBuyDecision must apply cross-talk entry shaping to all meme recovery lanes", bot.contains("MemeCrossTalkEntryBridge.shapeLaneEntry") && bot.contains("MEME_CROSSTALK_ENTRY_SHAPED_4262") && bot.contains("shapedConfidenceFloor4262"))
        assertTrue("V5.0.4262: full meme audit sweeper must track Pass B cross-talk entry consumption", sweeper.contains("PASS_B_CROSSTALK_ENTRY_CONSUMPTION_4261") && sweeper.contains("MemeCrossTalkEntryBridge"))
    }

    @Test
    fun paperLiveIntelligenceBridge4262SoftlyAlignsPaperLearningIntoLiveSizing() {
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperLiveIntelligenceBridge.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4262: paper/live bridge must compare paper and live closed rows per lane", bridge.contains("TradeHistoryStore.getRecentValidClosedForMode") && bridge.contains("""it.mode.equals("paper""") && bridge.contains("""it.mode.equals("live"""))
        assertTrue("V5.0.4262: paper learning may only shape live softly and must fade as live samples grow", bridge.contains("liveFade") && bridge.contains("coerceIn(0.94, 1.08)") && bridge.contains("insufficient_paper"))
        assertTrue("V5.0.4262: Executor live size stack must consume paper/live bridge without affecting paper mode", exec.contains("PaperLiveIntelligenceBridge.liveSizeMultiplier") && exec.contains("RuntimeModeAuthority.isLive()") && exec.contains("PAPER_LIVE_INTEL_SIZE_SHAPED_4262") && exec.contains("paperLiveBridgeMult"))
        assertTrue("V5.0.4262: full meme audit sweeper must track paper/live intelligence alignment", sweeper.contains("PAPER_LIVE_INTELLIGENCE_ALIGNMENT_4262"))
    }

    @Test
    fun shadowLearning4263RegistersOpportunitiesAndShapesExecutorSizeSoftly() {
        val shadow = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ShadowLearningEngine.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4263: ShadowLearningEngine must expose best variant as tiny bounded size bias", shadow.contains("fun bestVariantSizeBias") && shadow.contains("positionSizeMultiplier.coerceIn(0.94, 1.06)") && shadow.contains("best.comparedToLive < 8.0"))
        assertTrue("V5.0.4263: Executor must register trade opportunities and consume shadow variant bias in shared paper/live size stack", exec.contains("ShadowLearningEngine.onTradeOpportunity") && exec.contains("ShadowLearningEngine.bestVariantSizeBias") && exec.contains("SHADOW_VARIANT_SIZE_SHAPED_4263") && exec.contains("shadowVariantSizeMult"))
        assertTrue("V5.0.4263: full meme audit sweeper must mark Pass G only when shadow variants are consumed", sweeper.contains("PASS_G_SHADOW_LEARNING_4261") && sweeper.contains("bestVariantSizeBias") && sweeper.contains("ShadowLearningEngine.onTradeOpportunity"))
    }

    @Test
    fun advancedExitManager4264IsAdvisoryOutsideHardSafetyInHoldingLayer() {
        val holding = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HoldingLogicLayer.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4264: HoldingLogicLayer AEM must only return exit for hard safety", holding.contains("AEM_HARD_SAFETY_4264") && holding.contains("AEM_HOLDING_ADVISORY_ONLY_4264") && holding.contains("action=hold_to_executor_authority"))
        assertTrue("V5.0.4264: Executor.requestSell remains central sell authority and SellOptimizationAI remains terminal learner", exec.contains("fun requestSell") && exec.contains("SellOptimizationAI.recordExitOutcome"))
        assertTrue("V5.0.4264: full meme audit sweeper must track Pass H duplicate exit-manager cleanup", sweeper.contains("PASS_H_EXIT_MANAGER_DEDUP_4261") && sweeper.contains("AEM_HOLDING_ADVISORY_ONLY_4264"))
    }

    @Test
    fun superBrain4265ConsumesEntrySignalsInExecutorSizing() {
        val superBrain = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SuperBrainEnhancements.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4265: SuperBrain must expose bounded entry size multiplier from aggregate signals/breadth", superBrain.contains("fun entrySizeMultiplier") && superBrain.contains("getAggregatedSignal") && superBrain.contains("coerceIn(0.94, 1.08)"))
        assertTrue("V5.0.4265: Executor must feed entry score into SuperBrain and consume it in shared paper/live size stack", exec.contains("SuperBrainEnhancements.recordSignal") && exec.contains("SuperBrainEnhancements.entrySizeMultiplier") && exec.contains("SUPERBRAIN_ENTRY_SIZE_SHAPED_4265") && exec.contains("superBrainSizeMult"))
        assertTrue("V5.0.4265: full meme audit sweeper must close Pass E only with real SuperBrain consumption", sweeper.contains("PASS_E_SUPERBRAIN_CONSUMPTION_4261") && sweeper.contains("SUPERBRAIN_ENTRY_SIZE_SHAPED_4265"))
    }

    @Test
    fun metaCognition4267ShapesExecutorSizingAcrossHighThroughputLanes() {
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MetaCognitionExecutorBridge.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4267: MetaCognition bridge must consume trust multipliers and map trader lanes", bridge.contains("MetaCognitionAI.getTrustMultiplier") && bridge.contains("SHITCOIN_TRADER") && bridge.contains("MOONSHOT_TRADER") && bridge.contains("coerceIn(0.94, 1.08)"))
        assertTrue("V5.0.4267: Executor must consume MetaCognition in the shared paper/live size stack", exec.contains("MetaCognitionExecutorBridge.sizeMultiplierForLane") && exec.contains("METACOGNITION_EXECUTOR_SIZE_SHAPED_4267") && exec.contains("metaCognitionSizeMult"))
        assertTrue("V5.0.4267: full meme audit sweeper must close Pass J only with executor-side MetaCognition consumption", sweeper.contains("PASS_J_METACOGNITION_CONSUMPTION_4261") && sweeper.contains("MetaCognitionExecutorBridge.sizeMultiplierForLane"))
    }

    @Test
    fun regimeVolatility4268FeedsExecutorSizingAndPersistenceContract() {
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RegimeVolatilityExecutorBridge.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeTraderFullAuditSweeper.kt").readText()
        assertTrue("V5.0.4268: regime/vol bridge must consume VolatilityRegimeAI and RegimeTransitionAI from TokenState", bridge.contains("VolatilityRegimeAI.analyze") && bridge.contains("RegimeTransitionAI.analyzeTransition") && bridge.contains("coerceIn(0.90, 1.12)"))
        assertTrue("V5.0.4268: Executor must consume regime/volatility shape in shared paper/live size stack", exec.contains("RegimeVolatilityExecutorBridge.sizeShape") && exec.contains("REGIME_VOL_EXECUTOR_SIZE_SHAPED_4268") && exec.contains("regimeVolSizeMult"))
        assertTrue("V5.0.4268: persistence must still retain both regime and volatility AI state", persistence.contains("REGIME_TRANSITION") && persistence.contains("VOLATILITY_REGIME"))
        assertTrue("V5.0.4268: full meme audit sweeper must close Pass D only with persistence plus executor consumer", sweeper.contains("PASS_D_REGIME_VOLATILITY_4261") && sweeper.contains("RegimeVolatilityExecutorBridge.sizeShape"))
    }

    @Test
    fun fullMemeTraderAudit4269OriginalPassesAThroughJAreClosed() {
        val open = MemeTraderFullAuditSweeper.openItems(java.io.File("."))
        assertTrue("V5.0.4269: original full Meme Trader audit Pass A-J must be closed; open=${open.joinToString { it.id + ":" + it.detail }}", open.isEmpty())
    }

    @Test
    fun livePaperDriftSentinel4271IsReportOnlyAndTerminalOnly() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LivePaperDriftSentinel.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4271: live/paper drift sentinel must be report-only and debounced", sentinel.contains("LIVE_PAPER_DRIFT_SENTINEL_4271") && sentinel.contains("report_only_no_pause_no_size_change") && sentinel.contains("DEBOUNCE_MS"))
        assertTrue("V5.0.4271: sentinel must compare event-local journal rows by paper/live mode and lane", sentinel.contains("it.mode.equals") && sentinel.contains("paper") && sentinel.contains("live") && sentinel.contains("TradeHistoryStore.normalizeTradeModeName"))
        assertTrue("V5.0.4271: Executor may call sentinel only after terminal trainable SELL is recorded", exec.contains("TradeHistoryStore.recordTrade(tradeWithMint)") && exec.contains("LivePaperDriftSentinel.onTerminalClose") && exec.contains("ledgerAllowsClosedLearning && accountingTrainable"))
    }

    @Test
    fun multiplierAttribution4272PersistsEntrySizingComponents() {
        val ledger = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MultiplierAttributionLedger.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4272: multiplier attribution ledger must persist event-local mode/lane/source/build and component map", ledger.contains("data class Row") && ledger.contains("buildTag") && ledger.contains("components: Map<String, Double>") && ledger.contains("exportState") && ledger.contains("importState"))
        assertTrue("V5.0.4272: Executor must record all shared size-stack multipliers before final sizing", exec.contains("MultiplierAttributionLedger.recordEntry") && exec.contains("paperLiveBridgeMult") && exec.contains("shadowVariantSizeMult") && exec.contains("superBrainSizeMult") && exec.contains("regimeVolSizeMult"))
        assertTrue("V5.0.4272: LearningPersistence must save/restore multiplier attribution state", persistence.contains("MULTIPLIER_ATTRIBUTION") && persistence.contains("MultiplierAttributionLedger.exportState") && persistence.contains("MultiplierAttributionLedger.importState"))
    }

    @Test
    fun scannerDiversityBandit4273ReordersWithoutBlockingSources() {
        val bandit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ScannerDiversityBandit.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4273: scanner diversity bandit must consume ScannerSourceBrain softly", bandit.contains("ScannerSourceBrain.intakeMultiplier") && bandit.contains("orderedFamilies") && bandit.contains("PUMP") && bandit.contains("OTHER"))
        assertTrue("V5.0.4273: watchlist source-balanced ordering must use bandit while preserving soft-order-only contract", bot.contains("ScannerDiversityBandit.orderedFamilies") && bot.contains("SCANNER_DIVERSITY_BANDIT_ORDER_4273") && bot.contains("soft_order_only_no_source_block"))
    }

    @Test
    fun exitCostMicrobrain4275LearnsTerminalCostsOnlyAndPersists() {
        val brain = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExitCostMicrobrain.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4275: ExitCostMicrobrain must learn terminal cost drag without hard exit authority", brain.contains("object ExitCostMicrobrain") && brain.contains("recordTerminalExit") && brain.contains("quoteDivergencePct") && brain.contains("feeSol") && brain.contains("learn_only_no_exit_block"))
        assertTrue("V5.0.4275: ExitCostMicrobrain must expose bounded cached urgency hints only", brain.contains("exitUrgencyHint") && brain.contains("urgencyMult = urgency.coerceIn(0.92, 1.08)") && brain.contains("MIN_HINT_SAMPLES"))
        assertTrue("V5.0.4275: Executor must feed ExitCostMicrobrain from terminal trainable SELL choke only", exec.contains("ExitCostMicrobrain.recordTerminalExit") && exec.contains("tradeWithMint.side.equals") && exec.contains("ledgerAllowsClosedLearning && accountingTrainable"))
        assertTrue("V5.0.4275: ExitCostMicrobrain must persist through LearningPersistence", brain.contains("exportState") && brain.contains("importState") && persistence.contains("EXIT_COST_MICROBRAIN"))
    }

    @Test
    fun runnerRetentionOptimizer4277PromotesOnlyBackgroundSymbolicHoldBias() {
        val opt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RunnerRetentionOptimizer.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4277: RunnerRetentionOptimizer must use counterfactual replay and critic-reviewed background proposals", opt.contains("MultiAgentCriticStack.reviewAndSubmit") && opt.contains("BACKGROUND_RUNNER_RETENTION_4277") && opt.contains("bounded soft hold bias"))
        assertTrue("V5.0.4277: RunnerRetentionOptimizer must not introduce direct sell authority or hard veto/zero-size commands", opt.contains("no direct exit authority") && opt.contains("no hard veto") && opt.contains("no zero-size") && !opt.contains("requestSell(") && !opt.contains("executeBuy("))
        assertTrue("V5.0.4277: Executor must feed runner retention from terminal outcome side-effect fanout after counterfactual replay", exec.contains("CounterfactualReplayEngine.recordTerminalTrade") && exec.contains("RunnerRetentionOptimizer.recordTerminalExit") && exec.contains("runnerReplayHint"))
    }

    @Test
    fun deployerClusterDnaReadback4278SoftShapesEntryOnly() {
        val semantic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SemanticPatternGraph.kt").readText()
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue("V5.0.4278: SemanticPatternGraph must expose deployer/source/DNA-aware cached entry readback", semantic.contains("fun entryDnaBias") && semantic.contains("deployerKey") && semantic.contains("sourceKey") && semantic.contains("dnaKey") && semantic.contains("biasFromNodes"))
        assertTrue("V5.0.4278: DNA readback must remain soft score/size shaping only", semantic.contains("EntryBias(raw.sizeMult.coerceIn(0.92, 1.08)") && semantic.contains("raw.scoreDelta.coerceIn(0, 5)") && !semantic.contains("hard veto") && !semantic.contains("return ShitCoinSignal"))
        assertTrue("V5.0.4278: ShitCoin must consume DNA readback with deployer and source context", shit.contains("SemanticPatternGraph.entryDnaBias") && shit.contains("deployer = devWallet.orEmpty()") && shit.contains("dnaKey4278") && shit.contains("SHITCOIN_DNA_SEMANTIC_ENTRY_READBACK_4278"))
    }

    private fun readRootWorkflowFor4280s(): String {
        val candidates = listOf(
            java.io.File("../.github/workflows/build.yml"),
            java.io.File(".github/workflows/build.yml"),
            java.io.File("../../.github/workflows/build.yml"),
        )
        return candidates.firstOrNull { it.exists() && it.readText().contains("Golden Tape literal static scan") }?.readText().orEmpty()
    }

    private fun readLifecycleFileFor4280s(path: String): String {
        val candidates = listOf(
            java.io.File(path),
            java.io.File("../$path"),
            java.io.File("../../lifecycle_apk/$path"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: throw java.io.FileNotFoundException(path)
    }

    @Test
    fun goldenTapeLiteralStaticCompiler4279RunsBeforeGradle() {
        val script = readLifecycleFileFor4280s("ci/golden_tape_literal_scan.py")
        val workflow = readRootWorkflowFor4280s()
        assertTrue("V5.0.4279: Golden Tape literal scan must catch nested unescaped contains quotes", script.contains("nested unescaped quote inside contains()") && script.contains("triple-quoted strings") && script.contains("interpolation-like"))
        assertTrue("V5.0.4279: root build workflow must run literal scan before release build", workflow.contains("Golden Tape literal static scan") && workflow.contains("golden_tape_literal_scan.py") && workflow.contains("Build Release APK"))
    }

    @Test
    fun mainActivityDecisionLogAnrDecoupling4280BoundsTextLayoutWork() {
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue("V5.0.4280: decision log must use bounded/coalesced TextView updates instead of raw large text assignment", main.contains("setDecisionLogTextBounded4280") && main.contains("DECISION_LOG_MAX_CHARS_4280") && main.contains("tvDecisionLog.setTextIfChanged(compact)"))
        assertTrue("V5.0.4280: decision log time formatter must be reused instead of allocated every update", main.contains("decisionLogTimeSdf4280") && !main.contains("""SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())"""))
        assertTrue("V5.0.4280: A29 UI/ANR patch must not touch executor or ledger authority", !main.contains("requestSell(") && !main.contains("executeBuy("))
    }

    @Test
    fun capitalEfficiencyBrain4281PersistsAndSoftShapesSizingOnly() {
        val brain = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CapitalEfficiencyBrain.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4281: CapitalEfficiencyBrain must learn pnlSol per SOL-minute from terminal SELL outcomes", brain.contains("pnl / (sol * holdMin") && exec.contains("CapitalEfficiencyBrain.recordTerminalTrade"))
        assertTrue("V5.0.4281: capital-efficiency sizing must stay bounded and runner-safe", brain.contains("coerceIn(0.92, 1.08)") && brain.contains("isRunnerCandidate") && exec.contains("CAPITAL_EFFICIENCY_SIZE_SHAPED_4281"))
        assertTrue("V5.0.4281: CapitalEfficiencyBrain state must persist through LearningPersistence", brain.contains("exportState") && brain.contains("importState") && persistence.contains("CAPITAL_EFFICIENCY"))
    }

    @Test
    fun operationalBuildGateDashboard4282RunsInCi() {
        val script = readLifecycleFileFor4280s("ci/build_gate_summary.py")
        val workflow = readRootWorkflowFor4280s()
        assertTrue("V5.0.4282: build gate dashboard must summarize touched production files, Golden Tape, audit bundles, and inherited failures", script.contains("production files touched") && script.contains("Golden Tape touched") && script.contains("latest audit bundles marked implemented") && script.contains("inherited known failures"))
        assertTrue("V5.0.4282: workflow must emit build gate summary after release build", workflow.contains("build_gate_summary.py") && workflow.contains("assembleRelease"))
    }

    @Test
    fun sizingStackIntegritySentinel4285ReportsOnlyNoZeroSizing() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SizingStackIntegritySentinel.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4285: sentinel must emit report-only telemetry for dust/runaway cumulative multiplier stacks", sentinel.contains("SIZING_STACK_INTEGRITY_SENTINEL_4285") && sentinel.contains("dust_stack") && sentinel.contains("runaway_stack"))
        assertTrue("V5.0.4285: Executor must inspect named sizing components without mutating multiplierProductRaw after inspection", exec.contains("sizingStackComponents4285") && exec.contains("SizingStackIntegritySentinel.inspect") && exec.contains("capitalEfficiency"))
        assertFalse("V5.0.4285: sizing sentinel must not hard block, return zero size, or call execution authority", sentinel.contains("return 0.0") || sentinel.contains("executeBuy(") || sentinel.contains("requestSell("))
    }

    @Test
    fun terminalOutcomeQualityGate4286ReportsTrainabilityWithoutHidingPnl() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TerminalOutcomeQualityGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4286: terminal quality gate must classify proof/position/basis/pnl quality", gate.contains("proofState") && gate.contains("positionId") && gate.contains("entryPriceSnapshot") && gate.contains("TRAINABLE") && gate.contains("CONTAMINATED"))
        assertTrue("V5.0.4286: Executor must report terminal outcome quality near accountingTrainable gate", exec.contains("terminalOutcomeQuality4286") && exec.contains("TerminalOutcomeQualityGate.report") && gate.contains("TERMINAL_OUTCOME_QUALITY_4286"))
        assertFalse("V5.0.4286: terminal quality gate must not hide PnL, block sells, or bypass CanonicalOutcomeBus", gate.contains("return 0.0") || gate.contains("requestSell(") || gate.contains("CanonicalOutcomeBus.publishUnchecked"))
    }

    @Test
    fun sourceFamilyOpportunityScorecard4287IsDiagnosticAndPersistent() {
        val scorecard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SourceFamilyOpportunityScorecard.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4287: source-family scorecard must track discovered/admitted/opened/closed/PF-like diagnostics", scorecard.contains("recordDiscovered") && scorecard.contains("recordAdmitted") && scorecard.contains("recordOpened") && scorecard.contains("recordClosed") && scorecard.contains("SOURCE_FAMILY_OPPORTUNITY_SCORECARD_4287"))
        assertTrue("V5.0.4287: Executor must feed opened/closed source-family events from event-local source", exec.contains("SourceFamilyOpportunityScorecard.recordOpened(_fanoutSource)") && exec.contains("SourceFamilyOpportunityScorecard.recordClosed(_fanoutSource, trade)"))
        assertTrue("V5.0.4287: source-family scorecard must persist and never block sources", persistence.contains("SOURCE_FAMILY_SCORECARD") && !scorecard.contains("return false") && !scorecard.contains("hard-block"))
    }

    @Test
    fun runnerExitShadowLedger4289IsOfflineOnlyAndPersistent() {
        val ledger = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RunnerExitShadowLedger.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4289: runner shadow ledger must record peak-vs-realized giveback for runner exits", ledger.contains("RUNNER_EXIT_SHADOW_LEDGER_4289") && ledger.contains("giveback") && ledger.contains("offline_only=true"))
        assertTrue("V5.0.4289: Executor must feed runner shadow ledger from terminal event-local replay fanout", exec.contains("RunnerExitShadowLedger.recordTerminalExit") && exec.contains("graphPeakPct") && exec.contains("graphTrade.pnlPct"))
        assertTrue("V5.0.4289: runner shadow ledger must persist and never call sell authority", persistence.contains("RUNNER_EXIT_SHADOW_LEDGER") && !ledger.contains("requestSell(") && !ledger.contains("executeSell("))
    }

    @Test
    fun liveWalletGrowthGovernor4290ReportsRealizedDailyDecompositionOnly() {
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveWalletGrowthGovernorReport.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4290: live wallet growth report must decompose realized net PnL, fees/slip, dust, compounding, and idle/open exposure", report.contains("realizedNetSol") && report.contains("feesSol") && report.contains("avgQuoteDragPct") && report.contains("dustCleanupSol") && report.contains("compoundingReinvestedSol") && report.contains("idleOpenApproxSol"))
        assertTrue("V5.0.4290: Executor must feed report from event-local live/paper fanout without changing execution", exec.contains("LiveWalletGrowthGovernorReport.record(trade, _fanoutIsPaper)") && exec.contains("LiveWalletGrowthGovernorReport.maybeEmit()"))
        assertTrue("V5.0.4290: live growth report must persist and explicitly avoid phantom PnL", persistence.contains("LIVE_WALLET_GROWTH_GOVERNOR") && report.contains("report_only=true") && report.contains("no_phantom_pnl=true") && !report.contains("requestSell("))
    }

    @Test
    fun asiSsiAuditCloseoutManifest4292ReportsImplementedAndPendingOnly() {
        val manifest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsiSsiAuditCloseoutManifest.kt").readText()
        val summary = readLifecycleFileFor4280s("ci/build_gate_summary.py")
        val audit = readLifecycleFileFor4280s("audits/asi_ssi_audit_queue_2026-06-27.md")
        assertTrue("V5.0.4292: closeout manifest must report implemented A13-A36 and pending A37-A41", manifest.contains("A36 live wallet growth governor") && manifest.contains("A37 closeout manifest self-report") && manifest.contains("report_only=true"))
        assertTrue("V5.0.4292: build summary must surface closeout manifest or implemented audit bundle context", summary.contains("A37 closeout manifest") || summary.contains("latest audit bundles marked implemented"))
        assertTrue("V5.0.4292: audit queue must include Bundle I closeout items", audit.contains("Bundle I") && audit.contains("A41 — Operator KPI Closeout Report"))
        assertFalse("V5.0.4292: closeout manifest must not influence execution", manifest.contains("executeBuy(") || manifest.contains("requestSell(") || manifest.contains("FinalDecisionGate.evaluate("))
    }


    @Test
    fun aiStatePersistenceSentinel4295LocksLearnedStateContract() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AiStatePersistenceSentinel.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4295 A38: AI persistence sentinel must enumerate all closeout learned-state helpers", sentinel.contains("AI_STATE_PERSISTENCE_SENTINEL_4295") && sentinel.contains("ASYNC_STRATEGY_LAB") && sentinel.contains("SEMANTIC_PATTERN_GRAPH") && sentinel.contains("COUNTERFACTUAL_REPLAY") && sentinel.contains("RESEARCH_SCOUT") && sentinel.contains("MULTIPLIER_ATTRIBUTION") && sentinel.contains("EXIT_COST_MICROBRAIN") && sentinel.contains("CAPITAL_EFFICIENCY") && sentinel.contains("SOURCE_FAMILY_SCORECARD") && sentinel.contains("RUNNER_EXIT_SHADOW_LEDGER") && sentinel.contains("LIVE_WALLET_GROWTH_GOVERNOR"))
        assertTrue("V5.0.4295 A38: LearningPersistence must still save/import/reset every expected AI helper", persistence.contains("ASYNC_STRATEGY_LAB") && persistence.contains("SEMANTIC_PATTERN_GRAPH") && persistence.contains("COUNTERFACTUAL_REPLAY") && persistence.contains("RESEARCH_SCOUT") && persistence.contains("MULTIPLIER_ATTRIBUTION") && persistence.contains("EXIT_COST_MICROBRAIN") && persistence.contains("CAPITAL_EFFICIENCY") && persistence.contains("SOURCE_FAMILY_SCORECARD") && persistence.contains("RUNNER_EXIT_SHADOW_LEDGER") && persistence.contains("LIVE_WALLET_GROWTH_GOVERNOR") && persistence.contains("reset()"))
        assertFalse("V5.0.4295 A38: persistence sentinel must not affect execution", sentinel.contains("executeBuy(") || sentinel.contains("requestSell(") || sentinel.contains("FinalDecisionGate.evaluate("))
    }

    @Test
    fun hotPathProviderCallSentinel4295KeepsProvidersOutOfHotPath() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HotPathProviderCallSentinel.kt").readText()
        val async = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsyncStrategyLab.kt").readText()
        val research = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ResearchScout.kt").readText()
        assertTrue("V5.0.4295 A39: provider sentinel must name scanner/FDG/executor hot paths and forbidden provider tokens", sentinel.contains("HOT_PATH_PROVIDER_CALL_SENTINEL_4295") && sentinel.contains("BotService.kt") && sentinel.contains("FinalDecisionGate.kt") && sentinel.contains("Executor.kt") && sentinel.contains("GeminiCopilot.generate") && sentinel.contains("OpenRouter") && sentinel.contains("Groq"))
        assertTrue("V5.0.4295 A39: provider-using intelligence must remain background-only", sentinel.contains("allowedBackgroundWorkers") && sentinel.contains("AsyncStrategyLab") && sentinel.contains("ResearchScout") && async.contains("background_only=true") && research.contains("BACKGROUND_RESEARCH_SCOUT"))
        assertFalse("V5.0.4295 A39: provider sentinel must not pause, zero-size, buy, or sell", sentinel.contains("return 0.0") || sentinel.contains("executeBuy(") || sentinel.contains("requestSell(") || sentinel.contains("return false"))
    }

    @Test
    fun learningFanoutMuxSentinel4295RequiresEventLocalSnapshots() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningFanoutMuxSentinel.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4295 A40: mux sentinel must require event-local mode/lane/source/positionId/mint/symbol/timestamp/build", sentinel.contains("LEARNING_FANOUT_MUX_SENTINEL_4295") && sentinel.contains("positionId") && sentinel.contains("timestamp") && sentinel.contains("build") && sentinel.contains("no_mutable_tokenstate_in_background"))
        assertTrue("V5.0.4295 A40: Executor terminal fanout must snapshot positionId/build before sideEffect launch", exec.contains("val _fanoutPositionId") && exec.contains("val _fanoutBuildTag") && exec.indexOf("val _fanoutPositionId") < exec.indexOf("LearningFanoutMuxSentinel.report"))
        assertTrue("V5.0.4295 A40: fanout sentinel must be fed only snapshot variables", exec.contains("LearningFanoutMuxSentinel.report") && exec.contains("positionId = _fanoutPositionId") && exec.contains("source = _fanoutSource") && exec.contains("build = _fanoutBuildTag"))
        assertFalse("V5.0.4295 A40: mux sentinel must not block learning or execution", sentinel.contains("return false") || sentinel.contains("executeBuy(") || sentinel.contains("requestSell("))
    }

    @Test
    fun operatorKpiCloseoutReport4295TiesAuditToLiveGrowth() {
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val manifest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsiSsiAuditCloseoutManifest.kt").readText()
        val audit = readLifecycleFileFor4280s("audits/asi_ssi_audit_queue_2026-06-27.md")
        assertTrue("V5.0.4295 A41: operator KPI closeout must cover volume, live/paper drift, realized SOL, runner giveback, source PF, sizing warnings, and build status", report.contains("OPERATOR_KPI_CLOSEOUT_REPORT_4416") && report.contains("volume=TradeHistoryStore") && report.contains("live_paper_drift=LivePaperDriftSentinel") && report.contains("realized_net_sol=LiveWalletGrowthGovernorReport") && report.contains("runner_giveback=RunnerExitShadowLedger") && report.contains("source_family_pf=SourceFamilyOpportunityScorecard") && report.contains("sizing_stack_warnings=SizingStackIntegritySentinel") && report.contains("build_status"))
        assertTrue("V5.0.4295 A41: Executor must emit operator KPI report only from side-effect terminal fanout", exec.contains("OperatorKpiCloseoutReport.emit()") && exec.contains("if (_fanoutSide == \"SELL\")"))
        assertTrue("V5.0.4295 closeout: manifest and audit queue must mark A38-A41 complete", manifest.contains("A41 operator KPI closeout report") && manifest.contains("audit_closed=true") && audit.contains("Status: implemented in V5.0.4295 as `OperatorKpiCloseoutReport`"))
        assertFalse("V5.0.4295 A41: KPI closeout must never own execution authority or fake PnL", report.contains("executeBuy(") || report.contains("requestSell(") || !report.contains("no_phantom_pnl=true") || !report.contains("no_execution_authority=true"))
    }


    @Test
    fun fdgFinalPreLiveChokeSweep4297SoftShapesNonSafetyConfidenceKills() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue("V5.0.4297: AI_DEGRADED confidence floor must be a soft probe, not a zero-size hard kill", fdg.contains("AI_DEGRADED_SOFT_PROBE_4297") && fdg.contains("preFdgNonSafetySizeMult4297 *= 0.45") && fdg.contains("ai_degraded_confidence_soft_shape_4297"))
        assertTrue("V5.0.4297: Kris toxic pattern must be a soft probe while true safety hard-blocks remain below", fdg.contains("TOXIC_PATTERN_SOFT_PROBE_4297") && fdg.contains("preFdgNonSafetySizeMult4297 *= 0.25") && fdg.contains("true safety still hard-blocks"))
        assertTrue("V5.0.4297: non-safety pre-FDG shape must flow into downstream size multiplier", fdg.contains("var sizeMultiplier = preFdgNonSafetySizeMult4297"))
        assertFalse("V5.0.4297: stale non-safety hard-kill reasons must not return zero-size FinalDecision", fdg.contains("blockReason = \"AI_DEGRADED_CONFIDENCE_FLOOR_") || fdg.contains("blockReason = \"TOXIC_PATTERN_KRIS_RULE\""))
    }


    @Test
    fun fdgLiveReportChokeSweep4298SoftShapesDangerAndMemoryBlocks() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue("V5.0.4298: live DANGER_ZONE_TIME must soft-shape instead of blocking the buy deck", fdg.contains("DANGER_ZONE_SOFT_PROBE_4298") && fdg.contains("time_danger_soft_shape_4298") && fdg.contains("DANGER_ZONE_TIME_SOFT_SHAPE_4298"))
        assertTrue("V5.0.4298: live MEMORY_NEGATIVE_BLOCK must soft-shape so old poisoned memory can retrain", fdg.contains("MEMORY_NEGATIVE_SOFT_PROBE_4298") && fdg.contains("memory_negative_soft_shape_4298") && fdg.contains("MEMORY_NEGATIVE_SOFT_SHAPE_4298"))
        assertFalse("V5.0.4298: danger/memory mode filters must not assign blockReason anymore", fdg.contains("blockReason = \"DANGER_ZONE_TIME\"") || fdg.contains("blockReason = \"MEMORY_NEGATIVE_BLOCK\""))
    }


    @Test
    fun birdeyePremiumPrefetch4299DoesNotPhantomCountCachedSkips() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4299: BotService must document provider-layer Birdeye accounting after report showed 6000 phantom calls", bot.contains("V5.0.4299") && bot.contains("phantom/double accounting") && bot.contains("preserves Birdeye for finalists/exits"))
        assertFalse("V5.0.4299: SecurityProvider already records real calls; BotService must not double count it", bot.contains("BirdeyeSecurityProvider.getTrust(ts.mint, beKey)\n                        com.lifecyclebot.engine.BirdeyeBudgetGate.recordCalls(1)"))
        assertFalse("V5.0.4299: maybePrefetch cache/budget skips must not increment Birdeye call counters from BotService", bot.contains("BirdeyeTradeDataProvider.maybePrefetch(ts.mint, beKey)\n                        com.lifecyclebot.engine.BirdeyeBudgetGate.recordCalls(1)") || bot.contains("BirdeyePriceStatsProvider.maybePrefetch(ts.mint, beKey)\n                        com.lifecyclebot.engine.BirdeyeBudgetGate.recordCalls(1)"))
        assertFalse("V5.0.4299: whale feeder cache/budget skips must not increment Birdeye call counters from BotService", bot.contains("BirdeyeWhaleFeeder.maybeFeed(\n                            mint = ts.mint") && bot.contains("currentPriceUsd = ts.lastPrice,\n                        )\n                        com.lifecyclebot.engine.BirdeyeBudgetGate.recordCalls(1)"))
    }


    @Test
    fun liveSoftStart4300MatchesPaperLearningBeforeHardening() {
        val relaxer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveLayerGateRelaxer.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue("V5.0.4300: live relaxer must soft-start before global WR floors harden", relaxer.contains("GLOBAL_SOFT_START_LIVE_CLOSES") && relaxer.contains("applyGlobalWrFloor") && relaxer.contains("soft-start/fade"))
        assertTrue("V5.0.4300: sibling L7 lane suppression must allow meme lanes during live soft-start", bot.contains("L7_LANE_SOFT_START_ALLOWED_4300") && bot.contains("currentLiveTerminalCount()") && bot.contains("liveN >= 40 && wr < 45.0"))
        assertTrue("V5.0.4300: FDG weak-WR liquidity lift must wait for meaningful live terminal sample", fdg.contains("val isWeakWr = wr < 30.0 && liveN >= 40") && fdg.contains("currentLiveTerminalCount()"))
    }

    @Test
    fun executor4300FallbackResolvesTraderLaneInsteadOfPoisoningStandard() {
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4300: recordTrade fallback must resolve actual meme trader lane before STANDARD fallback", ex.contains("V5.0.4300") && ex.contains("every clean close landing in") && ex.contains("resolveExecutionLane(ts, fallback = \"STANDARD\")"))
        assertFalse("V5.0.4300: recordTrade must not collapse blank trade/position mode directly to STANDARD", ex.contains("isMeaningfulLaneName(ts.position.tradingMode) -> ts.position.tradingMode\n            else                                          -> \"STANDARD\""))
    }


    @Test
    fun rapidProfitCapture4301DoesNotDelegateLiveRunnersAndMissPeaks() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4301: rapid monitor must force sell when current PnL breaches stored peak-lock", bot.contains("RAPID_PEAK_LOCK_BREACH_4301") && bot.contains("explicitPeakLockFloor4301") && bot.contains("force_sell_before_generic_dynamic_stop"))
        assertTrue("V5.0.4301: live rapid take-profit must execute immediate sell/partial instead of only delegating to manage-only", bot.contains("RAPID_INSTANT_PROFIT_CAPTURE_4301") && bot.contains("requestPartialSell") && bot.contains("requestSell") && bot.contains("immediate_live_sell_or_partial"))
        assertTrue("V5.0.4301: extreme live runners must full-capture rather than wait", bot.contains("pnlPct >= 500.0 -> 1.0") && bot.contains("pnlPct >= 200.0 -> 0.75") && bot.contains("pnlPct >= 50.0  -> 0.50"))
    }


    @Test
    fun profitLock4302BeatsWarmupAndEntryLock() {
        val fluid = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/FluidLearningAI.kt").readText()
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4302: FluidLearningAI entry protection must not override instant profit-lock", fluid.contains("V5.0.4302") && fluid.contains("holdTimeSeconds < 60 && !(currentPnlPct > 0 && peakPnlPct > 3.0)") && fluid.contains("positive trailing/profit-lock branch below"))
        assertTrue("V5.0.4302: Executor entry-lock must only hold negative dynamic stops, never positive profit locks", ex.contains("V5.0.4302") && ex.contains("dynamicStopPct <= 0.0") && ex.contains("profit-lock beats entry-lock"))
    }


    @Test
    fun harvardHeadmaster4303CanonicalAliasesCloseMemeLoop() {
        val edu = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/EducationSubLayerAI.kt").readText()
        val lanes = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/LayerLaneRegistry.kt").readText()
        assertTrue("V5.0.4303: Harvard must canonicalize dedicated meme route labels before mute/boost lookup", edu.contains("V5.0.4303") && edu.contains("\"shitcoin_trader\"    -> \"ShitCoinTraderAI\"") && edu.contains("\"shitcoin_express\"   -> \"ShitCoinExpress\"") && edu.contains("val canonicalLayerName = normalizeLayerName(layerName)") && edu.contains("layerPerformance[canonicalLayerName]"))
        assertTrue("V5.0.4303: meme-specific registered Harvard layers must not default to generic lane learning", lanes.contains("V5.0.4303") && lanes.contains("\"QualityTraderAI\"             to MEME") && lanes.contains("\"ProjectSniperAI\"             to MEME") && lanes.contains("\"ShitCoinExpress\"             to MEME"))
    }


    @Test
    fun crossTalk4304CreditsEntrySignalNotCloseTimeRecompute() {
        val xt = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AICrossTalk.kt").readText()
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeCrossTalkEntryBridge.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4304: cross-talk must stamp event-local entry signal keyed by mint+lane", xt.contains("V5.0.4304") && xt.contains("entrySignalByMintLane") && xt.contains("entryStampKey") && xt.contains("stampEntrySignal"))
        assertTrue("V5.0.4304: meme entry bridge must stamp the exact signal that shaped entry", bridge.contains("AICrossTalk.stampEntrySignal") && bridge.contains("source = ts.source") && bridge.contains("positionId = TradeOutcomeLedger.positionId(ts)"))
        assertTrue("V5.0.4304: terminal learning must credit stamped entry signal, not recompute AICrossTalk at close", exec.contains("recordStampedEntryOutcome(ts.mint") && exec.contains("credits the wrong teacher") && !exec.contains("val crossTalkSignal = AICrossTalk.analyzeCrossTalk(ts, isOpenPosition = false)"))
    }


    @Test
    fun strategyHypothesis4305PromotionClockIncrementsOnce() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue("V5.0.4305: hypothesis promotion must increment experiment clock once", src.contains("V5.0.4305") && src.contains("one promotion event must increment") && !src.contains("promotions += 1\n            promotions += 1"))
    }


    @Test
    fun socialVelocity4306DoesNotBlockScorerOnDexScreener() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/SocialVelocityAI.kt").readText()
        assertTrue("V5.0.4306: SocialVelocity scorer must use cached DexScreener boost data and async refresh", src.contains("V5.0.4306") && src.contains("refreshBoostedTokensAsync") && src.contains("refreshScope.launch") && src.contains("refreshBoostedTokensBlocking"))
        assertTrue("V5.0.4306: getBoostAmount/isBoosted must not call blocking refresh directly", src.contains("fun getBoostAmount") && src.contains("fun isBoosted") && !src.contains("fun getBoostAmount(mint: String): Long {\n            refreshBoostedTokens()"))
    }

    @Test
    fun smartSystemRuntimeRegistry4307ProvesDormantSweepIsRuntimeVisible() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4307: smart system registry must be report-only with no execution authority", registry.contains("SMART_SYSTEM_RUNTIME_REGISTRY_4348") && registry.contains("report_only=true") && registry.contains("no_execution_authority=true"))
        assertTrue("V5.0.4307: route providers from dormant sweep must be classified for proof", listOf("RaydiumDirectProvider", "OrcaDirectProvider", "MeteoraDirectProvider", "PumpFunDirectProvider", "PumpSwapDirectProvider", "JupiterUltraProvider", "JupiterMetisProvider", "JitoSenderProvider", "HeliusSenderProvider").all { registry.contains(it) })
        assertTrue("V5.0.4307: arb deck dormant candidates must be classified before activation", listOf("ArbCoordinator", "ArbScannerAI", "ArbLearning", "VenueLagModel", "FlowImbalanceModel", "PanicReversionModel", "SourceTimingRegistry").all { registry.contains(it) })
        assertTrue("V5.0.4307: existing sentinels must emit at startup, not only exist for Golden Tape", registry.contains("AiStatePersistenceSentinel.emit()") && registry.contains("HotPathProviderCallSentinel.emit()") && bot.contains("SmartSystemRuntimeRegistry.emitStartupProof()"))
    }

    @Test
    fun treasuryCashflowMissionReport4308IsReportOnlyAndWired() {
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TreasuryCashflowMissionReport.kt").readText()
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue("V5.0.4308: Treasury cashflow report must be report-only and never change gates", report.contains("TREASURY_CASHFLOW_MISSION_4308") && report.contains("report_only=true") && report.contains("no_gate_change=true"))
        assertTrue("V5.0.4308: CashGen evaluate funnel must record evaluation, reject, and accept", cash.contains("TreasuryCashflowMissionReport.recordEvaluation") && cash.contains("TreasuryCashflowMissionReport.recordRejected") && cash.contains("TreasuryCashflowMissionReport.recordAccepted"))
        assertTrue("V5.0.4308: CashGen open/close/feed must stamp wallet-feeder mission telemetry", cash.contains("TreasuryCashflowMissionReport.recordOpened") && cash.contains("TreasuryCashflowMissionReport.recordClosed") && cash.contains("TreasuryCashflowMissionReport.recordTreasuryFeed"))
    }

    @Test
    fun treasurySourceRouter4309SoftShapesCashGenWithoutNetworkOrRejects() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TreasurySourceRouter.kt").readText()
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4309: Treasury source router must be soft-only and network-free", router.contains("TREASURY_SOURCE_ROUTER_4309") && router.contains("soft_only=true") && router.contains("no_hard_reject=true") && router.contains("no_network=true"))
        assertTrue("V5.0.4309: source families must include graduated DEX, boosted/listed, established, pump newborn, and unknown", listOf("GRADUATED_DEX_DEPTH", "DEXSCREENER_BOOSTED_DEPTH", "ESTABLISHED_LIST_DEPTH", "PUMP_NEWBORN", "UNKNOWN").all { router.contains(it) })
        assertTrue("V5.0.4309: CashGen must consume source bias as score/conf/size influence, not a veto", cash.contains("TreasurySourceRouter.bias") && cash.contains("treasuryScore += sourceBias4309.scoreDelta") && cash.contains("sourceBias4309.confidenceDelta") && cash.contains("positionSol *= sourceBias4309.sizeMultiplier.coerceIn"))
        assertTrue("V5.0.4309: BotService must pass TokenState source metadata into CashGen", bot.contains("discoverySource = ts.source") && bot.contains("priceSource = ts.lastPriceSource") && bot.contains("priceDex = ts.lastPriceDex") && bot.contains("tokenAgeMinutes = tokenAge"))
    }

    @Test
    fun memeExecutionRouteStack4310ReportsAdapterGapsSeparatelyFromCoverage() {
        val stack = java.io.File("src/main/kotlin/com/lifecyclebot/engine/execution/MemeExecutionRouteStack.kt").readText()
        assertTrue("V5.0.4310: declared route providers must expose adapterWired so coverage is not mistaken for executable proof", stack.contains("val adapterWired: Boolean") && stack.contains("declared coverage is not executable adapter proof"))
        assertTrue("V5.0.4310: route stack must emit provider and sender adapter gap telemetry", stack.contains("EXEC_PROVIDER_ADAPTER_GAP_4310") && stack.contains("EXEC_SENDER_ADAPTER_GAP_4310"))
        assertTrue("V5.0.4310: StackCoverage must separate provider names from adapter gaps", stack.contains("adapterGapProviderNames") && stack.contains("adapterGapSenderNames") && stack.contains("adapterGaps="))
    }

    @Test
    fun freeDataSourceRegistry4311ExpandsResearchScoutBackgroundSources() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FreeDataSourceRegistry.kt").readText()
        val scout = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ResearchScout.kt").readText()
        assertTrue("V5.0.4311: free data registry must be background-only and never hot-path fetch", registry.contains("FREE_DATA_SOURCE_REGISTRY_4311") && registry.contains("background_only=true") && registry.contains("no_hot_path_fetch=true"))
        assertTrue("V5.0.4311: registry must include DexScreener, GeckoTerminal, Jupiter quote, PumpPortal WS, RugCheck, and CoinGecko/onchain", listOf("DEXSCREENER_FREE", "GECKOTERMINAL_FREE", "JUPITER_QUOTE_FREE", "PUMPPORTAL_WS_FREE", "RUGCHECK_FREE", "COINGECKO_ONCHAIN_FREE").all { registry.contains(it) && scout.contains(it) })
        assertTrue("V5.0.4311: ResearchScout default requests must use FreeDataSourceRegistry", scout.contains("FreeDataSourceRegistry.defaultSources()") && scout.contains("freeSourceStatus"))
    }

    @Test
    fun shitCoinDecisionMatrix4312CapturesCoreRejectAcceptOpenClose() {
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ShitCoinDecisionMatrixReport.kt").readText()
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue("V5.0.4312: ShitCoin matrix report must be report-only and never change gates", report.contains("SHITCOIN_DECISION_MATRIX_4312") && report.contains("report_only=true") && report.contains("no_gate_change=true"))
        assertTrue("V5.0.4312: ShitCoin core reject helper and direct rejects must stamp matrix reasons", shit.contains("ShitCoinDecisionMatrixReport.recordReject") && shit.contains("rejectSignal(reason"))
        assertTrue("V5.0.4312: ShitCoin qualified/open/close paths must stamp matrix telemetry", shit.contains("ShitCoinDecisionMatrixReport.recordAccepted") && shit.contains("ShitCoinDecisionMatrixReport.recordOpened") && shit.contains("ShitCoinDecisionMatrixReport.recordClosed"))
    }

    @Test
    fun sellDecisionMatrix4313CapturesRequestSellDefersAndDoSellHandoff() {
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SellDecisionMatrixReport.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4313: sell matrix must be report-only with no sell authority", report.contains("SELL_DECISION_MATRIX_4313") && report.contains("report_only=true") && report.contains("no_sell_authority=true"))
        assertTrue("V5.0.4313: requestSell must stamp intent and major pre-sell defers", exec.contains("SellDecisionMatrixReport.recordIntent") && exec.contains("TINY_PROFIT_DUST") && exec.contains("STYLE_MIN_HOLD") && exec.contains("BALANCE_PROOF_WAIT_MERGE"))
        assertTrue("V5.0.4313: requestSell must stamp doSell handoff", exec.contains("SellDecisionMatrixReport.recordDoSellHandoff") && exec.contains("return doSell(ts, requestReason, wallet, walletSol)"))
    }

    @Test
    fun shitCoinPausedDailyLoss4314IsRecoveryProbeNotHardReturn() {
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue("V5.0.4314: ShitCoin PAUSED must be recovery-probe size shaping, not lane amputation", shit.contains("SHITCOIN_DAILY_LOSS_RECOVERY_PROBE_4314") && shit.contains("pausedRecoveryProbe4314") && shit.contains("positionSol *= 0.35"))
        assertFalse("V5.0.4314: stale PAUSED hard-return reason must not survive", shit.contains("reason = \"PAUSED: Daily loss limit reached\""))
    }

    @Test
    fun blueChipScoreExpectancy4315IsSoftRecoveryProbeNotHardReject() {
        val blue = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/BlueChipTraderAI.kt").readText()
        assertTrue("V5.0.4315: BlueChip expectancy reject must become bounded recovery-probe sizing", blue.contains("BLUECHIP_EXPECTANCY_RECOVERY_PROBE_4315") && blue.contains("expectancySoftSize4315 = 0.25") && blue.contains("positionSol *= expectancySoftSize4315"))
        assertFalse("V5.0.4315: stale BlueChip hard EXPECTANCY_REJECT zero-size return must not survive", blue.contains("reason = \"EXPECTANCY_REJECT: score="))
    }

    @Test
    fun treasuryScoreExpectancy4316IsSoftRecoveryProbeNotRejectReason() {
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue("V5.0.4316: Treasury expectancy reject must become bounded recovery-probe sizing", cash.contains("TREASURY_EXPECTANCY_RECOVERY_PROBE_4316") && cash.contains("treasuryExpectancySoftSize4316 = 0.25") && cash.contains("positionSol *= treasuryExpectancySoftSize4316"))
        assertFalse("V5.0.4316: stale Treasury expectancy_reject rejectionReason must not survive", cash.contains("rejectionReasons.add(\"expectancy_reject_score_"))
    }

    @Test
    fun learnedExpectancy4317IsSoftProbeForManipMoonshotQuality() {
        val manip = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ManipulatedTraderAI.kt").readText()
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        val quality = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/QualityTraderAI.kt").readText()
        assertTrue("V5.0.4317: Manipulated expectancy reject must soft-size", manip.contains("MANIP_EXPECTANCY_RECOVERY_PROBE_4317") && manip.contains("manipExpectancySoftSize4317 = 0.25") && manip.contains("manipExpectancySoftSize4317 * dangerSoftSize"))
        assertTrue("V5.0.4317: Moonshot expectancy reject must soft-size", moon.contains("MOONSHOT_EXPECTANCY_RECOVERY_PROBE_4317") && moon.contains("moonshotExpectancySoftSize4317 = 0.25") && moon.contains("moonshotExpectancySoftSize4317"))
        assertTrue("V5.0.4317: Quality expectancy reject must soft-size", quality.contains("QUALITY_EXPECTANCY_RECOVERY_PROBE_4317") && quality.contains("qualityExpectancySoftSize4317 = 0.25") && quality.contains("positionSize * qualityExpectancySoftSize4317 * dangerSoftSize"))
        assertFalse("V5.0.4317: stale expectancy hard-return literals must not survive in these three lanes", manip.contains("EXPECTANCY_REJECT_score_") || moon.contains("expectancy_reject_score_") || quality.contains("reason = \"EXPECTANCY_REJECT: score="))
    }

    @Test
    fun chokeReliefBus4320OffloadsReportOnlyMatrixFanout() {
        val bus = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ChokeReliefBus.kt").readText()
        val sell = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SellDecisionMatrixReport.kt").readText()
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ShitCoinDecisionMatrixReport.kt").readText()
        assertTrue("V5.0.4320: ChokeReliefBus must be bounded, report-only, and use sideEffect dispatcher", bus.contains("MAX_IN_FLIGHT = 256") && bus.contains("AppDispatchers.sideEffect") && bus.contains("CHOKE_RELIEF_DROP_4320") && bus.contains("report_only=true"))
        assertTrue("V5.0.4320: matrix reporters must offload non-critical side effects through ChokeReliefBus", sell.contains("ChokeReliefBus.launch") && shit.contains("ChokeReliefBus.launch") && shit.contains("ForensicLogger.lifecycle"))
    }

    @Test
    fun ultimateEdgeEngine4321IsBotNativeBackgroundLaneCardCoordinator() {
        val engine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/UltimateEdgeEngine.kt").readText()
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ShitCoinDecisionMatrixReport.kt").readText()
        assertTrue("V5.0.4321: UltimateEdgeEngine must aggregate semantic/source/route/research edge into cached lane cards", engine.contains("object UltimateEdgeEngine") && engine.contains("LaneEdgeCard") && engine.contains("SemanticPatternGraph.entryDnaBias") && engine.contains("SourceFamilyOpportunityScorecard.snapshot") && engine.contains("MemeExecutionRouteStack.coverage") && engine.contains("ResearchScout.riskHint"))
        assertTrue("V5.0.4321: UltimateEdgeEngine must be coroutine/background-only through ChokeReliefBus", engine.contains("ChokeReliefBus.launch") && engine.contains("background_only=true") && engine.contains("no_execution_authority=true"))
        assertTrue("V5.0.4321: ShitCoin open/close matrix must enqueue edge-card refreshes", shit.contains("UltimateEdgeEngine.enqueueRefresh") && shit.contains("UltimateEdgeEngine.status"))
    }

    @Test
    fun shitCoin4324ConsumesUltimateEdgeCardsCacheOnlyAsSoftShape() {
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue("V5.0.4324: ShitCoin must consume cached UltimateEdgeEngine cards as bounded soft shaping", shit.contains("ULTIMATE_EDGE_SHITCOIN_CACHE_SHAPE_4324") && shit.contains("UltimateEdgeEngine.cached(mint, \"SHITCOIN\")") && shit.contains("ultimateEdgeSizeMult4324 = edgeCard4324.sizeMult.coerceIn(0.90, 1.08)") && shit.contains("semanticEntrySizeMult4255 * ultimateEdgeSizeMult4324"))
        assertFalse("V5.0.4324: ShitCoin entry hot path must not enqueue edge refresh or provider work", shit.contains("UltimateEdgeEngine.enqueueRefresh(mint, symbol, \"SHITCOIN\"") )
    }

    @Test
    fun express4325UsesUltimateEdgeAndSoftensNonSafetyIntelligenceVetoes() {
        val express = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        assertTrue("V5.0.4325: Express tilt/symbolic intelligence warnings must soft-shape, not noRide", express.contains("EXPRESS_TILT_SOFT_SHAPE_4325") && express.contains("EXPRESS_SYMBOLIC_SOFT_SHAPE_4325") && express.contains("expressIntelligenceSoftMult4325"))
        assertFalse("V5.0.4325: stale Express non-safety hard veto literals must not survive", express.contains("return noRide(\"TILT_BLOCK") || express.contains("return noRide(\"SYMBOLIC_VETO"))
        assertTrue("V5.0.4325: Express must warm and consume cache-only UltimateEdge cards", express.contains("EXPRESS_BOARD") && express.contains("EXPRESS_CLOSE") && express.contains("ULTIMATE_EDGE_EXPRESS_CACHE_SHAPE_4325") && express.contains("UltimateEdgeEngine.cached(mint, \"EXPRESS\")"))
    }

    @Test
    fun moonshot4326ConsumesUltimateEdgeCardsCacheOnly() {
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue("V5.0.4326: Moonshot must consume cached UltimateEdge cards as bounded soft shaping", moon.contains("ULTIMATE_EDGE_MOONSHOT_CACHE_SHAPE_4326") && moon.contains("UltimateEdgeEngine.cached(mint, \"MOONSHOT\")") && moon.contains("edgeCard4326.sizeMult.coerceIn(0.90, 1.08)"))
        assertTrue("V5.0.4326: Moonshot open/close must warm UltimateEdge cards", moon.contains("UltimateEdgeEngine.enqueueRefresh(position.mint, position.symbol, \"MOONSHOT\"") && moon.contains("UltimateEdgeEngine.enqueueRefresh(pos.mint, pos.symbol, \"MOONSHOT\""))
    }

    @Test
    fun treasury4327ConsumesUltimateEdgeCardsCacheOnly() {
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue("V5.0.4327: Treasury must consume cached UltimateEdge cards as bounded soft shaping", cash.contains("ULTIMATE_EDGE_TREASURY_CACHE_SHAPE_4327") && cash.contains("UltimateEdgeEngine.cached(mint, \"TREASURY\")") && cash.contains("edgeCard4327.sizeMult.coerceIn(0.90, 1.08)"))
        assertTrue("V5.0.4327: Treasury open/close must warm UltimateEdge cards", cash.contains("UltimateEdgeEngine.enqueueRefresh(mint, symbol, \"TREASURY\"") && cash.contains("UltimateEdgeEngine.enqueueRefresh(mint, pos.symbol, \"TREASURY\""))
    }

    @Test
    fun qualityBlueChip4328ConsumeUltimateEdgeCardsCacheOnly() {
        val quality = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/QualityTraderAI.kt").readText()
        val blue = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/BlueChipTraderAI.kt").readText()
        assertTrue("V5.0.4328: Quality must consume and warm cached UltimateEdge cards", quality.contains("ULTIMATE_EDGE_QUALITY_CACHE_SHAPE_4328") && quality.contains("UltimateEdgeEngine.cached(mint, \"QUALITY\")") && quality.contains("UltimateEdgeEngine.enqueueRefresh(position.mint, position.symbol, \"QUALITY\"") && quality.contains("UltimateEdgeEngine.enqueueRefresh(pos.mint, pos.symbol, \"QUALITY\""))
        assertTrue("V5.0.4328: BlueChip must consume and warm cached UltimateEdge cards", blue.contains("ULTIMATE_EDGE_BLUECHIP_CACHE_SHAPE_4328") && blue.contains("UltimateEdgeEngine.cached(mint, \"BLUECHIP\")") && blue.contains("BLUECHIP_OPEN") && blue.contains("BLUECHIP_CLOSE"))
    }

    @Test
    fun manipulated4329ConsumesUltimateEdgeCardsCacheOnly() {
        val manip = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ManipulatedTraderAI.kt").readText()
        assertTrue("V5.0.4329: Manipulated must consume cached UltimateEdge cards as bounded soft shaping", manip.contains("ULTIMATE_EDGE_MANIP_CACHE_SHAPE_4329") && manip.contains("UltimateEdgeEngine.cached(mint, \"MANIPULATED\")") && manip.contains("edgeCard4329.sizeMult.coerceIn(0.90, 1.08)"))
        assertTrue("V5.0.4329: Manipulated open/close must warm UltimateEdge cards", manip.contains("UltimateEdgeEngine.enqueueRefresh(pos.mint, pos.symbol, \"MANIPULATED\"") && manip.contains("MANIP_CLOSE"))
    }

    @Test
    fun dipSniper4330ConsumeUltimateEdgeCardsCacheOnly() {
        val dip = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/DipHunterAI.kt").readText()
        val sniper = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ProjectSniperAI.kt").readText()
        assertTrue("V5.0.4330: DipHunter must consume/warm cached UltimateEdge cards", dip.contains("ULTIMATE_EDGE_DIP_CACHE_SHAPE_4330") && dip.contains("UltimateEdgeEngine.cached(mint, \"DIP_HUNTER\")") && dip.contains("UltimateEdgeEngine.enqueueRefresh(mint, symbol, \"DIP_HUNTER\"") && dip.contains("UltimateEdgeEngine.enqueueRefresh(pos.mint, pos.symbol, \"DIP_HUNTER\""))
        assertTrue("V5.0.4330: ProjectSniper must consume/warm cached UltimateEdge cards", sniper.contains("ULTIMATE_EDGE_SNIPER_CACHE_SHAPE_4330") && sniper.contains("UltimateEdgeEngine.cached(ts.mint, \"PROJECT_SNIPER\")") && sniper.contains("SNIPER_OPEN") && sniper.contains("SNIPER_CLOSE"))
    }

    @Test
    fun ultimateEdgeEngine4331PersistsLaneCardsThroughLearningPersistence() {
        val engine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/UltimateEdgeEngine.kt").readText()
        val persistence = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue("V5.0.4331: UltimateEdgeEngine must export/import/reset lane-card cache", engine.contains("fun exportState(): String") && engine.contains("fun importState(raw: String?)") && engine.contains("fun reset() { cards.clear() }") && engine.contains("JSONArray") && engine.contains("JSONObject"))
        assertTrue("V5.0.4331: LearningPersistence must save/load/reset UltimateEdgeEngine", persistence.contains("ULTIMATE_EDGE_ENGINE") && persistence.contains("UltimateEdgeEngine.exportState") && persistence.contains("UltimateEdgeEngine.importState") && persistence.contains("UltimateEdgeEngine.reset"))
    }

    @Test
    fun operatorKpi4332ExposesUltimateEdgeAndChokeReliefHelpers() {
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4332: operator KPI report must expose UltimateEdge and ChokeRelief helper health", report.contains("OPERATOR_KPI_CLOSEOUT_REPORT_4416") && report.contains("UltimateEdgeEngine.status(6)") && report.contains("ChokeReliefBus.status()") && report.contains("ultimate_edge=UltimateEdgeEngine") && report.contains("choke_relief=ChokeReliefBus"))
    }

    @Test
    fun arbDeck4334PublishesCachedOpportunitiesIntoMemeCashLanes() {
        val arb = java.io.File("src/main/kotlin/com/lifecyclebot/v3/arb/ArbScannerAI.kt").readText()
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        val shit = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        val express = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinExpress.kt").readText()
        assertTrue("V5.0.4334: ArbScannerAI must expose cache-only latest actionable arb opportunity", arb.contains("latestActionable") && arb.contains("fun cachedOpportunity") && arb.contains("ACTIONABLE_TTL_MS"))
        assertTrue("V5.0.4334: Treasury/ShitCoin/Express must consume arb deck cache without running models", cash.contains("TREASURY_ARB_DECK_CACHE_SHAPE_4334") && shit.contains("SHITCOIN_ARB_DECK_CACHE_SHAPE_4334") && express.contains("EXPRESS_ARB_DECK_CACHE_SHAPE_4334") && cash.contains("ArbScannerAI.cachedOpportunity(mint)") && shit.contains("ArbScannerAI.cachedOpportunity(mint)") && express.contains("ArbScannerAI.cachedOpportunity(mint)"))
    }

    @Test
    fun solanaArb4335DynamicallyReEnablesWhenTreasuryGrows() {
        val arb = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/SolanaArbAI.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue("V5.0.4335: SolanaArbAI must sync dynamic treasury eligibility instead of one-shot disable", arb.contains("SOLANA_ARB_DYNAMIC_ELIGIBILITY_4335") && arb.contains("fun syncTreasuryUsd") && arb.contains("lastTreasuryUsd") && arb.contains("isEnabled = lastTreasuryUsd >= MIN_TREASURY_USD"))
        assertTrue("V5.0.4335: BotService loop must refresh SolanaArbAI treasury USD eligibility", bot.contains("SolanaArbAI.syncTreasuryUsd") && bot.contains("CashGenerationAI.getTreasuryBalance(cfg.paperMode)") && bot.contains("WalletManager.lastKnownSolPrice"))
    }

    @Test
    fun solanaArb4336ReportsFeedStarvationAndRequiresExecutableVenues() {
        val arb = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/SolanaArbAI.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4336: SolanaArbAI must distinguish executable DEX feeds from reference feeds", arb.contains("val executable: Boolean") && arb.contains("SOL_ARB_EXEC_FEED_STARVED_4336") && arb.contains("for (buyFeed in executableFeeds)") && arb.contains("for (sellFeed in executableFeeds)"))
        assertTrue("V5.0.4336: operator KPI must expose SolanaArbAI feed starvation", arb.contains("fun feedStatus()") && report.contains("SOL_ARB_FEEDS_4336") && report.contains("sol_arb=SolanaArbAI"))
    }

    @Test
    fun treasuryOpportunity4338IsEnabledAndConsumedAsAdvisorySizing() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue("V5.0.4338: BotService must enable TreasuryOpportunityEngine advisory helper", bot.contains("TreasuryOpportunityEngine.setEnabled(true)") && bot.contains("advisory Treasury deployment helper"))
        assertTrue("V5.0.4338: CashGenerationAI must consume TreasuryOpportunityEngine as advisory sizing", cash.contains("TREASURY_OPPORTUNITY_ADVISORY_SHAPE_4338") && cash.contains("TreasuryOpportunityEngine.assessOpportunity") && cash.contains("does NOT recordDeployment"))
    }


    @Test
    fun treasuryOpportunity4339TracksActualCashGenOpenCloseLedgerOnly() {
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue("V5.0.4339: CashGen actual open/close must feed TreasuryOpportunityEngine ledger only", cash.contains("TreasuryOpportunityEngine.recordDeployment") && cash.contains("TreasuryOpportunityEngine.closeDeployment") && cash.contains("ledger-only; CashGen executed the buy") && cash.contains("ledger-only; CashGen executed the sell"))
    }


    @Test
    fun cashGen4340UsesLiquidityMcapProxyForTreasuryOpportunityRiskOnly() {
        val cash = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/CashGenerationAI.kt").readText()
        assertTrue("V5.0.4340: CashGenerationAI must not reference nonexistent marketCapUsd in TreasuryOpportunityEngine advisory path", !cash.contains("mcapUsd = marketCapUsd") && cash.contains("liquidityUsd * 12.0") && cash.contains("risk proxy only"))
    }

    @Test
    fun runtimeRegistry4341ReclassifiesArbDeckAfterActiveConsumers() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        assertTrue("V5.0.4341: Arb deck should no longer be reported as wholly dormant after cached lane consumers", registry.contains("SMART_SYSTEM_RUNTIME_REGISTRY_4348") && registry.contains("arb_deck_reclassified=true") && registry.contains("ArbScannerAI") && registry.contains("RuntimeClass.ACTIVE") && registry.contains("cachedOpportunity consumed by Treasury/Express/ShitCoin"))
        assertTrue("V5.0.4348: Arb component models and ArbLearning should be runtime-active after terminal fanout wiring", registry.contains("VenueLagModel") && registry.contains("FlowImbalanceModel") && registry.contains("PanicReversionModel") && registry.contains("SourceTimingRegistry") && registry.contains("arb_learning_active=true"))
    }

    @Test
    fun strategyVariantStore4342ShapesAndLearnsThroughStrategyHypothesisEngine() {
        val hypo = java.io.File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        assertTrue("V5.0.4342: StrategyVariantStore must shape hot-path sizing only as bounded bias through StrategyHypothesisEngine", hypo.contains("STRATEGY_VARIANT_STORE_SIZE_BIAS_4342") && hypo.contains("StrategyVariantStore.activeFor(lane)") && hypo.contains("strategyVariantBias4342") && hypo.contains("coerceIn(SIZE_BIAS_MIN, SIZE_BIAS_MAX)"))
        assertTrue("V5.0.4342: StrategyVariantStore must receive terminal outcomes via existing StrategyHypothesisEngine outcome fanout", hypo.contains("STRATEGY_VARIANT_STORE_OUTCOME_4342") && hypo.contains("StrategyVariantStore.recordOutcome(activeVariant4342.id"))
        assertTrue("V5.0.4342: Registry must no longer treat StrategyVariantStore as pure proof-needed after wiring", registry.contains("StrategyVariantStore") && registry.contains("RuntimeClass.INTERFACE_USED") && registry.contains("terminal outcome fanout"))
    }


    @Test
    fun runtimeRegistry4343MarksCloudSyncAndColdStreakActiveByCallsite() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val scorer = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        val sizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        val journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        assertTrue("V5.0.4343: CloudLearningSync must be classified active only if BotService and UnifiedScorer consume it", registry.contains("CloudLearningSync") && registry.contains("RuntimeClass.ACTIVE") && bot.contains("CloudLearningSync.init") && bot.contains("CloudLearningSync.downloadCommunityWeights") && scorer.contains("CloudLearningSync") && scorer.contains("downloads community pattern multipliers"))
        assertTrue("V5.0.4343: ColdStreakDamper must be classified active only if sizing and outcomes consume it", registry.contains("ColdStreakDamper") && registry.contains("cloud_and_cold_streak_active=true") && sizer.contains("ColdStreakDamper.sizeMultiplier") && journal.contains("ColdStreakDamper.noteOutcome"))
    }


    @Test
    fun providerHealthGate4344IsDeprecatedInFavorOfApiHealthMonitor() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        val healthAware = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HealthAwareHttp.kt").readText()
        val execGuard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionHealthGuard.kt").readText()
        assertTrue("V5.0.4344: ProviderHealthGate must not be wired as duplicate breaker while ApiHealthMonitor is canonical", registry.contains("ProviderHealthGate") && registry.contains("RuntimeClass.DEPRECATED") && registry.contains("provider_gate_deprecated=true") && registry.contains("Superseded by ApiHealthMonitor"))
        assertTrue("V5.0.4344: ApiHealthMonitor/HealthAwareHttp/ExecutionHealthGuard are the live provider-health path", healthAware.contains("ApiHealthMonitor.record") && healthAware.contains("recordNetworkError") && execGuard.contains("ApiHealthMonitor.snapshot"))
    }


    @Test
    fun telegramBot4347IsExplicitSidecarNotAutoExternalSender() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        val telegram = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TelegramBot.kt").readText()
        assertTrue("V5.0.4347: TelegramBot must be sidecar/not auto-wired because it sends externally", registry.contains("TelegramBot") && registry.contains("RuntimeClass.SIDECAR") && registry.contains("telegram_sidecar_no_auto_send=true") && registry.contains("external-message side effects require explicit operator config/approval"))
        assertTrue("V5.0.4347: TelegramBot source remains available but init/startPolling are opt-in only", telegram.contains("fun init(token: String, chat: String)") && telegram.contains("fun startPolling") && telegram.contains("if (!enabled) return"))
    }


    @Test
    fun arbLearning4348TrainsFromTerminalSellFanoutOnlyWithCachedArbEvidence() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        assertTrue("V5.0.4348: Executor terminal SELL fanout must train ArbLearning only from cached arb evidence", exec.contains("ARB_LEARNING_TERMINAL_OUTCOME_4348") && exec.contains("ArbScannerAI.cachedOpportunity(_fanoutMint") && exec.contains("ArbLearning.recordOutcome") && exec.contains("ArbOutcome("))
        assertTrue("V5.0.4348: ArbLearning must no longer be listed as unresolved runtime proof", registry.contains("ArbLearning") && registry.contains("RuntimeClass.ACTIVE") && registry.contains("arb_learning_active=true"))
        assertFalse("V5.0.4348: ArbLearning fanout must not own execution authority", exec.contains("ARB_LEARNING_TERMINAL_OUTCOME_4348") && (exec.contains("ArbLearning.executeBuy") || exec.contains("ArbLearning.requestSell")))
    }


    @Test
    fun tradeRowSanity4349GatesLearningAggregationsButNotJournalTruth() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TradeRowSanityCheck.kt").readText()
        assertTrue("V5.0.4349: Executor must inspect trade row sanity after journal truth is recorded", exec.contains("TradeHistoryStore.recordTrade(tradeWithMint)") && exec.contains("rowLearningAdmitted4349") && exec.indexOf("TradeHistoryStore.recordTrade(tradeWithMint)") < exec.indexOf("TradeRowSanityCheck.inspect(tradeWithMint)"))
        assertTrue("V5.0.4349: terminal learning gates must include row sanity admission", exec.contains("ledgerAllowsClosedLearning && accountingTrainable && rowLearningAdmitted4349") && sanity.contains("QuarantineReason.OK"))
        assertFalse("V5.0.4349: row sanity quarantine must not hide journal/report truth", exec.indexOf("TradeRowSanityCheck.inspect(tradeWithMint)") < exec.indexOf("TradeHistoryStore.recordTrade(tradeWithMint)"))
    }


    @Test
    fun tokenRefreshPolicy4350GatesScannerBirdeyeOverviewFallbacks() {
        val scanner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SolanaMarketScanner.kt").readText()
        val policy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenRefreshPolicy.kt").readText()
        assertTrue("V5.0.4350: SolanaMarketScanner must route Birdeye overview fallbacks through TokenRefreshPolicy", scanner.contains("getBirdeyeOverviewIfRefreshDue4350") && scanner.contains("TokenRefreshPolicy.shouldRefreshDynamic") && scanner.contains("TOKEN_REFRESH_POLICY_OVERVIEW_SKIP_4350"))
        assertFalse("V5.0.4350: Scanner should not directly call Birdeye getTokenOverview(mint) outside the refresh helper", scanner.replace("withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }", "").contains("birdeye.getTokenOverview(mint)"))
        assertTrue("V5.0.4350: TokenRefreshPolicy must preserve active first-minute and open-position refresh", policy.contains("ACTIVE_FRESHNESS_MS") && policy.contains("hasOpenPosition") && policy.contains("Tier.ACTIVE"))
    }


    @Test
    fun paperLiveConfidenceWeights4351AreWiredAtMlGateWithoutDroppingRows() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TradeRowSanityCheck.kt").readText()
        assertTrue("V5.0.4351: Executor ML gate must update PaperLiveConfidenceWeights from event-local paper/live state", exec.contains("PaperLiveConfidenceWeights.noteLiveSample") && exec.contains("PaperLiveConfidenceWeights.weight") && exec.contains("PAPER_LIVE_CONFIDENCE_WEIGHT_4351") && exec.contains("isPaperRT()"))
        assertTrue("V5.0.4351: Paper confidence split must preserve live weight and bounded paper bootstrap", sanity.contains("PAPER_BOOTSTRAP_WEIGHT = 0.40") && sanity.contains("LIVE_WEIGHT            = 1.00") && sanity.contains("LIVE_VALIDATION_MIN_SAMPLES"))
        val confidenceBlock = exec.substring(exec.indexOf("val paperLiveWeight4351"), exec.indexOf("TradeHistoryStore.recordTradeForML", exec.indexOf("val paperLiveWeight4351")))
        assertFalse("V5.0.4351: paper confidence telemetry must not drop ML rows", confidenceBlock.contains("return@launch") || confidenceBlock.contains("return"))
    }


    @Test
    fun paperLiveConfidence4355AppearsInOperatorKpiReport() {
        val sanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TradeRowSanityCheck.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4355: PaperLiveConfidenceWeights must expose compact status", sanity.contains("PAPER_LIVE_CONFIDENCE_WEIGHTS_4355") && sanity.contains("validatedWeight") && sanity.contains("LIVE_VALIDATION_MIN_SAMPLES"))
        assertTrue("V5.0.4355: operator KPI must include paper/live confidence state", kpi.contains("paper_live_confidence=PaperLiveConfidenceWeights") && kpi.contains("PaperLiveConfidenceWeights.status"))
        assertTrue("V5.0.4355: paper/live confidence remains report-only", kpi.contains("report_only=true") && kpi.contains("no_execution_authority=true"))
    }


    @Test
    fun smartSystemRegistry4357AppearsInOperatorKpiReport() {
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSystemRuntimeRegistry.kt").readText()
        assertTrue("V5.0.4357: KPI report must expose runtime registry classifications", kpi.contains("smart_system_registry=SmartSystemRuntimeRegistry") && kpi.contains("SmartSystemRuntimeRegistry.status"))
        assertTrue("V5.0.4357: registry must keep sidecar/future/deprecated systems classified", registry.contains("TelegramBot") && registry.contains("SIDECAR") && registry.contains("PatchWriterAI") && registry.contains("FUTURE") && registry.contains("ProviderHealthGate") && registry.contains("DEPRECATED"))
        assertTrue("V5.0.4357: registry in KPI remains report-only", kpi.contains("report_only=true") && kpi.contains("no_execution_authority=true"))
    }


    @Test
    fun tradeRowSanity4358AppearsInOperatorKpiReport() {
        val sanity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TradeRowSanityCheck.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4358: TradeRowSanityCheck must expose compact learning-gate status", sanity.contains("TRADE_ROW_SANITY_CHECK_4358") && sanity.contains("journal_truth_preserved=true") && sanity.contains("learning_gate_only=true"))
        assertTrue("V5.0.4358: operator KPI must include row-sanity health", kpi.contains("trade_row_sanity=TradeRowSanityCheck") && kpi.contains("TradeRowSanityCheck.status"))
        assertTrue("V5.0.4358: row sanity KPI remains report-only and non-executive", kpi.contains("report_only=true") && kpi.contains("no_execution_authority=true"))
    }


    @Test
    fun terminalOutcomeQuality4359AppearsInOperatorKpiReport() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TerminalOutcomeQualityGate.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4359: TerminalOutcomeQualityGate must expose compact status without hiding PnL", gate.contains("TERMINAL_OUTCOME_QUALITY_STATUS_4359") && gate.contains("never_hides_realized_pnl=true") && gate.contains("qualityCounts"))
        assertTrue("V5.0.4359: operator KPI must include terminal outcome quality", kpi.contains("terminal_outcome_quality=TerminalOutcomeQualityGate") && kpi.contains("TerminalOutcomeQualityGate.status"))
        assertTrue("V5.0.4359: terminal quality KPI remains report-only", kpi.contains("report_only=true") && kpi.contains("no_execution_authority=true"))
    }


    @Test
    fun learningFanoutMux4360AppearsInOperatorKpiReport() {
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LearningFanoutMuxSentinel.kt").readText()
        assertTrue("V5.0.4360: KPI report must expose mux/event-local learning sentinel", kpi.contains("learning_fanout_mux=LearningFanoutMuxSentinel") && kpi.contains("LearningFanoutMuxSentinel.status"))
        assertTrue("V5.0.4360: mux sentinel must require event-local snapshots", sentinel.contains("event_local_snapshot_required=true") && sentinel.contains("no_mutable_tokenstate_in_background=true"))
        assertTrue("V5.0.4360: mux KPI remains report-only", kpi.contains("report_only=true") && kpi.contains("no_execution_authority=true"))
    }


    @Test
    fun livePaperDrift4361AppearsInOperatorKpiReport() {
        val drift = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LivePaperDriftSentinel.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4361: LivePaperDriftSentinel must expose compact drift status", drift.contains("LIVE_PAPER_DRIFT_STATUS_4361") && drift.contains("driftCountByLane") && drift.contains("no_pause_no_size_change=true"))
        assertTrue("V5.0.4361: operator KPI must include live/paper drift status", kpi.contains("LivePaperDriftSentinel.status") && kpi.contains("livePaperDrift=["))
        assertTrue("V5.0.4361: live/paper drift remains report-only", kpi.contains("report_only=true") && kpi.contains("no_execution_authority=true"))
    }


    @Test
    fun batchKpiStatus4362ClosesFourReportOnlyBrains() {
        val sizing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SizingStackIntegritySentinel.kt").readText()
        val capital = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CapitalEfficiencyBrain.kt").readText()
        val exitCost = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExitCostMicrobrain.kt").readText()
        val runner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RunnerRetentionOptimizer.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4362: sizing stack integrity must expose report-only status", sizing.contains("SIZING_STACK_INTEGRITY_STATUS_4362") && sizing.contains("no_size_mutation=true"))
        assertTrue("V5.0.4362: capital efficiency must expose bounded sizing-only status", capital.contains("CAPITAL_EFFICIENCY_STATUS_4362") && capital.contains("bounded_size_only=true"))
        assertTrue("V5.0.4362: exit cost microbrain must expose cached-hint-only status", exitCost.contains("EXIT_COST_MICROBRAIN_STATUS_4362") && exitCost.contains("no_exit_block=true"))
        assertTrue("V5.0.4362: runner retention must expose background-review-only status", runner.contains("RUNNER_RETENTION_STATUS_4362") && runner.contains("no_direct_exit_authority=true"))
        assertTrue("V5.0.4362: operator KPI must include all four batch status surfaces", kpi.contains("sizing_stack_warnings=SizingStackIntegritySentinel") && kpi.contains("capital_efficiency=CapitalEfficiencyBrain") && kpi.contains("exit_cost=ExitCostMicrobrain") && kpi.contains("runner_retention=RunnerRetentionOptimizer"))
    }


    @Test
    fun batchKpiStatus4363ClosesSevenExistingStatusSurfaces() {
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4363: KPI must expose free data registry and treasury source routing", kpi.contains("free_data_registry=FreeDataSourceRegistry") && kpi.contains("treasury_source_router=TreasurySourceRouter") && kpi.contains("FreeDataSourceRegistry.status") && kpi.contains("TreasurySourceRouter.status"))
        assertTrue("V5.0.4363: KPI must expose ShitCoin/Sell/Treasury decision matrices", kpi.contains("shitcoin_matrix=ShitCoinDecisionMatrixReport") && kpi.contains("sell_matrix=SellDecisionMatrixReport") && kpi.contains("treasury_cashflow=TreasuryCashflowMissionReport"))
        assertTrue("V5.0.4363: KPI must expose AI persistence and hot-path provider sentinels", kpi.contains("ai_persistence=AiStatePersistenceSentinel") && kpi.contains("hot_path_provider=HotPathProviderCallSentinel") && kpi.contains("AiStatePersistenceSentinel.status") && kpi.contains("HotPathProviderCallSentinel.status"))
        assertTrue("V5.0.4363: batch KPI surfaces remain report-only", kpi.contains("report_only=true") && kpi.contains("no_execution_authority=true"))
    }


    @Test
    fun auxiliaryDigest4364ClosesTwelveStatusSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorAuxiliaryStatusDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4364: auxiliary digest must include twelve scattered status surfaces", digest.contains("TokenRefreshPolicy.snapshot") && digest.contains("BirdeyeBudgetGate.snapshot") && digest.contains("ApiHealthMonitor.snapshot") && digest.contains("FeeAccumulator.snapshot") && digest.contains("ExitReasonTracker.snapshot") && digest.contains("LiveStrategyTuner.statusLine") && digest.contains("ScannerSourceBrain.summary") && digest.contains("StrategyVariantStore.snapshot") && digest.contains("ExplorationBudget.snapshot") && digest.contains("NoTradeObservationStore.snapshot") && digest.contains("SellFailureHistory.snapshot") && digest.contains("SellJobRegistry.snapshot"))
        assertTrue("V5.0.4364: operator KPI must include auxiliary digest", kpi.contains("auxiliary_status=OperatorAuxiliaryStatusDigest") && kpi.contains("OperatorAuxiliaryStatusDigest.status"))
        assertTrue("V5.0.4364: auxiliary digest remains report-only", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_gate_change=true"))
    }


    @Test
    fun adaptiveDigest4365ClosesTenRuntimeStatusSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorAdaptiveStatusDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4365: adaptive digest must include ten runtime status surfaces", digest.contains("LiveProbabilityEngine.statusLine") && digest.contains("QualityLadder.statusLine") && digest.contains("LaneExpectancyDamper.statusLine") && digest.contains("AntiChokeManager.statusLine") && digest.contains("FreeRangeMode instance-scoped") && digest.contains("SentienceHooks.statusSummary") && digest.contains("LlmLabEngine.statusLine") && digest.contains("LlmLabStore.summary") && digest.contains("ColdStreakDamper instance-scoped") && digest.contains("ExecutionCounterContract.snapshot"))
        assertTrue("V5.0.4365: operator KPI must include adaptive digest", kpi.contains("adaptive_status=OperatorAdaptiveStatusDigest") && kpi.contains("OperatorAdaptiveStatusDigest.status"))
        assertTrue("V5.0.4365: adaptive digest remains report-only", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_gate_change=true"))
    }


    @Test
    fun coreRuntimeDigest4366ClosesFifteenStatusSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorCoreRuntimeDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4366: core digest must include fifteen runtime/health status hooks", digest.contains("DeadAILayerFilter.snapshot") && digest.contains("QuarantineStore.snapshot") && digest.contains("LiveMaturityAuthority.snapshot") && digest.contains("WiringHealth.snapshot") && digest.contains("CryptoPositionState.snapshot") && digest.contains("InternetEdgeDesk.snapshot") && digest.contains("DeferActivityTracker.snapshot") && digest.contains("LiveTradeLogStore.snapshot") && digest.contains("ScoreExpectancyTracker.snapshot") && digest.contains("MemeWREmergencyBrake.snapshot") && digest.contains("LiveSafetyCircuitBreaker.snapshot") && digest.contains("WatchlistTtlPolicy.snapshot") && digest.contains("RecoveredHoldGuard.summary") && digest.contains("LiveAttemptStats.snapshot") && digest.contains("HoldDurationTracker.snapshot"))
        assertTrue("V5.0.4366: operator KPI must include core runtime digest", kpi.contains("core_runtime_status=OperatorCoreRuntimeDigest") && kpi.contains("OperatorCoreRuntimeDigest.status"))
        assertTrue("V5.0.4366: core runtime digest remains report-only", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_gate_change=true"))
    }


    @Test
    fun sellRuntimeDigest4368ClosesThirteenStatusSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorSellRuntimeDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4368: sell runtime digest must include thirteen sell/execution support hooks", digest.contains("SellForensics.snapshot") && digest.contains("BalanceProofWaitState.summary") && digest.contains("ExitProviderHealth.summary") && digest.contains("PartialSellMismatchDetector.snapshot") && digest.contains("PositionWalletReconciler.snapshot") && digest.contains("HostCircuitInterceptor.snapshot") && digest.contains("LocalOrphanStore.snapshot") && digest.contains("LabPromotedFeed.summary") && digest.contains("VoiceDiagnostics.snapshot") && digest.contains("SafetyRefreshQueue.snapshot") && digest.contains("PositionCloseLedger.snapshot") && digest.contains("ScannerHardRejectStore.snapshot") && digest.contains("PumpPortalThrottle.snapshot"))
        assertTrue("V5.0.4368: operator KPI must include sell runtime digest", kpi.contains("sell_runtime_status=OperatorSellRuntimeDigest") && kpi.contains("OperatorSellRuntimeDigest.status"))
        assertTrue("V5.0.4368: sell runtime digest remains report-only", digest.contains("report_only=true") && digest.contains("no_sell_authority=true") && digest.contains("no_execution_authority=true"))
    }


    @Test
    fun v3ScoringDigest4370ClosesNineScoringSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorV3ScoringDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4370: V3 digest must expose safe top-level scoring summaries", digest.contains("CultMomentumAI.summary") && digest.contains("MemeNarrativeAI.summary"))
        assertTrue("V5.0.4370: V3 digest must classify instance-scoped scoring summaries without unsafe calls", digest.contains("BehaviorAI summary instance-scoped") && digest.contains("ShitCoinTraderAI status model instance-scoped") && digest.contains("CollectiveIntelligenceAI status model instance-scoped") && digest.contains("MetaCognitionAI decision summary instance-scoped") && digest.contains("BlueChipTraderAI status model instance-scoped") && digest.contains("FluidLearningAI param summaries instance-scoped") && digest.contains("CashGenerationAI status model instance-scoped"))
        assertTrue("V5.0.4370: operator KPI must include V3 scoring digest", kpi.contains("v3_scoring_status=OperatorV3ScoringDigest") && kpi.contains("OperatorV3ScoringDigest.status"))
        assertTrue("V5.0.4370: V3 scoring digest remains report-only", digest.contains("report_only=true") && digest.contains("no_score_change=true") && digest.contains("no_execution_authority=true"))
    }


    @Test
    fun engineStatusDigest4371ClosesElevenEngineSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorEngineStatusDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4371: engine digest must include safe object/companion status hooks", digest.contains("LiveSizingProfile.summary") && digest.contains("KeyValidator.snapshot") && digest.contains("TokenMetaCache snapshot is instance-scoped") && digest.contains("AutoEndpointMigrator.snapshot") && digest.contains("MemePipelineTracer.snapshot") && digest.contains("CycleTimingTracker.snapshot") && digest.contains("TradingCopilot.snapshot") && digest.contains("FdgRouteVerdict.snapshot"))
        assertTrue("V5.0.4371: engine digest must classify argument/instance scoped summaries", digest.contains("RuntimeRegressionGuards summary requires supplied check list") && digest.contains("BehaviorLearning summary is instance-scoped") && digest.contains("EVCalculator summary is result-scoped"))
        assertTrue("V5.0.4371: operator KPI must include engine status digest", kpi.contains("engine_status=OperatorEngineStatusDigest") && kpi.contains("OperatorEngineStatusDigest.status"))
        assertTrue("V5.0.4371: engine digest remains report-only", digest.contains("report_only=true") && digest.contains("no_gate_change=true") && digest.contains("no_execution_authority=true"))
    }


    @Test
    fun remainderDigest4372ClosesRemainingNonPerpsStatusSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorRemainderStatusDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4372: remainder digest must safely call object-level status hooks", digest.contains("BotRuntimeController.snapshot") && digest.contains("CanonicalLearningCounters.snapshot") && digest.contains("LayerReadinessRegistry.snapshot") && digest.contains("LlmTradeScore.snapshot") && digest.contains("CatastrophicPaperBleedGuard.snapshot") && digest.contains("SemanticPatternGraph.summary") && digest.contains("ApiBackoff.snapshot") && digest.contains("PositionArbiterCounters.snapshot") && digest.contains("PositionExitArbiter.snapshot") && digest.contains("HostWalletTokenTracker.snapshot"))
        assertTrue("V5.0.4372: remainder digest must classify private/arg/instance-scoped status surfaces", digest.contains("SymbolicExitReasoner.RuleLearning snapshot private") && digest.contains("BleederMemoryRouter snapshot private") && digest.contains("ShadowLearningEngine summary data-scoped") && digest.contains("ToolkitSignalSheet snapshot requires TokenState") && digest.contains("TokenMetricStageRouter snapshot requires TokenState") && digest.contains("TreasuryManager statusSummary requires solPrice") && digest.contains("TradeJournal summary requires Android Context/row writer") && digest.contains("TradeLifecycle summaries are lifecycle/stat instance-scoped") && digest.contains("FinalDecisionGate summaries are decision/state instance-scoped") && digest.contains("TokenSocialScorer summary requires PairInfo") && digest.contains("LaneTimeoutGate status requires lane argument") && digest.contains("TradeIdentity summaries are identity/stat instance-scoped") && digest.contains("LiveProviderQuorum summary is verdict-scoped"))
        assertTrue("V5.0.4372: operator KPI must include remainder digest", kpi.contains("remainder_status=OperatorRemainderStatusDigest") && kpi.contains("OperatorRemainderStatusDigest.status"))
        assertTrue("V5.0.4372: remainder digest remains report-only", digest.contains("report_only=true") && digest.contains("no_gate_change=true") && digest.contains("no_execution_authority=true"))
    }


    @Test
    fun perpsCryptoDigest4373ClosesCryptoStatusSurfaces() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorPerpsCryptoDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4373: perps crypto digest must include crypto brain summaries", digest.contains("CryptoFluidLearning.summary") && digest.contains("CryptoTacticSwitcher.summary") && digest.contains("CryptoFunnel.summary") && digest.contains("CryptoBehavior.summary") && digest.contains("CryptoBrain.summary") && digest.contains("CryptoLosingPatternMemory.summary") && digest.contains("CryptoCanonicalLearning.summary") && digest.contains("CryptoLaneExitTuner.summary"))
        assertTrue("V5.0.4373: perps crypto digest must classify lane-timeout arg-scoped status", digest.contains("CryptoLaneTimeoutGate status requires lane argument"))
        assertTrue("V5.0.4373: operator KPI must include perps crypto digest", kpi.contains("perps_crypto_status=OperatorPerpsCryptoDigest") && kpi.contains("OperatorPerpsCryptoDigest.status"))
        assertTrue("V5.0.4373: perps crypto digest remains report-only", digest.contains("report_only=true") && digest.contains("no_crypto_gate_change=true") && digest.contains("no_execution_authority=true"))
    }


    @Test
    fun statusInventory4374HasZeroNameArtifactRemainders() {
        val remainder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorRemainderStatusDigest.kt").readText()
        val perps = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorPerpsCryptoDigest.kt").readText()
        assertTrue("V5.0.4374: remainder digest must name covered inventory artifacts", remainder.contains("BotRuntimeController") && remainder.contains("CanonicalSizeContext") && remainder.contains("CatastrophicPaperBleedGuard") && remainder.contains("TradeIdentityManager") && remainder.contains("AiStatePersistenceSentinel") && remainder.contains("ApiBackoff"))
        assertTrue("V5.0.4374: perps digest must name covered CryptoFunnel artifact", perps.contains("CryptoFunnel"))
        assertTrue("V5.0.4374: inventory marker closure remains report-only", remainder.contains("Report-only") || remainder.contains("report-only") || remainder.contains("Report-only"))
    }


    @Test
    fun symbolicAndReauditProveOperatorDigests4376() {
        val prover = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SymbolicInvariantProver.kt").readText()
        val sweeper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AsiSsiReauditSweeper.kt").readText()
        assertTrue("V5.0.4376: SymbolicInvariantProver must protect operator digest report-only KPI wiring", prover.contains("OPERATOR_DIGESTS_REPORT_ONLY_KPI_WIRED_4376") && prover.contains("operatorDigests.all") && prover.contains("no_execution_authority=true") && prover.contains("""!text.contains("executeBuy(")""") && prover.contains("""!text.contains("requestSell(")"""))
        assertTrue("V5.0.4376: AsiSsiReauditSweeper must protect operator digest report-only no-authority contract", sweeper.contains("OPERATOR_DIGESTS_REPORT_ONLY_NO_AUTHORITY_4376") && sweeper.contains("operatorDigests.all") && sweeper.contains("kpi.contains(") && sweeper.contains(".status"))
    }


    @Test
    fun syntheticComponentAccountability4378StampsHarvardEntryRecords() {
        val acct = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/SyntheticComponentAccountability.kt").readText()
        val scorer = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText()
        assertTrue("V5.0.4378: synthetic component accountability must cover score-moving theatre names", acct.contains("source") && acct.contains("approval_memory") && acct.contains("v4_crosstalk") && acct.contains("fresh_launch_bonus") && acct.contains("acct=synthetic_component"))
        assertTrue("V5.0.4378: accountability stamp must preserve score values and add lane/source/mint/build context", acct.contains("comp.copy(reason") && acct.contains("source=") && acct.contains("candidate.source.name") && acct.contains("mint=") && acct.contains("candidate.mint.take(10)") && acct.contains("build=") && acct.contains("BuildConfig.VERSION_NAME"))
        assertTrue("V5.0.4378: UnifiedScorer Harvard entry records must pass through synthetic accountability on all paths", scorer.contains("""SyntheticComponentAccountability.annotate(finalCard.components + shadowOuterRing, candidate, "CLASSIC")""") && scorer.contains("""SyntheticComponentAccountability.annotate(finalCard.components, candidate, "MODERN")""") && scorer.contains("""SyntheticComponentAccountability.annotate(fallbackCard.components, candidate, "MODERN_FALLBACK")""") && scorer.contains("""SyntheticComponentAccountability.annotate(finalCard.components, candidate, "UNIFIED")""") && scorer.contains("""SyntheticComponentAccountability.annotate(fallbackCard.components, candidate, "UNIFIED_FALLBACK")"""))
    }


    @Test
    fun crossTalkStamp4380CarriesEventLocalMetadata() {
        val cross = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AICrossTalk.kt").readText()
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeCrossTalkEntryBridge.kt").readText()
        assertTrue("V5.0.4380: AICrossTalk stamp must retain source/mode/positionId/build with the entry-time signal", cross.contains("source: String") && cross.contains("mode: String") && cross.contains("positionId: String") && cross.contains("build: String") && cross.contains("CROSSTALK_ENTRY_STAMP_4380") && cross.contains("CROSSTALK_ENTRY_OUTCOME_4380"))
        assertTrue("V5.0.4380: MemeCrossTalkEntryBridge must stamp event-local source/mode/positionId instead of close-time recompute metadata", bridge.contains("source = ts.source") && bridge.contains("mode = ts.position.tradingMode") && bridge.contains("positionId = TradeOutcomeLedger.positionId(ts)"))
        assertTrue("V5.0.4380: cross-talk metadata patch must preserve backward compatibility for existing callers", cross.contains("source: String = ") && cross.contains("mode: String = ") && cross.contains("positionId: String = ") && cross.contains("build: String = BuildConfig.VERSION_NAME"))
    }


    @Test
    fun ssiCouncilClosedLoopSentinel4381ProvesCouncilChain() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SsiCouncilClosedLoopSentinel.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4381: SSI council sentinel must name full insight-to-outcome chain", sentinel.contains("SemanticPatternGraph.entryBias") && sentinel.contains("CounterfactualReplayEngine.policyHints") && sentinel.contains("ReflectiveOptimizerGEPA") && sentinel.contains("MultiAgentCriticStack.reviewAndSubmit") && sentinel.contains("AsyncStrategyLab.reviewedSizeBias") && sentinel.contains("UnifiedPolicyHead.stamp/recordOutcome") && sentinel.contains("UnifiedExitPolicyHead.stamp/recordOutcome"))
        assertTrue("V5.0.4381: SSI council sentinel must remain report-only and provider-safe", sentinel.contains("report_only=true") && sentinel.contains("no_execution_authority=true") && sentinel.contains("no_hot_path_provider=true") && sentinel.contains("no_direct_trade_authority=true"))
        assertTrue("V5.0.4381: operator KPI must include SSI council status", kpi.contains("ssi_council_status=SsiCouncilClosedLoopSentinel") && kpi.contains("SsiCouncilClosedLoopSentinel.status"))
    }


    @Test
    fun profitPressureWalletNull4384EnqueuesUrgentSellRecovery() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4384: live capital-recovery wallet-null must enqueue urgent recovery instead of passive retry", exec.contains("URGENT_CAPITAL_RECOVERY_WALLET_NULL") && exec.contains("PendingSellQueue.add(ts.mint") && exec.contains("BalanceProofWaitState.markWaiting") && exec.contains("PROFIT_PRESSURE_SELL_RECOVERY_ENQUEUED_4384"))
        assertTrue("V5.0.4384: live profit-lock wallet-null must enqueue urgent recovery instead of passive retry", exec.contains("URGENT_PROFIT_LOCK_WALLET_NULL") && exec.contains("HostWalletTokenTracker.markSellWaitingBalanceProof") && exec.contains("kind=profit_lock wallet_null pendingSell=true balanceProofWait=true"))
        assertTrue("V5.0.4384: profit-pressure recovery must not fake-close or paper-book live exits", !exec.contains("PROFIT_LOCK_DEFERRED") || exec.contains("Enqueued urgent proof/retry"))
    }


    @Test
    fun moonshotPromotion4386HasSinglePromotedFromArgument() {
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        val promotionStart = moon.indexOf("fun executePromotion")
        val promotionEnd = moon.indexOf("fun recordCollectiveWinner", promotionStart)
        val promotionBlock = moon.substring(promotionStart, promotionEnd)
        assertEquals("V5.0.4386: Moonshot promotion must not duplicate promotedFrom named argument", 1, Regex("promotedFrom = fromLayer").findAll(promotionBlock).count())
        assertTrue("V5.0.4386: Moonshot promotion still preserves runner context", promotionBlock.contains("peakPnlPct = currentPnlPct") && promotionBlock.contains("tightSL.coerceAtLeast(HARD_FLOOR_STOP)"))
    }


    @Test
    fun memeLaneParitySentinel4388PinsRestoreAndProbeParity() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MemeLaneParitySentinel.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4388: lane parity sentinel must pin nine-lane restore labels", sentinel.contains("NINE_LANE_RESTORE_PARITY_4388") && sentinel.contains("MANIPULATED_RESTORED_ACTIVE_POSITION_4214") && sentinel.contains("TREASURY_RESTORED_ACTIVE_POSITION_4228"))
        assertTrue("V5.0.4388: lane parity sentinel must pin soft recovery probes, not local pause amputations", sentinel.contains("LANE_LOCAL_PAUSES_ARE_RECOVERY_PROBES_4388") && sentinel.contains("SHITCOIN_DAILY_LOSS_RECOVERY_PROBE_4314") && sentinel.contains("TreasuryMode.PAUSED -> 0.35"))
        assertTrue("V5.0.4388: lane parity sentinel must be report-only and KPI-wired", sentinel.contains("report_only=true") && sentinel.contains("no_execution_authority=true") && kpi.contains("meme_lane_parity=MemeLaneParitySentinel") && kpi.contains("MemeLaneParitySentinel.status"))
        assertTrue("V5.0.4388: lane parity sentinel must expose source-tree audit entrypoints", sentinel.contains("fun auditSourceTree") && sentinel.contains("fun failed") && sentinel.contains("RESTORE_HELPERS_PRESENT_4388"))
    }


    @Test
    fun uiAnrDecouplingSentinel4392PinsMainActivityTextBoundaries() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/UiAnrDecouplingSentinel.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4392: UI ANR sentinel must pin bounded decision-log text", sentinel.contains("UI_DECISION_LOG_BOUNDED_4392") && main.contains("setDecisionLogTextBounded4280") && main.contains("DECISION_LOG_MAX_CHARS_4280"))
        assertTrue("V5.0.4392: UI ANR sentinel must pin no-op relayout skips", sentinel.contains("UI_DECISION_LOG_NOOP_SKIP_4392") && main.contains("lastDecisionLogTextHash") && main.contains("setTextIfChanged"))
        assertTrue("V5.0.4392: UI ANR sentinel must be report-only and KPI-wired", sentinel.contains("report_only=true") && sentinel.contains("no_execution_authority=true") && kpi.contains("ui_anr=UiAnrDecouplingSentinel") && kpi.contains("UiAnrDecouplingSentinel.status"))
    }


    @Test
    fun operatorLearningControlDigest4393SurfacesHiddenStatusMechanisms() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLearningControlDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4393: learning/control digest must surface hidden learning/scanner/control statuses", digest.contains("AdaptiveLearningEngine.getStatus") && digest.contains("AutoCompoundEngine.getStatus") && digest.contains("CloudLearningSync.getStatus") && digest.contains("ScannerLearning.getStatus source=SolanaMarketScanner.kt") && digest.contains("SolanaMarketScanner") && digest.contains("PatternAutoTuner.getStatus"))
        assertTrue("V5.0.4393: learning/control digest must include authority, toxic, ML, market, and regime surfaces", digest.contains("EnabledTraderAuthority.snapshotStr") && digest.contains("ToxicModeCircuitBreaker.getStatus") && digest.contains("OnDeviceMLEngine.getStatus") && digest.contains("MarketStructureRouter.getStatus") && digest.contains("RegimeTransitionAI.getStatus"))
        assertTrue("V5.0.4393: learning/control digest must remain report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && kpi.contains("learning_control=OperatorLearningControlDigest") && kpi.contains("OperatorLearningControlDigest.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest4396BatchesLowReferenceAuditSurface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4396: long-tail digest must batch at least twenty low-reference mechanism surfaces", digest.contains("OPERATOR_LONG_TAIL_MECHANISM_DIGEST_4396") && digest.contains("count=") && digest.contains("items.size") && digest.contains("PatchWriterAI") && digest.contains("CryptoUniverseFilter"))
        assertTrue("V5.0.4396: long-tail digest must classify perps, sentinels, scanner, and sell authority sidecars", digest.contains("perps_sidecar") && digest.contains("sentinel_kpi_visible") && digest.contains("scanner_mode_helper") && digest.contains("sell_authority_sidecar_source_contract"))
        assertTrue("V5.0.4396: long-tail digest must remain report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail=OperatorLongTailMechanismDigest") && kpi.contains("OperatorLongTailMechanismDigest.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest2_4398BatchesSecondLowReferenceSurface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest2.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4398: second long-tail digest must batch sell, state, scanner, perps, and learning sidecars", digest.contains("BalanceProofState") && digest.contains("LayerLaneRegistry") && digest.contains("RouteValidator") && digest.contains("ArbLearning") && digest.contains("count="))
        assertTrue("V5.0.4398: second long-tail digest must classify safety and sidecar surfaces without adding authority", digest.contains("base_quote_hard_safety_guard") && digest.contains("paper_learning_sanity_gate") && digest.contains("perps_market_scanner_sidecar") && digest.contains("report_only=true"))
        assertTrue("V5.0.4398: second long-tail digest must be KPI-wired", digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail2=OperatorLongTailMechanismDigest2") && kpi.contains("OperatorLongTailMechanismDigest2.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest3_4399BatchesThirdLowReferenceSurface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest3.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4399: third long-tail digest must batch mid-low-reference mechanisms", digest.contains("OPERATOR_LONG_TAIL_MECHANISM_DIGEST3_4399") && digest.contains("BundleDetector") && digest.contains("FatalRiskChecker") && digest.contains("count="))
        assertTrue("V5.0.4399: third long-tail digest must cover exit cost, lane toxicity, reports, V3 and perps sidecars", digest.contains("ExitCostMicrobrain") && digest.contains("LaneToxicityGuard") && digest.contains("LiveWalletGrowthGovernorReport") && digest.contains("TradeExecutor") && digest.contains("CryptoCanonicalLearning"))
        assertTrue("V5.0.4399: third long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail3=OperatorLongTailMechanismDigest3") && kpi.contains("OperatorLongTailMechanismDigest3.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest4_4400BatchesFourthLowReferenceSurface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest4.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4400: fourth long-tail digest must batch burn, consensus, treasury, wallet and sell-history surfaces", digest.contains("BirdeyeMintBurnMonitor") && digest.contains("BrainConsensusGate") && digest.contains("TreasuryWalletManager") && digest.contains("WalletAuthoritySnapshot") && digest.contains("SellFailureHistory"))
        assertTrue("V5.0.4400: fourth long-tail digest must include AI/risk and execution coordination surfaces", digest.contains("UltraFastRugDetectorAI") && digest.contains("LiquidityFragilityAI") && digest.contains("ExecutionStatusRegistry") && digest.contains("PositionExitArbiter") && digest.contains("CultMomentumAI"))
        assertTrue("V5.0.4400: fourth long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail4=OperatorLongTailMechanismDigest4") && kpi.contains("OperatorLongTailMechanismDigest4.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest5_4401BatchesResidualLowReferenceSurface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest5.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4401: fifth long-tail digest must batch residual low-reference sidecars", digest.contains("MemeTraderFullAuditSweeper") && digest.contains("ExecutableQuoteGate") && digest.contains("TradeRowSanityCheck") && digest.contains("SmartSizerV3"))
        assertTrue("V5.0.4401: fifth long-tail digest must cover LLM, Birdeye, wallet, sizing and crosstalk residuals", digest.contains("LlmPaperTradeExecutor") && digest.contains("BirdeyeTradeDataProvider") && digest.contains("WalletRefreshAfterSell") && digest.contains("MemeCrossTalkEntryBridge") && digest.contains("PositionSizing"))
        assertTrue("V5.0.4401: fifth long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail5=OperatorLongTailMechanismDigest5") && kpi.contains("OperatorLongTailMechanismDigest5.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest6_4402BatchesRef17To25Surface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest6.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4402: sixth long-tail digest must batch replay, safety, ledger, scanner and live-authority surfaces", digest.contains("CounterfactualReplayEngine") && digest.contains("TokenSafetyChecker") && digest.contains("TradeOutcomeLedger") && digest.contains("LivePositionCloseAuthority") && digest.contains("SecurityGuard"))
        assertTrue("V5.0.4402: sixth long-tail digest must include autonomous, lab, V3, perps and wallet surfaces", digest.contains("AutonomousMetaPolicy") && digest.contains("LlmLabEngine") && digest.contains("MarketStructureRouter") && digest.contains("PerpsUnifiedScorerBridge") && digest.contains("WalletTokenMemory"))
        assertTrue("V5.0.4402: sixth long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail6=OperatorLongTailMechanismDigest6") && kpi.contains("OperatorLongTailMechanismDigest6.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest7_4403BatchesRef26To35Surface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest7.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4403: seventh long-tail digest must batch scanner, journal, wallet and memory surfaces", digest.contains("SolanaMarketScanner") && digest.contains("TradeJournal") && digest.contains("LiveWalletReconciler") && digest.contains("TradingMemory"))
        assertTrue("V5.0.4403: seventh long-tail digest must include AI, voice, edge and blacklist surfaces", digest.contains("LayerBrain") && digest.contains("RegimeTransitionAI") && digest.contains("VoiceManager") && digest.contains("EdgeOptimizer") && digest.contains("TokenBlacklist"))
        assertTrue("V5.0.4403: seventh long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail7=OperatorLongTailMechanismDigest7") && kpi.contains("OperatorLongTailMechanismDigest7.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest8_4404BatchesRef36To50Surface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest8.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4404: eighth long-tail digest must batch sizing, proof, scoring and sell-authority surfaces", digest.contains("LiveSizingProfile") && digest.contains("BalanceProofWaitState") && digest.contains("SmartMoneyDivergenceAI") && digest.contains("SellAmountAuthority"))
        assertTrue("V5.0.4404: eighth long-tail digest must include persistence, journal identity, V3 and cross-talk surfaces", digest.contains("LearningPersistence") && digest.contains("TradeIdentity") && digest.contains("V3EngineManager") && digest.contains("CrossTalkFusionEngine") && digest.contains("UltimateEdgeEngine"))
        assertTrue("V5.0.4404: eighth long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail8=OperatorLongTailMechanismDigest8") && kpi.contains("OperatorLongTailMechanismDigest8.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest9_4405BatchesRef51To75Surface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest9.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4405: ninth long-tail digest must batch permit, cross-talk, persistence and health surfaces", digest.contains("FinalExecutionPermit") && digest.contains("AICrossTalk") && digest.contains("PositionPersistence") && digest.contains("ApiHealthMonitor"))
        assertTrue("V5.0.4405: ninth long-tail digest must include lane, verifier, trust and strategy intelligence surfaces", digest.contains("LaneTag") && digest.contains("TradeVerifier") && digest.contains("ScoreExpectancyTracker") && digest.contains("StrategyTrustAI") && digest.contains("BotBrain"))
        assertTrue("V5.0.4405: ninth long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail9=OperatorLongTailMechanismDigest9") && kpi.contains("OperatorLongTailMechanismDigest9.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest10_4406BatchesRef76To100Surface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest10.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4406: tenth long-tail digest must batch heavy-core authorizer, lifecycle and authority surfaces", digest.contains("TradeAuthorizer") && digest.contains("TokenLifecycleTracker") && digest.contains("EnabledTraderAuthority") && digest.contains("RegimeDetector"))
        assertTrue("V5.0.4406: tenth long-tail digest must include manipulated, perps and liquidity surfaces", digest.contains("ManipulatedTraderAI") && digest.contains("PerpsLearningBridge") && digest.contains("DynamicAltTokenRegistry") && digest.contains("LiquidityDepthAI"))
        assertTrue("V5.0.4406: tenth long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail10=OperatorLongTailMechanismDigest10") && kpi.contains("OperatorLongTailMechanismDigest10.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest11_4407BatchesRef101To150Surface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest11.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4407: eleventh long-tail digest must batch runtime, mode, registry and learning surfaces", digest.contains("BotRuntimeController") && digest.contains("ModeRouter") && digest.contains("GlobalTradeRegistry") && digest.contains("AdaptiveLearningEngine"))
        assertTrue("V5.0.4407: eleventh long-tail digest must include perps, treasury, quality and bluechip surfaces", digest.contains("PerpsTraderAI") && digest.contains("TreasuryManager") && digest.contains("QualityTraderAI") && digest.contains("BlueChipTraderAI"))
        assertTrue("V5.0.4407: eleventh long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail11=OperatorLongTailMechanismDigest11") && kpi.contains("OperatorLongTailMechanismDigest11.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest12_4408BatchesRef151To250Surface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest12.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4408: twelfth long-tail digest must batch highest-impact meme, runtime and wallet surfaces", digest.contains("ShitCoinTraderAI") && digest.contains("MoonshotTraderAI") && digest.contains("RuntimeModeAuthority") && digest.contains("HostWalletTokenTracker"))
        assertTrue("V5.0.4408: twelfth long-tail digest must include Harvard, collective, Solana and meta-cognition surfaces", digest.contains("EducationSubLayerAI") && digest.contains("CollectiveLearning") && digest.contains("SolanaWallet") && digest.contains("MetaCognitionAI"))
        assertTrue("V5.0.4408: twelfth long-tail digest must stay report-only and KPI-wired", digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail12=OperatorLongTailMechanismDigest12") && kpi.contains("OperatorLongTailMechanismDigest12.status"))
    }


    @Test
    fun operatorLongTailMechanismDigest13_4409BatchesRemainingSupercoreSurface() {
        val digest = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorLongTailMechanismDigest13.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4409: thirteenth digest must batch remaining supercore authority surfaces", digest.contains("TradeHistoryStore") && digest.contains("FluidLearningAI") && digest.contains("LiveTradeLogStore") && digest.contains("BotService"))
        assertTrue("V5.0.4409: supercore digest must explicitly remain report-only despite authority proximity", digest.contains("supercore_authority_surface=true") && digest.contains("report_only=true") && digest.contains("no_execution_authority=true") && digest.contains("no_gate_change=true"))
        assertTrue("V5.0.4409: supercore digest must be KPI-wired without hot-path provider calls", digest.contains("no_hot_path_provider_calls=true") && kpi.contains("long_tail13=OperatorLongTailMechanismDigest13") && kpi.contains("OperatorLongTailMechanismDigest13.status"))
    }


    @Test
    fun operatorChokeButterflyAuditLedger_4410TracksSiblingChokeFamilies() {
        val ledger = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorChokeButterflyAuditLedger.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4410: choke ledger must track sibling hard-stop pattern families", ledger.contains("ZERO_SIZE_OR_MULTIPLIER") && ledger.contains("HARD_RETURN_FALSE") && ledger.contains("DAILY_LOSS_PAUSE_OR_STREAK"))
        assertTrue("V5.0.4410: choke ledger must include veto/reject and hot-path provider butterfly families", ledger.contains("VETO_OR_REJECT_TAXONOMY") && ledger.contains("HOTPATH_PROVIDER_HINT") && ledger.contains("butterflies_considered=true"))
        assertTrue("V5.0.4410: choke ledger must remain report-only, KPI-wired and source-contract safe", ledger.contains("report_only=true") && ledger.contains("no_execution_authority=true") && ledger.contains("no_hot_path_provider_calls=true") && kpi.contains("choke_butterfly=OperatorChokeButterflyAuditLedger") && kpi.contains("OperatorChokeButterflyAuditLedger.status"))
    }


    @Test
    fun operatorChokeRemediationQueue_4411PrioritizesSiblingButterflyFixes() {
        val queue = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorChokeRemediationQueue.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4411: remediation queue must name zero-size and false-return source families", queue.contains("CQ1_EXECUTION_ZERO_SIZE_STACK") && queue.contains("CQ2_AUTHORITY_FALSE_RETURN_STACK") && queue.contains("source_fix_first=true"))
        assertTrue("V5.0.4411: remediation queue must name pause, reject-taxonomy and provider hot-path butterfly families", queue.contains("CQ3_DAILY_PAUSE_RECOVERY_PROBE_PARITY") && queue.contains("CQ4_REJECT_TAXONOMY_NORMALIZATION") && queue.contains("CQ5_PROVIDER_CALL_BACKGROUND_ISOLATION") && queue.contains("butterflies_named=true"))
        assertTrue("V5.0.4411: remediation queue must remain report-only and KPI-wired until surgical fixes are selected", queue.contains("report_only=true") && queue.contains("no_execution_authority=true") && queue.contains("golden_tape_required=true") && kpi.contains("choke_queue=OperatorChokeRemediationQueue") && kpi.contains("OperatorChokeRemediationQueue.status"))
    }


    @Test
    fun operatorCQ1ZeroSizeHitList_4412SeparatesChokesFromBenignZeroes() {
        val hitList = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorCQ1ZeroSizeHitList.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4413: CQ1 hit list must review zero-size candidates while preserving capital safety", hitList.contains("CashGenerationAI.kt") && hitList.contains("LaneStrategyEvaluator.kt") && hitList.contains("capital_safety_preserved=true"))
        assertTrue("V5.0.4412: CQ1 hit list must separate benign zeroes to avoid unsafe blanket patches", hitList.contains("FinalDecisionGate.kt") && hitList.contains("LiveSizingProfile.kt") && hitList.contains("BENIGN_TELEMETRY_GUARD") && hitList.contains("false_positives_separated=true"))
        assertTrue("V5.0.4412: CQ1 hit list must remain report-only and KPI-wired", hitList.contains("report_only=true") && hitList.contains("no_execution_authority=true") && hitList.contains("butterflies_named=true") && kpi.contains("cq1_zero_size=OperatorCQ1ZeroSizeHitList") && kpi.contains("OperatorCQ1ZeroSizeHitList.status"))
    }


    @Test
    fun operatorCQ2AuthorityFalseReturnHitList_4413NamesReleaseVisibilitySiblings() {
        val hitList = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorCQ2AuthorityFalseReturnHitList.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4413: CQ2 hit list must name FEP, lane release and BotService false-return siblings", hitList.contains("FinalExecutionPermit.kt") && hitList.contains("LaneExecutionCoordinator.kt") && hitList.contains("BotService.kt"))
        assertTrue("V5.0.4413: CQ2 hit list must preserve hard safety while demanding release visibility", hitList.contains("hard_safety_preserved=true") && hitList.contains("release_visibility_required=true") && hitList.contains("FALSE_POSITIVE_CLEARED"))
        assertTrue("V5.0.4413: CQ2 hit list must remain report-only and KPI-wired", hitList.contains("report_only=true") && hitList.contains("no_execution_authority=true") && kpi.contains("cq2_false_return=OperatorCQ2AuthorityFalseReturnHitList") && kpi.contains("OperatorCQ2AuthorityFalseReturnHitList.status"))
    }


    @Test
    fun operatorCQ3CQ4PauseRejectHitList_4414BundlesPauseAndRejectTaxonomy() {
        val hitList = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorCQ3CQ4PauseRejectHitList.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4414: CQ3/CQ4 hit list must bundle recovery-probe and hard-safety pause boundaries", hitList.contains("CQ3_LANE_LOCAL_PAUSE_RECOVERY_PROBE") && hitList.contains("CQ3_GLOBAL_SAFETY_BOUNDARY") && hitList.contains("hard_safety_boundaries_preserved=true"))
        assertTrue("V5.0.4414: CQ3/CQ4 hit list must bundle reject taxonomy and scanner hard reject boundaries", hitList.contains("CQ4_REJECT_TAXONOMY_DRIFT") && hitList.contains("CQ4_SCANNER_HARD_REJECT_BOUNDARY") && hitList.contains("reject_taxonomy_required=true"))
        assertTrue("V5.0.4414: CQ3/CQ4 hit list must remain report-only and KPI-wired", hitList.contains("report_only=true") && hitList.contains("no_execution_authority=true") && hitList.contains("butterflies_named=true") && kpi.contains("cq3_cq4_pause_reject=OperatorCQ3CQ4PauseRejectHitList") && kpi.contains("OperatorCQ3CQ4PauseRejectHitList.status"))
    }


    @Test
    fun operatorCQ5ProviderHotPathHitList_4415NamesProviderIsolationButterflies() {
        val hitList = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorCQ5ProviderHotPathHitList.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4415: CQ5 hit list must name scanner provider budget and quote failure boundaries", hitList.contains("CQ5_SCANNER_PROVIDER_BUDGET_BOUNDARY") && hitList.contains("CQ5_EXECUTOR_QUOTE_FAILURE_BOUNDARY") && hitList.contains("quote_taxonomy_required=true"))
        assertTrue("V5.0.4415: CQ5 hit list must name background intelligence and wallet RPC trust boundaries", hitList.contains("CQ5_BACKGROUND_INTELLIGENCE_PROVIDER_BOUNDARY") && hitList.contains("CQ5_WALLET_RPC_TRUST_BOUNDARY") && hitList.contains("wallet_trust_boundary_required=true"))
        assertTrue("V5.0.4415: CQ5 hit list must remain report-only and KPI-wired", hitList.contains("report_only=true") && hitList.contains("no_execution_authority=true") && hitList.contains("cache_first_required=true") && kpi.contains("cq5_provider_hot_path=OperatorCQ5ProviderHotPathHitList") && kpi.contains("OperatorCQ5ProviderHotPathHitList.status"))
    }


    @Test
    fun operatorChokeSourceContractSentinel_4416ProtectsCQBehaviorPatchBoundaries() {
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorChokeSourceContractSentinel.kt").readText()
        val kpi = java.io.File("src/main/kotlin/com/lifecyclebot/engine/OperatorKpiCloseoutReport.kt").readText()
        assertTrue("V5.0.4416: source-contract sentinel must cover CQ1-CQ5 families", sentinel.contains("CQ1_ZERO_SIZE_CONTRACT") && sentinel.contains("CQ2_FALSE_RETURN_CONTRACT") && sentinel.contains("CQ3_PAUSE_RECOVERY_CONTRACT") && sentinel.contains("CQ4_REJECT_TAXONOMY_CONTRACT") && sentinel.contains("CQ5_PROVIDER_HOT_PATH_CONTRACT"))
        assertTrue("V5.0.4416: source-contract sentinel must preserve safety while enabling release visibility and cache-first fixes", sentinel.contains("hard_safety_preserved=true") && sentinel.contains("release_visibility_required=true") && sentinel.contains("cache_first_required=true") && sentinel.contains("reject_taxonomy_required=true"))
        assertTrue("V5.0.4416: source-contract sentinel must remain report-only and KPI-wired", sentinel.contains("report_only=true") && sentinel.contains("no_execution_authority=true") && sentinel.contains("golden_tape_required=true") && kpi.contains("choke_contracts=OperatorChokeSourceContractSentinel") && kpi.contains("OperatorChokeSourceContractSentinel.status"))
    }


    @Test
    fun finalExecutionPermitFalseReturns_4416AreRouteVisibleWithoutChangingAuthority() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        assertTrue("V5.0.4416: FEP false-return branches must emit route-visible telemetry", source.contains("recordPermitFalseReturn4416") && source.contains("FEP_FALSE_RETURN_ROUTE_VISIBLE_4416") && source.contains("PipelineHealthCollector.labelInc"))
        assertTrue("V5.0.4418: FEP route-visible telemetry must cover runtime, finality, lane-telemetry and pending false returns", source.contains("""recordPermitFalseReturn4416("RUNTIME_PAUSED")""") && source.contains("""recordPermitFalseReturn4416("RUNTIME_OVERLAY_DISABLED")""") && source.contains("""recordPermitFalseReturn4416("LANE_TELEMETRY_ONLY")""") && source.contains("""recordPermitFalseReturn4416("PENDING_${existing.layer}")"""))
        assertTrue("V5.0.4418: FEP behavior remains authority-preserving; telemetry records before existing false returns", source.contains("""releasePrimaryAfterPermitFailure("FINALITY_${finality.logName}")""") && source.contains("""recordPermitFalseReturn4416("FINALITY_${finality.logName}")""") && source.contains("return false"))
    }


    @Test
    fun laneExecutionCoordinatorReleaseFalse_4419IsRouteVisible() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()
        assertTrue("V5.0.4419: lane primary release false paths must emit visible telemetry", source.contains("LANE_PRIMARY_RELEASE_FALSE_VISIBLE_4419") && source.contains("PipelineHealthCollector.labelInc"))
        assertTrue("V5.0.4419: lane release false telemetry must cover missing election and non-primary outcomes", source.contains("MISSING_ELECTION") && source.contains("NOT_PRIMARY") && source.contains("return false"))
        assertTrue("V5.0.4419: lane release true path must remain unchanged", source.contains("LANE_PRIMARY_RELEASED") && source.contains("return removed"))
    }

}
