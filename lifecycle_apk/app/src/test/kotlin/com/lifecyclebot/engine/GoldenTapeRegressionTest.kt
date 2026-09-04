Warning: truncated output (original token count: 231634)
Total output lines: 9149

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
        assertTrue(bot.contains("val expressFinalSize = expressFdg?.sizeSol") && bot.contains("?: expressSignal.positionSizeSol.coerceAtLeast(0.01)"))
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
        assertTrue("Pipeline block must be core-only so learning/tuning is not duplicated", hub.contains("PIPELINE HEALTH — CORE") && !hub.contains("PIPELINE HEALTH — CONDENSED", ignoreCase = false))
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
        assertFalse("SCORE_TOO_LOW must not become FDG green tiny probe", bot.contains("V3 REJECT→PROBE"))
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
 …181634 tokens truncated…in/com/lifecyclebot/engine/lab/LlmLabTrader.kt").readText()
        val slot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SlotHealthGate.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue("6490 resolver must preserve the executable floor only when fee-aware capital and lane cap can fund it",
            resolver.contains("authorityCapLamports" + "6498") && resolver.contains("PAPER_ENTRY_FEE_RESERVE_RATE_6490") &&
                resolver.contains("CAPITAL_BELOW_MIN_EXECUTABLE_6490") && !resolver.contains("minimumFundable6490"))
        assertTrue("6491 precheck must not authorize before canonical size resolution",
            openGate.contains("EXEC_OPEN_PRECHECK_SIZE_PENDING_6491") &&
                openGate.contains("EXEC_OPEN_BLOCKED_SIZE_NOT_EXECUTABLE_6491") &&
                !openGate.contains("EXEC_TICKET_DEFERRED_UNTIL_SIZE_RESOLVED_6490") &&
                // V5.0.6497 §1 — resolvedSizeSol is now sourced from
                // effectiveResolvedSize6497 (fold of preResolvedSizeSol6490
                // and the SealedOrderSizeAuthority6497 seal). Either name
                // proves the ticket-publish site consumes the canonical
                // resolved size (never a manufactured 0.0 fallback).
                (openGate.contains("resolvedSize = effectiveResolvedSize6497.coerceAtLeast(0.0)") &&
                    openGate.contains("fdgIntent6519.copy(")) &&
                executor.contains("PRE_TICKET_SIZE_RESOLUTION_FAILED_6490"))
        assertTrue("6490 duplicate position creation must be blocked at canonical mutation authority",
            canonical.contains("CANONICAL_SAME_MODE_MINT_OPEN_REJECTED_6490") &&
                canonical.contains("it.mode == canonicalMode6490") && canonical.contains("it.mint == mint"))
        assertTrue("6490 historical duplicate paper debits must refund basis without learning contamination",
            tx.contains("refundDuplicateActiveMintLots6490") && tx.contains("DUPLICATE_SAME_MINT_REFUND_6490") &&
                terminal.contains("suppressLearningFanout") && terminal.contains("INVENTORY_CORRECTION_LEARNING_SUPPRESSED_6490"))
        assertTrue("6490 LAB hypotheses must coalesce per mint and remain outside canonical journal inventory",
            lab.contains("LAB_SAME_MINT_HYPOTHESIS_COALESCED_6490") && lab.contains("LAB_SANDBOX_OPEN_ISOLATED_6490") &&
                !lab.contains("V3JournalRecorder.recordOpen") && !lab.contains("V3JournalRecorder.recordClose"))
        assertTrue("6490 slot and report counts must read canonical current-mode active mints",
            slot.contains("activeMintProjections6490(\"paper\")") && report.contains("Canonical active mints (current mode):") &&
                report.contains("LAB sandbox projection:"))
    }



    @Test
    fun V5_0_6491_size_before_allow_primary_lane_fanout_and_exact_acceptance_diagnostics() {
        val resolver = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()
        val invariant = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolverInvariant6468.kt").readText()
        val openGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val permit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val probability = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        val acceptance = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/AcceptanceInvariantAudit6441.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

        assertTrue("6491 sizing boundary must compare integer lamports, including exact equality",
            resolver.contains("SOL_LAMPORTS_6491") && resolver.contains("toLamports6491") &&
                resolver.contains("boundedExecutableLamports6498 >= minExecLamports6491") &&
                resolver.contains("OK_MIN_PROMOTED_6600") && invariant.contains("Cash and lane cap are"))
        val sizePrecheck = openGate.indexOf("EXEC_OPEN_PRECHECK_SIZE_PENDING_6491")
        val mintClaim = openGate.indexOf("executableBuyClaim6487.putIfAbsent")
        val allowed = openGate.indexOf("ForensicLogger.lifecycle(" + '"' + "EXEC_OPEN_ALLOWED" + '"')
        assertTrue("6491 unresolved size must return before mint claim and EXEC_OPEN_ALLOWED",
            sizePrecheck >= 0 && mintClaim > sizePrecheck && allowed > mintClaim &&
                permit.contains("sizeFinalityTicketPresent6491") && permit.contains("preResolvedSizeSol6490 = sizeSol"))
        assertTrue("6599 supersedes 6533 rescue fanout with trunk plus one qualified canonical specialist primary",
            bot.contains("ExecutionAuthorityPolicy6533.isTrunkLane(l)") &&
                bot.contains("boundedRescue6600") && bot.contains("specialistEvaluationAllowed6600") &&
                bot.contains("claimedOwner6600") && !bot.contains("strongestDesk6599") && !bot.contains("LANE_READ_ONLY_NON_PRIMARY_6491"))
        assertTrue("6491 toxic SHITCOIN/TREASURY shaping must use learned lane-local entry floors, not global pauses",
            probability.contains("learnedEntryFloorDelta6491") && probability.contains("lane == " + '"' + "SHITCOIN" + '"') &&
                probability.contains("lane == " + '"' + "TREASURY" + '"') && bot.contains("LANE_LOCAL_LEARNED_FLOOR_READ_ONLY_6491"))
        assertTrue("6491 acceptance failures must expose exact failed invariants and observed count",
            acceptance.contains("invariants=") && acceptance.contains("expected=all_invariants_pass") &&
                acceptance.contains("failedInvariants=") && report.contains("executable fan-out/intake"))
    }


    @Test
    fun V5_0_6492_canonical_inventory_marks_tokenmap_partial_and_marketcap_truth() {
        val position = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val lot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalLotQuantity6464.kt").readText()
        val capital = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalCapitalAuthority6450.kt").readText()
        val tokenMap = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val dex = java.io.File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/lab/LlmLabTrader.kt").readText()

        assertTrue("6492 replay carry must rebuild canonical position and funded lot projections",
            position.contains("CANONICAL_CARRY_POSITION_RESTORED_" + "6492") &&
                position.contains("POSITION_STATE_PROJECTED_FROM_CANONICAL_" + "6492") &&
                lot.contains("CANONICAL_CARRY_LOT_RESTORED_" + "6492"))
        assertTrue("6492 missing quote must retain last-good mark or basis, never zero-value open inventory",
            capital.contains("lastGoodMark" + "6492") && capital.contains("CAPITAL_STALE_LAST_GOOD_MARK_" + "6492") &&
                capital.contains("CAPITAL_MARK_FALLBACK_NO_CANON_POSITION_" + "6492"))
        assertTrue("6492 TokenMap must publish shared mint result and retry pending maps on short TTL",
            tokenMap.contains("canonicalResultByMint" + "6492") && tokenMap.contains("PENDING_RESULT_RETRY_MS_" + "6492") &&
                tokenMap.contains("TOKEN_MAP_SHARED_RESULT_HIT_" + "6492") && executor.contains("PAPER_BUY_DEFERRED_TOKEN_MAP_RETRY_" + "6492"))
        assertTrue("6492 terminal paper sell must use canonical remaining raw qty, decimals and basis",
            executor.contains("terminalRemainingRaw" + "6492") && executor.contains("terminalRemainingCost" + "6492") &&
                executor.contains("val soldQtyRaw6474 = terminalRemainingRaw" + "6492"))
        assertTrue("6492 DexScreener must never alias FDV into marketCap",
            dex.contains("marketCap   = p.optDouble(" + '"' + "marketCap" + '"' + ", 0.0)") &&
                !dex.contains("if (it == 0.0) p.optDouble"))
        assertTrue("6492 market cap requires provenance before BLUECHIP or learning",
            registry.contains("hasTrustedMarketCap" + "6492") && registry.contains("mcapSource") &&
                crypto.contains("CRYPTO_MCAP_UNTRUSTED_DROPPED_" + "6492") && crypto.contains("no_bluechip_no_learning"))
        assertTrue("6492 report must expose paper/live/current canonical inventory independently",
            report.contains("Canonical PAPER active mints:") && report.contains("Canonical LIVE active mints:") &&
                report.contains("Canonical active mints (current mode):"))
        assertTrue("6490 LAB hypothesis positions must stay outside canonical TradeHistoryStore",
            lab.contains("LAB_SANDBOX_OPEN_ISOLATED_" + "6490") && lab.contains("LAB_SANDBOX_CLOSE_ISOLATED_" + "6490"))
    }


    @Test
    fun V5_0_6493_token_identity_is_mint_never_symbol() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val resolver = java.io.File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoUniverseRouteResolver.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoUniverseExecutor.kt").readText()
        val trader = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val reconcile = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val watchlist = java.io.File("src/main/kotlin/com/lifecyclebot/perps/WatchlistEngine.kt").readText()
        val price = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PriceAggregator.kt").readText()
        val scorer = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsUnifiedScorerBridge.kt").readText()
        val ui = java.io.File("src/main/kotlin/com/lifecyclebot/ui/CryptoAltActivity.kt").readText()
        val dex = java.io.File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val candidate = java.io.File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoFinalBuyCandidate.kt").readText()

        assertTrue("6493 registry must index multiple canonical IDs per display symbol and reject ambiguous execution",
            registry.contains("symbolCandidates" + "6493") && registry.contains("getUniqueExecutableTokenBySymbol" + "6493") &&
                registry.contains("CRYPTO_EXEC_MINT_AMBIGUOUS_REJECTED_" + "6493"))
        assertTrue("6493 CoinGecko trending and Jupiter discovery must never join by symbol",
            registry.contains("val mint     = " + '"' + "cg:" + '$' + "{tok.id}" + '"') &&
                registry.contains("NEVER migrate CoinGecko data onto a Jupiter") &&
                !registry.contains("val cgKey = symbolIndex[symbol]"))
        assertTrue("6493 dynamic route and executor must carry explicit symbol plus canonical mint",
            resolver.contains("assetSymbol" + "6493") && resolver.contains("targetMint" + "6493") &&
                resolver.contains("no symbol fallback allowed") && executor.contains("targetMint" + "6493"))
        assertTrue("6493 trader canonical key, live handoff and learning must use dynMint/canonical identity",
            trader.contains("signal.dynMint?.trim()") && trader.contains("targetMint6493 = signal.dynMint") &&
                trader.contains("canonicalAssetId6493 = position.canonicalAssetKey") &&
                trader.contains("canonicalAssetId6493 = pos.dynMint ?: pos.canonicalAssetKey"))
        assertTrue("6493 wallet reconciliation and price hydration must never resolve an arbitrary ticker owner",
            reconcile.contains("if (p.dynMint != null) p.dynMint") &&
                price.contains("getUniqueExecutableTokenBySymbol6493(symbol)"))
        assertTrue("6493 watchlist and UI must persist/load crypto by canonical asset ID",
            watchlist.contains("val assetId:") && watchlist.contains("legacy-symbol:") &&
                watchlist.contains("getTokenByMint(item.assetId)") && ui.contains("pos.dynMint?.let") && ui.contains("getTokenByMint(it)"))
        assertTrue("6493 scorer entry/close keys must accept canonical asset identity",
            scorer.contains("canonicalAssetId6493") && scorer.contains("?: makeMintKey(assetClass, symbol)"))
        assertTrue("6493 Dex data must require exact requested base mint before copying token economics",
            dex.contains("baseAddress.equals(tokenAddress, ignoreCase = chainId != " + '"' + "solana" + '"' + ")") &&
                dex.contains("val baseAddress = row.optJSONObject(" + '"' + "baseToken" + '"' + ")?.optString(" + '"' + "address" + '"' + ", " + '"' + '"' + ")"))
        assertTrue("6493 dynamic AI and candidate economics must be address keyed with explicit provenance",
            trader.contains("ShitCoinTraderAI.hasPosition(tok.mint)") &&
                trader.contains("mint              = tok.mint") &&
                trader.contains("val exactMetrics6493 = exactAssetMetrics6493(signal)") &&
                trader.contains("market = PerpsMarket.DYN") &&
                !trader.contains("val enumMkt = PerpsMarket.values().find { it.symbol == tok.symbol }") &&
                candidate.contains("marketCapSource6493"))
        assertTrue("6493 must not fabricate token liquidity from bulk volume or trust legacy generic Dex caps",
            !trader.contains("vol * 0.10") && !registry.contains("DEXSCREENER_MARKET_CAP") &&
                registry.contains("DEXSCREENER_BASE_MINT_MARKET_CAP"))
    }


    @Test
    fun V5_0_6494_immutable_lane_election_ticket_and_pre_fdg_occupancy() {
        val coordinator = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()
        val permit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        assertTrue(coordinator.contains("val electionId: String") && coordinator.contains("val authorityVersion: Long"))
        assertTrue(coordinator.contains("lanes = listOf(laneUpper)") &&
            coordinator.contains("preferred = laneUpper") &&
            coordinator.contains("val allowed = e.primaryLane == laneUpper") &&
            coordinator.contains("e.copy(sealed = true)"))
        assertTrue(auth.contains("electedLane6494 = laneElection.primaryLane") && auth.contains("electionId6494 = laneElection.electionId"))
        assertFalse("permit must not independently re-elect after authorization", permit.contains("LaneExecutionCoordinator.canRequestExecution(mint, layer)"))
        assertTrue(permit.contains("IMMUTABLE_EXEC_TICKET_MISSING_6494") && permit.contains("IMMUTABLE_ELECTION_LANE_MISMATCH_6494"))
        assertTrue(gate.contains("isRealExecutionLane(receiptLane6494) -> receiptLane6494") &&
            gate.contains("canonicalLane = canonicalLane6519") && gate.contains("fdgIntent6519.copy("))
        assertTrue(bot.contains("PRE_FDG_CANON_MINT_OCCUPIED_SUPPRESSED_6494") && bot.contains("executionBookForLane6494(cyclePrimaryLane)"))
        assertFalse("lane handoff must not replace auth receipt with mutable recent lookup", bot.contains("val treasuryAttemptId = ExecutableOpenGate.recentAllowedAttemptId"))
    }


    @Test
    fun V5_0_6495_quarantines_impossible_finalized_economics_and_provider_bypasses() {
        val finalBus = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        val tactic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val expectancy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ScoreExpectancyTracker.kt").readText()
        val dex = java.io.File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val circuit = java.io.File("src/main/kotlin/com/lifecyclebot/network/HostCircuitInterceptor.kt").readText()

        assertTrue(finalBus.contains("CANONICAL_FINALIZED_ECONOMICS_QUARANTINED_6495"))
        assertTrue(finalBus.contains("IMPLIED_PROCEEDS_ABOVE_5000_SOL") && finalBus.contains("RETURN_FRACTION_PCT_MISMATCH"))
        assertTrue(tactic.contains("TacticSwitcher.onCanonicalTradeClosed6486") && tactic.contains("persisted_economics_quarantined_6495"))
        assertTrue(tactic.contains("TradeHistoryStore.isValidAccountingTrade(it)"))
        assertTrue(expectancy.contains("SCORE_EXPECTANCY_PERSISTED_ECONOMICS_QUARANTINED_6495"))
        assertTrue(dex.contains("never bypass HealthAwareHttp/ApiBackoff with a raw retry"))
        assertFalse("DexScreener must not retry raw after the health wrapper", dex.contains("http.newCall(req).execute()"))
        assertTrue(circuit.contains("ApiBackoff shared-client lockout") && circuit.contains("response.code == 403"))
    }


    @Test
    fun V5_0_6498_paper_terminal_state_qty_parity_and_sizing_are_canonical() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        val boundary = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/SellQtyBoundaryClamp6427.kt").readText()
        val parity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/PositionRegistryParityAudit6464.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/engine/EmergentGuardrails.kt").readText()
        val sizing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()
        val groq = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GroqRouteConfig6498.kt").readText()
        val paperTx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()

        assertTrue(executor.contains("PositionStateLedger6454.onEntry(pid6485)") && executor.contains("syncAuthoritativeRaw(pid6485"))
        assertTrue(executor.indexOf("canonicalPaperSellCommitted6474 = close6474.applied") in 1 until executor.lastIndexOf("PaperTerminalProjectionConvergence6509.converge"))
        assertTrue(paperTx.contains("PositionStateLedger6454.onEntry(positionId)") && paperTx.contains("SellQtyBoundaryClamp6427.syncAuthoritativeRaw(positionId"))
        assertTrue(bridge.contains("admitRaw(positionId, soldQtyRaw") && bridge.contains("commitRaw(positionId, soldQtyRaw, terminal)"))
        assertTrue(boundary.contains("SELL_QTY_BOUNDARY_ADMITTED_" + "6498") && boundary.contains("SELL_QTY_BOUNDARY_REJECTED_" + "6498"))
        assertTrue(parity.contains("canonicalStateByMint" + "6498") && parity.contains("c=$" + "expectedState6498"))
        assertTrue(registry.contains("state = p.state") && registry.contains("PARTIALLY_CLOSED"))
        assertTrue(
            "V5.0.6498+6612: laddered must remain the MAX of risk-derived and ladderTarget — accept either `risk` or `nudgedRisk` (bounded contributor merge preserves the doctrine)",
            (sizing.contains("kotlin.math.max(risk, ladderTarget)") || sizing.contains("kotlin.math.max(nudgedRisk, ladderTarget)")) &&
                !sizing.contains("kotlin.math.min(risk, ladderFloor)")
        )
        assertTrue(sizing.contains("authorityCapLamports" + "6498"))
        assertTrue(groq.contains("openai/gpt-oss-20b"))
    }


    @Test
    fun V5_0_6509_source_rooted_quantity_close_and_exec_intent_authorities() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val qty = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperTokenQuantityAuthority6509.kt").readText()
        val mirror = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt").readText()
        val close = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperTerminalProjectionConvergence6509.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()

        assertFalse("6509 recordTrade must never derive sold-token fraction from economic return", executor.contains("entryQtyForJournal * (trade.sol / entryCostForJournal)"))
        assertTrue(executor.contains("canonicalConsumedRaw = rawVerdict6520.normalizedRaw") &&
            !executor.contains("journalSoldRaw(trade.soldQtyToken"))
        assertTrue(qty.contains("expected = (costSol * solUsd) / tokenPriceUsd") && qty.contains("decoded = decode(raw, decimals)"))
        assertTrue(executor.contains("PAPER_BUY_DEFERRED_SOL_USD_MISSING_6509") && !executor.contains("effectiveSol / maxOf(effectivePrice, 1e-12)"))
        assertFalse("6514: unknown PAPER decimals are advisory, never a blocking reason", executor.contains("PAPER_BUY_DEFERRED_DECIMALS_MISSING_" + "6509"))
        assertTrue(executor.contains("paperTokenDecimals6509") && executor.contains("tokenDecimals = paperTokenDecimals6509"))
        assertTrue(mirror.contains("tokenDecimals = tokenDecimals") && !mirror.contains("tokenDecimals = 9,                 // provisional"))
        assertTrue(close.contains("POST_CLOSE_LEDGER_STAMP_FAIL_6509") && close.contains("POST_CLOSE_PAPER_AUTH_FAIL_6509") &&
            close.contains("POST_CLOSE_GUARDRAIL_REMOVE_FAIL_6509") && close.contains("POST_CLOSE_GLOBAL_REGISTRY_FAIL_6509") &&
            close.contains("POST_CLOSE_PORTFOLIO_REMOVE_FAIL_6509"))
        assertTrue(executor.contains("canonicalClosedNoActive") && executor.contains("return SellResult.ALREADY_CLOSED"))
        assertTrue(gate.contains("canonicalExecutableIntent6509") && gate.contains("EXEC_RAW_SIGNAL_DIAGNOSTIC_IGNORED_6509"))
        val canonicalIntent = gate.substring(gate.indexOf("internal fun canonicalExecutableIntent6509"), gate.indexOf("private val states"))
        assertFalse("execution ticket is output, never input to pre-execution FDG authority", canonicalIntent.contains("hasImmutableTicket"))
        assertTrue("raw non-BUY remains diagnostic after canonical FDG authorization", gate.contains("EXEC_RAW_SIGNAL_DIAGNOSTIC_IGNORED_6509"))
    }


    @Test
    fun V5_0_6510_lane_decision_mark_partial_and_incident_authorities_are_source_rooted() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val identity = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeIdentity.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val decision = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionDecisionSnapshot6510.kt").readText()
        val mark = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkAuthorityIntegrityGate6496.kt").readText()
        val partial = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperPartialOperation6510.kt").readText()
        val incident = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/RootCauseIncidentLifecycle6510.kt").readText()
        val freshness = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/RootCauseFreshnessAuthority6496.kt").readText()

        assertTrue(identity.contains("var executionLane: String") && identity.contains("var fdgCandidateVersion: Long"))
        assertFalse("discovery provenance must never resolve execution lane", exec.contains("normalizeExecutionLane(identity?.source)") || exec.contains("normalizeExecutionLane(ts.source)"))
        assertTrue(exec.contains("EXEC_LANE_IDENTITY_INVARIANT_FAILED") && exec.contains("FDG_MUTABLE_SIGNAL_IGNORED_6512"))
        assertTrue(gate.contains("ExecutionDecisionSnapshot6510.record") && decision.contains("byAuthorityKey") && decision.contains("runtimeGeneration") && decision.contains("mode"))
        assertTrue(mark.contains("val priceAuthoritative") && mark.contains("val routeExecutable"))
        assertTrue(partial.contains("""val operationId = """") && partial.contains("positionId") && partial.contains("sequence") && partial.contains("CanonicalPaperTerminalBridge6469.finalizeSell"))
        assertFalse("paper partial operation IDs must not contain wallclock generations", partial.contains("System.currentTimeMillis()}_"))
        assertTrue(incident.contains("enum class State { OPEN, RESOLVED }") && freshness.contains("elapsed time never reactivates lifetime history"))
    }


    @Test
    fun V5_0_6511_paper_pre_ticket_floor_is_independent_and_preserves_valid_shaped_buys() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        val minimumStart = exec.indexOf("private fun minConfiguredPaperTradeSol")
        val minimumEnd = exec.indexOf("private fun clampPaperTradeSol", minimumStart)
        val minimumBlock = exec.substring(minimumStart, minimumEnd)
        assertFalse("ordinary requested smallBuySol must not be executable-floor authority", minimumBlock.contains("c.smallBuySol"))
        assertFalse("wallet-relative requested sizing must not become an executable floor", minimumBlock.contains("paperSimulatedBalance * 0.001"))
        assertTrue(minimumBlock.contains("PaperPreTicketSizeFloor6511.boundedMinimum(runtimeMinimum6511)"))
        assertTrue(exec.contains("ABSOLUTE_EXECUTABLE_FLOOR_SOL = 0.05") && sizer.contains("ECONOMIC_MIN_SIZE_PROMOTED_6555") && sizer.contains("val dustFloor = 0.05"))
        val promote = exec.indexOf("val effectiveRequestedSol6511")
        val bridge = exec.indexOf("TraderSizingBridge6444.resolveForLane", promote)
        val reject = exec.indexOf("PAPER_BUY_REJECTED_BEFORE_TICKET_SIZE_6490", bridge)
        val ticket = exec.indexOf("ExecutableOpenGate.canOpenExecutablePosition", reject)
        val commit = exec.indexOf("V5.0.6485 — ATOMIC PAPER BUY COMMIT", ticket)
        assertTrue(promote >= 0 && promote < bridge && bridge < reject && reject < ticket && ticket < commit)
        assertTrue(exec.contains("val floorPromotionRequested6511 = false") && exec.contains("sealedNotional6552") && exec.contains("PAPER_BUY_REJECTED_BEFORE_TICKET_SIZE_6490"))
        assertFalse("V5.0.6567: reduced adaptive requests must never be inflated by a downstream floor", exec.contains("PAPER_BUY_SIZE_FLOOR_PROMOTED_6511"))
    }


    @Test
    fun V5_0_6554_canonical_entry_uses_open_gate_authority_and_open_direction() {
        val contract = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(contract.contains("registerCanonicalIntent6554") && contract.contains("EXEC_INTENT_REGISTRATION_FAILED"))
        assertTrue(contract.contains("""side = "BUY"""") && contract.contains("""action = "OPEN"""") && contract.contains("direction = if"))
        assertTrue(gate.contains("fun registerCanonicalIntent6554") && gate.contains("activeExecutionIntents6519") && gate.contains("executionTickets"))
        assertTrue(gate.contains("private fun publishFdgIntent6519") && gate.contains("registerCanonicalIntent6554(sizedIntent)"))
        assertTrue(contract.contains("CANONICAL_PENDING_EXPIRED") && contract.contains("markFailed") && contract.contains("markDeferred") && contract.contains("markCancelled"))
    }

    @Test
    fun V5_0_6563_cyclic_is_not_unconditionally_disabled_after_runtime_plan() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("CYCLIC_RUNTIME_ENABLED_6563"))
        assertTrue(bot.contains("val cyclicEnabled6563 = plan6526.paperMode || marketsStartCfg.cyclicTradeEnabled"))
        assertTrue(!bot.contains("CyclicTradeEngine.setEnabled(false)"))
    }

    @Test
    fun V5_0_6562_crypto_paper_learning_reaches_fdg_without_relaxing_live_momentum() {
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(crypto.contains("CRYPTO_PAPER_LEARNING_ADMISSION_6562"))
        assertTrue(crypto.contains("val paperLearningCandidate6562 = isPaperMode.get() && score >= 50 && confidence >= 40"))
        assertTrue(crypto.contains("!paperLearningCandidate6562"))
        assertTrue(crypto.indexOf("paperLearningCandidate6562") < crypto.indexOf("return AltSignal("))
    }

    @Test
    fun V5_0_6561_markets_exit_uses_canonical_paper_close_and_live_finality() {
        val stocks = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(stocks.contains("CanonicalPaperTransaction6486.close") && stocks.contains("closeLivePositionProof6486"))
        assertTrue(crypto.contains("CanonicalPaperTransaction6486.close") && crypto.contains("closeLivePositionProof6486"))
        assertTrue(stocks.contains("if (!canonicalClose6486.applied)"))
        assertTrue(crypto.contains("if (!canonicalClose6486.applied)"))
    }

    @Test
    fun V5_0_6561_markets_specialist_reaches_canonical_fdg_before_open() {
        val stocks = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        assertTrue(stocks.contains("CanonicalEntryAuthority6551.submit"))
        assertTrue(stocks.contains("CanonicalAssetEntryCandidate6551"))
        assertTrue(stocks.contains("marketIntent6561"))
        assertTrue(stocks.contains("CanonicalEntryAuthority6551.markConfirmed"))
        assertTrue(stocks.contains("CanonicalEntryAuthority6551.markFailed"))
        assertTrue(stocks.indexOf("CanonicalEntryAuthority6551.submit") < stocks.indexOf("val position = StockPosition"))
    }

    @Test
    fun V5_0_6560_paper_xstocks_bypass_traditional_hours_but_live_does_not() {
        val stocks = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        assertTrue(stocks.contains("MARKETS_PAPER_24X7_EXECUTION_6560"))
        assertTrue(stocks.contains("if (!isPaperMode.get() && !isStockMarketOpen())"))
        assertTrue(stocks.indexOf("if (!isPaperMode.get() && !isStockMarketOpen())") < stocks.indexOf("MARKETS_PAPER_24X7_EXECUTION_6560"))
    }

    @Test
    fun V5_0_6559_paper_runtime_precedes_stale_live_authority_for_markets_and_crypto() {
        val markets = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(markets.contains("MARKETS_PAPER_RUNTIME_PRECEDENCE_6559") && markets.contains("val paperRuntime6559"))
        assertTrue(crypto.contains("CRYPTO_PAPER_RUNTIME_PRECEDENCE_6559") && crypto.contains("val paperRuntime6559"))
        assertTrue(markets.indexOf("val paperRuntime6559") < markets.indexOf("effectiveSnapshot()"))
        assertTrue(crypto.indexOf("val paperRuntime6559") < crypto.indexOf("effectiveSnapshot()"))
    }

    @Test
    fun V5_0_6558_cross_asset_size_seal_and_perps_sandbox_are_source_rooted() {
        val sizing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalSizingBridge6532.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val perpsEngine = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt").readText()
        val perpsTrader = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsTraderAI.kt").readText()
        val sandbox = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/PerpsSandbox6463.kt").readText()
        assertTrue(sizing.contains("sizing is advisory input, never a pre-FDG"))
        assertTrue(gate.contains("resolvedSizeSol6558") && gate.contains("CROSS_ASSET_LEGACY_SIGNAL_DIVERGENCE_6554") && gate.contains("action=diagnostic_only"))
        assertTrue(crypto.contains("CanonicalEntryAuthority6551.submit") && crypto.contains("canonicalCryptoIntent6565.resolvedSize"))
        assertTrue(perpsEngine.contains("CanonicalEntryAuthority6551.submit") && perpsEngine.contains("sealedPerpIntent6570") && perpsEngine.contains("canonicalPerpsSize6570 = sealedPerpIntent6570.resolvedSizeSol"))
        assertTrue(perpsTrader.contains("PerpsSandbox6463.openLeveragedPaper") && perpsTrader.contains("CanonicalPaperTransaction6486.refund"))
        assertTrue(sandbox.contains("PERPS_EXEC_DISPATCH_6554") && sandbox.contains("PERPS_OPEN_CONFIRMED_6554") && sandbox.contains("PERPS_OPEN_REFUSED_6554"))
    }

    @Test
    fun V5_0_6553_paper_mode_reaches_markets_and_crypto_scanners() {
        val markets = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(markets.contains("!cfg.paperMode") && markets.contains("MARKETS_PAPER_LEARN_EVERYTHING_ADMITTED_6553"))
        assertTrue(crypto.contains("!cfg.paperMode") && crypto.contains("CRYPTO_PAPER_LEARN_EVERYTHING_ADMITTED_6553"))
        assertTrue(crypto.contains("discoveryAgeMinutes6554") && crypto.contains("CRYPTO_DYN_EXPRESS_EXECUTABLE_6554") && crypto.contains("CRYPTO_DYN_MANIP_EXECUTABLE_6554"))
        assertFalse(crypto.substring(crypto.indexOf("private suspend fun runDynamicTokenScan"), crypto.indexOf("private suspend fun runScanCycle")).contains("tokenAgeMinutes   = 9999.0"))
    }

    @Test
    fun V5_0_6512_execution_authority_and_provider_rotation_are_source_rooted() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val decision = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionDecisionSnapshot6510.kt").readText()
        val snapshot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionSnapshotAuthority6496.kt").readText()
        val aggregator = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PriceAggregator.kt").readText()
        val dex = java.io.File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val provider = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProviderAuthority.kt").readText()
        val fabric = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val finalBus = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val consumer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

        val intent = gate.substring(gate.indexOf("internal fun canonicalExecutableIntent6509"), gate.indexOf("private val states"))
        assertFalse(intent.contains("hasImmutableTicket"))
        assertTrue(gate.contains("fdgElectionLocks6512") && gate.contains("rank(old?.preFdgVerdict) >= rank(finalVerdict)"))
        assertTrue(gate.contains("signal = if (keepOld) old?.signal") && gate.contains("selectedLane = if (keepOld) old?.selectedLane"))
        assertTrue(decision.contains("byAuthorityKey") && decision.contains("runtimeGeneration") && decision.contains("candidateVersion") && decision.contains("executionLane"))
        assertFalse(snapshot.contains("add(" + "\"primaryLane("))
        assertTrue(gate.contains("canonicalOccupancy =") && gate.contains("mode.uppercase()}:" + "$" + "mint") && gate.contains("PAPER") && gate.contains("LIVE"))
        assertTrue(exec.contains("FDG_MUTABLE_SIGNAL_IGNORED_6512") && exec.contains("EXEC_AUTHORITY_MISSING_DEFERRED_6512") && exec.contains("releaseIfPrimary"))
        assertFalse(exec.contains("ENTRY_BRIDGE_NON_BUY_GUARD_6504"))
        assertTrue(aggregator.contains("DataSource.DEXPAPRIKA") && aggregator.contains("data-api.binance.vision"))
        assertTrue(dex.contains("fetchDexPaprikaToken6512") && provider.contains("DEXPAPRIKA") && provider.contains("ProviderConfig"))
        assertTrue(fabric.contains("object PolicySynthesizer6512") && fabric.contains("data class AateDecisionEnvelope6512") && fabric.contains("AATE_POLICY") && fabric.contains("AATE_REWARD"))
        assertTrue(fabric.contains("byAttempt") && fabric.contains("byPosition") && fabric.contains("rewardedPositions"))
        assertTrue(finalBus.contains("AatePolicyReward") && consumer.contains("deliverToAatePolicyReward"))
        assertTrue(bot.contains("canonicalExitTokenSnapshot6512") && bot.contains("CanonicalPositionAuthority6441.openPositions()") && bot.contains("CANONICAL_EXIT_FEED_6512"))
    }


    @Test
    fun V5_0_6513_entry_authority_paper_finality_and_exit_marks_are_source_authoritative() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val decision = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionDecisionSnapshot6510.kt").readText()
        val permit = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val mirror = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutorCanonicalMirror6442.kt").readText()
        val idem = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/IdempotencyKeyStore6437.kt").readText()
        val canon = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val tokenMap = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMapAuthority.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val root = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/RootCauseClassifier6471.kt").readText()

        assertTrue(decision.contains("authorityVersion") && decision.contains("authoritativeSignal") && decision.contains("safetyVerdict") && decision.contains("resolvedSizeSol"))
        assertTrue(gate.contains("immutableAuthority6513") && gate.contains("immutableFdgBuy6519"))
        assertTrue(gate.contains("fdgIntent6519.copy(") && gate.contains("authoritativeSignal = \"BUY\"") &&
            gate.contains("fdgVerdict = winner.preFdgVerdict") &&
            gate.contains("winner.preFdgVerdict == \"PROBE_ONLY\"") &&
            gate.contains("CanonicalFinalDecision6613.PROBE_ONLY"))
        assertTrue(gate.contains("AUTHORITY_INVARIANT_FAILURE") && gate.contains("EXEC_AUTHORITY_STATE_MISMATCH"))
        assertTrue(permit.contains("executionTicket6494.primaryLane != executionTicket6494.lane") && permit.contains("executionTicket6494.authoritativeSignal != " + "\"BUY\""))
        assertTrue(exec.contains("ticket6513?.primaryLane") && exec.contains("PAPER_BUY_TERMINAL_REPLAY_RECOVERED_6513"))
        val begin = exec.indexOf("PaperEntryFinalityAuthority6497.beginAttempt(entryFinalityId6497")
        val reserve = exec.indexOf("ExecutorCanonicalMirror6442.mirrorBuyAttempt(", begin)
        val debit = exec.indexOf("PaperAccountLedger6430.onBuy(actualSol, fee6485)", reserve)
        val fill = exec.indexOf("ExecutorCanonicalMirror6442.mirrorBuyFill(", debit)
        val journal = exec.indexOf("recordTrade(ts, trade)", fill)
        val terminal = exec.indexOf("PaperEntryFinalityAuthority6497.markOk(entryFinalityId6497)", journal)
        assertTrue(begin > 0 && reserve > begin && debit > reserve && fill > debit && journal > fill && terminal > journal)
        assertTrue(mirror.contains("buy_attempt:" + "$" + "attemptId") && idem.contains("fun terminalFor"))
        assertTrue(canon.contains("entryPriceUsd") && canon.contains("entryPriceSource") && canon.contains("entryPoolAddress") && canon.contains("entryPriceUsd = repairedPrice6519"))
        assertTrue(tokenMap.contains("cachedForExit6513") && bot.contains("CANONICAL_EXIT_MARK_REFRESH_QUEUED_6513"))
        assertTrue(bot.contains("scope.launch(kotlinx.coroutines.Dispatchers.IO)"))
        assertTrue(root.indexOf("EXEC_AUTHORITY_STATE_MISMATCH") < root.indexOf("DATA_PROVIDER_AUTH_LOCKOUT_6468"))
    }


    @Test
    fun V5_0_6514_paper_ticket_dispatch_ignores_missing_decimals_and_releases_every_nonterminal_authority() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val canonical = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val tx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()

        assertFalse(executor.contains("PAPER_BUY_DEFERRED_DECIMALS_MISSING_" + "6509"))
        // V5.0.6547b — after operator report that V5.0.6547 P0-1 defer stalled
        // every trade, we restored the 6514 advisory-continue path. Both the
        // legacy 6514 counter and the 6547 companion counters must be present.
        assertTrue(executor.contains("PAPER_DECIMALS_PENDING_ADVISORY_" + "6514"))
        assertTrue(
            executor.contains("PAPER_DECIMALS_PENDING_DEFER_" + "6547") &&
                executor.contains("PAPER_TICKET_REQUEUED_" + "6547"),
        )
        assertTrue(executor.contains("PAPER_TICKET_DISPATCHED_" + "6514"))
        assertTrue(executor.contains("PAPER_TICKET_TERMINAL_OPEN_" + "6514"))
        assertTrue(executor.contains("PAPER_TICKET_TERMINAL_BLOCK_" + "6514"))
        assertTrue(gate.contains("PAPER_TICKET_NONTERMINAL_RELEASE_" + "6514"))
        assertTrue(executor.contains("EXEC_LEASE_LEAK_INVARIANT_" + "6514"))
        assertTrue(executor.contains("releasePaperBuyNonTerminal6514(" + "\"" + "SOL_USD_MISSING_" + "6509" + "\"" + ")"))
        assertTrue(executor.contains("action=release_all_authority_retry_next_cycle"))
        assertTrue(executor.contains("FinalExecutionPermit.releaseExecution(ts.mint)"))
        assertTrue(executor.contains("LaneExecutionCoordinator.releaseIfPrimary"))
        assertTrue(gate.contains("releaseAttemptNonTerminal" + "6514"))
        assertTrue(gate.contains("executionTickets.remove(attemptId)"))
        assertTrue(gate.contains("executableBuyClaim6487.entries.removeIf"))
        assertTrue(canonical.contains("val quantityScale: Int = tokenDecimals"))
        assertTrue(tx.contains("quantityScale: Int = decimals"))
        assertTrue(executor.contains("quantityScale = paperQuantityScale" + "6514"))
        val modeBranch = executor.indexOf("if (isPaperMode) {")
        val paperDispatch = executor.indexOf("paperBuy(ts, effSol", modeBranch)
        val liveBranch = executor.indexOf("} else if (wallet == null) {", modeBranch)
        assertTrue("PAPER must dispatch before LIVE-only executor validation", modeBranch > 0 && paperDispatch > modeBranch && liveBranch > paperDispatch)
        val journal = executor.indexOf("recordTrade(ts, trade)", executor.indexOf("fun paperBuy("))
        val terminal = executor.indexOf("PAPER_TICKET_TERMINAL_OPEN_" + "6514", executor.indexOf("fun paperBuy("))
        assertTrue("real BUY journal must be on the paper open path", journal > 0 && terminal > 0)
    }


    @Test
    fun V5_0_6515_canonical_bootstrap_is_off_main_and_hard_barriers_execution() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val onCreate = bot.indexOf("override fun onCreate()")
        val foreground = bot.indexOf("startForeground(NOTIF_ID", onCreate)
        val canonicalLaunch = bot.indexOf("canonicalBootstrapJob" + "6515 = scope.launch", onCreate)
        val eventLoad = bot.indexOf("EconomicEventSchema" + "6464.init6486", canonicalLaunch)
        val serviceLaunch = bot.indexOf("serviceBootstrapJob" + "6516 = scope.launch", eventLoad)
        val start = bot.indexOf("fun startBot()")
        val gate = bot.indexOf("deferStartUntilServiceReady" + "6516()", start)
        assertTrue("foreground service must precede every durable bootstrap", foreground > onCreate && canonicalLaunch > foreground)
        assertTrue("durable event load must execute inside IO canonical bootstrap", eventLoad > canonicalLaunch)
        assertTrue("complete service bootstrap must wait behind canonical replay", serviceLaunch > eventLoad)
        assertTrue("every startBot path must hit the complete service-ready gate first", gate > start && gate < bot.indexOf("isShuttingDown = false", start))
        assertTrue(bot.contains("canonicalBootstrapJob" + "6515?.join()"))
        assertTrue(bot.contains("val bootstrap = serviceBootstrapJob" + "6516"))
        assertTrue(bot.contains("START_DEFERRED_SERVICE_BOOTSTRAP_" + "6516"))
        assertTrue(bot.contains("START_BLOCKED_SERVICE_BOOTSTRAP_FAILED_" + "6516"))
    }

    @Test
    fun V5_0_6516_complete_persisted_state_startup_family_is_off_main() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val onCreate = bot.indexOf("override fun onCreate()")
        val serviceLaunch = bot.indexOf("serviceBootstrapJob" + "6516 = scope.launch", onCreate)
        val onStart = bot.indexOf("override fun onStartCommand", serviceLaunch)
        val inlinePrefix = bot.substring(onCreate, serviceLaunch)
        val ioBody = bot.substring(serviceLaunch, onStart)
        assertFalse(inlinePrefix.contains("FeeRetryQueue.init("))
        assertFalse(inlinePrefix.contains("FeeAccumulator.init("))
        assertFalse(inlinePrefix.contains("ScannerHardRejectStore.init("))
        assertFalse(inlinePrefix.contains("TradeHistoryStore.init("))
        assertFalse(inlinePrefix.contains("LearningPersistence.init("))
        assertFalse(inlinePrefix.contains("PositionPersistence.init("))
        assertFalse(inlinePrefix.contains("PerpsTraderAI.init("))
        assertFalse(inlinePrefix.contains("TokenizedStockTrader.start("))
        assertFalse(inlinePrefix.contains("CryptoAltTrader.start("))
        assertTrue(ioBody.contains("FeeRetryQueue.init(applicationContext)"))
        assertTrue(ioBody.contains("TradeHistoryStore.init(applicationContext)"))
        assertTrue(ioBody.contains("LearningPersistence.init(applicationContext)"))
        assertTrue(ioBody.contains("PositionPersistence.init(applicationContext)"))
        assertTrue(ioBody.contains("SERVICE_BOOTSTRAP_READY_" + "6516"))
        assertTrue(ioBody.contains("SERVICE_BOOTSTRAP_FAILED_" + "6516"))
    }


    @Test
    fun V5_0_6516a_runtime_smoke_replays_max_persisted_history_and_requires_live_start() {
        val smoke = java.io.File("../../ci/runtime-test.sh").readText()
        assertTrue(smoke.contains("canonical_economic_events_" + "6486.xml"))
        assertTrue(smoke.contains("range(4096)"))
        assertTrue(smoke.contains("seeded_events=8192"))
        assertTrue(smoke.contains("SEED_COUNT") && smoke.contains("= " + "\"8192\""))
        assertTrue(smoke.contains("CANONICAL_BOOTSTRAP_READY_" + "6515"))
        assertTrue(smoke.contains("SERVICE_BOOTSTRAP_READY_" + "6516"))
        assertTrue(smoke.contains("pidof com.lifecyclebot.aate"))
        assertTrue(smoke.contains("ANR in com.lifecyclebot.aate"))
        assertTrue(smoke.contains("Process: com.lifecyclebot.aate"))
        assertTrue(smoke.contains("FN_LOOP") && smoke.contains("Persisted UI Start/Stop PASS"))
    }


    @Test
    fun V5_0_6517_start_stop_is_immediate_visible_durable_and_cancellable() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        val vm = java.io.File("src/main/kotlin/com/lifecyclebot/ui/BotViewModel.kt").readText()
        val view = main.indexOf("btnToggle       = findViewById(R.id.btnToggle)")
        val immediateBind = main.indexOf("bindRuntimeToggleListener" + "6517()", view)
        val renderer = main.indexOf("private fun renderRuntimeBar(")
        assertTrue(view > 0 && immediateBind > view && immediateBind < renderer)
        assertFalse(main.contains("btnToggle.isEnabled = false"))
        assertTrue(main.contains("UI_RUNTIME_TOGGLE_TAP_" + "6517"))
        assertTrue(main.contains("Cancel Start") && main.contains("START FAILED ·"))
        assertTrue(bot.contains("serviceStartRequested" + "6517.set(true)"))
        assertTrue(bot.contains("START_REQUEST_RETAINED_DURING_BOOTSTRAP_" + "6517"))
        assertTrue(bot.contains("SERVICE_BOOTSTRAP_JOB_MISSING_" + "6517"))
        assertFalse(bot.contains("while (!serviceBootstrapReady" + "6516)"))
        assertTrue(bot.contains("DEFERRED_START_CANCELLED_BY_STOP_" + "6517"))
        assertTrue(vm.contains("UI_START_DISPATCHED_" + "6517"))
        assertTrue(vm.contains("UI_START_FALLBACK_DISPATCHED_" + "6517"))
        val smoke = java.io.File("../../ci/runtime-test.sh").readText()
        val receiver = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmokeTestReceiver.kt").readText()
        assertTrue(receiver.contains("start_service") && receiver.contains("SMOKE_UI_SETUP_ONLY_" + "6517"))
        assertTrue("debug smoke setup must hit disk before force-stopping its auth task", receiver.contains(".commit()") && smoke.contains("am force-stop com.lifecyclebot.aate"))
        assertTrue(smoke.contains("--ez start_service false"))
        assertTrue(smoke.contains("ui_tap id btnToggle ui_start_1.xml"))
        assertTrue(smoke.contains("ui_tap text " + "\"Stop bot\""))
        assertTrue(smoke.contains("ui_tap id btnToggle ui_start_2.xml"))
        assertTrue(smoke.contains("FN_UI_TAP") && smoke.contains("FN_UI_START") && smoke.contains("FN_UI_STOP"))
    }


    @Test
    fun V5_0_6518_executor_liveBuy_wide_register_arithmetic_is_in_kept_helper() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val helper = executor.indexOf("private fun dnaProvenWinnerSizeBoost" + "6518")
        val live = executor.indexOf("private fun liveBuy(", helper)
        val call = executor.indexOf("dnaProvenWinnerSizeBoost" + "6518(ts, layerTag)", live)
        assertTrue(helper > 0 && live > helper && call > live)
        assertTrue(executor.substring(helper - 100, helper).contains("@androidx.annotation.Keep"))
        val extractedHelper = executor.substring(helper, live)
        assertTrue(extractedHelper.contains("LiveWinDNAStore.setupFrequency"))
        assertTrue(extractedHelper.contains("(avgWin - 20.0) / 100.0"))
        val smoke = java.io.File("../../ci/runtime-test.sh").readText()
        assertTrue(smoke.contains("FN_VERIFY_ERROR"))
        assertTrue(smoke.contains("Verifier rejected"))
    }


    @Test
    fun V5_0_6520_raw_quantity_is_canonical_end_to_end() {
        val model = java.io.File("src/main/kotlin/com/lifecyclebot/data/Models.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val history = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val terminal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalRawQuantityAuthority6520.kt").readText()
        assertTrue(model.contains("val entryRawQty: BigInteger") && model.contains("val canonicalConsumedRaw: BigInteger") && model.contains("val remainingRawQty: BigInteger"))
        assertTrue(executor.contains("paperRawFromEconomics(") && !executor.contains("journalSoldRaw(trade.soldQtyToken"))
        assertTrue(executor.contains("canonicalConsumedRaw = rawVerdict6520.normalizedRaw") && terminal.contains("canonicalConsumedRaw = soldQtyRaw"))
        assertTrue(history.contains("entry_raw_qty TEXT") && history.contains("put(" + "\"canonical_consumed_raw\"" + ", t.canonicalConsumedRaw.toString())"))
        assertTrue(authority.contains("LEGACY_ROUNDING_EPSILON_RAW: BigInteger = BigInteger.ONE") && authority.contains("DECIMAL_SCALE_MISMATCH"))
        assertFalse(authority.contains("BigDecimal(double)"))
    }


    @Test
    fun V5_0_6521_quantity_invariant_repairs_from_canonical_raw_without_force_close() {
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/QuantityInvariantAuthority6500.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val ui = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue(authority.contains("remainingQtyRaw.toBigDecimal().movePointLeft") && authority.contains("reconstructFromCanonical"))
        assertFalse(authority.contains("WalletManager.lastKnownSolPrice"))
        assertFalse(authority.contains("HistoricalEconomicQuarantine6496.reportOrphanLot"))
        assertTrue(bot.contains("QUANTITY_PROJECTION_RECONSTRUCTED_FROM_CANONICAL_RAW_6521") && bot.contains("PositionPersistence.savePosition(ts)"))
        assertFalse(bot.contains("requestSell(ts = ts, reason = " + "\"INVARIANT_QUARANTINE_6500\""))
        assertTrue(ui.contains("QuantityInvariantAuthority6500.check(ts.mint, pos).ok"))
    }


    @Test
    fun V5_0_6522_canonical_quantity_terminal_counts_marks_and_snapshot_contract() {
        val amount = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTokenAmount6522.kt").readText()
        val wallet = java.io.File("src/main/kotlin/com/lifecyclebot/network/SolanaWallet.kt").readText()
        val processor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ProcessorAmountPlanner.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val terminal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/TerminalMutationAuthority6466.kt").readText()
        val paper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        val counts = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeCountAuthority6522.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val marks = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkAuthorityIntegrityGate6496.kt").readText()
        assertTrue(amount.contains("val raw: BigInteger") && amount.contains("fun ui(): BigDecimal"))
        assertTrue(wallet.contains("optString(" + "\"amount\"" + ", " + "\"\"" + ")"))
        assertFalse(wallet.contains("Map<String, Pair<Double, Int>>"))
        assertFalse(processor.contains("BigDecimal(requestedUiQty"))
        assertFalse(executor.contains("val qty = entrySol / entryPrice"))
        assertFalse(executor.contains("val healedQty = (pos.costSol * priceMoveMultiple) / actualPrice"))
        assertTrue(amount.contains("FULL_CLOSE_NOT_EXACT_REMAINDER") && amount.contains("QTY_DECIMAL_SKEW"))
        assertTrue(terminal.contains("$" + "{mode.lowercase()}|$" + "positionId|$" + "generation|$" + "closeType"))
        assertFalse(terminal.contains("$" + "{runId.get()}|"))
        assertTrue(paper.contains("CANONICAL_QTY_$" + "{qtyValidation6522.reason}"))
        assertTrue(counts.contains("sessionCompletedTrades") && counts.contains("lifetimeCompletedTrades") && counts.contains("openTrades"))
        assertTrue(report.contains("val revision6522 = reportRevision6522.incrementAndGet()"))
        assertTrue(report.contains("Session completed trades:") && report.contains("Lifetime completed trades:") && report.contains("Open positions:"))
        assertTrue(marks.contains("val priceValidity") && marks.contains("val liquidityValidity"))
        assertTrue(marks.contains("!poolAddress.startsWith(" + "\"MINT_ROUTE:\""))
    }

    @Test
    fun V5_0_6533_execution_authority_is_causal_bounded_and_cross_universe_safe() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val policy = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutionAuthorityPolicy6533.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val plan = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/TraderRuntimePlan6526.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

        assertTrue(policy.contains("fun isTrunkLane") && policy.contains("fun selectOneRescue"))
        assertTrue(bot.contains("V3_CANONICAL_HANDOFF_PENDING_6533") && bot.contains("recordFdgAndGetIntent6533"))
        assertTrue(bot.contains("FinalDecisionGate.evaluate(") && bot.contains("val v3AttemptId = v3Intent6533.attemptId"))
        assertFalse(bot.contains("V3_CORE_SHADOW_EXECUTE_VISIBILITY_6487"))
        assertTrue(executor.contains("V3_BUY_REJECTED_NO_EXACT_INTENT_6533"))
        val v3BuyArea6533 = executor.substringAfter("fun v3Buy(").substringBefore("\n    fun ")
        assertFalse(v3BuyArea6533.contains("recentAllowedAttemptIdAnyLane(ts.mint)"))
        assertTrue(gate.contains("requiresSolanaTokenMap") && gate.contains("allowTrunkExecutionHandoff6533"))
        assertTrue(gate.contains("intent.fdgVerdict.uppercase() in setOf(") && gate.contains("PROBE_ONLY"))
        assertTrue(crypto.contains("CRYPTO_SHORT_REROUTED_TO_PERP_6533") && crypto.contains("ADAPTER_DIRECTION_UNSUPPORTED"))
        assertFalse(crypto.contains("SPOT_SHORT_UNSUPPORTED"))
        assertTrue(crypto.contains("CanonicalEntryAuthority6551.submit") && crypto.contains("finalExecutableVerdict6647"))
        assertTrue(plan.contains("paperLearnEverything6533") && bot.contains("publish(plan6526.enabledTraderSet())"))
        assertTrue(report.contains("EXECUTION AUTHORITY INVARIANTS 6533") &&
            report.contains("NON_SOLANA_TOKENMAP_HARDNO") && report.contains("EXECUTABLE_FANOUT_PER_CANDIDATE_GT_2"))
    }


    @Test
    fun V5_0_6544_existing_authorities_prove_multichain_discovery_without_parallel_architecture() {
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val dex = java.io.File("src/main/kotlin/com/lifecyclebot/network/DexscreenerApi.kt").readText()
        val resolver = java.io.File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoUniverseRouteResolver.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/perps/crypto/CryptoUniverseExecutor.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val commodities = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CommoditiesTrader.kt").readText()
        val markets = java.io.File("src/main/kotlin/com/lifecyclebot/perps/MarketsLiveExecutor.kt").readText()
        val tokenized = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedAssetRegistry.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val paperExecutor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()

        assertEquals("base|0xabc", com.lifecyclebot.perps.DynamicAltTokenRegistry.canonicalIdentity6544("base", "0xabc"))
        assertEquals("unknown|0xabc", com.lifecyclebot.perps.DynamicAltTokenRegistry.canonicalIdentity6544("", "0xabc"))
        assertTrue(registry.contains("val chainId: String = " + '"' + '"') &&
            registry.contains("val canonicalIdentity6544") && registry.contains("val tokenAddress: String = mint"))
        assertTrue(dex.contains("val chainId: String") && dex.contains("val dexId: String") &&
            dex.contains("val tokenAddress: String") && dex.contains("val quoteAddress: String") &&
            dex.contains("val pairCreatedAt: Long") && dex.contains("token-pairs/v1/" + '$' + "{encode(chainId)}/" + '$' + "{encode(tokenAddress)}"))
        assertFalse(registry.contains("if (pair.chainId != " + '"' + "solana" + '"' + ") continue"))
        assertTrue(registry.contains("fetchGeckoPools6544") && registry.contains("HealthAwareHttp.execute(http, req, host)") &&
            registry.contains("FRESH_DISCOVERY_MS_6544") && registry.contains("ACTIVE_DISCOVERY_MS_6544") &&
            registry.contains("getBlendedOpportunityQueue6544"))
        assertTrue(resolver.contains("nonSolanaExplicit6544") && resolver.contains("targetChainId6544"))
        assertTrue(executor.contains("targetChainId6544 = targetChainId6544"))
        assertTrue(crypto.contains("getBlendedOpportunityQueue6544()") && crypto.contains("dynAssetKey") &&
            crypto.contains("targetChainId6544 = signal.dynChainId"))
        assertTrue(commodities.contains("val commodityMarkets = PerpsMarket.values().filter { it.isCommodity }") &&
            commodities.contains("MarketsLiveExecutor.executeLiveTradeProof6486"))
        assertTrue(markets.contains("executeLiveTradeProof6486") && tokenized.contains("hasRealRoute"))
        assertTrue(markets.contains("V5.0.6545 — canonical bridge rail") &&
            markets.contains("UniversalBridgeEngine.prepareCapital(") &&
            markets.contains("canonical bridge returned no swap signature; refusing open") &&
            !markets.substringAfter("V5.0.6545 — canonical bridge rail").substringBefore("private suspend fun executeJupiterSwap")
                .contains("inputMint         = SOL_MINT"))
        assertTrue(bot.contains("backgroundLivenessSnapshot6544") && bot.contains("recordBackgroundProgress6544") &&
            bot.contains("dozeEvidence6544") && bot.contains("HEARTBEAT_RESCUE_PROGRESS_TIMEOUT_6544") &&
            bot.contains("LONG_CYCLE_NOT_DOZE_6544") && bot.contains("batteryOptWhitelisted="))
        assertTrue(paperExecutor.contains("val sellGeneration6474 = canonicalTerminalPosition6492.openedAtMs") &&
            !paperExecutor.contains("val sellGeneration6474 = tradeId.tradeId"))
        assertTrue(report.contains("Background Runtime Progress (V5.0.6544)") &&
            report.contains("BG_BOT_LOOP_TICK") && report.contains("BG_SCAN_CB") && report.contains("BG_INTAKE") &&
            report.contains("BG_FDG") && report.contains("BG_EXIT"))
        assertTrue(report.contains("Crypto Universe Discovery (V5.0.6544)"))
        assertTrue(registry.contains("networks observed=") && registry.contains("DEXes observed=") &&
            registry.contains("fresh pools discovered=") && registry.contains("unique chain+token identities=") &&
            registry.contains("discoveries by chain=") && registry.contains("pool cohorts <5m=") &&
            registry.contains("fresh reaching CryptoBrain=") && registry.contains("fresh reaching V3/FDG=") &&
            registry.contains("paper-only unavailable live route=") && registry.contains("live-routable candidates=") &&
            registry.contains("static-vs-dynamic evaluation share="))
        val generator = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MultiChainWalletGenerator6546.kt").readText()
        val vault = java.io.File("src/main/kotlin/com/lifecyclebot/engine/MultiChainWalletVault6546.kt").readText()
        val walletManager = java.io.File("src/main/kotlin/com/lifecyclebot/engine/WalletManager.kt").readText()
        assertTrue(generator.contains("SLIP-0010") && generator.contains("44 or HARDENED") &&
            generator.contains("ethereumAddress") && generator.contains("bitcoinAddress"))
        assertTrue(vault.contains("EncryptedSharedPreferences") && vault.contains("solana_private_key_b58") &&
            vault.contains("MULTICHAIN_PUBLIC_ADDRESS_EMPTY"))
        assertTrue(walletManager.contains("generateMultiChainWallet") && walletManager.contains("loadMultiChainWallet"))
    }


    @Test
    fun V5_0_6564_paper_universes_restore_measurable_handoffs_ticket_truth_wallet_truth_and_causal_learning() {
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val stocks = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        val perps = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt").readText()
        val perpsBrain = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsTraderAI.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val report = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val money = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ReportingHub.kt").readText()
        val governor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/RealizedWalletCompoundingGovernor.kt").readText()
        val contract = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()

        assertTrue(crypto.contains("CRYPTO_SIGNAL_SELECTED_6566") &&
            crypto.contains("source=DYNAMIC_ALT") && crypto.contains("source=STATIC_ALT") &&
            crypto.contains("lane=CRYPTO_ALT source=CANONICAL_HANDOFF_6566") &&
            crypto.contains("path=CRYPTO_ALT mode="))
        assertTrue(stocks.contains("MARKETS_STOCK_SIGNAL_SELECTED_6566") &&
            stocks.contains("lane=MARKETS_STOCKS source=CANONICAL_HANDOFF_6566") &&
            contract.contains("PHASE.FDG") && contract.contains("sealed=true assetClass="))
        assertTrue(perps.contains("lane=PERPS source=") &&
            perps.contains("LaneExecutionCoordinator.candidateVersionFor(signal.market.symbol)"))
        assertFalse(perps.contains("candidateVersion = System.currentTimeMillis()"))

        assertTrue(gate.contains("val ticketAuthority6564 = resolveSealedIntent6613(") &&
            gate.contains("ticketAuthority6564?.fdgAllowed") &&
            gate.contains("ticketAuthority6564?.fdgVerdict") &&
            gate.contains("ticketAuthority6564 == null"))
        assertTrue(gate.contains("LIVE_EXECUTION_TICKET_TTL_MS = 45_000L") &&
            gate.contains("PAPER_EXECUTION_TICKET_TTL_MS = 180_000L") &&
            gate.contains("ticket.mode.equals(\"PAPER\", true)"))

        // V5.0.6604 §PAPER_LEDGER_READ_UNIFICATION — the reporting/governor
        // surfaces may now read from the canonical facade
        // (PaperCapitalAuthority6577) which is a READ-ONLY delegation to
        // the same PaperAccountLedger6430 authority. Both references are
        // acceptable — the operator's convergence goal is any read path
        // that ultimately resolves to the single ledger.
        assertTrue((money.contains("PaperAccountLedger6430.isAuthorityInitialized6489()") ||
                money.contains("PaperCapitalAuthority6577.isAuthorityInitialized6489()")) &&
            (money.contains("PaperAccountLedger6430.cashSol()") ||
                money.contains("PaperCapitalAuthority6577.cashSol()")))
        assertTrue((governor.contains("PaperAccountLedger6430.isAuthorityInitialized6489()") ||
                governor.contains("PaperCapitalAuthority6577.isAuthorityInitialized6489()")) &&
            (governor.contains("PaperAccountLedger6430.cashSol()") ||
                governor.contains("PaperCapitalAuthority6577.cashSol()")))

        assertTrue(report.contains("unique intake symbols:") && report.contains("unique intake → V3:") &&
            report.contains("pre-V3 returns:") && report.contains("PRE_V3_RETURN_"))
        assertTrue(stocks.contains("CanonicalPublishHelper.publishExit") &&
            stocks.contains("entryPattern = \"STOCK_"))
        assertTrue(perpsBrain.contains("CanonicalPublishHelper.publishExit") &&
            perpsBrain.contains("entryPattern = \"PERPS_"))
    }


    @Test
    fun V5_0_6565_all_cross_asset_paper_opens_carry_one_compatible_canonical_intent() {
        val contract = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val perps = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsTraderAI.kt").readText()
        val forex = java.io.File("src/main/kotlin/com/lifecyclebot/perps/ForexTrader.kt").readText()
        val metals = java.io.File("src/main/kotlin/com/lifecyclebot/perps/MetalsTrader.kt").readText()
        val commodities = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CommoditiesTrader.kt").readText()
        assertTrue(contract.contains("candidate.mode.equals(\"LIVE\", true) && candidate.direction.equals(\"SHORT\", true)"))
        assertTrue(contract.contains("UPSTREAM_INTENT_CONFLICT") && contract.contains("registered.resolvedSize - sizing.finalSizeSol"))
        listOf(crypto, perps, forex, metals, commodities).forEach {
            assertTrue(it.contains("CanonicalEntryAuthority6551.submit"))
            assertTrue(it.contains("executionIntent = "))
        }
        assertTrue(perps.contains("executionIntent6565") && crypto.contains("canonicalCryptoIntent6565"))
    }


    @Test
    fun V5_0_6566_partials_are_sequence_idempotent_canonical_and_nonterminal() {
        val partial = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperPartialOperation6510.kt").readText()
        val reducer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        val stock = java.io.File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        val forex = java.io.File("src/main/kotlin/com/lifecyclebot/perps/ForexTrader.kt").readText()
        val metals = java.io.File("src/main/kotlin/com/lifecyclebot/perps/MetalsTrader.kt").readText()
        val commodities = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CommoditiesTrader.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        assertTrue(partial.contains("requestSequences.computeIfAbsent") && partial.contains("seq.incrementAndGet()"))
        assertFalse(partial.contains("abs(exitReason.hashCode()"))
        assertTrue(reducer.contains("fun partial(") && reducer.contains("CanonicalPaperPartialOperation6510.commit"))
        listOf(stock, forex, metals, commodities, crypto).forEach {
            assertTrue(it.contains("partialPosition6566"))
            assertTrue(it.contains("CanonicalPaperTransaction6486.partial"))
            assertFalse(it.contains("Action.PARTIAL ->\n                        closePosition"))
        }
        assertEquals(2, Regex("FluidLearning\\.recordPaperSell\\(").findAll(stock).count()) // shutdown + one normal terminal path
    }


    @Test
    fun V5_0_6566_meme_partial_and_cyclic_close_state_require_applied_finality() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        val cyclic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        assertTrue(executor.contains("data class PartialSellReceipt6566") &&
            executor.contains("fun requestPartialSellConfirmed6566("))
        assertTrue(executor.contains("partial6510.operationId") &&
            executor.contains("LIVE_PARTIAL_CONFIRMED") && executor.contains("finalSig"))
        assertEquals(10, Regex("requestPartialSellConfirmed6566\\(").findAll(bot).count())
        assertTrue("all optimistic rung/chunk mutations must be receipt-gated",
            Regex("partialReceipt6566\\.applied").findAll(bot).count() >= 10)
        val checkExitStart6566 = moon.indexOf("fun checkExit(")
        val checkExit = moon.substring(checkExitStart6566, moon.indexOf("private fun updateLearning(", checkExitStart6566))
        assertFalse(checkExit.contains("partialRungsTaken += 1"))
        assertTrue(checkExit.contains("val proposedRung = pos.partialRungsTaken + 1"))
        val onPartial = moon.substring(moon.indexOf("fun onPartialSell("), moon.indexOf("fun getActivePositions("))
        assertTrue(onPartial.contains("partialRungsTaken = (pos.partialRungsTaken + 1)"))
        assertFalse("tick must never resurrect disabled Cyclic authority", cyclic.contains("if (!enabled.get()) { enabled.set(true) }"))
        assertTrue(cyclic.contains("CYCLIC_TICK_SKIPPED_DISABLED_6566"))
        val closeCycle = cyclic.substring(cyclic.indexOf("private fun closeCycle("), cyclic.indexOf("private fun clearLocalCycleState6566"))
        assertTrue(closeCycle.indexOf("when (sellResult6566)") < closeCycle.indexOf("// Update ring only after confirmed sell finality."))
        assertTrue(closeCycle.contains("CYCLIC_SELL_NOT_FINAL_6566") && closeCycle.contains("retain_position_no_learning"))
        assertTrue(closeCycle.contains("Executor.SellResult.CONFIRMED") && closeCycle.contains("Executor.SellResult.PAPER_CONFIRMED"))
    }


    @Test
    fun V5_0_6566_crypto_markets_handoffs_and_meme_dedupe_preserve_executable_intake() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val files = listOf(
            "CryptoAltTrader.kt" to "CRYPTO_ALT",
            "TokenizedStockTrader.kt" to "MARKETS_STOCKS",
            "ForexTrader.kt" to "MARKETS_FOREX",
            "MetalsTrader.kt" to "MARKETS_METALS",
            "CommoditiesTrader.kt" to "MARKETS_COMMODITIES",
        )
        files.forEach { (name, lane) ->
            val src = java.io.File("src/main/kotlin/com/lifecyclebot/perps/$name").readText()
            val handoff = src.indexOf("lane=$lane source=CANONICAL_HANDOFF_6566")
            val submit = src.indexOf("CanonicalEntryAuthority6551.submit", handoff)
            assertTrue("$lane must emit active lane-eval immediately before canonical FDG submit", handoff >= 0 && submit > handoff && submit - handoff < 1200)
            assertEquals("$lane must have one canonical active handoff marker", 1, Regex("lane=$lane source=CANONICAL_HANDOFF_6566").findAll(src).count())
        }
        assertTrue(bot.contains("intakeSeenSourcesByMint6566") && bot.contains("newSourceEvidence6566"))
        assertTrue(bot.contains("GlobalTradeRegistry.updateProbationScanner(mint, source)"))
        assertTrue(bot.contains("GlobalTradeRegistry.mergeAffinity(mint, lanes6566, tools6566)"))
        assertTrue(bot.contains("ScannerHydrationQueues6347.Bucket.LIVE_READY") && bot.contains("MEME_DEDUPE_REFRESH_6566"))
        assertTrue(bot.contains("MEME_INTAKE_DEDUPE_EVIDENCE_REFRESH_6566"))
    }



    @Test
    fun V5_0_6567_quantity_sizing_finality_funnel_learning_health_and_ui_are_canonical() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sizing = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/OrderSizeResolver6441.kt").readText()
        val sizingBridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalSizingBridge6532.kt").readText()
        val cyclic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalEntryAuthority6540.kt").readText()
        val entrySnapshot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        val finalized = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val tactic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val classifier = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/RootCauseClassifier6471.kt").readText()
        val health = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        val main = java.io.File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()

        assertTrue(executor.contains("canonicalTerminalPosition6492.quantityScale.coerceIn(0, 18)"))
        assertFalse(executor.contains("canonicalTerminalPosition6492.tokenDecimals.takeIf"))
        assertTrue(sizing.contains("applyPaperMemeMinimum") && sizingBridge.contains("applyPaperMemeMinimum = assetClass == AssetClass.SOLANA_TOKEN"))
        assertTrue(sizing.contains("val effectiveShapedLamports6506 = laneClampedLamports6491"))
        assertFalse(sizing.contains("ORDER_SIZE_PROMOTED_TO_MIN_EXECUTABLE_6506"))
        assertTrue(executor.contains("val floorPromotionRequested6511 = false"))

        val specialists = listOf("BlueChipTraderAI.kt", "CashGenerationAI.kt", "ManipulatedTraderAI.kt",
            "MoonshotTraderAI.kt", "QualityTraderAI.kt", "ShitCoinExpress.kt", "ShitCoinTraderAI.kt")
            .map { java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/$it").readText() }
        specialists.forEach {
            assertFalse(it.contains("V3JournalRecorder.recordClose("))
            assertFalse(it.contains("CanonicalPublishHelper.publishExit("))
            assertTrue(it.contains("canonical terminal bridge owns the single SELL journal projection") ||
                it.contains("canonical finalized bus is published by TerminalBridge/Executor only"))
        }

        assertTrue(cyclic.contains("CONFIRMED_FALSE, UNKNOWN, PROVIDER_UNAVAILABLE, CONFIRMED_TRUE"))
        assertTrue(cyclic.contains("if (isLiveMode) evidence == CyclicSellabilityEvidence6567.CONFIRMED_TRUE") && cyclic.contains("else evidence != CyclicSellabilityEvidence6567.CONFIRMED_FALSE"))
        assertTrue(crypto.contains("uniqueDynSignals6567") && crypto.contains("groupBy { it.dynAssetKey"))
        assertTrue(crypto.contains("POSITION_CAP_REACHED") && crypto.contains("OBSERVE_SPECIALIST_SILENCE_6569") &&
            !crypto.contains("""markEvaluationDisposition6567(refreshed, "NO_ACTIONABLE_SPECIALIST_SIGNAL")"""))
        assertFalse(crypto.contains(".take(25) // V5.9.128: raised from 3"))
        assertTrue(registry.contains("evaluation terminal dispositions=") && registry.contains("CRYPTO_EVAL_TERMINAL_6567"))
        assertTrue(bot.contains("if (isCryptoUniverseSource6535) out += \"CRYPTO_ALT\""))

        mapOf("TokenizedStockTrader.kt" to "STOCKS", "ForexTrader.kt" to "FOREX",
            "MetalsTrader.kt" to "METALS", "CommoditiesTrader.kt" to "COMMODITIES").forEach { (file, family) ->
            val src = java.io.File("src/main/kotlin/com/lifecyclebot/perps/$file").readText()
            assertTrue(src.contains("isPaperMode.get() ||"))
            assertTrue(src.contains("MARKETS_FUNNEL_6567|FAMILY=$family|STAGE=SIGNAL_SELECTED"))
            assertTrue(src.contains("MARKETS_FUNNEL_6567|FAMILY=$family|STAGE=PAPER_TRUST_ADVISORY"))
        }
        assertTrue(authority.contains("AssetClassStats6567") && authority.contains("assetClassFunnelReport6567"))
        assertTrue(health.contains("Cross-Asset Canonical Funnel (V5.0.6567) [CANONICAL CURRENT SESSION]"))

        assertTrue(entrySnapshot.contains("LearningPersistence.save(persistenceKey6567") &&
            entrySnapshot.contains("LearningPersistence.load(persistenceKey6567"))
        assertTrue(finalized.contains("val entrySource: String") && finalized.contains("val marketRegime: String") && finalized.contains("val scoreBand: String"))
        assertTrue(tactic.contains("persistHistorical6567") && tactic.contains("historicalTradesForCohort"))
        assertTrue(classifier.contains("currentPaperConservationHealthy6567") && classifier.contains("classifier_current_canonical_delta_healthy_6567"))
        assertTrue(health.contains("[SESSION HISTORICAL COUNTERS]") && health.contains("[CANONICAL CURRENT SNAPSHOTS]"))

        assertTrue(main.contains("if (structuralChange) llOpenPositions.removeAllViews()"))
        assertTrue(main.contains("val dividerView: android.view.View"))
        assertTrue(main.contains("if (structuralChange) {") && main.contains("cached.dividerView"))
        assertFalse(main.contains("llOpenPositions.addView(View(this).apply"))
    }

    @Test
    fun V5_0_6568_meme_entry_policy_and_tactic_rewards_are_causal() {
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val tactic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(exec.contains("LIVE_ENTRY_POLICY_SNAPSHOT_CANONICAL_6568") && exec.contains("entryThresholdSnapshot = ts.position.entryPolicySnapshot"))
        assertTrue(exec.contains("entryTactic=" + "$" + "electedTactic6568") && exec.contains("brainConsensus=" + "$" + "brainVerdict6568") && exec.contains("policyPWin=" + "$" + "{policyPWin6568.fmt(3)}"))
        assertTrue(tactic.contains("TACTIC_HISTORICAL_OUTCOME_ATTRIBUTED_6568") && tactic.contains("if (elected.name == current) onTradeClosed"))
        assertFalse(tactic.contains("entered.isBlank() || entered == current"))
        assertTrue(bot.contains("authoritativePolicyPositive6568") && bot.contains("NEGATIVE_CONSENSUS_NORMAL_BUY_SUPPRESSED_6568") && bot.contains("liqOk && authoritativePolicyPositive6568"))
    }


    @Test
    fun V5_0_6568_completion_causal_learning_integrity_and_shaping() {
        val snapshot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        val advisor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/AutoPipelineAdvisor6462.kt").readText()
        val eligibility = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperLearningEligibility6519.kt").readText()
        val bridge = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val journal = java.io.File("src/main/kotlin/com/lifecyclebot/engine/V3JournalRecorder.kt").readText()
        val sizer = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        assertTrue("6568 typed immutable entry policy evidence", snapshot.contains("entryPolicySnapshotId") && snapshot.contains("v3Components") && snapshot.contains("brainConsensusVerdict") && snapshot.contains("policyProbability") && snapshot.contains("specialistContributions") && snapshot.contains("sizingMultipliers") && snapshot.contains("authorizationReason"))
        assertTrue("6568 every-25 close causal report", snapshot.contains("rows.size % 25 == 0") && snapshot.contains("MEME_WINNER_LOSER_CAUSAL_REPORT_6568"))
        assertTrue("6568 persisted bounded causal learner", snapshot.contains("meme_causal_learning_6568") && snapshot.contains("while (rows.size > 100)") && snapshot.contains("ensureRestored"))
        assertTrue("6568 integrity diagnostics cannot mutate strategy", advisor.contains("ADVISOR_INTEGRITY_DIAGNOSTIC_ONLY_6568") && !advisor.contains("""Candidate("entryCooldownSec", +3.0""") && !advisor.contains("PENDING_ENTRY_LEAKED_INTO_OPEN_6461=$" + "pendingLeaks — throttle entries"))
        assertTrue("6568 invalid terminals forensic only", eligibility.contains("FORENSIC_ONLY_$" + "invalid") && journal.contains("JOURNAL_STRATEGY_LEARNING_QUARANTINED_6568"))
        assertTrue("6568 one canonical causal consumer", bridge.contains("deliverToMemeCausalLearning6568") && bridge.contains("DamageControlGate.noteOutcome") && !journal.contains("TacticSwitcher.onTradeClosed(layer, band, pnlPctLearn)"))
        assertTrue("6568 WR/PF shapes not disables", snapshot.contains("if (wr>=0.15 && pf>=0.5)") && snapshot.contains("0.70 else 0.20") && sizer.contains("MEME_CAUSAL_PERFORMANCE_SHAPED_6568"))
    }


    @Test
    fun V5_0_6569_cross_asset_causal_liveness_identity_advisor_and_economics() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val authority = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalEntryAuthority6540.kt").readText()
        val contract = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        val paper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        val finality = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val advisor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/AutoPipelineAdvisor6462.kt").readText()
        assertTrue("6569 immutable class lives on sealed ticket", gate.contains("assetClassTag") && contract.contains("assetClassTag = candidate.assetClass.tag"))
        assertTrue("6569 dispatch never re-derives class from executor lane", contract.contains("intentAssetClass6569(intent)") && !contract.contains("markAdapterDispatchFor6551(AssetClass.fromLane(intent.canonicalLane)"))
        assertTrue("6569 terminal learning carries immutable class", finality.contains("assetClassTag = event.assetClassTag.ifBlank"))
        assertTrue("6569 intent conservation is explicit", authority.contains("dispatchReject=") && authority.contains("pending=") && authority.contains("unexplained="))
        assertTrue("6569 three-window producer liveness fault", authority.contains("MARKET_CLASS_LIVENESS_FAULT") && authority.contains("zero.get() >= 3L"))
        assertTrue("6569 specialist silence observes through shared authority", crypto.contains("OBSERVE_SPECIALIST_SILENCE_6569") && crypto.contains("markFdgReach6544(sharedTok6569") && !crypto.contains("""markEvaluationDisposition6567(refreshed, "NO_ACTIONABLE_SPECIALIST_SIGNAL")"""))
        assertTrue("6569 bounded Crypto shared-intelligence work", crypto.contains(".take(25)"))
        assertTrue("6569 advisor causal-domain isolation", advisor.contains("ADVISOR_CROSS_DOMAIN_MUTATION_BLOCKED_6569") && advisor.contains("REPLAY_DRIVEN_ENTRY_COOLDOWN_ROLLED_BACK_6569") && !advisor.contains("""Candidate("entryCooldownSec", +3.0"""))
        assertTrue("6569 leveraged terminal proceeds and quarantine", crypto.contains("sol              = (pos.sizeSol + pnlSol).coerceAtLeast(0.0)") && paper.contains("LEVERAGED_TERMINAL_ARITHMETIC_DIVERGENCE_6569") && paper.contains("PaperLearningEligibility6519.record"))
    }

    @Test
    fun V5_0_6570_execution_and_exit_authority_repair_contract() {
        val mark = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        val markGate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkAuthorityIntegrityGate6496.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val cyclic = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CyclicTradeEngine.kt").readText()
        val perps = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsExecutionEngine.kt").readText()
        val perpsAi = java.io.File("src/main/kotlin/com/lifecyclebot/perps/PerpsTraderAI.kt").readText()
        val positions = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        val paper = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val live = java.io.File("src/main/kotlin/com/lifecyclebot/perps/MarketsLiveExecutor.kt").readText()
        val forex = java.io.File("src/main/kotlin/com/lifecyclebot/perps/ForexTrader.kt").readText()
        val commodities = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CommoditiesTrader.kt").readText()
        val metals = java.io.File("src/main/kotlin/com/lifecyclebot/perps/MetalsTrader.kt").readText()

        assertTrue(mark.contains("CanonicalMarkPurpose6570.OBSERVATION_SCORING") &&
            mark.contains("resolveExecutableFromSourceEvidence6616") &&
            bot.contains("CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616"))
        assertTrue(markGate.contains("isObservationAuthoritative6570") && markGate.contains("GECKOTERMINAL"))
        assertTrue(crypto.contains("markEvaluationProgress6570(refreshed") && crypto.contains("markEvaluationDisposition6567(observedTok6569") &&
            crypto.contains("SHARED_INTELLIGENCE_BACKLOG_COALESCED"))
        assertEquals(1, Regex("SHARED_INTELLIGENCE_BACKLOG_COALESCED_REQUEUE").findAll(crypto).count())
        assertTrue(registry.contains("evaluationGeneration6615") && registry.contains("evaluationInflight6615") &&
            registry.contains("CRYPTO_EVAL_GENERATION_COALESCED_6615"))
        assertTrue(crypto.contains("canonicalFinalSize6570 = canonicalCryptoIntent6565.resolvedSize") && crypto.contains("markFailed(canonicalCryptoIntent6565"))
        assertTrue(cyclic.contains("CanonicalMintOccupancyRegistry6464.isOpen") && cyclic.contains("if (isLiveMode) evidence == CyclicSellabilityEvidence6567.CONFIRMED_TRUE"))
        assertTrue(perps.contains("CanonicalAssetEntryCandidate6551") && perps.contains("assetClass = com.lifecyclebot.engine.truth.AssetClass.PERPS") && perps.contains("sealedPerpIntent6570"))
        assertFalse(perps.contains("recordFdgAndGetIntent6533("))
        assertTrue(perpsAi.contains("executionIntent6565 ?: when (perpsAdmission6565)"))
        assertTrue(positions.contains("fun exitEligibility6570(") && paper.contains("exitEligibility6570(positionId, mint, expectedMode = \"paper\")"))
        assertTrue(executor.contains("exitEligibility6570(") && live.contains("exitEligibility6570("))
        assertTrue(commodities.contains("layerVotes[\"CommoditiesStrategy\"] = direction") && metals.contains("layerVotes[\"MetalsStrategy\"] = direction"))
        assertTrue(forex.contains("AssetClass.FOREX, \"RAW_SIGNAL\""))
    }

    @Test
    fun V5_0_6613_causal_execution_memetrader_and_crypto_handoff_contract() {
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val mark = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        val partial = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperPartialOperation6510.kt").readText()
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()

        assertTrue(gate.contains("CanonicalFinalDecision6613") && gate.contains("resolveSealedIntent6613"))
        assertTrue(gate.contains("RESTORED_ALLOW_TICKET_WITHOUT_BUY_DECISION") && gate.contains("RESTORED_TICKET_DECISION_DIVERGES_FROM_SEALED_FDG"))
        assertTrue(mark.contains("promoteObservationToExecutable6613") && mark.contains("CANONICAL_MINT_SOURCE_MARK_6613"))
        assertTrue(executor.contains("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT") && executor.contains("LANE_EXEC_WITHOUT_SEALED_FDG_PROVENANCE"))
        assertTrue(sheet.contains("INTENT_CHOKED") && sheet.contains("MARK_CHOKED") && sheet.contains("EXEC_CHOKED") && sheet.contains("LEARNING_CHOKED"))
        assertFalse(sheet.contains("""TELEMETRY_ONLY"""))
        assertTrue(partial.contains("TierState6613") && partial.contains("QUANTITY_RESERVED") && partial.contains("ACCOUNTED") && partial.contains("COMPLETE"))
        assertTrue(bot.contains("LEARNED_POLICY_NEGATIVE_LANE_WAIT_SHAPED_6613") && bot.contains("TACTIC_ROTATED_WEAK_WAIT_SHAPED_6613"))
        assertFalse(bot.contains("""blockReason = "LEARNED_POLICY_VETO_6593""""))
        val candidateStamp = crypto.indexOf("""AssetClass.CRYPTO_ALT, "CANDIDATE"""")
        val canonicalSubmit = crypto.indexOf("CanonicalEntryAuthority6551.submit", candidateStamp)
        assertTrue(candidateStamp >= 0 && canonicalSubmit > candidateStamp)
        assertTrue(crypto.contains("CRYPTO_LEARNED_SIZE_FLOORED_NONZERO_6613") && crypto.contains("terminalDisposition6613"))
    }


    @Test
    fun V5_0_6614_meme_specialist_owner_fdg_mark_and_ticket_identity_is_immutable() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val coordinator = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()
        val auth = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val marks = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        val sheet = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()
        assertTrue(bot.contains("roleFitPrimary6614") && bot.contains("strongestRole6614") && bot.contains("ensembleCoreFit6614"))
        assertTrue(coordinator.contains("Do not re-elect it here using static") && !coordinator.contains("TREASURY_DEFER_SPECIALIST_FIRST"))
        assertTrue(auth.contains("ExecutionBook.CASHGEN") && auth.contains("ExecutionBook.PROJECT_SNIPER"))
        assertTrue(bot.contains("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME") && bot.contains("specialistCausalId6614"))
        assertTrue(marks.contains("refreshFromExecutableTokenMap6614") && marks.contains("TOKEN_MAP_ROUTE_NOT_EXECUTABLE"))
        assertTrue(gate.contains("markId6614") && gate.contains("markVersion6614") && gate.contains("sealedProvenance6614"))
        assertTrue(gate.contains("TICKET_REFRESH_AUTHORITY_FAILURE") && gate.contains("EXPIRED_TICKET_ECONOMIC_REJECT_6614"))
        assertTrue(sheet.contains("fdgAllow + fdgBlock == 0L") && sheet.contains("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME="))
    }


    @Test
    fun V5_0_6615_loop_starvation_work_is_single_flight_and_generation_owned() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val worker = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MaintenanceWorker6448.kt").readText()
        val sentinel = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MarketDataProvenance6471.kt").readText()
        val marks = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MarkAuthorityIntegrityGate6496.kt").readText()
        val registry = java.io.File("src/main/kotlin/com/lifecyclebot/perps/DynamicAltTokenRegistry.kt").readText()
        val crypto = java.io.File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(worker.contains("runningNames6615.add(name)") && worker.contains("runningNames6615.remove(name)"))
        assertTrue(bot.contains("requestHotWatchlistRebalance6615") && bot.contains("hot_watchlist_rebalance_6615") &&
            bot.contains("canonical_cycle_end_6615") && bot.contains("val maxBatchMillis = 7_500L"))
        assertTrue(sentinel.contains("SentinelState6615") && sentinel.contains("MARKET_DATA_SENTINEL_COALESCED_6615"))
        assertTrue(marks.contains("MarkState6615") && marks.contains("PAPER_MARK_UNCHANGED_COALESCED_6615"))
        assertTrue(registry.contains("evaluationGeneration6615") && registry.contains("evaluationInflight6615") && registry.contains("CRYPTO_EVAL_STALE_COMPLETION_DROPPED_6615"))
        assertTrue(crypto.contains("SHARED_INTELLIGENCE_BACKLOG_COALESCED") &&
            Regex("SHARED_INTELLIGENCE_BACKLOG_COALESCED_REQUEUE").findAll(crypto).count() == 1)
        assertTrue(gate.contains("EXEC_OPEN_BLOCKED_NO_EXECUTION_INTENT_6615") && gate.contains("NO_EXECUTION_INTENT"))
    }


    @Test
    fun V5_0_6616_canonical_mark_and_supervisor_generation_lifetime_are_causal() {
        val bot = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val marks = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPriceMark6522.kt").readText()
        assertTrue(marks.contains("resolveExecutableFromSourceEvidence6616") &&
            marks.contains("SOURCE_BASE_IDENTITY_MISMATCH") && marks.contains("SOURCE_EVIDENCE_STALE") &&
            marks.contains("return promoteObservationToExecutable6613(mint, nowMs)"))
        assertTrue(bot.contains("resolveExecutableFromSourceEvidence6616") &&
            executor.contains("resolveExecutableFromSourceEvidence6616"))
        assertTrue(bot.contains("startedMonotonicMs") && bot.contains("SystemClock.elapsedRealtime()") &&
            bot.contains("sinceProgress >= SUPERVISOR_LEASE_PROGRESS_TTL_MS") &&
            bot.contains("job?.isActive == true") && bot.contains("SUPERVISOR_FORCE_RELEASE_DEFERRED_YOUNG_6616"))
        assertFalse(bot.contains("SupervisorLease(mint = mint, startedMs = System.currentTimeMillis()"))
    }

}
