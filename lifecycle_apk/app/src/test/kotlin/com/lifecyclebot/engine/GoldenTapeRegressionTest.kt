Warning: truncated output (original token count: 314657)
... 210051 bytes omitted ...

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
        // V5.0.7287 — terminal paper sells pay the venue's fee + the app fee +
        // the fixed network leg; the learned slip term that re-charged the
        // tier slippage is gone (PaperVenueCost7287).
        assertTrue("Terminal paper sells must charge venue-priced friction", exec.contains("executable-live paper friction") && exec.contains(".venueFeePct(ts.mint, ts.lastLiquidityUsd, ts.lastMcap) + MEME_TRADING_FEE_PERCENT * 100.0") && !exec.contains("val simulatedFeePct = (1.6 + expectedRouteSlipPct"))
        assertTrue("Paper terminal SELL rows must carry feeSol/netPnlSol into journal and learning", exec.contains("val simulatedFeeSol") && exec.contains("feeSol = simulatedFeeSol") && exec.contains("netPnlSol = pnl"))
        assertTrue("Legacy journal consumers must receive net-normalized pnlPct before TradeHistoryStore", exec.contains("paper→live transfer authority") && exec.contains("PAPER_LIVE_TRANSFER_NET_PCT_NORMALIZED") && exec.indexOf("paper→live transfer authority") < exec.indexOf("TradeHistoryStore.recordTrade(tradeWithMint)"))
        assertTrue("Partial net pct must use sold-leg basis, not full position cost", exec.contains("val isPartialClose = tradeWithMint.side.equals(\"PARTIAL_SELL\", true)") && exec.contains("Partial SELL rows use sol as the sold-leg cost basis"))
        assertTrue("Canonical rich publish must agree with the net-…247152 tokens truncated…ins("val cashClamped = cashClamped1"))
        assertTrue(osr.indexOf("val cashClamped = cashClamped1") < osr.indexOf("val laneClamped = cashClamped.coerceAtMost(laneRiskCapSol)"))

        // Seam 3: every sell request is classified by the exit discipline.
        val exec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("com.lifecyclebot.engine.truth.FieldManual7715.noteExit(reason, ts.position.isPaperPosition)"))
        assertTrue(exec.indexOf("FieldManual7715.noteExit(") > exec.indexOf("fun requestSell(ts: TokenState, reason: String, wallet: SolanaWallet?, walletSol: Double): SellResult {"))

        // Seam 4: every LLM call carries the doctrine first.
        val gem = java.io.File("src/main/kotlin/com/lifecyclebot/engine/GeminiCopilot.kt").readText()
        assertTrue(gem.contains("com.lifecyclebot.engine.truth.FieldManual7715.withDoctrine7715(systemPrompt)"))
        assertEquals(2, Regex("systemPrompt = doctrinePrompt7715\\(systemPrompt\\),").findAll(gem).count())

        // Report line and the verbatim manual in docs.
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.contains("Field manual (§7715):"))
        val doc = java.io.File("../../docs/FIELD_MANUAL.md").readText()
        assertTrue(doc.contains("The Crypto Trader"))
        assertTrue(doc.contains("Decision: ENTER / SMALL PROBE / WAIT / PASS"))
    }

    @Test
    fun V5_0_7717_startup_crash_guard_capacity_slots_and_the_manual_behind_active() {
        // 5.0.7715 crashed the app the instant the password was accepted; the
        // trace was never readable (ErrorLogger is asynchronous, the default
        // handler kills the process) and the emulator never reaches the path.
        // Operator on the 7697 slot ladder: "I don't want two slots ... the
        // bot to have the ability to trade as it likes but not spread cash
        // over 30 tokens."
        val guard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/StartupCrashGuard7717.kt").readText()
        assertTrue(guard.contains("object StartupCrashGuard7717 {"))
        assertTrue(guard.contains("const val STARTUP_WINDOW_MS_7717 = 120_000L"))
        assertTrue(guard.contains("const val SUPPRESS_MS_7717 = 6L * 60L * 60_000L"))
        assertTrue(guard.contains("const val LAST_CRASH_FILE_7717 = \"last_crash_7717.txt\""))
        assertTrue(guard.contains("fun markProcessStart(ctx: Context, buildTag: String) {"))
        assertTrue(guard.contains("fun recordCrash(ctx: Context, thread: Thread, t: Throwable, buildTag: String) {"))
        // Synchronous: commit(), not apply(), and a file beside it.
        assertTrue(guard.contains("e.commit()"))
        assertFalse(guard.substringAfter("fun recordCrash(").substringBefore("private fun buildHead(").contains(".apply()"))
        assertTrue(guard.contains("fun manualSuppressed(): Boolean {"))
        assertTrue(guard.contains("fun lastCrashSummary(): String {"))
        assertTrue(guard.contains("fun uptimeMs(): Long"))
        assertTrue(guard.contains("fun statusLine(): String ="))

        // The app records first and loads first.
        val app = java.io.File("src/main/kotlin/com/lifecyclebot/AATEApp.kt").readText()
        assertTrue(app.contains("com.lifecyclebot.engine.truth.StartupCrashGuard7717.markProcessStart(this, com.lifecyclebot.BuildConfig.VERSION_NAME)"))
        val handler = app.substringAfter("Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->")
        assertTrue(handler.indexOf("StartupCrashGuard7717.recordCrash(") < handler.indexOf("ErrorLogger.crash("))

        // The report prints the last crash at the top.
        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.indexOf("Last crash (§7717):") < phc.indexOf("Wallet adoption (§7706):"))
        assertTrue(phc.indexOf("Last crash (§7717):") > phc.indexOf("===== AATE Pipeline Health Snapshot ====="))

        // The manual is behind active(): a minute after start, never after a startup crash.
        val fm = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/FieldManual7715.kt").readText()
        assertTrue(fm.contains("const val ACTIVATION_DELAY_MS_7717 = 60_000L"))
        assertTrue(fm.contains("!StartupCrashGuard7717.manualSuppressed() && StartupCrashGuard7717.uptimeMs() >= ACTIVATION_DELAY_MS_7717"))
        assertTrue(fm.contains("if (mint.isBlank() || !active()) return 1.0"))
        assertTrue(fm.substringAfter("fun riskCapSol(").contains("if (!active()) return Double.POSITIVE_INFINITY"))
        assertTrue(fm.substringAfter("fun noteExit(").contains("if (!active()) return cls"))
        assertTrue(fm.substringAfter("fun withDoctrine7715(").contains("if (!active()) return systemPrompt"))
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("private fun fieldManualBlock7715("))
        assertTrue(fdg.contains("if (!com.lifecyclebot.engine.truth.FieldManual7715.active()) null else {"))

        // Slots: routable capacity bounded to 3..8, computed without the
        // preflight (share() -> slots() would recurse through it).
        val doc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveConcentrationDoctrine7697.kt").readText()
        assertTrue(doc.contains("const val MIN_SLOTS_7717 = 3"))
        assertTrue(doc.contains("const val MAX_SLOTS_7717 = 8"))
        val slotsFn = doc.substringAfter("fun slots(tradeableSol: Double): Int {").substringBefore("fun share(")
        assertTrue(slotsFn.contains("EconomicUnitInvariant7061.usdToSol(com.lifecyclebot.v3.sizing.SmartSizerV3.LIVE_ROUTABLE_MIN_USD_7127, solUsd)"))
        assertTrue(slotsFn.contains("kotlin.math.floor(t / routableMin).toInt()"))
        assertTrue(slotsFn.contains("return capacity.coerceIn(MIN_SLOTS_7717, MAX_SLOTS_7717)"))
        assertFalse(slotsFn.contains("routableCapacityPreflight7224"))
        val sizer = java.io.File("src/main/kotlin/com/lifecyclebot/v3/sizing/SmartSizerV3.kt").readText()
        assertTrue(sizer.contains("        const val LIVE_ROUTABLE_MIN_USD_7127 = 5.0"))

        // Bot-sourced dust is adopted at the $2 floor so it is sold, not left squatting a slot.
        val rec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        assertTrue(rec.contains("private fun isBotSourcedRow7717(p: HostWalletTokenTracker.TrackedTokenPosition?): Boolean ="))
        // 7718 lowered the bot-holding floor to zero; the row test stands.
        assertTrue(rec.contains("return if (isBotSignedRow7708(p) || isBotSourcedRow7717(p)) BOT_HOLDING_ADOPTION_FLOOR_USD_7718 else ADOPTION_MIN_VALUE_USD_7706"))
    }

    @Test
    fun V5_0_7718_nothing_the_bot_buys_is_ever_unmanaged() {
        // Operator: "also nothing the bot buys should ever be unmanaged. that
        // is a hard rule." Three enforcement points: the adoption bridge has
        // no value floor for bot holdings, buy admission kicks the bridge the
        // moment it sees one, and the report measures the rule every time.
        val rec = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveCanonicalRecovery6686.kt").readText()
        assertTrue(rec.contains("private const val BOT_HOLDING_ADOPTION_FLOOR_USD_7718 = 0.0"))
        assertTrue(rec.contains("return if (isBotSignedRow7708(p) || isBotSourcedRow7717(p)) BOT_HOLDING_ADOPTION_FLOOR_USD_7718 else ADOPTION_MIN_VALUE_USD_7706"))
        assertTrue(rec.contains("fun requestAdoptionAsync7718(mints: Collection<String>) {"))
        assertTrue(rec.contains("val n = recoverWalletSnapshot(BotService.status, subset)"))
        assertTrue(rec.contains("WalletAccountCache.snapshot(ttlMs = 60_000L)"))
        assertTrue(rec.contains("private const val HEAL_KICK_MIN_INTERVAL_MS_7718 = 60_000L"))
        assertTrue(rec.contains("BOT_HOLDING_HEAL_KICKED_7718"))
        assertTrue(rec.contains("botHealKicks7718="))
        // External deposits keep the $5 floor: the rule is about what the bot bought.
        assertTrue(rec.contains("private const val ADOPTION_MIN_VALUE_USD_7706 = 5.0"))

        val gate = java.io.File("src/main/kotlin/com/lifecyclebot/engine/sell/LiveBuyAdmissionGate.kt").readText()
        val assess = gate.substringAfter("fun assess(walletAddress: String): Decision {")
        assertTrue(assess.contains("com.lifecyclebot.engine.LiveCanonicalRecovery6686.requestAdoptionAsync7718(unmanaged)"))
        assertTrue(assess.indexOf("requestAdoptionAsync7718(unmanaged)") < assess.indexOf("\"UNMANAGED_BOT_WALLET_HOLDING\","))
        // The 7709 invariant itself is untouched.
        assertTrue(gate.contains("botSource && positive && p.mint in positiveWalletMints && !dustUnroutable7714"))
        assertTrue(gate.contains("walletRaw > canonicalRaw + java.math.BigInteger.ONE || positiveUiWithoutRaw"))

        val phc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.contains("Bot-buy coverage (§7718):"))
        assertTrue(phc.contains("\"VIOLATION unmanagedBotMints=${'$'}{d7718.mints.size}"))
        assertTrue(phc.indexOf("Bot-buy coverage (§7718):") > phc.indexOf("Wallet adoption (§7706):"))

        val fm = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/FieldManual7715.kt").readText()
        assertTrue(fm.contains("Nothing the bot buys is ever unmanaged"))
    }

    @Test
    fun V5_0_7719_the_bot_learns_before_it_tightens_and_the_quote_host_stops_sleeping() {
        // Operator standard (5.0.7716): launch lanes start fluid at about score
        // 15 and $1,500 market cap; tightening after losses only once entry,
        // strategy and hold are proven poor on a real sample; otherwise pivot.
        // Plus two chokes found in the same snapshot: the quote host put to
        // sleep by the market sweep's token lists, and 461 smart-money buys
        // dropped because the copy engine did not know the insider list.

        // Streak shaping is evidence-gated and milder.
        val ea = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutableEntryAuthority6450.kt").readText()
        assertTrue(ea.contains("const val STREAK_EVIDENCE_MIN_CLOSES_7719 = 10"))
        assertTrue(ea.contains("const val STREAK_SHAPE_ONE_7719 = 3"))
        assertTrue(ea.contains("const val STREAK_SHAPE_TWO_7719 = 5"))
        assertTrue(ea.contains("fun laneHasEvidence7719(lane: String): Boolean = try {"))
        assertTrue(ea.contains("STREAK_TIGHTEN_DEFERRED_NO_EVIDENCE_7719"))
        val delta = ea.substringAfter("fun scoreFloorDeltaFor6488(").substringBefore("fun sizeMultiplierFor6488(")
        assertTrue(delta.contains("!streakShapingActive7719(lane, mode) -> 0"))
        assertTrue(delta.contains("STREAK_SHAPE_TWO_7719 -> 10"))
        assertTrue(delta.contains("else -> 5"))
        assertFalse(delta.contains("-> 15"))
        val mult = ea.substringAfter("fun sizeMultiplierFor6488(").substringBefore("// Compatibility telemetry only")
        assertTrue(mult.contains("!streakShapingActive7719(lane, mode) -> 1.0"))
        assertTrue(mult.contains("STREAK_SHAPE_TWO_7719 -> 0.5"))
        assertTrue(mult.contains("else -> 0.75"))
        assertTrue(ea.contains("val streakPrior7181 = sizeMultiplierFor6488(lane, mode)"))
        assertTrue(ea.contains("laneHasEvidence7719(lane) // V5.0.7719"))

        // Pre-buy risk/reward is fluid for launch lanes: cold-start prior and $500 to the mature bars.
        val cs = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CommonSenseTradePlaybook.kt").readText()
        assertTrue(cs.contains("val launchLane7719 = canon(lane).let {"))
        assertTrue(cs.contains("val coldScore7719 = try { ColdStartPriors.coldStartScoreFloor(canon(lane)).toDouble() } catch (_: Throwable) { 15.0 }"))
        assertTrue(cs.contains("fun fluid7719(bootstrap: Double, mature: Double): Double ="))
        assertTrue(cs.contains("tradeType == \"MOMENTUM_SCALP\" -> score >= fluid7719(coldScore7719, 58.0) && liq >= fluid7719(500.0, 1_500.0)"))
        assertTrue(cs.contains("else -> score >= fluid7719(coldScore7719, 42.0) && liq >= fluid7719(500.0, 1_000.0)"))
        assertTrue(cs.contains("COMMON_SENSE_PREBUY_FLUID_LAUNCH_FLOOR_7719"))
        // The absolute sellability floor stays.
        assertTrue(cs.contains("!liquidityKnown || liq < 500.0 -> false"))

        // MOONSHOT's floor: $1,500 at cold start, the 7337 $10k when mature; the fresh-launch admission reads the same number.
        val m = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(m.contains("const val MIN_MARKET_CAP_BOOTSTRAP_USD_7719 = 1_500.0"))
        assertTrue(m.contains("fun minMarketCapUsdFluid7719(): Double {"))
        assertTrue(m.contains("const val MIN_MARKET_CAP_USD = 10_000.0"))
        val a = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/MoonshotFreshLaunchAdmission7044.kt").readText()
        assertTrue(a.contains("if (mcap < com.lifecyclebot.v3.scoring.MoonshotTraderAI.minMarketCapUsdFluid7719()) return no(\"MCAP_BELOW_FLOOR\")"))
        val sc = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue(sc.contains("private const val SC_SCORE_BOOTSTRAP = 15"))

        // The host circuit cools down per host AND provider label, so token lists cannot sleep the quote path.
        val hc = java.io.File("src/main/kotlin/com/lifecyclebot/network/HostCircuitInterceptor.kt").readText()
        assertTrue(hc.contains("val stateKey7719 = if (provider.isNotBlank()) \"${'$'}host|${'$'}provider\" else host"))
        assertTrue(hc.contains("val state = states.getOrPut(stateKey7719) { HostState() }"))
        assertFalse(hc.contains("states.getOrPut(host) { HostState() }"))
        val ms = java.io.File("src/main/kotlin/com/lifecyclebot/engine/market/MarketSweep7297.kt").readText()
        assertTrue(ms.contains("private const val LITE_TOKENS_DEAD_MS_7719 = 30L * 60_000L"))
        assertTrue(ms.contains("if (fail == \"HTTP_401\" || fail == \"HTTP_403\" || fail == \"HTTP_404\") {"))
        assertTrue(ms.contains("MARKET_SWEEP_LITE_TOKENS_DEAD_LATCHED_7719"))

        // A watched insider wallet is a tracked copy wallet.
        val ct = java.io.File("src/main/kotlin/com/lifecyclebot/engine/CopyTradeEngine.kt").readText()
        assertTrue(ct.contains("val tracked = wallets[buyerWallet] ?: insiderAsCopyWallet7719(buyerWallet)?.also {"))
        assertTrue(ct.contains("private fun insiderAsCopyWallet7719(address: String): CopyWallet? = try {"))
        assertTrue(ct.contains("com.lifecyclebot.perps.InsiderWalletTracker.getActiveWallets()"))

        // Crash-loop safe mode: "it runs but I can't get past the login screen. I can't get logs."
        val g = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/StartupCrashGuard7717.kt").readText()
        assertTrue(g.contains("const val CRASH_LOOP_WINDOW_MS_7719 = 15L * 60_000L"))
        assertTrue(g.contains("fun inCrashLoop(): Boolean ="))
        assertTrue(g.contains("fun crashForDisplay(): String? {"))
        assertTrue(g.contains("fun fullCrashText(ctx: Context): String = try {"))
        assertTrue(g.contains("startupCrashStreak = streak"))
        val bs = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertEquals(2, Regex("AUTO_START_REFUSED_CRASH_LOOP_7719\", \"source=").findAll(bs).count())
        assertTrue(bs.contains("if (!userRequested && try { com.lifecyclebot.engine.truth.StartupCrashGuard7717.inCrashLoop() }"))
        val app = java.io.File("src/main/kotlin/com/lifecyclebot/AATEApp.kt").readText()
        assertTrue(app.contains("if (!crashLoop7719) try { scheduleServiceRestart(force = true) } catch (_: Throwable) {}"))
        val sec = java.io.File("src/main/kotlin/com/lifecyclebot/ui/SecurityActivity.kt").readText()
        assertTrue(sec.contains("private fun showLastCrash7719() {"))
        assertTrue(sec.indexOf("showLastCrash7719()") < sec.indexOf("private fun showLastCrash7719()"))
        assertTrue(sec.contains("android.content.ClipData.newPlainText(\"AATE crash\", full)"))
    }

    @Test
    fun V5_0_7720_the_field_manual_leaves_no_footprint_inside_the_giant_evaluate() {
        // 5.0.7715 crashed the app the instant the PIN was accepted; 7716 (a
        // clean revert) worked; 7717/7718 crashed again. The only part of the
        // 7715 diff touching a method with a known ART verifier limit was
        // FinalDecisionGate.evaluate(): two new locals and a FinalDecision(...)
        // block. The repo had met this three times (7415, 7417, 7629). Measured
        // declarations in evaluate(): 503 after 7417, 514 in 7716, 516 in 7715.
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val evalStart = fdg.indexOf("    fun evaluate(\n        ts: TokenState,")
        assertTrue(evalStart > 0)
        val body = fdg.substring(evalStart)
        val code = body.lines().filter { !it.trim().startsWith("//") }.joinToString("\n")
        // One call, no locals, no construction, inside evaluate().
        assertTrue(code.contains("fieldManualBlock7715(ts, candidate, specialistLane, laneName, config.paperMode, proposedSizeSol, mode)?.let { return it }"))
        assertFalse(code.contains("val manualLane7715"))
        assertFalse(code.contains("val manual7715"))
        assertFalse(code.contains("manual7715.blocks"))
        assertFalse(code.contains("\"field_manual_7715\","))
        // The helper builds the whole blocked verdict and sits before evaluate().
        val helperAt = fdg.indexOf("private fun fieldManualBlock7715(")
        assertTrue(helperAt in 1 until evalStart)
        val helper = fdg.substring(helperAt, evalStart)
        assertTrue(helper.contains("): FinalDecision? = try {"))
        assertTrue(helper.contains("if (!v.blocks) null else FinalDecision("))
        assertTrue(helper.contains("\"field_manual_7715\","))
        assertTrue(helper.contains("blockReason = v.blockReason,"))
        // The budget is a required build step, not a diagnostic test.
        val scan = java.io.File("../ci/fdg_evaluate_budget_scan.py").readText()
        assertTrue(scan.contains("MAX_DECLARATIONS = 514"))
        assertTrue(scan.contains("MAX_FINAL_DECISION_RETURNS = 11"))
        val wf = java.io.File("../../.github/workflows/build.yml").readText()
        assertTrue(wf.contains("run: python3 ci/fdg_evaluate_budget_scan.py"))
        assertTrue(wf.indexOf("fdg_evaluate_budget_scan.py") < wf.indexOf("name: Build Release APK"))
    }

    @Test
    fun V5_0_7721_quote_4xx_is_not_an_outage_and_an_old_builds_crash_does_not_gag_a_new_one() {
        // 5.0.7720 snapshot: live preflight JUPITER_QUOTE PASS at transport=73%
        // while the Executor's provider-degraded block computed 35% (it counted
        // 58 candidate 4xx as outages) and refused all 34 live buys
        // (PROVIDER_DEGRADED_BUY_BLOCK_6264=49). Same snapshot: the Field
        // Manual sat suppressed for 313 more minutes because 5.0.7718 had
        // crashed, on a build that no longer carried the cause.
        val ex = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val block = ex.substringAfter("val jupQSr = apiHealth[\"jupiter_quote\"]?.let { s ->").substringBefore("} ?: 1.0")
        assertTrue(block.contains("val transportTotal = s.successes.get() + s.failures5xx.get() + s.networkErrors.get()"))
        assertFalse(block.contains("s.failures4xx.get()"))
        assertTrue(ex.contains("if (dexSr < 0.70 && jupQSr < 0.60) {"))
        val g = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/StartupCrashGuard7717.kt").readText()
        assertTrue(g.contains("if (suppressUntilMs > processStartMs && lastCrashBuild.isNotBlank() && lastCrashBuild != buildTag) {"))
        assertTrue(g.contains("STARTUP_CRASH_GUARD_LIFTED_NEW_BUILD_7721"))
        assertTrue(g.indexOf("thisBuild = buildTag") < g.indexOf("STARTUP_CRASH_GUARD_LIFTED_NEW_BUILD_7721"))

        // Same snapshot: two adopted rows at -0.6% / -1.3% read -54% / -60% through
        // the 7393 fill-basis swap (inferred cost, not a receipt) and fired 486
        // catastrophic exits in five minutes. Adopted rows keep their stamp.
        val bs = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bs.contains("val basisPx7393 = if (fillDisagrees7393 && !adoptedRow7721) {"))
        assertTrue(bs.contains("LIVE_EXIT_BASIS_STAMP_KEPT_ADOPTED_ROW_7721"))
        assertTrue(bs.contains("src7721.contains(\"HOST_TRACKER_SIGNED_BUY_7708\")"))
    }

    @Test
    fun V5_0_7722_learning_chokes_a_streak_needs_evidence_every_lane_explores_and_an_inferred_basis_does_not_teach() {
        // 5.0.7720 snapshot, 478 s: every MOONSHOT request sized at exactly half
        // (req=0.05972 final=0.02986 reason=OK) on a lane with 8 lifetime closes —
        // ColdStreakDamper read a persisted 6..9 loss streak and halved without
        // the 10-close evidence bar 7719 set for streak shaping.
        val csd = java.io.File("src/main/kotlin/com/lifecyclebot/engine/runtime/ColdStreakDamper.kt").readText()
        val damp = csd.substringAfter("fun sizeMultiplier(lane: String, isPaper: Boolean): Double {").substringBefore("val mult = when {")
        assertTrue(damp.contains("ExecutableEntryAuthority6450.laneHasEvidence7719(lane)"))
        assertTrue(damp.contains("COLD_STREAK_DAMP_DEFERRED_NO_EVIDENCE_7722"))
        assertTrue(damp.indexOf("COLD_STREAK_NOT_DAMPED_POSITIVE_EV_7331") < damp.indexOf("COLD_STREAK_DAMP_DEFERRED_NO_EVIDENCE_7722"))
        assertTrue(csd.contains("COLD_STREAK_NOT_DAMPED_POSITIVE_EV_7331"))

        // Same snapshot: CANONICAL_V3_SCORE_FLOOR_7243=622 while TREASURY carried
        // laneScore=70 against floor=16.4 — the single global exploration slot was
        // held by the open MOONSHOT/EXPRESS probes. Every lane now has its own
        // slot; the runner rule (7323) and the pure global predicate are unchanged.
        val lsa = com.lifecyclebot.engine.truth.LaneScoreAdmission7308
        assertTrue(lsa.laneSlotFree7722("TREASURY", 0, 0L, 1_000_000L))
        assertFalse(lsa.laneSlotFree7722("TREASURY", 1, 0L, 1_000_000L))
        assertFalse(lsa.laneSlotFree7722("TREASURY", 0, 900_000L, 1_000_000L))
        assertTrue(lsa.laneSlotFree7722("TREASURY", 0, 700_000L, 1_000_000L))
        assertFalse(lsa.runnerSlotFree("QUALITY", 0, 0L, 1_000_000L))
        assertTrue(lsa.explorationSlotFree(0, 0L, 1_000_000L))
        val lsaSrc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneScoreAdmission7308.kt").readText()
        val now = lsaSrc.substringAfter("fun runnerSlotFreeNow(").substringBefore("fun explorationSlotFreeNow(")
        assertTrue(now.contains("if (runnerFree) return true"))
        assertTrue(now.contains("!com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(l) && laneSlotFree7722(l, open, lastAt, nowMs)"))
        assertTrue(now.contains("LANE_EXPLORATION_LANE_SLOT_7722_"))
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("LaneScoreAdmission7308.runnerSlotFreeNow(floorLane7266)"))

        // Same snapshot: an adopted CRYPTO_SPOT row (HOST_TRACKER_SIGNED, entry=183.80
        // cost=0.0984 qty=0.02906) sold at TICK_CATASTROPHIC_CONFIRMED_-54PCT and
        // booked -0.055 SOL into canonical performance. A close on an inferred
        // basis stays on the bus for the audit and is excluded from every learner.
        val bus = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalTradeFinalizedBus6450.kt").readText()
        assertTrue(bus.contains("val inferredBasis7722: String? = try {"))
        assertTrue(bus.contains("CanonicalPositionAuthority6441.getPosition(event.positionId)?.entryPriceSource?.uppercase()"))
        assertTrue(bus.contains("learningEligible = learningEligibility6519.eligible && economicInvalid6495 == null && inferredBasis7722 == null,"))
        assertTrue(bus.contains("\"INFERRED_BASIS_7722:\$inferredBasis7722\""))
        assertTrue(bus.contains("if (economicInvalid6495 == null && inferredBasis7722 != null) {"))
        assertTrue(bus.contains("FINALIZED_BUS_PUBLISHED_EXCLUDED_INFERRED_BASIS_7722"))
        assertTrue(bus.indexOf("val inferredBasis7722: String? = try {") < bus.indexOf("val env = CanonicalFinalizedTradeBus6464.Envelope("))
        for (k in listOf("OBSERVED_MARK_ADOPTION_7706", "HOST_TRACKER_SIGNED_BUY_7708", "BASIS_UNKNOWN", "RECOVERY_6686")) {
            assertTrue(k, bus.contains("src7722.contains(\"$k\")"))
        }
        assertEquals("5.0.7722", java.io.File("../../AATE_VERSION").readText().trim())
        assertEquals("5.0.7722", java.io.File("../AATE_VERSION").readText().trim())
    }

    @Test
    fun V5_0_7723_the_autonomous_stack_closes_its_loops_and_paper_proof_does_not_sell_live() {
        // Code trace of the 5.0.7720 autonomous stack (promotions=0, liveBarCleared=0,
        // updates=6 of 11 outcomes, strategies=36/36 archived=0 maxGen=2).

        // (1) LabPromotedFeed.shouldExitByPromotedRule read PROMOTED alone (8 paper
        // trades) and could FLAT_EXIT a live MOONSHOT position; the 7106 live bar
        // guarded the entry nudge only. On live only a bar-clearing strategy may.
        val feed = java.io.File("src/main/kotlin/com/lifecyclebot/engine/lab/LabPromotedFeed.kt").readText()
        assertTrue(feed.contains("fun shouldExitByPromotedRule(asset: LabAssetClass, pnlPct: Double, holdMinutes: Long, live: Boolean = false): Boolean {"))
        val exitRule = feed.substringAfter("fun shouldExitByPromotedRule(").substringBefore("fun summary()")
        assertTrue(exitRule.contains("val refusal = try { liveNudgeRefusal7106(s.id, 0.0) } catch (_: Throwable) { \"UNAVAILABLE\" }"))
        assertTrue(exitRule.contains("LAB_EXIT_RULE_LIVE_BAR_REFUSED_7723"))
        assertTrue(exitRule.indexOf("if (!live) true else {") < exitRule.indexOf("if (promoted.isEmpty()) return false"))
        val moon = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()
        assertTrue(moon.contains("live = !pos.isPaperMode,\n                ) && !liveFlatExitSuppressed7695(pos, holdMinutes, pnlPct, \"LAB_PROMOTED_RULE\")"))

        // (2) AutonomousMetaPolicy stamped by mint only; FDG stamps once per lane it
        // evaluates, so the executing lane's stamp was overwritten. Per-lane stamp,
        // credited by the closing lane.
        val amp = java.io.File("src/main/kotlin/com/lifecyclebot/engine/AutonomousMetaPolicy.kt").readText()
        assertTrue(amp.contains("fun recordOutcome(mint: String, pnlPct: Double, lane: String? = null, mode: String? = null) {"))
        assertTrue(amp.contains("pending[laneStampKey7723(mint, lane, mode7725)] = key7723"))
        assertTrue(amp.contains("val key = laneKey7723 ?: mintKey7723 ?: run {"))
        assertTrue(amp.contains("AUTONOMOUS_META_CROSS_MODE_OUTCOME_REFUSED_7725"))
        assertTrue(amp.contains("AUTONOMOUS_META_CREDIT_LANE_STAMP_7723"))
        assertTrue(amp.contains("AUTONOMOUS_META_PENDING_PRUNED_7723"))
        val envSrc = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        assertTrue(envSrc.contains("AutonomousMetaPolicy.recordOutcome(env.mint, env.realizedReturnPct, env.lane, env.mode)"))
        assertFalse(java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText().contains("AutonomousMetaPolicy.recordOutcome("))
        val mp = com.lifecyclebot.engine.AutonomousMetaPolicy
        assertEquals(mp.contextKey("MOONSHOT", 57, "NORMAL", "LIVE"), mp.contextKey("MOONSHOT", 57, "NORMAL", "LIVE"))
        assertFalse(mp.contextKey("MOONSHOT", 57, "NORMAL", "LIVE") == mp.contextKey("MOONSHOT", 57, "NORMAL", "PAPER"))
        val sourceScorecard = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SourceFamilyOpportunityScorecard.kt").readText()
        assertTrue(sourceScorecard.contains("key.split(',', '+', '|')"))
        assertTrue(sourceScorecard.contains(".put(\"live\", exportStats7403(liveStats7403))"))
        assertTrue(sourceScorecard.contains(".put(\"paper\", exportStats7403(paperStats7403))"))
        assertTrue(sourceScorecard.contains("restore(liveStats7403, o.optJSONArray(\"live\"))"))
        assertTrue(sourceScorecard.contains("if (!trade.side.equals(\"SELL\", true)) return"))

        // (3) The Lab froze at MAX_LIVE_STRATEGIES: creation skipped at cap and the
        // only cull needed 30 trades on one strategy. A slot is freed (idle 24 h,
        // else worst loser with a paper-promotion sample) before each creation.
        val lab = java.io.File("src/main/kotlin/com/lifecyclebot/engine/lab/LlmLabEngine.kt").readText()
        assertTrue(lab.contains("if (LlmLabStore.activeStrategies().size >= MAX_LIVE_STRATEGIES) retireForSlot7723()"))
        assertTrue(lab.indexOf("retireForSlot7723()") < lab.indexOf("LAB_CREATION_SKIPPED_AT_CAP_7104"))
        assertTrue(lab.contains("private fun retireForSlot7723() {"))
        assertTrue(lab.contains("private const val IDLE_RETIRE_MS_7723 = 24L * 60L * 60_000L"))
        assertTrue(lab.contains("val active = LlmLabStore.allStrategies().filter { it.status == LabStrategyStatus.ACTIVE }"))
        assertTrue(lab.contains("LAB_SLOT_FREED_7723"))
        assertTrue(lab.contains("LAB_SLOT_NOT_FREED_NO_CANDIDATE_7723"))
        assertEquals("5.0.7727", java.io.File("../../AATE_VERSION").readText().trim())
        assertEquals("5.0.7727", java.io.File("../AATE_VERSION").readText().trim())
    }

}
