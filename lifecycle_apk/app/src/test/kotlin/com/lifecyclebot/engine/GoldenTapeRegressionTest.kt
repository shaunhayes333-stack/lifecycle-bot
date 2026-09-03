package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.9.1562 â€” Golden-tape blocker taxonomy harness.
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
            "Safety: Rugcheck pending â€” live mode, no high-score override",
            "Safety: Rugcheck API timeout (live: PENDING_REVIEW)",
            "Safety: SAFETY_RUN_FAILED_PARTIAL_DATA: timeout",
            "Safety: LOW_LIQUIDITY: \$900 < \$1200",
            "Safety: Liquidity \$900 < \$1,200 live exit-safety floor â€” un-exitable",
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
        assertTrue(source.contains("canonical LIVE truth is MANAGED live truth"))
        assertTrue(source.contains("TokenLifecycleTracker.liveMemeOpenCount()") && source.contains("raw TokenLifecycleTracker.openCount() includes stale"))
        assertTrue(source.contains("val managedLiveOpen = maxOf(localLiveOpen, hostOpen, lifecyclePendingConfirmed, lifecycleOpen)"))
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
        val recordIdx = source.indexOf("V5.9.1570 â€” Express FDG verdict")
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
    fun forced_open_positions_never_enter_discovery_supervisor() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val selector = bot.substringAfter("private fun selectOrderedMintsForCycle(").substringBefore("private fun emitWatchlistCapTrace")
        assertTrue(selector.contains("val forcedOpenForSupervisor: List<String> = emptyList()"))
        assertTrue(selector.contains("val mustInclude = mutableListOf<String>()"))
        assertFalse("canonical opens must never consume discovery supervisor slots", selector.contains("val mustInclude = forcedOpenMints.toMutableList()"))
        assertFalse("forced opens must not inflate discovery admission capacity", java.io.File("src/main/kotlin/com/lifecyclebot/engine/SupervisorAdmissionPlanner.kt").readText().contains("forcedOpenCount"))
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
        assertTrue(sent.contains("DRAWDOWN CIRCUIT: ðŸ›¡ï¸ DEFENSIVE"))
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
        assertTrue(bot.contains("val expressFinalSize = expressFdg?.sizeSol") && bot.contains("?: expressSignal.positionSizeSol.coerceAtLeast(0.01)"))
        assertTrue(bot.contains("sizeSol = expressFinalSize"))
        assertTrue(bot.contains("entrySol = expressFinalSize"))
        val start = bot.indexOf("val expressFinalSize")
        val end = bot.indexOf("addLog(\"ðŸ’©ðŸš‚ EXPRESS:", start)
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
        assertTrue("V5.0.6599: Executor may consume cached immutable desk plans but must not call sheet build or add a second buy/sell authority", executor.contains("ToolkitSignalSheet.snapshot(ts)") && executor.contains("EntryStrategySnapshot6450.setEntry") && !executor.contains("ToolkitSignalSheet.build(") && !executor.contains("DIAMOND_HANDS_RUNNER"))
        assertTrue("Toolkit sheet must refresh silently without bot-loop blocking", sheet.contains("GlobalScope.launch(AppDispatchers.sideEffect)") && sheet.contains("inFlight.add(mint)") && sheet.contains("fallbackSheet"))
        assertTrue("Internet LLM edge must be background-only and feed cached soft setup bias", internet.contains("GlobalScope.launch(AppDispatchers.sideEffect)") && internet.contains("GeminiCopilot.rawText") && internet.contains("setupScoreBias") && sheet.contains("InternetEdgeDesk.setupScoreBias") && sheet.contains("InternetEdgeDesk.refreshAsync"))
        assertFalse("Toolkit sheet must not perform network or LLM calls directly", listOf("http", "OkHttp", "Retrofit", "Groq", "GeminiCopilot.rawText", "Thread.sleep", "runBlocking").any { sheet.contains(it) })
    }




    @Test
    fun unified_report_budget_prioritizes_toolkit_and_prevents_tail_truncation() {
        val hub = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()

        assertTrue("Unified report must include a first-class toolkit section near the top", hub.contains("TOOLKIT SIGNAL SHEET") && hub.contains("buildToolkitSignalSummary"))
        assertTrue("Unified report budgets must fit under chat cap before hard truncation", hub.contains("PASTE-SAFE REPORT CONTRACT") && hub.contains("paste-safe hard cap"))
        assertTrue("Pipeline block must be core-only so learning/tuning is not duplicated", hub.contains("PIPELINE HEALTH â€” CORE") && !hub.contains("PIPELINE HEALTH â€” CONDENSED", ignoreCase = false))
        assertTrue("Error logs must be bounded tightly via compact table to avoid eating the report tail", hub.contains("ErrorLogger.exportToCompactTable(limit = 80)"))
        assertTrue("Toolkit setup/chart counters must feed report visibility", sheet.contains("TOOLKIT_SETUP_${'$'}{built.setup.name}") && sheet.contains("TOOLKIT_CHART_${'$'}{built.chartPattern.uppercase().take(48)}"))
        assertTrue("ANR evidence must remain visible in compact report", hub.contains("===== ANR / main-thread health") && hub.contains("===== ANR top blocking call sites") && hub.contains("ANR top:"))
        assertTrue("Internet edge desk must be visible in toolkit report section", hub.contains("InternetEdgeDesk.summaryLine") && hub.contains("INTERNET_EDGE_REFRESHED"))
        assertTrue("Learning-heavy PHC sections must not be duplicated inside core pipeline block", !hub.contains("\"===== Strategy Hypothesis Engine\"") && !hub.contains("\"===== Lane Exit Tuner\"") && !hub.contains("\"===== Autonomous Meta-Policy\"") && !hub.contains("\"===== Unified Policy Head\""))
    }









    @Test
    fun live_stale_restore_cannot_resurrect_old_fdg_approval() {
        val openGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue("LIVE stale-WATCH restore must be ticket based, not global-version based", openGate.contains("data class ExecutionIntent") && openGate.contains("EXEC_TICKET_RESTORED_IMMUTABLE"))
        assertTrue("LIVE stale-candidate version churn must not kill an immutable ticket", openGate.contains("immutableTicket == null && ticketAuthority6564 == null && immutableAuthority6513 == null && !selectedLaneMatchesRequest") && openGate.contains("immutableTicket == null"))
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
        assertTrue("Rotation must include internal lanes that were previously idle (V5.0.4599: specialists no longer in ring)", listOf("MOONSHOT", "MANIPULATED", "QUALITY", "DIP_HUNTER", "TREASURY", "CASHGEN", "BLUECHIP").all { bot.contains(it) })
        assertTrue("V5.0.6600: source/style owner plus one qualified rescue replace insertion-order desk collapse", bot.contains("forced ?: styleLanes.firstOrNull()") && bot.contains("boundedRescue6600") && bot.contains("specialistEvaluationAllowed6600") && bot.contains("claimedOwner6600"))
        assertTrue("V5.0.6014: successful lanes must get bounded entry feed instead of MANIPULATED/SHITCOIN/EXPRESS budget", bot.contains("SUCCESSFUL_LANE_FEED_RESTORED_6014") && bot.contains("successfulFeedLanes6014") && bot.contains("QUALITY") && bot.contains("MOONSHOT") && bot.contains("BLUECHIP") && bot.contains("CRYPTO") && !bot.contains("SPECIALIST_ENTRY_EVAL_RESTORED_6013"))
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
        assertTrue("Journal/Learning UI screens must use bounded snapshots and not copy unbounded full journals", journalActivity.contains("getAllValidTradesSnapshot(5_000)") && learningCounter.contains("getStatsCached().totalStoredTrades") && !journalActivity.contains("TradeHistoryStore.getAllTrades()") && !learningCounter.contains("TradeHistoryStore.getAllTrades()"))
    }
    @Test
    fun paper_to_live_transfer_uses_executable_net_edge_not_gross_paper_pct() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val openGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue("Terminal paper sells must charge live-like round-trip friction plus learned route slip", exec.contains("executable-live paper friction") && exec.contains("expectedExtraSlipPct(ts.lastLiquidityUsd)") && exec.contains("val simulatedFeePct = (1.6 + expectedRouteSlipPct"))
        assertTrue("Paper terminal SELL rows must carry feeSol/netPnlSol into journal and learning", exec.contains("val simulatedFeeSol") && exec.contains("feeSol = simulatedFeeSol") && exec.contains("netPnlSol = pnl"))
        assertTrue("Legacy journal consumers must receive net-normalized pnlPct before TradeHistoryStore", exec.contains("paperâ†’live transfer authority") && exec.contains("PAPER_LIVE_TRANSFER_NET_PCT_NORMALIZED") && exec.indexOf("paperâ†’live transfer authority") < exec.indexOf("TradeHistoryStore.recordTrade(tradeWithMint)"))
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
        assertTrue("V5.0.6599: regime pivot remains a soft per-candidate score bias and visible in toolkit reasons", sheet.contains("regimeSetupBias(c.setup, regime)") && sheet.contains("fun causalScore(c: Candidate)") && sheet.contains("regimeBias="))
        assertTrue("Router must remap weak-CHOP/DUMP/risk_off styles through same-lane pivot logic before style application", router.contains("weakChopStylePivot") && router.contains("sameLaneWeakPivotStyle") && router.contains("isWeakRuntimeRegime") && router.contains("RegimeDetector.Regime.DUMP") && router.contains("isRiskOffSheet") && router.contains("Style.DEGEN_MICRO_SNIPE") && router.contains("Style.NARRATIVE_SOCIAL_IGNITION") && router.contains("classification.tradeType in setOf(ModeRouter.TradeType.FRESH_LAUNCH, ModeRouter.TradeType.SENTIMENT_IGNITION, ModeRouter.TradeType.GRADUATION") && router.contains("weakChopSheet && classification.tradeType == ModeRouter.TradeType.BREAKOUT_CONTINUATION"))
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
        assertTrue("Guard must preserve lane ownership and expose re-education treatment", guard.contains("chooseNonToxicLane") && guard.contains("filterNonToxic") && guard.contains("return lanes.firstOrNull") && guard.contains("treatmentFor"))
        assertTrue("Agentic style election must keep compatibility calls while same-lane style logic owns toxicity", router.contains("LaneToxicityGuard.chooseNonToxicLane") && router.contains("LaneToxicityGuard.filterNonToxic") && router.contains("sameLaneWeakPivotStyle"))
        assertTrue("MemeTrader owner rotation toxicity handling must no longer imply lane amputation", bot.contains("scoreForToxicity") && bot.contains("LaneToxicityGuard.filterNonToxic(rawOwnerPool") && guard.contains("Preserve original lane ownership"))
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
        assertTrue(bot.contains("val msEffectiveSize = moonshotFdgDecision?.sizeSol"))
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
        assertTrue("V5.0.6016: good Pump/Raydium/source-degraded candidates must feed successful meme lanes", bot.contains("SUCCESSFUL_MEME_SOURCE_BREADTH_6016") && bot.contains("out += \"QUALITY\"") && bot.contains("out += \"MOONSHOT\"") && bot.contains("out += \"BLUECHIP\""))
        assertFalse("V5.0.6016: generic Pump thin-liq must not source-feed MANIPULATED eval budget", bot.contains("out += \"MANIPULATED\""))
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
        assertTrue(hub.contains("raw rows are summarized, not dumped"))
        assertTrue(hub.contains("buildMutex"))
        val pipelineUi = java.io.File("src/main/kotlin/com/lifecyclebot/ui/PipelineHealthActivity.kt").readText()
        val errorUi = java.io.File("src/main/kotlin/com/lifecyclebot/ui/ErrorLogActivity.kt").readText()
        val forensicUi = java.io.File("src/main/kotlin/com/lifecyclebot/ui/LiveTradeLogActivity.kt").readText()
        val pipelineCopy = pipelineUi.substring(pipelineUi.indexOf("private fun copyToClipboardAsync"))
        val errorExport = errorUi.substring(errorUi.indexOf("private fun exportLogs"), errorUi.indexOf("private fun confirmClear"))
        val forensicClick = forensicUi.substring(forensicUi.indexOf("setOnClickListener"), forensicUi.indexOf("header.addView"))
        assertTrue("Pipeline copy/generate must use the 6308 dedicated-thread + watchdog path, not starved ReportingHub IO", pipelineCopy.contains("UNIFIED_REPORT_COPY_TAP_6308") && pipelineCopy.contains("PipelineHealth-GenerateCopy-6308") && pipelineCopy.contains("buildEmergencyPipelineReport6308") && pipelineCopy.contains("postDelayed"))
        assertTrue("Pipeline copy must deliver clipboard writes through mainHandler, with visible success/fail telemetry", pipelineCopy.contains("mainHandler.post") && pipelineCopy.contains("setPrimaryClip") && pipelineCopy.contains("UNIFIED_REPORT_COPY_OK_6308") && pipelineCopy.contains("UNIFIED_REPORT_COPY_FAIL_6308"))
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
        assertTrue("TradeJournal stats must validate JournalEntry rows with entry basis fields", journal.contains("JournalEntryâ†’Trade validation") && journal.contains("entryPriceSnapshot = e.entryPrice") && journal.contains("entryCostSol = e.entryCostSol") && journal.contains("price = if (sellLike) e.exitPrice else e.entryPrice"))
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
        val plan = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/TraderRuntimePlan6526.kt").readText()
        assertTrue("Meme runtime must treat legacy BOTH mode through the immutable runtime plan", plan.contains("cfg.tradingMode == 2 && memeOn") && bot.contains("plan6526.enabledTraderSet()"))
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
        assertFalse("SCORE_TOO_LOW must not become FDG green tiny probe", bot.contains("V3 REJECTâ†’PROBE"))
        assertFalse("SCORE_TOO_LOW must not set v3SizeSol from fdgDecision", bot.contains("V3-REJECT-PROBE"))
    }


    @Test
    fun live_buy_signature_confirmation_must_wait_for_authoritative_balance_proof() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("LIVE_BUY_SIDE_EFFECTS_DEFERRED_6637"))
        assertTrue(exec.contains("BUY_PENDING_BALANCE_PROOF"))
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
        assertTrue("Full MemeTrader ring must include previously idle internal lanes (V5.0.4599: specialists TRADERS not lanes)", listOf("MOONSHOT", "MANIPULATED", "QUALITY", "DIP_HUNTER", "TREASURY", "CASHGEN", "BLUECHIP").all { bot.contains(it) })
        assertTrue("Owner rotation must be affinity-first and toxicity-treated without lane amputation", bot.contains("affinityRanked") && bot.contains("rawOwnerPool") && bot.contains("LaneToxicityGuard.filterNonToxic(rawOwnerPool"))
        assertTrue("EXPRESS must use the same bounded lane gate and emit LANE_EVAL", bot.contains("expressLaneAllowedThisCycle") && bot.contains("lane=EXPRESS paper="))
        assertTrue(bot.contains("boundedRescue6600") && bot.contains("CONTRIBUTOR_ONLY") && bot.contains("LANE_SUPPRESSED_BY_OWNER_ROTATION"))
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
        assertTrue(bot.contains("exactly ONE canonical primary"))
        assertTrue(bot.contains("specialistEvaluationAllowed6600") && bot.contains("boundedRescue6600"))
        assertTrue(bot.contains("LIVE_ALL_LANE_CONTRIBUTION_4469"))
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
        assertTrue(router.contains("lowScoreBleedContext -> sameLaneWeakPivotStyle(laneHint, Style.DEFENSIVE_PROBE)"))
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
            // V5.0.4135 â€” workflow now composes VERSION_NAME from BASE + BUILD_NUMBER
            // (operator override 2026-06-25 â€” see apk_version_patch_derived_from_ci_run_number).
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
    fun paper_direct_executor_missing_state_cannot_synthesize_terminal_candidate() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertFalse("V5.0.6485: PAPER must not synthesize missing final candidates", gate.contains("PAPER_EXEC_OPEN_SYNTHETIC_FINAL_CANDIDATE"))
        assertTrue("V5.0.6485: LIVE direct-lane restore remains safety/liquidity bounded", gate.contains("modeUpper == " + "\"LIVE\"") && gate.contains("LIVE_EXEC_OPEN_SYNTHETIC_FINAL_CANDIDATE") && gate.contains("isRealExecutionLane(requestedLaneForSynth)") && gate.contains("liveLiquidityUsd > 0.0") && gate.contains("rug != 0"))
    }


    @Test
    fun meme_hot_pool_source_fix_preserves_balance_but_samples_healthy_runtime() {
        val service = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(service.contains("SUPERVISOR_HEALTHY_MEME_MAX_INFLIGHT: Int = 48"))
        assertTrue(service.contains("supervisorTimeoutsForPlanning = if ((nowMs - supervisorTimeoutWindowStartMs) < 600_000L) supervisorTimeoutWindowCount else 0"))
        assertTrue("selector should tolerate timeout noise below the planner pressure band", service.contains("val lowTimeoutNoise = supervisorTimeoutsForPlanning < 30") && service.contains("selectorHealthy = lowTimeoutNoise"))
        assertTrue("forced opens must never consume the discovery supervisor", service.contains("val forcedOpenForSupervisor: List<String> = emptyList()"))
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
        // V5.0.3915 â€” operator dump 06-19 19:28: the previous semantic
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
        assertTrue(snapshot.contains("TokenLifecycleTracker.liveMemeOpenCount()") && snapshot.contains("cleanup/reconcile inputs elsewhere, never live-open truth here"))
        assertTrue(snapshot.contains("val managedLiveOpen = maxOf(localLiveOpen, hostOpen, lifecyclePendingConfirmed, lifecycleOpen)"))
        assertTrue(snapshot.contains("confirmed-pending buys remain counted"))

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
    fun v3_live_handoff_requires_exact_canonical_fdg_intent() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val v3Buy = exec.substringAfter("fun v3Buy(").substringBefore("\n    fun ")
        assertTrue(gate.contains("recordFdgAndGetIntent6533") && gate.contains("allowTrunkExecutionHandoff6533"))
        assertTrue(bot.contains("val v3AttemptId = v3Intent6533.attemptId") && bot.contains("finalityPrechecked = false"))
        assertTrue(v3Buy.contains("V3_BUY_REJECTED_NO_EXACT_INTENT_6533"))
        assertFalse(v3Buy.contains("recentAllowedAttemptIdAnyLane"))
        assertFalse("Executor must not emit generic NO_FINAL_BUY_CANDIDATE", exec.contains("reason=FINALITY_BLOCK:NO_FINAL_BUY_CANDIDATE"))
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
        assertTrue(exec.contains("submitted live-open liability"))
        assertTrue(exec.contains("LIVE_BUY_PROOF_SIDE_EFFECTS_COMMITTED_6637"))
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
        // V5.0.3746 â€” operator spec items 1, 4, 5, 7, 9, 11.
        // BALANCE_UNKNOWN must hand the mint to BalanceProofPoller via the
        // WAITING_BALANCE_PROOF state â€” it MUST NOT enter PendingSellQueue
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
        assertFalse("one provider missing mint must not be zero", sellAuthority.contains("mint NOT in the map AND map is non-empty â†’ genuine zero"))

        assertTrue(tracker.contains("markNoCurrentHeldProof"))
        assertTrue(tracker.contains("RPC_EMPTY_MAP_MINT_ABSENT"))
        assertTrue(tracker.contains("NO_CURRENT_HELD_PROOF"))
        assertTrue(tracker.contains("HISTORICAL_RAW_NOT_CURRENT_HELD_PROOF"))
        assertTrue(tracker.contains("STALE_RECOVERY_UNPROVEN"))
        assertFalse("no current proof must not become open recovery", tracker.contains("OPEN_BALANCE_UNKNOWN_RECOVERY_REQUIRED"))
        assertTrue(tracker.contains("hasLastPositiveRaw(p)"))
        assertTrue(tracker.contains("isOpenForAccounting"))
        assertTrue(tracker.contains("p.zeroBalanceConfirmedByTwoProviders"))
        assertFalse("ghost reaper must not emit the old unverified close marker", tracker.contains("GHOST_REAPED zero-balance open row â†’ CLOSED"))
        assertFalse("startup reconcile must not close live rows from a bare wallet=0", tracker.contains("STARTUP_GHOST_RECONCILE wallet=0 â†’ CLOSED"))

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
        val plan = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/TraderRuntimePlan6526.kt").readText()

        assertTrue(plan.contains("cryptoUniverseOn") && plan.contains("Trader.CRYPTO_ALT"))
        assertTrue(bot.contains("plan6526.enabledTraderSet()"))
        assertTrue(bot.contains("CryptoAltTrader.start"))
        assertTrue(bot.contains("CryptoAltTrader.setEnabled(cryptoUniverseOn"))
        assertTrue("V5.0.6526: Crypto Universe/Markets derivation now flows through TraderRuntimePlan6526",
            bot.contains("com.lifecyclebot.engine.truth.TraderRuntimePlan6526.from(") &&
            bot.contains("plan6526.perpsEffective") &&
            bot.contains("plan6526.cryptoUniverseOn"))
        assertTrue("V5.0.4155: internal meme authority must include all specialist lanes except CYCLIC sidecar",
            auth.contains("internalMemeLayers") && auth.contains("set - Trader.CRYPTO_ALT - internalMemeLayers") && auth.contains("Trader.QUALITY") && auth.contains("Trader.TREASURY") && auth.contains("Trader.PROJECT_SNIPER") && !auth.substringAfter("val internalMemeLayers = setOf(").substringBefore(")").contains("Trader.CYCLIC"))
        assertTrue(auth.contains("return laneSet.size == 1 && Trader.MEME in laneSet"))
        assertTrue(crypto.contains("operatorExplicitlyEnabled"))
        assertTrue(crypto.contains("cfg.cryptoAltsEnabled && cfg.marketsTraderEnabled"))
        assertTrue("V5.0.6015: CryptoAltTrader must honor EnabledTraderAuthority CRYPTO_ALT over stale market toggles", crypto.contains("authorityAllowsCrypto6015") && crypto.contains("Trader.CRYPTO_ALT in authority") && crypto.contains("!cfg.paperMode && !authorityAllowsCrypto6015") && crypto.contains("CRYPTO_PAPER_LEARN_EVERYTHING_ADMITTED_6553"))
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
        assertTrue(snap.contains("TokenLifecycleTracker.liveMemeOpenCount()") && snap.contains("raw TokenLifecycleTracker.openCount() includes stale"))
        assertTrue(snap.contains("val managedLiveOpen = maxOf(localLiveOpen, hostOpen, lifecyclePendingConfirmed, lifecycleOpen)"))
        assertTrue(snap.contains("canonical LIVE truth is MANAGED live truth"))
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
        // V5.0.6060 â€” operator directive: revert daily batching, transfer fees per-cycle live.
        // FeeAccumulator still exists as a per-cycle safety net (transient send failures fall
        // into FeeRetryQueue) but the threshold is now sub-cent so tryFlush() drains every
        // scan cycle rather than holding to 1 SOL. Golden tape must assert the LIVE behaviour.
        assertTrue("fee accumulator must be configured for live per-cycle transfer (V5.0.6060 revert)",
            accumulator.contains("DEFAULT_FLUSH_THRESHOLD_SOL = 0.0001") &&
            accumulator.contains("val totalPending") &&
            accumulator.contains("totalPending < flushThresholdSol") &&
            accumulator.contains("LIVE PER-CYCLE TRANSFER"))
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
            tracker.contains("REAP_SKIPPED_BALANCE_UNKNOWN mint absent from one wallet snapshot â€” keeping open"))
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
    fun wallet_rpc_rejects_tls_trust_failures_without_bypass() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        assertTrue(src.contains("WALLET_RPC_TLS_REJECTED"))
        assertFalse(src.contains("WALLET_RPC_TLS_FALLBACK_USED"))
        assertFalse(src.contains("unsafeWalletRpcClient"))
        assertFalse(src.contains("hostnameVerifier"))
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
        assertFalse("transaction-signing traffic must not trust user-installed CAs", net.contains("<certificates src=\"user\""))
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
        // V5.0.6405 Â§18 â€” the (sol/qty)Ã—solUsd formula was refactored into
        // EntryPriceIntegrityAuthority6405.deriveTrustedEntryUsd requires
        // the immutable event-time SOL/USD witness. Missing price truth
        // keeps the fill pending; it never substitutes a fixed price.
        assertTrue(exec.contains("EntryPriceIntegrityAuthority6405"))
        assertTrue(exec.contains("deriveTrustedEntryUsd"))
        assertTrue(exec.contains("entrySolUsdWitness6637"))
        assertFalse(exec.contains("SOL_USD_COLD_FALLBACK"))
        assertTrue(exec.contains("trustedEntry6405?.source ?: \"LIVE_PROOF_COST_BASIS\""))
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
        assertTrue(src.contains("Invalid price input â€” hold until trustworthy price"))
        assertFalse("invalid guide/basis price must not force a sell", src.contains("Invalid price input â€” forced exit"))
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
            exec.contains("SELL RPC EMPTY/TIMEOUT: getTokenAccountsWithDecimals â€” proceeding via RPC-EMPTY rescue"))
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
    fun dropped_wallet_token_recovery_is_enabled_but_guarded() {
        val tracker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/HostWalletTokenTracker.kt").readText()
        assertTrue("V5.0.6034: wallet-held dropped tokens must be recoverable into tracker for exit management", tracker.contains("RECOVER_ORPHAN_WALLET_TOKENS: Boolean = true") && tracker.contains("V5.0.6034") && tracker.contains("Unknown-basis recovered rows remain WALLET_RECONCILED") && tracker.contains("ORPHAN_WALLET_TOKEN_ATTACHED"))
        assertTrue(tracker.contains("if (!RECOVER_ORPHAN_WALLET_TOKENS) {"))
        assertTrue(tracker.contains("ORPHAN_WALLET_TOKEN_IGNORED"))
        assertTrue(tracker.contains("fun purgeOrphanRecoveredRows"))
        assertTrue(tracker.contains("ORPHAN_WALLET_TOKEN_PURGED"))
        assertTrue(tracker.contains("purgeOrphanRecoveredRows(\"INIT\")"))
        assertTrue(tracker.contains("purgeOrphanRecoveredRows(\"WALLET_SNAPSHOT\")"))

        // The legacy ignore branch remains as an emergency flag-off guard, but the default
        // is now recovery-on so dropped wallet-held bags become monitored OPEN_TRACKING rows.
        val guardIdx = tracker.indexOf("ORPHAN_WALLET_TOKEN_IGNORED")
        val adoptIdx = tracker.indexOf("symbol = \"RECOVERED_")
        assertTrue("legacy orphan ignore guard must remain before recovery builder for emergency flag-off safety", guardIdx in 1 until adoptIdx)
    }


    @Test
    fun runtime_state_header_reads_canonical_authority_and_stop_does_not_resave() {
        val pipe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val svc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        // Fault #6 â€” header Execution state must derive from BotRuntimeController, not a hardcoded ACTIVE.
        assertTrue(pipe.contains("BotRuntimeController.snapshot()"))
        assertTrue(pipe.contains("POST_STOP_SNAPSHOT"))
        assertTrue(pipe.contains("RuntimeState.STOPPED"))
        assertTrue(pipe.contains("RuntimeState.STOPPING"))
        assertTrue(pipe.contains("RuntimeState.STARTING"))
        assertFalse("header must not unconditionally guess ACTIVE without consulting runtime authority",
            pipe.contains("val state = if (blockedMs > 0 && ageSec in 0..120) {"))

        // Fault #1 â€” manual stop finalizes persistence and onDestroy must not re-save stale rows.
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
        // OPEN_TRACKING mint at dust must reap it â€” not keep it open and latch
        // SELL_ONLY_SAFE_MODE via openCountMismatch / pendingSellQueue.
        assertTrue(recon.contains("DUST_RAW_REAP"))
        assertTrue(recon.contains("DUST_ZOMBIE_POSITION_REAPED"))
        assertTrue(recon.contains("if (walletRawExact > java.math.BigInteger.valueOf(DUST_RAW_REAP)) continue"))
        assertFalse(recon.contains("rawApprox <= DUST_RAW_REAP"))
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
        assertTrue(cls.contains("no signature â€” clear lock unless retry scheduled"))

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

        val reqGuard = exec.indexOf("V5.0.3801 â€” PAPER source guard before any executor activity")
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

        assertTrue("SQLite schema must version linkage columns", store.contains("const val DB_VERSION = 8"))
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
        assertTrue("JournalActivity count must include BUY, SELL, and partial lifecycle rows", journalActivity.contains("tvJournalCount.text = entries.size.toString()") && journalActivity.contains("val sellEntries = entries.filter") && journalActivity.contains("stats only; visible list is full lifecycle"))
        assertFalse("partials must not be demoted to terminal-only stats", journal.contains("terminalSells") || journalActivity.contains("isTerminalSell"))

        assertTrue("capital recovery partial must store realized leg pct, not full-position gainPct", executor.contains("val paperLegPct = pct(paperCostBasis, sellSol)") && executor.contains("pnlSol, paperLegPct"))
        assertTrue("profit lock partial must share the same canonical leg-accounting path", executor.contains("executeProfitLockSellPaperOrLive") && executor.contains("CanonicalPaperPartialOperation6510.commit"))
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
        assertTrue("LaneExpectancyDamper must use clean live terminal expectancy only", damper.contains("StrategyTelemetry.computeCleanLiveTerminalLeaderboard()") && !damper.contains("StrategyTelemetry.computeLeaderboard()"))
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
        val plan = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/TraderRuntimePlan6526.kt").readText()
        assertTrue("Authority enum must expose every internal meme layer except disabled CYCLIC sidecar", listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "QUALITY", "TREASURY", "CASHGEN", "BLUECHIP", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER").all { auth.contains(it) })
        assertTrue("Meme-only publish must include full internal specialist set except CYCLIC", listOf("Trader.QUALITY", "Trader.TREASURY", "Trader.CASHGEN", "Trader.BLUECHIP", "Trader.PROJECT_SNIPER", "Trader.DIP_HUNTER", "Trader.MANIPULATED").all { plan.contains(it) } && !plan.contains("s += EnabledTraderAuthority.Trader.CYCLIC") && bot.contains("plan6526.enabledTraderSet()"))
        assertTrue("Internal specialists must be ignored by isMemeLiveOnly so markets/perps remain isolated; CYCLIC must not be an internal meme layer", auth.contains("internalMemeLayers") && auth.contains("Trader.PROJECT_SNIPER") && auth.contains("set - Trader.CRYPTO_ALT - internalMemeLayers") && !auth.substringAfter("val internalMemeLayers = setOf(").substringBefore(")").contains("Trader.CYCLIC"))
        assertTrue("Runtime plan must expose active meme lanes and Cyclic must follow paper/live authority", plan.contains("fun enabledTraderSet()") && bot.contains("val cyclicEnabled6563 = plan6526.paperMode || marketsStartCfg.cyclicTradeEnabled") && !bot.contains("CyclicTradeEngine.setEnabled(false)"))
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
        val buyBlock = exec.substring(exec.indexOf("internal fun doBuy"), exec.indexOf("// V5.9.401 â€” Sentience hook #7"))
        assertTrue("Sentience pre-trade veto must be advisory telemetry only", buyBlock.contains("SENTIENCE_VETO_ADVISORY_4189") && buyBlock.contains("ignored_no_hard_veto") && !buyBlock.contains("LLM SENTIENCE VETO"))
        assertTrue("Emergent LLM BLOCK must not return before live buy", buyBlock.contains("EMERGENT_LLM_BLOCK_ADVISORY_4189") && buyBlock.contains("LLM BLOCK ADVISORY") && !buyBlock.contains("ðŸ§  LLM BLOCK: ${'$'}{ts.symbol}"))
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
            "ADVISORY_SHAPE must not freeze FDG-approved executable lanes; it preserves pre-pivot lane authority while style/tactic pivots internally",
            exec.contains("action=preserve_executable_lane") &&
                exec.contains("val prePivotExecutableLane") &&
                exec.contains("val canonicalRoutedLane = when") &&
                exec.contains("stylePivotAdvisory -> prePivotExecutableLane") &&
                exec.contains("STYLE_PIVOT_INNER_LANE_EXECUTABLE") &&
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
    fun style_pivot_inner_lane_can_be_executable_with_proof() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "Style-pivot advisory must preserve throughput through lane-local executable pivots",
            exec.contains("STYLE_PIVOT_INNER_LANE_EXECUTABLE") &&
                exec.contains("innerLaneExecutablePivot") &&
                exec.contains("pivotHasExecutionProof") &&
                exec.contains("stylePivotAdvisory -> prePivotExecutableLane")
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
        assertTrue("EXPRESS bleeder must pivot inside EXPRESS, not promote to quality", router.contains("EXPRESS_BLEEDER_INNER_LANE_PIVOT") && router.contains("EXPRESS_BLEEDER_AWAIT_QUALITY_PROOF"))
        assertTrue("Treasury/CashGen must be an active lane-local promotion target", router.contains("TREASURY_CASHGEN_LANE_LOCAL_PROMOTED") && breakEven.contains("\"CASHGEN\" -> setOf(\"TREASURY\")"))
        assertTrue("CYCLIC bleeder must pivot inner tactic only with proof", router.contains("CYCLIC_PULLBACK_RECLAIM_INNER_LANE_PIVOT") && router.contains("CYCLIC_BLEEDER_INNER_LANE_PIVOT") && router.contains("CYCLIC_BLEEDER_AWAIT_QUALITY_PROOF"))
        assertTrue("WHALE/COPY cannot direct-trigger full live; it must become lane-local confirmation", router.contains("WHALE_COPY_INNER_LANE_CONFIRMATION_NO_DIRECT_TRIGGER") && router.contains("WALLET_RECOVERED_PROVEN_PROMOTION") && router.contains("WHALE_COPY_AWAIT_REPEAT_WIN_AND_PROOF"))
        assertTrue("MOONSHOT S41-60 must not native-live buy; only LDQ rescue or toxic defer", router.contains("MOONSHOT_S41_60_INNER_LANE_SMART_CONFIRMATION_V4545") && router.contains("MOONSHOT_S41_60_LIVE_TOXIC_DEFER_V4153") && router.contains("scoreBand == \"S41-60\""))
        assertTrue("SHITCOIN live bleed must re-educate/depth-shape, not rename itself into quality", router.contains("SHITCOIN_LIVE_BLEED_REEDUCATE_VOLUME_IGNITION") && !router.contains("SHITCOIN_LIVE_BLEED_QUALITY_PROMOTION") && router.contains("SHITCOIN_THIN_ROUTE_DEPTH"))
        assertTrue("Fresh 1k-5k SHITCOIN depth must become live-adaptive or clean high-confidence reduced quality routing", router.contains("SHITCOIN_THIN_ROUTE_DEPTH_LIVE_ADAPTIVE_REEDUCATE_4545") && router.contains("pivotThinDepthToQuality") && router.contains("LiveMaturityAuthority.snapshot()") && router.contains("cleanHighConfidenceBootstrap"))
        assertTrue("Low-score QUALITY and thin PRESALE/TREASURY live entries must defer at source", router.contains("QUALITY_LOW_SCORE_LIVE_DEFER") && router.contains("PRESALE_AWAIT_MIN_DEPTH_AND_PROOF") && router.contains("TREASURY_CASHGEN_AWAIT_DEPTH_SCORE_PROOF"))
        assertTrue("LiveBreakEvenGuard must use live-first terminal edge with capped paper advisory", breakEven.contains("liveTerminalEdge") && breakEven.contains("paperAdvisoryEdge") && breakEven.contains("TradeHistoryStore.getRecentValidClosedTrades") && breakEven.contains("LIQUIDITY_DEPTH_QUALITY") && breakEven.contains("PULLBACK_RECLAIM"))
        assertTrue("LiveBreakEvenGuard must calculate all-in required edge", breakEven.contains("buySlippagePct") && breakEven.contains("expectedSellSlippagePct") && breakEven.contains("priorityFeePct") && breakEven.contains("platformFeePct") && breakEven.contains("givebackBufferPct") && breakEven.contains("minProfitBufferPct"))
        assertTrue("Router must emit required live break-even decision log", router.contains("LIVE_BREAK_EVEN_CHECK") && router.contains("expectedEdge") && router.contains("requiredEdge") && router.contains("pivotReason"))
        assertTrue("Below-cost routes must defer for real quality edge instead of probe, while green bootstrap/live-adaptive clean-proof quality gaps can full-quality release outside toxic MOONSHOT S41-60", router.contains("BREAK_EVEN_DEFER_QUALITY_EDGE_NOT_CONFIRMED") && router.contains("BREAK_EVEN_LIVE_ADAPTIVE_FULL_QUALITY_RELEASE") && router.contains("BREAK_EVEN_GREEN_BOOTSTRAP_FULL_QUALITY_RELEASE") && router.contains("canGreenBootstrapFullQualityRelease") && router.contains("qualityReleaseMultiplier") && router.contains("liveBootstrapGreen") && router.contains("MOONSHOT_S41_60_LIVE_TOXIC_DEFER_V4153"))
        val growth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        assertTrue("Live growth doctrine must be materially aggressive for 2x-5x/day target", growth.contains("AGGRESSIVE_2X_5X_LIVE_WALLET_GROWTH") && growth.contains("\"MOONSHOT\" -> 0.35") && growth.contains("walletSol < 10.0 -> 1.250"))
        assertTrue("Final live sizing must emit full growth/cap telemetry", exec.contains("GROWTH_MODE_TRACE") && exec.contains("liquidityCap") && exec.contains("walletCap") && exec.contains("minExec"))
        assertTrue("V5.0.6083: all paper/live lanes must receive tick-time runner/hard-floor protection", bot.contains("V5.0.6083") && bot.contains("val tickProfitLockEligible = true") && !bot.contains("""ForensicLogger.lifecycle("TICK_PROFIT_LOCK_SKIPPED_LANE"""))
        assertTrue("V5.0.4152: tick/universal peak-lock exits must use the same FluidLearningAI high-lock floor shown in UI, not stale loose peak ratios",
            bot.contains("UI/EXEC HIGH-LOCK PARITY") && bot.contains("TICK_PROFIT_LOCK_EXEC_PRICE_REBASE") &&
            bot.contains("FluidLearningAI.getDynamicFluidStop") && bot.contains("pnlPctNow >= lockedFloor") &&
            bot.contains("UNIVERSAL_PEAK_LOCK_peak") && bot.contains("fluidLockFloor") && bot.contains("highLockImmediate"))
        val exec4153 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.4153: cumulative 100pct partials must stamp terminal FULL_EXIT_100PCT, not partial_100pct", exec4153.contains("FULL_EXIT_100PCT") && exec4153.contains("newSoldPct >= 99.9") && !exec4153.contains("partial_100pct"))
        assertTrue("BleederMemoryRouter must use live-only recent closed rows", bleeder.contains("mode.equals(\"live\"") && bleeder.contains("n20") && bleeder.contains("n50") && bleeder.contains("n100") && bleeder.contains("deepLosses50") && bleeder.contains("failedBasisCount") && bleeder.contains("orphanCount"))
        assertTrue("liveBuy must emit decision before lease/quote, apply pivot size, and bucket style-pivot advisory reasons", exec.contains("LIVE_ENTRY_DECISION") && exec.contains("LiveStylePivotRouter.route") && exec.contains("LIVE_STYLE_PIVOT_SIZE_APPLIED") && exec.contains("STYLE_PIVOT_ADVISORY") && exec.contains("STYLE_PIVOT_ADVISORY_REASON_"))
        assertTrue(
            "proof-finalized live journal must preserve the pivoted lane stamped on the provisional position",
            exec.contains("tradingMode  = routedLaneTag") &&
                exec.contains("tradingMode = ts.position.tradingMode.ifBlank") &&
                exec.contains("routedLaneTag.ifBlank"),
        )
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
        assertTrue("Unified report must have a hard chat-size budget", hub.contains("MAX_UNIFIED_REPORT_CHARS = 100_000") && hub.contains("REPORT_TRUNCATED_UNEXPECTED") && hub.contains("PASTE_SAFE_V6048"))
        assertTrue("Unified report scope must include learning/tuning/journal", hub.contains("learning / tuning / journal") && hub.contains("LEARNING + TUNING STATE") && hub.contains("TRADE JOURNAL SUMMARY"))
        assertTrue("Unified report must use compact core pipeline, not raw full dump only", hub.contains("compactPipelineDump(PipelineHealthCollector.dumpText())") && hub.contains("PIPELINE HEALTH â€” CORE"))
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
    fun event_triggered_sentience_feeds_strategy_authority_without_hot_path_calls() {
        val sentience = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SentienceOrchestrator.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeDoctor.kt").readText()
        assertTrue(sentience.contains("EVENT_TRIGGERED_SENTIENCE_SAFE"))
        assertTrue(sentience.contains("fun noteRuntimeEvent"))
        assertTrue(sentience.contains("event_to_strategy_authority_6090"))
        assertTrue(sentience.contains("feeding this into autonomous strategy authority"))
        assertTrue(sentience.contains("no Gemini/Groq/LLM call here"))
        assertTrue(doctor.contains("publishSentienceEventReflections"))
        assertTrue(doctor.contains("SentienceOrchestrator.noteRuntimeEvent"))
        assertFalse("safe autonomy event reflection must not reintroduce 3807 scorer telemetry hook", java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/UnifiedScorer.kt").readText().contains("AIStackSnapshot"))
    }


    @Test
    fun losing_pattern_memory_soft_sizes_emerging_bootstrap_bleeders_before_maturity() {
        val losing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LosingPatternMemory.kt").readText()
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

        assertTrue("LosingPatternMemory must define emerging danger before mature sample=10", losing.contains("val isEmergingDanger"))
        assertTrue("emerging danger must be sample gated at 4..9 (V5.0.4597 tighter learning)", losing.contains("sample in 4..9"))
        assertTrue("emerging danger must require net-negative mean", losing.contains("meanPnl <= -3.0"))
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
        val closeConvergence6509 = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperTerminalProjectionConvergence6509.kt").readText()

        assertTrue("paper close authority must exist", paperClose.contains("object PaperPositionCloseAuthority"))
        assertTrue("paper authority must expose CLOSE_REQUESTED", paperClose.contains("CLOSE_REQUESTED"))
        assertTrue("paper authority must low-rate duplicate close telemetry", paperClose.contains("PAPER_CLOSE_ALREADY_PENDING"))

        val guardIdx = exec.indexOf("PaperPositionCloseAuthority.preSellGuard")
        val traceIdx = exec.indexOf("ExecutionRootCauseTrace.sell(\"DO_SELL_ENTRY\"")
        assertTrue("paper close guard must run before DO_SELL_ENTRY / EXEC_TRACE_SELL", guardIdx >= 0 && traceIdx >= 0 && guardIdx < traceIdx)
        assertTrue("first paper close must mark requested before trace", exec.contains("PaperPositionCloseAuthority.markCloseRequested"))
        assertTrue("paperSell must finalize the paper authority when ledger closes", closeConvergence6509.contains("PaperPositionCloseAuthority.markClosed(\"PAPER\", mint"))

        assertTrue("paper buy must clamp before position and journal mutation", exec.contains("PAPER_SEALED_NOTIONAL_CONSUMED_6552") && exec.contains("sealedNotional6552"))
        assertTrue("paper buy max must be bankroll-backed live-transfer size, not legacy maxPositionSol micro-cap", exec.contains("ALL PAPER ENTRIES") && exec.contains("paperSimulatedBalance * 0.10") && exec.contains("coerceIn(legacyMax, 2.0)"))
        // V5.0.6511 â€” executable minimum is independent from requested sizing and wallet percentage.
        assertTrue("paper buy minimum must use the independent bounded runtime floor", exec.contains("PaperPreTicketSizeFloor6511.boundedMinimum(runtimeMinimum6511)") && !exec.substring(exec.indexOf("private fun minConfiguredPaperTradeSol"), exec.indexOf("private fun clampPaperTradeSol")).contains("c.smallBuySol"))
        assertTrue("paper buy sizing helper must delegate to the sole canonical resolver", exec.contains("PAPER_SIZE_CANONICAL_RESOLVER_6510") && exec.contains("OrderSizeResolver6441.resolve("))

        // V5.0.6366 â€” F4 raised the paper learning-eligibility ceiling from
        // paperSimulatedBalance * 0.10 (clamped to 2.0) to paperSimulatedBalance * 0.25
        // (clamped to [2.0, 20.0]) so paper closes larger than 2.0 SOL stop being
        // silently starved from the learning aggregators. Golden tape now enforces
        // the new bounds instead of the old fixed 2.0 hard cap.
        assertTrue("paper sanity must use proportional live-transfer sizing bounds before quarantining rows",
            paperSanity.contains("paperSimulatedBalance * 0.25") &&
                paperSanity.contains("paperSimulatedBalance * 0.01") &&
                paperSanity.contains("coerceIn(2.0, 20.0)") &&
                paperSanity.contains("PAPER_SOL_ABOVE_CONFIG_MAX"))
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
        assertTrue("Paper hero headline must render canonical spendable CASH and show equity in the accessible breakdown (V5.0.6616 Â§JOURNAL_BALANCE_HERO_SINGLE_AUTHORITY_REPAIR)",
            main.contains("UnifiedAccountSnapshot6635.read(\"MEME\")") && main.contains("PAPER Â· CASH") &&
                main.contains("unifiedSnap6635?.cashSol") && main.contains("tvBalanceLarge.setTextIfChanged(compactHeroBalance(balSol))") &&
                main.contains("ACCOUNTING ERROR") &&
                !main.contains("PaperWalletStore.restore"))
        assertTrue("Paper hero must remain mobile-safe while exposing the canonical five-surface breakdown",
            main.contains("compactHeroBalance") && main.contains("PAPER Â· CASH") &&
                main.contains("OPEN_MV") && main.contains("UNREALIZED") && main.contains("REALIZED") && main.contains("EQUITY") &&
                !main.contains("ðŸ“ PAPER CASH â—Ž") && !main.contains("equityâ‰ˆâ—Ž") && !main.contains("displayBankrollSol"))
        assertTrue("Paper PnL and win rate must be fail-closed account/portfolio values, never a mixed MEME/global percentage", main.contains("DeskPerformanceAuthority6648.Book.PORTFOLIO") && main.contains("ACCOUNT REALIZED") && main.contains("PORTFOLIO WR") && main.contains("ACCOUNT UNAVAILABLE") && !main.contains("val startCapitalSol = (balSol - pnl)"))
        assertTrue("Paper equity detail must come only from canonical capital authority, not an open-position UI projection", main.contains("CanonicalCapitalAuthority6450.snapshot()") && main.contains("contentDescription") && !main.contains("paperEquityAtCostSol") && !main.contains("paperOpenCostSol"))
        assertTrue("Hero XML must be a premium command card, not a flat debug balance row", mainLayout.contains("commandHeroCard") && mainLayout.contains("@drawable/aate_hero_premium_bg") && mainLayout.contains("AATE COMMAND") && mainLayout.contains("rowSymTelemetry"))
        assertTrue("Hero XML must protect mobile width with auto-size headline and capped mode chip", mainLayout.contains("android:autoSizeTextType=\"uniform\"") && mainLayout.contains("android:maxWidth=\"66dp\"") && mainLayout.contains("android:ellipsize=\"end\"") && mainLayout.contains("android:autoSizeMaxTextSize=\"32sp\""))
        assertTrue("Main UI restyle must use the AATE premium visual system", mainLayout.contains("@drawable/aate_screen_bg") && mainLayout.contains("@drawable/aate_metric_card_bg") && mainLayout.contains("@drawable/aate_signal_card_bg") && mainLayout.contains("@drawable/aate_nav_tile_bg") && mainLayout.contains("@drawable/aate_nav_icon_badge_bg") && mainLayout.contains("MISSION CONTROL Â· NAVIGATION DECK") && !mainLayout.contains("@drawable/stats_pill_bg"))
        assertTrue("Mission Control must be a consistent 3x5 deck, not uneven rows of debug buttons", mainLayout.contains("missionControlDeck") && mainLayout.contains("Row 1: primary operating surfaces") && mainLayout.contains("Row 2: configuration and strategy tools") && mainLayout.contains("Row 3: operator diagnostics") && mainLayout.split("@drawable/aate_nav_tile_bg").size - 1 == 15 && mainLayout.split("@drawable/aate_nav_icon_badge_bg").size - 1 == 15)
        assertTrue("Mission Control icons must be large semantic vector pictograms, not tiny letter abbreviations", listOf("aate_ic_wallet", "aate_ic_journal", "aate_ic_markets", "aate_ic_lab", "aate_ic_pipeline", "aate_ic_settings", "aate_ic_logs", "aate_ic_persona", "aate_ic_crypto", "aate_ic_behavior", "aate_ic_universe", "aate_ic_phase", "aate_ic_learning", "aate_ic_forensics", "aate_ic_tuning").all { mainLayout.contains("@drawable/$it") } && mainLayout.split("android:minWidth=\"42dp\"").size - 1 == 15 && listOf("WA", "JR", "MK", "LB", "PL", "ST", "LG", "PS", "CR", "BT", "UN", "PH", "FX", "LN").none { mainLayout.contains("android:text=\"$it\"") })
        assertTrue("Seven-wide trader layer chips must use compact non-wrapping labels on mobile", listOf("SNIP", "CASH", "BLUE", "RISK", "FAST", "MANIP", "MOON").all { mainLayout.contains("android:text=\"$it\"") } && listOf("TARGET", "TREASURY", "BLUECHIP", "HIGH-RISK", "SIGNAL", "LAUNCH").none { mainLayout.contains("android:text=\"$it\" android:textSize=\"18sp\"") } && mainLayout.split("android:singleLine=\"true\"").size - 1 >= 7)
        assertTrue("Mission Control must preserve hidden Alerts wiring without adding a visible extra tile", mainLayout.contains("btnQuickAlerts") && mainLayout.contains("android:visibility=\"gone\"") && mainLayout.split("@drawable/aate_nav_tile_bg").size - 1 == 15)
        assertTrue("Live Readiness and bottom runtime controls must use the premium redesigned surfaces", mainLayout.contains("@drawable/aate_readiness_card_bg") && mainLayout.contains("@drawable/aate_readiness_metric_bg") && mainLayout.contains("@drawable/aate_runtime_bar_bg") && mainLayout.contains("LIVE READINESS Â· MEME") && mainLayout.contains("Readiness progress"))
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
        val consumerEmojiChrome = listOf("ðŸ§ ", "ðŸ’Ž", "ðŸª™", "ðŸ“ˆ", "ðŸ“Š", "ðŸ‘›", "ðŸ›", "ðŸŽš", "ðŸŽ­", "ðŸ§ª", "ðŸ”¥", "ðŸ›¡", "âš¡", "ðŸ“¥", "ðŸ“¦", "ðŸ’¾", "ðŸ—‘", "ðŸ””", "ðŸ”’", "ðŸš€", "ðŸ†", "ðŸŒ¡")
        assertTrue("AATE XML chrome must read like an institutional product, not emoji-labeled consumer crypto UI", activityLayouts.all { layout -> val xml = layout.readText(); consumerEmojiChrome.none { token -> xml.contains("android:text=\"$token") || xml.contains("$token ") } })
        assertTrue("Main runtime chrome must render institutional readiness/trader copy", !main.contains("ðŸš€ Live Readiness") && !main.contains("ðŸ§  All Traders") && !main.contains("ðŸ“Š ${'$'}perAssetLine") && !main.contains("ðŸ›¡ Guards") && !main.contains("ðŸ† Top-3") && main.contains("LIVE READINESS Â· MEME") && main.contains("PORTFOLIO Â· ${'$'}totalTrades trades"))
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
        assertTrue("Impossible paper wallet balances must repair from journal-derived cash authority", bot.contains("PAPER_WALLET_IMPOSSIBLE_REPAIRED") && bot.contains("PAPER_WALLET_IMPOSSIBLE_DIAGNOSTIC_ONLY_6475") && bot.contains("expectedCash") && bot.contains("action=no_wallet_heal_from_journal"))
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
        assertTrue("Pipeline PerformanceAnalytics must read bounded canonical TradeHistoryStore rows, not legacy TradeDatabase or full journal copies", phc.contains("canonicalPerformanceTrades") && (phc.contains("TradeHistoryStore.getRecentValidClosedTrades") || phc.contains("TradeHistoryStore.getRecentCleanStrategyTerminalTrades")) && !phc.contains("BotService.instance?.tradeDb"))
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

        assertTrue("Raw zero liquidity is TokenMap-pending; only TRUE_ZERO_LIQUIDITY hard-blocks after provider quorum", safety.contains("LIQUIDITY_UNKNOWN_PENDING_TOKEN_MAP") && preTrade.contains("TRUE_ZERO_LIQUIDITY") && !safety.contains("ZERO LIQUIDITY â€” no executable route"))
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
        // V5.0.4117 â€” AGI stack must be wired into buy sizing
        assertTrue("AGI size stack: LiveStrategyTuner.sizeMult must be in multiplierProduct", executor.contains("strategyTunerSizeMult") && executor.contains("LiveStrategyTuner.sizeMultiplier"))
        assertTrue("AGI size stack: ScannerSourceBrain.intakeMultiplier must be in multiplierProduct", executor.contains("sourceBrainSizeMult") && executor.contains("ScannerSourceBrain.intakeMultiplier"))
        assertTrue("AGI size stack: UnifiedPolicyHead.conviction must be in multiplierProduct", executor.contains("uphConvictionMult") && executor.contains("UnifiedPolicyHead.conviction"))
        assertTrue("AGI size stack: AGI_SIZE_STACK_APPLIED telemetry must be emitted", executor.contains("AGI_SIZE_STACK_APPLIED"))
        // V5.0.4118 â€” all lanes must have pivot paths in LiveStylePivotRouter
        val pivotRouter = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText()
        assertTrue("Pivot router must handle STANDARD lane (was missing, starving volume)", pivotRouter.contains(""""STANDARD" ->"""))
        assertTrue("Pivot router must handle MANIPULATED lane (was missing, starving volume)", pivotRouter.contains(""""MANIPULATED" ->"""))
        assertTrue("Pivot router must handle DIP_HUNTER lane (was missing, starving volume)", pivotRouter.contains(""""DIP_HUNTER" ->"""))
        assertTrue("STANDARD must have bleeder inner-lane pivot path", pivotRouter.contains("STANDARD_BLEEDER_INNER_LANE_PIVOT"))
        assertTrue("MANIPULATED must have volume ignition confirmation path", pivotRouter.contains("MANIPULATED_NATIVE_VOLUME_IGNITION_CONFIRMED"))
        assertTrue("DIP_HUNTER must have pullback reclaim promotion path", pivotRouter.contains("DIP_HUNTER_PULLBACK_RECLAIM_CONFIRMED"))
        // V5.0.4119 â€” break-even guard buffers reduced to open mid-score volume
        val breakEven = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveBreakEvenGuard.kt").readText()
        assertTrue("Break-even giveback buffer for default lanes must be 1.5 (was 2.5)", breakEven.contains("else -> 1.5"))
        assertTrue("Break-even minProfit buffer for default lanes must be 2.0 (was 5.0)", breakEven.contains("else -> 2.0"))
        val toxicMoonNative = "MOONSHOT_" + "S55_60_" + "NATIVE_SIZE_SHAPED"
        assertTrue("MOONSHOT S41-60 live-toxic bucket must not have a native size-shaped path", !pivotRouter.contains(toxicMoonNative) && pivotRouter.contains("MOONSHOT_S41_60_LIVE_TOXIC_DEFER_V4153"))
        assertTrue("MOONSHOT S41-60 may only rescue through independent LDQ quality proof", pivotRouter.contains("MOONSHOT_S41_60_INNER_LANE_SMART_CONFIRMATION_V4545"))
        // V5.0.4121 â€” LayerBrain integration for SmartMoneyDivergenceAI + HoldTimeOptimizerAI
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
        assertTrue("MainActivity must shed row-heavy rendering only for bounded recent ANR windows", main.contains("MAIN_HEAVY_RENDER_ANR_SHED") && main.contains("anrHintCountNow()") && main.contains("newAnrHint6655") && main.contains("lastAnrHeavyRenderShedArmMs6655") && !main.contains("anrHintsForRenderShed >= 100") && (main.contains("skip=heavy_dashboard_rows") || main.contains("skip=non_open_heavy_dashboard_rows")))
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

        // V5.0.3919 â€” extract ONLY the executionProviderLabels array literal so
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
        assertTrue("V5.0.4559: fresh Raydium/new-pool buys must try PumpPortal auto before Jupiter quote exhaustion", exec.contains("pumpPortalAutoEligible4559") && exec.contains("SCANNER_DIRECT_RAYDIUM_NEW_POOL") && exec.contains("Fresh Raydium/new-pool routes often are not") && exec.contains("deep_amm_use_jupiter_first"))

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
        assertTrue("live finality must synthesize a current direct-lane candidate when state is missing but safety/liquidity are present", gate.contains("modeUpper == " + "\"LIVE\"") && gate.contains("LIVE_SYNTHETIC_FINAL_CANDIDATE") && gate.contains("LIVE_EXEC_OPEN_SYNTHETIC_FINAL_CANDIDATE"))
    }


    @Test
    fun live_finality_watch_and_empty_drain_safe_mode_must_not_choke_live_buys() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val safe = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/SellOnlySafeMode.kt").readText()
        assertTrue("FDG-approved WATCH/PROBE must be restorable when current candidate is safe/liquid", gate.contains("verdictAllowedByFdg") && gate.contains("WATCH") && gate.contains("PROBE") && gate.contains("LIVE_RESTORE_STALE_WATCH_SOFT_ALLOW"))
        assertTrue("WATCH restore must be backed by FDG/ticket authority, safety, liquidity, and no hardNo", gate.contains("verdictAllowedByFdg") && gate.contains("liqOk") && gate.contains("effectiveHardNoReasons.isEmpty()") && gate.contains("ExecutionIntent"))
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
        assertTrue("V5.0.6600: live MemeTrader retains qualified evidence and allows only source/style owner plus bounded rescue before one-mint claim", bot.contains("LIVE_RING_OWNER_COLLAPSE") && bot.contains("LIVE_ALL_LANE_CONTRIBUTION_4469") && bot.contains("boundedRescue6600") && bot.contains("claimedOwner6600") && !bot.contains("strongestDesk6599"))
        assertTrue("V5.0.6483: paused owner lanes remain bounded owners but pivot tactics before execution", bot.contains("OWNER_LANE_TACTIC_PIVOT_6483") && bot.contains("LaneAutoPauseGuard.isPaused(l)"))
        assertTrue("V5.0.6483: successful-lane feed preserves quality proof without learned pause denial", bot.contains("SUCCESSFUL_LANE_FEED_DENIED_QUALITY_PROOF_6483") && bot.contains("qualityProofOk6014") && !bot.contains("LIVE_FULL_RING_LANE_OBSERVE"))
        assertFalse("live full-ring observe must not return true before owner rotation", bot.contains("LIVE_FULL_RING_LANE_OBSERVE") || bot.contains("fullRingObserve"))
        assertTrue("V5.0.6599: runtime report exposes qualified desk aggregation and canonical/contributor counts", pipe.contains("MEME_RING=qualifiedDeskAggregation") && pipe.contains("canonicalPrimary=") && pipe.contains("contributorOnly=") && pipe.contains("LIVE_ALL_LANE_CONTRIBUTION_4469"))
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
        assertTrue("V5.0.4202/6019: live ledger drift cap must use effective reconciler truth and wallet-proof grace, not PositionWalletReconciler alone", exec.contains("effectiveChecked = maxOf(positionChecked, sellChecked, liveWalletChecked)") && exec.contains("LiveWalletReconciler.totalChecked()") && exec.contains("SellReconciler.totalChecked") && exec.contains("getOpenAwaitingWalletProofCount") && exec.contains("proofGrace6019") && exec.contains("LIVE_LEDGER_DRIFT_CAP_BYPASS_RESOLVING_4202"))
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
        assertTrue("V5.0.4219/6022: Quality TP risk/reward floor must use absolute effective stop distance", botService4219.contains("kotlin.math.abs(qualitySignal6022.stopLossPct) * 2.0") && !botService4219.contains("qualitySignal.stopLossPct * 2.0  // Always >= 2x the stop"))
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
        assertTrue("V5.0.4580: growth lane fallback must use real dispatchable lanes and wake capital-efficient lanes before recent bleeders", doctrine.contains("dispatchableContributionLanes") && doctrine.contains("BLUECHIP") && doctrine.contains("MOONSHOT") && doctrine.contains("QUALITY") && doctrine.substringAfter("dispatchableContributionLanes").indexOf("BLUECHIP") < doctrine.substringAfter("dispatchableContributionLanes").indexOf("EXPRESS"))
        assertTrue("V5.0.4557: AgenticStyleRouter must force a bounded dormant-lane fallback when the selected style/base has no such lane", router.contains("forceContributionFallback4557") && router.contains("growthFallbackLane4557 in LiveGrowthDoctrine.dispatchableContributionLanes"))
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
        // V5.0.4117 â€” AGI stack must be wired into buy sizing
        assertTrue("AGI size stack: strategyTunerSizeMult in multiplierProduct", exec.contains("strategyTunerSizeMult"))
        assertTrue("AGI size stack: uphConvictionMult in multiplierProduct", exec.contains("uphConvictionMult"))
        assertTrue("V5.0.4568: live floor must keep bleeders executable instead of dust-sized", exec.contains("laneEvMult < 0.50") && exec.contains("-> 0.35") && exec.contains("executable defensive-pivot floor"))
        assertTrue("V5.0.4580: LaneExpectancyDamper must press early clean-live winners, not only shrink losers", damper.contains("WALLET GROWTH ALLOCATOR") && damper.contains("EARLY_WINNER_MIN_TRADES") && damper.contains("earlyWinner") && damper.contains("m.totalSolPnl > 0.0") && damper.contains("EARLY_WINNER_MIN_WR_PCT"))
        assertTrue("Bleeder floor must be materially below half-size for wallet growth", damper.contains("private const val MIN_MULT = 0.18") && damper.contains("CATASTROPHIC_MIN_MULT = 0.08"))
    }

    @Test
    fun runtime_3955_finality_orphan_and_balance_wait_faults_are_source_scoped() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val snap = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RuntimeStateSnapshot.kt").readText()
        val doctor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/InvariantGuardian.kt").readText()
        val wait = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/BalanceProofWaitState.kt").readText()
        assertTrue("FDG-approved safety-blind WATCH must soft-allow with nonzero liquidity/no hardNo", gate.contains("safetyBlindSoftAllow") && gate.contains("safetyKnownOk || safetyBlindSoftAllow") && gate.contains("Confirmed rugs and zero-liquidity still block later"))
        assertTrue("orphan live accounting must subtract reconciler GRACE from managed desync, not wallet extras", snap.contains("positionReconSnapshot?.grace") && snap.contains("val graceAllowance = maxOf(1, reconcilerGrace)") && snap.contains("orphanLive must mean managed-state desync") && snap.contains("managedDesync"))
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
        assertTrue("live zero-confidence must become a micro-probe tag", zeroBlock.contains("live_zero_conf_micro_probe") && zeroBlock.contains("conf=0% â†’ LIVE micro-probe"))
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
        assertTrue("Final executable gate must create immutable execution tickets", gate.contains("data class ExecutionIntent") && gate.contains("EXEC_TICKET_CREATED") && gate.contains("allowedAttempts[laneKey(ticket.mint, ticket.lane)]") && gate.contains("EXEC_INTENT_CREATED"))
        assertTrue("ticket restore must bypass mutable WATCH/version/lane churn", gate.contains("EXEC_TICKET_RESTORED_IMMUTABLE") && gate.contains("immutableTicket == null && ticketAuthority6564 == null && immutableAuthority6513 == null && !selectedLaneMatchesRequest") && gate.contains("""safetyTier.equals("UNKNOWN", true) && immutableTicket == null"""))
        assertTrue("stale/finality failures need separate counters", exec.contains("BUY_FAILED_FINALITY") && exec.contains("BUY_FAILED_STALE_TICKET") && exec.contains("BUY_FAILED_ROUTE") && exec.contains("BUY_FAILED_SAFETY"))
        assertTrue("executor phase counters must represent proof-first tx progress", listOf("EXEC_SELECTED", "EXEC_TICKET_CREATED", "QUOTE_REQUESTED", "QUOTE_OK", "SWAP_BUILT", "TX_SIGNED", "TX_SUBMITTED", "TX_CONFIRMED", "BUY_PENDING_BALANCE_PROOF", "BUY_JOURNALED").all { (gate + exec).contains(it) })
        assertTrue("only a verified BUY without a journal may fail the regression guard", pipe.contains("VERIFIED_BUY_WITHOUT_JOURNAL") && pipe.contains("LIVE_BUY_PROOF_SIDE_EFFECTS_COMMITTED_6637") && pipe.contains("REGRESSION_GUARDS_FAIL"))
        assertTrue("confirmed live BUY must defer side effects until authoritative proof", exec.contains("LIVE_BUY_SIDE_EFFECTS_DEFERRED_6637") && exec.contains("LIVE_BUY_PROOF_SIDE_EFFECTS_COMMITTED_6637") && exec.indexOf("BUY_PENDING_BALANCE_PROOF") < exec.indexOf("BUY_JOURNALED"))
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
        assertTrue("LIVE mode desync must abort/fail explicitly without treating fresh placeholders as paper", executor.contains("LIVE_MODE_DESYNC") && (executor.contains("execCtx.execMode != ExecMode.LIVE") || executor.contains("execModeResolved != ExecMode.LIVE")) && executor.contains("alreadyOpenPosition") && executor.contains("pre-open TokenState.position is a placeholder"))
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
    fun strategy_bleed_reeducates_lanes_instead_of_promoting_or_quarantining() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStylePivotRouter.kt").readText()
        assertTrue("SHITCOIN live bleed must re-educate inside SHITCOIN, not quality-promote", router.contains("SHITCOIN_LIVE_BLEED_REEDUCATE_VOLUME_IGNITION") && !router.contains("SHITCOIN_LIVE_BLEED_QUALITY_PROMOTION") && !router.contains("SHITCOIN_LIVE_BLEED_QUARANTINE"))
        assertTrue("PRESALE/PROJECT sniper bleed must re-educate route proof inside original lane", router.contains("PRESALE_SNIPE_LIVE_BLEED_REEDUCATE_ROUTE_PROOF") && !router.contains("PRESALE_SNIPE_LIVE_BLEED_QUARANTINE"))
        assertTrue("Bleeding lanes may size-shape tactically but must preserve finalLane ownership", router.contains("finalLane = lane") && router.contains("laneLocalStyleFrom"))
    }


    @Test
    fun live_strategy_tuner_uses_cached_live_terminal_metrics_and_lets_winners_ride() {
        val tuner = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveStrategyTuner.kt").readText()
        val doctrine = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveGrowthDoctrine.kt").readText()
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AgenticStyleRouter.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val reporting = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()

        assertTrue("LiveStrategyTuner must consume clean live terminal StrategyTelemetry only", tuner.contains("StrategyTelemetry.computeCleanLiveTerminalLeaderboard") && !tuner.contains("computeLeaderboard("))
        assertTrue("LiveStrategyTuner must be cached for hot paths", tuner.contains("CACHE_MS") && tuner.contains("cached") && tuner.contains("cacheAtMs"))
        assertTrue("LiveStrategyTuner must be soft-shape only, not a veto/zero-size authority", tuner.contains("Soft-shape only") && !tuner.contains("return false") && !tuner.contains("sizeMult = 0.0"))
        assertTrue("LiveStrategyTuner must bias proven live winners toward compounding runner patience", tuner.contains("compounding_runner") && tuner.contains("partialTriggerMult") && tuner.contains("holdMult = (1.25") && tuner.contains("tpMult = (1.16"))
        assertTrue("LiveStrategyTuner must gate capital winners by hit-rate while preserving asymmetric probes", tuner.contains("hit-rate gated net-SOL doctrine") && tuner.contains("hitRateHealthy") && tuner.contains("low_wr_asymmetric_probe") && tuner.contains("avgWinEdge"))
        assertTrue("V5.0.4584: toxic bleeder tuning must pivot tactic/style first, not buy the same setup smaller and hold longer", tuner.contains("toxic_reclaim_tactic_pivot") && tuner.contains("bleeder_recovery_pivot") && tuner.contains("holdMult = if (toxicInnerLanePivot)") && tuner.contains("partialTriggerMult = if (toxicInnerLanePivot)") && !tuner.contains("holdMult = (1.18 + depth * 0.72).coerceIn(1.12, 1.90)"))
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
        // V5.0.4135 â€” Operator override (2026-06-25). The previous invariant
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
        assertTrue("Probability facade must blend ForwardOutcomeModel + UnifiedPolicyHead + clean live terminal lane priors", prob.contains("ForwardOutcomeModel.forecast") && prob.contains("UnifiedPolicyHead.predictWinProb") && prob.contains("StrategyTelemetry.computeCleanLiveTerminalLeaderboard"))
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
        assertTrue("LiveProbabilityEngine must cap low hit-rate lanes below neutral even with positive SOL/PnL", prob.contains("lowHitRateCap") && prob.contains("maxOf(pWin, lanePWin) < 0.35 -> 0.68") && (prob.contains("minOf(rawMult, lowHitRateCap)") || prob.contains("minOf(rawMult, qualityAwareCap)")))
        assertTrue("V5.0.4596 quality-aware cap must derive from lowHitRateCap + qualityBoost, still bounded", prob.contains("qualityAwareCap") && prob.contains("qualityBoost") && prob.contains("lowHitRateCap * qualityBoost"))
        assertTrue("V5.0.4596 quality boost must be sample-gated and never let a low-score bleeder get outsized capital", prob.contains("score >= 85 -> 1.35") && prob.contains("else        -> 0.75"))
        assertTrue("LiveStrategyTuner must require healthy live WR before winner sizing", tuner.contains("hitRateHealthy") && tuner.contains("wr >= 45.0") && tuner.contains("wr >= 35.0 && pf > 0.0"))
        assertTrue("Low-WR positive-SOL lanes must be asymmetric probes, not runner_press winners", tuner.contains("low_wr_asymmetric_probe") && tuner.contains("wr < 35.0 && sol > 0.0") && tuner.contains("sizeMult = (0.78"))
        assertTrue("V5.0.4584: toxic bleeders must pivot to same-lane reclaim/liquidity style instead of micro-probe runner patience", tuner.contains("toxic_reclaim_tactic_pivot") && !tuner.contains("val sizeFloor = if (toxicBleed) 0.12 else 0.35") && router.contains("TOXIC_RECLAIM_TACTIC") && router.contains("toxicTacticPivot4584"))
    }


    @Test
    fun live_zero_signal_v3_execute_cannot_bypass_as_standard_buy() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val v3 = java.io.File("src/main/kotlin/com/lifecyclebot/v3/V3EngineManager.kt").readText()
        assertTrue("laneQualifiedBuyDecision must convert zero-score/zero-conf with exitable liquidity into PROBE_ONLY, not park live", bot.contains("LANE_WAIT_OVERRIDE_ZERO_SIGNAL_DUST_PROBE_4164") && bot.contains("FDG_ZERO_SCORE_DUST_PROBE_4164") && bot.contains("""blockReason = "PROBE_ONLY"""") && !bot.contains("ZERO_SIGNAL_DEFERRED_NO_LIVE_CAPITAL"))
        assertTrue("V3 ExecuteRequest must carry score/conf/band metadata", v3.contains("val score: Int? = null") && v3.contains("val confidence: Int? = null") && v3.contains("val band: String? = null") && v3.contains("score = decision.finalScore") && v3.contains("confidence = decision.effectiveConfidence"))
        val v3ExecBlock = bot.substring(bot.indexOf("fun runV3Execution"), bot.indexOf("fun manualBuy"))
        assertTrue("V5.0.6018: runV3Execution must floor live zero-signal entries for compounding, not dollar-size dust", v3ExecBlock.contains("V3_ZERO_SIGNAL_COMPOUND_FLOOR_6018") && v3ExecBlock.contains("v3ZeroSignalProbe = reqScore <= 0 && reqConf <= 10") && v3ExecBlock.contains("LiveSizingProfile.lastMileEntryFloor") && v3ExecBlock.contains("sol = if (!isPaper && v3ZeroSignalProbe) execSol else req.sizeSol"))
        val sizing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveSizingProfile.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue("V5.0.6018: live compounding floors must be above dollar-trade sizing", sizing.contains("MIN_ENTRY_SOL: Double = 0.060") && sizing.contains("DEFAULT_ENTRY_SOL: Double = 0.080") && sizing.contains("BASE_WALLET_PCT: Double = 0.120") && sizing.contains("MAX_INITIAL_WALLET_PCT: Double = 0.180"))
        assertTrue("V5.0.6018: executor post-floor soft-allow path must not collapse buys back to 0.01-0.025 SOL", exec.contains("LIVE_RESTORE_LANE_CAP_COMPOUND_FLOOR_6018") && exec.contains("lastMileEntryFloor") && !exec.contains("coerceIn(0.01, 0.025)"))
        assertTrue("V3 bridge must pass real score/band into Executor instead of hardcoded score=50 quality=V3", bot.contains("score = (req.score ?: ts.lastV3Score ?: 50).toDouble()") && bot.contains("quality = req.band ?: \"V3\""))
    }


    @Test
    fun token_metric_stage_router_blocks_peak_and_rug_prone_live_entries() {
        val router = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMetricStageRouter.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val mode = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ModeRouter.kt").readText()
        assertTrue("Stage router must identify base/mid/markup/peak/dump/rug states", router.contains("BASE_START") && router.contains("MID_ACCUMULATION") && router.contains("CONTROLLED_MARKUP") && router.contains("PEAK_EXHAUSTION") && router.contains("RUG_PRONE"))
        assertTrue("Metric-stage rug safety must run before causal trunk handling", bot.contains("TOKEN_METRIC_STAGE_LANE_SOFT_MISMATCH_4162") && bot.contains("TokenMetricStageRouter.laneFit(ts, l)") && bot.contains("metricFit.stage == TokenMetricStageRouter.Stage.RUG_PRONE") && bot.indexOf("metricFit.stage == TokenMetricStageRouter.Stage.RUG_PRONE") < bot.indexOf("ExecutionAuthorityPolicy6533.isTrunkLane(l)"))
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
        assertTrue("scanner breadth must be wider than the old RAWâ‰ˆ50 shallow bench", scanner.contains("offset in listOf(0, 50, 100, 150, 200, 250)") && scanner.contains("totalEmitted >= 120") && scanner.contains("take(10_000)"))
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
        val builder = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CanonicalFeaturesBuilder.k×Î¼ÓFòµë(š+myÓD6Æ÷6W5GvVÇfU7FGW57W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$W†–Æ–'•7FGW4F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3cC¢W†–Æ–'’F–vW7B×W7B–æ6ÇVFRGvVÇfR66GFW&VB7FGW27W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%Fö¶Vå&Vg&W6…öÆ–7’ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$&—&FW–T'VFvWDvFRç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$”†VÇF„Ööæ—F÷"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$fVT67V×VÆF÷"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$W†—E&V6öåG&6¶W"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$Æ—fU7G&FVw•GVæW"ç7FGW4Æ–æR"’bbF–vW7Bæ6öçF–ç2‚%66ææW%6÷W&6T'&–âç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚%7G&FVw•f&–çE7F÷&Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$W‡Æ÷&F–öä'VFvWBç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$æõG&FTö'6W'fF–öå7F÷&Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%6VÆÄf–ÇW&T†—7F÷'’ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%6VÆÄ¦ö%&Vv—7G'’ç6æ6†÷B"’¢76W'EG'VR‚%cRããC3cC¢÷W&F÷"µ’×W7B–æ6ÇVFRW†–Æ–'’F–vW7B"Â·’æ6öçF–ç2‚&W†–Æ–'•÷7FGW3Ô÷W&F÷$W†–Æ–'•7FGW4F–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷$W†–Æ–'•7FGW4F–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3cC¢W†–Æ–'’F–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’¢Ð  ¢FW7@¢gVâFF—fTF–vW7CC3cT6Æ÷6W5FVå'VçF–ÖU7FGW57W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$FF—fU7FGW4F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3cS¢FF—fRF–vW7B×W7B–æ6ÇVFRFVâ'VçF–ÖR7FGW27W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$Æ—fU&ö&&–Æ—G”Væv–æRç7FGW4Æ–æR"’bbF–vW7Bæ6öçF–ç2‚%VÆ—G”ÆFFW"ç7FGW4Æ–æR"’bbF–vW7Bæ6öçF–ç2‚$ÆæTW‡V7Fæ7”F×W"ç7FGW4Æ–æR"’bbF–vW7Bæ6öçF–ç2‚$çF”6†ö¶TÖævW"ç7FGW4Æ–æR"’bbF–vW7Bæ6öçF–ç2‚$g&VU&ævTÖöFR–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚%6VçF–Væ6T†öö·2ç7FGW57VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$ÆÆÔÆ$Væv–æRç7FGW4Æ–æR"’bbF–vW7Bæ6öçF–ç2‚$ÆÆÔÆ%7F÷&Rç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$6öÆE7G&V´F×W"–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$W†V7WF–öä6÷VçFW$6öçG&7Bç6æ6†÷B"’¢76W'EG'VR‚%cRããC3cS¢÷W&F÷"µ’×W7B–æ6ÇVFRFF—fRF–vW7B"Â·’æ6öçF–ç2‚&FF—fU÷7FGW3Ô÷W&F÷$FF—fU7FGW4F–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷$FF—fU7FGW4F–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3cS¢FF—fRF–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’¢Ð  ¢FW7@¢gVâ6÷&U'VçF–ÖTF–vW7CC3cd6Æ÷6W4f–gFVVå7FGW57W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$6÷&U'VçF–ÖTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3cc¢6÷&RF–vW7B×W7B–æ6ÇVFRf–gFVVâ'VçF–ÖRö†VÇF‚7FGW2†öö·2"ÂF–vW7Bæ6öçF–ç2‚$FVD”Æ–W$f–ÇFW"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%V&çF–æU7F÷&Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$Æ—fTÖGW&—G”WF†÷&—G’ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%v—&–æt†VÇF‚ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$7'—Fõ÷6—F–öå7FFRç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$–çFW&æWDVFvTFW6²ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$FVfW$7F—f—G•G&6¶W"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$Æ—fUG&FTÆöu7F÷&Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%66÷&TW‡V7Fæ7•G&6¶W"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$ÖVÖUu$VÖW&vVæ7”'&¶Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$Æ—fU6fWG”6—&7V—D'&V¶W"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%vF6†Æ—7EGFÅöÆ–7’ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%&V6÷fW&VD†öÆDwV&Bç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$Æ—fTGFV×E7FG2ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$†öÆDGW&F–öåG&6¶W"ç6æ6†÷B"’¢76W'EG'VR‚%cRããC3cc¢÷W&F÷"µ’×W7B–æ6ÇVFR6÷&R'VçF–ÖRF–vW7B"Â·’æ6öçF–ç2‚&6÷&U÷'VçF–ÖU÷7FGW3Ô÷W&F÷$6÷&U'VçF–ÖTF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷$6÷&U'VçF–ÖTF–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3cc¢6÷&R'VçF–ÖRF–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’¢Ð  ¢FW7@¢gVâ6VÆÅ'VçF–ÖTF–vW7CC3c„6Æ÷6W5F†—'FVVå7FGW57W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%6VÆÅ'VçF–ÖTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3cƒ¢6VÆÂ'VçF–ÖRF–vW7B×W7B–æ6ÇVFRF†—'FVVâ6VÆÂöW†V7WF–öâ7W÷'B†öö·2"ÂF–vW7Bæ6öçF–ç2‚%6VÆÄf÷&Vç6–72ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$&Ææ6U&ööev—E7FFRç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$W†—E&÷f–FW$†VÇF‚ç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚%'F–Å6VÆÄÖ—6ÖF6„FWFV7F÷"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%÷6—F–öåvÆÆWE&V6öæ6–ÆW"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$†÷7D6—&7V—D–çFW&6WF÷"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$Æö6Ä÷'†å7F÷&Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$Æ%&öÖ÷FVDfVVBç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚%fö–6TF–væ÷7F–72ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%6fWG•&Vg&W6…VWVRç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%÷6—F–öä6Æ÷6TÆVFvW"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%66ææW$†&E&V¦V7E7F÷&Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%V×÷'FÅF‡&÷GFÆRç6æ6†÷B"’¢76W'EG'VR‚%cRããC3cƒ¢÷W&F÷"µ’×W7B–æ6ÇVFR6VÆÂ'VçF–ÖRF–vW7B"Â·’æ6öçF–ç2‚'6VÆÅ÷'VçF–ÖU÷7FGW3Ô÷W&F÷%6VÆÅ'VçF–ÖTF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%6VÆÅ'VçF–ÖTF–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3cƒ¢6VÆÂ'VçF–ÖRF–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõ÷6VÆÅöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢Ð  ¢FW7@¢gVâc566÷&–ætF–vW7CC3s6Æ÷6W4æ–æU66÷&–æu7W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%c566÷&–ætF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3s¢c2F–vW7B×W7BW‡÷6R6fRF÷ÖÆWfVÂ66÷&–ær7VÖÖ&–W2"ÂF–vW7Bæ6öçF–ç2‚$7VÇDÖöÖVçGVÔ’ç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$ÖVÖTæ'&F—fT’ç7VÖÖ'’"’¢76W'EG'VR‚%cRããC3s¢c2F–vW7B×W7B6Æ76–g’–ç7Fæ6R×66÷VB66÷&–ær7VÖÖ&–W2v—F†÷WBVç6fR6ÆÇ2"ÂF–vW7Bæ6öçF–ç2‚$&V†f–÷$’7VÖÖ'’–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚%6†—D6ö–åG&FW$’7FGW2ÖöFVÂ–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$6öÆÆV7F—fT–çFVÆÆ–vVæ6T’7FGW2ÖöFVÂ–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$ÖWF6övæ—F–öä’FV6—6–öâ7VÖÖ'’–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$&ÇVT6†—G&FW$’7FGW2ÖöFVÂ–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$fÇV–DÆV&æ–æt’&Ò7VÖÖ&–W2–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$66„vVæW&F–öä’7FGW2ÖöFVÂ–ç7Fæ6R×66÷VB"’¢76W'EG'VR‚%cRããC3s¢÷W&F÷"µ’×W7B–æ6ÇVFRc266÷&–ærF–vW7B"Â·’æ6öçF–ç2‚'c5÷66÷&–æu÷7FGW3Ô÷W&F÷%c566÷&–ætF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%c566÷&–ætF–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3s¢c266÷&–ærF–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõ÷66÷&Uö6†ævS×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢Ð  ¢FW7@¢gVâVæv–æU7FGW4F–vW7CC3s6Æ÷6W4VÆWfVäVæv–æU7W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$Væv–æU7FGW4F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3s¢Væv–æRF–vW7B×W7B–æ6ÇVFR6fRö&¦V7Bö6ö×æ–öâ7FGW2†öö·2"ÂF–vW7Bæ6öçF–ç2‚$Æ—fU6—¦–æu&öf–ÆRç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$¶W•fÆ–FF÷"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%Fö¶VäÖWF66†R6æ6†÷B—2–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$WFôVæGö–çDÖ–w&F÷"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$ÖVÖU—VÆ–æUG&6W"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$7–6ÆUF–Ö–æuG&6¶W"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%G&F–æt6÷–Æ÷Bç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$fFu&÷WFUfW&F–7Bç6æ6†÷B"’¢76W'EG'VR‚%cRããC3s¢Væv–æRF–vW7B×W7B6Æ76–g’&wVÖVçBö–ç7Fæ6R66÷VB7VÖÖ&–W2"ÂF–vW7Bæ6öçF–ç2‚%'VçF–ÖU&Vw&W76–öäwV&G27VÖÖ'’&WV—&W27WÆ–VB6†V6²Æ—7B"’bbF–vW7Bæ6öçF–ç2‚$&V†f–÷$ÆV&æ–ær7VÖÖ'’—2–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$Ud6Æ7VÆF÷"7VÖÖ'’—2&W7VÇB×66÷VB"’¢76W'EG'VR‚%cRããC3s¢÷W&F÷"µ’×W7B–æ6ÇVFRVæv–æR7FGW2F–vW7B"Â·’æ6öçF–ç2‚&Væv–æU÷7FGW3Ô÷W&F÷$Væv–æU7FGW4F–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷$Væv–æU7FGW4F–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3s¢Væv–æRF–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢Ð  ¢FW7@¢gVâ&VÖ–æFW$F–vW7CC3s$6Æ÷6W5&VÖ–æ–ætæöåW'57FGW57W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%&VÖ–æFW%7FGW4F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3s#¢&VÖ–æFW"F–vW7B×W7B6fVÇ’6ÆÂö&¦V7BÖÆWfVÂ7FGW2†öö·2"ÂF–vW7Bæ6öçF–ç2‚$&÷E'VçF–ÖT6öçG&öÆÆW"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$6æöæ–6ÄÆV&æ–æt6÷VçFW'2ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$Æ–W%&VF–æW75&Vv—7G'’ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$ÆÆÕG&FU66÷&Rç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$6F7G&÷†–5W$&ÆVVDwV&Bç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%6VÖçF–5GFW&äw&‚ç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$”&6¶öfbç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%÷6—F–öä&&—FW$6÷VçFW'2ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%÷6—F–öäW†—D&&—FW"ç6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W"ç6æ6†÷B"’¢76W'EG'VR‚%cRããC3s#¢&VÖ–æFW"F–vW7B×W7B6Æ76–g’&—fFRö&rö–ç7Fæ6R×66÷VB7FGW27W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%7–Ö&öÆ–4W†—E&V6öæW"å'VÆTÆV&æ–ær6æ6†÷B&—fFR"’bbF–vW7Bæ6öçF–ç2‚$&ÆVVFW$ÖVÖ÷'•&÷WFW"6æ6†÷B&—fFR"’bbF–vW7Bæ6öçF–ç2‚%6†F÷tÆV&æ–ætVæv–æR7VÖÖ'’FF×66÷VB"’bbF–vW7Bæ6öçF–ç2‚%FööÆ¶—E6–væÅ6†VWB6æ6†÷B&WV—&W2Fö¶Vå7FFR"’bbF–vW7Bæ6öçF–ç2‚%Fö¶VäÖWG&–57FvU&÷WFW"6æ6†÷B&WV—&W2Fö¶Vå7FFR"’bbF–vW7Bæ6öçF–ç2‚%G&V7W'”ÖævW"7FGW57VÖÖ'’&WV—&W26öÅ&–6R"’bbF–vW7Bæ6öçF–ç2‚%G&FT¦÷W&æÂ7VÖÖ'’&WV—&W2æG&ö–B6öçFW‡B÷&÷rw&—FW""’bbF–vW7Bæ6öçF–ç2‚%G&FTÆ–fV7–6ÆR7VÖÖ&–W2&RÆ–fV7–6ÆR÷7FB–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$f–æÄFV6—6–öävFR7VÖÖ&–W2&RFV6—6–öâ÷7FFR–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚%Fö¶Vå6ö6–Å66÷&W"7VÖÖ'’&WV—&W2—$–æfò"’bbF–vW7Bæ6öçF–ç2‚$ÆæUF–ÖV÷WDvFR7FGW2&WV—&W2ÆæR&wVÖVçB"’bbF–vW7Bæ6öçF–ç2‚%G&FT–FVçF—G’7VÖÖ&–W2&R–FVçF—G’÷7FB–ç7Fæ6R×66÷VB"’bbF–vW7Bæ6öçF–ç2‚$Æ—fU&÷f–FW%V÷'VÒ7VÖÖ'’—2fW&F–7B×66÷VB"’¢76W'EG'VR‚%cRããC3s#¢÷W&F÷"µ’×W7B–æ6ÇVFR&VÖ–æFW"F–vW7B"Â·’æ6öçF–ç2‚'&VÖ–æFW%÷7FGW3Ô÷W&F÷%&VÖ–æFW%7FGW4F–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%&VÖ–æFW%7FGW4F–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3s#¢&VÖ–æFW"F–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢Ð  ¢FW7@¢gVâW'47'—FôF–vW7CC3s46Æ÷6W47'—Fõ7FGW57W&f6W2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%W'47'—FôF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3s3¢W'27'—FòF–vW7B×W7B–æ6ÇVFR7'—Fò'&–â7VÖÖ&–W2"ÂF–vW7Bæ6öçF–ç2‚$7'—FôfÇV–DÆV&æ–ærç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$7'—FõF7F–57v—F6†W"ç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$7'—FôgVææVÂç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$7'—Fô&V†f–÷"ç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$7'—Fô'&–âç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$7'—FôÆ÷6–æuGFW&äÖVÖ÷'’ç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$7'—Fô6æöæ–6ÄÆV&æ–ærç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚$7'—FôÆæTW†—EGVæW"ç7VÖÖ'’"’¢76W'EG'VR‚%cRããC3s3¢W'27'—FòF–vW7B×W7B6Æ76–g’ÆæR×F–ÖV÷WB&r×66÷VB7FGW2"ÂF–vW7Bæ6öçF–ç2‚$7'—FôÆæUF–ÖV÷WDvFR7FGW2&WV—&W2ÆæR&wVÖVçB"’¢76W'EG'VR‚%cRããC3s3¢÷W&F÷"µ’×W7B–æ6ÇVFRW'27'—FòF–vW7B"Â·’æ6öçF–ç2‚'W'5ö7'—Fõ÷7FGW3Ô÷W&F÷%W'47'—FôF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%W'47'—FôF–vW7Bç7FGW2"’¢76W'EG'VR‚%cRããC3s3¢W'27'—FòF–vW7B&VÖ–ç2&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö7'—FõövFUö6†ævS×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢Ð  ¢FW7@¢gVâ7FGW4–çfVçF÷'“C3sD†5¦W&ôæÖT'F–f7E&VÖ–æFW'2‚’°¢fÂ&VÖ–æFW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%&VÖ–æFW%7FGW4F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂW'2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%W'47'—FôF–vW7Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3sC¢&VÖ–æFW"F–vW7B×W7BæÖR6÷fW&VB–çfVçF÷'’'F–f7G2"Â&VÖ–æFW"æ6öçF–ç2‚$&÷E'VçF–ÖT6öçG&öÆÆW""’bb&VÖ–æFW"æ6öçF–ç2‚$6æöæ–6Å6—¦T6öçFW‡B"’bb&VÖ–æFW"æ6öçF–ç2‚$6F7G&÷†–5W$&ÆVVDwV&B"’bb&VÖ–æFW"æ6öçF–ç2‚%G&FT–FVçF—G”ÖævW""’bb&VÖ–æFW"æ6öçF–ç2‚$•7FFUW'6—7FVæ6U6VçF–æVÂ"’bb&VÖ–æFW"æ6öçF–ç2‚$”&6¶öfb"’¢76W'EG'VR‚%cRããC3sC¢W'2F–vW7B×W7BæÖR6÷fW&VB7'—FôgVææVÂ'F–f7B"ÂW'2æ6öçF–ç2‚$7'—FôgVææVÂ"’¢76W'EG'VR‚%cRããC3sC¢–çfVçF÷'’Ö&¶W"6Æ÷7W&R&VÖ–ç2&W÷'BÖöæÇ’"Â&VÖ–æFW"æ6öçF–ç2‚%&W÷'BÖöæÇ’"’ÇÂ&VÖ–æFW"æ6öçF–ç2‚'&W÷'BÖöæÇ’"’ÇÂ&VÖ–æFW"æ6öçF–ç2‚%&W÷'BÖöæÇ’"’¢Ð  ¢FW7@¢gVâ7–Ö&öÆ–4æE&VVF—E&÷fT÷W&F÷$F–vW7G3C3sb‚’°¢fÂ&÷fW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ7–Ö&öÆ–4–çf&–çE&÷fW"æ·B"’ç&VEFW‡B‚¢fÂ7vVWW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô6•76•&VVF—E7vVWW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3sc¢7–Ö&öÆ–4–çf&–çE&÷fW"×W7B&÷FV7B÷W&F÷"F–vW7B&W÷'BÖöæÇ’µ’v—&–ær"Â&÷fW"æ6öçF–ç2‚$õU$Dõ%ôD”tU5E5õ$Uõ%EôôäÅ•ôµ•õt•$TEóC3sb"’bb&÷fW"æ6öçF–ç2‚&÷W&F÷$F–vW7G2æÆÂ"’bb&÷fW"æ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb&÷fW"æ6öçF–ç2‚"""FW‡Bæ6öçF–ç2‚&W†V7WFT'W’‚"’"""’bb&÷fW"æ6öçF–ç2‚"""FW‡Bæ6öçF–ç2‚'&WVW7E6VÆÂ‚"’"""’¢76W'EG'VR‚%cRããC3sc¢6•76•&VVF—E7vVWW"×W7B&÷FV7B÷W&F÷"F–vW7B&W÷'BÖöæÇ’æòÖWF†÷&—G’6öçG&7B"Â7vVWW"æ6öçF–ç2‚$õU$Dõ%ôD”tU5E5õ$Uõ%EôôäÅ•ôäõôUD„õ$•E•óC3sb"’bb7vVWW"æ6öçF–ç2‚&÷W&F÷$F–vW7G2æÆÂ"’bb7vVWW"æ6öçF–ç2‚&·’æ6öçF–ç2‚"’bb7vVWW"æ6öçF–ç2‚"ç7FGW2"’¢Ð  ¢FW7@¢gVâ7–çF†WF–46ö×öæVçD66÷VçF&–Æ—G“C3s…7F×4†'f&DVçG'•&V6÷&G2‚’°¢fÂ67BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærõ7–çF†WF–46ö×öæVçD66÷VçF&–Æ—G’æ·B"’ç&VEFW‡B‚¢fÂ66÷&W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærõVæ–f–VE66÷&W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3sƒ¢7–çF†WF–26ö×öæVçB66÷VçF&–Æ—G’×W7B6÷fW"66÷&RÖÖ÷f–ærF†VG&RæÖW2"Â67Bæ6öçF–ç2‚'6÷W&6R"’bb67Bæ6öçF–ç2‚&&÷fÅöÖVÖ÷'’"’bb67Bæ6öçF–ç2‚'cEö7&÷77FÆ²"’bb67Bæ6öçF–ç2‚&g&W6…öÆVæ6…ö&öçW2"’bb67Bæ6öçF–ç2‚&67C×7–çF†WF–5ö6ö×öæVçB"’¢76W'EG'VR‚%cRããC3sƒ¢66÷VçF&–Æ—G’7F××W7B&W6W'fR66÷&RfÇVW2æBFBÆæR÷6÷W&6RöÖ–çBö'V–ÆB6öçFW‡B"Â67Bæ6öçF–ç2‚&6ö×æ6÷’‡&V6öâ"’bb67Bæ6öçF–ç2‚'6÷W&6SÒ"’bb67Bæ6öçF–ç2‚&6æF–FFRç6÷W&6RææÖR"’bb67Bæ6öçF–ç2‚&Ö–çCÒ"’bb67Bæ6öçF–ç2‚&6æF–FFRæÖ–çBçF¶Rƒ’"’bb67Bæ6öçF–ç2‚&'V–ÆCÒ"’bb67Bæ6öçF–ç2‚$'V–ÆD6öæf–rådU%4”ôåôäÔR"’¢76W'EG'VR‚%cRããC3sƒ¢Væ–f–VE66÷&W"†'f&BVçG'’&V6÷&G2×W7B72F‡&÷Vv‚7–çF†WF–266÷VçF&–Æ—G’öâÆÂF‡2"Â66÷&W"æ6öçF–ç2‚""%7–çF†WF–46ö×öæVçD66÷VçF&–Æ—G’æææ÷FFR†f–æÄ6&Bæ6ö×öæVçG2²6†F÷t÷WFW%&–ærÂ6æF–FFRÂ$4Ä54”2"’"""’bb66÷&W"æ6öçF–ç2‚""%7–çF†WF–46ö×öæVçD66÷VçF&–Æ—G’æææ÷FFR†f–æÄ6&Bæ6ö×öæVçG2Â6æF–FFRÂ$ÔôDU$â"’"""’bb66÷&W"æ6öçF–ç2‚""%7–çF†WF–46ö×öæVçD66÷VçF&–Æ—G’æææ÷FFR†fÆÆ&6´6&Bæ6ö×öæVçG2Â6æF–FFRÂ$ÔôDU$åôdÄÄ$4²"’"""’bb66÷&W"æ6öçF–ç2‚""%7–çF†WF–46ö×öæVçD66÷VçF&–Æ—G’æææ÷FFR†f–æÄ6&Bæ6ö×öæVçG2Â6æF–FFRÂ%Tä”d”TB"’"""’bb66÷&W"æ6öçF–ç2‚""%7–çF†WF–46ö×öæVçD66÷VçF&–Æ—G’æææ÷FFR†fÆÆ&6´6&Bæ6ö×öæVçG2Â6æF–FFRÂ%Tä”d”TEôdÄÄ$4²"’"""’¢Ð  ¢FW7@¢gVâ7&÷75FÆµ7F×C3ƒ6'&–W4WfVçDÆö6ÄÖWFFF‚’°¢fÂ7&÷72Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô”7&÷75FÆ²æ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖVÖT7&÷75FÆ´VçG'”'&–FvRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3ƒ¢”7&÷75FÆ²7F××W7B&WF–â6÷W&6RöÖöFR÷÷6—F–öä–Bö'V–ÆBv—F‚F†RVçG'’×F–ÖR6–væÂ"Â7&÷72æ6öçF–ç2‚'6÷W&6S¢7G&–ær"’bb7&÷72æ6öçF–ç2‚&ÖöFS¢7G&–ær"’bb7&÷72æ6öçF–ç2‚'÷6—F–öä–C¢7G&–ær"’bb7&÷72æ6öçF–ç2‚&'V–ÆC¢7G&–ær"’bb7&÷72æ6öçF–ç2‚$5$õ55DÄµôTåE%•õ5DÕóC3ƒ"’bb7&÷72æ6öçF–ç2‚$5$õ55DÄµôTåE%•ôõUD4ôÔUóC3ƒ"’¢76W'EG'VR‚%cRããC3ƒ¢ÖVÖT7&÷75FÆ´VçG'”'&–FvR×W7B7F×WfVçBÖÆö6Â6÷W&6RöÖöFR÷÷6—F–öä–B–ç7FVBöb6Æ÷6R×F–ÖR&V6ö×WFRÖWFFF"Â'&–FvRæ6öçF–ç2‚'6÷W&6RÒG2ç6÷W&6R"’bb'&–FvRæ6öçF–ç2‚&ÖöFRÒG2ç÷6—F–öâçG&F–ætÖöFR"’bb'&–FvRæ6öçF–ç2‚'÷6—F–öä–BÒG&FT÷WF6öÖTÆVFvW"ç÷6—F–öä–B‡G2’"’¢76W'EG'VR‚%cRããC3ƒ¢7&÷72×FÆ²ÖWFFFF6‚×W7B&W6W'fR&6·v&B6ö×F–&–Æ—G’f÷"W†—7F–ær6ÆÆW'2"Â7&÷72æ6öçF–ç2‚'6÷W&6S¢7G&–ærÒ"’bb7&÷72æ6öçF–ç2‚&ÖöFS¢7G&–ærÒ"’bb7&÷72æ6öçF–ç2‚'÷6—F–öä–C¢7G&–ærÒ"’bb7&÷72æ6öçF–ç2‚&'V–ÆC¢7G&–ærÒ'V–ÆD6öæf–rådU%4”ôåôäÔR"’¢Ð  ¢FW7@¢gVâ76”6÷Væ6–Ä6Æ÷6VDÆö÷6VçF–æVÃC3ƒ&÷fW46÷Væ6–Ä6†–â‚’°¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ76”6÷Væ6–Ä6Æ÷6VDÆö÷6VçF–æVÂæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3ƒ¢54’6÷Væ6–Â6VçF–æVÂ×W7BæÖRgVÆÂ–ç6–v‡B×FòÖ÷WF6öÖR6†–â"Â6VçF–æVÂæ6öçF–ç2‚%6VÖçF–5GFW&äw&‚æVçG'”&–2"’bb6VçF–æVÂæ6öçF–ç2‚$6÷VçFW&f7GVÅ&WÆ”Væv–æRçöÆ–7”†–çG2"’bb6VçF–æVÂæ6öçF–ç2‚%&VfÆV7F—fT÷F–Ö—¦W$tU"’bb6VçF–æVÂæ6öçF–ç2‚$×VÇF”vVçD7&—F–57F6²ç&Wf–WtæE7V&Ö—B"’bb6VçF–æVÂæ6öçF–ç2‚$7–æ57G&FVw”Æ"ç&Wf–WvVE6—¦T&–2"’bb6VçF–æVÂæ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBç7F×÷&V6÷&D÷WF6öÖR"’bb6VçF–æVÂæ6öçF–ç2‚%Væ–f–VDW†—EöÆ–7”†VBç7F×÷&V6÷&D÷WF6öÖR"’¢76W'EG'VR‚%cRããC3ƒ¢54’6÷Væ6–Â6VçF–æVÂ×W7B&VÖ–â&W÷'BÖöæÇ’æB&÷f–FW"×6fR"Â6VçF–æVÂæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW#×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&æõöF—&V7E÷G&FUöWF†÷&—G“×G'VR"’¢76W'EG'VR‚%cRããC3ƒ¢÷W&F÷"µ’×W7B–æ6ÇVFR54’6÷Væ6–Â7FGW2"Â·’æ6öçF–ç2‚'76•ö6÷Væ6–Å÷7FGW3Õ76”6÷Væ6–Ä6Æ÷6VDÆö÷6VçF–æVÂ"’bb·’æ6öçF–ç2‚%76”6÷Væ6–Ä6Æ÷6VDÆö÷6VçF–æVÂç7FGW2"’¢Ð  ¢FW7@¢gVâ&öf—E&W77W&UvÆÆWDçVÆÃC3ƒDVçVWVW5W&vVçE6VÆÅ&V6÷fW'’‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3ƒC¢Æ—fR6—FÂ×&V6÷fW'’vÆÆWBÖçVÆÂ×W7BVçVWVRW&vVçB&V6÷fW'’–ç7FVBöb76—fR&WG'’"ÂW†V2æ6öçF–ç2‚%U$tTåEô4•DÅõ$T4õdU%•õtÄÄUEôåTÄÂ"’bbW†V2æ6öçF–ç2‚%VæF–æu6VÆÅVWVRæFB‡G2æÖ–çB"’bbW†V2æ6öçF–ç2‚$&Ææ6U&ööev—E7FFRæÖ&µv—F–ær"’bbW†V2æ6öçF–ç2‚%$ôd•Eõ$U55U$Uõ4TÄÅõ$T4õdU%•ôTåTUTTEóC3ƒB"’¢76W'EG'VR‚%cRããC3ƒC¢Æ—fR&öf—BÖÆö6²vÆÆWBÖçVÆÂ×W7BVçVWVRW&vVçB&V6÷fW'’–ç7FVBöb76—fR&WG'’"ÂW†V2æ6öçF–ç2‚%U$tTåEõ$ôd•EôÄô4µõtÄÄUEôåTÄÂ"’bbW†V2æ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W"æÖ&µ6VÆÅv—F–æt&Ææ6U&ööb"’bbW†V2æ6öçF–ç2‚&¶–æC×&öf—EöÆö6²vÆÆWEöçVÆÂVæF–æu6VÆÃ×G'VR&Ææ6U&ööev—C×G'VR"’¢76W'EG'VR‚%cRããC3ƒC¢&öf—B×&W77W&R&V6÷fW'’×W7Bæ÷Bf¶RÖ6Æ÷6R÷"W"Ö&öö²Æ—fRW†—G2"ÂW†V2æ6öçF–ç2‚%$ôd•EôÄô4µôDTdU%$TB"’ÇÂW†V2æ6öçF–ç2‚$VçVWVVBW&vVçB&ööb÷&WG'’"’¢Ð  ¢FW7@¢gVâÖööç6†÷E&öÖ÷F–öãC3ƒd†56–ævÆU&öÖ÷FVDg&öÔ&wVÖVçB‚’°¢fÂÖööâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærôÖööç6†÷EG&FW$’æ·B"’ç&VEFW‡B‚¢fÂ&öÖ÷F–öå7F'BÒÖööâæ–æFW„öb‚&gVâW†V7WFU&öÖ÷F–öâ"¢fÂ&öÖ÷F–öäVæBÒÖööâæ–æFW„öb‚&gVâ&V6÷&D6öÆÆV7F—fUv–ææW""Â&öÖ÷F–öå7F'B¢fÂ&öÖ÷F–öä&Æö6²ÒÖööâç7V'7G&–ær‡&öÖ÷F–öå7F'BÂ&öÖ÷F–öäVæB¢76W'DWVÇ2‚%cRããC3ƒc¢Öööç6†÷B&öÖ÷F–öâ×W7Bæ÷BGWÆ–6FR&öÖ÷FVDg&öÒæÖVB&wVÖVçB"ÂÂ&VvW‚‚'&öÖ÷FVDg&öÒÒg&öÔÆ–W""’æf–æDÆÂ‡&öÖ÷F–öä&Æö6²’æ6÷VçB‚’¢76W'EG'VR‚%cRããC3ƒc¢Öööç6†÷B&öÖ÷F–öâ7F–ÆÂ&W6W'fW2'VææW"6öçFW‡B"Â&öÖ÷F–öä&Æö6²æ6öçF–ç2‚'VµæÅ7BÒ7W'&VçEæÅ7B"’bb&öÖ÷F–öä&Æö6²æ6öçF–ç2‚'F–v‡E4Âæ6öW&6TDÆV7B„„$EôdÄôõ%õ5Dõ’"’¢Ð  ¢FW7@¢gVâÖVÖTÆæU&—G•6VçF–æVÃC3ƒ…–ç5&W7F÷&TæE&ö&U&—G’‚’°¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖVÖTÆæU&—G•6VçF–æVÂæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3ƒƒ¢ÆæR&—G’6VçF–æVÂ×W7B–âæ–æRÖÆæR&W7F÷&RÆ&VÇ2"Â6VçF–æVÂæ6öçF–ç2‚$ä”äUôÄäUõ$U5Dõ$Uõ$•E•óC3ƒ‚"’bb6VçF–æVÂæ6öçF–ç2‚$Ôä•TÄDTEõ$U5Dõ$TEô5D•dUõõ4•D”ôåóC#B"’bb6VçF–æVÂæ6öçF–ç2‚%E$T5U%•õ$U5Dõ$TEô5D•dUõõ4•D”ôåóC##‚"’¢76W'EG'VR‚%cRããC3ƒƒ¢ÆæR&—G’6VçF–æVÂ×W7B–â6ögB&V6÷fW'’&ö&W2Âæ÷BÆö6ÂW6R×WFF–öç2"Â6VçF–æVÂæ6öçF–ç2‚$ÄäUôÄô4ÅõU4U5ô$Uõ$T4õdU%•õ$ô$U5óC3ƒ‚"’bb6VçF–æVÂæ6öçF–ç2‚%4„•D4ô”åôD”Å•ôÄõ55õ$T4õdU%•õ$ô$UóC3B"’bb6VçF–æVÂæ6öçF–ç2‚%G&V7W'”ÖöFRåU4TBÓâã3R"’¢76W'EG'VR‚%cRããC3ƒƒ¢ÆæR&—G’6VçF–æVÂ×W7B&R&W÷'BÖöæÇ’æBµ’×v—&VB"Â6VçF–æVÂæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb·’æ6öçF–ç2‚&ÖVÖUöÆæU÷&—G“ÔÖVÖTÆæU&—G•6VçF–æVÂ"’bb·’æ6öçF–ç2‚$ÖVÖTÆæU&—G•6VçF–æVÂç7FGW2"’¢76W'EG'VR‚%cRããC3ƒƒ¢ÆæR&—G’6VçF–æVÂ×W7BW‡÷6R6÷W&6R×G&VRVF—BVçG'—ö–çG2"Â6VçF–æVÂæ6öçF–ç2‚&gVâVF—E6÷W&6UG&VR"’bb6VçF–æVÂæ6öçF–ç2‚&gVâf–ÆVB"’bb6VçF–æVÂæ6öçF–ç2‚%$U5Dõ$Uô„TÅU%5õ$U4TåEóC3ƒ‚"’¢Ð  ¢FW7@¢gVâV”ç$FV6÷WÆ–æu6VçF–æVÃC3“%–ç4Ö–ä7F—f—G•FW‡D&÷VæF&–W2‚’°¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõV”ç$FV6÷WÆ–æu6VçF–æVÂæ·B"’ç&VEFW‡B‚¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3“#¢T’å"6VçF–æVÂ×W7B–â&÷VæFVBFV6—6–öâÖÆörFW‡B"Â6VçF–æVÂæ6öçF–ç2‚%T•ôDT4•4”ôåôÄôuô$õTäDTEóC3“""’bbÖ–âæ6öçF–ç2‚'6WDFV6—6–öäÆöuFW‡D&÷VæFVCC#ƒ"’bbÖ–âæ6öçF–ç2‚$DT4•4”ôåôÄôuôÔ…ô4„%5óC#ƒ"’¢76W'EG'VR‚%cRããC3“#¢T’å"6VçF–æVÂ×W7B–âæòÖ÷&VÆ–÷WB6¶—2"Â6VçF–æVÂæ6öçF–ç2‚%T•ôDT4•4”ôåôÄôuôäôõõ4´•óC3“""’bbÖ–âæ6öçF–ç2‚&Æ7DFV6—6–öäÆöuFW‡D†6‚"’bbÖ–âæ6öçF–ç2‚'6WEFW‡D–d6†ævVB"’¢76W'EG'VR‚%cRããC3“#¢T’å"6VçF–æVÂ×W7B&R&W÷'BÖöæÇ’æBµ’×v—&VB"Â6VçF–æVÂæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb·’æ6öçF–ç2‚'V•öç#ÕV”ç$FV6÷WÆ–æu6VçF–æVÂ"’bb·’æ6öçF–ç2‚%V”ç$FV6÷WÆ–æu6VçF–æVÂç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆV&æ–æt6öçG&öÄF–vW7CC3“57W&f6W4†–FFVå7FGW4ÖV6†æ—6×2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆV&æ–æt6öçG&öÄF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3“3¢ÆV&æ–ærö6öçG&öÂF–vW7B×W7B7W&f6R†–FFVâÆV&æ–ær÷66ææW"ö6öçG&öÂ7FGW6W2"ÂF–vW7Bæ6öçF–ç2‚$FF—fTÆV&æ–ætVæv–æRævWE7FGW2"’bbF–vW7Bæ6öçF–ç2‚$WFô6ö×÷VæDVæv–æRævWE7FGW2"’bbF–vW7Bæ6öçF–ç2‚$6Æ÷VDÆV&æ–æu7–æ2ævWE7FGW2"’bbF–vW7Bæ6öçF–ç2‚%66ææW$ÆV&æ–ærævWE7FGW26÷W&6SÕ6öÆæÖ&¶WE66ææW"æ·B"’bbF–vW7Bæ6öçF–ç2‚%6öÆæÖ&¶WE66ææW""’bbF–vW7Bæ6öçF–ç2‚%GFW&äWFõGVæW"ævWE7FGW2"’¢76W'EG'VR‚%cRããC3“3¢ÆV&æ–ærö6öçG&öÂF–vW7B×W7B–æ6ÇVFRWF†÷&—G’ÂF÷†–2ÂÔÂÂÖ&¶WBÂæB&Vv–ÖR7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$Væ&ÆVEG&FW$WF†÷&—G’ç6æ6†÷E7G""’bbF–vW7Bæ6öçF–ç2‚%F÷†–4ÖöFT6—&7V—D'&V¶W"ævWE7FGW2"’bbF–vW7Bæ6öçF–ç2‚$öäFWf–6TÔÄVæv–æRævWE7FGW2"’bbF–vW7Bæ6öçF–ç2‚$Ö&¶WE7G'V7GW&U&÷WFW"ævWE7FGW2"’bbF–vW7Bæ6öçF–ç2‚%&Vv–ÖUG&ç6—F–öä’ævWE7FGW2"’¢76W'EG'VR‚%cRããC3“3¢ÆV&æ–ærö6öçG&öÂF–vW7B×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb·’æ6öçF–ç2‚&ÆV&æ–æuö6öçG&öÃÔ÷W&F÷$ÆV&æ–æt6öçG&öÄF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆV&æ–æt6öçG&öÄF–vW7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CC3“d&F6†W4Æ÷u&VfW&Væ6TVF—E7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3“c¢Æöær×F–ÂF–vW7B×W7B&F6‚BÆV7BGvVçG’Æ÷r×&VfW&Væ6RÖV6†æ—6Ò7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%ôÄôäuõD”ÅôÔT4„ä•4ÕôD”tU5EóC3“b"’bbF–vW7Bæ6öçF–ç2‚&6÷VçCÒ"’bbF–vW7Bæ6öçF–ç2‚&—FV×2ç6—¦R"’bbF–vW7Bæ6öçF–ç2‚%F6…w&—FW$’"’bbF–vW7Bæ6öçF–ç2‚$7'—FõVæ—fW'6Tf–ÇFW""’¢76W'EG'VR‚%cRããC3“c¢Æöær×F–ÂF–vW7B×W7B6Æ76–g’W'2Â6VçF–æVÇ2Â66ææW"ÂæB6VÆÂWF†÷&—G’6–FV6'2"ÂF–vW7Bæ6öçF–ç2‚'W'5÷6–FV6""’bbF–vW7Bæ6öçF–ç2‚'6VçF–æVÅö·•÷f—6–&ÆR"’bbF–vW7Bæ6öçF–ç2‚'66ææW%öÖöFUö†VÇW""’bbF–vW7Bæ6öçF–ç2‚'6VÆÅöWF†÷&—G•÷6–FV6%÷6÷W&6Uö6öçG&7B"’¢76W'EG'VR‚%cRããC3“c¢Æöær×F–ÂF–vW7B×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C%óC3“„&F6†W56V6öæDÆ÷u&VfW&Væ6U7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C"æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3“ƒ¢6V6öæBÆöær×F–ÂF–vW7B×W7B&F6‚6VÆÂÂ7FFRÂ66ææW"ÂW'2ÂæBÆV&æ–ær6–FV6'2"ÂF–vW7Bæ6öçF–ç2‚$&Ææ6U&ööe7FFR"’bbF–vW7Bæ6öçF–ç2‚$Æ–W$ÆæU&Vv—7G'’"’bbF–vW7Bæ6öçF–ç2‚%&÷WFUfÆ–FF÷""’bbF–vW7Bæ6öçF–ç2‚$&$ÆV&æ–ær"’bbF–vW7Bæ6öçF–ç2‚&6÷VçCÒ"’¢76W'EG'VR‚%cRããC3“ƒ¢6V6öæBÆöær×F–ÂF–vW7B×W7B6Æ76–g’6fWG’æB6–FV6"7W&f6W2v—F†÷WBFF–ærWF†÷&—G’"ÂF–vW7Bæ6öçF–ç2‚&&6U÷V÷FUö†&E÷6fWG•öwV&B"’bbF–vW7Bæ6öçF–ç2‚'W%öÆV&æ–æu÷6æ—G•övFR"’bbF–vW7Bæ6öçF–ç2‚'W'5öÖ&¶WE÷66ææW%÷6–FV6""’bbF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’¢76W'EG'VR‚%cRããC3“ƒ¢6V6öæBÆöær×F–ÂF–vW7B×W7B&Rµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–Ã#Ô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C""’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C"ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C5óC3“”&F6†W5F†—&DÆ÷u&VfW&Væ6U7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C2æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããC3““¢F†—&BÆöær×F–ÂF–vW7B×W7B&F6‚Ö–BÖÆ÷r×&VfW&Væ6RÖV6†æ—6×2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%ôÄôäuõD”ÅôÔT4„ä•4ÕôD”tU5C5óC3“’"’bbF–vW7Bæ6öçF–ç2‚$'VæFÆTFWFV7F÷""’bbF–vW7Bæ6öçF–ç2‚$fFÅ&—6´6†V6¶W""’bbF–vW7Bæ6öçF–ç2‚&6÷VçCÒ"’¢76W'EG'VR‚%cRããC3““¢F†—&BÆöær×F–ÂF–vW7B×W7B6÷fW"W†—B6÷7BÂÆæRF÷†–6—G’Â&W÷'G2Âc2æBW'26–FV6'2"ÂF–vW7Bæ6öçF–ç2‚$W†—D6÷7DÖ–7&ö'&–â"’bbF–vW7Bæ6öçF–ç2‚$ÆæUF÷†–6—G”wV&B"’bbF–vW7Bæ6öçF–ç2‚$Æ—fUvÆÆWDw&÷wF„v÷fW&æ÷%&W÷'B"’bbF–vW7Bæ6öçF–ç2‚%G&FTW†V7WF÷""’bbF–vW7Bæ6öçF–ç2‚$7'—Fô6æöæ–6ÄÆV&æ–ær"’¢76W'EG'VR‚%cRããC3““¢F†—&BÆöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–Ã3Ô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C2"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C2ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CEóCC&F6†W4f÷W'F„Æ÷u&VfW&Væ6U7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CBæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC¢f÷W'F‚Æöær×F–ÂF–vW7B×W7B&F6‚'W&âÂ6öç6Vç7W2ÂG&V7W'’ÂvÆÆWBæB6VÆÂÖ†—7F÷'’7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$&—&FW–TÖ–çD'W&äÖöæ—F÷""’bbF–vW7Bæ6öçF–ç2‚$'&–ä6öç6Vç7W4vFR"’bbF–vW7Bæ6öçF–ç2‚%G&V7W'•vÆÆWDÖævW""’bbF–vW7Bæ6öçF–ç2‚%vÆÆWDWF†÷&—G•6æ6†÷B"’bbF–vW7Bæ6öçF–ç2‚%6VÆÄf–ÇW&T†—7F÷'’"’¢76W'EG'VR‚%cRããCC¢f÷W'F‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFR’÷&—6²æBW†V7WF–öâ6ö÷&F–æF–öâ7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%VÇG&f7E'VtFWFV7F÷$’"’bbF–vW7Bæ6öçF–ç2‚$Æ—V–F—G”g&v–Æ—G”’"’bbF–vW7Bæ6öçF–ç2‚$W†V7WF–öå7FGW5&Vv—7G'’"’bbF–vW7Bæ6öçF–ç2‚%÷6—F–öäW†—D&&—FW""’bbF–vW7Bæ6öçF–ç2‚$7VÇDÖöÖVçGVÔ’"’¢76W'EG'VR‚%cRããCC¢f÷W'F‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃCÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CB"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CBç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CUóCC&F6†W5&W6–GVÄÆ÷u&VfW&Væ6U7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CRæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC¢f–gF‚Æöær×F–ÂF–vW7B×W7B&F6‚&W6–GVÂÆ÷r×&VfW&Væ6R6–FV6'2"ÂF–vW7Bæ6öçF–ç2‚$ÖVÖUG&FW$gVÆÄVF—E7vVWW""’bbF–vW7Bæ6öçF–ç2‚$W†V7WF&ÆUV÷FTvFR"’bbF–vW7Bæ6öçF–ç2‚%G&FU&÷u6æ—G”6†V6²"’bbF–vW7Bæ6öçF–ç2‚%6Ö'E6—¦W%c2"’¢76W'EG'VR‚%cRããCC¢f–gF‚Æöær×F–ÂF–vW7B×W7B6÷fW"ÄÄÒÂ&—&FW–RÂvÆÆWBÂ6—¦–æræB7&÷77FÆ²&W6–GVÇ2"ÂF–vW7Bæ6öçF–ç2‚$ÆÆÕW%G&FTW†V7WF÷""’bbF–vW7Bæ6öçF–ç2‚$&—&FW–UG&FTFF&÷f–FW""’bbF–vW7Bæ6öçF–ç2‚%vÆÆWE&Vg&W6„gFW%6VÆÂ"’bbF–vW7Bæ6öçF–ç2‚$ÖVÖT7&÷75FÆ´VçG'”'&–FvR"’bbF–vW7Bæ6öçF–ç2‚%÷6—F–öå6—¦–ær"’¢76W'EG'VR‚%cRããCC¢f–gF‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃSÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CR"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CRç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CeóCC$&F6†W5&VcuFó#U7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cbæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#¢6—‡F‚Æöær×F–ÂF–vW7B×W7B&F6‚&WÆ’Â6fWG’ÂÆVFvW"Â66ææW"æBÆ—fRÖWF†÷&—G’7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$6÷VçFW&f7GVÅ&WÆ”Væv–æR"’bbF–vW7Bæ6öçF–ç2‚%Fö¶Vå6fWG”6†V6¶W""’bbF–vW7Bæ6öçF–ç2‚%G&FT÷WF6öÖTÆVFvW""’bbF–vW7Bæ6öçF–ç2‚$Æ—fU÷6—F–öä6Æ÷6TWF†÷&—G’"’bbF–vW7Bæ6öçF–ç2‚%6V7W&—G”wV&B"’¢76W'EG'VR‚%cRããCC#¢6—‡F‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFRWFöæöÖ÷W2ÂÆ"Âc2ÂW'2æBvÆÆWB7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$WFöæöÖ÷W4ÖWFöÆ–7’"’bbF–vW7Bæ6öçF–ç2‚$ÆÆÔÆ$Væv–æR"’bbF–vW7Bæ6öçF–ç2‚$Ö&¶WE7G'V7GW&U&÷WFW""’bbF–vW7Bæ6öçF–ç2‚%W'5Væ–f–VE66÷&W$'&–FvR"’bbF–vW7Bæ6öçF–ç2‚%vÆÆWEFö¶VäÖVÖ÷'’"’¢76W'EG'VR‚%cRããCC#¢6—‡F‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃcÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cb"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cbç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CuóCC4&F6†W5&Vc#eFó3U7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cræ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3¢6WfVçF‚Æöær×F–ÂF–vW7B×W7B&F6‚66ææW"Â¦÷W&æÂÂvÆÆWBæBÖVÖ÷'’7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%6öÆæÖ&¶WE66ææW""’bbF–vW7Bæ6öçF–ç2‚%G&FT¦÷W&æÂ"’bbF–vW7Bæ6öçF–ç2‚$Æ—fUvÆÆWE&V6öæ6–ÆW""’bbF–vW7Bæ6öçF–ç2‚%G&F–ætÖVÖ÷'’"’¢76W'EG'VR‚%cRããCC3¢6WfVçF‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFR’Âfö–6RÂVFvRæB&Æ6¶Æ—7B7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$Æ–W$'&–â"’bbF–vW7Bæ6öçF–ç2‚%&Vv–ÖUG&ç6—F–öä’"’bbF–vW7Bæ6öçF–ç2‚%fö–6TÖævW""’bbF–vW7Bæ6öçF–ç2‚$VFvT÷F–Ö—¦W""’bbF–vW7Bæ6öçF–ç2‚%Fö¶Vä&Æ6¶Æ—7B"’¢76W'EG'VR‚%cRããCC3¢6WfVçF‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃsÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cr"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Crç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C…óCCD&F6†W5&Vc3eFóS7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C‚æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCC¢V–v‡F‚Æöær×F–ÂF–vW7B×W7B&F6‚6—¦–ærÂ&ööbÂ66÷&–æræB6VÆÂÖWF†÷&—G’7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$Æ—fU6—¦–æu&öf–ÆR"’bbF–vW7Bæ6öçF–ç2‚$&Ææ6U&ööev—E7FFR"’bbF–vW7Bæ6öçF–ç2‚%6Ö'DÖöæW”F—fW&vVæ6T’"’bbF–vW7Bæ6öçF–ç2‚%6VÆÄÖ÷VçDWF†÷&—G’"’¢76W'EG'VR‚%cRããCCC¢V–v‡F‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFRW'6—7FVæ6RÂ¦÷W&æÂ–FVçF—G’Âc2æB7&÷72×FÆ²7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$ÆV&æ–æuW'6—7FVæ6R"’bbF–vW7Bæ6öçF–ç2‚%G&FT–FVçF—G’"’bbF–vW7Bæ6öçF–ç2‚%c4Væv–æTÖævW""’bbF–vW7Bæ6öçF–ç2‚$7&÷75FÆ´gW6–öäVæv–æR"’bbF–vW7Bæ6öçF–ç2‚%VÇF–ÖFTVFvTVæv–æR"’¢76W'EG'VR‚%cRããCCC¢V–v‡F‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃƒÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C‚"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C‚ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C•óCCT&F6†W5&VcSFósU7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C’æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCS¢æ–çF‚Æöær×F–ÂF–vW7B×W7B&F6‚W&Ö—BÂ7&÷72×FÆ²ÂW'6—7FVæ6RæB†VÇF‚7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$f–æÄW†V7WF–öåW&Ö—B"’bbF–vW7Bæ6öçF–ç2‚$”7&÷75FÆ²"’bbF–vW7Bæ6öçF–ç2‚%÷6—F–öåW'6—7FVæ6R"’bbF–vW7Bæ6öçF–ç2‚$”†VÇF„Ööæ—F÷""’¢76W'EG'VR‚%cRããCCS¢æ–çF‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFRÆæRÂfW&–f–W"ÂG'W7BæB7G&FVw’–çFVÆÆ–vVæ6R7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$ÆæUFr"’bbF–vW7Bæ6öçF–ç2‚%G&FUfW&–f–W""’bbF–vW7Bæ6öçF–ç2‚%66÷&TW‡V7Fæ7•G&6¶W""’bbF–vW7Bæ6öçF–ç2‚%7G&FVw•G'W7D’"’bbF–vW7Bæ6öçF–ç2‚$&÷D'&–â"’¢76W'EG'VR‚%cRããCCS¢æ–çF‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–Ã“Ô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C’"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C’ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CóCCd&F6†W5&VcseFó7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCc¢FVçF‚Æöær×F–ÂF–vW7B×W7B&F6‚†Vg’Ö6÷&RWF†÷&—¦W"ÂÆ–fV7–6ÆRæBWF†÷&—G’7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%G&FTWF†÷&—¦W""’bbF–vW7Bæ6öçF–ç2‚%Fö¶VäÆ–fV7–6ÆUG&6¶W""’bbF–vW7Bæ6öçF–ç2‚$Væ&ÆVEG&FW$WF†÷&—G’"’bbF–vW7Bæ6öçF–ç2‚%&Vv–ÖTFWFV7F÷""’¢76W'EG'VR‚%cRããCCc¢FVçF‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFRÖæ—VÆFVBÂW'2æBÆ—V–F—G’7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$Öæ—VÆFVEG&FW$’"’bbF–vW7Bæ6öçF–ç2‚%W'4ÆV&æ–æt'&–FvR"’bbF–vW7Bæ6öçF–ç2‚$G–æÖ–4ÇEFö¶Vå&Vv—7G'’"’bbF–vW7Bæ6öçF–ç2‚$Æ—V–F—G”FWF„’"’¢76W'EG'VR‚%cRããCCc¢FVçF‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7CóCCt&F6†W5&VcFóS7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCs¢VÆWfVçF‚Æöær×F–ÂF–vW7B×W7B&F6‚'VçF–ÖRÂÖöFRÂ&Vv—7G'’æBÆV&æ–ær7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$&÷E'VçF–ÖT6öçG&öÆÆW""’bbF–vW7Bæ6öçF–ç2‚$ÖöFU&÷WFW""’bbF–vW7Bæ6öçF–ç2‚$vÆö&ÅG&FU&Vv—7G'’"’bbF–vW7Bæ6öçF–ç2‚$FF—fTÆV&æ–ætVæv–æR"’¢76W'EG'VR‚%cRããCCs¢VÆWfVçF‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFRW'2ÂG&V7W'’ÂVÆ—G’æB&ÇVV6†—7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%W'5G&FW$’"’bbF–vW7Bæ6öçF–ç2‚%G&V7W'”ÖævW""’bbF–vW7Bæ6öçF–ç2‚%VÆ—G•G&FW$’"’bbF–vW7Bæ6öçF–ç2‚$&ÇVT6†—G&FW$’"’¢76W'EG'VR‚%cRããCCs¢VÆWfVçF‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–ÃÔ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7Cç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C%óCC„&F6†W5&VcSFó#S7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C"æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCƒ¢GvVÆgF‚Æöær×F–ÂF–vW7B×W7B&F6‚†–v†W7BÖ–×7BÖVÖRÂ'VçF–ÖRæBvÆÆWB7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%6†—D6ö–åG&FW$’"’bbF–vW7Bæ6öçF–ç2‚$Öööç6†÷EG&FW$’"’bbF–vW7Bæ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’"’bbF–vW7Bæ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W""’¢76W'EG'VR‚%cRããCCƒ¢GvVÆgF‚Æöær×F–ÂF–vW7B×W7B–æ6ÇVFR†'f&BÂ6öÆÆV7F—fRÂ6öÆææBÖWFÖ6övæ—F–öâ7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚$VGV6F–öå7V$Æ–W$’"’bbF–vW7Bæ6öçF–ç2‚$6öÆÆV7F—fTÆV&æ–ær"’bbF–vW7Bæ6öçF–ç2‚%6öÆævÆÆWB"’bbF–vW7Bæ6öçF–ç2‚$ÖWF6övæ—F–öä’"’¢76W'EG'VR‚%cRããCCƒ¢GvVÆgF‚Æöær×F–ÂF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–Ã#Ô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C""’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C"ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C5óCC”&F6†W5&VÖ–æ–æu7WW&6÷&U7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C2æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC“¢F†—'FVVçF‚F–vW7B×W7B&F6‚&VÖ–æ–ær7WW&6÷&RWF†÷&—G’7W&f6W2"ÂF–vW7Bæ6öçF–ç2‚%G&FT†—7F÷'•7F÷&R"’bbF–vW7Bæ6öçF–ç2‚$fÇV–DÆV&æ–æt’"’bbF–vW7Bæ6öçF–ç2‚$Æ—fUG&FTÆöu7F÷&R"’bbF–vW7Bæ6öçF–ç2‚$&÷E6W'f–6R"’¢76W'EG'VR‚%cRããCC“¢7WW&6÷&RF–vW7B×W7BW‡Æ–6—FÇ’&VÖ–â&W÷'BÖöæÇ’FW7—FRWF†÷&—G’&÷†–Ö—G’"ÂF–vW7Bæ6öçF–ç2‚'7WW&6÷&UöWF†÷&—G•÷7W&f6S×G'VR"’bbF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’¢76W'EG'VR‚%cRããCC“¢7WW&6÷&RF–vW7B×W7B&Rµ’×v—&VBv—F†÷WB†÷B×F‚&÷f–FW"6ÆÇ2"ÂF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&Æöæu÷F–Ã3Ô÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C2"’bb·’æ6öçF–ç2‚$÷W&F÷$ÆöæuF–ÄÖV6†æ—6ÔF–vW7C2ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$6†ö¶T'WGFW&fÇ”VF—DÆVFvW%óCCG&6·56–&Æ–æt6†ö¶TfÖ–Æ–W2‚’°¢fÂÆVFvW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$6†ö¶T'WGFW&fÇ”VF—DÆVFvW"æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC¢6†ö¶RÆVFvW"×W7BG&6²6–&Æ–ær†&B×7F÷GFW&âfÖ–Æ–W2"ÂÆVFvW"æ6öçF–ç2‚%¤U$õõ4•¤Uôõ%ôÕTÅD•Ä”U""’bbÆVFvW"æ6öçF–ç2‚$„$Eõ$UEU$åôdÅ4R"’bbÆVFvW"æ6öçF–ç2‚$D”Å•ôÄõ55õU4Uôõ%õ5E$T²"’¢76W'EG'VR‚%cRããCC¢6†ö¶RÆVFvW"×W7B–æ6ÇVFRfWFò÷&V¦V7BæB†÷B×F‚&÷f–FW"'WGFW&fÇ’fÖ–Æ–W2"ÂÆVFvW"æ6öçF–ç2‚%dUDõôõ%õ$T¤T5EõD„ôäôÕ’"’bbÆVFvW"æ6öçF–ç2‚$„õED…õ$õd”DU%ô„”åB"’bbÆVFvW"æ6öçF–ç2‚&'WGFW&fÆ–W5ö6öç6–FW&VC×G'VR"’¢76W'EG'VR‚%cRããCC¢6†ö¶RÆVFvW"×W7B&VÖ–â&W÷'BÖöæÇ’Âµ’×v—&VBæB6÷W&6RÖ6öçG&7B6fR"ÂÆVFvW"æ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbÆVFvW"æ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbÆVFvW"æ6öçF–ç2‚&æõö†÷E÷F…÷&÷f–FW%ö6ÆÇ3×G'VR"’bb·’æ6öçF–ç2‚&6†ö¶Uö'WGFW&fÇ“Ô÷W&F÷$6†ö¶T'WGFW&fÇ”VF—DÆVFvW""’bb·’æ6öçF–ç2‚$÷W&F÷$6†ö¶T'WGFW&fÇ”VF—DÆVFvW"ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$6†ö¶U&VÖVF–F–öåVWVUóCC&–÷&—F—¦W56–&Æ–æt'WGFW&fÇ”f—†W2‚’°¢fÂVWVRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$6†ö¶U&VÖVF–F–öåVWVRæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC¢&VÖVF–F–öâVWVR×W7BæÖR¦W&ò×6—¦RæBfÇ6R×&WGW&â6÷W&6RfÖ–Æ–W2"ÂVWVRæ6öçF–ç2‚$5ôU„T5UD”ôåõ¤U$õõ4•¤Uõ5D4²"’bbVWVRæ6öçF–ç2‚$5%ôUD„õ$•E•ôdÅ4Uõ$UEU$åõ5D4²"’bbVWVRæ6öçF–ç2‚'6÷W&6Uöf—…öf—'7C×G'VR"’¢76W'EG'VR‚%cRããCC¢&VÖVF–F–öâVWVR×W7BæÖRW6RÂ&V¦V7B×F†öæö×’æB&÷f–FW"†÷B×F‚'WGFW&fÇ’fÖ–Æ–W2"ÂVWVRæ6öçF–ç2‚$55ôD”Å•õU4Uõ$T4õdU%•õ$ô$Uõ$•E’"’bbVWVRæ6öçF–ç2‚$5Eõ$T¤T5EõD„ôäôÕ•ôäõ$ÔÄ•¤D”ôâ"’bbVWVRæ6öçF–ç2‚$5Uõ$õd”DU%ô4ÄÅô$4´u$õTäEô•4ôÄD”ôâ"’bbVWVRæ6öçF–ç2‚&'WGFW&fÆ–W5öæÖVC×G'VR"’¢76W'EG'VR‚%cRããCC¢&VÖVF–F–öâVWVR×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VBVçF–Â7W&v–6Âf—†W2&R6VÆV7FVB"ÂVWVRæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbVWVRæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbVWVRæ6öçF–ç2‚&vöÆFVå÷FU÷&WV—&VC×G'VR"’bb·’æ6öçF–ç2‚&6†ö¶U÷VWVSÔ÷W&F÷$6†ö¶U&VÖVF–F–öåVWVR"’bb·’æ6öçF–ç2‚$÷W&F÷$6†ö¶U&VÖVF–F–öåVWVRç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$5¦W&õ6—¦T†—DÆ—7EóCC%6W&FW46†ö¶W4g&öÔ&Væ–vå¦W&öW2‚’°¢fÂ†—DÆ—7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$5¦W&õ6—¦T†—DÆ—7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3¢5†—BÆ—7B×W7B&Wf–Wr¦W&ò×6—¦R6æF–FFW2v†–ÆR&W6W'f–ær6—FÂ6fWG’"Â†—DÆ—7Bæ6öçF–ç2‚$66„vVæW&F–öä’æ·B"’bb†—DÆ—7Bæ6öçF–ç2‚$ÆæU7G&FVw”WfÇVF÷"æ·B"’bb†—DÆ—7Bæ6öçF–ç2‚&6—FÅ÷6fWG•÷&W6W'fVC×G'VR"’¢76W'EG'VR‚%cRããCC#¢5†—BÆ—7B×W7B6W&FR&Væ–vâ¦W&öW2Fòfö–BVç6fR&Ææ¶WBF6†W2"Â†—DÆ—7Bæ6öçF–ç2‚$f–æÄFV6—6–öävFRæ·B"’bb†—DÆ—7Bæ6öçF–ç2‚$Æ—fU6—¦–æu&öf–ÆRæ·B"’bb†—DÆ—7Bæ6öçF–ç2‚$$Tä”tåõDTÄTÔUE%•ôuT$B"’bb†—DÆ—7Bæ6öçF–ç2‚&fÇ6U÷÷6—F—fW5÷6W&FVC×G'VR"’¢76W'EG'VR‚%cRããCC#¢5†—BÆ—7B×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VB"Â†—DÆ—7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚&'WGFW&fÆ–W5öæÖVC×G'VR"’bb·’æ6öçF–ç2‚&7÷¦W&õ÷6—¦SÔ÷W&F÷$5¦W&õ6—¦T†—DÆ—7B"’bb·’æ6öçF–ç2‚$÷W&F÷$5¦W&õ6—¦T†—DÆ—7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$5$WF†÷&—G”fÇ6U&WGW&ä†—DÆ—7EóCC4æÖW5&VÆV6Uf—6–&–Æ—G•6–&Æ–æw2‚’°¢fÂ†—DÆ—7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$5$WF†÷&—G”fÇ6U&WGW&ä†—DÆ—7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3¢5"†—BÆ—7B×W7BæÖRdUÂÆæR&VÆV6RæB&÷E6W'f–6RfÇ6R×&WGW&â6–&Æ–æw2"Â†—DÆ—7Bæ6öçF–ç2‚$f–æÄW†V7WF–öåW&Ö—Bæ·B"’bb†—DÆ—7Bæ6öçF–ç2‚$ÆæTW†V7WF–öä6ö÷&F–æF÷"æ·B"’bb†—DÆ—7Bæ6öçF–ç2‚$&÷E6W'f–6Ræ·B"’¢76W'EG'VR‚%cRããCC3¢5"†—BÆ—7B×W7B&W6W'fR†&B6fWG’v†–ÆRFVÖæF–ær&VÆV6Rf—6–&–Æ—G’"Â†—DÆ—7Bæ6öçF–ç2‚&†&E÷6fWG•÷&W6W'fVC×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚'&VÆV6U÷f—6–&–Æ—G•÷&WV—&VC×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚$dÅ4Uõõ4•D•dUô4ÄT$TB"’¢76W'EG'VR‚%cRããCC3¢5"†—BÆ—7B×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VB"Â†—DÆ—7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb·’æ6öçF–ç2‚&7%öfÇ6U÷&WGW&ãÔ÷W&F÷$5$WF†÷&—G”fÇ6U&WGW&ä†—DÆ—7B"’bb·’æ6öçF–ç2‚$÷W&F÷$5$WF†÷&—G”fÇ6U&WGW&ä†—DÆ—7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$545EW6U&V¦V7D†—DÆ—7EóCCD'VæFÆW5W6TæE&V¦V7EF†öæö×’‚’°¢fÂ†—DÆ—7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$545EW6U&V¦V7D†—DÆ—7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCC¢52ô5B†—BÆ—7B×W7B'VæFÆR&V6÷fW'’×&ö&RæB†&B×6fWG’W6R&÷VæF&–W2"Â†—DÆ—7Bæ6öçF–ç2‚$55ôÄäUôÄô4ÅõU4Uõ$T4õdU%•õ$ô$R"’bb†—DÆ—7Bæ6öçF–ç2‚$55ôtÄô$Åõ4dUE•ô$õTäD%’"’bb†—DÆ—7Bæ6öçF–ç2‚&†&E÷6fWG•ö&÷VæF&–W5÷&W6W'fVC×G'VR"’¢76W'EG'VR‚%cRããCCC¢52ô5B†—BÆ—7B×W7B'VæFÆR&V¦V7BF†öæö×’æB66ææW"†&B&V¦V7B&÷VæF&–W2"Â†—DÆ—7Bæ6öçF–ç2‚$5Eõ$T¤T5EõD„ôäôÕ•ôE$”eB"’bb†—DÆ—7Bæ6öçF–ç2‚$5Eõ44ääU%ô„$Eõ$T¤T5Eô$õTäD%’"’bb†—DÆ—7Bæ6öçF–ç2‚'&V¦V7E÷F†öæö×•÷&WV—&VC×G'VR"’¢76W'EG'VR‚%cRããCCC¢52ô5B†—BÆ—7B×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VB"Â†—DÆ—7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚&'WGFW&fÆ–W5öæÖVC×G'VR"’bb·’æ6öçF–ç2‚&75ö7E÷W6U÷&V¦V7CÔ÷W&F÷$545EW6U&V¦V7D†—DÆ—7B"’bb·’æ6öçF–ç2‚$÷W&F÷$545EW6U&V¦V7D†—DÆ—7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$5U&÷f–FW$†÷EF„†—DÆ—7EóCCTæÖW5&÷f–FW$—6öÆF–öä'WGFW&fÆ–W2‚’°¢fÂ†—DÆ—7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$5U&÷f–FW$†÷EF„†—DÆ—7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCS¢5R†—BÆ—7B×W7BæÖR66ææW"&÷f–FW"'VFvWBæBV÷FRf–ÇW&R&÷VæF&–W2"Â†—DÆ—7Bæ6öçF–ç2‚$5Uõ44ääU%õ$õd”DU%ô%TDtUEô$õTäD%’"’bb†—DÆ—7Bæ6öçF–ç2‚$5UôU„T5UDõ%õTõDUôd”ÅU$Uô$õTäD%’"’bb†—DÆ—7Bæ6öçF–ç2‚'V÷FU÷F†öæö×•÷&WV—&VC×G'VR"’¢76W'EG'VR‚%cRããCCS¢5R†—BÆ—7B×W7BæÖR&6¶w&÷VæB–çFVÆÆ–vVæ6RæBvÆÆWB%2G'W7B&÷VæF&–W2"Â†—DÆ—7Bæ6öçF–ç2‚$5Uô$4´u$õTäEô”åDTÄÄ”tTä4Uõ$õd”DU%ô$õTäD%’"’bb†—DÆ—7Bæ6öçF–ç2‚$5UõtÄÄUEõ%5õE%U5Eô$õTäD%’"’bb†—DÆ—7Bæ6öçF–ç2‚'vÆÆWE÷G'W7Eö&÷VæF'•÷&WV—&VC×G'VR"’¢76W'EG'VR‚%cRããCCS¢5R†—BÆ—7B×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VB"Â†—DÆ—7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb†—DÆ—7Bæ6öçF–ç2‚&66†Uöf—'7E÷&WV—&VC×G'VR"’bb·’æ6öçF–ç2‚&7U÷&÷f–FW%ö†÷E÷FƒÔ÷W&F÷$5U&÷f–FW$†÷EF„†—DÆ—7B"’bb·’æ6öçF–ç2‚$÷W&F÷$5U&÷f–FW$†÷EF„†—DÆ—7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷$6†ö¶U6÷W&6T6öçG&7E6VçF–æVÅóCCe&÷FV7G45&V†f–÷%F6„&÷VæF&–W2‚’°¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$6†ö¶U6÷W&6T6öçG&7E6VçF–æVÂæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCc¢6÷W&6RÖ6öçG&7B6VçF–æVÂ×W7B6÷fW"5Ô5RfÖ–Æ–W2"Â6VçF–æVÂæ6öçF–ç2‚$5õ¤U$õõ4•¤Uô4ôåE$5B"’bb6VçF–æVÂæ6öçF–ç2‚$5%ôdÅ4Uõ$UEU$åô4ôåE$5B"’bb6VçF–æVÂæ6öçF–ç2‚$55õU4Uõ$T4õdU%•ô4ôåE$5B"’bb6VçF–æVÂæ6öçF–ç2‚$5Eõ$T¤T5EõD„ôäôÕ•ô4ôåE$5B"’bb6VçF–æVÂæ6öçF–ç2‚$5Uõ$õd”DU%ô„õEõD…ô4ôåE$5B"’¢76W'EG'VR‚%cRããCCc¢6÷W&6RÖ6öçG&7B6VçF–æVÂ×W7B&W6W'fR6fWG’v†–ÆRVæ&Æ–ær&VÆV6Rf—6–&–Æ—G’æB66†RÖf—'7Bf—†W2"Â6VçF–æVÂæ6öçF–ç2‚&†&E÷6fWG•÷&W6W'fVC×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚'&VÆV6U÷f—6–&–Æ—G•÷&WV—&VC×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&66†Uöf—'7E÷&WV—&VC×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚'&V¦V7E÷F†öæö×•÷&WV—&VC×G'VR"’¢76W'EG'VR‚%cRããCCc¢6÷W&6RÖ6öçG&7B6VçF–æVÂ×W7B&VÖ–â&W÷'BÖöæÇ’æBµ’×v—&VB"Â6VçF–æVÂæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚&vöÆFVå÷FU÷&WV—&VC×G'VR"’bb·’æ6öçF–ç2‚&6†ö¶Uö6öçG&7G3Ô÷W&F÷$6†ö¶U6÷W&6T6öçG&7E6VçF–æVÂ"’bb·’æ6öçF–ç2‚$÷W&F÷$6†ö¶U6÷W&6T6öçG&7E6VçF–æVÂç7FGW2"’¢Ð  ¢FW7@¢gVâf–æÄW†V7WF–öåW&Ö—DfÇ6U&WGW&ç5óCCd&U&÷WFUf—6–&ÆUv—F†÷WD6†æv–ætWF†÷&—G’‚’°¢fÂ6÷W&6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄW†V7WF–öåW&Ö—Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCc¢dUfÇ6R×&WGW&â'&æ6†W2×W7BVÖ—B&÷WFR×f—6–&ÆRFVÆVÖWG'’"Â6÷W&6Ræ6öçF–ç2‚'&V6÷&EW&Ö—DfÇ6U&WGW&ãCCb"’bb6÷W&6Ræ6öçF–ç2‚$dUôdÅ4Uõ$UEU$åõ$õUDUõd•4”$ÄUóCCb"’bb6÷W&6Ræ6öçF–ç2‚%—VÆ–æT†VÇF„6öÆÆV7F÷"æÆ&VÄ–æ2"’¢76W'EG'VR‚%cRããcC“C¢dU&÷WFR×f—6–&ÆRFVÆVÖWG'’×W7B6÷fW"'VçF–ÖRÂf–æÆ—G’Â–Ö×WF&ÆR×F–6¶WBæBVæF–ærfÇ6R&WGW&ç2"Â6÷W&6Ræ6öçF–ç2‚%%TåD”ÔUõU4TB"’bb6÷W&6Ræ6öçF–ç2‚%%TåD”ÔUôõdU$Ä•ôD•4$ÄTB"’bb6÷W&6Ræ6öçF–ç2‚$”ÔÕUD$ÄUôU„T5õD”4´UEôÔ•54”äuócC“B"’bb6÷W&6Ræ6öçF–ç2‚$”ÔÕUD$ÄUôTÄT5D”ôåôÄäUôÔ•4ÔD4…ócC“B"’bb6÷W&6Ræ6öçF–ç2‚%TäD”äuò"’¢76W'EG'VR‚%cRããCC#¢dU&V†f–÷"&VÖ–ç2WF†÷&—G’×&W6W'f–æs²FVÆVÖWG'’&V6÷&G2&Vf÷&RW†—7F–ærfÇ6R&WGW&ç2"Â6÷W&6Ræ6öçF–ç2‚$d”äÄ•E•ò"’bb6÷W&6Ræ6öçF–ç2‚'&VÆV6U&–Ö'”gFW%W&Ö—Df–ÇW&R"’bb6÷W&6Ræ6öçF–ç2‚'&V6÷&EW&Ö—DfÇ6U&WGW&ãCCb"’bb6÷W&6Ræ6öçF–ç2‚'&WGW&âfÇ6R"’¢Ð  ¢FW7@¢gVâÆæTW†V7WF–öä6ö÷&F–æF÷%&VÆV6TfÇ6UóCC”—5&÷WFUf—6–&ÆR‚’°¢fÂ6÷W&6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæTW†V7WF–öä6ö÷&F–æF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC“¢ÆæR&–Ö'’&VÆV6RfÇ6RF‡2×W7BVÖ—Bf—6–&ÆRFVÆVÖWG'’"Â6÷W&6Ræ6öçF–ç2‚$ÄäUõ$”Ô%•õ$TÄT4UôdÅ4Uõd•4”$ÄUóCC’"’bb6÷W&6Ræ6öçF–ç2‚%—VÆ–æT†VÇF„6öÆÆV7F÷"æÆ&VÄ–æ2"’¢76W'EG'VR‚%cRããCC“¢ÆæR&VÆV6RfÇ6RFVÆVÖWG'’×W7B6÷fW"Ö—76–ærVÆV7F–öâæBæöâ×&–Ö'’÷WF6öÖW2"Â6÷W&6Ræ6öçF–ç2‚$Ô•54”äuôTÄT5D”ôâ"’bb6÷W&6Ræ6öçF–ç2‚$äõEõ$”Ô%’"’bb6÷W&6Ræ6öçF–ç2‚'&WGW&âfÇ6R"’¢76W'EG'VR‚%cRããCC“¢ÆæR&VÆV6RG'VRF‚×W7B&VÖ–âVæ6†ævVB"Â6÷W&6Ræ6öçF–ç2‚$ÄäUõ$”Ô%•õ$TÄT4TB"’bb6÷W&6Ræ6öçF–ç2‚'&WGW&â&VÖ÷fVB"’¢Ð  ¢FW7@¢gVâ6†ö¶Uf—6–&–Æ—G•FVÆVÖWG'•óCC#W6W4&÷VæFVE6–FTVffV7D'W2‚’°¢fÂfWÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄW†V7WF–öåW&Ö—Bæ·B"’ç&VEFW‡B‚¢fÂÆæRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæTW†V7WF–öä6ö÷&F–æF÷"æ·B"’ç&VEFW‡B‚¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$6†ö¶U6÷W&6T6öçG&7E6VçF–æVÂæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#¢dUfÇ6R×&WGW&âf—6–&–Æ—G’×W7BW6R6†ö¶U&VÆ–Vd'W2–ç7FVBöb–æÆ–æR†Vg’Æövv–ær"ÂfWæ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’bbfWæ6öçF–ç2‚$dUôdÅ4Uõ$UEU$åõ$õUDUõd•4”$ÄUóCC#"’bbfWæ6öçF–ç2‚'f–Ô6†ö¶U&VÆ–Vd'W2"’¢76W'EG'VR‚%cRããCC#¢ÆæR&VÆV6RÖfÇ6Rf—6–&–Æ—G’×W7BW6R6†ö¶U&VÆ–Vd'W2–ç7FVBöb–æÆ–æR†Vg’Æövv–ær"ÂÆæRæ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’bbÆæRæ6öçF–ç2‚$ÄäUõ$”Ô%•õ$TÄT4UôdÅ4Uõd•4”$ÄUóCC#"’bbÆæRæ6öçF–ç2‚'f–Ô6†ö¶U&VÆ–Vd'W2"’¢76W'EG'VR‚%cRããCC#¢6†ö¶R6÷W&6R6öçG&7B×W7B–â&÷VæFVB'W2W6vR"Â6VçF–æVÂæ6öçF–ç2‚&6†ö¶U÷f—6–&–Æ—G•ö'W5óCC#×G'VR"’bb6VçF–æVÂæ6öçF–ç2‚'&VÆV6U÷f—6–&–Æ—G•÷&WV—&VC×G'VR"’¢Ð  ¢FW7@¢gVâWFôÖöFUW6VEóCC#$—5&V6÷fW'•&ö&Tæ÷D†&D6†ö¶R‚’°¢fÂ6÷W&6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôWFôÖöFTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$6†ö¶U6÷W&6T6öçG&7E6VçF–æVÂæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC##¢WFôÖöFRU4TB×W7B&VÖ–âV–WBÖ†÷W"6WF–öâÖöFR"Â6÷W&6Ræ6öçF–ç2‚$&÷DÖöFRåU4TB"’bb6÷W&6Ræ6öçF–ç2‚$UDõõU4TEõ$T4õdU%•õ$ô$UóCC#""’¢76W'EG'VR‚%cRããCC##¢WFôÖöFRU4TB×W7Bæ÷B†&BÖ×WFFRVçG&–W2v—F‚““—‚óã6—¦–ær"Â6÷W&6Ræ6öçF–ç2‚&VçG'•66÷&T×VÇF—Æ–W"Ò““’ã"’bb6÷W&6Ræ6öçF–ç2‚'÷6—F–öå6—¦T×VÇF—Æ–W"Òã"’¢76W'EG'VR‚%cRããCC##¢WFôÖöFRU4TB×W7BW6R&÷VæFVB&V6÷fW'’×&ö&R6†–ær"Â6÷W&6Ræ6öçF–ç2‚&VçG'•66÷&T×VÇF—Æ–W"Òã3R"’bb6÷W&6Ræ6öçF–ç2‚'÷6—F–öå6—¦T×VÇF—Æ–W"Òã3R"’bb6VçF–æVÂæ6öçF–ç2‚&WFöÖöFU÷W6VE÷&V6÷fW'•÷&ö&UóCC##×G'VR"’¢Ð  ¢FW7@¢gVâ&V¦V7EF†öæö×•óCC#4æ÷&ÖÆ—¦W45DÆ&VÇ5v—F†÷WD6†æv–ætWF†÷&—G’‚’°¢fÂF†öæö×’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×’æ·B"’ç&VEFW‡B‚¢fÂWF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FTWF†÷&—¦W"æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#3¢&V¦V7BF†öæö×’×W7BVæf÷&6RVæF–æröÆ÷rÖÆ—÷Vç&öf—F&ÆRFö7G&–æR"ÂF†öæö×’æ6öçF–ç2‚'VæF–æsÕTäÅE’"’bbF†öæö×’æ6öçF–ç2‚&Æ÷uöÆ—ÔÄõuôÄ•õ4•¤Uõ$TET5D”ôâ"’bbF†öæö×’æ6öçF–ç2‚'Vç&öf—F&ÆSÔ4õ5Eõ$T¤T5B"’¢76W'EG'VR‚%cRããCC#3¢WF†÷&—¦F–öå&W7VÇB×W7BW‡÷6RF†öæö×’v—F†÷WB6†æv–ær6öç7G'V7F÷"öWF†÷&—G’&V†f–÷""ÂWF‚æ6öçF–ç2‚'fÂ&V¦V7EF†öæö×“¢&V¦V7EF†öæö×’ä6Æ76–f–6F–öâ"’bbWF‚æ6öçF–ç2‚%&V¦V7EF†öæö×’æ6Æ76–g’‡&V6öâÂ&Æö6´ÆWfVÂ’"’bbF†öæö×’æ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢76W'EG'VR‚%cRããCC#3¢&V¦V7BF†öæö×’×W7B&Rµ’×v—&VBæB†&B×6fWG’&W6W'f–ær"Â·’æ6öçF–ç2‚'&V¦V7E÷F†öæö×“Õ&V¦V7EF†öæö×’"’bb·’æ6öçF–ç2‚%&V¦V7EF†öæö×’ç7FGW2"’bbF†öæö×’æ6öçF–ç2‚&†&E÷6fWG•÷&W6W'fVC×G'VR"’¢Ð  ¢FW7@¢gVâG&FTWF†÷&—¦W%&V¦V7EF†öæö×•óCC#D—46öç7VÖVEf–6†ö¶U&VÆ–Vd'W2‚’°¢fÂWF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FTWF†÷&—¦W"æ·B"’ç&VEFW‡B‚¢fÂF†öæö×’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#C¢G&FTWF†÷&—¦W"×W7B6öç7VÖR&V¦V7BF†öæö×’F‡&÷Vv‚&÷VæFVB†VÇW""ÂWF‚æ6öçF–ç2‚'&V¦V7DWFƒCC#B"’bbWF‚æ6öçF–ç2‚%E$DUôUD…õ$T¤T5EõD„ôäôÕ•óCC#B"’bbWF‚æ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’¢76W'EG'VR‚%cRããCC#C¢†–v‚Ö6†ö¶RWF‚&V¦V7G2×W7B&÷WFRF‡&÷Vv‚F†öæö×’†VÇW""ÂWF‚æ6öçF–ç2‚%$TUD…ô$Äô4µõ%TåD”ÔUõU4TB"’bbWF‚æ6öçF–ç2‚$DTdU%õ4ÄõEô„TÅD…ò"’bbWF‚æ6öçF–ç2‚%$TUD…ò"’bbWF‚æ6öçF–ç2‚$d”äÄ•E•ò"’¢76W'EG'VR‚%cRããCC#C¢&V¦V7BF†öæö×’&VÖ–ç2WF†÷&—G’ÖæWWG&ÂæB—2Ö&¶VB6öç7VÖVB"ÂF†öæö×’æ6öçF–ç2‚'G&FUöWF†÷&—¦W%ö6öç7VÖVEóCC#C×G'VR"’bbF†öæö×’æ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢Ð  ¢FW7@¢gVâ&V¦V7EF†öæö×”ÆVFvW%óCC#T6öç7VÖW5G&FTWF†÷&—¦W$6FVv÷&–W4f÷%&W÷'G2‚’°¢fÂÆVFvW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×”ÆVFvW"æ·B"’ç&VEFW‡B‚¢fÂWF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FTWF†÷&—¦W"æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#S¢&V¦V7BF†öæö×’ÆVFvW"×W7B6÷VçB'’6FVv÷'’æBÆæRv—F†÷WBWF†÷&—G’"ÂÆVFvW"æ6öçF–ç2‚%$T¤T5EõD„ôäôÕ•ôÄTDtU%óCC#R"’bbÆVFvW"æ6öçF–ç2‚&'”6CÒ"’bbÆVFvW"æ6öçF–ç2‚'F÷ÆæSÒ"’bbÆVFvW"æ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢76W'EG'VR‚%cRããCC#S¢G&FTWF†÷&—¦W"F†öæö×’†VÇW"×W7BfVVBF†R&W÷'B×6–FRÆVFvW"f–&÷VæFVB'W2"ÂWF‚æ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç&V6÷&B"’bbWF‚æ6öçF–ç2‚&ÆVFvW#Õ&V¦V7EF†öæö×”ÆVFvW""’bbWF‚æ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’¢76W'EG'VR‚%cRããCC#S¢&V¦V7BF†öæö×’ÆVFvW"×W7B&Rµ’×v—&VB"Â·’æ6öçF–ç2‚'&V¦V7E÷F†öæö×•öÆVFvW#Õ&V¦V7EF†öæö×”ÆVFvW""’bb·’æ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷%&V¦V7E&W77W&TF–vW7EóCC#e7W&f6W5F†öæö×”6†ö¶TÖ‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%&V¦V7E&W77W&TF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#c¢&V¦V7B&W77W&RF–vW7B×W7B6öç7VÖRF†öæö×’æBÆVFvW""ÂF–vW7Bæ6öçF–ç2‚%&V¦V7EF†öæö×’ç7FGW2"’bbF–vW7Bæ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç7FGW2"’bbF–vW7Bæ6öçF–ç2‚$õU$Dõ%õ$T¤T5Eõ$U55U$UôD”tU5EóCC#b"’¢76W'EG'VR‚%cRããCC#c¢&V¦V7B&W77W&RF–vW7B×W7B&W6W'fRFö7G&–æRÖ–ær"ÂF–vW7Bæ6öçF–ç2‚'VæF–æuö—5÷VæÇG’"’bbF–vW7Bæ6öçF–ç2‚&Æ÷uöÆ—ö—5÷6—¦U÷&VGV7F–öâ"’bbF–vW7Bæ6öçF–ç2‚'Vç&öf—F&ÆUö—5ö6÷7E÷&V¦V7B"’bbF–vW7Bæ6öçF–ç2‚&†&E÷6fWG•÷&W6W'fVB"’¢76W'EG'VR‚%cRããCC#c¢&V¦V7B&W77W&RF–vW7B×W7B7F’&W÷'BÖöæÇ’æBµ’×v—&VB"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’bb·’æ6öçF–ç2‚'&V¦V7E÷&W77W&UöF–vW7CÔ÷W&F÷%&V¦V7E&W77W&TF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%&V¦V7E&W77W&TF–vW7Bç7FGW2"’¢Ð  ¢FW7@¢gVâf–æÄFV6—6–öävFU&V¦V7EF†öæö×•óCC#tfVVG4ÆVFvW$6VçG&ÆÇ’‚’°¢fÂfFrÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄFV6—6–öävFRæ·B"’ç&VEFW‡B‚¢fÂF†öæö×’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#s¢f–æÄFV6—6–öâ×W7BW‡÷6Ræ÷&ÖÆ—¦VB&V¦V7BF†öæö×’"ÂfFræ6öçF–ç2‚'fÂ&V¦V7EF†öæö×“¢&V¦V7EF†öæö×’ä6Æ76–f–6F–öãò"’bbfFræ6öçF–ç2‚%&V¦V7EF†öæö×’æ6Æ76–g’†&Æö6µ&V6öâó¢&÷fÅ&V6öâÂçVÆÂ’"’¢76W'EG'VR‚%cRããCC#s¢dDr&V¦V7BF†öæö×’×W7B&RfVBF‡&÷Vv‚&÷VæFVB6†ö¶U&VÆ–Vd'W2–çFòF†RÆVFvW""ÂfFræ6öçF–ç2‚$dDuõ$T¤T5EõD„ôäôÕ•óCC#r"’bbfFræ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’bbfFræ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç&V6÷&B"’¢76W'EG'VR‚%cRããCC#s¢¦W&òÖÆ—V–F—G’×W7B7F’†&B6fWG’v†–ÆRvVæW&–2Æ÷rÖÆ—Ö2Fò6—¦R×&VGV7F–öâ"ÂF†öæö×’æ6öçF–ç2‚%¤U$õôÄ•T”D•E’"’bbF†öæö×’æ6öçF–ç2‚'¦W&õöÆ—V–F—G•ö†&E÷6fWG“×G'VR"’bbF†öæö×’æ6öçF–ç2‚&fFuö6öç7VÖVEóCC#s×G'VR"’¢Ð  ¢FW7@¢gVâW†V7WF÷%&TGFV×E&V¦V7EF†öæö×•óCC#„fVVG4ÆVFvW%v—F†÷WD&Æö6¶–ætÆ—fT'W”f–Â‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂF†öæö×’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#ƒ¢W†V7WF÷"Æ—fR&VGFV×B†&B&V¦V7G2×W7B6Æ76–g’–çFò&V¦V7BF†öæö×’"ÂW†V2æ6öçF–ç2‚$U„T5UDõ%õ$TEDTÕEõ$T¤T5EõD„ôäôÕ•óCC#‚"’bbW†V2æ6öçF–ç2‚%&V¦V7EF†öæö×’æ6Æ76–g’‡&V6öâÂG&FTWF†÷&—¦W"ä&Æö6´ÆWfVÂä„$B’"’¢76W'EG'VR‚%cRããCC#ƒ¢W†V7WF÷"&V¦V7BF†öæö×’×W7B&RÆVFvW&VBf–&÷VæFVB6†ö¶U&VÆ–Vd'W2"ÂW†V2æ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’bbW†V2æ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç&V6÷&B"’bbW†V2æ6öçF–ç2‚&ÆVFvW#Õ&V¦V7EF†öæö×”ÆVFvW""’¢76W'EG'VR‚%cRããCC#ƒ¢Æ—fR'W’f–ÇW&RVÖ—76–öâ×W7B&VÖ–â÷WG6–FRF†R&W÷'BÖöæÇ’FVÆVÖWG'’'W2"ÂW†V2æ6öçF–ç2‚&VÖ—DÆ—fT'W”f–Â‡G2Â6öÂÂ&V6öâÂFWF–Â’"’bbF†öæö×’æ6öçF–ç2‚&W†V7WF÷%÷&VGFV×Eö6öç7VÖVEóCC#ƒ×G'VR"’¢Ð  ¢FW7@¢gVâ66ææW$†&E&V¦V7EF†öæö×•óCC#”fVVG4ÆVFvW$g&öÔ6VçG&Å7F÷&R‚’°¢fÂ7F÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ66ææW$†&E&V¦V7E7F÷&Ræ·B"’ç&VEFW‡B‚¢fÂF†öæö×’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC#“¢66ææW"†&B&V¦V7B7F÷&R×W7B6Æ76–g’†&B×&V¦V7B&V6öç2"Â7F÷&Ræ6öçF–ç2‚%44ääU%ô„$Eõ$T¤T5EõD„ôäôÕ•óCC#’"’bb7F÷&Ræ6öçF–ç2‚%&V¦V7EF†öæö×’æ6Æ76–g’†6ÆVå&V6öâÂG&FTWF†÷&—¦W"ä&Æö6´ÆWfVÂä„$B’"’¢76W'EG'VR‚%cRããCC#“¢66ææW"†&B&V¦V7BF†öæö×’×W7BfVVBF†RÆVFvW"f–6†ö¶U&VÆ–Vd'W2"Â7F÷&Ræ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’bb7F÷&Ræ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç&V6÷&B"’bb7F÷&Ræ6öçF–ç2‚&ÆVFvW#Õ&V¦V7EF†öæö×”ÆVFvW""’¢76W'EG'VR‚%cRããCC#“¢66ææW"†&B&V¦V7BF†öæö×’6öç7V×F–öâ×W7B&R6÷W&6RÖ6öçG&7B–ææVB"ÂF†öæö×’æ6öçF–ç2‚'66ææW%ö†&E÷&V¦V7Eö6öç7VÖVEóCC#“×G'VR"’¢Ð  ¢FW7@¢gVâÆV&æ–æu&V¦V7DÆ&VÅ6VçF–æVÅóCC3vF6†W4¦÷W&æÄÆ&VÇ5&W÷'DöæÇ’‚’°¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–æu&V¦V7DÆ&VÅ6VçF–æVÂæ·B"’ç&VEFW‡B‚¢fÂ7F÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT†—7F÷'•7F÷&Ræ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3¢ÆV&æ–ær&V¦V7BÆ&VÂ6VçF–æVÂ×W7BvF6‚&V¦V7BöFVfW"ö&Æö6²Æ&VÇ2"Â6VçF–æVÂæ6öçF–ç2‚$ÄT$ä”äuõ$T¤T5EôÄ$TÅõ4TåD”äTÅóCC3"’bb6VçF–æVÂæ6öçF–ç2‚$äõõTõDR"’bb6VçF–æVÂæ6öçF–ç2‚&æõöÆV&æ–æuö&Æö6³×G'VR"’¢76W'EG'VR‚%cRããCC3¢G&FT†—7F÷'•7F÷&R×W7B–ç7V7B&÷w2&Vf÷&RW'6—7FVæ6Rv—F†÷WB6†æv–ærV&çF–æR"Â7F÷&Ræ6öçF–ç2‚$ÆV&æ–æu&V¦V7DÆ&VÅ6VçF–æVÂæ–ç7V7B‡G&FUFõ7F÷&R"’bb7F÷&Ræ6öçF–ç2‚%G&FT†—7F÷'•7F÷&Rç&V6÷&EG&FRç&UW'6—7FVæ6R"’¢76W'EG'VR‚%cRããCC3¢ÆV&æ–ær&V¦V7BÆ&VÂ6VçF–æVÂ×W7B&Rµ’×v—&VB"Â·’æ6öçF–ç2‚&ÆV&æ–æu÷&V¦V7EöÆ&VÅ÷6VçF–æVÃÔÆV&æ–æu&V¦V7DÆ&VÅ6VçF–æVÂ"’bb·’æ6öçF–ç2‚$ÆV&æ–æu&V¦V7DÆ&VÅ6VçF–æVÂç7FGW2"’¢Ð  ¢FW7@¢gVâ&V¦V7EF†öæö×”6÷fW&vTF–vW7EóCC3–ç4VæEFôVæDVF—D6÷fW&vR‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×”6÷fW&vTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3¢F†öæö×’6÷fW&vRF–vW7B×W7B–â66ææW.(i$dD~(i&WFŽ(i&W†V7WF÷.(i&ÆV&æ–ær6÷fW&vR"ÂF–vW7Bæ6öçF–ç2‚%44ääU%ô„$Eõ$T¤T5B"’bbF–vW7Bæ6öçF–ç2‚$dDuôd”äÅôDT4•4”ôâ"’bbF–vW7Bæ6öçF–ç2‚%E$DUôUD„õ$•¤U""’bbF–vW7Bæ6öçF–ç2‚$U„T5UDõ%õ$TEDTÕB"’bbF–vW7Bæ6öçF–ç2‚%E$DUô„•5Dõ%•õ$UôÄT$ä”är"’¢76W'EG'VR‚%cRããCC3¢F†öæö×’6÷fW&vRF–vW7B×W7B7F’&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöÆV&æ–æuö×WFF–öã×G'VR"’¢76W'EG'VR‚%cRããCC3¢F†öæö×’6÷fW&vRF–vW7B×W7B&Rµ’×v—&VB"Â·’æ6öçF–ç2‚'&V¦V7E÷F†öæö×•ö6÷fW&vSÕ&V¦V7EF†öæö×”6÷fW&vTF–vW7B"’bb·’æ6öçF–ç2‚%&V¦V7EF†öæö×”6÷fW&vTF–vW7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ&V¦V7EF†öæö×”VF—E&Vv—7FW%óCC3$6Æ÷6W4'VæFÆU6÷W&6T6öçG&7B‚’°¢fÂ&Vv—7FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×”VF—E&Vv—7FW"æ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3#¢&V¦V7BF†öæö×’VF—B&Vv—7FW"×W7B6Æ÷6RÆÂÖæFF÷'’6†–â7W&f6W2"Â&Vv—7FW"æ6öçF–ç2‚%44ääU%ô„$Eõ$T¤T5EóCC#’"’bb&Vv—7FW"æ6öçF–ç2‚$dDuôd”äÅôDT4•4”ôåóCC#r"’bb&Vv—7FW"æ6öçF–ç2‚%E$DUôUD„õ$•¤U%óCC#B"’bb&Vv—7FW"æ6öçF–ç2‚$U„T5UDõ%õ$TEDTÕEóCC#‚"’bb&Vv—7FW"æ6öçF–ç2‚%E$DUô„•5Dõ%•õ$UôÄT$ä”äuóCC3"’¢76W'EG'VR‚%cRããCC3#¢&V¦V7BF†öæö×’VF—B&Vv—7FW"×W7B–âFö7G&–æRæBæòÖ†÷B×F‚×7–æ2–çf&–çB"Â&Vv—7FW"æ6öçF–ç2‚'VæF–æuö—5÷VæÇG’"’bb&Vv—7FW"æ6öçF–ç2‚&Æ÷uöÆ—ö—5÷6—¦U÷&VGV7F–öâ"’bb&Vv—7FW"æ6öçF–ç2‚'¦W&õöÆ—ö—5ö†&E÷6fWG’"’bb&Vv—7FW"æ6öçF–ç2‚&æõö†÷E÷F…÷7–æ5öÆövv–ær"’¢76W'EG'VR‚%cRããCC3#¢&V¦V7BF†öæö×’VF—B&Vv—7FW"×W7B&Rµ’×v—&VBæB&W÷'BÖöæÇ’"Â&Vv—7FW"æ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb&Vv—7FW"æ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’bb&Vv—7FW"æ6öçF–ç2‚&æõöÆV&æ–æuö×WFF–öã×G'VR"’bb·’æ6öçF–ç2‚'&V¦V7E÷F†öæö×•öVF—E÷&Vv—7FW#Õ&V¦V7EF†öæö×”VF—E&Vv—7FW""’bb·’æ6öçF–ç2‚%&V¦V7EF†öæö×”VF—E&Vv—7FW"ç7FGW2"’¢Ð  ¢FW7@¢gVâW†V7WF÷$FVfW'&VD'W•F†öæö×•óCC346Æ÷6W5V÷FTFVfW'&VD÷F–öæÅ7W&f6R‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&Vv—7FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×”VF—E&Vv—7FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC33¢Æ—fT'W”FVfW'&VB×W7B6Æ76–g’FVfW'&VB÷V÷FR&V6öç2–çFò&V¦V7BF†öæö×’"ÂW†V2æ6öçF–ç2‚$U„T5UDõ%ôDTdU%$TEô%U•õD„ôäôÕ•óCC32"’bbW†V2æ6öçF–ç2‚%&V¦V7EF†öæö×’æ6Æ76–g’‡&V6öâÂçVÆÂ’"’¢76W'EG'VR‚%cRããCC33¢Æ—fT'W”FVfW'&VBF†öæö×’×W7B&RÆVFvW&VBf–&÷VæFVB'W2v†–ÆR&W6W'f–ærVÖ—DÆ—fT'W”f–Â"ÂW†V2æ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç&V6÷&B"’bbW†V2æ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’bbW†V2æ6öçF–ç2‚&VÖ—DÆ—fT'W”f–Â‡G2Â6öÂÂ&V6öâÂFWF–Â’"’¢76W'EG'VR‚%cRããCC32óCC3s¢VF—B&Vv—7FW"×W7BÖ&²FVfW'&VBV÷FR&V6öç26Æ÷6VBv—F†÷WB–ææ–ær7FÆR†6VB÷F–öæÂ6æ6†÷G2"Â&Vv—7FW"æ6öçF–ç2‚&FVfW'&VE÷V÷FU÷&V6öç5óCC32"’bb&Vv—7FW"æ6öçF–ç2‚&6Æ÷6VEö÷F–öæÃÕ²"’¢Ð  ¢FW7@¢gVâ6æöæ–6Å&V¦V7EF†öæö×•&÷uFuóCC3T–ç7V7G4ÆVv7•G&FT&Vf÷&T÷WF6öÖUV&Æ—6‚‚’°¢fÂ6æöæ–6ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô6æöæ–6ÄÆV&æ–æræ·B"’ç&VEFW‡B‚¢fÂFrÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô6æöæ–6Å&V¦V7EF†öæö×•&÷uFræ·B"’ç&VEFW‡B‚¢fÂ&Vv—7FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×”VF—E&Vv—7FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3S¢6æöæ–6Ä÷WF6öÖT'W2V&Æ—6„g&öÔÆVv7•G&FR×W7B6ÆÂ&V¦V7BF†öæö×’&÷rFr"Â6æöæ–6Âæ6öçF–ç2‚$6æöæ–6Å&V¦V7EF†öæö×•&÷uFræ–ç7V7DÆVv7•G&FR"’bb6æöæ–6Âæ6öçF–ç2‚$6æöæ–6Ä÷WF6öÖT'W2çV&Æ—6„g&öÔÆVv7•G&FRæVçG'’"’¢76W'EG'VR‚%cRããCC3S¢6æöæ–6Â&V¦V7B&÷rFr×W7B&R&W÷'BÖöæÇ’æBÆVFvW&VB"ÂFræ6öçF–ç2‚$4äôä”4Åõ$T¤T5Eõ$õuõDuóCC3R"’bbFræ6öçF–ç2‚%&V¦V7EF†öæö×”ÆVFvW"ç&V6÷&B"’bbFræ6öçF–ç2‚&æõö÷WF6öÖUö×WFF–öã×G'VR"’¢76W'EG'VR‚%cRããCC3RóCC3s¢VF—B&Vv—7FW"×W7BÖ&²6æöæ–6Â÷WF6öÖR&÷rFr6Æ÷6VBWfVâgFW"T’'&V¶F÷vâ6Æ÷7W&R"Â&Vv—7FW"æ6öçF–ç2‚&6æöæ–6Åö÷WF6öÖU÷&÷u÷FuóCC3R"’bb&Vv—7FW"æ6öçF–ç2‚&6Æ÷6VEö÷F–öæÃÕ²"’¢Ð  ¢FW7@¢gVâ÷W&F÷%&V¦V7EV”'&V¶F÷våóCC3d6Æ÷6W5&VÖ–æ–æt÷F–öæÅF†öæö×•7W&f6R‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%&V¦V7EV”'&V¶F÷väF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢fÂ&Vv—7FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×”VF—E&Vv—7FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3c¢&V¦V7BT’'&V¶F÷vâ×W7B7W&f6R6FVv÷'’öÆæRö†&B×g2×G&–æ&ÆR&W77W&R"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õ$T¤T5EõT•ô%$T´DõtåôD”tU5EóCC3b"’bbF–vW7Bæ6öçF–ç2‚&6FVv÷'•÷&W77W&R"’bbF–vW7Bæ6öçF–ç2‚&ÆæU÷&W77W&R"’bbF–vW7Bæ6öçF–ç2‚&†&E÷g5÷G&–æ&ÆR"’¢76W'EG'VR‚%cRããCC3c¢&V¦V7BT’'&V¶F÷vâ×W7B&RT’×6fRæB&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'V•÷6fS×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöÖ–å÷F‡&VE÷&VæFW#×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’¢76W'EG'VR‚%cRããCC3c¢VF—B&Vv—7FW"×W7B6Æ÷6RÆÂ÷F–öæÂ&V¦V7B×F†öæö×’7W&f6W2"Â&Vv—7FW"æ6öçF–ç2‚'V•ö'&V¶F÷våóCC3b"’bb&Vv—7FW"æ6öçF–ç2‚'&VÖ–æ–æuö÷F–öæÃÕµÒ"’bb·’æ6öçF–ç2‚'&V¦V7E÷V•ö'&V¶F÷vãÔ÷W&F÷%&V¦V7EV”'&V¶F÷väF–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷%VWVU'VçF–ÖTF–vW7EóCC3…7W&f6W4&6¶w&÷VæEVWVW5&W÷'DöæÇ’‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%VWVU'VçF–ÖTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3ƒ¢VWVR'VçF–ÖRF–vW7B×W7B6÷fW"VæF–ær6VÆÂÂF÷vç7G&VÒv÷&²Â&V6öæ6–ÆRÂæBfVR&WG'’VWVW2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õTUTUõ%TåD”ÔUôD”tU5EóCC3‚"’bbF–vW7Bæ6öçF–ç2‚%VæF–æu6VÆÅVWVR"’bbF–vW7Bæ6öçF–ç2‚$F÷vç7G&VÕv÷&µVWVR"’bbF–vW7Bæ6öçF–ç2‚%VæF–æu&V6öæ6–ÆUVWVR"’bbF–vW7Bæ6öçF–ç2‚$fVU&WG'•VWVR"’¢76W'EG'VR‚%cRããCC3ƒ¢VWVR'VçF–ÖRF–vW7B×W7B&R&W÷'BÖöæÇ’æBfö–BVWVR×WFF–öâ"ÂF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõ÷VWVUö×WFF–öã×G'VR"’bbF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’¢76W'EG'VR‚%cRããCC3ƒ¢VWVR'VçF–ÖRF–vW7B×W7B&Rµ’×v—&VB"Â·’æ6öçF–ç2‚'VWVU÷'VçF–ÖSÔ÷W&F÷%VWVU'VçF–ÖTF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%VWVU'VçF–ÖTF–vW7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷%VæF–æu6VÆÅ&WG'”F–vW7EóCC3•–ç5&WG'•6fWG”6öçG&7G2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%VæF–æu6VÆÅ&WG'”F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂVæF–ærÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõVæF–æu6VÆÅVWVRæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC3“¢VæF–ær6VÆÂ&WG'’F–vW7B×W7B–â&B–ÆöBæB6Æ÷6VBÖÖ–çB6fWG’"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õTäD”äuõ4TÄÅõ$UE%•ôD”tU5EóCC3’"’bbF–vW7Bæ6öçF–ç2‚&&E÷–ÆöEöæ÷E÷&WVWVVB"’bbF–vW7Bæ6öçF–ç2‚&6Æ÷6VEö÷%ö6Æ÷6–æuöÖ–çE÷W&vVB"’¢76W'EG'VR‚%cRããCC3“¢VæF–æu6VÆÅVWVR6÷W&6R×W7B7F–ÆÂfö–B&B×–ÆöB&WVWVR"ÂVæF–æræ6öçF–ç2‚%4TÄÅõ$UE%•ô$Äô4´TEô$Eõ”ÄôB"’bbVæF–æræ6öçF–ç2‚&æ÷B&WVWVVB"’¢76W'EG'VR‚%cRããCC3“¢VæF–ær6VÆÂ&WG'’F–vW7B×W7B&Rµ’×v—&VBæB&W÷'BÖöæÇ’"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõ÷VWVUö×WFF–öã×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõ÷6VÆÅöWF†÷&—G“×G'VR"’bb·’æ6öçF–ç2‚'VæF–æu÷6VÆÅ÷&WG'“Ô÷W&F÷%VæF–æu6VÆÅ&WG'”F–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷$VF—E&VÖ–æ–ætW7F–ÖFUóCCC%&W÷'G5VWVTÆVgEv—F†÷WDvF–ær‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$VF—E&VÖ–æ–ætW7F–ÖFTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCC#¢VF—B&VÖ–æ–ærW7F–ÖFR×W7B&W÷'B4’÷&V¦V7B×F†öæö×’6Æ÷7W&RæB&rÖ&¶W"&6¶Æör"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%ôTD•Eõ$TÔ”ä”äuôU5D”ÔDUóCCC""’bbF–vW7Bæ6öçF–ç2‚&ÖæFF÷'•ö6•÷VæF–æsÓ"’bbF–vW7Bæ6öçF–ç2‚'&V¦V7E÷F†öæö×•ö÷F–öæÅ÷VæF–æsÓ"’bbF–vW7Bæ6öçF–ç2‚'&uö†–v…÷6–væÅöÖ&¶W%ö&6¶ÆösÓ3S2"’¢76W'EG'VR‚%cRããCCC#¢VF—B&VÖ–æ–ærW7F–ÖFR×W7BÆ—7BæW‡BVWVR&F6†W2"ÂF–vW7Bæ6öçF–ç2‚'6÷W&6UöÖ&¶W%÷G&–vUóCCC2"’bbF–vW7Bæ6öçF–ç2‚&&÷G6W'f–6UöF—6&ÆVEöæ÷E÷v—&VE÷7vVW"’bbF–vW7Bæ6öçF–ç2‚'VWVU÷&V6öæ6–ÆUöfVU÷&WG'•÷f—6–&–Æ—G’"’bbF–vW7Bæ6öçF–ç2‚'&W6–GVÅöF–vW7Eö6öç6öÆ–FF–öâ"’¢76W'EG'VR‚%cRããCCC#¢VF—B&VÖ–æ–ærW7F–ÖFR×W7B&Rµ’×v—&VBæBæöâÖvF–ær"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&W7F–ÖFUöæ÷EövFS×G'VR"’bb·’æ6öçF–ç2‚&VF—E÷&VÖ–æ–æsÔ÷W&F÷$VF—E&VÖ–æ–ætW7F–ÖFTF–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷%6÷W&6TÖ&¶W%G&–vUóCCC5VçF–f–W5&W6–GVÄVF—D6ÇW7FW'2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%6÷W&6TÖ&¶W%G&–vTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂW7F–ÖFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$VF—E&VÖ–æ–ætW7F–ÖFTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCC3¢6÷W&6RÖ&¶W"G&–vRF–vW7B×W7BVçF–g’&röæö—6Rö7F–öæ&ÆR&W6–GVÇ2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õ4õU$4UôÔ$´U%õE$”tUôD”tU5EóCCC2"’bbF–vW7Bæ6öçF–ç2‚'&uöÖ&¶W'3Ó3S2"’bbF–vW7Bæ6öçF–ç2‚&7'VFU÷÷7Eöæö—6SÓ#“‚"’bbF–vW7Bæ6öçF–ç2‚&÷W&F÷%ö7F–öæ&ÆUöW7F–ÖFSÓƒó#"’¢76W'EG'VR‚%cRããCCC3¢6÷W&6RÖ&¶W"G&–vRF–vW7B×W7BæÖRF†RF÷&W6–GVÂf–ÆR6ÇW7FW'2"ÂF–vW7Bæ6öçF–ç2‚$&÷E6W'f–6S£C‚"’bbF–vW7Bæ6öçF–ç2‚$W†V7WF÷#£‚"’bbF–vW7Bæ6öçF–ç2‚$f–æÄFV6—6–öävFS£‚"’bbF–vW7Bæ6öçF–ç2‚%Fö¶Vå6fWG”6†V6¶W#£‚"’¢76W'EG'VR‚%cRããCCC3¢6÷W&6RÖ&¶W"G&–vR×W7B&Rµ’×v—&VBæB&VfÆV7FVB–â&VÖ–æ–ærW7F–ÖFR"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbW7F–ÖFRæ6öçF–ç2‚'6÷W&6UöÖ&¶W%÷G&–vUóCCC2"’bb·’æ6öçF–ç2‚'6÷W&6UöÖ&¶W%÷G&–vSÔ÷W&F÷%6÷W&6TÖ&¶W%G&–vTF–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷$&÷E6W'f–6TÖ&¶W%G&–vUóCCCD6Æ76–f–W4Æ&vW7E&W6–GVÄ6ÇW7FW"‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$&÷E6W'f–6TÖ&¶W%G&–vTF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCCC¢&÷E6W'f–6RÖ&¶W"G&–vR×W7BVçF–g’F†RÆ&vW7B&W6–GVÂ6ÇW7FW""ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%ô$õE4U%d”4UôÔ$´U%õE$”tUôD”tU5EóCCCB"’bbF–vW7Bæ6öçF–ç2‚'&uö&÷G6W'f–6UöÖ&¶W'3ÓC‚"’bbF–vW7Bæ6öçF–ç2‚&6æF–FFUö7F–öæ&ÆSÒ"’¢76W'EG'VR‚%cRããCCCC¢&÷E6W'f–6RÖ&¶W"G&–vR×W7B6W&FR–çFVçF–öæÂF—6&ÆVB7FFW2g&öÒ6æF–FFW2"ÂF–vW7Bæ6öçF–ç2‚&Æ–¶VÇ•ö–çFVçF–öæÃÒ"’bbF–vW7Bæ6öçF–ç2‚'c5÷6VÆÅ÷6–FUöæ÷E÷v—&VEö†W&R"’bbF–vW7Bæ6öçF–ç2‚&F÷&ÖçE÷66÷&W%öfVVFW'2"’¢76W'EG'VR‚%cRããCCCC¢&÷E6W'f–6RÖ&¶W"G&–vR×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö&÷G6W'f–6Uö&V†f–÷%ö6†ævS×G'VR"’bb·’æ6öçF–ç2‚&&÷G6W'f–6UöÖ&¶W%÷G&–vSÔ÷W&F÷$&÷E6W'f–6TÖ&¶W%G&–vTF–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷$W†V7WF÷$fFu6fWG”Ö&¶W$F–vW7EóCCCUG&–vU&W6–GVÅ6fWG”6ÇW7FW'2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$W†V7WF÷$fFu6fWG”Ö&¶W$F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCCS¢W†V7WF÷"ôdDr÷6fWG’Ö&¶W"F–vW7B×W7BVçF–g’F†RæW‡B&W6–GVÂ6ÇW7FW'2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%ôU„T5UDõ%ôdDuõ4dUE•ôÔ$´U%ôD”tU5EóCCCR"’bbF–vW7Bæ6öçF–ç2‚$W†V7WF÷#£‚"’bbF–vW7Bæ6öçF–ç2‚$f–æÄFV6—6–öävFS£‚"’bbF–vW7Bæ6öçF–ç2‚%Fö¶Vå6fWG”6†V6¶W#£‚"’bbF–vW7Bæ6öçF–ç2‚%F÷†–4ÖöFT6—&7V—D'&V¶W#£b"’¢76W'EG'VR‚%cRããCCCS¢W†V7WF÷"ôdDr÷6fWG’Ö&¶W"F–vW7B×W7B6W&FR–çFVçF–öæÂÆ&VÇ2g&öÒ6æF–FFR7F–öâ"ÂF–vW7Bæ6öçF–ç2‚&Æ–¶VÇ•ö–çFVçF–öæÃÒ"’bbF–vW7Bæ6öçF–ç2‚&6æF–FFUö7F–öæ&ÆSÒ"’bbF–vW7Bæ6öçF–ç2‚&fFu÷&V6öå÷F†öæö×•öÆ–væÖVçB"’¢76W'EG'VR‚%cRããCCCS¢W†V7WF÷"ôdDr÷6fWG’Ö&¶W"F–vW7B×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’bb·’æ6öçF–ç2‚&W†V7WF÷%öfFu÷6fWG•öÖ&¶W'3Ô÷W&F÷$W†V7WF÷$fFu6fWG”Ö&¶W$F–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷%'VçF–ÖTVæGö–çDÖ&¶W$F–vW7EóCCCeG&–vU'VçF–ÖT†VÇF„6ÇW7FW'2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%'VçF–ÖTVæGö–çDÖ&¶W$F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCCc¢'VçF–ÖRVæGö–çBÖ&¶W"F–vW7B×W7BVçF–g’'VçF–ÖRöVæGö–çB&W6–GVÂ6ÇW7FW'2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õ%TåD”ÔUôTäEô”åEôÔ$´U%ôD”tU5EóCCCb"’bbF–vW7Bæ6öçF–ç2‚%'VçF–ÖU&W—%7FFS£B"’bbF–vW7Bæ6öçF–ç2‚$W†V7WF–öäVæGö–çD†VÇFƒ£2"’bbF–vW7Bæ6öçF–ç2‚$W†—E&÷f–FW$†VÇFƒ£’"’¢76W'EG'VR‚%cRããCCCc¢'VçF–ÖRVæGö–çBÖ&¶W"F–vW7B×W7B6W&FR–çFVçF–öæÂW6W"ö6öæf–rF—6&ÆVB7FFW2g&öÒfVÇB6æF–FFW2"ÂF–vW7Bæ6öçF–ç2‚&Æ–¶VÇ•ö–çFVçF–öæÃÒ"’bbF–vW7Bæ6öçF–ç2‚&6æF–FFUö7F–öæ&ÆSÒ"’bbF–vW7Bæ6öçF–ç2‚'66ææW%÷W6W%öF—6&ÆVE÷g5öfVÇE÷7Æ—B"’¢76W'EG'VR‚%cRããCCCc¢'VçF–ÖRVæGö–çBÖ&¶W"F–vW7B×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöVæGö–çEövFUö6†ævS×G'VR"’bb·’æ6öçF–ç2‚''VçF–ÖUöVæGö–çEöÖ&¶W'3Ô÷W&F÷%'VçF–ÖTVæGö–çDÖ&¶W$F–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷$Ö–EF–ÄÖ&¶W$F–vW7EóCCCuG&–vTÖVF—VÕ&W6–GVÄ6ÇW7FW'2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$Ö–EF–ÄÖ&¶W$F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCCs¢Ö–B×F–ÂÖ&¶W"F–vW7B×W7BVçF–g’ÖVF—VÒ&W6–GVÂ6ÇW7FW'2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%ôÔ”EõD”ÅôÔ$´U%ôD”tU5EóCCCr"’bbF–vW7Bæ6öçF–ç2‚$FF—VÆ–æS£""’bbF–vW7Bæ6öçF–ç2‚$çF”6†ö¶TÖævW#£"’bbF–vW7Bæ6öçF–ç2‚%7G&FVw•FVÆVÖWG'“£"’¢76W'EG'VR‚%cRããCCCs¢Ö–B×F–ÂÖ&¶W"F–vW7B×W7BÆ—7B7F–öæ&ÆR6÷W&6RÖ6öçG&7BF&vWG2"ÂF–vW7Bæ6öçF–ç2‚&FF÷—VÆ–æU÷V÷FUö–FVçF—G’"’bbF–vW7Bæ6öçF–ç2‚&çF•ö6†ö¶U÷'VæU÷f—6–&–Æ—G’"’bbF–vW7Bæ6öçF–ç2‚'7G&FVw•÷FVÆVÖWG'•öæö÷ö6öçG&7B"’bbF–vW7Bæ6öçF–ç2‚'66ææW%öF÷&ÖçEöfVVE÷f—6–&–Æ—G’"’¢76W'EG'VR‚%cRããCCCs¢Ö–B×F–ÂÖ&¶W"F–vW7B×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…ö6†ævS×G'VR"’bb·’æ6öçF–ç2‚&Ö–E÷F–ÅöÖ&¶W'3Ô÷W&F÷$Ö–EF–ÄÖ&¶W$F–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷%6ÖÆÅF–ÄÖ&¶W$F–vW7EóCCC„6ö×&W76W5&W6–GVÄÖ&¶W%F–Â‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%6ÖÆÅF–ÄÖ&¶W$F–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCCƒ¢6ÖÆÂ×F–ÂÖ&¶W"F–vW7B×W7BæÖR&W6–GVÂÆ÷rÖ6÷VçB6ÇW7FW'2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õ4ÔÄÅõD”ÅôÔ$´U%ôD”tU5EóCCC‚"’bbF–vW7Bæ6öçF–ç2‚$6æöæ–6ÄfVGW&W4'V–ÆFW#£R"’bbF–vW7Bæ6öçF–ç2‚$f–æÄW†V7WF–öåW&Ö—C£R"’bbF–vW7Bæ6öçF–ç2‚%6öÆæÖ&¶WE66ææW#£b"’¢76W'EG'VR‚%cRããCCCƒ¢6ÖÆÂ×F–ÂÖ&¶W"F–vW7B×W7BÆ—7Bfö7W26öçG&7G2æB&W6–GVÂW7F–ÖFR"ÂF–vW7Bæ6öçF–ç2‚'W&Ö—EöfÇ6U÷f—6–&–Æ—G’"’bbF–vW7Bæ6öçF–ç2‚'66ææW%öfVVE÷f—6–&–Æ—G’"’bbF–vW7Bæ6öçF–ç2‚'&VÖ–æ–æuögFW%÷G&–vUöW7F–ÖFSÓ3Uóc"’¢76W'EG'VR‚%cRããCCCƒ¢6ÖÆÂ×F–ÂÖ&¶W"F–vW7B×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"ÂF–vW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõö†÷E÷F…ö6†ævS×G'VR"’bb·’æ6öçF–ç2‚'6ÖÆÅ÷F–ÅöÖ&¶W'3Ô÷W&F÷%6ÖÆÅF–ÄÖ&¶W$F–vW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷%W&Ö—DfVGW&T6öçG&7DF–vW7EóCCC•–ç56ÖÆÅF–Å6÷W&6T6öçG&7G2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%W&Ö—DfVGW&T6öçG&7DF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢fÂfWÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄW†V7WF–öåW&Ö—Bæ·B"’ç&VEFW‡B‚¢fÂ6æöæ–6ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô6æöæ–6ÄfVGW&W4'V–ÆFW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCC“¢W&Ö—BöfVGW&R6öçG&7BF–vW7B×W7B–âdUfÇ6Rf—6–&–Æ—G’æB6æöæ–6Â–FVçF—G’fVGW&W2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õU$Ô•EôdTEU$Uô4ôåE$5EôD”tU5EóCCC’"’bbF–vW7Bæ6öçF–ç2‚$f–æÄW†V7WF–öåW&Ö—EöfÇ6U÷f—6–&–Æ—G’"’bbF–vW7Bæ6öçF–ç2‚$6æöæ–6ÄfVGW&W4'V–ÆFW%ö–FVçF—G•öfVGW&W2"’¢76W'EG'VR‚%cRããCCC“¢W&Ö—BöfVGW&R6öçG&7BF–vW7B×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"ÂF–vW7Bæ6öçF–ç2‚&æõöW†V7WF–öåöWF†÷&—G“×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöfVGW&Uö×WFF–öã×G'VR"’bb·’æ6öçF–ç2‚'W&Ö—EöfVGW&Uö6öçG&7CÔ÷W&F÷%W&Ö—DfVGW&T6öçG&7DF–vW7B"’¢76W'EG'VR‚%cRããCCC“¢6÷W&6R6öçG&7G2×W7B6öçF–çVRFòW‡÷6RdUæB6æöæ–6ÂfVGW&R6÷W&6Rf–ÆW2f÷"F–ÂVF—B"ÂfWæ6öçF–ç2‚$f–æÄW†V7WF–öåW&Ö—B"’bb6æöæ–6Âæ6öçF–ç2‚$6æöæ–6ÄfVGW&W4'V–ÆFW""’¢Ð  ¢FW7@¢gVâ÷W&F÷%66ææW$VGV6F–öä÷fW&Æ”6öçG&7EóCCS–ç5&W6–GVÅ6ÖÆÅF–Ä6öçG&7G2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%66ææW$VGV6F–öä÷fW&Æ”6öçG&7DF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCS¢66ææW"öVGV6F–öâö÷fW&Æ’F–vW7B×W7B–â&W6–GVÂ6ÖÆÂ×F–Â6öçG&7G2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õ44ääU%ôTET4D”ôåôõdU$Ä•ô4ôåE$5EôD”tU5EóCCS"’bbF–vW7Bæ6öçF–ç2‚%6öÆæÖ&¶WE66ææW%öfVVE÷f—6–&–Æ—G’"’bbF–vW7Bæ6öçF–ç2‚$VGV6F–öå7V$Æ–W$•÷6ögEövFR"’bbF–vW7Bæ6öçF–ç2‚%'VçF–ÖT6öæf–t÷fW&Æ•÷W6U÷F†öæö×’"’¢76W'EG'VR‚%cRããCCS¢66ææW"öVGV6F–öâö÷fW&Æ’F–vW7B×W7B&W6W'fR&V†f–÷"ÖæWWG&Â6÷W&6R6öçG&7G2"ÂF–vW7Bæ6öçF–ç2‚&VGV6F–öåö×WFUö—5÷6ögE÷6†R"’bbF–vW7Bæ6öçF–ç2‚&÷fW&Æ•÷W6Uöæ÷EöfVÇB"’bbF–vW7Bæ6öçF–ç2‚&æõ÷66ææW%övFUö6†ævS×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõöÆæUöF—6&ÆUö6†ævS×G'VR"’¢76W'EG'VR‚%cRããCCS¢66ææW"öVGV6F–öâö÷fW&Æ’F–vW7B×W7B&Rµ’×v—&VB"Â·’æ6öçF–ç2‚'66ææW%öVGV6F–öåö÷fW&Æ“Ô÷W&F÷%66ææW$VGV6F–öä÷fW&Æ”6öçG&7DF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%66ææW$VGV6F–öä÷fW&Æ”6öçG&7DF–vW7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷%vÆÆWE—VÆ–æTwV&G&–Ä6öçG&7EóCCS–ç5&W6–GVÅF–Ä6öçG&7G2‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%vÆÆWE—VÆ–æTwV&G&–Ä6öçG&7DF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCS¢vÆÆWB÷—VÆ–æRöwV&G&–ÂF–vW7B×W7B–â&W6–GVÂF–Â6öçG&7G2"ÂF–vW7Bæ6öçF–ç2‚$õU$Dõ%õtÄÄUEõ•TÄ”äUôuT$E$”Åô4ôåE$5EôD”tU5EóCCS"’bbF–vW7Bæ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W%ö6Æ÷6VEö÷Vå÷7FGW2"’bbF–vW7Bæ6öçF–ç2‚%—VÆ–æT†VÇF„6öÆÆV7F÷%öÆ&VÅö6ÆVçW"’bbF–vW7Bæ6öçF–ç2‚$VÖW&vVçDwV&G&–Ç5÷6—¦U÷6†U÷f—6–&–Æ—G’"’¢76W'EG'VR‚%cRããCCS¢vÆÆWB÷—VÆ–æRöwV&G&–ÂF–vW7B×W7B&W6W'fRæöâÖWF†÷&—G’&V†f–÷""ÂF–vW7Bæ6öçF–ç2‚'vÆÆWE÷7FGW5÷&W÷'EööæÇ’"’bbF–vW7Bæ6öçF–ç2‚&wV&G&–Ç5÷6ögE÷6†Uöæ÷E÷fWFò"’bbF–vW7Bæ6öçF–ç2‚&æõ÷vÆÆWEöWF†÷&—G•ö6†ævS×G'VR"’bbF–vW7Bæ6öçF–ç2‚&æõ÷—VÆ–æUövFUö6†ævS×G'VR"’¢76W'EG'VR‚%cRããCCS¢vÆÆWB÷—VÆ–æRöwV&G&–ÂF–vW7B×W7B&Rµ’×v—&VB"Â·’æ6öçF–ç2‚'vÆÆWE÷—VÆ–æUöwV&G&–ÃÔ÷W&F÷%vÆÆWE—VÆ–æTwV&G&–Ä6öçG&7DF–vW7B"’bb·’æ6öçF–ç2‚$÷W&F÷%vÆÆWE—VÆ–æTwV&G&–Ä6öçG&7DF–vW7Bç7FGW2"’¢Ð  ¢FW7@¢gVâ÷W&F÷%6÷W&6T6öçG&7D6Æ÷6V÷WDÖæ–fW7EóCCS%G&6·5F–ÅFôf–æ—6‚‚’°¢fÂÖæ–fW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%6÷W&6T6öçG&7D6Æ÷6V÷WDÖæ–fW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCSc¢6÷W&6R6öçG&7B6Æ÷6V÷WBÖæ–fW7B×W7B6÷VçB6Æ÷6VBF–Â&F6†W2"ÂÖæ–fW7Bæ6öçF–ç2‚$õU$Dõ%õ4õU$4Uô4ôåE$5Eô4Äõ4TõUEôÔä”dU5EóCCSb"’bbÖæ–fW7Bæ6öçF–ç2‚&6Æ÷6VE÷F–Åö&F6†W3Ò"’bbÖæ–fW7Bæ6öçF–ç2‚'&VÖ–æ–æu÷6÷W&6Uö6öçG&7E÷F–ÅöW7F–ÖFSÓóu÷VæF–æuö6’"’¢76W'EG'VR‚%cRããCCS#¢6÷W&6R6öçG&7B6Æ÷6V÷WBÖæ–fW7B×W7B–æ6ÇVFRÆFW7B–â&F6†W2"ÂÖæ–fW7Bæ6öçF–ç2‚#CCC’W&Ö—Bö6æöæ–6ÂfVGW&R6÷W&6R6öçG&7G2"’bbÖæ–fW7Bæ6öçF–ç2‚#CCS66ææW"öVGV6F–öâö÷fW&Æ’6÷W&6R6öçG&7G2"’bbÖæ–fW7Bæ6öçF–ç2‚#CCSvÆÆWB÷—VÆ–æRöwV&G&–Â6÷W&6R6öçG&7G2"’¢76W'EG'VR‚%cRããCCS#¢6÷W&6R6öçG&7B6Æ÷6V÷WBÖæ–fW7B×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"ÂÖæ–fW7Bæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bbÖæ–fW7Bæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’bb·’æ6öçF–ç2‚'6÷W&6Uö6öçG&7Eö6Æ÷6V÷WCÔ÷W&F÷%6÷W&6T6öçG&7D6Æ÷6V÷WDÖæ–fW7B"’¢Ð  ¢FW7@¢gVâ÷W&F÷$f–æÅ&W6–GVÅ6÷W&6T6öçG&7E7vVWóCCSd6Æ÷6W5F–ÅVæF–æt6’‚’°¢fÂ7vVWÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$f–æÅ&W6–GVÅ6÷W&6T6öçG&7E7vVWæ·B"’ç&VEFW‡B‚¢fÂÖæ–fW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%6÷W&6T6öçG&7D6Æ÷6V÷WDÖæ–fW7Bæ·B"’ç&VEFW‡B‚¢fÂ·’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$·”6Æ÷6V÷WE&W÷'Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCSc¢f–æÂ&W6–GVÂ6÷W&6RÖ6öçG&7B7vVW×W7B6Æ÷6RF†RæÖVBF–Â6ÇW7FW'2"Â7vVWæ6öçF–ç2‚$õU$Dõ%ôd”äÅõ$U4”ETÅõ4õU$4Uô4ôåE$5Eõ5tTUóCCSb"’bb7vVWæ6öçF–ç2‚'7G&FVw•÷FVÆVÖWG'•öæö÷ö6öçG&7B"’bb7vVWæ6öçF–ç2‚&çF•ö6†ö¶U÷'VæU÷f—6–&–Æ—G’"’bb7vVWæ6öçF–ç2‚&FF÷—VÆ–æUö–FVçF—G•ö†–çB"’¢76W'EG'VR‚%cRããCCSc¢6÷W&6RÖ6öçG&7B6Æ÷6V÷WBÖæ–fW7B×W7B–æ6ÇVFRf–æÂ7vVWæBVæF–ærÔ4’&W6–GVÂW7F–ÖFR"ÂÖæ–fW7Bæ6öçF–ç2‚#CCS2f–æÂ&W6–GVÂ6÷W&6RÖ6öçG&7B7vVW"’bbÖæ–fW7Bæ6öçF–ç2‚'&VÖ–æ–æu÷6÷W&6Uö6öçG&7E÷F–ÅöW7F–ÖFSÓóu÷VæF–æuö6’"’¢76W'EG'VR‚%cRããCCSc¢f–æÂ&W6–GVÂ6÷W&6RÖ6öçG&7B7vVW×W7B&Rµ’×v—&VBæB&V†f–÷"ÖæWWG&Â"Â7vVWæ6öçF–ç2‚'&W÷'EööæÇ“×G'VR"’bb7vVWæ6öçF–ç2‚&æõövFUö6†ævS×G'VR"’bb·’æ6öçF–ç2‚&f–æÅ÷&W6–GVÅ÷7vVWÔ÷W&F÷$f–æÅ&W6–GVÅ6÷W&6T6öçG&7E7vVW"’¢Ð  ¢FW7@¢gVâ&V¦V7EF†öæö×•óCCSt6Æ76–f–W4W†V7WF÷$FVfW'&VEF–ÖV÷WDæEW6U&V6öç2‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V¦V7EF†öæö×’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCSs¢FVfW'&VBW†V7WF÷"F†öæö×’×W7B6Æ76–g’ÆæR×F–ÖV÷WB&W67VR2VæÇG’Âæ÷BTä´äõtåõ$Ud”Ur"Â7&2æ6öçF–ç2‚$ÄäUõD”ÔTõUB"’bb7&2æ6öçF–ç2‚%D”ÔTõUEõ$U45TR"’bb&V¦V7EF†öæö×’æ6Æ76–g’‚$ÄäUõD”ÔTõUEõ$U45TR"ÂçVÆÂ’æ6FVv÷'’ÓÒ&V¦V7EF†öæö×’ä6FVv÷'’åTäÅE’¢76W'EG'VR‚%cRããCCSs¢Æ—fRW6RFVfW'&VBW†V7WF÷"F†öæö×’×W7B6Æ76–g’2VæÇG’v†–ÆR&W6W'f–ær%TåD”ÔUõU4TB†&B6fWG’"Â7&2æ6öçF–ç2‚$Ä•dUõU4R"’bb&V¦V7EF†öæö×’æ6Æ76–g’‚$Ä•dUõU4UôDTdU%$TB"ÂçVÆÂ’æ6FVv÷'’ÓÒ&V¦V7EF†öæö×’ä6FVv÷'’åTäÅE’bb&V¦V7EF†öæö×’æ6Æ76–g’‚%%TåD”ÔUõU4TB"ÂçVÆÂ’æ6FVv÷'’ÓÒ&V¦V7EF†öæö×’ä6FVv÷'’ä„$Eõ4dUE’¢76W'EG'VR‚%cRããCCSs¢F†öæö×’7FGW2×W7BÖ&²FVfW'&VBW†V7WF÷"&V6öâW‡ç6–öâ"Â7&2æ6öçF–ç2‚&FVfW'&VEöW†V7WF÷%÷&V6öåöW‡ç6–öåóCCSs×G'VR"’¢Ð  ¢FW7@¢gVâvVÖ–æ”6÷–Æ÷EóCCS…V&çF–æW4FVD'VFvWFVDæDæö—7•&÷f–FW'2‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôvVÖ–æ”6÷–Æ÷Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCSƒ¢vVÖ–æ”6÷–Æ÷B×W7BV&çF–æRæöâÓC#’fFÂ&÷f–FW"f–ÇW&W2F‡&÷Vv‚F†RW†—7F–ær6ööÆF÷vâ6¶—F‚"Â7&2æ6öçF–ç2‚'&V6÷&E&÷f–FW$6ööÆF÷vâ"’bb7&2æ6öçF–ç2‚&'VFvWEöW†6VVFVB"’bb7&2æ6öçF–ç2‚&ÖöFVÅ÷Væf–Æ&ÆR"’¢76W'EG'VR‚%cRããCCcS¢vVÖ–æ”6÷–Æ÷B×W7B6ööÂF÷vâW‡‚&÷f–FW"W'&÷'2–ç7FVBöb&WG'’×7ÖÖ–ær'VçF–ÖR†VÇF‚"Â7&2æ6öçF–ç2‚'6W'fW%ò"’bb7&2æ6öçF–ç2‚'&W7öç6Ræ6öFR"’bb7&2æ6öçF–ç2‚&6ööÆVEöF÷våóCCS‚"’bb7&2æ6öçF–ç2‚$ÄÄÕõ$õd”DU%ô4ôôÄDõtåóCCS‚"’¢76W'EG'VR‚%cRããCCS“¢&÷f–FW"6ööÆF÷vâ×W7B&W6W'fR&6¶w&÷VæBÖöæÇ’&V†f–÷"æBfö–BdDröW†V7WF÷"†÷B×F‚FWVæFVæ7’"Â7&2æ6öçF–ç2‚'&FTÆ–Ö—FVEVçF–Ä'•&÷f–FW%·&÷f–FW$æÖUÒ"’bb7&2æ6öçF–ç2‚$f÷&Vç6–4ÆövvW"æÆ–fV7–6ÆR"’bb7&2æ6öçF–ç2‚$ÄÄÕõ$õd”DU%ô4ôôÄDõtåóCCS‚"’¢Ð   ¢FW7@¢gVâW†V7WF÷$Æ—fT'W•óCCc6öçfW'G4F—66—Æ–æUfWFöW5Fõ&V6÷fW'•&ö&U6—¦–ær‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCc¢Æ—fT'W’F—66—Æ–æRW6R÷F–ÖV÷WBö'&–FvR×W7B6öçF–çVR2&V6÷fW'’&ö&W2Âæ÷B†&B×&WGW&â&Vf÷&RW†V7WF–öâ"Â7&2æ6öçF–ç2‚$D•44•Ä”äUõ$T4õdU%•õ$ô$UóCCc"’bb7&2æ6öçF–ç2‚&7F–öãÖ6öçF–çVU÷FõöÆ—fUö'W’"’bb7&2æ6öçF–ç2‚&F—66—Æ–æU&V6÷fW'•6—¦T×VÇF—Æ–W#CCcÒã3R"’¢76W'EG'VR‚%cRããCCc¢Æ—fT'W’F—66—Æ–æR&V6÷fW'’×VÇF—Æ–W"×W7BVçFW"W†—7F–ærÆ—fR6—¦R7F6²"Â7&2æ6öçF–ç2‚$Ä•dUôD•44•Ä”äUõ$T4õdU%•õ4•¤UôÄ”TEóCCc"’bb7&2æ–æFW„öb‚$Ä•dUôD•44•Ä”äUõ$T4õdU%•õ4•¤UôÄ”TEóCCc"’Â7&2æ–æFW„öb‚$Ä•dUõ5E”ÄUõ•dõEõ4•¤UôÄ”TB"’¢76W'EG'VR‚%cRããCCcóCScƒ¢G'VR†&B×6fWG’'VrvFW2&VÖ–â†&Bv†–ÆRETÕF÷†–6—G’—f÷G2–ç7FVBöb†&B×7F÷–ær"Â7&2æ6öçF–ç2‚%%Tuô$Ä4´Ä•5EõdUDõõcC3B"’bb7&2æ6öçF–ç2‚&VÖ—DÆ—fT'W”f–Â"’bb7&2æ6öçF–ç2‚%%Tuô$Ä4´Ä•5B"’bb7&2æ6öçF–ç2‚%$Tt”ÔUô”ääU%ôÄäUõ•dõEóCSc‚"’¢Ð  ¢FW7@¢gVâW†V7WF÷%c4'W•óCCc%7W&W76W4ÆV&æ–ætæD6öÆÆV7F—fUv†Vä'W”F–Dæ÷D÷Vâ‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCcc¢c2'W’F‚×W7B6GW&RW"öÆ—fR'W’Ö÷Vâ&W7VÇB&Vf÷&R&V6÷&F–ærVçG'’"Â7&2æ6öçF–ç2‚'fÂ÷VæVD'•c4'W“CCc#¢&ööÆVâ"’bb7&2æ6öçF–ç2‚'W$'W’‚"’bb7&2æ6öçF–ç2‚'G'VR"’bb7&2æ6öçF–ç2‚&–b‚÷VæVD'•c4'W“CCc"’"’¢76W'EG'VR‚%cRããCCc#¢FVfW'&VBöæ÷BÖ÷Vâc2'W—2×W7B7W&W72c4Væv–æTÖævW"VçG'’æB6öÆÆV7F—fR%U’WÆöB"Â7&2æ6öçF–ç2‚%c5ô%U•ôäõEôõTäTEõ5U$U55ôÄT$ä”äuóCCc""’bb7&2æ–æFW„öb‚&–b‚÷VæVD'•c4'W“CCc"’"’Â7&2æ–æFW„öb‚%c4Væv–æTÖævW"ç&V6÷&DVçG'’"’bb7&2æ–æFW„öb‚&–b‚÷VæVD'•c4'W“CCc"’"’Â7&2æ–æFW„öb‚$6öÆÆV7F—fTÆV&æ–ærçWÆöEG&FR"’¢76W'EG'VR‚%cRããCCc#¢6öÆÆV7F—fR%U’WÆöB×W7B&VÖ–âgFW"6öæf—&ÖVB÷VâÂ&WfVçF–ærf¶R%U’ÆV&æ–ær&÷w2"Â7&2æ–æFW„öb‚%c4Væv–æTÖævW"ç&V6÷&DVçG'’"’Â7&2æ–æFW„öb‚$6öÆÆV7F—fTÆV&æ–ærçWÆöEG&FR"’¢Ð  ¢FW7@¢gVâf–æÄFV6—6–öävFUóCCc46öçfW'G4Ö6Æ—&F–ô†&D&Æö6µFõ6ögE6—¦U6†R‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄFV6—6–öävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCc3¢dDrÖ6öÆ—V–F—G’&F–òã#‚×W7B6ögB×6†RÂæ÷B†&BÖ&Æö6²"Â7&2æ6öçF–ç2‚&Ö6öÆ—÷&F–õöW‡G&VÖU÷6ögB"’bb7&2æ6öçF–ç2‚$Ô4ôÄ•õ$D”õôU…E$TÔUõ4•¤Uõ$TET5D”ôåóCCc2"’bb7&2æ6öçF–ç2‚$„$Eô$Äô4µôÔ4ôÄ•õ$D”õò"’¢76W'EG'VR‚%cRããCCc3¢dDr×W7B&W÷'BW‡G&VÖRÖ6öÆ—V–F—G’6ögB6†–ærf÷"'VçF–ÖR&Æö6²f—6–&–Æ—G’"Â7&2æ6öçF–ç2‚$dDuôÔ4ôÄ•õ$D”õôU…E$TÔUõ4ôeEõ4„TEóCCc2"’bb7&2æ6öçF–ç2‚&W‡G&VÖU÷F†–åöÖ6öÆ—÷6—¦U÷&VGV7F–öâ"’¢76W'EG'VR‚%cRããCCs¢G'VRæöâÖW†—F&ÆRÆ—V–F—G’fÆö÷"&VÖ–ç2†&B6fWG’"Â7&2æ6öçF–ç2‚$„$Eô$Äô4µôÄ•T”D•E•ô$TÄõuóS"’bb7&2æ6öçF–ç2‚&Æ—V–F—G•óS"’bb7&2æ6öçF–ç2‚&æöâÖW†—F&ÆRGW7B"’¢Ð  ¢FW7@¢gVâ&÷E6W'f–6UóCCc„66†vVä×W7E'F–6—FT–äÆ—fTÆæT÷væW%&÷FF–öâ‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCcƒ¢44„tTâ×W7B&R–âF†RÆ—fRÖVÖUG&FW"÷væW"&–ærÂæ÷BöæÇ’Væ&ÆVB6÷6ÖWF–6ÆÇ’"Â7&2æ6öçF–ç2‚'fÂgVÆÄÖVÖUG&FW%&–ærÒÆ—7Döb"’bb7&2æ6öçF–ç2‚%E$T5U%’"’bb7&2æ6öçF–ç2‚$44„tTâ"’bb7&2æ6öçF–ç2‚$$ÅTT4„•"’¢76W'EG'VR‚%cRããCCcƒ¢44„tTâ×W7B6†&RVÆ—G’öFWF‚&W67VRVÆ–v–&–Æ—G’v—F‚E$T5U%’æB$ÅTT4„•"Â7&2æ6öçF–ç2‚%TÄ•E’"’bb7&2æ6öçF–ç2‚%E$T5U%’"’bb7&2æ6öçF–ç2‚$44„tTâ"’bb7&2æ6öçF–ç2‚%$ô¤T5Eõ4ä•U""’¢76W'EG'VR‚%cRããCCcs¢6÷W&6Rff–æ—G’Ç&VG’6VVG244„tTâÂ6òF†R÷væW"&–ær×W7B6öç7VÖR—BF÷vç7G&VÒ"Â7&2æ6öçF–ç2‚$DU…ô$ôõ5DTB"’bb7&2æ6öçF–ç2‚$44„tTâ"’bb7&2æ6öçF–ç2‚$ÔTÔUE$DU%ôõtäU%ôÄäR"’¢Ð  ¢FW7@¢gVâ&÷E6W'f–6UóCCc”Æ—fTÖVÖTÖöFTWfÇVFW4ÆÄ–çFW&æÅG&FW$ÆæW4f÷$ÆV&æ–ær‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcc¢Æ—fRÔTÔRÖöæÇ’&WF–ç2VÆ–f–VBFW6·2v†–ÆR6÷W&6R÷7G–ÆR÷væW"ÇW2&÷VæFVB&W67VR6âW‡&W72&VÂ%U’"Â7&2æ6öçF–ç2‚$Ä•dUôÄÅôÄäUô4ôåE$”%UD”ôåóCCc’"’bb7&2æ6öçF–ç2‚&&÷VæFVE&W67VScc"’bb7&2æ6öçF–ç2‚'7V6–Æ—7DWfÇVF–öäÆÆ÷vVCcc"’bb7&2æ6öçF–ç2‚'&WGW&â7V6–Æ—7DWfÇVF–öäÆÆ÷vVCcc"’¢76W'EG'VR‚%cRããcc¢÷væW'6†—6ææ÷BFW&—fRg&öÒFW6²Ö–ç6W'F–öâ÷&FW"æBöæRÖÖ–çBVÆV7F–öâGW&ç2ÆFW"ÆæW2–çFò6öçG&–'WF÷'2"Â7&2æ6öçF–ç2‚'7G&öævW7DFW6³cS“’"’bb7&2æ6öçF–ç2‚&7W'&VçDVÆV7F–öãcc"’bb7&2æ6öçF–ç2‚$4ôåE$”%UDõ%ôôäÅ’"’¢76W'EG'VR‚%cRããCCc“¢gVÆÂ–çFW&æÂ&–ær–æ6ÇVFW2WfW'’ÖVÖRG&FW"6öçG&–'WF÷"f÷"Æ—fRÆV&æ–ær…cRããCS““¢7V6–Æ—7G2Ö÷fVB÷WBöb&–ær’"ÂÆ—7Döb‚$Ôôôå4„õB"Â$Ôä•TÄDTB"Â%TÄ•E’"Â$D•ô…TåDU""Â%E$T5U%’"Â$44„tTâ"Â$$ÅTT4„•"’æÆÂ²7&2æ6öçF–ç2†—B’Ò¢Ð  ¢FW7@¢gVâVæ–f–VEöÆ–7”†VEóCCs7F÷&W5VæF–æu6–væÇ5W$Ö–çDæDÆæR‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõVæ–f–VEöÆ–7”†VBæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCs¢Væ–f–VEöÆ–7”†VBVæF–ær7F×2×W7B7F÷&RÆÂÆæW2W"Ö–çBÂæ÷B÷fW'w&—FR6–&Æ–æw2"Â7&2æ6öçF–ç2‚$6öæ7W'&VçD†6„ÖÅ7G&–ærÂ¦fçWF–Âæ6öæ7W'&VçBä6öæ7W'&VçD†6„ÖÅ7G&–ærÂF÷V&ÆT'&“ãâ"’bb7&2æ6öçF–ç2‚'VæF–æræ6ö×WFT–d'6VçB†Ö–çB’"’¢76W'EG'VR‚%cRããCCs¢6WGFÆVB÷WF6öÖR×W7BG&–âWfW'’7F×VBÆæR†VBf÷"W"öÆ—fR6öçG&–'WF–öâ&—G’"Â7&2æ6öçF–ç2‚&f÷"‚†ÆæRÂ‚’–â&V72’"’bb7&2æ6öçF–ç2‚%Tä”d”TEõôÄ”5•ô„TEôÄÅôÄäUôõUD4ôÔUóCCs"’¢76W'EG'VR‚%cRããCCs¢W"ÖÆæR†VG2×W7B7F–ÆÂG&–â–æFWVæFVçFÇ’v†–ÆRvÆö&Âv&Ò×7F'BÇ6òWFFW2"Â7&2æ6öçF–ç2‚&vWD÷$7&VFTÆæT†VB†ÆæR’"’bb7&2æ6öçF–ç2‚'G&–æVB³Ò"’bb7&2æ6öçF–ç2‚&‚çG&–æVB³Ò"’¢Ð   ¢FW7@¢gVâ÷Vå÷6—F–öåV•óCCs•W6W4&6—4wV&FVEvÆÆWD6÷'&W7öæFVçDv–äF—7Æ’‚’°¢fÂ7F—f—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ô7'—FôÇD7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂfÒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ô&÷Ef–WtÖöFVÂæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCs“¢÷Vâ×÷6—F–öâæVÇ2×W7BW6R6†&VB&6—2ÖwV&FVBF—7Æ’†VÇW"Âæ÷B&rFö¶Vâ&Vbv–âÖF‚"Â7F—f—G’æ6öçF–ç2‚'V”6ö×&&ÆT÷Vå&–6SCCs’"’bb7F—f—G’æ6öçF–ç2‚$õTåõõ4•D”ôåõT•ô$4•5õ$T$4TEóCCs’"’bb7F—f—G’æ6öçF–ç2‚&F—7Æ•ööæÇ•÷vÆÆWEö6÷'&W7öæFVæ6R"’¢76W'EG'VR‚%cRããCCs“¢÷Vâ×÷6—F–öâv–â&÷w2×W7B&÷WFRF‡&÷Vv‚F†R†VÇW"&Vf÷&Rf÷&ÖGF–ærW&6VçBõ4ôÂ"Â7F—f—G’æ6öçF–ç2‚'V”v–å7CCCs’"’bb7F—f—G’æ6öçF–ç2‚$&÷E6W'f–6Rç7FGW2çFö¶Vç5·÷2æÖ–çEÓòç&VcòçF¶T–b²—BâÒó¢÷2æVçG'•&–6R"’¢76W'EG'VR€¢%cRããcc3c¢vw&VvFRVç&VÆ—6VBäÂ×W7BW6RF†R6æöæ–6ÂÖvFVB6æ6†÷BæB6†&VB&–6–ærWF†÷&—G’"À¢fÒæ6öçF–ç2‚'F÷FÅVç&VÆ—6VEæÅ6öÂÒ÷Vå6æ6†÷Bç7VÔöb"’b`¢fÒæ6öçF–ç2‚$÷VåæÅ6æ—G’ç&–6–æuG'WF‚"’b`¢fÒæ6öçF–ç2‚$&÷Ef–WtÖöFVÂçF÷FÅVç&VÆ—6VCcc3b"’b`¢fÒæ6öçF–ç2‚&–b‡G'WFƒòçG'W7FVBÓÒG'VR’G'WF‚çæÅ6öÂVÇ6Rã"’b`¢fÒæ6öçF–ç2‚&VçG'’¢†7W'&VçDÖ6òVçG'”Ö6’"’À¢¢Ð   ¢FW7@¢gVâ&÷E6W'f–6UóCCƒW†—EG&–vvW'5W6UvÆÆWD6÷'&W7öæFVçD&6—4wV&B‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCƒ¢f7BW†—BG&–vvW'2×W7B6†&RF†RvÆÆWBÖ6÷'&W7öæFVçB&6—2wV&B"Â7&2æ6öçF–ç2‚'vÆÆWD6÷'&W7öæFVçD÷Vå&–6SCCƒ"’bb7&2æ6öçF–ç2‚$U„•EõE$”ttU%ô$4•5õ$T$4TEóCCƒ"’bb7&2æ6öçF–ç2‚'vÆÆWEö6÷'&W7öæFVçEöW†—E÷G&–vvW""’¢76W'EG'VR‚%cRããCCƒ¢&–BÖöæ—F÷"äÂ×W7BWfÇVFRF†R&6—2ÖwV&FVB&–6R&Vf÷&R7F÷÷F¶R×&öf—BFV6—6–öç2"Â7&2æ6öçF–ç2‚'&–DW†—E&–6SCCƒ"’bb7&2æ6öçF–ç2‚$&÷E6W'f–6Rç&–E7F÷"’bb7&2æ6öçF–ç2‚&7W'&VçE&–6RÒ&–DW†—E&–6SCCƒ"’¢76W'EG'VR‚%cRããCCƒ¢Væ—fW'6ÂW†—B7vVWæBF–6²&öf—BÆö6²×W7Bæ÷B7Böâ&r†çFöÒÖ&·2"Â7&2æ6öçF–ç2‚%Tä•dU%4ÅôU„•Eõ5tTU"’bb7&2æ6öçF–ç2‚'6fTW†V5„f÷%F–6´Æö6³CCƒ"’bb7&2æ6öçF–ç2‚%D”4µõ$ôd•EôÄô4²"’¢Ð   ¢FW7@¢gVâ&÷E6W'f–6UóCCƒ466†vVäÆæT6÷fW&vTæE&ö¦V7E6æ—W%6–ævÆTvFR‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCƒ3¢44„tTâ×W7B†fRÆæRÖWfÂ6÷fW&vRWfVâF†÷Vv‚W†V7WF–öâ—26†&VBv—F‚G&V7W'’ô66„vVæW&F–öä’"Â7&2æ6öçF–ç2‚&66†vVäÆæTÆÆ÷vVEF†—47–6ÆSCCƒ2"’bb7&2æ6öçF–ç2‚&ÆæSÔ44„tTâ"’bb7&2æ6öçF–ç2‚%E$T5U%•ô44„tTåõ4„$TEôU„T2"’bb7&2æ6öçF–ç2‚$44„tTåôÄ”5ôÄäUôUdÅóCCƒ2"’¢fÂ6æ—W$vFTæVVFÆRÒ'6†÷VÆE'Vä'W”ÆæTf÷$7–6ÆR‡G2Â"²r"r²%$ô¤T5Eõ4ä•U""²r"p¢76W'EG'VR‚%cRããCCƒ3¢&ö¦V7E6æ—W"×W7B6ö×WFR÷væW"FÖ—76–öâöæ6RW"Fö¶Vâö7–6ÆRÂæ÷BGWÆ–6FR6†÷VÆE'Vâ6ÆÇ2"Â7&2æ6öçF–ç2‚'&ö¦V7E6æ—W$ÆæTÆÆ÷vVEF†—47–6ÆSCCƒ2"’bb7&2æ6öçF–ç2‚'6–ævÆTvFSCCƒ3×G'VR"’bb7&2æ–æFW„öb‡6æ—W$vFTæVVFÆR’ÓÒ7&2æÆ7D–æFW„öb‡6æ—W$vFTæVVFÆR’¢Ð   ¢FW7@¢gVâ&÷E6W'f–6TÆ–fV7–6ÆUóCCƒD&÷VæG56W'f–6UFö7DF—7F6‚‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6TÆ–fV7–6ÆTW‡Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCCƒC¢6W'f–6RFö7G2×W7B&RvÆö&ÆÇ’&FRÖÆ–Ö—FVBöFVGWVBFò&WfVçBÖ–â×F‡&VBå"'W'7G2"Â7&2æ6öçF–ç2‚%4U%d”4UõDô5EôÔ”åôtôÕ5óCCƒB"’bb7&2æ6öçF–ç2‚%4U%d”4UõDô5EôDTEUUôÕ5óCCƒB"’bb7&2æ6öçF–ç2‚%4U%d”4UõDô5Eõ5U$U54TEóCCƒB"’¢76W'EG'VR‚%cRããCCƒC¢6W'f–6RFö7G26†÷VÆBW6RF†R6†&VB†æFÆW"æB6†÷'BGW&F–öâÂæ÷B÷7BVæÆ–Ö—FVBÆöærFö7G2"Â7&2æ6öçF–ç2‚'6W'f–6UFö7D†æFÆW#CCƒBç÷7B"’bb7&2æ6öçF–ç2‚%Fö7BäÄTäuD…õ4„õ%B"’bb7&2æ6öçF–ç2‚&æG&ö–Bçv–FvWBåFö7BäÄTäuD…ôÄôär"’¢Ð   ¢FW7@¢gVâ&W÷'F–æt‡V%óCCƒuVæ–f–VE&W÷'D—57FU6fTæE6V7F–öä6ö×ÆWFR‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCƒ¢Væ–f–VB'VçF–ÖR&W÷'BVçfVÆ÷RWFFVBFò²6†'2f÷"gVÆÂ÷W&F÷"6öçFW‡B"Â7&2æ6öçF–ç2‚$Ô…õTä”d”TEõ$Uõ%Eô4„%2Òó"’bb7&2æ6öçF–ç2‚%5DUõ4dUõccC‚"’bb7&2æ6öçF–ç2‚'7FR×6fR†&B6"’¢76W'EG'VR‚%cRããCCƒs¢Væ–f–VB&W÷'B×W7B&WF–âÆÂÖ¦÷"6V7F–öç2v†–ÆR&VGV6–ær&r×&÷r–ÆöB"ÂÆ—7Döb‚$U„T5UD•dR4ä4„õB"Â%DôôÄ´•B4”täÂ4„TUB"Â%•TÄ”äR„TÅD‚(	B4õ$R"Â$ÄT$ä”är²ETä”är5DDR"Â%E$DR¤õU$äÂ5TÔÔ%’"Â$dõ$Tå4”25TÔÔ%’"Â$U%$õ"Äôu2(	B$T4TåB"’æÆÂ²7&2æ6öçF–ç2†—B’Ò¢76W'EG'VR‚%cRããcCƒ¢&V6VçBW'&÷"&÷w2×W7B&R7VÖÖ&—¦VBBccC‚Æ–Ö—CÓƒ†æ÷BF†RöÆB#B÷"F†R&RÓCCƒrc’"Â7&2æ6öçF–ç2‚$W'&÷$ÆövvW"æW‡÷'EFô6ö×7EF&ÆR†Æ–Ö—BÒƒ’"’bb7&2æ6öçF–ç2‚$W'&÷$ÆövvW"æW‡÷'EFô6ö×7EF&ÆR†Æ–Ö—BÒc’"’¢Ð   ¢FW7@¢gVâ&÷E6W'f–6UóCCƒ•VÆ—G”Öööç6†÷DæD6÷&Uf—6–&–Æ—G”6ææ÷DF—6V"‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂVÆ—G”Öööç6†÷DæVVFÆSCCƒ’Ò'6WDöb‚"²r"r²%TÄ•E’"²r"r²"Â"²r"r²$Ôôôå4„õB"²r"r²"’ ¢76W'EG'VR‚%cRããCCƒ“¢TÄ•E’æBÔôôå4„õB×W7B¶VWÆ—fR&VBÖfÆö÷"v†Vâ÷væW"&÷FF–öâ7W&W76W2dDr"Â7&2æ6öçF–ç2‚$Ä•dUôÄäUõ$TEôdÄôõ%óCCƒ’"’bb7&2æ6öçF–ç2‚$Ä•dUôÄäUõ$TEôdÄôõ%óCCƒ•òB"’bb7&2æ6öçF–ç2‚&æõöfFs×G'VR"’bb7&2æ6öçF–ç2‡VÆ—G”Öööç6†÷DæVVFÆSCCƒ’’¢76W'EG'VR‚%cRããCCƒ“¢c26÷&RæB5DäD$BG'Væ²ÆæW2×W7B&Rf—6–&ÆR–âÆæRÖWfÂFVÆVÖWG'’v—F†÷WBW‡G&dDrfæ÷WB"Â7&2æ6öçF–ç2‚%c5ô4õ$Uõd•4”$”Ä•E•óCCƒ’"’bb7&2æ6öçF–ç2‚$4õ$Uõ5DäD$Eõd•4”$”Ä•E•óCCƒ’"’bb7&2æ6öçF–ç2‚&ÆæSÕc5ô4õ$R"’bb7&2æ6öçF–ç2‚&ÆæSÕ5DäD$B"’bb7&2æ6öçF–ç2‚&æõöW‡G&öfFs×G'VR"’¢Ð   ¢FW7@¢gVâ—VÆ–æT†VÇF…óCC“$ÆæU'6W%&W6W'fW5c4æE6†F÷u&VG4Fôæ÷EG&—fæ÷WB‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂçVÖW&–4ÆæU&VvWƒCC“"Ò%&VvW‚‚"²r"r²&ÆæSÒ…´Õ£Ó•õÒ²’"²r"r²"’ ¢fÂ7FÆTÆæU&VvWƒCC“"Ò%&VvW‚‚"²r"r²&ÆæSÒ…´Õ¥õÒ²’"²r"r²"’ ¢76W'EG'VR‚%cRããCC“#¢ÆæR'6W"×W7B&W6W'fRçVÖW&–2ÆæW2Æ–¶Rc5ô4õ$R–ç7FVBöb&W÷'F–ærb"Â7&2æ6öçF–ç2†çVÖW&–4ÆæU&VvWƒCC“"’bb7&2æ6öçF–ç2‡7FÆTÆæU&VvWƒCC“"’¢76W'EG'VR‚%cRããc3sS¢6†F÷r÷&VBÖöæÇ’ÆæRf—6–&–Æ—G’×W7Bæ÷B–æ7&VÖVçB7F—fRÄäUôUdÂfæ÷WB6÷VçFW'2"Â7&2æ6öçF–ç2‚'6†F÷tÆæTWfÃCCƒ""’bb7&2æ6öçF–ç2‚$ÄäUôUdÅõ4„Dõuõ$TEôôäÅ•óCC“""’bb7&2æ6öçF–ç2‚&æõöW‡G&öfFs×G'VR"’bb7&2æ6öçF–ç2‚&æõöfFs×G'VR"’bb7&2æ6öçF–ç2‚&ÆæTWfÅ6†F÷u&VDöæÇ”6÷VçG2"’bb7&2æ6öçF–ç2‚%6†F÷r÷&VBÖöæÇ’æöâÕTÄ•E’WfÂ"’bb7&2æ6öçF–ç2‚$ÄäUôUdÂ6†F÷r÷&VBÖöæÇ’'’ÆæR"’bb7&2æ6öçF–ç2‚&–b‡6†F÷tÆæTWfÃCCƒ"’'V×†ÆæTWfÅ6†F÷u&VDöæÇ”6÷VçG2ÂÆæR’"’bb7&2æ6öçF–ç2‚&VÇ6R'V×†ÆæTWfÄ6÷VçG2ÂÆæR’"’¢Ð   ¢FW7@¢gVâ7G&FVw•G'WF„ÆVFvW%óCC“DFVGWW56ÖTÖ–çEFW&Ö–æÄ6Æ÷6Uv–æF÷r‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ7G&FVw•G'WF„ÆVFvW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC“C¢7G&FVw’Ö6ÆVâu"õäÂ×W7BW†6ÇVFRGWÆ–6FRFW&Ö–æÂ6VÆÂ&÷w2f÷"F†R6ÖRÖ–çB6Æ÷6R'W'7B"Â7&2æ6öçF–ç2‚%4ÔUôÔ”åEõDU$Ô”äÅôDTEUõt”äDõuôÕ2"’bb7&2æ6öçF–ç2‚'6VVäÖ–çD6Æ÷6Uv–æF÷w2"’bb7&2æ6öçF–ç2‚%5E$DTu•ôÔ”åEô4Äõ4Uõt”äDõuôDTEUTEóCC“B"’¢76W'EG'VR‚%cRããCC“C¢GWÆ–6FRFW&Ö–æÂFVGWR×W7B&VÖ–â7G&FVw’×&VBÖöæÇ’æBæ÷BFVÆWFR&r¦÷W&æÂ&÷w2"Â7&2æ6öçF–ç2‚&6ÆVæVEFW&Ö–æÅ&÷w2"’bb7&2æ6öçF–ç2‚&vWE&V6VçEfÆ–D6Æ÷6VEG&FW5&r"’bb7&2æ6öçF–ç2‚%7G&FVw•G'WF„ÆVFvW""’bb7&2æ6öçF–ç2‚&FVÆWFR"’¢Ð   ¢FW7@¢gVâ&÷E6W'f–6UóCC“T6öæf—&ÖVD6F7G&÷†T'—76W5F–6µ†çFöÔFVE¦öæR‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC“S¢6öæf—&ÖVBW†V7WF&ÆR×&–6R6F7G&÷†W2×W7B'—72F†RöÆB&VÆ÷rÓS†çFöÒFVB¦öæR"Â7&2æ6öçF–ç2‚&6F7G&÷†–46öæf—&ÖVCCCƒR"’bb7&2æ6öçF–ç2‚&W†V5g5&tFVÇFCCƒRÃÒ#ã"’bb7&2æ6öçF–ç2‚%D”4µô4D5E$õ„”5ô4ôäd•$ÔTEô%•55õ„åDôÕóCC“R"’¢76W'EG'VR‚%cRããCC“S¢æ÷&ÖÂ†çFöÒ&÷FV7F–öâ×W7B&VÖ–âf÷"Væ6öæf—&ÖVB'7W&B&VG2"Â7&2æ6öçF–ç2‚'fÂ†çFöÕ&VBÒæÅ7Dæ÷rÂÓSãbb6F7G&÷†–46öæf—&ÖVCCCƒR"’bb7&2æ6öçF–ç2‚"†çFöÕ&VBbbGvõ7G&–¶R"’¢Ð   ¢FW7@¢gVâ&W÷'F–æt‡V%óCC“e6†÷w5vÆÆWE&VÆ—¦F–öäv–ç7D¦÷W&æÅæÂ‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC“c¢÷W&F–öæÂ&W÷'B×W7B6†÷rv†W&R¦÷W&æÂäÂ6—G2fW'7W2Æ—fRvÆÆWBG'WF‚"Â7&2æ6öçF–ç2‚%vÆÆWB&VÆ—¦F–öã¢"’bb7&2æ6öçF–ç2‚&Æ—fUvÆÆWCÒ"’bb7&2æ6öçF–ç2‚'7G&FVw”6ÆVåäÃÒ"’bb7&2æ6öçF–ç2‚'&t¦÷W&æÅäÃÒ"’bb7&2æ6öçF–ç2‚&¦÷W&æÅ÷æÅö—5öæ÷E÷vÆÆWEö&Ææ6R"’¢76W'EG'VR‚%cRããCC“c¢vÆÆWB&VÆ—¦F–öâ×W7B&VBÆ—fRvÆÆWB7FFRæB†÷7B÷Vâ6÷VçBv—F†÷WBF÷V6†–ærW†V7WF–öâ"Â7&2æ6öçF–ç2‚$&÷E6W'f–6Rç7FGW2çvÆÆWE6öÂ"’bb7&2æ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W"ævWD÷Vä6÷VçB‚’"’bb7&2æ6öçF–ç2‚&'V–ÆD¦÷W&æÅ7VÖÖ'’"’¢Ð   ¢FW7@¢gVâ¦÷W&æÄ7F—f—G•óCC“tF—7Æ—4gVÆÄÆ–fV7–6ÆU&÷w4æ÷E6VÆÄöæÇ’‚’°¢fÂV’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ô¦÷W&æÄ7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂ7F÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT†—7F÷'•7F÷&Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCC“s¢G&FR¦÷W&æÂFF6÷W&6R×W7B–æ6ÇVFR%U’Â4TÄÂÂæB%D”ÂÆ–fV7–6ÆR&÷w2"ÂV’æ6öçF–ç2‚&vWDÆÅfÆ–EG&FW56æ6†÷BƒUó’"’bb7F÷&Ræ6öçF–ç2‚&gVâvWDÆÅfÆ–EG&FW56æ6†÷B"’bb7F÷&Ræ6öçF–ç2‚$–æ6ÇVFW2%U’Â4TÄÂÂæB%D”Åõ4TÄÂ&÷w2"’¢76W'EG'VR‚%cRããCC“s¢G&FR¦÷W&æÂ&Vg&W6‚÷&VæFW"vF–ær×W7BW6RÆ–fV7–6ÆR&÷r6÷VçBÂæ÷B6VÆÂÖöæÇ’6÷VçB"ÂV’æ6öçF–ç2‚'fÂÆ–fV7–6ÆU&÷t6÷VçBÒf–ÇFW&VBç6—¦R"’bbV’æ6öçF–ç2‚&Æ7E&VæFW&VEG&FT6÷VçBÒÆ–fV7–6ÆU&÷t6÷VçB"’bbV’æ6öçF–ç2‚'Gd¦÷W&æÄ6÷VçBçFW‡BÒVçG&–W2ç6—¦RçFõ7G&–ær‚’"’¢fÂ6—¦T–çFW'öÆF–öäg&vÖVçBÒ'6—¦R"²"G²rBw×² ¢76W'EG'VR‚%cRããCC“s¢%U’&÷w2×W7B&VæFW"2VçG&–W2–ç7FVBöbf¶RäÂ÷WF6öÖW2"ÂV’æ6öçF–ç2‚'fÂ—4'W•&÷rÒVçG'’ç6–FRæWVÇ2"’bbV’æ6öçF–ç2‚$%U•ôTåE%’"’bbV’æ6öçF–ç2‡6—¦T–çFW'öÆF–öäg&vÖVçB’bbV’æ6öçF–ç2‚&¦÷W&æÂ&÷w2"’¢Ð   ¢FW7@¢gVâÆV&æ–æuW'6—7FVæ6UóCSæWfW$W‡÷'G4†Vg”'&–ç4öäÖ–åF‡&VB‚’°¢fÂÇÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–æuW'6—7FVæ6Ræ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS¢6fTÆÂ×W7B&VF—&V7BÖ–â×F‡&VB6ÆÇ2&Vf÷&RW‡÷'E7FFR6W&–Æ—¦F–öâ"ÂÇæ6öçF–ç2‚$Æö÷W"æ×”Æö÷W"‚’ÓÒÆö÷W"ævWDÖ–äÆö÷W"‚’"’bbÇæ6öçF–ç2‚$ÄT$ä”äuõU%4•5Eõ$TD•$T5DTEôôdeôÔ”åóCS"’bbÇæ–æFW„öb‚$Æö÷W"æ×”Æö÷W"‚’ÓÒÆö÷W"ævWDÖ–äÆö÷W"‚’"’ÂÇæ–æFW„öb‚'6fTÆÄ&Æö6¶–æt–çFW&æÂ"’¢76W'EG'VR‚%cRããCS¢W&–öF–2'VçF–ÖRW'6—7FVæ6R×W7BVWVR7–æ2Âæ÷B&Æö6²F†R&÷BÆö÷v—F‚W‡÷'E7FFR"ÂÇæ6öçF–ç2‚&gVâ&WVW7E6fTÆÄ7–æ2"’bbÇæ6öçF–ç2‚$ÄT$ä”äuõU%4•5Eô5”ä5õ4dUóCS"’bb&÷Bæ6öçF–ç2‚$ÆV&æ–æuW'6—7FVæ6Rç&WVW7E6fTÆÄ7–æ2"’bb&÷Bæ6öçF–ç2‚'W&–öF–5öÆö÷óCS"’¢fÂW&–öF–56fT&Æö6²Ò&÷Bç7V'7G&–ær‚†&÷Bæ–æFW„öb‚'W&–öF–5öÆö÷óCS"’Ò##’æ6öW&6TDÆV7Bƒ’Â†&÷Bæ–æFW„öb‚'W&–öF–5öÆö÷óCS"’²##’æ6öW&6TDÖ÷7B†&÷BæÆVæwF‚’¢76W'DfÇ6R‚%cRããCS¢W&–öF–2'VçF–ÖRF‚×W7Bæ÷B6ÆÂÆV&æ–æuW'6—7FVæ6Rç6fTÆÂ‚’–æÆ–æR"ÂW&–öF–56fT&Æö6²æ6öçF–ç2‚$ÆV&æ–æuW'6—7FVæ6Rç6fTÆÂ‚’"’¢Ð   ¢FW7@¢gVâ7G&FVw•G'WF…óCS%&WV—&W4f÷&Vç6–4ÖöæW•&öödf÷$Æ&vTÆ—fUæÂ‚’°¢fÂG'WF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ7G&FVw•G'WF„ÆVFvW"æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS#¢7G&FVw’Ö6ÆVâäÂ×W7BW†6ÇVFRVæVF—FVBÆ&vRÆ—fRÖöæW’&÷w2"ÂG'WF‚æ6öçF–ç2‚&f÷&Vç6–5&V¦V7E&V6öâ"’bbG'WF‚æ6öçF–ç2‚$Ä$tUõäÅôäõEõtÄÄUEôd”äÂ"’bbG'WF‚æ6öçF–ç2‚%5E$DTu•ôdõ$Tå4”5ôU„4ÅTDTEò"’bbG'WF‚æ6öçF–ç2‚%äÅõ4ôÅõU$4TåEôÔ•4ÔD4‚"’¢76W'EG'VR‚%cRããCS#¢¦÷W&æÂ&÷w2×W7B6''’&ööe7FFRg&öÒF†R6†&VBW†V7WF÷"6†ö¶Rö–çB"ÂW†V2æ6öçF–ç2‚'&ööe7FFTf÷$¦÷W&æÃCS""’bbW†V2æ6öçF–ç2‚$Ä•dUôd”äÄ•¤TB"’bbW†V2æ6öçF–ç2‚'&ööe7FFRÒ&ööe7FFTf÷$¦÷W&æÃCS""’¢fÂf÷&Vç6–4–çFW'öÆF–öäg&vÖVçBÒ&f÷&Vç6–3Ò"²"G²rBw×² ¢76W'EG'VR‚%cRããCS#¢&W÷'B×W7B7W&f6Rf÷&Vç6–27G&FVw’W†6ÇW6–öç2"Â&W÷'Bæ6öçF–ç2†f÷&Vç6–4–çFW'öÆF–öäg&vÖVçB’bb&W÷'Bæ6öçF–ç2‚'G'WF‚æVF—Bæf÷&Vç6–4W†6ÇVFVB"’¢Ð   ¢FW7@¢gVâ†÷7EvÆÆWEG&6¶W%óCSDFöW4æ÷D6÷VçE7FÆU&tv†÷7G44÷Vâ‚’°¢fÂG&6¶W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô†÷7EvÆÆWEFö¶VåG&6¶W"æ·B"’ç&VEFW‡B‚¢fÂwV&BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V6÷fW&VD†öÆDwV&Bæ·B"’ç&VEFW‡B‚¢fÂf—'7E6VVå¦W&ôg&vÖVçBÒ&f—'7E6VVåvÆÆWD×2Òòæ÷DÆöær"²"…Â&f—'7E6VVåvÆÆWD×5Â"ÂÂ’ ¢fÂÆ7E6VVå¦W&ôg&vÖVçBÒ&Æ7E6VVåvÆÆWD×2Òòæ÷DÆöær"²"…Â&Æ7E6VVåvÆÆWD×5Â"ÂÂ’ ¢fÂæôæ÷u&Vg&W6„g&vÖVçBÒ&æWfW"&Vg&W6‚ÆVv7’W'6—7FVB&÷w2Fò"²%Â&æ÷uÂ" ¢76W'EG'VR‚%cRããCSC¢W'6—7FVBG&6¶W"&÷w2×W7Bæ÷B&R&Vg&W6†VBFòæ÷röâÆöB"ÂG&6¶W"æ6öçF–ç2†f—'7E6VVå¦W&ôg&vÖVçB’bbG&6¶W"æ6öçF–ç2†Æ7E6VVå¦W&ôg&vÖVçB’bbG&6¶W"æ6öçF–ç2†æôæ÷u&Vg&W6„g&vÖVçB’¢76W'EG'VR‚%cRããCSC¢7W'&VçBæòÖ†VÆBvÆÆWBWF†÷&—G’×W7B÷WG&æ²7FÆR&r÷6—F—fRÆ–&–Æ—G’"ÂG&6¶W"æ6öçF–ç2‚&7W'&VçBvÆÆWBWF†÷&—G’÷WG&æ·27FÆRG‚÷G&6¶W"&r"’bbG&6¶W"æ6öçF–ç2‚%vÆÆWDWF†÷&—G•6æ6†÷Bä%4TåEô4ôäd•$ÔTB"’bbG&6¶W"æ6öçF–ç2‚%vÆÆWDWF†÷&—G•6æ6†÷Bääõô5U%$TåEô„TÄEõ$ôôb"’bbG&6¶W"æ6öçF–ç2‚'&WGW&â†4g&W6„'W”Æ–&–Æ—G’‡Âæ÷r’"’¢76W'EG'VR‚%cRããCSC¢&V6÷fW&VB†öÆBw&6R×W7B&R6ÆV&VBf÷"Ö–çG2'6VçBg&öÒ7W'&VçBvÆÆWB6æ6†÷B"ÂG&6¶W"æ6öçF–ç2‚%&V6÷fW&VD†öÆDwV&Bç&V6öæ6–ÆUv—F„†VÆDÖ–çG2‡vÆÆWDÖ–çG2æ¶W—2’"’bbwV&Bæ6öçF–ç2‚&gVâ&V6öæ6–ÆUv—F„†VÆDÖ–çG2"’bbwV&Bæ6öçF–ç2‚%$T4õdU$TEô„ôÄEôt„õ5Eôu$4Uô4ÄT$TEóCSB"’¢Ð   ¢FW7@¢gVâVGV6F–öäf—&V†÷6UóCSUW6W56öÄ&6—4&Vf÷&UG&–æ–ætÖVÖ÷'”'&–ç2‚’°¢fÂVGRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærôVGV6F–öå7V$Æ–W$’æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSS¢VGV6F–öå7V$Æ–W$’×W7B6''’4ôÂ&6—2f–VÆG2"ÂVGRæ6öçF–ç2‚'fÂVçG'”6÷7E6öÃ¢F÷V&ÆRÒã"’bbVGRæ6öçF–ç2‚'fÂæÅ6öÃ¢F÷V&ÆRÒã"’¢76W'EG'VR‚%cRããCSS¢VGV6F–öâf—&V†÷6R×W7BV&çF–æR4ôÂÖ&6—2Ö—6ÖF6†W2&Vf÷&RFö¶Våv–äÖVÖ÷'’õGFW&äÖVÖ÷'’G&–æ–ær"ÂVGRæ6öçF–ç2‚$ÆV&æ–æuæÅ6æ—F—¦W"æ–ç7V7E7B"’bbVGRæ6öçF–ç2‚$VGV6F–öå7V$Æ–W$’æf—&V†÷6SCSR"’bbVGRæ6öçF–ç2‚$TET4D”ôåôd•$T„õ4UõT$åD”äTEóCSR"’bbVGRæ–æFW„öb‚$ÆV&æ–æuæÅ6æ—F—¦W"æ–ç7V7E7B"’ÂVGRæ–æFW„öb‚%Fö¶Våv–äÖVÖ÷'’ç&V6÷&EG&FT÷WF6öÖR"’¢76W'EG'VR‚%cRããCSS¢W†V7WF÷"6Æ÷6RF‡2×W7B÷VÆFRVGV6F–öâ4ôÂ&6—2"ÂW†V2æ6öçF–ç2‚&VçG'”6÷7E6öÂÒG2ç÷6—F–öâæ6÷7E6öÂ"’bbW†V2æ6öçF–ç2‚&VçG'”6÷7E6öÂÒ÷2æ6÷7E6öÂ"’bbW†V2æ6öçF–ç2‚'æÅ6öÂÒæÂ"’¢Ð   ¢FW7@¢gVâÆ—fU7G&FVw•GVæW%óCSeW6W56öÅG'WF„f÷%7E'VææW$6Æ–×2‚’°¢fÂGVæW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU7G&FVw•GVæW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSc¢†–v‚W&6VçFvRUbv—F‚æVvF—fR4ôÂ×W7B&V6öÖR&ö&RÂæ÷B'VææW"W†V×F–öâ"ÂGVæW"æ6öçF–ç2‚'7E6öÄ6öçG&F–7F–öâ"’bbGVæW"æ6öçF–ç2‚'7E÷6öÅö6öçG&F–7F–öå÷&ö&R"’bbGVæW"æ6öçF–ç2‚&ÖVâãÒ#ã"’bbGVæW"æ6öçF–ç2‚'6öÂÂÓã"’¢76W'EG'VR‚%cRããCSc¢7–ÖÖWG&–2'VææW"W†V×F–öâ×W7B&WV—&RæöâÖæVvF—fR4ôÂG'WF‚"ÂGVæW"æ6öçF–ç2‚&âãÒ‚bbÖVâãÒ#ãbb6öÂãÒã"’bbGVæW"æ6öçF–ç2‚&âãÒ3bbw"ãÒCãbb6öÂãÒã"’¢76W'EG'VR‚%cRããCSƒC¢6WfW&RÆ÷rÕu"Æ—fRÆæW2—f÷BF÷†–2BããÓ‚v—F†÷WB†&B&Æö6¶–ær÷"'VææW"×F–Væ6RÖ–7&ò×&ö&W2"ÂGVæW"æ6öçF–ç2‚&âãÒ‚bbw"ÃÒRãbb6öÂÂã"’bbGVæW"æ6öçF–ç2‚'F÷†–5÷&V6Æ–Õ÷F7F–5÷—f÷B"’bbGVæW"æ6öçF–ç2‚'F÷†–4–ææW$ÆæU—f÷B"’¢Ð   ¢FW7@¢gVâ&÷FV7FVDÖVÖT–çF¶UóCSu&ö&F–öäFöW4æ÷D'—75¦W&ôÆ—V–F—G•&V¦V7B‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ¦W&ôÆ—&Æö6²Ò&÷Bç7V'7G&–ær†&÷Bæ–æFW„öb‚%cRããCSr"’Â&÷Bæ–æFW„öb‚%cRã’ã##‚"’¢fÂ&Vv—7G'•&W7F÷&Tg&vÖVçBÒ'fÂ—5&Vv—7G'•&W7F÷&RÒ6÷W&6RÓÒ"²%Â$ÔTÔUõ$Tt•5E%•õ$U5Dõ$UÂ" ¢fÂ&ö&F–öå&öÖ÷F–öäg&vÖVçBÒ'fÂ—5&ö&F–öå&öÖ÷F–öâÒ6÷W&6RÓÒ"²%Â%$ô$D”ôåÂ" ¢fÂ7FÆT6öÖ&–æVDg&vÖVçBÒ'6÷W&6RÓÒ"²%Â$ÔTÔUõ$Tt•5E%•õ$U5Dõ$UÂ""²"ÇÂ6÷W&6RÓÒ"²%Â%$ô$D”ôåÂ" ¢76W'EG'VR‚%cRããCSs¢&ö&F–öâ&öÖ÷F–öâ×W7Bæ÷B&RG&VFVB2&Vv—7G'’&W7F÷&Rf÷"W&R×¦W&òÆ—V–F—G’"Â¦W&ôÆ—&Æö6²æ6öçF–ç2‡&Vv—7G'•&W7F÷&Tg&vÖVçB’bb¦W&ôÆ—&Æö6²æ6öçF–ç2‡&ö&F–öå&öÖ÷F–öäg&vÖVçB’bb¦W&ôÆ—&Æö6²æ6öçF–ç2‡7FÆT6öÖ&–æVDg&vÖVçB’¢76W'EG'VR‚%cRããCSs¢W&R×¦W&ò&ö&F–öâ&öÖ÷F–öç2×W7B&VÖ–â6öÆBæBæò×vF6†Æ—7B&Vf÷&R‡–G&F–öâ"Â¦W&ôÆ—&Æö6²æ6öçF–ç2‚$”åD´Uõ$ô$D”ôåôÄ•õ¤U$õõ$T¤T5EóCSr"’bb¦W&ôÆ—&Æö6²æ6öçF–ç2‚%$ô$D”ôåôÄ•õ¤U$õõ$T¤T5EóCSr"’bb¦W&ôÆ—&Æö6²æ6öçF–ç2‚&æõ÷vF6†Æ—7C×G'VR"’bb¦W&ôÆ—&Æö6²æ–æFW„öb‚$”åD´Uõ$ô$D”ôåôÄ•õ¤U$õõ$T¤T5EóCSr"’Â¦W&ôÆ—&Æö6²æ–æFW„öb‚'&WGW&âfÇ6R"’¢76W'EG'VR‚%cRããCSs¢&ö&F–öâ&öÖ÷F–öâÖ’'—72&ö&F–öâ&÷WF–æröæÇ’gFW"¦W&òÖÆ—vFR"Â&÷Bæ6öçF–ç2‚'F†BF‚Ö’'—72&ö&F–öâ&÷WF–ærÂ'WB—B×W7B7F–ÆÂ72"’bb&÷Bæ6öçF–ç2‚'W&R×¦W&òÆ—V–F—G’&V¦V7B&Vf÷&R†÷BvF6†Æ—7B‡–G&F–öâ"’¢Ð    ¢FW7@¢gVâFö¶Våv–äÖVÖ÷'•óCS…&W—'5W'6—7FVEö—6öäöäÆöB‚’°¢fÂÖVÒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶Våv–äÖVÖ÷'’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒ¢W'6—7FVBv–ææW'2×W7B72Ö&¶WBÖ6Ö&6—26æ—G’Âæ÷BöæÇ’7B&÷VæG2"ÂÖVÒæ6öçF–ç2‚'6æUW'6—7FVEv–ææW#CS‚"’bbÖVÒæ6öçF–ç2‚&–×Æ–VE7B"’bbÖVÒæ6öçF–ç2‚&'2†–×Æ–VE7BÒrçæÅW&6VçB’"’¢76W'EG'VR‚%cRããCSƒ¢ÆöB×W7BV&çF–æR&BW'6—7FVBv–ææW'2÷GFW&ç2÷Fö¶Vå7FG2&Vf÷&RGFW&ävöÆFVävö÷6R6â&VBF†VÒ"ÂÖVÒæ6öçF–ç2‚'W'6—7FVEv–ææW%V&çF–æSCS‚"’bbÖVÒæ6öçF–ç2‚'W'6—7FVEGFW&åV&çF–æSCS‚"’bbÖVÒæ6öçF–ç2‚'W'6—7FVEFö¶Vå7FG5V&çF–æSCS‚"’bbÖVÒæ–æFW„öb‚'6æUW'6—7FVEv–ææW#CS‚‡r’"’ÂÖVÒæ–æFW„öb‚'v–ææ–æuFö¶Vç5·ræÖ–çEÒÒr"’¢76W'EG'VR‚%cRããCSƒ¢&W—&VBFö¶Våv–äÖVÖ÷'’W'6—7FVæ6R×W7B&R7W&f6VBæB6fVBöæ6R"ÂÖVÒæ6öçF–ç2‚%Dô´Tåõt”åôÔTÔõ%•õU%4•5DTEõô•4ôåõU$tTEóCS‚"’bbÖVÒæ6öçF–ç2‚'6fR‚’"’¢Ð   ¢FW7@¢gVâ&W÷'F–æt‡V%óCS”W†V7WF—fUW6W57G&FVw”6ÆVåv†Vå&t¦÷W&æÄ–æfÆFVB‚’°¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS“¢W†V7WF—fR6æ6†÷B×W7B6ö×WFR7G&FVw•G'WF„ÆVFvW"&Vf÷&R&–çF–ær¦÷W&æÂÖöæW’"Â&W÷'Bæ6öçF–ç2‚'7G&FVw•G'WFƒCS’"’bb&W÷'Bæ6öçF–ç2‚%7G&FVw•G'WF„ÆVFvW"æ6ÆVâ"’bb&W÷'Bæ6öçF–ç2‚&W†6ÇVFVCCS’"’¢76W'EG'VR‚%cRããc#¢–æfÆFVB&r¦÷W&æÂ×W7B&RFVÖ÷FVB&V†–æB7G&FVw’6ÆVâ†VFÆ–æR"Â&W÷'Bæ6öçF–ç2‚%7G&FVw’6ÆVâ†VFÆ–æR"’bb&W÷'Bæ6öçF–ç2‚%&r¦÷W&æÂVF—B"’bb&W÷'Bæ6öçF–ç2‚&æ÷FS×&uöæ÷E÷7G&FVw•÷G'WF‚"’¢76W'EG'VR‚%cRããc#¢G&FR¦÷W&æÂ7VÖÖ'’×W7Bæ÷BÆ&VÂ&rF÷FÇ2÷"'F–Â&÷w226æöæ–6Â7G&FVw’6Æ÷6W2"Â&W÷'Bæ6öçF–ç2‚%&r¦÷W&æÂF÷FÇ2"’bb&W÷'Bæ6öçF–ç2‚&æ÷FS×&U÷G'WF…öÆVFvW%öVF—Eöæ÷E÷7G&FVw•ö6Æ÷6W2"’bb&W÷'Bæ6öçF–ç2‚##F‚&r6VÆÂ&÷w2"’bb&W÷'Bæ6öçF–ç2‚$6æöæ–6ÂF÷FÇ3¢6Æ÷6W3Ò"’bb&W÷'Bæ6öçF–ç2‚$¦÷W&æÂ6æöæ–6Ã¢"’¢Ð   ¢FW7@¢gVâW†V7WF÷%óCSÆ—fU66÷&T&æEw%6ögE6†W4VçG'•6—¦R‚’°¢fÂ66÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ66÷&TW‡V7Fæ7•G&6¶W"æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS¢Æ—fR66÷&RÖ&æBW‡V7Fæ7’×W7BW‡÷6R6—¦RÖöæÇ’6†RÂæ÷BÆ—fR&V¦V7B"Â66÷&Ræ6öçF–ç2‚&gVâÆ—fU6—¦U6†R"’bb66÷&Ræ6öçF–ç2‚&6F7G&÷†–5÷66÷&Uö&æE÷&ö&R"’bb66÷&Ræ6öçF–ç2‚'÷6—F—fU÷66÷&Uö&æE÷&W72"’¢76W'EG'VR‚%cRããCS¢W†V7WF÷"Æ—fR6—¦–ær7F6²×W7B6öç7VÖR66÷&RÖ&æBu"õäÂ×VÇF—Æ–W""ÂW†V2æ6öçF–ç2‚$Ä•dUôU…T5Dä5•õ44õ$Uô$äEõ4ôeEõ4„TEóCS"’bbW†V2æ6öçF–ç2‚'66÷&T&æEu#CS"’bbW†V2æ6öçF–ç2‚%66÷&TW‡V7Fæ7•G&6¶W"æÆ—fU6—¦U6†R"’¢76W'EG'VR‚%cRããCS¢ÆVv7’Æ—fR&V¦V7B'—72&VÖ–ç2æöâÖ&Æö6¶–ær"Â66÷&Ræ6öçF–ç2‚$Ä•dUôU…T5Dä5•õ$T¤T5Eô%•54TB"’bb66÷&Ræ6öçF–ç2‚'&WGW&âfÇ6R"’¢Ð ¢FW7@¢gVâW†V7WF÷%óCS&VÆ—¦VEvÆÆWD6ö×÷VæF–æuW6W57G&FVw•G'WF„öæÇ’‚’°¢fÂv÷bÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&VÆ—¦VEvÆÆWD6ö×÷VæF–ætv÷fW&æ÷"æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcƒ¢6ö×÷VæF–ærv÷fW&æ÷"×W7BW6RÖöFRÖÆö6Â&VÆ—¦VBÖöæW’&÷w2Âæ÷B&r6æöæ–6ÂF÷FÇ2÷"&ÆVæFVBFW&Ö–æÂÖöæÇ’7G&FVw•G'WF‚&÷w2"Âv÷bæ6öçF–ç2‚&vWE&V6VçEfÆ–D6Æ÷6VEG&FW5&r"’bbv÷bæ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’æ—5W"‚’"’bbv÷bæ6öçF–ç2‚&ÖöFScƒ"’bbv÷bæ6öçF–ç2‚%%D”Åõ4TÄÂ"’bbv÷bæ6öçF–ç2‚%&VÆ—¦VEvÆÆWD6ö×÷VæF–æræÖöæW•&÷w3cƒ"’bbv÷bæ6öçF–ç2‚&vWD6æöæ–6ÅF÷FÇ2"’¢76W'EG'VR‚%cRããCS¢6ö×÷VæF–ær&Vg&W6‚×W7B&R66†VBö&6¶w&÷VæBÂæ÷B7–æ6‡&öæ÷W2W"†÷B×F‚6æF–FFR"Âv÷bæ6öçF–ç2‚%$Te$U4…õEDÅôÕ2"’bbv÷bæ6öçF–ç2‚$vÆö&Å66÷RæÆVæ6‚„F—7F6†W'2ç6–FTVffV7B’"’bbv÷bæ6öçF–ç2‚&66†VBæ×VÇF—Æ–W""’¢76W'EG'VR‚%cRããCS¢W†V7WF÷"6—¦–ær7F6²×W7B6öç7VÖRvÆÆWB×&VÆ—¦VB6ö×÷VæF–ær×VÇF—Æ–W""ÂW†V2æ6öçF–ç2‚%$TÄ•¤TEõtÄÄUEô4ôÕõTäD”äuõ4„TEóCS"’bbW†V2æ6öçF–ç2‚'vÆÆWD6ö×÷VæCCS"’bbW†V2æ6öçF–ç2‚%&VÆ—¦VEvÆÆWD6ö×÷VæF–ætv÷fW&æ÷"ç6—¦T×VÇF—Æ–W""’¢76W'EG'VR‚%cRããCS¢÷W&F÷"&W÷'B×W7B7W&f6R6ö×÷VæF–ær7FFR"Â&W÷'Bæ6öçF–ç2‚%&VÆ—¦VEvÆÆWD6ö×÷VæF–ætv÷fW&æ÷"ç7FGW4Æ–æR"’¢Ð   ¢FW7@¢gVâ7G&FVw•FVÆVÖWG'•óCS46ÆVäÆ—fUG'WF„fVVG4vFU&VÆ†W"‚’°¢fÂFVÆVÖWG'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ7G&FVw•FVÆVÖWG'’æ·B"’ç&VEFW‡B‚¢fÂ&VÆ†W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fTÆ–W$vFU&VÆ†W"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3¢7G&FVw•FVÆVÖWG'’×W7BW‡÷6R6ÆVâÆ—fRFW&Ö–æÂWF†÷&—G’"ÂFVÆVÖWG'’æ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&B"’bbFVÆVÖWG'’æ6öçF–ç2‚%7G&FVw•G'WF„ÆVFvW"æ6ÆVâ"’¢fÂ&ö"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU&ö&&–Æ—G”Væv–æRæ·B"’ç&VEFW‡B‚¢fÂGVæW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU7G&FVw•GVæW"æ·B"’ç&VEFW‡B‚¢fÂF×W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæTW‡V7Fæ7”F×W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3¢Æ—fTÆ–W$vFU&VÆ†W"u"ö66†R×W7BW6R6ÆVâÆ—fR7G&FVw’G'WF‚"Â&VÆ†W"æ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&B†Æ–Ö—BÒóS’"’bb&VÆ†W"æ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&B†Æ–Ö—BÒ%óS’"’bb&VÆ†W"æ6öçF–ç2‚'fÂÆVFW&&ö&BÒ7G&FVw•FVÆVÖWG'’æ6ö×WFTÆ—fUFW&Ö–æÄÆVFW&&ö&B†Æ–Ö—BÒ%óS’"’¢76W'EG'VR‚%cRããCS3¢Æ—fR&ö&&–Æ—G’÷GVæW"öF×W"×W7BW6R6ÆVâÆ—fR7G&FVw’G'WF‚"Â&ö"æ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&B†Æ–Ö—BÒóS’"’bbGVæW"æ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&B†Æ–Ö—BÒóS’"’bbF×W"æ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&B‚’"’¢76W'EG'VR‚%cRããCS3¢÷W&F÷"&W÷'B×W7B7W&f6R6ÆVâÆ—fR7G&FVw’G'WF‚"Â&W÷'Bæ6öçF–ç2‚$6ÆVäÆ—fU7G&FVw•G'WF‚"’bb&W÷'Bæ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&BƒsS’"’¢Ð   ¢FW7@¢gVâW†V7WF÷%óCSD6VçG&ÅFW&Ö–æÅöÆ–7”fæ÷WEW6W46Æ÷6VDÆV&æ–ætvFR‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSC¢FW&Ö–æÂöÆ–7’†VG2×W7B&RfVBg&öÒ&V6÷&EG&FR6Æ÷6VBÖÆV&æ–ær6†ö¶Rö–çB"ÂW†V2æ6öçF–ç2‚$4TåE$ÂDU$Ô”äÂôÄ”5’däõUB"’bbW†V2æ6öçF–ç2‚$4TåE$ÅõDU$Ô”äÅõôÄ”5•ôdäõUEóCSB"’¢76W'EG'VR‚%cRããCSC¢6VçG&Âfæ÷WB×W7B&VÖ–âFW&Ö–æÂ4TÄÂ²ÆVFvW"ö66÷VçF–ær÷6æ—G’vFVB"ÂW†V2æ6öçF–ç2‚'G&FUv—F„Ö–çBç6–FRæWVÇ2‚"’bbW†V2æ6öçF–ç2‚%4TÄÂ"’bbW†V2æ6öçF–ç2‚&ÆVFvW$ÆÆ÷w46Æ÷6VDÆV&æ–ærbb66÷VçF–æuG&–æ&ÆRbb&÷tÆV&æ–ætFÖ—GFVCC3C’"’¢òòcRããc’(	BW†—BÖ'&–âÆ&VÂf—‚â&–÷"æÄf÷$†VG3CSBâÓRãÆ&VÆÆVBÓBP¢òòÆ÷76W22&÷F–ÖÂW†—G2"Â6òF†R'&–âÆV&æVBFòW"Ö†æBWfW'’v–ææW"à¢òò6÷'&V7C¢&VÂ&æ¶VBv–âöæÇ’ƒãÒ"R’õ"ÖævVBE÷G&–Æ–ærW†—C²4ÂæWfW"à¢76W'EG'VR‚%cRããCSC¢6VçG&Âfæ÷WB×W7BfVVBÆÂVæF–æröÆ–7’†VG27–æ6‡&öæ÷W6Ç’"ÂW†V2æ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂç&V6÷&D÷WF6öÖR†Ö–çDf÷$†VG3CSBÂæÄf÷$†VG3CSB’"’bbW†V2æ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBç&V6÷&D÷WF6öÖR†Ö–çDf÷$†VG3CSBÂæÄf÷$†VG3CSB’"’bbW†V2æ6öçF–ç2‚%Væ–f–VDW†—EöÆ–7”†VBç&V6÷&D÷WF6öÖR†Ö–çDf÷$†VG3CSBÂW†—Ev4÷F–ÖÂ’"’bbW†V2æ6öçF–ç2‚$vÆö&Å66÷RæÆVæ6‚„F—7F6†W'2ç6–FTVffV7B’"’¢76W'EG'VR‚%cRããc“¢W†—BÖ'&–âÆ&VÂ×W7B&V¦V7B5DõôÄõ52÷67&F6‚Â7&VF—BE÷G&–Æ–ærÂæBvFR&ræÂB&VÂ&æ¶VB×v–âfÆö÷"ƒãÓ"R’"ÂW†V2æ6öçF–ç2‚%Tä”d”TEôU„•EõôÄ”5•ô„TEôÄ$TÅôd•…óc’"’bbW†V2æ6öçF–ç2‚%5DõôÄõ52"’bbW†V2æ6öçF–ç2‚%D´Uõ$ôd•B"’bbW†V2æ6öçF–ç2‚'æÄf÷$†VG3CSBãÒ"ã"’¢Ð   ¢FW7@¢gVâ&VÆ—¦VEvÆÆWD6ö×÷VæF–æuóCSUW6W4–çG&F”†–v…vFW$æDF–Ç•&öw&W72‚’°¢fÂv÷bÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&VÆ—¦VEvÆÆWD6ö×÷VæF–ætv÷fW&æ÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSS¢6ö×÷VæF–ærv÷fW&æ÷"×W7BG&6²'&—6&æR–çG&F’vÆÆWB†–v‚×vFW""Âv÷bæ6öçF–ç2‚$W7G&Æ–ô'&—6&æR"’bbv÷bæ6öçF–ç2‚&F”†–v…vÆÆWE6öÂ"’bbv÷bæ6öçF–ç2‚&G&vF÷väg&öÔF”†–v…7B"’¢76W'EG'VR‚%cRããCSS¢6ö×÷VæF–ærv÷fW&æ÷"×W7BW‡÷6RF–Ç’'‚óW‚&öw&W72"Âv÷bæ6öçF–ç2‚&F•&öw&W75‚"’bbv÷bæ6öçF–ç2‚'Gvõ÷…öF•ö6ö×÷VæB"’bbv÷bæ6öçF–ç2‚&f—fU÷…öF•÷&÷FV7Eö6ö×÷VæB"’¢76W'EG'VR‚%cRããCSS¢†–v‚×vFW"G&vF÷vâ×W7B&÷FV7B&öf—G2&Vf÷&RÖ÷&R6ö×÷VæF–ær"Âv÷bæ6öçF–ç2‚&–çG&F•ö†–v…÷vFW%÷&öf—E÷&÷FV7B"’bbv÷bæ6öçF–ç2‚&–çG&F•ö†–v…÷vFW%ö6ööÆ–ær"’¢Ð   ¢FW7@¢gVâ&W÷'F–æt‡V%óCSe&–ÖW5&W6V&6…66÷WDg&öÕ&W÷'DöæÇ”&6¶w&÷VæEF‚‚’°¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSc¢&W÷'BFööÆ¶—B7VÖÖ'’×W7B&–ÖR&W6V&6…66÷WB–â&6¶w&÷VæBöæÇ’"Â&W÷'Bæ6öçF–ç2‚'&–ÖU&W6V&6…66÷WDg&öÕ'VçF–ÖSCSb"’bb&W÷'Bæ6öçF–ç2‚$$4´u$õTäEõ$U4T$4…õ44õUEõ$Uõ%EóCSb"’bb&W÷'Bæ6öçF–ç2‚$F—7F6†W'2ç6–FTVffV7B"’¢76W'EG'VR‚%cRããCSc¢&W÷'B&–ÖW"×W7BVçVWVR&÷VæFVB66†VB'VçF–ÖRFö¶Vç2æB'VâW&–öF–27vVW"Â&W÷'Bæ6öçF–ç2‚'F¶Rƒ"’"’bb&W÷'Bæ6öçF–ç2‚%&W6V&6…66÷WBæVçVWVT&6¶w&÷VæE&WVW7B"’bb&W÷'Bæ6öçF–ç2‚&Ö–&U'VåW&–öF–4&6¶w&÷VæE7vVW"’¢76W'EG'VR‚%cRããCSc¢÷W&F÷"&W÷'B×W7B7W&f6R&W6V&6…66÷WBVWVRö66†R7FGW2"Â&W÷'Bæ6öçF–ç2‚%&W6V&6…66÷WBæg&VU6÷W&6U7FGW2"’bb&W÷'Bæ6öçF–ç2‚%$U4T$4…õ44õUEõ$Uõ%Eõ$”ÔTEóCSb"’bb&W÷'Bæ6öçF–ç2‚&&6¶w&÷VæEööæÇ“×G'VR"’¢Ð   ¢FW7@¢gVâG&FT†—7F÷'•7F÷&UóCStW‡÷6W46ÆVå7FG56æ6†÷Ev—F†÷WE&t×WFF–öâ‚’°¢fÂ7F÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT†—7F÷'•7F÷&Ræ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSs¢G&FT†—7F÷'•7F÷&R×W7BW‡÷6R6ÆVâ7G&FVw•G'WF„ÆVFvW"7FG26æ6†÷B"Â7F÷&Ræ6öçF–ç2‚&vWD6ÆVå7FG56æ6†÷CCSr"’bb7F÷&Ræ6öçF–ç2‚%7G&FVw•G'WF„ÆVFvW"æ6ÆVâ"’bb7F÷&Ræ6öçF–ç2‚'&r¦÷W&æÂ"’¢76W'EG'VR‚%cRããCSs¢6ÆVâ7FG26æ6†÷B×W7B&R&VBÖöæÇ’æB&W6W'fR&rf÷&Vç6–2&÷w2"Â7F÷&Ræ6öçF–ç2‚'&u&W6W'fVB"’ÇÂ&W÷'Bæ6öçF–ç2‚'&u&W6W'fVC×G'VR"’¢76W'EG'VR‚%cRããCSs¢¦÷W&æÂ7VÖÖ'’×W7B6†÷r6ÆVâ7FG2&W6–FR&r66†R"Â&W÷'Bæ6öçF–ç2‚%7F÷&R6ÆVâ7FG2CSr"’bb&W÷'Bæ6öçF–ç2‚&vWD6ÆVå7FG56æ6†÷CCSr"’bb&W÷'Bæ6öçF–ç2‚'6÷W&6SÕ7G&FVw•G'WF„ÆVFvW""’¢Ð   ¢FW7@¢gVâW†V7WF–öå&÷WFU&VÆ–&–Æ—G•óCS„fVVG4VæGö–çDf–ÇW&W4–çFõ6ögE6—¦–ær‚’°¢fÂÖVÒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF–öå&÷WFU&VÆ–&–Æ—G”ÖVÖ÷'’æ·B"’ç&VEFW‡B‚¢fÂVæGö–çBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF–öäVæGö–çD†VÇF‚æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒ¢&÷WFR&VÆ–&–Æ—G’ÖVÖ÷'’×W7B&R6ögB×6—¦RöæÇ’"ÂÖVÒæ6öçF–ç2‚'6ögB6—¦R×VÇF—Æ–W""’bbÖVÒæ6öçF–ç2‚'6—¦T×VÇF—Æ–W$f÷%6÷W&6R"’bbÖVÒæ6öçF–ç2‚'&WGW&âfÇ6R"’¢76W'EG'VR‚%cRããCSƒ¢VæGö–çBF—6&ÆW2×W7BfVVB&÷WFR&VÆ–&–Æ—G’ÖVÖ÷'’"ÂVæGö–çBæ6öçF–ç2‚$W†V7WF–öå&÷WFU&VÆ–&–Æ—G”ÖVÖ÷'’ç&V6÷&Df–ÇW&R†VæGö–çBÂ&V6öâÂÖ–çB’"’¢76W'EG'VR‚%cRããCSƒ¢W†V7WF÷"6—¦–ær7F6²×W7B–æ6ÇVFR&÷WFR&VÆ–&–Æ—G’×VÇF—Æ–W""ÂW†V2æ6öçF–ç2‚%$õUDUõ$TÄ”$”Ä•E•õ4•¤Uõ4„TEóCS‚"’bbW†V2æ6öçF–ç2‚'&÷WFU&VÆ–&–Æ—G“CS‚"’¢76W'EG'VR‚%cRããCSƒ¢&W÷'G2×W7B7W&f6R&÷WFR&VÆ–&–Æ—G’7FFR"Â&W÷'Bæ6öçF–ç2‚$W†V7WF–öå&÷WFU&VÆ–&–Æ—G”ÖVÖ÷'’ç7FGW4Æ–æR"’¢Ð   ¢FW7@¢gVâÆ—fTfæ÷WE&W77W&UóCS#$æ'&÷w4'&öE&öf—F&ÆU&W67VUv—F†÷WDÆæT×WFF–öâ‚’°¢fÂ&W77W&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fTÆæTfæ÷WE&W77W&Ræ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS##¢fæ÷WB&W77W&RFWFV7F÷"×W7BW6R66†VBÆæTWfÂö–çF¶RÇW2Æ—fRu""Â&W77W&Ræ6öçF–ç2‚'&F–òâ‚ã"’bb&W77W&Ræ6öçF–ç2‚'w"Â3ã"’bb&W77W&Ræ6öçF–ç2‚%EDÅôÕ2"’¢76W'EG'VR‚%cRããcc¢&W77W&RFVÆVÖWG'’&VÖ–ç2v†–ÆR&÷VæFVB÷væW"÷&W67VR&WfVçG2'&öBfæ÷WB"Â&÷Bæ6öçF–ç2‚$Ä•dUôdäõUEõ$U55U$Uô4ôåE$”%UDõ%ôôäÅ•ócS“’"’bb&÷Bæ6öçF–ç2‚&&÷VæFVE&W67VScc"’bb&÷Bæ6öçF–ç2‚&6Æ–ÖVD÷væW#cc"’¢76W'EG'VR‚%cRããcS““¢6æöæ–6ÂFW6²VÆ–v–&–Æ—G’&VÖ–ç2&ööbæBF÷†–6—G’f–ÇFW&VB"Â&÷Bæ6öçF–ç2‚&f–ÇFW$æöåF÷†–2‡&t÷væW%ööÂ"’bb&÷Bæ6öçF–ç2‚'VÆ—G”VÆ–v–&ÆR"’bb&÷Bæ6öçF–ç2‚&66„vVäVÆ–v–&ÆR"’bb&÷Bæ6öçF–ç2‚&FW6–væFVDFW6µVÆ–f–VCcS“’"’¢76W'EG'VR‚%cRããCS##¢&W÷'G2×W7B7W&f6Rfæ÷WB&W77W&R7FFR"Â&W÷'Bæ6öçF–ç2‚$Æ—fTÆæTfæ÷WE&W77W&Rç6æ6†÷B"’¢Ð   ¢FW7@¢gVâf–æÄFV6—6–öävFUóCS#4ÆWG4vöÆFVävö÷6T'—75vVµw%vF6†Æ—7DÆ–gDöæÇ’‚’°¢fÂfFrÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄFV6—6–öävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS#3¢vV²Õu"C†²vF6†Æ—7BÆ–gB×W7B6öç7VÇBGFW&ävöÆFVävö÷6R"ÂfFræ6öçF–ç2‚%GFW&ävöÆFVävö÷6RæVFvR‡G2ææÖRÂG2ç7–Ö&öÂ’"’bbfFræ6öçF–ç2‚&vö÷6UfW&F–7CCS#2"’¢76W'EG'VR‚%cRããCS#3¢öæÇ’tôÄBõt”ääU"ÆV&æVBGFW&ç2'—72F†RvV²Õu"Æ–gB"ÂfFræ6öçF–ç2‚%Fö¶Våv–äÖVÖ÷'’åfW&F–7BätôÄB"’bbfFræ6öçF–ç2‚%Fö¶Våv–äÖVÖ÷'’åfW&F–7Båt”ääU""’bbfFræ6öçF–ç2‚"ÆV&æVEv–ææW#CS#2"’¢76W'EG'VR‚%cRããCS#3¢'—72×W7B&RFVÆVÖWG'’×f—6–&ÆRæB7F–ÆÂ&W6W'fRF†R&rvF6†Æ—7BfÆö÷""ÂfFræ6öçF–ç2‚$dDuôtôÄDTåôtôõ4Uõu%ôÄ”eEô%•55óCS#2"’bbfFræ6öçF–ç2‚&VÇ6RtD4„Ä•5EôdÄôõ%õ$r"’¢Ð   ¢FW7@¢gVâ&÷E6W'f–6UóCS#E—f÷G5F÷†–5&–Ö'”ÆæUF‡&÷Vv„vVçF–57G–ÆU7F6²‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS#C¢6æöæ–6Â&–Ö'’ÆæR×W7BFWFV7BÆV&æVBF÷†–2VÆV7FVB&–Ö&–W2"Â&÷Bæ6öçF–ç2‚'66÷&Tf÷%—f÷CCS#B"’bb&÷Bæ6öçF–ç2‚$ÆæUF÷†–6—G”wV&Bæ—4æWDæVvF—fTFævW"†VÆV7FVE&–Ö'“CS#BÂ66÷&Tf÷%—f÷CCS#B’"’¢fÂwV&BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæUF÷†–6—G”wV&Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSCbóCSCs¢F÷†–2VÆV7FVB&–Ö&–W2×W7B&W6W'fR÷&–v–æÂ÷væW'6†—æB7F×G&VFÖVçB"Â&÷Bæ6öçF–ç2‚%$”Ô%•õ5E$DTu•õ$TTET4DUô”ääU%ôÄäUóCSCr"’bbwV&Bæ6öçF–ç2‚%&W6W'fR÷&–v–æÂÆæR÷væW'6†—"’bbwV&Bæ6öçF–ç2‚'G&VFÖVçDf÷""’¢76W'EG'VR‚%cRããCSCs¢—f÷B×W7B6†ævR–ææW"ÖÆæR7G&FVw’&Vf÷&RW&6†6RÂæ÷B§V×ÆæR÷"§W7B&VGV6R6—¦R"Â&÷Bæ6öçF–ç2‚%$”Ô%•õ5E$DTu•õ$TTET4DUô”ääU%ôÄäUóCSCr"’bb&÷Bæ6öçF–ç2‚&f–æÅ&–Ö'“Ò"²"B"²'—f÷FVE&–Ö'“CS#B"’bb&÷Bæ6öçF–ç2‚&6F7G&÷†–5÷66÷&Uö&æEöÖ–7&õ÷&ö&UóCS#B"’¢Ð   ¢FW7@¢gVâFSCS#e&W7F÷&W46÷&TÆ—fU6—¦TæE&V¦V7G4GW7EGV—F–öâ‚’°¢fÂÆæRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærôÆæUöÆ–7’æ·B"’ç&VEFW‡B‚¢fÂ&÷WFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærôfFu&÷WFUfW&F–7Bæ·B"’ç&VEFW‡B‚¢fÂfFrÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄFV6—6–öävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS#c¢4„•D4ô”âôÔä•TÄDTB×W7B&R&VÂ&VGV6VBW†V7WF–öâÆæW2Âæ÷BW&ÖæVçBW"ÖÖ–7&òFVfVÇG2"ÂÆæRæ6öçF–ç2‚&¶W’æ6öçF–ç2‚"²%Â%4„•D4ô”åÂ""²"’Óâ7FFRå$TET4TEõ4•¤UôU„T5UD”ôâ"’bbÆæRæ6öçF–ç2‚&¶W’æ6öçF–ç2‚"²%Â$Ôä•TÄDTEÂ""²"’Óâ7FFRå$TET4TEõ4•¤UôU„T5UD”ôâ"’bbÆæRæ6öçF–ç2‚&æ÷BW&ÖæVçBW"ÖÖ–7&òÆæW2"’¢76W'EG'VR‚%cRããc“C¢Æ—fRÆV&æVBFævW"&÷WF–ær×W7B¶VWU%ôÔ”5$òW"ÖöæÇ’–ç7FVBöbW66ÆF–ærFò&VGV6VBÆ—fR'W—2"Â&÷WFRæ6öçF–ç2‚$Ä•dUô$ÄTTDU%õU%ôôäÅ•ô$Äô4µóc“B"’bb&÷WFRæ6öçF–ç2‚'bÓÒfW&F–7BäÄÄõuõU%ôÔ”5$ò"’bb&÷WFRæ6öçF–ç2‚%fW&F–7Bä$Äô4µôÄ•dUô$ÄTTDU%õU%ôôäÅ’"’bb&÷WFRæ6öçF–ç2‚$Ä•dUõU%ôÔ”5$õôU44ÄDTEõDõõ$TET4TEóCS#b"’¢76W'EG'VR‚%cRããCS#c¢Æ—fRdDr×W7B&V¦V7BÖ–7&ò÷&ö&RGW7BGV—F–öâæB&WV—&R7G&FVw’—f÷B"ÂfFræ6öçF–ç2‚$Ä•dUôEU5EõET•D”ôåõ$UT•$U5õ5E$DTu•õ•dõEóCS#b"’bbfFræ6öçF–ç2‚&Æ—fUöGW7E÷GV—F–öå÷&V¦V7FVEóCS#b"’bbfFræ6öçF–ç2‚$æõG&FTö'6W'fF–öå7F÷&Rç&V6÷&D&Æö6²"’¢76W'EG'VR‚%cRããCS#c¢fÆ–BÆ—fR&÷WFW2×VÇF—Æ–W"×7F6¶VB&VÆ÷r6÷&R×W7B&W7F÷&RDR6÷&R'W’fÆö÷""ÂfFræ6öçF–ç2‚&Æ—fUö6÷&U÷6—¦UöfÆö÷%óCS#b"’bbfFræ6öçF–ç2‚&6öÒæÆ–fV7–6ÆV&÷BæFFä&÷D6öæf–r‚’ç6ÖÆÄ'W•6öÂ"’bbfFræ6öçF–ç2‚'fÆ–BÆ—fR&÷WFR&W7F÷&VB"’¢Ð   ¢FW7@¢gVâFSCS#tÆæTÆö6ÄW†V7WF–öäö&W—4fFtf–æÅ6—¦R‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS#s¢Öööç6†÷B×W7Bæ÷B6dDrf–æÂ6—¦R&6²Fò&r7VvvW7FVE6—¦U6öÂ"Â&÷Bæ6öçF–ç2‚'fÂ×4VffV7F—fU6—¦RÒÖööç6†÷DfFtFV6—6–öãòç6—¦U6öÂ"’bb&÷Bæ6öçF–ç2‚&×W7Bæ÷B6–ÆVçFÇ’6Æ×—B&6²Fò&r7VvvW7FVE6—¦U6öÂ"’¢76W'EG'VR‚%cRããCS#s¢6†—D6ö–â×W7BÇ’W†V7WF&ÆRdDr6—¦R&Vf÷&RW&Ö—BöW†V7WF÷""Â&÷Bæ6öçF–ç2‚%4„•D4ô”åôdDuôd”äÅõ4•¤UôÄ”TEóCS#r"’bb&÷Bæ6öçF–ç2‚&F§W7FVE6—¦RÒ6†—D6ö–äfFrç6—¦U6öÂ"’¢76W'EG'VR‚%cRããCS#s¢W‡&W72×W7Bæ÷B6dDrf–æÂ6—¦R&6²Fò&rW‡&W726–væÂ6—¦R"Â&÷Bæ6öçF–ç2‚'fÂW‡&W74f–æÅ6—¦RÒW‡&W74fFsòç6—¦U6öÂ"’bb&÷Bæ6öçF–ç2‚#ó¢W‡&W756–væÂç÷6—F–öå6—¦U6öÂæ6öW&6TDÆV7Bƒã’"’bb&÷Bæ6öçF–ç2‚$Fòæ÷B6&W7F÷&VBö6÷&RdDr6—¦R&6²F÷vâ"’¢Ð   ¢FW7@¢gVâFSCS#„GV×&Vv–ÖU&V6÷fW'•6—¦TæE&TfFt6÷VçFW'2‚’°¢fÂ&Vv–ÖRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&Vv–ÖTFWFV7F÷"æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS#‚ócCƒƒ¢ETÕ&VÖ–ç2&V6÷fW'’×6—¦VBv—F†÷WB7&÷72ÖÆæR7G&V²×W‚"Â&Vv–ÖRæ6öçF–ç2‚%&Vv–ÖRäETÕÓâ³"’bb&Vv–ÖRæ6öçF–ç2‚%&Vv–ÖRäETÕÓâã3R"’bb&Vv–ÖRæ6öçF–ç2‚'&WGW&â&Vv–ÖTFVÇF"’bb&Vv–ÖRæ6öçF–ç2‚'&WGW&â&Vv–ÖT×VÇB"’bb&Vv–ÖRæ6öçF–ç2‚'7G&V´FVÇF"’bb&Vv–ÖRæ6öçF–ç2‚'7G&V´×VÇB"’¢76W'EG'VR‚%cRããCS#ƒ¢ETÕÆ—fRÆæR62×W7BW6R&V6÷fW'’×6—¦R6†–ær–ç7FVBöbãÖ–7&ò62"ÂW†V2æ6öçF–ç2‚%cRããCS#‚(	BETÕ&Vv–ÖR6†÷VÆB—f÷B÷&VGV6R"’bbW†V2æ6öçF–ç2‚&GV×&Vv–ÖTÆ—fRbbÆæUFræ6öçF–ç2‚"²%Â%4„•D4ô”åÂ""²"’Óâã3R"’bbW†V2æ6öçF–ç2‚&GV×&Vv–ÖTÆ—fRbbÆæUFræ6öçF–ç2‚"²%Â$U…$U55Â""²"’Óâã3R"’¢76W'EG'VR‚%cRããCScƒ¢ETÕÆ—fR&VÆF—fRfÆö÷"×W7B&RW†V7WF&ÆRFVfVç6—fR×—f÷B6—¦RÂæ÷BGW7BGV—F–öâ"ÂW†V2æ6öçF–ç2‚&GV×&Vv–ÖTÆ—fRÓâã3R"’bbW†V2æ6öçF–ç2‚&W†V7WF&ÆRFVfVç6—fR×—f÷BfÆö÷""’¢76W'EG'VR‚%cRããCS#ƒ¢†–v‚Ö6öçf–7F–öâÆ—V–B6WGW2Ö’7F–ÆÂ6—¦RW–âETÕVæFW"7G&–7FW"66÷&RöÆ—V–F—G’"ÂW†V2æ6öçF–ç2‚'66÷&RãÒƒ"ãbbG2æÆ7DÆ—V–F—G•W6BãÒ#Uóã"’¢76W'EG'VR‚%cRããCS#ƒ¢&RÔdDrÆæRVÆ–f–6F–öâöG&÷6÷VçFW'2×W7BW‡÷6RÆæTWfÎ(i$dDr6öÆÆ6R"Â&÷Bæ6öçF–ç2‚%$TdDuôÄäUô4äD”DDUò"’bb&÷Bæ6öçF–ç2‚%$TdDuôE$õõD„”åôÄ•ò"’bb&÷Bæ6öçF–ç2‚%$TdDuô%U•õTÄ”d”TEò"’¢Ð   ¢FW7@¢gVâFSCS#”6÷&÷WF–æTÖF†VÖF–6ÄVFvTVæv–æT6GW&W4'&öD'W•6VÆÅF‚‚’°¢fÂVFvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖF†VÖF–6ÄVFvTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ—RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS#“¢VFvRVæv–æR×W7B&R6÷&÷WF–æRÖG&–æVBöfbF†R†÷BF‚"ÂVFvRæ6öçF–ç2‚$vÆö&Å66÷RæÆVæ6‚„F—7F6†W'2ç6–FTVffV7B’"’bbVFvRæ6öçF–ç2‚$6öæ7W'&VçDÆ–æ¶VEVWVSÄVFvTWfVçCâ"’bbVFvRæ6öçF–ç2‚&æò7–æ6‡&öæ÷W2’ôò"’¢76W'EG'VR‚%cRããCS#“¢VFvRVæv–æR×W7B6GW&R'&öBFVæöÖ–æF÷"÷6—¦–ær÷FW&Ö–æÂFF"ÂVFvRæ6öçF–ç2‚&6GW&TVçG'”÷÷'GVæ—G’"’bbVFvRæ6öçF–ç2‚&6GW&U6—¦–ær"’bbVFvRæ6öçF–ç2‚&6GW&UFW&Ö–æÂ"’bbVFvRæ6öçF–ç2‚&6GW&TW†—DFV6—6–öâ"’¢76W'EG'VR‚%cRããCS#’óCS3C¢&RÔdDr'W’F‚×W7BfVVBÖF†VÖF–6ÂVFvRFVæöÖ–æF÷'2F‡&÷Vv‚Æ–fV7–6ÆR'W2"Â&÷Bæ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç&TfFt6æF–FFR"’bb&÷Bæ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç&TfFtFÖ—B"’bbVFvRæ6öçF–ç2‚&6GW&TVçG'”÷÷'GVæ—G’"’¢76W'EG'VR‚%cRããCS#’óCS3s¢W†V7WF÷"×W7BfVVB6—¦–ærGG&–'WF–öâæBFW&Ö–æÂ4TÄÂõ%D”Â÷WF6öÖW2F‡&÷Vv‚Æ–fV7–6ÆR'W2"ÂW†V2æ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç6—¦–ætFV6—6–öâ"’bbW†V2æ6öçF–ç2‚&6ö×öæVçG2Ò6—¦–æu7F6´6ö×öæVçG3C#ƒR"’bbW†V2æ6öçF–ç2‚$ÖF†VÖF–6ÄVFvTVæv–æRæ6GW&UFW&Ö–æÂ"’¢76W'EG'VR‚%cRããCS#“¢&W÷'G2×W7BW‡÷6RF†RÖF†VÖF–6ÂVFvRVæv–æR"Â—Ræ6öçF–ç2‚$ÖF†VÖF–6ÄVFvTVæv–æRæf÷&ÖDf÷%—VÆ–æTGV×"’¢Ð   ¢FW7@¢gVâFSCS3ÖF†VÖF–6ÄVFvTVæv–æUv—&W4gVÆÄW†—7F–ætVFvT&Æö6²‚’°¢fÂVFvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖF†VÖF–6ÄVFvTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3¢VFvRVæv–æR×W7B–×÷'B6–FRÖVffV7BF—7F6†W"W‡Æ–6—FÇ’"ÂVFvRæ6öçF–ç2‚&–×÷'B6öÒæÆ–fV7–6ÆV&÷BçWF–ÂäF—7F6†W'2"’¢76W'EG'VR‚%cRããCS3¢VçG'’WfVçG2×W7BfVVB6÷W&6RFVæöÖ–æF÷"66÷&V6&BæBVÇF–ÖFTVFvTVæv–æR"ÂVFvRæ6öçF–ç2‚%6÷W&6TfÖ–Ç”÷÷'GVæ—G•66÷&V6&Bç&V6÷&DF—66÷fW&VB"’bbVFvRæ6öçF–ç2‚%6÷W&6TfÖ–Ç”÷÷'GVæ—G•66÷&V6&Bç&V6÷&DFÖ—GFVB"’bbVFvRæ6öçF–ç2‚%VÇF–ÖFTVFvTVæv–æRæVçVWVU&Vg&W6‚"’¢76W'EG'VR‚%cRããCS3¢FW&Ö–æÂWfVçG2×W7BfVVB66÷&TW‡V7Fæ7•G&6¶W"'’ÆæR÷6÷W&6R÷&Vv–ÖR"ÂVFvRæ6öçF–ç2‚%66÷&TW‡V7Fæ7•G&6¶W"ç&V6÷&B"’bbVFvRæ6öçF–ç2‚$ÔTUôÄäUò"’bbVFvRæ6öçF–ç2‚$ÔTUõ4õU$4Uò"’bbVFvRæ6öçF–ç2‚$ÔTUõ$Tt”ÔUò"’¢76W'EG'VR‚%cRããCS3¢æöÖÇ’ÖF‚×W7B&÷÷6R&÷VæFVB7–æ57G&FVw”Æ"‡—÷F†W6W2F‡&÷Vv‚6†ö¶U&VÆ–Vd'W2öæÇ’"ÂVFvRæ6öçF–ç2‚$6†ö¶U&VÆ–Vd'W2æÆVæ6‚"’bbVFvRæ6öçF–ç2‚$7–æ57G&FVw”Æ"ç7V&Ö—D&6¶w&÷VæD‡—÷F†W6—2"’bbVFvRæ6öçF–ç2‚'7–Ö&öÆ–46†V6¶VBÒG'VR"’¢76W'EG'VR‚%cRããCS3¢f–ÆÂ6GW&R×W7B&Rf–Æ&ÆRæBv—&VBFòW†V7WF–öä6÷7E&VF–7F÷$’ÆV&æ–ær"ÂVFvRæ6öçF–ç2‚&gVâ6GW&Tf–ÆÂ"’bbVFvRæ6öçF–ç2‚$W†V7WF–öä6÷7E&VF–7F÷$’æÆV&â"’¢76W'EG'VR‚%cRããCS3¢W†V7WF÷"FW&Ö–æÂ6GW&R×W7B72VçG'’66÷&R÷&Vv–ÖR–çFòF†RÖF‚Væv–æR"ÂW†V2æ6öçF–ç2‚'66÷&RÒ‡G&FUv—F„Ö–çBç66÷&RçF¶T–b"’bbW†V2æ6öçF–ç2‚'&Vv–ÖRÒG'’²6öÒæÆ–fV7–6ÆV&÷BæVæv–æRå&Vv–ÖTFWFV7F÷"æ7W'&VçE&Vv–ÖR‚’ææÖR"’¢Ð   ¢FW7@¢gVâFSCS3ÖF†VÖF–6ÄVFvTVæv–æUv—&W5F†Uv†öÆTæÖVD&Æö6²‚’°¢fÂVFvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖF†VÖF–6ÄVFvTVæv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3¢gVÆÂæÖVB&Æö6²×W7B&R&W6VçB–âF†R&W÷'B–çFVw&F–öâÆ–æR"ÂVFvRæ6öçF–ç2‚%VÇF–ÖFTVFvTVæv–æR´6÷VçFW&f7GVÅ&WÆ”Væv–æRµ6VÖçF–5GFW&äw&‚´7–æ57G&FVw”Æ"´×VÇF—Æ–W$GG&–'WF–öäÆVFvW"´W†—D6÷7DÖ–7&ö'&–â´6—FÄVff–6–Væ7”'&–âµ6÷W&6TfÖ–Ç”÷÷'GVæ—G•66÷&V6&Bµ66÷&TW‡V7Fæ7•G&6¶W"´W†V7WF–öä6÷7E&VF–7F÷$’´Æ—fU&ö&&–Æ—G”Væv–æR´f÷'v&D÷WF6öÖTÖöFVÂµVæ–f–VEöÆ–7”†VBµVæ–f–VDW†—EöÆ–7”†VBµ7G&FVw”‡—÷F†W6—4Væv–æR´6†ö¶U&VÆ–Vd'W2"’¢76W'EG'VR‚%cRããCS3¢VçG'’&VF&6²×W7B–æ6ÇVFRÆ—fU&ö&&–Æ—G”Væv–æRÂf÷'v&D÷WF6öÖTÖöFVÂÂVæ–f–VEöÆ–7”†VBæB6VÖçF–5GFW&äw&‚"ÂVFvRæ6öçF–ç2‚$Æ—fU&ö&&–Æ—G”Væv–æRæf÷&V67B"’bbVFvRæ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂæf÷&V67B"’bbVFvRæ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBç&VF–7Ev–å&ö""’bbVFvRæ6öçF–ç2‚%6VÖçF–5GFW&äw&‚æVçG'”Fæ&–2"’¢76W'EG'VR‚%cRããCS3¢6—¦–ærfæ÷WB×W7BfVVB×VÇF—Æ–W$GG&–'WF–öäÆVFvW"æB&VBÆ—fRGVæW"ö6—FÂ'&–ç2"ÂVFvRæ6öçF–ç2‚$×VÇF—Æ–W$GG&–'WF–öäÆVFvW"ç&V6÷&DVçG'’"’bbVFvRæ6öçF–ç2‚$Æ—fU7G&FVw•GVæW"æF§W7FÖVçB"’bbVFvRæ6öçF–ç2‚$ÆæTW‡V7Fæ7”F×W"ç6—¦T×VÇF—Æ–W""’bbVFvRæ6öçF–ç2‚$6—FÄVff–6–Væ7”'&–âç6—¦T×VÇF—Æ–W""’¢76W'EG'VR‚%cRããCS3¢FW&Ö–æÂ&VF&6²×W7B–æ6ÇVFR6÷VçFW&f7GVÂÂW†—BÖ6÷7BÂ6—FÂæB‡—÷F†W6—27F6·2v—F†÷WBGWÆ–6FRöÆ–7’Ö†VBG&–æ–ær"ÂVFvRæ6öçF–ç2‚$6÷VçFW&f7GVÅ&WÆ”Væv–æRçöÆ–7”†–çG2"’bbVFvRæ6öçF–ç2‚$W†—D6÷7DÖ–7&ö'&–âæW†—EW&vVæ7”†–çB"’bbVFvRæ6öçF–ç2‚%7G&FVw”‡—÷F†W6—4Væv–æRævWE6—¦T&–2"’bbVFvRæ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂç&V6÷&D÷WF6öÖR"’¢76W'EG'VR‚%cRããCS3¢—VÆ–æRGV××W7BW‡÷6RgVÆÂ7F6²7FGW2æB&VF&6·2"ÂVFvRæ6öçF–ç2‚'7F6²&VF&6·3¢"’bbVFvRæ6öçF–ç2‚'7F6²7FGW3¢"’bbVFvRæ6öçF–ç2‚&'•7F6µ&VF&6²"’¢Ð   ¢FW7@¢gVâFSCS3%v†öÆU7—7FVÔÆV&ç4g&öÔÆ–fV7–6ÆTæ÷DöæÇ•&W7VÇG2‚’°¢fÂVFvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖF†VÖF–6ÄVFvTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3#¢6—¦–ærö÷VâÖ–çFVçB×W7B7F×VçG'’öÆ–7’†VG2&Vf÷&RFW&Ö–æÂ÷WF6öÖW2"ÂVFvRæ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂç7F×"’bbVFvRæ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBç7F×"’bbVFvRæ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂæf÷&V67B"’bbVFvRæ6öçF–ç2‚$Æ—fU&ö&&–Æ—G”Væv–æRæf÷&V67B"’¢76W'EG'VR‚%cRããCS3#¢6—¦–ærfæ÷WB×W7BW‡÷6R7G&FVw’‡—÷F†W6—26—¦RæB7F÷&–2Âæ÷BöæÇ’FW&Ö–æÂ&V6÷&D÷WF6öÖR"ÂVFvRæ6öçF–ç2‚%7G&FVw”‡—÷F†W6—4Væv–æRævWE6—¦T&–2"’bbVFvRæ6öçF–ç2‚%7G&FVw”‡—÷F†W6—4Væv–æRævWE7F÷&–2"’bbVFvRæ6öçF–ç2‚%7G&FVw”‡—÷F†W6—4Væv–æRç6—¦–ær"’¢76W'EG'VR‚%cRããCS3"óCS3c¢W†V7WF÷"&WVW7E6VÆÂ×W7BfVVBW†—BFV6—6–öâ–çFVçBöFVfW"FFF‡&÷Vv‚Æ–fV7–6ÆR'W2&Vf÷&RFW&Ö–æÂ&÷w2"ÂW†V2æ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2æW†—DFV6—6–öâ‚"²%Â'&WVW7E6VÆÂæ–çFVçEÂ""’bbW†V2æ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2æW†—DFV6—6–öâ‚"²%Â'&WVW7E6VÆÂæFVfW%Â""’bbW†V2æ6öçF–ç2‚$DTdU%õD”å•õ$ôd•EôEU5B"’bbW†V2æ6öçF–ç2‚$DTdU%õ5E”ÄUôÔ”åô„ôÄB"’bbW†V2æ6öçF–ç2‚$DTdU%õ$T4ôä4”ÄU%ô„TÅD…•ô„ôÄB"’¢76W'EG'VR‚%cRããCS3#¢6GW&U6—¦–ær×W7B6''’&Vv–ÖR÷7G–ÆR6òF÷vç7G&VÒVæv–æW2Fòæ÷BÆV&â&Æ–æB'V6¶WG2"ÂVFvRæ6öçF–ç2‚'&Vv–ÖS¢7G&–ærÒ"²%Â%Â""’bbVFvRæ6öçF–ç2‚'7G–ÆS¢7G&–ærÒ"²%Â%Â""’bbW†V2æ6öçF–ç2‚'&Vv–ÖRÒ7W'&VçE&Vv–ÖTf÷$Æ—fUöÆ–7’ææÖR"’bbW†V2æ6öçF–ç2‚'7G–ÆRÒÆæUFr"’¢Ð   ¢FW7@¢gVâFSCS34gVÆÄÆV&æ–æu7—7FVÔVF—D—56÷W&6T6öçG&7FVB‚’°¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$gVÆÄÆV&æ–æu7—7FVÔVF—DF–vW7Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS33¢gVÆÂÆV&æ–ærVF—B×W7BFV6Æ&R&W7VÇBÖöæÇ’ÆV&æ–ær–ç7Vff–6–VçB"ÂF–vW7Bæ6öçF–ç2‚'&W7VÇEööæÇ•öÆV&æ–æuö—5ö–ç7Vff–6–VçEöf÷%÷6VÆeö–×&÷fVÖVçB"’bbF–vW7Bæ6öçF–ç2‚&6æF–FFU÷6VVâ"’bbF–vW7Bæ6öçF–ç2‚&W†—EöFVfW""’bbF–vW7Bæ6öçF–ç2‚&6÷VçFW&f7GVÅöÖ—76VEöVFvR"’¢76W'EG'VR‚%cRããCS33¢VF—B6FVv÷&–W2×W7B–æ6ÇVFR7F'fF–öâÂFVBGf—6÷'’ÂÖæW6–Â×W‚æB6÷VçFW&f7GVÂ&Æ–æFæW72"ÂF–vW7Bæ6öçF–ç2‚$DDõ5D%dD”ôâ"’bbF–vW7Bæ6öçF–ç2‚$DTEôEd•4õ%•ôäõô4ôå5TÔU""’bbF–vW7Bæ6öçF–ç2‚$ÔäU4”ôäõõU%4•5DTä4R"’bbF–vW7Bæ6öçF–ç2‚$ÕU…ô$Ä”äDäU52"’bbF–vW7Bæ6öçF–ç2‚$4õTåDU$d5ETÅô$Ä”äDäU52"’¢76W'EG'VR‚%cRããCS33¢VæFW&fVB&–÷&—G’Æ—7B×W7B–æ6ÇVFRöÆ–7’Â&WÆ’Â6VÖçF–2Â'VææW"æB&V†f–÷"ÆV&æW'2"ÂF–vW7Bæ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂ"’bbF–vW7Bæ6öçF–ç2‚%Væ–f–VEöÆ–7”†VB"’bbF–vW7Bæ6öçF–ç2‚$6÷VçFW&f7GVÅ&WÆ”Væv–æR"’bbF–vW7Bæ6öçF–ç2‚%6VÖçF–5GFW&äw&‚"’bbF–vW7Bæ6öçF–ç2‚%'VææW%&WFVçF–öä÷F–Ö—¦W""’bbF–vW7Bæ6öçF–ç2‚$&V†f–÷$ÆV&æ–ær"’¢76W'EG'VR‚%cRããCS33¢Gf—6÷'’&–÷&—G’Æ—7B×W7B–æ6ÇVFR6÷W&6RÂ×VÇF—Æ–W"Â&÷WFRÂ&ö&&–Æ—G’æB'&–FvRVæv–æW2"ÂF–vW7Bæ6öçF–ç2‚%6÷W&6TfÖ–Ç”÷÷'GVæ—G•66÷&V6&B"’bbF–vW7Bæ6öçF–ç2‚$×VÇF—Æ–W$GG&–'WF–öäÆVFvW""’bbF–vW7Bæ6öçF–ç2‚$W†V7WF–öå&÷WFU&VÆ–&–Æ—G”ÖVÖ÷'’"’bbF–vW7Bæ6öçF–ç2‚$Æ—fU&ö&&–Æ—G”Væv–æR"’bbF–vW7Bæ6öçF–ç2‚$ÖWF6övæ—F–öäW†V7WF÷$'&–FvR"’¢76W'EG'VR‚%cRããCS33¢F6‚6WVVæ6R×W7B&WV—&R66ææW"ôdDrÂ66÷&W"ÂW†—Bö6÷VçFW&f7GVÂÂWF†÷&—G’æBW'6—7FVæ6R6Æ÷6V÷WB"ÂF–vW7Bæ6öçF–ç2‚#CS3B66ææW%÷c5öfFu÷&V¦V7EöÆ&VÅöFF÷ÆæR"’bbF–vW7Bæ6öçF–ç2‚#CS3R66÷&W%öÖöFVÅöfVVF&6µöfæ÷WB"’bbF–vW7Bæ6öçF–ç2‚#CS3bW†—Eö†öÆEö6÷VçFW&f7GVÅöfVVF&6µöfæ÷WB"’bbF–vW7Bæ6öçF–ç2‚#CS3rGf—6÷'•öWF†÷&—G•öæE÷6—¦–æuö6öç7VÖW%÷v—&–ær"’bbF–vW7Bæ6öçF–ç2‚#CS3‚W'6—7FVæ6UöÖæW6–öæEö×W…ö6Æ÷6V÷WB"’¢Ð   ¢FW7@¢gVâFSCS3DÆV&æ–ætÆ–fV7–6ÆT'W5&WÆ6W4öæTöfe&TfFt†öö·2‚’°¢fÂ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–ætÆ–fV7–6ÆT'W2æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3C¢Æ–fV7–6ÆR'W2×W7BFVf–æR6÷W&6RÖÆWfVÂ6æF–FFR÷&V¦V7B÷&ö&RöFÖ—B†VÇW'2"Â'W2æ6öçF–ç2‚'&TfFt6æF–FFR"’bb'W2æ6öçF–ç2‚'&TfFu&V¦V7B"’bb'W2æ6öçF–ç2‚'&TfFu&ö&R"’bb'W2æ6öçF–ç2‚'&TfFtFÖ—B"’¢76W'EG'VR‚%cRããCS3C¢Æ–fV7–6ÆR'W2×W7BfVVBÖF†VÖF–6ÄVFvTVæv–æRæB7FæF&F—¦VB—VÆ–æT†VÇF‚Æ&VÇ2"Â'W2æ6öçF–ç2‚$ÖF†VÖF–6ÄVFvTVæv–æRæ6GW&TVçG'”÷÷'GVæ—G’"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUò"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUôDT4•4”ôåò"’¢76W'EG'VR‚%cRããCS3C¢6VçG&ÂÆæR×VÆ–f–VB&RÔdDr6÷W&6R×W7BW6RF†RÆ–fV7–6ÆR'W2f÷"6æF–FFR÷&V¦V7B÷&ö&RöFÖ—BÆ&VÇ2"Â&÷Bæ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç&TfFt6æF–FFR"’bb&÷Bæ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç&TfFu&V¦V7B"’bb&÷Bæ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç&TfFu&ö&R"’bb&÷Bæ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç&TfFtFÖ—B"’¢76W'EG'VR‚%cRããCS3C¢Æ–fV7–6ÆR'W2×W7B&VÖ–â6÷W&6RÖÆWfVÂ÷&W÷'BÖÆV&æ–æröæÇ’v—F†÷WBG&FRWF†÷&—G’"Â'W2æ6öçF–ç2‚&æõ÷G&FUöWF†÷&—G“×G'VR"’bb'W2æ6öçF–ç2‚&W†V7WFT'W’"’bb'W2æ6öçF–ç2‚'&WVW7E6VÆÂ‚"’¢Ð   ¢FW7@¢gVâFSCS3UVæ–f–VE66÷&W$fVVG4Æ–fV7–6ÆT6ö×öæVçE6æ6†÷G2‚’°¢fÂ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–ætÆ–fV7–6ÆT'W2æ·B"’ç&VEFW‡B‚¢fÂ66÷&W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærõVæ–f–VE66÷&W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3S¢Æ–fV7–6ÆR'W2×W7BW‡÷6R66÷&W"6ö×öæVçB6æ6†÷G226÷W&6RÖÆWfVÂFF"Â'W2æ6öçF–ç2‚&gVâ66÷&W$6ö×öæVçG2"’bb'W2æ6öçF–ç2‚'c5÷66÷&W%ö6ö×öæVçG2"’bb'W2æ6öçF–ç2‚%44õ$U%õ5E$ôäuô%U’"’bb'W2æ6öçF–ç2‚%44õ$U%ôäTtD•dR"’¢76W'EG'VR‚%cRããCS3S¢6Æ76–2ÂÖöFW&âæBVæ–f–VB66÷&W"F‡2×W7BVÖ—BÆ–fV7–6ÆR6ö×öæVçB6æ6†÷G2"Â66÷&W"æ6öçF–ç2‚'66÷&W$6ö×öæVçG2‚"²%Â$4Ä54”5Â""’bb66÷&W"æ6öçF–ç2‚'66÷&W$6ö×öæVçG2‚"²%Â$ÔôDU$åÂ""’bb66÷&W"æ6öçF–ç2‚'66÷&W$6ö×öæVçG2‚"²%Â%Tä”d”TEÂ""’¢76W'EG'VR‚%cRããCS3S¢66÷&W"fÆÆ&6²F‡2×W7BÇ6òfVVBÆ–fV7–6ÆRFF–ç7FVBöbF—6V&–ær"Â66÷&W"æ6öçF–ç2‚'66÷&W$6ö×öæVçG2‚"²%Â$4Ä54”5ôdÄÄ$4µÂ""’bb66÷&W"æ6öçF–ç2‚'66÷&W$6ö×öæVçG2‚"²%Â$ÔôDU$åôdÄÄ$4µÂ""’bb66÷&W"æ6öçF–ç2‚'66÷&W$6ö×öæVçG2‚"²%Â%Tä”d”TEôdÄÄ$4µÂ""’¢76W'EG'VR‚%cRããCS3S¢66÷&W"6æ6†÷G2×W7B6''’6÷W&6RÂÖ–çBÂÆ—V–F—G’ÂÖ&¶WB6æB7W'&VçB&Vv–ÖR"Â'W2æ6öçF–ç2‚&6æF–FFRç6÷W&6RææÖR"’bb'W2æ6öçF–ç2‚&6æF–FFRæÖ–çB"’bb'W2æ6öçF–ç2‚&6æF–FFRæÆ—V–F—G•W6B"’bb'W2æ6öçF–ç2‚&6æF–FFRæÖ&¶WD6W6B"’bb'W2æ6öçF–ç2‚%&Vv–ÖTFWFV7F÷"æ7W'&VçE&Vv–ÖR"’¢Ð   ¢FW7@¢gVâFSCS3dW†—D†öÆD6÷VçFW&f7GVÅ6–væÇ5W6TÆ–fV7–6ÆT'W2‚’°¢fÂ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–ætÆ–fV7–6ÆT'W2æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÖVRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖF†VÖF–6ÄVFvTVæv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3c¢Æ–fV7–6ÆR'W2×W7BFVf–æR7FæF&F—¦VBW†—BÖFV6—6–öâæB†öÆBÖFVfW"Æ&VÇ2"Â'W2æ6öçF–ç2‚&gVâW†—DFV6—6–öâ"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUôU„•Eò"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUôU„•EôDT4•4”ôåò"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUô„ôÄEôDTdU""’¢76W'EG'VR‚%cRããCS3c¢W†V7WF÷"&WVW7E6VÆÂ–çFVçBöFVfW"×W7BW6RÆ–fV7–6ÆR'W2Âæ÷BF—&V7BöæRÖöfbÔTR†öö·2"ÂW†V2æ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2æW†—DFV6—6–öâ‚"²%Â'&WVW7E6VÆÂæ–çFVçEÂ""’bbW†V2æ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2æW†—DFV6—6–öâ‚"²%Â'&WVW7E6VÆÂæFVfW%Â""’bbW†V2æ6öçF–ç2‚$ÖF†VÖF–6ÄVFvTVæv–æRæ6GW&TW†—DFV6—6–öâ‚"²%Â'&WVW7E6VÆÂæ–çFVçEÂ""’¢76W'EG'VR‚%cRããCS3c¢W†—BFV6—6–öâfæ÷WB×W7B7F–ÆÂ&V6‚6÷VçFW&f7GVÂæBW†—BÖ6÷7B6öç7VÖW'2F‡&÷Vv‚ÔTR"ÂÖVRæ6öçF–ç2‚$6÷VçFW&f7GVÅ&WÆ”Væv–æRçöÆ–7”†–çG2"’bbÖVRæ6öçF–ç2‚$W†—D6÷7DÖ–7&ö'&–âæW†—EW&vVæ7”†–çB"’bbÖVRæ6öçF–ç2‚%7G&FVw”‡—÷F†W6—4Væv–æRævWE6—¦T&–2"’¢76W'EG'VR‚%cRããCS3c¢&WVW7E6VÆÂFVfW"Æ&VÇ2×W7B&W6W'fR6–&Æ–ær†öÆBö6†ö¶R6FVv÷&–W2"ÂW†V2æ6öçF–ç2‚$DTdU%õD”å•õ$ôd•EôEU5B"’bbW†V2æ6öçF–ç2‚$DTdU%õ5E”ÄUôÔ”åô„ôÄB"’bbW†V2æ6öçF–ç2‚$DTdU%õ$T4ôä4”ÄU%ô„TÅD…•ô„ôÄB"’¢Ð   ¢FW7@¢gVâFSCS3u6—¦–ætGf—6÷'”6öç7VÖW'5W6TÆ–fV7–6ÆT'W2‚’°¢fÂ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–ætÆ–fV7–6ÆT'W2æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÖVRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖF†VÖF–6ÄVFvTVæv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3s¢Æ–fV7–6ÆR'W2×W7B7FæF&F—¦Rf–æÂ6—¦–ærW‡÷7W&RæB6Æ×Æ&VÇ2"Â'W2æ6öçF–ç2‚&gVâ6—¦–ætFV6—6–öâ"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUõ4•¤”äuò"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUõ4•¤”äuô4ÄÕTB"’bb'W2æ6öçF–ç2‚$ÄT$ä”äuôÄ”dT5”4ÄUõ4•¤”äuõ¤U$ò"’¢76W'EG'VR‚%cRããCS3s¢W†V7WF÷"f–æÂFô'W’6—¦–ær6÷W&6R×W7B&÷WFRF‡&÷Vv‚Æ–fV7–6ÆR'W2"ÂW†V2æ6öçF–ç2‚$ÆV&æ–ætÆ–fV7–6ÆT'W2ç6—¦–ætFV6—6–öâ‚"’bbW†V2æ6öçF–ç2‚$ÖF†VÖF–6ÄVFvTVæv–æRæ6GW&U6—¦–ær‚"’¢76W'EG'VR‚%cRããCS3s¢6—¦–ærfæ÷WB×W7B7F–ÆÂ&V6‚öÆ–7’7F×2æBGf—6÷'’6öç7VÖW'2"ÂÖVRæ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂç7F×"’bbÖVRæ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBç7F×"’bbÖVRæ6öçF–ç2‚$×VÇF—Æ–W$GG&–'WF–öäÆVFvW"ç&V6÷&DVçG'’"’bbÖVRæ6öçF–ç2‚%7G&FVw”‡—÷F†W6—4Væv–æRævWE7F÷&–2"’bbÖVRæ6öçF–ç2‚$6—FÄVff–6–Væ7”'&–âç6—¦T×VÇF—Æ–W""’¢76W'EG'VR‚%cRããCS3s¢6—¦–ærÆ–fV7–6ÆR×W7B&W6W'fR6ö×öæVçBÖÂ&Vv–ÖRæB7G–ÆRf÷"×W‚Öv&RÆV&æ–ær"Â'W2æ6öçF–ç2‚&6ö×öæVçG2Ò6ö×öæVçG2"’bb'W2æ6öçF–ç2‚'&Vv–ÖRÒ&Vv–ÖR"’bb'W2æ6öçF–ç2‚'7G–ÆRÒ7G–ÆR"’¢Ð   ¢FW7@¢gVâFSCS3„Æ–fV7–6ÆTWfVçG46''•'VçF–ÖTÖöFT×W‚‚’°¢fÂÖVRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖF†VÖF–6ÄVFvTVæv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCS3ƒ¢ÖF†VÖF–6ÄVFvTVæv–æRVFvTWfVçB×W7B6''’'VçF–ÖRÖöFRæB'V–ÆBFr"ÂÖVRæ6öçF–ç2‚'fÂÖöFS¢7G&–ærÒG'’²'VçF–ÖTÖöFTWF†÷&—G’æWF†÷&—G’‚’ææÖRÒ"’bbÖVRæ6öçF–ç2‚'fÂ'V–ÆC¢7G&–ærÒ6öÒæÆ–fV7–6ÆV&÷Bä'V–ÆD6öæf–rådU%4”ôåôäÔR"’¢76W'EG'VR‚%cRããCS3ƒ¢Æ–fV7–6ÆR7FG2×W7B'V6¶WB'’ÖöFRæBFV6—6–öâFò&WfVçBW"öÆ—fRÆV&æ–ær6ÖV""ÂÖVRæ6öçF–ç2‚&'”ÖöFTFV6—6–öâ"’bbÖVRæ6öçF–ç2‚&¶W’†RæÖöFRÂRæFV6—6–öâ’"’bbÖVRæ6öçF–ç2‚&'’ÖöFRöFV6—6–öâ"’¢76W'EG'VR‚%cRããCS3ƒ¢&V6VçBÆ–fV7–6ÆRÆ–æW2×W7B–æ6ÇVFRÖöFR&Vf÷&RÆæR÷6÷W&6R"ÂÖVRæ6öçF–ç2‚&ÖöFSÒG²rBw×¶RæÖöFWÒÆæSÒG²rBw×¶RæÆæWÒ7&3ÒG²rBw×¶Rç6÷W&6WÒ"’¢Ð   ¢FW7@¢gVâFSCSC÷'†äÆ—fU÷6—F–öç4–væ÷&UVæÖævVEvÆÆWDW‡G&2‚’°¢fÂ6æÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ'VçF–ÖU7FFU6æ6†÷Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSSc¢Æ—fR6æöæ–6Â÷Vâ6Æ÷G2×W7BW6R†÷7BÖ&6¶VBÆ–fV7–6ÆRG'WF‚Âæ÷B&rÆ–fV7–6ÆR÷Vä6÷VçBv†÷7G2"Â6ææ6öçF–ç2‚%Fö¶VäÆ–fV7–6ÆUG&6¶W"æÆ—fTÖVÖT÷Vä6÷VçB‚’"’bb6ææ6öçF–ç2‚'&rFö¶VäÆ–fV7–6ÆUG&6¶W"æ÷Vä6÷VçB‚’–æ6ÇVFW27FÆR"’bb6ææ6öçF–ç2‚'fÂÖævVDÆ—fT÷VâÒÖ„öb†Æö6ÄÆ—fT÷VâÂ†÷7D÷VâÂÆ–fV7–6ÆUVæF–æt6öæf—&ÖVBÂÆ–fV7–6ÆT÷Vâ’"’¢76W'EG'VR‚%cRããCSC¢÷'†äÆ—fR×W7B&RÖævVB×7FFRFW7–æ2Âæ÷BvÆÆWD†VÆBÖÆ—fT÷Vâ"Â6ææ6öçF–ç2‚&÷'†äÆ—fR×W7BÖVâÖævVB×7FFRFW7–æ2"’bb6ææ6öçF–ç2‚&ÖævVDFW7–æ2"’bb6ææ6öçF–ç2‚"‚‡vÆÆWD†VÆBÒÆ—fT÷Vâ’Òw&6TÆÆ÷væ6R’"’¢76W'EG'VR‚%cRããCSC¢vÆÆWBW‡G&2&R7F–ÆÂFö7VÖVçFVB2&V6öæ6–ÆR÷W&vRv÷&²Âæ÷BW†V7WF&ÆR÷Vâ6Æ÷G2"Â6ææ6öçF–ç2‚&W‡G&vÆÆWBÖ–çG2vW&RVæÖævVB–çfVçF÷'’öGW7B"’bb6ææ6öçF–ç2‚'&V6öæ6–ÆVB÷W&vVB"’bb6ææ6öçF–ç2‚&æ÷B÷VâW†V7WF&ÆR6Æ÷G2"’¢Ð   ¢FW7@¢gVâFSCSC&t6Æ÷6VEG&FU6æ6†÷DæWfW$Æö6·4Ö–åF‡&VB‚’°¢fÂ7F÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT†—7F÷'•7F÷&Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSC¢&r6Æ÷6VBG&FR6æ6†÷G2×W7BFWFV7BÖ–âF‡&VBæB&WGW&â66†VBFF"Â7F÷&Ræ6öçF–ç2‚%$uô4Äõ4TEô44„UôÕ2"’bb7F÷&Ræ6öçF–ç2‚$Æö÷W"æ×”Æö÷W"‚’ÓÒÆö÷W"ævWDÖ–äÆö÷W"‚’"’bb7F÷&Ræ6öçF–ç2‚%$uô4Äõ4TEõE$DU5ôÔ”åô44„Uõ$UEU$åóCSC"’¢76W'EG'VR‚%cRããCSC¢&r6Æ÷6VBG&FR&Vg&W6‚×W7B'VâöâG&FT†—7F÷'””òö&6¶w&÷VæBÂæ÷BVæFW"Ö–â×F‡&VB¦÷W&æÂÆö6²"Â7F÷&Ræ6öçF–ç2‚'66†VGVÆU&t6Æ÷6VEG&FW5&Vg&W6‚"’bb7F÷&Ræ6öçF–ç2‚&6ö×WFU&V6VçEfÆ–D6Æ÷6VEG&FW5&r"’bb7F÷&Ræ6öçF–ç2‚&–ô†æFÆW#òç÷7B‡"’ó¢F‡&VB‡"Â"²%Â%G&FU&t6Æ÷6VE&Vg&W6…Â""²"’ç7F'B‚’"’¢76W'EG'VR‚%cRããCSC¢F†R¦÷W&æÂÆö6²×W7BöæÇ’&RF¶Vâ–âF†R6ö×WFR†VÇW"gFW"Vç7W&T–æ—F–Æ—¦VB"Â7F÷&Ræ6öçF–ç2‚'&—fFRgVâ6ö×WFU&V6VçEfÆ–D6Æ÷6VEG&FW5&r"’bb7F÷&Ræ6öçF–ç2‚&Vç7W&T–æ—F–Æ—¦VB‚’"’bb7F÷&Ræ6öçF–ç2‚'7–æ6‡&öæ—¦VB†Æö6²’"’¢Ð   ¢FW7@¢gVâFSCSC%öÆ–7”†VG4öæÇ•G&–äE&V6÷&EG&FT6†ö¶Uö–çB‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSC#¢öÆ–7’†VG2×W7B7F–ÆÂG&–âBF†RvFVB&V6÷&EG&FR6†ö¶Rö–çB"ÂW†V2æ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂç&V6÷&D÷WF6öÖR†Ö–çDf÷$†VG3CSB"’bbW†V2æ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBç&V6÷&D÷WF6öÖR†Ö–çDf÷$†VG3CSB"’bbW†V2æ6öçF–ç2‚%Væ–f–VDW†—EöÆ–7”†VBç&V6÷&D÷WF6öÖR†Ö–çDf÷$†VG3CSB"’¢76W'EG'VR‚%cRããCSC#¢ÆVv7’W"öÆ—fR6VÆÂ6ÆÆ&6·2×W7B7W&W72F—&V7BöÆ–7’Ö†VBfæ÷WB"ÂW†V2æ6öçF–ç2‚%ôÄ”5•ô„TEôD•$T5EôdäõUEõ5U$U54TEóCSC""’bbW†V2æ6öçF–ç2‚&FòäõBG&–âf÷'v&D÷WF6öÖTÖöFVÂõVæ–f–VEöÆ–7”†VB"’¢76W'DWVÇ2‚%cRããCSC#¢æòF—&V7BG2æÖ–çBöÆ–7’Ö†VB&V6÷&D÷WF6öÖR6ÆÇ2Ö’7W'f—fR÷WG6–FR&V6÷&EG&FR"ÂÂ&VvW‚‚"""„f÷'v&D÷WF6öÖTÖöFVÇÅVæ–f–VEöÆ–7”†VGÅVæ–f–VDW†—EöÆ–7”†VB•Âç&V6÷&D÷WF6öÖUÂ‡G5ÂæÖ–çB"""’æf–æDÆÂ†W†V2’æ6÷VçB‚’¢Ð   ¢FW7@¢gVâFSCSCDvVçF–57G–ÆU&÷WFW%W6W4–ææW$ÆæU—f÷G4æ÷DÆæT§V×2‚’°¢fÂ&÷WFW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôvVçF–57G–ÆU&÷WFW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSCC¢F÷†–2×&Vv–ÖR—f÷F–ær×W7B&RFö7VÖVçFVB2–ææW"ÖÆæR7G&FVw’—f÷F–ær"Â&÷WFW"æ6öçF–ç2‚$”ääU"ÔÄäR•dõBDô5E$”äR"’bb&÷WFW"æ6öçF–ç2‚'6ÖRÆæRÂ"’bb&÷WFW"æ6öçF–ç2‚&F–ffW&VçBvfRöÖ6÷F7F–2ö6öæf—&ÖF–öâö†öÆB&öf–ÆR"’¢76W'EG'VR‚%cRããCSCC¢&–BF÷†–2&Vv–ÖR—f÷B×W7Bæ÷B&WVæBTÄ•E’ôD•õE$T5U%’ô$ÅTT4„•ÆæRW66W2"Â&÷WFW"æ6öçF–ç2‚'&—fFRgVâ&–EF÷†–5&Vv–ÖU—f÷B"’bb&÷WFW"æ6öçF–ç2‚'&WGW&âV×G”Æ—7B‚’"’bb&÷WFW"æ6öçF–ç2‚""&Æ—7Döb‚%TÄ•E’"Â$D•ô…TåDU""Â%E$T5U%’"Â$$ÅTT4„•"’"""’¢76W'EG'VR‚%cRããCSCC¢vV²×&Vv–ÖR7G–ÆRFV6—6–öç2×W7B&÷WFRF‡&÷Vv‚6ÖTÆæUvVµ—f÷E7G–ÆRv—F‚ÆæT†–çB"Â&÷WFW"æ6öçF–ç2‚'&—fFRgVâ6ÖTÆæUvVµ—f÷E7G–ÆR"’bb&÷WFW"æ6öçF–ç2‚'vV´6†÷7G–ÆU—f÷B†—BÂ6†VWBÂvV´6†÷6†VWBÂÆæT†–çB’"’bb&÷WFW"æ6öçF–ç2‚'6ÖTÆæUvVµ—f÷E7G–ÆR†ÆæT†–çBÂ7G–ÆRäDTdTå4•dUõ$ô$R’"’¢76W'EG'VR‚%cRããCSCC¢Ôôôå4„õBæB4„•D4ô”â×W7B—f÷B7G&FVw’–çFW&æÆÇ’Âæ÷BGV×ÆæW2"Â&÷WFW"æ6öçF–ç2‚"""$Ôôôå4„õB"Óâv†Vâ†fÆÆ&6²’"""’bb&÷WFW"æ6öçF–ç2‚%7G–ÆRå4Ô%EõtÄÄUEô4õ•ôdôÄÄõr"’bb&÷WFW"æ6öçF–ç2‚"""%4„•D4ô”â"Óâv†Vâ†fÆÆ&6²’"""’bb&÷WFW"æ6öçF–ç2‚%7G–ÆRådôÅTÔUô”tä•D”ôåõ44Å"’¢Ð   ¢FW7@¢gVâFSCSCTÆ—fU7G–ÆU—f÷E&÷WFW%&W6W'fW4ÆæT÷væW'6†—‚’°¢fÂ&÷WFW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU7G–ÆU—f÷E&÷WFW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSCS¢Æ—fU7G–ÆU—f÷E&÷WFW"×W7BFö7VÖVçB–ææW"ÖÆæRFö7G&–æR"Â&÷WFW"æ6öçF–ç2‚%cRããCSCR–ææW"ÖÆæRFö7G&–æR"’bb&÷WFW"æ6öçF–ç2‚&÷&–v–æÂÆæR÷vç2F†RÆW76öâ"’¢76W'EG'VR‚%cRããCSCS¢ÆVv7’&öÖ÷FUVÆ—G’×W7B&W6W'fRf–æÄÆæSÖÆæR"Â&÷WFW"æ6öçF–ç2‚&gVâ&öÖ÷FUVÆ—G’"’bb&÷WFW"æ6öçF–ç2‚&f–æÄÆæRÒÆæR"’bb&÷WFW"æ6öçF–ç2‚$”ääU%ôÄäUõ•dõC¢"’¢76W'DfÇ6R‚%cRããCSCS¢tD4…õ$ô$D”ôâ×W7Bæ÷B&WÆ6Rf–æÄÆæR"Â&÷WFW"æ6öçF–ç2‚""&f–æÄÆæRÒ%tD4…õ$ô$D”ôâ"""’¢76W'DfÇ6R‚%cRããCSCS¢4„•D4ô”âÆ—fR&ÆVVB×W7B&RÖVGV6FRÂæ÷BV&çF–æRÖF—6&ÆR"Â&÷WFW"æ6öçF–ç2‚%4„•D4ô”åôÄ•dUô$ÄTTEõT$åD”äR"’¢76W'EG'VR‚%cRããCSCS¢7&÷72ÖÆæRVÆ—G’F&vWG2×W7B&V6öÖRÆæRÖÆö6Â7G–ÆW2"Â&÷WFW"æ6öçF–ç2‚$Ôôôå4„õEõ4Ô%EõtÄÄUEô4ôäd•$ÔTB"’bb&÷WFW"æ6öçF–ç2‚%4„•D4ô”åõdôÅTÔUô”tä•D”ôåô4ôäd•$ÔTB"’bb&÷WFW"æ6öçF–ç2‚$ÄäUôÄô4Åõ$õUDUôÄ•ô„ôÄDU%õ%Tuô$4•5õ$ôôb"’¢Ð   ¢FW7@¢gVâFSCSCdÆæUF÷†–6—G”wV&E&VVGV6FW4–ç7FVDöd6†ö÷6–ætÇFW&æFTÆæR‚’°¢fÂwV&BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæUF÷†–6—G”wV&Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSCc¢F÷†–6—G’wV&B×W7BFö7VÖVçB–ææW"ÖÆæR&RÖVGV6F–öâ"ÂwV&Bæ6öçF–ç2‚%cRããCSCb–ææW"ÖÆæR&RÖVGV6F–öâFö7G&–æR"’bbwV&Bæ6öçF–ç2‚&æWfW"6†ö÷6W2F–ffW&VçBÆæR2âW66R"’¢76W'EG'VR‚%cRããCSCc¢wV&B×W7BW‡÷6RÆæRÖÆö6ÂG&VFÖVçDf÷""ÂwV&Bæ6öçF–ç2‚&VçVÒ6Æ72G&VFÖVçB"’bbwV&Bæ6öçF–ç2‚&gVâG&VFÖVçDf÷""’bbwV&Bæ6öçF–ç2‚%$TTET4DUõD5D”2"’bbwV&Bæ6öçF–ç2‚%$TTET4DUô4ôäd•$ÔD”ôâ"’¢76W'EG'VR‚%cRããCSCc¢6†ö÷6TæöåF÷†–4ÆæR×W7B&W6W'fR÷&–v–æÂÆæR÷&FW""ÂwV&Bæ6öçF–ç2‚'&WGW&âÆæW2æf—'7D÷$çVÆÂ²—Bæ—4æ÷D&Ææ²‚’Ò"’bbwV&Bæ6öçF–ç2‚&6ö×F–&–Æ—G’6†–ÒöæÇ’"’¢76W'EG'VR‚%cRããCSCc¢f–ÇFW$æöåF÷†–2×W7Bæ÷B×WFFRF÷†–2ÆæW2"ÂwV&Bæ6öçF–ç2‚%&W6W'fR÷&–v–æÂÆæR÷væW'6†—"’bbwV&Bæ6öçF–ç2‚&ÆæW2æf–ÇFW"²—Bæ—4æ÷D&Ææ²‚’ÒæF—7F–æ7B‚’"’¢76W'DfÇ6R‚%cRããCSCc¢wV&B×W7Bæ÷B&VfW"TÄ•E’ôD•õE$T5U%’W66RÆæW2"ÂwV&Bæ6öçF–ç2‚""&Æ—7Döb‚%TÄ•E’"Â$D•ô…TåDU""Â%E$T5U%’"Â$$ÅTT4„•"’"""’ÇÂwV&Bæ6öçF–ç2‚'&Vbæ–æFW„öb"’¢Ð   ¢FW7@¢gVâFSCSCt&÷E6W'f–6U&VVGV6FW5&–Ö'”–ç7FVDöe&WÆ6–ætÆæR‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSCs¢F÷†–2&–Ö'’×W7B7F×&RÖVGV6F–öâÂæ÷B—f÷BFòæ÷F†W"ÆæR"Â&÷Bæ6öçF–ç2‚%$”Ô%•õ5E$DTu•õ$TTET4DUô”ääU%ôÄäUóCSCr"’bb&÷Bæ6öçF–ç2‚&æõöÆæUö§V××G'VR"’bb&÷Bæ6öçF–ç2‚&VÆV7FVE&–Ö'“CS#B"’¢76W'DfÇ6R‚%cRããCSCs¢öÆBæöâ×F÷†–2&–Ö'’ÆæR§V×FVÆVÖWG'’×W7B&RvöæR"Â&÷Bæ6öçF–ç2‚%$”Ô%•õ5E$DTu•õ•dõEõDõôäôåDõ„”5óCS#B"’¢76W'EG'VR‚%cRããCSCs¢÷væW"&÷FF–öâ6öÖÖVçG2×W7B6’F÷†–6—G’G&VFÖVçBÂæ÷Bf–ÇFW&–ærö×WFF–öâ"Â&÷Bæ6öçF–ç2‚'F÷†–6—G’G&VFÖVçBÂæ÷BÆæR×WFF–öâ"’¢76W'EG'VR‚%cRããCSCs¢&÷E6W'f–6R×W7BW6RÆæUF÷†–6—G”wV&BçG&VFÖVçDf÷""Â&÷Bæ6öçF–ç2‚$ÆæUF÷†–6—G”wV&BçG&VFÖVçDf÷"†VÆV7FVE&–Ö'“CS#B"’¢Ð   ¢FW7@¢gVâFSCSC…7G&FVw”f–ÇW&W4&V6öÖT7W'&–7VÇVÔæ÷DÆæTF—6&ÆR‚’°¢fÂFö7G&–æRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô–ææW$ÆæU&VVGV6F–öäFö7G&–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSCƒ¢Fö7G&–æRÖ&¶W"×W7BF—7F–æwV—6‚7G&FVw’f–ÇW&Rg&öÒ†&B6fWG’"ÂFö7G&–æRæ6öçF–ç2‚%7G&FVw’Öf–ÇW&R'V6¶WG2×W7Bæ÷BF—6&ÆRö×WFFRÆæR"’bbFö7G&–æRæ6öçF–ç2‚%G'VR†&B6fWG’7F–ÆÂ†2WF†÷&—G’Fò&Æö6²"’¢76W'EG'VR‚%cRããCSCƒ¢7G&FVw’f–ÇW&W2×W7BÖFòÆæRÖÆö6Â7W'&–7VÇVÒ"ÂFö7G&–æRæ6öçF–ç2‚$f–ÇW&T¶–æBå5E$DTu•ôd”ÅU$R"’bbFö7G&–æRæ6öçF–ç2‚$7W'&–7VÇVÒåD5D”5õ5t•D4‚"’bbFö7G&–æRæ6öçF–ç2‚$7W'&–7VÇVÒä„ôÄEôU„•Eõ$UE$”â"’bbFö7G&–æRæ6öçF–ç2‚$7W'&–7VÇVÒäTåE%•ô4ôäd•$ÔD”ôâ"’¢76W'EG'VR‚%cRããCSCƒ¢öæÇ’†&B6fWG’Ö’F—6&ÆR"ÂFö7G&–æRæ6öçF–ç2‚&gVâÆÆ÷w4F—6&ÆR"’bbFö7G&–æRæ6öçF–ç2‚&¶–æBÓÒf–ÇW&T¶–æBä„$Eõ4dUE’"’¢Ð   ¢FW7@¢gVâFSCSSÆ—fT†VÆEFö¶Vç4&U7F–6·”ÖævVD7&÷75vF6†Æ—7DæE&W7F÷&R‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW'6—7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ÷6—F–öåW'6—7FVæ6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSS¢&÷E6W'f–6R×W7BW‡÷6RÆ—fR†VÆBöÖævVB7F–6·’wV&B"Â&÷Bæ6öçF–ç2‚$TåE%•ôUD„õ$•E•ô„TÄEõ5D”4µ•õ5DEU5ôuT$B"’bb&÷Bæ6öçF–ç2‚&gVâÆ—fT†VÆD÷$ÖævVDÖ–çB"’bb&÷Bæ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W"äõTåõ5DEU4U2"’¢76W'EG'VR‚%cRããCSS¢v†÷7BW&vR×W7Bæ÷B&VÖ÷fRÆ—fRÖ†VÆBÖ–çG2"Â&÷Bæ6öçF–ç2‚$TåE%•ôUD„õ$•E•ô„TÄEôt„õ5EõU$tUô$Äô4´TEóCSS"’bb&÷Bæ–æFW„öb‚&–b†Æ—fT†VÆD÷$ÖævVDÖ–çB†Ö–çB’’"’Â&÷Bæ–æFW„öb‚'7FGW2çFö¶Vç2ç&VÖ÷fR†Ö–çB’"’¢76W'EG'VR‚%cRããCSS¢6÷W&6R&V&Ææ6Röæò×—"Wf–7F–öâ×W7B&W6W'fRÆ—fRÖ†VÆBÖ–çG2&Vf÷&R&Vv—7G'’÷vF6†Æ—7BFVÖ÷F–öâ"Â&÷Bæ6öçF–ç2‚$TåE%•ôUD„õ$•E•ô„TÄEõ4õU$4Uõ$T$Ää4UôUd”5Eô$Äô4´TEóCSS"’bb&÷Bæ6öçF–ç2‚$TåE%•ôUD„õ$•E•ô„TÄEôäõõ•%ôDTÔõDUõ$TÔõdUô$Äô4´TEóCSS"’bb&÷Bæ–æFW„öb‚&–b†Æ—fT†VÆD÷$ÖævVDÖ–çB†Ö–çB’’"’Â&÷Bæ–æFW„öb‚""'&V6öâÒ$äõõ•%ôäõôdÄÄ$4µôtTB"""’¢76W'DfÇ6R‚%cRããCSS¢Æ—fR&W7F÷&R×W7Bæ÷B6¶—6öÆVÇ’&V6W6RF†RW'6—7FVB&÷r—2öÆFW"F†ârF—2"ÂW'6—7Bæ6öçF–ç2‚%5DÄRÆ—fR÷6—F–öâ"’bbW'6—7Bæ6öçF–ç2‚'6¶—–ær&W7F÷&R"’¢76W'EG'VR‚%cRããCSS¢7FÆRÖvVBÆ—fR&W7F÷&R×W7B&RFVÆVÖWG'’×&W6W'fVB"ÂW'6—7Bæ6öçF–ç2‚$Ä•dUõõ4•D”ôåõ$U5Dõ$UôtUõEDÅô%•55óCSS"’bbW'6—7Bæ6öçF–ç2‚'&W7F÷&Rç—v“²6VÆÂ÷¦W&òf–æÆ—G’÷vç2&VÖ÷fÂ"’¢Ð   ¢FW7@¢gVâFSCSS4Öæ—VÆFVE&—6´÷fW&Æ”—4Öæ—VÆFVDÆæTöæÇ’‚’°¢fÂ6fWG’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶Vå6fWG”6†V6¶W"æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSS3¢&—6²÷fW&Æ’'6W"×W7BFWFV7B6–ævÆRÖ†öÆFW"÷VçfW&–f–VBö†–v‚Ö†öÆFW"Ö6öæ6VçG&F–öâÖæ—VÆF–öâ"Â6fWG’æ6öçF–ç2‚'6–ævÆT†öÆFW$÷væW'6†—&—6²"’bb6fWG’æ6öçF–ç2‚'VçfW&–f–VEFö¶Vå&—6²"’bb6fWG’æ6öçF–ç2‚&†–v„†öÆFW$6öæ6VçG&F–öå&—6²"’¢76W'EG'VR‚%cRããCSS3¢Fö¶Vå6fWG”6†V6¶W"×W7B7F×Ôä•TÄDTEôôäÅ•ôõdU$Ä•óCSS2f÷"Æ—fRÖæ—VÆF–öâ÷fW&Æ—2"Â6fWG’æ6öçF–ç2‚$Ôä•TÄDTEôôäÅ•ôõdU$Ä•óCSS2"’bb6fWG’æ6öçF–ç2‚&7F–öãÖÖæ—VÆFVEöÆæUööæÇ’"’¢76W'EG'VR‚%cRããCSS3¢6†&VB&RÔdDrÆæRvFR×W7B&V¦V7BÖæ—VÆFVB÷fW&Æ—2g&öÒWfW'’æöâÔÔä•TÄDTBÆæR"Â&÷Bæ6öçF–ç2‚&Öæ—VÆFVDöæÇ”÷fW&Æ”7F—fSCSS2"’bb&÷Bæ6öçF–ç2‚$Ôä•TÄDTEôôäÅ•ôäôåôÔä•TÄDTEôÄäUõ$T¤T5DTEóCSS2"’bb&÷Bæ6öçF–ç2‚$Ôä•TÄDTEôôäÅ•ôõdU$Ä•ôäôåôÔä•TÄDTEôÄäUóCSS2"’¢76W'EG'VR‚%cRããCSS3¢Öæ—VÆFVBÖöæÇ’&V¦V7F–öâ×W7B†Vâ&Vf÷&RvV²t•BöGW7B×&ö&R÷fW'&–FR6âGW&â—B–çFòÆ—fR'W’"Â&÷Bæ–æFW„öb‚$Ôä•TÄDTEôôäÅ•ôäôåôÔä•TÄDTEôÄäUõ$T¤T5DTEóCSS2"’Â&÷Bæ–æFW„öb‚$ÄäUõt•EôõdU%$”DUôEU5Eõ$ô$R"’¢Ð   ¢FW7@¢gVâFSCSSe'VçF–ÖT6æöæ–6Ä÷VäFöW4æ÷D6÷VçE&tÆ–fV7–6ÆTv†÷7E&÷w2‚’°¢fÂ6æÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ'VçF–ÖU7FFU6æ6†÷Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSSc¢'VçF–ÖU7FFU6æ6†÷B×W7Bæ÷BW6R&rFö¶VäÆ–fV7–6ÆUG&6¶W"æ÷Vä6÷VçB26æöæ–6ÂÆ—fRÖ÷VâG'WF‚"Â6ææ6öçF–ç2‚'fÂÆ–fV7–6ÆT÷VâÒG'’²Fö¶VäÆ–fV7–6ÆUG&6¶W"æÆ—fTÖVÖT÷Vä6÷VçB‚’"’bb6ææ6öçF–ç2‚'fÂÆ–fV7–6ÆT÷VâÒG'’²Fö¶VäÆ–fV7–6ÆUG&6¶W"æ÷Vä6÷VçB‚’"’¢76W'EG'VR‚%cRããCSSc¢7FÆRÆ–fV7–6ÆR&÷w2&VÖ–â6ÆVçW÷&V6öæ6–ÆR–çWG2Âæ÷B'VçF–ÖRÖævVB6Æ÷G2"Â6ææ6öçF–ç2‚&6ÆVçW÷&V6öæ6–ÆR–çWG2VÇ6Wv†W&RÂæWfW"Æ—fRÖ÷VâG'WF‚†W&R"’bb6ææ6öçF–ç2‚%E$4´U%ôõTåôDU5”ä5ô5$•D”4Â"’¢Ð   ¢FW7@¢gVâFSCSStF—7F6†&ÆT6öçG&–'WF–öäfÆÆ&6µv¶W4F÷&ÖçEG&FW$ÆæW2‚’°¢fÂFö7G&–æRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fTw&÷wF„Fö7G&–æRæ·B"’ç&VEFW‡B‚¢fÂ&÷WFW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôvVçF–57G–ÆU&÷WFW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSSs¢6öçG&–'WF–öâfÆÆ&6²×W7B&R&W7G&–7FVBFòÆæW2&÷E6W'f–6R7GVÆÇ’F—7F6†W2"ÂFö7G&–æRæ6öçF–ç2‚&F—7F6†&ÆT6öçG&–'WF–öäÆæW2"’bbFö7G&–æRæ6öçF–ç2‚$44„tTâ"’bbFö7G&–æRæ6öçF–ç2‚%E$T5U%’"’bbFö7G&–æRæ6öçF–ç2‚$$ÅTT4„•"’bbFö7G&–æRç7V'7G&–ætgFW"‚&F—7F6†&ÆT6öçG&–'WF–öäÆæW2"’ç7V'7G&–æt&Vf÷&R‚&gVâw&÷wF„ÆæTfÆÆ&6²"’æ6öçF–ç2‚%t„ÄUôdôÄÄõr"’¢76W'EG'VR‚%cRããCSSs¢&÷WFW"×W7B¶VW&÷VæFVBfæ÷WBv†–ÆRf÷&6–æröæRF÷&ÖçB&VÂÖÆæRÇFW&æFRv†Vâ'6VçB"Â&÷WFW"æ6öçF–ç2‚&f÷&6T6öçG&–'WF–öäfÆÆ&6³CSSr"’bb&÷WFW"æ6öçF–ç2‚&VÇ6R–b†ÇFW&æFW2æ—4æ÷DV×G’‚’’"’bb&÷WFW"æ6öçF–ç2‚&w&÷wF„fÆÆ&6´ÆæSCSSsòæÆWB²÷WB³Ò—BÒ"’¢Ð   ¢FW7@¢gVâFSCSS•&–F—VÔæWuööÅW6W5V×÷'FÄWFô&Vf÷&T§W—FW%V÷FTW††W7F–öâ‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSS“¢&–F—VÒæWr×ööÂF—66÷fW'’×W7Bæ÷B6¶—V×÷'FÂWFòæBvò7G&–v‡BFò§W—FW""ÂW†V2æ6öçF–ç2‚'V×÷'FÄWFôVÆ–v–&ÆSCSS’"’bbW†V2æ6öçF–ç2‚'7&5WW$f÷%&÷WFRæ6öçF–ç2"’bbW†V2æ6öçF–ç2‚%$”D•TÕôäUuõôôÂ"’bbW†V2æ6öçF–ç2‚%TõDUôU„„U5DTB"’¢76W'EG'VR‚%cRããCSS“¢öæÇ’FVWVç7W÷'FVBÔ×2&VÖ–â§W—FW"Öf—'7B"ÂW†V2æ6öçF–ç2‚&FVWVç7W÷'FVDÖÓCSS’"’bbW†V2æ6öçF–ç2‚$ÔUDTõ$"’bbW†V2æ6öçF–ç2‚$õ$4"’bbW†V2æ6öçF–ç2‚&FVWöÖÕ÷W6Uö§W—FW%öf—'7B"’¢Ð   ¢FW7@¢gVâFSCScgVÆÅ6öÆæ6÷W&6T'&VGF„—5&W7F÷&VEv—F†÷WD†÷EF„fWF6‚‚’°¢fÂ66ææW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6öÆæÖ&¶WE66ææW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSc¢66ææW"×W7B&W7F÷&RgVÆÂ6öÆæ6÷W&6R'&VGF‚"Â66ææW"æ6öçF–ç2‚$eTÄÂ4ôÄä4õU$4R%$TED‚$U5Dõ$R"’bb66ææW"æ6öçF–ç2‚%44åôeTÄÅõ4ôÄäõ4õU$4Uô%$TED…óCSc"’¢76W'EG'VR‚%cRããcs¢66ææW"&F6‚×W7B7F'BF†RgVÆÂ6÷W&6R6WB–ç7FVBöbÆWGF–ærV×õ&–F—VÒÖöæ÷öÆ—¦RW&Ö—G2"Â66ææW"æ6öçF–ç2‚'6÷W&6RÖ'&VGF‚f—&æW72"’bb66ææW"æ6öçF–ç2‚'&WVW7FVEW&Ö—G3cr"’bb66ææW"æ6öçF–ç2‚&&Ææ6VD7F—fScr"’bb66ææW"æ6öçF–ç2‚'6÷W&6W2vW&R6æ6VÆÆVB&Vf÷&RF†W’6÷VÆBFÖ—B6æF–FFW2"’¢76W'EG'VR‚%cRããCSc¢FW…67&VVæW"V&Æ–26öÆæ7W&f6W2×W7B'VâWfW'’7–6ÆRÂæ÷BöæRVæGö–çBW"f÷W"7–6ÆW2"Â66ææW"æ6öçF–ç2‚'66äFW„&ö÷7FVB"’bb66ææW"æ6öçF–ç2‚'66äFW…G&VæF–ær"’bb66ææW"æ6öçF–ç2‚'66äFW„v–æW'2"’bb66ææW"æ6öçF–ç2‚'66åF÷föÇVÖUFö¶Vç2"’bb66ææW"æ6öçF–ç2‚$FW…67&VVæW"V&Æ–26öÆæ7W&f6W2WfW'’7–6ÆR"’¢76W'EG'VR‚%cRããCSc¢vV6¶òôÖWFV÷&ô6ö–ävV6¶ògVÆÂÖæWGv÷&²fVVFW'2×W7B&R&RÖVæ&ÆVB&V†–æBF†RW†—7F–ær'VFvWB"Â66ææW"æ6öçF–ç2‚'66ävV6¶õG&VæF–æuööÇ2"’bb66ææW"æ6öçF–ç2‚'66ävV6¶õF÷ööÇ4'•föÇVÖR"’bb66ææW"æ6öçF–ç2‚'66äÖWFV÷&ööÇ5f–vV6¶ò"’bb66ææW"æ6öçF–ç2‚'66ä6ö–ävV6¶ôW7F&Æ—6†VB"’bb66ææW"æ6öçF–ç2‚&vV6¶ô'VFvWFVC×G'VR"’¢76W'DfÇ6R‚%cRããCSc¢7FÆR6÷W&6RÖ×WFF–öâ6öÖÖVçB×W7Bæ÷B7W'f—fR"Â66ææW"æ6öçF–ç2‚$D•4$ÄTC¢66ävV6¶õG&VæF–æuööÇ2"’¢Ð   ¢FW7@¢gVâFSCSc&V6÷&EG&FU7W&W76W5FW&Ö–æÅ6VÆÄgFW$Ö–çD6Æ÷6TÆVFvW$f–æÆ—G’‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSc¢&V6÷&EG&FR×W7B6öç7VÇBÖ–çBÖ¶W–VB÷6—F–öä6Æ÷6TÆVFvW"&Vf÷&RFW&Ö–æÂ4TÄÂ¦÷W&æÆ–ær"ÂW†V2æ6öçF–ç2‚%cRããCSc(	BÖ–çBÖf–æÆ—G’¦÷W&æÂ6†ö¶R"’bbW†V2æ6öçF–ç2‚%÷6—F–öä6Æ÷6TÆVFvW"æ6Æ÷6T–Döb‡G2æÖ–çB’"’bbW†V2æ6öçF–ç2‚'FW&Ö–æÅ6VÆÄ¦÷W&æÆVD6Æ÷6T–G3CSc"’¢76W'EG'VR‚%cRããCSc¢GWÆ–6FRFW&Ö–æÂ4TÄÂ&÷w2×W7B&R7W&W76VBgFW"F†Rf—'7B¦÷W&æÆVB6Æ÷6T–BÂæ÷B&Vf÷&RF†R&VÂ&÷r"ÂW†V2æ6öçF–ç2‚"FW&Ö–æÅ6VÆÄ¦÷W&æÆVD6Æ÷6T–G3CScæFB†W†—7F–æt6Æ÷6T–CCSc’"’bbW†V2æ6öçF–ç2‚%DU$Ô”äÅõ4TÄÅôEUÄ”4DUõ5U$U54TEô%•ô4Äõ4UôÄTDtU%óCSc"’¢76W'EG'VR‚%cRããCSc¢F6‚×W7B7V6–f–6ÆÇ’&÷FV7Bv–ç7BÆæRÖG&–gBGWÆ–6FR6Æ÷6W2"ÂW†V2æ6öçF–ç2‚%G&FT÷WF6öÖTÆVFvW""’bbW†V2æ6öçF–ç2‚&¶W—2–æ6ÇVFRÆæRöVçG'’–FVçF—G’"’bbW†V2æ6öçF–ç2‚'6ÖRÖ–çB6Æ÷6–ærGv–6RVæFW"F–ffW&VçBÆæRÆ&VÇ2"’bbW†V2æ6öçF–ç2‚$äõB7W&W72F†Rf—'7B&VÂ&÷r"’¢Ð   ¢FW7@¢gVâFSCSc$Æ—fU'F–Åv–ç4æ÷F–g”öæÇ”gFW$f–æÆ—G’‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSc#¢Æ—fR'F–ÂG&–vvW"×W7B&Râ&ÖVB6–væÂÂæ÷B&VÆ—¦VB×v–âæ÷F–f–6F–öâ"ÂW†V2æ6öçF–ç2‚%%D”Âd”äÄ•E’E%UD‚"’bbW†V2æ6öçF–ç2‚$Ä•dUõ%D”Åõ4”täÅô$ÔTEôäõEôd”äÅóCSc""’¢76W'EG'VR‚%cRããCSc#¢&RÖf–æÆ—G’Æ—fR'F–ÂF‚×W7BW‡Æ–6—FÇ’fö–Bv–âæ÷F–f–6F–öâVçF–Âf–æÆ—G’"ÂW†V2æ6öçF–ç2‚&æõ÷v–åöæ÷F–g•÷VçF–Åöf–æÆ—G’"’bbW†V2æ6öçF–ç2‚&v—F–ærÆ—fR&÷WFRöf–æÆ—G’"’¢76W'EG'VR‚%cRããCSc#¢&VÆ—¦VBÆ—fR'F–Âæ÷F–f–6F–öâ×W7B&VÖ–âgFW"6–væGW&Rö66÷VçF–ær"ÂW†V2æ6öçF–ç2‚/	ù+Æ—fR'F–Â6VÆÂ"’bbW†V2æ6öçF–ç2‚'6÷VæG3òçÆ”Ö–ÆW7FöæR†v–å7B’"’¢Ð   ¢FW7@¢gVâFSCSc4÷Vå÷6—F–öåV•6W&FW5Vç&VÆ—¦VDW7F–ÖFTg&öÕvÆÆWEG'WF‚‚’°¢fÂV’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ô7'—FôÇD7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSc3¢÷Vâ×÷6—F–öâF—7Æ’×W7BÆ&VÂäÂ2Vç&VÆ—¦VBW7F–ÖFRÂæ÷BvÆÆWBÖöæW’"ÂV’æ6öçF–ç2‚%T’tÄÄUBE%UD‚"’bbV’æ6öçF–ç2‚$÷VâäÂ—2Ö&²÷&÷WFRW7F–ÖFRöæÇ’"’¢fÂæÅ7D–çFW'öÆF–öãCSc2Ò&W7B"²"B"²'¶–b‡æÅ7BãÒ’ ¢fÂæÅ6öÄ–çFW'öÆF–öãCSc2Ò'Vç&VÆ—¦VB"²"B"²'¶–b‡æÅ6öÂãÒ’ ¢76W'EG'VR‚%cRããCSc3¢w&VVâ÷Vâ×÷6—F–öâÖ&²v–ç2×W7Bæ÷B&RF—7Æ–VB2&VÆ—¦VB&öf—B"ÂV’æ6öçF–ç2‡æÅ7D–çFW'öÆF–öãCSc2’bbV’æ6öçF–ç2‡æÅ6öÄ–çFW'öÆF–öãCSc2’¢76W'EG'VR‚%cRããCSc3¢ÖVÖR÷Vâ×÷6—F–öâ6&G2×W7BW‡Æ–6—FÇ’6’Ö&²W7F–ÖFW2&Ræ÷BvÆÆWB×&VÆ—¦VB"ÂV’æ6öçF–ç2‚$Ö&²W7F–ÖFRöæÇ’(	Bæ÷BvÆÆWB×&VÆ—¦VBVçF–Â6VÆÂf–æÆ—G’"’bbV’æ6öçF–ç2‚'Vç&VÆ—¦VBR²ãFn)xâ"’¢Ð   ¢FW7@¢gVâFSCScE&ö&F–öåvF6†Æ—7E6÷'D—4öfdÖ–åF‡&VB‚’°¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCScC¢vF6†Æ—7DÖöFVÂ×W7B6''’&ö&F–öâ†÷B6Æ–6Rg&öÒF†R&6¶w&÷VæB&V6ö×WFR"ÂÖ–âæ6öçF–ç2‚'&ö&F–öåf—6–&ÆR"’bbÖ–âæ6öçF–ç2‚'&ö&F–öåF÷FÂ"’bbÖ–âæ6öçF–ç2‚%cRããCScB(	B&ö&F–öâ×W7B&R6÷'FVBö6VBöfbÖ–â"’¢76W'EG'VR‚%cRããCScC¢&ö&F–öâVçG&–W2×W7B&RfWF6†VB÷6÷'FVB–â&V6ö×WFTÖ–å&VæFW$ÖöFVÄ7–æ2Âæ÷B&VæFW%vF6†Æ—7B"ÂÖ–âæ6öçF–ç2‚'VÆÂ÷6÷'Bö6&ö&F–öâöâF—7F6†W'2äFVfVÇB"’bbÖ–âæ6öçF–ç2‚'&ö&F–öåf—6–&ÆSCScB"’¢76W'EG'VR‚%cRããCScC¢&VæFW%vF6†Æ—7B×W7B6öç7VÖRF†R66†VB&ö&F–öâ6æ6†÷B"ÂÖ–âæ6öçF–ç2‚'W6RF†RöfbÖÖ–â&ö&F–öâ6æ6†÷B"’bbÖ–âæ6öçF–ç2‚'fÂ&ö&F–öåf—6–&ÆTÖöFVÂÒvÄÖöFVÂç&ö&F–öåf—6–&ÆR"’bbÖ–âæ6öçF–ç2‚'fÂ&ö&F–öäVçG&–W56—¦RÒvÄÖöFVÂç&ö&F–öåF÷FÂ"’¢Ð   ¢FW7@¢gVâFSCScd÷VäÖævVEFö¶Vç46ææ÷D&U'F—F–öæVD–çFô–FÆU7W&f6R‚’°¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCScc¢÷Vâ÷VæF–ær÷G’Ö†VÆBFö¶Vç2×W7B&RÖævVÖVçBö&Æ–vF–öç2&Vf÷&R66ææW"F—7Æ’6Æ76–f–6F–öâ"ÂÖ–âæ6öçF–ç2‚$õTâõ4•D”ôâ5U$d4R$U4U%dD”ôâ"’bbÖ–âæ6öçF–ç2‚&÷Vä÷$ÖævVCCScb"’¢76W'EG'VR‚%cRããCScc¢7F—fR7W&f6R×W7B&V6V—fR÷VâÖævVBFö¶Vç2&Vf÷&R6†F÷r÷6fWG’–FÆR6Æ76–f–6F–öâ"ÂÖ–âæ6öçF–ç2‚&–b†÷Vä÷$ÖævVCCScb’7F—fUFö¶Vç2æFB‡G2’"’bbÖ–âæ6öçF–ç2‚&VÇ6R–b‡†6R–â6†F÷u†6W2ÇÂG2ç6fWG’æ—4&Æö6¶VB’–FÆUFö¶Vç2æFB‡G2’"’¢76W'EG'VR‚%cRããCScc¢&W6W'fF–öâ×W7B–æ6ÇVFRVæF–ærfW&–g’æB÷6—F—fRFö¶VâVçF—G’Âæ÷B§W7B—4÷Vâ"ÂÖ–âæ6öçF–ç2‚'G2ç÷6—F–öâçVæF–æufW&–g’"’bbÖ–âæ6öçF–ç2‚'G2ç÷6—F–öâçG•Fö¶Vââã"’¢Ð   ¢FW7@¢gVâFSCScu7F'GW†VÆEvÆÆWE6æ6†÷E&W7F÷&W4÷VäWF†÷&—G”&Vf÷&U7FGW46†V6²‚’°¢fÂG&6¶W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô†÷7EvÆÆWEFö¶VåG&6¶W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCScs¢7F'GWvÆÆWBÖ†VÆB6æ6†÷B×W7Bw&—FR„TÄBWF†÷&—G’&Vf÷&R&W7F÷&–ær7FGW2"ÂG&6¶W"æ6öçF–ç2‚%5D%EU„TÄBUD„õ$•E’$U•""’bbG&6¶W"æ6öçF–ç2‚%5D%EUõtÄÄUEõ4ä4„õEóCScr"’bbG&6¶W"æ6öçF–ç2‚%vÆÆWDWF†÷&—G•6æ6†÷Bä„TÄB"’¢76W'EG'VR‚%cRããCScs¢vVçV–æVÇ’†VÆB7F'GWFö¶Vç2×W7B&W7F÷&R÷VâÖævVÖVçBÂæ÷B&V6öÖR7FÆR×Vç&÷fVâ"ÂG&6¶W"æ6öçF–ç2‚'ç7FGW2Ò÷6—F–öå7FGW2äõTåõ$U5Dõ$TB"’bbG&6¶W"æ6öçF–ç2‚%5D%EUô„TÄEôUD„õ$•E•õ$U5Dõ$TEóCScr"’¢76W'EG'VR‚%cRããCScs¢7F'GW†VÆB&W7F÷&R×W7B6ÆV"¦W&òÖ6öæf—&ÒFW&Ö–æÂ7FFR"ÂG&6¶W"æ6öçF–ç2‚'æ6öç6V7WF—fU¦W&ô6öæf—&×2Ò"’bbG&6¶W"æ6öçF–ç2‚'ç¦W&ô&Ææ6T6öæf—&ÖVD'•Gvõ&÷f–FW'2ÒfÇ6R"’¢Ð   ¢FW7@¢gVâFSCSc„Æ—fU&ö&&–Æ—G•F÷†–4ÆæW5—f÷D–ç7FVDöd†&E7F÷÷$GW7E6—¦R‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCScƒ¢ETÕF÷†–2ÆæR'&æ6‚×W7B—f÷B–ç6–FRÆæRæB6öçF–çVRÂæ÷B&WGW&âfÇ6R"ÂW†V2æ6öçF–ç2‚$”ääU"ÔÄäR5E$DTu’•dõBÂäõB„$B5Dõ"’bbW†V2æ6öçF–ç2‚%$Tt”ÔUô”ääU%ôÄäUõ•dõEóCSc‚"’bbW†V2æ6öçF–ç2‚&7F–öãÖ6öçF–çVUöFVfVç6—fU÷7G–ÆR"’¢76W'DfÇ6R‚%cRããCScƒ¢öÆBETÕF÷†–2'&æ6‚×W7Bæ÷BFVfW"÷&W66÷&RæB&WGW&âfÇ6Rg&öÒÆ—fT'W’"ÂW†V2æ6öçF–ç2‚%$Tt”ÔUõ•dõEõ$T54U52"’bbW†V2æ6öçF–ç2‚'&W66÷&U÷FõöÖ–7&õö÷%÷vF6‚"’¢76W'EG'VR‚%cRããCScƒ¢Æ—fRÆV&æVB6—¦–ærfÆö÷"×W7B&VÖ–âW†V7WF&ÆRæBæ÷BGW7B×6—¦RF†R&ÆVVFW"'&æ6‚Fòã‚"ÂW†V2æ6öçF–ç2‚&W†V7WF&ÆRFVfVç6—fR×—f÷BfÆö÷""’bbW†V2æ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’æ—4Æ—fR‚’bb†ÆæTWd×VÇBÂãSÇÂÆæU6—¦T6ÂãS’Óâã3R"’¢Ð   ¢FW7@¢gVâFSCSs÷Vå÷6—F–öç5æVÅW6W4†÷7EG'WF„æ÷E7–çF†WF–4ÆæT66†R‚’°¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSs¢Æ—fR÷Vâ÷6—F–öç2æVÂ×W7BvFR&÷w2F‡&÷Vv‚†÷7EvÆÆWEFö¶VåG&6¶W"6ö÷VâG'WF‚æB÷6—F–öä6Æ÷6TÆVFvW""ÂÖ–âæ6öçF–ç2‚%$TÂõTâÕõ4•D”ôâäTÂE%UD‚"’bbÖ–âæ6öçF–ç2‚&Æ—fT÷VåæVÅG'WFƒCSs"’bbÖ–âæ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W"æ—466÷VçF&ÆR"’bbÖ–âæ6öçF–ç2‚%÷6—F–öä6Æ÷6TÆVFvW"æ—46Æ÷6VB"’¢76W'EG'VR‚%cRããCSs¢&6R7FFRæ÷Vå÷6—F–öç2×W7B&Rf–ÇFW&VB'’Æ—fR†÷7BG'WF‚–âÄ•dRÖöFR"ÂÖ–âæ6öçF–ç2‚"æf–ÇFW"²—5W$ÖöFRÇÂÆ—fT÷VåæVÅG'WFƒCSs†—BæÖ–çB’Ò"’¢76W'EG'VR‚%cRããCSs¢7–çF†W6—¦VB7V"×G&FW"&÷w2×W7Bæ÷B&W7W'&V7B6öÆBö6Æ÷6VBÆ—fR÷6—F–öç2"ÂÖ–âæ6öçF–ç2‚$õTåõäTÅõ5”åD…õ5DÄUõ4´•TEóCSs"’bbÖ–âæ6öçF–ç2‚&–b‚—5W"bbÆ—fT÷VåæVÅG'WFƒCSs†Ö–çB’’"’¢76W'EG'VR‚%cRããCSs¢Æ—fRæVÂ×W7Bæ÷B6†÷rW"ÖÖöFR7–çF†WF–2&÷w2v†Vâ'VçF–ÖR—2Æ—fR"ÂÖ–âæ6öçF–ç2‚&–b†—5W"Ò—5W$ÖöFR’&WGW&â"’¢Ð   ¢FW7@¢gVâFSCSs$Æ—fU&ö&&–Æ—G•&–E&VVGV6F–öäFöW4æ÷D&ö÷G7G&÷%¦W&õ6—¦R‚’°¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU&ö&&–Æ—G”Væv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSs#¢Æ—fR&ö&&–Æ—G’×W7BW6R&–B&RÖVGV6F–öâ–ç7FVBöb–B&ö÷G7G&GV—F–öâ"Â7&2æ6öçF–ç2‚$Ä•dR$”B$RÔTET4D”ôâÂäõB”B$ôõE5E$ET•D”ôâ"’bb7&2æ6öçF–ç2‚&&EGvõG&FTUb"’bb7&2æ6öçF–ç2‚&æõöÆ—fUö&ö÷G7G&÷GV—F–öã×G'VR"’¢76W'EG'VR‚%cRããCSs#¢F÷†–2Æ—fR'V6¶WG2×W7B—f÷B–ç6–FRÆæRv—F‚W†V7WF&ÆRfÆö÷"Âæ÷BÆV&æVB¦W&ò×6—¦R"Â7&2æ6öçF–ç2‚$TåE%•õ$ô$$”Ä•E•õ$”Eõ•dõEõ4„TEóCSs""’bb7&2æ6öçF–ç2‚&7F–öãÖÆæUöÆö6Å÷F7F–5÷—f÷B"’bb7&2æ6öçF–ç2‚'6—¦TfÆö÷#Óã3R"’bb7&2æ6öçF–ç2‚&6öW&6TDÆV7Bƒã3R’"’¢76W'DfÇ6R‚%cRããCSs#¢öÆBÆV&æVB†&B×7F÷FVÆVÖWG'’×W7Bæ÷B7W'f—fR"Â7&2æ6öçF–ç2‚$TåE%•õ$ô$$”Ä•E•ôÄäUô„$Eõ5DõTB"’ÇÂ7&2æ6öçF–ç2‚'6—¦^(i#ã"’¢76W'DfÇ6R‚%cRããCSs#¢Æ—fR&ö&&–Æ—G’7FGW2×W7Bæ÷B6Æ–ÒÆ—fR&ö÷G7G&öæòÖGW&RÆæW2"Â7&2æ6öçF–ç2‚&&ö÷G7G&öæòÖGW&RÆ—fRÆæW2"’¢Ð   ¢FW7@¢gVâFSCSs46öÖÖöå6Vç6UÆ–&öö´—5v—&VEF‡&÷Vv„Æ—fT'W•v—F„FF†VÇW"‚’°¢fÂÆ–&öö²Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô6öÖÖöå6Vç6UG&FUÆ–&öö²æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷$W†–Æ–'•7FGW4F–vW7Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSs3¢6öÖÖöâ×6Vç6RÆ–&öö²†VÇW"×W7BW†—7Bv—F‚66†VB6–FRÖVffV7B&Vg&W6‚"ÂÆ–&öö²æ6öçF–ç2‚%cRããCSs5ô4ôÔÔôåõ4Tå4UõÄ”$ôô²"’bbÆ–&öö²æ6öçF–ç2‚$F—7F6†W'2ç6–FTVffV7B"’bbÆ–&öö²æ6öçF–ç2‚$6öæ7W'&VçD†6„Ö"’bbÆ–&öö²æ6öçF–ç2‚&76W75&T'W’"’¢76W'EG'VR‚%cRããCSs3¢Æ–&öö²×W7BVæf÷&6RçF’×7GW–B†&BFF÷6fWG’&6–72"ÂÆ–&öö²æ6öçF–ç2‚%$”4Uô$4•5õTä´äõtâ"’bbÆ–&öö²æ6öçF–ç2‚%4TÄÅõ$õUDUõTä´äõtâ"’bbÆ–&öö²æ6öçF–ç2‚%Dô´TåôÔô”ä4ôÕÄUDR"’bbÆ–&öö²æ6öçF–ç2‚%4dUE•ôõ%ô„ôÄDU%õ$•4²"’¢76W'EG'VR‚%cRããCSsS¢Ö&–wV÷W27G'V7GW&Rõ%"×W7B6†R÷—f÷B–ç7FVBöb&ÇVçFÇ’6†ö¶–ær6WGW×&–6‚G&FW2"ÂÆ–&öö²æ6öçF–ç2‚$4ôÔÔôåõ4Tå4Uõ$T%U•õ4„TEóCSsR"’bbÆ–&öö²æ6öçF–ç2‚%$•4µõ$Ut$Eõ$TET4TEõ4•¤R"’bbÆ–&öö²æ6öçF–ç2‚$”ädU%ô”ådÄ”DD”ôåôe$ôÕõ4UEU"’bbÆ–&öö²æ6öçF–ç2‚'G&FU÷6WGW÷—f÷Eöæ÷Eö&Æö6²"’¢76W'EG'VR‚%cRããCSs3¢Æ—fT'W’×W7B6ÆÂF†RÆ–&öö²&Vf÷&RÆ—fR7VæB"ÂW†V7WF÷"æ6öçF–ç2‚$6öÖÖöå6Vç6UG&FUÆ–&öö²æ76W75&T'W’"’bbW†V7WF÷"æ6öçF–ç2‚$4ôÔÔôåõ4Tå4Uõ$T%U•ò"’bbW†V7WF÷"æ6öçF–ç2‚$%U•õDU$Ô”äÅô4ôÔÔôåõ4Tå4R"’¢76W'EG'VR‚%cRããCSs3¢Æ–&öö²6öæf–FVæ6R×W7BfVVBF†RW†—7F–ærÆ—fR6—¦R7F6²"ÂW†V7WF÷"æ6öçF–ç2‚&6öÖÖöå6Vç6U6—¦T×VÇF—Æ–W#CSs2"’bbW†V7WF÷"æ6öçF–ç2‚$4ôÔÔôåõ4Tå4Uõ4•¤UôÄ”TEóCSs2"’¢76W'EG'VR‚%cRããCSs3¢÷W&F÷"F–vW7B×W7B7W&f6RÆ–&öö²7FFR"ÂF–vW7Bæ6öçF–ç2‚$6öÖÖöå6Vç6UG&FUÆ–&öö²ç7FGW4Æ–æR"’bbF–vW7Bæ6öçF–ç2‚'Æ–&ööµöW†V7WF–öåöWF†÷&—G“ÔW†V7WF÷"æÆ—fT'W’"’¢fÂ&÷Cc#Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc#¢66÷&RfÆö÷'2×W7B&RfÇV–B66fföÆF–ærF†B6ögB×7F'G2ÂF–v‡FVç2ÂF†VâFVÆVvFW2Fòt’õ54’WF†÷&—G’"Â&÷Cc#æ6öçF–ç2‚$dÅT”Eõ44õ$Uõ44ddôÄEóc#"’bb&÷Cc#æ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBæ7W'&VçDWF†÷&—G’"’bb&÷Cc#æ6öçF–ç2‚$WF†÷&—G•F–W"ä$ôõE5E$Óâ‡7G'V7GW&ÄfÆö÷#c#Ò‚ã"’bb&÷Cc#æ6öçF–ç2‚$WF†÷&—G•F–W"äUD„õ$•DD•dRÓâã"’¢76W'EG'VR‚%cRããc#¢vööBÖVÖRÆæW2×W7BvWB&VÂföÇVÖR—f÷G2Âæ÷B&ö&RÖöæÇ’7F'fF–öâ"Â&÷Cc#æ6öçF–ç2‚$tôôEôÄäUõt•EõdôÅTÔUõ•dõEóc#"’bb&÷Cc#æ6öçF–ç2‚%TÄ•E’"’bb&÷Cc#æ6öçF–ç2‚$44„tTâ"’bb&÷Cc#æ6öçF–ç2‚%E$T5U%’"’bbÆ–&öö²æ6öçF–ç2‚$tôôEôÄäUôäõõ5E%T5EU$UõdôÅTÔUõ•dõEóc#"’bbÆ–&öö²æ6öçF–ç2‚&FævW&÷W57G'V7GW&R"’¢76W'EG'VR‚%cRããc#¢6öÖÖöå6Vç6R×W7B&VB66†VBt’÷FööÆ¶—B÷&W6V&6‚'&–â6öçFW‡B&Vf÷&R6Æ76–g––ær7G'V7GW&R"ÂÆ–&öö²æ6öçF–ç2‚%FööÆ¶—E6–væÅ6†VWBç6æ6†÷B"’bbÆ–&öö²æ6öçF–ç2‚%&W6V&6…66÷WBç&—6´†–çB"’bbÆ–&öö²æ6öçF–ç2‚%VÇF–ÖFTVFvTVæv–æRæ66†VB"’bbÆ–&öö²æ6öçF–ç2‚&'&–å6WGW"’bbÆ–&öö²æ6öçF–ç2‚&'&–ä6öæf–FVæ6R"’bbÆ–&öö²æ6öçF–ç2‚&'&–ä6öçFW‡B"’¢76W'EG'VR‚%cRããc#¢6öÖÖöå6Vç6R'&–â66W72×W7B&VÖ–â†÷B×F‚6fRæB7–æ2ö66†RÖf—'7B"ÂÆ–&öö²æ6öçF–ç2‚%VÇF–ÖFTVFvTVæv–æRæVçVWVU&Vg&W6‚"’bbÆ–&öö²æ6öçF–ç2‚&6öÖÖöå÷6Vç6Uö'&–åö6öçFW‡Eóc#"’bbÆ–&öö²æ6öçF–ç2‚&†÷B×F‚&÷f–FW"ôÄÄÒöæWGv÷&²6ÆÂ"’bbÆ–&öö²æ6öçF–ç2‚%VÇF–ÖFTVFvTVæv–æRæWfÇVFR‡G2’"’¢76W'EG'VR‚%cRããc##¢FööÆ¶—BvööBÖÆæR6WGW2×W7B'&–FvR&RÔdDrÆæR7F'fF–öâ–çFò&VÂdDr6æF–FFW2"Â&÷Cc#æ6öçF–ç2‚%FööÆ¶—DvööDÆæT'&–FvSc#""’bb&÷Cc#æ6öçF–ç2‚%DôôÄ´•EôtôôEôÄäUô%$”DtUóc#""’bb&÷Cc#æ6öçF–ç2‚%DôôÄ´•Eô%$”DtUòG²rBw×¶ÆæT¶W—Ò"’bb&÷Cc#æ6öçF–ç2‚&7F–öã×6VæE÷FõöfFr"’¢76W'EG'VR‚%cRããc##¢VÆ—G’&ÇVT6†—æBG&V7W'’×W7B6öç7VÖR'&–FvVB6–væÂ6÷–W2–ç7FVBöb&WV—&–ærÆVv7’6†÷VÆDVçFW""Â&÷Cc#æ6öçF–ç2‚'VÆ—G•6–væÃc#""’bb&÷Cc#æ6öçF–ç2‚&&ÇVT6†—6–væÃc#""’bb&÷Cc#æ6öçF–ç2‚'G&V7W'•6–væÃc#""’bb&÷Cc#æ6öçF–ç2‚'VÆ—G•6–væÂæ6÷’"’bb&÷Cc#æ6öçF–ç2‚&&ÇVT6†—6–væÂæ6÷’"’bb&÷Cc#æ6öçF–ç2‚'G&V7W'•6–væÂæ6÷’"’¢fÂÖ–ãc#BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããccCƒ¢Ö–âF6†&ö&B†VFÆ–æR×W7BW‡Æ–6—FÇ’W6RF†R÷'FföÆ–ò&öö²æBf–Â6Æ÷6VBv†Vâ66÷VçB&V6öæ6–Æ–F–öâ—2Væf–Æ&ÆR"ÂÖ–ãc#Bæ6öçF–ç2‚$FW6µW&f÷&Öæ6TWF†÷&—G“ccC‚ä&öö²åõ%DdôÄ”ò"’bbÖ–ãc#Bæ6öçF–ç2‚'Væ–f–VE6æcc3Sòç&VÆ—¦VEæÅ6öÂ"’bbÖ–ãc#Bæ6öçF–ç2‚$44õTåBTäd”Ä$ÄR"’bbÖ–ãc#Bæ6öçF–ç2‚%õ%DdôÄ”òu""’¢fÂfFsc#RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄFV6—6–öävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc#S¢dDr66÷&RvFW2×W7B6öç7VÖRVffV7F—fRÆæRôt’6öç6Vç7W266÷&Rg&öÒG&FR"ÂfFsc#Ræ6öçF–ç2‚&VffV7F—fTvFU66÷&Sc#R"’bbfFsc#Ræ6öçF–ç2‚$dDuôTddT5D•dUôtDUõ44õ$Uóc#R"’bbfFsc#Ræ6öçF–ç2‚'66÷&UövFW5÷W6Uö6öç6Vç7W5ög&öÕ÷G&FS"’bbfFsc#Ræ6öçF–ç2‚%Væ–f–VEöÆ–7”†VBæ7W'&VçDWF†÷&—G’†ÆæTæÖR’"’¢76W'EG'VR‚%cRããc#S¢dDrVæ¶æ÷vâ×†6RæBÆ—fRÖVFvRvFW2×W7BW6RVffV7F—fR66÷&Rv†–ÆRÆövv–ær&röÆæR7Æ—B"ÂfFsc#Ræ6öçF–ç2‚'fÂ—4†–v…66÷&RÒVffV7F—fTvFU66÷&Sc#RãÒÖ–å66÷&R"’bbfFsc#Ræ6öçF–ç2‚'fÂ†4FV6VçE66÷&RÒVffV7F—fTvFU66÷&Sc#RãÒÆ—fTÖ–äVçG'•66÷&R"’bbfFsc#Ræ6öçF–ç2‚'&sÒG²rBw×¶6æF–FFRæVçG'•66÷&RçFô–çB‚—ÒÆæSÒG²rBw×¶ÆæT6öç6Vç7W566÷&Sc#RçFô–çB‚—Ò"’¢76W'EG'VR‚%cRããc#S¢'&–ä6öç6Vç7W4vFR×W7BWfÇVFRF†RVffV7F—fRÖvFR6æF–FFRÂæ÷B7FÆR&rc266÷&R"ÂfFsc#Ræ6öçF–ç2‚&fFtvFT6æF–FFSc#R"’bbfFsc#Ræ6öçF–ç2‚$'&–ä6öç6Vç7W4vFRæWfÇVFR‡G2ÂfFtvFT6æF–FFSc#RÂÖöFUFr’"’¢fÂfFt'&–ãc#bÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôfFt'&–ä6†–âæ·B"’ç&VEFW‡B‚¢fÂW†V3c#bÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc#c¢dDr×W7B†fRâW‡Æ–6—B'&–âÖ6†–âw&VV&–Æ—G’Æ–W""ÂfFt'&–ãc#bæ6öçF–ç2‚&ö&¦V7BfFt'&–ä6†–â"’bbfFt'&–ãc#bæ6öçF–ç2‚&VçVÒ6Æ72fW&F–7B²Ä”täTBÂ4ôädÄ”5DTBÂ$Äô4´”ärÒ"’bbfFt'&–ãc#bæ6öçF–ç2‚&6öÖÖöå÷6Vç6R"’bbfFt'&–ãc#bæ6öçF–ç2‚&çF–6†ö¶U÷6ögFVæ–ær"’¢76W'EG'VR‚%cRããc#c¢dDr×W7B6ögFVâæöâÖ†&B&Æö6¶W'2v†VâF†R'&–â6†–âÆ–vç2"ÂfFsc#Ræ6öçF–ç2‚$fFt'&–ä6†–âæWfÇVFR"’bbfFsc#Ræ6öçF–ç2‚$dDuô%$”åô4„”åóc#b"’bbfFsc#Ræ6öçF–ç2‚$dDuô%$”åõ4ôeDTäTEô$Äô4µóc#b"’bbfFsc#Ræ6öçF–ç2‚'6ögD&Æö6µ&V6öãc#b"’bbfFsc#Ræ6öçF–ç2‚&&Æö6´ÆWfVÂÒ&Æö6´ÆWfVÂä„$B"’¢76W'EG'VR‚%cRããc#c¢6öÖÖöå6Vç6R&V'W’×W7B&V6öÖR6†–â×6†VB6WF–öâÂæ÷B&Æ–æBW†V7WF÷"ö'7G'V7F–öâ"ÂW†V3c#bæ6öçF–ç2‚$4ôÔÔôåõ4Tå4Uõ$T%U•õ4ôeDTäTEóc#b"’bbW†V3c#bæ6öçF–ç2‚&6öÖÖöå6Vç6T†&Cc#b"’bbW†V3c#bæ6öçF–ç2‚$fFt'&–ä6†–âæWfÇVFR"’bbW†V3c#bæ6öçF–ç2‚$dDuô%$”åô4ôÔÔôåõ4Tå4Uõ4ôeDTåóc#b"’¢76W'EG'VR‚%cRããc#s¢dDr'&–â6†–â×W7BG&—fR6—¦–ærö6ö×÷VæF–ærF&vWG2Âæ÷BöæÇ’&Æö6¶W"&V6öæ6–Æ–F–öâ"ÂfFt'&–ãc#bæ6öçF–ç2‚&6ö×÷VæF–æt×VÇF—Æ–W""’bbfFt'&–ãc#bæ6öçF–ç2‚'F&vWDÖöFR"’bbfFt'&–ãc#bæ6öçF–ç2‚$4ôÕõTäB"’bbfFt'&–ãc#bæ6öçF–ç2‚%TÄ•E•õdôÅTÔR"’bbfFsc#Ræ6öçF–ç2‚$dDuô%$”åô4ôÕõTäD”äuõD$tUEóc#r"’bbfFsc#Ræ6öçF–ç2‚&fFuö'&–åö6ö×÷VæF–ær"’¢fÂvÆÆWD6ö×c#‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&VÆ—¦VEvÆÆWD6ö×÷VæF–ætv÷fW&æ÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc#ƒ¢6ö×÷VæF–ærv÷fW&æ÷"×W7B–æ6ÇVFRG'W7FVB÷VâÆ—fRWV—G’&W77W&Rv—F†÷WBf¶–ær¦÷W&æÂäÂ"ÂvÆÆWD6ö×c#‚æ6öçF–ç2‚'G'W7FVD÷VäÆ—fTWV—G’"’bbvÆÆWD6ö×c#‚æ6öçF–ç2‚$÷VåæÅ6æ—G’æ–ç7V7B"’bbvÆÆWD6ö×c#‚æ6öçF–ç2‚'G'W7FVEö÷VåöWV—G•ö6ö×÷VæE÷&W77W&Uóc#‚"’bbvÆÆWD6ö×c#‚æ6öçF–ç2‚&÷VåG'W7FVB"’¢76W'EG'VR‚%cRããc#ƒ¢Æ—fR'VææW'2v—F‚vÆÆWBÖÖFW&–ÂVç&VÆ—¦VB&öf—B×W7B†'fW7B4ôÂF‡&÷Vv‚6VÆÂf–æÆ—G’"ÂW†V3c#bæ6öçF–ç2‚%tÄÄUEôu$õuD…ô„%dU5EõE$”ttU$TEóc#‚"’bbW†V3c#bæ6öçF–ç2‚&ÆæE÷'VææW%÷&öf—Eö–å÷vÆÆWB"’bbW†V3c#bæ6öçF–ç2‚&W†V7WFU&öf—DÆö6µ6VÆÂ‡G2ÂvÆÆWBÂ6VÆÄg&7F–öãc#‚"’bbW†V3c#bæ6öçF–ç2‚%tÄÄUEôu$õuD…ô„%dU5EôDTdU%$TEõ$”4UõTå$TÅóc#‚"’¢fÂ&VÅ&–6TÆö6³c#’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&VÅ&–6TÆö6²æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc#“¢&÷WFR×&VÂ&öf—B×W7B&R†'fW7F&ÆRWfVâv†VâT’6Æ–Ò—2÷fW'7FFVB"Â&VÅ&–6TÆö6³c#’æ6öçF–ç2‚'&÷WFT–×Æ–VDv–ä×VÇF—ÆR"’bbW†V3c#bæ6öçF–ç2‚%$õUDUõ$TÅô4Ä”ÕôÔ•4ÔD4…ô„%dU5Eóc#’"’bbW†V3c#bæ6öçF–ç2‚'V•ö6Æ–Õö÷fW'7FFVEö'WE÷&÷WFU÷&öf—E÷&VÂ"’bbW†V3c#bæ6öçF–ç2‚'&÷WFU÷&VÅö†'fW7Eò"’¢fÂÖ–åV“c#’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc#“¢÷Vâ×÷6—F–öâT’×W7BÆ&VÂVç&VÆ—¦VB&÷WFRG'WF‚–ç7FVBöb–×Ç––ærvÆÆWB×&VÂÖöæW’"ÂÖ–åV“c#’æ6öçF–ç2‚&Æ7E&÷WFUG'WF‚"’bbÖ–åV“c#’æ6öçF–ç2‚%Tå$TÄ•¤TB+r&÷WFRVæF–ær"’bbÖ–åV“c#’æ6öçF–ç2‚&6Æ–ÒÖÖ—6ÖF6‚"’bbÖ–åV“c#’æ6öçF–ç2‚%$õUDRâ"’¢fÂ&W÷'Cc#’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc#“¢Væ–f–VB&W÷'B×W7BG&6RF—66÷fW'’ö÷Vâ÷&÷WFR÷6VÆÂö¦÷W&æÂ÷vÆÆWBÖöæW’G'WF‚"Â&W÷'Cc#’æ6öçF–ç2‚$ÔôäU’D‚E%UD‚"’bb&W÷'Cc#’æ6öçF–ç2‚&'V–ÆDÖöæW•F…G'WF…7VÖÖ'“c#’"’bb&W÷'Cc#’æ6öçF–ç2‚&÷Vå÷Vç&VÆ—¦VEöæ÷E÷vÆÆWE÷VçF–Å÷6VÆÅöf–æÆ—G’"’bb&W÷'Cc#’æ6öçF–ç2‚'G'WF‚6öçG&7C¢D•44õdU%’&–6R÷6÷W&6RÓâ%U’VçG'’6æ6†÷BÓâõTâVç&VÆ—¦VB&6—2÷&÷WFRÆ&VÂÓâ4TÄÂf–æÆ—G’æWB4ôÂÓâ¤õU$äÂ7G&FVw’G'WF‚ÓâtÄÄUB&Ææ6R"’¢fÂ&VÆ†W#c3Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fTÆ–W$vFU&VÆ†W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3¢ÆæR×÷6—F—fRÆ—fRUb×W7B'—72vÆö&Âu"&VÆ†W"Æö6²v†–ÆR&ÆVVFW"ÆæW27F’Vç&VÆ†VB"Â&VÆ†W#c3æ6öçF–ç2‚&ÆæU÷6—F—fT66†R"’bb&VÆ†W#c3æ6öçF–ç2‚$ÄäRÕõ4•D•dRõdU%$”DR"’bb&VÆ†W#c3æ6öçF–ç2‚&–b†ÆæU÷6—F—fSc3’&WGW&â&6R"’bb&VÆ†W#c3æ6öçF–ç2‚$$ÅTT4„•"’bb&VÆ†W#c3æ6öçF–ç2‚'&W÷'Bc#‚6†÷w2$ÅTT4„•u##"’bb&VÆ†W#c3æ6öçF–ç2‚%TÄ•E’"’bb&VÆ†W#c3æ6öçF–ç2‚'&W÷'Bc#‚6†÷w2TÄ•E’u#C"’¢fÂ&÷Cc3Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3¢Æ—fR'VçF–ÖR×W7Bæ÷BFWVæBöâ67&VVâôÖ–ä7F—f—G’7F––ærÆ—fR"Â&÷Cc3æ6öçF–ç2‚&Vç7W&U'VçF–ÖUv¶TÆö6³c3"’bb&÷Cc3æ6öçF–ç2‚$Åt•5ôôåõt´TÄô4µõ$T54U%DTEóc3"’bb&÷Cc3æ6öçF–ç2‚&Vç7W&TÇv—4öå'VçF–ÖTwV&G3c3"’bb&÷Cc3æ6öçF–ç2‚$Åt•5ôôåõ%TåD”ÔUõ$U45TUóc3"’bb&÷Cc3æ6öçF–ç2‚&Vç7W&T†÷DW†—DÆ—fR‚’"’bb&÷Cc3æ6öçF–ç2‚&öä7&VFUögFW%÷7F'Df÷&Vw&÷VæB"’bb&÷Cc3æ6öçF–ç2‚&7F–öå÷7F'EöÇ&VG•÷'Vææ–ær"’¢fÂÖæ–fW7Cc3"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âôæG&ö–DÖæ–fW7Bç†ÖÂ"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3#¢Çv—2ÖöâÆ—fRG&F–ær×W7B†öÆBæWGv÷&²2vVÆÂ25Rv†–ÆR6W'f–6R—27F—fR"Â&÷Cc3æ6öçF–ç2‚&Vç7W&U'VçF–ÖUv–f”Æö6³c3""’bb&÷Cc3æ6öçF–ç2‚$Åt•5ôôåõt”d•ôÄô4µõ$T54U%DTEóc3""’bb&÷Cc3æ6öçF–ç2‚&Æ–fV7–6ÆV&÷C¦æWGv÷&³¦Çv—5ööåóc3""’bb&÷Cc3æ6öçF–ç2‚'v–f”Æö6³c3"ÒçVÆÂ"’bbÖæ–fW7Cc3"æ6öçF–ç2‚&æG&ö–BçW&Ö—76–öâä44U55õt”d•õ5DDR"’bbÖæ–fW7Cc3"æ6öçF–ç2‚&æG&ö–BçW&Ö—76–öâä4„ätUõt”d•õ5DDR"’¢fÂG&6¶W#c3BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô†÷7EvÆÆWEFö¶VåG&6¶W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3C¢G&÷VBvÆÆWBFö¶Vç2×W7B&V6÷fW"–çFòõTåõE$4´”är–ç7FVBöb&V–ær–væ÷&VB"ÂG&6¶W#c3Bæ6öçF–ç2‚%$T4õdU%ôõ%„åõtÄÄUEõDô´Tå3¢&ööÆVâÒG'VR"’bbG&6¶W#c3Bæ6öçF–ç2‚$õ%„åõtÄÄUEõDô´TåôED4„TB"’bbG&6¶W#c3Bæ6öçF–ç2‚$õ%„åõtÄÄUEõDô´TåôÔôä•Dõ$TEôdõ%ôU„•B"’bbG&6¶W#c3Bæ6öçF–ç2‚'6÷W&6RÒ÷6—F–öå6÷W&6RåtÄÄUEõ$T4ôä4”ÄTB"’¢fÂ6VÆÅ&V3c3RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷6VÆÂõ6VÆÅ&V6öæ6–ÆW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3S¢6VÆÅ&V6öæ6–ÆW"×W7BÇ’&rvÆÆWB6æ6†÷B&Vf÷&RG&6¶W"Ö†VÆBWFòÖ†VÂ"Â6VÆÅ&V3c3Ræ6öçF–ç2‚%4TÄÅõ$T4ôä4”ÄU%õtÄÄUEõ4ä4„õEôÄ”TEóc3R"’bb6VÆÅ&V3c3Ræ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W"æÇ•vÆÆWE6æ6†÷B‡Fö¶Vç2’"’bb6VÆÅ&V3c3Ræ–æFW„öb‚%4TÄÅõ$T4ôä4”ÄU%õtÄÄUEõ4ä4„õEôÄ”TEóc3R"’Â6VÆÅ&V3c3Ræ–æFW„öb‚$†÷7EvÆÆWEFö¶VåG&6¶W"ævWD7GVÆÇ”†VÆDÖ–çG2‚’"’bb6VÆÅ&V3c3Ræ6öçF–ç2‚%4TÄÅõ$T4ôä4”ÄU%ô„TÄEôUDô„TÅóc3R"’¢fÂ&VVçG'“c3bÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&TVçG'”Æö6¶÷WBæ·B"’ç&VEFW‡B‚¢fÂW†V4vFSc3bÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3c¢6ÖRÖÖ–çB&VVçG'’&VÖ–ç2†&B'WBfÖ–Ç’ÖöæÇ’Æö6¶÷WG26ögBÖÆÆ÷rF‡&÷Vv‡WB"Â&VVçG'“c3bæ6öçF–ç2‚&FF6Æ72Æö6´FV6—6–öâ"’bb&VVçG'“c3bæ6öçF–ç2‚'6ÖTÖ–çBÒG'VR"’bb&VVçG'“c3bæ6öçF–ç2‚&fÖ–Ç”öæÇ’ÒG'VR"’bbW†V4vFSc3bæ6öçF–ç2‚$U„T5ôõTåõ$TTåE%•ôdÔ”Å•õ4ôeEôÄÄõuóc3b"’bbW†V4vFSc3bæ6öçF–ç2‚&–b†Æö6´FV6—6–öâç6ÖTÖ–çB’"’bbW†V4vFSc3bæ6öçF–ç2‚$dDrö'&–â÷6fWG’6âFV6–FRF†RG&FR"’¢fÂ&–6UG'WFƒc3rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷VåæÅ6æ—G’æ·B"’ç&VEFW‡B‚¢fÂ&W÷'Cc3rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢fÂ—Sc3rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂf÷&Vç6–3c3rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöW†V7WF–öâôf÷&Vç6–5&W÷'DW‡÷'FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3s¢öæR6æöæ–6Â&–6–ærG'WF‚f÷"ÖöæW’Fƒ²æò&÷WFR×VæF–ær6–FRÖ6†ææVÂF÷væw&FR"Â&–6UG'WFƒc3ræ6öçF–ç2‚&FF6Æ72&–6–æuG'WF‚"’bb&–6UG'WFƒc3ræ6öçF–ç2‚&gVâ&–6–æuG'WF‚"’bb&W÷'Cc3ræ6öçF–ç2‚$÷VåæÅ6æ—G’ç&–6–æuG'WF‚"’bb&W÷'Cc3ræ6öçF–ç2‚'&÷WFSÖ6æöæ–6ÅöÖ&µ÷6÷W&6R"’bb&W÷'Cc3ræ6öçF–ç2‚'&÷WFU÷VæF–æu÷VçG'W7FVEóc3r"’bb&W÷'Cc3ræ6öçF–ç2‚'VæF–æuö÷WFÆ–W%öæ÷Eö6÷VçFVB"’¢76W'EG'VR‚%cRããc3s¢ÖöæW’F‚6VÆÂf–æÆ—G’×W7BW6RWfVçB6÷VçFW'2Âæ÷B7FÆRÆ&VÇ2"Â—Sc3ræ6öçF–ç2‚&gVâW†V4Æ—fU6VÆÄf–Ä6÷VçB‚’"’bb—Sc3ræ6öçF–ç2‚&gVâW†V4Æ—fU6VÆÅVæF–ætf–æÆ—G”6÷VçB‚’"’bb&W÷'Cc3ræ6öçF–ç2‚%—VÆ–æT†VÇF„6öÆÆV7F÷%öWfVçEö6÷VçFW'5óc3r"’bb&W÷'Cc3ræ6öçF–ç2‚%—VÆ–æT†VÇF„6öÆÆV7F÷"æW†V4Æ—fU6VÆÄö´6÷VçB‚’"’bb&W÷'Cc3ræ6öçF–ç2‚%—VÆ–æT†VÇF„6öÆÆV7F÷"æW†V4Æ—fU6VÆÅVæF–ætf–æÆ—G”6÷VçB‚’"’¢76W'EG'VR‚%cRããc3s¢f÷&Vç6–27VÖÖ'’×W7B6†÷rVffV7F—fR&V6öæ6–ÆW"G'WF‚7&÷72÷6—F–öâ÷6VÆÂöÆ—fR×vÆÆWB&V6öæ6–ÆW'2"Âf÷&Vç6–3c3ræ6öçF–ç2‚%&V6öã¢VffV7F—fSÒ"’bbf÷&Vç6–3c3ræ6öçF–ç2‚%6VÆÅ&V6öæ6–ÆW"çF÷FÄ6†V6¶VB"’bbf÷&Vç6–3c3ræ6öçF–ç2‚$Æ—fUvÆÆWE&V6öæ6–ÆW"çF÷FÄ6†V6¶VB‚’"’¢fÂ&÷Cc3‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW†V3c3‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÖ–ãc3‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂÖöFSc3‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖöFU7V6–f–4W†—G2æ·B"’ç&VEFW‡B‚¢fÂ&V6—6–öãc3‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&V6—6–öäW†—DÆöv–2æ·B"’ç&VEFW‡B‚¢fÂ7'—FôÇEV“c3‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ô7'—FôÇD7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3ƒ¢6÷W&6R&–6–ærõäÂ†÷BF‡2×W7BW6R÷VåæÅ6æ—G’Âæ÷BÆö6Â&rf÷&×VÆ2"Â&÷Cc3‚æ6öçF–ç2‚$&÷E6W'f–6Rç7F'GW÷7vVWóc3‚"’bb&÷Cc3‚æ6öçF–ç2‚$&÷E6W'f–6RæfÆÆ&6µö†&EöfÆö÷%óc3‚"’bbW†V3c3‚æ6öçF–ç2‚$W†V7WF÷"æ7F—fUö†&EöfÆö÷%óc3‚"’bbW†V3c3‚æ6öçF–ç2‚$W†V7WF÷"æ6Æ÷6UöÆVFvW%÷7F×óc3‚"’bbÖöFSc3‚æ6öçF–ç2‚$ÖöFU7V6–f–4W†—G2æWfÇVFUóc3‚"’bb&V6—6–öãc3‚æ6öçF–ç2‚%&V6—6–öäW†—DÆöv–2æWfÇVFUóc3‚"’¢76W'EG'VR‚%cRããc3ƒ¢Ö–ä7F—f—G’Æ–fV7–6ÆR÷Vâ×÷6—F–öâF—7Æ—2×W7B&÷WFRäÂF‡&÷Vv‚Ö–åV•æÅ7Cc3‚ô÷VåæÅ6æ—G’"ÂÖ–ãc3‚æ6öçF–ç2‚&gVâÖ–åV•æÅ7Cc3‚"’bbÖ–ãc3‚æ6öçF–ç2‚$÷VåæÅ6æ—G’æ–ç7V7B†VçG'•&–6RÂ‚"’bbÖ–ãc3‚æ6öçF–ç2‚&Öööç6†÷E÷&÷uóc3‚"’bbÖ–ãc3‚æ6öçF–ç2‚'VÆ—G•÷÷6—F–öåóc3‚"’bbÖ–ãc3‚æ6öçF–ç2‚'G&V7W'•÷÷6—F–öåóc3‚"’bbÖ–ãc3‚æ6öçF–ç2‚'6æ—W%öÖ—76–öåóc3‚"’bbÖ–ãc3‚æ6öçF–ç2‚&&ÇVV6†—÷÷6—F–öåóc3‚"’bbÖ–ãc3‚æ6öçF–ç2‚&W‡&W75÷&–FUóc3‚"’bbÖ–ãc3‚æ6öçF–ç2‚&Öæ—VÆFVE÷÷6—F–öåóc3‚"’bb7'—FôÇEV“c3‚æ6öçF–ç2‚$7'—FôÇD7F—f—G’çV”v–å7CCCs•óc3‚"’¢fÂf÷&Vç6–3c3’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöW†V7WF–öâôf÷&Vç6–5&W÷'DW‡÷'FW"æ·B"’ç&VEFW‡B‚¢fÂ66„vVãc3’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærô66„vVæW&F–öä’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3“¢÷Vâ÷6—F–öç2æVÂ×W7B6†÷rF÷†VÆB÷Vâ×÷6—F–öâ&÷w2æBÆ—7BF†R†VÆB&W7B–ç7FVBöbG&÷–ærFF"ÂÖ–ãc3‚æ6öçF–ç2‚'&—fFRfÂõTåõ5õ$õuô4¢–çBÒ"’bbÖ–ãc3‚æ6öçF–ç2‚&†–FFVä†VÆCc3’"’bbÖ–ãc3‚æ6öçF–ç2‚"²G²rBwÖ†–FFVä6÷VçB7F–ÆÂ†VÆBöÖævVB&VÆ÷rF÷G²rBwÕ$TäDU%ô4"’bbÖ–ãc3‚æ6öçF–ç2‚&†VÆDÖ–çG3c3’"’bbÖ–ãc3‚æ6öçF–ç2‚&÷&FW"†–vŽ(i&Æ÷r"’¢76W'EG'VR‚%cRããc3“¢f÷&Vç6–2ö÷W&F÷"Æöw2×W7B–æ6ÇVFRÆ—fRGVæ–ærFF"Âf÷&Vç6–3c3’æ6öçF–ç2‚'GVæ–æuóc3’"’bbf÷&Vç6–3c3’æ6öçF–ç2‚$Æ—fU7G&FVw•GVæW"ç7FGW4Æ–æR‚’"’bbf÷&Vç6–3c3’æ6öçF–ç2‚%GVæ–æs¢G²rBw×GVæ–æsc3’"’¢76W'EG'VR‚%cRããc3“¢66„vVæW&F–öä’c3‚6ö×–ÆRf—‚×W7BW6R÷2æÖ–çB–ç6–FR6†V6´W†—D–çFW&æÂ"Â66„vVãc3’æ6öçF–ç2‚$66„vVæW&F–öä•öW†—Eóc3‚òG²rBw×·÷2æÖ–çBçF¶Rƒ‚—Ò"’bb66„vVãc3’æ6öçF–ç2‚$66„vVæW&F–öä•÷6V6öæF'•óc3‚òG²rBw×·÷2æÖ–çBçF¶Rƒ‚—Ò"’bb66„vVãc3’æ6öçF–ç2‚$66„vVæW&F–öä•öW†—Eóc3‚òG²rBw×¶Ö–çBçF¶Rƒ‚—Ò"’¢76W'EG'VR‚%cRããcC¢†÷7B×G&6¶W"÷Vâ&÷w2×W7B7–çF†W6—¦R–çFò÷Vâ÷6—F–öç26òvÆÆWBÖ†VÆB&÷w26ææ÷B&Ææ²F†RæVÂ"ÂÖ–ãc3‚æ6öçF–ç2‚$õTåõäTÅô„õ5EõE$4´U%õ5”åD…ócC"’bbÖ–ãc3‚æ6öçF–ç2‚$†÷7EvÆÆWEFö¶VåG&6¶W"ævWD÷VåG&6¶VE÷6—F–öç2‚’"’bbÖ–ãc3‚æ6öçF–ç2‚$„õ5EõtÄÄUEõE$4´U%ócC"’¢76W'EG'VR‚%cRããcCócsƒ¢å"6†VB×W7B¶VW÷Vâ÷6—F–öç2&VæFW&–ærg&öÒF†R66†VBöfbÖÖ–âÖöFVÂæBöæÇ’6¶—æöâÖ÷Vâ†Vg’&÷w2"ÂÖ–ãc3‚æ6öçF–ç2‚&÷VäÖöFVÄGW&–æu6†VCcs‚"’bbÖ–ãc3‚æ6öçF–ç2‚'&VæFW$÷Vå÷6—F–öç2†÷Vå÷4GW&–æu6†VCcCÂ&U6÷'FVCcs‚ÒG'VR’"’bbÖ–ãc3‚æ6öçF–ç2‚'6¶—Öæöåö÷Våö†Vg•öF6†&ö&E÷&÷w2"’¢76W'EG'VR‚%cRããcC¢'F–Â6VÆÇ2&R&VÆ—¦VBvÆÆWBÖ÷fVÖVçG2æB×W7B7&VF—B÷&Vg&W6‚vÆÆWB7W&f6W2"ÂW†V7WF÷"æ6öçF–ç2‚%%D”Åõ4TÄÅõtÄÄUEô5$TD•DTEócC"’bbW†V7WF÷"æ6öçF–ç2‚$Ä•dUõ%D”ÅõtÄÄUEõ$Te$U4…ôdõ$4TEócC"’bbW†V7WF÷"æ6öçF–ç2‚'&Vg&W6„&Ææ6R†f÷&6RÒG'VR’"’¢76W'EG'VR‚%cRããcC¢%D”Åõ4TÄÂ×W7BfVVBÖ÷fVÖVçBv–â÷&W÷'B÷'Vâ7W&f6W2v—F†÷WBv—F–ærf÷"FW&Ö–æÂ6Æ÷6R"ÂW†V7WF÷"æ6öçF–ç2‚%%D”Åõ4TÄÅôÔõdTÔTåEôdäõUEócC"’bbW†V7WF÷"æ6öçF–ç2‚'G&FRç6–FRæWVÇ2‚"²%Â%%D”Åõ4TÄÅÂ""²"ÂG'VR’"’bbW†V7WF÷"æ6öçF–ç2‚%'VåG&6¶W#3Bç&V6÷&EG&FR‡7–Ö&öÂÒöfæ÷WE7–Ö&öÂ"’¢fÂ7G&FVw•FVÆVÖWG'“cs’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ7G&FVw•FVÆVÖWG'’æ·B"’ç&VEFW‡B‚¢fÂÆ—fU7G&FVw•GVæW#cs’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU7G&FVw•GVæW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcs“¢W"ÖöFR×W7B6ö×÷VæBöÆV&âg&öÒ6ÆVâW"FW&Ö–æÂ&÷w2v†–ÆRÆ—fRÖöFR&VÖ–ç26ÆVâÖÆ—fR—6öÆFVB"Â7G&FVw•FVÆVÖWG'“cs’æ6öçF–ç2‚&6ö×WFT6ÆVåW%FW&Ö–æÄÆVFW&&ö&B"’bbÆ—fU7G&FVw•GVæW#cs’æ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’æ—5W"‚’"’bbÆ—fU7G&FVw•GVæW#cs’æ6öçF–ç2‚&6ö×WFT6ÆVåW%FW&Ö–æÄÆVFW&&ö&B"’bbÆ—fU7G&FVw•GVæW#cs’æ6öçF–ç2‚&6ö×WFT6ÆVäÆ—fUFW&Ö–æÄÆVFW&&ö&B"’¢fÂ&÷CcƒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcƒ¢'VææW"öv—fRÖ&6²W†—G2×W7B'—72W"6WGFÆRÖ–â6òv&×W6ææ÷BÆWBV·26öÆÆ6R"Â&÷Ccƒæ6öçF–ç2‚$E$tDõtåôe$ôÕõTµõ4UEDÄUô%•55ócƒ"’bb&÷Ccƒæ–æFW„öb‚$E$tDõtåôe$ôÕõTµõ4UEDÄUô%•55ócƒ"’Â&÷Ccƒæ–æFW„öb‚&—4–åW%6WGFÆT–â‡G2Â6frçW$ÖöFR’"’¢fÂvÆÆWDv÷ccƒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&VÆ—¦VEvÆÆWD6ö×÷VæF–ætv÷fW&æ÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcƒ¢vÆÆWB6ö×÷VæF–ær×W7BW6RÖöFRÖÆö6Â&VÆ—¦VB4TÄÂµ%D”Åõ4TÄÂÖöæW’&÷w2Âæ÷B&ÆVæFVBFW&Ö–æÂÖöæÇ’7G&FVw’&÷w2"ÂvÆÆWDv÷ccƒæ6öçF–ç2‚%cRããcƒ"’bbvÆÆWDv÷ccƒæ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’æ—5W"‚’"’bbvÆÆWDv÷ccƒæ6öçF–ç2‚&ÖöFScƒ"’bbvÆÆWDv÷ccƒæ6öçF–ç2‚%%D”Åõ4TÄÂ"’bbvÆÆWDv÷ccƒæ6öçF–ç2‚%&VÆ—¦VEvÆÆWD6ö×÷VæF–æræÖöæW•&÷w3cƒ"’¢fÂW†V3cƒ"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcƒ#¢W"6—¦–ær×W7BW†W&6—6RÆ—fRÖÖöæW’6ö×÷VæF–ærfÆö÷'2æBv–ææW"6V–Æ–ærf÷"&—G’"ÂW†V3cƒ"æ6öçF–ç2‚&ÖöæW•6—¦–ætÖöFScƒ""’bbW†V3cƒ"æ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’æ—5W"‚’"’bbW†V3cƒ"æ6öçF–ç2‚'v–ææW$Ö„&ö÷7BÒ–b†ÖöæW•6—¦–ætÖöFScƒ""’bbW†V3cƒ"æ6öçF–ç2‚$ÔôäU•ôÔôDUô%5ôdÄôõ%ôÄ”eEócƒ""’¢fÂ&÷Ccƒ2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcƒ3¢F–6²×F–ÖR†&BÖfÆö÷"÷&öf—BÖÆö6²6†VÆÂ×W7B6÷fW"ÆÂW"öÆ—fRÆæW2Âæ÷B6¶—$ÅTT4„•õ$U4ÄRôÄôäuô„ôÄB"Â&÷Ccƒ2æ6öçF–ç2‚%cRããcƒ2"’bb&÷Ccƒ2æ6öçF–ç2‚'fÂF–6µ&öf—DÆö6´VÆ–v–&ÆRÒG'VR"’bb&÷Ccƒ2æ6öçF–ç2‚""$f÷&Vç6–4ÆövvW"æÆ–fV7–6ÆR‚%D”4µõ$ôd•EôÄô4µõ4´•TEôÄäR"""’¢fÂÖ–ãcƒBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããccC“¢Ö–âT’ÆV&æ–ær÷&VF–æW72×W7BW6RW‡Æ–6—BFW6²&öö·2æB6öçF–âæòvÆö&Â¦÷W&æÂv–â×&FR&ö¦V7F–öâ"ÂÖ–ãcƒBæ6öçF–ç2‚$FW6µW&f÷&Öæ6TWF†÷&—G“ccC‚ä&öö²äÔTÔR"’bbÖ–ãcƒBæ6öçF–ç2‚$FW6µW&f÷&Öæ6TWF†÷&—G“ccC‚ä&öö²åõ%DdôÄ”ò"’bbÖ–ãcƒBæ6öçF–ç2‚$Ö&¶WG2&VF–æW72W‡Æ–6—FÇ’6öÖ&–æW2öæÇ’—G26†–ÆBFW6·2"’bbÖ–ãcƒBæ6öçF–ç2‚$¦÷W&æÅ&—G•V•6æ6†÷CcƒR"’bbÖ–ãcƒBæ6öçF–ç2‚&¦÷W&æÅ&—G•7FG56æ6†÷CcƒR‚’"’¢fÂ6VçF–Væ6Sc“Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6VçF–Væ6T÷&6†W7G&F÷"æ·B"’ç&VEFW‡B‚¢fÂÆ#c“Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô7–æ57G&FVw”Æ"æ·B"’ç&VEFW‡B‚¢fÂÖWFc“Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖWF6övæ—F–öäW†V7WF÷$'&–FvRæ·B"’ç&VEFW‡B‚¢fÂ76“c“Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ76•–Æ÷D6÷Væ6–Âæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“¢6VçF–Væ6RWfVçG2×W7BfVVBWFöæöÖ÷W27G&FVw’WF†÷&—G’Âæ÷B÷vW&ÆW72æòÖ×WFF–öâ6öÖÖVçF'’"Â6VçF–Væ6Sc“æ6öçF–ç2‚%$T”å2ÔôdbUDôäôÕ’"’bb6VçF–Væ6Sc“æ6öçF–ç2‚&fVVF–ærF†—2–çFòWFöæöÖ÷W27G&FVw’WF†÷&—G’"’bb6VçF–Væ6Sc“æ6öçF–ç2‚&WfVçE÷Fõ÷7G&FVw•öWF†÷&—G•óc“"’bb6VçF–Væ6Sc“æ6öçF–ç2‚'v—F†÷WB6†æv–ærvFW2Â6—¦–ærÂ÷"W†V7WF–öâ"’¢76W'EG'VR‚%cRããc“¢&Wf–WvVB7–æ2ôÄÄÒÆ"‡—÷F†W6W2×W7B†fR&VÂæöâ×6fWG’6—¦–ærWF†÷&—G’"ÂÆ#c“æ6öçF–ç2‚'&Wf–WvVB‡—÷F†W6W2&Ræ÷r&VÂ7G&FVw’WF†÷&—G’"’bbÆ#c“æ6öçF–ç2‚&6öW&6T–âƒãcÂãSR’"’bbÆ#c“æ6öçF–ç2‚&7GVFVEöWF†÷&—G•óc“×G'VR"’bbÆ#c“æ6öçF–ç2‚&6öW&6T–âƒã“"Âã‚’"’¢76W'EG'VR‚%cRããc“¢ÖWFÖ6övæ—F–öâ'&–FvR×W7BÖFW&–ÆÇ’6öçG&öÂæöâ×6fWG’6—¦–ærg&öÒV&Ç’G&FW2"ÂÖWFc“æ6öçF–ç2‚%cRããc“"’bbÖWFc“æ6öçF–ç2‚&6öW&6T–âƒãSRÂãcR’"’bbÖWFc“æ6öçF–ç2‚&6öW&6T–âƒãcRÂãCR’"’bbÖWFc“æ6öçF–ç2‚&6öW&6T–âƒã“BÂã‚’"’¢76W'EG'VR‚%cRããc“¢54’–Æ÷B×W7BWFöæöÖ÷W6Ç’6öçG&öÂW"öÆ—fRæöâ×6fWG’7G&FVw’v—F‚v–FW"WF†÷&—G’"Â76“c“æ6öçF–ç2‚&Æ—fRãSRâããƒÂW"ãCRâã"ã"’bb76“c“æ6öçF–ç2‚'&WGW&â–b‡W"’Òæ6öW&6T–âƒãCÂ"ã#R’VÇ6RÒæ6öW&6T–âƒãCRÂã“’"’bb76“c“æ6öçF–ç2‚%54•õ”ÄõEôÄäUõ$U5TÔTEóc“"’bb76“c“æ6öçF–ç2‚&v—F–æuö6öçG&öÅ÷F÷vW%öÖçVÅ&W7VÖR"’¢76W'EG'VR‚%cRããc“¢W†V7WF÷"t’6—¦R7F6²6V–Æ–ær×W7B÷Vâv†Vâ’WF†÷&—G’—27F—fRv†–ÆR&W6W'f–ær6fWG’6Æ×2"ÂW†V3cƒ"æ6öçF–ç2‚%$T”å2Ôôdb’5E$DTu’UD„õ$•E’"’bbW†V3cƒ"æ6öçF–ç2‚&v”WF†÷&—G”7F—fSc“"’bbW†V3cƒ"æ6öçF–ç2‚&–b…'VçF–ÖTÖöFTWF†÷&—G’æ—5W"‚’’"ãSVÇ6R"ã"’bb†W†V3cƒ"æ6öçF–ç2‚'&öGV7Bæ6öW&6T–â‡÷4WdfÆö÷"Âv”6V–Æ–æsc“’"’ÇÂW†V3cƒ"æ6öçF–ç2‚'&öGV7Bæ6öW&6T–â‡÷4WdfÆö÷"Âv”6V–Æ–æscCb’"’ÇÂW†V3cƒ"æ6öçF–ç2‚'&öGV7Bæ6öW&6T–â‡÷4WdfÆö÷"Âv”6V–Æ–æscC’’"’’¢fÂ†öÆF–æsc“Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô†öÆF–ætÆöv–4Æ–W"æ·B"’ç&VEFW‡B‚¢fÂW†V3c“Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“¢WFöæöÖ÷W2FB×Fò×v–ææW"×W7B&÷WFRv†ÆRôt’öF–ÖöæB6öçf–7F–öâ–çFò&VÂF÷×WW†V7WF–öâ"ÂW†V3c“æ6öçF–ç2‚&WFöæöÖ÷W5F÷W6–væÃc“"’bbW†V3c“æ6öçF–ç2‚$UDôäôÔõU5õDõUõ4”täÅóc“"’bbW†V3c“æ6öçF–ç2‚$UDôäôÔõU5ôDT4•4”ôåõDõUõ4”täÅóc“"’bbW†V3c“æ6öçF–ç2‚$„ôÄD”äuôÄôt”5ôDEôÔõ$UõDõUóc“"’bbW†V3c“æ6öçF–ç2‚&FõF÷W‡G2ÂvÆÆWE6öÂÂvÆÆWBÂF÷FÄW‡÷7W&U6öÂ’"’¢76W'EG'VR‚%cRããc“¢F÷×W6—¦–ær×W7Bv—fRD”ÔôäEô„äE2öÆöærÖ†öÆB'VææW'2FVWW"—&Ö–F–ærv†–ÆR&WF–æ–ærW‡÷7W&R62"ÂW†V3c“æ6öçF–ç2‚&F–ÖöæD†æG3c“"’bbW†V3c“æ6öçF–ç2‚&2çF÷WÖ…F÷FÅ6öÂ¢2ã"’bbW†V3c“æ6öçF–ç2‚'W$FEvÆÆWE7Cc“"’bbW†V3c“æ6öçF–ç2‚'vÆÆWE6öÂ¢W$FEvÆÆWE7Cc“"’bbW†V3c“æ6öçF–ç2‚&W‡÷7W&T6V–Æ–æu6öÂÒvÆÆWE6öÂ¢ãs"’¢76W'EG'VR‚%cRããc“¢†öÆF–ætÆöv–2×W7BW‡÷6RG'VRD”ÔôäEô„äE2ÖöFRæBDEôÔõ$R×W7B&Rât’6öçf–7F–öâ6–væÂ"Â†öÆF–æsc“æ6öçF–ç2‚$D”ÔôäEô„äE2"’bb†öÆF–æsc“æ6öçF–ç2‚#Sã"’bb†öÆF–æsc“æ6öçF–ç2‚$ç’6öçf–7F–öâ'VææW""’bb†öÆF–æsc“æ6öçF–ç2‚$f—'7B×&–FR6öçf–7F–öâFö¶Vç2"’bb†öÆF–æsc“æ6öçF–ç2‚$D”ÔôäEõDõôt•dT$4µóc“"’bb†öÆF–æsc“æ6öçF–ç2‚$t’FBÖÖ÷&R6öçf–7F–öâ"’bb†öÆF–æsc“æ6öçF–ç2‚%76•–Æ÷D6÷Væ6–ÂæW†—EF–Væ6R"’¢fÂ–ç6–FW$6÷“c“"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô–ç6–FW$6÷”Væv–æRæ·B"’ç&VEFW‡B‚¢fÂÖW&vSc“"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶VäÖW&vUVWVRæ·B"’ç&VEFW‡B‚¢fÂÖöFU&÷WFW#c“"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖöFU&÷WFW"æ·B"’ç&VEFW‡B‚¢fÂ7G–ÆU&÷WFW#c“"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôvVçF–57G–ÆU&÷WFW"æ·B"’ç&VEFW‡B‚¢fÂÖ–åV“c“"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂÖöFU7V6–f–4W†—G3c“"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖöFU7V6–f–4W†—G2æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“#¢–ç6–FW"6†&²6÷’Ö'W’×W7BVçVWVR2FVF–6FVB6÷W&6Rv—F‚ÆæR÷FööÂff–æ—G’"Â–ç6–FW$6÷“c“"æ6öçF–ç2‚'66ææW"Ò"’bb–ç6–FW$6÷“c“"æ6öçF–ç2‚$”å4”DU%õ4„$²"’bb–ç6–FW$6÷“c“"æ6öçF–ç2‚&ÆæTff–æ—G’Ò6WDöb"’bb–ç6–FW$6÷“c“"æ6öçF–ç2‚$”å4”DU%õtÄÄUB"’bbÖW&vSc“"æ6öçF–ç2‚$”å4”DU%õ4„$²"’bbÖW&vSc“"æ6öçF–ç2‚&&W7E66ææW"ÓÒ"’¢76W'EG'VR‚%cRããc“#¢–ç6–FW"6†&²×W7B&R&VÂÖöFU&÷WFW"ôvVçF–57G–ÆR&÷WFRÂæ÷B†–FFVât„ÄUô4õ’"ÂÖöFU&÷WFW#c“"æ6öçF–ç2‚$”å4”DU%õ4„$²‚"’bbÖöFU&÷WFW#c“"æ6öçF–ç2‚'vÆÆWB÷6ö6–Â6†&²Ç†6÷W&6R"’bbÖöFU&÷WFW#c“"æ6öçF–ç2‚%G&FUG—Rä”å4”DU%õ4„$²ÓâVæ–f–VDÖöFT÷&6†W7G&F÷"äW‡FVæFVDÖöFRä4õ•õE$DR"’bb7G–ÆU&÷WFW#c“"æ6öçF–ç2‚$”å4”DU%õ4„$µôdôÄÄõr"’bb7G–ÆU&÷WFW#c“"æ6öçF–ç2‚$ÖöFU&÷WFW"åG&FUG—Rä”å4”DU%õ4„$²"’¢76W'EG'VR‚%cRããc“#¢–ç6–FW"6†&²F—7G&–'WF–öâ×W7B6VÆÂÖVÖWG&FW"÷6—F–öç2æB6†÷röâÖ–âT’"ÂW†V3c“æ6öçF–ç2‚$”å4”DU%õ4„$µôÔTÔUôU„•Eóc“""’bb–ç6–FW$6÷“c“"æ6öçF–ç2‚&W†—E6–væÄf÷$Ö–çB"’bbÖöFU7V6–f–4W†—G3c“"æ6öçF–ç2‚$ÖöFU&÷WFW"åG&FUG—Rä”å4”DU%õ4„$²ÓâWfÇVFT6÷•G&FTW†—B"’bbÖ–åV“c“"æ6öçF–ç2‚&vWEV•7VÖÖ'’‚’"’bbÖ–åV“c“"æ6öçF–ç2‚%4„$²"’¢76W'EG'VR‚%cRããc“#¢F–ÖöæB†æG2ÆæR×W7B†fRÖ–âT’f—6–&–Æ—G’ÇW2W‡ç6–öâ7FGW2"ÂÖ–åV“c“"æ6öçF–ç2‚&F–ÖöæD÷Vâ"’bbÖ–åV“c“"æ6öçF–ç2‚$D”ÔôäB÷VãÒ"’bbÖ–åV“c“"æ6öçF–ç2‚&W‡æCÓ7‚ósR"’bbÖ–åV“c“"æ6öçF–ç2‚&F–ÖöæD6÷VçB"’bbÖ–åV“c“"æ6öçF–ç2‚&F–ÖöæD6÷VçB"’bbÖ–åV“c“"æ6öçF–ç2‚&W‡æCÓ7‚"’¢fÂÆæTW†—EGVæW#c“2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærôÆæTW†—EGVæW"æ·B"’ç&VEFW‡B‚¢fÂGVæ–æuV“c“2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’õGVæ–æt7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“3¢ÆæR7G&FVw’&WÆ’×W7B7GVFR&÷VæFVBÆæTW†—EGVæW"Eõ4Â&–2Âæ÷B&VÖ–âT’ÖöæÇ’"ÂÆæTW†—EGVæW#c“2æ6öçF–ç2‚%&WÆ”&–2"’bbÆæTW†—EGVæW#c“2æ6öçF–ç2‚$ÆæU7G&FVw”WfÇVF÷"æ&W7EW$ÆæR‚’"’bbÆæTW†—EGVæW#c“2æ6öçF–ç2‚$ÄäUõ5E$DTu•õ$UÄ•ô$”5õ$Te$U4…óc“2"’bbÆæTW†—EGVæW#c“2æ6öçF–ç2‚$ÆæU7G&FVw•&WÆ’&–2c“2"’bbÆæTW†—EGVæW#c“2æ6öçF–ç2‚'&Vg&W6…&WÆ”&–47–æ2"’bbÆæTW†—EGVæW#c“2æ6öçF–ç2‚&vWEG×VÇB"’bbÆæTW†—EGVæW#c“2æ6öçF–ç2‚&vWE6Ä×VÇB"’¢76W'EG'VR‚%cRããc“3¢GVæ–ærT’×W7B7F÷6Æ–Ö–ær&WÆ’—2&VBÖöæÇ’öæòÆæW2v†Vâv&×W6öçG&–'WF÷'2W†—7B"ÂGVæ–æuV“c“2æ6öçF–ç2‚$ÆæR7G&FVw’&WÆ’æ÷rfVVG2&÷VæFVBÆæTW†—EGVæW"Eõ4Â&–2"’bbGVæ–æuV“c“2æ6öçF–ç2‚'v&Ö–æs¢&VÆ÷r7FF—7F–6ÂF‡&W6†öÆBÂ'WBÆæW2÷G&FW'2&R6öçG&–'WF–ær"’bbGVæ–æuV“c“2æ6öçF–ç2‚'&t&ö&BçF¶Rƒ"’"’¢fÂfFu&÷WFSc“BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærôfFu&÷WFUfW&F–7Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“C¢Æ—fRW"ÖÖ–7&ò&ÆVVFW"'V6¶WG2×W7B&RW"ÖöæÇ’Âæ÷BW66ÆFVBFòÆ—fR&VGV6VB×6—¦R"ÂfFu&÷WFSc“Bæ6öçF–ç2‚$$Äô4µôÄ•dUô$ÄTTDU%õU%ôôäÅ’"’bbfFu&÷WFSc“Bæ6öçF–ç2‚$Ä•dUô$ÄTTDU%õU%ôôäÅ•ô$Äô4µóc“B"’bbfFu&÷WFSc“Bæ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’æ—4Æ—fR‚’"’bbfFu&÷WFSc“Bæ6öçF–ç2‚$Ä•dUõU%ôÔ”5$õôU44ÄDTEõDõõ$TET4TEóCS#b"’¢fÂ&÷E6W'f–6Sc“BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“C¢ÆæRV&çF–æR&Æö6·2Æ—fRVçG&–W2'WBW"¶VW2V&çF–æVBÆæW26×Æ–ær"Â&÷E6W'f–6Sc“Bæ6öçF–ç2‚%U%ôÄäUõT$åD”äUõ5D”ÄÅõ4ÕÄ”äuóc“B"’bb&÷E6W'f–6Sc“Bæ6öçF–ç2‚%'VçF–ÖTÖöFTWF†÷&—G’æ—5W"‚’"’bb&÷E6W'f–6Sc“Bæ6öçF–ç2‚'W%öVæ&ÆVEöÆ—fU÷V&çF–æVB"’bb&÷E6W'f–6Sc“Bæ6öçF–ç2‚$ÆæUV&çF–æT6öçG&öÆÆW"æÆöt&Æö6¶VDVçG'’"’¢fÂÆæUöÆ–7“c“BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærôÆæUöÆ–7’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“C¢æWrÆæW2×W7B&RW‡Æ–6—Bf—'7BÖ6Æ72ÆæUöÆ–7’6—F—¦Vç2"ÂÆæUöÆ–7“c“Bæ6öçF–ç2‚$D”ÔôäB"’bbÆæUöÆ–7“c“Bæ6öçF–ç2‚$”å4”DU""’bbÆæUöÆ–7“c“Bæ6öçF–ç2‚%4„$²"’bbÆæUöÆ–7“c“Bæ6öçF–ç2‚&æWrÆæW2&Rf—'7BÖ6Æ72öÆ–7’6—F—¦Vç2"’¢fÂ7'—FôÇCc“RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂÖ–ä7F—f—G“c“RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂÖ–äÆ–÷WCc“RÒ¦fæ–òäf–ÆR‚'7&2öÖ–â÷&W2öÆ–÷WBö7F—f—G•öÖ–âç†ÖÂ"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“S¢7'—FòVæ—fW'6R×W7BW6RÔTÔR×&—G’6—¦–ærÂæ÷BÖ–7&òÖöæÇ’G&VFÖ–ÆÂ"Â7'—FôÇCc“Ræ6öçF–ç2‚$DTdTÅEõ4•¤Uõ5BÒbã"’bb7'—FôÇCc“Ræ6öçF–ç2‚$5%•DõõTä•dU%4UôÔTÔUõ$•E•õ4•¤Uóc“R"’bb7'—FôÇCc“Ræ6öçF–ç2‚&&Ææ6R¢ãCR"’¢76W'EG'VR‚%cRããc“S¢7'—FòF÷†–2'V6¶WG2×W7B6ögB×6†RF7F–2÷6—¦R–ç7FVBöb†&B×&WGW&æ–ær"Â7'—FôÇCc“Ræ6öçF–ç2‚$5%•DõõDõ„”5õEDU$åõ4ôeEõ4„Uóc“R"’bb7'—FôÇCc“Ræ6öçF–ç2‚&7'—FõF÷†–56—¦T×VÇCc“RÒã3R"’bb7'—FôÇCc“Ræ6öçF–ç2‚$5%•DõõDõ„”5õEDU$åô„$Eô$Äô4²"’¢76W'EG'VR‚%cRããc“S¢7'—Fòc2'&–FvR×W7B&V6V—fR&VÆ—7F–2Æ—V–F—G’6öçFW‡B"Â7'—FôÇCc“Ræ6öçF–ç2‚&VçG'”Æ—W6BÒW†7D76WDÖWG&–73cC“2‡6–væÂ’æÆ—V–F—G•W6B"’bb7'—FôÇCc“Ræ6öçF–ç2‚$ÔTÔRG&FW"w2&–6‚VçG'’6æ6†÷B7G'V7GW&R"’¢76W'EG'VR‚%cRããc“S¢Ö–âT’×W7B7W&f6RF†RW‡æFVBÔTÔR´5%•DòÆ–W"7F6²"ÂÖ–ä7F—f—G“c“Ræ6öçF–ç2‚#C²Æ–W'2+rÔTÔR´5%•Dò"’bbÖ–äÆ–÷WCc“Ræ6öçF–ç2‚#C²Æ–W'2+rÔTÔR´5%•Dò"’¢fÂ–ç6–FW$6÷“c“bÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô–ç6–FW$6÷”Væv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“c¢”å4”DU%õ4„$²ô4õ’×W7B÷Vâ7'—FòVæ—fW'6R÷6—F–öç2Âæ÷B§W7BÔTÔR÷vF6†Æ—7BGf—6÷&–W2"Â7'—FôÇCc“Ræ6öçF–ç2‚&6÷”'W”g&öÔ–ç6–FW%6–væÂ"’bb7'—FôÇCc“Ræ6öçF–ç2‚$”å4”DU%õ4„$µô5%•Dõô4õ•ô%U•óc“b"’bb–ç6–FW$6÷“c“bæ6öçF–ç2‚&6÷”'W”7'—FôÇCc“b"’bb–ç6–FW$6÷“c“bæ6öçF–ç2‚$7'—FôÇEG&FW"æ6÷”'W”g&öÔ–ç6–FW%6–væÂ"’¢76W'EG'VR‚%cRããc“c¢ÅE2&VF–æW72×W7BW‡÷6R7'—Fò6—¦RöÆ–W"öÆ–7’öâF†RÖ–âT’"Â7'—FôÇCc“Ræ6öçF–ç2‚'6—¦UöÆ–7’"’bb7'—FôÇCc“Ræ6öçF–ç2‚&Æ–W%öÆ–7’"’bbÖ–ä7F—f—G“c“Ræ6öçF–ç2‚'fÂÆ–W%öÆ–7’"’bbÖ–ä7F—f—G“c“Ræ6öçF–ç2‚'fÂ6—¦UöÆ–7’"’bbÖ–ä7F—f—G“c“Ræ6öçF–ç2‚%ÂFÆ–W%öÆ–7’"’¢fÂ7–6Æ–3c“rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô7–6Æ–5G&FTVæv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcScs¢5”4Ä”2&–ær×W7B&W6W'fR6öæf—&ÖVB†&B6fWG’v†–ÆRVæ¶æ÷vâ÷&÷f–FW"v26öçF–çVRFò6æöæ–6ÂdDr"Â7–6Æ–3c“ræ6öçF–ç2‚&7–6Æ–4VçG'•6VÆÆ&–Æ—G”wV&Cc“r"’bb7–6Æ–3c“ræ6öçF–ç2‚$4ôäd•$ÔTEôdÅ4RÂTä´äõtâÂ$õd”DU%õTäd”Ä$ÄRÂ4ôäd•$ÔTEõE%TR"’bb7–6Æ–3c“ræ6öçF–ç2‚$5”4Ä”5õ4TÄÄ$”Ä•E•ôTåE%•õ$T¤T5Eóc“r"’bb7–6Æ–3c“ræ6öçF–ç2‚&Wf–FVæ6RÒ7–6Æ–56VÆÆ&–Æ—G”Wf–FVæ6ScScrä4ôäd•$ÔTEôdÅ4R"’¢76W'EG'VR‚%cRããc“s¢5”4Ä”2æ÷&ÖÂæB7F'fF–öâ×&ö&Rf–ÇFW'2×W7B&÷F‚Ç’6VÆÆ&–Æ—G’wV&B"Â7–6Æ–3c“ræ6öçF–ç2‚""&7–6Æ–4VçG'•6VÆÆ&–Æ—G”wV&Cc“r‡G2Â&6æF–FFR""""’bb7–6Æ–3c“ræ6öçF–ç2‚""&7–6Æ–4VçG'•6VÆÆ&–Æ—G”wV&Cc“r‡G2Â'&ö&R""""’¢fÂ&W÷'Cc“‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc“ƒ¢W†V7WF—fR&W÷'BÆö÷ö¦÷W&æÂ6÷VçFW'2×W7BfÆÂ&6²FòÆ&VÂ6÷VçFW'2W6VB'’6÷&RGV×"Â&W÷'Cc“‚æ6öçF–ç2‚""&Æ&VÃc“‚‚$$õEôÄôõõD”4²"’"""’bb&W÷'Cc“‚æ6öçF–ç2‚""'—RæÆ&VÄ6÷VçG5²$$õEôÄôõõD”4²%Ò"""’bb&W÷'Cc“‚æ6öçF–ç2‚""'—RæÆ&VÄ6÷VçG5²%E$DT¥$äÅõ$T2%Ò"""’¢76W'EG'VR‚%cRããc“ƒ¢ÖöæW’F‚×W7B7Æ—BÆö6ÂÆ—fR÷W"÷Vç2æB†÷7BG&6¶W"–ç7FVBöb†–F–ærW"VæFW"†÷7D÷VãÓ"Â&W÷'Cc“‚æ6öçF–ç2‚&Æö6Ä÷VãÖÆ—fS¢"’bb&W÷'Cc“‚æ6öçF–ç2‚'W#¢"’bb&W÷'Cc“‚æ6öçF–ç2‚&†÷7EG&6¶W#Ò"’bb&W÷'Cc“‚æ6öçF–ç2‚'G'W7FVEW$÷Vâ"’bb&W÷'Cc“‚æ6öçF–ç2‚'G'W7FVDÆ—fT÷Vâ"’¢76W'EG'VR‚%cRããc“ƒ¢&W÷'G2×W7B6†÷rVæ&ÆVEG&FW$WF†÷&—G’6–FV6'26ò5%•DòöÖ&¶WG2Æ–W'2Fòæ÷BF—6V"g&öÒ'VçF–ÖRVæ&ÆVBÆ—7B"Â&W÷'Cc“‚æ6öçF–ç2‚$Væ&ÆVEG&FW$WF†÷&—G’ç6æ6†÷E7G"‚’"’bb&W÷'Cc“‚æ6öçF–ç2‚&WF†÷&—G“Ò"²"B"²&WF†÷&—G”Væ&ÆVCc“‚"’bb&W÷'Cc“‚æ6öçF–ç2‚&WF†÷&—G“Ò"²"B"²&WF†÷&—G•'VçF–ÖSc“‚"’¢fÂ&÷WFT‡–G&F÷#c“’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&÷WFUG'WF„‡–G&F÷"æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷#c“’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc““¢†VÆBFö¶Vç2×W7B‡–G&FR&÷WFRG'WF‚g&öÒW'6—7FVB'W’ö÷Vâ&÷WFRÂæWfW"FVÖ÷FRv–ææW'2Fò&÷WFRVæ¶æ÷vâ"Â&÷WFT‡–G&F÷#c“’æ6öçF–ç2‚$„TÄBDô´Tâ%U’Õ$õUDR…”E$D”ôâ"’bb&÷WFT‡–G&F÷#c“’æ6öçF–ç2‚'G2ç÷6—F–öâæVçG'•&–6U6÷W&6R"’bb&÷WFT‡–G&F÷#c“’æ6öçF–ç2‚'G2ç÷6—F–öâæVçG'•ööÄFG&W72"’bb&÷WFT‡–G&F÷#c“’æ6öçF–ç2‚$TåE%•õTÕ"’bb&÷WFT‡–G&F÷#c“’æ6öçF–ç2‚$TåE%•õôôÂ"’bb&÷WFT‡–G&F÷#c“’æ6öçF–ç2‚$TåE%•ô¥U•DU""’¢76W'EG'VR‚%cRããc““¢Æ—fR6VÆÂ&÷WF–ær×W7BFW&—fRV×ô§W—FW"f—'7B&–÷&—G’g&öÒW'6—7FVB'W’&÷WFRÖWFFF"ÂW†V7WF÷#c“’æ6öçF–ç2‚'6VÆÅ&÷WFU&–÷&—G”g&öÔ'W•&÷WFSc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚%6VÆÅ&÷WFU&–÷&—G“c“’åTÕôd•%5B"’bbW†V7WF÷#c“’æ6öçF–ç2‚%6VÆÅ&÷WFU&–÷&—G“c“’ä¥U•DU%ôd•%5B"’bbW†V7WF÷#c“’æ6öçF–ç2‚%4TÄÅõ$õUDUõ$”õ$•E•ô%U•õ$õUDUóc“’"’¢76W'EG'VR‚%cRããc““¢gVÆÂÂ&öf—BÖÆö6²Â'F–ÂÂæB÷'†â6VÆÇ2×W7B6¶—vVæW&–2V×Öf—'7Bv†Vâ'W’&÷WFR6—2§W—FW"÷fVçVRf—'7B"ÂW†V7WF÷#c“’æ6öçF–ç2‚'&öf—DÆö6µV×f—'7Cc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚''F–ÅV×f—'7Cc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚&W†—EV×f—'7Cc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚&÷'†åV×f—'7Cc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚%TÕôD•$T5Eõ4´•TEô%U•õ$õUDUóc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚$&÷E6W'f–6Rç7FGW2çFö¶Vç5¶Ö–çEÒ"’¢fÂÖ–åV“cÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc¢&÷WFR×VæF–ærT’×W7BfÆÂ&6²FòW'6—7FVBVçG'’&÷WFRÖWFFFf÷"†VÆBv–ææW'2"ÂÖ–åV“cæ6öçF–ç2‚&VçG'•&÷WFSc"’bbÖ–åV“cæ6öçF–ç2‚'÷2æVçG'•&–6U6÷W&6R"’bbÖ–åV“cæ6öçF–ç2‚'÷2æVçG'•ööÄFG&W72"’bbÖ–åV“cæ6öçF–ç2‚%Tå$TÄ•¤TB+rVçG'’&÷WFR"’¢76W'EG'VR‚%cRããc¢ÖFW&–Â'VææW'2v—F‚¶æ÷vâW'6—7FVB'W’&÷WFR×W7B†'fW7BWfVâv†VâföÆF–ÆR&VÅ&–6TÆö6²66†R—2Ö—76–æröF—6w&VW2"ÂW†V7WF÷#c“’æ6öçF–ç2‚'G'•W'6—7FVDVçG'•&÷WFT†'fW7Cc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚%U%4•5DTEôTåE%•õ$õUDUô„%dU5Eóc“’"’bbW†V7WF÷#c“’æ6öçF–ç2‚'&VÇ&–6VÆö6µöÖ—76–æuö÷%öF—6w&VW5ö'WEö'W•÷&÷WFUö¶æ÷vâ"’bbW†V7WF÷#c“’æ6öçF–ç2‚""'G'•W'6—7FVDVçG'•&÷WFT†'fW7Cc“’‚'VÇG&÷'VææW%ö&æ²"’"""’bbW†V7WF÷#c“’æ6öçF–ç2‚""'G'•W'6—7FVDVçG'•&÷WFT†'fW7Cc“’‚'vÆÆWEöw&÷wF…ö†'fW7B"’"""’¢Ð   ¢FW7@¢gVâFSCSsdÆ—fT'W”Ç&VG”÷Vå7V66W74†5FW&Ö–æÅG&6R‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSsc¢Æ—fR'W’×W7B†fRW"ÖGFV×BFW&Ö–æÂG&6R†VÇW""ÂW†V7WF÷"æ6öçF–ç2‚$%U•ôEDTÕEõE$4UóCSsb"’bbW†V7WF÷"æ6öçF–ç2‚&'W”GFV×EG&6SCSsb"’bbW†V7WF÷"æ6öçF–ç2‚%DU$Ô”äÅôô²"’bbW†V7WF÷"æ6öçF–ç2‚%DU$Ô”äÅôd”Â"’¢76W'EG'VR‚%cRããCSsc¢Ç&VG’Ö÷VâÖBÖ6öæf—&Ò7V66W72×W7B&6¶f–ÆÂF†RÖ—76–ær%U’¦÷W&æÂ&Vf÷&RFW&Ö–æÂô²"ÂW†V7WF÷"æ6öçF–ç2‚%õ4•D”ôåôÅ$TE•ôõTåôEô4ôäd•$Ò"’bbW†V7WF÷"æ6öçF–ç2‚$Ä•dUô%U•ô¤õU$äÅô$4´d”ÄÄTEóCSsb"’bbW†V7WF÷"æ6öçF–ç2‚$%U•ôÅ$TE•ôõTåôEô4ôäd•$Õô$4´d”ÄÅóCSsb"’bbW†V7WF÷"æ6öçF–ç2‚$%U•õDU$Ô”äÅôô³¥õ4•D”ôåôÅ$TE•ôõTåôEô4ôäd•$Ò"’¢76W'EG'VR‚%cRããCSsc¢Æ—fR%U’¦÷W&æÂ&6¶f–ÆÂ×W7B&R–FV×÷FVçB'’6–væGW&R"ÂW†V7WF÷"æ6öçF–ç2‚&Æ—fT'W”¦÷W&æÆVE6–w3CSsb"’bbW†V7WF÷"æ6öçF–ç2‚$Ä•dUô%U•ô¤õU$äÅôEUõ5U$U54TEóCSsb"’¢76W'EG'VR‚%cRããCSsc¢æ÷&ÖÂ÷6—F–öâ7F×F‚×W7BVÖ—BF†RGFV×BG&6R&Vf÷&RFW&Ö–æÂô²"ÂW†V7WF÷"æ6öçF–ç2‚&'W”GFV×EG&6SCSsb"’bbW†V7WF÷"æ6öçF–ç2‚%õ4•D”ôåõ5DÕTB"’bbW†V7WF÷"æ6öçF–ç2‚$Ä•dUõõ4•D”ôåõ5DÕTB"’bbW†V7WF÷"æ6öçF–ç2‚$%U•õDU$Ô”äÅôô³¥E…ô4ôäd•$ÔTEõTäD”äuõtÄÄUEôDTÅD"’¢76W'EG'VR‚%cRããCSsc¢7–æ2vÆÆWB×&ööb%U•ôô²õTäD”ärF‡2×W7B6öææV7B&6²FòF†R6ÖRGFV×BG&6R"ÂW†V7WF÷"æ6öçF–ç2‚%tÄÄUEõ$ôôeôô²"’bbW†V7WF÷"æ6öçF–ç2‚%tÄÄUEõ$ôôeõTäD”är"’bbW†V7WF÷"æ6öçF–ç2‚$Ä•dUô%U•ôÄäDTB"’¢Ð   ¢FW7@¢gVâFSCSs„Æ—fT'W”FöW4æ÷D†&Df–Å6VçF–æVÅ66÷&W2‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSsƒ¢Fô'W’×W7Bæ÷&ÖÆ—¦R6ÆÆW"6VçF–æVÂ66÷&W2&Vf÷&RÆ—fRW†V7WF÷"6—¦–æröGf—6÷'2"ÂW†V7WF÷"æ6öçF–ç2‚$Ä•dUô%U•õ44õ$Uôäõ$ÔÄ•¤TEóCSs‚"’bbW†V7WF÷"æ6öçF–ç2‚'fÂW†V566÷&SCSs‚"’bbW†V7WF÷"æ6öçF–ç2‚'G2æVçG'•66÷&Ræ—4f–æ—FR‚’"’bbW†V7WF÷"æ6öçF–ç2‚&VÇ6RÓâSã"’¢76W'EG'VR‚%cRããCSsƒ¢F—&V7BÆ—fT'W’6ÆÆW'2×W7Bæ÷&ÖÆ—¦R6VçF–æVÂ66÷&W2BF†Rf–æÂÆ—fR6†ö¶R"ÂW†V7WF÷"æ6öçF–ç2‚$Ä•dUô%U•õ44õ$Uôäõ$ÔÄ•¤TEôEô4„ô´UóCSs‚"’bbW†V7WF÷"æ6öçF–ç2‚'fÂ&tÆ—fU66÷&SCSs‚Ò66÷&R"’bbW†V7WF÷"æ6öçF–ç2‚&6öçF–çVUöæõö–çfÆ–E÷66÷&U÷fWFò"’¢76W'DfÇ6R‚%cRããCSsƒ¢Æ—fR”ådÄ”Eõ44õ$R×W7Bæ÷B&VÖ–âF†RFöÖ–æçB6VçF–æVÂ×66÷&R†&BfWFò&Vf÷&R66÷&Ræ÷&ÖÆ—¦F–öâ"ÂW†V7WF÷"æ6öçF–ç2‚$Æ—fR'W’6¶—VC¢–çfÆ–B66÷&R"’¢Ð   ¢FW7@¢gVâFSCSs•F÷†–4Æ—fT&ÆVVE—f÷G57G&FVw”æ÷EF–Væ6TÖ–7&õ&ö&R‚’°¢fÂGVæW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU7G&FVw•GVæW"æ·B"’ç&VEFW‡B‚¢fÂ&÷WFW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôvVçF–57G–ÆU&÷WFW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒC¢F÷†–2Æ—fR&ÆVVB×W7B&RÆ&VÆÆVB2&V6Æ–ÒF7F–2—f÷B"ÂGVæW"æ6öçF–ç2‚'F÷†–5÷&V6Æ–Õ÷F7F–5÷—f÷B"’¢76W'DfÇ6R‚%cRããCSs“¢F÷†–2Æ—fR&ÆVVB×W7Bæ÷B¶VWöÆBF÷†–5÷'VææW%÷—f÷B&V†f–÷""ÂGVæW"æ6öçF–ç2‚&Æ&VÂÒ–b‡F÷†–4&ÆVVB’"’bbGVæW"æ6öçF–ç2‚'F÷†–5÷'VææW%÷—f÷B"’¢76W'EG'VR‚%cRããCSs“¢F÷†–2—f÷B×W7B6†÷'FVâ†öÆBæB&æ²V&Æ–W""ÂGVæW"æ6öçF–ç2‚&†öÆD×VÇBÒ–b‡F÷†–4–ææW$ÆæU—f÷B’"’bbGVæW"æ6öçF–ç2‚&6öW&6T–âƒãSRÂãƒB’"’bbGVæW"æ6öçF–ç2‚&6öW&6T–âƒãc"Âã“"’"’¢76W'EG'VR‚%cRããCSƒC¢F÷†–2—f÷B×W7B7v—F6‚7G–ÆR–ç6–FRF†RÆæR&Vf÷&R6—¦–ær"Â&÷WFW"æ6öçF–ç2‚'GVæVD&6U7G–ÆR"’bb&÷WFW"æ6öçF–ç2‚'F÷†–5F7F–5—f÷CCSƒB"’bb&÷WFW"æ6öçF–ç2‚%Dõ„”5õ$T4Ä”ÕõD5D”2"’bb&÷WFW"æ6öçF–ç2‚'6ÖTÆæUvVµ—f÷E7G–ÆR†ÆæT†–çBÂ7G–ÆRåDõ„”5õ$T4Ä”ÕõD5D”2’"’¢Ð   ¢FW7@¢gVâFSCSƒ6—FÄÆÆö6F÷%&W76W4V&Ç”Æ—fUv–ææW'4&Vf÷&T&ÆVVFW'2‚’°¢fÂF×W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæTW‡V7Fæ7”F×W"æ·B"’ç&VEFW‡B‚¢fÂFö7G&–æRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fTw&÷wF„Fö7G&–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒ¢V&Ç’Æ—fRv–ææW'2×W7BvWB&÷VæFVB6ö×÷VæF–ær&ö÷7B"ÂF×W"æ6öçF–ç2‚$T$Å•õt”ääU%ôÔ”åõE$DU2Ò""’bbF×W"æ6öçF–ç2‚&V&Ç•v–ææW""’bbF×W"æ6öçF–ç2‚&6öW&6T–âƒãÂ6’"’¢76W'EG'VR‚%cRããCSƒ¢&öf—F&ÆR'VææW"ÆæW2×W7B&R&ö÷7FVB–ç7FVBöb6–ÆVçFÇ’6öçF–çV–ærBã"ÂF×W"æ6öçF–ç2‚&—5'VææW$ÆæR†Òç7G&FVw’’"’bbF×W"æ6öçF–ç2‚&÷WE¶Òç7G&FVw’çG&–Ò‚’çWW&66R‚•ÒÒÖ„öb"’bbF×W"æ6öçF–ç2‚&–b†—5'VææW$ÆæR†Òç7G&FVw’’bb†ÒçF÷FÅ6öÅæÂâãÇÂÒçv–å&FU7BãÒ3Rã’’6öçF–çVR"’bbF×W"æ6öçF–ç2‚&—5'VææW$ÆæR†Òç7G&FVw’’bbÒçF÷FÅ6öÅæÂâã"’ ¢76W'EG'VR‚%cRããCSƒ¢u"Ö&6VB'VææW"W†V×F–öâ×W7B&WV—&RæöâÖæVvF—fRæWB4ôÂ"ÂF×W"æ6öçF–ç2‚&Òçv–å&FU7BãÒu%õ%TääU%ôÔ”åõ5BbbÒçF÷FÅ6öÅæÂãÒã"’¢fÂfÆÆ&6²ÒFö7G&–æRç7V'7G&–ætgFW"‚&F—7F6†&ÆT6öçG&–'WF–öäÆæW2"’ç7V'7G&–ætgFW"‚&Æ—7Döb‚"’ç7V'7G&–æt&Vf÷&R‚&gVâw&÷wF„ÆæTfÆÆ&6²"¢fÂ&ÇVRÒfÆÆ&6²æ–æFW„öb‚%Â$$ÅTT4„•Â""¢fÂÖööâÒfÆÆ&6²æ–æFW„öb‚%Â$Ôôôå4„õEÂ""¢fÂVÆ—G’ÒfÆÆ&6²æ–æFW„öb‚%Â%TÄ•E•Â""¢fÂW‡&W72ÒfÆÆ&6²æ–æFW„öb‚%Â$U…$U55Â""¢fÂÖæ—ÒfÆÆ&6²æ–æFW„öb‚%Â$Ôä•TÄDTEÂ""¢fÂ6†—BÒfÆÆ&6²æ–æFW„öb‚%Â%4„•D4ô”åÂ""¢76W'EG'VR‚%cRããCSƒ¢6öçG&–'WF–öâfÆÆ&6²×W7B&–÷&—F—¦R$ÅTT4„•ôÔôôå4„õBõTÄ•E’&Vf÷&RU…$U52ôÔä•TÄDTBõ4„•D4ô”â"ÂÆ—7Döb†&ÇVRÂÖööâÂVÆ—G’ÂW‡&W72ÂÖæ—Â6†—B’æÆÂ²—BãÒÒbb&ÇVRÂW‡&W72bbÖööâÂÖæ—bbVÆ—G’Â6†—B¢Ð   ¢FW7@¢gVâFSCSƒ7'—FõVæ—fW'6T6æöæ–6ÄÆV&æ–æt—5÷7D6öÖÖ—DæD—6öÆFVB‚’°¢fÂG&FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂ'&–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòö'&–âô7'—Fô'&–âæ·B"’ç&VEFW‡B‚¢òòæ6†÷"F†R6öÖÖ—GFVB6æöæ–6Â÷6—F–öâ&Vf÷&Rç’ÆV&æ–ær6–FRVffV7G2à¢fÂ÷7D6öÖÖ—BÒG&FW"ç7V'7G&–ætgFW"‚'÷6—F–öç5·÷6—F–öâæ–EÒ"’ç7V'7G&–æt&Vf÷&R‚"òòcRã’ã3#"¢fÂG–å66âÒG&FW"ç7V'7G&–ætgFW"‚$G–æÖ–2Fö¶Vâ66â"’ç7V'7G&–æt&Vf÷&R‚'&—fFR7W7VæBgVâ'Vå66ä7–6ÆR"¢fÂ6Æ÷6T&Æö6²ÒG&FW"ç7V'7G&–ætgFW"‚"òòcRããCSƒ(	B5%•Dò•4ôÄD”ôâtÄÂ"’ç7V'7G&–æt&Vf÷&R‚"òò)H)HW'4ÆV&æ–æt'&–FvR"¢76W'EG'VR‚%cRããCSƒ¢7'—Fô'&–âæöåG&FU7F'B×W7Bf—&RöæÇ’gFW"W"öÆ—fR÷Vâ—26öÖÖ—GFVB"Â÷7D6öÖÖ—Bæ6öçF–ç2‚$7'—Fô'&–âæöåG&FU7F'B‚’"’bb÷7D6öÖÖ—Bæ6öçF–ç2‚%vÆÆWE÷6—F–öäÆö6²ç&V6÷&D÷Vâ"’bb÷7D6öÖÖ—Bæ6öçF–ç2‚$7'—FôÇB"’bb÷7D6öÖÖ—Bæ6öçF–ç2‚&6æöæ–6Äf–æÅ6—¦ScSs"’¢76W'DfÇ6R‚%cRããCSƒ¢G–æÖ–27'—Fò6–væÂvVæW&F–öâ×W7Bæ÷Bf¶R6æöæ–6Â÷Vç2"ÂG–å66âæ6öçF–ç2‚$7'—Fô'&–âæöåG&FU7F'B‚’"’¢76W'EG'VR‚%cRããCSƒ¢7'—Fô'&–â6Æ÷6R&V6öæ6–Æ–F–öâ×W7BæWfW"FV7&VÖVçB6æöæ–6Âö÷Vâ&VÆ÷r¦W&ò"Â'&–âæ6öçF–ç2‚&÷VåG&FW2ævWB‚’âÂ"’bb'&–âæ6öçF–ç2‚&6æöæ–6ÅF÷FÂævWB‚’âÂ"’bb'&–âæ6öçF–ç2‚'&V6÷fW&VEG&FW2æ–æ7&VÖVçDæDvWB‚’"’¢76W'EG'VR‚%cRããCSƒ¢7'—FôÇB6Æ÷6W2×W7B7F’—6öÆFVBg&öÒÖVÖRövÆö&ÂÆV&æW'2"Â6Æ÷6T&Æö6²æ6öçF–ç2‚&ÖVÖRövÆö&ÂÆV&æW'26¶—VB"’bb6Æ÷6T&Æö6²æ6öçF–ç2‚$ÖWF6övæ—F–öä’ç&V6÷&EG&FT÷WF6öÖR"’bb6Æ÷6T&Æö6²æ6öçF–ç2‚%6†F÷tÆV&æ–ætVæv–æRæöäÆ—fUG&FTW†—B"’¢fÂVçG'”—6òÒG&FW"ç7V'7G&–ætgFW"‚'÷7BÖ6öÖÖ—B—6öÆF–öâ÷6fWG’"’ç7V'7G&–æt&Vf÷&R‚"òò)H)Hæ'&F—fTfÆ÷t’"¢76W'EG'VR‚%cRããCSƒ¢7'—FôÇBVçG&–W2×W7B7F’—6öÆFVBg&öÒÖVÖRövÆö&ÂVçG'’ÆV&æW'2"ÂVçG'”—6òæ6öçF–ç2‚&ÖVÖRövÆö&ÂVçG'’ÆV&æW'26¶—VB"’bbVçG'”—6òæ6öçF–ç2‚$ÖWF6övæ—F–öä’ç&V6÷&DVçG'•&VF–7F–öç2"’bbVçG'”—6òæ6öçF–ç2‚%6†F÷tÆV&æ–ætVæv–æRæöåG&FT÷÷'GVæ—G’"’¢Ð   ¢FW7@¢gVâFSCSƒ$7'—FõVæ—fW'6T6Æ÷6UW6W5&VÄG–äÖ–çE6VÆÄ&6µFõ6öÂ‚’°¢fÂG&FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂÖ&¶WG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖ&¶WG4Æ—fTW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ6Æ÷6TfâÒÖ&¶WG2ç7V'7G&–ætgFW"‚'7W7VæBgVâ6Æ÷6TÆ—fU÷6—F–öâ"’ç7V'7G&–æt&Vf÷&R‚'fÂ†–çWDÖ–çBÂÖ÷VçEVæ—G2’"¢76W'EG'VR‚%cRããCSƒ#¢7'—FôÇB6Æ÷6R×W7B72F†R&VÂG–æÖ–27'—FòÖ–çB÷7–Ö&öÂ–çFòÆ—fR6Æ÷6R"ÂG&FW"æ6öçF–ç2‚&7'—FõF&vWDÖ–çD÷fW'&–FRÒ÷2æG–äÖ–çB"’bbG&FW"æ6öçF–ç2‚&7'—Fõ7–Ö&öÄ÷fW'&–FRÒÖ·E7–Ò"’¢76W'EG'VR‚%cRããCSƒ#¢Ö&¶WG2Æ—fR6Æ÷6R×W7B&VfW"7'—FõF&vWDÖ–çD÷fW'&–FRò6Æ÷6U7–Ö&öÂ÷fW"F†RE”â6VçF–æVÂ"Â6Æ÷6Tfâæ6öçF–ç2‚&7'—FõF&vWDÖ–çD÷fW'&–FR"’bb6Æ÷6Tfâæ6öçF–ç2‚'fÂ6Æ÷6U7–Ö&öÂ"’bb6Æ÷6Tfâæ6öçF–ç2‚$7'—Fõw&VD76WDÖW"ç&W6öÇfUw&VDÖ–çB†6Æ÷6U7–Ö&öÂ’"’bb6Æ÷6Tfâæ6öçF–ç2‚&Ö&¶WBÒW'4Ö&¶WBäE”â"’¢76W'EG'VR‚%cRããCSƒ#¢÷fW'&–FFVâ7'—FòF&vWBÖ–çB×W7BW6RFö¶Væ—¦VBF&vWB6Æ÷6RF‚Âæ÷BU4D2ÆVv7’fÆÆ&6²"Â6Æ÷6Tfâæ6öçF–ç2‚&Ö&¶WBæ—47'—FòÇÂ7'—FõF&vWDÖ–çD÷fW'&–FRæ—4çVÆÄ÷$&Ææ²‚’"’bb6Æ÷6Tfâæ6öçF–ç2‚"7'—FõF&vWDÖ–çD÷fW'&–FRæ—4çVÆÄ÷$&Ææ²‚’"’¢Ð   ¢FW7@¢gVâFSCSƒ$7'—FõG&FW$Æöw4ÖVÖT6ö×&&ÆTF–væ÷7F–72‚’°¢fÂgVææVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòö'&–âô7'—FôgVææVÂæ·B"’ç&VEFW‡B‚¢fÂF–rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòô7'—FõVæ—fW'6Tf÷&Vç6–72æ·B"’ç&VEFW‡B‚¢fÂF–vW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô÷W&F÷%W'47'—FôF–vW7Bæ·B"’ç&VEFW‡B‚¢fÂÖ&¶WG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖ&¶WG4Æ—fTW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂG&FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒ#¢7'—FògVææVÂ×W7BW‡÷6R÷VæVBæB6Æ÷6VBö²öf–Â6÷VçFW'2"ÂgVææVÂæ6öçF–ç2‚&÷VæVBö³Ò"’bbgVææVÂæ6öçF–ç2‚&6Æ÷6VBö³Ò"’bbgVææVÂæ6öçF–ç2‚&gVâ6Æ÷6R‡7V66W73¢&ööÆVâ’"’¢76W'EG'VR‚%cRããCSƒ#¢7'—FòVæ—fW'6Rf÷&Vç6–72×W7Bvw&VvFR†6R÷&÷WFRöF–væ÷7F–2&V6öâ6÷VçG2"ÂF–ræ6öçF–ç2‚'†6T6÷VçG2"’bbF–ræ6öçF–ç2‚&F–t6÷VçG2"’bbF–ræ6öçF–ç2‚'&÷WFT6÷VçG2"’bbF–ræ6öçF–ç2‚&gVâ7VÖÖ'’‚’"’¢76W'EG'VR‚%cRããCSƒ#¢7'—Fò6Æ÷6W2×W7BÆör7F'Bö–çWB÷7V66W72öf–ÇW&R†6W2Æ–¶RÖVÖR6VÆÂF–væ÷7F–72"ÂÖ&¶WG2æ6öçF–ç2‚$5Uô4Äõ4Uõ5D%B"’bbÖ&¶WG2æ6öçF–ç2‚$5Uô4Äõ4Uô”åUEõ$U4ôÅdTB"’bbÖ&¶WG2æ6öçF–ç2‚$5Uô4Äõ4Uôô²"’bbÖ&¶WG2æ6öçF–ç2‚$5Uô4Äõ4Uôäõõ4”täEU$R"’bbÖ&¶WG2æ6öçF–ç2‚$5Uô4Äõ4UõD$tUEô%4TåB"’¢76W'EG'VR‚%cRããCSƒ#¢7'—FôÇB6Æ÷6R×W7BfVVB7'—FôgVææVÂ6Æ÷6R6÷VçFW'2"ÂG&FW"æ6öçF–ç2‚$7'—FôgVææVÂæ6Æ÷6R†6Æ÷6U7V66W73cCƒb’"’¢76W'EG'VR‚%cRããCSƒ#¢÷W&F÷"7'—FòF–vW7B×W7B7W&f6R'&–FvRF–væ÷7F–72v—F‚Æör×&—G’Ö&¶W""ÂF–vW7Bæ6öçF–ç2‚&'&–FvTF–r"’bbF–vW7Bæ6öçF–ç2‚$7'—FõVæ—fW'6Tf÷&Vç6–72ç7VÖÖ'’"’bbF–vW7Bæ6öçF–ç2‚&7'—FõöÆöu÷&—G“×G'VR"’¢Ð   ¢FW7@¢gVâFSCSƒE6÷W&6T6†ö¶TF–væ÷7F–74æDÆV&æ–æuV&çF–æR‚’°¢fÂF–rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6÷W&6T6†ö¶TF–væ÷7F–73CSƒBæ·B"’ç&VEFW‡B‚¢fÂ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–ætÆ–fV7–6ÆT'W2æ·B"’ç&VEFW‡B‚¢fÂ7F÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT†—7F÷'•7F÷&Ræ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂÖVÒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶Våv–äÖVÖ÷'’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒC¢&RÔdDrÆ–fV7–6ÆR'W2×W7Bvw&VvFRÆæR÷6÷W&6R÷&V6öâ6†ö¶RF–væ÷7F–72"ÂF–ræ6öçF–ç2‚&gVâ&TfFr"’bb'W2æ6öçF–ç2‚%6÷W&6T6†ö¶TF–væ÷7F–73CSƒBç&TfFr"’bb&W÷'Bæ6öçF–ç2‚%6÷W&6T6†ö¶TF–væ÷7F–73CSƒBç7VÖÖ'’"’¢76W'EG'VR‚%cRããCSƒC¢6VÆÂö²Fò¦÷W&æÂ×W7BW‡÷6R¦÷W&æÆVBöFVGW÷V&çF–æR&V6öç2"ÂF–ræ6öçF–ç2‚&gVâ6VÆÄ¦÷W&æÂ"’bb7F÷&Ræ6öçF–ç2‚&GWÆ–6FU÷7W&W76VB"’bb7F÷&Ræ6öçF–ç2‚&¦÷W&æÆVEò"²"B"²'·G&FUFõ7F÷&Rç6–FRçWW&66R‚—Ò"’bb7F÷&Ræ6öçF–ç2‚&66÷VçF–æu÷V&çF–æVB"’¢76W'EG'VR‚%cRããCSƒC¢7F÷ÖÆ÷72÷fW''VâF–væ÷7F–72×W7B&V6÷&BG&–vvW"æBf–æÆ—G’ÆFVæ7’"ÂF–ræ6öçF–ç2‚'7F÷G&–vvW&VB"’bbF–ræ6öçF–ç2‚'7F÷f–æÆ—¦VB"’bb'W2æ6öçF–ç2‚%6÷W&6T6†ö¶TF–væ÷7F–73CSƒBç7F÷G&–vvW&VB"’bb7F÷&Ræ6öçF–ç2‚%6÷W&6T6†ö¶TF–væ÷7F–73CSƒBç7F÷f–æÆ—¦VB"’¢76W'EG'VR‚%cRããCSƒC¢Fö¶Våv–äÖVÖ÷'’×W7BV&çF–æR6†F÷r÷6–×VÆFVB6÷W&6W2g&öÒ&VÂv–ææW"ÖVÖ÷'’"ÂÖVÒæ6öçF–ç2‚&—56†F÷t÷%6–×VÆFVE6÷W&6R"’bbÖVÒæ6öçF–ç2‚%Dô´Tåõt”åôÔTÔõ%•õ4„Dõuõ4õU$4R"’bbÖVÒæ6öçF–ç2‚%U%4•5DTEõ4õU$4UõEDU$åõ4„Dõr"’¢Ð ¢FW7@¢gVâFSCSƒEF÷†–4ÆæU—f÷D—57G&FVw”f—'7Dæ÷DÖ–7&õ&ö&TöæÇ’‚’°¢fÂGVæW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU7G&FVw•GVæW"æ·B"’ç&VEFW‡B‚¢fÂ&÷WFW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôvVçF–57G–ÆU&÷WFW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒC¢F÷†–2Æ—fRÆæW2×W7B&W÷'B&V6Æ–ÒF7F–2—f÷BÂæ÷BöÆB–ææW"ÖÆæRÖ–7&ò×&ö&Rv÷&F–ær"ÂGVæW"æ6öçF–ç2‚'F÷†–5÷&V6Æ–Õ÷F7F–5÷—f÷B"’bbGVæW"æ6öçF–ç2‚&Æ&VÂÒ–b‡F÷†–4–ææW$ÆæU—f÷B’"²%Â'F÷†–5ö–ææW%öÆæU÷—f÷EÂ""’¢76W'EG'VR‚%cRããCSƒC¢vVçF–57G–ÆU&÷WFW"×W7B6öçfW'BF÷†–2GVæ–ær–çFòÆæRÖÆö6Â&V6Æ–ÒöÆ—V–F—G’7G–ÆR"Â&÷WFW"æ6öçF–ç2‚%Dõ„”5õ$T4Ä”ÕõD5D”2"’bb&÷WFW"æ6öçF–ç2‚%TÄÄ$4µõ$T4Ä”Ò"’bb&÷WFW"æ6öçF–ç2‚$Ä•T”D•E•ôDUD‚"’bb&÷WFW"æ6öçF–ç2‚'F÷†–5F7F–5—f÷CCSƒB"’¢Ð   ¢FW7@¢gVâFSCSƒT6öÖÖöå6Vç6UVæ¶æ÷vå6fWG•—f÷G4æ÷DÆæT6†ö¶W2‚’°¢fÂÆ–&öö²Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô6öÖÖöå6Vç6UG&FUÆ–&öö²æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒS¢G'VR†&B6fWG’×W7B&VÖ–âFW&Ö–æÂ6öÖÖöâ×6Vç6R&V¦V7B"ÂÆ–&öö²æ6öçF–ç2‚%E%TUô„$Eõ4dUE•ôõ%ô„ôÄDU%õ$•4²"’bbÆ–&öö²æ6öçF–ç2‚&†&E6fWG”&Æö6¶VB"’bbÆ–&öö²æ6öçF–ç2‚&†öÆFW$†&E&—6²"’¢76W'EG'VR‚%cRããCSƒRóc#¢&÷f–FW"Ö&Æ–æB6fWG’ö†öÆFW"Væ6W'F–çG’×W7B6†R÷—f÷BF‡&÷Vv‚fÇV–B66÷&W2–ç7FVBöb6†ö¶–ærÆÂÆæW2"ÂÆ–&öö²æ6öçF–ç2‚%4dUE•ô„ôÄDU%õTä4ôäd•$ÔTEõD5D”5õ•dõB"’bbÆ–&öö²æ6öçF–ç2‚'&÷f–FW$&Æ–æE6fWG’"’bbÆ–&öö²æ6öçF–ç2‚&fÇV–E66÷&Sc#ƒSRã’"’¢76W'EG'VR‚%cRããCSƒS¢öÆB4dUE•ôõ%ô„ôÄDU%õ$•4²6†÷VÆB&VÖ–âöæÇ’2æöâ×G&FV&ÆRfÆÆ&6²Âæ÷Bf—'7B†&B'&æ6‚"ÂÆ–&öö²æ–æFW„öb‚&gVâÆÆ÷u6†VB"’ÂÆ–&öö²æ–æFW„öb‚%4dUE•ô„ôÄDU%õTä4ôäd•$ÔTEõD5D”5õ•dõB"’bbÆ–&öö²æ–æFW„öb‚%4dUE•ô„ôÄDU%õTä4ôäd•$ÔTEõD5D”5õ•dõB"’ÂÆ–&öö²æÆ7D–æFW„öb‚%4dUE•ôõ%ô„ôÄDU%õ$•4²"’¢Ð ¢FW7@¢gVâFSCSƒU&VÄÖöæW”æ÷F–f–6F–öç5&WV—&UvÆÆWDf–æÆ—G”æD66÷VçF–ær‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããCSƒS¢6—FÂ÷&öf—Bæ÷F–f–6F–öç2×W7B&RFVfW'&VBVçF–ÂÆ—fR6VÆÂf–æÆ—G’"ÂW†V2æ6öçF–ç2‚$4•DÅõ$T4õdU%•ôäõD”e•ôDTdU%$TEõTåD”Åôd”äÄ•E•óCSƒR"’bbW†V2æ6öçF–ç2‚%$ôd•EôÄô4µôäõD”e•ôDTdU%$TEõTåD”Åôd”äÄ•E•óCSƒR"’¢76W'EG'VR‚%cRããCSƒS¢6—FÂ&V6÷fW&VB7FFR×W7B&WV—&RfW&–f–VB4ôÂ&ö6VVG2Fò6÷fW"÷&–v–æÂ6÷7B"ÂW†V2æ6öçF–ç2‚'&VÆ—¦VD6—FÅ&V6÷fW'“CSƒR"’bbW†V2æ6öçF–ç2‚'6öÄ&6²ãÒ÷2æ6÷7E6öÂ¢ã“‚"’bbW†V2æ6öçF–ç2‚$4•DÅõ$T4õdU%•õ5DDUõ5U$U54TEõTå$TÄ•¤TEóCSƒR"’¢76W'EG'VR‚%cRããCSƒS¢&VÂÖÖöæW’æ÷F–f–6F–öç2×W7BW6RvÆÆWBÖf–æÆ—¦VB6öÄ&6²öæWEæÂæBFVGWR'’6VÆÂ¶W’"ÂW†V2æ6öçF–ç2‚'&VÆ—¦VDÖöæW”æ÷F–f–VE6VÆÄ¶W—3CSƒR"’bbW†V2æ6öçF–ç2‚$4•DÅõ$T4õdU%•ôäõD”e•õ$TÄ•¤TEóCSƒR"’bbW†V2æ6öçF–ç2‚%$TÅôÔôäU•ôäõD”e•ôEUõ5U$U54TEóCSƒR"’¢76W'DfÇ6R‚%cRããCSƒS¢&RÖf–æÆ—G’G&–vvW"F‚×W7Bæ÷B6Æ–Ò–æ—F–Â–çfW7FÖVçB6V7W&VB"ÂW†V2æ6öçF–ç2‚&–æ—F–Â–çfW7FÖVçB6V7W&VB"’¢Ð  ¢FW7@¢gVâFSc•vÆÆWE&öödw&6TæE&öGV7F—fTfæ÷WD&Tæ÷DfVÇG2‚’°¢fÂ'VçF–ÖU6æc’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ'VçF–ÖU7FFU6æ6†÷Bæ·B"’ç&VEFW‡B‚¢fÂwV&F–ãc’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô–çf&–çDwV&F–âæ·B"’ç&VEFW‡B‚¢fÂ†÷7Cc’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô†÷7EvÆÆWEFö¶VåG&6¶W"æ·B"’ç&VEFW‡B‚¢fÂW†V3c’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‡'VçF–ÖU6æc’æ6öçF–ç2‚&÷Väv—F–æuvÆÆWE&ööb"’bbwV&F–ãc’æ6öçF–ç2‚'vÆÆWE&öödÆÆ÷væ6Sc’"’bbwV&F–ãc’æ6öçF–ç2‚&VffV7F—fTÆVFvW$G&–gCc’"’bb†÷7Cc’æ6öçF–ç2‚&vWD÷Väv—F–æuvÆÆWE&ööd6÷VçB"’bbW†V3c’æ6öçF–ç2‚'&öödw&6Sc’"’¢76W'EG'VR†wV&F–ãc’æ6öçF–ç2‚'&öGV7F—fTfæ÷WCc’"’bbwV&F–ãc’æ6öçF–ç2‚&ÆæU&F–òâ‚ã"’bbwV&F–ãc’æ6öçF–ç2‚&ÆæU&F–òâ"ãbb&öGV7F—fTfæ÷WCc’"’¢Ð ¢FW7@¢gVâFScs6–ævÆU&÷f–FW%¦W&õ&WV—&W5Gvõ&VD6÷'&ö&÷&F–öä&Vf÷&TFVÖ÷F–öâ‚’°¢fÂ†÷7CcsÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô†÷7EvÆÆWEFö¶VåG&6¶W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcs¢6–ævÆR×&÷f–FW"¦W&òöæò×6VÆÂ×6–r'&æ6‚×W7B&WV—&R"6öç6V7WF—fR&VG2&Vf÷&RFVÖ÷F–ær7F–ÆÂÖ†VÆB÷6—F–öâ÷WBöbõTåõ5DEU4U2"Â†÷7Ccsæ6öçF–ç2‚%4”ätÄUõ$õd”DU%õ¤U$õô4õ%$ô$õ$D”ôåõTäD”äuócs"’bb†÷7Ccsæ6öçF–ç2‚&–b‡æ6öç6V7WF—fU¦W&ô6öæf—&×2Â"’"’bb†÷7Ccsæ6öçF–ç2‚&Ö&´æô7W'&VçD†VÆE&ööb‡ÂÂ%4”ätÄUõ$õd”DU%õ¤U$õôäõõ4TÄÅõ4”uÂ"’"’¢76W'EG'VR‚%cRããcs¢vVæW&ÂöæR×&÷f–FW"×¦W&òÖ–âÖfÆ–v‡B'&æ6‚×W7BÇ6ò&WV—&R"×&VB6÷'&ö&÷&F–öâÂæ÷B7BöâF†Rf—'7B&VB"Â†÷7Ccsæ6öçF–ç2‚$ôäUõ$õd”DU%õ¤U$õô4õ%$ô$õ$D”ôåõTäD”äuócs"’bb†÷7Ccsæ6öçF–ç2‚&Ö&´æô7W'&VçD†VÆE&ööb‡ÂÂ$ôäUõ$õd”DU%õ¤U$õô”åôdÄ”t…EÂ"’"’¢76W'EG'VR‚%cRããcs¢6÷'&ö&÷&F–öâ6÷VçFW"×W7B–æ7&VÖVçB&Vf÷&RF†RFVÖ÷F–öâ'&æ6‚f—&W2ÂÖF6†–ærF†R%4TåEôÔ”åBõ4TÄÅõdU$”e””är"×&VBÆFFW"Ç&VG’W6VBVÇ6Wv†W&R–âF†—2f–ÆR"Â†÷7Ccsæ6öçF–ç2‚'æ6öç6V7WF—fU¦W&ô6öæf—&×2³Ò"’bb†÷7Ccsæ6öçF–ç2‚'æ6öç6V7WF—fU¦W&ô6öæf—&×2ãÒ""’¢Ð  ¢FW7@¢gVâFScsu76”v•GVæW4g&öÕG&FTöæTæô&ö÷G7G&7GVF÷$6Æ–fg2‚’°¢fÂÖWFÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôWFöæöÖ÷W4ÖWFöÆ–7’æ·B"’ç&VEFW‡B‚¢fÂGVæW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU7G&FVw•GVæW"æ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖWF6övæ—F–öäW†V7WF÷$'&–FvRæ·B"’ç&VEFW‡B‚¢fÂW‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõVæ–f–VEöÆ–7”†VBæ·B"’ç&VEFW‡B‚¢fÂ&ö"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU&ö&&–Æ—G”Væv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcss¢WFöæöÖ÷W4ÖWFöÆ–7’×W7B6†Rg&öÒF†Rf—'7B6WGFÆVB6öçFW‡B–ç7FVBöb&WGW&æ–æræWWG&ÂVçF–ÂÔ”åõ4ÕÄU2"ÂÖWFæ6öçF–ç2‚&–b†&Òç6×ÆW2ÃÒ’&WGW&âã"’bbÖWFæ6öçF–ç2‚%E$DSõ$ÕôdÄôõ""’bbÖWFæ6öçF–ç2‚'G&FS&×csr"’bbÖWFæ6öçF–ç2‚&–b†&Òç6×ÆW2ÂÔ”åõ4ÕÄU2’&WGW&âã"’¢76W'EG'VR‚%cRããcss¢Æ—fU7G&FVw•GVæW"×W7BFÖ—BãÓâãBÆæW2F‡&÷Vv‚&÷VæFVBG&FRÓ&×–ç7FVBöb6¶—–ærÆÂÆæW2VæFW"f—fR6Æ÷6W2"ÂGVæW"æ6öçF–ç2‚&–b†ÒçG&FW2ÃÒ’6öçF–çVR"’bbGVæW"æ6öçF–ç2‚&–b†â–âVçF–ÂÔ”åõETäUõE$DU2’"’bbGVæW"æ6öçF–ç2‚'G&FS÷÷6—F—fU÷&×ócsr"’bbGVæW"æ6öçF–ç2‚'G&FS÷&—6µ÷&×ócsr"’bbGVæW"æ6öçF–ç2‚&–b†ÒçG&FW2ÂÔ”åõETäUõE$DU2’6öçF–çVR"’¢76W'EG'VR‚%cRããcss¢ÖWF6övæ—F–öâW†V7WF÷"'&–FvR×W7B&VÖ÷fRF†R3×G&FRæWWG&Â6Æ–fbæB&×V&Ç’G'W7B×VÇF—Æ–W'2"Â'&–FvRæ6öçF–ç2‚&–b†æÇ—¦VCcsrÃÒ’&WGW&âã"’bb'&–FvRæ6öçF–ç2‚'G&FS&×csr"’bb'&–FvRæ6öçF–ç2‚&vWEF÷FÅG&FW4æÇ—¦VB‚’Â3’&WGW&âã"’¢76W'EG'VR‚%cRããcss¢Væ–f–VEöÆ–7”†VBæBÆ—fU&ö&&–Æ—G”Væv–æR×W7B&ÆVæB&ö÷G7G&öÆ–7’6–væÇ2g&öÒf—'7BG&–æ–ær6×ÆRÂæ÷B¦W&ò×vV–v‡B&ö÷G7G&"ÂW‚æ6öçF–ç2‚'G&–æVDf÷%&×csr"’bbW‚æ6öçF–ç2‚'G&FS&×csr"’bb&ö"æ6öçF–ç2‚'öÆ–7•6×ÆW3csr"’bb&ö"æ6öçF–ç2‚'öÆ–7•rÒ–b…Væ–f–VEöÆ–7”†VBæf÷&ÖDf÷%—VÆ–æTGV×‚’"’¢Ð   ¢FW7@¢gVâFScs…V”¦÷W&æÅ&VF–æW746÷”æE&W7VÇDÆV&æ–æuG'WF‚‚’°¢fÂ7F÷&RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT†—7F÷'•7F÷&Ræ·B"’ç&VEFW‡B‚¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂW'"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôW'&÷$Æöt7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÆ"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆ"ôÆÆÔÆ$Væv–æRæ·B"’ç&VEFW‡B‚¢fÂ76’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ76•–Æ÷D6÷Væ6–Âæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcsƒ¢T’÷&VF–æW727FG2×W7BW6R7G&FVw•G'WF„ÆVFvW"Ö6ÆVâG'WF‚æBæòÖFF×W7Bæ÷Bf¶RSRu""Â7F÷&Ræ6öçF–ç2‚'&WGW&âG'’²vWD6ÆVå7FG56æ6†÷CCSr‚’Ò"’bb7F÷&Ræ6öçF–ç2‚'fÂv–å&FS¢F÷V&ÆRÒã"’bb7F÷&Ræ6öçF–ç2‚'fÂft†öÆEF–ÖTÖ–çWFW3¢–çBÒ"’bb7F÷&Ræ6öçF–ç2‚&VÇ6RSã"’¢76W'EG'VR€¢%cRããcc3c¢÷Vâ÷6—F–öç26öçG&7B×W7B&W6W'fRF†RF÷×FVâöfö÷FW"ÖöFVÂv†–ÆRvw&VvF–æröæÇ’6æöæ–6ÂG'W7FVBäÂ"À¢Ö–âæ6öçF–ç2‚$÷Vå÷6—F–öç4ÖöFVÃcs‚"’b`¢Ö–âæ6öçF–ç2‚&66†VD÷Vå÷6—F–öç4ÖöFVÃcs‚"’b`¢Ö–âæ6öçF–ç2‚'&V6ö×WFUF÷FÅWæÃcc3b"’b`¢Ö–âæ6öçF–ç2‚$÷VåæÅ6æ—G’ç&–6–æuG'WF‚"’b`¢Ö–âæ6öçF–ç2‚'&U6÷'FVCcs‚ÒG'VR"’b`¢Ö–âæ6öçF–ç2‚'fÂ$TäDU%ô4ÒõTåõ5õ$õuô4"’À¢¢76W'EG'VR‚%cRããcsƒ¢'VçF–ÖR&W÷'BW‡÷'B'WGFöâ×W7BÇv—26÷’âö'6W'f&ÆRVæ–f–VB×&W÷'BfÆÆ&6²"ÂW'"æ6öçF–ç2‚%Tä”d”TEõ$Uõ%EôU…õ%Eô4Ä”4µócs‚"’bbW'"æ6öçF–ç2‚%Væ–f–VB&W÷'B6÷–VB"’bbW'"æ6öçF–ç2‚%—VÆ–æT†VÇF„6öÆÆV7F÷"æGV×FW‡B‚’çF¶Rƒ#Eó’"’¢76W'EG'VR‚%cRããcsƒ¢ÆÂ6VÆÂÖÆ–¶R&W7VÇG2×W7BfVVBÄÄÒõ54’6öçFW‡Bv—F‚66WFVB÷G&–æ&ÆRfÆw2v†–ÆRöÆ–7’†VG2&VÖ–â6ÆVâÖvFVB"ÂW†V2æ6öçF–ç2‚$ÄÅõ$U5TÅEô4ôåDU…Eôô%4U%dTEócs‚"’bbW†V2æ6öçF–ç2‚'&V6÷&DW‡FW&æÄ÷WF6öÖScs‚"’bbÆ"æ6öçF–ç2‚&W‡FW&æÄ÷WF6öÖU7VÖÖ'“cs‚"’bb76’æ6öçF–ç2‚%$U5TÅE3cs‚"’¢76W'EG'VR‚%cRããcsƒ¢Æ—fR÷6—F–öç2×W7B&W6W'fRvVçF–57G–ÆU&÷WFW"7G–ÆR7W&f6R–ç7FVBöb6öÆÆ6–ærFòvVæW&–2ÆæRVÖö¦’"ÂW†V2æ6öçF–ç2‚'&W6W'fRF†RgVÆÂvVçF–57G–ÆU&÷WFW"7G–ÆR7W&f6R"’bbW†V2æ6öçF–ç2‚'G&F–ætÖöFTVÖö¦’ÒÆ—7Döb"’bbW†V2æ6öçF–ç2‚'&÷WFVE7G–ÆUFræ–d&Ææ²"’¢Ð  ¢FW7@¢gVâcUóóc3…÷—VÆ–æU÷&W÷'EövVæW&F–öåö†5÷vF6†FöuöfÆÆ&6µöæEöÖ–åö6Æ—&ö&B‚’°¢fÂV’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’õ—VÆ–æT†VÇF„7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3ƒ¢—VÆ–æR&W÷'BvVæW&F–öâ×W7BæWfW"6–ÆVçFÇ’†ærgFW"f—'7BFö7B"À¢V’æ6öçF–ç2‚$uT$åDTTB•TÄ”äR$Uõ%BtTäU$D”ôâ"’bbV’æ6öçF–ç2‚%Tä”d”TEõ$Uõ%EõtD4„DôuôdÄÄ$4µóc3‚"’bbV’æ6öçF–ç2‚&gVÆÅö'V–ÆFW%÷F–ÖV÷WEó‡2"’¢76W'EG'VR‚%cRããc3ƒ¢6Æ—&ö&Bw&—FW2×W7B†Vâg&öÒÖ–ä†æFÆW"FVÆ—fW'’Âæ÷BF†R&W÷'B'V–ÆFW"F‡&VB"À¢V’æ6öçF–ç2‚&gVâFVÆ—fW%&W÷'Cc3‚"’bbV’æ6öçF–ç2‚&Ö–ä†æFÆW"ç÷7B"’bbV’æ6öçF–ç2‚&6"ç6WE&–Ö'”6Æ—"’bbV’æ6öçF–ç2‚%&W÷'BvVæW&FVB'WB6Æ—&ö&Bf–ÆVB"’¢76W'EG'VR‚%cRããc3ƒ¢vVæW&FVB&W÷'B×W7BÇ6ò&VæFW"öâ×67&VVâ–â&÷VæFVB6V7F–öç2"À¢V’æ6öçF–ç2‚&gVÆÄGV×66†RÒ6fUFW‡B"’bbV’æ6öçF–ç2‚'&W÷'E6V7F–öç2Ò7Æ—DGV×–çFõ6V7F–öç2‡6fUFW‡B’"’bbV’æ6öçF–ç2‚'&VæFW$7W'&VçE6V7F–öâ‚’"’¢76W'EG'VR‚%cRããc3ƒ¢fÆÆ&6²&W÷'B×W7B–æ6ÇVFRF†R6†ö¶RÖ7&—F–6Â—VÆ–æR7W&f6W2"À¢V’æ6öçF–ç2‚$DR•TÄ”äRTÔU$tTä5’$Uõ%BcRããc3‚"’bbV’æ6öçF–ç2‚$dDr$Äô4²$T4ôå2"’bbV’æ6öçF–ç2‚$Ä•dR%U’d”Â$T4ôå2"’bbV’æ6öçF–ç2‚$”åD´R%’4õU$4R"’bbV’æ6öçF–ç2‚%$T4TåBUdTåE2"’¢Ð  ¢FW7@¢gVâcUóóc3c•÷W%öÆæUöfæ÷WEö6ææ÷EöGWÆ–6FUö÷Våö÷%ö'&öEö66†vVå÷&W67VR‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW$'W’ÒW†V2ç7V'7G&–ær†W†V2æ–æFW„öb‚&gVâW$'W’‚"’ÂW†V2æ–æFW„öb‚"òòF†—2'Vç2&Vf÷&Rw&W"U%ô%U’"ÂW†V2æ–æFW„öb‚&gVâW$'W’‚"’’¢76W'EG'VR‚%cRããc3c“¢W$'W’×W7B7V—&RâV&Ç’W"ÖÖ–çB%U’ÆV6R&Vf÷&RG&FT–Bö÷Vâ×WFF–öâ6òÆæRfæ÷WB6ææ÷BF÷V&ÆRÖ÷VâF†R6ÖRW"Ö–çB"À¢W$'W’æ6öçF–ç2‚%U"%U’däõUB$4R4Ä”Ò"’b`¢W$'W’æ6öçF–ç2‚$W†V7WF–öäGFV×DÆV6Ræ7V—&R"’b`¢W$'W’æ–æFW„öb‚$W†V7WF–öäGFV×DÆV6Ræ7V—&R"’ÂW$'W’æ–æFW„öb‚'fÂG&FT–BÒ"’b`¢W$'W’æ6öçF–ç2‚%U%ô%U•ôEUÄ”4DUõ5U$U54TEóc3c’"’¢76W'EG'VR‚%cRããc3c“¢W$'W’×W7B&VÆV6RF†RV&Ç’ÆV6Röâæ÷BÖ÷VæVBF‡2æB6ÆV"—Böâ7V66W76gVÂ÷Vâ"À¢W$'W’æ6öçF–ç2‚$W†V7WF–öäGFV×DÆV6Rç&VÆV6TæöåFW&Ö–æÂ"’b`¢W$'W’æ6öçF–ç2‚%U%ô%U•ôäõEôõTäTEò"’b`¢W$'W’æ6öçF–ç2‚$W†V7WF–öäGFV×DÆV6RçFW&Ö–æÄö²"’b`¢‡W$'W’æ6öçF–ç2‚%U%ô%U•ôõTäTEóc3c’"’ÇÂW$'W’æ6öçF–ç2‚%U%ô%U•ôõTäTEóc3s"’’¢76W'EG'VR‚%cRããcS““¢W"7V6–Æ—7BW†V7WF–öâ—2F†RöæRVÆ–f–VB6æöæ–6Â&–Ö'’"À¢&÷Bæ6öçF–ç2‚&&÷VæFVE&W67VScc"’bb&÷Bæ6öçF–ç2‚'7V6–Æ—7DWfÇVF–öäÆÆ÷vVCcc"’b`¢&÷Bæ6öçF–ç2‚$4ôåE$”%UDõ%ôôäÅ’"’bb&÷Bæ6öçF–ç2‚'7G&öævW7DFW6³cS“’"’¢Ð  ¢FW7@¢gVâcUóóc3s÷W%ö'W•ö&Æö6·5÷6ÖUöÖ–çEöÆ–5ö&Vf÷&U÷&–6U÷v÷&²‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂW$'W’ÒW†V2ç7V'7G&–ær†W†V2æ–æFW„öb‚&gVâW$'W’‚"’ÂW†V2æ–æFW„öb‚"òòF†—2'Vç2&Vf÷&Rw&W"U%ô%U’"ÂW†V2æ–æFW„öb‚&gVâW$'W’‚"’’¢76W'EG'VR‚%cRããc3s¢W$'W’×W7B6öç7VÇBvÆö&ÂVÖW&vVçDwV&G&–Ç2÷væW"&Vf÷&R&–6R÷6—¦Rv÷&²6ò6W&FRFö¶Vå7FFRÆ–6W26ææ÷B&V÷VâF†R6ÖRÖ–çBgFW"F†R6†÷'BÆV6RW‡—&W2"À¢W$'W’æ6öçF–ç2‚$tÄô$Â4ÔRÔÔ”åBU"õTâuT$B"’b`¢W$'W’æ6öçF–ç2‚$VÖW&vVçDwV&G&–Ç2ævWE÷6—F–öäÆ–W"‡G&FT–BæÖ–çB’"’b`¢W$'W’æ–æFW„öb‚$VÖW&vVçDwV&G&–Ç2ævWE÷6—F–öäÆ–W"‡G&FT–BæÖ–çB’"’ÂW$'W’æ–æFW„öb‚'fÂ&–6RÒvWD7GVÅ&–6R‡G2’"’b`¢W$'W’æ6öçF–ç2‚%U%ô%U•õ4ÔUôÔ”åEôõTåõ5U$U54TEóc3s"’¢fÂ&Vv—7G'•w&—FSc3sÒW$'W’æ–æFW„öb‚$VÖW&vVçDwV&G&–Ç2ç&Vv—7FW%÷6—F–öâ"¢fÂvÆö&Åw&—FSc3sÒW$'W’æ–æFW„öb‚$vÆö&ÅG&FU&Vv—7G'’ç&Vv—7FW%÷6—F–öâ"Â&Vv—7G'•w&—FSc3s¢fÂ÷VæVEFW&Ö–æÃc3sÒW$'W’æ–æFW„öb‚&Ö&µW%F–6¶WEFW&Ö–æÄ÷Vâ"²#cSB"ÂvÆö&Åw&—FSc3s¢fÂFW&Ö–æÄ†VÇW#c3sÒW$'W’ç7V'7G&–ær‡W$'W’æ–æFW„öb‚&gVâÖ&µW%F–6¶WEFW&Ö–æÄ÷Vâ"²#cSB"’ÂW$'W’æ–æFW„öb‚"òòcRããcCS"ÂW$'W’æ–æFW„öb‚&gVâÖ&µW%F–6¶WEFW&Ö–æÄ÷Vâ"²#cSB"’’¢76W'EG'VR‚%cRããc3s¢7V66W76gVÂæWrÖ÷VâÆV6R×W7B6ÆV"öæÇ’gFW"VÖW&vVçDwV&G&–Ç2æBvÆö&ÅG&FU&Vv—7G'’&Vv—7G&–W3²&WG'’&V6÷fW'’Ö’FW&Ö–æÆ—¦R—G2W†—7F–ær&W7VÇBV&Æ–W""À¢&Vv—7G'•w&—FSc3sãÒbbvÆö&Åw&—FSc3sâ&Vv—7G'•w&—FSc3sbb÷VæVEFW&Ö–æÃc3sâvÆö&Åw&—FSc3sb`¢FW&Ö–æÄ†VÇW#c3sæ6öçF–ç2‚$W†V7WF–öäGFV×DÆV6RçFW&Ö–æÄö²"’b`¢W$'W’æ6öçF–ç2‚%U%ô%U•ôõTäTEóc3s"’¢Ð  ¢FW7@¢gVâcUóóc3sö÷VåövFU÷6ÖUöÖ–çE÷W%ö6ööÆF÷våöæEöv†÷7E÷¦W&õöÖ–çEööæÇ•öÆö6¶÷WB‚’°¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂ&VVçG'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&TVçG'”Æö6¶÷WBæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3s¢6ÖRÖÖ–çBU"GWÆ–6FW2×W7B&R&Æö6¶VB–âW†V7WF&ÆT÷VävFR&Vf÷&RW$'W’6ò&Æö6¶VB‚’–ç7FÆÇ26ööÆF÷vâæB&WVFVBÆ–6W27F÷'W&æ–ær'W’×F‚v÷&²"À¢vFRæ6öçF–ç2‚$õTâÔtDR4ÔRÔÔ”åBU"4ôôÄDõtâ"’b`¢vFRæ6öçF–ç2‚$VÖW&vVçDwV&G&–Ç2ævWE÷6—F–öäÆ–W"†Ö–çB’"’b`¢vFRæ6öçF–ç2‚$U„T5ôõTåõ4ÔUôÔ”åEôÅ$TE•ôõTåô4ôôÄDõtåóc3s"’b`¢vFRæ6öçF–ç2‚$U„T5ôõTåô$Äô4´TEõ4ÔUôÔ”åEôÅ$TE•ôõTåóc3s"’b`¢vFRæ–æFW„öb‚$õTâÔtDR4ÔRÔÔ”åBU"4ôôÄDõtâ"’ÂvFRæ–æFW„öb‚%4„DõuõE$”åôôäÅ’—2äõBâW†V7WF–öâfWFò"’¢76W'EG'VR‚%cRããc3s¢t„õ5Eõ$Tõ¤U$õô$Ää4R×W7BÖ–çBÖÆö6²öæÇ’Âæ÷BfÖ–Ç’ÖÆö6²Â6òv†÷7B6ÆVçWFöW2æ÷B6†ö¶Rg&W6‚fÖ–Ç’6æF–FFW2"À¢&VVçG'’æ6öçF–ç2‚&v†÷7E¦W&ô6ÆVçWc3s"’b`¢&VVçG'’æ6öçF–ç2‚%$TTåE%•ôÄô4´õUEô$ÔTEôÔ”åEôôäÅ•ôt„õ5Eõ¤U$õóc3s"’b`¢&VVçG'’æ6öçF–ç2‚&–b‡7–Ö&öÄfÖ–Ç’æ—4æ÷D&Ææ²‚’bbv†÷7E¦W&ô6ÆVçWc3s’'”fÖ–Ç’"’b`¢&VVçG'’æ6öçF–ç2‚&fÖ–Ç”Æö6¶VCÒ"²"G²rBwÒ"²'²v†÷7E¦W&ô6ÆVçWc3sÒ"’¢Ð  ¢FW7@¢gVâcUóóc3s%÷Væ—fW'6Åö6ö×÷VæE÷F&vWEöÆ–W5öÆÅ÷&VÅöÆæW5öÆ—fUöæE÷W"‚’°¢fÂF&vWBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÖVÖT6ö×÷VæEF&vWCc#Sbæ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããc3s#¢'‚ÓW‚6ö×÷VæBöÆ–7’×W7B&RVæ—fW'6Â7&÷72Æ—fR÷W"æBÆÂ&VÂÆæW2Âæ÷BÖVÖRÖöæÇ’"À¢F&vWBæ6öçF–ç2‚%Tä•dU%4Â'Ž(	3W‚D”Å’4ôÕõTäBôÄ”5’"’b`¢F&vWBæ6öçF–ç2‚$å’ÖöFR"’b`¢F&vWBæ6öçF–ç2‚&Æ—fR÷"W""’b`¢F&vWBæ6öçF–ç2‚""&–b†ÆæUWæ—4&Ææ²‚’ÇÂÆæUWÓÒ%Tä´äõtâ"’&WGW&âã"""’b`¢F&vWBæ6öçF–ç2‚"""ÆæUWæ6öçF–ç2‚$ÔTÔR"’"""’b`¢F&vWBæ6öçF–ç2‚$æöâÔÔTÔRÆæW2vWBã9r"’¢76W'EG'VR‚%cRããc3s#¢W†V7WF÷"6—¦–ær×W7B×VÇF—Ç’F†RVæ—fW'6ÂF&vWBF‡&÷Vv‚W$'W’fÇV–B6—¦–ærFVÆVÖWG'’"À¢W†V2æ6öçF–ç2‚%Tä•dU%4Â'‚ÓW‚F–Ç’6ö×÷VæBF&vWB"’b`¢W†V2æ6öçF–ç2‚'Væ—fW'6ÅF&vWD×VÇCc3s""’b`¢W†V2æ6öçF–ç2‚'Væ—fW'6ÅFwCc3s""’b`¢W†V2æ6öçF–ç2‚&ÖVÖUFwCÒ"’¢76W'EG'VR‚%cRããc3s#¢&W÷'G2×W7BW‡÷6RF†RVæ—fW'6ÂF&vWBÆ&VÂ–ç7FVBöbÖVÖRÖöæÇ’ÆæwVvR"À¢&W÷'Bæ6öçF–ç2‚'Væ—fW'6Åö6ö×÷VæE÷F&vWEóc3s""’b`¢F&vWBæ6öçF–ç2‚%cRããc3s%õTä•dU%4Åô4ôÕõTäEõD$tUB"’¢Ð  ¢FW7@¢gVâcUóócCCe÷6Æ÷uö7–6ÆU÷6÷W&6Uö6†ö¶W5ö&Uö'VFvWFVEö&Vf÷&U÷7WW'f—6÷"‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ6ÖTÖ–çBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ6ÖTÖ–çDFVGWWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂ66ææW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ66ææW$fæ÷WDFVGWSc3sBæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCCc¢6Æ÷rÖ7–6ÆRtD4„Ä•5Eõ$”õ$•D•¤TB×W7B&R'VFvWFVB&Vf÷&R7WW'f—6÷"–ç7FVBöbgVÆÂ×66÷&–ærWfW'’Ö–çBWfW'’F–6²"À¢&÷Bæ6öçF–ç2‚%tD4„Ä•5Eõ$”õ$•E•ô%TDtUEô%•55ócCCb"’b`¢&÷Bæ6öçF–ç2‚&Æ7E&Wd7–6ÆT×3cC#â3óÂ"’b`¢&÷Bæ6öçF–ç2‚&Æö÷6÷VçBR2Ò"’b`¢&÷Bæ6öçF–ç2‚'6¶—ögVÆÅ÷6÷'E÷F†—5÷F–6²"’b`¢&÷Bæ6öçF–ç2‚'fÂ&–÷&—F—¦VEvF6†Æ—7BÒ–b†6frçc4Væv–æTVæ&ÆVBbbvF6†Æ—7E&–÷&—G”'VFvWD'—73cCCb’"’¢76W'EG'VR‚%cRããcCCc¢6ÖRÖÖ–çBFVGW×W7BW6R6æöæ–6Â²VÖW&vVçB²&Vv—7G'’÷VâG'WF‚æB4ôÄU44R×W7Bæ÷B7&VFRæWr66ææW"6æF–FFR"À¢6ÖTÖ–çBæ6öçF–ç2‚$VÖW&vVçDwV&G&–Ç2ævWE÷6—F–öäÆ–W"†Ö–çB’"’b`¢6ÖTÖ–çBæ6öçF–ç2‚$vÆö&ÅG&FU&Vv—7G'’æ†4÷Vå÷6—F–öâ†Ö–çB’"’b`¢6ÖTÖ–çBæ6öçF–ç2‚%4ÔUôÔ”åEô$Äô4µôõTåôTÔU$tTåEócCCb"’b`¢66ææW"æ6öçF–ç2‚$FV6—6–öâä4ôÄU44R"’b`¢66ææW"ç7V'7G&–ær‡66ææW"æ–æFW„öb‚'fÂFV6—6–öâÒ"’Â66ææW"æ–æFW„öb‚'&WGW&âG'VR"Â66ææW"æ–æFW„öb‚'fÂFV6—6–öâÒ"’’’æ6öçF–ç2‚'&WGW&âfÇ6R"’¢76W'EG'VR‚%cRããcCCc¢Æ%Væ—fW'6UF–6²×W7B6æ6†÷B&÷VæFVB&W&W6VçFF—fR6Æ–6Röâ6Æ÷r7–6ÆW2–ç7FVBöbG&fW'6–ærF†RgVÆÂFö¶VâÖ&Vf÷&R7WW'f—6÷""À¢&÷Bæ6öçF–ç2‚$Ä%õTä•dU%4UõD”4µô%TDtUDTEócCCb"’b`¢&÷Bæ6öçF–ç2‚&Æ7E&Wd7–6ÆT×3cC#â3óÂ"’b`¢&÷Bæ6öçF–ç2‚'fÂÆ$'VFvWFVEFö¶Vä6cCCbÒ–b†Æ7E&Wd7–6ÆT×3cC#â3óÂ’CVÇ6R#"’b`¢&÷Bæ6öçF–ç2‚"çF¶R†Æ$'VFvWFVEFö¶Vä6cCCb’"’¢Ð  ¢FW7@¢gVâcUóócCCu÷÷7EöÆV&æ–æuöæE÷6ÖUöÖ–çE÷&WVEö6†ö¶W5ö&Uööfeö†÷E÷F‚‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ÷VävFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCCs¢’7FGW2ö6ÆVçWÖ–çFVææ6R×W7B'VâöfbF†R&÷BÆö÷æB6¶—6ÆVçWGW&–ær6Æ÷r7–6ÆW26òõ5EôÄT$ä”äuôÔ”åDTää4R6ææ÷B7FÆÂÖöæW’×F‚F‡&÷Vv‡WB"À¢&÷Bæ6öçF–ç2‚%õ5EôÄT$ä”äuôÔ”åDTää4R6÷W&6Rf—‚"’b`¢&÷Bæ6öçF–ç2‚&æÖRÒÂ&•÷7FGW5öÖ–çFVææ6UócCƒ•Â""’b`¢&÷Bæ6öçF–ç2‚$Ö–çFVææ6Uv÷&¶W#cCC‚ç7V&Ö—B"’b`¢&÷Bæ6öçF–ç2‚$•õ5DEU5ôÔ”åEô5”ä5ócCCr"’b`¢&÷Bæ6öçF–ç2‚$•õ5DEU5ô4ÄTåUõ4´•TEõ4Äõuô5”4ÄUócCCr"’b`¢&÷Bæ6öçF–ç2‚&Æ7E&Wd7–6ÆT×3cC#ÃÒ3óÂ"’¢76W'EG'VR‚%cRããcCCƒ¢W$66÷VçDÆVFvW"×W7B&RF†RW"Ö66‚WF†÷&—G“²6æöæ–6Â66‚—2öæÇ’7–æ6VBf6FR6òW"ff÷&F&–Æ—G’6ææ÷B7Æ—BÖ'&–â"À¢&÷Bæ6öçF–ç2‚%4”ätÄRU"4•DÂUD„õ$•E’%$”DtR"’b`¢&÷Bæ6öçF–ç2‚'7–æ5W$6—FÄWF†÷&—G“cCC‚"’b`¢&÷Bæ6öçF–ç2‚%U%ô4•DÅôUD„õ$•E•õ5”ä4TEócCC‚"’b`¢&÷Bæ6öçF–ç2‚'W%ö66÷VçEöÆVFvW%öf6FUócCC‚"’b`¢&÷Bæ6öçF–ç2‚""'7–æ5W$6—FÄWF†÷&—G“cCC‚‚&&÷EöÆö÷÷F÷"’"""’b`¢&÷Bæ6öçF–ç2‚'W%öFVÇF÷&ö¦V7F–öåócCsR"’b`¢&÷Bæ6öçF–ç2‚'&W—$66„g&öÔF—7Æ–VCcCC‚†F—7Æ–VD66‚"’¢76W'EG'VR‚%cRããcCCs¢&WVFVBU"6ÖRÖÖ–çBÆ–6W2×W7B&R6öÆW66VB&Vf÷&RW†V7WF&ÆT÷VävFR÷&–6R÷6—¦Rv÷&²Âv†–ÆRF†Rc3sf–æÆ—G’wV&B&VÖ–ç226fWG’&VÇB"À¢W†V2æ6öçF–ç2‚'W%6ÖTÖ–çD÷Vä6ööÆF÷våVçF–ÃcCCr"’b`¢W†V2æ6öçF–ç2‚%U%õ4ÔUôÔ”åEôõTåô4ôÄU44TEócCCr"’b`¢W†V2æ6öçF–ç2‚%U%õ4ÔUôÔ”åEôõTåõ4õU$4Uõ5U$U54TEócCCr"’b`¢W†V2æ–æFW„öb‚%U%õ4ÔUôÔ”åEôõTåõ4õU$4Uõ5U$U54TEócCCr"’ÂW†V2æ–æFW„öb‚$W†V7WF&ÆT÷VävFRæ6ä÷VäW†V7WF&ÆU÷6—F–öâ"’b`¢÷VävFRæ6öçF–ç2‚%U%õ4ÔUôÔ”åEôÅ$TE•ôõTåóc3s"’¢Ð   ¢FW7@¢gVâcUóócCC…÷&VÖ–æ–æuö6†ö¶U÷6÷W&6Uö6öçG&7G2‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÖ—'&÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF÷$6æöæ–6ÄÖ—'&÷#cCC"æ·B"’ç&VEFW‡B‚¢fÂ7FFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ÷6—F–öå7FFTÆVFvW#cC#ræ·B"’ç&VEFW‡B‚¢fÂ6Æ÷6TÆVFvW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ÷6—F–öä6Æ÷6TÆVFvW"æ·B"’ç&VEFW‡B‚¢fÂFö¶VäÖÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶VäÖWF†÷&—G’æ·B"’ç&VEFW‡B‚¢fÂW$ÆVFvW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õW$66÷VçDÆVFvW#cC3æ·B"’ç&VEFW‡B‚¢fÂ6—¦U&W6öÇfW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô÷&FW%6—¦U&W6öÇfW#cCCæ·B"’ç&VEFW‡B‚¢fÂ×VÇBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô×VÇF—Æ–W$GG&–'WF–öäÆVFvW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCCƒ¢7WW'f—6÷"F–ÖV÷WB×W7B–FVçF–g’W†7BÆV6R÷F6²ö6ÆÇ6—FR÷&öw&W72æBf÷&6R×&VÆV6RöæÇ’gFW"6æ6VÂ6¶æ÷vÆVFvVÖVçBFVÆVÖWG'’"À¢&÷Bæ6öçF–ç2‚%7WW'f—6÷$ÆV6R‚"’b`¢&÷Bæ6öçF–ç2‚&ÆV6T–C¢Æöær"’b`¢&÷Bæ6öçF–ç2‚'7WW'f—6÷%F–ÖV÷WDFWF–ÃcCC‚"’b`¢&÷Bæ6öçF–ç2‚%5UU%d•4õ%õtõ$´U%õD”ÔTõUEõD4µõ$ô4U55õDô´Tåô5”4ÄUócCC‚"’b`¢&÷Bæ6öçF–ç2‚%5UU%d•4õ%ô4ä4TÅô4µôôµócCC‚"’b`¢&÷Bæ6öçF–ç2‚%5UU%d•4õ%ô4ä4TÅô4µôÔ•54”äuócCC‚"’¢76W'EG'VR‚%cRããcCCƒ¢Fö¶VäÖWF†÷&—G’×W7B7W&W726öæ7W'&VçBGWÆ–6FRDô´TåôÔõ5D%Bv—F‚Ö–çBÖ¶W–VB7F—fR‡–G&F–öâæB¤ô”åôU„•5D”är6÷VçFW'2"À¢Fö¶VäÖæ6öçF–ç2‚&7F—fT‡–G&F–öä'”Ö–çB"’b`¢Fö¶VäÖæ6öçF–ç2‚%Dô´TåôÔõ5D%EõTä•TR"’b`¢Fö¶VäÖæ6öçF–ç2‚%Dô´TåôÔô¤ô”åôU„•5D”är"’b`¢Fö¶VäÖæ6öçF–ç2‚%Dô´TåôÔô4ôÕÄUDR"’b`¢Fö¶VäÖæ6öçF–ç2‚%Dô´TåôÔôd”ÄTB"’b`¢Fö¶VäÖæ6öçF–ç2‚%Dô´TåôÔõ$UE%’"’b`¢Fö¶VäÖæ6öçF–ç2‚%Dô´TåôÔô5D•dUõT²"’¢76W'EG'VR‚%cRããcCCƒ¢6æöæ–6ÂÆ–fV7–6ÆR×W7B&Rw&—GFVâ'’6öæf—&ÖVBW†V7WF÷"WfVçG2Âæ÷B¦W&òÖ6÷7BÆVv7’&Vv—7FW$÷Vâ÷"6Æ÷6RÖÆVFvW"–æfW&Væ6R"À¢7FFRæ6öçF–ç2‚$FòäõBÖ—'&÷"&Vv—7FW$÷Vâ‚’–çFò6æöæ–6Â†W&R"’b`¢W†V2æ6öçF–ç2‚&Ö—'&÷$'W”f–ÆÂ"’b`¢W†V2æ6öçF–ç2‚%4TÄÂÖ—'&÷"Ö÷fVBFò6öæf—&ÖVBW"f–ÆÂ"’b`¢6Æ÷6TÆVFvW"æ6öçF–ç2‚%÷6—F–öä6Æ÷6TÆVFvW"—26Æ÷6RÖWFFFÆVFvW"öæÇ’"’b`¢Ö—'&÷"æ6öçF–ç2‚&Æ7D6Æ÷6VE÷6—F–öä–D'”Ö–çB"’b`¢Ö—'&÷"æ6öçF–ç2‚'&Wv&BW&—G’—2FVÆ—fW&VBöæÇ’gFW""’b`¢¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôf–æÆ—¦VD'W46öç7VÖW$'&–FvScCcRæ·B"’ç&VEFW‡B‚¢æ6öçF–ç2‚&FVÆ—fW%Fõ&Wv&EW&—G’"’¢76W'EG'VR‚%cRããcCCƒ¢W"66÷VçBÆVFvW"×W7B7&VF—B6öæf—&ÖVB6VÆÇ2÷'F–Ç2æB&W6öÇfW"×W7B&VB—B2W"WF†÷&—G’"À¢W$ÆVFvW"æ6öçF–ç2‚&6äff÷&D'W’"’b`¢&÷Bæ6öçF–ç2‚'&W—$66„g&öÔF—7Æ–VCcCC‚†F—7Æ–VD66‚"’b`¢¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚’æ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æöå6VÆÂ"’b`¢òòcRããccB*uU%ôÄTDtU%õ$TEõTä”d”4D”ôâ(	B&W6öÇfW"Ö¢òòæ÷r&VBF†R6æöæ–6Âf6FR…W$6—FÄWF†÷&—G“cSsr¢òòv†–6‚—2F†–â$TBÔôäÅ’FVÆVvF–öâFòF†R6ÖRÆVFvW ¢òòWF†÷&—G’Â6òV—F†W"&VfW&Væ6R—266WF&ÆRà¢‡6—¦U&W6öÇfW"æ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æ66…6öÂ"’ÇÀ¢6—¦U&W6öÇfW"æ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræ66…6öÂ"’’b`¢6—¦U&W6öÇfW"æ6öçF–ç2‚%U%ôTåE%•ôdTUõ$U4U%dUõ$DUócC“"’¢76W'EG'VR‚%cRããcCCƒ¢ÆV&æW"fæ÷WB×W7B&R&Æö6¶VBf÷"'F–Â÷"æöâÕ&Wv&EW&—G’Öf–æÆ—¦VB4TÄÂ&÷w2"À¢W†V2æ6öçF–ç2‚%$Ut$EõU$•E•õ%D”ÅôÄT$ä”äuô$Äô4´TEócCC‚"’b`¢W†V2æ6öçF–ç2‚%$Ut$EõU$•E•ôÄT$ä”äuô$Äô4´TEócCC‚"’b`¢W†V2æ6öçF–ç2‚%&Wv&EW&—G”vFScCCæ÷WF6öÖTöb"’¢76W'EG'VR‚%cRããcCCƒ¢×VÇF—Æ–W"GG&–'WF–öâ×W7B&W÷'B6VÆV7FVB÷66÷&–ær÷6—¦–ærÆæW2æBVÖ—BÖ—76–ærGG&–'WF–öâ–ç7FVBöb6–ÆVçB5DäD$BfÆÆ&6²"À¢×VÇBæ6öçF–ç2‚'6VÆV7FVDÆæR"’b`¢×VÇBæ6öçF–ç2‚'66÷&–ætÆæR"’b`¢×VÇBæ6öçF–ç2‚'6—¦–ætÆæR"’b`¢×VÇBæ6öçF–ç2‚$ÄäUôEE$”%UD”ôåôÔ•54”är"’b`¢×VÇBæ6öçF–ç2‚&7F–öã×&W6W'fUö6æF–FFUöæõ÷7FæF&EöfÆÆ&6²"’¢Ð   ¢FW7@¢gVâcUóócCƒuöÆVFvW%ö—5÷F†UööæÇ•ö6—FÅöWF†÷&—G•öæE÷&WÆ•ö—5öF–væ÷7F–2‚’°¢fÂ&WÆ’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%&WÆ“cCcBæ·B"’ç&VEFW‡B‚¢fÂÆVFvW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õW$66÷VçDÆVFvW#cC3æ·B"’ç&VEFW‡B‚¢fÂ6—FÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Ä6—FÄWF†÷&—G“cCSæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCƒs¢&WÆ’&VÖ–ç2&—G’ö÷'†âF–væ÷7F–2"Â&WÆ’æ6öçF–ç2‚&÷'†ä÷Vä6÷7E6öÂ"’bb&WÆ’æ6öçF–ç2‚$õTåô4õ5Eõt•D„õUEô4äôä”4ÅôÄõEócCsR"’bb&WÆ’æ6öçF–ç2‚&6ö×&UFôÆVFvW""’¢76W'EG'VR‚%cRããcCƒs¢&WÆ’FW&—fW2w&÷72&VÆ—¦VBg&öÒG—VBf–VÆG2–ç7FVBöb7FÆRæWBvw&VvFR"Â&WÆ’æ6öçF–ç2‚&6æöæ–6Äw&÷75&VÆ—¦VCcCƒr"’bb&WÆ’æ6öçF–ç2‚&Ræw&÷75&ö6VVG56öÂÒRæÆÆö6FVD6÷7D&6—56öÂ"’¢76W'DfÇ6R‚%cRããcCƒs¢&WÆ’6ææ÷BW&–öF–6ÆÇ’÷fW'w&—FRWF†÷&—FF—fRÆVFvW""Â&WÆ’æ6öçF–ç2‚'&W—$ÆVFvW$–d6ÆVâ"’ÇÂÆVFvW"æ6öçF–ç2‚'&WÆ6Tg&öÔ6æöæ–6Å&WÆ’"’¢76W'EG'VR‚%cRããcCƒs¢öæÇ’öæR×F–ÖRÆVv7’Ö–w&F–öâÖ’6VVBÖ—76–ærGW&&ÆRÆVFvW""Â&WÆ’æ6öçF–ç2‚&Ö–w&FTÆVv7”ÆVFvW$öæ6ScCƒr"’bbÆVFvW"æ6öçF–ç2‚&–æ—EW'6—7FVçCcCƒr"’bbÆVFvW"æ6öçF–ç2‚'W'6—7D7W'&VçCcCƒr"’¢76W'EG'VR‚%cRããcCƒs¢6—FÂ6æ6†÷B&VG2ÆVFvW"66‚ö÷Vâ÷&VÆ—¦VBöfVW2F—&V7FÇ’‡÷7BÓccBÖ’&VBf–W$6—FÄWF†÷&—G“cSsrf6FR(	B6ÖRWF†÷&—G’’"À¢†6—FÂæ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æ66…6öÂ‚’"’ÇÂ6—FÂæ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræ66…6öÂ‚’"’’b`¢†6—FÂæ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æ÷Vä6÷7D&6—56öÂ‚’"’ÇÂ6—FÂæ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræ÷Vä6÷7D&6—56öÂ‚’"’’b`¢†6—FÂæ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3ç&VÆ—¦VEæÅ6öÂ‚’"’ÇÂ6—FÂæ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsrç&VÆ—¦VEæÅ6öÂ‚’"’’b`¢†6—FÂæ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æfVW56öÂ‚’"’ÇÂ6—FÂæ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræfVW56öÂ‚’"’’¢76W'DfÇ6R‚%cRããcCƒs¢6—FÂ6æ6†÷B6ææ÷B&VfW"&WÆ’F÷FÇ2"Â6—FÂæ6öçF–ç2‚$6æöæ–6ÅW%&WÆ“cCcBæÆ7E6æ6†÷B‚’"’ÇÂ6—FÂæ6öçF–ç2‚'&WÆ“òæ÷Vä6÷7D&6—56öÂ"’¢76W'EG'VR‚%cRããcCƒs¢vÆÆWB7W&f6W2&R7–æ6‡&öæ—¦VBg&öÒÆVFvW"WF†÷&—G’"Â&÷Bæ6öçF–ç2‚'7–æ5W$6—FÄWF†÷&—G“cCC‚"’bb&÷Bæ6öçF–ç2‚%W%vÆÆWE7F÷&RçW'6—7B†Æ–6F–öä6öçFW‡BÂÆVFvW$66‚’"’¢Ð ¢FW7@¢gVâcUóócCsUö¦÷W&æÅö—5÷&ö¦V7F–öåöæEöf–æÆ—¦VEö6µö—5÷G'WF†gVÂ‚’°¢fÂ¦÷W&æÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõc4¦÷W&æÅ&V6÷&FW"æ·B"’ç&VEFW‡B‚¢fÂ6†W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôw&÷wF„Æ–væVE&Wv&E6†W#cC3’æ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôf–æÆ—¦VD'W46öç7VÖW$'&–FvScCcRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCsS¢¦÷W&æÂ6Æ÷6R×W7B&WV—&RW‡Æ–6—B6æöæ–6Âf–æÆ—G’f÷"ÆV&æW"×WFF–öâ"Â¦÷W&æÂæ6öçF–ç2‚&—46æöæ–6Äf–æÆ—¦VC¢&ööÆVâÒfÇ6R"’bb¦÷W&æÂæ6öçF–ç2‚&–b†—46æöæ–6Äf–æÆ—¦VB’"’¢76W'DfÇ6R‚%cRããcCsS¢&Wv&B6†W"×W7Bæ÷B×WFFRÆ÷727G&V²F—&V7FÇ’"Â6†W"æ6öçF–ç2‚$Æ÷6–æu7G&Vµ&VfÆWƒcC3’æöåG&FT6Æ÷6VB‡&VÆ—¦VE6öÄFVÇF"’¢76W'DfÇ6R‚%cRããcCƒS¢f–æÆ—¦VB6öç7VÖW'2×W7Bæ÷B&WF–âVçv—&VB÷76—fR4²'&æ6†W2"Â'&–FvRæ6öçF–ç2‚$d”äÄ•¤TEô4ôå5TÔU%õTåt•$TEò"’ÇÂ'&–FvRæ6öçF–ç2‚'&—fFRgVâVçv—&VB‚"’¢76W'EG'VR‚%cRããcCƒS¢F6†&ö&B4²&WV—&W2&VÂ6æöæ–6Â&ö¦V7F–öâw&—FR"Â'&–FvRæ6öçF–ç2‚$F6†&ö&DFF&÷f–FW"æöä6æöæ–6ÅG&FTf–æÆ—¦VCcCƒR"’¢Ð ¢FW7@¢gVâcUóócCsUö6Æ–Õöf—'7Eö6Æ÷6UöæEö6öæf—&ÖVEöf–ÆÅöWF†÷&—G’‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚¢fÂ6Æ÷6T6Æ–ÒÒ'&–FvRæ–æFW„öb‚%FW&Ö–æÅ6VÆÄ–FV×÷FVæ7“cCcBæ&Vv–åFW&Ö–æÂ"¢fÂ6Æ÷6TÖ—'&÷"Ò'&–FvRæ–æFW„öb‚$W†V7WF÷$6æöæ–6ÄÖ—'&÷#cCC"æÖ—'&÷%6VÆÂ"¢fÂ6Æ÷6TÆVFvW"Ò'&–FvRæ–æFW„öb‚%W$66÷VçDÆVFvW#cC3æöå6VÆÂ"¢76W'EG'VR‚%cRããcCsS¢FW&Ö–æÂ–FV×÷FVæ7’×W7B6Æ–Ò&Vf÷&RÖ—'&÷"æB66‚×WFF–öâ"Â6Æ÷6T6Æ–ÒãÒbb6Æ÷6T6Æ–ÒÂ6Æ÷6TÖ—'&÷"bb6Æ÷6T6Æ–ÒÂ6Æ÷6TÆVFvW"¢fÂf–ÆÄÖ&¶W"ÒW†V2æ–æFW„öb‚%cRããcCƒR(	BDôÔ”2U"%U’4ôÔÔ•B"¢fÂ'W”FV&—BÒW†V2æ–æFW„öb‚%W$66÷VçDÆVFvW#cC3æöä'W’†7GVÅ6öÂ"¢fÂf—'7D'W”vFRÒW†V2æ–æFW„öb‚%U%ô%U•ô$Äô4´TEõ$U4ÄUõ4ä•Uóc3s4b"¢76W'EG'VR‚%cRããcCsS¢W"%U’66‚FV&—B×W7B&RgFW"VçG'’vFW2"Âf–ÆÄÖ&¶W"ãÒbb'W”FV&—Bâf–ÆÄÖ&¶W"bbf—'7D'W”vFRÂ'W”FV&—B¢fÂ'F–ÃcSÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%'F–Ä÷W&F–öãcSæ·B"’ç&VEFW‡B‚¢fÂ&WF—&VE7–çF†WF–5'F–Å&Vf—ƒcSÒ'W%÷'F–Åò"²"B"²'–B ¢76W'EG'VR‚%cRããcCsRócS¢WFöæöÖ÷W2æBÖçVÂW"'F–Ç2×W7B&÷WFRF‡&÷Vv‚öæR6Æ–ÒÖf—'7B6æöæ–6Â÷W&F–öâ"ÂW†V2æ6öçF–ç2‚$6æöæ–6ÅW%'F–Ä÷W&F–öãcSæ6öÖÖ—B"’bb'F–ÃcSæ6öçF–ç2‚$6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æf–æÆ—¦U6VÆÂ"’bbW†V2æ6öçF–ç2‚'W%öÖçVÅ÷'F–Åò"’bbW†V2æ6öçF–ç2‡&WF—&VE7–çF†WF–5'F–Å&Vf—ƒcS’¢76W'EG'VR‚%cRããcCsS¢6Æ÷6RWF†÷&—F–W2×W7B&VÆV6R6æöæ–6Âö67Wæ7’"Â¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ÷6—F–öä6Æ÷6TÆVFvW"æ·B"’ç&VEFW‡B‚’æ6öçF–ç2‚$6æöæ–6ÄÖ–çDö67Wæ7•&Vv—7G'“cCcBæÖ&´6Æ÷6VB"’bb¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõW%÷6—F–öä6Æ÷6TWF†÷&—G’æ·B"’ç&VEFW‡B‚’æ6öçF–ç2‚$6æöæ–6ÄÖ–çDö67Wæ7•&Vv—7G'“cCcBæÖ&´6Æ÷6VB"’¢Ð ¢FW7@¢gVâcUóócCsEögVÆÅ÷W%÷6VÆÅö6öÖÖ—G5ö6æöæ–6Åö66÷VçF–æuö&Vf÷&Uö¦÷W&æÅ÷&ö¦V7F–öâ‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚¢fÂgVÆÅ6VÆÄ–G‚ÒW†V2æ–æFW„öb‚&gVâW%6VÆÂ‚"¢fÂ6öÖÖ—D–G‚ÒW†V2æ–æFW„öb‚$4äôä”4ÅõU%õ4TÄÅô4ôÔÔ•EócCsB"ÂgVÆÅ6VÆÄ–G‚¢fÂ¦÷W&æÄ–G‚ÒW†V2æ–æFW„öb‚'&V6÷&EG&FR‡G4ÆV&æ–æu6æÂG&FU6æ’"ÂgVÆÅ6VÆÄ–G‚¢76W'EG'VR‚%cRããcCsC¢gVÆÂU"4TÄÂ×W7B&÷WFRF‡&÷Vv‚6æöæ–6ÅW%FW&Ö–æÄ'&–FvRæf–æÆ—¦U6VÆÂ&Vf÷&R¦÷W&æÂöÆV&æ–ær&ö¦V7F–öâ"ÂgVÆÅ6VÆÄ–G‚ãÒbb6öÖÖ—D–G‚âgVÆÅ6VÆÄ–G‚bb¦÷W&æÄ–G‚â6öÖÖ—D–G‚¢76W'EG'VR‚%cRããcCsC¢66÷VçF–ær&V¦V7F–öâ×W7Bæ÷Bw&—FR7V66W76gVÂ4TÄÂ¦÷W&æÂ&÷r"ÂW†V2æ6öçF–ç2‚%U%ô44õTåD”äuôÕUDD”ôåõ$T¤T5DTB"’bbW†V2æ6öçF–ç2‚&7F–öãÖæõ÷7V66W76gVÅ÷6VÆÅö¦÷W&æÂ"’bbW†V2æ6öçF–ç2‚'&WGW&â6VÆÅ&W7VÇBäd”ÄTEôdDÂ"’¢76W'EG'VR‚%cRããcCsC¢6æöæ–6ÂFW&Ö–æÂ'&–FvR÷vç2F†RgVÆÂW"6VÆÂÖöæW’öWfVçBfæ÷WB"ÂW†V2æ6öçF–ç2‚$6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æf–æÆ—¦U6VÆÂ"’bb'&–FvRæ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æöå6VÆÂ"’bb'&–FvRæ6öçF–ç2‚$V6öæöÖ–4WfVçE66†VÖcCcBç&V6÷&E6VÆÂ"’bb'&–FvRæ6öçF–ç2‚$6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSçV&Æ—6‚"’¢76W'DfÇ6R‚%cRããcCsC¢gVÆÂW"6VÆÂ×W7Bæ÷BF—&V7FÇ’6ÆÂÖ—'&÷%6VÆÂµW$66÷VçDÆVFvW"2F—fW&vVçBgVÆÂÖ6Æ÷6RF‚"ÂW†V2ç7V'7G&–ær†gVÆÅ6VÆÄ–G‚’æ6öçF–ç2‚""$W†V7WF÷$6æöæ–6ÄÖ—'&÷#cCC"æÖ—'&÷%6VÆÂ€¢Ö–çBÒG&FT–BæÖ–çB"""’¢Ð  ¢FW7@¢gVâcUóócCseöVF—E÷&W—'5öfVU÷&WÆ•öö67Wæ7•öæE÷FW&Ö–æÅö×W‚‚’°¢fÂV6öâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôV6öæöÖ–4WfVçE66†VÖcCcBæ·B"’ç&VEFW‡B‚¢fÂ&WÆ’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%&WÆ“cCcBæ·B"’ç&VEFW‡B‚¢fÂö62Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÄÖ–çDö67Wæ7•&Vv—7G'“cCcBæ·B"’ç&VEFW‡B‚¢fÂ7vVWÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôf÷&6VD6Æ÷6U6Æ÷E7vVWW#cCc‚æ·B"’ç&VEFW‡B‚¢fÂÆ÷72Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ÷6–æuGFW&äÖVÖ÷'’æ·B"’ç&VEFW‡B‚¢fÂ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Äf–æÆ—¦VEG&FT'W3cCcBæ·B"’ç&VEFW‡B‚¢fÂ&–6‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCsc¢VçG'’fVR×W7B&RG—VB6W&FVÇ’g&öÒ&–æ6—Â÷Vâ6÷7B"ÂV6öâæ6öçF–ç2‚&VçG'”fVW56öÂ"’bb&WÆ’æ6öçF–ç2‚&÷Vä6÷7B³ÒRæW†V7WFVD6÷7E6öÂ"’bb&WÆ’æ6öçF–ç2‚&fVW2³ÒRæVçG'”fVW56öÂ"’¢76W'EG'VR‚%cRããcCsc¢66†VB&WÆ’×W7BW‡—&RöâWfVçB×WFF–öâ"ÂV6öâæ6öçF–ç2‚&WfVçEfW'6–öâæ–æ7&VÖVçDæDvWB‚’"’bb&WÆ’æ6öçF–ç2‚&—BæWfVçEfW'6–öâÓÒG'’"’¢76W'EG'VR‚%cRããcCsc¢f÷&6VB6Æ÷6R7vVW×W7B—FW&FRæB&VÆV6R&VÂö67Wæ7’&÷w2"Âö62æ6öçF–ç2‚&gVâ6æ6†÷DVçG&–W2‚’"’bb7vVWæ6öçF–ç2‚$dõ$4TEô4Äõ4Uõ4ÄõEõ$TÄT4TEô%•õ5tTUócCsb"’bb7vVWæ6öçF–ç2‚&Ö&´6Æ÷6VB†VçG'’æÖöFRÂVçG'’æÖ–çB’"’¢76W'EG'VR‚%cRããcCsc¢Æ÷6–ærGFW&â67V×VÆF÷"×W7B&RcBÖ&—BæBÆ—fRFV6—6–öç2ÖöFRÖÆö6Â"ÂÆ÷72æ6öçF–ç2‚$Æöæt'&’ƒ2’"’bbÆ÷72æ6öçF–ç2‚&–b…'VçF–ÖTÖöFTWF†÷&—G’æ—4Æ—fR‚’’Æ—fT66†RVÇ6R66†R"’¢76W'EG'VR‚%cRããcCsc¢f–æÆ—¦VB×W‚×W7B6''’÷6—F–öâÖöFR&ööbæB&V¦V7Bæöâ×FW&Ö–æÂWfVçG2"Â'W2æ6öçF–ç2‚'fÂ&ööe7FFS¢7G&–ær"’bb'W2æ6öçF–ç2‚"VçbçFW&Ö–æÂ"’bb&–6‚æ6öçF–ç2‚&öæRFW&Ö–æÂ–FVçF—G’7&÷72F†R&–6‚cCSWfVçB"’¢76W'DfÇ6R‚%cRããcCsc¢'F–ÂW"6VÆÇ2×W7Bæ÷BV&Æ—6‚f–æÆ—¦VB'W2WfVçG2"Â¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚’æ6öçF–ç2‚'G&FT–BÒ–D¶W’"²"Â"’¢Ð  ¢FW7@¢gVâcUóócCsuöÆ—fU÷Fö¶VåöÖVÖ÷'•÷&WV—&W5öWfVçEöÆö6Å÷&ööeöæE÷W'6—7G5÷7Æ—B‚’°¢fÂÖVÖ÷'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶Våv–äÖVÖ÷'’æ·B"’ç&VEFW‡B‚¢fÂVGRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærôVGV6F–öå7V$Æ–W$’æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂW'6—7FVæ6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆV&æ–æuW'6—7FVæ6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCss¢÷6—F—fRÆ—fRFö¶VâWF†÷&—G’×W7B&R—6öÆFVB"ÂÖVÖ÷'’æ6öçF–ç2‚&Æ—fUv–ææ–æuFö¶Vç2"’bbÖVÖ÷'’æ6öçF–ç2‚&Æ—fUFö¶Vå7FG2"’¢76W'EG'VR‚%cRããcCss¢Æ—fR×WFF–öâ×W7B&WV—&RÖöFRÇW26öæf—&ÖVBö6æöæ–6Â&ööb"ÂÖVÖ÷'’æ6öçF–ç2‚'fÂfW&–f–VDÆ—fRÒÖöFRæWVÇ2"’bbÖVÖ÷'’æ6öçF–ç2‚'&ööe7FFRæ6öçF–ç2‚"²%Â""²&6öæf—&ÖVB"²%Â""’¢76W'EG'VR‚%cRããcCss¢Æ—fRÖöæÇ’7FFR×W7B6fRæBÆöB7–ÖÖWG&–6ÆÇ’"ÂÖVÖ÷'’æ6öçF–ç2‚$´U•ôÄ•dUõt”ääU%5ócCsr"’bbÖVÖ÷'’æ6öçF–ç2‚$´U•ôÄ•dUõDô´Tåõ5DE5ócCsr"’bbÖVÖ÷'’æ6öçF–ç2‚&Æ—fUv–ææW'57G""’bbÖVÖ÷'’æ6öçF–ç2‚&Æ—fUFö¶Vå7FG57G""’¢76W'EG'VR‚%cRããcCss¢VGV6F–öâWfVçB×W7B6''’W†V7WF–öâÖÆö6ÂÖöFR÷&ööb"ÂVGRæ6öçF–ç2‚'fÂW†V7WF–öäÖöFS¢7G&–ær"’bbVGRæ6öçF–ç2‚'fÂ&ööe7FFS¢7G&–ær"’bbW†V2æ6öçF–ç2‚'&ööe7FFRÒ"²%Â""²&6æöæ–6Åöf–æÆ—¦VB"²%Â""’¢76W'EG'VR‚%cRããcCss¢Fö¶Våv–äÖVÖ÷'’fÇW6‚×W7B'VâVæFW"öfbÖÖ–âÆV&æ–æuW'6—7FVæ6R"ÂW'6—7FVæ6Ræ6öçF–ç2‚%Fö¶Våv–äÖVÖ÷'’ç6fR‚’"’bbW'6—7FVæ6Ræ6öçF–ç2‚'6fTÆÄ&Æö6¶–æt–çFW&æÂ"’¢Ð  ¢FW7@¢gVâcUóócCs…öfFu÷&÷f–FW%ö6ÆÇ5ö&Uö&6¶w&÷VæEö66†VEööæÇ’‚’°¢fÂfFrÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄFV6—6–öävFRæ·B"’ç&VEFW‡B‚¢fÂ66†RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô7–æ4vVÖ–æ”æ'&F—fT66†ScCs‚æ·B"’ç&VEFW‡B‚¢76W'DfÇ6R‚%cRããcCsƒ¢dDr×W7Bæ÷B7–æ6‡&öæ÷W6Ç’6ÆÂvVÖ–æ’V–6²66Ò"ÂfFræ6öçF–ç2‚$vVÖ–æ”6÷–Æ÷BçV–6µ66Ô6†V6²"’¢76W'DfÇ6R‚%cRããcCsƒ¢dDr×W7Bæ÷B7–æ6‡&öæ÷W6Ç’6ÆÂvVÖ–æ’æ'&F—fR"ÂfFræ6öçF–ç2‚$vVÖ–æ”6÷–Æ÷BææÇ—¦Tæ'&F—fR"’¢76W'DfÇ6R‚%cRããcCsƒ¢dDr×W7Bæ÷B7–æ6‡&öæ÷W6Ç’6ÆÂVæ–f–VDæ'&F—fT’"ÂfFræ6öçF–ç2‚%Væ–f–VDæ'&F—fT’ææÇ—¦R"’¢76W'EG'VR‚%cRããcCsƒ¢dDr66†RÖ—72×W7B66†VGVÆR&6¶w&÷VæB”òæB7F’æWWG&Â"ÂfFræ6öçF–ç2‚$7–æ4vVÖ–æ”æ'&F—fT66†ScCs‚æ66†VD÷%&WVW7B"’bbfFræ6öçF–ç2‚&&6¶w&÷VæB&Vg&W6‚ÂæWWG&Âæ÷r"’¢76W'EG'VR‚%cRããcCsƒ¢&÷f–FW"6ÆÇ2×W7BW†—7BöæÇ’&V†–æB”ò6÷&÷WF–æR66†R"Â66†Ræ6öçF–ç2‚$F—7F6†W'2ä”ò"’bb66†Ræ6öçF–ç2‚'66÷RæÆVæ6‚"’bb66†Ræ6öçF–ç2‚&–äfÆ–v‡BæFB"’bb66†Ræ6öçF–ç2‚$tTÔ”ä•ôä%$D•dUô44„UôÔ•55ôäUUE$ÅócCs‚"’¢Ð  ¢FW7@¢gVâcUóócCs•öfÇV–EöæEöW†—Eö†÷E÷F‡5öæWfW%÷v—Eööå÷&÷f–FW'2‚’°¢fÂfÇV–BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærôfÇV–DÆV&æ–æt’æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ66†RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô7–æ4vVÖ–æ”W†—DGf–6T66†ScCs’æ·B"’ç&VEFW‡B‚¢76W'DfÇ6R‚%cRããcCs“¢fÇV–B6—¦–ær×W7Bæ÷B'Vä&Æö6¶–ærf÷"4ôÂ&–6R"ÂfÇV–Bæ6öçF–ç2‚''Vä&Æö6¶–ær"’ÇÂfÇV–Bæ6öçF–ç2‚%&–6Tvw&VvF÷"ævWE&–6R"’¢76W'EG'VR‚%cRããcCs“¢fÇV–B6—¦–ær×W7B6öç7VÖR&÷VæFVB66†VB4ôÂ&–6R"ÂfÇV–Bæ6öçF–ç2‚$Vff–6–Væ7”Æ–W"ævWD66†VE&–6R‚“òç6öÅ&–6UW6B"’¢76W'DfÇ6R‚%cRããcCs“¢W†V7WF÷"W†—BFV6—6–öâ×W7Bæ÷B6ÆÂvVÖ–æ’F—&V7FÇ’"ÂW†V2æ6öçF–ç2‚'fÂvVÖ–æ”Gf–6RÒvVÖ–æ”6÷–Æ÷BævWDW†—DGf–6R"’¢76W'EG'VR‚%cRããcCs“¢W†—BGf–6R×W7B&R&6¶w&÷VæB66†VBv—F‚æWWG&ÂÖ—72FVÆVÖWG'’"ÂW†V2æ6öçF–ç2‚$7–æ4vVÖ–æ”W†—DGf–6T66†ScCs’æ66†VD÷%&WVW7B"’bb66†Ræ6öçF–ç2‚$F—7F6†W'2ä”ò"’bb66†Ræ6öçF–ç2‚$tTÔ”ä•ôU„•Eô44„UôÔ•55ôäUUE$ÅócCs’"’¢76W'EG'VR‚%cRããcCs“¢66†R¶W’×W7B&–æBÖ–çBÇW2äÂæBV²'V6¶WG2"Â66†Ræ6öçF–ç2‚&fÆö÷"‡æÅ7BòRã’"’bb66†Ræ6öçF–ç2‚&fÆö÷"‡Vµ7BòRã’"’¢Ð  ¢FW7@¢gVâcUóócCƒ÷vF6†Æ—7Eö6ö—5ööæUöFöÖ–5÷6÷W&6UöWF†÷&—G’‚’°¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôvÆö&ÅG&FU&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂ6W'f–6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ6öæf–rÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöFFô&÷D6öæf–ræ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCƒ¢&Vv—7G'’×W7BW‡÷6RöæR6æöæ–6Â##6"Â&Vv—7G'’æ6öçF–ç2‚&6öç7BfÂÔ…õtD4„Ä•5Eõ4•¤RÒ##"’¢76W'EG'VR‚%cRããccSS¢FÖ—76–öâ6×W7B'VFvWBF—66÷fW'’6W&FVÇ’g&öÒ&WF–æVB†VÆB–çfVçF÷'’"Â&Vv—7G'’æ6öçF–ç2‚$7–æ6‡&öæ—¦VB"²%Æâ"²"gVâFEFõvF6†Æ—7B"’bb&Vv—7G'’æ6öçF–ç2‚&F—66÷fW'”gFW#ccSRÂÔ…õtD4„Ä•5Eõ4•¤RÂÂ&vÆö&Å÷&Vv—7G'•öF—66÷fW'•Â""’¢76W'EG'VR‚%cRããccSS¢6VÆV7F÷"æBVF—B×W7B&VBF—66÷fW'’Ö'VFvWBf7G2"Â6W'f–6Ræ6öçF–ç2‚$Ô…ô5D•dUõtD4„Ä•5BÒ6öÒæÆ–fV7–6ÆV&÷BæVæv–æRävÆö&ÅG&FU&Vv—7G'’äÔ…õtD4„Ä•5Eõ4•¤R"’bb6W'f–6Ræ6öçF–ç2‚'fÂ7W'&VçEvF6†Æ—7BÒ6öÒæÆ–fV7–6ÆV&÷BæVæv–æRävÆö&ÅG&FU&Vv—7G'’æF—66÷fW'•vF6†Æ—7E6—¦SccSR‚’"’bb6W'f–6Ræ6öçF–ç2‚'fÂ6öæf–wW&VD6Ò6öÒæÆ–fV7–6ÆV&÷BæVæv–æRävÆö&ÅG&FU&Vv—7G'’äÔ…õtD4„Ä•5Eõ4•¤R"’¢76W'DfÇ6R‚%cRããcCƒ¢VF—B×W7Bæ÷BG&VBÆ&VÂ6÷VçFW"2‡—6–6Â6—¦R"Â6W'f–6Ræ6öçF–ç2‚&Æ&VÄ6÷VçE6æ6†÷B‚"²%Â""²$„õEõtD4„Ä•5Eõ4•¤Uôô%4U%dTEócCs2"²%Â""’¢76W'EG'VR‚%cRããcCƒ¢W'6—7FVB6öæf–r×W7Bæ÷B&RÖW‡æB&÷fR‡—6–6ÂWF†÷&—G’"Â6öæf–ræ6öçF–ç2‚&Ö…vF6†Æ—7E6—¦S¢–çBÒ##"’bb6öæf–ræ6öçF–ç2‚&6öW&6T–âƒÂ##’"’¢Ð  ¢FW7@¢gVâcUóócCƒöÆæU÷&W77W&U÷—f÷G5÷F7F–5ö&Vf÷&U÷6V6öæF'•÷6—¦–ær‚’°¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÆæTFÖ—76–öävFScCs2æ·B"’ç&VEFW‡B‚¢fÂF7F–72Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærõF7F–57v—F6†W"æ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCƒ¢F7F–27v—F6†W"×W7BW‡÷6RÆæRÖÆö6Â&W77W&R&÷FF–öâ"ÂF7F–72æ6öçF–ç2‚&gVâ&÷FFTf÷$ÆæU&W77W&R"’bbF7F–72æ6öçF–ç2‚&ÆæRÖÆö6Â×&W77W&S¢"’¢76W'EG'VR‚%cRããcCƒ¢FÖ—76–öâ×W7B&÷FFRF7F–2&Vf÷&RÇ––ær6—¦R×VÇF—Æ–W""ÂvFRæ–æFW„öb‚%F7F–57v—F6†W"ç&÷FFTf÷$ÆæU&W77W&R"’–âVçF–ÂvFRæ–æFW„öb‚'&WVW7FVE6—¦U6öÂ¢Bç6—¦T×VÇF—Æ–W""’¢76W'EG'VR‚%cRããcCƒ¢&÷fVB6—¦–ær×W7B&WF–âW‡Æ–6—BW†V7WF&ÆRfÆö÷""ÂvFRæ6öçF–ç2‚&6öW&6TDÆV7B†Ö–äW†V7WF&ÆU6—¦U6öÂæ6öW&6TDÆV7Bƒã’’"’bbW†V2æ6öçF–ç2‚'W$W†V7WF&ÆTÖ–æ–×VÕ6öÃcSÒÖ–ä6öæf–wW&VEW%G&FU6öÂ‚’"’¢76W'DfÇ6R‚%cRããcCƒ¢W"W†V7WF÷"×W7Bæ÷BG&ç6ÆFRÆV&æVBÆæR&W77W&R–çFò¦W&ò"ÂW†V2æ6öçF–ç2‚%U%ô%U•ôÄäUôDÔ•54”ôåõ4´•ócCs2"’¢76W'EG'VR‚%cRããcSS#¢6VÆVBæ÷F–öæÂ—26öç7VÖVBv—F†÷WB÷7B×F–6¶WB6†W'2"ÂW†V2æ6öçF–ç2‚%U%õ4TÄTEôäõD”ôäÅô4ôå5TÔTEócSS""’bbW†V2æ6öçF–ç2‚'6VÆVDæ÷F–öæÃcSS""’¢76W'DfÇ6R‚%cRããcCƒ¢ÆV&æVBF÷†–2'V6¶WB&W77W&R×W7Bæ÷B†&B×fWFòW"öÆ—fR'W—2"ÂW†V2æ6öçF–ç2‚%U%ô%U•õDõ„”5ô%T4´UEô„$EõdUDõóc#C’"’ÇÂW†V2æ6öçF–ç2‚%Dõ„”5ô%T4´UEô„$EõdUDõóc#C’"’¢76W'EG'VR‚%cRããcCƒ¢&÷F‚ÖöFW2×W7B—f÷BF7F–2–ç6–FRF†RÆæR"ÂW†V2æ6öçF–ç2‚%U%ô%U•õDõ„”5ô%T4´UEõD5D”5õ•dõEócCƒ"’bbW†V2æ6öçF–ç2‚$Ä•dUô%U•õDõ„”5ô%T4´UEõD5D”5õ•dõEócCƒ"’¢Ð  ¢FW7@¢gVâcUóócCƒ%öÆV&æVEöFæ÷W6UöæE÷GFW&å÷&W77W&U÷—f÷Eöæ÷E÷fWFò‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂfFrÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄFV6—6–öävFRæ·B"’ç&VEFW‡B‚¢76W'DfÇ6R‚%cRããcCƒ#¢GWÆ–6FRDäÆV&æW'2×W7Bæ÷B&÷'BÆ—fR'W—2"ÂW†V2æ6öçF–ç2‚$DäõdUDõôT$Å•ôÄ”TEóc3""’ÇÂW†V2æ6öçF–ç2‚$Däõ$õdTåôÄõ4U%õdUDõóc#cB"’¢76W'EG'VR‚%cRããcCƒ#¢&÷F‚Dä&W77W&R6—FW2×W7B—f÷B6ÖRÖÆæRF7F–72"ÂW†V2æ6öçF–ç2‚$DäôT$Å•õD5D”5õ•dõEócCƒ""’bbW†V2æ6öçF–ç2‚$Däõ$õdTåôÄõ4U%õD5D”5õ•dõEócCƒ""’bbW†V2æ6öçF–ç2‚&7F–öãÖ6öçF–çVU÷6ÖUöÆæR"’¢76W'DfÇ6R‚%cRããcCƒ#¢ÆæRW6RæBF÷†–2GFW&âÆV&æ–ær×W7Bæ÷B7&VFRdDr†&B&Æö6·2"ÂfFræ6öçF–ç2‚$ÄäUôUDõõU4TEô„$Eô$Äô4²"’ÇÂfFræ6öçF–ç2‚%Dõ„”5õEDU$åô„$Eô$Äô4²"’¢76W'EG'VR‚%cRããcCƒ#¢ÆæRW6RæBF÷†–2GFW&ç2×W7B&÷FFRF7F–2&Vf÷&R6V6öæF'’6—¦R6†–ær"ÂfFræ6öçF–ç2‚$ÄäUôUDõõD5D”5õ•dõEócCƒ""’bbfFræ6öçF–ç2‚%Dõ„”5õEDU$åõD5D”5õ•dõEócCƒ""’bbfFræ6öçF–ç2‚%F7F–57v—F6†W"ç&÷FFTf÷$ÆæU&W77W&R"’¢76W'EG'VR‚%cRããcCƒ#¢ÆV&æVB&W77W&R¶VW2âW†V7WF&ÆRfÆö÷""ÂfFræ6öçF–ç2‚&f–æÅ6—¦RÒ†f–æÅ6—¦R¢ã3R’æ6öW&6TDÆV7Bƒã’"’¢76W'EG'VR‚%cRããcCƒ#¢&÷f–FW"ÖFVw&FVBW†V7WF–öâ6fWG’&VÖ–ç2–çF7B"ÂW†V2æ6öçF–ç2‚%$õd”DU%ôDTu$DTEô%U•ô$Äô4µóc#cB"’bbW†V2æ6öçF–ç2‚&FW…7"Âãsbb§W7"Âãc"’¢Ð  ¢FW7@¢gVâcUóócCƒ5öÆæU÷W6Uö6ææ÷EöF—6&ÆU÷G&FW%ö÷%÷ö—6öå÷66ææW%ö–çF¶R‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ6fWG’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶Vå6fWG”6†V6¶W"æ·B"’ç&VEFW‡B‚¢76W'DfÇ6R‚%cRããcCƒ3¢ÆV&æVBW6R×W7Bæ÷BFVç’÷væW"ÆæW2÷"v†öÆRW‡&W72WfÇVF–öâ"Â&÷Bæ6öçF–ç2‚$õtäU%ôÄäUõU4TEôDTä”TEóCS“‚"’ÇÂ&÷Bæ6öçF–ç2‚$U…$U55ôÄäUõU4TEôT$Å•ôtDUóCS“B"’¢76W'EG'VR‚%cRããcCƒ3¢÷væW"Â7V66W76gVÂÖfVVBæBW‡&W72F‡2×W7B—f÷B6ÖRÖÆæRF7F–72"Â&÷Bæ6öçF–ç2‚$õtäU%ôÄäUõD5D”5õ•dõEócCƒ2"’bb&÷Bæ6öçF–ç2‚%5T44U54eTÅôÄäUôdTTEõD5D”5õ•dõEócCƒ2"’bb&÷Bæ6öçF–ç2‚$U…$U55ôÄäUõD5D”5õ•dõEócCƒ2"’¢76W'DfÇ6R‚%cRããcCƒ3¢ÆV&æVBÔä•ÆæR&W77W&R×W7Bæ÷BW'6—7B66ææW"†&B&V¦V7G2"Â6fWG’æ6öçF–ç2‚$Ôä•TÄDTEôôäÅ•ôÄäUõT$åD”äTEóCS“""’ÇÂ&÷Bæ6öçF–ç2‚$Ôä•TÄDTEôôäÅ•ôÄäUõT$åD”äTEóCS“"’¢76W'EG'VR‚%cRããcCƒ3¢VÆ—G’&ööb7F–ÆÂvFW2VÆ—G’ö&ÇVV6†—7V66W76gVÂfVVB"Â&÷Bæ6öçF–ç2‚&–b‡VÆ—G•&öödö³cB’"’bb&÷Bæ6öçF–ç2‚%5T44U54eTÅôÄäUôdTTEôDTä”TEõTÄ•E•õ$ôôeócCƒ2"’¢76W'EG'VR‚%cRããcCƒ3¢W‡&W727F–ÆÂ&WV—&W27–6ÆR÷væW'6†—æBVæ&ÆVBWF†÷&—G’"Â&÷Bæ6öçF–ç2‚&W‡&W74ÆæTÆÆ÷vVEF†—47–6ÆR"’bb&÷Bæ6öçF–ç2‚%6†—D6ö–äW‡&W72æ—4Væ&ÆVB‚’"’¢76W'EG'VR‚%cRããcCƒ3¢Öæ—VÆFVB6fWG’÷fW&Æ’&VÖ–ç27G&öær6ögBVæÇG’"Â6fWG’æ6öçF–ç2‚$Ôä•TÄDTEôôäÅ•ôõdU$Ä•óCSS2"’bb6fWG’æ6öçF–ç2‚'VæÇG’³ÒSR"’¢Ð  ¢FW7@¢gVâcUóócCƒEöÆ—fUöÆæUöv÷fW&æ÷%ö6ææ÷E÷&W7W'&V7E÷÷7EöfFuö†&E÷W6R‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂv÷fW&æ÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fTÆæTv÷fW&æ÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCƒC¢W†V7WF÷"7F–ÆÂfVVG2ÆæRWf–FVæ6R–çFòv÷fW&æ÷""ÂW†V2æ6öçF–ç2‚$Æ—fTÆæTv÷fW&æ÷"ç&T'W”&ÆVVFW%W6Uv—F…6WGWæDÖ–çB"’¢76W'DfÇ6R‚%cRããcCƒC¢ÆV&æVBÆæRv÷fW&æ÷"×W7Bæ÷B&÷'BgFW"dDr"ÂW†V2æ6öçF–ç2‚$Ä•dUôÄäUô„$EõU4TEóc#Cr"’¢76W'EG'VR‚%cRããcCƒC¢v÷fW&æ÷"6÷W&6R×W7B&VÖ–â6ögBæBæöâÖF—6&Æ–ær"Âv÷fW&æ÷"æ6öçF–ç2‚$Ä•dUôÄäUô„$EõU4UôDTÔõDTEõDõõ4ôeEóc33"’bbv÷fW&æ÷"æ6öçF–ç2‚'&WGW&âfÇ6RFò"’¢76W'EG'VR‚%cRããcCƒC¢G'VR†&B×'VrGf—6÷"WF†÷&—G’&VÖ–ç2–çF7B"ÂW†V2æ6öçF–ç2‚%%Tuõ$Td”ÅDU%ô„$Eôd”Â"’bbW†V2æ6öçF–ç2‚$†&E'Vu&Tf–ÇFW"äf–ÇFW%6WfW&—G’ä„$Eôd”Â"’¢Ð  ¢FW7@¢gVâcUóócCƒU÷W%ö'W•öFöÖ–6—G•öæE÷6–ævÆU÷FW&Ö–æÅö'W2‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂ&W6öÇfW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô÷&FW%6—¦U&W6öÇfW#cCCæ·B"’ç&VEFW‡B‚¢fÂ6Æ÷6TÆVFvW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ÷6—F–öä6Æ÷6TÆVFvW"æ·B"’ç&VEFW‡B‚¢fÂW$'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚¢fÂ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSæ·B"’ç&VEFW‡B‚¢fÂ6öç7VÖW'2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôf–æÆ—¦VD'W46öç7VÖW$'&–FvScCcRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚#cCƒRW"%U’×W7B&W&RÆö6ÆÇ’&Vf÷&RW‡÷6–ærFö¶Vå7FFR"ÂW†V2æ6öçF–ç2‚'fÂgVæFVEW%÷6—F–öãcCƒRÒ÷6—F–öâ‚"’bbW†V2æ6öçF–ç2‚'G2ç÷6—F–öâÒgVæFVEW%÷6—F–öãcCƒR"’¢76W'EG'VR‚#cCƒRf–ÆVB%U’×W7B6ö×Vç6FRÆVFvW"ö6æöæ–6ÂöÆ÷Böö67Wæ7’öÆV6R"ÂW†V2æ6öçF–ç2‚'&öÆÆ&6µW$VçG'“cCƒR"’bbW†V2æ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3ç&öÆÆ&6´'W’"’bbW†V2æ6öçF–ç2‚&&÷'D'W“cCƒR"’bbW†V2æ6öçF–ç2‚&&÷'D÷VãcCƒR"’bbW†V2æ6öçF–ç2‚&Ö&´6Æ÷6VB‚"²%Â'W%Â""’bbW†V2æ6öçF–ç2‚%U%ô%U•ô$õ%DTEócCƒR"’¢76W'EG'VR‚#cCƒRW"7V66W72&VF–6FR&WV—&W2gVæFVB6æöæ–6ÂõTâæBö67Wæ7’"ÂW†V2æ6öçF–ç2‚&†4gVæFVD÷VäÆ÷CcCƒR"’bbW†V2æ6öçF–ç2‚$6æöæ–6ÄÖ–çDö67Wæ7•&Vv—7G'“cCcBæ—4÷Vâ"’bbW†V2æ6öçF–ç2‚$Æ–fV7–6ÆRäõTâ"’¢76W'EG'VR‚#cCƒR†2öæR–Ö×WF&ÆRW†V7WF&ÆRW"Ö–æ–×VÒWF†÷&—G’"Â&W6öÇfW"æ6öçF–ç2‚%U%ôU„T5UD$ÄUôÔ”ä”ÕTÕõ4ôÂÒãR"’bb&W6öÇfW"æ6öçF–ç2‚'W$W†V7WF&ÆTÖ–æ–×VÕ6öÂ‚’"’¢76W'DfÇ6R‚#cCƒRW†V7WF÷"6ææ÷B×WFFRF†RW"Ö–æ–×VÒGW&–ærW†V7WF–öâ"ÂW†V2æ6öçF–ç2‚$÷&FW%6—¦U&W6öÇfW#cCCçWFFUW$W†V7WF&ÆTÖ–æ–×VÕ6öÂ"’¢76W'DfÇ6R‚#cCƒRW"Ö—76–ær×7FFRF‚6ææ÷B7–çF†W6—¦Rf–æÂ6æF–FFR"ÂvFRæ6öçF–ç2‚%U%ôU„T5ôõTåõ5”åD„UD”5ôd”äÅô4äD”DDR"’¢76W'DfÇ6R‚#cCƒRÖWFFF6Æ÷6RÆVFvW"6ææ÷BV&Æ—6‚FW&Ö–æÂÆV&æ–ærWfVçG2"Â6Æ÷6TÆVFvW"æ6öçF–ç2‚$6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSçV&Æ—6‚"’ÇÂ6Æ÷6TÆVFvW"æ6öçF–ç2‚$6æöæ–6Äf–æÆ—¦VEG&FT'W3cCcBçV&Æ—6‚"’¢76W'EG'VR‚#cCƒR6æöæ–6ÂW"FW&Ö–æÂ&VGV6W"V&Æ—6†W2F†R&–6‚'W2"ÂW$'&–FvRæ6öçF–ç2‚$6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSçV&Æ—6‚"’¢76W'EG'VR‚#cCƒR&–6‚'W26VçG&ÆÇ’vFW2V&çF–æRæBf÷'v&G2&—G’öæ6R"Â'W2æ6öçF–ç2‚$ÆV&æ–æuV&çF–æTvFScCsç6†÷VÆDG&÷f÷$ÆV&æ–ær"’bb'W2æ6öçF–ç2‚&Vç7W&T6æöæ–6Ä6öç7VÖW'3cCƒR"’¢76W'EG'VR‚#cCƒRÆÂæÖVB6öç7VÖW'2–çfö¶R&VÂ—2"Â6öç7VÖW'2æ6öçF–ç2‚$ÆV&æW%&Wv&D'&–FvScCCæ66WDf–æÆ—¦VCcCƒb"’bb6öç7VÖW'2æ6öçF–ç2‚$w&÷wF„Æ–væVE&Wv&E6†W#cC3’ç6†R"’bb6öç7VÖW'2æ6öçF–ç2‚%F7F–57v—F6†W"æöä6æöæ–6ÅG&FT6Æ÷6VCcCƒb"’bb6öç7VÖW'2æ6öçF–ç2‚$Æ—fTÆæTv÷fW&æ÷"ç&V6÷&D'—74÷WF6öÖR"’bb6öç7VÖW'2æ6öçF–ç2‚$6—FÅ&W6W'fF–öä7&VVCcC3’ç&V6÷&Df–æÆ—¦VCcCƒb"’bb6öç7VÖW'2æ6öçF–ç2‚$f÷'v&D÷WF6öÖTÖöFVÂç&V6÷&D÷WF6öÖR"’bb6öç7VÖW'2æ6öçF–ç2‚$F6†&ö&DFF&÷f–FW"æöä6æöæ–6ÅG&FTf–æÆ—¦VCcCƒR"’¢Ð   ¢FW7@¢gVâcUóócCƒeö6æöæ–6Åö6Æ÷7W&Uö'&–FvUöæEöÆ—fUöf–æÆ—G•ö&U÷G—VB‚’°¢fÂG‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%G&ç67F–öãcCƒbæ·B"’ç&VEFW‡B‚¢fÂWF†÷&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõVæ—fW'6Ä'&–FvTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂÖ&¶WG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖ&¶WG4Æ—fTW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòô7'—FõVæ—fW'6TW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂf–æÆ—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Äf–æÆ—G•W'6—7FVæ6ScCƒbæ·B"’ç&VEFW‡B‚¢fÂ6öç7VÖW'2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôf–æÆ—¦VD'W46öç7VÖW$'&–FvScCcRæ·B"’ç&VEFW‡B‚¢fÂ&—G”'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Äf–æÆ—¦VEG&FT'W3cCcBæ·B"’ç&VEFW‡B‚¢fÂ&W÷'F–ærÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢fÂVæ–f–VBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõVæ–f–VEöÆ–7”†VBæ·B"’ç&VEFW‡B‚¢fÂÖWFÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôWFöæöÖ÷W4ÖWFöÆ–7’æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cCƒbÆÂW"V6öæöÖ–2×WFF–öç2W6RöæRG&ç67F–öâ&VGV6W""À¢G‚æ6öçF–ç2‚""&gVâ÷Vâ‚"""’bbG‚æ6öçF–ç2‚""&gVâFB‚"""’bbG‚æ6öçF–ç2‚""&gVâ6Æ÷6R‚"""’bbG‚æ6öçF–ç2‚""&gVâ&VgVæB‚"""’¢76W'EG'VR‚#cCƒbW"f6FRFV&—B6ææ÷BÖ—6Æ&VÂ6æöæ–6ÂÖöFR2Æ—fR"À¢G‚æ6öçF–ç2‚""&ÖöFT÷fW'&–FRÒ'W"""""’bbWF†÷&—G’æ6öçF–ç2‚""&ÖöFT÷fW'&–FS¢7G&–æsòÒçVÆÂ"""’¢76W'EG'VR‚#cCƒb'&–FvRVçF—F–W2×W7B6öÖRg&öÒ&÷fVB÷WGWBFVÇF2"À¢'&–FvRæ6öçF–ç2‚""'fW&–g•F&vWDFVÇFcCƒb"""’bb'&–FvRæ6öçF–ç2‚""%U4D2÷WGWBFVÇFVç&÷fVBgFW"'&–FvR"""’b`¢'&–FvRæ6öçF–ç2‚""%&VÆV6R6–væGW&RW†—7G2'WB÷WGWBFVÇF—2Vç&÷fVB"""’b`¢'&–FvRæ6öçF–ç2‚""'F&vWDFV6–ÖÇ2Òf–ÆÂæFV6–ÖÇ2"""’bb'&–FvRæ6öçF–ç2‚""'&ööe7FFRÒf–ÆÂç&ööb"""’¢76W'DfÇ6R‚#cCƒb'&–FvR×W7Bæ÷B7–çF†W6—¦RW‡V7FVBU4D2÷WGWB"Â'&–FvRæ6öçF–ç2‚""'6—¦UW6B¢óó¢ã“ƒR"""’¢76W'EG'VR‚#cCƒbÖ&¶WG2÷Vç2æB6Æ÷6W26''’G—VB&ööbæB6æöæ–6ÂVçF—G’"À¢Ö&¶WG2æ6öçF–ç2‚""&FF6Æ72Ö&¶WG4f–ÆÃcCƒb"""’bbÖ&¶WG2æ6öçF–ç2‚""&FF6Æ72Ö&¶WG46Æ÷6ScCƒb"""’b`¢Ö&¶WG2æ6öçF–ç2‚""&6æöæ–6Å÷3cCƒbç&VÖ–æ–æuG•&r"""’bbÖ&¶WG2æ6öçF–ç2‚""$6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSçV&Æ—6‚"""’¢76W'DfÇ6R‚#cCƒb&WVW7FVBÆWfW&vR×W7Bæ÷B6–ÆVçFÇ’FVw&FRFò7÷B"ÂÖ&¶WG2æ6öçF–ç2‚""&FVw&FTfÆ6…Fõ7÷B"""’¢76W'EG'VR‚#cCƒb7'—FòVæ—fW'6R&WV—&W2fW&–f–VB'&–FvRVçF—G’&Vf÷&RõTâ"À¢7'—Fòæ6öçF–ç2‚""&'&–FvRçF&vWDÖ÷VçE&rÃÒÂ"""’bb7'—Fòæ6öçF–ç2‚""$6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ÷Vå÷6—F–öâ"""’b`¢7'—Fòæ6öçF–ç2‚""$DTÅDôÄDUõE%U5Eõ4”r"""’¢76W'EG'VR‚#cCƒb&–6‚f–æÆ—G’—2GW&&ÆRæB6öç7VÖW"4·2&RW'6—7FVB"À¢f–æÆ—G’æ6öçF–ç2‚""%$Td•‚Ò&f–æÃ¢""""’bbf–æÆ—G’æ6öçF–ç2‚""$4µõ$Td•…ócCƒb"""’b`¢f–æÆ—G’æ6öçF–ç2‚""'&V6÷&D6³cCƒb"""’bbf–æÆ—G’æ6öçF–ç2‚""&6¶VD–G3cCƒb"""’b`¢&—G”'W2æ6öçF–ç2‚""'&VFVÆ—fW%VæF–æscCƒb"""’bb&—G”'W2æ6öçF–ç2‚""'&WVW7E&WG'“cCƒb"""’¢76W'EG'VR‚#cCƒböÆ–7’VæF–ærÆ&VÇ2W'6—7BöâWfW'’7F×æB6WGFÆVÖVçB"À¢Væ–f–VBæ6öçF–ç2‚""'WB‚'VæF–ær""""’bbVæ–f–VBæ6öçF–ç2‚""$vÆö&Å66÷RæÆVæ6‚„F—7F6†W'2ç6–FTVffV7B’"""’b`¢ÖWFæ6öçF–ç2‚""'WB‚'VæF–ær""""’bbÖWFæ6öçF–ç2‚""$vÆö&Å66÷RæÆVæ6‚„F—7F6†W'2ç6–FTVffV7B’"""’¢76W'EG'VR‚#cCƒbÖöæW’F‚F—7F–æwV—6†W2÷Vâ6÷7BæBVç&VÆ—¦VBäÂ"À¢&W÷'F–æræ6öçF–ç2‚""'G'W7FVDÆ—fT÷Vä6÷7B"""’bb&W÷'F–æræ6öçF–ç2‚""'VçG'W7FVDÆ—fT÷Vä6÷7B"""’b`¢&W÷'F–æræ6öçF–ç2‚""'G'W7FVDÆ—fUVç&VÆ—¦VEæÂ"""’bb&W÷'F–æræ6öçF–ç2‚""&÷Vå÷Vç&VÆ—¦VEöæ÷E÷vÆÆWE÷VçF–Å÷6VÆÅöf–æÆ—G’"""’¢Ð  ¢FW7@¢gVâcUóócCƒu÷6–ævÆU÷&÷WFU÷&UöfFuöFVfVæ6UöæEöæõögVæFVE÷v—E÷&ö&R‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂVçG'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF&ÆTVçG'”WF†÷&—G“cCSæ·B"’ç&VEFW‡B‚¢fÂ&Vv–ÖRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&Vv–ÖTFWFV7F÷"æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂWfVçBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôV6öæöÖ–4WfVçE66†VÖcCcBæ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cCƒrVçG'’WF†÷&—G’—2&V6÷&FVB&Vf÷&RW†V7WF&ÆRÆæRv÷&²"À¢&÷Bæ–æFW„öb‚'&V6÷&DVçG'”WF†÷&—G“cCƒr"’ãÒbb&÷Bæ–æFW„öb‚'&V6÷&DVçG'”WF†÷&—G“cCƒr"’Â&÷Bæ–æFW„öb‚$f–æÄFV6—6–öävFRæWfÇVFR‚"’¢76W'EG'VR‚#cCƒræÖVB6†F÷rÆæW26ææ÷B7&VFRdDrF–6¶WG2÷"W†V7WF&ÆR÷Vç2"À¢vFRæ6öçF–ç2‚&—56†F÷u&VDöæÇ”ÆæScCƒr"’bbvFRæ6öçF–ç2‚%c5ô4õ$R"’bbvFRæ6öçF–ç2‚%5DäD$B"’bbvFRæ6öçF–ç2‚$44„tTâ"’b`¢vFRæ6öçF–ç2‚%4„DõuôÄäUôdDuõ5U$U54TEócCƒr"’bbvFRæ6öçF–ç2‚$U„T5ôõTåô$Äô4´TEõ4„DõuôÄäUócCƒr"’¢76W'EG'VR‚#cCƒröæRÖ–çB÷fW'6–öâ†2öæRW†V7WF&ÆR%U’6Æ–Ò"À¢vFRæ6öçF–ç2‚&W†V7WF&ÆT'W”6Æ–ÓcCƒrçWD–d'6VçB"’bbvFRæ6öçF–ç2‚$ôäUôU„T5UD$ÄUô%U•õU%ôÔ”åEõdU%4”ôâ"’bbvFRæ6öçF–ç2‚$U„T5ô%U•ôÔ”åEõdU%4”ôåôEUÄ”4DUõ5U$U54TEócCƒr"’¢76W'DfÇ6R‚#cCƒrdDr&V6÷&F–ær6ææ÷BV&Æ—6‚V&Ç’F–6¶WG2"À¢vFRç7V'7G&–ær†vFRæ–æFW„öb‚&gVâ&V6÷&DfFr"’ÂvFRæ–æFW„öb‚&gVâ6ÆV$W†V7WF&ÆT&÷fÂ"’’æ6öçF–ç2‚'V&Æ—6…F–6¶WB‚"’¢76W'EG'VR‚#cCƒr7G&V²FVæ–Â7W&W76W2dDrF–6¶WG2æBf–æÂU„T2vFR"À¢vFRæ6öçF–ç2‚$dDuõ5U$U54TEôTåE%•ôUD„õ$•E•ócCƒr"’bbvFRæ6öçF–ç2‚$U„T5ôtDUô$Äô4´TEôTåE%•ôUD„õ$•E•ócCƒr"’¢76W'EG'VR‚#cCƒrFVfVç6—fRt•BæB¦W&ò×6–væÂ&ö&W2&VÖ–â6†F÷rÖöæÇ’"À¢&÷Bæ6öçF–ç2‚$DTdTå4•dUõt•Eõ$ô$Uõ5U$U54TEócCƒr"’bb&÷Bæ6öçF–ç2‚$DTdTå4•dUõt•Eõ4„DõuôôäÅ•ócCƒr"’b`¢&÷Bæ6öçF–ç2‚'6–væÂÒ"²r"r²%t•B"²r"r’bb&÷Bæ6öçF–ç2‚'6†÷VÆEG&FRÒfÇ6R"’¢76W'EG'VR‚#cCƒ‚7G&V²6†–ær—2ÖöFRÖÆæR66÷VBæB&÷VæFVB&÷fR¦W&ò"À¢VçG'’æ6öçF–ç2‚&6ö†÷'D¶W’†RæÖöFRÂRæVçG'”ÆæR’"’bbVçG'’æ6öçF–ç2‚'6—¦T×VÇF—Æ–W$f÷#cCƒ‚"’b`¢VçG'’æ6öçF–ç2‚'7G&V²ãÒ5E$Tµô„$EôÄ”Ô•BÇÂ6ööÆ–ærÓâã3R"’b`¢VçG'’æ6öçF–ç2‚'7G&V²ãÒ5E$Tµô„$EôÄ”Ô•BÓâã"’¢76W'EG'VR‚#cCƒ‚vÆö&Â&Vv–ÖRæòÆöævW"6öç7VÖW27G&V²7FFRv†–ÆRW†V7WF÷'2&WF–âf–æÂÆæR6—¦–ær"À¢&Vv–ÖRæ6öçF–ç2‚'66÷&TfÆö÷$FVÇFcCƒr‚’"’bb&Vv–ÖRæ6öçF–ç2‚'6—¦T×VÇF—Æ–W#cCƒr‚’"’b`¢W†V7WF÷"æ6öçF–ç2‚&vFUfW&F–7CcCSç&V6öÖÖVæFVE6—¦U6öÂ"’bbW†V7WF÷"æ6öçF–ç2‚&VçG'”WF†÷&—G•6öÃcCƒr"’¢76W'EG'VR‚#cCƒrV6öæöÖ–2WfVçB&VÆ—¦VBÖF6†W2w&÷72ÖÆVFvW"6öçfVçF–öâv—F‚fVW26W&FR"À¢WfVçBæ6öçF–ç2‚'fÂ&VÆ—¦VBÒw&÷72ÒÆÆö6FVD6÷7B"’bbWfVçBæ6öçF–ç2‚&æWE&ö6VVG56öÂÒæWB"’bbWfVçBæ6öçF–ç2‚&W†—DfVW56öÂÒfVW2"’¢Ð  ¢FW7@¢gVâcUóócCƒuö&6¶w&÷VæE÷'VçF–ÖUö—5÷6W'f–6Uö÷væVE÷&öw&W75ö&6¶VEöæEöF÷¦U÷&V6÷fW&&ÆR‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BôDTæ·B"’ç&VEFW‡B‚¢fÂvF6†FörÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6W'f–6UvF6†Föræ·B"’ç&VEFW‡B‚¢fÂWF†÷&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô&6¶w&÷VæEG&F–ætWF†÷&—G“cCc’æ·B"’ç&VEFW‡B‚¢fÂÖæ–fW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âôæG&ö–DÖæ–fW7Bç†ÖÂ"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cCƒr6W'f–6RÆVæ6‚Â&W67VRÂ7F÷æBFW7G&÷’÷vâ&6¶w&÷VæB'VçF–ÖRWF†÷&—G’"À¢&÷Bæ6öçF–ç2‚$&÷E6W'f–6Rç7F'D&÷BæÆVæ6ƒcCƒr"’bb&÷Bæ6öçF–ç2‚$&÷E6W'f–6Rç&W67VU&VÆVæ6ƒcCƒr"’b`¢&÷Bæ6öçF–ç2‚$&÷E6W'f–6Rç7F÷&÷BâB"²'6÷W&6RãcCƒr"’bb&÷Bæ6öçF–ç2‚$&÷E6W'f–6RæöäFW7G&÷“cCƒr"’b`¢&÷Bæ6öçF–ç2‚'&Vv—7FW%'VçF–ÖT¦ö""’¢76W'EG'VR‚#cCƒr&6¶w&÷VæB†VÇF‚&WV—&W2Æ—fRÆö÷Â6W'f–6RWF†÷&—G’æBg&W6‚&öw&W72"À¢&÷Bæ6öçF–ç2‚&FF6Æ72&6¶w&÷VæE'VçF–ÖT†VÇFƒcCƒr"’bb&÷Bæ6öçF–ç2‚&Æö÷7F—fRbbf÷&Vw&÷VæD7F—fRbbWF†÷&—G”7F—fR"’b`¢&÷Bæ6öçF–ç2‚'&öw&W74vRÃÒ#óÂ"’bb&÷Bæ6öçF–ç2‚&—4&6¶w&÷VæE'VçF–ÖT†VÇF‡“cCƒr"’¢76W'EG'VR‚#cCƒrvF6†För&÷WFW27FÆR7F—fR'VçF–ÖRFò†V'F&VB&W67VR&F†W"F†âæòÖ÷5D%B"À¢vF6†Föræ6öçF–ç2‚$„T%D$TEõ$U45TR"’bbvF6†Föræ6öçF–ç2‚$&÷E6W'f–6Ræ—4&6¶w&÷VæE'VçF–ÖT†VÇF‡“cCƒr‚’"’b`¢vF6†Föræ6öçF–ç2‚$&÷E6W'f–6Rä5D”ôåôÄôõô„T%D$TB"’bbvF6†Föræ6öçF–ç2‚'fÂ—5'Vææ–ærÒ&÷E6W'f–6Rç7FGW2ç'Vææ–ær"’¢76W'EG'VR‚#cCƒr7F—f—G’&6¶w&÷VæBwV&BW6W2†VÇF‚'WB6ææ÷B×WFFR'VçF–ÖRWF†÷&—G’"À¢æ6öçF–ç2‚&—4ç”7F—f—G•f—6–&ÆScCƒr"’bbæ6öçF–ç2‚$&÷E6W'f–6Ræ—4&6¶w&÷VæE'VçF–ÖT†VÇF‡“cCƒr‚’"’b`¢æ6öçF–ç2‚'&V6÷fW'”7F–öâÒ–b‡'VçF–ÖT7F—fR’&÷E6W'f–6Rä5D”ôåôÄôõô„T%D$TB"’b`¢æ6öçF–ç2‚$&6¶w&÷VæEG&F–ætWF†÷&—G“cCc’ç6WE'VçF–ÖT7F—fR"’¢76W'EG'VR‚#cCƒrf÷&Vw&÷VæB&ööb—2WfVçBÖÆö6ÂÂæ÷B†&F6öFVBFVÆVÖWG'’"À¢&÷Bæ6öçF–ç2‚'6W'f–6Tf÷&Vw&÷VæD7F—fScCƒrÒG'VR"’bb&÷Bæ6öçF–ç2‚'6W'f–6Tf÷&Vw&÷VæD7F—fScCƒrÒfÇ6R"’b`¢&÷Bæ6öçF–ç2‚&—56W'f–6Tf÷&Vw&÷VæBÒ6W'f–6Tf÷&Vw&÷VæD7F—fScCƒr"’bb&÷Bæ6öçF–ç2‚&—56W'f–6Tf÷&Vw&÷VæBÒG'VR"’¢76W'EG'VR‚#cCƒr†V'F&VB&V76W'G2f÷&Vw&÷VæBÂv¶RæBæWGv÷&²wV&G2–æFWVæFVçBöbT’"À¢&÷Bæ6öçF–ç2‚&Vç7W&TÇv—4öå'VçF–ÖTwV&G3c3‚"²r"r²&Æö÷ö†V'F&VEócCƒr"²r"r²"’"’b`¢&÷Bæ6öçF–ç2‚$&6¶w&÷VæEG&F–ætWF†÷&—G“cCc’æöå67&VVäöfeF–6²‚’"’b`¢&÷Bæ6öçF–ç2‚$&6¶w&÷VæEG&F–ætWF†÷&—G“cCc’æöåV”'6VçEF–6²‚’"’¢76W'EG'VR‚#cCƒr7FÆR†V'F&VB6ææ÷B&Wf—fR6öæf—&ÖVBÖçVÂ7F÷"À¢&÷Bæ–æFW„öb‚&—4ÖçVÅ7F÷&WVW7FVB†Æ–6F–öä6öçFW‡B’"Â&÷Bæ–æFW„öb‚$5D”ôåôÄôõô„T%D$TBÓâ"’’–à¢†&÷Bæ–æFW„öb‚$5D”ôåôÄôõô„T%D$TBÓâ"’²’VçF–Â&÷Bæ–æFW„öb‚&Vç7W&TÇv—4öå'VçF–ÖTwV&G3c3‚"²r"r²&Æö÷ö†V'F&VEócCƒr"²r"r²"’"’¢76W'EG'VR‚#cCƒr&GFW'’&ö×BÆF6‚&W6WG2öæ6RW"&ö6W72VçF–ÂW†V×F–öâ—2w&çFVB"À¢æ6öçF–ç2‚&&GFW'•ö÷E÷&ö×FVE÷6W76–öâ"’bbæ6öçF–ç2‚'W"×&ö6W72&ö×BÆF6‚"’¢76W'EG'VR‚#cCƒræG&ö–BB7V6–Â×W6Rf÷&Vw&÷VæB6W'f–6RFV6Æ&W2—G27V'G—R"À¢Öæ–fW7Bæ6öçF–ç2‚$dõ$Tu$õTäEõ4U%d”4Uõ5T4”ÅõU4R"’bbÖæ–fW7Bæ6öçF–ç2‚%$õU%E•õ5T4”ÅõU4Uôdu5õ5T%E•R"’b`¢Öæ–fW7Bæ6öçF–ç2‚&f÷&Vw&÷VæE6W'f–6UG—SÒ"²r"r²&FF7–æ7Ç7V6–ÅW6R"²r"r’¢76W'EG'VR‚#cCc’T’6ÆÆW"&V¦V7F–öâ&VÖ–ç2†&BæB6W'f–6RWF†÷&—G’&VÖ–ç26W&FR"À¢WF†÷&—G’æ6öçF–ç2‚%T•ôÄ”dT5”4ÄUõ%TåD”ÔUôÕUDD”ôåõ$T¤T5DTB"’bbWF†÷&—G’æ6öçF–ç2‚'V”6ÆÆW$&Æ6¶Æ—7B"’¢Ð  ¢FW7@¢gVâcUóócCƒuöÆ–çE÷&VÆV6UöW'&÷'5ö&Uö6Æ÷6VE÷v—F†÷WEöf¶UöW†V7WF–öåöf–æÆ—G’‚’°¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõVæ—fW'6Ä'&–FvTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂÖ&¶WG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖ&¶WG4Æ—fTW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ—VÆ–æRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’õ—VÆ–æT†VÇF„7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂÖæ–fW7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âôæG&ö–DÖæ–fW7Bç†ÖÂ"’ç&VEFW‡B‚¢76W'EG'VR‚%cRããcCƒs¢'&–FvRW†7B&r6öçfW'6–öâ×W7B7W÷'B’#bæB&V¦V7B÷fW&fÆ÷r&F†W"F†âf'&–6F–ærÆöæräÔ…õdÅTR÷¦W&ò"À¢'&–FvRæ6öçF–ç2‚'FôÆöætW†7D6ö×CcCƒr"’b`¢'&–FvRæ6öçF–ç2‚%$uõTåD•E•ôõdU$dÄõuõ$T¤T5DTEócCƒr"’b`¢'&–FvRæ6öçF–ç2‚"â"²&ÆöæufÇVTW†7B‚’"’b`¢'&–FvRæ6öçF–ç2‚&6F6‚…ó¢F‡&÷v&ÆR’²ÆöæräÔ…õdÅTRÒ"’¢76W'EG'VR‚%cRããcCƒs¢Ö&¶WG26Æ÷6R×W7B&V¦V7B6æöæ–6ÂVçF—F–W2æ÷B&W&W6VçF&ÆR'’—G2ÆöærW†V7WF–öâ’"À¢Ö&¶WG2æ6öçF–ç2‚$&–t–çFVvW"çfÇVTöb‡Væ—G2’Ò6æöæ–6Å&r"’b`¢Ö&¶WG2æ6öçF–ç2‚$6æöæ–6Â6Æ÷6RVçF—G’W†6VVG27W÷'FVBW†V7WF–öâ&ævR"’¢fÂÆö6µ7F'BÒW†V7WF÷"æ–æFW„öb‚&–b‚6öÒæÆ–fV7–6ÆV&÷BæVæv–æRç6VÆÂå6VÆÄW†V7WF–öäÆö6·2çG'”7V—&R‡G2æÖ–çB’’"¢fÂwV&FVDÆörÒW†V7WF÷"æ–æFW„öb‚&–b†6öÒæÆ–fV7–6ÆV&÷BæVæv–æRç6VÆÂå6VÆÅ7ÔwV&Bç6†÷VÆDÆöt&Æö6¶VB‡G2æÖ–çBÂ&V6öâ’’²"ÂÆö6µ7F'B¢fÂ&WGW&ägFW$ÆörÒW†V7WF÷"æ–æFW„öb‚'&WGW&â"ÂwV&FVDÆör¢76W'EG'VR‚%cRããcCƒs¢&Æö6¶VB×6VÆÂf÷&Vç6–2Æövv–ær×W7B&RW‡Æ–6—FÇ’66÷VBæB&WGW&âv—F†÷WBW†V7WF–ær6VÆÂ"À¢Æö6µ7F'BãÒbbwV&FVDÆörâÆö6µ7F'Bbb&WGW&ägFW$ÆörâwV&FVDÆör¢76W'EG'VR‚%cRããcCƒs¢—VÆ–æRFW‡B÷F–Ö—¦F–öâ¶VW2F†R7W÷'FVBÆVv7’6öç7FçBVæFW"âW‡Æ–6—BÆ–çB6öçG&7B"À¢—VÆ–æRæ6öçF–ç2‚&6öæf–wW&TGV×FW‡DÆ–÷WCcCƒr"’bb—VÆ–æRæ6öçF–ç2‚$Æ–æT'&V¶W"ä%$Tµõ5E$DTu•õ4”ÕÄR"’¢76W'EG'VR‚%cRããcCƒs¢æG&ö–BB7V6–Â×W6Rf÷&Vw&÷VæB6W'f–6RÖWFFF&VÖ–ç2FV6Æ&VB"À¢Öæ–fW7Bæ6öçF–ç2‚%$õU%E•õ5T4”ÅõU4Uôdu5õ5T%E•R"’¢Ð  ¢FW7@¢gVâcUóócCƒ…÷'VçF–ÖUö6†ö¶UöæEöÆæUöWF†÷&—G•ö&U÷6÷W&6Uö&÷VæFVB‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂVçG'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF&ÆTVçG'”WF†÷&—G“cCSæ·B"’ç&VEFW‡B‚¢fÂ&Vv–ÖRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&Vv–ÖTFWFV7F÷"æ·B"’ç&VEFW‡B‚¢fÂ†VÇF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂÆVFvW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õW$66÷VçDÆVFvW#cC3æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cCƒ‚6÷7FÇ’÷7BÖÆV&æ–ær6æ—F—¦RæBvF6†Föw2&RFVFÆ–æRÖ&÷VæFVB6–ævÆRÖfÆ–v‡Bv÷&²"À¢&÷Bæ6öçF–ç2‚&7–6ÆU÷6æ—F—¦UócCƒ‚"’bb&÷Bæ6öçF–ç2‚'66ææW%ö†VÇF…ócCƒ‚"’b`¢&÷Bæ6öçF–ç2‚'&ö¦V7E÷6æ—W%÷7vVWócCƒ‚"’bb&÷Bæ6öçF–ç2‚&Ö&¶WG5öVæv–æU÷vF6†FöuócCƒ‚"’b`¢&÷Bæ6öçF–ç2‚$Ö–çFVææ6Uv÷&¶W#cCC‚ç7V&Ö—B"’¢76W'EG'VR‚#cCƒ‚7G&V²WF†÷&—G’W6W2WfVçBÖÆö6ÂÖöFRæBÆæRæBæWfW"†&BÖFVæ–W27G&FVw’†—7F÷'’"À¢VçG'’æ6öçF–ç2‚&6ö†÷'D¶W’†RæÖöFRÂRæVçG'”ÆæR’"’bbVçG'’æ6öçF–ç2‚$U„T5UD$ÄUôTåE%•ô4ô„õ%Eõ4„TEócCƒ‚"’b`¢VçG'’æ6öçF–ç2‚%fW&F–7BäÄÄõr"’bbVçG'’æ6öçF–ç2‚$FV6—6–öâ…fW&F–7BäDTå•ôÄõ4”äuõ5E$T²Âã"’¢fÂ&VfÆW‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÆ÷6–æu7G&Vµ&VfÆWƒcC3’æ·B"’ç&VEFW‡B‚¢fÂW&Ö—BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄW†V7WF–öåW&Ö—Bæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚#cCƒ‚GWÆ–6FRÆ÷6–ær×7G&V²&VfÆW‚—26ö†÷'BFVÆVÖWG'’öæÇ’æB6ææ÷BfWFòf–æÄW†V7WF–öåW&Ö—B"À¢&VfÆW‚æ6öçF–ç2‚$Äõ4”äuõ5E$Tµô4ô„õ%Eôô%4U%dTEócCƒ‚"’bb&VfÆW‚æ6öçF–ç2‚'&WGW&âfÇ6R"’b`¢W&Ö—Bæ6öçF–ç2‚$Äõ4”äuõ5E$Tµô4ô„õ%EôäõôtÄô$ÅõdUDõócCƒ‚"’b`¢W&Ö—Bæ6öçF–ç2‚&–b†6öÒæÆ–fV7–6ÆV&÷BæVæv–æRçG'WF‚äÆ÷6–æu7G&Vµ&VfÆWƒcC3’ç6†÷VÆD&Æö6´æWt'W—2‚’’"’¢76W'EG'VR‚#cCƒ‚vw&VvFR&Vv–ÖR6ææ÷B6ö×÷6RÆæR7G&V²–çFò66÷&TfÆö÷#Ó÷"6—¦SÓ"À¢&Vv–ÖRæ6öçF–ç2‚'66÷&TfÆö÷$FVÇFcCƒr‚’"’bb&Vv–ÖRæ6öçF–ç2‚'6—¦T×VÇF—Æ–W#cCƒr‚’"’b`¢&Vv–ÖRæ6öçF–ç2‚%&Vv–ÖRä4„õÓâã3R"’¢76W'EG'VR‚#cCƒ‚&W÷'B6Æ76–f–W2&WVFVB&V6VçB7FÆÇ22'VçF–ÖR6†ö¶R&F†W"F†â—6öÆFVBT’"À¢†VÇF‚æ6öçF–ç2‚'&V6VçD7–6ÆUF–ÃcCƒ‚"’bb†VÇF‚æ6öçF–ç2‚'&V6VçE6WfW&ScCƒ‚ãÒ2"’b`¢†VÇF‚æ6öçF–ç2‚%%TåD”ÔUô4„ô´S¢&WVFVBöW66ÆF–ær7FÆÇ2"’b`¢†VÇF‚æ6öçF–ç2‚'&V6VçE6WfW&ScCƒ‚ÃÒ"’¢76W'EG'VR‚#cCƒ‚'VçF–ÖR&W—"&W6W'fW2F†R6æöæ–6ÂW"ÆVFvW"WF†÷&—G’"À¢ÆVFvW"æ6öçF–ç2‚&ö&¦V7BW$66÷VçDÆVFvW#cC3"’bbÆVFvW"æ6öçF–ç2‚&gVâ76W'D–çf&–çB"’b`¢ÆVFvW"æ6öçF–ç2‚&gVâ÷Vä6÷7D&6—56öÂ"’bbÆVFvW"æ6öçF–ç2‚&gVâ&VÆ—¦VEæÅ6öÂ"’¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôVÖW&vVçDwV&G&–Ç2æ·B"’ç&VEFW‡B‚¢fÂ&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ÷6—F–öå&Vv—7G'•&—G”VF—CcCcBæ·B"’ç&VEFW‡B‚¢76W'EG'VR‚#cCƒ‚&Vv—7G'’&VÖ–ç2öæRFöÖ–26æöæ–6Â&ö¦V7F–öâv†–ÆRcCƒ’vw&VvFW27F—fRV6öæöÖ–2Æ÷G2'’Ö–çB"À¢&Vv—7G'’æ6öçF–ç2‚$FöÖ–5&VfW&Væ6SÄÖÅ7G&–ærÂ÷6—F–öä–æfóãâ"’bb&Vv—7G'’æ6öçF–ç2‚&÷Vå÷6—F–öç2ç6WB‡&WÆ6VÖVçB’"’b`¢&Vv—7G'’æ6öçF–ç2‚&w&÷W'’²—BæÖ–çBÒ"’bb&Vv—7G'’æ6öçF–ç2‚&Æ÷G2æföÆB†¦fæÖF‚ä&–t–çFVvW"å¤U$ò’"’b`¢&÷Bæ6öçF–ç2‚$6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ÷Vå÷6—F–öç2‚’"’¢76W'EG'VR‚#cC“&—G’6ö×&W27W'&VçBÖÖöFRÖ–çBÖvw&VvFVBVçF—G’æB&6—2–âF†R&Vv—7G'’–FVçF—G’FöÖ–â"À¢&—G’æ6öçF–ç2‚&7F—fTÖ–çE&ö¦V7F–öç3cC“†7F—fTÖöFScC“’"’bb&—G’æ6öçF–ç2‚'&VÖ–æ–æuG•&r"’bb&—G’æ6öçF–ç2‚'&VÖ–æ–æt6÷7D&6—56öÂ"’¢Ð  ¢FW7@¢gVâcUóócCƒ•ö6÷'&V7FæW75ö6†ö¶UöæEö6æöæ–6Å÷vÆÆWEö6öçG&7B‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂv÷&¶W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÖ–çFVææ6Uv÷&¶W#cCC‚æ·B"’ç&VEFW‡B‚¢fÂÖW&vRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶VäÖW&vUVWVRæ·B"’ç&VEFW‡B‚¢fÂ÷VävFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂ6—FÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Ä6—FÄWF†÷&—G“cCSæ·B"’ç&VEFW‡B‚¢fÂ6æöæ–6ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂö67Wæ7’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÄÖ–çDö67Wæ7•&Vv—7G'“cCcBæ·B"’ç&VEFW‡B‚¢fÂ&WÆ’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%&WÆ“cCcBæ·B"’ç&VEFW‡B‚¢fÂWfVçG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôV6öæöÖ–4WfVçE66†VÖcCcBæ·B"’ç&VEFW‡B‚¢fÂvÆÆWBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²õ6öÆævÆÆWBæ·B"’ç&VEFW‡B‚¢fÂW'2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'4Ö&¶WDFFfWF6†W"æ·B"’ç&VEFW‡B‚¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cCƒ’Ö–çFVææ6RæB66ææW"&V6÷fW'’×W7B&R—6öÆFVBg&öÒ$õEôÄôõ"À¢v÷&¶W"æ6öçF–ç2‚%F‡&VB‡F6²ÂÂ$DRÖÖ–çBÒ"’bbv÷&¶W"æ6öçF–ç2‚&æWtf—†VEF‡&VEööÂ"’b`¢&÷Bæ6öçF–ç2‚&–æW'E÷66ææW%ö†&E÷&V6÷fW'•ócCƒ’"’bb&÷Bæ6öçF–ç2‚$Ö–çFVææ6Uv÷&¶W#cCC‚ç7V&Ö—B"’¢76W'EG'VR‚#cCƒ’–çF¶Rv÷&²×W7B&R&÷VæFVBæB&÷FF–ærv—F†÷WB6‡&–æ¶–ærF†R&Vv—7G'’"À¢ÖW&vRæ6öçF–ç2‚&Ö…66ã¢–çBÒS""’bbÖW&vRæ6öçF–ç2‚&Ö„VÖ—C¢–çBÒ“b"’b`¢&÷Bæ6öçF–ç2‚%tD4„Ä•5Eõ$”õ$•E•õ$õDD”äuõt”äDõuócCƒ’"’bb&÷Bæ6öçF–ç2‚'&WVBƒ#‚’"’¢fÂöÆD†&EfWFòÒ$ÄT$äTEõDõ„”5ôÄäUò"²$„$EõdUDõóc3s’ ¢76W'EG'VR‚#cCƒ’ÆV&æVBF÷†–6—G’×W7B6ögB×6†Rv—F‚gVÆÂÆæRFVÆVÖWG'’æBæWfW"†&B×fWFò"À¢÷VävFRæ6öçF–ç2‚$ÄT$äTEõDõ„”5ôÄäUõ4ôeEõ4„TEócCƒ—ÆÆæSÒ"’bb÷VävFRæ6öçF–ç2†öÆD†&EfWFò’¢76W'EG'VR‚#cC“6æöæ–6Â÷6—F–öç2Væf÷&6RöæR7F—fRV6öæöÖ–2÷6—F–öâW"ÖöFRæBÖ–çB"À¢6æöæ–6Âæ6öçF–ç2‚&FF6Æ727F—fTÖ–çE&ö¦V7F–öãcCƒ’"’bb6æöæ–6Âæ6öçF–ç2‚&w&÷W'’²Â""²"B"²'¶—BæÖöFRæÆ÷vW&66R‚—×Â"²"B"²'¶—BæÖ–çGÕÂ"Ò"’b`¢6æöæ–6Âæ6öçF–ç2‚$4äôä”4Åõ4ÔUôÔôDUôÔ”åEôõTåõ$T¤T5DTEócC“"’bbö67Wæ7’æ6öçF–ç2‚'&V6öæ6–ÆT7F—fTg&öÔ6æöæ–6ÃcCƒ’"’¢76W'EG'VR‚#cC“WV—G’fÇVW2V6‚W"ÖöFRÖ–çBöæ6RæBF†RU"†W&ò6†÷w244‚ÇW2WV—G’–â66W76–&ÆR'&V¶F÷vâ…cRããccb’"À¢6—FÂæ6öçF–ç2‚&7F—fTÖ–çE&ö¦V7F–öç3cC“…Â'W%Â"’"’bb6—FÂæ6öçF–ç2‚&Ö&µ&÷f–FW"†vw&VvFRæÖ–çB’"’b`¢Ö–âæ6öçF–ç2‚%Væ–f–VD66÷VçE6æ6†÷Ccc3Rç&VB…Â$ÔTÔUÂ"’"’bbÖ–âæ6öçF–ç2‚%U"+r44‚"’bbÖ–âæ6öçF–ç2‚%W%vÆÆWE7F÷&Rç&W7F÷&R"’¢76W'EG'VR‚#cCƒ’&WÆ’6'&–W2&RÖWF†÷&—G’†—7F÷'’æBföÆG2&÷VæFVBÖWfVçBWf–7F–öâv—F†÷WB&Ww&—F–ærÖöæW’÷"Æ÷G2"À¢&WÆ’æ6öçF–ç2‚&W7F&Æ—6…&WÆ”6''“cCƒ’"’bbWfVçG2æ6öçF–ç2‚&föÆDWf–7FVD–çFõ&WÆ”6''“cCƒ’"’b`¢WfVçG2æ6öçF–ç2‚%$UÄ•ô4%%•ôÔ”u$DTEôe$ôÕôÄTDtU%ócCƒ’"’¢76W'EG'VR‚#cCƒ’&÷f–FW"F‡26ææ÷BæW7B'Vä&Æö6¶–ær–âW'2÷"vÆÆWBG&ç67F–öâ&÷WF–ær"À¢vÆÆWBæ6öçF–ç2‚&¶÷FÆ–ç‚æ6÷&÷WF–æW2ç'Vâ"²$&Æö6¶–ær"’bbW'2æ6öçF–ç2‚&¶÷FÆ–ç‚æ6÷&÷WF–æW2ç'Vâ"²$&Æö6¶–ær"’b`¢vÆÆWBæ6öçF–ç2‚'7V&Ö—E&÷FV7FVDæõv—CcCƒ’"’bbvÆÆWBæ6öçF–ç2‚$¤•DõôDTDÄ”äUócCƒ’"’ææ÷B‚’¢Ð  ¢FW7@¢gVâcUóócC“ö–çfVçF÷'•öFVGWöæEöW†V7WF&ÆUöfÆö÷%ö&Uö6æöæ–6Â‚’°¢fÂ&W6öÇfW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô÷&FW%6—¦U&W6öÇfW#cCCæ·B"’ç&VEFW‡B‚¢fÂ÷VävFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ6æöæ–6ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂG‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%G&ç67F–öãcCƒbæ·B"’ç&VEFW‡B‚¢fÂFW&Ö–æÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚¢fÂÆ"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆ"ôÆÆÔÆ%G&FW"æ·B"’ç&VEFW‡B‚¢fÂ6Æ÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6Æ÷D†VÇF„vFRæ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚#cC“&W6öÇfW"×W7B&W6W'fRF†RW†V7WF&ÆRfÆö÷"öæÇ’v†VâfVRÖv&R6—FÂæBÆæR66âgVæB—B"À¢&W6öÇfW"æ6öçF–ç2‚&WF†÷&—G”6Æ×÷'G2"²#cC“‚"’bb&W6öÇfW"æ6öçF–ç2‚%U%ôTåE%•ôdTUõ$U4U%dUõ$DUócC“"’b`¢&W6öÇfW"æ6öçF–ç2‚$4•DÅô$TÄõuôÔ”åôU„T5UD$ÄUócC“"’bb&W6öÇfW"æ6öçF–ç2‚&Ö–æ–×VÔgVæF&ÆScC“"’¢76W'EG'VR‚#cC“&V6†V6²×W7Bæ÷BWF†÷&—¦R&Vf÷&R6æöæ–6Â6—¦R&W6öÇWF–öâ"À¢÷VävFRæ6öçF–ç2‚$U„T5ôõTåõ$T4„T4µõ4•¤UõTäD”äuócC“"’b`¢÷VävFRæ6öçF–ç2‚$U„T5ôõTåô$Äô4´TEõ4•¤UôäõEôU„T5UD$ÄUócC“"’b`¢÷VävFRæ6öçF–ç2‚$U„T5õD”4´UEôDTdU%$TEõTåD”Åõ4•¤Uõ$U4ôÅdTEócC“"’b`¢òòcRããcC“r*s(	B&W6öÇfVE6—¦U6öÂ—2æ÷r6÷W&6VBg&öÐ¢òòVffV7F—fU&W6öÇfVE6—¦ScC“r†föÆBöb&U&W6öÇfVE6—¦U6öÃcC“ ¢òòæBF†R6VÆVD÷&FW%6—¦TWF†÷&—G“cC“r6VÂ’âV—F†W"æÖP¢òò&÷fW2F†RF–6¶WB×V&Æ—6‚6—FR6öç7VÖW2F†R6æöæ–6À¢òò&W6öÇfVB6—¦R†æWfW"ÖçVf7GW&VBãfÆÆ&6²’à¢†÷VävFRæ6öçF–ç2‚'&W6öÇfVE6—¦RÒVffV7F—fU&W6öÇfVE6—¦ScC“ræ6öW&6TDÆV7Bƒã’"’b`¢÷VävFRæ6öçF–ç2‚&fFt–çFVçCcS’æ6÷’‚"’’b`¢W†V7WF÷"æ6öçF–ç2‚%$UõD”4´UEõ4•¤Uõ$U4ôÅUD”ôåôd”ÄTEócC“"’¢76W'EG'VR‚#cC“GWÆ–6FR÷6—F–öâ7&VF–öâ×W7B&R&Æö6¶VBB6æöæ–6Â×WFF–öâWF†÷&—G’"À¢6æöæ–6Âæ6öçF–ç2‚$4äôä”4Åõ4ÔUôÔôDUôÔ”åEôõTåõ$T¤T5DTEócC“"’b`¢6æöæ–6Âæ6öçF–ç2‚&—BæÖöFRÓÒ6æöæ–6ÄÖöFScC“"’bb6æöæ–6Âæ6öçF–ç2‚&—BæÖ–çBÓÒÖ–çB"’¢76W'EG'VR‚#cC“†—7F÷&–6ÂGWÆ–6FRW"FV&—G2×W7B&VgVæB&6—2v—F†÷WBÆV&æ–ær6öçFÖ–æF–öâ"À¢G‚æ6öçF–ç2‚'&VgVæDGWÆ–6FT7F—fTÖ–çDÆ÷G3cC“"’bbG‚æ6öçF–ç2‚$EUÄ”4DUõ4ÔUôÔ”åEõ$TeTäEócC“"’b`¢FW&Ö–æÂæ6öçF–ç2‚'7W&W74ÆV&æ–ætfæ÷WB"’bbFW&Ö–æÂæ6öçF–ç2‚$”ådTåDõ%•ô4õ%$T5D”ôåôÄT$ä”äuõ5U$U54TEócC“"’¢76W'EG'VR‚#cC“Ä"‡—÷F†W6W2×W7B6öÆW66RW"Ö–çBæB&VÖ–â÷WG6–FR6æöæ–6Â¦÷W&æÂ–çfVçF÷'’"À¢Æ"æ6öçF–ç2‚$Ä%õ4ÔUôÔ”åEô…•õD„U4•5ô4ôÄU44TEócC“"’bbÆ"æ6öçF–ç2‚$Ä%õ4äD$õ…ôõTåô•4ôÄDTEócC“"’b`¢Æ"æ6öçF–ç2‚%c4¦÷W&æÅ&V6÷&FW"ç&V6÷&D÷Vâ"’bbÆ"æ6öçF–ç2‚%c4¦÷W&æÅ&V6÷&FW"ç&V6÷&D6Æ÷6R"’¢76W'EG'VR‚#cC“6Æ÷BæB&W÷'B6÷VçG2×W7B&VB6æöæ–6Â7W'&VçBÖÖöFR7F—fRÖ–çG2"À¢6Æ÷Bæ6öçF–ç2‚&7F—fTÖ–çE&ö¦V7F–öç3cC“…Â'W%Â"’"’bb&W÷'Bæ6öçF–ç2‚$6æöæ–6Â7F—fRÖ–çG2†7W'&VçBÖöFR“¢"’b`¢&W÷'Bæ6öçF–ç2‚$Ä"6æF&÷‚&ö¦V7F–öã¢"’¢Ð   ¢FW7@¢gVâcUóócC“÷6—¦Uö&Vf÷&UöÆÆ÷u÷&–Ö'•öÆæUöfæ÷WEöæEöW†7Eö66WFæ6UöF–væ÷7F–72‚’°¢fÂ&W6öÇfW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô÷&FW%6—¦U&W6öÇfW#cCCæ·B"’ç&VEFW‡B‚¢fÂ–çf&–çBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô÷&FW%6—¦U&W6öÇfW$–çf&–çCcCc‚æ·B"’ç&VEFW‡B‚¢fÂ÷VävFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂW&Ö—BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄW†V7WF–öåW&Ö—Bæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ&ö&&–Æ—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆ—fU&ö&&–Æ—G”Væv–æRæ·B"’ç&VEFW‡B‚¢fÂ66WFæ6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô66WFæ6T–çf&–çDVF—CcCCæ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cC“6—¦–ær&÷VæF'’×W7B6ö×&R–çFVvW"Æ×÷'G2Â–æ6ÇVF–ærW†7BWVÆ—G’"À¢&W6öÇfW"æ6öçF–ç2‚%4ôÅôÄÕõ%E5ócC“"’bb&W6öÇfW"æ6öçF–ç2‚'FôÆ×÷'G3cC“"’b`¢&W6öÇfW"æ6öçF–ç2‚&&÷VæFVDW†V7WF&ÆTÆ×÷'G3cC“‚ãÒÖ–äW†V4Æ×÷'G3cC“"’b`¢&W6öÇfW"æ6öçF–ç2‚$ôµôÔ”åõ$ôÔõDTEócc"’bb–çf&–çBæ6öçF–ç2‚$66‚æBÆæR6&R"’¢fÂ6—¦U&V6†V6²Ò÷VävFRæ–æFW„öb‚$U„T5ôõTåõ$T4„T4µõ4•¤UõTäD”äuócC“"¢fÂÖ–çD6Æ–ÒÒ÷VävFRæ–æFW„öb‚&W†V7WF&ÆT'W”6Æ–ÓcCƒrçWD–d'6VçB"¢fÂÆÆ÷vVBÒ÷VävFRæ–æFW„öb‚$f÷&Vç6–4ÆövvW"æÆ–fV7–6ÆR‚"²r"r²$U„T5ôõTåôÄÄõtTB"²r"r¢76W'EG'VR‚#cC“Vç&W6öÇfVB6—¦R×W7B&WGW&â&Vf÷&RÖ–çB6Æ–ÒæBU„T5ôõTåôÄÄõtTB"À¢6—¦U&V6†V6²ãÒbbÖ–çD6Æ–Òâ6—¦U&V6†V6²bbÆÆ÷vVBâÖ–çD6Æ–Òb`¢W&Ö—Bæ6öçF–ç2‚'6—¦Tf–æÆ—G•F–6¶WE&W6VçCcC“"’bbW&Ö—Bæ6öçF–ç2‚'&U&W6öÇfVE6—¦U6öÃcC“Ò6—¦U6öÂ"’¢76W'EG'VR‚#cS“’7WW'6VFW2cS32&W67VRfæ÷WBv—F‚G'Væ²ÇW2öæRVÆ–f–VB6æöæ–6Â7V6–Æ—7B&–Ö'’"À¢&÷Bæ6öçF–ç2‚$W†V7WF–öäWF†÷&—G•öÆ–7“cS32æ—5G'Væ´ÆæR†Â’"’b`¢&÷Bæ6öçF–ç2‚&&÷VæFVE&W67VScc"’bb&÷Bæ6öçF–ç2‚'7V6–Æ—7DWfÇVF–öäÆÆ÷vVCcc"’b`¢&÷Bæ6öçF–ç2‚&6Æ–ÖVD÷væW#cc"’bb&÷Bæ6öçF–ç2‚'7G&öævW7DFW6³cS“’"’bb&÷Bæ6öçF–ç2‚$ÄäUõ$TEôôäÅ•ôäôåõ$”Ô%•ócC“"’¢76W'EG'VR‚#cC“F÷†–24„•D4ô”âõE$T5U%’6†–ær×W7BW6RÆV&æVBÆæRÖÆö6ÂVçG'’fÆö÷'2Âæ÷BvÆö&ÂW6W2"À¢&ö&&–Æ—G’æ6öçF–ç2‚&ÆV&æVDVçG'”fÆö÷$FVÇFcC“"’bb&ö&&–Æ—G’æ6öçF–ç2‚&ÆæRÓÒ"²r"r²%4„•D4ô”â"²r"r’b`¢&ö&&–Æ—G’æ6öçF–ç2‚&ÆæRÓÒ"²r"r²%E$T5U%’"²r"r’bb&÷Bæ6öçF–ç2‚$ÄäUôÄô4ÅôÄT$äTEôdÄôõ%õ$TEôôäÅ•ócC“"’¢76W'EG'VR‚#cC“66WFæ6Rf–ÇW&W2×W7BW‡÷6RW†7Bf–ÆVB–çf&–çG2æBö'6W'fVB6÷VçB"À¢66WFæ6Ræ6öçF–ç2‚&–çf&–çG3Ò"’bb66WFæ6Ræ6öçF–ç2‚&W‡V7FVCÖÆÅö–çf&–çG5÷72"’b`¢66WFæ6Ræ6öçF–ç2‚&f–ÆVD–çf&–çG3Ò"’bb&W÷'Bæ6öçF–ç2‚&W†V7WF&ÆRfâÖ÷WBö–çF¶R"’¢Ð  ¢FW7@¢gVâcUóócC“%ö6æöæ–6Åö–çfVçF÷'•öÖ&·5÷Fö¶VæÖ÷'F–ÅöæEöÖ&¶WF6÷G'WF‚‚’°¢fÂ÷6—F–öâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂÆ÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÄÆ÷EVçF—G“cCcBæ·B"’ç&VEFW‡B‚¢fÂ6—FÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Ä6—FÄWF†÷&—G“cCSæ·B"’ç&VEFW‡B‚¢fÂFö¶VäÖÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶VäÖWF†÷&—G’æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂFW‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²ôFW‡67&VVæW$’æ·B"’ç&VEFW‡B‚¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂÆ"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆ"ôÆÆÔÆ%G&FW"æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cC“"&WÆ’6''’×W7B&V'V–ÆB6æöæ–6Â÷6—F–öâæBgVæFVBÆ÷B&ö¦V7F–öç2"À¢÷6—F–öâæ6öçF–ç2‚$4äôä”4Åô4%%•õõ4•D”ôåõ$U5Dõ$TEò"²#cC“""’b`¢÷6—F–öâæ6öçF–ç2‚%õ4•D”ôåõ5DDUõ$ô¤T5DTEôe$ôÕô4äôä”4Åò"²#cC“""’b`¢Æ÷Bæ6öçF–ç2‚$4äôä”4Åô4%%•ôÄõEõ$U5Dõ$TEò"²#cC“""’¢76W'EG'VR‚#cC“"Ö—76–ærV÷FR×W7B&WF–âÆ7BÖvööBÖ&²÷"&6—2ÂæWfW"¦W&ò×fÇVR÷Vâ–çfVçF÷'’"À¢6—FÂæ6öçF–ç2‚&Æ7DvööDÖ&²"²#cC“""’bb6—FÂæ6öçF–ç2‚$4•DÅõ5DÄUôÄ5EôtôôEôÔ$µò"²#cC“""’b`¢6—FÂæ6öçF–ç2‚$4•DÅôÔ$µôdÄÄ$4µôäõô4äôåõõ4•D”ôåò"²#cC“""’¢76W'EG'VR‚#cC“"Fö¶VäÖ×W7BV&Æ—6‚6†&VBÖ–çB&W7VÇBæB&WG'’VæF–ærÖ2öâ6†÷'BEDÂ"À¢Fö¶VäÖæ6öçF–ç2‚&6æöæ–6Å&W7VÇD'”Ö–çB"²#cC“""’bbFö¶VäÖæ6öçF–ç2‚%TäD”äuõ$U5TÅEõ$UE%•ôÕ5ò"²#cC“""’b`¢Fö¶VäÖæ6öçF–ç2‚%Dô´TåôÔõ4„$TEõ$U5TÅEô„•Eò"²#cC“""’bbW†V7WF÷"æ6öçF–ç2‚%U%ô%U•ôDTdU%$TEõDô´TåôÔõ$UE%•ò"²#cC“""’¢76W'EG'VR‚#cC“"FW&Ö–æÂW"6VÆÂ×W7BW6R6æöæ–6Â&VÖ–æ–ær&rG’ÂFV6–ÖÇ2æB&6—2"À¢W†V7WF÷"æ6öçF–ç2‚'FW&Ö–æÅ&VÖ–æ–æu&r"²#cC“""’bbW†V7WF÷"æ6öçF–ç2‚'FW&Ö–æÅ&VÖ–æ–æt6÷7B"²#cC“""’b`¢W†V7WF÷"æ6öçF–ç2‚'fÂ6öÆEG•&scCsBÒFW&Ö–æÅ&VÖ–æ–æu&r"²#cC“""’¢76W'EG'VR‚#cC“"FW…67&VVæW"×W7BæWfW"Æ–2dEb–çFòÖ&¶WD6"À¢FW‚æ6öçF–ç2‚&Ö&¶WD6Òæ÷DF÷V&ÆR‚"²r"r²&Ö&¶WD6"²r"r²"Âã’"’b`¢FW‚æ6öçF–ç2‚&–b†—BÓÒã’æ÷DF÷V&ÆR"’¢76W'EG'VR‚#cC“"Ö&¶WB6&WV—&W2&÷fVææ6R&Vf÷&R$ÅTT4„•÷"ÆV&æ–ær"À¢&Vv—7G'’æ6öçF–ç2‚&†5G'W7FVDÖ&¶WD6"²#cC“""’bb&Vv—7G'’æ6öçF–ç2‚&Ö66÷W&6R"’b`¢7'—Fòæ6öçF–ç2‚$5%•DõôÔ4õTåE%U5DTEôE$õTEò"²#cC“""’bb7'—Fòæ6öçF–ç2‚&æõö&ÇVV6†—öæõöÆV&æ–ær"’¢76W'EG'VR‚#cC“"&W÷'B×W7BW‡÷6RW"öÆ—fRö7W'&VçB6æöæ–6Â–çfVçF÷'’–æFWVæFVçFÇ’"À¢&W÷'Bæ6öçF–ç2‚$6æöæ–6ÂU"7F—fRÖ–çG3¢"’bb&W÷'Bæ6öçF–ç2‚$6æöæ–6ÂÄ•dR7F—fRÖ–çG3¢"’b`¢&W÷'Bæ6öçF–ç2‚$6æöæ–6Â7F—fRÖ–çG2†7W'&VçBÖöFR“¢"’¢76W'EG'VR‚#cC“Ä"‡—÷F†W6—2÷6—F–öç2×W7B7F’÷WG6–FR6æöæ–6ÂG&FT†—7F÷'•7F÷&R"À¢Æ"æ6öçF–ç2‚$Ä%õ4äD$õ…ôõTåô•4ôÄDTEò"²#cC“"’bbÆ"æ6öçF–ç2‚$Ä%õ4äD$õ…ô4Äõ4Uô•4ôÄDTEò"²#cC“"’¢Ð  ¢FW7@¢gVâcUóócC“5÷Fö¶Våö–FVçF—G•ö—5öÖ–çEöæWfW%÷7–Ö&öÂ‚’°¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂ&W6öÇfW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòô7'—FõVæ—fW'6U&÷WFU&W6öÇfW"æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòô7'—FõVæ—fW'6TW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂG&FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂ&V6öæ6–ÆRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂvF6†Æ—7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õvF6†Æ—7DVæv–æRæ·B"’ç&VEFW‡B‚¢fÂ&–6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õ&–6Tvw&VvF÷"æ·B"’ç&VEFW‡B‚¢fÂ66÷&W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'5Væ–f–VE66÷&W$'&–FvRæ·B"’ç&VEFW‡B‚¢fÂV’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ô7'—FôÇD7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂFW‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²ôFW‡67&VVæW$’æ·B"’ç&VEFW‡B‚¢fÂ6æF–FFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòô7'—Fôf–æÄ'W”6æF–FFRæ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‚#cC“2&Vv—7G'’×W7B–æFW‚×VÇF—ÆR6æöæ–6Â”G2W"F—7Æ’7–Ö&öÂæB&V¦V7BÖ&–wV÷W2W†V7WF–öâ"À¢&Vv—7G'’æ6öçF–ç2‚'7–Ö&öÄ6æF–FFW2"²#cC“2"’bb&Vv—7G'’æ6öçF–ç2‚&vWEVæ—VTW†V7WF&ÆUFö¶Vä'•7–Ö&öÂ"²#cC“2"’b`¢&Vv—7G'’æ6öçF–ç2‚$5%•DõôU„T5ôÔ”åEôÔ$”uTõU5õ$T¤T5DTEò"²#cC“2"’¢76W'EG'VR‚#cC“26ö–ävV6¶òG&VæF–æræB§W—FW"F—66÷fW'’×W7BæWfW"¦ö–â'’7–Ö&öÂ"À¢&Vv—7G'’æ6öçF–ç2‚'fÂÖ–çBÒ"²r"r²&6s¢"²rBr²'·Fö²æ–GÒ"²r"r’b`¢&Vv—7G'’æ6öçF–ç2‚$äUdU"Ö–w&FR6ö–ävV6¶òFFöçFò§W—FW""’b`¢&Vv—7G'’æ6öçF–ç2‚'fÂ6t¶W’Ò7–Ö&öÄ–æFW…·7–Ö&öÅÒ"’¢76W'EG'VR‚#cC“2G–æÖ–2&÷WFRæBW†V7WF÷"×W7B6''’W‡Æ–6—B7–Ö&öÂÇW26æöæ–6ÂÖ–çB"À¢&W6öÇfW"æ6öçF–ç2‚&76WE7–Ö&öÂ"²#cC“2"’bb&W6öÇfW"æ6öçF–ç2‚'F&vWDÖ–çB"²#cC“2"’b`¢&W6öÇfW"æ6öçF–ç2‚&æò7–Ö&öÂfÆÆ&6²ÆÆ÷vVB"’bbW†V7WF÷"æ6öçF–ç2‚'F&vWDÖ–çB"²#cC“2"’¢76W'EG'VR‚#cC“2G&FW"6æöæ–6Â¶W’ÂÆ—fR†æFöfbæBÆV&æ–ær×W7BW6RG–äÖ–çBö6æöæ–6Â–FVçF—G’"À¢G&FW"æ6öçF–ç2‚'6–væÂæG–äÖ–çCòçG&–Ò‚’"’bbG&FW"æ6öçF–ç2‚'F&vWDÖ–çCcC“2Ò6–væÂæG–äÖ–çB"’b`¢G&FW"æ6öçF–ç2‚&6æöæ–6Ä76WD–CcC“2Ò÷6—F–öâæ6æöæ–6Ä76WD¶W’"’b`¢G&FW"æ6öçF–ç2‚&6æöæ–6Ä76WD–CcC“2Ò÷2æG–äÖ–çBó¢÷2æ6æöæ–6Ä76WD¶W’"’¢76W'EG'VR‚#cC“2vÆÆWB&V6öæ6–Æ–F–öâæB&–6R‡–G&F–öâ×W7BæWfW"&W6öÇfRâ&&—G&'’F–6¶W"÷væW""À¢&V6öæ6–ÆRæ6öçF–ç2‚&–b‡æG–äÖ–çBÒçVÆÂ’æG–äÖ–çB"’b`¢&–6Ræ6öçF–ç2‚&vWEVæ—VTW†V7WF&ÆUFö¶Vä'•7–Ö&öÃcC“2‡7–Ö&öÂ’"’¢76W'EG'VR‚#cC“2vF6†Æ—7BæBT’×W7BW'6—7BöÆöB7'—Fò'’6æöæ–6Â76WB”B"À¢vF6†Æ—7Bæ6öçF–ç2‚'fÂ76WD–C¢"’bbvF6†Æ—7Bæ6öçF–ç2‚&ÆVv7’×7–Ö&öÃ¢"’b`¢vF6†Æ—7Bæ6öçF–ç2‚&vWEFö¶Vä'”Ö–çB†—FVÒæ76WD–B’"’bbV’æ6öçF–ç2‚'÷2æG–äÖ–çCòæÆWB"’bbV’æ6öçF–ç2‚&vWEFö¶Vä'”Ö–çB†—B’"’¢76W'EG'VR‚#cC“266÷&W"VçG'’ö6Æ÷6R¶W—2×W7B66WB6æöæ–6Â76WB–FVçF—G’"À¢66÷&W"æ6öçF–ç2‚&6æöæ–6Ä76WD–CcC“2"’bb66÷&W"æ6öçF–ç2‚#ó¢Ö¶TÖ–çD¶W’†76WD6Æ72Â7–Ö&öÂ’"’¢76W'EG'VR‚#cC“2FW‚FF×W7B&WV—&RW†7B&WVW7FVB&6RÖ–çB&Vf÷&R6÷––ærFö¶VâV6öæöÖ–72"À¢FW‚æ6öçF–ç2‚&&6TFG&W72æWVÇ2‡Fö¶VäFG&W72Â–væ÷&T66RÒ6†–ä–BÒ"²r"r²'6öÆæ"²r"r²"’"’b`¢FW‚æ6öçF–ç2‚'fÂ&6TFG&W72Ò&÷ræ÷D¥4ôäö&¦V7B‚"²r"r²&&6UFö¶Vâ"²r"r²"“òæ÷E7G&–ær‚"²r"r²&FG&W72"²r"r²"Â"²r"r²r"r²"’"’¢76W'EG'VR‚#cC“2G–æÖ–2’æB6æF–FFRV6öæöÖ–72×W7B&RFG&W72¶W–VBv—F‚W‡Æ–6—B&÷fVææ6R"À¢G&FW"æ6öçF–ç2‚%6†—D6ö–åG&FW$’æ†5÷6—F–öâ‡Fö²æÖ–çB’"’b`¢G&FW"æ6öçF–ç2‚&Ö–çBÒFö²æÖ–çB"’b`¢G&FW"æ6öçF–ç2‚'fÂW†7DÖWG&–73cC“2ÒW†7D76WDÖWG&–73cC“2‡6–væÂ’"’b`¢G&FW"æ6öçF–ç2‚&Ö&¶WBÒW'4Ö&¶WBäE”â"’b`¢G&FW"æ6öçF–ç2‚'fÂVçVÔÖ·BÒW'4Ö&¶WBçfÇVW2‚’æf–æB²—Bç7–Ö&öÂÓÒFö²ç7–Ö&öÂÒ"’b`¢6æF–FFRæ6öçF–ç2‚&Ö&¶WD66÷W&6ScC“2"’¢76W'EG'VR‚#cC“2×W7Bæ÷Bf'&–6FRFö¶VâÆ—V–F—G’g&öÒ'VÆ²föÇVÖR÷"G'W7BÆVv7’vVæW&–2FW‚62"À¢G&FW"æ6öçF–ç2‚'föÂ¢ã"’bb&Vv—7G'’æ6öçF–ç2‚$DU…45$TTäU%ôÔ$´UEô4"’b`¢&Vv—7G'’æ6öçF–ç2‚$DU…45$TTäU%ô$4UôÔ”åEôÔ$´UEô4"’¢Ð  ¢FW7@¢gVâcUóócC“Eö–Ö×WF&ÆUöÆæUöVÆV7F–öå÷F–6¶WEöæE÷&UöfFuöö67Wæ7’‚’°¢fÂ6ö÷&F–æF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæTW†V7WF–öä6ö÷&F–æF÷"æ·B"’ç&VEFW‡B‚¢fÂWF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FTWF†÷&—¦W"æ·B"’ç&VEFW‡B‚¢fÂW&Ö—BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄW†V7WF–öåW&Ö—Bæ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†6ö÷&F–æF÷"æ6öçF–ç2‚'fÂVÆV7F–öä–C¢7G&–ær"’bb6ö÷&F–æF÷"æ6öçF–ç2‚'fÂWF†÷&—G•fW'6–öã¢Æöær"’¢76W'EG'VR†6ö÷&F–æF÷"æ6öçF–ç2‚&ÆæW2ÒÆ—7Döb†ÆæUWW"’"’b`¢6ö÷&F–æF÷"æ6öçF–ç2‚'&VfW'&VBÒÆæUWW""’b`¢6ö÷&F–æF÷"æ6öçF–ç2‚'fÂÆÆ÷vVBÒRç&–Ö'”ÆæRÓÒÆæUWW""’b`¢6ö÷&F–æF÷"æ6öçF–ç2‚&Ræ6÷’‡6VÆVBÒG'VR’"’¢76W'EG'VR†WF‚æ6öçF–ç2‚&VÆV7FVDÆæScC“BÒÆæTVÆV7F–öâç&–Ö'”ÆæR"’bbWF‚æ6öçF–ç2‚&VÆV7F–öä–CcC“BÒÆæTVÆV7F–öâæVÆV7F–öä–B"’¢76W'DfÇ6R‚'W&Ö—B×W7Bæ÷B–æFWVæFVçFÇ’&RÖVÆV7BgFW"WF†÷&—¦F–öâ"ÂW&Ö—Bæ6öçF–ç2‚$ÆæTW†V7WF–öä6ö÷&F–æF÷"æ6å&WVW7DW†V7WF–öâ†Ö–çBÂÆ–W"’"’¢76W'EG'VR‡W&Ö—Bæ6öçF–ç2‚$”ÔÕUD$ÄUôU„T5õD”4´UEôÔ•54”äuócC“B"’bbW&Ö—Bæ6öçF–ç2‚$”ÔÕUD$ÄUôTÄT5D”ôåôÄäUôÔ•4ÔD4…ócC“B"’¢76W'EG'VR†vFRæ6öçF–ç2‚&—5&VÄW†V7WF–öäÆæR‡&V6V—DÆæScC“B’Óâ&V6V—DÆæScC“B"’b`¢vFRæ6öçF–ç2‚&6æöæ–6ÄÆæRÒ6æöæ–6ÄÆæScS’"’bbvFRæ6öçF–ç2‚&fFt–çFVçCcS’æ6÷’‚"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%$UôdDuô4äôåôÔ”åEôô45U”TEõ5U$U54TEócC“B"’bb&÷Bæ6öçF–ç2‚&W†V7WF–öä&öö´f÷$ÆæScC“B†7–6ÆU&–Ö'”ÆæR’"’¢76W'DfÇ6R‚&ÆæR†æFöfb×W7Bæ÷B&WÆ6RWF‚&V6V—Bv—F‚×WF&ÆR&V6VçBÆöö·W"Â&÷Bæ6öçF–ç2‚'fÂG&V7W'”GFV×D–BÒW†V7WF&ÆT÷VävFRç&V6VçDÆÆ÷vVDGFV×D–B"’¢Ð  ¢FW7@¢gVâcUóócC“U÷V&çF–æW5ö–×÷76–&ÆUöf–æÆ—¦VEöV6öæöÖ–75öæE÷&÷f–FW%ö'—76W2‚’°¢fÂf–æÄ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSæ·B"’ç&VEFW‡B‚¢fÂF7F–2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærõF7F–57v—F6†W"æ·B"’ç&VEFW‡B‚¢fÂW‡V7Fæ7’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ66÷&TW‡V7Fæ7•G&6¶W"æ·B"’ç&VEFW‡B‚¢fÂFW‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²ôFW‡67&VVæW$’æ·B"’ç&VEFW‡B‚¢fÂ6—&7V—BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²ô†÷7D6—&7V—D–çFW&6WF÷"æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†f–æÄ'W2æ6öçF–ç2‚$4äôä”4Åôd”äÄ•¤TEôT4ôäôÔ”55õT$åD”äTEócC“R"’¢76W'EG'VR†f–æÄ'W2æ6öçF–ç2‚$”ÕÄ”TEõ$ô4TTE5ô$õdUóSõ4ôÂ"’bbf–æÄ'W2æ6öçF–ç2‚%$UEU$åôe$5D”ôåõ5EôÔ•4ÔD4‚"’¢76W'EG'VR‡F7F–2æ6öçF–ç2‚%F7F–57v—F6†W"æöä6æöæ–6ÅG&FT6Æ÷6VCcCƒb"’bbF7F–2æ6öçF–ç2‚'W'6—7FVEöV6öæöÖ–75÷V&çF–æVEócC“R"’¢76W'EG'VR‡F7F–2æ6öçF–ç2‚%G&FT†—7F÷'•7F÷&Ræ—5fÆ–D66÷VçF–æuG&FR†—B’"’¢76W'EG'VR†W‡V7Fæ7’æ6öçF–ç2‚%44õ$UôU…T5Dä5•õU%4•5DTEôT4ôäôÔ”55õT$åD”äTEócC“R"’¢76W'EG'VR†FW‚æ6öçF–ç2‚&æWfW"'—72†VÇF„v&T‡GGô”&6¶öfbv—F‚&r&WG'’"’¢76W'DfÇ6R‚$FW…67&VVæW"×W7Bæ÷B&WG'’&rgFW"F†R†VÇF‚w&W""ÂFW‚æ6öçF–ç2‚&‡GGææWt6ÆÂ‡&W’æW†V7WFR‚’"’¢76W'EG'VR†6—&7V—Bæ6öçF–ç2‚$”&6¶öfb6†&VBÖ6Æ–VçBÆö6¶÷WB"’bb6—&7V—Bæ6öçF–ç2‚'&W7öç6Ræ6öFRÓÒC2"’¢Ð  ¢FW7@¢gVâcUóócC“…÷W%÷FW&Ö–æÅ÷7FFU÷G•÷&—G•öæE÷6—¦–æuö&Uö6æöæ–6Â‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚¢fÂ&÷VæF'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ6VÆÅG”&÷VæF'”6Æ×cC#ræ·B"’ç&VEFW‡B‚¢fÂ&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ÷6—F–öå&Vv—7G'•&—G”VF—CcCcBæ·B"’ç&VEFW‡B‚¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôVÖW&vVçDwV&G&–Ç2æ·B"’ç&VEFW‡B‚¢fÂ6—¦–ærÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô÷&FW%6—¦U&W6öÇfW#cCCæ·B"’ç&VEFW‡B‚¢fÂw&÷Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôw&÷&÷WFT6öæf–scC“‚æ·B"’ç&VEFW‡B‚¢fÂW%G‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%G&ç67F–öãcCƒbæ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚%÷6—F–öå7FFTÆVFvW#cCSBæöäVçG'’‡–CcCƒR’"’bbW†V7WF÷"æ6öçF–ç2‚'7–æ4WF†÷&—FF—fU&r‡–CcCƒR"’¢76W'EG'VR†W†V7WF÷"æ–æFW„öb‚&6æöæ–6ÅW%6VÆÄ6öÖÖ—GFVCcCsBÒ6Æ÷6ScCsBæÆ–VB"’–âVçF–ÂW†V7WF÷"æÆ7D–æFW„öb‚%W%FW&Ö–æÅ&ö¦V7F–öä6öçfW&vVæ6ScS’æ6öçfW&vR"’¢76W'EG'VR‡W%G‚æ6öçF–ç2‚%÷6—F–öå7FFTÆVFvW#cCSBæöäVçG'’‡÷6—F–öä–B’"’bbW%G‚æ6öçF–ç2‚%6VÆÅG”&÷VæF'”6Æ×cC#rç7–æ4WF†÷&—FF—fU&r‡÷6—F–öä–B"’¢76W'EG'VR†'&–FvRæ6öçF–ç2‚&FÖ—E&r‡÷6—F–öä–BÂ6öÆEG•&r"’bb'&–FvRæ6öçF–ç2‚&6öÖÖ—E&r‡÷6—F–öä–BÂ6öÆEG•&rÂFW&Ö–æÂ’"’¢76W'EG'VR†&÷VæF'’æ6öçF–ç2‚%4TÄÅõE•ô$õTäD%•ôDÔ•EDTEò"²#cC“‚"’bb&÷VæF'’æ6öçF–ç2‚%4TÄÅõE•ô$õTäD%•õ$T¤T5DTEò"²#cC“‚"’¢76W'EG'VR‡&—G’æ6öçF–ç2‚&6æöæ–6Å7FFT'”Ö–çB"²#cC“‚"’bb&—G’æ6öçF–ç2‚&3ÒB"²&W‡V7FVE7FFScC“‚"’¢76W'EG'VR‡&Vv—7G'’æ6öçF–ç2‚'7FFRÒç7FFR"’bb&Vv—7G'’æ6öçF–ç2‚%%D”ÄÅ•ô4Äõ4TB"’¢76W'EG'VR€¢%cRããcC“‚³cc#¢ÆFFW&VB×W7B&VÖ–âF†RÔ‚öb&—6²ÖFW&—fVBæBÆFFW%F&vWB(	B66WBV—F†W"&—6¶÷"çVFvVE&—6¶†&÷VæFVB6öçG&–'WF÷"ÖW&vR&W6W'fW2F†RFö7G&–æR’"À¢‡6—¦–æræ6öçF–ç2‚&¶÷FÆ–âæÖF‚æÖ‚‡&—6²ÂÆFFW%F&vWB’"’ÇÂ6—¦–æræ6öçF–ç2‚&¶÷FÆ–âæÖF‚æÖ‚†çVFvVE&—6²ÂÆFFW%F&vWB’"’’b`¢6—¦–æræ6öçF–ç2‚&¶÷FÆ–âæÖF‚æÖ–â‡&—6²ÂÆFFW$fÆö÷"’"¢¢76W'EG'VR‡6—¦–æræ6öçF–ç2‚&WF†÷&—G”6Æ×÷'G2"²#cC“‚"’¢76W'EG'VR†w&÷æ6öçF–ç2‚&÷Væ’öwBÖ÷72Ó#""’¢Ð  ¢FW7@¢gVâcUóócS•÷6÷W&6U÷&ö÷FVE÷VçF—G•ö6Æ÷6UöæEöW†V5ö–çFVçEöWF†÷&—F–W2‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂG’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õW%Fö¶VåVçF—G”WF†÷&—G“cS’æ·B"’ç&VEFW‡B‚¢fÂÖ—'&÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF÷$6æöæ–6ÄÖ—'&÷#cCC"æ·B"’ç&VEFW‡B‚¢fÂ6Æ÷6RÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõW%FW&Ö–æÅ&ö¦V7F–öä6öçfW&vVæ6ScS’æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚ ¢76W'DfÇ6R‚#cS’&V6÷&EG&FR×W7BæWfW"FW&—fR6öÆB×Fö¶Vâg&7F–öâg&öÒV6öæöÖ–2&WGW&â"ÂW†V7WF÷"æ6öçF–ç2‚&VçG'•G”f÷$¦÷W&æÂ¢‡G&FRç6öÂòVçG'”6÷7Df÷$¦÷W&æÂ’"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚&6æöæ–6Ä6öç7VÖVE&rÒ&ufW&F–7CcS#ææ÷&ÖÆ—¦VE&r"’b`¢W†V7WF÷"æ6öçF–ç2‚&¦÷W&æÅ6öÆE&r‡G&FRç6öÆEG•Fö¶Vâ"’¢76W'EG'VR‡G’æ6öçF–ç2‚&W‡V7FVBÒ†6÷7E6öÂ¢6öÅW6B’òFö¶Vå&–6UW6B"’bbG’æ6öçF–ç2‚&FV6öFVBÒFV6öFR‡&rÂFV6–ÖÇ2’"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚%U%ô%U•ôDTdU%$TEõ4ôÅõU4EôÔ•54”äuócS’"’bbW†V7WF÷"æ6öçF–ç2‚&VffV7F—fU6öÂòÖ„öb†VffV7F—fU&–6RÂRÓ"’"’¢76W'DfÇ6R‚#cSC¢Væ¶æ÷vâU"FV6–ÖÇ2&RGf—6÷'’ÂæWfW"&Æö6¶–ær&V6öâ"ÂW†V7WF÷"æ6öçF–ç2‚%U%ô%U•ôDTdU%$TEôDT4”ÔÅ5ôÔ•54”äuò"²#cS’"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚'W%Fö¶VäFV6–ÖÇ3cS’"’bbW†V7WF÷"æ6öçF–ç2‚'Fö¶VäFV6–ÖÇ2ÒW%Fö¶VäFV6–ÖÇ3cS’"’¢76W'EG'VR†Ö—'&÷"æ6öçF–ç2‚'Fö¶VäFV6–ÖÇ2ÒFö¶VäFV6–ÖÇ2"’bbÖ—'&÷"æ6öçF–ç2‚'Fö¶VäFV6–ÖÇ2Ò’Âòò&÷f—6–öæÂ"’¢76W'EG'VR†6Æ÷6Ræ6öçF–ç2‚%õ5Eô4Äõ4UôÄTDtU%õ5DÕôd”ÅócS’"’bb6Æ÷6Ræ6öçF–ç2‚%õ5Eô4Äõ4UõU%ôUD…ôd”ÅócS’"’b`¢6Æ÷6Ræ6öçF–ç2‚%õ5Eô4Äõ4UôuT$E$”Åõ$TÔõdUôd”ÅócS’"’bb6Æ÷6Ræ6öçF–ç2‚%õ5Eô4Äõ4UôtÄô$Åõ$Tt•5E%•ôd”ÅócS’"’b`¢6Æ÷6Ræ6öçF–ç2‚%õ5Eô4Äõ4Uõõ%DdôÄ”õõ$TÔõdUôd”ÅócS’"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚&6æöæ–6Ä6Æ÷6VDæô7F—fR"’bbW†V7WF÷"æ6öçF–ç2‚'&WGW&â6VÆÅ&W7VÇBäÅ$TE•ô4Äõ4TB"’¢76W'EG'VR†vFRæ6öçF–ç2‚&6æöæ–6ÄW†V7WF&ÆT–çFVçCcS’"’bbvFRæ6öçF–ç2‚$U„T5õ$uõ4”täÅôD”täõ5D”5ô”täõ$TEócS’"’¢fÂ6æöæ–6Ä–çFVçBÒvFRç7V'7G&–ær†vFRæ–æFW„öb‚&–çFW&æÂgVâ6æöæ–6ÄW†V7WF&ÆT–çFVçCcS’"’ÂvFRæ–æFW„öb‚'&—fFRfÂ7FFW2"’¢76W'DfÇ6R‚&W†V7WF–öâF–6¶WB—2÷WGWBÂæWfW"–çWBFò&RÖW†V7WF–öâdDrWF†÷&—G’"Â6æöæ–6Ä–çFVçBæ6öçF–ç2‚&†4–Ö×WF&ÆUF–6¶WB"’¢76W'EG'VR‚'&ræöâÔ%U’&VÖ–ç2F–væ÷7F–2gFW"6æöæ–6ÂdDrWF†÷&—¦F–öâ"ÂvFRæ6öçF–ç2‚$U„T5õ$uõ4”täÅôD”täõ5D”5ô”täõ$TEócS’"’¢Ð  ¢FW7@¢gVâcUóócSöÆæUöFV6—6–öåöÖ&µ÷'F–ÅöæEö–æ6–FVçEöWF†÷&—F–W5ö&U÷6÷W&6U÷&ö÷FVB‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ–FVçF—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT–FVçF—G’æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂFV6—6–öâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF–öäFV6—6–öå6æ6†÷CcSæ·B"’ç&VEFW‡B‚¢fÂÖ&²Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÖ&´WF†÷&—G”–çFVw&—G”vFScC“bæ·B"’ç&VEFW‡B‚¢fÂ'F–ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%'F–Ä÷W&F–öãcSæ·B"’ç&VEFW‡B‚¢fÂ–æ6–FVçBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ&ö÷D6W6T–æ6–FVçDÆ–fV7–6ÆScSæ·B"’ç&VEFW‡B‚¢fÂg&W6†æW72Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ&ö÷D6W6Tg&W6†æW74WF†÷&—G“cC“bæ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†–FVçF—G’æ6öçF–ç2‚'f"W†V7WF–öäÆæS¢7G&–ær"’bb–FVçF—G’æ6öçF–ç2‚'f"fFt6æF–FFUfW'6–öã¢Æöær"’¢76W'DfÇ6R‚&F—66÷fW'’&÷fVææ6R×W7BæWfW"&W6öÇfRW†V7WF–öâÆæR"ÂW†V2æ6öçF–ç2‚&æ÷&ÖÆ—¦TW†V7WF–öäÆæR†–FVçF—G“òç6÷W&6R’"’ÇÂW†V2æ6öçF–ç2‚&æ÷&ÖÆ—¦TW†V7WF–öäÆæR‡G2ç6÷W&6R’"’¢76W'EG'VR†W†V2æ6öçF–ç2‚$U„T5ôÄäUô”DTåD•E•ô”åd$”åEôd”ÄTB"’bbW†V2æ6öçF–ç2‚$dDuôÕUD$ÄUõ4”täÅô”täõ$TEócS""’¢76W'EG'VR†vFRæ6öçF–ç2‚$W†V7WF–öäFV6—6–öå6æ6†÷CcSç&V6÷&B"’bbFV6—6–öâæ6öçF–ç2‚&'”WF†÷&—G”¶W’"’bbFV6—6–öâæ6öçF–ç2‚''VçF–ÖTvVæW&F–öâ"’bbFV6—6–öâæ6öçF–ç2‚&ÖöFR"’¢76W'EG'VR†Ö&²æ6öçF–ç2‚'fÂ&–6TWF†÷&—FF—fR"’bbÖ&²æ6öçF–ç2‚'fÂ&÷WFTW†V7WF&ÆR"’¢76W'EG'VR‡'F–Âæ6öçF–ç2‚""'fÂ÷W&F–öä–BÒ""""’bb'F–Âæ6öçF–ç2‚'÷6—F–öä–B"’bb'F–Âæ6öçF–ç2‚'6WVVæ6R"’bb'F–Âæ6öçF–ç2‚$6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æf–æÆ—¦U6VÆÂ"’¢76W'DfÇ6R‚'W"'F–Â÷W&F–öâ”G2×W7Bæ÷B6öçF–âvÆÆ6Æö6²vVæW&F–öç2"Â'F–Âæ6öçF–ç2‚%7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚—Õò"’¢76W'EG'VR†–æ6–FVçBæ6öçF–ç2‚&VçVÒ6Æ727FFR²õTâÂ$U4ôÅdTBÒ"’bbg&W6†æW72æ6öçF–ç2‚&VÆ6VBF–ÖRæWfW"&V7F—fFW2Æ–fWF–ÖR†—7F÷'’"’¢Ð  ¢FW7@¢gVâcUóócS÷W%÷&U÷F–6¶WEöfÆö÷%ö—5ö–æFWVæFVçEöæE÷&W6W'fW5÷fÆ–E÷6†VEö'W—2‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ6—¦W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6Ö'E6—¦W"æ·B"’ç&VEFW‡B‚¢fÂÖ–æ–×VÕ7F'BÒW†V2æ–æFW„öb‚'&—fFRgVâÖ–ä6öæf–wW&VEW%G&FU6öÂ"¢fÂÖ–æ–×VÔVæBÒW†V2æ–æFW„öb‚'&—fFRgVâ6Æ×W%G&FU6öÂ"ÂÖ–æ–×VÕ7F'B¢fÂÖ–æ–×VÔ&Æö6²ÒW†V2ç7V'7G&–ær†Ö–æ–×VÕ7F'BÂÖ–æ–×VÔVæB¢76W'DfÇ6R‚&÷&F–æ'’&WVW7FVB6ÖÆÄ'W•6öÂ×W7Bæ÷B&RW†V7WF&ÆRÖfÆö÷"WF†÷&—G’"ÂÖ–æ–×VÔ&Æö6²æ6öçF–ç2‚&2ç6ÖÆÄ'W•6öÂ"’¢76W'DfÇ6R‚'vÆÆWB×&VÆF—fR&WVW7FVB6—¦–ær×W7Bæ÷B&V6öÖRâW†V7WF&ÆRfÆö÷""ÂÖ–æ–×VÔ&Æö6²æ6öçF–ç2‚'W%6–×VÆFVD&Ææ6R¢ã"’¢76W'EG'VR†Ö–æ–×VÔ&Æö6²æ6öçF–ç2‚%W%&UF–6¶WE6—¦TfÆö÷#cSæ&÷VæFVDÖ–æ–×VÒ‡'VçF–ÖTÖ–æ–×VÓcS’"’¢76W'EG'VR†W†V2æ6öçF–ç2‚$%4ôÅUDUôU„T5UD$ÄUôdÄôõ%õ4ôÂÒãR"’bb6—¦W"æ6öçF–ç2‚$T4ôäôÔ”5ôÔ”åõ4•¤Uõ$ôÔõDTEócSSR"’bb6—¦W"æ6öçF–ç2‚'fÂGW7DfÆö÷"ÒãR"’¢fÂ&öÖ÷FRÒW†V2æ–æFW„öb‚'fÂVffV7F—fU&WVW7FVE6öÃcS"¢fÂ'&–FvRÒW†V2æ–æFW„öb‚%G&FW%6—¦–æt'&–FvScCCBç&W6öÇfTf÷$ÆæR"Â&öÖ÷FR¢fÂ&V¦V7BÒW†V2æ–æFW„öb‚%U%ô%U•õ$T¤T5DTEô$Tdõ$UõD”4´UEõ4•¤UócC“"Â'&–FvR¢fÂF–6¶WBÒW†V2æ–æFW„öb‚$W†V7WF&ÆT÷VävFRæ6ä÷VäW†V7WF&ÆU÷6—F–öâ"Â&V¦V7B¢fÂ6öÖÖ—BÒW†V2æ–æFW„öb‚%cRããcCƒR(	BDôÔ”2U"%U’4ôÔÔ•B"ÂF–6¶WB¢76W'EG'VR‡&öÖ÷FRãÒbb&öÖ÷FRÂ'&–FvRbb'&–FvRÂ&V¦V7Bbb&V¦V7BÂF–6¶WBbbF–6¶WBÂ6öÖÖ—B¢76W'EG'VR†W†V2æ6öçF–ç2‚'fÂfÆö÷%&öÖ÷F–öå&WVW7FVCcSÒfÇ6R"’bbW†V2æ6öçF–ç2‚'6VÆVDæ÷F–öæÃcSS""’bbW†V2æ6öçF–ç2‚%U%ô%U•õ$T¤T5DTEô$Tdõ$UõD”4´UEõ4•¤UócC“"’¢76W'DfÇ6R‚%cRããcScs¢&VGV6VBFF—fR&WVW7G2×W7BæWfW"&R–æfÆFVB'’F÷vç7G&VÒfÆö÷""ÂW†V2æ6öçF–ç2‚%U%ô%U•õ4•¤UôdÄôõ%õ$ôÔõDTEócS"’¢Ð  ¢FW7@¢gVâcUóócSSEö6æöæ–6ÅöVçG'•÷W6W5ö÷VåövFUöWF†÷&—G•öæEö÷VåöF—&V7F–öâ‚’°¢fÂ6öçG&7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Ä76WDVçG'”6öçG&7CcSSæ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR†6öçG&7Bæ6öçF–ç2‚'&Vv—7FW$6æöæ–6Ä–çFVçCcSSB"’bb6öçG&7Bæ6öçF–ç2‚$U„T5ô”åDTåEõ$Tt•5E$D”ôåôd”ÄTB"’¢76W'EG'VR†6öçG&7Bæ6öçF–ç2‚""'6–FRÒ$%U’""""’bb6öçG&7Bæ6öçF–ç2‚""&7F–öâÒ$õTâ""""’bb6öçG&7Bæ6öçF–ç2‚&F—&V7F–öâÒ–b"’¢76W'EG'VR†vFRæ6öçF–ç2‚&gVâ&Vv—7FW$6æöæ–6Ä–çFVçCcSSB"’bbvFRæ6öçF–ç2‚&7F—fTW†V7WF–öä–çFVçG3cS’"’bbvFRæ6öçF–ç2‚&W†V7WF–öåF–6¶WG2"’¢76W'EG'VR†vFRæ6öçF–ç2‚'&—fFRgVâV&Æ—6„fFt–çFVçCcS’"’bbvFRæ6öçF–ç2‚'&Vv—7FW$6æöæ–6Ä–çFVçCcSSB‡6—¦VD–çFVçB’"’¢76W'EG'VR†6öçG&7Bæ6öçF–ç2‚$4äôä”4ÅõTäD”äuôU…•$TB"’bb6öçG&7Bæ6öçF–ç2‚&Ö&´f–ÆVB"’bb6öçG&7Bæ6öçF–ç2‚&Ö&´FVfW'&VB"’bb6öçG&7Bæ6öçF–ç2‚&Ö&´6æ6VÆÆVB"’¢Ð ¢FW7@¢gVâcUóócSc5ö7–6Æ–5ö—5öæ÷E÷Væ6öæF—F–öæÆÇ•öF—6&ÆVEögFW%÷'VçF–ÖU÷Æâ‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR†&÷Bæ6öçF–ç2‚$5”4Ä”5õ%TåD”ÔUôTä$ÄTEócSc2"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚'fÂ7–6Æ–4Væ&ÆVCcSc2ÒÆãcS#bçW$ÖöFRÇÂÖ&¶WG57F'D6fræ7–6Æ–5G&FTVæ&ÆVB"’¢76W'EG'VR‚&÷Bæ6öçF–ç2‚$7–6Æ–5G&FTVæv–æRç6WDVæ&ÆVB†fÇ6R’"’¢Ð ¢FW7@¢gVâcUóócSc%ö7'—Fõ÷W%öÆV&æ–æu÷&V6†W5öfFu÷v—F†÷WE÷&VÆ†–æuöÆ—fUöÖöÖVçGVÒ‚’°¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$5%•DõõU%ôÄT$ä”äuôDÔ•54”ôåócSc""’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚'fÂW$ÆV&æ–æt6æF–FFScSc"Ò—5W$ÖöFRævWB‚’bb66÷&RãÒSbb6öæf–FVæ6RãÒC"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚"W$ÆV&æ–æt6æF–FFScSc""’¢76W'EG'VR†7'—Fòæ–æFW„öb‚'W$ÆV&æ–æt6æF–FFScSc""’Â7'—Fòæ–æFW„öb‚'&WGW&âÇE6–væÂ‚"’¢Ð ¢FW7@¢gVâcUóócScöÖ&¶WG5öW†—E÷W6W5ö6æöæ–6Å÷W%ö6Æ÷6UöæEöÆ—fUöf–æÆ—G’‚’°¢fÂ7Fö6·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VE7Fö6µG&FW"æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$6æöæ–6ÅW%G&ç67F–öãcCƒbæ6Æ÷6R"’bb7Fö6·2æ6öçF–ç2‚&6Æ÷6TÆ—fU÷6—F–öå&ööccCƒb"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$6æöæ–6ÅW%G&ç67F–öãcCƒbæ6Æ÷6R"’bb7'—Fòæ6öçF–ç2‚&6Æ÷6TÆ—fU÷6—F–öå&ööccCƒb"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚&–b‚6æöæ–6Ä6Æ÷6ScCƒbæÆ–VB’"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚&–b‚6æöæ–6Ä6Æ÷6ScCƒbæÆ–VB’"’¢Ð ¢FW7@¢gVâcUóócScöÖ&¶WG5÷7V6–Æ—7E÷&V6†W5ö6æöæ–6ÅöfFuö&Vf÷&Uö÷Vâ‚’°¢fÂ7Fö6·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VE7Fö6µG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$6æöæ–6Ä76WDVçG'”6æF–FFScSS"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚&Ö&¶WD–çFVçCcSc"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSæÖ&´6öæf—&ÖVB"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSæÖ&´f–ÆVB"’¢76W'EG'VR‡7Fö6·2æ–æFW„öb‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"’Â7Fö6·2æ–æFW„öb‚'fÂ÷6—F–öâÒ7Fö6µ÷6—F–öâ"’¢Ð ¢FW7@¢gVâcUóócSc÷W%÷‡7Fö6·5ö'—75÷G&F—F–öæÅö†÷W'5ö'WEöÆ—fUöFöW5öæ÷B‚’°¢fÂ7Fö6·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VE7Fö6µG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$Ô$´UE5õU%ó#EƒuôU„T5UD”ôåócSc"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚&–b‚—5W$ÖöFRævWB‚’bb—57Fö6´Ö&¶WD÷Vâ‚’’"’¢76W'EG'VR‡7Fö6·2æ–æFW„öb‚&–b‚—5W$ÖöFRævWB‚’bb—57Fö6´Ö&¶WD÷Vâ‚’’"’Â7Fö6·2æ–æFW„öb‚$Ô$´UE5õU%ó#EƒuôU„T5UD”ôåócSc"’¢Ð ¢FW7@¢gVâcUóócSS•÷W%÷'VçF–ÖU÷&V6VFW5÷7FÆUöÆ—fUöWF†÷&—G•öf÷%öÖ&¶WG5öæEö7'—Fò‚’°¢fÂÖ&¶WG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VE7Fö6µG&FW"æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR†Ö&¶WG2æ6öçF–ç2‚$Ô$´UE5õU%õ%TåD”ÔUõ$T4TDTä4UócSS’"’bbÖ&¶WG2æ6öçF–ç2‚'fÂW%'VçF–ÖScSS’"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$5%•DõõU%õ%TåD”ÔUõ$T4TDTä4UócSS’"’bb7'—Fòæ6öçF–ç2‚'fÂW%'VçF–ÖScSS’"’¢76W'EG'VR†Ö&¶WG2æ–æFW„öb‚'fÂW%'VçF–ÖScSS’"’ÂÖ&¶WG2æ–æFW„öb‚&VffV7F—fU6æ6†÷B‚’"’¢76W'EG'VR†7'—Fòæ–æFW„öb‚'fÂW%'VçF–ÖScSS’"’Â7'—Fòæ–æFW„öb‚&VffV7F—fU6æ6†÷B‚’"’¢Ð ¢FW7@¢gVâcUóócSS…ö7&÷75ö76WE÷6—¦U÷6VÅöæE÷W'5÷6æF&÷…ö&U÷6÷W&6U÷&ö÷FVB‚’°¢fÂ6—¦–ærÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å6—¦–æt'&–FvScS3"æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂW'4Væv–æRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'4W†V7WF–öäVæv–æRæ·B"’ç&VEFW‡B‚¢fÂW'5G&FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'5G&FW$’æ·B"’ç&VEFW‡B‚¢fÂ6æF&÷‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õW'56æF&÷ƒcCc2æ·B"’ç&VEFW‡B‚¢76W'EG'VR‡6—¦–æræ6öçF–ç2‚'6—¦–ær—2Gf—6÷'’–çWBÂæWfW"&RÔdDr"’¢76W'EG'VR†vFRæ6öçF–ç2‚'&W6öÇfVE6—¦U6öÃcSS‚"’bbvFRæ6öçF–ç2‚$5$õ55ô54UEôÄTt5•õ4”täÅôD•dU$tTä4UócSSB"’bbvFRæ6öçF–ç2‚&7F–öãÖF–væ÷7F–5ööæÇ’"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"’bb7'—Fòæ6öçF–ç2‚&6æöæ–6Ä7'—Fô–çFVçCcScRç&W6öÇfVE6—¦R"’¢76W'EG'VR‡W'4Væv–æRæ6öçF–ç2‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"’bbW'4Væv–æRæ6öçF–ç2‚'6VÆVEW'–çFVçCcSs"’bbW'4Væv–æRæ6öçF–ç2‚&6æöæ–6ÅW'56—¦ScSsÒ6VÆVEW'–çFVçCcSsç&W6öÇfVE6—¦U6öÂ"’¢76W'EG'VR‡W'5G&FW"æ6öçF–ç2‚%W'56æF&÷ƒcCc2æ÷VäÆWfW&vVEW""’bbW'5G&FW"æ6öçF–ç2‚$6æöæ–6ÅW%G&ç67F–öãcCƒbç&VgVæB"’¢76W'EG'VR‡6æF&÷‚æ6öçF–ç2‚%U%5ôU„T5ôD•5D4…ócSSB"’bb6æF&÷‚æ6öçF–ç2‚%U%5ôõTåô4ôäd•$ÔTEócSSB"’bb6æF&÷‚æ6öçF–ç2‚%U%5ôõTåõ$TeU4TEócSSB"’¢Ð ¢FW7@¢gVâcUóócSS5÷W%öÖöFU÷&V6†W5öÖ&¶WG5öæEö7'—Fõ÷66ææW'2‚’°¢fÂÖ&¶WG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VE7Fö6µG&FW"æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR†Ö&¶WG2æ6öçF–ç2‚"6frçW$ÖöFR"’bbÖ&¶WG2æ6öçF–ç2‚$Ô$´UE5õU%ôÄT$åôUdU%•D„”äuôDÔ•EDTEócSS2"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚"6frçW$ÖöFR"’bb7'—Fòæ6öçF–ç2‚$5%•DõõU%ôÄT$åôUdU%•D„”äuôDÔ•EDTEócSS2"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚&F—66÷fW'”vTÖ–çWFW3cSSB"’bb7'—Fòæ6öçF–ç2‚$5%•DõôE”åôU…$U55ôU„T5UD$ÄUócSSB"’bb7'—Fòæ6öçF–ç2‚$5%•DõôE”åôÔä•ôU„T5UD$ÄUócSSB"’¢76W'DfÇ6R†7'—Fòç7V'7G&–ær†7'—Fòæ–æFW„öb‚'&—fFR7W7VæBgVâ'VäG–æÖ–5Fö¶Vå66â"’Â7'—Fòæ–æFW„öb‚'&—fFR7W7VæBgVâ'Vå66ä7–6ÆR"’’æ6öçF–ç2‚'Fö¶VävTÖ–çWFW2Ò“““’ã"’¢Ð ¢FW7@¢gVâcUóócS%öW†V7WF–öåöWF†÷&—G•öæE÷&÷f–FW%÷&÷FF–öåö&U÷6÷W&6U÷&ö÷FVB‚’°¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂFV6—6–öâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF–öäFV6—6–öå6æ6†÷CcSæ·B"’ç&VEFW‡B‚¢fÂ6æ6†÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF–öå6æ6†÷DWF†÷&—G“cC“bæ·B"’ç&VEFW‡B‚¢fÂvw&VvF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õ&–6Tvw&VvF÷"æ·B"’ç&VEFW‡B‚¢fÂFW‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²ôFW‡67&VVæW$’æ·B"’ç&VEFW‡B‚¢fÂ&÷f–FW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&÷f–FW$WF†÷&—G’æ·B"’ç&VEFW‡B‚¢fÂf'&–2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôFTFV6—6–öäVçfVÆ÷ScS"æ·B"’ç&VEFW‡B‚¢fÂf–æÄ'W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Äf–æÆ—¦VEG&FT'W3cCcBæ·B"’ç&VEFW‡B‚¢fÂ6öç7VÖW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôf–æÆ—¦VD'W46öç7VÖW$'&–FvScCcRæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚ ¢fÂ–çFVçBÒvFRç7V'7G&–ær†vFRæ–æFW„öb‚&–çFW&æÂgVâ6æöæ–6ÄW†V7WF&ÆT–çFVçCcS’"’ÂvFRæ–æFW„öb‚'&—fFRfÂ7FFW2"’¢76W'DfÇ6R†–çFVçBæ6öçF–ç2‚&†4–Ö×WF&ÆUF–6¶WB"’¢76W'EG'VR†vFRæ6öçF–ç2‚&fFtVÆV7F–öäÆö6·3cS""’bbvFRæ6öçF–ç2‚'&æ²†öÆCòç&TfFufW&F–7B’ãÒ&æ²†f–æÅfW&F–7B’"’¢76W'EG'VR†vFRæ6öçF–ç2‚'6–væÂÒ–b†¶VWöÆB’öÆCòç6–væÂ"’bbvFRæ6öçF–ç2‚'6VÆV7FVDÆæRÒ–b†¶VWöÆB’öÆCòç6VÆV7FVDÆæR"’¢76W'EG'VR†FV6—6–öâæ6öçF–ç2‚&'”WF†÷&—G”¶W’"’bbFV6—6–öâæ6öçF–ç2‚''VçF–ÖTvVæW&F–öâ"’bbFV6—6–öâæ6öçF–ç2‚&6æF–FFUfW'6–öâ"’bbFV6—6–öâæ6öçF–ç2‚&W†V7WF–öäÆæR"’¢76W'DfÇ6R‡6æ6†÷Bæ6öçF–ç2‚&FB‚"²%Â'&–Ö'”ÆæR‚"’¢76W'EG'VR†vFRæ6öçF–ç2‚&6æöæ–6Äö67Wæ7’Ò"’bbvFRæ6öçF–ç2‚&ÖöFRçWW&66R‚—Ó¢"²"B"²&Ö–çB"’bbvFRæ6öçF–ç2‚%U""’bbvFRæ6öçF–ç2‚$Ä•dR"’¢76W'EG'VR†W†V2æ6öçF–ç2‚$dDuôÕUD$ÄUõ4”täÅô”täõ$TEócS""’bbW†V2æ6öçF–ç2‚$U„T5ôUD„õ$•E•ôÔ•54”äuôDTdU%$TEócS""’bbW†V2æ6öçF–ç2‚'&VÆV6T–e&–Ö'’"’¢76W'DfÇ6R†W†V2æ6öçF–ç2‚$TåE%•ô%$”DtUôäôåô%U•ôuT$EócSB"’¢76W'EG'VR†vw&VvF÷"æ6öçF–ç2‚$FF6÷W&6RäDU…$”´"’bbvw&VvF÷"æ6öçF–ç2‚&FFÖ’æ&–ææ6Rçf—6–öâ"’¢76W'EG'VR†FW‚æ6öçF–ç2‚&fWF6„FW…&–¶Fö¶VãcS""’bb&÷f–FW"æ6öçF–ç2‚$DU…$”´"’bb&÷f–FW"æ6öçF–ç2‚%&÷f–FW$6öæf–r"’¢76W'EG'VR†f'&–2æ6öçF–ç2‚&ö&¦V7BöÆ–7•7–çF†W6—¦W#cS""’bbf'&–2æ6öçF–ç2‚&FF6Æ72FTFV6—6–öäVçfVÆ÷ScS""’bbf'&–2æ6öçF–ç2‚$DUõôÄ”5’"’bbf'&–2æ6öçF–ç2‚$DUõ$Ut$B"’¢76W'EG'VR†f'&–2æ6öçF–ç2‚&'”GFV×B"’bbf'&–2æ6öçF–ç2‚&'•÷6—F–öâ"’bbf'&–2æ6öçF–ç2‚'&Wv&FVE÷6—F–öç2"’¢76W'EG'VR†f–æÄ'W2æ6öçF–ç2‚$FUöÆ–7•&Wv&B"’bb6öç7VÖW"æ6öçF–ç2‚&FVÆ—fW%FôFUöÆ–7•&Wv&B"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚&6æöæ–6ÄW†—EFö¶Vå6æ6†÷CcS""’bb&÷Bæ6öçF–ç2‚$6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ÷Vå÷6—F–öç2‚’"’bb&÷Bæ6öçF–ç2‚$4äôä”4ÅôU„•EôdTTEócS""’¢Ð  ¢FW7@¢gVâcUóócS5öVçG'•öWF†÷&—G•÷W%öf–æÆ—G•öæEöW†—EöÖ&·5ö&U÷6÷W&6UöWF†÷&—FF—fR‚’°¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂFV6—6–öâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF–öäFV6—6–öå6æ6†÷CcSæ·B"’ç&VEFW‡B‚¢fÂW&Ö—BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôf–æÄW†V7WF–öåW&Ö—Bæ·B"’ç&VEFW‡B‚¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÖ—'&÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôW†V7WF÷$6æöæ–6ÄÖ—'&÷#cCC"æ·B"’ç&VEFW‡B‚¢fÂ–FVÒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô–FV×÷FVæ7”¶W•7F÷&ScC3ræ·B"’ç&VEFW‡B‚¢fÂ6æöâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂFö¶VäÖÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFö¶VäÖWF†÷&—G’æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ&ö÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ&ö÷D6W6T6Æ76–f–W#cCsæ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†FV6—6–öâæ6öçF–ç2‚&WF†÷&—G•fW'6–öâ"’bbFV6—6–öâæ6öçF–ç2‚&WF†÷&—FF—fU6–væÂ"’bbFV6—6–öâæ6öçF–ç2‚'6fWG•fW&F–7B"’bbFV6—6–öâæ6öçF–ç2‚'&W6öÇfVE6—¦U6öÂ"’¢76W'EG'VR†vFRæ6öçF–ç2‚&–Ö×WF&ÆTWF†÷&—G“cS2"’bbvFRæ6öçF–ç2‚&–Ö×WF&ÆTfFt'W“cS’"’¢76W'EG'VR†vFRæ6öçF–ç2‚&fFt–çFVçCcS’æ6÷’‚"’bbvFRæ6öçF–ç2‚&WF†÷&—FF—fU6–væÂÒÂ$%U•Â""’b`¢vFRæ6öçF–ç2‚&fFufW&F–7BÒ–b‡v–ææW"ç&TfFufW&F–7B–â6WDöb"’¢76W'EG'VR†vFRæ6öçF–ç2‚$UD„õ$•E•ô”åd$”åEôd”ÅU$R"’bbvFRæ6öçF–ç2‚$U„T5ôUD„õ$•E•õ5DDUôÔ•4ÔD4‚"’¢76W'EG'VR‡W&Ö—Bæ6öçF–ç2‚&W†V7WF–öåF–6¶WCcC“Bç&–Ö'”ÆæRÒW†V7WF–öåF–6¶WCcC“BæÆæR"’bbW&Ö—Bæ6öçF–ç2‚&W†V7WF–öåF–6¶WCcC“BæWF†÷&—FF—fU6–væÂÒ"²%Â$%U•Â""’¢76W'EG'VR†W†V2æ6öçF–ç2‚'F–6¶WCcS3òç&–Ö'”ÆæR"’bbW†V2æ6öçF–ç2‚%U%ô%U•õDU$Ô”äÅõ$UÄ•õ$T4õdU$TEócS2"’¢fÂ&Vv–âÒW†V2æ–æFW„öb‚%W$VçG'”f–æÆ—G”WF†÷&—G“cC“ræ&Vv–äGFV×B†VçG'”f–æÆ—G”–CcC“r"¢fÂ&W6W'fRÒW†V2æ–æFW„öb‚$W†V7WF÷$6æöæ–6ÄÖ—'&÷#cCC"æÖ—'&÷$'W”GFV×B‚"Â&Vv–â¢fÂFV&—BÒW†V2æ–æFW„öb‚%W$66÷VçDÆVFvW#cC3æöä'W’†7GVÅ6öÂÂfVScCƒR’"Â&W6W'fR¢fÂf–ÆÂÒW†V2æ–æFW„öb‚$W†V7WF÷$6æöæ–6ÄÖ—'&÷#cCC"æÖ—'&÷$'W”f–ÆÂ‚"ÂFV&—B¢fÂ¦÷W&æÂÒW†V2æ–æFW„öb‚'&V6÷&EG&FR‡G2ÂG&FR’"Âf–ÆÂ¢fÂFW&Ö–æÂÒW†V2æ–æFW„öb‚%W$VçG'”f–æÆ—G”WF†÷&—G“cC“ræÖ&´ö²†VçG'”f–æÆ—G”–CcC“r’"Â¦÷W&æÂ¢76W'EG'VR†&Vv–ââbb&W6W'fRâ&Vv–âbbFV&—Bâ&W6W'fRbbf–ÆÂâFV&—Bbb¦÷W&æÂâf–ÆÂbbFW&Ö–æÂâ¦÷W&æÂ¢76W'EG'VR†Ö—'&÷"æ6öçF–ç2‚&'W•öGFV×C¢"²"B"²&GFV×D–B"’bb–FVÒæ6öçF–ç2‚&gVâFW&Ö–æÄf÷""’¢76W'EG'VR†6æöâæ6öçF–ç2‚&VçG'•&–6UW6B"’bb6æöâæ6öçF–ç2‚&VçG'•&–6U6÷W&6R"’bb6æöâæ6öçF–ç2‚&VçG'•ööÄFG&W72"’bb6æöâæ6öçF–ç2‚&VçG'•&–6UW6BÒ&W—&VE&–6ScS’"’¢76W'EG'VR‡Fö¶VäÖæ6öçF–ç2‚&66†VDf÷$W†—CcS2"’bb&÷Bæ6öçF–ç2‚$4äôä”4ÅôU„•EôÔ$µõ$Te$U4…õTUTTEócS2"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚'66÷RæÆVæ6‚†¶÷FÆ–ç‚æ6÷&÷WF–æW2äF—7F6†W'2ä”ò’"’¢76W'EG'VR‡&ö÷Bæ–æFW„öb‚$U„T5ôUD„õ$•E•õ5DDUôÔ•4ÔD4‚"’Â&ö÷Bæ–æFW„öb‚$DDõ$õd”DU%ôUD…ôÄô4´õUEócCc‚"’¢Ð  ¢FW7@¢gVâcUóócSE÷W%÷F–6¶WEöF—7F6…ö–væ÷&W5öÖ—76–æuöFV6–ÖÇ5öæE÷&VÆV6W5öWfW'•öæöçFW&Ö–æÅöWF†÷&—G’‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂ6æöæ–6ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂG‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%G&ç67F–öãcCƒbæ·B"’ç&VEFW‡B‚ ¢76W'DfÇ6R†W†V7WF÷"æ6öçF–ç2‚%U%ô%U•ôDTdU%$TEôDT4”ÔÅ5ôÔ•54”äuò"²#cS’"’¢òòcRããcSCv"(	BgFW"÷W&F÷"&W÷'BF†BcRããcSCrÓFVfW"7FÆÆV@¢òòWfW'’G&FRÂvR&W7F÷&VBF†RcSBGf—6÷'’Ö6öçF–çVRF‚â&÷F‚F†P¢òòÆVv7’cSB6÷VçFW"æBF†RcSCr6ö×æ–öâ6÷VçFW'2×W7B&R&W6VçBà¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚%U%ôDT4”ÔÅ5õTäD”äuôEd•4õ%•ò"²#cSB"’¢76W'EG'VR€¢W†V7WF÷"æ6öçF–ç2‚%U%ôDT4”ÔÅ5õTäD”äuôDTdU%ò"²#cSCr"’b`¢W†V7WF÷"æ6öçF–ç2‚%U%õD”4´UEõ$UTUTTEò"²#cSCr"’À¢¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚%U%õD”4´UEôD•5D4„TEò"²#cSB"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚%U%õD”4´UEõDU$Ô”äÅôõTåò"²#cSB"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚%U%õD”4´UEõDU$Ô”äÅô$Äô4µò"²#cSB"’¢76W'EG'VR†vFRæ6öçF–ç2‚%U%õD”4´UEôäôåDU$Ô”äÅõ$TÄT4Uò"²#cSB"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚$U„T5ôÄT4UôÄTµô”åd$”åEò"²#cSB"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚'&VÆV6UW$'W”æöåFW&Ö–æÃcSB‚"²%Â""²%4ôÅõU4EôÔ•54”äuò"²#cS’"²%Â""²"’"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚&7F–öã×&VÆV6UöÆÅöWF†÷&—G•÷&WG'•öæW‡Eö7–6ÆR"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚$f–æÄW†V7WF–öåW&Ö—Bç&VÆV6TW†V7WF–öâ‡G2æÖ–çB’"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚$ÆæTW†V7WF–öä6ö÷&F–æF÷"ç&VÆV6T–e&–Ö'’"’¢76W'EG'VR†vFRæ6öçF–ç2‚'&VÆV6TGFV×DæöåFW&Ö–æÂ"²#cSB"’¢76W'EG'VR†vFRæ6öçF–ç2‚&W†V7WF–öåF–6¶WG2ç&VÖ÷fR†GFV×D–B’"’¢76W'EG'VR†vFRæ6öçF–ç2‚&W†V7WF&ÆT'W”6Æ–ÓcCƒræVçG&–W2ç&VÖ÷fT–b"’¢76W'EG'VR†6æöæ–6Âæ6öçF–ç2‚'fÂVçF—G•66ÆS¢–çBÒFö¶VäFV6–ÖÇ2"’¢76W'EG'VR‡G‚æ6öçF–ç2‚'VçF—G•66ÆS¢–çBÒFV6–ÖÇ2"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚'VçF—G•66ÆRÒW%VçF—G•66ÆR"²#cSB"’¢fÂÖöFT'&æ6‚ÒW†V7WF÷"æ–æFW„öb‚&–b†—5W$ÖöFR’²"¢fÂW$F—7F6‚ÒW†V7WF÷"æ–æFW„öb‚'W$'W’‡G2ÂVfe6öÂ"ÂÖöFT'&æ6‚¢fÂÆ—fT'&æ6‚ÒW†V7WF÷"æ–æFW„öb‚'ÒVÇ6R–b‡vÆÆWBÓÒçVÆÂ’²"ÂÖöFT'&æ6‚¢76W'EG'VR‚%U"×W7BF—7F6‚&Vf÷&RÄ•dRÖöæÇ’W†V7WF÷"fÆ–FF–öâ"ÂÖöFT'&æ6‚âbbW$F—7F6‚âÖöFT'&æ6‚bbÆ—fT'&æ6‚âW$F—7F6‚¢fÂ¦÷W&æÂÒW†V7WF÷"æ–æFW„öb‚'&V6÷&EG&FR‡G2ÂG&FR’"ÂW†V7WF÷"æ–æFW„öb‚&gVâW$'W’‚"’¢fÂFW&Ö–æÂÒW†V7WF÷"æ–æFW„öb‚%U%õD”4´UEõDU$Ô”äÅôõTåò"²#cSB"ÂW†V7WF÷"æ–æFW„öb‚&gVâW$'W’‚"’¢76W'EG'VR‚'&VÂ%U’¦÷W&æÂ×W7B&RöâF†RW"÷VâF‚"Â¦÷W&æÂâbbFW&Ö–æÂâ¢Ð  ¢FW7@¢gVâcUóócSUö6æöæ–6Åö&ö÷G7G&ö—5ööfeöÖ–åöæEö†&Eö&'&–W'5öW†V7WF–öâ‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂöä7&VFRÒ&÷Bæ–æFW„öb‚&÷fW'&–FRgVâöä7&VFR‚’"¢fÂf÷&Vw&÷VæBÒ&÷Bæ–æFW„öb‚'7F'Df÷&Vw&÷VæB„äõD”eô”B"Âöä7&VFR¢fÂ6æöæ–6ÄÆVæ6‚Ò&÷Bæ–æFW„öb‚&6æöæ–6Ä&ö÷G7G&¦ö""²#cSRÒ66÷RæÆVæ6‚"Âöä7&VFR¢fÂWfVçDÆöBÒ&÷Bæ–æFW„öb‚$V6öæöÖ–4WfVçE66†VÖ"²#cCcBæ–æ—CcCƒb"Â6æöæ–6ÄÆVæ6‚¢fÂ6W'f–6TÆVæ6‚Ò&÷Bæ–æFW„öb‚'6W'f–6T&ö÷G7G&¦ö""²#cSbÒ66÷RæÆVæ6‚"ÂWfVçDÆöB¢fÂ7F'BÒ&÷Bæ–æFW„öb‚&gVâ7F'D&÷B‚’"¢fÂvFRÒ&÷Bæ–æFW„öb‚&FVfW%7F'EVçF–Å6W'f–6U&VG’"²#cSb‚’"Â7F'B¢76W'EG'VR‚&f÷&Vw&÷VæB6W'f–6R×W7B&V6VFRWfW'’GW&&ÆR&ö÷G7G&"Âf÷&Vw&÷VæBâöä7&VFRbb6æöæ–6ÄÆVæ6‚âf÷&Vw&÷VæB¢76W'EG'VR‚&GW&&ÆRWfVçBÆöB×W7BW†V7WFR–ç6–FR”ò6æöæ–6Â&ö÷G7G&"ÂWfVçDÆöBâ6æöæ–6ÄÆVæ6‚¢76W'EG'VR‚&6ö×ÆWFR6W'f–6R&ö÷G7G&×W7Bv—B&V†–æB6æöæ–6Â&WÆ’"Â6W'f–6TÆVæ6‚âWfVçDÆöB¢76W'EG'VR‚&WfW'’7F'D&÷BF‚×W7B†—BF†R6ö×ÆWFR6W'f–6R×&VG’vFRf—'7B"ÂvFRâ7F'BbbvFRÂ&÷Bæ–æFW„öb‚&—56‡WGF–ætF÷vâÒfÇ6R"Â7F'B’¢76W'EG'VR†&÷Bæ6öçF–ç2‚&6æöæ–6Ä&ö÷G7G&¦ö""²#cSSòæ¦ö–â‚’"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚'fÂ&ö÷G7G&Ò6W'f–6T&ö÷G7G&¦ö""²#cSb"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%5D%EôDTdU%$TEõ4U%d”4Uô$ôõE5E$ò"²#cSb"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%5D%Eô$Äô4´TEõ4U%d”4Uô$ôõE5E$ôd”ÄTEò"²#cSb"’¢Ð ¢FW7@¢gVâcUóócSeö6ö×ÆWFU÷W'6—7FVE÷7FFU÷7F'GWöfÖ–Ç•ö—5ööfeöÖ–â‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂöä7&VFRÒ&÷Bæ–æFW„öb‚&÷fW'&–FRgVâöä7&VFR‚’"¢fÂ6W'f–6TÆVæ6‚Ò&÷Bæ–æFW„öb‚'6W'f–6T&ö÷G7G&¦ö""²#cSbÒ66÷RæÆVæ6‚"Âöä7&VFR¢fÂöå7F'BÒ&÷Bæ–æFW„öb‚&÷fW'&–FRgVâöå7F'D6öÖÖæB"Â6W'f–6TÆVæ6‚¢fÂ–æÆ–æU&Vf—‚Ò&÷Bç7V'7G&–ær†öä7&VFRÂ6W'f–6TÆVæ6‚¢fÂ–ô&öG’Ò&÷Bç7V'7G&–ær‡6W'f–6TÆVæ6‚Âöå7F'B¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚$fVU&WG'•VWVRæ–æ—B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚$fVT67V×VÆF÷"æ–æ—B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚%66ææW$†&E&V¦V7E7F÷&Ræ–æ—B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚%G&FT†—7F÷'•7F÷&Ræ–æ—B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚$ÆV&æ–æuW'6—7FVæ6Ræ–æ—B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚%÷6—F–öåW'6—7FVæ6Ræ–æ—B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚%W'5G&FW$’æ–æ—B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚%Fö¶Væ—¦VE7Fö6µG&FW"ç7F'B‚"’¢76W'DfÇ6R†–æÆ–æU&Vf—‚æ6öçF–ç2‚$7'—FôÇEG&FW"ç7F'B‚"’¢76W'EG'VR†–ô&öG’æ6öçF–ç2‚$fVU&WG'•VWVRæ–æ—B†Æ–6F–öä6öçFW‡B’"’¢76W'EG'VR†–ô&öG’æ6öçF–ç2‚%G&FT†—7F÷'•7F÷&Ræ–æ—B†Æ–6F–öä6öçFW‡B’"’¢76W'EG'VR†–ô&öG’æ6öçF–ç2‚$ÆV&æ–æuW'6—7FVæ6Ræ–æ—B†Æ–6F–öä6öçFW‡B’"’¢76W'EG'VR†–ô&öG’æ6öçF–ç2‚%÷6—F–öåW'6—7FVæ6Ræ–æ—B†Æ–6F–öä6öçFW‡B’"’¢76W'EG'VR†–ô&öG’æ6öçF–ç2‚%4U%d”4Uô$ôõE5E$õ$TE•ò"²#cSb"’¢76W'EG'VR†–ô&öG’æ6öçF–ç2‚%4U%d”4Uô$ôõE5E$ôd”ÄTEò"²#cSb"’¢Ð  ¢FW7@¢gVâcUóócSf÷'VçF–ÖU÷6Öö¶U÷&WÆ—5öÖ…÷W'6—7FVEö†—7F÷'•öæE÷&WV—&W5öÆ—fU÷7F'B‚’°¢fÂ6Öö¶RÒ¦fæ–òäf–ÆR‚"ââòââö6’÷'VçF–ÖR×FW7Bç6‚"’ç&VEFW‡B‚¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚&6æöæ–6ÅöV6öæöÖ–5öWfVçG5ò"²#cCƒbç†ÖÂ"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚'&ævRƒC“b’"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚'6VVFVEöWfVçG3Óƒ“""’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚%4TTEô4õTåB"’bb6Öö¶Ræ6öçF–ç2‚#Ò"²%Â#ƒ“%Â""’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚$4äôä”4Åô$ôõE5E$õ$TE•ò"²#cSR"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚%4U%d”4Uô$ôõE5E$õ$TE•ò"²#cSb"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚'–Föb6öÒæÆ–fV7–6ÆV&÷BæFR"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚$å"–â6öÒæÆ–fV7–6ÆV&÷BæFR"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚%&ö6W73¢6öÒæÆ–fV7–6ÆV&÷BæFR"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚$dåôÄôõ"’bb6Öö¶Ræ6öçF–ç2‚%W'6—7FVBT’7F'Bõ7F÷52"’¢Ð  ¢FW7@¢gVâcUóócSu÷7F'E÷7F÷ö—5ö–ÖÖVF–FU÷f—6–&ÆUöGW&&ÆUöæEö6æ6VÆÆ&ÆR‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢fÂfÒÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ô&÷Ef–WtÖöFVÂæ·B"’ç&VEFW‡B‚¢fÂf–WrÒÖ–âæ–æFW„öb‚&'FåFövvÆRÒf–æEf–Wt'”–B…"æ–Bæ'FåFövvÆR’"¢fÂ–ÖÖVF–FT&–æBÒÖ–âæ–æFW„öb‚&&–æE'VçF–ÖUFövvÆTÆ—7FVæW""²#cSr‚’"Âf–Wr¢fÂ&VæFW&W"ÒÖ–âæ–æFW„öb‚'&—fFRgVâ&VæFW%'VçF–ÖT&"‚"¢76W'EG'VR‡f–Wrâbb–ÖÖVF–FT&–æBâf–Wrbb–ÖÖVF–FT&–æBÂ&VæFW&W"¢76W'DfÇ6R†Ö–âæ6öçF–ç2‚&'FåFövvÆRæ—4Væ&ÆVBÒfÇ6R"’¢76W'EG'VR†Ö–âæ6öçF–ç2‚%T•õ%TåD”ÔUõDôttÄUõDò"²#cSr"’¢76W'EG'VR†Ö–âæ6öçF–ç2‚$6æ6VÂ7F'B"’bbÖ–âæ6öçF–ç2‚%5D%Bd”ÄTB+r"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚'6W'f–6U7F'E&WVW7FVB"²#cSrç6WB‡G'VR’"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%5D%Eõ$UTU5Eõ$UD”äTEôEU$”äuô$ôõE5E$ò"²#cSr"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%4U%d”4Uô$ôõE5E$ô¤ô%ôÔ•54”äuò"²#cSr"’¢76W'DfÇ6R†&÷Bæ6öçF–ç2‚'v†–ÆR‚6W'f–6T&ö÷G7G&&VG’"²#cSb’"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚$DTdU%$TEõ5D%Eô4ä4TÄÄTEô%•õ5Dõò"²#cSr"’¢76W'EG'VR‡fÒæ6öçF–ç2‚%T•õ5D%EôD•5D4„TEò"²#cSr"’¢76W'EG'VR‡fÒæ6öçF–ç2‚%T•õ5D%EôdÄÄ$4µôD•5D4„TEò"²#cSr"’¢fÂ6Öö¶RÒ¦fæ–òäf–ÆR‚"ââòââö6’÷'VçF–ÖR×FW7Bç6‚"’ç&VEFW‡B‚¢fÂ&V6V—fW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6Öö¶UFW7E&V6V—fW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‡&V6V—fW"æ6öçF–ç2‚'7F'E÷6W'f–6R"’bb&V6V—fW"æ6öçF–ç2‚%4Ôô´UõT•õ4UEUôôäÅ•ò"²#cSr"’¢76W'EG'VR‚&FV'Vr6Öö¶R6WGW×W7B†—BF—6²&Vf÷&Rf÷&6R×7F÷–ær—G2WF‚F6²"Â&V6V—fW"æ6öçF–ç2‚"æ6öÖÖ—B‚’"’bb6Öö¶Ræ6öçF–ç2‚&Òf÷&6R×7F÷6öÒæÆ–fV7–6ÆV&÷BæFR"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚"ÒÖW¢7F'E÷6W'f–6RfÇ6R"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚'V•÷F–B'FåFövvÆRV•÷7F'Eóç†ÖÂ"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚'V•÷FFW‡B"²%Â%7F÷&÷EÂ""’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚'V•÷F–B'FåFövvÆRV•÷7F'Eó"ç†ÖÂ"’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚$dåõT•õD"’bb6Öö¶Ræ6öçF–ç2‚$dåõT•õ5D%B"’bb6Öö¶Ræ6öçF–ç2‚$dåõT•õ5Dõ"’¢Ð  ¢FW7@¢gVâcUóócS…öW†V7WF÷%öÆ—fT'W•÷v–FU÷&Vv—7FW%ö&—F†ÖWF–5ö—5ö–åö¶WEö†VÇW"‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ†VÇW"ÒW†V7WF÷"æ–æFW„öb‚'&—fFRgVâFæ&÷fVåv–ææW%6—¦T&ö÷7B"²#cS‚"¢fÂÆ—fRÒW†V7WF÷"æ–æFW„öb‚'&—fFRgVâÆ—fT'W’‚"Â†VÇW"¢fÂ6ÆÂÒW†V7WF÷"æ–æFW„öb‚&Fæ&÷fVåv–ææW%6—¦T&ö÷7B"²#cS‚‡G2ÂÆ–W%Fr’"ÂÆ—fR¢76W'EG'VR††VÇW"âbbÆ—fRâ†VÇW"bb6ÆÂâÆ—fR¢76W'EG'VR†W†V7WF÷"ç7V'7G&–ær††VÇW"ÒÂ†VÇW"’æ6öçF–ç2‚$æG&ö–G‚æææ÷FF–öâä¶VW"’¢fÂW‡G&7FVD†VÇW"ÒW†V7WF÷"ç7V'7G&–ær††VÇW"ÂÆ—fR¢76W'EG'VR†W‡G&7FVD†VÇW"æ6öçF–ç2‚$Æ—fUv–äDä7F÷&Rç6WGWg&WVVæ7’"’¢76W'EG'VR†W‡G&7FVD†VÇW"æ6öçF–ç2‚"†fuv–âÒ#ã’òã"’¢fÂ6Öö¶RÒ¦fæ–òäf–ÆR‚"ââòââö6’÷'VçF–ÖR×FW7Bç6‚"’ç&VEFW‡B‚¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚$dåõdU$”e•ôU%$õ""’¢76W'EG'VR‡6Öö¶Ræ6öçF–ç2‚%fW&–f–W"&V¦V7FVB"’¢Ð  ¢FW7@¢gVâcUóócS#÷&u÷VçF—G•ö—5ö6æöæ–6ÅöVæE÷FõöVæB‚’°¢fÂÖöFVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöFFôÖöFVÇ2æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ†—7F÷'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FT†—7F÷'•7F÷&Ræ·B"’ç&VEFW‡B‚¢fÂFW&Ö–æÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚¢fÂWF†÷&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å&uVçF—G”WF†÷&—G“cS#æ·B"’ç&VEFW‡B‚¢76W'EG'VR†ÖöFVÂæ6öçF–ç2‚'fÂVçG'•&uG“¢&–t–çFVvW""’bbÖöFVÂæ6öçF–ç2‚'fÂ6æöæ–6Ä6öç7VÖVE&s¢&–t–çFVvW""’bbÖöFVÂæ6öçF–ç2‚'fÂ&VÖ–æ–æu&uG“¢&–t–çFVvW""’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚'W%&tg&öÔV6öæöÖ–72‚"’bbW†V7WF÷"æ6öçF–ç2‚&¦÷W&æÅ6öÆE&r‡G&FRç6öÆEG•Fö¶Vâ"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚&6æöæ–6Ä6öç7VÖVE&rÒ&ufW&F–7CcS#ææ÷&ÖÆ—¦VE&r"’bbFW&Ö–æÂæ6öçF–ç2‚&6æöæ–6Ä6öç7VÖVE&rÒ6öÆEG•&r"’¢76W'EG'VR††—7F÷'’æ6öçF–ç2‚&VçG'•÷&u÷G’DU…B"’bb†—7F÷'’æ6öçF–ç2‚'WB‚"²%Â&6æöæ–6Åö6öç7VÖVE÷&uÂ""²"ÂBæ6æöæ–6Ä6öç7VÖVE&rçFõ7G&–ær‚’’"’¢76W'EG'VR†WF†÷&—G’æ6öçF–ç2‚$ÄTt5•õ$õTäD”äuôU4”Äôåõ$s¢&–t–çFVvW"Ò&–t–çFVvW"äôäR"’bbWF†÷&—G’æ6öçF–ç2‚$DT4”ÔÅõ44ÄUôÔ•4ÔD4‚"’¢76W'DfÇ6R†WF†÷&—G’æ6öçF–ç2‚$&–tFV6–ÖÂ†F÷V&ÆR’"’¢Ð  ¢FW7@¢gVâcUóócS#÷VçF—G•ö–çf&–çE÷&W—'5ög&öÕö6æöæ–6Å÷&u÷v—F†÷WEöf÷&6Uö6Æ÷6R‚’°¢fÂWF†÷&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õVçF—G”–çf&–çDWF†÷&—G“cSæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂV’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚¢76W'EG'VR†WF†÷&—G’æ6öçF–ç2‚'&VÖ–æ–æuG•&rçFô&–tFV6–ÖÂ‚’æÖ÷fUö–çDÆVgB"’bbWF†÷&—G’æ6öçF–ç2‚'&V6öç7G'V7Dg&öÔ6æöæ–6Â"’¢76W'DfÇ6R†WF†÷&—G’æ6öçF–ç2‚%vÆÆWDÖævW"æÆ7D¶æ÷vå6öÅ&–6R"’¢76W'DfÇ6R†WF†÷&—G’æ6öçF–ç2‚$†—7F÷&–6ÄV6öæöÖ–5V&çF–æScC“bç&W÷'D÷'†äÆ÷B"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%TåD•E•õ$ô¤T5D”ôåõ$T4ôå5E%T5DTEôe$ôÕô4äôä”4Åõ$uócS#"’bb&÷Bæ6öçF–ç2‚%÷6—F–öåW'6—7FVæ6Rç6fU÷6—F–öâ‡G2’"’¢76W'DfÇ6R†&÷Bæ6öçF–ç2‚'&WVW7E6VÆÂ‡G2ÒG2Â&V6öâÒ"²%Â$”åd$”åEõT$åD”äUócSÂ""’¢76W'EG'VR‡V’æ6öçF–ç2‚%VçF—G”–çf&–çDWF†÷&—G“cSæ6†V6²‡G2æÖ–çBÂ÷2’æö²"’¢Ð  ¢FW7@¢gVâcUóócS#%ö6æöæ–6Å÷VçF—G•÷FW&Ö–æÅö6÷VçG5öÖ&·5öæE÷6æ6†÷Eö6öçG&7B‚’°¢fÂÖ÷VçBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅFö¶VäÖ÷VçCcS#"æ·B"’ç&VEFW‡B‚¢fÂvÆÆWBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²õ6öÆævÆÆWBæ·B"’ç&VEFW‡B‚¢fÂ&ö6W76÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&ö6W76÷$Ö÷VçEÆææW"æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂFW&Ö–æÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õFW&Ö–æÄ×WFF–öäWF†÷&—G“cCcbæ·B"’ç&VEFW‡B‚¢fÂW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%FW&Ö–æÄ'&–FvScCc’æ·B"’ç&VEFW‡B‚¢fÂ6÷VçG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅG&FT6÷VçDWF†÷&—G“cS#"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂÖ&·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÖ&´WF†÷&—G”–çFVw&—G”vFScC“bæ·B"’ç&VEFW‡B‚¢76W'EG'VR†Ö÷VçBæ6öçF–ç2‚'fÂ&s¢&–t–çFVvW""’bbÖ÷VçBæ6öçF–ç2‚&gVâV’‚“¢&–tFV6–ÖÂ"’¢76W'EG'VR‡vÆÆWBæ6öçF–ç2‚&÷E7G&–ær‚"²%Â&Ö÷VçEÂ""²"Â"²%Â%Â""²"’"’¢76W'DfÇ6R‡vÆÆWBæ6öçF–ç2‚$ÖÅ7G&–ærÂ—#ÄF÷V&ÆRÂ–çCãâ"’¢76W'DfÇ6R‡&ö6W76÷"æ6öçF–ç2‚$&–tFV6–ÖÂ‡&WVW7FVEV•G’"’¢76W'DfÇ6R†W†V7WF÷"æ6öçF–ç2‚'fÂG’ÒVçG'•6öÂòVçG'•&–6R"’¢76W'DfÇ6R†W†V7WF÷"æ6öçF–ç2‚'fÂ†VÆVEG’Ò‡÷2æ6÷7E6öÂ¢&–6TÖ÷fT×VÇF—ÆR’ò7GVÅ&–6R"’¢76W'EG'VR†Ö÷VçBæ6öçF–ç2‚$eTÄÅô4Äõ4UôäõEôU„5Eõ$TÔ”äDU""’bbÖ÷VçBæ6öçF–ç2‚%E•ôDT4”ÔÅõ4´Ur"’¢76W'EG'VR‡FW&Ö–æÂæ6öçF–ç2‚"B"²'¶ÖöFRæÆ÷vW&66R‚—×ÂB"²'÷6—F–öä–GÂB"²&vVæW&F–öçÂB"²&6Æ÷6UG—R"’¢76W'DfÇ6R‡FW&Ö–æÂæ6öçF–ç2‚"B"²'·'Vä–BævWB‚—×Â"’¢76W'EG'VR‡W"æ6öçF–ç2‚$4äôä”4ÅõE•òB"²'·G•fÆ–FF–öãcS#"ç&V6öçÒ"’¢76W'EG'VR†6÷VçG2æ6öçF–ç2‚'6W76–öä6ö×ÆWFVEG&FW2"’bb6÷VçG2æ6öçF–ç2‚&Æ–fWF–ÖT6ö×ÆWFVEG&FW2"’bb6÷VçG2æ6öçF–ç2‚&÷VåG&FW2"’¢76W'EG'VR‡&W÷'Bæ6öçF–ç2‚'fÂ&Wf—6–öãcS#"Ò&W÷'E&Wf—6–öãcS#"æ–æ7&VÖVçDæDvWB‚’"’¢76W'EG'VR‡&W÷'Bæ6öçF–ç2‚%6W76–öâ6ö×ÆWFVBG&FW3¢"’bb&W÷'Bæ6öçF–ç2‚$Æ–fWF–ÖR6ö×ÆWFVBG&FW3¢"’bb&W÷'Bæ6öçF–ç2‚$÷Vâ÷6—F–öç3¢"’¢76W'EG'VR†Ö&·2æ6öçF–ç2‚'fÂ&–6UfÆ–F—G’"’bbÖ&·2æ6öçF–ç2‚'fÂÆ—V–F—G•fÆ–F—G’"’¢76W'EG'VR†Ö&·2æ6öçF–ç2‚"ööÄFG&W72ç7F'G5v—F‚‚"²%Â$Ô”åEõ$õUDS¥Â""’¢Ð ¢FW7@¢gVâcUóócS35öW†V7WF–öåöWF†÷&—G•ö—5ö6W6Åö&÷VæFVEöæEö7&÷75÷Væ—fW'6U÷6fR‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂöÆ–7’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF–öäWF†÷&—G•öÆ–7“cS32æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂÆâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õG&FW%'VçF–ÖUÆãcS#bæ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR‡öÆ–7’æ6öçF–ç2‚&gVâ—5G'Væ´ÆæR"’bböÆ–7’æ6öçF–ç2‚&gVâ6VÆV7DöæU&W67VR"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%c5ô4äôä”4Åô„äDôdeõTäD”äuócS32"’bb&÷Bæ6öçF–ç2‚'&V6÷&DfFtæDvWD–çFVçCcS32"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚$f–æÄFV6—6–öävFRæWfÇVFR‚"’bb&÷Bæ6öçF–ç2‚'fÂc4GFV×D–BÒc4–çFVçCcS32æGFV×D–B"’¢76W'DfÇ6R†&÷Bæ6öçF–ç2‚%c5ô4õ$Uõ4„DõuôU„T5UDUõd•4”$”Ä•E•ócCƒr"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚%c5ô%U•õ$T¤T5DTEôäõôU„5Eô”åDTåEócS32"’¢fÂc4'W”&VcS32ÒW†V7WF÷"ç7V'7G&–ætgFW"‚&gVâc4'W’‚"’ç7V'7G&–æt&Vf÷&R‚%ÆâgVâ"¢76W'DfÇ6R‡c4'W”&VcS32æ6öçF–ç2‚'&V6VçDÆÆ÷vVDGFV×D–Dç”ÆæR‡G2æÖ–çB’"’¢76W'EG'VR†vFRæ6öçF–ç2‚'&WV—&W56öÆæFö¶VäÖ"’bbvFRæ6öçF–ç2‚&ÆÆ÷uG'Væ´W†V7WF–öä†æFöfccS32"’¢76W'EG'VR†vFRæ6öçF–ç2‚&–çFVçBæfFufW&F–7BçWW&66R‚’–â6WDöb‚"’bbvFRæ6öçF–ç2‚%$ô$UôôäÅ’"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$5%•Dõõ4„õ%Eõ$U$õUDTEõDõõU%ócS32"’bb7'—Fòæ6öçF–ç2‚$DDU%ôD•$T5D”ôåõTå5Uõ%DTB"’¢76W'DfÇ6R†7'—Fòæ6öçF–ç2‚%5õEõ4„õ%EõTå5Uõ%DTB"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"’bb7'—Fòæ6öçF–ç2‚&f–æÄW†V7WF&ÆUfW&F–7CccCr"’¢76W'EG'VR‡Æâæ6öçF–ç2‚'W$ÆV&äWfW'—F†–æscS32"’bb&÷Bæ6öçF–ç2‚'V&Æ—6‚‡ÆãcS#bæVæ&ÆVEG&FW%6WB‚’’"’¢76W'EG'VR‡&W÷'Bæ6öçF–ç2‚$U„T5UD”ôâUD„õ$•E’”åd$”åE2cS32"’b`¢&W÷'Bæ6öçF–ç2‚$äôåõ4ôÄäõDô´TäÔô„$Däò"’bb&W÷'Bæ6öçF–ç2‚$U„T5UD$ÄUôdäõUEõU%ô4äD”DDUôuEó""’¢Ð  ¢FW7@¢gVâcUóócSCEöW†—7F–æuöWF†÷&—F–W5÷&÷fUö×VÇF–6†–åöF—66÷fW'•÷v—F†÷WE÷&ÆÆVÅö&6†—FV7GW&R‚’°¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂFW‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöæWGv÷&²ôFW‡67&VVæW$’æ·B"’ç&VEFW‡B‚¢fÂ&W6öÇfW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòô7'—FõVæ—fW'6U&÷WFU&W6öÇfW"æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ö7'—Fòô7'—FõVæ—fW'6TW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂ6öÖÖöF—F–W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô6öÖÖöF—F–W5G&FW"æ·B"’ç&VEFW‡B‚¢fÂÖ&¶WG2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖ&¶WG4Æ—fTW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂFö¶Væ—¦VBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VD76WE&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW$W†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚ ¢76W'DWVÇ2‚&&6WÃ†&2"Â6öÒæÆ–fV7–6ÆV&÷BçW'2äG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ6æöæ–6Ä–FVçF—G“cSCB‚&&6R"Â#†&2"’¢76W'DWVÇ2‚'Væ¶æ÷vçÃ†&2"Â6öÒæÆ–fV7–6ÆV&÷BçW'2äG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ6æöæ–6Ä–FVçF—G“cSCB‚""Â#†&2"’¢76W'EG'VR‡&Vv—7G'’æ6öçF–ç2‚'fÂ6†–ä–C¢7G&–ærÒ"²r"r²r"r’b`¢&Vv—7G'’æ6öçF–ç2‚'fÂ6æöæ–6Ä–FVçF—G“cSCB"’bb&Vv—7G'’æ6öçF–ç2‚'fÂFö¶VäFG&W73¢7G&–ærÒÖ–çB"’¢76W'EG'VR†FW‚æ6öçF–ç2‚'fÂ6†–ä–C¢7G&–ær"’bbFW‚æ6öçF–ç2‚'fÂFW„–C¢7G&–ær"’b`¢FW‚æ6öçF–ç2‚'fÂFö¶VäFG&W73¢7G&–ær"’bbFW‚æ6öçF–ç2‚'fÂV÷FTFG&W73¢7G&–ær"’b`¢FW‚æ6öçF–ç2‚'fÂ—$7&VFVDC¢Æöær"’bbFW‚æ6öçF–ç2‚'Fö¶Vâ×—'2÷cò"²rBr²'¶Væ6öFR†6†–ä–B—Òò"²rBr²'¶Væ6öFR‡Fö¶VäFG&W72—Ò"’¢76W'DfÇ6R‡&Vv—7G'’æ6öçF–ç2‚&–b‡—"æ6†–ä–BÒ"²r"r²'6öÆæ"²r"r²"’6öçF–çVR"’¢76W'EG'VR‡&Vv—7G'’æ6öçF–ç2‚&fWF6„vV6¶õööÇ3cSCB"’bb&Vv—7G'’æ6öçF–ç2‚$†VÇF„v&T‡GGæW†V7WFR†‡GGÂ&WÂ†÷7B’"’b`¢&Vv—7G'’æ6öçF–ç2‚$e$U4…ôD•44õdU%•ôÕ5ócSCB"’bb&Vv—7G'’æ6öçF–ç2‚$5D•dUôD•44õdU%•ôÕ5ócSCB"’b`¢&Vv—7G'’æ6öçF–ç2‚&vWD&ÆVæFVD÷÷'GVæ—G•VWVScSCB"’¢76W'EG'VR‡&W6öÇfW"æ6öçF–ç2‚&æöå6öÆæW‡Æ–6—CcSCB"’bb&W6öÇfW"æ6öçF–ç2‚'F&vWD6†–ä–CcSCB"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚'F&vWD6†–ä–CcSCBÒF&vWD6†–ä–CcSCB"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚&vWD&ÆVæFVD÷÷'GVæ—G•VWVScSCB‚’"’bb7'—Fòæ6öçF–ç2‚&G–ä76WD¶W’"’b`¢7'—Fòæ6öçF–ç2‚'F&vWD6†–ä–CcSCBÒ6–væÂæG–ä6†–ä–B"’¢76W'EG'VR†6öÖÖöF—F–W2æ6öçF–ç2‚'fÂ6öÖÖöF—G”Ö&¶WG2ÒW'4Ö&¶WBçfÇVW2‚’æf–ÇFW"²—Bæ—46öÖÖöF—G’Ò"’b`¢6öÖÖöF—F–W2æ6öçF–ç2‚$Ö&¶WG4Æ—fTW†V7WF÷"æW†V7WFTÆ—fUG&FU&ööccCƒb"’¢76W'EG'VR†Ö&¶WG2æ6öçF–ç2‚&W†V7WFTÆ—fUG&FU&ööccCƒb"’bbFö¶Væ—¦VBæ6öçF–ç2‚&†5&VÅ&÷WFR"’¢76W'EG'VR†Ö&¶WG2æ6öçF–ç2‚%cRããcSCR(	B6æöæ–6Â'&–FvR&–Â"’b`¢Ö&¶WG2æ6öçF–ç2‚%Væ—fW'6Ä'&–FvTVæv–æRç&W&T6—FÂ‚"’b`¢Ö&¶WG2æ6öçF–ç2‚&6æöæ–6Â'&–FvR&WGW&æVBæò7v6–væGW&S²&VgW6–ær÷Vâ"’b`¢Ö&¶WG2ç7V'7G&–ætgFW"‚%cRããcSCR(	B6æöæ–6Â'&–FvR&–Â"’ç7V'7G&–æt&Vf÷&R‚'&—fFR7W7VæBgVâW†V7WFT§W—FW%7v"¢æ6öçF–ç2‚&–çWDÖ–çBÒ4ôÅôÔ”åB"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚&&6¶w&÷VæDÆ—fVæW756æ6†÷CcSCB"’bb&÷Bæ6öçF–ç2‚'&V6÷&D&6¶w&÷VæE&öw&W73cSCB"’b`¢&÷Bæ6öçF–ç2‚&F÷¦TWf–FVæ6ScSCB"’bb&÷Bæ6öçF–ç2‚$„T%D$TEõ$U45TUõ$ôu$U55õD”ÔTõUEócSCB"’b`¢&÷Bæ6öçF–ç2‚$Äôäuô5”4ÄUôäõEôDõ¤UócSCB"’bb&÷Bæ6öçF–ç2‚&&GFW'”÷Ev†—FVÆ—7FVCÒ"’¢76W'EG'VR‡W$W†V7WF÷"æ6öçF–ç2‚'fÂ6VÆÄvVæW&F–öãcCsBÒ6æöæ–6ÅFW&Ö–æÅ÷6—F–öãcC“"æ÷VæVDD×2"’b`¢W$W†V7WF÷"æ6öçF–ç2‚'fÂ6VÆÄvVæW&F–öãcCsBÒG&FT–BçG&FT–B"’¢76W'EG'VR‡&W÷'Bæ6öçF–ç2‚$&6¶w&÷VæB'VçF–ÖR&öw&W72…cRããcSCB’"’b`¢&W÷'Bæ6öçF–ç2‚$$uô$õEôÄôõõD”4²"’bb&W÷'Bæ6öçF–ç2‚$$uõ44åô4""’bb&W÷'Bæ6öçF–ç2‚$$uô”åD´R"’b`¢&W÷'Bæ6öçF–ç2‚$$uôdDr"’bb&W÷'Bæ6öçF–ç2‚$$uôU„•B"’¢76W'EG'VR‡&W÷'Bæ6öçF–ç2‚$7'—FòVæ—fW'6RF—66÷fW'’…cRããcSCB’"’¢76W'EG'VR‡&Vv—7G'’æ6öçF–ç2‚&æWGv÷&·2ö'6W'fVCÒ"’bb&Vv—7G'’æ6öçF–ç2‚$DU†W2ö'6W'fVCÒ"’b`¢&Vv—7G'’æ6öçF–ç2‚&g&W6‚ööÇ2F—66÷fW&VCÒ"’bb&Vv—7G'’æ6öçF–ç2‚'Væ—VR6†–â·Fö¶Vâ–FVçF—F–W3Ò"’b`¢&Vv—7G'’æ6öçF–ç2‚&F—66÷fW&–W2'’6†–ãÒ"’bb&Vv—7G'’æ6öçF–ç2‚'ööÂ6ö†÷'G2ÃVÓÒ"’b`¢&Vv—7G'’æ6öçF–ç2‚&g&W6‚&V6†–ær7'—Fô'&–ãÒ"’bb&Vv—7G'’æ6öçF–ç2‚&g&W6‚&V6†–ærc2ôdDsÒ"’b`¢&Vv—7G'’æ6öçF–ç2‚'W"ÖöæÇ’Væf–Æ&ÆRÆ—fR&÷WFSÒ"’bb&Vv—7G'’æ6öçF–ç2‚&Æ—fR×&÷WF&ÆR6æF–FFW3Ò"’b`¢&Vv—7G'’æ6öçF–ç2‚'7FF–2×g2ÖG–æÖ–2WfÇVF–öâ6†&SÒ"’¢fÂvVæW&F÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô×VÇF”6†–åvÆÆWDvVæW&F÷#cSCbæ·B"’ç&VEFW‡B‚¢fÂfVÇBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô×VÇF”6†–åvÆÆWEfVÇCcSCbæ·B"’ç&VEFW‡B‚¢fÂvÆÆWDÖævW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõvÆÆWDÖævW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR†vVæW&F÷"æ6öçF–ç2‚%4Ä•Ó"’bbvVæW&F÷"æ6öçF–ç2‚#CB÷"„$DTäTB"’b`¢vVæW&F÷"æ6öçF–ç2‚&WF†W&WVÔFG&W72"’bbvVæW&F÷"æ6öçF–ç2‚&&—F6ö–äFG&W72"’¢76W'EG'VR‡fVÇBæ6öçF–ç2‚$Væ7'—FVE6†&VE&VfW&Væ6W2"’bbfVÇBæ6öçF–ç2‚'6öÆæ÷&—fFUö¶W•ö#S‚"’b`¢fVÇBæ6öçF–ç2‚$ÕTÅD”4„”åõT$Ä”5ôDE$U55ôTÕE’"’¢76W'EG'VR‡vÆÆWDÖævW"æ6öçF–ç2‚&vVæW&FT×VÇF”6†–åvÆÆWB"’bbvÆÆWDÖævW"æ6öçF–ç2‚&ÆöD×VÇF”6†–åvÆÆWB"’¢Ð  ¢FW7@¢gVâcUóócScE÷W%÷Væ—fW'6W5÷&W7F÷&UöÖV7W&&ÆUö†æFöfg5÷F–6¶WE÷G'WF…÷vÆÆWE÷G'WF…öæEö6W6ÅöÆV&æ–ær‚’°¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂ7Fö6·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VE7Fö6µG&FW"æ·B"’ç&VEFW‡B‚¢fÂW'2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'4W†V7WF–öäVæv–æRæ·B"’ç&VEFW‡B‚¢fÂW'4'&–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'5G&FW$’æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂ&W÷'BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂÖöæW’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&W÷'F–æt‡V"æ·B"’ç&VEFW‡B‚¢fÂv÷fW&æ÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ&VÆ—¦VEvÆÆWD6ö×÷VæF–ætv÷fW&æ÷"æ·B"’ç&VEFW‡B‚¢fÂ6öçG&7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Ä76WDVçG'”6öçG&7CcSSæ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$5%•Dõõ4”täÅõ4TÄT5DTEócScb"’b`¢7'—Fòæ6öçF–ç2‚'6÷W&6SÔE”äÔ”5ôÅB"’bb7'—Fòæ6öçF–ç2‚'6÷W&6SÕ5DD”5ôÅB"’b`¢7'—Fòæ6öçF–ç2‚&ÆæSÔ5%•DõôÅB6÷W&6SÔ4äôä”4Åô„äDôdeócScb"’b`¢7'—Fòæ6öçF–ç2‚'FƒÔ5%•DõôÅBÖöFSÒ"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$Ô$´UE5õ5Dô4µõ4”täÅõ4TÄT5DTEócScb"’b`¢7Fö6·2æ6öçF–ç2‚&ÆæSÔÔ$´UE5õ5Dô4µ26÷W&6SÔ4äôä”4Åô„äDôdeócScb"’b`¢6öçG&7Bæ6öçF–ç2‚%„4RädDr"’bb6öçG&7Bæ6öçF–ç2‚'6VÆVC×G'VR76WD6Æ73Ò"’¢76W'EG'VR‡W'2æ6öçF–ç2‚&ÆæSÕU%26÷W&6SÒ"’b`¢W'2æ6öçF–ç2‚$ÆæTW†V7WF–öä6ö÷&F–æF÷"æ6æF–FFUfW'6–öäf÷"‡6–væÂæÖ&¶WBç7–Ö&öÂ’"’¢76W'DfÇ6R‡W'2æ6öçF–ç2‚&6æF–FFUfW'6–öâÒ7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚’"’ ¢76W'EG'VR†vFRæ6öçF–ç2‚'fÂF–6¶WDWF†÷&—G“cScBÒ&W6öÇfU6VÆVD–çFVçCcc2‚"’b`¢vFRæ6öçF–ç2‚'F–6¶WDWF†÷&—G“cScCòæfFtÆÆ÷vVB"’b`¢vFRæ6öçF–ç2‚'F–6¶WDWF†÷&—G“cScCòæfFufW&F–7B"’b`¢vFRæ6öçF–ç2‚'F–6¶WDWF†÷&—G“cScBÓÒçVÆÂ"’¢76W'EG'VR†vFRæ6öçF–ç2‚$Ä•dUôU„T5UD”ôåõD”4´UEõEDÅôÕ2ÒCUóÂ"’b`¢vFRæ6öçF–ç2‚%U%ôU„T5UD”ôåõD”4´UEõEDÅôÕ2ÒƒóÂ"’b`¢vFRæ6öçF–ç2‚'F–6¶WBæÖöFRæWVÇ2…Â%U%Â"ÂG'VR’"’ ¢òòcRããccB*uU%ôÄTDtU%õ$TEõTä”d”4D”ôâ(	BF†R&W÷'F–æröv÷fW&æ÷ ¢òò7W&f6W2Ö’æ÷r&VBg&öÒF†R6æöæ–6Âf6FP¢òò…W$6—FÄWF†÷&—G“cSsr’v†–6‚—2$TBÔôäÅ’FVÆVvF–öâFð¢òòF†R6ÖRW$66÷VçDÆVFvW#cC3WF†÷&—G’â&÷F‚&VfW&Væ6W2&P¢òò66WF&ÆR(	BF†R÷W&F÷"w26öçfW&vVæ6RvöÂ—2ç’&VBF€¢òòF†BVÇF–ÖFVÇ’&W6öÇfW2FòF†R6–ævÆRÆVFvW"à¢76W'EG'VR‚†ÖöæW’æ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æ—4WF†÷&—G”–æ—F–Æ—¦VCcCƒ’‚’"’ÇÀ¢ÖöæW’æ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræ—4WF†÷&—G”–æ—F–Æ—¦VCcCƒ’‚’"’’b`¢†ÖöæW’æ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æ66…6öÂ‚’"’ÇÀ¢ÖöæW’æ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræ66…6öÂ‚’"’’¢76W'EG'VR‚†v÷fW&æ÷"æ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æ—4WF†÷&—G”–æ—F–Æ—¦VCcCƒ’‚’"’ÇÀ¢v÷fW&æ÷"æ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræ—4WF†÷&—G”–æ—F–Æ—¦VCcCƒ’‚’"’’b`¢†v÷fW&æ÷"æ6öçF–ç2‚%W$66÷VçDÆVFvW#cC3æ66…6öÂ‚’"’ÇÀ¢v÷fW&æ÷"æ6öçF–ç2‚%W$6—FÄWF†÷&—G“cSsræ66…6öÂ‚’"’’ ¢76W'EG'VR‡&W÷'Bæ6öçF–ç2‚'Væ—VR–çF¶R7–Ö&öÇ3¢"’bb&W÷'Bæ6öçF–ç2‚'Væ—VR–çF¶R(i"c3¢"’b`¢&W÷'Bæ6öçF–ç2‚'&RÕc2&WGW&ç3¢"’bb&W÷'Bæ6öçF–ç2‚%$Uõc5õ$UEU$åò"’¢76W'EG'VR‡7Fö6·2æ6öçF–ç2‚$6æöæ–6ÅV&Æ—6„†VÇW"çV&Æ—6„W†—B"’b`¢7Fö6·2æ6öçF–ç2‚&VçG'•GFW&âÒÂ%5Dô4µò"’¢76W'EG'VR‡W'4'&–âæ6öçF–ç2‚$6æöæ–6ÅV&Æ—6„†VÇW"çV&Æ—6„W†—B"’b`¢W'4'&–âæ6öçF–ç2‚&VçG'•GFW&âÒÂ%U%5ò"’¢Ð  ¢FW7@¢gVâcUóócScUöÆÅö7&÷75ö76WE÷W%ö÷Vç5ö6''•ööæUö6ö×F–&ÆUö6æöæ–6Åö–çFVçB‚’°¢fÂ6öçG&7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Ä76WDVçG'”6öçG&7CcSSæ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂW'2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'5G&FW$’æ·B"’ç&VEFW‡B‚¢fÂf÷&W‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôf÷&W…G&FW"æ·B"’ç&VEFW‡B‚¢fÂÖWFÇ2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖWFÇ5G&FW"æ·B"’ç&VEFW‡B‚¢fÂ6öÖÖöF—F–W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô6öÖÖöF—F–W5G&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR†6öçG&7Bæ6öçF–ç2‚&6æF–FFRæÖöFRæWVÇ2…Â$Ä•dUÂ"ÂG'VR’bb6æF–FFRæF—&V7F–öâæWVÇ2…Â%4„õ%EÂ"ÂG'VR’"’¢76W'EG'VR†6öçG&7Bæ6öçF–ç2‚%U5E$TÕô”åDTåEô4ôädÄ”5B"’bb6öçG&7Bæ6öçF–ç2‚'&Vv—7FW&VBç&W6öÇfVE6—¦RÒ6—¦–æræf–æÅ6—¦U6öÂ"’¢Æ—7Döb†7'—FòÂW'2Âf÷&W‚ÂÖWFÇ2Â6öÖÖöF—F–W2’æf÷$V6‚°¢76W'EG'VR†—Bæ6öçF–ç2‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"’¢76W'EG'VR†—Bæ6öçF–ç2‚&W†V7WF–öä–çFVçBÒ"’¢Ð¢76W'EG'VR‡W'2æ6öçF–ç2‚&W†V7WF–öä–çFVçCcScR"’bb7'—Fòæ6öçF–ç2‚&6æöæ–6Ä7'—Fô–çFVçCcScR"’¢Ð  ¢FW7@¢gVâcUóócSce÷'F–Ç5ö&U÷6WVVæ6Uö–FV×÷FVçEö6æöæ–6ÅöæEöæöçFW&Ö–æÂ‚’°¢fÂ'F–ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%'F–Ä÷W&F–öãcSæ·B"’ç&VEFW‡B‚¢fÂ&VGV6W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%G&ç67F–öãcCƒbæ·B"’ç&VEFW‡B‚¢fÂ7Fö6²Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õFö¶Væ—¦VE7Fö6µG&FW"æ·B"’ç&VEFW‡B‚¢fÂf÷&W‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôf÷&W…G&FW"æ·B"’ç&VEFW‡B‚¢fÂÖWFÇ2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖWFÇ5G&FW"æ·B"’ç&VEFW‡B‚¢fÂ6öÖÖöF—F–W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô6öÖÖöF—F–W5G&FW"æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‡'F–Âæ6öçF–ç2‚'&WVW7E6WVVæ6W2æ6ö×WFT–d'6VçB"’bb'F–Âæ6öçF–ç2‚'6Wæ–æ7&VÖVçDæDvWB‚’"’¢76W'DfÇ6R‡'F–Âæ6öçF–ç2‚&'2†W†—E&V6öâæ†6„6öFR‚’"’¢76W'EG'VR‡&VGV6W"æ6öçF–ç2‚&gVâ'F–Â‚"’bb&VGV6W"æ6öçF–ç2‚$6æöæ–6ÅW%'F–Ä÷W&F–öãcSæ6öÖÖ—B"’¢Æ—7Döb‡7Fö6²Âf÷&W‚ÂÖWFÇ2Â6öÖÖöF—F–W2Â7'—Fò’æf÷$V6‚°¢76W'EG'VR†—Bæ6öçF–ç2‚''F–Å÷6—F–öãcScb"’¢76W'EG'VR†—Bæ6öçF–ç2‚$6æöæ–6ÅW%G&ç67F–öãcCƒbç'F–Â"’¢76W'DfÇ6R†—Bæ6öçF–ç2‚$7F–öâå%D”ÂÓåÆâ6Æ÷6U÷6—F–öâ"’¢Ð¢76W'DWVÇ2ƒ"Â&VvW‚‚$fÇV–DÆV&æ–æuÅÂç&V6÷&EW%6VÆÅÅÂ‚"’æf–æDÆÂ‡7Fö6²’æ6÷VçB‚’’òò6‡WFF÷vâ²öæRæ÷&ÖÂFW&Ö–æÂF€¢Ð  ¢FW7@¢gVâcUóócSceöÖVÖU÷'F–ÅöæEö7–6Æ–5ö6Æ÷6U÷7FFU÷&WV—&UöÆ–VEöf–æÆ—G’‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂÖööâÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–ærôÖööç6†÷EG&FW$’æ·B"’ç&VEFW‡B‚¢fÂ7–6Æ–2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô7–6Æ–5G&FTVæv–æRæ·B"’ç&VEFW‡B‚¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚&FF6Æ72'F–Å6VÆÅ&V6V—CcScb"’b`¢W†V7WF÷"æ6öçF–ç2‚&gVâ&WVW7E'F–Å6VÆÄ6öæf—&ÖVCcScb‚"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚''F–ÃcSæ÷W&F–öä–B"’b`¢W†V7WF÷"æ6öçF–ç2‚$Ä•dUõ%D”Åô4ôäd•$ÔTB"’bbW†V7WF÷"æ6öçF–ç2‚&f–æÅ6–r"’¢76W'DWVÇ2ƒÂ&VvW‚‚'&WVW7E'F–Å6VÆÄ6öæf—&ÖVCcSceÅÂ‚"’æf–æDÆÂ†&÷B’æ6÷VçB‚’¢76W'EG'VR‚&ÆÂ÷F–Ö—7F–2'Værö6‡Væ²×WFF–öç2×W7B&R&V6V—BÖvFVB"À¢&VvW‚‚''F–Å&V6V—CcSceÅÂæÆ–VB"’æf–æDÆÂ†&÷B’æ6÷VçB‚’ãÒ¢fÂ6†V6´W†—E7F'CcScbÒÖööâæ–æFW„öb‚&gVâ6†V6´W†—B‚"¢fÂ6†V6´W†—BÒÖööâç7V'7G&–ær†6†V6´W†—E7F'CcScbÂÖööâæ–æFW„öb‚'&—fFRgVâWFFTÆV&æ–ær‚"Â6†V6´W†—E7F'CcScb’¢76W'DfÇ6R†6†V6´W†—Bæ6öçF–ç2‚''F–Å'Væw5F¶Vâ³Ò"’¢76W'EG'VR†6†V6´W†—Bæ6öçF–ç2‚'fÂ&÷÷6VE'VærÒ÷2ç'F–Å'Væw5F¶Vâ²"’¢fÂöå'F–ÂÒÖööâç7V'7G&–ær†Öööâæ–æFW„öb‚&gVâöå'F–Å6VÆÂ‚"’ÂÖööâæ–æFW„öb‚&gVâvWD7F—fU÷6—F–öç2‚"’¢76W'EG'VR†öå'F–Âæ6öçF–ç2‚''F–Å'Væw5F¶VâÒ‡÷2ç'F–Å'Væw5F¶Vâ²’"’¢76W'DfÇ6R‚'F–6²×W7BæWfW"&W7W'&V7BF—6&ÆVB7–6Æ–2WF†÷&—G’"Â7–6Æ–2æ6öçF–ç2‚&–b‚Væ&ÆVBævWB‚’’²Væ&ÆVBç6WB‡G'VR’Ò"’¢76W'EG'VR†7–6Æ–2æ6öçF–ç2‚$5”4Ä”5õD”4µõ4´•TEôD•4$ÄTEócScb"’¢fÂ6Æ÷6T7–6ÆRÒ7–6Æ–2ç7V'7G&–ær†7–6Æ–2æ–æFW„öb‚'&—fFRgVâ6Æ÷6T7–6ÆR‚"’Â7–6Æ–2æ–æFW„öb‚'&—fFRgVâ6ÆV$Æö6Ä7–6ÆU7FFScScb"’¢76W'EG'VR†6Æ÷6T7–6ÆRæ–æFW„öb‚'v†Vâ‡6VÆÅ&W7VÇCcScb’"’Â6Æ÷6T7–6ÆRæ–æFW„öb‚"òòWFFR&–æröæÇ’gFW"6öæf—&ÖVB6VÆÂf–æÆ—G’â"’¢76W'EG'VR†6Æ÷6T7–6ÆRæ6öçF–ç2‚$5”4Ä”5õ4TÄÅôäõEôd”äÅócScb"’bb6Æ÷6T7–6ÆRæ6öçF–ç2‚'&WF–å÷÷6—F–öåöæõöÆV&æ–ær"’¢76W'EG'VR†6Æ÷6T7–6ÆRæ6öçF–ç2‚$W†V7WF÷"å6VÆÅ&W7VÇBä4ôäd•$ÔTB"’bb6Æ÷6T7–6ÆRæ6öçF–ç2‚$W†V7WF÷"å6VÆÅ&W7VÇBåU%ô4ôäd•$ÔTB"’¢Ð  ¢FW7@¢gVâcUóócSceö7'—FõöÖ&¶WG5ö†æFöfg5öæEöÖVÖUöFVGWU÷&W6W'fUöW†V7WF&ÆUö–çF¶R‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂf–ÆW2ÒÆ—7Döb€¢$7'—FôÇEG&FW"æ·B"Fò$5%•DõôÅB"À¢%Fö¶Væ—¦VE7Fö6µG&FW"æ·B"Fò$Ô$´UE5õ5Dô4µ2"À¢$f÷&W…G&FW"æ·B"Fò$Ô$´UE5ôdõ$U‚"À¢$ÖWFÇ5G&FW"æ·B"Fò$Ô$´UE5ôÔUDÅ2"À¢$6öÖÖöF—F–W5G&FW"æ·B"Fò$Ô$´UE5ô4ôÔÔôD•D”U2"À¢¢f–ÆW2æf÷$V6‚²†æÖRÂÆæR’Óà¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2òFæÖR"’ç&VEFW‡B‚¢fÂ†æFöfbÒ7&2æ–æFW„öb‚&ÆæSÒFÆæR6÷W&6SÔ4äôä”4Åô„äDôdeócScb"¢fÂ7V&Ö—BÒ7&2æ–æFW„öb‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"Â†æFöfb¢76W'EG'VR‚"FÆæR×W7BVÖ—B7F—fRÆæRÖWfÂ–ÖÖVF–FVÇ’&Vf÷&R6æöæ–6ÂdDr7V&Ö—B"Â†æFöfbãÒbb7V&Ö—Bâ†æFöfbbb7V&Ö—BÒ†æFöfbÂ#¢76W'DWVÇ2‚"FÆæR×W7B†fRöæR6æöæ–6Â7F—fR†æFöfbÖ&¶W""ÂÂ&VvW‚‚&ÆæSÒFÆæR6÷W&6SÔ4äôä”4Åô„äDôdeócScb"’æf–æDÆÂ‡7&2’æ6÷VçB‚’¢Ð¢76W'EG'VR†&÷Bæ6öçF–ç2‚&–çF¶U6VVå6÷W&6W4'”Ö–çCcScb"’bb&÷Bæ6öçF–ç2‚&æWu6÷W&6TWf–FVæ6ScScb"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚$vÆö&ÅG&FU&Vv—7G'’çWFFU&ö&F–öå66ææW"†Ö–çBÂ6÷W&6R’"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚$vÆö&ÅG&FU&Vv—7G'’æÖW&vTff–æ—G’†Ö–çBÂÆæW3cScbÂFööÇ3cScb’"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%66ææW$‡–G&F–öåVWVW3c3Crä'V6¶WBäÄ•dUõ$TE’"’bb&÷Bæ6öçF–ç2‚$ÔTÔUôDTEUUõ$Te$U4…ócScb"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚$ÔTÔUô”åD´UôDTEUUôUd”DTä4Uõ$Te$U4…ócScb"’¢Ð   ¢FW7@¢gVâcUóócScu÷VçF—G•÷6—¦–æuöf–æÆ—G•ögVææVÅöÆV&æ–æuö†VÇF…öæE÷V•ö&Uö6æöæ–6Â‚’°¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ6—¦–ærÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô÷&FW%6—¦U&W6öÇfW#cCCæ·B"’ç&VEFW‡B‚¢fÂ6—¦–æt'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å6—¦–æt'&–FvScS3"æ·B"’ç&VEFW‡B‚¢fÂ7–6Æ–2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô7–6Æ–5G&FTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂWF†÷&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÄVçG'”WF†÷&—G“cSCæ·B"’ç&VEFW‡B‚¢fÂVçG'•6æ6†÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôVçG'•7G&FVw•6æ6†÷CcCSæ·B"’ç&VEFW‡B‚¢fÂf–æÆ—¦VBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Äf–æÆ—¦VEG&FT'W3cCcBæ·B"’ç&VEFW‡B‚¢fÂF7F–2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærõF7F–57v—F6†W"æ·B"’ç&VEFW‡B‚¢fÂ6Æ76–f–W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õ&ö÷D6W6T6Æ76–f–W#cCsæ·B"’ç&VEFW‡B‚¢fÂ†VÇF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ—VÆ–æT†VÇF„6öÆÆV7F÷"æ·B"’ç&VEFW‡B‚¢fÂÖ–âÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷V’ôÖ–ä7F—f—G’æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚&6æöæ–6ÅFW&Ö–æÅ÷6—F–öãcC“"çVçF—G•66ÆRæ6öW&6T–âƒÂ‚’"’¢76W'DfÇ6R†W†V7WF÷"æ6öçF–ç2‚&6æöæ–6ÅFW&Ö–æÅ÷6—F–öãcC“"çFö¶VäFV6–ÖÇ2çF¶T–b"’¢76W'EG'VR‡6—¦–æræ6öçF–ç2‚&Ç•W$ÖVÖTÖ–æ–×VÒ"’bb6—¦–æt'&–FvRæ6öçF–ç2‚&Ç•W$ÖVÖTÖ–æ–×VÒÒ76WD6Æ72ÓÒ76WD6Æ72å4ôÄäõDô´Tâ"’¢76W'EG'VR‡6—¦–æræ6öçF–ç2‚'fÂVffV7F—fU6†VDÆ×÷'G3cSbÒÆæT6Æ×VDÆ×÷'G3cC“"’¢76W'DfÇ6R‡6—¦–æræ6öçF–ç2‚$õ$DU%õ4•¤Uõ$ôÔõDTEõDõôÔ”åôU„T5UD$ÄUócSb"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚'fÂfÆö÷%&öÖ÷F–öå&WVW7FVCcSÒfÇ6R"’ ¢fÂ7V6–Æ—7G2ÒÆ—7Döb‚$&ÇVT6†—G&FW$’æ·B"Â$66„vVæW&F–öä’æ·B"Â$Öæ—VÆFVEG&FW$’æ·B"À¢$Öööç6†÷EG&FW$’æ·B"Â%VÆ—G•G&FW$’æ·B"Â%6†—D6ö–äW‡&W72æ·B"Â%6†—D6ö–åG&FW$’æ·B"¢æÖ²¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷c2÷66÷&–æròF—B"’ç&VEFW‡B‚’Ð¢7V6–Æ—7G2æf÷$V6‚°¢76W'DfÇ6R†—Bæ6öçF–ç2‚%c4¦÷W&æÅ&V6÷&FW"ç&V6÷&D6Æ÷6R‚"’¢76W'DfÇ6R†—Bæ6öçF–ç2‚$6æöæ–6ÅV&Æ—6„†VÇW"çV&Æ—6„W†—B‚"’¢76W'EG'VR†—Bæ6öçF–ç2‚&6æöæ–6ÂFW&Ö–æÂ'&–FvR÷vç2F†R6–ævÆR4TÄÂ¦÷W&æÂ&ö¦V7F–öâ"’ÇÀ¢—Bæ6öçF–ç2‚&6æöæ–6Âf–æÆ—¦VB'W2—2V&Æ—6†VB'’FW&Ö–æÄ'&–FvRôW†V7WF÷"öæÇ’"’¢Ð ¢76W'EG'VR†7–6Æ–2æ6öçF–ç2‚$4ôäd•$ÔTEôdÅ4RÂTä´äõtâÂ$õd”DU%õTäd”Ä$ÄRÂ4ôäd•$ÔTEõE%TR"’¢76W'EG'VR†7–6Æ–2æ6öçF–ç2‚&–b†—4Æ—fTÖöFR’Wf–FVæ6RÓÒ7–6Æ–56VÆÆ&–Æ—G”Wf–FVæ6ScScrä4ôäd•$ÔTEõE%TR"’bb7–6Æ–2æ6öçF–ç2‚&VÇ6RWf–FVæ6RÒ7–6Æ–56VÆÆ&–Æ—G”Wf–FVæ6ScScrä4ôäd•$ÔTEôdÅ4R"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚'Væ—VTG–å6–væÇ3cScr"’bb7'—Fòæ6öçF–ç2‚&w&÷W'’²—BæG–ä76WD¶W’"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚%õ4•D”ôåô4õ$T4„TB"’bb7'—Fòæ6öçF–ç2‚$ô%4U%dUõ5T4”Ä•5Eõ4”ÄTä4UócSc’"’b`¢7'—Fòæ6öçF–ç2‚""&Ö&´WfÇVF–öäF—7÷6—F–öãcScr‡&Vg&W6†VBÂ$äõô5D”ôä$ÄUõ5T4”Ä•5Eõ4”täÂ"’"""’¢76W'DfÇ6R†7'—Fòæ6öçF–ç2‚"çF¶Rƒ#R’òòcRã’ã#ƒ¢&—6VBg&öÒ2"’¢76W'EG'VR‡&Vv—7G'’æ6öçF–ç2‚&WfÇVF–öâFW&Ö–æÂF—7÷6—F–öç3Ò"’bb&Vv—7G'’æ6öçF–ç2‚$5%•DõôUdÅõDU$Ô”äÅócScr"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚&–b†—47'—FõVæ—fW'6U6÷W&6ScS3R’÷WB³ÒÂ$5%•DõôÅEÂ""’ ¢Ööb‚%Fö¶Væ—¦VE7Fö6µG&FW"æ·B"Fò%5Dô4µ2"Â$f÷&W…G&FW"æ·B"Fò$dõ$U‚"À¢$ÖWFÇ5G&FW"æ·B"Fò$ÔUDÅ2"Â$6öÖÖöF—F–W5G&FW"æ·B"Fò$4ôÔÔôD•D”U2"’æf÷$V6‚²†f–ÆRÂfÖ–Ç’’Óà¢fÂ7&2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2òFf–ÆR"’ç&VEFW‡B‚¢76W'EG'VR‡7&2æ6öçF–ç2‚&—5W$ÖöFRævWB‚’ÇÂ"’¢76W'EG'VR‡7&2æ6öçF–ç2‚$Ô$´UE5ôeTääTÅócScwÄdÔ”Å“ÒFfÖ–Ç—Å5DtSÕ4”täÅõ4TÄT5DTB"’¢76W'EG'VR‡7&2æ6öçF–ç2‚$Ô$´UE5ôeTääTÅócScwÄdÔ”Å“ÒFfÖ–Ç—Å5DtSÕU%õE%U5EôEd•4õ%’"’¢Ð¢76W'EG'VR†WF†÷&—G’æ6öçF–ç2‚$76WD6Æ757FG3cScr"’bbWF†÷&—G’æ6öçF–ç2‚&76WD6Æ74gVææVÅ&W÷'CcScr"’¢76W'EG'VR††VÇF‚æ6öçF–ç2‚$7&÷72Ô76WB6æöæ–6ÂgVææVÂ…cRããcScr’´4äôä”4Â5U%$TåB4U54”ôåÒ"’ ¢76W'EG'VR†VçG'•6æ6†÷Bæ6öçF–ç2‚$ÆV&æ–æuW'6—7FVæ6Rç6fR‡W'6—7FVæ6T¶W“cScr"’b`¢VçG'•6æ6†÷Bæ6öçF–ç2‚$ÆV&æ–æuW'6—7FVæ6RæÆöB‡W'6—7FVæ6T¶W“cScr"’¢76W'EG'VR†f–æÆ—¦VBæ6öçF–ç2‚'fÂVçG'•6÷W&6S¢7G&–ær"’bbf–æÆ—¦VBæ6öçF–ç2‚'fÂÖ&¶WE&Vv–ÖS¢7G&–ær"’bbf–æÆ—¦VBæ6öçF–ç2‚'fÂ66÷&T&æC¢7G&–ær"’¢76W'EG'VR‡F7F–2æ6öçF–ç2‚'W'6—7D†—7F÷&–6ÃcScr"’bbF7F–2æ6öçF–ç2‚&†—7F÷&–6ÅG&FW4f÷$6ö†÷'B"’¢76W'EG'VR†6Æ76–f–W"æ6öçF–ç2‚&7W'&VçEW$6öç6W'fF–öä†VÇF‡“cScr"’bb6Æ76–f–W"æ6öçF–ç2‚&6Æ76–f–W%ö7W'&VçEö6æöæ–6ÅöFVÇFö†VÇF‡•ócScr"’¢76W'EG'VR††VÇF‚æ6öçF–ç2‚%µ4U54”ôâ„•5Dõ$”4Â4õTåDU%5Ò"’bb†VÇF‚æ6öçF–ç2‚%´4äôä”4Â5U%$TåB4ä4„õE5Ò"’ ¢76W'EG'VR†Ö–âæ6öçF–ç2‚&–b‡7G'V7GW&Ä6†ævR’ÆÄ÷Vå÷6—F–öç2ç&VÖ÷fTÆÅf–Ww2‚’"’¢76W'EG'VR†Ö–âæ6öçF–ç2‚'fÂF—f–FW%f–Ws¢æG&ö–Bçf–Wråf–Wr"’¢76W'EG'VR†Ö–âæ6öçF–ç2‚&–b‡7G'V7GW&Ä6†ævR’²"’bbÖ–âæ6öçF–ç2‚&66†VBæF—f–FW%f–Wr"’¢76W'DfÇ6R†Ö–âæ6öçF–ç2‚&ÆÄ÷Vå÷6—F–öç2æFEf–Wr…f–Wr‡F†—2’æÇ’"’¢Ð ¢FW7@¢gVâcUóócSc…öÖVÖUöVçG'•÷öÆ–7•öæE÷F7F–5÷&Wv&G5ö&Uö6W6Â‚’°¢fÂW†V2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂF7F–2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRöÆV&æ–ærõF7F–57v—F6†W"æ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢76W'EG'VR†W†V2æ6öçF–ç2‚$Ä•dUôTåE%•õôÄ”5•õ4ä4„õEô4äôä”4ÅócSc‚"’bbW†V2æ6öçF–ç2‚&VçG'•F‡&W6†öÆE6æ6†÷BÒG2ç÷6—F–öâæVçG'•öÆ–7•6æ6†÷B"’¢76W'EG'VR†W†V2æ6öçF–ç2‚&VçG'•F7F–3Ò"²"B"²&VÆV7FVEF7F–3cSc‚"’bbW†V2æ6öçF–ç2‚&'&–ä6öç6Vç7W3Ò"²"B"²&'&–åfW&F–7CcSc‚"’bbW†V2æ6öçF–ç2‚'öÆ–7•v–ãÒ"²"B"²'·öÆ–7•v–ãcSc‚æf×Bƒ2—Ò"’¢76W'EG'VR‡F7F–2æ6öçF–ç2‚%D5D”5ô„•5Dõ$”4ÅôõUD4ôÔUôEE$”%UDTEócSc‚"’bbF7F–2æ6öçF–ç2‚&–b†VÆV7FVBææÖRÓÒ7W'&VçB’öåG&FT6Æ÷6VB"’¢76W'DfÇ6R‡F7F–2æ6öçF–ç2‚&VçFW&VBæ—4&Ææ²‚’ÇÂVçFW&VBÓÒ7W'&VçB"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚&WF†÷&—FF—fUöÆ–7•÷6—F—fScSc‚"’bb&÷Bæ6öçF–ç2‚$äTtD•dUô4ôå4Tå5U5ôäõ$ÔÅô%U•õ5U$U54TEócSc‚"’bb&÷Bæ6öçF–ç2‚&Æ—ö²bbWF†÷&—FF—fUöÆ–7•÷6—F—fScSc‚"’¢Ð  ¢FW7@¢gVâcUóócSc…ö6ö×ÆWF–öåö6W6ÅöÆV&æ–æuö–çFVw&—G•öæE÷6†–ær‚’°¢fÂ6æ6†÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôVçG'•7G&FVw•6æ6†÷CcCSæ·B"’ç&VEFW‡B‚¢fÂGf—6÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôWFõ—VÆ–æTGf—6÷#cCc"æ·B"’ç&VEFW‡B‚¢fÂVÆ–v–&–Æ—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚õW$ÆV&æ–ætVÆ–v–&–Æ—G“cS’æ·B"’ç&VEFW‡B‚¢fÂ'&–FvRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôf–æÆ—¦VD'W46öç7VÖW$'&–FvScCcRæ·B"’ç&VEFW‡B‚¢fÂ¦÷W&æÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõc4¦÷W&æÅ&V6÷&FW"æ·B"’ç&VEFW‡B‚¢fÂ6—¦W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõ6Ö'E6—¦W"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚#cSc‚G—VB–Ö×WF&ÆRVçG'’öÆ–7’Wf–FVæ6R"Â6æ6†÷Bæ6öçF–ç2‚&VçG'•öÆ–7•6æ6†÷D–B"’bb6æ6†÷Bæ6öçF–ç2‚'c46ö×öæVçG2"’bb6æ6†÷Bæ6öçF–ç2‚&'&–ä6öç6Vç7W5fW&F–7B"’bb6æ6†÷Bæ6öçF–ç2‚'öÆ–7•&ö&&–Æ—G’"’bb6æ6†÷Bæ6öçF–ç2‚'7V6–Æ—7D6öçG&–'WF–öç2"’bb6æ6†÷Bæ6öçF–ç2‚'6—¦–æt×VÇF—Æ–W'2"’bb6æ6†÷Bæ6öçF–ç2‚&WF†÷&—¦F–öå&V6öâ"’¢76W'EG'VR‚#cSc‚WfW'’Ó#R6Æ÷6R6W6Â&W÷'B"Â6æ6†÷Bæ6öçF–ç2‚'&÷w2ç6—¦RR#RÓÒ"’bb6æ6†÷Bæ6öçF–ç2‚$ÔTÔUõt”ääU%ôÄõ4U%ô4U4Åõ$Uõ%EócSc‚"’¢76W'EG'VR‚#cSc‚W'6—7FVB&÷VæFVB6W6ÂÆV&æW""Â6æ6†÷Bæ6öçF–ç2‚&ÖVÖUö6W6ÅöÆV&æ–æuócSc‚"’bb6æ6†÷Bæ6öçF–ç2‚'v†–ÆR‡&÷w2ç6—¦Râ’"’bb6æ6†÷Bæ6öçF–ç2‚&Vç7W&U&W7F÷&VB"’¢76W'EG'VR‚#cSc‚–çFVw&—G’F–væ÷7F–726ææ÷B×WFFR7G&FVw’"ÂGf—6÷"æ6öçF–ç2‚$Ed•4õ%ô”åDTu$•E•ôD”täõ5D”5ôôäÅ•ócSc‚"’bbGf—6÷"æ6öçF–ç2‚""$6æF–FFR‚&VçG'”6ööÆF÷vå6V2"Â³2ã"""’bbGf—6÷"æ6öçF–ç2‚%TäD”äuôTåE%•ôÄT´TEô”åDõôõTåócCcÒB"²'VæF–ætÆV·2(	BF‡&÷GFÆRVçG&–W2"’¢76W'EG'VR‚#cSc‚–çfÆ–BFW&Ö–æÇ2f÷&Vç6–2öæÇ’"ÂVÆ–v–&–Æ—G’æ6öçF–ç2‚$dõ$Tå4”5ôôäÅ•òB"²&–çfÆ–B"’bb¦÷W&æÂæ6öçF–ç2‚$¤õU$äÅõ5E$DTu•ôÄT$ä”äuõT$åD”äTEócSc‚"’¢76W'EG'VR‚#cSc‚öæR6æöæ–6Â6W6Â6öç7VÖW""Â'&–FvRæ6öçF–ç2‚&FVÆ—fW%FôÖVÖT6W6ÄÆV&æ–æscSc‚"’bb'&–FvRæ6öçF–ç2‚$FÖvT6öçG&öÄvFRææ÷FT÷WF6öÖR"’bb¦÷W&æÂæ6öçF–ç2‚%F7F–57v—F6†W"æöåG&FT6Æ÷6VB†Æ–W"Â&æBÂæÅ7DÆV&â’"’¢76W'EG'VR‚#cSc‚u"õb6†W2æ÷BF—6&ÆW2"Â6æ6†÷Bæ6öçF–ç2‚&–b‡w#ãÓãRbbcãÓãR’"’bb6æ6†÷Bæ6öçF–ç2‚#ãsVÇ6Rã#"’bb6—¦W"æ6öçF–ç2‚$ÔTÔUô4U4ÅõU$dõ$Ôä4Uõ4„TEócSc‚"’¢Ð  ¢FW7@¢gVâcUóócSc•ö7&÷75ö76WEö6W6ÅöÆ—fVæW75ö–FVçF—G•öGf—6÷%öæEöV6öæöÖ–72‚’°¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂWF†÷&—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÄVçG'”WF†÷&—G“cSCæ·B"’ç&VEFW‡B‚¢fÂ6öçG&7BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Ä76WDVçG'”6öçG&7CcSSæ·B"’ç&VEFW‡B‚¢fÂW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%G&ç67F–öãcCƒbæ·B"’ç&VEFW‡B‚¢fÂf–æÆ—G’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅG&FTf–æÆ—¦VD'W3cCSæ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂGf—6÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôWFõ—VÆ–æTGf—6÷#cCc"æ·B"’ç&VEFW‡B‚¢76W'EG'VR‚#cSc’–Ö×WF&ÆR6Æ72Æ—fW2öâ6VÆVBF–6¶WB"ÂvFRæ6öçF–ç2‚&76WD6Æ75Fr"’bb6öçG&7Bæ6öçF–ç2‚&76WD6Æ75FrÒ6æF–FFRæ76WD6Æ72çFr"’¢76W'EG'VR‚#cSc’F—7F6‚æWfW"&RÖFW&—fW26Æ72g&öÒW†V7WF÷"ÆæR"Â6öçG&7Bæ6öçF–ç2‚&–çFVçD76WD6Æ73cSc’†–çFVçB’"’bb6öçG&7Bæ6öçF–ç2‚&Ö&´FFW$F—7F6„f÷#cSS„76WD6Æ72æg&öÔÆæR†–çFVçBæ6æöæ–6ÄÆæR’"’¢76W'EG'VR‚#cSc’FW&Ö–æÂÆV&æ–ær6'&–W2–Ö×WF&ÆR6Æ72"Âf–æÆ—G’æ6öçF–ç2‚&76WD6Æ75FrÒWfVçBæ76WD6Æ75Fræ–d&Ææ²"’¢76W'EG'VR‚#cSc’–çFVçB6öç6W'fF–öâ—2W‡Æ–6—B"ÂWF†÷&—G’æ6öçF–ç2‚&F—7F6…&V¦V7CÒ"’bbWF†÷&—G’æ6öçF–ç2‚'VæF–æsÒ"’bbWF†÷&—G’æ6öçF–ç2‚'VæW‡Æ–æVCÒ"’¢76W'EG'VR‚#cSc’F‡&VR×v–æF÷r&öGV6W"Æ—fVæW72fVÇB"ÂWF†÷&—G’æ6öçF–ç2‚$Ô$´UEô4Ä55ôÄ•dTäU55ôdTÅB"’bbWF†÷&—G’æ6öçF–ç2‚'¦W&òævWB‚’ãÒ4Â"’¢76W'EG'VR‚#cSc’7V6–Æ—7B6–ÆVæ6Rö'6W'fW2F‡&÷Vv‚6†&VBWF†÷&—G’"Â7'—Fòæ6öçF–ç2‚$ô%4U%dUõ5T4”Ä•5Eõ4”ÄTä4UócSc’"’bb7'—Fòæ6öçF–ç2‚&Ö&´fFu&V6ƒcSCB‡6†&VEFö³cSc’"’bb7'—Fòæ6öçF–ç2‚""&Ö&´WfÇVF–öäF—7÷6—F–öãcScr‡&Vg&W6†VBÂ$äõô5D”ôä$ÄUõ5T4”Ä•5Eõ4”täÂ"’"""’¢76W'EG'VR‚#cSc’&÷VæFVB7'—Fò6†&VBÖ–çFVÆÆ–vVæ6Rv÷&²"Â7'—Fòæ6öçF–ç2‚"çF¶Rƒ#R’"’¢76W'EG'VR‚#cSc’Gf—6÷"6W6ÂÖFöÖ–â—6öÆF–öâ"ÂGf—6÷"æ6öçF–ç2‚$Ed•4õ%ô5$õ55ôDôÔ”åôÕUDD”ôåô$Äô4´TEócSc’"’bbGf—6÷"æ6öçF–ç2‚%$UÄ•ôE$•dTåôTåE%•ô4ôôÄDõtåõ$ôÄÄTEô$4µócSc’"’bbGf—6÷"æ6öçF–ç2‚""$6æF–FFR‚&VçG'”6ööÆF÷vå6V2"Â³2ã"""’¢76W'EG'VR‚#cSc’ÆWfW&vVBFW&Ö–æÂ&ö6VVG2æBV&çF–æR"Â7'—Fòæ6öçF–ç2‚'6öÂÒ‡÷2ç6—¦U6öÂ²æÅ6öÂ’æ6öW&6TDÆV7Bƒã’"’bbW"æ6öçF–ç2‚$ÄUdU$tTEõDU$Ô”äÅô$•D„ÔUD”5ôD•dU$tTä4UócSc’"’bbW"æ6öçF–ç2‚%W$ÆV&æ–ætVÆ–v–&–Æ—G“cS’ç&V6÷&B"’¢Ð ¢FW7@¢gVâcUóócSsöW†V7WF–öåöæEöW†—EöWF†÷&—G•÷&W—%ö6öçG&7B‚’°¢fÂÖ&²Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å&–6TÖ&³cS#"æ·B"’ç&VEFW‡B‚¢fÂÖ&´vFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÖ&´WF†÷&—G”–çFVw&—G”vFScC“bæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂ7–6Æ–2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô7–6Æ–5G&FTVæv–æRæ·B"’ç&VEFW‡B‚¢fÂW'2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'4W†V7WF–öäVæv–æRæ·B"’ç&VEFW‡B‚¢fÂW'4’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2õW'5G&FW$’æ·B"’ç&VEFW‡B‚¢fÂ÷6—F–öç2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å÷6—F–öäWF†÷&—G“cCCæ·B"’ç&VEFW‡B‚¢fÂW"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%G&ç67F–öãcCƒbæ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÆ—fRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖ&¶WG4Æ—fTW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂf÷&W‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôf÷&W…G&FW"æ·B"’ç&VEFW‡B‚¢fÂ6öÖÖöF—F–W2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô6öÖÖöF—F–W5G&FW"æ·B"’ç&VEFW‡B‚¢fÂÖWFÇ2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôÖWFÇ5G&FW"æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†Ö&²æ6öçF–ç2‚$6æöæ–6ÄÖ&µW'÷6ScSsäô%4U%dD”ôåõ44õ$”är"’b`¢Ö&²æ6öçF–ç2‚'&W6öÇfTW†V7WF&ÆTg&öÕ6÷W&6TWf–FVæ6Sccb"’b`¢&÷Bæ6öçF–ç2‚$6æöæ–6Å&–6TÖ&µ&Vv—7G'“cS#"ç&W6öÇfTW†V7WF&ÆTg&öÕ6÷W&6TWf–FVæ6Sccb"’¢76W'EG'VR†Ö&´vFRæ6öçF–ç2‚&—4ö'6W'fF–öäWF†÷&—FF—fScSs"’bbÖ&´vFRæ6öçF–ç2‚$tT4´õDU$Ô”äÂ"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚&Ö&´WfÇVF–öå&öw&W73cSs‡&Vg&W6†VB"’bb7'—Fòæ6öçF–ç2‚&Ö&´WfÇVF–öäF—7÷6—F–öãcScr†ö'6W'fVEFö³cSc’"’b`¢7'—Fòæ6öçF–ç2‚%4„$TEô”åDTÄÄ”tTä4Uô$4´Äôuô4ôÄU44TB"’¢76W'DWVÇ2ƒÂ&VvW‚‚%4„$TEô”åDTÄÄ”tTä4Uô$4´Äôuô4ôÄU44TEõ$UTUTR"’æf–æDÆÂ†7'—Fò’æ6÷VçB‚’¢76W'EG'VR‡&Vv—7G'’æ6öçF–ç2‚&WfÇVF–öävVæW&F–öãccR"’bb&Vv—7G'’æ6öçF–ç2‚&WfÇVF–öä–æfÆ–v‡CccR"’b`¢&Vv—7G'’æ6öçF–ç2‚$5%•DõôUdÅôtTäU$D”ôåô4ôÄU44TEóccR"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚&6æöæ–6Äf–æÅ6—¦ScSsÒ6æöæ–6Ä7'—Fô–çFVçCcScRç&W6öÇfVE6—¦R"’bb7'—Fòæ6öçF–ç2‚&Ö&´f–ÆVB†6æöæ–6Ä7'—Fô–çFVçCcScR"’¢76W'EG'VR†7–6Æ–2æ6öçF–ç2‚$6æöæ–6ÄÖ–çDö67Wæ7•&Vv—7G'“cCcBæ—4÷Vâ"’bb7–6Æ–2æ6öçF–ç2‚&–b†—4Æ—fTÖöFR’Wf–FVæ6RÓÒ7–6Æ–56VÆÆ&–Æ—G”Wf–FVæ6ScScrä4ôäd•$ÔTEõE%TR"’¢76W'EG'VR‡W'2æ6öçF–ç2‚$6æöæ–6Ä76WDVçG'”6æF–FFScSS"’bbW'2æ6öçF–ç2‚&76WD6Æ72Ò6öÒæÆ–fV7–6ÆV&÷BæVæv–æRçG'WF‚ä76WD6Æ72åU%2"’bbW'2æ6öçF–ç2‚'6VÆVEW'–çFVçCcSs"’¢76W'DfÇ6R‡W'2æ6öçF–ç2‚'&V6÷&DfFtæDvWD–çFVçCcS32‚"’¢76W'EG'VR‡W'4’æ6öçF–ç2‚&W†V7WF–öä–çFVçCcScRó¢v†Vâ‡W'4FÖ—76–öãcScR’"’¢76W'EG'VR‡÷6—F–öç2æ6öçF–ç2‚&gVâW†—DVÆ–v–&–Æ—G“cSs‚"’bbW"æ6öçF–ç2‚&W†—DVÆ–v–&–Æ—G“cSs‡÷6—F–öä–BÂÖ–çBÂW‡V7FVDÖöFRÒÂ'W%Â"’"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚&W†—DVÆ–v–&–Æ—G“cSs‚"’bbÆ—fRæ6öçF–ç2‚&W†—DVÆ–v–&–Æ—G“cSs‚"’¢76W'EG'VR†6öÖÖöF—F–W2æ6öçF–ç2‚&Æ–W%f÷FW5µÂ$6öÖÖöF—F–W57G&FVw•Â%ÒÒF—&V7F–öâ"’bbÖWFÇ2æ6öçF–ç2‚&Æ–W%f÷FW5µÂ$ÖWFÇ57G&FVw•Â%ÒÒF—&V7F–öâ"’¢76W'EG'VR†f÷&W‚æ6öçF–ç2‚$76WD6Æ72ädõ$U‚ÂÂ%$uõ4”täÅÂ""’¢Ð ¢FW7@¢gVâcUóócc5ö6W6ÅöW†V7WF–öåöÖVÖWG&FW%öæEö7'—Fõö†æFöfeö6öçG&7B‚’°¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂÖ&²Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å&–6TÖ&³cS#"æ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂ6†VWBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFööÆ¶—E6–væÅ6†VWBæ·B"’ç&VEFW‡B‚¢fÂ'F–ÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6ÅW%'F–Ä÷W&F–öãcSæ·B"’ç&VEFW‡B‚¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚ ¢76W'EG'VR†vFRæ6öçF–ç2‚$6æöæ–6Äf–æÄFV6—6–öãcc2"’bbvFRæ6öçF–ç2‚'&W6öÇfU6VÆVD–çFVçCcc2"’¢76W'EG'VR†vFRæ6öçF–ç2‚%$U5Dõ$TEôÄÄõuõD”4´UEõt•D„õUEô%U•ôDT4•4”ôâ"’bbvFRæ6öçF–ç2‚%$U5Dõ$TEõD”4´UEôDT4•4”ôåôD•dU$tU5ôe$ôÕõ4TÄTEôdDr"’¢76W'EG'VR†Ö&²æ6öçF–ç2‚'&öÖ÷FTö'6W'fF–öåFôW†V7WF&ÆScc2"’bbÖ&²æ6öçF–ç2‚$4äôä”4ÅôÔ”åEõ4õU$4UôÔ$µócc2"’¢76W'EG'VR†W†V7WF÷"æ6öçF–ç2‚$ÄäUôU„T5õt•D„õUEõ4ÔUôÄäUô4äôä”4Åô”åDTåB"’bbW†V7WF÷"æ6öçF–ç2‚$ÄäUôU„T5õt•D„õUEõ4TÄTEôdDuõ$õdTää4R"’¢76W'EG'VR‡6†VWBæ6öçF–ç2‚$”åDTåEô4„ô´TB"’bb6†VWBæ6öçF–ç2‚$Ô$µô4„ô´TB"’bb6†VWBæ6öçF–ç2‚$U„T5ô4„ô´TB"’bb6†VWBæ6öçF–ç2‚$ÄT$ä”äuô4„ô´TB"’¢76W'DfÇ6R‡6†VWBæ6öçF–ç2‚""%DTÄTÔUE%•ôôäÅ’"""’¢76W'EG'VR‡'F–Âæ6öçF–ç2‚%F–W%7FFScc2"’bb'F–Âæ6öçF–ç2‚%TåD•E•õ$U4U%dTB"’bb'F–Âæ6öçF–ç2‚$44õTåDTB"’bb'F–Âæ6öçF–ç2‚$4ôÕÄUDR"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚$ÄT$äTEõôÄ”5•ôäTtD•dUôÄäUõt•Eõ4„TEócc2"’bb&÷Bæ6öçF–ç2‚%D5D”5õ$õDDTEõtTµõt•Eõ4„TEócc2"’¢76W'DfÇ6R†&÷Bæ6öçF–ç2‚""&&Æö6µ&V6öâÒ$ÄT$äTEõôÄ”5•õdUDõócS“2""""’¢fÂ6æF–FFU7F×Ò7'—Fòæ–æFW„öb‚""$76WD6Æ72ä5%•DõôÅBÂ$4äD”DDR""""¢fÂ6æöæ–6Å7V&Ö—BÒ7'—Fòæ–æFW„öb‚$6æöæ–6ÄVçG'”WF†÷&—G“cSSç7V&Ö—B"Â6æF–FFU7F×¢76W'EG'VR†6æF–FFU7F×ãÒbb6æöæ–6Å7V&Ö—Bâ6æF–FFU7F×¢76W'EG'VR†7'—Fòæ6öçF–ç2‚$5%•DõôÄT$äTEõ4•¤UôdÄôõ$TEôäôå¤U$õócc2"’bb7'—Fòæ6öçF–ç2‚'FW&Ö–æÄF—7÷6—F–öãcc2"’¢Ð  ¢FW7@¢gVâcUóóccEöÖVÖU÷7V6–Æ—7Eö÷væW%öfFuöÖ&µöæE÷F–6¶WEö–FVçF—G•ö—5ö–Ö×WF&ÆR‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂ6ö÷&F–æF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôÆæTW†V7WF–öä6ö÷&F–æF÷"æ·B"’ç&VEFW‡B‚¢fÂWF‚Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõG&FTWF†÷&—¦W"æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢fÂÖ&·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å&–6TÖ&³cS#"æ·B"’ç&VEFW‡B‚¢fÂ6†VWBÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRõFööÆ¶—E6–væÅ6†VWBæ·B"’ç&VEFW‡B‚¢76W'EG'VR†&÷Bæ6öçF–ç2‚'&öÆTf—E&–Ö'“ccB"’bb&÷Bæ6öçF–ç2‚'7G&öævW7E&öÆSccB"’bb&÷Bæ6öçF–ç2‚&Vç6VÖ&ÆT6÷&Tf—CccB"’¢76W'EG'VR†6ö÷&F–æF÷"æ6öçF–ç2‚$Fòæ÷B&RÖVÆV7B—B†W&RW6–ær7FF–2"’bb6ö÷&F–æF÷"æ6öçF–ç2‚%E$T5U%•ôDTdU%õ5T4”Ä•5Eôd•%5B"’¢76W'EG'VR†WF‚æ6öçF–ç2‚$W†V7WF–öä&öö²ä44„tTâ"’bbWF‚æ6öçF–ç2‚$W†V7WF–öä&öö²å$ô¤T5Eõ4ä•U""’¢76W'EG'VR†&÷Bæ6öçF–ç2‚%5T4”Ä•5Eô”åDTåEõt•D„õUEôdDuôõUD4ôÔR"’bb&÷Bæ6öçF–ç2‚'7V6–Æ—7D6W6Ä–CccB"’¢76W'EG'VR†Ö&·2æ6öçF–ç2‚'&Vg&W6„g&öÔW†V7WF&ÆUFö¶VäÖccB"’bbÖ&·2æ6öçF–ç2‚%Dô´TåôÔõ$õUDUôäõEôU„T5UD$ÄR"’¢76W'EG'VR†vFRæ6öçF–ç2‚&Ö&´–CccB"’bbvFRæ6öçF–ç2‚&Ö&µfW'6–öãccB"’bbvFRæ6öçF–ç2‚'6VÆVE&÷fVææ6SccB"’¢76W'EG'VR†vFRæ6öçF–ç2‚%D”4´UEõ$Te$U4…ôUD„õ$•E•ôd”ÅU$R"’bbvFRæ6öçF–ç2‚$U…•$TEõD”4´UEôT4ôäôÔ”5õ$T¤T5EóccB"’¢76W'EG'VR‡6†VWBæ6öçF–ç2‚&fFtÆÆ÷r²fFt&Æö6²ÓÒÂ"’bb6†VWBæ6öçF–ç2‚%5T4”Ä•5Eô”åDTåEõt•D„õUEôdDuôõUD4ôÔSÒ"’¢Ð  ¢FW7@¢gVâcUóóccUöÆö÷÷7F'fF–öå÷v÷&µö—5÷6–ævÆUöfÆ–v‡EöæEövVæW&F–öåö÷væVB‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂv÷&¶W"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÖ–çFVææ6Uv÷&¶W#cCC‚æ·B"’ç&VEFW‡B‚¢fÂ6VçF–æVÂÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÖ&¶WDFF&÷fVææ6ScCsæ·B"’ç&VEFW‡B‚¢fÂÖ&·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ôÖ&´WF†÷&—G”–çFVw&—G”vFScC“bæ·B"’ç&VEFW‡B‚¢fÂ&Vv—7G'’Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ôG–æÖ–4ÇEFö¶Vå&Vv—7G'’æ·B"’ç&VEFW‡B‚¢fÂ7'—FòÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷B÷W'2ô7'—FôÇEG&FW"æ·B"’ç&VEFW‡B‚¢fÂvFRÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF&ÆT÷VävFRæ·B"’ç&VEFW‡B‚¢76W'EG'VR‡v÷&¶W"æ6öçF–ç2‚''Vææ–ætæÖW3ccRæFB†æÖR’"’bbv÷&¶W"æ6öçF–ç2‚''Vææ–ætæÖW3ccRç&VÖ÷fR†æÖR’"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚'&WVW7D†÷EvF6†Æ—7E&V&Ææ6SccR"’bb&÷Bæ6öçF–ç2‚&†÷E÷vF6†Æ—7E÷&V&Ææ6UóccR"’b`¢&÷Bæ6öçF–ç2‚&6æöæ–6Åö7–6ÆUöVæEóccR"’bb&÷Bæ6öçF–ç2‚'fÂÖ„&F6„Ö–ÆÆ—2ÒuóSÂ"’¢76W'EG'VR‡6VçF–æVÂæ6öçF–ç2‚%6VçF–æVÅ7FFSccR"’bb6VçF–æVÂæ6öçF–ç2‚$Ô$´UEôDDõ4TåD”äTÅô4ôÄU44TEóccR"’¢76W'EG'VR†Ö&·2æ6öçF–ç2‚$Ö&µ7FFSccR"’bbÖ&·2æ6öçF–ç2‚%U%ôÔ$µõTä4„ätTEô4ôÄU44TEóccR"’¢76W'EG'VR‡&Vv—7G'’æ6öçF–ç2‚&WfÇVF–öävVæW&F–öãccR"’bb&Vv—7G'’æ6öçF–ç2‚&WfÇVF–öä–æfÆ–v‡CccR"’bb&Vv—7G'’æ6öçF–ç2‚$5%•DõôUdÅõ5DÄUô4ôÕÄUD”ôåôE$õTEóccR"’¢76W'EG'VR†7'—Fòæ6öçF–ç2‚%4„$TEô”åDTÄÄ”tTä4Uô$4´Äôuô4ôÄU44TB"’b`¢&VvW‚‚%4„$TEô”åDTÄÄ”tTä4Uô$4´Äôuô4ôÄU44TEõ$UTUTR"’æf–æDÆÂ†7'—Fò’æ6÷VçB‚’ÓÒ¢76W'EG'VR†vFRæ6öçF–ç2‚$U„T5ôõTåô$Äô4´TEôäõôU„T5UD”ôåô”åDTåEóccR"’bbvFRæ6öçF–ç2‚$äõôU„T5UD”ôåô”åDTåB"’¢Ð  ¢FW7@¢gVâcUóócceö6æöæ–6ÅöÖ&µöæE÷7WW'f—6÷%övVæW&F–öåöÆ–fWF–ÖUö&Uö6W6Â‚’°¢fÂ&÷BÒ¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRô&÷E6W'f–6Ræ·B"’ç&VEFW‡B‚¢fÂW†V7WF÷"Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æRôW†V7WF÷"æ·B"’ç&VEFW‡B‚¢fÂÖ&·2Ò¦fæ–òäf–ÆR‚'7&2öÖ–âö¶÷FÆ–âö6öÒöÆ–fV7–6ÆV&÷BöVæv–æR÷G'WF‚ô6æöæ–6Å&–6TÖ&³cS#"æ·B"’ç&VEFW‡B‚¢76W'EG'VR†Ö&·2æ6öçF–ç2‚'&W6öÇfTW†V7WF&ÆTg&öÕ6÷W&6TWf–FVæ6Sccb"’b`¢Ö&·2æ6öçF–ç2‚%4õU$4Uô$4Uô”DTåD•E•ôÔ•4ÔD4‚"’bbÖ&·2æ6öçF–ç2‚%4õU$4UôUd”DTä4Uõ5DÄR"’b`¢Ö&·2æ6öçF–ç2‚'&WGW&â&öÖ÷FTö'6W'fF–öåFôW†V7WF&ÆScc2†Ö–çBÂæ÷t×2’"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚'&W6öÇfTW†V7WF&ÆTg&öÕ6÷W&6TWf–FVæ6Sccb"’b`¢W†V7WF÷"æ6öçF–ç2‚'&W6öÇfTW†V7WF&ÆTg&öÕ6÷W&6TWf–FVæ6Sccb"’¢76W'EG'VR†&÷Bæ6öçF–ç2‚'7F'FVDÖöæ÷Föæ–4×2"’bb&÷Bæ6öçF–ç2‚%7—7FVÔ6Æö6²æVÆ6VE&VÇF–ÖR‚’"’b`¢&÷Bæ6öçF–ç2‚'6–æ6U&öw&W72ãÒ5UU%d•4õ%ôÄT4Uõ$ôu$U55õEDÅôÕ2"’b`¢&÷Bæ6öçF–ç2‚&¦ö#òæ—47F—fRÓÒG'VR"’bb&÷Bæ6öçF–ç2‚%5UU%d•4õ%ôdõ$4Uõ$TÄT4UôDTdU%$TEõ”õTäuóccb"’¢76W'DfÇ6R†&÷Bæ6öçF–ç2‚%7WW'f—6÷$ÆV6R†Ö–çBÒÖ–çBÂ7F'FVD×2Ò7—7FVÒæ7W'&VçEF–ÖTÖ–ÆÆ—2‚’"’¢Ð §Ð