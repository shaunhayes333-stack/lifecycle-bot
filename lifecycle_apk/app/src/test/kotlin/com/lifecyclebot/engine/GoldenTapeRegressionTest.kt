Y™Áäx-ÆÈ‹j◊ù¢Îi∫⁄+äßj[hëÈ‹¢ÈÌﬂO<Ám|ÎÕˆo+^≤â¢∂◊ùpackage com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.9.1562 ‚Äî Golden-tape blocker taxonomy harness.
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
            "Safety: Rugcheck pending ‚Äî live mode, no high-score override",
            "Safety: Rugcheck API timeout (live: PENDING_REVIEW)",
            "Safety: SAFETY_RUN_FAILED_PARTIAL_DATA: timeout",
            "Safety: LOW_LIQUIDITY: \$900 < \$1200",
            "Safety: Liquidity \$900 < \$1,200 live exit-safety floor ‚Äî un-exitable",
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
        val recordIdx = source.indexOf("V5.9.1570 ‚Äî Express FDG verdict")
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
        assertTrue(sent.contains("DRAWDOWN CIRCUIT: üõ°Ô∏è DEFENSIVE"))
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
        assertTrue(bot.contains("val expressFinalSize = expressFdg?.sizeSol ?: expressSignal.positionSizeSol.coerceAtLeast(0.01)"))
        assertTrue(bot.contains("sizeSol = expressFinalSize"))
        assertTrue(bot.contains("entrySol = expressFinalSize"))
        val start = bot.indexOf("val expressFinalSize")
        val end = bot.indexOf("addLog(\"üí©üöÇ EXPRESS:", start)
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
        assertTrue("Pipeline block must be core-only so learning/tuning is not duplicated", hub.contains("PIPELINE HEALTH ‚Äî CORE") && !hub.contains("PIPELINE HEALTH ‚Äî CONDENSED", ignoreCase = false))
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
        assertTrue("Legacy journal consumers must receive net-normalized pnlPct before TradeHistoryStore", exec.contains("paper‚Üílive transfer authority") && exec.contains("PAPER_LIVE_TRANSFER_NET_PCT_NORMALIZED") && exec.indexOf("paper‚Üílive transfer authority") < exec.indexOf("TradeHistoryStore.recordTrade(tradeWithMint)"))
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
        assertTrue("TradeJournal stats must validate JournalEntry rows with entry basis fields", journal.contains("JournalEntry‚ÜíTrade validation") && journal.contains("entryPriceSnapshot = e.entryPrice") && journal.contains("entryCostSol = e.entryCostSol") && journal.contains("price = if (sellLike) e.exitPrice else e.entryPrice"))
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
        assertFalse("SCORE_TOO_LOW must not become FDG green tiny probe", bot.contains("V3 REJECT‚ÜíPROBE"))
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
            // V5.0.4135 ‚Äî workflow now composes VERSION_NAME from BASE + BUILD_NUMBER
            // (operator override 2026-06-25 ‚Äî see apk_version_patch_derived_from_ci_run_number).
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
        // V5.0.3915 ‚Äî operator dump 06-19 19:28: the previous semantic
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
        // V5.0.3746 ‚Äî operator spec items 1, 4, 5, 7, 9, 11.
        // BALANCE_UNKNOWN must hand the mint to BalanceProofPoller via the
        // WAITING_BALANCE_PROOF state ‚Äî it MUST NOT enter PendingSellQueue
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
        assertFalse("one provider missing mint must not be zero", sellAuthority.contains("mint NOT in the map AND map is non-empty ‚Üí genuine zero"))

        assertTrue(tracker.contains("markNoCurrentHeldProof"))
        assertTrue(tracker.contains("RPC_EMPTY_MAP_MINT_ABSENT"))
        assertTrue(tracker.contains("NO_CURRENT_HELD_PROOF"))
        assertTrue(tracker.contains("HISTORICAL_RAW_NOT_CURRENT_HELD_PROOF"))
        assertTrue(tracker.contains("STALE_RECOVERY_UNPROVEN"))
        assertFalse("no current proof must not become open recovery", tracker.contains("OPEN_BALANCE_UNKNOWN_RECOVERY_REQUIRED"))
        assertTrue(tracker.contains("hasLastPositiveRaw(p)"))
        assertTrue(tracker.contains("isOpenForAccounting"))
        assertTrue(tracker.contains("p.zeroBalanceConfirmedByTwoProviders"))
        assertFalse("ghost reaper must not emit the old unverified close marker", tracker.contains("GHOST_REAPED zero-balance open row ‚Üí CLOSED"))
        assertFalse("startup reconcile must not close live rows from a bare wallet=0", tracker.contains("STARTUP_GHOST_RECONCILE wallet=0 ‚Üí CLOSED"))

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
        // V5.0.6060 ‚Äî operator directive: revert daily batching, transfer fees per-cycle live.
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
            tracker.contains("REAP_SKIPPED_BALANCE_UNKNOWN mint absent from one wallet snapshot ‚Äî keeping open"))
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
        // V5.0.6405 ¬ß18 ‚Äî the (sol/qty)√ósolUsd formula was refactored into
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
        assertTrue(src.contains("Invalid price input ‚Äî hold until trustworthy price"))
        assertFalse("invalid guide/basis price must not force a sell", src.contains("Invalid price input ‚Äî forced exit"))
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
            exec.contains("SELL RPC EMPTY/TIMEOUT: getTokenAccountsWithDecimals ‚Äî proceeding via RPC-EMPTY rescue"))
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

        // Fault #6 ‚Äî header Execution state must derive from BotRuntimeController, not a hardcoded ACTIVE.
        assertTrue(pipe.contains("BotRuntimeController.snapshot()"))
        assertTrue(pipe.contains("POST_STOP_SNAPSHOT"))
        assertTrue(pipe.contains("RuntimeState.STOPPED"))
        assertTrue(pipe.contains("RuntimeState.STOPPING"))
        assertTrue(pipe.contains("RuntimeState.STARTING"))
        assertFalse("header must not unconditionally guess ACTIVE without consulting runtime authority",
            pipe.contains("val state = if (blockedMs > 0 && ageSec in 0..120) {"))

        // Fault #1 ‚Äî manual stop finalizes persistence and onDestroy must not re-save stale rows.
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
        // OPEN_TRACKING mint at dust must reap it ‚Äî not keep it open and latch
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
        assertTrue(cls.contains("no signature ‚Äî clear lock unless retry scheduled"))

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

        val reqGuard = exec.indexOf("V5.0.3801 ‚Äî PAPER source guard before any executor activity")
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
        val buyBlock = exec.substring(exec.indexOf("internal fun doBuy"), exec.indexOf("// V5.9.401 ‚Äî Sentience hook #7"))
        assertTrue("Sentience pre-trade veto must be advisory telemetry only", buyBlock.contains("SENTIENCE_VETO_ADVISORY_4189") && buyBlock.contains("ignored_no_hard_veto") && !buyBlock.contains("LLM SENTIENCE VETO"))
        assertTrue("Emergent LLM BLOCK must not return before live buy", buyBlock.contains("EMERGENT_LLM_BLOCK_ADVISORY_4189") && buyBlock.contains("LLM BLOCK ADVISORY") && !buyBlock.contains("üß† LLM BLOCK: ${'$'}{ts.symbol}"))
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
        assertTrue("Unified report must use compact core pipeline, not raw full dump only", hub.contains("compactPipelineDump(PipelineHealthCollector.dumpText())") && hub.contains("PIPELINE HEALTH ‚Äî CORE"))
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
        val scorer = java.io.File("src/main/k◊]µÁkhëÈÏ∂ªßq´^tÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹¿ËÅâÖÕîÅÕ—Ö—îπΩ¡ïπAΩÕ•—•ΩπÃÅµ’Õ–ÅâîÅô•±—ï…ïêÅâ‰Å±•ŸîÅ°ΩÕ–Å—…’—†Å•∏Å1%YÅµΩëîà∞ÅµÖ•∏πçΩπ—Ö•πÃ†àπô•±—ï»ÅÏÅ•ÕAÖ¡ï…5ΩëîÅÒÅ±•Ÿï=¡ïπAÖπï±Q…’—†–‘‹¿°•–πµ•π–§ÅÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹¿ËÅÕÂπ—°ïÕ•ÈïêÅÕ’àµ—…Öëï»Å…Ω›ÃÅµ’Õ–ÅπΩ–Å…ïÕ’……ïç–ÅÕΩ±êΩç±ΩÕïêÅ±•ŸîÅ¡ΩÕ•—•ΩπÃà∞ÅµÖ•∏πçΩπ—Ö•πÃ†â=A9}A91}Me9Q!}MQ1}M-%AA|–‘‹¿à§ÄòòÅµÖ•∏πçΩπ—Ö•πÃ†â•òÄ†Ö•ÕAÖ¡ï»ÄòòÄÖ±•Ÿï=¡ïπAÖπï±Q…’—†–‘‹¿°µ•π–§§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹¿ËÅ±•ŸîÅ¡Öπï∞Åµ’Õ–ÅπΩ–ÅÕ°Ω‹Å¡Ö¡ï»µµΩëîÅÕÂπ—°ï—•åÅ…Ω›ÃÅ›°ï∏Å…’π—•µîÅ•ÃÅ±•Ÿîà∞ÅµÖ•∏πçΩπ—Ö•πÃ†â•òÄ°•ÕAÖ¡ï»ÄÑÙÅ•ÕAÖ¡ï…5Ωëî§Å…ï—’…∏à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‹…1•ŸïA…ΩâÖâ•±•—ÂIÖ¡•ëIïïë’çÖ—•ΩπΩïÕ9Ω—	ΩΩ—Õ—…Ö¡=…iï…ΩM•Èî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ…åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•ŸïA…ΩâÖâ•±•—Âπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹»ËÅ±•ŸîÅ¡…ΩâÖâ•±•—‰Åµ’Õ–Å’ÕîÅ…Ö¡•êÅ…îµïë’çÖ—•Ω∏Å•πÕ—ïÖêÅΩòÅ¡Ö•êÅâΩΩ—Õ—…Ö¿Å—’•—•Ω∏à∞ÅÕ…åπçΩπ—Ö•πÃ†â1%YÅIA%ÅIµUQ%=8∞Å9=PÅA%Å	==QMQI@ÅQU%Q%=8à§ÄòòÅÕ…åπçΩπ—Ö•πÃ†ââÖëQ›ΩQ…ÖëïXà§ÄòòÅÕ…åπçΩπ—Ö•πÃ†âπΩ}±•Ÿï}âΩΩ—Õ—…Ö¡}—’•—•Ω∏ı—…’îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹»ËÅ—Ω·•åÅ±•ŸîÅâ’ç≠ï—ÃÅµ’Õ–Å¡•ŸΩ–Å•πÕ•ëîÅ±ÖπîÅ›•—†Åï·ïç’—Öâ±îÅô±ΩΩ»∞ÅπΩ–Å±ïÖ…πïêÅÈï…ºµÕ•Èîà∞ÅÕ…åπçΩπ—Ö•πÃ†â9QIe}AI=		%1%Qe}IA%}A%Y=Q}M!A|–‘‹»à§ÄòòÅÕ…åπçΩπ—Ö•πÃ†âÖç—•Ω∏ı±Öπï}±ΩçÖ±}—Öç—•ç}¡•ŸΩ–à§ÄòòÅÕ…åπçΩπ—Ö•πÃ†âÕ•Èï±ΩΩ»Ù¿∏Ã‘à§ÄòòÅÕ…åπçΩπ—Ö•πÃ†âçΩï…çï—1ïÖÕ–†¿∏Ã‘§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏–‘‹»ËÅΩ±êÅ±ïÖ…πïêÅ°Ö…êµÕ—Ω¿Å—ï±ïµï—…‰Åµ’Õ–ÅπΩ–ÅÕ’…Ÿ•Ÿîà∞ÅÕ…åπçΩπ—Ö•πÃ†â9QIe}AI=		%1%Qe}19}!I}MQ=AAà§ÅÒÅÕ…åπçΩπ—Ö•πÃ†âÕ•ÈóäH¿∏¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏–‘‹»ËÅ±•ŸîÅ¡…ΩâÖâ•±•—‰ÅÕ—Ö—’ÃÅµ’Õ–ÅπΩ–Åç±Ö•¥Å±•ŸîÅâΩΩ—Õ—…Ö¿ΩπºÅµÖ—’…îÅ±ÖπïÃà∞ÅÕ…åπçΩπ—Ö•πÃ†ââΩΩ—Õ—…Ö¿ΩπºÅµÖ—’…îÅ±•ŸîÅ±ÖπïÃà§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‹ÕΩµµΩπMïπÕïA±ÖÂâΩΩ≠%Õ]•…ïëQ°…Ω’ù°1•Ÿï	’Â]•—°Ö—Ö!ï±¡ï»†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å¡±ÖÂâΩΩ¨ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩΩµµΩπMïπÕïQ…ÖëïA±ÖÂâΩΩ¨π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åë•ùïÕ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ=¡ï…Ö—Ω…’·•±•Ö…ÂM—Ö—’Õ•ùïÕ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÃËÅçΩµµΩ∏µÕïπÕîÅ¡±ÖÂâΩΩ¨Å°ï±¡ï»Åµ’Õ–Åï·•Õ–Å›•—†ÅçÖç°ïêÅÕ•ëîµïôôïç–Å…ïô…ïÕ†à∞Å¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âX‘∏¿∏–‘‹Õ}=55=9}M9M}A1e	==,à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â¡¡•Õ¡Ö—ç°ï…ÃπÕ•ëïôôïç–à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âΩπç’……ïπ—!ÖÕ°5Ö¿à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âÖÕÕïÕÕA…ï	’‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÃËÅ¡±ÖÂâΩΩ¨Åµ’Õ–ÅïπôΩ…çîÅÖπ—§µÕ—’¡•êÅ°Ö…êÅëÖ—ÑΩÕÖôï—‰ÅâÖÕ•çÃà∞Å¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âAI%}	M%M}U9-9=]8à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âM11}I=UQ}U9-9=]8à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âQ=-9}5A}%9=5A1Qà§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âMQe}=I}!=1I}I%M,à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹‘ËÅÖµâ•ù’Ω’ÃÅÕ—…’ç—’…îΩIHÅµ’Õ–ÅÕ°Ö¡îΩ¡•ŸΩ–Å•πÕ—ïÖêÅΩòÅâ±’π—±‰Åç°Ω≠•πúÅÕï—’¿µ…•ç†Å—…ÖëïÃà∞Å¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â=55=9}M9M}AI	Ue}M!A|–‘‹‘à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âI%M-}I]I}IU}M%ià§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â%9I}%9Y1%Q%=9}I=5}MQU@à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â—…Öëï}Õï—’¡}¡•ŸΩ—}πΩ—}â±Ωç¨à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÃËÅ±•Ÿï	’‰Åµ’Õ–ÅçÖ±∞Å—°îÅ¡±ÖÂâΩΩ¨ÅâïôΩ…îÅ±•ŸîÅÕ¡ïπêà∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†âΩµµΩπMïπÕïQ…ÖëïA±ÖÂâΩΩ¨πÖÕÕïÕÕA…ï	’‰à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â=55=9}M9M}AI	Ue|à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â	Ue}QI5%91}=55=9}M9Mà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÃËÅ¡±ÖÂâΩΩ¨ÅçΩπô•ëïπçîÅµ’Õ–ÅôïïêÅ—°îÅï·•Õ—•πúÅ±•ŸîÅÕ•ÈîÅÕ—Öç¨à∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†âçΩµµΩπMïπÕïM•Èï5’±—•¡±•ï»–‘‹Ãà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â=55=9}M9M}M%i}AA1%|–‘‹Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÃËÅΩ¡ï…Ö—Ω»Åë•ùïÕ–Åµ’Õ–ÅÕ’…ôÖçîÅ¡±ÖÂâΩΩ¨ÅÕ—Ö—îà∞Åë•ùïÕ–πçΩπ—Ö•πÃ†âΩµµΩπMïπÕïQ…ÖëïA±ÖÂâΩΩ¨πÕ—Ö—’Õ1•πîà§ÄòòÅë•ùïÕ–πçΩπ—Ö•πÃ†â¡±ÖÂâΩΩ≠}ï·ïç’—•Ωπ}Ö’—°Ω…•—‰ı·ïç’—Ω»π±•Ÿï	’‰à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÿ¿»¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»¿ËÅÕçΩ…îÅô±ΩΩ…ÃÅµ’Õ–ÅâîÅô±’•êÅÕçÖôôΩ±ë•πúÅ—°Ö–ÅÕΩô–µÕ—Ö…—Ã∞Å—•ù°—ïπÃ∞Å—°ï∏Åëï±ïùÖ—ïÃÅ—ºÅ$ΩMM$ÅÖ’—°Ω…•—‰à∞ÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â1U%}M=I}M=1|ÿ¿»¿à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âUπ•ô•ïëAΩ±•çÂ!ïÖêπç’……ïπ—’—°Ω…•—‰à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â’—°Ω…•—ÂQ•ï»π	==QMQI@Ä¥¯Ä°Õ—…’ç—’…Ö±±ΩΩ»ÿ¿»¿Ä¥Äƒ‡∏¿à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â’—°Ω…•—ÂQ•ï»πUQ!=I%QQ%YÄ¥¯Ä¿∏¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»¿ËÅùΩΩêÅµïµîÅ±ÖπïÃÅµ’Õ–Åùï–Å…ïÖ∞ÅŸΩ±’µîÅ¡•ŸΩ—Ã∞ÅπΩ–Å¡…ΩâîµΩπ±‰ÅÕ—Ö…ŸÖ—•Ω∏à∞ÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â==}19}]%Q}Y=1U5}A%Y=Q|ÿ¿»¿à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âEU1%Qdà§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âM!8à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âQIMUIdà§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â==}19}9=}MQIUQUI}Y=1U5}A%Y=Q|ÿ¿»¿à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âëÖπùï…Ω’ÕM—…’ç—’…îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»ƒËÅΩµµΩπMïπÕîÅµ’Õ–Å…ïÖêÅçÖç°ïêÅ$Ω—ΩΩ±≠•–Ω…ïÕïÖ…ç†Åâ…Ö•∏ÅçΩπ—ï·–ÅâïôΩ…îÅç±ÖÕÕ•ôÂ•πúÅÕ—…’ç—’…îà∞Å¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âQΩΩ±≠•—M•ùπÖ±M°ïï–πÕπÖ¡Õ°Ω–à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âIïÕïÖ…ç°MçΩ’–π…•Õ≠!•π–à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âU±—•µÖ—ïëùïπù•πîπçÖç°ïêà§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†ââ…Ö•πMï—’¿à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†ââ…Ö•πΩπô•ëïπçîà§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†ââ…Ö•πΩπ—ï·–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»ƒËÅΩµµΩπMïπÕîÅâ…Ö•∏ÅÖççïÕÃÅµ’Õ–Å…ïµÖ•∏Å°Ω–µ¡Ö—†ÅÕÖôîÅÖπêÅÖÕÂπåΩçÖç°îµô•…Õ–à∞Å¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âU±—•µÖ—ïëùïπù•πîπïπ≈’ï’ïIïô…ïÕ†à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âçΩµµΩπ}ÕïπÕï}â…Ö•π}çΩπ—ï·—|ÿ¿»ƒà§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â°Ω–µ¡Ö—†Å¡…ΩŸ•ëï»Ω114Ωπï—›Ω…¨ÅçÖ±∞à§ÄòòÄÖ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âU±—•µÖ—ïëùïπù•πîπïŸÖ±’Ö—î°—Ã§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»»ËÅQΩΩ±≠•–ÅùΩΩêµ±ÖπîÅÕï—’¡ÃÅµ’Õ–Åâ…•ëùîÅ¡…îµÅ±ÖπîÅÕ—Ö…ŸÖ—•Ω∏Å•π—ºÅ…ïÖ∞ÅÅçÖπë•ëÖ—ïÃà∞ÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âQΩΩ±≠•—ΩΩë1Öπï	…•ëùîÿ¿»»à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âQ==1-%Q}==}19}	I%|ÿ¿»»à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âQ==1-%Q}	I%|ëÏúêùıÌ±Öπï-ïÂÙà§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†âÖç—•Ω∏ıÕïπë}—Ω}ôëúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»»ËÅE’Ö±•—‰Å	±’ï°•¿ÅÖπêÅQ…ïÖÕ’…‰Åµ’Õ–ÅçΩπÕ’µîÅâ…•ëùïêÅÕ•ùπÖ∞ÅçΩ¡•ïÃÅ•πÕ—ïÖêÅΩòÅ…ï≈’•…•πúÅ±ïùÖç‰ÅÕ°Ω’±ëπ—ï»à∞ÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â≈’Ö±•—ÂM•ùπÖ∞ÿ¿»»à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†ââ±’ï°•¡M•ùπÖ∞ÿ¿»»à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â—…ïÖÕ’…ÂM•ùπÖ∞ÿ¿»»à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â≈’Ö±•—ÂM•ùπÖ∞πçΩ¡‰à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†ââ±’ï°•¡M•ùπÖ∞πçΩ¡‰à§ÄòòÅâΩ–ÿ¿»¿πçΩπ—Ö•πÃ†â—…ïÖÕ’…ÂM•ùπÖ∞πçΩ¡‰à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•∏ÿ¿»–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‡‘ËÅµÖ•∏ÅëÖÕ°âΩÖ…êÅ°ïÖë±•πîÅµ’Õ–Å’ÕîÅ—°îÅï·Öç–Å)Ω’…πÖ±ç—•Ÿ•—‰Å±•ôïçÂç±îµ…Ω‹Å¡Ö…•—‰Å°ï±¡ï»∞ÅπΩ–ÅçÖç°ïêÅç±ïÖ∏ÅÕ—Ö—Ãà∞ÅµÖ•∏ÿ¿»–πçΩπ—Ö•πÃ†âaPÅ)=UI90Å!HÅAI%QdÅ!1AHà§ÄòòÅµÖ•∏ÿ¿»–πçΩπ—Ö•πÃ†âùï—±±YÖ±•ëQ…ÖëïÕMπÖ¡Õ°Ω–†’|¿¿¿§à§ÄòòÅµÖ•∏ÿ¿»–πçΩπ—Ö•πÃ†âùï—±±Q…ÖëïÕ…Ωµà†§à§ÄòòÅµÖ•∏ÿ¿»–πçΩπ—Ö•πÃ†âQ…Öëï)Ω’…πÖ∞°Ö¡¡±•çÖ—•ΩπΩπ—ï·–§πùï—M—Ö—Õ•±—ï…ïê°ïπ—…•ïÃ§à§ÄòòÅµÖ•∏ÿ¿»–πçΩπ—Ö•πÃ†â©Ω’…πÖ±AÖ…•—ÂM—Ö—ÕMπÖ¡Õ°Ω–ÿ¿‡‘†§Ä¸ËÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπQ…Öëï!•Õ—Ω…ÂM—Ω…îπùï—M—Ö—ÕÖç°ïê†§à§ÄòòÄÖµÖ•∏ÿ¿»–πçΩπ—Ö•πÃ†â10ÅQIILÅ°ïÖë±•πîÅ’ÕïÃÅM—…Ö—ïùÂQ…’—°1ïëùï»µç±ïÖ∏Å—…’—†à§§(ÄÄÄÄÄÄÄÅŸÖ∞Åôëúÿ¿»‘ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ•πÖ±ïç•Õ•ΩπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‘ËÅÅÕçΩ…îÅùÖ—ïÃÅµ’Õ–ÅçΩπÕ’µîÅïôôïç—•ŸîÅ±ÖπîΩ$ÅçΩπÕïπÕ’ÃÅÕçΩ…îÅô…Ω¥Å—…ÖëîÄƒà∞Åôëúÿ¿»‘πçΩπ—Ö•πÃ†âïôôïç—•ŸïÖ—ïMçΩ…îÿ¿»‘à§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†â}Q%Y}Q}M=I|ÿ¿»‘à§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†âÕçΩ…ï}ùÖ—ïÕ}’Õï}çΩπÕïπÕ’Õ}ô…Ωµ}—…Öëîƒà§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†âUπ•ô•ïëAΩ±•çÂ!ïÖêπç’……ïπ—’—°Ω…•—‰°±Öπï9Öµî§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‘ËÅÅ’π≠πΩ›∏µ¡°ÖÕîÅÖπêÅ±•ŸîµïëùîÅùÖ—ïÃÅµ’Õ–Å’ÕîÅïôôïç—•ŸîÅÕçΩ…îÅ›°•±îÅ±Ωùù•πúÅ…Ö‹Ω±ÖπîÅÕ¡±•–à∞Åôëúÿ¿»‘πçΩπ—Ö•πÃ†âŸÖ∞Å•Õ!•ù°MçΩ…îÄÙÅïôôïç—•ŸïÖ—ïMçΩ…îÿ¿»‘Ä¯ÙÅµ•πMçΩ…îà§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†âŸÖ∞Å°ÖÕïçïπ—MçΩ…îÄÙÅïôôïç—•ŸïÖ—ïMçΩ…îÿ¿»‘Ä¯ÙÅ±•Ÿï5•ππ—…ÂMçΩ…îà§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†â…Ö‹ÙëÏúêùıÌçÖπë•ëÖ—îπïπ—…ÂMçΩ…îπ—Ω%π–†•ÙÅ±ÖπîÙëÏúêùıÌ±ÖπïΩπÕïπÕ’ÕMçΩ…îÿ¿»‘π—Ω%π–†•Ùà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‘ËÅ	…Ö•πΩπÕïπÕ’ÕÖ—îÅµ’Õ–ÅïŸÖ±’Ö—îÅ—°îÅïôôïç—•ŸîµùÖ—îÅçÖπë•ëÖ—î∞ÅπΩ–ÅÕ—Ö±îÅ…Ö‹ÅXÃÅÕçΩ…îà∞Åôëúÿ¿»‘πçΩπ—Ö•πÃ†âôëùÖ—ïÖπë•ëÖ—îÿ¿»‘à§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†â	…Ö•πΩπÕïπÕ’ÕÖ—îπïŸÖ±’Ö—î°—Ã∞ÅôëùÖ—ïÖπë•ëÖ—îÿ¿»‘∞ÅµΩëïQÖú§à§§(ÄÄÄÄÄÄÄÅŸÖ∞Åôëù	…Ö•∏ÿ¿»ÿÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩëù	…Ö•π°Ö•∏π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÿ¿»ÿÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»ÿËÅÅµ’Õ–Å°ÖŸîÅÖ∏Åï·¡±•ç•–Åâ…Ö•∏µç°Ö•∏ÅÖù…ïïÖâ•±•—‰Å±ÖÂï»à∞Åôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†âΩâ©ïç–Åëù	…Ö•π°Ö•∏à§ÄòòÅôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†âïπ’¥Åç±ÖÕÃÅYï…ë•ç–ÅÏÅ1%9∞Å=91%Q∞Å	1=-%9ÅÙà§ÄòòÅôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†âçΩµµΩπ}ÕïπÕîà§ÄòòÅôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†âÖπ—•ç°Ω≠ï}ÕΩô—ïπ•πúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»ÿËÅÅµ’Õ–ÅÕΩô—ï∏ÅπΩ∏µ°Ö…êÅâ±Ωç≠ï…ÃÅ›°ï∏Å—°îÅâ…Ö•∏Åç°Ö•∏ÅÖ±•ùπÃà∞Åôëúÿ¿»‘πçΩπ—Ö•πÃ†âëù	…Ö•π°Ö•∏πïŸÖ±’Ö—îà§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†â}	I%9}!%9|ÿ¿»ÿà§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†â}	I%9}M=Q9}	1=-|ÿ¿»ÿà§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†âÕΩô—	±Ωç≠IïÖÕΩ∏ÿ¿»ÿà§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†ââ±Ωç≠1ïŸï∞ÄÑÙÅ	±Ωç≠1ïŸï∞π!Ià§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»ÿËÅΩµµΩπMïπÕîÅ¡…ïâ’‰Åµ’Õ–ÅâïçΩµîÅç°Ö•∏µÕ°Ö¡ïêÅçÖ’—•Ω∏∞ÅπΩ–Åâ±•πêÅï·ïç’—Ω»ÅΩâÕ—…’ç—•Ω∏à∞Åï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†â=55=9}M9M}AI	Ue}M=Q9|ÿ¿»ÿà§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†âçΩµµΩπMïπÕï!Ö…êÿ¿»ÿà§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†âëù	…Ö•π°Ö•∏πïŸÖ±’Ö—îà§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†â}	I%9}=55=9}M9M}M=Q9|ÿ¿»ÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‹ËÅÅâ…Ö•∏Åç°Ö•∏Åµ’Õ–Åë…•ŸîÅÕ•È•πúΩçΩµ¡Ω’πë•πúÅ—Ö…ùï—Ã∞ÅπΩ–ÅΩπ±‰Åâ±Ωç≠ï»Å…ïçΩπç•±•Ö—•Ω∏à∞Åôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†âçΩµ¡Ω’πë•πù5’±—•¡±•ï»à§ÄòòÅôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†â—Ö…ùï—5Ωëîà§ÄòòÅôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†â=5A=U9à§ÄòòÅôëù	…Ö•∏ÿ¿»ÿπçΩπ—Ö•πÃ†âEU1%Qe}Y=1U5à§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†â}	I%9}=5A=U9%9}QIQ|ÿ¿»‹à§ÄòòÅôëúÿ¿»‘πçΩπ—Ö•πÃ†âôëù}â…Ö•π}çΩµ¡Ω’πë•πúà§§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ö±±ï—Ωµ¿ÿ¿»‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïÖ±•Èïë]Ö±±ï—Ωµ¡Ω’πë•πùΩŸï…πΩ»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‡ËÅçΩµ¡Ω’πë•πúÅùΩŸï…πΩ»Åµ’Õ–Å•πç±’ëîÅ—…’Õ—ïêÅΩ¡ï∏Å±•ŸîÅï≈’•—‰Å¡…ïÕÕ’…îÅ›•—°Ω’–ÅôÖ≠•πúÅ©Ω’…πÖ∞ÅAπ0à∞Å›Ö±±ï—Ωµ¿ÿ¿»‡πçΩπ—Ö•πÃ†â—…’Õ—ïë=¡ïπ1•Ÿï≈’•—‰à§ÄòòÅ›Ö±±ï—Ωµ¿ÿ¿»‡πçΩπ—Ö•πÃ†â=¡ïπAπ±MÖπ•—‰π•πÕ¡ïç–à§ÄòòÅ›Ö±±ï—Ωµ¿ÿ¿»‡πçΩπ—Ö•πÃ†â—…’Õ—ïë}Ω¡ïπ}ï≈’•—Â}çΩµ¡Ω’πë}¡…ïÕÕ’…ï|ÿ¿»‡à§ÄòòÅ›Ö±±ï—Ωµ¿ÿ¿»‡πçΩπ—Ö•πÃ†âΩ¡ïπQ…’Õ—ïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‡ËÅ±•ŸîÅ…’ππï…ÃÅ›•—†Å›Ö±±ï–µµÖ—ï…•Ö∞Å’π…ïÖ±•ÈïêÅ¡…Ωô•–Åµ’Õ–Å°Ö…ŸïÕ–ÅM=0Å—°…Ω’ù†ÅÕï±∞Åô•πÖ±•—‰à∞Åï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†â]11Q}I=]Q!}!IYMQ}QI%I|ÿ¿»‡à§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†â±Öπë}…’ππï…}¡…Ωô•—}•π}›Ö±±ï–à§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†âï·ïç’—ïA…Ωô•—1Ωç≠Mï±∞°—Ã∞Å›Ö±±ï–∞ÅÕï±±…Öç—•Ω∏ÿ¿»‡à§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†â]11Q}I=]Q!}!IYMQ}II}AI%}U9I1|ÿ¿»‡à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïÖ±A…•çï1Ωç¨ÿ¿»‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïÖ±A…•çï1Ωç¨π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‰ËÅ…Ω’—îµ…ïÖ∞Å¡…Ωô•–Åµ’Õ–ÅâîÅ°Ö…ŸïÕ—Öâ±îÅïŸï∏Å›°ï∏ÅU$Åç±Ö•¥Å•ÃÅΩŸï…Õ—Ö—ïêà∞Å…ïÖ±A…•çï1Ωç¨ÿ¿»‰πçΩπ—Ö•πÃ†â…Ω’—ï%µ¡±•ïëÖ•π5’±—•¡±îà§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†âI=UQ}I1}1%5}5%M5Q!}!IYMQ|ÿ¿»‰à§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†â’•}ç±Ö•µ}ΩŸï…Õ—Ö—ïë}â’—}…Ω’—ï}¡…Ωô•—}…ïÖ∞à§ÄòòÅï·ïåÿ¿»ÿπçΩπ—Ö•πÃ†â…Ω’—ï}…ïÖ±}°Ö…ŸïÕ—|à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•πU§ÿ¿»‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‰ËÅΩ¡ï∏µ¡ΩÕ•—•Ω∏ÅU$Åµ’Õ–Å±Öâï∞Å’π…ïÖ±•ÈïêÅ…Ω’—îÅ—…’—†Å•πÕ—ïÖêÅΩòÅ•µ¡±Â•πúÅ›Ö±±ï–µ…ïÖ∞ÅµΩπï‰à∞ÅµÖ•πU§ÿ¿»‰πçΩπ—Ö•πÃ†â±ÖÕ—IΩ’—ïQ…’—†à§ÄòòÅµÖ•πU§ÿ¿»‰πçΩπ—Ö•πÃ†âU9I1%iÉ
‹Å…Ω’—îÅ¡ïπë•πúà§ÄòòÅµÖ•πU§ÿ¿»‰πçΩπ—Ö•πÃ†âç±Ö•¥µµ•ÕµÖ—ç†à§ÄòòÅµÖ•πU§ÿ¿»‰πçΩπ—Ö•πÃ†âI=UQÅ¯à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÿ¿»‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIï¡Ω…—•πù!’àπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿»‰ËÅ’π•ô•ïêÅ…ï¡Ω…–Åµ’Õ–Å—…ÖçîÅë•ÕçΩŸï…‰ΩΩ¡ï∏Ω…Ω’—îΩÕï±∞Ω©Ω’…πÖ∞Ω›Ö±±ï–ÅµΩπï‰Å—…’—†à∞Å…ï¡Ω…–ÿ¿»‰πçΩπ—Ö•πÃ†â5=9dÅAQ ÅQIUQ à§ÄòòÅ…ï¡Ω…–ÿ¿»‰πçΩπ—Ö•πÃ†ââ’•±ë5ΩπïÂAÖ—°Q…’—°M’µµÖ…‰ÿ¿»‰à§ÄòòÅ…ï¡Ω…–ÿ¿»‰πçΩπ—Ö•πÃ†âΩ¡ïπ}’π…ïÖ±•Èïë}πΩ—}›Ö±±ï—}’π—•±}Õï±±}ô•πÖ±•—‰à§ÄòòÅ…ï¡Ω…–ÿ¿»‰πçΩπ—Ö•πÃ†â—…’—†ÅçΩπ—…Öç–ËÅ%M=YIdÅ¡…•çîΩÕΩ’…çîÄ¥¯Å	UdÅïπ—…‰ÅÕπÖ¡Õ°Ω–Ä¥¯Å=A8Å’π…ïÖ±•ÈïêÅâÖÕ•ÃΩ…Ω’—îÅ±Öâï∞Ä¥¯ÅM10Åô•πÖ±•—‰Åπï–ÅM=0Ä¥¯Å)=UI90ÅÕ—…Ö—ïù‰Å—…’—†Ä¥¯Å]11PÅâÖ±Öπçîà§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï±Ö·ï»ÿ¿ÃƒÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•Ÿï1ÖÂï…Ö—ïIï±Ö·ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿ÃƒËÅ±Öπîµ¡ΩÕ•—•ŸîÅ±•ŸîÅXÅµ’Õ–ÅâÂ¡ÖÕÃÅù±ΩâÖ∞Å]HÅ…ï±Ö·ï»Å±Ωç¨Å›°•±îÅâ±ïïëï»Å±ÖπïÃÅÕ—Ö‰Å’π…ï±Ö·ïêà∞Å…ï±Ö·ï»ÿ¿ÃƒπçΩπ—Ö•πÃ†â±ÖπïAΩÕ•—•ŸïÖç°îà§ÄòòÅ…ï±Ö·ï»ÿ¿ÃƒπçΩπ—Ö•πÃ†â19µA=M%Q%YÅ=YII%à§ÄòòÅ…ï±Ö·ï»ÿ¿ÃƒπçΩπ—Ö•πÃ†â•òÄ°±ÖπïAΩÕ•—•Ÿîÿ¿Ãƒ§Å…ï—’…∏ÅâÖÕîà§ÄòòÅ…ï±Ö·ï»ÿ¿ÃƒπçΩπ—Ö•πÃ†â	1U!%@à§ÄòòÅ…ï±Ö·ï»ÿ¿ÃƒπçΩπ—Ö•πÃ†â…ï¡Ω…–Äÿ¿»‡ÅÕ°Ω›ÃÅ	1U!%@Å]H»¿à§ÄòòÅ…ï±Ö·ï»ÿ¿ÃƒπçΩπ—Ö•πÃ†âEU1%Qdà§ÄòòÅ…ï±Ö·ï»ÿ¿ÃƒπçΩπ—Ö•πÃ†â…ï¡Ω…–Äÿ¿»‡ÅÕ°Ω›ÃÅEU1%QdÅ]H–¿à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÿ¿ÃƒÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿ÃƒËÅ±•ŸîÅ…’π—•µîÅµ’Õ–ÅπΩ–Åëï¡ïπêÅΩ∏ÅÕç…ïï∏Ω5Ö•πç—•Ÿ•—‰ÅÕ—ÖÂ•πúÅÖ±•Ÿîà∞ÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†âïπÕ’…ïI’π—•µï]Ö≠ï1Ωç¨ÿ¿Ãƒà§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†â1]eM}=9}]-1=-}IMMIQ|ÿ¿Ãƒà§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†âïπÕ’…ï±›ÖÂÕ=πI’π—•µï’Ö…ëÃÿ¿Ãƒà§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†â1]eM}=9}IU9Q%5}IMU|ÿ¿Ãƒà§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†âïπÕ’…ï!Ω—·•—±•Ÿî†§à§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†âΩπ…ïÖ—ï}Öô—ï…}Õ—Ö…—Ω…ïù…Ω’πêà§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†âÖç—•Ωπ}Õ—Ö…—}Ö±…ïÖëÂ}…’ππ•πúà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖπ•ôïÕ–ÿ¿Ã»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ωπë…Ω•ë5Öπ•ôïÕ–π·µ∞à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã»ËÅÖ±›ÖÂÃµΩ∏Å±•ŸîÅ—…Öë•πúÅµ’Õ–Å°Ω±êÅπï—›Ω…¨ÅÖÃÅ›ï±∞ÅÖÃÅATÅ›°•±îÅÕï…Ÿ•çîÅ•ÃÅÖç—•Ÿîà∞ÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†âïπÕ’…ïI’π—•µï]•ô•1Ωç¨ÿ¿Ã»à§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†â1]eM}=9}]%%}1=-}IMMIQ|ÿ¿Ã»à§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†â±•ôïçÂç±ïâΩ–Èπï—›Ω…¨ÈÖ±›ÖÂÕ}Ωπ|ÿ¿Ã»à§ÄòòÅâΩ–ÿ¿ÃƒπçΩπ—Ö•πÃ†â›•ô•1Ωç¨ÿ¿Ã»ÄÙÅπ’±∞à§ÄòòÅµÖπ•ôïÕ–ÿ¿Ã»πçΩπ—Ö•πÃ†âÖπë…Ω•êπ¡ï…µ•ÕÕ•Ω∏πMM}]%%}MQQà§ÄòòÅµÖπ•ôïÕ–ÿ¿Ã»πçΩπ—Ö•πÃ†âÖπë…Ω•êπ¡ï…µ•ÕÕ•Ω∏π!9}]%%}MQQà§§(ÄÄÄÄÄÄÄÅŸÖ∞Å—…Öç≠ï»ÿ¿Ã–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ!ΩÕ—]Ö±±ï—QΩ≠ïπQ…Öç≠ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã–ËÅë…Ω¡¡ïêÅ›Ö±±ï–Å—Ω≠ïπÃÅµ’Õ–Å…ïçΩŸï»Å•π—ºÅ=A9}QI-%9Å•πÕ—ïÖêÅΩòÅâï•πúÅ•ùπΩ…ïêà∞Å—…Öç≠ï»ÿ¿Ã–πçΩπ—Ö•πÃ†âI=YI}=IA!9}]11Q}Q=-9LËÅ	ΩΩ±ïÖ∏ÄÙÅ—…’îà§ÄòòÅ—…Öç≠ï»ÿ¿Ã–πçΩπ—Ö•πÃ†â=IA!9}]11Q}Q=-9}QQ!à§ÄòòÅ—…Öç≠ï»ÿ¿Ã–πçΩπ—Ö•πÃ†â=IA!9}]11Q}Q=-9}5=9%Q=I}=I}a%Pà§ÄòòÅ—…Öç≠ï»ÿ¿Ã–πçΩπ—Ö•πÃ†âÕΩ’…çîÄÙÅAΩÕ•—•ΩπMΩ’…çîπ]11Q}I=9%1à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕï±±Iïåÿ¿Ã‘ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÕï±∞ΩMï±±IïçΩπç•±ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‘ËÅMï±±IïçΩπç•±ï»Åµ’Õ–ÅÖ¡¡±‰Å…Ö‹Å›Ö±±ï–ÅÕπÖ¡Õ°Ω–ÅâïôΩ…îÅ—…Öç≠ï»µ°ï±êÅÖ’—ºµ°ïÖ∞à∞ÅÕï±±Iïåÿ¿Ã‘πçΩπ—Ö•πÃ†âM11}I=9%1I}]11Q}M9AM!=Q}AA1%|ÿ¿Ã‘à§ÄòòÅÕï±±Iïåÿ¿Ã‘πçΩπ—Ö•πÃ†â!ΩÕ—]Ö±±ï—QΩ≠ïπQ…Öç≠ï»πÖ¡¡±Â]Ö±±ï—MπÖ¡Õ°Ω–°—Ω≠ïπÃ§à§ÄòòÅÕï±±Iïåÿ¿Ã‘π•πëï·=ò†âM11}I=9%1I}]11Q}M9AM!=Q}AA1%|ÿ¿Ã‘à§ÄÅÕï±±Iïåÿ¿Ã‘π•πëï·=ò†â!ΩÕ—]Ö±±ï—QΩ≠ïπQ…Öç≠ï»πùï—ç—’Ö±±Â!ï±ë5•π—Ã†§à§ÄòòÅÕï±±Iïåÿ¿Ã‘πçΩπ—Ö•πÃ†âM11}I=9%1I}!1}UQ=!1|ÿ¿Ã‘à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïïπ—…‰ÿ¿ÃÿÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïπ—…Â1Ωç≠Ω’–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïçÖ—îÿ¿ÃÿÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿ÃÿËÅÕÖµîµµ•π–Å…ïïπ—…‰Å…ïµÖ•πÃÅ°Ö…êÅâ’–ÅôÖµ•±‰µΩπ±‰Å±Ωç≠Ω’—ÃÅÕΩô–µÖ±±Ω‹Å—°…Ω’ù°¡’–à∞Å…ïïπ—…‰ÿ¿ÃÿπçΩπ—Ö•πÃ†âëÖ—ÑÅç±ÖÕÃÅ1Ωç≠ïç•Õ•Ω∏à§ÄòòÅ…ïïπ—…‰ÿ¿ÃÿπçΩπ—Ö•πÃ†âÕÖµï5•π–ÄÙÅ—…’îà§ÄòòÅ…ïïπ—…‰ÿ¿ÃÿπçΩπ—Ö•πÃ†âôÖµ•±Â=π±‰ÄÙÅ—…’îà§ÄòòÅï·ïçÖ—îÿ¿ÃÿπçΩπ—Ö•πÃ†âa}=A9}I9QIe}5%1e}M=Q}11=]|ÿ¿Ãÿà§ÄòòÅï·ïçÖ—îÿ¿ÃÿπçΩπ—Ö•πÃ†â•òÄ°±Ωç≠ïç•Õ•Ω∏πÕÖµï5•π–§à§ÄòòÅï·ïçÖ—îÿ¿ÃÿπçΩπ—Ö•πÃ†âΩâ…Ö•∏ΩÕÖôï—‰ÅçÖ∏Åëïç•ëîÅ—°îÅ—…Öëîà§§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…•çïQ…’—†ÿ¿Ã‹ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ=¡ïπAπ±MÖπ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÿ¿Ã‹ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIï¡Ω…—•πù!’àπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡•¡îÿ¿Ã‹ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôΩ…ïπÕ•åÿ¿Ã‹ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩï·ïç’—•Ω∏ΩΩ…ïπÕ•çIï¡Ω…—·¡Ω…—ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‹ËÅΩπîÅçÖπΩπ•çÖ∞Å¡…•ç•πúÅ—…’—†ÅôΩ»ÅµΩπï‰Å¡Ö—†ÏÅπºÅ…Ω’—îµ¡ïπë•πúÅÕ•ëîµç°Öππï∞ÅëΩ›πù…Öëîà∞Å¡…•çïQ…’—†ÿ¿Ã‹πçΩπ—Ö•πÃ†âëÖ—ÑÅç±ÖÕÃÅA…•ç•πùQ…’—†à§ÄòòÅ¡…•çïQ…’—†ÿ¿Ã‹πçΩπ—Ö•πÃ†âô’∏Å¡…•ç•πùQ…’—†à§ÄòòÅ…ï¡Ω…–ÿ¿Ã‹πçΩπ—Ö•πÃ†â=¡ïπAπ±MÖπ•—‰π¡…•ç•πùQ…’—†à§ÄòòÅ…ï¡Ω…–ÿ¿Ã‹πçΩπ—Ö•πÃ†â…Ω’—îıçÖπΩπ•çÖ±}µÖ…≠}ÕΩ’…çîà§ÄòòÄÖ…ï¡Ω…–ÿ¿Ã‹πçΩπ—Ö•πÃ†â…Ω’—ï}¡ïπë•πù}’π—…’Õ—ïë|ÿ¿Ã‹à§ÄòòÄÖ…ï¡Ω…–ÿ¿Ã‹πçΩπ—Ö•πÃ†â¡ïπë•πù}Ω’—±•ï…}πΩ—}çΩ’π—ïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‹ËÅ5Ωπï‰ÅAÖ—†ÅÕï±∞Åô•πÖ±•—‰Åµ’Õ–Å’ÕîÅïŸïπ–ÅçΩ’π—ï…Ã∞ÅπΩ–ÅÕ—Ö±îÅ±Öâï±Ãà∞Å¡•¡îÿ¿Ã‹πçΩπ—Ö•πÃ†âô’∏Åï·ïç1•ŸïMï±±Ö•±Ω’π–†§à§ÄòòÅ¡•¡îÿ¿Ã‹πçΩπ—Ö•πÃ†âô’∏Åï·ïç1•ŸïMï±±Aïπë•πù•πÖ±•—ÂΩ’π–†§à§ÄòòÅ…ï¡Ω…–ÿ¿Ã‹πçΩπ—Ö•πÃ†âA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω…}ïŸïπ—}çΩ’π—ï…Õ|ÿ¿Ã‹à§ÄòòÅ…ï¡Ω…–ÿ¿Ã‹πçΩπ—Ö•πÃ†âA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»πï·ïç1•ŸïMï±±=≠Ω’π–†§à§ÄòòÅ…ï¡Ω…–ÿ¿Ã‹πçΩπ—Ö•πÃ†âA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»πï·ïç1•ŸïMï±±Aïπë•πù•πÖ±•—ÂΩ’π–†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‹ËÅΩ…ïπÕ•åÅÕ’µµÖ…‰Åµ’Õ–ÅÕ°Ω‹Åïôôïç—•ŸîÅ…ïçΩπç•±ï»Å—…’—†ÅÖç…ΩÕÃÅ¡ΩÕ•—•Ω∏ΩÕï±∞Ω±•Ÿîµ›Ö±±ï–Å…ïçΩπç•±ï…Ãà∞ÅôΩ…ïπÕ•åÿ¿Ã‹πçΩπ—Ö•πÃ†âIïçΩ∏ËÅïôôïç—•ŸîÙà§ÄòòÅôΩ…ïπÕ•åÿ¿Ã‹πçΩπ—Ö•πÃ†âMï±±IïçΩπç•±ï»π—Ω—Ö±°ïç≠ïêà§ÄòòÅôΩ…ïπÕ•åÿ¿Ã‹πçΩπ—Ö•πÃ†â1•Ÿï]Ö±±ï—IïçΩπç•±ï»π—Ω—Ö±°ïç≠ïê†§à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÿ¿Ã‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÿ¿Ã‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•∏ÿ¿Ã‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩëîÿ¿Ã‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5ΩëïM¡ïç•ô•ç·•—Ãπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…ïç•Õ•Ω∏ÿ¿Ã‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA…ïç•Õ•Ωπ·•—1Ωù•åπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—Ω±—U§ÿ¿Ã‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω…Â¡—Ω±—ç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‡ËÅÕΩ’…çîÅ¡…•ç•πúΩAπ0Å°Ω–Å¡Ö—°ÃÅµ’Õ–Å’ÕîÅ=¡ïπAπ±MÖπ•—‰∞ÅπΩ–Å±ΩçÖ∞Å…Ö‹ÅôΩ…µ’±ÖÃà∞ÅâΩ–ÿ¿Ã‡πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπÕ—Ö…—’¡}Õ›ïï¡|ÿ¿Ã‡à§ÄòòÅâΩ–ÿ¿Ã‡πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπôÖ±±âÖç≠}°Ö…ë}ô±ΩΩ…|ÿ¿Ã‡à§ÄòòÅï·ïåÿ¿Ã‡πçΩπ—Ö•πÃ†â·ïç’—Ω»πÖç—•Ÿï}°Ö…ë}ô±ΩΩ…|ÿ¿Ã‡à§ÄòòÅï·ïåÿ¿Ã‡πçΩπ—Ö•πÃ†â·ïç’—Ω»πç±ΩÕï}±ïëùï…}Õ—Öµ¡|ÿ¿Ã‡à§ÄòòÅµΩëîÿ¿Ã‡πçΩπ—Ö•πÃ†â5ΩëïM¡ïç•ô•ç·•—ÃπïŸÖ±’Ö—ï|ÿ¿Ã‡à§ÄòòÅ¡…ïç•Õ•Ω∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âA…ïç•Õ•Ωπ·•—1Ωù•åπïŸÖ±’Ö—ï|ÿ¿Ã‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‡ËÅ5Ö•πç—•Ÿ•—‰Å±•ôïçÂç±îÅΩ¡ï∏µ¡ΩÕ•—•Ω∏Åë•Õ¡±ÖÂÃÅµ’Õ–Å…Ω’—îÅAπ0Å—°…Ω’ù†ÅµÖ•πU•Aπ±Aç–ÿ¿Ã‡Ω=¡ïπAπ±MÖπ•—‰à∞ÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âô’∏ÅµÖ•πU•Aπ±Aç–ÿ¿Ã‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â=¡ïπAπ±MÖπ•—‰π•πÕ¡ïç–°ïπ—…ÂA…•çî∞Å¡‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âµΩΩπÕ°Ω—}…Ω›|ÿ¿Ã‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â≈’Ö±•—Â}¡ΩÕ•—•Ωπ|ÿ¿Ã‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â—…ïÖÕ’…Â}¡ΩÕ•—•Ωπ|ÿ¿Ã‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âÕπ•¡ï…}µ•ÕÕ•Ωπ|ÿ¿Ã‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†ââ±’ïç°•¡}¡ΩÕ•—•Ωπ|ÿ¿Ã‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âï·¡…ïÕÕ}…•ëï|ÿ¿Ã‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âµÖπ•¡’±Ö—ïë}¡ΩÕ•—•Ωπ|ÿ¿Ã‡à§ÄòòÅç…Â¡—Ω±—U§ÿ¿Ã‡πçΩπ—Ö•πÃ†â…Â¡—Ω±—ç—•Ÿ•—‰π’•Ö•πAç–––‹Â|ÿ¿Ã‡à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôΩ…ïπÕ•åÿ¿Ã‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩï·ïç’—•Ω∏ΩΩ…ïπÕ•çIï¡Ω…—·¡Ω…—ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖÕ°ï∏ÿ¿Ã‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩÿÃΩÕçΩ…•πúΩÖÕ°ïπï…Ö—•Ωπ$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‰ËÅ=¡ï∏ÅAΩÕ•—•ΩπÃÅ¡Öπï∞Åµ’Õ–ÅÕ°Ω‹Å—Ω¿Äƒ¿Å°ï±êÅΩ¡ï∏µ¡ΩÕ•—•Ω∏Å…Ω›ÃÅÖπêÅ±•Õ–Å—°îÅ°ï±êÅ…ïÕ–Å•πÕ—ïÖêÅΩòÅë…Ω¡¡•πúÅëÖ—Ñà∞ÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â¡…•ŸÖ—îÅŸÖ∞Å=A9A=M}I=]}@ËÅ%π–ÄÙÄƒ¿à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â°•ëëïπ!ï±êÿ¿Ã‰à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†à¨ÄëÏúêùı°•ëëïπΩ’π–ÅÕ—•±∞Å°ï±êΩµÖπÖùïêÅâï±Ω‹Å—Ω¿ÄëÏúêùıI9I}@à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â°ï±ë5•π—Ãÿ¿Ã‰à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âΩ…ëï»Å°•ù£äI±Ω‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‰ËÅôΩ…ïπÕ•åΩΩ¡ï…Ö—Ω»Å±ΩùÃÅµ’Õ–Å•πç±’ëîÅ±•ŸîÅ—’π•πúÅëÖ—Ñà∞ÅôΩ…ïπÕ•åÿ¿Ã‰πçΩπ—Ö•πÃ†â—’π•πù|ÿ¿Ã‰à§ÄòòÅôΩ…ïπÕ•åÿ¿Ã‰πçΩπ—Ö•πÃ†â1•ŸïM—…Ö—ïùÂQ’πï»πÕ—Ö—’Õ1•πî†§à§ÄòòÅôΩ…ïπÕ•åÿ¿Ã‰πçΩπ—Ö•πÃ†âQ’π•πúËÄëÏúêùı—’π•πúÿ¿Ã‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿Ã‰ËÅÖÕ°ïπï…Ö—•Ωπ$Äÿ¿Ã‡ÅçΩµ¡•±îÅô•‡Åµ’Õ–Å’ÕîÅ¡ΩÃπµ•π–Å•πÕ•ëîÅç°ïç≠·•—%π—ï…πÖ∞à∞ÅçÖÕ°ï∏ÿ¿Ã‰πçΩπ—Ö•πÃ†âÖÕ°ïπï…Ö—•Ωπ%}ï·•—|ÿ¿Ã‡ºëÏúêùıÌ¡ΩÃπµ•π–π—Ö≠î†‡•Ùà§ÄòòÅçÖÕ°ï∏ÿ¿Ã‰πçΩπ—Ö•πÃ†âÖÕ°ïπï…Ö—•Ωπ%}ÕïçΩπëÖ…Â|ÿ¿Ã‡ºëÏúêùıÌ¡ΩÃπµ•π–π—Ö≠î†‡•Ùà§ÄòòÄÖçÖÕ°ï∏ÿ¿Ã‰πçΩπ—Ö•πÃ†âÖÕ°ïπï…Ö—•Ωπ%}ï·•—|ÿ¿Ã‡ºëÏúêùıÌµ•π–π—Ö≠î†‡•Ùà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿–¿ËÅ°ΩÕ–µ—…Öç≠ï»ÅΩ¡ï∏Å…Ω›ÃÅµ’Õ–ÅÕÂπ—°ïÕ•ÈîÅ•π—ºÅ=¡ï∏ÅAΩÕ•—•ΩπÃÅÕºÅ›Ö±±ï–µ°ï±êÅ…Ω›ÃÅçÖππΩ–Åâ±Öπ¨Å—°îÅ¡Öπï∞à∞ÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â=A9}A91}!=MQ}QI-I}Me9Q!|ÿ¿–¿à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â!ΩÕ—]Ö±±ï—QΩ≠ïπQ…Öç≠ï»πùï—=¡ïπQ…Öç≠ïëAΩÕ•—•ΩπÃ†§à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â!=MQ}]11Q}QI-I|ÿ¿–¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿–¿ºÿ¿‹‡ËÅ9HÅÕ°ïêÅµ’Õ–Å≠ïï¿Å=¡ï∏ÅAΩÕ•—•ΩπÃÅ…ïπëï…•πúÅô…Ω¥Å—°îÅçÖç°ïêÅΩôòµµÖ•∏ÅµΩëï∞ÅÖπêÅΩπ±‰ÅÕ≠•¿ÅπΩ∏µΩ¡ï∏Å°ïÖŸ‰Å…Ω›Ãà∞ÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âΩ¡ïπ5Ωëï±’…•πùM°ïêÿ¿‹‡à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†â…ïπëï…=¡ïπAΩÕ•—•ΩπÃ°Ω¡ïπAΩÕ’…•πùM°ïêÿ¿–¿∞Å¡…ïMΩ…—ïêÿ¿‹‡ÄÙÅ—…’î§à§ÄòòÅµÖ•∏ÿ¿Ã‡πçΩπ—Ö•πÃ†âÕ≠•¿ıπΩπ}Ω¡ïπ}°ïÖŸÂ}ëÖÕ°âΩÖ…ë}…Ω›Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿–ƒËÅ¡Ö…—•Ö∞ÅÕï±±ÃÅÖ…îÅ…ïÖ±•ÈïêÅ›Ö±±ï–ÅµΩŸïµïπ—ÃÅÖπêÅµ’Õ–Åç…ïë•–Ω…ïô…ïÕ†Å›Ö±±ï–ÅÕ’…ôÖçïÃà∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†âAIQ%1}M11}]11Q}I%Q|ÿ¿–ƒà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}AIQ%1}]11Q}IIM!}=I|ÿ¿–ƒà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â…ïô…ïÕ°	Ö±Öπçî°ôΩ…çîÄÙÅ—…’î§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿–ƒËÅAIQ%1}M10Åµ’Õ–ÅôïïêÅµΩŸïµïπ–Å›•∏Ω…ï¡Ω…–Ω…’∏ÅÕ’…ôÖçïÃÅ›•—°Ω’–Å›Ö•—•πúÅôΩ»Å—ï…µ•πÖ∞Åç±ΩÕîà∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†âAIQ%1}M11}5=Y59Q}9=UQ|ÿ¿–ƒà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â—…ÖëîπÕ•ëîπï≈’Ö±Ã†àÄ¨ÄâpâAIQ%1}M11pààÄ¨Äà∞Å—…’î§à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âI’πQ…Öç≠ï»Ã¡π…ïçΩ…ëQ…Öëî°ÕÂµâΩ∞ÄÙÅ}ôÖπΩ’—MÂµâΩ∞à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—…Ö—ïùÂQï±ïµï—…‰ÿ¿‹‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩM—…Ö—ïùÂQï±ïµï—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±•ŸïM—…Ö—ïùÂQ’πï»ÿ¿‹‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•ŸïM—…Ö—ïùÂQ’πï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‰ËÅ¡Ö¡ï»ÅµΩëîÅµ’Õ–ÅçΩµ¡Ω’πêΩ±ïÖ…∏Åô…Ω¥Åç±ïÖ∏Å¡Ö¡ï»Å—ï…µ•πÖ∞Å…Ω›ÃÅ›°•±îÅ±•ŸîÅµΩëîÅ…ïµÖ•πÃÅç±ïÖ∏µ±•ŸîÅ•ÕΩ±Ö—ïêà∞ÅÕ—…Ö—ïùÂQï±ïµï—…‰ÿ¿‹‰πçΩπ—Ö•πÃ†âçΩµ¡’—ï±ïÖπAÖ¡ï…Qï…µ•πÖ±1ïÖëï…âΩÖ…êà§ÄòòÅ±•ŸïM—…Ö—ïùÂQ’πï»ÿ¿‹‰πçΩπ—Ö•πÃ†âI’π—•µï5Ωëï’—°Ω…•—‰π•ÕAÖ¡ï»†§à§ÄòòÅ±•ŸïM—…Ö—ïùÂQ’πï»ÿ¿‹‰πçΩπ—Ö•πÃ†âçΩµ¡’—ï±ïÖπAÖ¡ï…Qï…µ•πÖ±1ïÖëï…âΩÖ…êà§ÄòòÅ±•ŸïM—…Ö—ïùÂQ’πï»ÿ¿‹‰πçΩπ—Ö•πÃ†âçΩµ¡’—ï±ïÖπ1•ŸïQï…µ•πÖ±1ïÖëï…âΩÖ…êà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÿ¿‡¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‡¿ËÅ…’ππï»Ωù•ŸîµâÖç¨Åï·•—ÃÅµ’Õ–ÅâÂ¡ÖÕÃÅ¡Ö¡ï»ÅÕï——±îµ•∏ÅÕºÅ›Ö…µ’¿ÅçÖππΩ–Å±ï–Å¡ïÖ≠ÃÅçΩ±±Ö¡Õîà∞ÅâΩ–ÿ¿‡¿πçΩπ—Ö•πÃ†âI]=]9}I=5}A-}MQQ1}	eAMM|ÿ¿‡¿à§ÄòòÅâΩ–ÿ¿‡¿π•πëï·=ò†âI]=]9}I=5}A-}MQQ1}	eAMM|ÿ¿‡¿à§ÄÅâΩ–ÿ¿‡¿π•πëï·=ò†â•Õ%πAÖ¡ï…Mï——±ï%∏°—Ã∞Åçôúπ¡Ö¡ï…5Ωëî§à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ö±±ï—Ωÿÿ¿‡ƒÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïÖ±•Èïë]Ö±±ï—Ωµ¡Ω’πë•πùΩŸï…πΩ»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‡ƒËÅ›Ö±±ï–ÅçΩµ¡Ω’πë•πúÅµ’Õ–Å’ÕîÅµΩëîµ±ΩçÖ∞Å…ïÖ±•ÈïêÅM10≠AIQ%1}M10ÅµΩπï‰Å…Ω›Ã∞ÅπΩ–Åâ±ïπëïêÅ—ï…µ•πÖ∞µΩπ±‰ÅÕ—…Ö—ïù‰Å…Ω›Ãà∞Å›Ö±±ï—Ωÿÿ¿‡ƒπçΩπ—Ö•πÃ†âX‘∏¿∏ÿ¿‡ƒà§ÄòòÅ›Ö±±ï—Ωÿÿ¿‡ƒπçΩπ—Ö•πÃ†âI’π—•µï5Ωëï’—°Ω…•—‰π•ÕAÖ¡ï»†§à§ÄòòÅ›Ö±±ï—Ωÿÿ¿‡ƒπçΩπ—Ö•πÃ†âµΩëîÿ¿‡ƒà§ÄòòÅ›Ö±±ï—Ωÿÿ¿‡ƒπçΩπ—Ö•πÃ†âAIQ%1}M10à§ÄòòÅ›Ö±±ï—Ωÿÿ¿‡ƒπçΩπ—Ö•πÃ†âIïÖ±•Èïë]Ö±±ï—Ωµ¡Ω’πë•πúπµΩπïÂIΩ›Ãÿ¿‡ƒà§§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÿ¿‡»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‡»ËÅ¡Ö¡ï»ÅÕ•È•πúÅµ’Õ–Åï·ï…ç•ÕîÅ±•ŸîµµΩπï‰ÅçΩµ¡Ω’πë•πúÅô±ΩΩ…ÃÅÖπêÅ›•ππï»Åçï•±•πúÅôΩ»Å¡Ö…•—‰à∞Åï·ïåÿ¿‡»πçΩπ—Ö•πÃ†âµΩπïÂM•È•πù5Ωëîÿ¿‡»à§ÄòòÅï·ïåÿ¿‡»πçΩπ—Ö•πÃ†âI’π—•µï5Ωëï’—°Ω…•—‰π•ÕAÖ¡ï»†§à§ÄòòÅï·ïåÿ¿‡»πçΩπ—Ö•πÃ†â›•ππï…5Ö·	ΩΩÕ–ÄÙÅ•òÄ°µΩπïÂM•È•πù5Ωëîÿ¿‡»à§ÄòòÅï·ïåÿ¿‡»πçΩπ—Ö•πÃ†â5=9e}5=}	M}1==I}1%Q|ÿ¿‡»à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÿ¿‡ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‡ÃËÅ—•ç¨µ—•µîÅ°Ö…êµô±ΩΩ»Ω¡…Ωô•–µ±Ωç¨ÅÕ°ï±∞Åµ’Õ–ÅçΩŸï»ÅÖ±∞Å¡Ö¡ï»Ω±•ŸîÅ±ÖπïÃ∞ÅπΩ–ÅÕ≠•¿Å	1U!%@ΩAIM1Ω1=9}!=1à∞ÅâΩ–ÿ¿‡ÃπçΩπ—Ö•πÃ†âX‘∏¿∏ÿ¿‡Ãà§ÄòòÅâΩ–ÿ¿‡ÃπçΩπ—Ö•πÃ†âŸÖ∞Å—•ç≠A…Ωô•—1Ωç≠±•ù•â±îÄÙÅ—…’îà§ÄòòÄÖâΩ–ÿ¿‡ÃπçΩπ—Ö•πÃ†ààâΩ…ïπÕ•ç1Ωùùï»π±•ôïçÂç±î†âQ%-}AI=%Q}1=-}M-%AA}19ààà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•∏ÿ¿‡–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‡ÿËÅÖ±∞Åù±ΩâÖ∞ΩµÖ•∏ÅU$ÅÕ—Ö–Åë•Õ¡±ÖÂÃÅµ’Õ–ÅçΩπÕ’µîÅ)Ω’…πÖ±ç—•Ÿ•—‰Å¡Ö…•—‰ÅâïôΩ…îÅÕ—Ö±îÅ›Ö±±ï–ΩçÖç°îΩâ’ç≠ï–ÅÕΩ’…çïÃà∞ÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âX‘∏¿∏ÿ¿‡ÿà§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âaPÅ)=UI90Å!HÅAI%QdÅ!1AHà§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†â)Ω’…πÖ±AÖ…•—ÂU•MπÖ¡Õ°Ω–ÿ¿‡‘à§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†â…ïô…ïÕ°)Ω’…πÖ±AÖ…•—ÂMπÖ¡Õ°Ω–ÿ¿‡’ÕÂπå°Õ—Ö—î§à§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âŸÖ∞Å¡ï…Õ•Õ—ïëM—Ö—ÃÄÙÅ©Ω’…πÖ±AÖ…•—ÂM—Ö—ÕMπÖ¡Õ°Ω–ÿ¿‡‘†§Ä¸ËÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπQ…Öëï!•Õ—Ω…ÂM—Ω…îπùï—M—Ö—ÕÖç°ïê†§à§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âŸÖ∞Å…Ö›M—Ö—Ãÿ¿‡–ÄÙÅ—…‰ÅÏÅ©Ω’…πÖ±AÖ…•—ÂM—Ö—ÕMπÖ¡Õ°Ω–ÿ¿‡‘†§Ä¸ËÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπQ…Öëï!•Õ—Ω…ÂM—Ω…îπùï—M—Ö—ÕÖç°ïê†§à§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âŸÖ∞Å©Ω’…πÖ±M—Ö—ÃÄÙÅ©Ω’…πÖ±AÖ…•—ÂM—Ö—ÕMπÖ¡Õ°Ω–ÿ¿‡‘†§Ä¸ËÅ—…‰ÅÏÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπQ…Öëï!•Õ—Ω…ÂM—Ω…îπùï—M—Ö—ÕÖç°ïê†§à§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âŸÖ∞Å©¡IïÖë•πïÕÃÿ¿‡ÿÄÙÅ©Ω’…πÖ±AÖ…•—ÂM—Ö—ÕMπÖ¡Õ°Ω–ÿ¿‡‘†§à§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âŸÖ∞Å©¿ÿ¿‡ÿÄÙÅ©Ω’…πÖ±AÖ…•—ÂM—Ö—ÕMπÖ¡Õ°Ω–ÿ¿‡‘†§à§ÄòòÅµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†â©Ω’…πÖ∞Å…Ö‹Å¡Ö…•—‰à§ÄòòÄÖµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âŸÖ∞Å¡ï…Õ•Õ—ïëM—Ö—ÃÄÙÅ—…‰ÅÏÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπQ…Öëï!•Õ—Ω…ÂM—Ω…îπùï—±ïÖπM—Ö—ÕMπÖ¡Õ°Ω––‘ƒ‹†§à§ÄòòÄÖµÖ•∏ÿ¿‡–πçΩπ—Ö•πÃ†âŸÖ∞Åç±ïÖπM—Ö—Ãÿ¿»–ÄÙÅ—…‰ÅÏÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπQ…Öëï!•Õ—Ω…ÂM—Ω…îπùï—±ïÖπM—Ö—ÕMπÖ¡Õ°Ω––‘ƒ‹†§à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕïπ—•ïπçîÿ¿‰¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMïπ—•ïπçï=…ç°ïÕ—…Ö—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±Öàÿ¿‰¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÕÂπçM—…Ö—ïùÂ1Öàπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï—Ñÿ¿‰¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5ï—ÖΩùπ•—•Ωπ·ïç’—Ω…	…•ëùîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕÕ§ÿ¿‰¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMÕ•A•±Ω—Ω’πç•∞π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰¿ËÅÕïπ—•ïπçîÅïŸïπ—ÃÅµ’Õ–ÅôïïêÅÖ’—ΩπΩµΩ’ÃÅÕ—…Ö—ïù‰ÅÖ’—°Ω…•—‰∞ÅπΩ–Å¡Ω›ï…±ïÕÃÅπºµµ’—Ö—•Ω∏ÅçΩµµïπ—Ö…‰à∞ÅÕïπ—•ïπçîÿ¿‰¿πçΩπ—Ö•πÃ†âI%9Lµ=ÅUQ=9=5dà§ÄòòÅÕïπ—•ïπçîÿ¿‰¿πçΩπ—Ö•πÃ†âôïïë•πúÅ—°•ÃÅ•π—ºÅÖ’—ΩπΩµΩ’ÃÅÕ—…Ö—ïù‰ÅÖ’—°Ω…•—‰à§ÄòòÅÕïπ—•ïπçîÿ¿‰¿πçΩπ—Ö•πÃ†âïŸïπ—}—Ω}Õ—…Ö—ïùÂ}Ö’—°Ω…•—Â|ÿ¿‰¿à§ÄòòÄÖÕïπ—•ïπçîÿ¿‰¿πçΩπ—Ö•πÃ†â›•—°Ω’–Åç°Öπù•πúÅùÖ—ïÃ∞ÅÕ•È•πú∞ÅΩ»Åï·ïç’—•Ω∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰¿ËÅ…ïŸ•ï›ïêÅÖÕÂπåΩ114Å±ÖàÅ°Â¡Ω—°ïÕïÃÅµ’Õ–Å°ÖŸîÅ…ïÖ∞ÅπΩ∏µÕÖôï—‰ÅÕ•È•πúÅÖ’—°Ω…•—‰à∞Å±Öàÿ¿‰¿πçΩπ—Ö•πÃ†â…ïŸ•ï›ïêÅ°Â¡Ω—°ïÕïÃÅÖ…îÅπΩ‹Å…ïÖ∞ÅÕ—…Ö—ïù‰ÅÖ’—°Ω…•—‰à§ÄòòÅ±Öàÿ¿‰¿πçΩπ—Ö•πÃ†âçΩï…çï%∏†¿∏ÿ¿∞Äƒ∏‘‘§à§ÄòòÅ±Öàÿ¿‰¿πçΩπ—Ö•πÃ†âÖç—’Ö—ïë}Ö’—°Ω…•—Â|ÿ¿‰¿ı—…’îà§ÄòòÄÖ±Öàÿ¿‰¿πçΩπ—Ö•πÃ†âçΩï…çï%∏†¿∏‰»∞Äƒ∏¿‡§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰¿ËÅµï—ÑµçΩùπ•—•Ω∏Åâ…•ëùîÅµ’Õ–ÅµÖ—ï…•Ö±±‰ÅçΩπ—…Ω∞ÅπΩ∏µÕÖôï—‰ÅÕ•È•πúÅô…Ω¥ÅïÖ…±‰Å—…ÖëïÃà∞Åµï—Ñÿ¿‰¿πçΩπ—Ö•πÃ†âX‘∏¿∏ÿ¿‰¿à§ÄòòÅµï—Ñÿ¿‰¿πçΩπ—Ö•πÃ†âçΩï…çï%∏†¿∏‘‘∞Äƒ∏ÿ‘§à§ÄòòÅµï—Ñÿ¿‰¿πçΩπ—Ö•πÃ†âçΩï…çï%∏†¿∏ÿ‘∞Äƒ∏–‘§à§ÄòòÄÖµï—Ñÿ¿‰¿πçΩπ—Ö•πÃ†âçΩï…çï%∏†¿∏‰–∞Äƒ∏¿‡§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰¿ËÅMM$Å¡•±Ω–Åµ’Õ–ÅÖ’—ΩπΩµΩ’Õ±‰ÅçΩπ—…Ω∞Å¡Ö¡ï»Ω±•ŸîÅπΩ∏µÕÖôï—‰ÅÕ—…Ö—ïù‰Å›•—†Å›•ëï»ÅÖ’—°Ω…•—‰à∞ÅÕÕ§ÿ¿‰¿πçΩπ—Ö•πÃ†â±•ŸîÄ¿∏‘‘∏∏ƒ∏‡¿∞Å¡Ö¡ï»Ä¿∏–‘∏∏»∏ƒ¿à§ÄòòÅÕÕ§ÿ¿‰¿πçΩπ—Ö•πÃ†â…ï—’…∏Å•òÄ°¡Ö¡ï»§Å¥πçΩï…çï%∏†¿∏–¿∞Ä»∏»‘§Åï±ÕîÅ¥πçΩï…çï%∏†¿∏–‘∞Äƒ∏‰¿§à§ÄòòÅÕÕ§ÿ¿‰¿πçΩπ—Ö•πÃ†âMM%}A%1=Q}19}IMU5|ÿ¿‰¿à§ÄòòÄÖÕÕ§ÿ¿‰¿πçΩπ—Ö•πÃ†âÖ›Ö•—•πù}çΩπ—…Ω±}—Ω›ï…}µÖπ’Ö±IïÕ’µîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰¿ËÅï·ïç’—Ω»Å$ÅÕ•ÈîÅÕ—Öç¨Åçï•±•πúÅµ’Õ–ÅΩ¡ï∏Å›°ï∏Å$ÅÖ’—°Ω…•—‰Å•ÃÅÖç—•ŸîÅ›°•±îÅ¡…ïÕï…Ÿ•πúÅÕÖôï—‰Åç±Öµ¡Ãà∞Åï·ïåÿ¿‡»πçΩπ—Ö•πÃ†âI%9Lµ=Å$ÅMQIQdÅUQ!=I%Qdà§ÄòòÅï·ïåÿ¿‡»πçΩπ—Ö•πÃ†âÖù•’—°Ω…•—Âç—•Ÿîÿ¿‰¿à§ÄòòÅï·ïåÿ¿‡»πçΩπ—Ö•πÃ†â•òÄ°I’π—•µï5Ωëï’—°Ω…•—‰π•ÕAÖ¡ï»†§§Ä»∏‘¿Åï±ÕîÄ»∏¿¿à§ÄòòÄ°ï·ïåÿ¿‡»πçΩπ—Ö•πÃ†â¡…Ωë’ç–πçΩï…çï%∏°¡ΩÕŸ±ΩΩ»∞ÅÖù•ï•±•πúÿ¿‰¿§à§ÅÒÅï·ïåÿ¿‡»πçΩπ—Ö•πÃ†â¡…Ωë’ç–πçΩï…çï%∏°¡ΩÕŸ±ΩΩ»∞ÅÖù•ï•±•πúÿ–¿ÿ§à§ÅÒÅï·ïåÿ¿‡»πçΩπ—Ö•πÃ†â¡…Ωë’ç–πçΩï…çï%∏°¡ΩÕŸ±ΩΩ»∞ÅÖù•ï•±•πúÿ–¿‰§à§§§(ÄÄÄÄÄÄÄÅŸÖ∞Å°Ω±ë•πúÿ¿‰ƒÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ!Ω±ë•πù1Ωù•ç1ÖÂï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÿ¿‰ƒÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰ƒËÅÖ’—ΩπΩµΩ’ÃÅÖëêµ—ºµ›•ππï»Åµ’Õ–Å…Ω’—îÅ›°Ö±îΩ$Ωë•ÖµΩπêÅçΩπŸ•ç—•Ω∏Å•π—ºÅ…ïÖ∞Å—Ω¿µ’¿Åï·ïç’—•Ω∏à∞Åï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†âÖ’—ΩπΩµΩ’ÕQΩ¡U¡M•ùπÖ∞ÿ¿‰ƒà§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†âUQ=9=5=UM}Q=AUA}M%91|ÿ¿‰ƒà§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†âUQ=9=5=UM}%M%=9}Q=AUA}M%91|ÿ¿‰ƒà§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†â!=1%9}1=%}}5=I}Q=AUA|ÿ¿‰ƒà§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†âëΩQΩ¡U¿°—Ã∞Å›Ö±±ï—MΩ∞∞Å›Ö±±ï–∞Å—Ω—Ö±·¡ΩÕ’…ïMΩ∞§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰ƒËÅ—Ω¿µ’¿ÅÕ•È•πúÅµ’Õ–Åù•ŸîÅ%5=9}!9LΩ±Ωπúµ°Ω±êÅ…’ππï…ÃÅëïï¡ï»Å¡Â…Öµ•ë•πúÅ›°•±îÅ…ï—Ö•π•πúÅï·¡ΩÕ’…îÅçÖ¡Ãà∞Åï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†âë•ÖµΩπë!ÖπëÃÿ¿‰ƒà§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†âåπ—Ω¡U¡5Ö·QΩ—Ö±MΩ∞Ä®ÄÃ∏¿à§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†â¡ï…ëë]Ö±±ï—Aç–ÿ¿‰ƒà§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†â›Ö±±ï—MΩ∞Ä®Å¡ï…ëë]Ö±±ï—Aç–ÿ¿‰ƒà§ÄòòÅï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†âï·¡ΩÕ’…ïï•±•πùMΩ∞ÄÙÅ›Ö±±ï—MΩ∞Ä®Ä¿∏‹¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰ƒËÅ!Ω±ë•πù1Ωù•åÅµ’Õ–Åï·¡ΩÕîÅ—…’îÅ%5=9}!9LÅµΩëîÅÖπêÅ}5=IÅµ’Õ–ÅâîÅÖ∏Å$ÅçΩπŸ•ç—•Ω∏ÅÕ•ùπÖ∞à∞Å°Ω±ë•πúÿ¿‰ƒπçΩπ—Ö•πÃ†â%5=9}!9Là§ÄòòÅ°Ω±ë•πúÿ¿‰ƒπçΩπ—Ö•πÃ†à‘¿¿¿∏¿à§ÄòòÅ°Ω±ë•πúÿ¿‰ƒπçΩπ—Ö•πÃ†âπ‰ÅçΩπŸ•ç—•Ω∏Å…’ππï»à§ÄòòÅ°Ω±ë•πúÿ¿‰ƒπçΩπ—Ö•πÃ†â•…Õ–µ…•ëîÅçΩπŸ•ç—•Ω∏Å—Ω≠ïπÃà§ÄòòÅ°Ω±ë•πúÿ¿‰ƒπçΩπ—Ö•πÃ†â%5=9}Q=A}%Y	-|ÿ¿‰ƒà§ÄòòÅ°Ω±ë•πúÿ¿‰ƒπçΩπ—Ö•πÃ†â$ÅÖëêµµΩ…îÅçΩπŸ•ç—•Ω∏à§ÄòòÅ°Ω±ë•πúÿ¿‰ƒπçΩπ—Ö•πÃ†âMÕ•A•±Ω—Ω’πç•∞πï·•—AÖ—•ïπçîà§§(ÄÄÄÄÄÄÄÅŸÖ∞Å•πÕ•ëï…Ω¡‰ÿ¿‰»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ%πÕ•ëï…Ω¡Âπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï…ùîÿ¿‰»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπ5ï…ùïE’ï’îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩëïIΩ’—ï»ÿ¿‰»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5ΩëïIΩ’—ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Â±ïIΩ’—ï»ÿ¿‰»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩùïπ—•çM—Â±ïIΩ’—ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•πU§ÿ¿‰»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩëïM¡ïç•ô•ç·•—Ãÿ¿‰»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5ΩëïM¡ïç•ô•ç·•—Ãπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰»ËÅ•πÕ•ëï»ÅÕ°Ö…¨ÅçΩ¡‰µâ’‰Åµ’Õ–Åïπ≈’ï’îÅÖÃÅëïë•çÖ—ïêÅÕΩ’…çîÅ›•—†Å±ÖπîΩ—ΩΩ∞ÅÖôô•π•—‰à∞Å•πÕ•ëï…Ω¡‰ÿ¿‰»πçΩπ—Ö•πÃ†âÕçÖππï»ÄÄÙà§ÄòòÅ•πÕ•ëï…Ω¡‰ÿ¿‰»πçΩπ—Ö•πÃ†â%9M%I}M!I,à§ÄòòÅ•πÕ•ëï…Ω¡‰ÿ¿‰»πçΩπ—Ö•πÃ†â±Öπïôô•π•—‰ÄÙÅÕï—=òà§ÄòòÅ•πÕ•ëï…Ω¡‰ÿ¿‰»πçΩπ—Ö•πÃ†â%9M%I}]11Pà§ÄòòÅµï…ùîÿ¿‰»πçΩπ—Ö•πÃ†â%9M%I}M!I,à§ÄòòÅµï…ùîÿ¿‰»πçΩπ—Ö•πÃ†ââïÕ—MçÖππï»ÄÙÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰»ËÅ•πÕ•ëï»ÅÕ°Ö…¨Åµ’Õ–ÅâîÅÑÅ…ïÖ∞Å5ΩëïIΩ’—ï»Ωùïπ—•çM—Â±îÅ…Ω’—î∞ÅπΩ–Å°•ëëï∏Å]!1}=Adà∞ÅµΩëïIΩ’—ï»ÿ¿‰»πçΩπ—Ö•πÃ†â%9M%I}M!I,†à§ÄòòÅµΩëïIΩ’—ï»ÿ¿‰»πçΩπ—Ö•πÃ†â›Ö±±ï–ΩÕΩç•Ö∞ÅÕ°Ö…¨ÅÖ±¡°ÑÅÕΩ’…çîà§ÄòòÅµΩëïIΩ’—ï»ÿ¿‰»πçΩπ—Ö•πÃ†âQ…ÖëïQÂ¡îπ%9M%I}M!I,Ä¥¯ÅUπ•ô•ïë5Ωëï=…ç°ïÕ—…Ö—Ω»π·—ïπëïë5Ωëîπ=Ae}QIà§ÄòòÅÕ—Â±ïIΩ’—ï»ÿ¿‰»πçΩπ—Ö•πÃ†â%9M%I}M!I-}=11=\à§ÄòòÅÕ—Â±ïIΩ’—ï»ÿ¿‰»πçΩπ—Ö•πÃ†â5ΩëïIΩ’—ï»πQ…ÖëïQÂ¡îπ%9M%I}M!I,à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰»ËÅ•πÕ•ëï»ÅÕ°Ö…¨Åë•Õ—…•â’—•Ω∏Åµ’Õ–ÅÕï±∞Åµïµï—…Öëï»Å¡ΩÕ•—•ΩπÃÅÖπêÅÕ°Ω‹ÅΩ∏ÅµÖ•∏ÅU$à∞Åï·ïåÿ¿‰ƒπçΩπ—Ö•πÃ†â%9M%I}M!I-}55}a%Q|ÿ¿‰»à§ÄòòÅ•πÕ•ëï…Ω¡‰ÿ¿‰»πçΩπ—Ö•πÃ†âï·•—M•ùπÖ±Ω…5•π–à§ÄòòÅµΩëïM¡ïç•ô•ç·•—Ãÿ¿‰»πçΩπ—Ö•πÃ†â5ΩëïIΩ’—ï»πQ…ÖëïQÂ¡îπ%9M%I}M!I,Ä¥¯ÅïŸÖ±’Ö—ïΩ¡ÂQ…Öëï·•–à§ÄòòÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†âùï—U•M’µµÖ…‰†§à§ÄòòÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†âM!I,à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰»ËÅ•ÖµΩπêÅ!ÖπëÃÅ±ÖπîÅµ’Õ–Å°ÖŸîÅµÖ•∏ÅU$ÅŸ•Õ•â•±•—‰Å¡±’ÃÅï·¡ÖπÕ•Ω∏ÅÕ—Ö—’Ãà∞ÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†âë•ÖµΩπë=¡ï∏à§ÄòòÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†â%5=9ÅΩ¡ï∏Ùà§ÄòòÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†âï·¡ÖπêÙÕ‡º‹¿îà§ÄòòÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†âë•ÖµΩπëΩ’π–à§ÄòòÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†âë•ÖµΩπëΩ’π–à§ÄòòÅµÖ•πU§ÿ¿‰»πçΩπ—Ö•πÃ†âï·¡ÖπêÙÕ‡à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å±Öπï·•—Q’πï»ÿ¿‰ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ïÖ…π•πúΩ1Öπï·•—Q’πï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—’π•πùU§ÿ¿‰ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§ΩQ’π•πùç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰ÃËÅ1ÖπîÅM—…Ö—ïù‰ÅIï¡±Ö‰Åµ’Õ–ÅÖç—’Ö—îÅâΩ’πëïêÅ1Öπï·•—Q’πï»ÅQ@ΩM0Åâ•ÖÃ∞ÅπΩ–Å…ïµÖ•∏ÅU$µΩπ±‰à∞Å±Öπï·•—Q’πï»ÿ¿‰ÃπçΩπ—Ö•πÃ†âIï¡±ÖÂ	•ÖÃà§ÄòòÅ±Öπï·•—Q’πï»ÿ¿‰ÃπçΩπ—Ö•πÃ†â1ÖπïM—…Ö—ïùÂŸÖ±’Ö—Ω»πâïÕ—Aï…1Öπî†§à§ÄòòÅ±Öπï·•—Q’πï»ÿ¿‰ÃπçΩπ—Ö•πÃ†â19}MQIQe}IA1e}	%M}IIM!|ÿ¿‰Ãà§ÄòòÅ±Öπï·•—Q’πï»ÿ¿‰ÃπçΩπ—Ö•πÃ†â1ÖπïM—…Ö—ïùÂIï¡±Ö‰Åâ•ÖÃÄÿ¿‰Ãà§ÄòòÅ±Öπï·•—Q’πï»ÿ¿‰ÃπçΩπ—Ö•πÃ†â…ïô…ïÕ°Iï¡±ÖÂ	•ÖÕÕÂπåà§ÄòòÅ±Öπï·•—Q’πï»ÿ¿‰ÃπçΩπ—Ö•πÃ†âùï—Q¡5’±–à§ÄòòÅ±Öπï·•—Q’πï»ÿ¿‰ÃπçΩπ—Ö•πÃ†âùï—M±5’±–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰ÃËÅ—’π•πúÅU$Åµ’Õ–ÅÕ—Ω¿Åç±Ö•µ•πúÅ…ï¡±Ö‰Å•ÃÅ…ïÖêµΩπ±‰ΩπºÅ±ÖπïÃÅ›°ï∏Å›Ö…µ’¿ÅçΩπ—…•â’—Ω…ÃÅï·•Õ–à∞Å—’π•πùU§ÿ¿‰ÃπçΩπ—Ö•πÃ†â1ÖπîÅM—…Ö—ïù‰ÅIï¡±Ö‰ÅπΩ‹ÅôïïëÃÅâΩ’πëïêÅ1Öπï·•—Q’πï»ÅQ@ΩM0Åâ•ÖÃà§ÄòòÅ—’π•πùU§ÿ¿‰ÃπçΩπ—Ö•πÃ†â›Ö…µ•πúËÅâï±Ω‹ÅÕ—Ö—•Õ—•çÖ∞Å—°…ïÕ°Ω±ê∞Åâ’–Å±ÖπïÃΩ—…Öëï…ÃÅÖ…îÅçΩπ—…•â’—•πúà§ÄòòÅ—’π•πùU§ÿ¿‰ÃπçΩπ—Ö•πÃ†â…Ö›	ΩÖ…êπ—Ö≠î†ƒ»§à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôëùIΩ’—îÿ¿‰–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ïÖ…π•πúΩëùIΩ’—ïYï…ë•ç–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰–ËÅ±•ŸîÅ¡Ö¡ï»µµ•ç…ºÅâ±ïïëï»Åâ’ç≠ï—ÃÅµ’Õ–ÅâîÅ¡Ö¡ï»µΩπ±‰∞ÅπΩ–ÅïÕçÖ±Ö—ïêÅ—ºÅ±•ŸîÅ…ïë’çïêµÕ•Èîà∞ÅôëùIΩ’—îÿ¿‰–πçΩπ—Ö•πÃ†â	1=-}1%Y}	1I}AAI}=91dà§ÄòòÅôëùIΩ’—îÿ¿‰–πçΩπ—Ö•πÃ†â1%Y}	1I}AAI}=91e}	1=-|ÿ¿‰–à§ÄòòÅôëùIΩ’—îÿ¿‰–πçΩπ—Ö•πÃ†âI’π—•µï5Ωëï’—°Ω…•—‰π•Õ1•Ÿî†§à§ÄòòÄÖôëùIΩ’—îÿ¿‰–πçΩπ—Ö•πÃ†â1%Y}AAI}5%I=}M1Q}Q=}IU|–‘»ÿà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ—Mï…Ÿ•çîÿ¿‰–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰–ËÅ±ÖπîÅ≈’Ö…Öπ—•πîÅâ±Ωç≠ÃÅ±•ŸîÅïπ—…•ïÃÅâ’–Å¡Ö¡ï»Å≠ïï¡ÃÅ≈’Ö…Öπ—•πïêÅ±ÖπïÃÅÕÖµ¡±•πúà∞ÅâΩ—Mï…Ÿ•çîÿ¿‰–πçΩπ—Ö•πÃ†âAAI}19}EUI9Q%9}MQ%11}M5A1%9|ÿ¿‰–à§ÄòòÅâΩ—Mï…Ÿ•çîÿ¿‰–πçΩπ—Ö•πÃ†âI’π—•µï5Ωëï’—°Ω…•—‰π•ÕAÖ¡ï»†§à§ÄòòÅâΩ—Mï…Ÿ•çîÿ¿‰–πçΩπ—Ö•πÃ†â¡Ö¡ï…}ïπÖâ±ïë}±•Ÿï}≈’Ö…Öπ—•πïêà§ÄòòÅâΩ—Mï…Ÿ•çîÿ¿‰–πçΩπ—Ö•πÃ†â1ÖπïE’Ö…Öπ—•πïΩπ—…Ω±±ï»π±Ωù	±Ωç≠ïëπ—…‰à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å±ÖπïAΩ±•ç‰ÿ¿‰–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ïÖ…π•πúΩ1ÖπïAΩ±•ç‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰–ËÅπï‹Å±ÖπïÃÅµ’Õ–ÅâîÅï·¡±•ç•–Åô•…Õ–µç±ÖÕÃÅ1ÖπïAΩ±•ç‰Åç•—•ÈïπÃà∞Å±ÖπïAΩ±•ç‰ÿ¿‰–πçΩπ—Ö•πÃ†â%5=9à§ÄòòÅ±ÖπïAΩ±•ç‰ÿ¿‰–πçΩπ—Ö•πÃ†â%9M%Hà§ÄòòÅ±ÖπïAΩ±•ç‰ÿ¿‰–πçΩπ—Ö•πÃ†âM!I,à§ÄòòÅ±ÖπïAΩ±•ç‰ÿ¿‰–πçΩπ—Ö•πÃ†âπï‹Å±ÖπïÃÅÖ…îÅô•…Õ–µç±ÖÕÃÅ¡Ω±•ç‰Åç•—•ÈïπÃà§§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—Ω±–ÿ¿‰‘ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•πç—•Ÿ•—‰ÿ¿‰‘ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•π1ÖÂΩ’–ÿ¿‰‘ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω…ïÃΩ±ÖÂΩ’–ΩÖç—•Ÿ•—Â}µÖ•∏π·µ∞à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‘ËÅ…Â¡—ºÅUπ•Ÿï…ÕîÅµ’Õ–Å’ÕîÅ55µ¡Ö…•—‰ÅÕ•È•πú∞ÅπΩ–ÅÑÅµ•ç…ºµΩπ±‰Å—…ïÖëµ•±∞à∞Åç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âU1Q}M%i}APÄÄÄÄÄÄÙÄÿ∏¿à§ÄòòÅç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âIeAQ=}U9%YIM}55}AI%Qe}M%i|ÿ¿‰‘à§ÄòòÅç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†ââÖ±ÖπçîÄ®Ä¿∏–‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‘ËÅ…Â¡—ºÅ—Ω·•åÅâ’ç≠ï—ÃÅµ’Õ–ÅÕΩô–µÕ°Ö¡îÅ—Öç—•åΩÕ•ÈîÅ•πÕ—ïÖêÅΩòÅ°Ö…êµ…ï—’…π•πúà∞Åç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âIeAQ=}Q=a%}AQQI9}M=Q}M!A|ÿ¿‰‘à§ÄòòÅç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âç…Â¡—ΩQΩ·•çM•Èï5’±–ÿ¿‰‘ÄÙÄ¿∏Ã‘à§ÄòòÄÖç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âIeAQ=}Q=a%}AQQI9}!I}	1=,à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‘ËÅ…Â¡—ºÅXÃÅâ…•ëùîÅµ’Õ–Å…ïçï•ŸîÅ…ïÖ±•Õ—•åÅ±•≈’•ë•—‰ÅçΩπ—ï·–à∞Åç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âïπ—…Â1•≈UÕêÄÙÅï·Öç—ÕÕï—5ï—…•çÃÿ–‰Ã°Õ•ùπÖ∞§π±•≈’•ë•—ÂUÕêà§ÄòòÅç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†â55Å—…Öëï»ùÃÅ…•ç†Åïπ—…‰ÅÕπÖ¡Õ°Ω–ÅÕ—…’ç—’…îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‘ËÅµÖ•∏ÅU$Åµ’Õ–ÅÕ’…ôÖçîÅ—°îÅï·¡ÖπëïêÅ55≠IeAQ<Å±ÖÂï»ÅÕ—Öç¨à∞ÅµÖ•πç—•Ÿ•—‰ÿ¿‰‘πçΩπ—Ö•πÃ†à–ƒ¨Å±ÖÂï…ÃÉ
‹Å55≠IeAQ<à§ÄòòÅµÖ•π1ÖÂΩ’–ÿ¿‰‘πçΩπ—Ö•πÃ†à–ƒ¨Å±ÖÂï…ÃÉ
‹Å55≠IeAQ<à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å•πÕ•ëï…Ω¡‰ÿ¿‰ÿÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ%πÕ•ëï…Ω¡Âπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰ÿËÅ%9M%I}M!I,Ω=AdÅµ’Õ–ÅΩ¡ï∏Å…Â¡—ºÅUπ•Ÿï…ÕîÅ¡ΩÕ•—•ΩπÃ∞ÅπΩ–Å©’Õ–Å55Ω›Ö—ç°±•Õ–ÅÖëŸ•ÕΩ…•ïÃà∞Åç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âçΩ¡Â	’Â…Ωµ%πÕ•ëï…M•ùπÖ∞à§ÄòòÅç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†â%9M%I}M!I-}IeAQ=}=Ae}	Ue|ÿ¿‰ÿà§ÄòòÅ•πÕ•ëï…Ω¡‰ÿ¿‰ÿπçΩπ—Ö•πÃ†âçΩ¡Â	’Â…Â¡—Ω±–ÿ¿‰ÿà§ÄòòÅ•πÕ•ëï…Ω¡‰ÿ¿‰ÿπçΩπ—Ö•πÃ†â…Â¡—Ω±—Q…Öëï»πçΩ¡Â	’Â…Ωµ%πÕ•ëï…M•ùπÖ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰ÿËÅ1QLÅ…ïÖë•πïÕÃÅµ’Õ–Åï·¡ΩÕîÅç…Â¡—ºÅÕ•ÈîΩ±ÖÂï»Å¡Ω±•ç‰ÅΩ∏Å—°îÅµÖ•∏ÅU$à∞Åç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†âÕ•ÈïAΩ±•ç‰à§ÄòòÅç…Â¡—Ω±–ÿ¿‰‘πçΩπ—Ö•πÃ†â±ÖÂï…AΩ±•ç‰à§ÄòòÅµÖ•πç—•Ÿ•—‰ÿ¿‰‘πçΩπ—Ö•πÃ†âŸÖ∞Å±ÖÂï…AΩ±•ç‰à§ÄòòÅµÖ•πç—•Ÿ•—‰ÿ¿‰‘πçΩπ—Ö•πÃ†âŸÖ∞ÅÕ•ÈïAΩ±•ç‰à§ÄòòÅµÖ•πç—•Ÿ•—‰ÿ¿‰‘πçΩπ—Ö•πÃ†âpë±ÖÂï…AΩ±•ç‰à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÂç±•åÿ¿‰‹ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÂç±•çQ…Öëïπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ‘ÿ‹ËÅe1%Å…•πúÅµ’Õ–Å¡…ïÕï…ŸîÅçΩπô•…µïêÅ°Ö…êÅÕÖôï—‰Å›°•±îÅ’π≠πΩ›∏Ω¡…ΩŸ•ëï»ÅùÖ¡ÃÅçΩπ—•π’îÅ—ºÅçÖπΩπ•çÖ∞Åà∞ÅçÂç±•åÿ¿‰‹πçΩπ—Ö•πÃ†âçÂç±•çπ—…ÂMï±±Öâ•±•—Â’Ö…êÿ¿‰‹à§ÄòòÅçÂç±•åÿ¿‰‹πçΩπ—Ö•πÃ†â=9%I5}1M∞ÅU9-9=]8∞ÅAI=Y%I}U9Y%1	1∞Å=9%I5}QIUà§ÄòòÅçÂç±•åÿ¿‰‹πçΩπ—Ö•πÃ†âe1%}M11	%1%Qe}9QIe}I)Q|ÿ¿‰‹à§ÄòòÅçÂç±•åÿ¿‰‹πçΩπ—Ö•πÃ†âïŸ•ëïπçîÄÑÙÅÂç±•çMï±±Öâ•±•—ÂŸ•ëïπçîÿ‘ÿ‹π=9%I5}1Mà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‹ËÅe1%ÅπΩ…µÖ∞ÅÖπêÅÕ—Ö…ŸÖ—•Ω∏µ¡…ΩâîÅô•±—ï…ÃÅµ’Õ–ÅâΩ—†ÅÖ¡¡±‰ÅÕï±±Öâ•±•—‰Åù’Ö…êà∞ÅçÂç±•åÿ¿‰‹πçΩπ—Ö•πÃ†ààâçÂç±•çπ—…ÂMï±±Öâ•±•—Â’Ö…êÿ¿‰‹°—Ã∞ÄâçÖπë•ëÖ—îàààà§ÄòòÅçÂç±•åÿ¿‰‹πçΩπ—Ö•πÃ†ààâçÂç±•çπ—…ÂMï±±Öâ•±•—Â’Ö…êÿ¿‰‹°—Ã∞Äâ¡…Ωâîàààà§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÿ¿‰‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIï¡Ω…—•πù!’àπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‡ËÅï·ïç’—•ŸîÅ…ï¡Ω…–Å±ΩΩ¿Ω©Ω’…πÖ∞ÅçΩ’π—ï…ÃÅµ’Õ–ÅôÖ±∞ÅâÖç¨Å—ºÅ±Öâï∞ÅçΩ’π—ï…ÃÅ’ÕïêÅâ‰ÅçΩ…îÅë’µ¿à∞Å…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†ààâ±Öâï∞ÿ¿‰‡†â	=Q}1==A}Q%,à§ààà§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†ààâ¡•¡îπ±Öâï±Ω’π—Õlâ	=Q}1==A}Q%,âtààà§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†ààâ¡•¡îπ±Öâï±Ω’π—ÕlâQI)I91}Iâtààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‡ËÅµΩπï‰Å¡Ö—†Åµ’Õ–ÅÕ¡±•–Å±ΩçÖ∞Å±•ŸîΩ¡Ö¡ï»ÅΩ¡ïπÃÅÖπêÅ°ΩÕ–Å—…Öç≠ï»Å•πÕ—ïÖêÅΩòÅ°•ë•πúÅ¡Ö¡ï»Å’πëï»Å°ΩÕ—=¡ï∏Ù¿à∞Å…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†â±ΩçÖ±=¡ï∏ı±•ŸîËà§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†â¡Ö¡ï»Ëà§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†â°ΩÕ—Q…Öç≠ï»Ùà§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†â—…’Õ—ïëAÖ¡ï…=¡ï∏à§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†â—…’Õ—ïë1•Ÿï=¡ï∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‡ËÅ…ï¡Ω…—ÃÅµ’Õ–ÅÕ°Ω‹ÅπÖâ±ïëQ…Öëï…’—°Ω…•—‰ÅÕ•ëïçÖ…ÃÅÕºÅIeAQ<ΩµÖ…≠ï—ÃÅ±ÖÂï…ÃÅëºÅπΩ–Åë•ÕÖ¡¡ïÖ»Åô…Ω¥Å…’π—•µîÅïπÖâ±ïêÅ±•Õ–à∞Å…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†âπÖâ±ïëQ…Öëï…’—°Ω…•—‰πÕπÖ¡Õ°Ω—M—»†§à§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†âÖ’—°Ω…•—‰ÙàÄ¨ÄàêàÄ¨ÄâÖ’—°Ω…•—ÂπÖâ±ïêÿ¿‰‡à§ÄòòÅ…ï¡Ω…–ÿ¿‰‡πçΩπ—Ö•πÃ†âÖ’—°Ω…•—‰ÙàÄ¨ÄàêàÄ¨ÄâÖ’—°Ω…•—ÂI’π—•µîÿ¿‰‡à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…Ω’—ï!Âë…Ö—Ω»ÿ¿‰‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIΩ’—ïQ…’—°!Âë…Ö—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÿ¿‰‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‰ËÅ°ï±êÅ—Ω≠ïπÃÅµ’Õ–Å°Âë…Ö—îÅ…Ω’—îÅ—…’—†Åô…Ω¥Å¡ï…Õ•Õ—ïêÅâ’‰ΩΩ¡ï∏Å…Ω’—î∞ÅπïŸï»ÅëïµΩ—îÅ›•ππï…ÃÅ—ºÅ…Ω’—îÅ’π≠πΩ›∏à∞Å…Ω’—ï!Âë…Ö—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â!1ÅQ=-8Å	UdµI=UQÅ!eIQ%=8à§ÄòòÅ…Ω’—ï!Âë…Ö—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â—Ãπ¡ΩÕ•—•Ω∏πïπ—…ÂA…•çïMΩ’…çîà§ÄòòÅ…Ω’—ï!Âë…Ö—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â—Ãπ¡ΩÕ•—•Ω∏πïπ—…ÂAΩΩ±ëë…ïÕÃà§ÄòòÅ…Ω’—ï!Âë…Ö—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â9QIe}AU5@à§ÄòòÅ…Ω’—ï!Âë…Ö—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â9QIe}A==0à§ÄòòÅ…Ω’—ï!Âë…Ö—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â9QIe})UA%QHà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‰ËÅ±•ŸîÅÕï±∞Å…Ω’—•πúÅµ’Õ–Åëï…•ŸîÅA’µ¿Ω)’¡•—ï»Åô•…Õ–Å¡…•Ω…•—‰Åô…Ω¥Å¡ï…Õ•Õ—ïêÅâ’‰Å…Ω’—îÅµï—ÖëÖ—Ñà∞Åï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âÕï±±IΩ’—ïA…•Ω…•—Â…Ωµ	’ÂIΩ’—îÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âMï±±IΩ’—ïA…•Ω…•—‰ÿ¿‰‰πAU5A}%IMPà§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âMï±±IΩ’—ïA…•Ω…•—‰ÿ¿‰‰π)UA%QI}%IMPà§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âM11}I=UQ}AI%=I%Qe}	Ue}I=UQ|ÿ¿‰‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‰‰ËÅô’±∞∞Å¡…Ωô•–µ±Ωç¨∞Å¡Ö…—•Ö∞∞ÅÖπêÅΩ…¡°Ö∏ÅÕï±±ÃÅµ’Õ–ÅÕ≠•¿Åùïπï…•åÅA’µ¿µô•…Õ–Å›°ï∏Åâ’‰Å…Ω’—îÅÕÖÂÃÅ)’¡•—ï»ΩŸïπ’îÅô•…Õ–à∞Åï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â¡…Ωô•—1Ωç≠A’µ¡•…Õ–ÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â¡Ö…—•Ö±A’µ¡•…Õ–ÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âï·•—A’µ¡•…Õ–ÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âΩ…¡°ÖπA’µ¡•…Õ–ÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âAU5A}%IQ}M-%AA}	Ue}I=UQ|ÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπÕ—Ö—’Ãπ—Ω≠ïπÕmµ•π—tà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•πU§ÿƒ¿¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿƒ¿¿ËÅ…Ω’—îµ¡ïπë•πúÅU$Åµ’Õ–ÅôÖ±∞ÅâÖç¨Å—ºÅ¡ï…Õ•Õ—ïêÅïπ—…‰Å…Ω’—îÅµï—ÖëÖ—ÑÅôΩ»Å°ï±êÅ›•ππï…Ãà∞ÅµÖ•πU§ÿƒ¿¿πçΩπ—Ö•πÃ†âïπ—…ÂIΩ’—îÿƒ¿¿à§ÄòòÅµÖ•πU§ÿƒ¿¿πçΩπ—Ö•πÃ†â¡ΩÃπïπ—…ÂA…•çïMΩ’…çîà§ÄòòÅµÖ•πU§ÿƒ¿¿πçΩπ—Ö•πÃ†â¡ΩÃπïπ—…ÂAΩΩ±ëë…ïÕÃà§ÄòòÅµÖ•πU§ÿƒ¿¿πçΩπ—Ö•πÃ†âU9I1%iÉ
‹Åïπ—…‰Å…Ω’—îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿƒ¿¿ËÅµÖ—ï…•Ö∞Å…’ππï…ÃÅ›•—†Å≠πΩ›∏Å¡ï…Õ•Õ—ïêÅâ’‰Å…Ω’—îÅµ’Õ–Å°Ö…ŸïÕ–ÅïŸï∏Å›°ï∏ÅŸΩ±Ö—•±îÅIïÖ±A…•çï1Ωç¨ÅçÖç°îÅ•ÃÅµ•ÕÕ•πúΩë•ÕÖù…ïïÃà∞Åï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â—…ÂAï…Õ•Õ—ïëπ—…ÂIΩ’—ï!Ö…ŸïÕ–ÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†âAIM%MQ}9QIe}I=UQ}!IYMQ|ÿ¿‰‰à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†â…ïÖ±¡…•çï±Ωç≠}µ•ÕÕ•πù}Ω…}ë•ÕÖù…ïïÕ}â’—}â’Â}…Ω’—ï}≠πΩ›∏à§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†ààâ—…ÂAï…Õ•Õ—ïëπ—…ÂIΩ’—ï!Ö…ŸïÕ–ÿ¿‰‰†â’±—…Ö}…’ππï…}âÖπ¨à§ààà§ÄòòÅï·ïç’—Ω»ÿ¿‰‰πçΩπ—Ö•πÃ†ààâ—…ÂAï…Õ•Õ—ïëπ—…ÂIΩ’—ï!Ö…ŸïÕ–ÿ¿‰‰†â›Ö±±ï—}ù…Ω›—°}°Ö…ŸïÕ–à§ààà§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‹Ÿ1•Ÿï	’Â±…ïÖëÂ=¡ïπM’ççïÕÕ!ÖÕQï…µ•πÖ±Q…Öçî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÿËÅ±•ŸîÅâ’‰Åµ’Õ–Å°ÖŸîÅ¡ï»µÖ——ïµ¡–Å—ï…µ•πÖ∞Å—…ÖçîÅ°ï±¡ï»à∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†â	Ue}QQ5AQ}QI|–‘‹ÿà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†ââ’Â——ïµ¡—Q…Öçî–‘‹ÿà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âQI5%91}=,à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âQI5%91}%0à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÿËÅÖ±…ïÖë‰µΩ¡ï∏µÖ–µçΩπô•…¥ÅÕ’ççïÕÃÅµ’Õ–ÅâÖç≠ô•±∞Å—°îÅµ•ÕÕ•πúÅ	UdÅ©Ω’…πÖ∞ÅâïôΩ…îÅ—ï…µ•πÖ∞Å=,à∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†âA=M%Q%=9}1Ie}=A9}Q}=9%I4à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}	Ue})=UI91}	-%11|–‘‹ÿà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â	Ue}1Ie}=A9}Q}=9%I5}	-%11|–‘‹ÿà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â	Ue}QI5%91}=,ÈA=M%Q%=9}1Ie}=A9}Q}=9%I4à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÿËÅ±•ŸîÅ	UdÅ©Ω’…πÖ∞ÅâÖç≠ô•±∞Åµ’Õ–ÅâîÅ•ëïµ¡Ω—ïπ–Åâ‰ÅÕ•ùπÖ—’…îà∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†â±•Ÿï	’Â)Ω’…πÖ±ïëM•ùÃ–‘‹ÿà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}	Ue})=UI91}UA}MUAAIMM|–‘‹ÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÿËÅπΩ…µÖ∞Å¡ΩÕ•—•Ω∏ÅÕ—Öµ¿Å¡Ö—†Åµ’Õ–Åïµ•–Å—°îÅÖ——ïµ¡–Å—…ÖçîÅâïôΩ…îÅ—ï…µ•πÖ∞Å=,à∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†ââ’Â——ïµ¡—Q…Öçî–‘‹ÿà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âA=M%Q%=9}MQ5Aà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}A=M%Q%=9}MQ5Aà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â	Ue}QI5%91}=,ÈQa}=9%I5}A9%9}]11Q}1Qà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹ÿËÅÖÕÂπåÅ›Ö±±ï–µ¡…ΩΩòÅ	Ue}=,ΩA9%9Å¡Ö—°ÃÅµ’Õ–ÅçΩππïç–ÅâÖç¨Å—ºÅ—°îÅÕÖµîÅÖ——ïµ¡–Å—…Öçîà∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†â]11Q}AI==}=,à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â]11Q}AI==}A9%9à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}	Ue}19à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‹·1•Ÿï	’ÂΩïÕ9Ω—!Ö…ëÖ•±Mïπ—•πï±MçΩ…ïÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹‡ËÅëΩ	’‰Åµ’Õ–ÅπΩ…µÖ±•ÈîÅçÖ±±ï»ÅÕïπ—•πï∞ÅÕçΩ…ïÃÅâïôΩ…îÅ±•ŸîÅï·ïç’—Ω»ÅÕ•È•πúΩÖëŸ•ÕΩ…Ãà∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}	Ue}M=I}9=I51%i|–‘‹‡à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞Åï·ïçMçΩ…î–‘‹‡à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â—Ãπïπ—…ÂMçΩ…îπ•Õ•π•—î†§à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âï±ÕîÄ¥¯Ä‘¿∏¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹‡ËÅë•…ïç–Å±•Ÿï	’‰ÅçÖ±±ï…ÃÅµ’Õ–ÅπΩ…µÖ±•ÈîÅÕïπ—•πï∞ÅÕçΩ…ïÃÅÖ–Å—°îÅô•πÖ∞Å±•ŸîÅç°Ω≠îà∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}	Ue}M=I}9=I51%i}Q}!=-|–‘‹‡à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞Å…Ö›1•ŸïMçΩ…î–‘‹‡ÄÙÅÕçΩ…îà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âçΩπ—•π’ï}πΩ}•πŸÖ±•ë}ÕçΩ…ï}Ÿï—ºà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏–‘‹‡ËÅ±•ŸîÅ%9Y1%}M=IÅµ’Õ–ÅπΩ–Å…ïµÖ•∏Å—°îÅëΩµ•πÖπ–ÅÕïπ—•πï∞µÕçΩ…îÅ°Ö…êÅŸï—ºÅâïôΩ…îÅÕçΩ…îÅπΩ…µÖ±•ÈÖ—•Ω∏à∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†â1•ŸîÅâ’‰ÅÕ≠•¡¡ïêËÅ•πŸÖ±•êÅÕçΩ…îÄà§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‹ÂQΩ·•ç1•Ÿï	±ïïëA•ŸΩ—ÕM—…Ö—ïùÂ9Ω—AÖ—•ïπçï5•ç…ΩA…Ωâî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å—’πï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•ŸïM—…Ö—ïùÂQ’πï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…Ω’—ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩùïπ—•çM—Â±ïIΩ’—ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅ—Ω·•åÅ±•ŸîÅâ±ïïêÅµ’Õ–ÅâîÅ±Öâï±±ïêÅÖÃÅ…ïç±Ö•¥Å—Öç—•åÅ¡•ŸΩ–à∞Å—’πï»πçΩπ—Ö•πÃ†â—Ω·•ç}…ïç±Ö•µ}—Öç—•ç}¡•ŸΩ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏–‘‹‰ËÅ—Ω·•åÅ±•ŸîÅâ±ïïêÅµ’Õ–ÅπΩ–Å≠ïï¿ÅΩ±êÅ—Ω·•ç}…’ππï…}¡•ŸΩ–Åâï°ÖŸ•Ω»à∞Å—’πï»πçΩπ—Ö•πÃ†â±Öâï∞ÄÙÅ•òÄ°—Ω·•ç	±ïïê§à§ÄòòÅ—’πï»πçΩπ—Ö•πÃ†â—Ω·•ç}…’ππï…}¡•ŸΩ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‹‰ËÅ—Ω·•åÅ¡•ŸΩ–Åµ’Õ–ÅÕ°Ω…—ï∏Å°Ω±êÅÖπêÅâÖπ¨ÅïÖ…±•ï»à∞Å—’πï»πçΩπ—Ö•πÃ†â°Ω±ë5’±–ÄÙÅ•òÄ°—Ω·•ç%ππï…1ÖπïA•ŸΩ–§à§ÄòòÅ—’πï»πçΩπ—Ö•πÃ†âçΩï…çï%∏†¿∏‘‘∞Ä¿∏‡–§à§ÄòòÅ—’πï»πçΩπ—Ö•πÃ†âçΩï…çï%∏†¿∏ÿ»∞Ä¿∏‰»§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅ—Ω·•åÅ¡•ŸΩ–Åµ’Õ–ÅÕ›•—ç†ÅÕ—Â±îÅ•πÕ•ëîÅ—°îÅ±ÖπîÅâïôΩ…îÅÕ•È•πúà∞Å…Ω’—ï»πçΩπ—Ö•πÃ†â—’πïë	ÖÕïM—Â±îà§ÄòòÅ…Ω’—ï»πçΩπ—Ö•πÃ†â—Ω·•çQÖç—•çA•ŸΩ––‘‡–à§ÄòòÅ…Ω’—ï»πçΩπ—Ö•πÃ†âQ=a%}I1%5}QQ%à§ÄòòÅ…Ω’—ï»πçΩπ—Ö•πÃ†âÕÖµï1Öπï]ïÖ≠A•ŸΩ—M—Â±î°±Öπï!•π–∞ÅM—Â±îπQ=a%}I1%5}QQ%§à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡¡Ö¡•—Ö±±±ΩçÖ—Ω…A…ïÕÕïÕÖ…±Â1•Ÿï]•ππï…Õ	ïôΩ…ï	±ïïëï…Ã†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅëÖµ¡ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1Öπï·¡ïç—ÖπçÂÖµ¡ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅëΩç—…•πîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•Ÿï…Ω›—°Ωç—…•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡¿ËÅïÖ…±‰Å±•ŸîÅ›•ππï…ÃÅµ’Õ–Åùï–ÅÑÅâΩ’πëïêÅçΩµ¡Ω’πë•πúÅâΩΩÕ–à∞ÅëÖµ¡ï»πçΩπ—Ö•πÃ†âI1e}]%99I}5%9}QILÄÙÄ»à§ÄòòÅëÖµ¡ï»πçΩπ—Ö•πÃ†âïÖ…±Â]•ππï»à§ÄòòÅëÖµ¡ï»πçΩπ—Ö•πÃ†âçΩï…çï%∏†ƒ∏¿∞ÅçÖ¿§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡¿ËÅ¡…Ωô•—Öâ±îÅ…’ππï»Å±ÖπïÃÅµ’Õ–ÅâîÅâΩΩÕ—ïêÅ•πÕ—ïÖêÅΩòÅÕ•±ïπ—±‰ÅçΩπ—•π’•πúÅÖ–Äƒ∏¿à∞ÅëÖµ¡ï»πçΩπ—Ö•πÃ†â•ÕI’ππï…1Öπî°¥πÕ—…Ö—ïù‰§à§ÄòòÅëÖµ¡ï»πçΩπ—Ö•πÃ†âΩ’—m¥πÕ—…Ö—ïù‰π—…•¥†§π’¡¡ï…çÖÕî†•tÄÙÅµÖ·=òà§ÄòòÄÖëÖµ¡ï»πçΩπ—Ö•πÃ†â•òÄ°•ÕI’ππï…1Öπî°¥πÕ—…Ö—ïù‰§ÄòòÄ°¥π—Ω—Ö±MΩ±Aπ∞Ä¯Ä¿∏¿ÅÒÅ¥π›•πIÖ—ïAç–Ä¯ÙÄÃ‘∏¿§§ÅçΩπ—•π’îà§ÄòòÅëÖµ¡ï»πçΩπ—Ö•πÃ†â•ÕI’ππï…1Öπî°¥πÕ—…Ö—ïù‰§ÄòòÅ¥π—Ω—Ö±MΩ±Aπ∞Ä¯Ä¿∏¿à§§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡¿ËÅ]HµâÖÕïêÅ…’ππï»Åï·ïµ¡—•Ω∏Åµ’Õ–Å…ï≈’•…îÅπΩ∏µπïùÖ—•ŸîÅπï–ÅM=0à∞ÅëÖµ¡ï»πçΩπ—Ö•πÃ†â¥π›•πIÖ—ïAç–Ä¯ÙÅ]I}IU99I}5%9}APÄòòÅ¥π—Ω—Ö±MΩ±Aπ∞Ä¯ÙÄ¿∏¿à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôÖ±±âÖç¨ÄÙÅëΩç—…•πîπÕ’âÕ—…•πùô—ï»†âë•Õ¡Ö—ç°Öâ±ïΩπ—…•â’—•Ωπ1ÖπïÃà§πÕ’âÕ—…•πùô—ï»†â±•Õ—=ò†à§πÕ’âÕ—…•πù	ïôΩ…î†âô’∏Åù…Ω›—°1ÖπïÖ±±âÖç¨à§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ±’îÄÙÅôÖ±±âÖç¨π•πëï·=ò†âpâ	1U!%Apàà§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩΩ∏ÄÙÅôÖ±±âÖç¨π•πëï·=ò†âpâ5==9M!=Qpàà§(ÄÄÄÄÄÄÄÅŸÖ∞Å≈’Ö±•—‰ÄÙÅôÖ±±âÖç¨π•πëï·=ò†âpâEU1%Qepàà§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·¡…ïÕÃÄÙÅôÖ±±âÖç¨π•πëï·=ò†âpâaAIMMpàà§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖπ•¿ÄÙÅôÖ±±âÖç¨π•πëï·=ò†âpâ59%AU1Qpàà§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ°•–ÄÙÅôÖ±±âÖç¨π•πëï·=ò†âpâM!%Q=%9pàà§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡¿ËÅçΩπ—…•â’—•Ω∏ÅôÖ±±âÖç¨Åµ’Õ–Å¡…•Ω…•—•ÈîÅ	1U!%@Ω5==9M!=PΩEU1%QdÅâïôΩ…îÅaAIMLΩ59%AU1QΩM!%Q=%8à∞Å±•Õ—=ò°â±’î∞ÅµΩΩ∏∞Å≈’Ö±•—‰∞Åï·¡…ïÕÃ∞ÅµÖπ•¿∞ÅÕ°•–§πÖ±∞ÅÏÅ•–Ä¯ÙÄ¿ÅÙÄòòÅâ±’îÄÅï·¡…ïÕÃÄòòÅµΩΩ∏ÄÅµÖπ•¿ÄòòÅ≈’Ö±•—‰ÄÅÕ°•–§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡≈…Â¡—ΩUπ•Ÿï…ÕïÖπΩπ•çÖ±1ïÖ…π•πù%ÕAΩÕ—Ωµµ•—πë%ÕΩ±Ö—ïê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å—…Öëï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…Ö•∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩâ…Ö•∏Ω…Â¡—Ω	…Ö•∏π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÄººÅX‘∏¿∏ÿ‘‹¡ÑÉäPÅ—…Öëï»ÅπΩ‹Å’ÕïÃÅ¡ΩÕ•—•ΩπÕm¡ΩÃπ•ëtÄ°Õ°Ω…–µŸÖ»§ÅÖ–Å—°î(ÄÄÄÄÄÄÄÄººÅçÖπΩπ•çÖ∞ÅΩ¡ï∏ÅÕ•—îÏÅï·¡ÖπêÅ—°îÅÕ’âÕ—…•πúÅÖπç°Ω»Å—ºÅ—°Ö–Å±•—ï…Ö∞(ÄÄÄÄÄÄÄÄººÅ…Ö—°ï»Å—°Ö∏Å—°îÅΩ±ëï»Å¡ΩÕ•—•ΩπÕm¡ΩÕ•—•Ω∏π•ëtÅπÖµî∏(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ΩÕ—Ωµµ•–ÄÙÅ—…Öëï»πÕ’âÕ—…•πùô—ï»†â¡ΩÕ•—•ΩπÕm¡ΩÃπ•ëtà§πÕ’âÕ—…•πù	ïôΩ…î†àººÅX‘∏‰∏Ã»¿à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅëÂπMçÖ∏ÄÙÅ—…Öëï»πÕ’âÕ—…•πùô—ï»†âÂπÖµ•åÅ—Ω≠ï∏ÅÕçÖ∏à§πÕ’âÕ—…•πù	ïôΩ…î†â¡…•ŸÖ—îÅÕ’Õ¡ïπêÅô’∏Å…’πMçÖπÂç±îà§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕï	±Ωç¨ÄÙÅ—…Öëï»πÕ’âÕ—…•πùô—ï»†àººÅX‘∏¿∏–‘‡ƒÉäPÅIeAQ<Å%M=1Q%=8Å]10à§πÕ’âÕ—…•πù	ïôΩ…î†àººÉäRäR ÅAï…¡Õ1ïÖ…π•πù	…•ëùîà§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡ƒËÅ…Â¡—Ω	…Ö•∏πΩπQ…ÖëïM—Ö…–Åµ’Õ–Åô•…îÅΩπ±‰ÅÖô—ï»ÅÑÅ¡Ö¡ï»Ω±•ŸîÅΩ¡ï∏Å•ÃÅçΩµµ•——ïêà∞Å¡ΩÕ—Ωµµ•–πçΩπ—Ö•πÃ†â…Â¡—Ω	…Ö•∏πΩπQ…ÖëïM—Ö…–†§à§ÄòòÅ¡ΩÕ—Ωµµ•–πçΩπ—Ö•πÃ†â]Ö±±ï—AΩÕ•—•Ωπ1Ωç¨π…ïçΩ…ë=¡ï∏à§ÄòòÅ¡ΩÕ—Ωµµ•–πçΩπ—Ö•πÃ†â…Â¡—Ω±–à§ÄòòÅ¡ΩÕ—Ωµµ•–πçΩπ—Ö•πÃ†âô•πÖ±M•Èîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏–‘‡ƒËÅëÂπÖµ•åÅç…Â¡—ºÅÕ•ùπÖ∞Åùïπï…Ö—•Ω∏Åµ’Õ–ÅπΩ–ÅôÖ≠îÅçÖπΩπ•çÖ∞ÅΩ¡ïπÃà∞ÅëÂπMçÖ∏πçΩπ—Ö•πÃ†â…Â¡—Ω	…Ö•∏πΩπQ…ÖëïM—Ö…–†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡ƒËÅ…Â¡—Ω	…Ö•∏Åç±ΩÕîÅ…ïçΩπç•±•Ö—•Ω∏Åµ’Õ–ÅπïŸï»Åëïç…ïµïπ–ÅçÖπΩπ•çÖ∞ΩΩ¡ï∏Åâï±Ω‹ÅÈï…ºà∞Åâ…Ö•∏πçΩπ—Ö•πÃ†âΩ¡ïπQ…ÖëïÃπùï–†§Ä¯Ä¡0à§ÄòòÅâ…Ö•∏πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±QΩ—Ö∞πùï–†§Ä¯Ä¡0à§ÄòòÅâ…Ö•∏πçΩπ—Ö•πÃ†â…ïçΩŸï…ïëQ…ÖëïÃπ•πç…ïµïπ—πëï–†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡ƒËÅ…Â¡—Ω±–Åç±ΩÕïÃÅµ’Õ–ÅÕ—Ö‰Å•ÕΩ±Ö—ïêÅô…Ω¥ÅµïµîΩù±ΩâÖ∞Å±ïÖ…πï…Ãà∞Åç±ΩÕï	±Ωç¨πçΩπ—Ö•πÃ†âµïµîΩù±ΩâÖ∞Å±ïÖ…πï…ÃÅÕ≠•¡¡ïêà§ÄòòÄÖç±ΩÕï	±Ωç¨πçΩπ—Ö•πÃ†â5ï—ÖΩùπ•—•Ωπ$π…ïçΩ…ëQ…Öëï=’—çΩµîà§ÄòòÄÖç±ΩÕï	±Ωç¨πçΩπ—Ö•πÃ†âM°ÖëΩ›1ïÖ…π•πùπù•πîπΩπ1•ŸïQ…Öëï·•–à§§(ÄÄÄÄÄÄÄÅŸÖ∞Åïπ—…Â%ÕºÄÙÅ—…Öëï»πÕ’âÕ—…•πùô—ï»†â¡ΩÕ–µçΩµµ•–Å•ÕΩ±Ö—•Ω∏ΩÕÖôï—‰à§πÕ’âÕ—…•πù	ïôΩ…î†àººÉäRäR Å9Ö……Ö—•Ÿï±Ω›$à§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡ƒËÅ…Â¡—Ω±–Åïπ—…•ïÃÅµ’Õ–ÅÕ—Ö‰Å•ÕΩ±Ö—ïêÅô…Ω¥ÅµïµîΩù±ΩâÖ∞Åïπ—…‰Å±ïÖ…πï…Ãà∞Åïπ—…Â%ÕºπçΩπ—Ö•πÃ†âµïµîΩù±ΩâÖ∞Åïπ—…‰Å±ïÖ…πï…ÃÅÕ≠•¡¡ïêà§ÄòòÄÖïπ—…Â%ÕºπçΩπ—Ö•πÃ†â5ï—ÖΩùπ•—•Ωπ$π…ïçΩ…ëπ—…ÂA…ïë•ç—•ΩπÃà§ÄòòÄÖïπ—…Â%ÕºπçΩπ—Ö•πÃ†âM°ÖëΩ›1ïÖ…π•πùπù•πîπΩπQ…Öëï=¡¡Ω…—’π•—‰à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡……Â¡—ΩUπ•Ÿï…Õï±ΩÕïUÕïÕIïÖ±Âπ5•π—Mï±±	Öç≠QΩMΩ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å—…Öëï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ï—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5Ö…≠ï—Õ1•Ÿï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕï∏ÄÙÅµÖ…≠ï—ÃπÕ’âÕ—…•πùô—ï»†âÕ’Õ¡ïπêÅô’∏Åç±ΩÕï1•ŸïAΩÕ•—•Ω∏à§πÕ’âÕ—…•πù	ïôΩ…î†âŸÖ∞Ä°•π¡’—5•π–∞ÅÖµΩ’π—Uπ•—Ã§à§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅ…Â¡—Ω±–Åç±ΩÕîÅµ’Õ–Å¡ÖÕÃÅ—°îÅ…ïÖ∞ÅëÂπÖµ•åÅç…Â¡—ºÅµ•π–ΩÕÂµâΩ∞Å•π—ºÅ±•ŸîÅç±ΩÕîà∞Å—…Öëï»πçΩπ—Ö•πÃ†âç…Â¡—ΩQÖ…ùï—5•π—=Ÿï……•ëîÄÙÅ¡ΩÃπëÂπ5•π–à§ÄòòÅ—…Öëï»πçΩπ—Ö•πÃ†âç…Â¡—ΩMÂµâΩ±=Ÿï……•ëîÄÙÅµ≠—MÂ¥à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅ5Ö…≠ï—ÃÅ±•ŸîÅç±ΩÕîÅµ’Õ–Å¡…ïôï»Åç…Â¡—ΩQÖ…ùï—5•π—=Ÿï……•ëîÄºÅç±ΩÕïMÂµâΩ∞ÅΩŸï»Å—°îÅe8ÅÕïπ—•πï∞à∞Åç±ΩÕï∏πçΩπ—Ö•πÃ†âç…Â¡—ΩQÖ…ùï—5•π—=Ÿï……•ëîà§ÄòòÅç±ΩÕï∏πçΩπ—Ö•πÃ†âŸÖ∞Åç±ΩÕïMÂµâΩ∞à§ÄòòÅç±ΩÕï∏πçΩπ—Ö•πÃ†â…Â¡—Ω]…Ö¡¡ïëÕÕï—5Ö¡¡ï»π…ïÕΩ±Ÿï]…Ö¡¡ïë5•π–°ç±ΩÕïMÂµâΩ∞§à§ÄòòÅç±ΩÕï∏πçΩπ—Ö•πÃ†âµÖ…≠ï–ÄÑÙÅAï…¡Õ5Ö…≠ï–πe8à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅΩŸï……•ëëï∏Åç…Â¡—ºÅ—Ö…ùï–Åµ•π–Åµ’Õ–Å’ÕîÅ—Ω≠ïπ•ÈïêÅ—Ö…ùï–Åç±ΩÕîÅ¡Ö—†∞ÅπΩ–ÅUMÅ±ïùÖç‰ÅôÖ±±âÖç¨à∞Åç±ΩÕï∏πçΩπ—Ö•πÃ†âµÖ…≠ï–π•Õ…Â¡—ºÅÒÄÖç…Â¡—ΩQÖ…ùï—5•π—=Ÿï……•ëîπ•Õ9’±±=…	±Öπ¨†§à§ÄòòÅç±ΩÕï∏πçΩπ—Ö•πÃ†àÖç…Â¡—ΩQÖ…ùï—5•π—=Ÿï……•ëîπ•Õ9’±±=…	±Öπ¨†§à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡……Â¡—ΩQ…Öëï…1ΩùÕ5ïµïΩµ¡Ö…Öâ±ï•ÖùπΩÕ—•çÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åô’ππï∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩâ…Ö•∏Ω…Â¡—Ω’ππï∞π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åë•ÖúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩ…Â¡—ΩUπ•Ÿï…ÕïΩ…ïπÕ•çÃπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åë•ùïÕ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ=¡ï…Ö—Ω…Aï…¡Õ…Â¡—Ω•ùïÕ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ï—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5Ö…≠ï—Õ1•Ÿï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—…Öëï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅ…Â¡—ºÅô’ππï∞Åµ’Õ–Åï·¡ΩÕîÅΩ¡ïπïêÅÖπêÅç±ΩÕïêÅΩ¨ΩôÖ•∞ÅçΩ’π—ï…Ãà∞Åô’ππï∞πçΩπ—Ö•πÃ†âΩ¡ïπïêÄÄÄÄÄÄÄÄÅΩ¨Ùà§ÄòòÅô’ππï∞πçΩπ—Ö•πÃ†âç±ΩÕïêÄÄÄÄÄÄÄÄÅΩ¨Ùà§ÄòòÅô’ππï∞πçΩπ—Ö•πÃ†âô’∏Åç±ΩÕî°Õ’ççïÕÃËÅ	ΩΩ±ïÖ∏§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅ…Â¡—ºÅ’π•Ÿï…ÕîÅôΩ…ïπÕ•çÃÅµ’Õ–ÅÖùù…ïùÖ—îÅ¡°ÖÕîΩ…Ω’—îΩë•ÖùπΩÕ—•åÅ…ïÖÕΩ∏ÅçΩ’π—Ãà∞Åë•ÖúπçΩπ—Ö•πÃ†â¡°ÖÕïΩ’π—Ãà§ÄòòÅë•ÖúπçΩπ—Ö•πÃ†âë•ÖùΩ’π—Ãà§ÄòòÅë•ÖúπçΩπ—Ö•πÃ†â…Ω’—ïΩ’π—Ãà§ÄòòÅë•ÖúπçΩπ—Ö•πÃ†âô’∏ÅÕ’µµÖ…‰†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅ…Â¡—ºÅç±ΩÕïÃÅµ’Õ–Å±ΩúÅÕ—Ö…–Ω•π¡’–ΩÕ’ççïÕÃΩôÖ•±’…îÅ¡°ÖÕïÃÅ±•≠îÅµïµîÅÕï±∞Åë•ÖùπΩÕ—•çÃà∞ÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âU}1=M}MQIPà§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âU}1=M}%9AUQ}IM=1Yà§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âU}1=M}=,à§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âU}1=M}9=}M%9QUIà§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âU}1=M}QIQ}	M9Pà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅ…Â¡—Ω±–Åç±ΩÕîÅµ’Õ–ÅôïïêÅ…Â¡—Ω’ππï∞Åç±ΩÕîÅçΩ’π—ï…Ãà∞Å—…Öëï»πçΩπ—Ö•πÃ†â…Â¡—Ω’ππï∞πç±ΩÕî°ç±ΩÕïM’ççïÕÃÿ–‡ÿ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡»ËÅΩ¡ï…Ö—Ω»Åç…Â¡—ºÅë•ùïÕ–Åµ’Õ–ÅÕ’…ôÖçîÅâ…•ëùîÅë•ÖùπΩÕ—•çÃÅ›•—†Å±Ωúµ¡Ö…•—‰ÅµÖ…≠ï»à∞Åë•ùïÕ–πçΩπ—Ö•πÃ†ââ…•ëùï•Öúà§ÄòòÅë•ùïÕ–πçΩπ—Ö•πÃ†â…Â¡—ΩUπ•Ÿï…ÕïΩ…ïπÕ•çÃπÕ’µµÖ…‰à§ÄòòÅë•ùïÕ–πçΩπ—Ö•πÃ†âç…Â¡—Ω}±Ωù}¡Ö…•—‰ı—…’îà§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡—MΩ’…çï°Ω≠ï•ÖùπΩÕ—•çÕπë1ïÖ…π•πùE’Ö…Öπ—•πî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åë•ÖúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMΩ’…çï°Ω≠ï•ÖùπΩÕ—•çÃ–‘‡–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ’ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1ïÖ…π•πù1•ôïçÂç±ï	’Ãπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ω…îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQ…Öëï!•Õ—Ω…ÂM—Ω…îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï¥ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπ]•π5ïµΩ…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅ¡…îµÅ±•ôïçÂç±îÅâ’ÃÅµ’Õ–ÅÖùù…ïùÖ—îÅ±ÖπîΩÕΩ’…çîΩ…ïÖÕΩ∏Åç°Ω≠îÅë•ÖùπΩÕ—•çÃà∞Åë•ÖúπçΩπ—Ö•πÃ†âô’∏Å¡…ïëúà§ÄòòÅâ’ÃπçΩπ—Ö•πÃ†âMΩ’…çï°Ω≠ï•ÖùπΩÕ—•çÃ–‘‡–π¡…ïëúà§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âMΩ’…çï°Ω≠ï•ÖùπΩÕ—•çÃ–‘‡–πÕ’µµÖ…‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅÕï±∞ÅΩ¨Å—ºÅ©Ω’…πÖ∞Åµ’Õ–Åï·¡ΩÕîÅ©Ω’…πÖ±ïêΩëïë’¿Ω≈’Ö…Öπ—•πîÅ…ïÖÕΩπÃà∞Åë•ÖúπçΩπ—Ö•πÃ†âô’∏ÅÕï±±)Ω’…πÖ∞à§ÄòòÅÕ—Ω…îπçΩπ—Ö•πÃ†âë’¡±•çÖ—ï}Õ’¡¡…ïÕÕïêà§ÄòòÅÕ—Ω…îπçΩπ—Ö•πÃ†â©Ω’…πÖ±ïë|àÄ¨ÄàêàÄ¨ÄâÌ—…ÖëïQΩM—Ω…îπÕ•ëîπ’¡¡ï…çÖÕî†•Ùà§ÄòòÅÕ—Ω…îπçΩπ—Ö•πÃ†âÖççΩ’π—•πù}≈’Ö…Öπ—•πïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅÕ—Ω¿µ±ΩÕÃÅΩŸï……’∏Åë•ÖùπΩÕ—•çÃÅµ’Õ–Å…ïçΩ…êÅ—…•ùùï»ÅÖπêÅô•πÖ±•—‰Å±Ö—ïπç‰à∞Åë•ÖúπçΩπ—Ö•πÃ†âÕ—Ω¡Q…•ùùï…ïêà§ÄòòÅë•ÖúπçΩπ—Ö•πÃ†âÕ—Ω¡•πÖ±•Èïêà§ÄòòÅâ’ÃπçΩπ—Ö•πÃ†âMΩ’…çï°Ω≠ï•ÖùπΩÕ—•çÃ–‘‡–πÕ—Ω¡Q…•ùùï…ïêà§ÄòòÅÕ—Ω…îπçΩπ—Ö•πÃ†âMΩ’…çï°Ω≠ï•ÖùπΩÕ—•çÃ–‘‡–πÕ—Ω¡•πÖ±•Èïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅQΩ≠ïπ]•π5ïµΩ…‰Åµ’Õ–Å≈’Ö…Öπ—•πîÅÕ°ÖëΩ‹ΩÕ•µ’±Ö—ïêÅÕΩ’…çïÃÅô…Ω¥Å…ïÖ∞Å›•ππï»ÅµïµΩ…‰à∞Åµï¥πçΩπ—Ö•πÃ†â•ÕM°ÖëΩ›=…M•µ’±Ö—ïëMΩ’…çîà§ÄòòÅµï¥πçΩπ—Ö•πÃ†âQ=-9}]%9}55=Ie}M!=]}M=UIà§ÄòòÅµï¥πçΩπ—Ö•πÃ†âAIM%MQ}M=UI}AQQI9}M!=\à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡—QΩ·•ç1ÖπïA•ŸΩ—%ÕM—…Ö—ïùÂ•…Õ—9Ω—5•ç…ΩA…Ωâï=π±‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å—’πï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•ŸïM—…Ö—ïùÂQ’πï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…Ω’—ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩùïπ—•çM—Â±ïIΩ’—ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅ—Ω·•åÅ±•ŸîÅ±ÖπïÃÅµ’Õ–Å…ï¡Ω…–Å…ïç±Ö•¥Å—Öç—•åÅ¡•ŸΩ–∞ÅπΩ–ÅΩ±êÅ•ππï»µ±ÖπîÅµ•ç…ºµ¡…ΩâîÅ›Ω…ë•πúà∞Å—’πï»πçΩπ—Ö•πÃ†â—Ω·•ç}…ïç±Ö•µ}—Öç—•ç}¡•ŸΩ–à§ÄòòÄÖ—’πï»πçΩπ—Ö•πÃ†â±Öâï∞ÄÙÅ•òÄ°—Ω·•ç%ππï…1ÖπïA•ŸΩ–§ÄàÄ¨Äâpâ—Ω·•ç}•ππï…}±Öπï}¡•ŸΩ—pàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡–ËÅùïπ—•çM—Â±ïIΩ’—ï»Åµ’Õ–ÅçΩπŸï…–Å—Ω·•åÅ—’π•πúÅ•π—ºÅÑÅ±Öπîµ±ΩçÖ∞Å…ïç±Ö•¥Ω±•≈’•ë•—‰ÅÕ—Â±îà∞Å…Ω’—ï»πçΩπ—Ö•πÃ†âQ=a%}I1%5}QQ%à§ÄòòÅ…Ω’—ï»πçΩπ—Ö•πÃ†âAU11	-}I1%4à§ÄòòÅ…Ω’—ï»πçΩπ—Ö•πÃ†â1%EU%%Qe}AQ à§ÄòòÅ…Ω’—ï»πçΩπ—Ö•πÃ†â—Ω·•çQÖç—•çA•ŸΩ––‘‡–à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡’ΩµµΩπMïπÕïUπ≠πΩ›πMÖôï—ÂA•ŸΩ—Õ9Ω—1Öπï°Ω≠ïÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å¡±ÖÂâΩΩ¨ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩΩµµΩπMïπÕïQ…ÖëïA±ÖÂâΩΩ¨π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡‘ËÅ—…’îÅ°Ö…êÅÕÖôï—‰Åµ’Õ–Å…ïµÖ•∏ÅÑÅ—ï…µ•πÖ∞ÅçΩµµΩ∏µÕïπÕîÅ…ï©ïç–à∞Å¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âQIU}!I}MQe}=I}!=1I}I%M,à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â°Ö…ëMÖôï—Â	±Ωç≠ïêà§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â°Ω±ëï…!Ö…ëI•Õ¨à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡‘ºÿ¿»¿ËÅ¡…ΩŸ•ëï»µâ±•πêÅÕÖôï—‰Ω°Ω±ëï»Å’πçï…—Ö•π—‰Åµ’Õ–ÅÕ°Ö¡îΩ¡•ŸΩ–Å—°…Ω’ù†Åô±’•êÅÕçΩ…ïÃÅ•πÕ—ïÖêÅΩòÅç°Ω≠•πúÅÖ±∞Å±ÖπïÃà∞Å¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âMQe}!=1I}U9=9%I5}QQ%}A%Y=Pà§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†â¡…ΩŸ•ëï…	±•πëMÖôï—‰à§ÄòòÅ¡±ÖÂâΩΩ¨πçΩπ—Ö•πÃ†âô±’•ëMçΩ…îÿ¿»¿†‘‘∏¿§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡‘ËÅΩ±êÅMQe}=I}!=1I}I%M,ÅÕ°Ω’±êÅ…ïµÖ•∏ÅΩπ±‰ÅÖÃÅπΩ∏µ—…ÖëïÖâ±îÅôÖ±±âÖç¨∞ÅπΩ–Åô•…Õ–Å°Ö…êÅâ…Öπç†à∞Å¡±ÖÂâΩΩ¨π•πëï·=ò†âô’∏ÅÖ±±Ω›M°Ö¡ïêà§ÄÅ¡±ÖÂâΩΩ¨π•πëï·=ò†âMQe}!=1I}U9=9%I5}QQ%}A%Y=Pà§ÄòòÅ¡±ÖÂâΩΩ¨π•πëï·=ò†âMQe}!=1I}U9=9%I5}QQ%}A%Y=Pà§ÄÅ¡±ÖÂâΩΩ¨π±ÖÕ—%πëï·=ò†âMQe}=I}!=1I}I%M,à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—î–‘‡’IïÖ±5ΩπïÂ9Ω—•ô•çÖ—•ΩπÕIï≈’•…ï]Ö±±ï—•πÖ±•—ÂπëççΩ’π—•πú†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡‘ËÅçÖ¡•—Ö∞Ω¡…Ωô•–ÅπΩ—•ô•çÖ—•ΩπÃÅµ’Õ–ÅâîÅëïôï……ïêÅ’π—•∞Å±•ŸîÅÕï±∞Åô•πÖ±•—‰à∞Åï·ïåπçΩπ—Ö•πÃ†âA%Q1}I=YIe}9=Q%e}II}U9Q%1}%91%Qe|–‘‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âAI=%Q}1=-}9=Q%e}II}U9Q%1}%91%Qe|–‘‡‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡‘ËÅçÖ¡•—Ö∞Å…ïçΩŸï…ïêÅÕ—Ö—îÅµ’Õ–Å…ï≈’•…îÅŸï…•ô•ïêÅM=0Å¡…ΩçïïëÃÅ—ºÅçΩŸï»ÅΩ…•ù•πÖ∞ÅçΩÕ–à∞Åï·ïåπçΩπ—Ö•πÃ†â…ïÖ±•ÈïëÖ¡•—Ö±IïçΩŸï…‰–‘‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÕΩ±	Öç¨Ä¯ÙÅ¡ΩÃπçΩÕ—MΩ∞Ä®Ä¿∏‰‡à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âA%Q1}I=YIe}MQQ}MUAAIMM}U9I1%i|–‘‡‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏–‘‡‘ËÅ…ïÖ∞µµΩπï‰ÅπΩ—•ô•çÖ—•ΩπÃÅµ’Õ–Å’ÕîÅ›Ö±±ï–µô•πÖ±•ÈïêÅÕΩ±	Öç¨Ωπï—Aπ∞ÅÖπêÅëïë’¡îÅâ‰ÅÕï±∞Å≠ï‰à∞Åï·ïåπçΩπ—Ö•πÃ†â…ïÖ±•Èïë5ΩπïÂ9Ω—•ô•ïëMï±±-ïÂÃ–‘‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âA%Q1}I=YIe}9=Q%e}I1%i|–‘‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âI1}5=9e}9=Q%e}UA}MUAAIMM|–‘‡‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏–‘‡‘ËÅ¡…îµô•πÖ±•—‰Å—…•ùùï»Å¡Ö—†Åµ’Õ–ÅπΩ–Åç±Ö•¥Å•π•—•Ö∞Å•πŸïÕ—µïπ–ÅÕïç’…ïêà∞Åï·ïåπçΩπ—Ö•πÃ†â•π•—•Ö∞Å•πŸïÕ—µïπ–ÅÕïç’…ïêà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—îÿ¿ƒÂ]Ö±±ï—A…ΩΩô…ÖçïπëA…Ωë’ç—•ŸïÖπΩ’—…ï9Ω—Ö’±—Ã†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å…’π—•µïMπÖ¿ÿ¿ƒ‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩI’π—•µïM—Ö—ïMπÖ¡Õ°Ω–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åù’Ö…ë•Ö∏ÿ¿ƒ‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ%πŸÖ…•Öπ—’Ö…ë•Ö∏π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å°ΩÕ–ÿ¿ƒ‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ!ΩÕ—]Ö±±ï—QΩ≠ïπQ…Öç≠ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÿ¿ƒ‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…’π—•µïMπÖ¿ÿ¿ƒ‰πçΩπ—Ö•πÃ†âΩ¡ïπ›Ö•—•πù]Ö±±ï—A…ΩΩòà§ÄòòÅù’Ö…ë•Ö∏ÿ¿ƒ‰πçΩπ—Ö•πÃ†â›Ö±±ï—A…ΩΩô±±Ω›Öπçîÿ¿ƒ‰à§ÄòòÅù’Ö…ë•Ö∏ÿ¿ƒ‰πçΩπ—Ö•πÃ†âïôôïç—•Ÿï1ïëùï……•ô–ÿ¿ƒ‰à§ÄòòÅ°ΩÕ–ÿ¿ƒ‰πçΩπ—Ö•πÃ†âùï—=¡ïπ›Ö•—•πù]Ö±±ï—A…ΩΩôΩ’π–à§ÄòòÅï·ïåÿ¿ƒ‰πçΩπ—Ö•πÃ†â¡…ΩΩô…Öçîÿ¿ƒ‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ù’Ö…ë•Ö∏ÿ¿ƒ‰πçΩπ—Ö•πÃ†â¡…Ωë’ç—•ŸïÖπΩ’–ÿ¿ƒ‰à§ÄòòÅù’Ö…ë•Ö∏ÿ¿ƒ‰πçΩπ—Ö•πÃ†â±ÖπïIÖ—•ºÄ¯Äƒ‡∏¿à§ÄòòÅù’Ö…ë•Ö∏ÿ¿ƒ‰πçΩπ—Ö•πÃ†â±ÖπïIÖ—•ºÄ¯Äƒ»∏¿ÄòòÄÖ¡…Ωë’ç—•ŸïÖπΩ’–ÿ¿ƒ‰à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—îÿ¿‹¡M•πù±ïA…ΩŸ•ëï…iï…ΩIï≈’•…ïÕQ›ΩIïÖëΩ……ΩâΩ…Ö—•Ωπ	ïôΩ…ïïµΩ—•Ω∏†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å°ΩÕ–ÿ¿‹¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ!ΩÕ—]Ö±±ï—QΩ≠ïπQ…Öç≠ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹¿ËÅÕ•πù±îµ¡…ΩŸ•ëï»ÅÈï…ºΩπºµÕï±∞µÕ•úÅâ…Öπç†Åµ’Õ–Å…ï≈’•…îÄ»ÅçΩπÕïç’—•ŸîÅ…ïÖëÃÅâïôΩ…îÅëïµΩ—•πúÅÑÅÕ—•±∞µ°ï±êÅ¡ΩÕ•—•Ω∏ÅΩ’–ÅΩòÅ=A9}MQQUMLà∞Å°ΩÕ–ÿ¿‹¿πçΩπ—Ö•πÃ†âM%91}AI=Y%I}iI=}=II=	=IQ%=9}A9%9|ÿ¿‹¿à§ÄòòÅ°ΩÕ–ÿ¿‹¿πçΩπ—Ö•πÃ†â•òÄ°¿πçΩπÕïç’—•Ÿïiï…ΩΩπô•…µÃÄÄ»§à§ÄòòÅ°ΩÕ–ÿ¿‹¿πçΩπ—Ö•πÃ†âµÖ…≠9Ω’……ïπ—!ï±ëA…ΩΩò°¿∞ÅpâM%91}AI=Y%I}iI=}9=}M11}M%pà§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹¿ËÅùïπï…Ö∞ÅΩπîµ¡…ΩŸ•ëï»µÈï…ºµ•∏µô±•ù°–Åâ…Öπç†Åµ’Õ–ÅÖ±ÕºÅ…ï≈’•…îÄ»µ…ïÖêÅçΩ……ΩâΩ…Ö—•Ω∏∞ÅπΩ–ÅÖç–ÅΩ∏Å—°îÅô•…Õ–Å…ïÖêà∞Å°ΩÕ–ÿ¿‹¿πçΩπ—Ö•πÃ†â=9}AI=Y%I}iI=}=II=	=IQ%=9}A9%9|ÿ¿‹¿à§ÄòòÅ°ΩÕ–ÿ¿‹¿πçΩπ—Ö•πÃ†âµÖ…≠9Ω’……ïπ—!ï±ëA…ΩΩò°¿∞Åpâ=9}AI=Y%I}iI=}%9}1%!Qpà§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹¿ËÅçΩ……ΩâΩ…Ö—•Ω∏ÅçΩ’π—ï»Åµ’Õ–Å•πç…ïµïπ–ÅâïôΩ…îÅ—°îÅëïµΩ—•Ω∏Åâ…Öπç†Åô•…ïÃ∞ÅµÖ—ç°•πúÅ—°îÅ	M9Q}5%9PΩM11}YI%e%9Ä»µ…ïÖêÅ±Öëëï»ÅÖ±…ïÖë‰Å’ÕïêÅï±Õï›°ï…îÅ•∏Å—°•ÃÅô•±îà∞Å°ΩÕ–ÿ¿‹¿πçΩπ—Ö•πÃ†â¿πçΩπÕïç’—•Ÿïiï…ΩΩπô•…µÃÄ¨ÙÄƒà§ÄòòÅ°ΩÕ–ÿ¿‹¿πçΩπ—Ö•πÃ†â¿πçΩπÕïç’—•Ÿïiï…ΩΩπô•…µÃÄ¯ÙÄ»à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—îÿ¿‹›MÕ•ù•Q’πïÕ…ΩµQ…Öëï=πï9Ω	ΩΩ—Õ—…Ö¡ç—’Ö—Ω…±•ôôÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åµï—ÑÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ’—ΩπΩµΩ’Õ5ï—ÖAΩ±•ç‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—’πï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•ŸïM—…Ö—ïùÂQ’πï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5ï—ÖΩùπ•—•Ωπ·ïç’—Ω…	…•ëùîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å’¡†ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩUπ•ô•ïëAΩ±•çÂ!ïÖêπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…ΩàÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•ŸïA…ΩâÖâ•±•—Âπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‹ËÅ’—ΩπΩµΩ’Õ5ï—ÖAΩ±•ç‰Åµ’Õ–ÅÕ°Ö¡îÅô…Ω¥Å—°îÅô•…Õ–ÅÕï——±ïêÅçΩπ—ï·–Å•πÕ—ïÖêÅΩòÅ…ï—’…π•πúÅπï’—…Ö∞Å’π—•∞Å5%9}M5A1Là∞Åµï—ÑπçΩπ—Ö•πÃ†â•òÄ°Ö…¥πÕÖµ¡±ïÃÄÙÄ¿§Å…ï—’…∏Äƒ∏¿à§ÄòòÅµï—ÑπçΩπ—Ö•πÃ†âQI≈}I5A}1==Hà§ÄòòÅµï—ÑπçΩπ—Ö•πÃ†â—…Öëî≈IÖµ¿ÿ¿‹‹à§ÄòòÄÖµï—ÑπçΩπ—Ö•πÃ†â•òÄ°Ö…¥πÕÖµ¡±ïÃÄÅ5%9}M5A1L§Å…ï—’…∏Äƒ∏¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‹ËÅ1•ŸïM—…Ö—ïùÂQ’πï»Åµ’Õ–ÅÖëµ•–Å∏Ùƒ∏∏–Å±ÖπïÃÅ—°…Ω’ù†ÅâΩ’πëïêÅ—…Öëî¥ƒÅ…Öµ¿Å•πÕ—ïÖêÅΩòÅÕ≠•¡¡•πúÅÖ±∞Å±ÖπïÃÅ’πëï»Åô•ŸîÅç±ΩÕïÃà∞Å—’πï»πçΩπ—Ö•πÃ†â•òÄ°¥π—…ÖëïÃÄÙÄ¿§ÅçΩπ—•π’îà§ÄòòÅ—’πï»πçΩπ—Ö•πÃ†â•òÄ°∏Å•∏ÄƒÅ’π—•∞Å5%9}QU9}QIL§à§ÄòòÅ—’πï»πçΩπ—Ö•πÃ†â—…Öëî≈}¡ΩÕ•—•Ÿï}…Öµ¡|ÿ¿‹‹à§ÄòòÅ—’πï»πçΩπ—Ö•πÃ†â—…Öëî≈}…•Õ≠}…Öµ¡|ÿ¿‹‹à§ÄòòÄÖ—’πï»πçΩπ—Ö•πÃ†â•òÄ°¥π—…ÖëïÃÄÅ5%9}QU9}QIL§ÅçΩπ—•π’îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‹ËÅ5ï—ÖΩùπ•—•Ω∏Åï·ïç’—Ω»Åâ…•ëùîÅµ’Õ–Å…ïµΩŸîÅ—°îÄÃ¿µ—…ÖëîÅπï’—…Ö∞Åç±•ôòÅÖπêÅ…Öµ¿ÅïÖ…±‰Å—…’Õ–Åµ’±—•¡±•ï…Ãà∞Åâ…•ëùîπçΩπ—Ö•πÃ†â•òÄ°ÖπÖ±ÂÈïêÿ¿‹‹ÄÙÄ¿§Å…ï—’…∏Äƒ∏¿à§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†â—…Öëî≈IÖµ¿ÿ¿‹‹à§ÄòòÄÖâ…•ëùîπçΩπ—Ö•πÃ†âùï—QΩ—Ö±Q…ÖëïÕπÖ±ÂÈïê†§ÄÄÃ¿§Å…ï—’…∏Äƒ∏¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‹ËÅUπ•ô•ïëAΩ±•çÂ!ïÖêÅÖπêÅ1•ŸïA…ΩâÖâ•±•—Âπù•πîÅµ’Õ–Åâ±ïπêÅâΩΩ—Õ—…Ö¿Å¡Ω±•ç‰ÅÕ•ùπÖ±ÃÅô…Ω¥Åô•…Õ–Å—…Ö•π•πúÅÕÖµ¡±î∞ÅπΩ–ÅÈï…ºµ›ï•ù°–ÅâΩΩ—Õ—…Ö¿à∞Å’¡†πçΩπ—Ö•πÃ†â—…Ö•πïëΩ…IÖµ¿ÿ¿‹‹à§ÄòòÅ’¡†πçΩπ—Ö•πÃ†â—…Öëî≈IÖµ¿ÿ¿‹‹à§ÄòòÅ¡…ΩàπçΩπ—Ö•πÃ†â¡Ω±•çÂMÖµ¡±ïÃÿ¿‹‹à§ÄòòÄÖ¡…ΩàπçΩπ—Ö•πÃ†â¡Ω±•çÂ\ÄÙÅ•òÄ°Uπ•ô•ïëAΩ±•çÂ!ïÖêπôΩ…µÖ—Ω…A•¡ï±•πï’µ¿†§à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅÖÖ—îÿ¿‹·U•)Ω’…πÖ±IïÖë•πïÕÕΩ¡ÂπëIïÕ’±—1ïÖ…π•πùQ…’—††§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ω…îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQ…Öëï!•Õ—Ω…ÂM—Ω…îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï…»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω……Ω…1Ωùç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±ÖàÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ÖàΩ1±µ1Öâπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕÕ§ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMÕ•A•±Ω—Ω’πç•∞π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‡ËÅU$Ω…ïÖë•πïÕÃÅÕ—Ö—ÃÅµ’Õ–Å’ÕîÅM—…Ö—ïùÂQ…’—°1ïëùï»µç±ïÖ∏Å—…’—†ÅÖπêÅπºµëÖ—ÑÅµ’Õ–ÅπΩ–ÅôÖ≠îÄ‘¿îÅ]Hà∞ÅÕ—Ω…îπçΩπ—Ö•πÃ†â…ï—’…∏Å—…‰ÅÏÅùï—±ïÖπM—Ö—ÕMπÖ¡Õ°Ω––‘ƒ‹†§ÅÙà§ÄòòÅÕ—Ω…îπçΩπ—Ö•πÃ†âŸÖ∞Å›•πIÖ—îËÄÄÄÄÄÄÄÄÄÄÄÅΩ’â±îÄÙÄ¿∏¿à§ÄòòÅÕ—Ω…îπçΩπ—Ö•πÃ†âŸÖ∞ÅÖŸù!Ω±ëQ•µï5•π’—ïÃËÅ%π–ÄÄÄÄÙÄ¿à§ÄòòÄÖÕ—Ω…îπçΩπ—Ö•πÃ†âï±ÕîÄ‘¿∏¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†(ÄÄÄÄÄÄÄÄÄÄÄÄâX‘∏¿∏ÿÿÃÿËÅ=¡ï∏ÅAΩÕ•—•ΩπÃÅçΩπ—…Öç–Åµ’Õ–Å¡…ïÕï…ŸîÅ—°îÅ—Ω¿µ—ï∏ΩôΩΩ—ï»ÅµΩëï∞Å›°•±îÅÖùù…ïùÖ—•πúÅΩπ±‰ÅçÖπΩπ•çÖ∞Å—…’Õ—ïêÅAπ0à∞(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ•∏πçΩπ—Ö•πÃ†â=¡ïπAΩÕ•—•ΩπÕ5Ωëï∞ÿ¿‹‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ•∏πçΩπ—Ö•πÃ†âçÖç°ïë=¡ïπAΩÕ•—•ΩπÕ5Ωëï∞ÿ¿‹‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ•∏πçΩπ—Ö•πÃ†â¡…ïçΩµ¡’—ïQΩ—Ö±U¡π∞ÿÿÃÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ•∏πçΩπ—Ö•πÃ†â=¡ïπAπ±MÖπ•—‰π¡…•ç•πùQ…’—†à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ•∏πçΩπ—Ö•πÃ†â¡…ïMΩ…—ïêÿ¿‹‡ÄÙÅ—…’îà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ•∏πçΩπ—Ö•πÃ†âŸÖ∞ÅI9I}@ÄÙÅ=A9A=M}I=]}@à§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‡ËÅI’π—•µîÅ…ï¡Ω…–Åï·¡Ω…–Åâ’——Ω∏Åµ’Õ–ÅÖ±›ÖÂÃÅçΩ¡‰ÅÖ∏ÅΩâÕï…ŸÖâ±îÅ’π•ô•ïêµ…ï¡Ω…–ÅôÖ±±âÖç¨à∞Åï…»πçΩπ—Ö•πÃ†âU9%%}IA=IQ}aA=IQ}1%-|ÿ¿‹‡à§ÄòòÅï…»πçΩπ—Ö•πÃ†âUπ•ô•ïêÅ…ï¡Ω…–ÅçΩ¡•ïêà§ÄòòÅï…»πçΩπ—Ö•πÃ†âA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»πë’µ¡Qï·–†§π—Ö≠î†»—|¿¿¿§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‡ËÅÖ±∞ÅÕï±∞µ±•≠îÅ…ïÕ’±—ÃÅµ’Õ–ÅôïïêÅ114ΩMM$ÅçΩπ—ï·–Å›•—†ÅÖççï¡—ïêΩ—…Ö•πÖâ±îÅô±ÖùÃÅ›°•±îÅ¡Ω±•ç‰Å°ïÖëÃÅ…ïµÖ•∏Åç±ïÖ∏µùÖ—ïêà∞Åï·ïåπçΩπ—Ö•πÃ†â11}IMU1Q}=9QaQ}=	MIY|ÿ¿‹‡à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â…ïçΩ…ë·—ï…πÖ±=’—çΩµîÿ¿‹‡à§ÄòòÅ±ÖàπçΩπ—Ö•πÃ†âï·—ï…πÖ±=’—çΩµïM’µµÖ…‰ÿ¿‹‡à§ÄòòÅÕÕ§πçΩπ—Ö•πÃ†âIMU1QLÿ¿‹‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ¿‹‡ËÅ±•ŸîÅ¡ΩÕ•—•ΩπÃÅµ’Õ–Å¡…ïÕï…ŸîÅùïπ—•çM—Â±ïIΩ’—ï»ÅÕ—Â±îÅÕ’…ôÖçîÅ•πÕ—ïÖêÅΩòÅçΩ±±Ö¡Õ•πúÅ—ºÅùïπï…•åÅ±ÖπîÅïµΩ©§à∞Åï·ïåπçΩπ—Ö•πÃ†â¡…ïÕï…ŸîÅ—°îÅô’±∞Åùïπ—•çM—Â±ïIΩ’—ï»ÅÕ—Â±îÅÕ’…ôÖçîà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â—…Öë•πù5ΩëïµΩ©§ÄÙÅ±•Õ—=òà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â…Ω’—ïëM—Â±ïQÖúπ•ô	±Öπ¨à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÃ¿·}¡•¡ï±•πï}…ï¡Ω…—}ùïπï…Ö—•Ωπ}°ÖÕ}›Ö—ç°ëΩù}ôÖ±±âÖç≠}Öπë}µÖ•π}ç±•¡âΩÖ…ê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å’§ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§ΩA•¡ï±•πï!ïÖ±—°ç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ¿‡ËÅ¡•¡ï±•πîÅ…ï¡Ω…–Åùïπï…Ö—•Ω∏Åµ’Õ–ÅπïŸï»ÅÕ•±ïπ—±‰Å°ÖπúÅÖô—ï»Åô•…Õ–Å—ΩÖÕ–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ’§πçΩπ—Ö•πÃ†âUI9QÅA%A1%9ÅIA=IPÅ9IQ%=8à§ÄòòÅ’§πçΩπ—Ö•πÃ†âU9%%}IA=IQ}]Q!=}11	-|ÿÃ¿‡à§ÄòòÅ’§πçΩπ—Ö•πÃ†âô’±±}â’•±ëï…}—•µïΩ’—|·Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ¿‡ËÅç±•¡âΩÖ…êÅ›…•—ïÃÅµ’Õ–Å°Ö¡¡ï∏Åô…Ω¥ÅµÖ•π!Öπë±ï»Åëï±•Ÿï…‰∞ÅπΩ–Å—°îÅ…ï¡Ω…–Åâ’•±ëï»Å—°…ïÖêà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ’§πçΩπ—Ö•πÃ†âô’∏Åëï±•Ÿï…Iï¡Ω…–ÿÃ¿‡à§ÄòòÅ’§πçΩπ—Ö•πÃ†âµÖ•π!Öπë±ï»π¡ΩÕ–à§ÄòòÅ’§πçΩπ—Ö•πÃ†âçàπÕï—A…•µÖ…Â±•¿à§ÄòòÅ’§πçΩπ—Ö•πÃ†âIï¡Ω…–Åùïπï…Ö—ïêÅâ’–Åç±•¡âΩÖ…êÅôÖ•±ïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ¿‡ËÅùïπï…Ö—ïêÅ…ï¡Ω…–Åµ’Õ–ÅÖ±ÕºÅ…ïπëï»ÅΩ∏µÕç…ïï∏Å•∏ÅâΩ’πëïêÅÕïç—•ΩπÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ’§πçΩπ—Ö•πÃ†âô’±±’µ¡Öç°îÄÙÅÕÖôïQï·–à§ÄòòÅ’§πçΩπ—Ö•πÃ†â…ï¡Ω…—Mïç—•ΩπÃÄÙÅÕ¡±•—’µ¡%π—ΩMïç—•ΩπÃ°ÕÖôïQï·–§à§ÄòòÅ’§πçΩπ—Ö•πÃ†â…ïπëï…’……ïπ—Mïç—•Ω∏†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ¿‡ËÅôÖ±±âÖç¨Å…ï¡Ω…–Åµ’Õ–Å•πç±’ëîÅ—°îÅç°Ω≠îµç…•—•çÖ∞Å¡•¡ï±•πîÅÕ’…ôÖçïÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ’§πçΩπ—Ö•πÃ†âQÅA%A1%9Å5I9dÅIA=IPÅX‘∏¿∏ÿÃ¿‡à§ÄòòÅ’§πçΩπ—Ö•πÃ†âÅ	1=,ÅIM=9Là§ÄòòÅ’§πçΩπ—Ö•πÃ†â1%YÅ	UdÅ%0ÅIM=9Là§ÄòòÅ’§πçΩπ—Ö•πÃ†â%9Q-Å	dÅM=UIà§ÄòòÅ’§πçΩπ—Ö•πÃ†âI9PÅY9QLà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÃÿÂ}¡Ö¡ï…}±Öπï}ôÖπΩ’—}çÖππΩ—}ë’¡±•çÖ—ï}Ω¡ïπ}Ω…}â…ΩÖë}çÖÕ°ùïπ}…ïÕç’î†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï…	’‰ÄÙÅï·ïåπÕ’âÕ—…•πú°ï·ïåπ•πëï·=ò†âô’∏Å¡Ö¡ï…	’‰†à§∞Åï·ïåπ•πëï·=ò†àººÅQ°•ÃÅ…’πÃÅâïôΩ…îÅ›…Ö¡¡ï»ÅAAI}	Udà∞Åï·ïåπ•πëï·=ò†âô’∏Å¡Ö¡ï…	’‰†à§§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃÿ‰ËÅ¡Ö¡ï…	’‰Åµ’Õ–ÅÖç≈’•…îÅÖ∏ÅïÖ…±‰Å¡ï»µµ•π–Å	UdÅ±ïÖÕîÅâïôΩ…îÅ—…Öëï%êΩΩ¡ï∏Åµ’—Ö—•Ω∏ÅÕºÅ±ÖπîÅôÖπΩ’–ÅçÖππΩ–ÅëΩ’â±îµΩ¡ï∏Å—°îÅÕÖµîÅ¡Ö¡ï»Åµ•π–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âAAHÅ	UdÅ9=UPÅIÅ1%4à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†â·ïç’—•Ωπ——ïµ¡—1ïÖÕîπÖç≈’•…îà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰π•πëï·=ò†â·ïç’—•Ωπ——ïµ¡—1ïÖÕîπÖç≈’•…îà§ÄÅ¡Ö¡ï…	’‰π•πëï·=ò†âŸÖ∞Å—…Öëï%êÄÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âAAI}	Ue}UA1%Q}MUAAIMM|ÿÃÿ‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃÿ‰ËÅ¡Ö¡ï…	’‰Åµ’Õ–Å…ï±ïÖÕîÅ—°îÅïÖ…±‰Å±ïÖÕîÅΩ∏ÅπΩ–µΩ¡ïπïêÅ¡Ö—°ÃÅÖπêÅç±ïÖ»Å•–ÅΩ∏ÅÕ’ççïÕÕô’∞ÅΩ¡ï∏à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†â·ïç’—•Ωπ——ïµ¡—1ïÖÕîπ…ï±ïÖÕï9ΩπQï…µ•πÖ∞à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âAAI}	Ue}9=Q}=A9|à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†â·ïç’—•Ωπ——ïµ¡—1ïÖÕîπ—ï…µ•πÖ±=¨à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ°¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âAAI}	Ue}=A9|ÿÃÿ‰à§ÅÒÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âAAI}	Ue}=A9|ÿÃ‹¿à§§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ‘‰‰ËÅ¡Ö¡ï»ÅÕ¡ïç•Ö±•Õ–Åï·ïç’—•Ω∏Å•ÃÅ—°îÅΩπîÅ≈’Ö±•ô•ïêÅçÖπΩπ•çÖ∞Å¡…•µÖ…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†ââΩ’πëïëIïÕç’îÿÿ¿¿à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÕ¡ïç•Ö±•Õ—ŸÖ±’Ö—•Ωπ±±Ω›ïêÿÿ¿¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â=9QI%	UQ=I}=91dà§ÄòòÄÖâΩ–πçΩπ—Ö•πÃ†âÕ—…ΩπùïÕ—ïÕ¨ÿ‘‰‰à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÃ‹¡}¡Ö¡ï…}â’Â}â±Ωç≠Õ}ÕÖµï}µ•π—}Ö±•ÖÕ}âïôΩ…ï}¡…•çï}›Ω…¨†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï…	’‰ÄÙÅï·ïåπÕ’âÕ—…•πú°ï·ïåπ•πëï·=ò†âô’∏Å¡Ö¡ï…	’‰†à§∞Åï·ïåπ•πëï·=ò†àººÅQ°•ÃÅ…’πÃÅâïôΩ…îÅ›…Ö¡¡ï»ÅAAI}	Udà∞Åï·ïåπ•πëï·=ò†âô’∏Å¡Ö¡ï…	’‰†à§§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ‹¿ËÅ¡Ö¡ï…	’‰Åµ’Õ–ÅçΩπÕ’±–Åù±ΩâÖ∞Åµï…ùïπ—’Ö…ë…Ö•±ÃÅΩ›πï»ÅâïôΩ…îÅ¡…•çîΩÕ•ÈîÅ›Ω…¨ÅÕºÅÕï¡Ö…Ö—îÅQΩ≠ïπM—Ö—îÅÖ±•ÖÕïÃÅçÖππΩ–Å…ïΩ¡ï∏Å—°îÅÕÖµîÅµ•π–ÅÖô—ï»Å—°îÅÕ°Ω…–Å±ïÖÕîÅï·¡•…ïÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†â1=	0ÅM5µ5%9PÅAAHÅ=A8ÅUIà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âµï…ùïπ—’Ö…ë…Ö•±Ãπùï—AΩÕ•—•Ωπ1ÖÂï»°—…Öëï%êπµ•π–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰π•πëï·=ò†âµï…ùïπ—’Ö…ë…Ö•±Ãπùï—AΩÕ•—•Ωπ1ÖÂï»°—…Öëï%êπµ•π–§à§ÄÅ¡Ö¡ï…	’‰π•πëï·=ò†âŸÖ∞Å¡…•çîÄÙÅùï—ç—’Ö±A…•çî°—Ã§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âAAI}	Ue}M5}5%9Q}=A9}MUAAIMM|ÿÃ‹¿à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…Â]…•—îÿÃ‹¿ÄÙÅ¡Ö¡ï…	’‰π•πëï·=ò†âµï…ùïπ—’Ö…ë…Ö•±Ãπ…ïù•Õ—ï…AΩÕ•—•Ω∏à§(ÄÄÄÄÄÄÄÅŸÖ∞Åù±ΩâÖ±]…•—îÿÃ‹¿ÄÙÅ¡Ö¡ï…	’‰π•πëï·=ò†â±ΩâÖ±Q…ÖëïIïù•Õ—…‰π…ïù•Õ—ï…AΩÕ•—•Ω∏à∞Å…ïù•Õ—…Â]…•—îÿÃ‹¿§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩ¡ïπïëQï…µ•πÖ∞ÿÃ‹¿ÄÙÅ¡Ö¡ï…	’‰π•πëï·=ò†âµÖ…≠AÖ¡ï…Q•ç≠ï—Qï…µ•πÖ±=¡ï∏àÄ¨Äàÿ‘ƒ–à∞Åù±ΩâÖ±]…•—îÿÃ‹¿§(ÄÄÄÄÄÄÄÅŸÖ∞Å—ï…µ•πÖ±!ï±¡ï»ÿÃ‹¿ÄÙÅ¡Ö¡ï…	’‰πÕ’âÕ—…•πú°¡Ö¡ï…	’‰π•πëï·=ò†âô’∏ÅµÖ…≠AÖ¡ï…Q•ç≠ï—Qï…µ•πÖ±=¡ï∏àÄ¨Äàÿ‘ƒ–à§∞Å¡Ö¡ï…	’‰π•πëï·=ò†àººÅX‘∏¿∏ÿ–‘ƒà∞Å¡Ö¡ï…	’‰π•πëï·=ò†âô’∏ÅµÖ…≠AÖ¡ï…Q•ç≠ï—Qï…µ•πÖ±=¡ï∏àÄ¨Äàÿ‘ƒ–à§§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ‹¿ËÅÕ’ççïÕÕô’∞Åπï‹µΩ¡ï∏Å±ïÖÕîÅµ’Õ–Åç±ïÖ»ÅΩπ±‰ÅÖô—ï»Åµï…ùïπ—’Ö…ë…Ö•±ÃÅÖπêÅ±ΩâÖ±Q…ÖëïIïù•Õ—…‰Å…ïù•Õ—…•ïÃÏÅ…ï—…‰Å…ïçΩŸï…‰ÅµÖ‰Å—ï…µ•πÖ±•ÈîÅ•—ÃÅï·•Õ—•πúÅ…ïÕ’±–ÅïÖ…±•ï»à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…Â]…•—îÿÃ‹¿Ä¯ÙÄ¿ÄòòÅù±ΩâÖ±]…•—îÿÃ‹¿Ä¯Å…ïù•Õ—…Â]…•—îÿÃ‹¿ÄòòÅΩ¡ïπïëQï…µ•πÖ∞ÿÃ‹¿Ä¯Åù±ΩâÖ±]…•—îÿÃ‹¿Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—ï…µ•πÖ±!ï±¡ï»ÿÃ‹¿πçΩπ—Ö•πÃ†â·ïç’—•Ωπ——ïµ¡—1ïÖÕîπ—ï…µ•πÖ±=¨à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…	’‰πçΩπ—Ö•πÃ†âAAI}	Ue}=A9|ÿÃ‹¿à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÃ‹≈}Ω¡ïπ}ùÖ—ï}ÕÖµï}µ•π—}¡Ö¡ï…}çΩΩ±ëΩ›π}Öπë}ù°ΩÕ—}Èï…Ω}µ•π—}Ωπ±Â}±Ωç≠Ω’–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïïπ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïπ—…Â1Ωç≠Ω’–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ‹ƒËÅÕÖµîµµ•π–ÅAAHÅë’¡±•çÖ—ïÃÅµ’Õ–ÅâîÅâ±Ωç≠ïêÅ•∏Å·ïç’—Öâ±ï=¡ïπÖ—îÅâïôΩ…îÅ¡Ö¡ï…	’‰ÅÕºÅâ±Ωç≠ïê†§Å•πÕ—Ö±±ÃÅÑÅçΩΩ±ëΩ›∏ÅÖπêÅ…ï¡ïÖ—ïêÅÖ±•ÖÕïÃÅÕ—Ω¿Åâ’…π•πúÅâ’‰µ¡Ö—†Å›Ω…¨à∞(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†â=A8µQÅM5µ5%9PÅAAHÅ==1=]8à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âµï…ùïπ—’Ö…ë…Ö•±Ãπùï—AΩÕ•—•Ωπ1ÖÂï»°µ•π–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âa}=A9}M5}5%9Q}1Ie}=A9}==1=]9|ÿÃ‹ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âa}=A9}	1=-}M5}5%9Q}1Ie}=A9|ÿÃ‹ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπ•πëï·=ò†â=A8µQÅM5µ5%9PÅAAHÅ==1=]8à§ÄÅùÖ—îπ•πëï·=ò†âM!=]}QI%9}=91dÅ•ÃÅ9=PÅÖ∏Åï·ïç’—•Ω∏ÅŸï—ºà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ‹ƒËÅ!=MQ}IA}iI=}	19Åµ’Õ–Åµ•π–µ±Ωç¨ÅΩπ±‰∞ÅπΩ–ÅôÖµ•±‰µ±Ωç¨∞ÅÕºÅù°ΩÕ–Åç±ïÖπ’¿ÅëΩïÃÅπΩ–Åç°Ω≠îÅô…ïÕ†ÅôÖµ•±‰ÅçÖπë•ëÖ—ïÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïïπ—…‰πçΩπ—Ö•πÃ†âù°ΩÕ—iï…Ω±ïÖπ’¿ÿÃ‹ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïïπ—…‰πçΩπ—Ö•πÃ†âI9QIe}1=-=UQ}I5}5%9Q}=91e}!=MQ}iI=|ÿÃ‹ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïïπ—…‰πçΩπ—Ö•πÃ†â•òÄ°ÕÂµâΩ±Öµ•±‰π•Õ9Ω—	±Öπ¨†§ÄòòÄÖù°ΩÕ—iï…Ω±ïÖπ’¿ÿÃ‹ƒ§ÅâÂÖµ•±‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïïπ—…‰πçΩπ—Ö•πÃ†âôÖµ•±Â1Ωç≠ïêÙàÄ¨ÄàëÏúêùÙàÄ¨ÄâÏÖù°ΩÕ—iï…Ω±ïÖπ’¿ÿÃ‹≈Ùà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÃ‹…}’π•Ÿï…ÕÖ±}çΩµ¡Ω’πë}—Ö…ùï—}Ö¡¡±•ïÕ}Ö±±}…ïÖ±}±ÖπïÕ}±•Ÿï}Öπë}¡Ö¡ï»†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å—Ö…ùï–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5ïµïΩµ¡Ω’πëQÖ…ùï–ÿ»‘ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIï¡Ω…—•πù!’àπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ‹»ËÄ…‡¥’‡ÅçΩµ¡Ω’πêÅ¡Ω±•ç‰Åµ’Õ–ÅâîÅ’π•Ÿï…ÕÖ∞ÅÖç…ΩÕÃÅ±•ŸîΩ¡Ö¡ï»ÅÖπêÅÖ±∞Å…ïÖ∞Å±ÖπïÃ∞ÅπΩ–ÅµïµîµΩπ±‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—Ö…ùï–πçΩπ—Ö•πÃ†âU9%YIM0Ä…„äL’‡Å%1dÅ=5A=U9ÅA=1%dà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ö…ùï–πçΩπ—Ö•πÃ†â9dÅµΩëîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ö…ùï–πçΩπ—Ö•πÃ†â±•ŸîÅΩ»Å¡Ö¡ï»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ö…ùï–πçΩπ—Ö•πÃ†ààâ•òÄ°±ÖπïU¿π•Õ	±Öπ¨†§ÅÒÅ±ÖπïU¿ÄÙÙÄâU9-9=]8à§Å…ï—’…∏Äƒ∏¿ààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖ—Ö…ùï–πçΩπ—Ö•πÃ†àààÖ±ÖπïU¿πçΩπ—Ö•πÃ†â55à§ààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖ—Ö…ùï–πçΩπ—Ö•πÃ†â9Ω∏µ55Å±ÖπïÃÅùï–Äƒ∏¿√\à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ‹»ËÅ·ïç’—Ω»ÅÕ•È•πúÅµ’Õ–Åµ’±—•¡±‰Å—°îÅ’π•Ÿï…ÕÖ∞Å—Ö…ùï–Å—°…Ω’ù†Å¡Ö¡ï…	’‰Åô±’•êÅÕ•È•πúÅ—ï±ïµï—…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âU9%YIM0Ä…‡¥’‡ÅëÖ•±‰ÅçΩµ¡Ω’πêÅ—Ö…ùï–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†â’π•Ÿï…ÕÖ±QÖ…ùï—5’±–ÿÃ‹»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†â’π•Ÿï…ÕÖ±Qù–ÿÃ‹»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖï·ïåπçΩπ—Ö•πÃ†âµïµïQù–Ùà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿÃ‹»ËÅ…ï¡Ω…—ÃÅµ’Õ–Åï·¡ΩÕîÅ—°îÅ’π•Ÿï…ÕÖ∞Å—Ö…ùï–Å±Öâï∞Å•πÕ—ïÖêÅΩòÅµïµîµΩπ±‰Å±Öπù’Öùîà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â’π•Ÿï…ÕÖ±}çΩµ¡Ω’πë}—Ö…ùï—|ÿÃ‹»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ö…ùï–πçΩπ—Ö•πÃ†âX‘∏¿∏ÿÃ‹…}U9%YIM1}=5A=U9}QIPà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ––Ÿ}Õ±Ω›}çÂç±ï}ÕΩ’…çï}ç°Ω≠ïÕ}Ö…ï}â’ëùï—ïë}âïôΩ…ï}Õ’¡ï…Ÿ•ÕΩ»†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕÖµï5•π–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩMÖµï5•π—ïë’¡’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕçÖππï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMçÖππï…ÖπΩ’—ïë’¡îÿÃ‹–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––ÿËÅÕ±Ω‹µçÂç±îÅ]Q!1%MQ}AI%=I%Q%iÅµ’Õ–ÅâîÅâ’ëùï—ïêÅâïôΩ…îÅÕ’¡ï…Ÿ•ÕΩ»Å•πÕ—ïÖêÅΩòÅô’±∞µÕçΩ…•πúÅïŸï…‰Åµ•π–ÅïŸï…‰Å—•ç¨à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â]Q!1%MQ}AI%=I%Qe}	UQ}	eAMM|ÿ––ÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â±ÖÕ—A…ïŸÂç±ï5Ãÿ–»ƒÄ¯ÄÃ¡|¿¿¡0à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â±ΩΩ¡Ω’π–ÄîÄÃÄÑÙÄ¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÕ≠•¡}ô’±±}ÕΩ…—}—°•Õ}—•ç¨à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âŸÖ∞Å¡…•Ω…•—•Èïë]Ö—ç°±•Õ–ÄÙÅ•òÄ°çôúπÿÕπù•πïπÖâ±ïêÄòòÄÖ›Ö—ç°±•Õ—A…•Ω…•—Â	’ëùï—	Â¡ÖÕÃÿ––ÿ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––ÿËÅÕÖµîµµ•π–Åëïë’¿Åµ’Õ–Å’ÕîÅçÖπΩπ•çÖ∞Ä¨Åïµï…ùïπ–Ä¨Å…ïù•Õ—…‰ÅΩ¡ï∏Å—…’—†ÅÖπêÅ=1MÅµ’Õ–ÅπΩ–Åç…ïÖ—îÅÑÅπï‹ÅÕçÖππï»ÅçÖπë•ëÖ—îà∞(ÄÄÄÄÄÄÄÄÄÄÄÅÕÖµï5•π–πçΩπ—Ö•πÃ†âµï…ùïπ—’Ö…ë…Ö•±Ãπùï—AΩÕ•—•Ωπ1ÖÂï»°µ•π–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕÖµï5•π–πçΩπ—Ö•πÃ†â±ΩâÖ±Q…ÖëïIïù•Õ—…‰π°ÖÕ=¡ïπAΩÕ•—•Ω∏°µ•π–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕÖµï5•π–πçΩπ—Ö•πÃ†âM5}5%9Q}	1=-}=A9}5I9Q|ÿ––ÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕçÖππï»πçΩπ—Ö•πÃ†âïç•Õ•Ω∏π=1Mà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕçÖππï»πÕ’âÕ—…•πú°ÕçÖππï»π•πëï·=ò†âŸÖ∞Åëïç•Õ•Ω∏ÄÙà§∞ÅÕçÖππï»π•πëï·=ò†â…ï—’…∏Å—…’îà∞ÅÕçÖππï»π•πëï·=ò†âŸÖ∞Åëïç•Õ•Ω∏ÄÙà§§§πçΩπ—Ö•πÃ†â…ï—’…∏ÅôÖ±Õîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––ÿËÅ1ÖâUπ•Ÿï…ÕïQ•ç¨Åµ’Õ–ÅÕπÖ¡Õ°Ω–ÅÑÅâΩ’πëïêÅ…ï¡…ïÕïπ—Ö—•ŸîÅÕ±•çîÅΩ∏ÅÕ±Ω‹ÅçÂç±ïÃÅ•πÕ—ïÖêÅΩòÅ—…ÖŸï…Õ•πúÅ—°îÅô’±∞Å—Ω≠ï∏ÅµÖ¿ÅâïôΩ…îÅÕ’¡ï…Ÿ•ÕΩ»à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â1	}U9%YIM}Q%-}	UQ|ÿ––ÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â±ÖÕ—A…ïŸÂç±ï5Ãÿ–»ƒÄ¯ÄÃ¡|¿¿¡0à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âŸÖ∞Å±Öâ	’ëùï—ïëQΩ≠ïπÖ¿ÿ––ÿÄÙÅ•òÄ°±ÖÕ—A…ïŸÂç±ï5Ãÿ–»ƒÄ¯ÄÃ¡|¿¿¡0§Ä–¿Åï±ÕîÄƒ»¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†àπ—Ö≠î°±Öâ	’ëùï—ïëQΩ≠ïπÖ¿ÿ––ÿ§à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ––›}¡ΩÕ—}±ïÖ…π•πù}Öπë}ÕÖµï}µ•π—}…ï¡ïÖ—}ç°Ω≠ïÕ}Ö…ï}Ωôô}°Ω—}¡Ö—††§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩ¡ïπÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‹ËÅ$ÅÕ—Ö—’ÃΩç±ïÖπ’¿ÅµÖ•π—ïπÖπçîÅµ’Õ–Å…’∏ÅΩôòÅ—°îÅâΩ–Å±ΩΩ¿ÅÖπêÅÕ≠•¿Åç±ïÖπ’¿Åë’…•πúÅÕ±Ω‹ÅçÂç±ïÃÅÕºÅA=MQ}1I9%9}5%9Q99ÅçÖππΩ–ÅÕ—Ö±∞ÅµΩπï‰µ¡Ö—†Å—°…Ω’ù°¡’–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âA=MQ}1I9%9}5%9Q99ÅÕΩ’…çîÅô•‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âπÖµîÄÙÅpâÖ•}Õ—Ö—’Õ}µÖ•π—ïπÖπçï|ÿ–‡Âpàà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â5Ö•π—ïπÖπçï]Ω…≠ï»ÿ––‡πÕ’âµ•–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â%}MQQUM}5%9Q}Me9|ÿ––‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â%}MQQUM}19UA}M-%AA}M1=]}e1|ÿ––‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â±ÖÕ—A…ïŸÂç±ï5Ãÿ–»ƒÄÙÄÃ¡|¿¿¡0à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‡ËÅAÖ¡ï…ççΩ’π—1ïëùï»Åµ’Õ–ÅâîÅ—°îÅ¡Ö¡ï»µçÖÕ†ÅÖ’—°Ω…•—‰ÏÅçÖπΩπ•çÖ∞ÅçÖÕ†Å•ÃÅΩπ±‰ÅÑÅÕÂπçïêÅôÖçÖëîÅÕºÅ¡Ö¡ï»ÅÖôôΩ…ëÖâ•±•—‰ÅçÖππΩ–ÅÕ¡±•–µâ…Ö•∏à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âM%91ÅAAHÅA%Q0ÅUQ!=I%QdÅ	I%à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÕÂπçAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âAAI}A%Q1}UQ!=I%Qe}Me9|ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â¡Ö¡ï…}ÖççΩ’π—}±ïëùï…}ôÖçÖëï|ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†ààâÕÂπçAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ––‡†ââΩ—}±ΩΩ¡}—Ω¿à§ààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â¡Ö¡ï…}ëï±—Ö}¡…Ω©ïç—•Ωπ|ÿ–‹‘à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖâΩ–πçΩπ—Ö•πÃ†â…ï¡Ö•…ÖÕ°…Ωµ•Õ¡±ÖÂïêÿ––‡°ë•Õ¡±ÖÂïëÖÕ†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‹ËÅ…ï¡ïÖ—ïêÅAAHÅÕÖµîµµ•π–ÅÖ±•ÖÕïÃÅµ’Õ–ÅâîÅçΩÖ±ïÕçïêÅâïôΩ…îÅ·ïç’—Öâ±ï=¡ïπÖ—îΩ¡…•çîΩÕ•ÈîÅ›Ω…¨∞Å›°•±îÅ—°îÄÿÃ‹ƒÅô•πÖ±•—‰Åù’Ö…êÅ…ïµÖ•πÃÅÖÃÅÕÖôï—‰Åâï±–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†â¡Ö¡ï…MÖµï5•π—=¡ïπΩΩ±ëΩ›πUπ—•∞ÿ––‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âAAI}M5}5%9Q}=A9}=1M|ÿ––‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âAAI}M5}5%9Q}=A9}M=UI}MUAAIMM|ÿ––‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπ•πëï·=ò†âAAI}M5}5%9Q}=A9}M=UI}MUAAIMM|ÿ––‹à§ÄÅï·ïåπ•πëï·=ò†â·ïç’—Öâ±ï=¡ïπÖ—îπçÖπ=¡ïπ·ïç’—Öâ±ïAΩÕ•—•Ω∏à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅΩ¡ïπÖ—îπçΩπ—Ö•πÃ†âAAI}M5}5%9Q}1Ie}=A9|ÿÃ‹ƒà§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ––·}…ïµÖ•π•πù}ç°Ω≠ï}ÕΩ’…çï}çΩπ—…Öç—Ã†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ•……Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—Ω…ÖπΩπ•çÖ±5•……Ω»ÿ––»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ö—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAΩÕ•—•ΩπM—Ö—ï1ïëùï»ÿ–»‹π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕï1ïëùï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩAΩÕ•—•Ωπ±ΩÕï1ïëùï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Ω≠ïπ5Ö¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπ5Ö¡’—°Ω…•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï…1ïëùï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•ÈïIïÕΩ±Ÿï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω=…ëï…M•ÈïIïÕΩ±Ÿï»ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ’±–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5’±—•¡±•ï…——…•â’—•Ωπ1ïëùï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‡ËÅÕ’¡ï…Ÿ•ÕΩ»Å—•µïΩ’–Åµ’Õ–Å•ëïπ—•ô‰Åï·Öç–Å±ïÖÕîΩ—ÖÕ¨ΩçÖ±±Õ•—îΩ¡…Ωù…ïÕÃÅÖπêÅôΩ…çîµ…ï±ïÖÕîÅΩπ±‰ÅÖô—ï»ÅçÖπçï∞ÅÖç≠πΩ›±ïëùïµïπ–Å—ï±ïµï—…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âM’¡ï…Ÿ•ÕΩ…1ïÖÕî†à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â±ïÖÕï%êËÅ1Ωπúà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÕ’¡ï…Ÿ•ÕΩ…Q•µïΩ’—ï—Ö•∞ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âMUAIY%M=I}]=I-I}Q%5=UQ}QM-}AI=MM}Q=-9}e1|ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âMUAIY%M=I}91}-}=-|ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âMUAIY%M=I}91}-}5%MM%9|ÿ––‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‡ËÅQΩ≠ïπ5Ö¡’—°Ω…•—‰Åµ’Õ–ÅÕ’¡¡…ïÕÃÅçΩπç’……ïπ–Åë’¡±•çÖ—îÅQ=-9}5A}MQIPÅ›•—†Åµ•π–µ≠ïÂïêÅÖç—•ŸîÅ°Âë…Ö—•Ω∏ÅÖπêÅ)=%9}a%MQ%9ÅçΩ’π—ï…Ãà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âÖç—•Ÿï!Âë…Ö—•Ωπ	Â5•π–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âQ=-9}5A}MQIQ}U9%EUà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âQ=-9}5A})=%9}a%MQ%9à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âQ=-9}5A}=5A1Qà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âQ=-9}5A}%1à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âQ=-9}5A}IQIdà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âQ=-9}5A}Q%Y}A,à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‡ËÅçÖπΩπ•çÖ∞Å±•ôïçÂç±îÅµ’Õ–ÅâîÅ›…•——ï∏Åâ‰ÅçΩπô•…µïêÅï·ïç’—Ω»ÅïŸïπ—Ã∞ÅπΩ–ÅÈï…ºµçΩÕ–Å±ïùÖç‰Å…ïù•Õ—ï…=¡ï∏ÅΩ»Åç±ΩÕîµ±ïëùï»Å•πôï…ïπçîà∞(ÄÄÄÄÄÄÄÄÄÄÄÅÕ—Ö—îπçΩπ—Ö•πÃ†âºÅ9=PÅµ•……Ω»Å…ïù•Õ—ï…=¡ï∏†§Å•π—ºÅçÖπΩπ•çÖ∞Å°ï…îà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âµ•……Ω…	’Â•±∞à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âM10Åµ•……Ω»ÅµΩŸïêÅ—ºÅçΩπô•…µïêÅ¡Ö¡ï»Åô•±∞à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅç±ΩÕï1ïëùï»πçΩπ—Ö•πÃ†âAΩÕ•—•Ωπ±ΩÕï1ïëùï»Å•ÃÅÑÅç±ΩÕîÅµï—ÖëÖ—ÑÅ±ïëùï»ÅΩπ±‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµ•……Ω»πçΩπ—Ö•πÃ†â±ÖÕ—±ΩÕïëAΩÕ•—•Ωπ%ë	Â5•π–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµ•……Ω»πçΩπ—Ö•πÃ†âIï›Ö…ëA’…•—ÂÖ—îÿ––ƒπÖççï¡—•πÖ±•Èïë±ΩÕîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‡ËÅ¡Ö¡ï»ÅÖççΩ’π–Å±ïëùï»Åµ’Õ–Åç…ïë•–ÅçΩπô•…µïêÅÕï±±ÃΩ¡Ö…—•Ö±ÃÅÖπêÅ…ïÕΩ±Ÿï»Åµ’Õ–Å…ïÖêÅ•–ÅÖÃÅ¡Ö¡ï»ÅÖ’—°Ω…•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö¡ï…1ïëùï»πçΩπ—Ö•πÃ†âçÖπôôΩ…ë	’‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖâΩ–πçΩπ—Ö•πÃ†â…ï¡Ö•…ÖÕ°…Ωµ•Õ¡±ÖÂïêÿ––‡°ë•Õ¡±ÖÂïëÖÕ†à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πΩπMï±∞à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅX‘∏¿∏ÿÿ¿–É
ùAAI}1I}I}U9%%Q%=8ÉäPÅ…ïÕΩ±Ÿï»ÅµÖ‰(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅπΩ‹Å…ïÖêÅ—°îÅçÖπΩπ•çÖ∞ÅôÖçÖëîÄ°AÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅ›°•ç†Å•ÃÅÑÅ—°•∏ÅIµ=91dÅëï±ïùÖ—•Ω∏Å—ºÅ—°îÅÕÖµîÅ±ïëùï»(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅÖ’—°Ω…•—‰∞ÅÕºÅï•—°ï»Å…ïôï…ïπçîÅ•ÃÅÖççï¡—Öâ±î∏(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ°Õ•ÈïIïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πçÖÕ°MΩ∞à§ÅÒ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕ•ÈïIïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹πçÖÕ°MΩ∞à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÕ•ÈïIïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âAAI}9QIe}}IMIY}IQ|ÿ–‰¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‡ËÅ±ïÖ…πï»ÅôÖπΩ’–Åµ’Õ–ÅâîÅâ±Ωç≠ïêÅôΩ»Å¡Ö…—•Ö∞ÅΩ»ÅπΩ∏µIï›Ö…ëA’…•—‰µô•πÖ±•ÈïêÅM10Å…Ω›Ãà∞(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âI]I}AUI%Qe}AIQ%1}1I9%9}	1=-|ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âI]I}AUI%Qe}1I9%9}	1=-|ÿ––‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïåπçΩπ—Ö•πÃ†âIï›Ö…ëA’…•—ÂÖ—îÿ––ƒπΩ’—çΩµï=òà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ––‡ËÅµ’±—•¡±•ï»ÅÖ——…•â’—•Ω∏Åµ’Õ–Å…ï¡Ω…–ÅÕï±ïç—ïêΩÕçΩ…•πúΩÕ•È•πúÅ±ÖπïÃÅÖπêÅïµ•–Åµ•ÕÕ•πúÅÖ——…•â’—•Ω∏Å•πÕ—ïÖêÅΩòÅÕ•±ïπ–ÅMQ9IÅôÖ±±âÖç¨à∞(ÄÄÄÄÄÄÄÄÄÄÄÅµ’±–πçΩπ—Ö•πÃ†âÕï±ïç—ïë1Öπîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµ’±–πçΩπ—Ö•πÃ†âÕçΩ…•πù1Öπîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµ’±–πçΩπ—Ö•πÃ†âÕ•È•πù1Öπîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµ’±–πçΩπ—Ö•πÃ†â19}QQI%	UQ%=9}5%MM%9à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµ’±–πçΩπ—Ö•πÃ†âÖç—•Ω∏ı¡…ïÕï…Ÿï}çÖπë•ëÖ—ï}πΩ}Õ—ÖπëÖ…ë}ôÖ±±âÖç¨à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡›}±ïëùï…}•Õ}—°ï}Ωπ±Â}çÖ¡•—Ö±}Ö’—°Ω…•—Â}Öπë}…ï¡±ÖÂ}•Õ}ë•ÖùπΩÕ—•å†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡±Ö‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Iï¡±Ö‰ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±ïëùï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖ¡•—Ö∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Ö¡•—Ö±’—°Ω…•—‰ÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅ…ï¡±Ö‰Å…ïµÖ•πÃÅÑÅ¡Ö…•—‰ΩΩ…¡°Ö∏Åë•ÖùπΩÕ—•åà∞Å…ï¡±Ö‰πçΩπ—Ö•πÃ†âΩ…¡°Öπ=¡ïπΩÕ—MΩ∞à§ÄòòÅ…ï¡±Ö‰πçΩπ—Ö•πÃ†â=A9}=MQ}]%Q!=UQ}9=9%1}1=Q|ÿ–‹‘à§ÄòòÅ…ï¡±Ö‰πçΩπ—Ö•πÃ†âçΩµ¡Ö…ïQΩ1ïëùï»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅ…ï¡±Ö‰Åëï…•ŸïÃÅù…ΩÕÃÅ…ïÖ±•ÈïêÅô…Ω¥Å—Â¡ïêÅô•ï±ëÃÅ•πÕ—ïÖêÅΩòÅÕ—Ö±îÅπï–ÅÖùù…ïùÖ—îà∞Å…ï¡±Ö‰πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±…ΩÕÕIïÖ±•Èïêÿ–‡‹à§ÄòòÅ…ï¡±Ö‰πçΩπ—Ö•πÃ†âîπù…ΩÕÕA…ΩçïïëÕMΩ∞Ä¥ÅîπÖ±±ΩçÖ—ïëΩÕ—	ÖÕ•ÕMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡‹ËÅ…ï¡±Ö‰ÅçÖππΩ–Å¡ï…•Ωë•çÖ±±‰ÅΩŸï…›…•—îÅÖ’—°Ω…•—Ö—•ŸîÅ±ïëùï»à∞Å…ï¡±Ö‰πçΩπ—Ö•πÃ†â…ï¡Ö•…1ïëùï…%ô±ïÖ∏à§ÅÒÅ±ïëùï»πçΩπ—Ö•πÃ†â…ï¡±Öçï…ΩµÖπΩπ•çÖ±Iï¡±Ö‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅΩπ±‰ÅΩπîµ—•µîÅ±ïùÖç‰Åµ•ù…Ö—•Ω∏ÅµÖ‰ÅÕïïêÅµ•ÕÕ•πúÅë’…Öâ±îÅ±ïëùï»à∞Å…ï¡±Ö‰πçΩπ—Ö•πÃ†âµ•ù…Ö—ï1ïùÖçÂ1ïëùï…=πçîÿ–‡‹à§ÄòòÅ±ïëùï»πçΩπ—Ö•πÃ†â•π•—Aï…Õ•Õ—ïπ–ÿ–‡‹à§ÄòòÅ±ïëùï»πçΩπ—Ö•πÃ†â¡ï…Õ•Õ—’……ïπ–ÿ–‡‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅçÖ¡•—Ö∞ÅÕπÖ¡Õ°Ω–Å…ïÖëÃÅ±ïëùï»ÅçÖÕ†ΩΩ¡ï∏Ω…ïÖ±•ÈïêΩôïïÃÅë•…ïç—±‰Ä°¡ΩÕ–¥ÿÿ¿–ÅµÖ‰Å…ïÖêÅŸ•ÑÅAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹ÅôÖçÖëîÉäPÅÕÖµîÅÖ’—°Ω…•—‰§à∞(ÄÄÄÄÄÄÄÄÄÄÄÄ°çÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πçÖÕ°MΩ∞†§à§ÅÒÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹πçÖÕ°MΩ∞†§à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ°çÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πΩ¡ïπΩÕ—	ÖÕ•ÕMΩ∞†§à§ÅÒÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹πΩ¡ïπΩÕ—	ÖÕ•ÕMΩ∞†§à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ°çÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿π…ïÖ±•ÈïëAπ±MΩ∞†§à§ÅÒÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹π…ïÖ±•ÈïëAπ±MΩ∞†§à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ°çÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πôïïÕMΩ∞†§à§ÅÒÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹πôïïÕMΩ∞†§à§§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡‹ËÅçÖ¡•—Ö∞ÅÕπÖ¡Õ°Ω–ÅçÖππΩ–Å¡…ïôï»Å…ï¡±Ö‰Å—Ω—Ö±Ãà∞ÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Iï¡±Ö‰ÿ–ÿ–π±ÖÕ—MπÖ¡Õ°Ω–†§à§ÅÒÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†â…ï¡±Ö‰¸πΩ¡ïπΩÕ—	ÖÕ•ÕMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅ›Ö±±ï–ÅÕ’…ôÖçïÃÅÖ…îÅÕÂπç°…Ωπ•ÈïêÅô…Ω¥Å±ïëùï»ÅÖ’—°Ω…•—‰à∞ÅâΩ–πçΩπ—Ö•πÃ†âÕÂπçAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ––‡à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âAÖ¡ï…]Ö±±ï—M—Ω…îπ¡ï…Õ•Õ–°Ö¡¡±•çÖ—•ΩπΩπ—ï·–∞Å±ïëùï…ÖÕ†§à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‹’}©Ω’…πÖ±}•Õ}¡…Ω©ïç—•Ωπ}Öπë}ô•πÖ±•Èïë}Öç≠}•Õ}—…’—°ô’∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å©Ω’…πÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩXÕ)Ω’…πÖ±IïçΩ…ëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ°Ö¡ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω…Ω›—°±•ùπïëIï›Ö…ëM°Ö¡ï»ÿ–Ã‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω•πÖ±•Èïë	’ÕΩπÕ’µï…	…•ëùîÿ–ÿ‘π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‘ËÅ©Ω’…πÖ∞Åç±ΩÕîÅµ’Õ–Å…ï≈’•…îÅï·¡±•ç•–ÅçÖπΩπ•çÖ∞Åô•πÖ±•—‰ÅôΩ»Å±ïÖ…πï»Åµ’—Ö—•Ω∏à∞Å©Ω’…πÖ∞πçΩπ—Ö•πÃ†â•ÕÖπΩπ•çÖ±•πÖ±•ÈïêËÅ	ΩΩ±ïÖ∏ÄÙÅôÖ±Õîà§ÄòòÅ©Ω’…πÖ∞πçΩπ—Ö•πÃ†â•òÄ°•ÕÖπΩπ•çÖ±•πÖ±•Èïê§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹‘ËÅ…ï›Ö…êÅÕ°Ö¡ï»Åµ’Õ–ÅπΩ–Åµ’—Ö—îÅ±ΩÕÃÅÕ—…ïÖ¨Åë•…ïç—±‰à∞ÅÕ°Ö¡ï»πçΩπ—Ö•πÃ†â1ΩÕ•πùM—…ïÖ≠Iïô±ï‡ÿ–Ã‰πΩπQ…Öëï±ΩÕïê°…ïÖ±•ÈïëMΩ±ï±—Ñà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡‘ËÅô•πÖ±•ÈïêÅçΩπÕ’µï…ÃÅµ’Õ–ÅπΩ–Å…ï—Ö•∏Å’π›•…ïêΩ¡ÖÕÕ•ŸîÅ,Åâ…Öπç°ïÃà∞Åâ…•ëùîπçΩπ—Ö•πÃ†â%91%i}=9MU5I}U9]%I|à§ÅÒÅâ…•ëùîπçΩπ—Ö•πÃ†â¡…•ŸÖ—îÅô’∏Å’π›•…ïê†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‘ËÅëÖÕ°âΩÖ…êÅ,Å…ï≈’•…ïÃÅÑÅ…ïÖ∞ÅçÖπΩπ•çÖ∞Å¡…Ω©ïç—•Ω∏Å›…•—îà∞Åâ…•ëùîπçΩπ—Ö•πÃ†âÖÕ°âΩÖ…ëÖ—ÖA…ΩŸ•ëï»πΩπÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïêÿ–‡‘à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‹’}ç±Ö•µ}ô•…Õ—}ç±ΩÕï}Öπë}çΩπô•…µïë}ô•±±}Ö’—°Ω…•—‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕï±Ö•¥ÄÙÅâ…•ëùîπ•πëï·=ò†âQï…µ•πÖ±Mï±±%ëïµ¡Ω—ïπç‰ÿ–ÿ–πâïù•πQï…µ•πÖ∞à§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕï5•……Ω»ÄÙÅâ…•ëùîπ•πëï·=ò†â·ïç’—Ω…ÖπΩπ•çÖ±5•……Ω»ÿ––»πµ•……Ω…Mï±∞à§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕï1ïëùï»ÄÙÅâ…•ëùîπ•πëï·=ò†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πΩπMï±∞à§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‘ËÅ—ï…µ•πÖ∞Å•ëïµ¡Ω—ïπç‰Åµ’Õ–Åç±Ö•¥ÅâïôΩ…îÅµ•……Ω»ÅÖπêÅçÖÕ†Åµ’—Ö—•Ω∏à∞Åç±ΩÕï±Ö•¥Ä¯ÙÄ¿ÄòòÅç±ΩÕï±Ö•¥ÄÅç±ΩÕï5•……Ω»ÄòòÅç±ΩÕï±Ö•¥ÄÅç±ΩÕï1ïëùï»§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•±±5Ö…≠ï»ÄÙÅï·ïåπ•πëï·=ò†âX‘∏¿∏ÿ–‡‘ÉäPÅQ=5%ÅAAHÅ	UdÅ=55%Pà§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ’Âïâ•–ÄÙÅï·ïåπ•πëï·=ò†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πΩπ	’‰°Öç—’Ö±MΩ∞à§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•…Õ—	’ÂÖ—îÄÙÅï·ïåπ•πëï·=ò†âAAI}	Ue}	1=-}AIM1}M9%A|ÿÃ‹Õà§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‘ËÅ¡Ö¡ï»Å	UdÅçÖÕ†Åëïâ•–Åµ’Õ–ÅâîÅÖô—ï»Åïπ—…‰ÅùÖ—ïÃà∞Åô•±±5Ö…≠ï»Ä¯ÙÄ¿ÄòòÅâ’Âïâ•–Ä¯Åô•±±5Ö…≠ï»ÄòòÅô•…Õ—	’ÂÖ—îÄÅâ’Âïâ•–§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö…—•Ö∞ÿ‘ƒ¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…AÖ…—•Ö±=¡ï…Ö—•Ω∏ÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï—•…ïëMÂπ—°ï—•çAÖ…—•Ö±A…ïô•‡ÿ‘ƒ¿ÄÙÄâ¡Ö¡ï…}¡Ö…—•Ö±|àÄ¨ÄàêàÄ¨Äâ¡•êà(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‘ºÿ‘ƒ¿ËÅÖ’—ΩπΩµΩ’ÃÅÖπêÅµÖπ’Ö∞Å¡Ö¡ï»Å¡Ö…—•Ö±ÃÅµ’Õ–Å…Ω’—îÅ—°…Ω’ù†ÅΩπîÅç±Ö•¥µô•…Õ–ÅçÖπΩπ•çÖ∞ÅΩ¡ï…Ö—•Ω∏à∞Åï·ïåπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…AÖ…—•Ö±=¡ï…Ö—•Ω∏ÿ‘ƒ¿πçΩµµ•–à§ÄòòÅ¡Ö…—•Ö∞ÿ‘ƒ¿πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰πô•πÖ±•ÈïMï±∞à§ÄòòÄÖï·ïåπçΩπ—Ö•πÃ†â¡Ö¡ï…}µÖπ’Ö±}¡Ö…—•Ö±|à§ÄòòÄÖï·ïåπçΩπ—Ö•πÃ°…ï—•…ïëMÂπ—°ï—•çAÖ…—•Ö±A…ïô•‡ÿ‘ƒ¿§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‘ËÅç±ΩÕîÅÖ’—°Ω…•—•ïÃÅµ’Õ–Å…ï±ïÖÕîÅçÖπΩπ•çÖ∞ÅΩçç’¡Öπç‰à∞Å©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩAΩÕ•—•Ωπ±ΩÕï1ïëùï»π≠–à§π…ïÖëQï·–†§πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±5•π—=çç’¡ÖπçÂIïù•Õ—…‰ÿ–ÿ–πµÖ…≠±ΩÕïêà§ÄòòÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩAÖ¡ï…AΩÕ•—•Ωπ±ΩÕï’—°Ω…•—‰π≠–à§π…ïÖëQï·–†§πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±5•π—=çç’¡ÖπçÂIïù•Õ—…‰ÿ–ÿ–πµÖ…≠±ΩÕïêà§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‹—}ô’±±}¡Ö¡ï…}Õï±±}çΩµµ•—Õ}çÖπΩπ•çÖ±}ÖççΩ’π—•πù}âïôΩ…ï}©Ω’…πÖ±}¡…Ω©ïç—•Ω∏†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åô’±±Mï±±%ë‡ÄÙÅï·ïåπ•πëï·=ò†âô’∏Å¡Ö¡ï…Mï±∞†à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩµµ•—%ë‡ÄÙÅï·ïåπ•πëï·=ò†â9=9%1}AAI}M11}=55%Q|ÿ–‹–à∞Åô’±±Mï±±%ë‡§(ÄÄÄÄÄÄÄÅŸÖ∞Å©Ω’…πÖ±%ë‡ÄÙÅï·ïåπ•πëï·=ò†â…ïçΩ…ëQ…Öëî°—Õ1ïÖ…π•πùMπÖ¿∞Å—…ÖëïMπÖ¿§à∞Åô’±±Mï±±%ë‡§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹–ËÅô’±∞ÅAAHÅM10Åµ’Õ–Å…Ω’—îÅ—°…Ω’ù†ÅÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîπô•πÖ±•ÈïMï±∞ÅâïôΩ…îÅ©Ω’…πÖ∞Ω±ïÖ…π•πúÅ¡…Ω©ïç—•Ω∏à∞Åô’±±Mï±±%ë‡Ä¯ÙÄ¿ÄòòÅçΩµµ•—%ë‡Ä¯Åô’±±Mï±±%ë‡ÄòòÅ©Ω’…πÖ±%ë‡Ä¯ÅçΩµµ•—%ë‡§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹–ËÅÖççΩ’π—•πúÅ…ï©ïç—•Ω∏Åµ’Õ–ÅπΩ–Å›…•—îÅÑÅÕ’ççïÕÕô’∞ÅM10Å©Ω’…πÖ∞Å…Ω‹à∞Åï·ïåπçΩπ—Ö•πÃ†âAAI}=U9Q%9}5UQQ%=9}I)Qà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÖç—•Ω∏ıπΩ}Õ’ççïÕÕô’±}Õï±±}©Ω’…πÖ∞à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â…ï—’…∏ÅMï±±IïÕ’±–π%1}Q0à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹–ËÅçÖπΩπ•çÖ∞Å—ï…µ•πÖ∞Åâ…•ëùîÅΩ›πÃÅ—°îÅô’±∞Å¡Ö¡ï»ÅÕï±∞ÅµΩπï‰ΩïŸïπ–ÅôÖπΩ’–à∞Åï·ïåπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰πô•πÖ±•ÈïMï±∞à§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πΩπMï±∞à§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†âçΩπΩµ•çŸïπ—Mç°ïµÑÿ–ÿ–π…ïçΩ…ëMï±∞à§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π¡’â±•Õ†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹–ËÅô’±∞Å¡Ö¡ï»ÅÕï±∞Åµ’Õ–ÅπΩ–Åë•…ïç—±‰ÅçÖ±∞Åµ•……Ω…Mï±∞≠AÖ¡ï…ççΩ’π—1ïëùï»ÅÖÃÅÑÅë•Ÿï…ùïπ–Åô’±∞µç±ΩÕîÅ¡Ö—†à∞Åï·ïåπÕ’âÕ—…•πú°ô’±±Mï±±%ë‡§πçΩπ—Ö•πÃ†ààâ·ïç’—Ω…ÖπΩπ•çÖ±5•……Ω»ÿ––»πµ•……Ω…Mï±∞†(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµ•π–ÄÙÅ—…Öëï%êπµ•π–ààà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‹Ÿ}Ö’ë•—}…ï¡Ö•…Õ}ôïï}…ï¡±ÖÂ}Ωçç’¡ÖπçÂ}Öπë}—ï…µ•πÖ±}µ’‡†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅïçΩ∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩçΩπΩµ•çŸïπ—Mç°ïµÑÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡±Ö‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Iï¡±Ö‰ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩçåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±5•π—=çç’¡ÖπçÂIïù•Õ—…‰ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ›ïï¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩΩ…çïë±ΩÕïM±Ω—M›ïï¡ï»ÿ–ÿ‡π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±ΩÕÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1ΩÕ•πùAÖ——ï…π5ïµΩ…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ’ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±•πÖ±•ÈïëQ…Öëï	’Ãÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…•ç†ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹ÿËÅïπ—…‰ÅôïîÅµ’Õ–ÅâîÅ—Â¡ïêÅÕï¡Ö…Ö—ï±‰Åô…Ω¥Å¡…•πç•¡Ö∞ÅΩ¡ï∏ÅçΩÕ–à∞ÅïçΩ∏πçΩπ—Ö•πÃ†âïπ—…ÂïïÕMΩ∞à§ÄòòÅ…ï¡±Ö‰πçΩπ—Ö•πÃ†âΩ¡ïπΩÕ–Ä¨ÙÅîπï·ïç’—ïëΩÕ—MΩ∞à§ÄòòÅ…ï¡±Ö‰πçΩπ—Ö•πÃ†âôïïÃÄ¨ÙÅîπïπ—…ÂïïÕMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹ÿËÅçÖç°ïêÅ…ï¡±Ö‰Åµ’Õ–Åï·¡•…îÅΩ∏ÅïŸïπ–Åµ’—Ö—•Ω∏à∞ÅïçΩ∏πçΩπ—Ö•πÃ†âïŸïπ—Yï…Õ•Ω∏π•πç…ïµïπ—πëï–†§à§ÄòòÅ…ï¡±Ö‰πçΩπ—Ö•πÃ†â•–πïŸïπ—Yï…Õ•Ω∏ÄÙÙÅ—…‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹ÿËÅôΩ…çïêÅç±ΩÕîÅÕ›ïï¿Åµ’Õ–Å•—ï…Ö—îÅÖπêÅ…ï±ïÖÕîÅ…ïÖ∞ÅΩçç’¡Öπç‰Å…Ω›Ãà∞ÅΩçåπçΩπ—Ö•πÃ†âô’∏ÅÕπÖ¡Õ°Ω—π—…•ïÃ†§à§ÄòòÅÕ›ïï¿πçΩπ—Ö•πÃ†â=I}1=M}M1=Q}I1M}	e}M]A|ÿ–‹ÿà§ÄòòÅÕ›ïï¿πçΩπ—Ö•πÃ†âµÖ…≠±ΩÕïê°ïπ—…‰πµΩëî∞Åïπ—…‰πµ•π–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹ÿËÅ±ΩÕ•πúÅ¡Ö——ï…∏ÅÖçç’µ’±Ö—Ω»Åµ’Õ–ÅâîÄÿ–µâ•–ÅÖπêÅ±•ŸîÅëïç•Õ•ΩπÃÅµΩëîµ±ΩçÖ∞à∞Å±ΩÕÃπçΩπ—Ö•πÃ†â1Ωπù……Ö‰†Ã§à§ÄòòÅ±ΩÕÃπçΩπ—Ö•πÃ†â•òÄ°I’π—•µï5Ωëï’—°Ω…•—‰π•Õ1•Ÿî†§§Å±•ŸïÖç°îÅï±ÕîÅçÖç°îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹ÿËÅô•πÖ±•ÈïêÅµ’‡Åµ’Õ–ÅçÖ……‰Å¡ΩÕ•—•Ω∏ÅµΩëîÅ¡…ΩΩòÅÖπêÅ…ï©ïç–ÅπΩ∏µ—ï…µ•πÖ∞ÅïŸïπ—Ãà∞Åâ’ÃπçΩπ—Ö•πÃ†âŸÖ∞Å¡…ΩΩôM—Ö—îËÅM—…•πúà§ÄòòÅâ’ÃπçΩπ—Ö•πÃ†àÖïπÿπ—ï…µ•πÖ∞à§ÄòòÅ…•ç†πçΩπ—Ö•πÃ†âΩπîÅ—ï…µ•πÖ∞Å•ëïπ—•—‰ÅÖç…ΩÕÃÅ—°îÅ…•ç†Äÿ–‘¿ÅïŸïπ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹ÿËÅ¡Ö…—•Ö∞Å¡Ö¡ï»ÅÕï±±ÃÅµ’Õ–ÅπΩ–Å¡’â±•Õ†Åô•πÖ±•ÈïêÅâ’ÃÅïŸïπ—Ãà∞Å©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§πçΩπ—Ö•πÃ†â—…Öëï%êÄÙÅ•ë-ï‰àÄ¨Äà∞à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‹›}±•Ÿï}—Ω≠ïπ}µïµΩ…Â}…ï≈’•…ïÕ}ïŸïπ—}±ΩçÖ±}¡…ΩΩô}Öπë}¡ï…Õ•Õ—Õ}Õ¡±•–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅµïµΩ…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπ]•π5ïµΩ…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åïë‘ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩÿÃΩÕçΩ…•πúΩë’çÖ—•ΩπM’â1ÖÂï…$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…Õ•Õ—ïπçîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1ïÖ…π•πùAï…Õ•Õ—ïπçîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‹ËÅ¡ΩÕ•—•ŸîÅ±•ŸîÅ—Ω≠ï∏ÅÖ’—°Ω…•—‰Åµ’Õ–ÅâîÅ•ÕΩ±Ö—ïêà∞ÅµïµΩ…‰πçΩπ—Ö•πÃ†â±•Ÿï]•ππ•πùQΩ≠ïπÃà§ÄòòÅµïµΩ…‰πçΩπ—Ö•πÃ†â±•ŸïQΩ≠ïπM—Ö—Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‹ËÅ±•ŸîÅµ’—Ö—•Ω∏Åµ’Õ–Å…ï≈’•…îÅµΩëîÅ¡±’ÃÅçΩπô•…µïêΩçÖπΩπ•çÖ∞Å¡…ΩΩòà∞ÅµïµΩ…‰πçΩπ—Ö•πÃ†âŸÖ∞ÅŸï…•ô•ïë1•ŸîÄÙÅµΩëîπï≈’Ö±Ãà§ÄòòÅµïµΩ…‰πçΩπ—Ö•πÃ†â¡…ΩΩôM—Ö—îπçΩπ—Ö•πÃ†àÄ¨ÄâpààÄ¨ÄâçΩπô•…µïêàÄ¨Äâpàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‹ËÅ±•ŸîµΩπ±‰ÅÕ—Ö—îÅµ’Õ–ÅÕÖŸîÅÖπêÅ±ΩÖêÅÕÂµµï—…•çÖ±±‰à∞ÅµïµΩ…‰πçΩπ—Ö•πÃ†â-e}1%Y}]%99IM|ÿ–‹‹à§ÄòòÅµïµΩ…‰πçΩπ—Ö•πÃ†â-e}1%Y}Q=-9}MQQM|ÿ–‹‹à§ÄòòÅµïµΩ…‰πçΩπ—Ö•πÃ†â±•Ÿï]•ππï…ÕM—»à§ÄòòÅµïµΩ…‰πçΩπ—Ö•πÃ†â±•ŸïQΩ≠ïπM—Ö—ÕM—»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‹ËÅïë’çÖ—•Ω∏ÅïŸïπ–Åµ’Õ–ÅçÖ……‰Åï·ïç’—•Ω∏µ±ΩçÖ∞ÅµΩëîΩ¡…ΩΩòà∞Åïë‘πçΩπ—Ö•πÃ†âŸÖ∞Åï·ïç’—•Ωπ5ΩëîËÅM—…•πúà§ÄòòÅïë‘πçΩπ—Ö•πÃ†âŸÖ∞Å¡…ΩΩôM—Ö—îËÅM—…•πúà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â¡…ΩΩôM—Ö—îÄÙÄàÄ¨ÄâpààÄ¨ÄâçÖπΩπ•çÖ±}ô•πÖ±•ÈïêàÄ¨Äâpàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‹ËÅQΩ≠ïπ]•π5ïµΩ…‰Åô±’Õ†Åµ’Õ–Å…’∏Å’πëï»ÅΩôòµµÖ•∏Å1ïÖ…π•πùAï…Õ•Õ—ïπçîà∞Å¡ï…Õ•Õ—ïπçîπçΩπ—Ö•πÃ†âQΩ≠ïπ]•π5ïµΩ…‰πÕÖŸî†§à§ÄòòÅ¡ï…Õ•Õ—ïπçîπçΩπ—Ö•πÃ†âÕÖŸï±±	±Ωç≠•πù%π—ï…πÖ∞à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‹·}ôëù}¡…ΩŸ•ëï…}çÖ±±Õ}Ö…ï}âÖç≠ù…Ω’πë}çÖç°ïë}Ωπ±‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅôëúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ•πÖ±ïç•Õ•ΩπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖç°îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÕÂπçïµ•π•9Ö……Ö—•ŸïÖç°îÿ–‹‡π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹‡ËÅÅµ’Õ–ÅπΩ–ÅÕÂπç°…ΩπΩ’Õ±‰ÅçÖ±∞Åïµ•π§Å≈’•ç¨ÅÕçÖ¥à∞ÅôëúπçΩπ—Ö•πÃ†âïµ•π•Ω¡•±Ω–π≈’•ç≠MçÖµ°ïç¨à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹‡ËÅÅµ’Õ–ÅπΩ–ÅÕÂπç°…ΩπΩ’Õ±‰ÅçÖ±∞Åïµ•π§ÅπÖ……Ö—•Ÿîà∞ÅôëúπçΩπ—Ö•πÃ†âïµ•π•Ω¡•±Ω–πÖπÖ±ÂÈï9Ö……Ö—•Ÿîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹‡ËÅÅµ’Õ–ÅπΩ–ÅÕÂπç°…ΩπΩ’Õ±‰ÅçÖ±∞ÅUπ•ô•ïë9Ö……Ö—•Ÿï$à∞ÅôëúπçΩπ—Ö•πÃ†âUπ•ô•ïë9Ö……Ö—•Ÿï$πÖπÖ±ÂÈîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‡ËÅÅçÖç°îÅµ•ÕÃÅµ’Õ–ÅÕç°ïë’±îÅâÖç≠ù…Ω’πêÅ%<ÅÖπêÅÕ—Ö‰Åπï’—…Ö∞à∞ÅôëúπçΩπ—Ö•πÃ†âÕÂπçïµ•π•9Ö……Ö—•ŸïÖç°îÿ–‹‡πçÖç°ïë=…Iï≈’ïÕ–à§ÄòòÅôëúπçΩπ—Ö•πÃ†ââÖç≠ù…Ω’πêÅ…ïô…ïÕ†∞Åπï’—…Ö∞ÅπΩ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‡ËÅ¡…ΩŸ•ëï»ÅçÖ±±ÃÅµ’Õ–Åï·•Õ–ÅΩπ±‰Åâï°•πêÅ%<ÅçΩ…Ω’—•πîÅçÖç°îà∞ÅçÖç°îπçΩπ—Ö•πÃ†â•Õ¡Ö—ç°ï…Ãπ%<à§ÄòòÅçÖç°îπçΩπ—Ö•πÃ†âÕçΩ¡îπ±Ö’πç†à§ÄòòÅçÖç°îπçΩπ—Ö•πÃ†â•π±•ù°–πÖëêà§ÄòòÅçÖç°îπçΩπ—Ö•πÃ†â5%9%}9IIQ%Y}!}5%MM}9UQI1|ÿ–‹‡à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‹Â}ô±’•ë}Öπë}ï·•—}°Ω—}¡Ö—°Õ}πïŸï…}›Ö•—}Ωπ}¡…ΩŸ•ëï…Ã†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åô±’•êÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩÿÃΩÕçΩ…•πúΩ±’•ë1ïÖ…π•πù$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖç°îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÕÂπçïµ•π•·•—ëŸ•çïÖç°îÿ–‹‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹‰ËÅô±’•êÅÕ•È•πúÅµ’Õ–ÅπΩ–Å…’π	±Ωç≠•πúÅôΩ»ÅM=0Å¡…•çîà∞Åô±’•êπçΩπ—Ö•πÃ†â…’π	±Ωç≠•πúà§ÅÒÅô±’•êπçΩπ—Ö•πÃ†âA…•çïùù…ïùÖ—Ω»πùï—A…•çîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‰ËÅô±’•êÅÕ•È•πúÅµ’Õ–ÅçΩπÕ’µîÅâΩ’πëïêÅçÖç°ïêÅM=0Å¡…•çîà∞Åô±’•êπçΩπ—Ö•πÃ†âôô•ç•ïπçÂ1ÖÂï»πùï—Öç°ïëA…•çî†§¸πÕΩ±A…•çïUÕêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‹‰ËÅï·ïç’—Ω»Åï·•–Åëïç•Õ•Ω∏Åµ’Õ–ÅπΩ–ÅçÖ±∞Åïµ•π§Åë•…ïç—±‰à∞Åï·ïåπçΩπ—Ö•πÃ†âŸÖ∞Åùïµ•π•ëŸ•çîÄÙÅïµ•π•Ω¡•±Ω–πùï—·•—ëŸ•çîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‰ËÅï·•–ÅÖëŸ•çîÅµ’Õ–ÅâîÅâÖç≠ù…Ω’πêÅçÖç°ïêÅ›•—†Åπï’—…Ö∞Åµ•ÕÃÅ—ï±ïµï—…‰à∞Åï·ïåπçΩπ—Ö•πÃ†âÕÂπçïµ•π•·•—ëŸ•çïÖç°îÿ–‹‰πçÖç°ïë=…Iï≈’ïÕ–à§ÄòòÅçÖç°îπçΩπ—Ö•πÃ†â•Õ¡Ö—ç°ï…Ãπ%<à§ÄòòÅçÖç°îπçΩπ—Ö•πÃ†â5%9%}a%Q}!}5%MM}9UQI1|ÿ–‹‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‹‰ËÅçÖç°îÅ≠ï‰Åµ’Õ–Åâ•πêÅµ•π–Å¡±’ÃÅAπ0ÅÖπêÅ¡ïÖ¨Åâ’ç≠ï—Ãà∞ÅçÖç°îπçΩπ—Ö•πÃ†âô±ΩΩ»°¡π±Aç–ÄºÄ‘∏¿§à§ÄòòÅçÖç°îπçΩπ—Ö•πÃ†âô±ΩΩ»°¡ïÖ≠Aç–ÄºÄ‘∏¿§à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡¡}›Ö—ç°±•Õ—}çÖ¡}•Õ}Ωπï}Ö—Ωµ•ç}ÕΩ’…çï}Ö’—°Ω…•—‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ΩâÖ±Q…ÖëïIïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕï…Ÿ•çîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπô•úÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩëÖ—ÑΩ	Ω—Ωπô•úπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡¿ËÅ…ïù•Õ—…‰Åµ’Õ–Åï·¡ΩÕîÅΩπîÅçÖπΩπ•çÖ∞Ä»»¿ÅçÖ¿à∞Å…ïù•Õ—…‰πçΩπ—Ö•πÃ†âçΩπÕ–ÅŸÖ∞Å5a}]Q!1%MQ}M%iÄÙÄ»»¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡¿ËÅÖëµ•ÕÕ•Ω∏ÅÕ•Èîµç°ïç¨ÅïŸ•ç—•Ω∏Å•πÕï…—•Ω∏Åµ’Õ–ÅâîÅÕï…•Ö±•Èïêà∞Å…ïù•Õ—…‰πçΩπ—Ö•πÃ†âMÂπç°…Ωπ•ÈïêàÄ¨Äâq∏àÄ¨ÄàÄÄÄÅô’∏ÅÖëëQΩ]Ö—ç°±•Õ–à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â]Ö—ç°±•Õ—!Ö…ëÖ¡%πŸÖ…•Öπ–ÿ–‹ÃπÖÕÕï…—M•Èî°›Ö—ç°±•Õ–πÕ•Èî∞Å5a}]Q!1%MQ}M%ià§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡¿ËÅÕï±ïç—Ω»ÅÖπêÅÖ’ë•–Åµ’Õ–Å…ïÖêÅ…ïù•Õ—…‰ÅÕΩ’…çîÅôÖç—Ãà∞ÅÕï…Ÿ•çîπçΩπ—Ö•πÃ†â5a}Q%Y}]Q!1%MPÄÙÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπ±ΩâÖ±Q…ÖëïIïù•Õ—…‰π5a}]Q!1%MQ}M%ià§ÄòòÅÕï…Ÿ•çîπçΩπ—Ö•πÃ†âŸÖ∞Åç’……ïπ—]Ö—ç°±•Õ–ÄÙÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπ±ΩâÖ±Q…ÖëïIïù•Õ—…‰πÕ•Èî†§à§ÄòòÅÕï…Ÿ•çîπçΩπ—Ö•πÃ†âŸÖ∞ÅçΩπô•ù’…ïëÖ¿ÄÙÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπ±ΩâÖ±Q…ÖëïIïù•Õ—…‰π5a}]Q!1%MQ}M%ià§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡¿ËÅÖ’ë•–Åµ’Õ–ÅπΩ–Å—…ïÖ–ÅÑÅ±Öâï∞ÅçΩ’π—ï»ÅÖÃÅ¡°ÂÕ•çÖ∞ÅÕ•Èîà∞ÅÕï…Ÿ•çîπçΩπ—Ö•πÃ†â±Öâï±Ω’π—MπÖ¡Õ°Ω–†àÄ¨ÄâpààÄ¨Äâ!=Q}]Q!1%MQ}M%i}=	MIY|ÿ–‹ÃàÄ¨Äâpàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡¿ËÅ¡ï…Õ•Õ—ïêÅçΩπô•úÅµ’Õ–ÅπΩ–Å…îµï·¡ÖπêÅÖâΩŸîÅ¡°ÂÕ•çÖ∞ÅÖ’—°Ω…•—‰à∞ÅçΩπô•úπçΩπ—Ö•πÃ†âµÖ·]Ö—ç°±•Õ—M•ÈîËÅ%π–ÄÙÄ»»¿à§ÄòòÅçΩπô•úπçΩπ—Ö•πÃ†âçΩï…çï%∏†ƒ¿¿∞Ä»»¿§à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡≈}±Öπï}¡…ïÕÕ’…ï}¡•ŸΩ—Õ}—Öç—•ç}âïôΩ…ï}ÕïçΩπëÖ…Â}Õ•È•πú†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω1Öπïëµ•ÕÕ•ΩπÖ—îÿ–‹Ãπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Öç—•çÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ïÖ…π•πúΩQÖç—•çM›•—ç°ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ƒËÅ—Öç—•åÅÕ›•—ç°ï»Åµ’Õ–Åï·¡ΩÕîÅ±Öπîµ±ΩçÖ∞Å¡…ïÕÕ’…îÅ…Ω—Ö—•Ω∏à∞Å—Öç—•çÃπçΩπ—Ö•πÃ†âô’∏Å…Ω—Ö—ïΩ…1ÖπïA…ïÕÕ’…îà§ÄòòÅ—Öç—•çÃπçΩπ—Ö•πÃ†â±Öπîµ±ΩçÖ∞µ¡…ïÕÕ’…îËà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ƒËÅÖëµ•ÕÕ•Ω∏Åµ’Õ–Å…Ω—Ö—îÅ—Öç—•åÅâïôΩ…îÅÖ¡¡±Â•πúÅÕ•ÈîÅµ’±—•¡±•ï»à∞ÅùÖ—îπ•πëï·=ò†âQÖç—•çM›•—ç°ï»π…Ω—Ö—ïΩ…1ÖπïA…ïÕÕ’…îà§Å•∏ÄƒÅ’π—•∞ÅùÖ—îπ•πëï·=ò†â…ï≈’ïÕ—ïëM•ÈïMΩ∞Ä®ÅêπÕ•Èï5’±—•¡±•ï»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ƒËÅÖ¡¡…ΩŸïêÅÕ•È•πúÅµ’Õ–Å…ï—Ö•∏Åï·¡±•ç•–Åï·ïç’—Öâ±îÅô±ΩΩ»à∞ÅùÖ—îπçΩπ—Ö•πÃ†âçΩï…çï—1ïÖÕ–°µ•π·ïç’—Öâ±ïM•ÈïMΩ∞πçΩï…çï—1ïÖÕ–†¿∏¿§§à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â¡Ö¡ï…·ïç’—Öâ±ï5•π•µ’µMΩ∞ÿ‘ƒƒÄÙÅµ•πΩπô•ù’…ïëAÖ¡ï…Q…ÖëïMΩ∞†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡ƒËÅ¡Ö¡ï»Åï·ïç’—Ω»Åµ’Õ–ÅπΩ–Å—…ÖπÕ±Ö—îÅ±ïÖ…πïêÅ±ÖπîÅ¡…ïÕÕ’…îÅ•π—ºÅÈï…ºà∞Åï·ïåπçΩπ—Ö•πÃ†âAAI}	Ue}19}5%MM%=9}M-%A|ÿ–‹Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ‘‘»ËÅÕïÖ±ïêÅπΩ—•ΩπÖ∞Å•ÃÅçΩπÕ’µïêÅ›•—°Ω’–Å¡ΩÕ–µ—•ç≠ï–ÅÕ°Ö¡ï…Ãà∞Åï·ïåπçΩπ—Ö•πÃ†âAAI}M1}9=Q%=91}=9MU5|ÿ‘‘»à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÕïÖ±ïë9Ω—•ΩπÖ∞ÿ‘‘»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡ƒËÅ±ïÖ…πïêÅ—Ω·•åÅâ’ç≠ï–Å¡…ïÕÕ’…îÅµ’Õ–ÅπΩ–Å°Ö…êµŸï—ºÅ¡Ö¡ï»Ω±•ŸîÅâ’ÂÃà∞Åï·ïåπçΩπ—Ö•πÃ†âAAI}	Ue}Q=a%}	U-Q}!I}YQ=|ÿ»–‰à§ÅÒÅï·ïåπçΩπ—Ö•πÃ†âQ=a%}	U-Q}!I}YQ=|ÿ»–‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ƒËÅâΩ—†ÅµΩëïÃÅµ’Õ–Å¡•ŸΩ–Å—Öç—•åÅ•πÕ•ëîÅ—°îÅ±Öπîà∞Åï·ïåπçΩπ—Ö•πÃ†âAAI}	Ue}Q=a%}	U-Q}QQ%}A%Y=Q|ÿ–‡ƒà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â1%Y}	Ue}Q=a%}	U-Q}QQ%}A%Y=Q|ÿ–‡ƒà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡…}±ïÖ…πïë}ëπÖ}¡Ö’Õï}Öπë}¡Ö——ï…π}¡…ïÕÕ’…ï}¡•ŸΩ—}πΩ—}Ÿï—º†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôëúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ•πÖ±ïç•Õ•ΩπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡»ËÅë’¡±•çÖ—îÅ9Å±ïÖ…πï…ÃÅµ’Õ–ÅπΩ–ÅÖâΩ…–Å±•ŸîÅâ’ÂÃà∞Åï·ïåπçΩπ—Ö•πÃ†â9}YQ=}I1e}AA1%|ÿÃƒ»à§ÅÒÅï·ïåπçΩπ—Ö•πÃ†â9}AI=Y9}1=MI}YQ=|ÿ»ÿ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡»ËÅâΩ—†Å9Å¡…ïÕÕ’…îÅÕ•—ïÃÅµ’Õ–Å¡•ŸΩ–ÅÕÖµîµ±ÖπîÅ—Öç—•çÃà∞Åï·ïåπçΩπ—Ö•πÃ†â9}I1e}QQ%}A%Y=Q|ÿ–‡»à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â9}AI=Y9}1=MI}QQ%}A%Y=Q|ÿ–‡»à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÖç—•Ω∏ıçΩπ—•π’ï}ÕÖµï}±Öπîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡»ËÅ±ÖπîÅ¡Ö’ÕîÅÖπêÅ—Ω·•åÅ¡Ö——ï…∏Å±ïÖ…π•πúÅµ’Õ–ÅπΩ–Åç…ïÖ—îÅÅ°Ö…êÅâ±Ωç≠Ãà∞ÅôëúπçΩπ—Ö•πÃ†â19}UQ=}AUM}!I}	1=,à§ÅÒÅôëúπçΩπ—Ö•πÃ†âQ=a%}AQQI9}!I}	1=,à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡»ËÅ±ÖπîÅ¡Ö’ÕîÅÖπêÅ—Ω·•åÅ¡Ö——ï…πÃÅµ’Õ–Å…Ω—Ö—îÅ—Öç—•åÅâïôΩ…îÅÕïçΩπëÖ…‰ÅÕ•ÈîÅÕ°Ö¡•πúà∞ÅôëúπçΩπ—Ö•πÃ†â19}UQ=}QQ%}A%Y=Q|ÿ–‡»à§ÄòòÅôëúπçΩπ—Ö•πÃ†âQ=a%}AQQI9}QQ%}A%Y=Q|ÿ–‡»à§ÄòòÅôëúπçΩπ—Ö•πÃ†âQÖç—•çM›•—ç°ï»π…Ω—Ö—ïΩ…1ÖπïA…ïÕÕ’…îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡»ËÅ±ïÖ…πïêÅ¡…ïÕÕ’…îÅ≠ïï¡ÃÅÖ∏Åï·ïç’—Öâ±îÅô±ΩΩ»à∞ÅôëúπçΩπ—Ö•πÃ†âô•πÖ±M•ÈîÄÙÄ°ô•πÖ±M•ÈîÄ®Ä¿∏Ã‘§πçΩï…çï—1ïÖÕ–†¿∏¿ƒ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡»ËÅ¡…ΩŸ•ëï»µëïù…ÖëïêÅï·ïç’—•Ω∏ÅÕÖôï—‰Å…ïµÖ•πÃÅ•π—Öç–à∞Åï·ïåπçΩπ—Ö•πÃ†âAI=Y%I}I}	Ue}	1=-|ÿ»ÿ–à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âëï·M»ÄÄ¿∏‹¿ÄòòÅ©’¡EM»ÄÄ¿∏ÿ¿à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡Õ}±Öπï}¡Ö’Õï}çÖππΩ—}ë•ÕÖâ±ï}—…Öëï…}Ω…}¡Ω•ÕΩπ}ÕçÖππï…}•π—Ö≠î†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕÖôï—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπMÖôï—Â°ïç≠ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡ÃËÅ±ïÖ…πïêÅ¡Ö’ÕîÅµ’Õ–ÅπΩ–Åëïπ‰ÅΩ›πï»Å±ÖπïÃÅΩ»Å›°Ω±îÅ·¡…ïÕÃÅïŸÖ±’Ö—•Ω∏à∞ÅâΩ–πçΩπ—Ö•πÃ†â=]9I}19}AUM}9%|–‘‰‡à§ÅÒÅâΩ–πçΩπ—Ö•πÃ†âaAIMM}19}AUM}I1e}Q|–‘‰–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ÃËÅΩ›πï»∞ÅÕ’ççïÕÕô’∞µôïïêÅÖπêÅ·¡…ïÕÃÅ¡Ö—°ÃÅµ’Õ–Å¡•ŸΩ–ÅÕÖµîµ±ÖπîÅ—Öç—•çÃà∞ÅâΩ–πçΩπ—Ö•πÃ†â=]9I}19}QQ%}A%Y=Q|ÿ–‡Ãà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âMUMMU1}19}}QQ%}A%Y=Q|ÿ–‡Ãà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âaAIMM}19}QQ%}A%Y=Q|ÿ–‡Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡ÃËÅ±ïÖ…πïêÅ59%@Å±ÖπîÅ¡…ïÕÕ’…îÅµ’Õ–ÅπΩ–Å¡ï…Õ•Õ–ÅÕçÖππï»Å°Ö…êÅ…ï©ïç—Ãà∞ÅÕÖôï—‰πçΩπ—Ö•πÃ†â59%AU1Q}=91e}19}EUI9Q%9|–‘‰»à§ÅÒÅâΩ–πçΩπ—Ö•πÃ†â59%AU1Q}=91e}19}EUI9Q%9|–‘‰ƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ÃËÅ≈’Ö±•—‰Å¡…ΩΩòÅÕ—•±∞ÅùÖ—ïÃÅ≈’Ö±•—‰Ωâ±’ïç°•¿ÅÕ’ççïÕÕô’∞Åôïïêà∞ÅâΩ–πçΩπ—Ö•πÃ†â•òÄ°≈’Ö±•—ÂA…ΩΩô=¨ÿ¿ƒ–§à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âMUMMU1}19}}9%}EU1%Qe}AI==|ÿ–‡Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ÃËÅ·¡…ïÕÃÅÕ—•±∞Å…ï≈’•…ïÃÅçÂç±îÅΩ›πï…Õ°•¿ÅÖπêÅïπÖâ±ïêÅÖ’—°Ω…•—‰à∞ÅâΩ–πçΩπ—Ö•πÃ†âï·¡…ïÕÕ1Öπï±±Ω›ïëQ°•ÕÂç±îà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âM°•—Ω•π·¡…ïÕÃπ•ÕπÖâ±ïê†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡ÃËÅµÖπ•¡’±Ö—ïêÅÕÖôï—‰ÅΩŸï…±Ö‰Å…ïµÖ•πÃÅÑÅÕ—…ΩπúÅÕΩô–Å¡ïπÖ±—‰à∞ÅÕÖôï—‰πçΩπ—Ö•πÃ†â59%AU1Q}=91e}=YI1e|–‘‘Ãà§ÄòòÅÕÖôï—‰πçΩπ—Ö•πÃ†â¡ïπÖ±—‰Ä¨ÙÄ‘‘à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡—}±•Ÿï}±Öπï}ùΩŸï…πΩ…}çÖππΩ—}…ïÕ’……ïç—}¡ΩÕ—}ôëù}°Ö…ë}¡Ö’Õî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùΩŸï…πΩ»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•Ÿï1ÖπïΩŸï…πΩ»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡–ËÅï·ïç’—Ω»ÅÕ—•±∞ÅôïïëÃÅ±ÖπîÅïŸ•ëïπçîÅ•π—ºÅùΩŸï…πΩ»à∞Åï·ïåπçΩπ—Ö•πÃ†â1•Ÿï1ÖπïΩŸï…πΩ»π¡…ï	’Â	±ïïëï…AÖ’Õï]•—°Mï—’¡πë5•π–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ–‡–ËÅ±ïÖ…πïêÅ±ÖπîÅùΩŸï…πΩ»Åµ’Õ–ÅπΩ–ÅÖâΩ…–ÅÖô—ï»Åà∞Åï·ïåπçΩπ—Ö•πÃ†â1%Y}19}!I}AUM|ÿ»–‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡–ËÅùΩŸï…πΩ»ÅÕΩ’…çîÅµ’Õ–Å…ïµÖ•∏ÅÕΩô–ÅÖπêÅπΩ∏µë•ÕÖâ±•πúà∞ÅùΩŸï…πΩ»πçΩπ—Ö•πÃ†â1%Y}19}!I}AUM}5=Q}Q=}M=Q|ÿÃÃƒà§ÄòòÅùΩŸï…πΩ»πçΩπ—Ö•πÃ†â…ï—’…∏ÅôÖ±ÕîÅ—ºà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡–ËÅ—…’îÅ°Ö…êµ…’úÅÖëŸ•ÕΩ»ÅÖ’—°Ω…•—‰Å…ïµÖ•πÃÅ•π—Öç–à∞Åï·ïåπçΩπ—Ö•πÃ†âIU}AI%1QI}!I}%0à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â!Ö…ëI’ùA…ï•±—ï»π•±—ï…MïŸï…•—‰π!I}%0à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡’}¡Ö¡ï…}â’Â}Ö—Ωµ•ç•—Â}Öπë}Õ•πù±ï}—ï…µ•πÖ±}â’Ã†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïÕΩ±Ÿï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω=…ëï…M•ÈïIïÕΩ±Ÿï»ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕï1ïëùï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩAΩÕ•—•Ωπ±ΩÕï1ïëùï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï…	…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ’ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπÕ’µï…ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω•πÖ±•Èïë	’ÕΩπÕ’µï…	…•ëùîÿ–ÿ‘π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‘Å¡Ö¡ï»Å	UdÅµ’Õ–Å¡…ï¡Ö…îÅ±ΩçÖ±±‰ÅâïôΩ…îÅï·¡ΩÕ•πúÅQΩ≠ïπM—Ö—îà∞Åï·ïåπçΩπ—Ö•πÃ†âŸÖ∞Åô’πëïëAÖ¡ï…AΩÕ•—•Ω∏ÿ–‡‘ÄÙÅAΩÕ•—•Ω∏†à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â—Ãπ¡ΩÕ•—•Ω∏ÄÙÅô’πëïëAÖ¡ï…AΩÕ•—•Ω∏ÿ–‡‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‘ÅôÖ•±ïêÅ	UdÅµ’Õ–ÅçΩµ¡ïπÕÖ—îÅ±ïëùï»ΩçÖπΩπ•çÖ∞Ω±Ω–ΩΩçç’¡Öπç‰Ω±ïÖÕîà∞Åï·ïåπçΩπ—Ö•πÃ†â…Ω±±âÖç≠AÖ¡ï…π—…‰ÿ–‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿π…Ω±±âÖç≠	’‰à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÖâΩ…—	’‰ÿ–‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÖâΩ…—=¡ï∏ÿ–‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âµÖ…≠±ΩÕïê†àÄ¨Äâpâ¡Ö¡ï…pàà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âAAI}	Ue}	=IQ|ÿ–‡‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‘Å¡Ö¡ï»ÅÕ’ççïÕÃÅ¡…ïë•çÖ—îÅ…ï≈’•…ïÃÅô’πëïêÅçÖπΩπ•çÖ∞Å=A8ÅÖπêÅΩçç’¡Öπç‰à∞Åï·ïåπçΩπ—Ö•πÃ†â°ÖÕ’πëïë=¡ïπ1Ω–ÿ–‡‘à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±5•π—=çç’¡ÖπçÂIïù•Õ—…‰ÿ–ÿ–π•Õ=¡ï∏à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â1•ôïçÂç±îπ=A8à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‘Å°ÖÃÅΩπîÅï·ïç’—Öâ±îÅ¡Ö¡ï»Åµ•π•µ’¥ÅÖ’—°Ω…•—‰à∞Å…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†â¡Ö¡ï…·ïç’—Öâ±ï5•π•µ’µMΩ∞†§à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â’¡ëÖ—ïAÖ¡ï…·ïç’—Öâ±ï5•π•µ’µMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†àÿ–‡‘Å¡Ö¡ï»Åµ•ÕÕ•πúµÕ—Ö—îÅ¡Ö—†ÅçÖππΩ–ÅÕÂπ—°ïÕ•ÈîÅÑÅô•πÖ∞ÅçÖπë•ëÖ—îà∞ÅùÖ—îπçΩπ—Ö•πÃ†âAAI}a}=A9}Me9Q!Q%}%91}9%Qà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†àÿ–‡‘Åµï—ÖëÖ—ÑÅç±ΩÕîÅ±ïëùï»ÅçÖππΩ–Å¡’â±•Õ†Å—ï…µ•πÖ∞Å±ïÖ…π•πúÅïŸïπ—Ãà∞Åç±ΩÕï1ïëùï»πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π¡’â±•Õ†à§ÅÒÅç±ΩÕï1ïëùï»πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±•πÖ±•ÈïëQ…Öëï	’Ãÿ–ÿ–π¡’â±•Õ†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‘ÅçÖπΩπ•çÖ∞Å¡Ö¡ï»Å—ï…µ•πÖ∞Å…ïë’çï»Å¡’â±•Õ°ïÃÅ—°îÅ…•ç†Åâ’Ãà∞Å¡Ö¡ï…	…•ëùîπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π¡’â±•Õ†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‘Å…•ç†Åâ’ÃÅçïπ—…Ö±±‰ÅùÖ—ïÃÅ≈’Ö…Öπ—•πîÅÖπêÅôΩ…›Ö…ëÃÅ¡Ö…•—‰ÅΩπçîà∞Åâ’ÃπçΩπ—Ö•πÃ†â1ïÖ…π•πùE’Ö…Öπ—•πïÖ—îÿ–‹¿πÕ°Ω’±ë…Ω¡Ω…1ïÖ…π•πúà§ÄòòÅâ’ÃπçΩπ—Ö•πÃ†âïπÕ’…ïÖπΩπ•çÖ±ΩπÕ’µï…Ãÿ–‡‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‘ÅÖ±∞ÅπÖµïêÅçΩπÕ’µï…ÃÅ•πŸΩ≠îÅ…ïÖ∞ÅA%Ãà∞ÅçΩπÕ’µï…ÃπçΩπ—Ö•πÃ†â1ïÖ…πï…Iï›Ö…ë	…•ëùîÿ––¿πÖççï¡—•πÖ±•Èïêÿ–‡ÿà§ÄòòÅçΩπÕ’µï…ÃπçΩπ—Ö•πÃ†â…Ω›—°±•ùπïëIï›Ö…ëM°Ö¡ï»ÿ–Ã‰πÕ°Ö¡îà§ÄòòÅçΩπÕ’µï…ÃπçΩπ—Ö•πÃ†âQÖç—•çM›•—ç°ï»πΩπÖπΩπ•çÖ±Q…Öëï±ΩÕïêÿ–‡ÿà§ÄòòÅçΩπÕ’µï…ÃπçΩπ—Ö•πÃ†â1•Ÿï1ÖπïΩŸï…πΩ»π…ïçΩ…ë	Â¡ÖÕÕ=’—çΩµîà§ÄòòÅçΩπÕ’µï…ÃπçΩπ—Ö•πÃ†âÖ¡•—Ö±A…ïÕï…ŸÖ—•Ωπ…ïïêÿ–Ã‰π…ïçΩ…ë•πÖ±•Èïêÿ–‡ÿà§ÄòòÅçΩπÕ’µï…ÃπçΩπ—Ö•πÃ†âΩ…›Ö…ë=’—çΩµï5Ωëï∞π…ïçΩ…ë=’—çΩµîà§ÄòòÅçΩπÕ’µï…ÃπçΩπ—Ö•πÃ†âÖÕ°âΩÖ…ëÖ—ÖA…ΩŸ•ëï»πΩπÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïêÿ–‡‘à§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡Ÿ}çÖπΩπ•çÖ±}ç±ΩÕ’…ï}â…•ëùï}Öπë}±•Ÿï}ô•πÖ±•—Â}Ö…ï}—Â¡ïê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å—‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—°Ω…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩUπ•Ÿï…ÕÖ±	…•ëùïπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ï—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5Ö…≠ï—Õ1•Ÿï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩ…Â¡—ΩUπ•Ÿï…Õï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•πÖ±•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±•πÖ±•—ÂAï…Õ•Õ—ïπçîÿ–‡ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπÕ’µï…ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω•πÖ±•Èïë	’ÕΩπÕ’µï…	…•ëùîÿ–ÿ‘π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö…•—Â	’ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±•πÖ±•ÈïëQ…Öëï	’Ãÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…—•πúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIï¡Ω…—•πù!’àπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å’π•ô•ïêÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩUπ•ô•ïëAΩ±•çÂ!ïÖêπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï—ÑÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ’—ΩπΩµΩ’Õ5ï—ÖAΩ±•ç‰π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅÖ±∞Å¡Ö¡ï»ÅïçΩπΩµ•åÅµ’—Ö—•ΩπÃÅ’ÕîÅΩπîÅ—…ÖπÕÖç—•Ω∏Å…ïë’çï»à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—‡πçΩπ—Ö•πÃ†ààâô’∏ÅΩ¡ï∏†ààà§ÄòòÅ—‡πçΩπ—Ö•πÃ†ààâô’∏ÅÖëê†ààà§ÄòòÅ—‡πçΩπ—Ö•πÃ†ààâô’∏Åç±ΩÕî†ààà§ÄòòÅ—‡πçΩπ—Ö•πÃ†ààâô’∏Å…ïô’πê†ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅ¡Ö¡ï»ÅôÖçÖëîÅëïâ•–ÅçÖππΩ–Åµ•Õ±Öâï∞ÅçÖπΩπ•çÖ∞ÅµΩëîÅÖÃÅ±•Ÿîà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—‡πçΩπ—Ö•πÃ†ààâµΩëï=Ÿï……•ëîÄÙÄâ¡Ö¡ï»àààà§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†ààâµΩëï=Ÿï……•ëîËÅM—…•πú¸ÄÙÅπ’±∞ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅâ…•ëùîÅ≈’Öπ—•—•ïÃÅµ’Õ–ÅçΩµîÅô…Ω¥Å¡…ΩŸïêÅΩ’—¡’–Åëï±—ÖÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅâ…•ëùîπçΩπ—Ö•πÃ†ààâŸï…•ôÂQÖ…ùï—ï±—Ñÿ–‡ÿààà§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†ààâUMÅΩ’—¡’–Åëï±—ÑÅ’π¡…ΩŸïêÅÖô—ï»Åâ…•ëùîààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâ…•ëùîπçΩπ—Ö•πÃ†ààâIï±ïÖÕîÅÕ•ùπÖ—’…îÅï·•Õ—ÃÅâ’–ÅΩ’—¡’–Åëï±—ÑÅ•ÃÅ’π¡…ΩŸïêààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâ…•ëùîπçΩπ—Ö•πÃ†ààâ—Ö…ùï—ïç•µÖ±ÃÄÙÅô•±∞πëïç•µÖ±Ãààà§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†ààâ¡…ΩΩôM—Ö—îÄÙÅô•±∞π¡…ΩΩòààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†àÿ–‡ÿÅâ…•ëùîÅµ’Õ–ÅπΩ–ÅÕÂπ—°ïÕ•ÈîÅï·¡ïç—ïêÅUMÅΩ’—¡’–à∞Åâ…•ëùîπçΩπ—Ö•πÃ†ààâÕ•ÈïUÕêÄ®Ä≈|¿¿¡|¿¿¿Ä®Ä¿∏‰‡‘ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅ5Ö…≠ï—ÃÅΩ¡ïπÃÅÖπêÅç±ΩÕïÃÅçÖ……‰Å—Â¡ïêÅ¡…ΩΩòÅÖπêÅçÖπΩπ•çÖ∞Å≈’Öπ—•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†ààâëÖ—ÑÅç±ÖÕÃÅ5Ö…≠ï—Õ•±∞ÿ–‡ÿààà§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†ààâëÖ—ÑÅç±ÖÕÃÅ5Ö…≠ï—Õ±ΩÕîÿ–‡ÿààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†ààâçÖπΩπ•çÖ±AΩÃÿ–‡ÿπ…ïµÖ•π•πùE—ÂIÖ‹ààà§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†ààâÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π¡’â±•Õ†ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†àÿ–‡ÿÅ…ï≈’ïÕ—ïêÅ±ïŸï…ÖùîÅµ’Õ–ÅπΩ–ÅÕ•±ïπ—±‰Åëïù…ÖëîÅ—ºÅÕ¡Ω–à∞ÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†ààâëïù…Öëï±ÖÕ°QΩM¡Ω–ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅ…Â¡—ºÅUπ•Ÿï…ÕîÅ…ï≈’•…ïÃÅŸï…•ô•ïêÅâ…•ëùîÅ≈’Öπ—•—‰ÅâïôΩ…îÅ=A8à∞(ÄÄÄÄÄÄÄÄÄÄÄÅç…Â¡—ºπçΩπ—Ö•πÃ†ààââ…•ëùîπ—Ö…ùï—µΩ’π—IÖ‹ÄÙÄ¡0ààà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†ààâÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπΩ¡ïπAΩÕ•—•Ω∏ààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖç…Â¡—ºπçΩπ—Ö•πÃ†ààâ1Q}1Q}QIUMQ}M%ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅ…•ç†Åô•πÖ±•—‰Å•ÃÅë’…Öâ±îÅÖπêÅçΩπÕ’µï»Å-ÃÅÖ…îÅ¡ï…Õ•Õ—ïêà∞(ÄÄÄÄÄÄÄÄÄÄÄÅô•πÖ±•—‰πçΩπ—Ö•πÃ†ààâAI%`ÄÙÄâô•πÖ∞Ëàààà§ÄòòÅô•πÖ±•—‰πçΩπ—Ö•πÃ†ààâ-}AI%a|ÿ–‡ÿààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅô•πÖ±•—‰πçΩπ—Ö•πÃ†ààâ…ïçΩ…ëç¨ÿ–‡ÿààà§ÄòòÅô•πÖ±•—‰πçΩπ—Ö•πÃ†ààâÖç≠ïë%ëÃÿ–‡ÿààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö…•—Â	’ÃπçΩπ—Ö•πÃ†ààâ…ïëï±•Ÿï…Aïπë•πúÿ–‡ÿààà§ÄòòÅ¡Ö…•—Â	’ÃπçΩπ—Ö•πÃ†ààâ…ï≈’ïÕ—Iï—…‰ÿ–‡ÿààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅ¡Ω±•ç‰Å¡ïπë•πúÅ±Öâï±ÃÅ¡ï…Õ•Õ–ÅΩ∏ÅïŸï…‰ÅÕ—Öµ¿ÅÖπêÅÕï——±ïµïπ–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ’π•ô•ïêπçΩπ—Ö•πÃ†ààâ¡’–†â¡ïπë•πúàààà§ÄòòÅ’π•ô•ïêπçΩπ—Ö•πÃ†ààâ±ΩâÖ±MçΩ¡îπ±Ö’πç†°¡¡•Õ¡Ö—ç°ï…ÃπÕ•ëïôôïç–§ààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµï—ÑπçΩπ—Ö•πÃ†ààâ¡’–†â¡ïπë•πúàààà§ÄòòÅµï—ÑπçΩπ—Ö•πÃ†ààâ±ΩâÖ±MçΩ¡îπ±Ö’πç†°¡¡•Õ¡Ö—ç°ï…ÃπÕ•ëïôôïç–§ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡ÿÅµΩπï‰Å¡Ö—†Åë•Õ—•πù’•Õ°ïÃÅΩ¡ï∏ÅçΩÕ–ÅÖπêÅ’π…ïÖ±•ÈïêÅAπ0à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…—•πúπçΩπ—Ö•πÃ†ààâ—…’Õ—ïë1•Ÿï=¡ïπΩÕ–ààà§ÄòòÅ…ï¡Ω…—•πúπçΩπ—Ö•πÃ†ààâ’π—…’Õ—ïë1•Ÿï=¡ïπΩÕ–ààà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…—•πúπçΩπ—Ö•πÃ†ààâ—…’Õ—ïë1•ŸïUπ…ïÖ±•ÈïëAπ∞ààà§ÄòòÅ…ï¡Ω…—•πúπçΩπ—Ö•πÃ†ààâΩ¡ïπ}’π…ïÖ±•Èïë}πΩ—}›Ö±±ï—}’π—•±}Õï±±}ô•πÖ±•—‰ààà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡›}Õ•πù±ï}…Ω’—ï}¡…ï}ôëù}ëïôïπçï}Öπë}πΩ}ô’πëïë}›Ö•—}¡…Ωâî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åïπ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—Öâ±ïπ—…Â’—°Ω…•—‰ÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•µîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïù•µïï—ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅïŸïπ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩçΩπΩµ•çŸïπ—Mç°ïµÑÿ–ÿ–π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹Åïπ—…‰ÅÖ’—°Ω…•—‰Å•ÃÅ…ïçΩ…ëïêÅâïôΩ…îÅï·ïç’—Öâ±îÅ±ÖπîÅ›Ω…¨à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–π•πëï·=ò†â…ïçΩ…ëπ—…Â’—°Ω…•—‰ÿ–‡‹à§Ä¯ÙÄ¿ÄòòÅâΩ–π•πëï·=ò†â…ïçΩ…ëπ—…Â’—°Ω…•—‰ÿ–‡‹à§ÄÅâΩ–π•πëï·=ò†â•πÖ±ïç•Õ•ΩπÖ—îπïŸÖ±’Ö—î†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅπÖµïêÅÕ°ÖëΩ‹Å±ÖπïÃÅçÖππΩ–Åç…ïÖ—îÅÅ—•ç≠ï—ÃÅΩ»Åï·ïç’—Öâ±îÅΩ¡ïπÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†â•ÕM°ÖëΩ›IïÖë=π±Â1Öπîÿ–‡‹à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âXÕ}=Ià§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âMQ9Ià§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âM!8à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âM!=]}19}}MUAAIMM|ÿ–‡‹à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âa}=A9}	1=-}M!=]}19|ÿ–‡‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅΩπîÅµ•π–ΩŸï…Õ•Ω∏Å°ÖÃÅΩπîÅï·ïç’—Öâ±îÅ	UdÅç±Ö•¥à∞(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âï·ïç’—Öâ±ï	’Â±Ö•¥ÿ–‡‹π¡’—%ôâÕïπ–à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†â=9}aUQ	1}	Ue}AI}5%9Q}YIM%=8à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âa}	Ue}5%9Q}YIM%=9}UA1%Q}MUAAIMM|ÿ–‡‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†àÿ–‡‹ÅÅ…ïçΩ…ë•πúÅçÖππΩ–Å¡’â±•Õ†ÅïÖ…±‰Å—•ç≠ï—Ãà∞(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπÕ’âÕ—…•πú°ùÖ—îπ•πëï·=ò†âô’∏Å…ïçΩ…ëëúà§∞ÅùÖ—îπ•πëï·=ò†âô’∏Åç±ïÖ…·ïç’—Öâ±ï¡¡…ΩŸÖ∞à§§πçΩπ—Ö•πÃ†â¡’â±•Õ°Q•ç≠ï–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅÕ—…ïÖ¨Åëïπ•Ö∞ÅÕ’¡¡…ïÕÕïÃÅÅ—•ç≠ï—ÃÅÖπêÅô•πÖ∞ÅaÅùÖ—îà∞(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†â}MUAAIMM}9QIe}UQ!=I%Qe|ÿ–‡‹à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âa}Q}	1=-}9QIe}UQ!=I%Qe|ÿ–‡‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅëïôïπÕ•ŸîÅ]%PÅÖπêÅÈï…ºµÕ•ùπÖ∞Å¡…ΩâïÃÅ…ïµÖ•∏ÅÕ°ÖëΩ‹µΩπ±‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â9M%Y}]%Q}AI=	}MUAAIMM|ÿ–‡‹à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â9M%Y}]%Q}M!=]}=91e|ÿ–‡‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÕ•ùπÖ∞ÄÙÄàÄ¨ÄúàúÄ¨Äâ]%PàÄ¨Äúàú§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÕ°Ω’±ëQ…ÖëîÄÙÅôÖ±Õîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡ÅÕ—…ïÖ¨ÅÕ°Ö¡•πúÅ•ÃÅµΩëîµ±ÖπîÅÕçΩ¡ïêÅÖπêÅâΩ’πëïêÅÖâΩŸîÅÈï…ºà∞(ÄÄÄÄÄÄÄÄÄÄÄÅïπ—…‰πçΩπ—Ö•πÃ†âçΩ°Ω…—-ï‰°îπµΩëî∞Åîπïπ—…Â1Öπî§à§ÄòòÅïπ—…‰πçΩπ—Ö•πÃ†âÕ•Èï5’±—•¡±•ï…Ω»ÿ–‡‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅïπ—…‰πçΩπ—Ö•πÃ†âÕ—…ïÖ¨Ä¯ÙÅMQI-}!I}1%5%PÅÒÅçΩΩ±•πúÄ¥¯Ä¿∏Ã‘à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖïπ—…‰πçΩπ—Ö•πÃ†âÕ—…ïÖ¨Ä¯ÙÅMQI-}!I}1%5%PÄ¥¯Ä¿∏¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡Åù±ΩâÖ∞Å…ïù•µîÅπºÅ±Ωπùï»ÅçΩπÕ’µïÃÅÕ—…ïÖ¨ÅÕ—Ö—îÅ›°•±îÅï·ïç’—Ω…ÃÅ…ï—Ö•∏Åô•πÖ∞Å±ÖπîÅÕ•È•πúà∞(ÄÄÄÄÄÄÄÄÄÄÄÄÖ…ïù•µîπçΩπ—Ö•πÃ†âÕçΩ…ï±ΩΩ…ï±—Ñÿ–‡‹†§à§ÄòòÄÖ…ïù•µîπçΩπ—Ö•πÃ†âÕ•Èï5’±—•¡±•ï»ÿ–‡‹†§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âùÖ—ïYï…ë•ç–ÿ–‘ƒπ…ïçΩµµïπëïëM•ÈïMΩ∞à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âïπ—…Â’—°Ω…•—ÂMΩ∞ÿ–‡‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅïçΩπΩµ•åÅïŸïπ–Å…ïÖ±•ÈïêÅµÖ—ç°ïÃÅù…ΩÕÃµ±ïëùï»ÅçΩπŸïπ—•Ω∏Å›•—†ÅôïïÃÅÕï¡Ö…Ö—îà∞(ÄÄÄÄÄÄÄÄÄÄÄÅïŸïπ–πçΩπ—Ö•πÃ†âŸÖ∞Å…ïÖ±•ÈïêÄÙÅù…ΩÕÃÄ¥ÅÖ±±ΩçÖ—ïëΩÕ–à§ÄòòÅïŸïπ–πçΩπ—Ö•πÃ†âπï—A…ΩçïïëÕMΩ∞ÄÙÅπï–à§ÄòòÅïŸïπ–πçΩπ—Ö•πÃ†âï·•—ïïÕMΩ∞ÄÙÅôïïÃà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡›}âÖç≠ù…Ω’πë}…’π—•µï}•Õ}Õï…Ÿ•çï}Ω›πïë}¡…Ωù…ïÕÕ}âÖç≠ïë}Öπë}ëΩÈï}…ïçΩŸï…Öâ±î†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ¡¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩQ¡¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ö—ç°ëΩúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMï…Ÿ•çï]Ö—ç°ëΩúπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—°Ω…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω	Öç≠ù…Ω’πëQ…Öë•πù’—°Ω…•—‰ÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖπ•ôïÕ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ωπë…Ω•ë5Öπ•ôïÕ–π·µ∞à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅÕï…Ÿ•çîÅ±Ö’πç†∞Å…ïÕç’î∞ÅÕ—Ω¿ÅÖπêÅëïÕ—…Ω‰ÅΩ›∏ÅâÖç≠ù…Ω’πêÅ…’π—•µîÅÖ’—°Ω…•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπÕ—Ö…—	Ω–π±Ö’πç†ÿ–‡‹à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπ…ïÕç’ïIï±Ö’πç†ÿ–‡‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπÕ—Ω¡	Ω–∏êàÄ¨ÄâÕΩ’…çî∏ÿ–‡‹à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπΩπïÕ—…Ω‰ÿ–‡‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â…ïù•Õ—ï…I’π—•µï)Ωàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅâÖç≠ù…Ω’πêÅ°ïÖ±—†Å…ï≈’•…ïÃÅ±•ŸîÅ±ΩΩ¿∞ÅÕï…Ÿ•çîÅÖ’—°Ω…•—‰ÅÖπêÅô…ïÕ†Å¡…Ωù…ïÕÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âëÖ—ÑÅç±ÖÕÃÅ	Öç≠ù…Ω’πëI’π—•µï!ïÖ±—†ÿ–‡‹à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â±ΩΩ¡ç—•ŸîÄòòÅôΩ…ïù…Ω’πëç—•ŸîÄòòÅÖ’—°Ω…•—Âç—•Ÿîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â¡…Ωù…ïÕÕùîÄÙÄƒ»¡|¿¿¡0à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â•Õ	Öç≠ù…Ω’πëI’π—•µï!ïÖ±—°‰ÿ–‡‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹Å›Ö—ç°ëΩúÅ…Ω’—ïÃÅÕ—Ö±îÅÖç—•ŸîÅ…’π—•µîÅ—ºÅ°ïÖ…—âïÖ–Å…ïÕç’îÅ…Ö—°ï»Å—°Ö∏ÅπºµΩ¿ÅMQIPà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ›Ö—ç°ëΩúπçΩπ—Ö•πÃ†â!IQ	Q}IMUà§ÄòòÅ›Ö—ç°ëΩúπçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπ•Õ	Öç≠ù…Ω’πëI’π—•µï!ïÖ±—°‰ÿ–‡‹†§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ›Ö—ç°ëΩúπçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπQ%=9}1==A}!IQ	Pà§ÄòòÄÖ›Ö—ç°ëΩúπçΩπ—Ö•πÃ†âŸÖ∞Å•ÕI’ππ•πúÄÙÅ	Ω—Mï…Ÿ•çîπÕ—Ö—’Ãπ…’ππ•πúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹Åç—•Ÿ•—‰ÅâÖç≠ù…Ω’πêÅù’Ö…êÅ’ÕïÃÅ°ïÖ±—†Åâ’–ÅçÖππΩ–Åµ’—Ö—îÅ…’π—•µîÅÖ’—°Ω…•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅÖ¡¿πçΩπ—Ö•πÃ†â•ÕπÂç—•Ÿ•—ÂY•Õ•â±îÿ–‡‹à§ÄòòÅÖ¡¿πçΩπ—Ö•πÃ†â	Ω—Mï…Ÿ•çîπ•Õ	Öç≠ù…Ω’πëI’π—•µï!ïÖ±—°‰ÿ–‡‹†§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÖ¡¿πçΩπ—Ö•πÃ†â…ïçΩŸï…Âç—•Ω∏ÄÙÅ•òÄ°…’π—•µïç—•Ÿî§Å	Ω—Mï…Ÿ•çîπQ%=9}1==A}!IQ	Pà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖÖ¡¿πçΩπ—Ö•πÃ†â	Öç≠ù…Ω’πëQ…Öë•πù’—°Ω…•—‰ÿ–ÿ‰πÕï—I’π—•µïç—•Ÿîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅôΩ…ïù…Ω’πêÅ¡…ΩΩòÅ•ÃÅïŸïπ–µ±ΩçÖ∞∞ÅπΩ–Å°Ö…ëçΩëïêÅ—ï±ïµï—…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÕï…Ÿ•çïΩ…ïù…Ω’πëç—•Ÿîÿ–‡‹ÄÙÅ—…’îà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÕï…Ÿ•çïΩ…ïù…Ω’πëç—•Ÿîÿ–‡‹ÄÙÅôÖ±Õîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â•ÕMï…Ÿ•çïΩ…ïù…Ω’πêÄÙÅÕï…Ÿ•çïΩ…ïù…Ω’πëç—•Ÿîÿ–‡‹à§ÄòòÄÖâΩ–πçΩπ—Ö•πÃ†â•ÕMï…Ÿ•çïΩ…ïù…Ω’πêÄÙÅ—…’îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹Å°ïÖ…—âïÖ–Å…ïÖÕÕï…—ÃÅôΩ…ïù…Ω’πê∞Å›Ö≠îÅÖπêÅπï—›Ω…¨Åù’Ö…ëÃÅ•πëï¡ïπëïπ–ÅΩòÅU$à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âïπÕ’…ï±›ÖÂÕ=πI’π—•µï’Ö…ëÃÿ¿Ãƒ†àÄ¨ÄúàúÄ¨Äâ±ΩΩ¡}°ïÖ…—âïÖ—|ÿ–‡‹àÄ¨ÄúàúÄ¨Äà§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â	Öç≠ù…Ω’πëQ…Öë•πù’—°Ω…•—‰ÿ–ÿ‰πΩπMç…ïïπ=ôôQ•ç¨†§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â	Öç≠ù…Ω’πëQ…Öë•πù’—°Ω…•—‰ÿ–ÿ‰πΩπU•âÕïπ—Q•ç¨†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅÕ—Ö±îÅ°ïÖ…—âïÖ–ÅçÖππΩ–Å…ïŸ•ŸîÅÑÅçΩπô•…µïêÅµÖπ’Ö∞ÅÕ—Ω¿à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–π•πëï·=ò†â•Õ5Öπ’Ö±M—Ω¡Iï≈’ïÕ—ïê°Ö¡¡±•çÖ—•ΩπΩπ—ï·–§à∞ÅâΩ–π•πëï·=ò†âQ%=9}1==A}!IQ	PÄ¥¯à§§Å•∏(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ°âΩ–π•πëï·=ò†âQ%=9}1==A}!IQ	PÄ¥¯à§Ä¨Äƒ§Å’π—•∞ÅâΩ–π•πëï·=ò†âïπÕ’…ï±›ÖÂÕ=πI’π—•µï’Ö…ëÃÿ¿Ãƒ†àÄ¨ÄúàúÄ¨Äâ±ΩΩ¡}°ïÖ…—âïÖ—|ÿ–‡‹àÄ¨ÄúàúÄ¨Äà§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹ÅâÖ——ï…‰Å¡…Ωµ¡–Å±Ö—ç†Å…ïÕï—ÃÅΩπçîÅ¡ï»Å¡…ΩçïÕÃÅ’π—•∞Åï·ïµ¡—•Ω∏Å•ÃÅù…Öπ—ïêà∞(ÄÄÄÄÄÄÄÄÄÄÄÅÖ¡¿πçΩπ—Ö•πÃ†ââÖ——ï…Â}Ω¡—}¡…Ωµ¡—ïë}ÕïÕÕ•Ω∏à§ÄòòÅÖ¡¿πçΩπ—Ö•πÃ†â¡ï»µ¡…ΩçïÕÃÅ¡…Ωµ¡–Å±Ö—ç†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‹Åπë…Ω•êÄƒ–ÅÕ¡ïç•Ö∞µ’ÕîÅôΩ…ïù…Ω’πêÅÕï…Ÿ•çîÅëïç±Ö…ïÃÅ•—ÃÅÕ’â—Â¡îà∞(ÄÄÄÄÄÄÄÄÄÄÄÅµÖπ•ôïÕ–πçΩπ—Ö•πÃ†â=II=U9}MIY%}MA%1}UMà§ÄòòÅµÖπ•ôïÕ–πçΩπ—Ö•πÃ†âAI=AIQe}MA%1}UM}M}MU	QeAà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖπ•ôïÕ–πçΩπ—Ö•πÃ†âôΩ…ïù…Ω’πëMï…Ÿ•çïQÂ¡îÙàÄ¨ÄúàúÄ¨ÄâëÖ—ÖMÂπçÒÕ¡ïç•Ö±UÕîàÄ¨Äúàú§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–ÿ‰ÅU$ÅçÖ±±ï»Å…ï©ïç—•Ω∏Å…ïµÖ•πÃÅ°Ö…êÅÖπêÅÕï…Ÿ•çîÅÖ’—°Ω…•—‰Å…ïµÖ•πÃÅÕï¡Ö…Ö—îà∞(ÄÄÄÄÄÄÄÄÄÄÄÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†âU%}1%e1}IU9Q%5}5UQQ%=9}I)Qà§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†â’•Ö±±ï…	±Öç≠±•Õ–à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡›}±•π—}…ï±ïÖÕï}ï……Ω…Õ}Ö…ï}ç±ΩÕïë}›•—°Ω’—}ôÖ≠ï}ï·ïç’—•Ωπ}ô•πÖ±•—‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩUπ•Ÿï…ÕÖ±	…•ëùïπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ï—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5Ö…≠ï—Õ1•Ÿï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡•¡ï±•πîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§ΩA•¡ï±•πï!ïÖ±—°ç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖπ•ôïÕ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ωπë…Ω•ë5Öπ•ôïÕ–π·µ∞à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅâ…•ëùîÅï·Öç–Å…Ö‹ÅçΩπŸï…Õ•Ω∏Åµ’Õ–ÅÕ’¡¡Ω…–ÅA$Ä»ÿÅÖπêÅ…ï©ïç–ÅΩŸï…ô±Ω‹Å…Ö—°ï»Å—°Ö∏ÅôÖâ…•çÖ—•πúÅ1Ωπúπ5a}Y1UΩÈï…ºà∞(ÄÄÄÄÄÄÄÄÄÄÄÅâ…•ëùîπçΩπ—Ö•πÃ†â—Ω1Ωπù·Öç—Ωµ¡Ö–ÿ–‡‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâ…•ëùîπçΩπ—Ö•πÃ†âI]}EU9Q%Qe}=YI1=]}I)Q|ÿ–‡‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖâ…•ëùîπçΩπ—Ö•πÃ†à∏àÄ¨Äâ±ΩπùYÖ±’ï·Öç–†§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖâ…•ëùîπçΩπ—Ö•πÃ†âçÖ—ç†Ä°|ËÅQ°…Ω›Öâ±î§ÅÏÅ1Ωπúπ5a}Y1UÅÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅ5Ö…≠ï—ÃÅç±ΩÕîÅµ’Õ–Å…ï©ïç–ÅçÖπΩπ•çÖ∞Å≈’Öπ—•—•ïÃÅπΩ–Å…ï¡…ïÕïπ—Öâ±îÅâ‰Å•—ÃÅ1ΩπúÅï·ïç’—•Ω∏ÅA$à∞(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†â	•ù%π—ïùï»πŸÖ±’ï=ò°’π•—Ã§ÄÑÙÅçÖπΩπ•çÖ±IÖ‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ∞Åç±ΩÕîÅ≈’Öπ—•—‰Åï·çïïëÃÅÕ’¡¡Ω…—ïêÅï·ïç’—•Ω∏Å…Öπùîà§§(ÄÄÄÄÄÄÄÅŸÖ∞Å±Ωç≠M—Ö…–ÄÙÅï·ïç’—Ω»π•πëï·=ò†â•òÄ†ÖçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπÕï±∞πMï±±·ïç’—•Ωπ1Ωç≠Ãπ—…Âç≈’•…î°—Ãπµ•π–§§à§(ÄÄÄÄÄÄÄÅŸÖ∞Åù’Ö…ëïë1ΩúÄÙÅï·ïç’—Ω»π•πëï·=ò†â•òÄ°çΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπÕï±∞πMï±±M¡Öµ’Ö…êπÕ°Ω’±ë1Ωù	±Ωç≠ïê°—Ãπµ•π–∞Å…ïÖÕΩ∏§§ÅÏà∞Å±Ωç≠M—Ö…–§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï—’…πô—ï…1ΩúÄÙÅï·ïç’—Ω»π•πëï·=ò†â…ï—’…∏à∞Åù’Ö…ëïë1Ωú§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅâ±Ωç≠ïêµÕï±∞ÅôΩ…ïπÕ•åÅ±Ωùù•πúÅµ’Õ–ÅâîÅï·¡±•ç•—±‰ÅÕçΩ¡ïêÅÖπêÅ…ï—’…∏Å›•—°Ω’–Åï·ïç’—•πúÅÑÅÕï±∞à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ±Ωç≠M—Ö…–Ä¯ÙÄ¿ÄòòÅù’Ö…ëïë1ΩúÄ¯Å±Ωç≠M—Ö…–ÄòòÅ…ï—’…πô—ï…1ΩúÄ¯Åù’Ö…ëïë1Ωú§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅ¡•¡ï±•πîÅ—ï·–ÅΩ¡—•µ•ÈÖ—•Ω∏Å≠ïï¡ÃÅ—°îÅÕ’¡¡Ω…—ïêÅ±ïùÖç‰ÅçΩπÕ—Öπ–Å’πëï»ÅÖ∏Åï·¡±•ç•–Å±•π–ÅçΩπ—…Öç–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡•¡ï±•πîπçΩπ—Ö•πÃ†âçΩπô•ù’…ï’µ¡Qï·—1ÖÂΩ’–ÿ–‡‹à§ÄòòÅ¡•¡ï±•πîπçΩπ—Ö•πÃ†â1•πï	…ïÖ≠ï»π	I-}MQIQe}M%5A1à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âX‘∏¿∏ÿ–‡‹ËÅπë…Ω•êÄƒ–ÅÕ¡ïç•Ö∞µ’ÕîÅôΩ…ïù…Ω’πêÅÕï…Ÿ•çîÅµï—ÖëÖ—ÑÅ…ïµÖ•πÃÅëïç±Ö…ïêà∞(ÄÄÄÄÄÄÄÄÄÄÄÅµÖπ•ôïÕ–πçΩπ—Ö•πÃ†âAI=AIQe}MA%1}UM}M}MU	QeAà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡·}…’π—•µï}ç°Ω≠ï}Öπë}±Öπï}Ö’—°Ω…•—Â}Ö…ï}ÕΩ’…çï}âΩ’πëïê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åïπ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—Öâ±ïπ—…Â’—°Ω…•—‰ÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•µîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïù•µïï—ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å°ïÖ±—†ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±ïëùï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡ÅçΩÕ—±‰Å¡ΩÕ–µ±ïÖ…π•πúÅÕÖπ•—•ÈîÅÖπêÅ›Ö—ç°ëΩùÃÅÖ…îÅëïÖë±•πîµâΩ’πëïêÅÕ•πù±îµô±•ù°–Å›Ω…¨à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âçÂç±ï}ÕÖπ•—•Èï|ÿ–‡‡à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÕçÖππï…}°ïÖ±—°|ÿ–‡‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â¡…Ω©ïç—}Õπ•¡ï…}Õ›ïï¡|ÿ–‡‡à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âµÖ…≠ï—Õ}ïπù•πï}›Ö—ç°ëΩù|ÿ–‡‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â5Ö•π—ïπÖπçï]Ω…≠ï»ÿ––‡πÕ’âµ•–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡ÅÕ—…ïÖ¨ÅÖ’—°Ω…•—‰Å’ÕïÃÅïŸïπ–µ±ΩçÖ∞ÅµΩëîÅÖπêÅ±ÖπîÅÖπêÅπïŸï»Å°Ö…êµëïπ•ïÃÅÕ—…Ö—ïù‰Å°•Õ—Ω…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅïπ—…‰πçΩπ—Ö•πÃ†âçΩ°Ω…—-ï‰°îπµΩëî∞Åîπïπ—…Â1Öπî§à§ÄòòÅïπ—…‰πçΩπ—Ö•πÃ†âaUQ	1}9QIe}=!=IQ}M!A|ÿ–‡‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅïπ—…‰πçΩπ—Ö•πÃ†âYï…ë•ç–π11=\à§ÄòòÄÖïπ—…‰πçΩπ—Ö•πÃ†âïç•Õ•Ω∏°Yï…ë•ç–π9e}1=M%9}MQI,∞Ä¿∏¿à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïô±ï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω1ΩÕ•πùM—…ïÖ≠Iïô±ï‡ÿ–Ã‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…µ•–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ•πÖ±·ïç’—•ΩπAï…µ•–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡Åë’¡±•çÖ—îÅ±ΩÕ•πúµÕ—…ïÖ¨Å…ïô±ï‡Å•ÃÅçΩ°Ω…–Å—ï±ïµï—…‰ÅΩπ±‰ÅÖπêÅçÖππΩ–ÅŸï—ºÅ•πÖ±·ïç’—•ΩπAï…µ•–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïô±ï‡πçΩπ—Ö•πÃ†â1=M%9}MQI-}=!=IQ}=	MIY|ÿ–‡‡à§ÄòòÅ…ïô±ï‡πçΩπ—Ö•πÃ†â…ï—’…∏ÅôÖ±Õîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡ï…µ•–πçΩπ—Ö•πÃ†â1=M%9}MQI-}=!=IQ}9=}1=	1}YQ=|ÿ–‡‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖ¡ï…µ•–πçΩπ—Ö•πÃ†â•òÄ°çΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπ—…’—†π1ΩÕ•πùM—…ïÖ≠Iïô±ï‡ÿ–Ã‰πÕ°Ω’±ë	±Ωç≠9ï›	’ÂÃ†§§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡ÅÖùù…ïùÖ—îÅ…ïù•µîÅçÖππΩ–ÅçΩµ¡ΩÕîÅÑÅ±ÖπîÅÕ—…ïÖ¨Å•π—ºÅÕçΩ…ï±ΩΩ»Ùƒ¿¿ÅΩ»ÅÕ•ÈîÙ¿à∞(ÄÄÄÄÄÄÄÄÄÄÄÄÖ…ïù•µîπçΩπ—Ö•πÃ†âÕçΩ…ï±ΩΩ…ï±—Ñÿ–‡‹†§à§ÄòòÄÖ…ïù•µîπçΩπ—Ö•πÃ†âÕ•Èï5’±—•¡±•ï»ÿ–‡‹†§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•µîπçΩπ—Ö•πÃ†âIïù•µîπ!=@ÄÄÄÄÄÄÄÄÄ¥¯Ä¿∏Ã‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡Å…ï¡Ω…–Åç±ÖÕÕ•ô•ïÃÅ…ï¡ïÖ—ïêÅ…ïçïπ–ÅÕ—Ö±±ÃÅÖÃÅ…’π—•µîÅç°Ω≠îÅ…Ö—°ï»Å—°Ö∏Å•ÕΩ±Ö—ïêÅU$à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ°ïÖ±—†πçΩπ—Ö•πÃ†â…ïçïπ—Âç±ïQÖ•∞ÿ–‡‡à§ÄòòÅ°ïÖ±—†πçΩπ—Ö•πÃ†â…ïçïπ—MïŸï…îÿ–‡‡Ä¯ÙÄÃà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ°ïÖ±—†πçΩπ—Ö•πÃ†âIU9Q%5}!=-ËÅ…ï¡ïÖ—ïêΩïÕçÖ±Ö—•πúÅÕ—Ö±±Ãà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ°ïÖ±—†πçΩπ—Ö•πÃ†â…ïçïπ—MïŸï…îÿ–‡‡ÄÙÄƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡Å…’π—•µîÅ…ï¡Ö•»Å¡…ïÕï…ŸïÃÅ—°îÅçÖπΩπ•çÖ∞Å¡Ö¡ï»Å±ïëùï»ÅÖ’—°Ω…•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ±ïëùï»πçΩπ—Ö•πÃ†âΩâ©ïç–ÅAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿à§ÄòòÅ±ïëùï»πçΩπ—Ö•πÃ†âô’∏ÅÖÕÕï…—%πŸÖ…•Öπ–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ±ïëùï»πçΩπ—Ö•πÃ†âô’∏ÅΩ¡ïπΩÕ—	ÖÕ•ÕMΩ∞à§ÄòòÅ±ïëùï»πçΩπ—Ö•πÃ†âô’∏Å…ïÖ±•ÈïëAπ±MΩ∞à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩµï…ùïπ—’Ö…ë…Ö•±Ãπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAΩÕ•—•ΩπIïù•Õ—…ÂAÖ…•—Â’ë•–ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‡Å…ïù•Õ—…‰Å…ïµÖ•πÃÅΩπîÅÖ—Ωµ•åÅçÖπΩπ•çÖ∞Å¡…Ω©ïç—•Ω∏Å›°•±îÄÿ–‡‰ÅÖùù…ïùÖ—ïÃÅÖç—•ŸîÅïçΩπΩµ•åÅ±Ω—ÃÅâ‰Åµ•π–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â—Ωµ•çIïôï…ïπçîÒ5Ö¿ÒM—…•πú∞ÅAΩÕ•—•Ωπ%πôº¯¯à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âΩ¡ïπAΩÕ•—•ΩπÃπÕï–°…ï¡±Öçïµïπ–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âù…Ω’¡	‰ÅÏÅ•–πµ•π–ÅÙà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â±Ω—ÃπôΩ±ê°©ÖŸÑπµÖ—†π	•ù%π—ïùï»πiI<§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπΩ¡ïπAΩÕ•—•ΩπÃ†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿Å¡Ö…•—‰ÅçΩµ¡Ö…ïÃÅç’……ïπ–µµΩëîÅµ•π–µÖùù…ïùÖ—ïêÅ≈’Öπ—•—‰ÅÖπêÅâÖÕ•ÃÅ•∏Å—°îÅ…ïù•Õ—…‰Å•ëïπ—•—‰ÅëΩµÖ•∏à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡Ö…•—‰πçΩπ—Ö•πÃ†âÖç—•Ÿï5•π—A…Ω©ïç—•ΩπÃÿ–‰¿°Öç—•Ÿï5Ωëîÿ–‰¿§à§ÄòòÅ¡Ö…•—‰πçΩπ—Ö•πÃ†â…ïµÖ•π•πùE—ÂIÖ‹à§ÄòòÅ¡Ö…•—‰πçΩπ—Ö•πÃ†â…ïµÖ•π•πùΩÕ—	ÖÕ•ÕMΩ∞à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‡Â}çΩ……ïç—πïÕÕ}ç°Ω≠ï}Öπë}çÖπΩπ•çÖ±}›Ö±±ï—}çΩπ—…Öç–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ω…≠ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω5Ö•π—ïπÖπçï]Ω…≠ï»ÿ––‡π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï…ùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπ5ï…ùïE’ï’îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩ¡ïπÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖ¡•—Ö∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Ö¡•—Ö±’—°Ω…•—‰ÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπΩπ•çÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩçç’¡Öπç‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±5•π—=çç’¡ÖπçÂIïù•Õ—…‰ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡±Ö‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Iï¡±Ö‰ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅïŸïπ—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩçΩπΩµ•çŸïπ—Mç°ïµÑÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ö±±ï–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨ΩMΩ±ÖπÖ]Ö±±ï–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡Õ5Ö…≠ï—Ö—Öï—ç°ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‰ÅµÖ•π—ïπÖπçîÅÖπêÅÕçÖππï»Å…ïçΩŸï…‰Åµ’Õ–ÅâîÅ•ÕΩ±Ö—ïêÅô…Ω¥Å	=Q}1==@à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ›Ω…≠ï»πçΩπ—Ö•πÃ†âQ°…ïÖê°—ÖÕ¨∞ÅpâQµµÖ•π–¥à§ÄòòÅ›Ω…≠ï»πçΩπ—Ö•πÃ†âπï›•·ïëQ°…ïÖëAΩΩ∞à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â•πï…—}ÕçÖππï…}°Ö…ë}…ïçΩŸï…Â|ÿ–‡‰à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â5Ö•π—ïπÖπçï]Ω…≠ï»ÿ––‡πÕ’âµ•–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‰Å•π—Ö≠îÅ›Ω…¨Åµ’Õ–ÅâîÅâΩ’πëïêÅÖπêÅ…Ω—Ö—•πúÅ›•—°Ω’–ÅÕ°…•π≠•πúÅ—°îÅ…ïù•Õ—…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅµï…ùîπçΩπ—Ö•πÃ†âµÖ·MçÖ∏ËÅ%π–ÄÙÄ‘ƒ»à§ÄòòÅµï…ùîπçΩπ—Ö•πÃ†âµÖ·µ•–ËÅ%π–ÄÙÄ‰ÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â]Q!1%MQ}AI%=I%Qe}I=QQ%9}]%9=]|ÿ–‡‰à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â…ï¡ïÖ–†ƒ»‡§à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩ±ë!Ö…ëYï—ºÄÙÄâ1I9}Q=a%}19|àÄ¨Äâ!I}YQ=|ÿÃ‹‰à(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‰Å±ïÖ…πïêÅ—Ω·•ç•—‰Åµ’Õ–ÅÕΩô–µÕ°Ö¡îÅ›•—†Åô’±∞Å±ÖπîÅ—ï±ïµï—…‰ÅÖπêÅπïŸï»Å°Ö…êµŸï—ºà∞(ÄÄÄÄÄÄÄÄÄÄÄÅΩ¡ïπÖ—îπçΩπ—Ö•πÃ†â1I9}Q=a%}19}M=Q}M!A|ÿ–‡ÂÒ±ÖπîÙà§ÄòòÄÖΩ¡ïπÖ—îπçΩπ—Ö•πÃ°Ω±ë!Ö…ëYï—º§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿ÅçÖπΩπ•çÖ∞Å¡ΩÕ•—•ΩπÃÅïπôΩ…çîÅΩπîÅÖç—•ŸîÅïçΩπΩµ•åÅ¡ΩÕ•—•Ω∏Å¡ï»ÅµΩëîÅÖπêÅµ•π–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅçÖπΩπ•çÖ∞πçΩπ—Ö•πÃ†âëÖ—ÑÅç±ÖÕÃÅç—•Ÿï5•π—A…Ω©ïç—•Ω∏ÿ–‡‰à§ÄòòÅçÖπΩπ•çÖ∞πçΩπ—Ö•πÃ†âù…Ω’¡	‰ÅÏÅpààÄ¨ÄàêàÄ¨ÄâÌ•–πµΩëîπ±Ω›ï…çÖÕî†•ıàÄ¨ÄàêàÄ¨ÄâÌ•–πµ•π—ıpàÅÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçÖπΩπ•çÖ∞πçΩπ—Ö•πÃ†â9=9%1}M5}5=}5%9Q}=A9}I)Q|ÿ–‰¿à§ÄòòÅΩçç’¡Öπç‰πçΩπ—Ö•πÃ†â…ïçΩπç•±ïç—•Ÿï…ΩµÖπΩπ•çÖ∞ÿ–‡‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿Åï≈’•—‰ÅŸÖ±’ïÃÅïÖç†Å¡Ö¡ï»ÅµΩëîÅµ•π–ÅΩπçîÅÖπêÅ—°îÅAAHÅ°ï…ºÅÕ°Ω›ÃÅM Å¡±’ÃÅï≈’•—‰Å•∏ÅÖççïÕÕ•â±îÅâ…ïÖ≠ëΩ›∏Ä°X‘∏¿∏ÿÿƒÿ§à∞(ÄÄÄÄÄÄÄÄÄÄÄÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âÖç—•Ÿï5•π—A…Ω©ïç—•ΩπÃÿ–‰¿°pâ¡Ö¡ï…pà§à§ÄòòÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âµÖ…≠A…ΩŸ•ëï»°Öùù…ïùÖ—îπµ•π–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµÖ•∏πçΩπ—Ö•πÃ†â©Ω’…πÖ±MπÖ¿ÿÿƒÿ¸πçÖÕ°MΩ∞Ä¸ËÅ›Ö±±ï—MπÖ¿ÿ–‘ƒ¸πçÖÕ°MΩ∞à§ÄòòÅµÖ•∏πçΩπ—Ö•πÃ†âAAHÉ
‹ÅM à§ÄòòÄÖµÖ•∏πçΩπ—Ö•πÃ†âAÖ¡ï…]Ö±±ï—M—Ω…îπ…ïÕ—Ω…îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‰Å…ï¡±Ö‰ÅçÖ……•ïÃÅ¡…îµÖ’—°Ω…•—‰Å°•Õ—Ω…‰ÅÖπêÅôΩ±ëÃÅâΩ’πëïêµïŸïπ–ÅïŸ•ç—•Ω∏Å›•—°Ω’–Å…ï›…•—•πúÅµΩπï‰ÅΩ»Å±Ω—Ãà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡±Ö‰πçΩπ—Ö•πÃ†âïÕ—Öâ±•Õ°Iï¡±ÖÂÖ……‰ÿ–‡‰à§ÄòòÅïŸïπ—ÃπçΩπ—Ö•πÃ†âôΩ±ëŸ•ç—ïë%π—ΩIï¡±ÖÂÖ……‰ÿ–‡‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅïŸïπ—ÃπçΩπ—Ö•πÃ†âIA1e}IIe}5%IQ}I=5}1I|ÿ–‡‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‡‰Å¡…ΩŸ•ëï»Å¡Ö—°ÃÅçÖππΩ–ÅπïÕ–Å…’π	±Ωç≠•πúÅ•∏ÅAï…¡ÃÅΩ»Å›Ö±±ï–Å—…ÖπÕÖç—•Ω∏Å…Ω’—•πúà∞(ÄÄÄÄÄÄÄÄÄÄÄÄÖ›Ö±±ï–πçΩπ—Ö•πÃ†â≠Ω—±•π‡πçΩ…Ω’—•πïÃπ…’∏àÄ¨Äâ	±Ωç≠•πúà§ÄòòÄÖ¡ï…¡ÃπçΩπ—Ö•πÃ†â≠Ω—±•π‡πçΩ…Ω’—•πïÃπ…’∏àÄ¨Äâ	±Ωç≠•πúà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ›Ö±±ï–πçΩπ—Ö•πÃ†âÕ’âµ•—A…Ω—ïç—ïë9Ω]Ö•–ÿ–‡‰à§ÄòòÅ›Ö±±ï–πçΩπ—Ö•πÃ†â)%Q=}1%9|ÿ–‡‰à§ππΩ–†§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‰¡}•πŸïπ—Ω…Â}ëïë’¡}Öπë}ï·ïç’—Öâ±ï}ô±ΩΩ…}Ö…ï}çÖπΩπ•çÖ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïÕΩ±Ÿï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω=…ëï…M•ÈïIïÕΩ±Ÿï»ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩ¡ïπÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπΩπ•çÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—ï…µ•πÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±ÖàÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ÖàΩ1±µ1ÖâQ…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ±Ω–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩM±Ω—!ïÖ±—°Ö—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿Å…ïÕΩ±Ÿï»Åµ’Õ–Å¡…ïÕï…ŸîÅ—°îÅï·ïç’—Öâ±îÅô±ΩΩ»ÅΩπ±‰Å›°ï∏ÅôïîµÖ›Ö…îÅçÖ¡•—Ö∞ÅÖπêÅ±ÖπîÅçÖ¿ÅçÖ∏Åô’πêÅ•–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âÖ’—°Ω…•—ÂÖ¡1Öµ¡Ω…—ÃàÄ¨Äàÿ–‰‡à§ÄòòÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âAAI}9QIe}}IMIY}IQ|ÿ–‰¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âA%Q1}	1=]}5%9}aUQ	1|ÿ–‰¿à§ÄòòÄÖ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âµ•π•µ’µ’πëÖâ±îÿ–‰¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ƒÅ¡…ïç°ïç¨Åµ’Õ–ÅπΩ–ÅÖ’—°Ω…•ÈîÅâïôΩ…îÅçÖπΩπ•çÖ∞ÅÕ•ÈîÅ…ïÕΩ±’—•Ω∏à∞(ÄÄÄÄÄÄÄÄÄÄÄÅΩ¡ïπÖ—îπçΩπ—Ö•πÃ†âa}=A9}AI!-}M%i}A9%9|ÿ–‰ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅΩ¡ïπÖ—îπçΩπ—Ö•πÃ†âa}=A9}	1=-}M%i}9=Q}aUQ	1|ÿ–‰ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖΩ¡ïπÖ—îπçΩπ—Ö•πÃ†âa}Q%-Q}II}U9Q%1}M%i}IM=1Y|ÿ–‰¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅX‘∏¿∏ÿ–‰‹É
úƒÉäPÅ…ïÕΩ±ŸïëM•ÈïMΩ∞Å•ÃÅπΩ‹ÅÕΩ’…çïêÅô…Ω¥(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅïôôïç—•ŸïIïÕΩ±ŸïëM•Èîÿ–‰‹Ä°ôΩ±êÅΩòÅ¡…ïIïÕΩ±ŸïëM•ÈïMΩ∞ÿ–‰¿(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅÖπêÅ—°îÅMïÖ±ïë=…ëï…M•Èï’—°Ω…•—‰ÿ–‰‹ÅÕïÖ∞§∏Å•—°ï»ÅπÖµî(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅ¡…ΩŸïÃÅ—°îÅ—•ç≠ï–µ¡’â±•Õ†ÅÕ•—îÅçΩπÕ’µïÃÅ—°îÅçÖπΩπ•çÖ∞(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅ…ïÕΩ±ŸïêÅÕ•ÈîÄ°πïŸï»ÅÑÅµÖπ’ôÖç—’…ïêÄ¿∏¿ÅôÖ±±âÖç¨§∏(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄ°Ω¡ïπÖ—îπçΩπ—Ö•πÃ†â…ïÕΩ±ŸïëM•ÈîÄÙÅïôôïç—•ŸïIïÕΩ±ŸïëM•Èîÿ–‰‹πçΩï…çï—1ïÖÕ–†¿∏¿§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅΩ¡ïπÖ—îπçΩπ—Ö•πÃ†âôëù%π—ïπ–ÿ‘ƒ‰πçΩ¡‰†à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âAI}Q%-Q}M%i}IM=1UQ%=9}%1|ÿ–‰¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿Åë’¡±•çÖ—îÅ¡ΩÕ•—•Ω∏Åç…ïÖ—•Ω∏Åµ’Õ–ÅâîÅâ±Ωç≠ïêÅÖ–ÅçÖπΩπ•çÖ∞Åµ’—Ö—•Ω∏ÅÖ’—°Ω…•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅçÖπΩπ•çÖ∞πçΩπ—Ö•πÃ†â9=9%1}M5}5=}5%9Q}=A9}I)Q|ÿ–‰¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçÖπΩπ•çÖ∞πçΩπ—Ö•πÃ†â•–πµΩëîÄÙÙÅçÖπΩπ•çÖ±5Ωëîÿ–‰¿à§ÄòòÅçÖπΩπ•çÖ∞πçΩπ—Ö•πÃ†â•–πµ•π–ÄÙÙÅµ•π–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿Å°•Õ—Ω…•çÖ∞Åë’¡±•çÖ—îÅ¡Ö¡ï»Åëïâ•—ÃÅµ’Õ–Å…ïô’πêÅâÖÕ•ÃÅ›•—°Ω’–Å±ïÖ…π•πúÅçΩπ—Öµ•πÖ—•Ω∏à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—‡πçΩπ—Ö•πÃ†â…ïô’πë’¡±•çÖ—ïç—•Ÿï5•π—1Ω—Ãÿ–‰¿à§ÄòòÅ—‡πçΩπ—Ö•πÃ†âUA1%Q}M5}5%9Q}IU9|ÿ–‰¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—ï…µ•πÖ∞πçΩπ—Ö•πÃ†âÕ’¡¡…ïÕÕ1ïÖ…π•πùÖπΩ’–à§ÄòòÅ—ï…µ•πÖ∞πçΩπ—Ö•πÃ†â%9Y9Q=Ie}=IIQ%=9}1I9%9}MUAAIMM|ÿ–‰¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿Å1Å°Â¡Ω—°ïÕïÃÅµ’Õ–ÅçΩÖ±ïÕçîÅ¡ï»Åµ•π–ÅÖπêÅ…ïµÖ•∏ÅΩ’—Õ•ëîÅçÖπΩπ•çÖ∞Å©Ω’…πÖ∞Å•πŸïπ—Ω…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ±ÖàπçΩπ—Ö•πÃ†â1	}M5}5%9Q}!eA=Q!M%M}=1M|ÿ–‰¿à§ÄòòÅ±ÖàπçΩπ—Ö•πÃ†â1	}M9	=a}=A9}%M=1Q|ÿ–‰¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖ±ÖàπçΩπ—Ö•πÃ†âXÕ)Ω’…πÖ±IïçΩ…ëï»π…ïçΩ…ë=¡ï∏à§ÄòòÄÖ±ÖàπçΩπ—Ö•πÃ†âXÕ)Ω’…πÖ±IïçΩ…ëï»π…ïçΩ…ë±ΩÕîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿ÅÕ±Ω–ÅÖπêÅ…ï¡Ω…–ÅçΩ’π—ÃÅµ’Õ–Å…ïÖêÅçÖπΩπ•çÖ∞Åç’……ïπ–µµΩëîÅÖç—•ŸîÅµ•π—Ãà∞(ÄÄÄÄÄÄÄÄÄÄÄÅÕ±Ω–πçΩπ—Ö•πÃ†âÖç—•Ÿï5•π—A…Ω©ïç—•ΩπÃÿ–‰¿°pâ¡Ö¡ï…pà§à§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ∞ÅÖç—•ŸîÅµ•π—ÃÄ°ç’……ïπ–ÅµΩëî§Ëà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â1ÅÕÖπëâΩ‡Å¡…Ω©ïç—•Ω∏Ëà§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‰≈}Õ•Èï}âïôΩ…ï}Ö±±Ω›}¡…•µÖ…Â}±Öπï}ôÖπΩ’—}Öπë}ï·Öç—}Öççï¡—Öπçï}ë•ÖùπΩÕ—•çÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïÕΩ±Ÿï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω=…ëï…M•ÈïIïÕΩ±Ÿï»ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å•πŸÖ…•Öπ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω=…ëï…M•ÈïIïÕΩ±Ÿï…%πŸÖ…•Öπ–ÿ–ÿ‡π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩ¡ïπÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…µ•–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ•πÖ±·ïç’—•ΩπAï…µ•–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…ΩâÖâ•±•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1•ŸïA…ΩâÖâ•±•—Âπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖççï¡—ÖπçîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ωççï¡—Öπçï%πŸÖ…•Öπ—’ë•–ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ƒÅÕ•È•πúÅâΩ’πëÖ…‰Åµ’Õ–ÅçΩµ¡Ö…îÅ•π—ïùï»Å±Öµ¡Ω…—Ã∞Å•πç±’ë•πúÅï·Öç–Åï≈’Ö±•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âM=1}15A=IQM|ÿ–‰ƒà§ÄòòÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†â—Ω1Öµ¡Ω…—Ãÿ–‰ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†ââΩ’πëïë·ïç’—Öâ±ï1Öµ¡Ω…—Ãÿ–‰‡Ä¯ÙÅµ•π·ïç1Öµ¡Ω…—Ãÿ–‰ƒà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†â=-}5%9}AI=5=Q|ÿÿ¿¿à§ÄòòÅ•πŸÖ…•Öπ–πçΩπ—Ö•πÃ†âÖÕ†ÅÖπêÅ±ÖπîÅçÖ¿ÅÖ…îà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•ÈïA…ïç°ïç¨ÄÙÅΩ¡ïπÖ—îπ•πëï·=ò†âa}=A9}AI!-}M%i}A9%9|ÿ–‰ƒà§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ•π—±Ö•¥ÄÙÅΩ¡ïπÖ—îπ•πëï·=ò†âï·ïç’—Öâ±ï	’Â±Ö•¥ÿ–‡‹π¡’—%ôâÕïπ–à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ±±Ω›ïêÄÙÅΩ¡ïπÖ—îπ•πëï·=ò†âΩ…ïπÕ•ç1Ωùùï»π±•ôïçÂç±î†àÄ¨ÄúàúÄ¨Äâa}=A9}11=]àÄ¨Äúàú§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ƒÅ’π…ïÕΩ±ŸïêÅÕ•ÈîÅµ’Õ–Å…ï—’…∏ÅâïôΩ…îÅµ•π–Åç±Ö•¥ÅÖπêÅa}=A9}11=]à∞(ÄÄÄÄÄÄÄÄÄÄÄÅÕ•ÈïA…ïç°ïç¨Ä¯ÙÄ¿ÄòòÅµ•π—±Ö•¥Ä¯ÅÕ•ÈïA…ïç°ïç¨ÄòòÅÖ±±Ω›ïêÄ¯Åµ•π—±Ö•¥Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡ï…µ•–πçΩπ—Ö•πÃ†âÕ•Èï•πÖ±•—ÂQ•ç≠ï—A…ïÕïπ–ÿ–‰ƒà§ÄòòÅ¡ï…µ•–πçΩπ—Ö•πÃ†â¡…ïIïÕΩ±ŸïëM•ÈïMΩ∞ÿ–‰¿ÄÙÅÕ•ÈïMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘‰‰ÅÕ’¡ï…ÕïëïÃÄÿ‘ÃÃÅ…ïÕç’îÅôÖπΩ’–Å›•—†Å—…’π¨Å¡±’ÃÅΩπîÅ≈’Ö±•ô•ïêÅçÖπΩπ•çÖ∞ÅÕ¡ïç•Ö±•Õ–Å¡…•µÖ…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â·ïç’—•Ωπ’—°Ω…•—ÂAΩ±•ç‰ÿ‘ÃÃπ•ÕQ…’π≠1Öπî°∞§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†ââΩ’πëïëIïÕç’îÿÿ¿¿à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÕ¡ïç•Ö±•Õ—ŸÖ±’Ö—•Ωπ±±Ω›ïêÿÿ¿¿à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âç±Ö•µïë=›πï»ÿÿ¿¿à§ÄòòÄÖâΩ–πçΩπ—Ö•πÃ†âÕ—…ΩπùïÕ—ïÕ¨ÿ‘‰‰à§ÄòòÄÖâΩ–πçΩπ—Ö•πÃ†â19}I}=91e}9=9}AI%5Ie|ÿ–‰ƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ƒÅ—Ω·•åÅM!%Q=%8ΩQIMUIdÅÕ°Ö¡•πúÅµ’Õ–Å’ÕîÅ±ïÖ…πïêÅ±Öπîµ±ΩçÖ∞Åïπ—…‰Åô±ΩΩ…Ã∞ÅπΩ–Åù±ΩâÖ∞Å¡Ö’ÕïÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡…ΩâÖâ•±•—‰πçΩπ—Ö•πÃ†â±ïÖ…πïëπ—…Â±ΩΩ…ï±—Ñÿ–‰ƒà§ÄòòÅ¡…ΩâÖâ•±•—‰πçΩπ—Ö•πÃ†â±ÖπîÄÙÙÄàÄ¨ÄúàúÄ¨ÄâM!%Q=%8àÄ¨Äúàú§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡…ΩâÖâ•±•—‰πçΩπ—Ö•πÃ†â±ÖπîÄÙÙÄàÄ¨ÄúàúÄ¨ÄâQIMUIdàÄ¨Äúàú§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â19}1=1}1I9}1==I}I}=91e|ÿ–‰ƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ƒÅÖççï¡—ÖπçîÅôÖ•±’…ïÃÅµ’Õ–Åï·¡ΩÕîÅï·Öç–ÅôÖ•±ïêÅ•πŸÖ…•Öπ—ÃÅÖπêÅΩâÕï…ŸïêÅçΩ’π–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅÖççï¡—ÖπçîπçΩπ—Ö•πÃ†â•πŸÖ…•Öπ—ÃÙà§ÄòòÅÖççï¡—ÖπçîπçΩπ—Ö•πÃ†âï·¡ïç—ïêıÖ±±}•πŸÖ…•Öπ—Õ}¡ÖÕÃà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅÖççï¡—ÖπçîπçΩπ—Ö•πÃ†âôÖ•±ïë%πŸÖ…•Öπ—ÃÙà§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âï·ïç’—Öâ±îÅôÖ∏µΩ’–Ω•π—Ö≠îà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‰…}çÖπΩπ•çÖ±}•πŸïπ—Ω…Â}µÖ…≠Õ}—Ω≠ïπµÖ¡}¡Ö…—•Ö±}Öπë}µÖ…≠ï—çÖ¡}—…’—††§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ΩÕ•—•Ω∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±Ω–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±1Ω—E’Öπ—•—‰ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖ¡•—Ö∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Ö¡•—Ö±’—°Ω…•—‰ÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Ω≠ïπ5Ö¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπ5Ö¡’—°Ω…•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨Ωï·Õç…ïïπï…¡§π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±ÖàÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ÖàΩ1±µ1ÖâQ…Öëï»π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰»Å…ï¡±Ö‰ÅçÖ……‰Åµ’Õ–Å…ïâ’•±êÅçÖπΩπ•çÖ∞Å¡ΩÕ•—•Ω∏ÅÖπêÅô’πëïêÅ±Ω–Å¡…Ω©ïç—•ΩπÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ¡ΩÕ•—•Ω∏πçΩπ—Ö•πÃ†â9=9%1}IIe}A=M%Q%=9}IMQ=I|àÄ¨Äàÿ–‰»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡ΩÕ•—•Ω∏πçΩπ—Ö•πÃ†âA=M%Q%=9}MQQ}AI=)Q}I=5}9=9%1|àÄ¨Äàÿ–‰»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ±Ω–πçΩπ—Ö•πÃ†â9=9%1}IIe}1=Q}IMQ=I|àÄ¨Äàÿ–‰»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰»Åµ•ÕÕ•πúÅ≈’Ω—îÅµ’Õ–Å…ï—Ö•∏Å±ÖÕ–µùΩΩêÅµÖ…¨ÅΩ»ÅâÖÕ•Ã∞ÅπïŸï»ÅÈï…ºµŸÖ±’îÅΩ¡ï∏Å•πŸïπ—Ω…‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†â±ÖÕ—ΩΩë5Ö…¨àÄ¨Äàÿ–‰»à§ÄòòÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âA%Q1}MQ1}1MQ}==}5I-|àÄ¨Äàÿ–‰»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçÖ¡•—Ö∞πçΩπ—Ö•πÃ†âA%Q1}5I-}11	-}9=}9=9}A=M%Q%=9|àÄ¨Äàÿ–‰»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰»ÅQΩ≠ïπ5Ö¿Åµ’Õ–Å¡’â±•Õ†ÅÕ°Ö…ïêÅµ•π–Å…ïÕ’±–ÅÖπêÅ…ï—…‰Å¡ïπë•πúÅµÖ¡ÃÅΩ∏ÅÕ°Ω…–ÅQQ0à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±IïÕ’±—	Â5•π–àÄ¨Äàÿ–‰»à§ÄòòÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âA9%9}IMU1Q}IQIe}5M|àÄ¨Äàÿ–‰»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âQ=-9}5A}M!I}IMU1Q}!%Q|àÄ¨Äàÿ–‰»à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}	Ue}II}Q=-9}5A}IQIe|àÄ¨Äàÿ–‰»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰»Å—ï…µ•πÖ∞Å¡Ö¡ï»ÅÕï±∞Åµ’Õ–Å’ÕîÅçÖπΩπ•çÖ∞Å…ïµÖ•π•πúÅ…Ö‹Å≈—‰∞Åëïç•µÖ±ÃÅÖπêÅâÖÕ•Ãà∞(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â—ï…µ•πÖ±IïµÖ•π•πùIÖ‹àÄ¨Äàÿ–‰»à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â—ï…µ•πÖ±IïµÖ•π•πùΩÕ–àÄ¨Äàÿ–‰»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞ÅÕΩ±ëE—ÂIÖ‹ÿ–‹–ÄÙÅ—ï…µ•πÖ±IïµÖ•π•πùIÖ‹àÄ¨Äàÿ–‰»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰»Åï·Mç…ïïπï»Åµ’Õ–ÅπïŸï»ÅÖ±•ÖÃÅXÅ•π—ºÅµÖ…≠ï—Ö¿à∞(ÄÄÄÄÄÄÄÄÄÄÄÅëï‡πçΩπ—Ö•πÃ†âµÖ…≠ï—Ö¿ÄÄÄÙÅ¿πΩ¡—Ω’â±î†àÄ¨ÄúàúÄ¨ÄâµÖ…≠ï—Ö¿àÄ¨ÄúàúÄ¨Äà∞Ä¿∏¿§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖëï‡πçΩπ—Ö•πÃ†â•òÄ°•–ÄÙÙÄ¿∏¿§Å¿πΩ¡—Ω’â±îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰»ÅµÖ…≠ï–ÅçÖ¿Å…ï≈’•…ïÃÅ¡…ΩŸïπÖπçîÅâïôΩ…îÅ	1U!%@ÅΩ»Å±ïÖ…π•πúà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â°ÖÕQ…’Õ—ïë5Ö…≠ï—Ö¿àÄ¨Äàÿ–‰»à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âµçÖ¡MΩ’…çîà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}5A}U9QIUMQ}I=AA|àÄ¨Äàÿ–‰»à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âπΩ}â±’ïç°•¡}πΩ}±ïÖ…π•πúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰»Å…ï¡Ω…–Åµ’Õ–Åï·¡ΩÕîÅ¡Ö¡ï»Ω±•ŸîΩç’……ïπ–ÅçÖπΩπ•çÖ∞Å•πŸïπ—Ω…‰Å•πëï¡ïπëïπ—±‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ∞ÅAAHÅÖç—•ŸîÅµ•π—ÃËà§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ∞Å1%YÅÖç—•ŸîÅµ•π—ÃËà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ∞ÅÖç—•ŸîÅµ•π—ÃÄ°ç’……ïπ–ÅµΩëî§Ëà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰¿Å1Å°Â¡Ω—°ïÕ•ÃÅ¡ΩÕ•—•ΩπÃÅµ’Õ–ÅÕ—Ö‰ÅΩ’—Õ•ëîÅçÖπΩπ•çÖ∞ÅQ…Öëï!•Õ—Ω…ÂM—Ω…îà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ±ÖàπçΩπ—Ö•πÃ†â1	}M9	=a}=A9}%M=1Q|àÄ¨Äàÿ–‰¿à§ÄòòÅ±ÖàπçΩπ—Ö•πÃ†â1	}M9	=a}1=M}%M=1Q|àÄ¨Äàÿ–‰¿à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‰Õ}—Ω≠ïπ}•ëïπ—•—Â}•Õ}µ•π—}πïŸï…}ÕÂµâΩ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïÕΩ±Ÿï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩ…Â¡—ΩUπ•Ÿï…ÕïIΩ’—ïIïÕΩ±Ÿï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩ…Â¡—ΩUπ•Ÿï…Õï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—…Öëï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïçΩπç•±îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ö—ç°±•Õ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ]Ö—ç°±•Õ—πù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…•çîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩA…•çïùù…ïùÖ—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕçΩ…ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡ÕUπ•ô•ïëMçΩ…ï…	…•ëùîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å’§ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω…Â¡—Ω±—ç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨Ωï·Õç…ïïπï…¡§π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπë•ëÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩ…Â¡—Ω•πÖ±	’ÂÖπë•ëÖ—îπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅ…ïù•Õ—…‰Åµ’Õ–Å•πëï‡Åµ’±—•¡±îÅçÖπΩπ•çÖ∞Å%ÃÅ¡ï»Åë•Õ¡±Ö‰ÅÕÂµâΩ∞ÅÖπêÅ…ï©ïç–ÅÖµâ•ù’Ω’ÃÅï·ïç’—•Ω∏à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âÕÂµâΩ±Öπë•ëÖ—ïÃàÄ¨Äàÿ–‰Ãà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âùï—Uπ•≈’ï·ïç’—Öâ±ïQΩ≠ïπ	ÂMÂµâΩ∞àÄ¨Äàÿ–‰Ãà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âIeAQ=}a}5%9Q}5	%U=UM}I)Q|àÄ¨Äàÿ–‰Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅΩ•πïç≠ºÅ—…ïπë•πúÅÖπêÅ)’¡•—ï»Åë•ÕçΩŸï…‰Åµ’Õ–ÅπïŸï»Å©Ω•∏Åâ‰ÅÕÂµâΩ∞à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âŸÖ∞Åµ•π–ÄÄÄÄÄÙÄàÄ¨ÄúàúÄ¨ÄâçúËàÄ¨ÄúêúÄ¨ÄâÌ—Ω¨π•ëÙàÄ¨Äúàú§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â9YHÅµ•ù…Ö—îÅΩ•πïç≠ºÅëÖ—ÑÅΩπ—ºÅÑÅ)’¡•—ï»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âŸÖ∞Åçù-ï‰ÄÙÅÕÂµâΩ±%πëï·mÕÂµâΩ±tà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅëÂπÖµ•åÅ…Ω’—îÅÖπêÅï·ïç’—Ω»Åµ’Õ–ÅçÖ……‰Åï·¡±•ç•–ÅÕÂµâΩ∞Å¡±’ÃÅçÖπΩπ•çÖ∞Åµ•π–à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âÖÕÕï—MÂµâΩ∞àÄ¨Äàÿ–‰Ãà§ÄòòÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†â—Ö…ùï—5•π–àÄ¨Äàÿ–‰Ãà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âπºÅÕÂµâΩ∞ÅôÖ±±âÖç¨ÅÖ±±Ω›ïêà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â—Ö…ùï—5•π–àÄ¨Äàÿ–‰Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅ—…Öëï»ÅçÖπΩπ•çÖ∞Å≠ï‰∞Å±•ŸîÅ°ÖπëΩôòÅÖπêÅ±ïÖ…π•πúÅµ’Õ–Å’ÕîÅëÂπ5•π–ΩçÖπΩπ•çÖ∞Å•ëïπ—•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—…Öëï»πçΩπ—Ö•πÃ†âÕ•ùπÖ∞πëÂπ5•π–¸π—…•¥†§à§ÄòòÅ—…Öëï»πçΩπ—Ö•πÃ†â—Ö…ùï—5•π–ÿ–‰ÃÄÙÅÕ•ùπÖ∞πëÂπ5•π–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—…Öëï»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±ÕÕï—%êÿ–‰ÃÄÙÅ¡ΩÕ•—•Ω∏πçÖπΩπ•çÖ±ÕÕï—-ï‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—…Öëï»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±ÕÕï—%êÿ–‰ÃÄÙÅ¡ΩÃπëÂπ5•π–Ä¸ËÅ¡ΩÃπçÖπΩπ•çÖ±ÕÕï—-ï‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅ›Ö±±ï–Å…ïçΩπç•±•Ö—•Ω∏ÅÖπêÅ¡…•çîÅ°Âë…Ö—•Ω∏Åµ’Õ–ÅπïŸï»Å…ïÕΩ±ŸîÅÖ∏ÅÖ…â•—…Ö…‰Å—•ç≠ï»ÅΩ›πï»à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïçΩπç•±îπçΩπ—Ö•πÃ†â•òÄ°¿πëÂπ5•π–ÄÑÙÅπ’±∞§Å¿πëÂπ5•π–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ¡…•çîπçΩπ—Ö•πÃ†âùï—Uπ•≈’ï·ïç’—Öâ±ïQΩ≠ïπ	ÂMÂµâΩ∞ÿ–‰Ã°ÕÂµâΩ∞§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅ›Ö—ç°±•Õ–ÅÖπêÅU$Åµ’Õ–Å¡ï…Õ•Õ–Ω±ΩÖêÅç…Â¡—ºÅâ‰ÅçÖπΩπ•çÖ∞ÅÖÕÕï–Å%à∞(ÄÄÄÄÄÄÄÄÄÄÄÅ›Ö—ç°±•Õ–πçΩπ—Ö•πÃ†âŸÖ∞ÅÖÕÕï—%êËà§ÄòòÅ›Ö—ç°±•Õ–πçΩπ—Ö•πÃ†â±ïùÖç‰µÕÂµâΩ∞Ëà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ›Ö—ç°±•Õ–πçΩπ—Ö•πÃ†âùï—QΩ≠ïπ	Â5•π–°•—ï¥πÖÕÕï—%ê§à§ÄòòÅ’§πçΩπ—Ö•πÃ†â¡ΩÃπëÂπ5•π–¸π±ï–à§ÄòòÅ’§πçΩπ—Ö•πÃ†âùï—QΩ≠ïπ	Â5•π–°•–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅÕçΩ…ï»Åïπ—…‰Ωç±ΩÕîÅ≠ïÂÃÅµ’Õ–ÅÖççï¡–ÅçÖπΩπ•çÖ∞ÅÖÕÕï–Å•ëïπ—•—‰à∞(ÄÄÄÄÄÄÄÄÄÄÄÅÕçΩ…ï»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±ÕÕï—%êÿ–‰Ãà§ÄòòÅÕçΩ…ï»πçΩπ—Ö•πÃ†à¸ËÅµÖ≠ï5•π—-ï‰°ÖÕÕï—±ÖÕÃ∞ÅÕÂµâΩ∞§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅï‡ÅëÖ—ÑÅµ’Õ–Å…ï≈’•…îÅï·Öç–Å…ï≈’ïÕ—ïêÅâÖÕîÅµ•π–ÅâïôΩ…îÅçΩ¡Â•πúÅ—Ω≠ï∏ÅïçΩπΩµ•çÃà∞(ÄÄÄÄÄÄÄÄÄÄÄÅëï‡πçΩπ—Ö•πÃ†ââÖÕïëë…ïÕÃπï≈’Ö±Ã°—Ω≠ïπëë…ïÕÃ∞Å•ùπΩ…ïÖÕîÄÙÅç°Ö•π%êÄÑÙÄàÄ¨ÄúàúÄ¨ÄâÕΩ±ÖπÑàÄ¨ÄúàúÄ¨Äà§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅëï‡πçΩπ—Ö•πÃ†âŸÖ∞ÅâÖÕïëë…ïÕÃÄÙÅ…Ω‹πΩ¡—)M=9=â©ïç–†àÄ¨ÄúàúÄ¨ÄââÖÕïQΩ≠ï∏àÄ¨ÄúàúÄ¨Äà§¸πΩ¡—M—…•πú†àÄ¨ÄúàúÄ¨ÄâÖëë…ïÕÃàÄ¨ÄúàúÄ¨Äà∞ÄàÄ¨ÄúàúÄ¨ÄúàúÄ¨Äà§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅëÂπÖµ•åÅ$ÅÖπêÅçÖπë•ëÖ—îÅïçΩπΩµ•çÃÅµ’Õ–ÅâîÅÖëë…ïÕÃÅ≠ïÂïêÅ›•—†Åï·¡±•ç•–Å¡…ΩŸïπÖπçîà∞(ÄÄÄÄÄÄÄÄÄÄÄÅ—…Öëï»πçΩπ—Ö•πÃ†âM°•—Ω•πQ…Öëï…$π°ÖÕAΩÕ•—•Ω∏°—Ω¨πµ•π–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—…Öëï»πçΩπ—Ö•πÃ†âµ•π–ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÙÅ—Ω¨πµ•π–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—…Öëï»πçΩπ—Ö•πÃ†âŸÖ∞Åï·Öç—5ï—…•çÃÿ–‰ÃÄÙÅï·Öç—ÕÕï—5ï—…•çÃÿ–‰Ã°Õ•ùπÖ∞§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ—…Öëï»πçΩπ—Ö•πÃ†âµÖ…≠ï–ÄÙÅAï…¡Õ5Ö…≠ï–πe8à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖ—…Öëï»πçΩπ—Ö•πÃ†âŸÖ∞Åïπ’µ5≠–ÄÙÅAï…¡Õ5Ö…≠ï–πŸÖ±’ïÃ†§πô•πêÅÏÅ•–πÕÂµâΩ∞ÄÙÙÅ—Ω¨πÕÂµâΩ∞ÅÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅçÖπë•ëÖ—îπçΩπ—Ö•πÃ†âµÖ…≠ï—Ö¡MΩ’…çîÿ–‰Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ–‰ÃÅµ’Õ–ÅπΩ–ÅôÖâ…•çÖ—îÅ—Ω≠ï∏Å±•≈’•ë•—‰Åô…Ω¥Åâ’±¨ÅŸΩ±’µîÅΩ»Å—…’Õ–Å±ïùÖç‰Åùïπï…•åÅï‡ÅçÖ¡Ãà∞(ÄÄÄÄÄÄÄÄÄÄÄÄÖ—…Öëï»πçΩπ—Ö•πÃ†âŸΩ∞Ä®Ä¿∏ƒ¿à§ÄòòÄÖ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âaMI9I}5I-Q}@à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âaMI9I}	M}5%9Q}5I-Q}@à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‰—}•µµ’—Öâ±ï}±Öπï}ï±ïç—•Ωπ}—•ç≠ï—}Öπë}¡…ï}ôëù}Ωçç’¡Öπç‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩΩ…ë•πÖ—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1Öπï·ïç’—•ΩπΩΩ…ë•πÖ—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—†ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQ…Öëï’—°Ω…•Èï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…µ•–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ•πÖ±·ïç’—•ΩπAï…µ•–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†âŸÖ∞Åï±ïç—•Ωπ%êËÅM—…•πúà§ÄòòÅçΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†âŸÖ∞ÅÖ’—°Ω…•—ÂYï…Õ•Ω∏ËÅ1Ωπúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†â±ÖπïÃÄÙÅ±•Õ—=ò°±ÖπïU¡¡ï»§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅçΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†â¡…ïôï……ïêÄÙÅ±ÖπïU¡¡ï»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅçΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†âŸÖ∞ÅÖ±±Ω›ïêÄÙÅîπ¡…•µÖ…Â1ÖπîÄÙÙÅ±ÖπïU¡¡ï»à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅçΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†âîπçΩ¡‰°ÕïÖ±ïêÄÙÅ—…’î§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ö’—†πçΩπ—Ö•πÃ†âï±ïç—ïë1Öπîÿ–‰–ÄÙÅ±Öπï±ïç—•Ω∏π¡…•µÖ…Â1Öπîà§ÄòòÅÖ’—†πçΩπ—Ö•πÃ†âï±ïç—•Ωπ%êÿ–‰–ÄÙÅ±Öπï±ïç—•Ω∏πï±ïç—•Ωπ%êà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†â¡ï…µ•–Åµ’Õ–ÅπΩ–Å•πëï¡ïπëïπ—±‰Å…îµï±ïç–ÅÖô—ï»ÅÖ’—°Ω…•ÈÖ—•Ω∏à∞Å¡ï…µ•–πçΩπ—Ö•πÃ†â1Öπï·ïç’—•ΩπΩΩ…ë•πÖ—Ω»πçÖπIï≈’ïÕ—·ïç’—•Ω∏°µ•π–∞Å±ÖÂï»§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…µ•–πçΩπ—Ö•πÃ†â%55UQ	1}a}Q%-Q}5%MM%9|ÿ–‰–à§ÄòòÅ¡ï…µ•–πçΩπ—Ö•πÃ†â%55UQ	1}1Q%=9}19}5%M5Q!|ÿ–‰–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â•ÕIïÖ±·ïç’—•Ωπ1Öπî°…ïçï•¡—1Öπîÿ–‰–§Ä¥¯Å…ïçï•¡—1Öπîÿ–‰–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±1ÖπîÄÙÅçÖπΩπ•çÖ±1Öπîÿ‘ƒ‰à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âôëù%π—ïπ–ÿ‘ƒ‰πçΩ¡‰†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âAI}}9=9}5%9Q}=UA%}MUAAIMM|ÿ–‰–à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âï·ïç’—•Ωπ	ΩΩ≠Ω…1Öπîÿ–‰–°çÂç±ïA…•µÖ…Â1Öπî§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†â±ÖπîÅ°ÖπëΩôòÅµ’Õ–ÅπΩ–Å…ï¡±ÖçîÅÖ’—†Å…ïçï•¡–Å›•—†Åµ’—Öâ±îÅ…ïçïπ–Å±ΩΩ≠’¿à∞ÅâΩ–πçΩπ—Ö•πÃ†âŸÖ∞Å—…ïÖÕ’…Â——ïµ¡—%êÄÙÅ·ïç’—Öâ±ï=¡ïπÖ—îπ…ïçïπ—±±Ω›ïë——ïµ¡—%êà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‰’}≈’Ö…Öπ—•πïÕ}•µ¡ΩÕÕ•â±ï}ô•πÖ±•Èïë}ïçΩπΩµ•çÕ}Öπë}¡…ΩŸ•ëï…}âÂ¡ÖÕÕïÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åô•πÖ±	’ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Öç—•åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ïÖ…π•πúΩQÖç—•çM›•—ç°ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·¡ïç—Öπç‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMçΩ…ï·¡ïç—ÖπçÂQ…Öç≠ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨Ωï·Õç…ïïπï…¡§π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç•…ç’•–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨Ω!ΩÕ—•…ç’•—%π—ï…çï¡—Ω»π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ô•πÖ±	’ÃπçΩπ—Ö•πÃ†â9=9%1}%91%i}=9=5%M}EUI9Q%9|ÿ–‰‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ô•πÖ±	’ÃπçΩπ—Ö•πÃ†â%5A1%}AI=M}	=Y|‘¿¿¡}M=0à§ÄòòÅô•πÖ±	’ÃπçΩπ—Ö•πÃ†âIQUI9}IQ%=9}AQ}5%M5Q à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°—Öç—•åπçΩπ—Ö•πÃ†âQÖç—•çM›•—ç°ï»πΩπÖπΩπ•çÖ±Q…Öëï±ΩÕïêÿ–‡ÿà§ÄòòÅ—Öç—•åπçΩπ—Ö•πÃ†â¡ï…Õ•Õ—ïë}ïçΩπΩµ•çÕ}≈’Ö…Öπ—•πïë|ÿ–‰‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°—Öç—•åπçΩπ—Ö•πÃ†âQ…Öëï!•Õ—Ω…ÂM—Ω…îπ•ÕYÖ±•ëççΩ’π—•πùQ…Öëî°•–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·¡ïç—Öπç‰πçΩπ—Ö•πÃ†âM=I}aAQ9e}AIM%MQ}=9=5%M}EUI9Q%9|ÿ–‰‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ëï‡πçΩπ—Ö•πÃ†âπïŸï»ÅâÂ¡ÖÕÃÅ!ïÖ±—°›Ö…ï!——¿Ω¡•	Öç≠ΩôòÅ›•—†ÅÑÅ…Ö‹Å…ï—…‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âï·Mç…ïïπï»Åµ’Õ–ÅπΩ–Å…ï—…‰Å…Ö‹ÅÖô—ï»Å—°îÅ°ïÖ±—†Å›…Ö¡¡ï»à∞Åëï‡πçΩπ—Ö•πÃ†â°——¿ππï›Ö±∞°…ïƒ§πï·ïç’—î†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç•…ç’•–πçΩπ—Ö•πÃ†â¡•	Öç≠ΩôòÅÕ°Ö…ïêµç±•ïπ–Å±Ωç≠Ω’–à§ÄòòÅç•…ç’•–πçΩπ—Ö•πÃ†â…ïÕ¡ΩπÕîπçΩëîÄÙÙÄ–¿Ãà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ–‰·}¡Ö¡ï…}—ï…µ•πÖ±}Õ—Ö—ï}≈—Â}¡Ö…•—Â}Öπë}Õ•È•πù}Ö…ï}çÖπΩπ•çÖ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ’πëÖ…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩMï±±E—Â	Ω’πëÖ…Â±Öµ¿ÿ–»‹π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAΩÕ•—•ΩπIïù•Õ—…ÂAÖ…•—Â’ë•–ÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩµï…ùïπ—’Ö…ë…Ö•±Ãπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•È•πúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω=…ëï…M•ÈïIïÕΩ±Ÿï»ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åù…ΩƒÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ…Ω≈IΩ’—ïΩπô•úÿ–‰‡π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï…Q‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âAΩÕ•—•ΩπM—Ö—ï1ïëùï»ÿ–‘–πΩππ—…‰°¡•êÿ–‡‘§à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âÕÂπç’—°Ω…•—Ö—•ŸïIÖ‹°¡•êÿ–‡‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»π•πëï·=ò†âçÖπΩπ•çÖ±AÖ¡ï…Mï±±Ωµµ•——ïêÿ–‹–ÄÙÅç±ΩÕîÿ–‹–πÖ¡¡±•ïêà§Å•∏ÄƒÅ’π—•∞Åï·ïç’—Ω»π±ÖÕ—%πëï·=ò†âAÖ¡ï…Qï…µ•πÖ±A…Ω©ïç—•ΩπΩπŸï…ùïπçîÿ‘¿‰πçΩπŸï…ùîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ö¡ï…Q‡πçΩπ—Ö•πÃ†âAΩÕ•—•ΩπM—Ö—ï1ïëùï»ÿ–‘–πΩππ—…‰°¡ΩÕ•—•Ωπ%ê§à§ÄòòÅ¡Ö¡ï…Q‡πçΩπ—Ö•πÃ†âMï±±E—Â	Ω’πëÖ…Â±Öµ¿ÿ–»‹πÕÂπç’—°Ω…•—Ö—•ŸïIÖ‹°¡ΩÕ•—•Ωπ%êà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°â…•ëùîπçΩπ—Ö•πÃ†âÖëµ•—IÖ‹°¡ΩÕ•—•Ωπ%ê∞ÅÕΩ±ëE—ÂIÖ‹à§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†âçΩµµ•—IÖ‹°¡ΩÕ•—•Ωπ%ê∞ÅÕΩ±ëE—ÂIÖ‹∞Å—ï…µ•πÖ∞§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ’πëÖ…‰πçΩπ—Ö•πÃ†âM11}EQe}	=U9Ie}5%QQ|àÄ¨Äàÿ–‰‡à§ÄòòÅâΩ’πëÖ…‰πçΩπ—Ö•πÃ†âM11}EQe}	=U9Ie}I)Q|àÄ¨Äàÿ–‰‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ö…•—‰πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±M—Ö—ï	Â5•π–àÄ¨Äàÿ–‰‡à§ÄòòÅ¡Ö…•—‰πçΩπ—Ö•πÃ†âåÙêàÄ¨Äâï·¡ïç—ïëM—Ö—îÿ–‰‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïù•Õ—…‰πçΩπ—Ö•πÃ†âÕ—Ö—îÄÙÅ¿πÕ—Ö—îà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âAIQ%11e}1=Mà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†(ÄÄÄÄÄÄÄÄÄÄÄÄâX‘∏¿∏ÿ–‰‡¨ÿÿƒ»ËÅ±Öëëï…ïêÅµ’Õ–Å…ïµÖ•∏Å—°îÅ5`ÅΩòÅ…•Õ¨µëï…•ŸïêÅÖπêÅ±Öëëï…QÖ…ùï–ÉäPÅÖççï¡–Åï•—°ï»ÅÅ…•Õ≠ÄÅΩ»ÅÅπ’ëùïëI•Õ≠ÄÄ°âΩ’πëïêÅçΩπ—…•â’—Ω»Åµï…ùîÅ¡…ïÕï…ŸïÃÅ—°îÅëΩç—…•πî§à∞(ÄÄÄÄÄÄÄÄÄÄÄÄ°Õ•È•πúπçΩπ—Ö•πÃ†â≠Ω—±•∏πµÖ—†πµÖ‡°…•Õ¨∞Å±Öëëï…QÖ…ùï–§à§ÅÒÅÕ•È•πúπçΩπ—Ö•πÃ†â≠Ω—±•∏πµÖ—†πµÖ‡°π’ëùïëI•Õ¨∞Å±Öëëï…QÖ…ùï–§à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÖÕ•È•πúπçΩπ—Ö•πÃ†â≠Ω—±•∏πµÖ—†πµ•∏°…•Õ¨∞Å±Öëëï…±ΩΩ»§à§(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ•È•πúπçΩπ—Ö•πÃ†âÖ’—°Ω…•—ÂÖ¡1Öµ¡Ω…—ÃàÄ¨Äàÿ–‰‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ù…ΩƒπçΩπ—Ö•πÃ†âΩ¡ïπÖ§Ωù¡–µΩÕÃ¥»¡àà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘¿Â}ÕΩ’…çï}…ΩΩ—ïë}≈’Öπ—•—Â}ç±ΩÕï}Öπë}ï·ïç}•π—ïπ—}Ö’—°Ω…•—•ïÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å≈—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAÖ¡ï…QΩ≠ïπE’Öπ—•—Â’—°Ω…•—‰ÿ‘¿‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ•……Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—Ω…ÖπΩπ•çÖ±5•……Ω»ÿ––»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩAÖ¡ï…Qï…µ•πÖ±A…Ω©ïç—•ΩπΩπŸï…ùïπçîÿ‘¿‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†àÿ‘¿‰Å…ïçΩ…ëQ…ÖëîÅµ’Õ–ÅπïŸï»Åëï…•ŸîÅÕΩ±êµ—Ω≠ï∏Åô…Öç—•Ω∏Åô…Ω¥ÅïçΩπΩµ•åÅ…ï—’…∏à∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†âïπ—…ÂE—ÂΩ…)Ω’…πÖ∞Ä®Ä°—…ÖëîπÕΩ∞ÄºÅïπ—…ÂΩÕ—Ω…)Ω’…πÖ∞§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±ΩπÕ’µïëIÖ‹ÄÙÅ…Ö›Yï…ë•ç–ÿ‘»¿ππΩ…µÖ±•ÈïëIÖ‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÖï·ïç’—Ω»πçΩπ—Ö•πÃ†â©Ω’…πÖ±MΩ±ëIÖ‹°—…ÖëîπÕΩ±ëE—ÂQΩ≠ï∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°≈—‰πçΩπ—Ö•πÃ†âï·¡ïç—ïêÄÙÄ°çΩÕ—MΩ∞Ä®ÅÕΩ±UÕê§ÄºÅ—Ω≠ïπA…•çïUÕêà§ÄòòÅ≈—‰πçΩπ—Ö•πÃ†âëïçΩëïêÄÙÅëïçΩëî°…Ö‹∞Åëïç•µÖ±Ã§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}	Ue}II}M=1}UM}5%MM%9|ÿ‘¿‰à§ÄòòÄÖï·ïç’—Ω»πçΩπ—Ö•πÃ†âïôôïç—•ŸïMΩ∞ÄºÅµÖ·=ò°ïôôïç—•ŸïA…•çî∞Ä≈î¥ƒ»§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†àÿ‘ƒ–ËÅ’π≠πΩ›∏ÅAAHÅëïç•µÖ±ÃÅÖ…îÅÖëŸ•ÕΩ…‰∞ÅπïŸï»ÅÑÅâ±Ωç≠•πúÅ…ïÖÕΩ∏à∞Åï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}	Ue}II}%51M}5%MM%9|àÄ¨Äàÿ‘¿‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â¡Ö¡ï…QΩ≠ïπïç•µÖ±Ãÿ‘¿‰à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â—Ω≠ïπïç•µÖ±ÃÄÙÅ¡Ö¡ï…QΩ≠ïπïç•µÖ±Ãÿ‘¿‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µ•……Ω»πçΩπ—Ö•πÃ†â—Ω≠ïπïç•µÖ±ÃÄÙÅ—Ω≠ïπïç•µÖ±Ãà§ÄòòÄÖµ•……Ω»πçΩπ—Ö•πÃ†â—Ω≠ïπïç•µÖ±ÃÄÙÄ‰∞ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄººÅ¡…ΩŸ•Õ•ΩπÖ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç±ΩÕîπçΩπ—Ö•πÃ†âA=MQ}1=M}1I}MQ5A}%1|ÿ‘¿‰à§ÄòòÅç±ΩÕîπçΩπ—Ö•πÃ†âA=MQ}1=M}AAI}UQ!}%1|ÿ‘¿‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅç±ΩÕîπçΩπ—Ö•πÃ†âA=MQ}1=M}UII%1}I5=Y}%1|ÿ‘¿‰à§ÄòòÅç±ΩÕîπçΩπ—Ö•πÃ†âA=MQ}1=M}1=	1}I%MQIe}%1|ÿ‘¿‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅç±ΩÕîπçΩπ—Ö•πÃ†âA=MQ}1=M}A=IQ=1%=}I5=Y}%1|ÿ‘¿‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±±ΩÕïë9Ωç—•Ÿîà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â…ï—’…∏ÅMï±±IïÕ’±–π1Ie}1=Mà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±·ïç’—Öâ±ï%π—ïπ–ÿ‘¿‰à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âa}I]}M%91}%9=MQ%}%9=I|ÿ‘¿‰à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπΩπ•çÖ±%π—ïπ–ÄÙÅùÖ—îπÕ’âÕ—…•πú°ùÖ—îπ•πëï·=ò†â•π—ï…πÖ∞Åô’∏ÅçÖπΩπ•çÖ±·ïç’—Öâ±ï%π—ïπ–ÿ‘¿‰à§∞ÅùÖ—îπ•πëï·=ò†â¡…•ŸÖ—îÅŸÖ∞ÅÕ—Ö—ïÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âï·ïç’—•Ω∏Å—•ç≠ï–Å•ÃÅΩ’—¡’–∞ÅπïŸï»Å•π¡’–Å—ºÅ¡…îµï·ïç’—•Ω∏ÅÅÖ’—°Ω…•—‰à∞ÅçÖπΩπ•çÖ±%π—ïπ–πçΩπ—Ö•πÃ†â°ÖÕ%µµ’—Öâ±ïQ•ç≠ï–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†â…Ö‹ÅπΩ∏µ	UdÅ…ïµÖ•πÃÅë•ÖùπΩÕ—•åÅÖô—ï»ÅçÖπΩπ•çÖ∞ÅÅÖ’—°Ω…•ÈÖ—•Ω∏à∞ÅùÖ—îπçΩπ—Ö•πÃ†âa}I]}M%91}%9=MQ%}%9=I|ÿ‘¿‰à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒ¡}±Öπï}ëïç•Õ•Ωπ}µÖ…≠}¡Ö…—•Ö±}Öπë}•πç•ëïπ—}Ö’—°Ω…•—•ïÕ}Ö…ï}ÕΩ’…çï}…ΩΩ—ïê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å•ëïπ—•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQ…Öëï%ëïπ—•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëïç•Õ•Ω∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—•Ωπïç•Õ•ΩπMπÖ¡Õ°Ω–ÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…¨ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω5Ö…≠’—°Ω…•—Â%π—ïù…•—ÂÖ—îÿ–‰ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö…—•Ö∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…AÖ…—•Ö±=¡ï…Ö—•Ω∏ÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å•πç•ëïπ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩIΩΩ—Ö’Õï%πç•ëïπ—1•ôïçÂç±îÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åô…ïÕ°πïÕÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩIΩΩ—Ö’Õï…ïÕ°πïÕÕ’—°Ω…•—‰ÿ–‰ÿπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•ëïπ—•—‰πçΩπ—Ö•πÃ†âŸÖ»Åï·ïç’—•Ωπ1ÖπîËÅM—…•πúà§ÄòòÅ•ëïπ—•—‰πçΩπ—Ö•πÃ†âŸÖ»ÅôëùÖπë•ëÖ—ïYï…Õ•Ω∏ËÅ1Ωπúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âë•ÕçΩŸï…‰Å¡…ΩŸïπÖπçîÅµ’Õ–ÅπïŸï»Å…ïÕΩ±ŸîÅï·ïç’—•Ω∏Å±Öπîà∞Åï·ïåπçΩπ—Ö•πÃ†âπΩ…µÖ±•Èï·ïç’—•Ωπ1Öπî°•ëïπ—•—‰¸πÕΩ’…çî§à§ÅÒÅï·ïåπçΩπ—Ö•πÃ†âπΩ…µÖ±•Èï·ïç’—•Ωπ1Öπî°—ÃπÕΩ’…çî§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïåπçΩπ—Ö•πÃ†âa}19}%9Q%Qe}%9YI%9Q}%1à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â}5UQ	1}M%91}%9=I|ÿ‘ƒ»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â·ïç’—•Ωπïç•Õ•ΩπMπÖ¡Õ°Ω–ÿ‘ƒ¿π…ïçΩ…êà§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†ââÂ’—°Ω…•—Â-ï‰à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†â…’π—•µïïπï…Ö—•Ω∏à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†âµΩëîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…¨πçΩπ—Ö•πÃ†âŸÖ∞Å¡…•çï’—°Ω…•—Ö—•Ÿîà§ÄòòÅµÖ…¨πçΩπ—Ö•πÃ†âŸÖ∞Å…Ω’—ï·ïç’—Öâ±îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ö…—•Ö∞πçΩπ—Ö•πÃ†ààâŸÖ∞ÅΩ¡ï…Ö—•Ωπ%êÄÙÄàààà§ÄòòÅ¡Ö…—•Ö∞πçΩπ—Ö•πÃ†â¡ΩÕ•—•Ωπ%êà§ÄòòÅ¡Ö…—•Ö∞πçΩπ—Ö•πÃ†âÕï≈’ïπçîà§ÄòòÅ¡Ö…—•Ö∞πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰πô•πÖ±•ÈïMï±∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†â¡Ö¡ï»Å¡Ö…—•Ö∞ÅΩ¡ï…Ö—•Ω∏Å%ÃÅµ’Õ–ÅπΩ–ÅçΩπ—Ö•∏Å›Ö±±ç±Ωç¨Åùïπï…Ö—•ΩπÃà∞Å¡Ö…—•Ö∞πçΩπ—Ö•πÃ†âMÂÕ—ï¥πç’……ïπ—Q•µï5•±±•Ã†•ı|à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•πç•ëïπ–πçΩπ—Ö•πÃ†âïπ’¥Åç±ÖÕÃÅM—Ö—îÅÏÅ=A8∞ÅIM=1YÅÙà§ÄòòÅô…ïÕ°πïÕÃπçΩπ—Ö•πÃ†âï±Ö¡ÕïêÅ—•µîÅπïŸï»Å…ïÖç—•ŸÖ—ïÃÅ±•ôï—•µîÅ°•Õ—Ω…‰à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒ≈}¡Ö¡ï…}¡…ï}—•ç≠ï—}ô±ΩΩ…}•Õ}•πëï¡ïπëïπ—}Öπë}¡…ïÕï…ŸïÕ}ŸÖ±•ë}Õ°Ö¡ïë}â’ÂÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•Èï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMµÖ…—M•Èï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ•π•µ’µM—Ö…–ÄÙÅï·ïåπ•πëï·=ò†â¡…•ŸÖ—îÅô’∏Åµ•πΩπô•ù’…ïëAÖ¡ï…Q…ÖëïMΩ∞à§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ•π•µ’µπêÄÙÅï·ïåπ•πëï·=ò†â¡…•ŸÖ—îÅô’∏Åç±Öµ¡AÖ¡ï…Q…ÖëïMΩ∞à∞Åµ•π•µ’µM—Ö…–§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ•π•µ’µ	±Ωç¨ÄÙÅï·ïåπÕ’âÕ—…•πú°µ•π•µ’µM—Ö…–∞Åµ•π•µ’µπê§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âΩ…ë•πÖ…‰Å…ï≈’ïÕ—ïêÅÕµÖ±±	’ÂMΩ∞Åµ’Õ–ÅπΩ–ÅâîÅï·ïç’—Öâ±îµô±ΩΩ»ÅÖ’—°Ω…•—‰à∞Åµ•π•µ’µ	±Ωç¨πçΩπ—Ö•πÃ†âåπÕµÖ±±	’ÂMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†â›Ö±±ï–µ…ï±Ö—•ŸîÅ…ï≈’ïÕ—ïêÅÕ•È•πúÅµ’Õ–ÅπΩ–ÅâïçΩµîÅÖ∏Åï·ïç’—Öâ±îÅô±ΩΩ»à∞Åµ•π•µ’µ	±Ωç¨πçΩπ—Ö•πÃ†â¡Ö¡ï…M•µ’±Ö—ïë	Ö±ÖπçîÄ®Ä¿∏¿¿ƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µ•π•µ’µ	±Ωç¨πçΩπ—Ö•πÃ†âAÖ¡ï…A…ïQ•ç≠ï—M•Èï±ΩΩ»ÿ‘ƒƒπâΩ’πëïë5•π•µ’¥°…’π—•µï5•π•µ’¥ÿ‘ƒƒ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïåπçΩπ—Ö•πÃ†â	M=1UQ}aUQ	1}1==I}M=0ÄÙÄ¿∏¿‘à§ÄòòÅÕ•Èï»πçΩπ—Ö•πÃ†â=9=5%}5%9}M%i}AI=5=Q|ÿ‘‘‘à§ÄòòÅÕ•Èï»πçΩπ—Ö•πÃ†âŸÖ∞Åë’Õ—±ΩΩ»ÄÙÄ¿∏¿‘à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…ΩµΩ—îÄÙÅï·ïåπ•πëï·=ò†âŸÖ∞Åïôôïç—•ŸïIï≈’ïÕ—ïëMΩ∞ÿ‘ƒƒà§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅï·ïåπ•πëï·=ò†âQ…Öëï…M•È•πù	…•ëùîÿ–––π…ïÕΩ±ŸïΩ…1Öπîà∞Å¡…ΩµΩ—î§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï©ïç–ÄÙÅï·ïåπ•πëï·=ò†âAAI}	Ue}I)Q}	=I}Q%-Q}M%i|ÿ–‰¿à∞Åâ…•ëùî§(ÄÄÄÄÄÄÄÅŸÖ∞Å—•ç≠ï–ÄÙÅï·ïåπ•πëï·=ò†â·ïç’—Öâ±ï=¡ïπÖ—îπçÖπ=¡ïπ·ïç’—Öâ±ïAΩÕ•—•Ω∏à∞Å…ï©ïç–§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩµµ•–ÄÙÅï·ïåπ•πëï·=ò†âX‘∏¿∏ÿ–‡‘ÉäPÅQ=5%ÅAAHÅ	UdÅ=55%Pà∞Å—•ç≠ï–§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡…ΩµΩ—îÄ¯ÙÄ¿ÄòòÅ¡…ΩµΩ—îÄÅâ…•ëùîÄòòÅâ…•ëùîÄÅ…ï©ïç–ÄòòÅ…ï©ïç–ÄÅ—•ç≠ï–ÄòòÅ—•ç≠ï–ÄÅçΩµµ•–§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïåπçΩπ—Ö•πÃ†âŸÖ∞Åô±ΩΩ…A…ΩµΩ—•ΩπIï≈’ïÕ—ïêÿ‘ƒƒÄÙÅôÖ±Õîà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âÕïÖ±ïë9Ω—•ΩπÖ∞ÿ‘‘»à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âAAI}	Ue}I)Q}	=I}Q%-Q}M%i|ÿ–‰¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†âX‘∏¿∏ÿ‘ÿ‹ËÅ…ïë’çïêÅÖëÖ¡—•ŸîÅ…ï≈’ïÕ—ÃÅµ’Õ–ÅπïŸï»ÅâîÅ•πô±Ö—ïêÅâ‰ÅÑÅëΩ›πÕ—…ïÖ¥Åô±ΩΩ»à∞Åï·ïåπçΩπ—Ö•πÃ†âAAI}	Ue}M%i}1==I}AI=5=Q|ÿ‘ƒƒà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘‘—}çÖπΩπ•çÖ±}ïπ—…Â}’ÕïÕ}Ω¡ïπ}ùÖ—ï}Ö’—°Ω…•—Â}Öπë}Ω¡ïπ}ë•…ïç—•Ω∏†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπ—…Öç–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±ÕÕï—π—…ÂΩπ—…Öç–ÿ‘‘ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩπ—…Öç–πçΩπ—Ö•πÃ†â…ïù•Õ—ï…ÖπΩπ•çÖ±%π—ïπ–ÿ‘‘–à§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âa}%9Q9Q}I%MQIQ%=9}%1à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩπ—…Öç–πçΩπ—Ö•πÃ†ààâÕ•ëîÄÙÄâ	Udàààà§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†ààâÖç—•Ω∏ÄÙÄâ=A8àààà§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âë•…ïç—•Ω∏ÄÙÅ•òà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âô’∏Å…ïù•Õ—ï…ÖπΩπ•çÖ±%π—ïπ–ÿ‘‘–à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âÖç—•Ÿï·ïç’—•Ωπ%π—ïπ—Ãÿ‘ƒ‰à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âï·ïç’—•ΩπQ•ç≠ï—Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â¡…•ŸÖ—îÅô’∏Å¡’â±•Õ°ëù%π—ïπ–ÿ‘ƒ‰à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†â…ïù•Õ—ï…ÖπΩπ•çÖ±%π—ïπ–ÿ‘‘–°Õ•Èïë%π—ïπ–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩπ—…Öç–πçΩπ—Ö•πÃ†â9=9%1}A9%9}aA%Ià§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âµÖ…≠Ö•±ïêà§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âµÖ…≠ïôï……ïêà§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âµÖ…≠Öπçï±±ïêà§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿÕ}çÂç±•ç}•Õ}πΩ—}’πçΩπë•—•ΩπÖ±±Â}ë•ÕÖâ±ïë}Öô—ï…}…’π—•µï}¡±Ö∏†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âe1%}IU9Q%5}9	1|ÿ‘ÿÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âŸÖ∞ÅçÂç±•çπÖâ±ïêÿ‘ÿÃÄÙÅ¡±Ö∏ÿ‘»ÿπ¡Ö¡ï…5ΩëîÅÒÅµÖ…≠ï—ÕM—Ö…—ôúπçÂç±•çQ…ÖëïπÖâ±ïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†ÖâΩ–πçΩπ—Ö•πÃ†âÂç±•çQ…Öëïπù•πîπÕï—πÖâ±ïê°ôÖ±Õî§à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ…}ç…Â¡—Ω}¡Ö¡ï…}±ïÖ…π•πù}…ïÖç°ïÕ}ôëù}›•—°Ω’—}…ï±Ö·•πù}±•Ÿï}µΩµïπ—’¥†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}AAI}1I9%9}5%MM%=9|ÿ‘ÿ»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âŸÖ∞Å¡Ö¡ï…1ïÖ…π•πùÖπë•ëÖ—îÿ‘ÿ»ÄÙÅ•ÕAÖ¡ï…5Ωëîπùï–†§ÄòòÅÕçΩ…îÄ¯ÙÄ‘¿ÄòòÅçΩπô•ëïπçîÄ¯ÙÄ–¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†àÖ¡Ö¡ï…1ïÖ…π•πùÖπë•ëÖ—îÿ‘ÿ»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπ•πëï·=ò†â¡Ö¡ï…1ïÖ…π•πùÖπë•ëÖ—îÿ‘ÿ»à§ÄÅç…Â¡—ºπ•πëï·=ò†â…ï—’…∏Å±—M•ùπÖ∞†à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ≈}µÖ…≠ï—Õ}ï·•—}’ÕïÕ}çÖπΩπ•çÖ±}¡Ö¡ï…}ç±ΩÕï}Öπë}±•Ÿï}ô•πÖ±•—‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ωç≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπç±ΩÕîà§ÄòòÅÕ—Ωç≠ÃπçΩπ—Ö•πÃ†âç±ΩÕï1•ŸïAΩÕ•—•ΩπA…ΩΩòÿ–‡ÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπç±ΩÕîà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âç±ΩÕï1•ŸïAΩÕ•—•ΩπA…ΩΩòÿ–‡ÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†â•òÄ†ÖçÖπΩπ•çÖ±±ΩÕîÿ–‡ÿπÖ¡¡±•ïê§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†â•òÄ†ÖçÖπΩπ•çÖ±±ΩÕîÿ–‡ÿπÖ¡¡±•ïê§à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ≈}µÖ…≠ï—Õ}Õ¡ïç•Ö±•Õ—}…ïÖç°ïÕ}çÖπΩπ•çÖ±}ôëù}âïôΩ…ï}Ω¡ï∏†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ωç≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπÕ’âµ•–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±ÕÕï—π—…ÂÖπë•ëÖ—îÿ‘‘ƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†âµÖ…≠ï—%π—ïπ–ÿ‘ÿƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπµÖ…≠Ωπô•…µïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπµÖ…≠Ö•±ïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠Ãπ•πëï·=ò†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπÕ’âµ•–à§ÄÅÕ—Ωç≠Ãπ•πëï·=ò†âŸÖ∞Å¡ΩÕ•—•Ω∏ÄÙÅM—Ωç≠AΩÕ•—•Ω∏à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ¡}¡Ö¡ï…}·Õ—Ωç≠Õ}âÂ¡ÖÕÕ}—…Öë•—•ΩπÖ±}°Ω’…Õ}â’—}±•Ÿï}ëΩïÕ}πΩ–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ωç≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†â5I-QM}AAI|»—`›}aUQ%=9|ÿ‘ÿ¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†â•òÄ†Ö•ÕAÖ¡ï…5Ωëîπùï–†§ÄòòÄÖ•ÕM—Ωç≠5Ö…≠ï—=¡ï∏†§§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠Ãπ•πëï·=ò†â•òÄ†Ö•ÕAÖ¡ï…5Ωëîπùï–†§ÄòòÄÖ•ÕM—Ωç≠5Ö…≠ï—=¡ï∏†§§à§ÄÅÕ—Ωç≠Ãπ•πëï·=ò†â5I-QM}AAI|»—`›}aUQ%=9|ÿ‘ÿ¿à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘‘Â}¡Ö¡ï…}…’π—•µï}¡…ïçïëïÕ}Õ—Ö±ï}±•Ÿï}Ö’—°Ω…•—Â}ôΩ…}µÖ…≠ï—Õ}Öπë}ç…Â¡—º†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ï—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ï—ÃπçΩπ—Ö•πÃ†â5I-QM}AAI}IU9Q%5}AI9|ÿ‘‘‰à§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âŸÖ∞Å¡Ö¡ï…I’π—•µîÿ‘‘‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}AAI}IU9Q%5}AI9|ÿ‘‘‰à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âŸÖ∞Å¡Ö¡ï…I’π—•µîÿ‘‘‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ï—Ãπ•πëï·=ò†âŸÖ∞Å¡Ö¡ï…I’π—•µîÿ‘‘‰à§ÄÅµÖ…≠ï—Ãπ•πëï·=ò†âïôôïç—•ŸïMπÖ¡Õ°Ω–†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπ•πëï·=ò†âŸÖ∞Å¡Ö¡ï…I’π—•µîÿ‘‘‰à§ÄÅç…Â¡—ºπ•πëï·=ò†âïôôïç—•ŸïMπÖ¡Õ°Ω–†§à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘‘·}ç…ΩÕÕ}ÖÕÕï—}Õ•Èï}ÕïÖ±}Öπë}¡ï…¡Õ}ÕÖπëâΩ·}Ö…ï}ÕΩ’…çï}…ΩΩ—ïê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•È•πúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±M•È•πù	…•ëùîÿ‘Ã»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡Õπù•πîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡Õ·ïç’—•Ωππù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡ÕQ…Öëï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡ÕQ…Öëï…$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕÖπëâΩ‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAï…¡ÕMÖπëâΩ‡ÿ–ÿÃπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ•È•πúπçΩπ—Ö•πÃ†âÕ•È•πúÅ•ÃÅÖëŸ•ÕΩ…‰Å•π¡’–∞ÅπïŸï»ÅÑÅ¡…îµà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â…ïÕΩ±ŸïëM•ÈïMΩ∞ÿ‘‘‡à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âI=MM}MMQ}1e}M%91}%YI9|ÿ‘‘–à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âÖç—•Ω∏ıë•ÖùπΩÕ—•ç}Ωπ±‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†â…ïÕΩ±ŸïëM•ÈïMΩ∞ÿ‘‘‡ÄÙÅçÖπë•ëÖ—îπô•πÖ±M•Èîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…¡Õπù•πîπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπÕ’âµ•–à§ÄòòÅ¡ï…¡Õπù•πîπçΩπ—Ö•πÃ†âÕïÖ±ïëAï…¡%π—ïπ–ÿ‘‹¿à§ÄòòÅ¡ï…¡Õπù•πîπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±Aï…¡ÕM•Èîÿ‘‹¿ÄÙÅÕïÖ±ïëAï…¡%π—ïπ–ÿ‘‹¿π…ïÕΩ±ŸïëM•ÈïMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…¡ÕQ…Öëï»πçΩπ—Ö•πÃ†âAï…¡ÕMÖπëâΩ‡ÿ–ÿÃπΩ¡ïπ1ïŸï…ÖùïëAÖ¡ï»à§ÄòòÅ¡ï…¡ÕQ…Öëï»πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ…ïô’πêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕÖπëâΩ‡πçΩπ—Ö•πÃ†âAIAM}a}%MAQ!|ÿ‘‘–à§ÄòòÅÕÖπëâΩ‡πçΩπ—Ö•πÃ†âAIAM}=A9}=9%I5|ÿ‘‘–à§ÄòòÅÕÖπëâΩ‡πçΩπ—Ö•πÃ†âAIAM}=A9}IUM|ÿ‘‘–à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘‘Õ}¡Ö¡ï…}µΩëï}…ïÖç°ïÕ}µÖ…≠ï—Õ}Öπë}ç…Â¡—Ω}ÕçÖππï…Ã†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ï—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ï—ÃπçΩπ—Ö•πÃ†àÖçôúπ¡Ö¡ï…5Ωëîà§ÄòòÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†â5I-QM}AAI}1I9}YIeQ!%9}5%QQ|ÿ‘‘Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†àÖçôúπ¡Ö¡ï…5Ωëîà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}AAI}1I9}YIeQ!%9}5%QQ|ÿ‘‘Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âë•ÕçΩŸï…Âùï5•π’—ïÃÿ‘‘–à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}e9}aAIMM}aUQ	1|ÿ‘‘–à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}e9}59%A}aUQ	1|ÿ‘‘–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ç…Â¡—ºπÕ’âÕ—…•πú°ç…Â¡—ºπ•πëï·=ò†â¡…•ŸÖ—îÅÕ’Õ¡ïπêÅô’∏Å…’πÂπÖµ•çQΩ≠ïπMçÖ∏à§∞Åç…Â¡—ºπ•πëï·=ò†â¡…•ŸÖ—îÅÕ’Õ¡ïπêÅô’∏Å…’πMçÖπÂç±îà§§πçΩπ—Ö•πÃ†â—Ω≠ïπùï5•π’—ïÃÄÄÄÙÄ‰‰‰‰∏¿à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒ…}ï·ïç’—•Ωπ}Ö’—°Ω…•—Â}Öπë}¡…ΩŸ•ëï…}…Ω—Ö—•Ωπ}Ö…ï}ÕΩ’…çï}…ΩΩ—ïê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëïç•Õ•Ω∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—•Ωπïç•Õ•ΩπMπÖ¡Õ°Ω–ÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕπÖ¡Õ°Ω–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—•ΩπMπÖ¡Õ°Ω—’—°Ω…•—‰ÿ–‰ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖùù…ïùÖ—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩA…•çïùù…ïùÖ—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨Ωï·Õç…ïïπï…¡§π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…ΩŸ•ëï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA…ΩŸ•ëï…’—°Ω…•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôÖâ…•åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖ—ïïç•Õ•ΩππŸï±Ω¡îÿ‘ƒ»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•πÖ±	’ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±•πÖ±•ÈïëQ…Öëï	’Ãÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπÕ’µï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω•πÖ±•Èïë	’ÕΩπÕ’µï…	…•ëùîÿ–ÿ‘π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅŸÖ∞Å•π—ïπ–ÄÙÅùÖ—îπÕ’âÕ—…•πú°ùÖ—îπ•πëï·=ò†â•π—ï…πÖ∞Åô’∏ÅçÖπΩπ•çÖ±·ïç’—Öâ±ï%π—ïπ–ÿ‘¿‰à§∞ÅùÖ—îπ•πëï·=ò†â¡…•ŸÖ—îÅŸÖ∞ÅÕ—Ö—ïÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π—ïπ–πçΩπ—Ö•πÃ†â°ÖÕ%µµ’—Öâ±ïQ•ç≠ï–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âôëù±ïç—•Ωπ1Ωç≠Ãÿ‘ƒ»à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†â…Öπ¨°Ω±ê¸π¡…ïëùYï…ë•ç–§Ä¯ÙÅ…Öπ¨°ô•πÖ±Yï…ë•ç–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âÕ•ùπÖ∞ÄÙÅ•òÄ°≠ïï¡=±ê§ÅΩ±ê¸πÕ•ùπÖ∞à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âÕï±ïç—ïë1ÖπîÄÙÅ•òÄ°≠ïï¡=±ê§ÅΩ±ê¸πÕï±ïç—ïë1Öπîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ëïç•Õ•Ω∏πçΩπ—Ö•πÃ†ââÂ’—°Ω…•—Â-ï‰à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†â…’π—•µïïπï…Ö—•Ω∏à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†âçÖπë•ëÖ—ïYï…Õ•Ω∏à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†âï·ïç’—•Ωπ1Öπîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âÖëê†àÄ¨Äâpâ¡…•µÖ…Â1Öπî†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±=çç’¡Öπç‰ÄÙà§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âµΩëîπ’¡¡ï…çÖÕî†•ÙËàÄ¨ÄàêàÄ¨Äâµ•π–à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âAAHà§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†â1%Yà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïåπçΩπ—Ö•πÃ†â}5UQ	1}M%91}%9=I|ÿ‘ƒ»à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âa}UQ!=I%Qe}5%MM%9}II|ÿ‘ƒ»à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â…ï±ïÖÕï%ôA…•µÖ…‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ï·ïåπçΩπ—Ö•πÃ†â9QIe}	I%}9=9}	Ue}UI|ÿ‘¿–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Öùù…ïùÖ—Ω»πçΩπ—Ö•πÃ†âÖ—ÖMΩ’…çîπaAAI%-à§ÄòòÅÖùù…ïùÖ—Ω»πçΩπ—Ö•πÃ†âëÖ—ÑµÖ¡§πâ•πÖπçîπŸ•Õ•Ω∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ëï‡πçΩπ—Ö•πÃ†âôï—ç°ï·AÖ¡…•≠ÖQΩ≠ï∏ÿ‘ƒ»à§ÄòòÅ¡…ΩŸ•ëï»πçΩπ—Ö•πÃ†âaAAI%-à§ÄòòÅ¡…ΩŸ•ëï»πçΩπ—Ö•πÃ†âA…ΩŸ•ëï…Ωπô•úà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ôÖâ…•åπçΩπ—Ö•πÃ†âΩâ©ïç–ÅAΩ±•çÂMÂπ—°ïÕ•Èï»ÿ‘ƒ»à§ÄòòÅôÖâ…•åπçΩπ—Ö•πÃ†âëÖ—ÑÅç±ÖÕÃÅÖ—ïïç•Õ•ΩππŸï±Ω¡îÿ‘ƒ»à§ÄòòÅôÖâ…•åπçΩπ—Ö•πÃ†âQ}A=1%dà§ÄòòÅôÖâ…•åπçΩπ—Ö•πÃ†âQ}I]Ià§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ôÖâ…•åπçΩπ—Ö•πÃ†ââÂ——ïµ¡–à§ÄòòÅôÖâ…•åπçΩπ—Ö•πÃ†ââÂAΩÕ•—•Ω∏à§ÄòòÅôÖâ…•åπçΩπ—Ö•πÃ†â…ï›Ö…ëïëAΩÕ•—•ΩπÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ô•πÖ±	’ÃπçΩπ—Ö•πÃ†âÖ—ïAΩ±•çÂIï›Ö…êà§ÄòòÅçΩπÕ’µï»πçΩπ—Ö•πÃ†âëï±•Ÿï…QΩÖ—ïAΩ±•çÂIï›Ö…êà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±·•—QΩ≠ïπMπÖ¡Õ°Ω–ÿ‘ƒ»à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπΩ¡ïπAΩÕ•—•ΩπÃ†§à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â9=9%1}a%Q}|ÿ‘ƒ»à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒÕ}ïπ—…Â}Ö’—°Ω…•—Â}¡Ö¡ï…}ô•πÖ±•—Â}Öπë}ï·•—}µÖ…≠Õ}Ö…ï}ÕΩ’…çï}Ö’—°Ω…•—Ö—•Ÿî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëïç•Õ•Ω∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—•Ωπïç•Õ•ΩπMπÖ¡Õ°Ω–ÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…µ•–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ•πÖ±·ïç’—•ΩπAï…µ•–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµ•……Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω·ïç’—Ω…ÖπΩπ•çÖ±5•……Ω»ÿ––»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å•ëï¥ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω%ëïµ¡Ω—ïπçÂ-ïÂM—Ω…îÿ–Ã‹π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπΩ∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Ω≠ïπ5Ö¿ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩ≠ïπ5Ö¡’—°Ω…•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ΩΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩIΩΩ—Ö’Õï±ÖÕÕ•ô•ï»ÿ–‹ƒπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ëïç•Õ•Ω∏πçΩπ—Ö•πÃ†âÖ’—°Ω…•—ÂYï…Õ•Ω∏à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†âÖ’—°Ω…•—Ö—•ŸïM•ùπÖ∞à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†âÕÖôï—ÂYï…ë•ç–à§ÄòòÅëïç•Õ•Ω∏πçΩπ—Ö•πÃ†â…ïÕΩ±ŸïëM•ÈïMΩ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â•µµ’—Öâ±ï’—°Ω…•—‰ÿ‘ƒÃà§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†â•µµ’—Öâ±ïëù	’‰ÿ‘ƒ‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âôëù%π—ïπ–ÿ‘ƒ‰πçΩ¡‰†à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âÖ’—°Ω…•—Ö—•ŸïM•ùπÖ∞ÄÙÅpâ	Uepàà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âôëùYï…ë•ç–ÄÙÅ•òÄ°›•ππï»π¡…ïëùYï…ë•ç–Å•∏ÅÕï—=òà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âUQ!=I%Qe}%9YI%9Q}%1UIà§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âa}UQ!=I%Qe}MQQ}5%M5Q à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…µ•–πçΩπ—Ö•πÃ†âï·ïç’—•ΩπQ•ç≠ï–ÿ–‰–π¡…•µÖ…Â1ÖπîÄÑÙÅï·ïç’—•ΩπQ•ç≠ï–ÿ–‰–π±Öπîà§ÄòòÅ¡ï…µ•–πçΩπ—Ö•πÃ†âï·ïç’—•ΩπQ•ç≠ï–ÿ–‰–πÖ’—°Ω…•—Ö—•ŸïM•ùπÖ∞ÄÑÙÄàÄ¨Äâpâ	Uepàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïåπçΩπ—Ö•πÃ†â—•ç≠ï–ÿ‘ƒÃ¸π¡…•µÖ…Â1Öπîà§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âAAI}	Ue}QI5%91}IA1e}I=YI|ÿ‘ƒÃà§§(ÄÄÄÄÄÄÄÅŸÖ∞Åâïù•∏ÄÙÅï·ïåπ•πëï·=ò†âAÖ¡ï…π—…Â•πÖ±•—Â’—°Ω…•—‰ÿ–‰‹πâïù•π——ïµ¡–°ïπ—…Â•πÖ±•—Â%êÿ–‰‹à§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïÕï…ŸîÄÙÅï·ïåπ•πëï·=ò†â·ïç’—Ω…ÖπΩπ•çÖ±5•……Ω»ÿ––»πµ•……Ω…	’Â——ïµ¡–†à∞Åâïù•∏§(ÄÄÄÄÄÄÄÅŸÖ∞Åëïâ•–ÄÙÅï·ïåπ•πëï·=ò†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πΩπ	’‰°Öç—’Ö±MΩ∞∞Åôïîÿ–‡‘§à∞Å…ïÕï…Ÿî§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•±∞ÄÙÅï·ïåπ•πëï·=ò†â·ïç’—Ω…ÖπΩπ•çÖ±5•……Ω»ÿ––»πµ•……Ω…	’Â•±∞†à∞Åëïâ•–§(ÄÄÄÄÄÄÄÅŸÖ∞Å©Ω’…πÖ∞ÄÙÅï·ïåπ•πëï·=ò†â…ïçΩ…ëQ…Öëî°—Ã∞Å—…Öëî§à∞Åô•±∞§(ÄÄÄÄÄÄÄÅŸÖ∞Å—ï…µ•πÖ∞ÄÙÅï·ïåπ•πëï·=ò†âAÖ¡ï…π—…Â•πÖ±•—Â’—°Ω…•—‰ÿ–‰‹πµÖ…≠=¨°ïπ—…Â•πÖ±•—Â%êÿ–‰‹§à∞Å©Ω’…πÖ∞§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âïù•∏Ä¯Ä¿ÄòòÅ…ïÕï…ŸîÄ¯Åâïù•∏ÄòòÅëïâ•–Ä¯Å…ïÕï…ŸîÄòòÅô•±∞Ä¯Åëïâ•–ÄòòÅ©Ω’…πÖ∞Ä¯Åô•±∞ÄòòÅ—ï…µ•πÖ∞Ä¯Å©Ω’…πÖ∞§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µ•……Ω»πçΩπ—Ö•πÃ†ââ’Â}Ö——ïµ¡–ËàÄ¨ÄàêàÄ¨ÄâÖ——ïµ¡—%êà§ÄòòÅ•ëï¥πçΩπ—Ö•πÃ†âô’∏Å—ï…µ•πÖ±Ω»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çÖπΩ∏πçΩπ—Ö•πÃ†âïπ—…ÂA…•çïUÕêà§ÄòòÅçÖπΩ∏πçΩπ—Ö•πÃ†âïπ—…ÂA…•çïMΩ’…çîà§ÄòòÅçÖπΩ∏πçΩπ—Ö•πÃ†âïπ—…ÂAΩΩ±ëë…ïÕÃà§ÄòòÅçÖπΩ∏πçΩπ—Ö•πÃ†âïπ—…ÂA…•çïUÕêÄÙÅ…ï¡Ö•…ïëA…•çîÿ‘ƒ‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°—Ω≠ïπ5Ö¿πçΩπ—Ö•πÃ†âçÖç°ïëΩ…·•–ÿ‘ƒÃà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â9=9%1}a%Q}5I-}IIM!}EUU|ÿ‘ƒÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âÕçΩ¡îπ±Ö’πç†°≠Ω—±•π‡πçΩ…Ω’—•πïÃπ•Õ¡Ö—ç°ï…Ãπ%<§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ΩΩ–π•πëï·=ò†âa}UQ!=I%Qe}MQQ}5%M5Q à§ÄÅ…ΩΩ–π•πëï·=ò†âQ}AI=Y%I}UQ!}1=-=UQ|ÿ–ÿ‡à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒ—}¡Ö¡ï…}—•ç≠ï—}ë•Õ¡Ö—ç°}•ùπΩ…ïÕ}µ•ÕÕ•πù}ëïç•µÖ±Õ}Öπë}…ï±ïÖÕïÕ}ïŸï…Â}πΩπ—ï…µ•πÖ±}Ö’—°Ω…•—‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπΩπ•çÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}	Ue}II}%51M}5%MM%9|àÄ¨Äàÿ‘¿‰à§§(ÄÄÄÄÄÄÄÄººÅX‘∏¿∏ÿ‘–›àÉäPÅÖô—ï»ÅΩ¡ï…Ö—Ω»Å…ï¡Ω…–Å—°Ö–ÅX‘∏¿∏ÿ‘–‹Å@¿¥ƒÅëïôï»ÅÕ—Ö±±ïê(ÄÄÄÄÄÄÄÄººÅïŸï…‰Å—…Öëî∞Å›îÅ…ïÕ—Ω…ïêÅ—°îÄÿ‘ƒ–ÅÖëŸ•ÕΩ…‰µçΩπ—•π’îÅ¡Ö—†∏Å	Ω—†Å—°î(ÄÄÄÄÄÄÄÄººÅ±ïùÖç‰Äÿ‘ƒ–ÅçΩ’π—ï»ÅÖπêÅ—°îÄÿ‘–‹ÅçΩµ¡Öπ•Ω∏ÅçΩ’π—ï…ÃÅµ’Õ–ÅâîÅ¡…ïÕïπ–∏(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}%51M}A9%9}Y%M=Ie|àÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}%51M}A9%9}I|àÄ¨Äàÿ‘–‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}Q%-Q}IEUU|àÄ¨Äàÿ‘–‹à§∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}Q%-Q}%MAQ!|àÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}Q%-Q}QI5%91}=A9|àÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âAAI}Q%-Q}QI5%91}	1=-|àÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âAAI}Q%-Q}9=9QI5%91}I1M|àÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âa}1M}1-}%9YI%9Q|àÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â…ï±ïÖÕïAÖ¡ï…	’Â9ΩπQï…µ•πÖ∞ÿ‘ƒ–†àÄ¨ÄâpààÄ¨ÄâM=1}UM}5%MM%9|àÄ¨Äàÿ‘¿‰àÄ¨ÄâpààÄ¨Äà§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âÖç—•Ω∏ı…ï±ïÖÕï}Ö±±}Ö’—°Ω…•—Â}…ï—…Â}πï·—}çÂç±îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â•πÖ±·ïç’—•ΩπAï…µ•–π…ï±ïÖÕï·ïç’—•Ω∏°—Ãπµ•π–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â1Öπï·ïç’—•ΩπΩΩ…ë•πÖ—Ω»π…ï±ïÖÕï%ôA…•µÖ…‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â…ï±ïÖÕï——ïµ¡—9ΩπQï…µ•πÖ∞àÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âï·ïç’—•ΩπQ•ç≠ï—Ãπ…ïµΩŸî°Ö——ïµ¡—%ê§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âï·ïç’—Öâ±ï	’Â±Ö•¥ÿ–‡‹πïπ—…•ïÃπ…ïµΩŸï%òà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çÖπΩπ•çÖ∞πçΩπ—Ö•πÃ†âŸÖ∞Å≈’Öπ—•—ÂMçÖ±îËÅ%π–ÄÙÅ—Ω≠ïπïç•µÖ±Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°—‡πçΩπ—Ö•πÃ†â≈’Öπ—•—ÂMçÖ±îËÅ%π–ÄÙÅëïç•µÖ±Ãà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â≈’Öπ—•—ÂMçÖ±îÄÙÅ¡Ö¡ï…E’Öπ—•—ÂMçÖ±îàÄ¨Äàÿ‘ƒ–à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩëï	…Öπç†ÄÙÅï·ïç’—Ω»π•πëï·=ò†â•òÄ°•ÕAÖ¡ï…5Ωëî§ÅÏà§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï…•Õ¡Ö—ç†ÄÙÅï·ïç’—Ω»π•πëï·=ò†â¡Ö¡ï…	’‰°—Ã∞ÅïôôMΩ∞à∞ÅµΩëï	…Öπç†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±•Ÿï	…Öπç†ÄÙÅï·ïç’—Ω»π•πëï·=ò†âÙÅï±ÕîÅ•òÄ°›Ö±±ï–ÄÙÙÅπ’±∞§ÅÏà∞ÅµΩëï	…Öπç†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âAAHÅµ’Õ–Åë•Õ¡Ö—ç†ÅâïôΩ…îÅ1%YµΩπ±‰Åï·ïç’—Ω»ÅŸÖ±•ëÖ—•Ω∏à∞ÅµΩëï	…Öπç†Ä¯Ä¿ÄòòÅ¡Ö¡ï…•Õ¡Ö—ç†Ä¯ÅµΩëï	…Öπç†ÄòòÅ±•Ÿï	…Öπç†Ä¯Å¡Ö¡ï…•Õ¡Ö—ç†§(ÄÄÄÄÄÄÄÅŸÖ∞Å©Ω’…πÖ∞ÄÙÅï·ïç’—Ω»π•πëï·=ò†â…ïçΩ…ëQ…Öëî°—Ã∞Å—…Öëî§à∞Åï·ïç’—Ω»π•πëï·=ò†âô’∏Å¡Ö¡ï…	’‰†à§§(ÄÄÄÄÄÄÄÅŸÖ∞Å—ï…µ•πÖ∞ÄÙÅï·ïç’—Ω»π•πëï·=ò†âAAI}Q%-Q}QI5%91}=A9|àÄ¨Äàÿ‘ƒ–à∞Åï·ïç’—Ω»π•πëï·=ò†âô’∏Å¡Ö¡ï…	’‰†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†â…ïÖ∞Å	UdÅ©Ω’…πÖ∞Åµ’Õ–ÅâîÅΩ∏Å—°îÅ¡Ö¡ï»ÅΩ¡ï∏Å¡Ö—†à∞Å©Ω’…πÖ∞Ä¯Ä¿ÄòòÅ—ï…µ•πÖ∞Ä¯Ä¿§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒ’}çÖπΩπ•çÖ±}âΩΩ—Õ—…Ö¡}•Õ}Ωôô}µÖ•π}Öπë}°Ö…ë}âÖ……•ï…Õ}ï·ïç’—•Ω∏†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩπ…ïÖ—îÄÙÅâΩ–π•πëï·=ò†âΩŸï……•ëîÅô’∏ÅΩπ…ïÖ—î†§à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôΩ…ïù…Ω’πêÄÙÅâΩ–π•πëï·=ò†âÕ—Ö…—Ω…ïù…Ω’πê°9=Q%}%à∞ÅΩπ…ïÖ—î§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπΩπ•çÖ±1Ö’πç†ÄÙÅâΩ–π•πëï·=ò†âçÖπΩπ•çÖ±	ΩΩ—Õ—…Ö¡)ΩààÄ¨Äàÿ‘ƒ‘ÄÙÅÕçΩ¡îπ±Ö’πç†à∞ÅΩπ…ïÖ—î§(ÄÄÄÄÄÄÄÅŸÖ∞ÅïŸïπ—1ΩÖêÄÙÅâΩ–π•πëï·=ò†âçΩπΩµ•çŸïπ—Mç°ïµÑàÄ¨Äàÿ–ÿ–π•π•–ÿ–‡ÿà∞ÅçÖπΩπ•çÖ±1Ö’πç†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕï…Ÿ•çï1Ö’πç†ÄÙÅâΩ–π•πëï·=ò†âÕï…Ÿ•çï	ΩΩ—Õ—…Ö¡)ΩààÄ¨Äàÿ‘ƒÿÄÙÅÕçΩ¡îπ±Ö’πç†à∞ÅïŸïπ—1ΩÖê§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ö…–ÄÙÅâΩ–π•πëï·=ò†âô’∏ÅÕ—Ö…—	Ω–†§à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅâΩ–π•πëï·=ò†âëïôï…M—Ö…—Uπ—•±Mï…Ÿ•çïIïÖë‰àÄ¨Äàÿ‘ƒÿ†§à∞ÅÕ—Ö…–§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âôΩ…ïù…Ω’πêÅÕï…Ÿ•çîÅµ’Õ–Å¡…ïçïëîÅïŸï…‰Åë’…Öâ±îÅâΩΩ—Õ—…Ö¿à∞ÅôΩ…ïù…Ω’πêÄ¯ÅΩπ…ïÖ—îÄòòÅçÖπΩπ•çÖ±1Ö’πç†Ä¯ÅôΩ…ïù…Ω’πê§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âë’…Öâ±îÅïŸïπ–Å±ΩÖêÅµ’Õ–Åï·ïç’—îÅ•πÕ•ëîÅ%<ÅçÖπΩπ•çÖ∞ÅâΩΩ—Õ—…Ö¿à∞ÅïŸïπ—1ΩÖêÄ¯ÅçÖπΩπ•çÖ±1Ö’πç†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âçΩµ¡±ï—îÅÕï…Ÿ•çîÅâΩΩ—Õ—…Ö¿Åµ’Õ–Å›Ö•–Åâï°•πêÅçÖπΩπ•çÖ∞Å…ï¡±Ö‰à∞ÅÕï…Ÿ•çï1Ö’πç†Ä¯ÅïŸïπ—1ΩÖê§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âïŸï…‰ÅÕ—Ö…—	Ω–Å¡Ö—†Åµ’Õ–Å°•–Å—°îÅçΩµ¡±ï—îÅÕï…Ÿ•çîµ…ïÖë‰ÅùÖ—îÅô•…Õ–à∞ÅùÖ—îÄ¯ÅÕ—Ö…–ÄòòÅùÖ—îÄÅâΩ–π•πëï·=ò†â•ÕM°’——•πùΩ›∏ÄÙÅôÖ±Õîà∞ÅÕ—Ö…–§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±	ΩΩ—Õ—…Ö¡)ΩààÄ¨Äàÿ‘ƒ‘¸π©Ω•∏†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âŸÖ∞ÅâΩΩ—Õ—…Ö¿ÄÙÅÕï…Ÿ•çï	ΩΩ—Õ—…Ö¡)ΩààÄ¨Äàÿ‘ƒÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âMQIQ}II}MIY%}	==QMQIA|àÄ¨Äàÿ‘ƒÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âMQIQ}	1=-}MIY%}	==QMQIA}%1|àÄ¨Äàÿ‘ƒÿà§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒŸ}çΩµ¡±ï—ï}¡ï…Õ•Õ—ïë}Õ—Ö—ï}Õ—Ö…—’¡}ôÖµ•±Â}•Õ}Ωôô}µÖ•∏†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩπ…ïÖ—îÄÙÅâΩ–π•πëï·=ò†âΩŸï……•ëîÅô’∏ÅΩπ…ïÖ—î†§à§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕï…Ÿ•çï1Ö’πç†ÄÙÅâΩ–π•πëï·=ò†âÕï…Ÿ•çï	ΩΩ—Õ—…Ö¡)ΩààÄ¨Äàÿ‘ƒÿÄÙÅÕçΩ¡îπ±Ö’πç†à∞ÅΩπ…ïÖ—î§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩπM—Ö…–ÄÙÅâΩ–π•πëï·=ò†âΩŸï……•ëîÅô’∏ÅΩπM—Ö…—ΩµµÖπêà∞ÅÕï…Ÿ•çï1Ö’πç†§(ÄÄÄÄÄÄÄÅŸÖ∞Å•π±•πïA…ïô•‡ÄÙÅâΩ–πÕ’âÕ—…•πú°Ωπ…ïÖ—î∞ÅÕï…Ÿ•çï1Ö’πç†§(ÄÄÄÄÄÄÄÅŸÖ∞Å•Ω	Ωë‰ÄÙÅâΩ–πÕ’âÕ—…•πú°Õï…Ÿ•çï1Ö’πç†∞ÅΩπM—Ö…–§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†âïïIï—…ÂE’ï’îπ•π•–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†âïïçç’µ’±Ö—Ω»π•π•–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†âMçÖππï…!Ö…ëIï©ïç—M—Ω…îπ•π•–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†âQ…Öëï!•Õ—Ω…ÂM—Ω…îπ•π•–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†â1ïÖ…π•πùAï…Õ•Õ—ïπçîπ•π•–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†âAΩÕ•—•ΩπAï…Õ•Õ—ïπçîπ•π•–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†âAï…¡ÕQ…Öëï…$π•π•–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†âQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»πÕ—Ö…–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•π±•πïA…ïô•‡πçΩπ—Ö•πÃ†â…Â¡—Ω±—Q…Öëï»πÕ—Ö…–†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•Ω	Ωë‰πçΩπ—Ö•πÃ†âïïIï—…ÂE’ï’îπ•π•–°Ö¡¡±•çÖ—•ΩπΩπ—ï·–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•Ω	Ωë‰πçΩπ—Ö•πÃ†âQ…Öëï!•Õ—Ω…ÂM—Ω…îπ•π•–°Ö¡¡±•çÖ—•ΩπΩπ—ï·–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•Ω	Ωë‰πçΩπ—Ö•πÃ†â1ïÖ…π•πùAï…Õ•Õ—ïπçîπ•π•–°Ö¡¡±•çÖ—•ΩπΩπ—ï·–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•Ω	Ωë‰πçΩπ—Ö•πÃ†âAΩÕ•—•ΩπAï…Õ•Õ—ïπçîπ•π•–°Ö¡¡±•çÖ—•ΩπΩπ—ï·–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•Ω	Ωë‰πçΩπ—Ö•πÃ†âMIY%}	==QMQIA}Ie|àÄ¨Äàÿ‘ƒÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•Ω	Ωë‰πçΩπ—Ö•πÃ†âMIY%}	==QMQIA}%1|àÄ¨Äàÿ‘ƒÿà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒŸÖ}…’π—•µï}ÕµΩ≠ï}…ï¡±ÖÂÕ}µÖ·}¡ï…Õ•Õ—ïë}°•Õ—Ω…Â}Öπë}…ï≈’•…ïÕ}±•Ÿï}Õ—Ö…–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕµΩ≠îÄÙÅ©ÖŸÑπ•ºπ•±î†à∏∏º∏∏Ωç§Ω…’π—•µîµ—ïÕ–πÕ†à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±}ïçΩπΩµ•ç}ïŸïπ—Õ|àÄ¨Äàÿ–‡ÿπ·µ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â…Öπùî†–¿‰ÿ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†âÕïïëïë}ïŸïπ—ÃÙ‡ƒ‰»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†âM}=U9Pà§ÄòòÅÕµΩ≠îπçΩπ—Ö•πÃ†àÙÄàÄ¨Äâpà‡ƒ‰…pàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â9=9%1}	==QMQIA}Ie|àÄ¨Äàÿ‘ƒ‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†âMIY%}	==QMQIA}Ie|àÄ¨Äàÿ‘ƒÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â¡•ëΩòÅçΩ¥π±•ôïçÂç±ïâΩ–πÖÖ—îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â9HÅ•∏ÅçΩ¥π±•ôïçÂç±ïâΩ–πÖÖ—îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†âA…ΩçïÕÃËÅçΩ¥π±•ôïçÂç±ïâΩ–πÖÖ—îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â9}1==@à§ÄòòÅÕµΩ≠îπçΩπ—Ö•πÃ†âAï…Õ•Õ—ïêÅU$ÅM—Ö…–ΩM—Ω¿ÅAMLà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒ›}Õ—Ö…—}Õ—Ω¡}•Õ}•µµïë•Ö—ï}Ÿ•Õ•â±ï}ë’…Öâ±ï}Öπë}çÖπçï±±Öâ±î†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅŸ¥ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω	Ω—Y•ï›5Ωëï∞π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅŸ•ï‹ÄÙÅµÖ•∏π•πëï·=ò†ââ—πQΩùù±îÄÄÄÄÄÄÄÙÅô•πëY•ï›	Â%ê°Hπ•êπâ—πQΩùù±î§à§(ÄÄÄÄÄÄÄÅŸÖ∞Å•µµïë•Ö—ï	•πêÄÙÅµÖ•∏π•πëï·=ò†ââ•πëI’π—•µïQΩùù±ï1•Õ—ïπï»àÄ¨Äàÿ‘ƒ‹†§à∞ÅŸ•ï‹§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïπëï…ï»ÄÙÅµÖ•∏π•πëï·=ò†â¡…•ŸÖ—îÅô’∏Å…ïπëï…I’π—•µï	Ö»†à§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ÿ•ï‹Ä¯Ä¿ÄòòÅ•µµïë•Ö—ï	•πêÄ¯ÅŸ•ï‹ÄòòÅ•µµïë•Ö—ï	•πêÄÅ…ïπëï…ï»§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°µÖ•∏πçΩπ—Ö•πÃ†ââ—πQΩùù±îπ•ÕπÖâ±ïêÄÙÅôÖ±Õîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ•∏πçΩπ—Ö•πÃ†âU%}IU9Q%5}Q=1}QA|àÄ¨Äàÿ‘ƒ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ•∏πçΩπ—Ö•πÃ†âÖπçï∞ÅM—Ö…–à§ÄòòÅµÖ•∏πçΩπ—Ö•πÃ†âMQIPÅ%1É
‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âÕï…Ÿ•çïM—Ö…—Iï≈’ïÕ—ïêàÄ¨Äàÿ‘ƒ‹πÕï–°—…’î§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âMQIQ}IEUMQ}IQ%9}UI%9}	==QMQIA|àÄ¨Äàÿ‘ƒ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âMIY%}	==QMQIA})=	}5%MM%9|àÄ¨Äàÿ‘ƒ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°âΩ–πçΩπ—Ö•πÃ†â›°•±îÄ†ÖÕï…Ÿ•çï	ΩΩ—Õ—…Ö¡IïÖë‰àÄ¨Äàÿ‘ƒÿ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âII}MQIQ}911}	e}MQ=A|àÄ¨Äàÿ‘ƒ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ÿ¥πçΩπ—Ö•πÃ†âU%}MQIQ}%MAQ!|àÄ¨Äàÿ‘ƒ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ÿ¥πçΩπ—Ö•πÃ†âU%}MQIQ}11	-}%MAQ!|àÄ¨Äàÿ‘ƒ‹à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕµΩ≠îÄÙÅ©ÖŸÑπ•ºπ•±î†à∏∏º∏∏Ωç§Ω…’π—•µîµ—ïÕ–πÕ†à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïçï•Ÿï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMµΩ≠ïQïÕ—Iïçï•Ÿï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïçï•Ÿï»πçΩπ—Ö•πÃ†âÕ—Ö…—}Õï…Ÿ•çîà§ÄòòÅ…ïçï•Ÿï»πçΩπ—Ö•πÃ†âM5=-}U%}MQUA}=91e|àÄ¨Äàÿ‘ƒ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âëïâ’úÅÕµΩ≠îÅÕï—’¿Åµ’Õ–Å°•–Åë•Õ¨ÅâïôΩ…îÅôΩ…çîµÕ—Ω¡¡•πúÅ•—ÃÅÖ’—†Å—ÖÕ¨à∞Å…ïçï•Ÿï»πçΩπ—Ö•πÃ†àπçΩµµ•–†§à§ÄòòÅÕµΩ≠îπçΩπ—Ö•πÃ†âÖ¥ÅôΩ…çîµÕ—Ω¿ÅçΩ¥π±•ôïçÂç±ïâΩ–πÖÖ—îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†à¥µïËÅÕ—Ö…—}Õï…Ÿ•çîÅôÖ±Õîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â’•}—Ö¿Å•êÅâ—πQΩùù±îÅ’•}Õ—Ö…—|ƒπ·µ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â’•}—Ö¿Å—ï·–ÄàÄ¨ÄâpâM—Ω¿ÅâΩ—pàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â’•}—Ö¿Å•êÅâ—πQΩùù±îÅ’•}Õ—Ö…—|»π·µ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â9}U%}Q@à§ÄòòÅÕµΩ≠îπçΩπ—Ö•πÃ†â9}U%}MQIPà§ÄòòÅÕµΩ≠îπçΩπ—Ö•πÃ†â9}U%}MQ=@à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ƒ·}ï·ïç’—Ω…}±•Ÿï	’Â}›•ëï}…ïù•Õ—ï…}Ö…•—°µï—•ç}•Õ}•π}≠ï¡—}°ï±¡ï»†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å°ï±¡ï»ÄÙÅï·ïç’—Ω»π•πëï·=ò†â¡…•ŸÖ—îÅô’∏ÅëπÖA…ΩŸïπ]•ππï…M•Èï	ΩΩÕ–àÄ¨Äàÿ‘ƒ‡à§(ÄÄÄÄÄÄÄÅŸÖ∞Å±•ŸîÄÙÅï·ïç’—Ω»π•πëï·=ò†â¡…•ŸÖ—îÅô’∏Å±•Ÿï	’‰†à∞Å°ï±¡ï»§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖ±∞ÄÙÅï·ïç’—Ω»π•πëï·=ò†âëπÖA…ΩŸïπ]•ππï…M•Èï	ΩΩÕ–àÄ¨Äàÿ‘ƒ‡°—Ã∞Å±ÖÂï…QÖú§à∞Å±•Ÿî§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°°ï±¡ï»Ä¯Ä¿ÄòòÅ±•ŸîÄ¯Å°ï±¡ï»ÄòòÅçÖ±∞Ä¯Å±•Ÿî§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πÕ’âÕ—…•πú°°ï±¡ï»Ä¥Äƒ¿¿∞Å°ï±¡ï»§πçΩπ—Ö•πÃ†âÖπë…Ω•ë‡πÖππΩ—Ö—•Ω∏π-ïï¿à§§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·—…Öç—ïë!ï±¡ï»ÄÙÅï·ïç’—Ω»πÕ’âÕ—…•πú°°ï±¡ï»∞Å±•Ÿî§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·—…Öç—ïë!ï±¡ï»πçΩπ—Ö•πÃ†â1•Ÿï]•π9M—Ω…îπÕï—’¡…ï≈’ïπç‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·—…Öç—ïë!ï±¡ï»πçΩπ—Ö•πÃ†à°ÖŸù]•∏Ä¥Ä»¿∏¿§ÄºÄƒ¿¿∏¿à§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕµΩ≠îÄÙÅ©ÖŸÑπ•ºπ•±î†à∏∏º∏∏Ωç§Ω…’π—•µîµ—ïÕ–πÕ†à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†â9}YI%e}II=Hà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÕµΩ≠îπçΩπ—Ö•πÃ†âYï…•ô•ï»Å…ï©ïç—ïêà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘»¡}…Ö›}≈’Öπ—•—Â}•Õ}çÖπΩπ•çÖ±}ïπë}—Ω}ïπê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩëï∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩëÖ—ÑΩ5Ωëï±Ãπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å°•Õ—Ω…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQ…Öëï!•Õ—Ω…ÂM—Ω…îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—ï…µ•πÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—°Ω…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±IÖ›E’Öπ—•—Â’—°Ω…•—‰ÿ‘»¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µΩëï∞πçΩπ—Ö•πÃ†âŸÖ∞Åïπ—…ÂIÖ›E—‰ËÅ	•ù%π—ïùï»à§ÄòòÅµΩëï∞πçΩπ—Ö•πÃ†âŸÖ∞ÅçÖπΩπ•çÖ±ΩπÕ’µïëIÖ‹ËÅ	•ù%π—ïùï»à§ÄòòÅµΩëï∞πçΩπ—Ö•πÃ†âŸÖ∞Å…ïµÖ•π•πùIÖ›E—‰ËÅ	•ù%π—ïùï»à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â¡Ö¡ï…IÖ›…ΩµçΩπΩµ•çÃ†à§ÄòòÄÖï·ïç’—Ω»πçΩπ—Ö•πÃ†â©Ω’…πÖ±MΩ±ëIÖ‹°—…ÖëîπÕΩ±ëE—ÂQΩ≠ï∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±ΩπÕ’µïëIÖ‹ÄÙÅ…Ö›Yï…ë•ç–ÿ‘»¿ππΩ…µÖ±•ÈïëIÖ‹à§ÄòòÅ—ï…µ•πÖ∞πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±ΩπÕ’µïëIÖ‹ÄÙÅÕΩ±ëE—ÂIÖ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°°•Õ—Ω…‰πçΩπ—Ö•πÃ†âïπ—…Â}…Ö›}≈—‰ÅQaPà§ÄòòÅ°•Õ—Ω…‰πçΩπ—Ö•πÃ†â¡’–†àÄ¨ÄâpâçÖπΩπ•çÖ±}çΩπÕ’µïë}…Ö›pààÄ¨Äà∞Å–πçÖπΩπ•çÖ±ΩπÕ’µïëIÖ‹π—ΩM—…•πú†§§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ö’—°Ω…•—‰πçΩπ—Ö•πÃ†â1e}I=U9%9}AM%1=9}I\ËÅ	•ù%π—ïùï»ÄÙÅ	•ù%π—ïùï»π=9à§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†â%51}M1}5%M5Q à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°Ö’—°Ω…•—‰πçΩπ—Ö•πÃ†â	•ùïç•µÖ∞°ëΩ’â±î§à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘»≈}≈’Öπ—•—Â}•πŸÖ…•Öπ—}…ï¡Ö•…Õ}ô…Ωµ}çÖπΩπ•çÖ±}…Ö›}›•—°Ω’—}ôΩ…çï}ç±ΩÕî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—°Ω…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩE’Öπ—•—Â%πŸÖ…•Öπ—’—°Ω…•—‰ÿ‘¿¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å’§ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ö’—°Ω…•—‰πçΩπ—Ö•πÃ†â…ïµÖ•π•πùE—ÂIÖ‹π—Ω	•ùïç•µÖ∞†§πµΩŸïAΩ•π—1ïô–à§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†â…ïçΩπÕ—…’ç—…ΩµÖπΩπ•çÖ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°Ö’—°Ω…•—‰πçΩπ—Ö•πÃ†â]Ö±±ï—5ÖπÖùï»π±ÖÕ—-πΩ›πMΩ±A…•çîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°Ö’—°Ω…•—‰πçΩπ—Ö•πÃ†â!•Õ—Ω…•çÖ±çΩπΩµ•çE’Ö…Öπ—•πîÿ–‰ÿπ…ï¡Ω…—=…¡°Öπ1Ω–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âEU9Q%Qe}AI=)Q%=9}I=9MQIUQ}I=5}9=9%1}I]|ÿ‘»ƒà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âAΩÕ•—•ΩπAï…Õ•Õ—ïπçîπÕÖŸïAΩÕ•—•Ω∏°—Ã§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°âΩ–πçΩπ—Ö•πÃ†â…ï≈’ïÕ—Mï±∞°—ÃÄÙÅ—Ã∞Å…ïÖÕΩ∏ÄÙÄàÄ¨Äâpâ%9YI%9Q}EUI9Q%9|ÿ‘¿¡pàà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°’§πçΩπ—Ö•πÃ†âE’Öπ—•—Â%πŸÖ…•Öπ—’—°Ω…•—‰ÿ‘¿¿πç°ïç¨°—Ãπµ•π–∞Å¡ΩÃ§πΩ¨à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘»…}çÖπΩπ•çÖ±}≈’Öπ—•—Â}—ï…µ•πÖ±}çΩ’π—Õ}µÖ…≠Õ}Öπë}ÕπÖ¡Õ°Ω—}çΩπ—…Öç–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖµΩ’π–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±QΩ≠ïπµΩ’π–ÿ‘»»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ö±±ï–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨ΩMΩ±ÖπÖ]Ö±±ï–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡…ΩçïÕÕΩ»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA…ΩçïÕÕΩ…µΩ’π—A±Öππï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—ï…µ•πÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩQï…µ•πÖ±5’—Ö—•Ωπ’—°Ω…•—‰ÿ–ÿÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Qï…µ•πÖ±	…•ëùîÿ–ÿ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩ’π—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Q…ÖëïΩ’π—’—°Ω…•—‰ÿ‘»»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω5Ö…≠’—°Ω…•—Â%π—ïù…•—ÂÖ—îÿ–‰ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÖµΩ’π–πçΩπ—Ö•πÃ†âŸÖ∞Å…Ö‹ËÅ	•ù%π—ïùï»à§ÄòòÅÖµΩ’π–πçΩπ—Ö•πÃ†âô’∏Å’§†§ËÅ	•ùïç•µÖ∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°›Ö±±ï–πçΩπ—Ö•πÃ†âΩ¡—M—…•πú†àÄ¨ÄâpâÖµΩ’π—pààÄ¨Äà∞ÄàÄ¨ÄâpâpààÄ¨Äà§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°›Ö±±ï–πçΩπ—Ö•πÃ†â5Ö¿ÒM—…•πú∞ÅAÖ•»ÒΩ’â±î∞Å%π–¯¯à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°¡…ΩçïÕÕΩ»πçΩπ—Ö•πÃ†â	•ùïç•µÖ∞°…ï≈’ïÕ—ïëU•E—‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞Å≈—‰ÄÙÅïπ—…ÂMΩ∞ÄºÅïπ—…ÂA…•çîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞Å°ïÖ±ïëE—‰ÄÙÄ°¡ΩÃπçΩÕ—MΩ∞Ä®Å¡…•çï5ΩŸï5’±—•¡±î§ÄºÅÖç—’Ö±A…•çîà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ÖµΩ’π–πçΩπ—Ö•πÃ†âU11}1=M}9=Q}aQ}I5%9Hà§ÄòòÅÖµΩ’π–πçΩπ—Ö•πÃ†âEQe}%51}M-\à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°—ï…µ•πÖ∞πçΩπ—Ö•πÃ†àêàÄ¨ÄâÌµΩëîπ±Ω›ï…çÖÕî†•ıêàÄ¨Äâ¡ΩÕ•—•Ωπ%ëêàÄ¨Äâùïπï…Ö—•ΩπêàÄ¨Äâç±ΩÕïQÂ¡îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°—ï…µ•πÖ∞πçΩπ—Ö•πÃ†àêàÄ¨ÄâÌ…’π%êπùï–†•ıà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ö¡ï»πçΩπ—Ö•πÃ†â9=9%1}EQe|êàÄ¨ÄâÌ≈—ÂYÖ±•ëÖ—•Ω∏ÿ‘»»π…ïÖÕΩπÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩ’π—ÃπçΩπ—Ö•πÃ†âÕïÕÕ•ΩπΩµ¡±ï—ïëQ…ÖëïÃà§ÄòòÅçΩ’π—ÃπçΩπ—Ö•πÃ†â±•ôï—•µïΩµ¡±ï—ïëQ…ÖëïÃà§ÄòòÅçΩ’π—ÃπçΩπ—Ö•πÃ†âΩ¡ïπQ…ÖëïÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ï¡Ω…–πçΩπ—Ö•πÃ†âŸÖ∞Å…ïŸ•Õ•Ω∏ÿ‘»»ÄÙÅ…ï¡Ω…—IïŸ•Õ•Ω∏ÿ‘»»π•πç…ïµïπ—πëï–†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ï¡Ω…–πçΩπ—Ö•πÃ†âMïÕÕ•Ω∏ÅçΩµ¡±ï—ïêÅ—…ÖëïÃËà§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â1•ôï—•µîÅçΩµ¡±ï—ïêÅ—…ÖëïÃËà§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â=¡ï∏Å¡ΩÕ•—•ΩπÃËà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ÃπçΩπ—Ö•πÃ†âŸÖ∞Å¡…•çïYÖ±•ë•—‰à§ÄòòÅµÖ…≠ÃπçΩπ—Ö•πÃ†âŸÖ∞Å±•≈’•ë•—ÂYÖ±•ë•—‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ÃπçΩπ—Ö•πÃ†àÖ¡ΩΩ±ëë…ïÕÃπÕ—Ö…—Õ]•—††àÄ¨Äâpâ5%9Q}I=UQÈpàà§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÃÕ}ï·ïç’—•Ωπ}Ö’—°Ω…•—Â}•Õ}çÖ’ÕÖ±}âΩ’πëïë}Öπë}ç…ΩÕÕ}’π•Ÿï…Õï}ÕÖôî†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ω±•ç‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—•Ωπ’—°Ω…•—ÂAΩ±•ç‰ÿ‘ÃÃπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡±Ö∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩQ…Öëï…I’π—•µïA±Ö∏ÿ‘»ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ω±•ç‰πçΩπ—Ö•πÃ†âô’∏Å•ÕQ…’π≠1Öπîà§ÄòòÅ¡Ω±•ç‰πçΩπ—Ö•πÃ†âô’∏ÅÕï±ïç—=πïIïÕç’îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âXÕ}9=9%1}!9=}A9%9|ÿ‘ÃÃà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â…ïçΩ…ëëùπëï—%π—ïπ–ÿ‘ÃÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â•πÖ±ïç•Õ•ΩπÖ—îπïŸÖ±’Ö—î†à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âŸÖ∞ÅÿÕ——ïµ¡—%êÄÙÅÿÕ%π—ïπ–ÿ‘ÃÃπÖ——ïµ¡—%êà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°âΩ–πçΩπ—Ö•πÃ†âXÕ}=I}M!=]}aUQ}Y%M%	%1%Qe|ÿ–‡‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âXÕ}	Ue}I)Q}9=}aQ}%9Q9Q|ÿ‘ÃÃà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÿÕ	’Â…ïÑÿ‘ÃÃÄÙÅï·ïç’—Ω»πÕ’âÕ—…•πùô—ï»†âô’∏ÅÿÕ	’‰†à§πÕ’âÕ—…•πù	ïôΩ…î†âq∏ÄÄÄÅô’∏Äà§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ÿÕ	’Â…ïÑÿ‘ÃÃπçΩπ—Ö•πÃ†â…ïçïπ—±±Ω›ïë——ïµ¡—%ëπÂ1Öπî°—Ãπµ•π–§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â…ï≈’•…ïÕMΩ±ÖπÖQΩ≠ïπ5Ö¿à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âÖ±±Ω›Q…’π≠·ïç’—•Ωπ!ÖπëΩôòÿ‘ÃÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â•π—ïπ–πôëùYï…ë•ç–π’¡¡ï…çÖÕî†§Å•∏ÅÕï—=ò†à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âAI=	}=91dà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}M!=IQ}II=UQ}Q=}AIA|ÿ‘ÃÃà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âAQI}%IQ%=9}U9MUAA=IQà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ç…Â¡—ºπçΩπ—Ö•πÃ†âMA=Q}M!=IQ}U9MUAA=IQà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†â…ï≈’•…ïÕMΩ±ÖπÖQΩ≠ïπ5Ö¿ÄÙÅ·ïç’—•Ωπ’—°Ω…•—ÂAΩ±•ç‰ÿ‘ÃÃπ…ï≈’•…ïÕMΩ±ÖπÖQΩ≠ïπ5Ö¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡±Ö∏πçΩπ—Ö•πÃ†â¡Ö¡ï…1ïÖ…πŸï…Â—°•πúÿ‘ÃÃà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â¡’â±•Õ†°¡±Ö∏ÿ‘»ÿπïπÖâ±ïëQ…Öëï…Mï–†§§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ï¡Ω…–πçΩπ—Ö•πÃ†âaUQ%=8ÅUQ!=I%QdÅ%9YI%9QLÄÿ‘ÃÃà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â9=9}M=19}Q=-95A}!I9<à§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âaUQ	1}9=UQ}AI}9%Q}Q|»à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘–—}ï·•Õ—•πù}Ö’—°Ω…•—•ïÕ}¡…ΩŸï}µ’±—•ç°Ö•π}ë•ÕçΩŸï…Â}›•—°Ω’—}¡Ö…Ö±±ï±}Ö…ç°•—ïç—’…î†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åëï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωπï—›Ω…¨Ωï·Õç…ïïπï…¡§π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïÕΩ±Ÿï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩ…Â¡—ΩUπ•Ÿï…ÕïIΩ’—ïIïÕΩ±Ÿï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩç…Â¡—ºΩ…Â¡—ΩUπ•Ÿï…Õï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩµµΩë•—•ïÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩΩµµΩë•—•ïÕQ…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ï—ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5Ö…≠ï—Õ1•Ÿï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Ω≠ïπ•ÈïêÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëÕÕï—Iïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï…·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—≈’Ö±Ã†ââÖÕï¡·Öâåà∞ÅçΩ¥π±•ôïçÂç±ïâΩ–π¡ï…¡ÃπÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰πçÖπΩπ•çÖ±%ëïπ—•—‰ÿ‘––†ââÖÕîà∞Äà¡·Öâåà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—≈’Ö±Ã†â’π≠πΩ›π¡·Öâåà∞ÅçΩ¥π±•ôïçÂç±ïâΩ–π¡ï…¡ÃπÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰πçÖπΩπ•çÖ±%ëïπ—•—‰ÿ‘––†àà∞Äà¡·Öâåà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïù•Õ—…‰πçΩπ—Ö•πÃ†âŸÖ∞Åç°Ö•π%êËÅM—…•πúÄÙÄàÄ¨ÄúàúÄ¨Äúàú§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âŸÖ∞ÅçÖπΩπ•çÖ±%ëïπ—•—‰ÿ‘––à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âŸÖ∞Å—Ω≠ïπëë…ïÕÃËÅM—…•πúÄÙÅµ•π–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ëï‡πçΩπ—Ö•πÃ†âŸÖ∞Åç°Ö•π%êËÅM—…•πúà§ÄòòÅëï‡πçΩπ—Ö•πÃ†âŸÖ∞Åëï·%êËÅM—…•πúà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅëï‡πçΩπ—Ö•πÃ†âŸÖ∞Å—Ω≠ïπëë…ïÕÃËÅM—…•πúà§ÄòòÅëï‡πçΩπ—Ö•πÃ†âŸÖ∞Å≈’Ω—ïëë…ïÕÃËÅM—…•πúà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅëï‡πçΩπ—Ö•πÃ†âŸÖ∞Å¡Ö•……ïÖ—ïë–ËÅ1Ωπúà§ÄòòÅëï‡πçΩπ—Ö•πÃ†â—Ω≠ï∏µ¡Ö•…ÃΩÿƒºàÄ¨ÄúêúÄ¨ÄâÌïπçΩëî°ç°Ö•π%ê•ÙºàÄ¨ÄúêúÄ¨ÄâÌïπçΩëî°—Ω≠ïπëë…ïÕÃ•Ùà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°…ïù•Õ—…‰πçΩπ—Ö•πÃ†â•òÄ°¡Ö•»πç°Ö•π%êÄÑÙÄàÄ¨ÄúàúÄ¨ÄâÕΩ±ÖπÑàÄ¨ÄúàúÄ¨Äà§ÅçΩπ—•π’îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïù•Õ—…‰πçΩπ—Ö•πÃ†âôï—ç°ïç≠ΩAΩΩ±Ãÿ‘––à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â!ïÖ±—°›Ö…ï!——¿πï·ïç’—î°°——¿∞Å…ïƒ∞Å°ΩÕ–§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âIM!}%M=YIe}5M|ÿ‘––à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âQ%Y}%M=YIe}5M|ÿ‘––à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âùï—	±ïπëïë=¡¡Ω…—’π•—ÂE’ï’îÿ‘––à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†âπΩπMΩ±ÖπÖ·¡±•ç•–ÿ‘––à§ÄòòÅ…ïÕΩ±Ÿï»πçΩπ—Ö•πÃ†â—Ö…ùï—°Ö•π%êÿ‘––à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â—Ö…ùï—°Ö•π%êÿ‘––ÄÙÅ—Ö…ùï—°Ö•π%êÿ‘––à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âùï—	±ïπëïë=¡¡Ω…—’π•—ÂE’ï’îÿ‘––†§à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âëÂπÕÕï—-ï‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅç…Â¡—ºπçΩπ—Ö•πÃ†â—Ö…ùï—°Ö•π%êÿ‘––ÄÙÅÕ•ùπÖ∞πëÂπ°Ö•π%êà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩµµΩë•—•ïÃπçΩπ—Ö•πÃ†âŸÖ∞ÅçΩµµΩë•—Â5Ö…≠ï—ÃÄÙÅAï…¡Õ5Ö…≠ï–πŸÖ±’ïÃ†§πô•±—ï»ÅÏÅ•–π•ÕΩµµΩë•—‰ÅÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅçΩµµΩë•—•ïÃπçΩπ—Ö•πÃ†â5Ö…≠ï—Õ1•Ÿï·ïç’—Ω»πï·ïç’—ï1•ŸïQ…ÖëïA…ΩΩòÿ–‡ÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ï—ÃπçΩπ—Ö•πÃ†âï·ïç’—ï1•ŸïQ…ÖëïA…ΩΩòÿ–‡ÿà§ÄòòÅ—Ω≠ïπ•ÈïêπçΩπ—Ö•πÃ†â°ÖÕIïÖ±IΩ’—îà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ï—ÃπçΩπ—Ö•πÃ†âX‘∏¿∏ÿ‘–‘ÉäPÅçÖπΩπ•çÖ∞Åâ…•ëùîÅ…Ö•∞à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âUπ•Ÿï…ÕÖ±	…•ëùïπù•πîπ¡…ï¡Ö…ïÖ¡•—Ö∞†à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ï—ÃπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ∞Åâ…•ëùîÅ…ï—’…πïêÅπºÅÕ›Ö¿ÅÕ•ùπÖ—’…îÏÅ…ïô’Õ•πúÅΩ¡ï∏à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÖµÖ…≠ï—ÃπÕ’âÕ—…•πùô—ï»†âX‘∏¿∏ÿ‘–‘ÉäPÅçÖπΩπ•çÖ∞Åâ…•ëùîÅ…Ö•∞à§πÕ’âÕ—…•πù	ïôΩ…î†â¡…•ŸÖ—îÅÕ’Õ¡ïπêÅô’∏Åï·ïç’—ï)’¡•—ï…M›Ö¿à§(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄπçΩπ—Ö•πÃ†â•π¡’—5•π–ÄÄÄÄÄÄÄÄÄÙÅM=1}5%9Pà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†ââÖç≠ù…Ω’πë1•ŸïπïÕÕMπÖ¡Õ°Ω–ÿ‘––à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â…ïçΩ…ë	Öç≠ù…Ω’πëA…Ωù…ïÕÃÿ‘––à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âëΩÈïŸ•ëïπçîÿ‘––à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â!IQ	Q}IMU}AI=IMM}Q%5=UQ|ÿ‘––à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â1=9}e1}9=Q}=i|ÿ‘––à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†ââÖ——ï…Â=¡—]°•—ï±•Õ—ïêÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ö¡ï…·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞ÅÕï±±ïπï…Ö—•Ω∏ÿ–‹–ÄÙÅçÖπΩπ•çÖ±Qï…µ•πÖ±AΩÕ•—•Ω∏ÿ–‰»πΩ¡ïπïë—5Ãà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÖ¡Ö¡ï…·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞ÅÕï±±ïπï…Ö—•Ω∏ÿ–‹–ÄÙÅ—…Öëï%êπ—…Öëï%êà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ï¡Ω…–πçΩπ—Ö•πÃ†â	Öç≠ù…Ω’πêÅI’π—•µîÅA…Ωù…ïÕÃÄ°X‘∏¿∏ÿ‘––§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â	}	=Q}1==A}Q%,à§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â	}M9}à§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â	}%9Q-à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â	}à§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â	}a%Pà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ï¡Ω…–πçΩπ—Ö•πÃ†â…Â¡—ºÅUπ•Ÿï…ÕîÅ•ÕçΩŸï…‰Ä°X‘∏¿∏ÿ‘––§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïù•Õ—…‰πçΩπ—Ö•πÃ†âπï—›Ω…≠ÃÅΩâÕï…ŸïêÙà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âaïÃÅΩâÕï…ŸïêÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âô…ïÕ†Å¡ΩΩ±ÃÅë•ÕçΩŸï…ïêÙà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â’π•≈’îÅç°Ö•∏≠—Ω≠ï∏Å•ëïπ—•—•ïÃÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âë•ÕçΩŸï…•ïÃÅâ‰Åç°Ö•∏Ùà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â¡ΩΩ∞ÅçΩ°Ω…—ÃÄ’¥Ùà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âô…ïÕ†Å…ïÖç°•πúÅ…Â¡—Ω	…Ö•∏Ùà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âô…ïÕ†Å…ïÖç°•πúÅXÃΩÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â¡Ö¡ï»µΩπ±‰Å’πÖŸÖ•±Öâ±îÅ±•ŸîÅ…Ω’—îÙà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†â±•Ÿîµ…Ω’—Öâ±îÅçÖπë•ëÖ—ïÃÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âÕ—Ö—•åµŸÃµëÂπÖµ•åÅïŸÖ±’Ö—•Ω∏ÅÕ°Ö…îÙà§§(ÄÄÄÄÄÄÄÅŸÖ∞Åùïπï…Ö—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5’±—•°Ö•π]Ö±±ï—ïπï…Ö—Ω»ÿ‘–ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅŸÖ’±–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ5’±—•°Ö•π]Ö±±ï—YÖ’±–ÿ‘–ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ö±±ï—5ÖπÖùï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ]Ö±±ï—5ÖπÖùï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùïπï…Ö—Ω»πçΩπ—Ö•πÃ†âM1%@¥¿¿ƒ¿à§ÄòòÅùïπï…Ö—Ω»πçΩπ—Ö•πÃ†à––ÅΩ»Å!I9à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùïπï…Ö—Ω»πçΩπ—Ö•πÃ†âï—°ï…ï’µëë…ïÕÃà§ÄòòÅùïπï…Ö—Ω»πçΩπ—Ö•πÃ†ââ•—çΩ•πëë…ïÕÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ŸÖ’±–πçΩπ—Ö•πÃ†âπç…Â¡—ïëM°Ö…ïëA…ïôï…ïπçïÃà§ÄòòÅŸÖ’±–πçΩπ—Ö•πÃ†âÕΩ±ÖπÖ}¡…•ŸÖ—ï}≠ïÂ}à‘‡à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ’±–πçΩπ—Ö•πÃ†â5U1Q%!%9}AU	1%}IMM}5AQdà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°›Ö±±ï—5ÖπÖùï»πçΩπ—Ö•πÃ†âùïπï…Ö—ï5’±—•°Ö•π]Ö±±ï–à§ÄòòÅ›Ö±±ï—5ÖπÖùï»πçΩπ—Ö•πÃ†â±ΩÖë5’±—•°Ö•π]Ö±±ï–à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ—}¡Ö¡ï…}’π•Ÿï…ÕïÕ}…ïÕ—Ω…ï}µïÖÕ’…Öâ±ï}°ÖπëΩôôÕ}—•ç≠ï—}—…’—°}›Ö±±ï—}—…’—°}Öπë}çÖ’ÕÖ±}±ïÖ…π•πú†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ωç≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡Õ·ïç’—•Ωππù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡Õ	…Ö•∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡ÕQ…Öëï…$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ï¡Ω…–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩπï‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIï¡Ω…—•πù!’àπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùΩŸï…πΩ»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩIïÖ±•Èïë]Ö±±ï—Ωµ¡Ω’πë•πùΩŸï…πΩ»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπ—…Öç–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±ÕÕï—π—…ÂΩπ—…Öç–ÿ‘‘ƒπ≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}M%91}M1Q|ÿ‘ÿÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅç…Â¡—ºπçΩπ—Ö•πÃ†âÕΩ’…çîıe95%}1Pà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âÕΩ’…çîıMQQ%}1Pà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅç…Â¡—ºπçΩπ—Ö•πÃ†â±ÖπîıIeAQ=}1PÅÕΩ’…çîı9=9%1}!9=|ÿ‘ÿÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅç…Â¡—ºπçΩπ—Ö•πÃ†â¡Ö—†ıIeAQ=}1PÅµΩëîÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†â5I-QM}MQ=-}M%91}M1Q|ÿ‘ÿÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅÕ—Ωç≠ÃπçΩπ—Ö•πÃ†â±Öπîı5I-QM}MQ=-LÅÕΩ’…çîı9=9%1}!9=|ÿ‘ÿÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âA!Mπà§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âÕïÖ±ïêı—…’îÅÖÕÕï—±ÖÕÃÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…¡ÃπçΩπ—Ö•πÃ†â±ÖπîıAIALÅÕΩ’…çîÙà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ¡ï…¡ÃπçΩπ—Ö•πÃ†â1Öπï·ïç’—•ΩπΩΩ…ë•πÖ—Ω»πçÖπë•ëÖ—ïYï…Õ•ΩπΩ»°Õ•ùπÖ∞πµÖ…≠ï–πÕÂµâΩ∞§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°¡ï…¡ÃπçΩπ—Ö•πÃ†âçÖπë•ëÖ—ïYï…Õ•Ω∏ÄÙÅMÂÕ—ï¥πç’……ïπ—Q•µï5•±±•Ã†§à§§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âŸÖ∞Å—•ç≠ï—’—°Ω…•—‰ÿ‘ÿ–ÄÙÅ…ïÕΩ±ŸïMïÖ±ïë%π—ïπ–ÿÿƒÃ†à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†â—•ç≠ï—’—°Ω…•—‰ÿ‘ÿ–¸πôëù±±Ω›ïêà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†â—•ç≠ï—’—°Ω…•—‰ÿ‘ÿ–¸πôëùYï…ë•ç–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†â—•ç≠ï—’—°Ω…•—‰ÿ‘ÿ–ÄÙÙÅπ’±∞à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†â1%Y}aUQ%=9}Q%-Q}QQ1}5LÄÙÄ–’|¿¿¡0à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†âAAI}aUQ%=9}Q%-Q}QQ1}5LÄÙÄƒ‡¡|¿¿¡0à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅùÖ—îπçΩπ—Ö•πÃ†â—•ç≠ï–πµΩëîπï≈’Ö±Ã°pâAAIpà∞Å—…’î§à§§((ÄÄÄÄÄÄÄÄººÅX‘∏¿∏ÿÿ¿–É
ùAAI}1I}I}U9%%Q%=8ÉäPÅ—°îÅ…ï¡Ω…—•πúΩùΩŸï…πΩ»(ÄÄÄÄÄÄÄÄººÅÕ’…ôÖçïÃÅµÖ‰ÅπΩ‹Å…ïÖêÅô…Ω¥Å—°îÅçÖπΩπ•çÖ∞ÅôÖçÖëî(ÄÄÄÄÄÄÄÄººÄ°AÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹§Å›°•ç†Å•ÃÅÑÅIµ=91dÅëï±ïùÖ—•Ω∏Å—º(ÄÄÄÄÄÄÄÄººÅ—°îÅÕÖµîÅAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿ÅÖ’—°Ω…•—‰∏Å	Ω—†Å…ïôï…ïπçïÃÅÖ…î(ÄÄÄÄÄÄÄÄººÅÖççï¡—Öâ±îÉäPÅ—°îÅΩ¡ï…Ö—Ω»ùÃÅçΩπŸï…ùïπçîÅùΩÖ∞Å•ÃÅÖπ‰Å…ïÖêÅ¡Ö—†(ÄÄÄÄÄÄÄÄººÅ—°Ö–Å’±—•µÖ—ï±‰Å…ïÕΩ±ŸïÃÅ—ºÅ—°îÅÕ•πù±îÅ±ïëùï»∏(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†°µΩπï‰πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿π•Õ’—°Ω…•—Â%π•—•Ö±•Èïêÿ–‡‰†§à§ÅÒ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµΩπï‰πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹π•Õ’—°Ω…•—Â%π•—•Ö±•Èïêÿ–‡‰†§à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄ°µΩπï‰πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πçÖÕ°MΩ∞†§à§ÅÒ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅµΩπï‰πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹πçÖÕ°MΩ∞†§à§§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†°ùΩŸï…πΩ»πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿π•Õ’—°Ω…•—Â%π•—•Ö±•Èïêÿ–‡‰†§à§ÅÒ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅùΩŸï…πΩ»πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹π•Õ’—°Ω…•—Â%π•—•Ö±•Èïêÿ–‡‰†§à§§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄ°ùΩŸï…πΩ»πçΩπ—Ö•πÃ†âAÖ¡ï…ççΩ’π—1ïëùï»ÿ–Ã¿πçÖÕ°MΩ∞†§à§ÅÒ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅùΩŸï…πΩ»πçΩπ—Ö•πÃ†âAÖ¡ï…Ö¡•—Ö±’—°Ω…•—‰ÿ‘‹‹πçÖÕ°MΩ∞†§à§§§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ï¡Ω…–πçΩπ—Ö•πÃ†â’π•≈’îÅ•π—Ö≠îÅÕÂµâΩ±ÃËà§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â’π•≈’îÅ•π—Ö≠îÉäHÅXÃËà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ï¡Ω…–πçΩπ—Ö•πÃ†â¡…îµXÃÅ…ï—’…πÃËà§ÄòòÅ…ï¡Ω…–πçΩπ—Ö•πÃ†âAI}XÕ}IQUI9|à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ—Ωç≠ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±A’â±•Õ°!ï±¡ï»π¡’â±•Õ°·•–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅÕ—Ωç≠ÃπçΩπ—Ö•πÃ†âïπ—…ÂAÖ——ï…∏ÄÙÅpâMQ=-|à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…¡Õ	…Ö•∏πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±A’â±•Õ°!ï±¡ï»π¡’â±•Õ°·•–à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ¡ï…¡Õ	…Ö•∏πçΩπ—Ö•πÃ†âïπ—…ÂAÖ——ï…∏ÄÙÅpâAIAM|à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ’}Ö±±}ç…ΩÕÕ}ÖÕÕï—}¡Ö¡ï…}Ω¡ïπÕ}çÖ……Â}Ωπï}çΩµ¡Ö—•â±ï}çÖπΩπ•çÖ±}•π—ïπ–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπ—…Öç–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±ÕÕï—π—…ÂΩπ—…Öç–ÿ‘‘ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡ÕQ…Öëï…$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôΩ…ï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩΩ…ï·Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï—Ö±ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5ï—Ö±ÕQ…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩµµΩë•—•ïÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩΩµµΩë•—•ïÕQ…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩπ—…Öç–πçΩπ—Ö•πÃ†âçÖπë•ëÖ—îπµΩëîπï≈’Ö±Ã°pâ1%Ypà∞Å—…’î§ÄòòÅçÖπë•ëÖ—îπë•…ïç—•Ω∏πï≈’Ö±Ã°pâM!=IQpà∞Å—…’î§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩπ—…Öç–πçΩπ—Ö•πÃ†âUAMQI5}%9Q9Q}=91%Pà§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†â…ïù•Õ—ï…ïêπ…ïÕΩ±ŸïëM•ÈîÄ¥ÅÕ•È•πúπô•πÖ±M•ÈïMΩ∞à§§(ÄÄÄÄÄÄÄÅ±•Õ—=ò°ç…Â¡—º∞Å¡ï…¡Ã∞ÅôΩ…ï‡∞Åµï—Ö±Ã∞ÅçΩµµΩë•—•ïÃ§πôΩ…Öç†ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπÕ’âµ•–à§§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•–πçΩπ—Ö•πÃ†âï·ïç’—•Ωπ%π—ïπ–ÄÙÄà§§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…¡ÃπçΩπ—Ö•πÃ†âï·ïç’—•Ωπ%π—ïπ–ÿ‘ÿ‘à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±…Â¡—Ω%π—ïπ–ÿ‘ÿ‘à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿŸ}¡Ö…—•Ö±Õ}Ö…ï}Õï≈’ïπçï}•ëïµ¡Ω—ïπ—}çÖπΩπ•çÖ±}Öπë}πΩπ—ï…µ•πÖ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö…—•Ö∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…AÖ…—•Ö±=¡ï…Ö—•Ω∏ÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïë’çï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ—Ωç¨ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôΩ…ï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩΩ…ï·Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï—Ö±ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5ï—Ö±ÕQ…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩµµΩë•—•ïÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩΩµµΩë•—•ïÕQ…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ö…—•Ö∞πçΩπ—Ö•πÃ†â…ï≈’ïÕ—Mï≈’ïπçïÃπçΩµ¡’—ï%ôâÕïπ–à§ÄòòÅ¡Ö…—•Ö∞πçΩπ—Ö•πÃ†âÕïƒπ•πç…ïµïπ—πëï–†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°¡Ö…—•Ö∞πçΩπ—Ö•πÃ†âÖâÃ°ï·•—IïÖÕΩ∏π°ÖÕ°Ωëî†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïë’çï»πçΩπ—Ö•πÃ†âô’∏Å¡Ö…—•Ö∞†à§ÄòòÅ…ïë’çï»πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…AÖ…—•Ö±=¡ï…Ö—•Ω∏ÿ‘ƒ¿πçΩµµ•–à§§(ÄÄÄÄÄÄÄÅ±•Õ—=ò°Õ—Ωç¨∞ÅôΩ…ï‡∞Åµï—Ö±Ã∞ÅçΩµµΩë•—•ïÃ∞Åç…Â¡—º§πôΩ…Öç†ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•–πçΩπ—Ö•πÃ†â¡Ö…—•Ö±AΩÕ•—•Ω∏ÿ‘ÿÿà§§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ¡Ö…—•Ö∞à§§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•–πçΩπ—Ö•πÃ†âç—•Ω∏πAIQ%0Ä¥˘q∏ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅç±ΩÕïAΩÕ•—•Ω∏à§§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÅÖÕÕï…—≈’Ö±Ã†»∞ÅIïùï‡†â±’•ë1ïÖ…π•πùqpπ…ïçΩ…ëAÖ¡ï…Mï±±qp†à§πô•πë±∞°Õ—Ωç¨§πçΩ’π–†§§ÄººÅÕ°’—ëΩ›∏Ä¨ÅΩπîÅπΩ…µÖ∞Å—ï…µ•πÖ∞Å¡Ö—†(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿŸ}µïµï}¡Ö…—•Ö±}Öπë}çÂç±•ç}ç±ΩÕï}Õ—Ö—ï}…ï≈’•…ï}Ö¡¡±•ïë}ô•πÖ±•—‰†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµΩΩ∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩÿÃΩÕçΩ…•πúΩ5ΩΩπÕ°Ω—Q…Öëï…$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÂç±•åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÂç±•çQ…Öëïπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âëÖ—ÑÅç±ÖÕÃÅAÖ…—•Ö±Mï±±Iïçï•¡–ÿ‘ÿÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âô’∏Å…ï≈’ïÕ—AÖ…—•Ö±Mï±±Ωπô•…µïêÿ‘ÿÿ†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â¡Ö…—•Ö∞ÿ‘ƒ¿πΩ¡ï…Ö—•Ωπ%êà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â1%Y}AIQ%1}=9%I5à§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†âô•πÖ±M•úà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—≈’Ö±Ã†ƒ¿∞ÅIïùï‡†â…ï≈’ïÕ—AÖ…—•Ö±Mï±±Ωπô•…µïêÿ‘ÿŸqp†à§πô•πë±∞°âΩ–§πçΩ’π–†§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†âÖ±∞ÅΩ¡—•µ•Õ—•åÅ…’πúΩç°’π¨Åµ’—Ö—•ΩπÃÅµ’Õ–ÅâîÅ…ïçï•¡–µùÖ—ïêà∞(ÄÄÄÄÄÄÄÄÄÄÄÅIïùï‡†â¡Ö…—•Ö±Iïçï•¡–ÿ‘ÿŸqpπÖ¡¡±•ïêà§πô•πë±∞°âΩ–§πçΩ’π–†§Ä¯ÙÄƒ¿§(ÄÄÄÄÄÄÄÅŸÖ∞Åç°ïç≠·•—M—Ö…–ÿ‘ÿÿÄÙÅµΩΩ∏π•πëï·=ò†âô’∏Åç°ïç≠·•–†à§(ÄÄÄÄÄÄÄÅŸÖ∞Åç°ïç≠·•–ÄÙÅµΩΩ∏πÕ’âÕ—…•πú°ç°ïç≠·•—M—Ö…–ÿ‘ÿÿ∞ÅµΩΩ∏π•πëï·=ò†â¡…•ŸÖ—îÅô’∏Å’¡ëÖ—ï1ïÖ…π•πú†à∞Åç°ïç≠·•—M—Ö…–ÿ‘ÿÿ§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ç°ïç≠·•–πçΩπ—Ö•πÃ†â¡Ö…—•Ö±I’πùÕQÖ≠ï∏Ä¨ÙÄƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç°ïç≠·•–πçΩπ—Ö•πÃ†âŸÖ∞Å¡…Ω¡ΩÕïëI’πúÄÙÅ¡ΩÃπ¡Ö…—•Ö±I’πùÕQÖ≠ï∏Ä¨Äƒà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅΩπAÖ…—•Ö∞ÄÙÅµΩΩ∏πÕ’âÕ—…•πú°µΩΩ∏π•πëï·=ò†âô’∏ÅΩπAÖ…—•Ö±Mï±∞†à§∞ÅµΩΩ∏π•πëï·=ò†âô’∏Åùï—ç—•ŸïAΩÕ•—•ΩπÃ†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ΩπAÖ…—•Ö∞πçΩπ—Ö•πÃ†â¡Ö…—•Ö±I’πùÕQÖ≠ï∏ÄÙÄ°¡ΩÃπ¡Ö…—•Ö±I’πùÕQÖ≠ï∏Ä¨Äƒ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî†â—•ç¨Åµ’Õ–ÅπïŸï»Å…ïÕ’……ïç–Åë•ÕÖâ±ïêÅÂç±•åÅÖ’—°Ω…•—‰à∞ÅçÂç±•åπçΩπ—Ö•πÃ†â•òÄ†ÖïπÖâ±ïêπùï–†§§ÅÏÅïπÖâ±ïêπÕï–°—…’î§ÅÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çÂç±•åπçΩπ—Ö•πÃ†âe1%}Q%-}M-%AA}%M	1|ÿ‘ÿÿà§§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ΩÕïÂç±îÄÙÅçÂç±•åπÕ’âÕ—…•πú°çÂç±•åπ•πëï·=ò†â¡…•ŸÖ—îÅô’∏Åç±ΩÕïÂç±î†à§∞ÅçÂç±•åπ•πëï·=ò†â¡…•ŸÖ—îÅô’∏Åç±ïÖ…1ΩçÖ±Âç±ïM—Ö—îÿ‘ÿÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç±ΩÕïÂç±îπ•πëï·=ò†â›°ï∏Ä°Õï±±IïÕ’±–ÿ‘ÿÿ§à§ÄÅç±ΩÕïÂç±îπ•πëï·=ò†àººÅU¡ëÖ—îÅ…•πúÅΩπ±‰ÅÖô—ï»ÅçΩπô•…µïêÅÕï±∞Åô•πÖ±•—‰∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç±ΩÕïÂç±îπçΩπ—Ö•πÃ†âe1%}M11}9=Q}%91|ÿ‘ÿÿà§ÄòòÅç±ΩÕïÂç±îπçΩπ—Ö•πÃ†â…ï—Ö•π}¡ΩÕ•—•Ωπ}πΩ}±ïÖ…π•πúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç±ΩÕïÂç±îπçΩπ—Ö•πÃ†â·ïç’—Ω»πMï±±IïÕ’±–π=9%I5à§ÄòòÅç±ΩÕïÂç±îπçΩπ—Ö•πÃ†â·ïç’—Ω»πMï±±IïÕ’±–πAAI}=9%I5à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿŸ}ç…Â¡—Ω}µÖ…≠ï—Õ}°ÖπëΩôôÕ}Öπë}µïµï}ëïë’¡ï}¡…ïÕï…Ÿï}ï·ïç’—Öâ±ï}•π—Ö≠î†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•±ïÃÄÙÅ±•Õ—=ò†(ÄÄÄÄÄÄÄÄÄÄÄÄâ…Â¡—Ω±—Q…Öëï»π≠–àÅ—ºÄâIeAQ=}1Pà∞(ÄÄÄÄÄÄÄÄÄÄÄÄâQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–àÅ—ºÄâ5I-QM}MQ=-Là∞(ÄÄÄÄÄÄÄÄÄÄÄÄâΩ…ï·Q…Öëï»π≠–àÅ—ºÄâ5I-QM}=I`à∞(ÄÄÄÄÄÄÄÄÄÄÄÄâ5ï—Ö±ÕQ…Öëï»π≠–àÅ—ºÄâ5I-QM}5Q1Là∞(ÄÄÄÄÄÄÄÄÄÄÄÄâΩµµΩë•—•ïÕQ…Öëï»π≠–àÅ—ºÄâ5I-QM}=55=%Q%Là∞(ÄÄÄÄÄÄÄÄ§(ÄÄÄÄÄÄÄÅô•±ïÃπôΩ…Öç†ÅÏÄ°πÖµî∞Å±Öπî§Ä¥¯(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅÕ…åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃºëπÖµîà§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞Å°ÖπëΩôòÄÙÅÕ…åπ•πëï·=ò†â±ÖπîÙë±ÖπîÅÕΩ’…çîı9=9%1}!9=|ÿ‘ÿÿà§(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅÕ’âµ•–ÄÙÅÕ…åπ•πëï·=ò†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπÕ’âµ•–à∞Å°ÖπëΩôò§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àë±ÖπîÅµ’Õ–Åïµ•–ÅÖç—•ŸîÅ±ÖπîµïŸÖ∞Å•µµïë•Ö—ï±‰ÅâïôΩ…îÅçÖπΩπ•çÖ∞ÅÅÕ’âµ•–à∞Å°ÖπëΩôòÄ¯ÙÄ¿ÄòòÅÕ’âµ•–Ä¯Å°ÖπëΩôòÄòòÅÕ’âµ•–Ä¥Å°ÖπëΩôòÄÄƒ»¿¿§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—≈’Ö±Ã†àë±ÖπîÅµ’Õ–Å°ÖŸîÅΩπîÅçÖπΩπ•çÖ∞ÅÖç—•ŸîÅ°ÖπëΩôòÅµÖ…≠ï»à∞Äƒ∞ÅIïùï‡†â±ÖπîÙë±ÖπîÅÕΩ’…çîı9=9%1}!9=|ÿ‘ÿÿà§πô•πë±∞°Õ…å§πçΩ’π–†§§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â•π—Ö≠ïMïïπMΩ’…çïÕ	Â5•π–ÿ‘ÿÿà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âπï›MΩ’…çïŸ•ëïπçîÿ‘ÿÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â±ΩâÖ±Q…ÖëïIïù•Õ—…‰π’¡ëÖ—ïA…ΩâÖ—•ΩπMçÖππï»°µ•π–∞ÅÕΩ’…çî§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â±ΩâÖ±Q…ÖëïIïù•Õ—…‰πµï…ùïôô•π•—‰°µ•π–∞Å±ÖπïÃÿ‘ÿÿ∞Å—ΩΩ±Ãÿ‘ÿÿ§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âMçÖππï…!Âë…Ö—•ΩπE’ï’ïÃÿÃ–‹π	’ç≠ï–π1%Y}Idà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â55}UA}IIM!|ÿ‘ÿÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â55}%9Q-}UA}Y%9}IIM!|ÿ‘ÿÿà§§(ÄÄÄÅÙ((((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ›}≈’Öπ—•—Â}Õ•È•πù}ô•πÖ±•—Â}ô’ππï±}±ïÖ…π•πù}°ïÖ±—°}Öπë}’•}Ö…ï}çÖπΩπ•çÖ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•È•πúÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω=…ëï…M•ÈïIïÕΩ±Ÿï»ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•È•πù	…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±M•È•πù	…•ëùîÿ‘Ã»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÂç±•åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÂç±•çQ…Öëïπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—°Ω…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘–¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åïπ—…ÂMπÖ¡Õ°Ω–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ωπ—…ÂM—…Ö—ïùÂMπÖ¡Õ°Ω–ÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•πÖ±•ÈïêÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±•πÖ±•ÈïëQ…Öëï	’Ãÿ–ÿ–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Öç—•åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ïÖ…π•πúΩQÖç—•çM›•—ç°ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç±ÖÕÕ•ô•ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩIΩΩ—Ö’Õï±ÖÕÕ•ô•ï»ÿ–‹ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å°ïÖ±—†ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩA•¡ï±•πï!ïÖ±—°Ω±±ïç—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ•∏ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω’§Ω5Ö•πç—•Ÿ•—‰π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±Qï…µ•πÖ±AΩÕ•—•Ω∏ÿ–‰»π≈’Öπ—•—ÂMçÖ±îπçΩï…çï%∏†¿∞Äƒ‡§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±Qï…µ•πÖ±AΩÕ•—•Ω∏ÿ–‰»π—Ω≠ïπïç•µÖ±Ãπ—Ö≠ï%òà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ•È•πúπçΩπ—Ö•πÃ†âÖ¡¡±ÂAÖ¡ï…5ïµï5•π•µ’¥à§ÄòòÅÕ•È•πù	…•ëùîπçΩπ—Ö•πÃ†âÖ¡¡±ÂAÖ¡ï…5ïµï5•π•µ’¥ÄÙÅÖÕÕï—±ÖÕÃÄÙÙÅÕÕï—±ÖÕÃπM=19}Q=-8à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ•È•πúπçΩπ—Ö•πÃ†âŸÖ∞Åïôôïç—•ŸïM°Ö¡ïë1Öµ¡Ω…—Ãÿ‘¿ÿÄÙÅ±Öπï±Öµ¡ïë1Öµ¡Ω…—Ãÿ–‰ƒà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°Õ•È•πúπçΩπ—Ö•πÃ†â=II}M%i}AI=5=Q}Q=}5%9}aUQ	1|ÿ‘¿ÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âŸÖ∞Åô±ΩΩ…A…ΩµΩ—•ΩπIï≈’ïÕ—ïêÿ‘ƒƒÄÙÅôÖ±Õîà§§((ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ¡ïç•Ö±•Õ—ÃÄÙÅ±•Õ—=ò†â	±’ï°•¡Q…Öëï…$π≠–à∞ÄâÖÕ°ïπï…Ö—•Ωπ$π≠–à∞Äâ5Öπ•¡’±Ö—ïëQ…Öëï…$π≠–à∞(ÄÄÄÄÄÄÄÄÄÄÄÄâ5ΩΩπÕ°Ω—Q…Öëï…$π≠–à∞ÄâE’Ö±•—ÂQ…Öëï…$π≠–à∞ÄâM°•—Ω•π·¡…ïÕÃπ≠–à∞ÄâM°•—Ω•πQ…Öëï…$π≠–à§(ÄÄÄÄÄÄÄÄÄÄÄÄπµÖ¿ÅÏÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–ΩÿÃΩÕçΩ…•πúºë•–à§π…ïÖëQï·–†§ÅÙ(ÄÄÄÄÄÄÄÅÕ¡ïç•Ö±•Õ—ÃπôΩ…Öç†ÅÏ(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•–πçΩπ—Ö•πÃ†âXÕ)Ω’…πÖ±IïçΩ…ëï»π…ïçΩ…ë±ΩÕî†à§§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°•–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±A’â±•Õ°!ï±¡ï»π¡’â±•Õ°·•–†à§§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°•–πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ∞Å—ï…µ•πÖ∞Åâ…•ëùîÅΩ›πÃÅ—°îÅÕ•πù±îÅM10Å©Ω’…πÖ∞Å¡…Ω©ïç—•Ω∏à§ÅÒ(ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÄÅ•–πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ∞Åô•πÖ±•ÈïêÅâ’ÃÅ•ÃÅ¡’â±•Õ°ïêÅâ‰ÅQï…µ•πÖ±	…•ëùîΩ·ïç’—Ω»ÅΩπ±‰à§§(ÄÄÄÄÄÄÄÅÙ((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çÂç±•åπçΩπ—Ö•πÃ†â=9%I5}1M∞ÅU9-9=]8∞ÅAI=Y%I}U9Y%1	1∞Å=9%I5}QIUà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çÂç±•åπçΩπ—Ö•πÃ†â•òÄ°•Õ1•Ÿï5Ωëî§ÅïŸ•ëïπçîÄÙÙÅÂç±•çMï±±Öâ•±•—ÂŸ•ëïπçîÿ‘ÿ‹π=9%I5}QIUà§ÄòòÅçÂç±•åπçΩπ—Ö•πÃ†âï±ÕîÅïŸ•ëïπçîÄÑÙÅÂç±•çMï±±Öâ•±•—ÂŸ•ëïπçîÿ‘ÿ‹π=9%I5}1Mà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†â’π•≈’ïÂπM•ùπÖ±Ãÿ‘ÿ‹à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âù…Ω’¡	‰ÅÏÅ•–πëÂπÕÕï—-ï‰à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âA=M%Q%=9}A}I!à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†â=	MIY}MA%1%MQ}M%19|ÿ‘ÿ‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÄÖç…Â¡—ºπçΩπ—Ö•πÃ†ààâµÖ…≠ŸÖ±’Ö—•Ωπ•Õ¡ΩÕ•—•Ω∏ÿ‘ÿ‹°…ïô…ïÕ°ïê∞Äâ9=}Q%=9	1}MA%1%MQ}M%90à§ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°ç…Â¡—ºπçΩπ—Ö•πÃ†àπ—Ö≠î†»‘§ÄººÅX‘∏‰∏ƒ»‡ËÅ…Ö•ÕïêÅô…Ω¥ÄÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïù•Õ—…‰πçΩπ—Ö•πÃ†âïŸÖ±’Ö—•Ω∏Å—ï…µ•πÖ∞Åë•Õ¡ΩÕ•—•ΩπÃÙà§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âIeAQ=}Y1}QI5%91|ÿ‘ÿ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â•òÄ°•Õ…Â¡—ΩUπ•Ÿï…ÕïMΩ’…çîÿ‘Ã‘§ÅΩ’–Ä¨ÙÅpâIeAQ=}1Qpàà§§((ÄÄÄÄÄÄÄÅµÖ¡=ò†âQΩ≠ïπ•ÈïëM—Ωç≠Q…Öëï»π≠–àÅ—ºÄâMQ=-Là∞ÄâΩ…ï·Q…Öëï»π≠–àÅ—ºÄâ=I`à∞(ÄÄÄÄÄÄÄÄÄÄÄÄâ5ï—Ö±ÕQ…Öëï»π≠–àÅ—ºÄâ5Q1Là∞ÄâΩµµΩë•—•ïÕQ…Öëï»π≠–àÅ—ºÄâ=55=%Q%Là§πôΩ…Öç†ÅÏÄ°ô•±î∞ÅôÖµ•±‰§Ä¥¯(ÄÄÄÄÄÄÄÄÄÄÄÅŸÖ∞ÅÕ…åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡Ãºëô•±îà§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ…åπçΩπ—Ö•πÃ†â•ÕAÖ¡ï…5Ωëîπùï–†§ÅÒà§§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ…åπçΩπ—Ö•πÃ†â5I-QM}U991|ÿ‘ÿ›Ò5%1dÙëôÖµ•±ÂÒMQıM%91}M1Qà§§(ÄÄÄÄÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ…åπçΩπ—Ö•πÃ†â5I-QM}U991|ÿ‘ÿ›Ò5%1dÙëôÖµ•±ÂÒMQıAAI}QIUMQ}Y%M=Idà§§(ÄÄÄÄÄÄÄÅÙ(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ö’—°Ω…•—‰πçΩπ—Ö•πÃ†âÕÕï—±ÖÕÕM—Ö—Ãÿ‘ÿ‹à§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†âÖÕÕï—±ÖÕÕ’ππï±Iï¡Ω…–ÿ‘ÿ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°°ïÖ±—†πçΩπ—Ö•πÃ†â…ΩÕÃµÕÕï–ÅÖπΩπ•çÖ∞Å’ππï∞Ä°X‘∏¿∏ÿ‘ÿ‹§Åm9=9%0ÅUII9PÅMMM%=9tà§§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ïπ—…ÂMπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†â1ïÖ…π•πùAï…Õ•Õ—ïπçîπÕÖŸî°¡ï…Õ•Õ—ïπçï-ï‰ÿ‘ÿ‹à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅïπ—…ÂMπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†â1ïÖ…π•πùAï…Õ•Õ—ïπçîπ±ΩÖê°¡ï…Õ•Õ—ïπçï-ï‰ÿ‘ÿ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ô•πÖ±•ÈïêπçΩπ—Ö•πÃ†âŸÖ∞Åïπ—…ÂMΩ’…çîËÅM—…•πúà§ÄòòÅô•πÖ±•ÈïêπçΩπ—Ö•πÃ†âŸÖ∞ÅµÖ…≠ï—Iïù•µîËÅM—…•πúà§ÄòòÅô•πÖ±•ÈïêπçΩπ—Ö•πÃ†âŸÖ∞ÅÕçΩ…ï	ÖπêËÅM—…•πúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°—Öç—•åπçΩπ—Ö•πÃ†â¡ï…Õ•Õ—!•Õ—Ω…•çÖ∞ÿ‘ÿ‹à§ÄòòÅ—Öç—•åπçΩπ—Ö•πÃ†â°•Õ—Ω…•çÖ±Q…ÖëïÕΩ…Ω°Ω…–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç±ÖÕÕ•ô•ï»πçΩπ—Ö•πÃ†âç’……ïπ—AÖ¡ï…ΩπÕï…ŸÖ—•Ωπ!ïÖ±—°‰ÿ‘ÿ‹à§ÄòòÅç±ÖÕÕ•ô•ï»πçΩπ—Ö•πÃ†âç±ÖÕÕ•ô•ï…}ç’……ïπ—}çÖπΩπ•çÖ±}ëï±—Ö}°ïÖ±—°Â|ÿ‘ÿ‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°°ïÖ±—†πçΩπ—Ö•πÃ†âmMMM%=8Å!%MQ=I%0Å=U9QIMtà§ÄòòÅ°ïÖ±—†πçΩπ—Ö•πÃ†âm9=9%0ÅUII9PÅM9AM!=QMtà§§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ•∏πçΩπ—Ö•πÃ†â•òÄ°Õ—…’ç—’…Ö±°Öπùî§Å±±=¡ïπAΩÕ•—•ΩπÃπ…ïµΩŸï±±Y•ï›Ã†§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ•∏πçΩπ—Ö•πÃ†âŸÖ∞Åë•Ÿ•ëï…Y•ï‹ËÅÖπë…Ω•êπŸ•ï‹πY•ï‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ•∏πçΩπ—Ö•πÃ†â•òÄ°Õ—…’ç—’…Ö±°Öπùî§ÅÏà§ÄòòÅµÖ•∏πçΩπ—Ö•πÃ†âçÖç°ïêπë•Ÿ•ëï…Y•ï‹à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°µÖ•∏πçΩπ—Ö•πÃ†â±±=¡ïπAΩÕ•—•ΩπÃπÖëëY•ï‹°Y•ï‹°—°•Ã§πÖ¡¡±‰à§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ·}µïµï}ïπ—…Â}¡Ω±•çÂ}Öπë}—Öç—•ç}…ï›Ö…ëÕ}Ö…ï}çÖ’ÕÖ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïåÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å—Öç—•åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ±ïÖ…π•πúΩQÖç—•çM›•—ç°ï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïåπçΩπ—Ö•πÃ†â1%Y}9QIe}A=1%e}M9AM!=Q}9=9%1|ÿ‘ÿ‡à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†âïπ—…ÂQ°…ïÕ°Ω±ëMπÖ¡Õ°Ω–ÄÙÅ—Ãπ¡ΩÕ•—•Ω∏πïπ—…ÂAΩ±•çÂMπÖ¡Õ°Ω–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïåπçΩπ—Ö•πÃ†âïπ—…ÂQÖç—•åÙàÄ¨ÄàêàÄ¨Äâï±ïç—ïëQÖç—•åÿ‘ÿ‡à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†ââ…Ö•πΩπÕïπÕ’ÃÙàÄ¨ÄàêàÄ¨Äââ…Ö•πYï…ë•ç–ÿ‘ÿ‡à§ÄòòÅï·ïåπçΩπ—Ö•πÃ†â¡Ω±•çÂA]•∏ÙàÄ¨ÄàêàÄ¨ÄâÌ¡Ω±•çÂA]•∏ÿ‘ÿ‡πôµ–†Ã•Ùà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°—Öç—•åπçΩπ—Ö•πÃ†âQQ%}!%MQ=I%1}=UQ=5}QQI%	UQ|ÿ‘ÿ‡à§ÄòòÅ—Öç—•åπçΩπ—Ö•πÃ†â•òÄ°ï±ïç—ïêππÖµîÄÙÙÅç’……ïπ–§ÅΩπQ…Öëï±ΩÕïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°—Öç—•åπçΩπ—Ö•πÃ†âïπ—ï…ïêπ•Õ	±Öπ¨†§ÅÒÅïπ—ï…ïêÄÙÙÅç’……ïπ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âÖ’—°Ω…•—Ö—•ŸïAΩ±•çÂAΩÕ•—•Ÿîÿ‘ÿ‡à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â9Q%Y}=9M9MUM}9=I51}	Ue}MUAAIMM|ÿ‘ÿ‡à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â±•≈=¨ÄòòÅÖ’—°Ω…•—Ö—•ŸïAΩ±•çÂAΩÕ•—•Ÿîÿ‘ÿ‡à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿ·}çΩµ¡±ï—•Ωπ}çÖ’ÕÖ±}±ïÖ…π•πù}•π—ïù…•—Â}Öπë}Õ°Ö¡•πú†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕπÖ¡Õ°Ω–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ωπ—…ÂM—…Ö—ïùÂMπÖ¡Õ°Ω–ÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖëŸ•ÕΩ»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω’—ΩA•¡ï±•πïëŸ•ÕΩ»ÿ–ÿ»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï±•ù•â•±•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩAÖ¡ï…1ïÖ…π•πù±•ù•â•±•—‰ÿ‘ƒ‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åâ…•ëùîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω•πÖ±•Èïë	’ÕΩπÕ’µï…	…•ëùîÿ–ÿ‘π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å©Ω’…πÖ∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩXÕ)Ω’…πÖ±IïçΩ…ëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ•Èï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩMµÖ…—M•Èï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‡Å—Â¡ïêÅ•µµ’—Öâ±îÅïπ—…‰Å¡Ω±•ç‰ÅïŸ•ëïπçîà∞ÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âïπ—…ÂAΩ±•çÂMπÖ¡Õ°Ω—%êà§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âÿÕΩµ¡Ωπïπ—Ãà§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†ââ…Ö•πΩπÕïπÕ’ÕYï…ë•ç–à§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†â¡Ω±•çÂA…ΩâÖâ•±•—‰à§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âÕ¡ïç•Ö±•Õ—Ωπ—…•â’—•ΩπÃà§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âÕ•È•πù5’±—•¡±•ï…Ãà§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âÖ’—°Ω…•ÈÖ—•ΩπIïÖÕΩ∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‡ÅïŸï…‰¥»‘Åç±ΩÕîÅçÖ’ÕÖ∞Å…ï¡Ω…–à∞ÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†â…Ω›ÃπÕ•ÈîÄîÄ»‘ÄÙÙÄ¿à§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†â55}]%99I}1=MI}UM1}IA=IQ|ÿ‘ÿ‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‡Å¡ï…Õ•Õ—ïêÅâΩ’πëïêÅçÖ’ÕÖ∞Å±ïÖ…πï»à∞ÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âµïµï}çÖ’ÕÖ±}±ïÖ…π•πù|ÿ‘ÿ‡à§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†â›°•±îÄ°…Ω›ÃπÕ•ÈîÄ¯Äƒ¿¿§à§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†âïπÕ’…ïIïÕ—Ω…ïêà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‡Å•π—ïù…•—‰Åë•ÖùπΩÕ—•çÃÅçÖππΩ–Åµ’—Ö—îÅÕ—…Ö—ïù‰à∞ÅÖëŸ•ÕΩ»πçΩπ—Ö•πÃ†âY%M=I}%9QI%Qe}%9=MQ%}=91e|ÿ‘ÿ‡à§ÄòòÄÖÖëŸ•ÕΩ»πçΩπ—Ö•πÃ†ààâÖπë•ëÖ—î†âïπ—…ÂΩΩ±ëΩ›πMïåà∞Ä¨Ã∏¿ààà§ÄòòÄÖÖëŸ•ÕΩ»πçΩπ—Ö•πÃ†âA9%9}9QIe}1-}%9Q=}=A9|ÿ–ÿƒÙêàÄ¨Äâ¡ïπë•πù1ïÖ≠ÃÉäPÅ—°…Ω——±îÅïπ—…•ïÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‡Å•πŸÖ±•êÅ—ï…µ•πÖ±ÃÅôΩ…ïπÕ•åÅΩπ±‰à∞Åï±•ù•â•±•—‰πçΩπ—Ö•πÃ†â=I9M%}=91e|êàÄ¨Äâ•πŸÖ±•êà§ÄòòÅ©Ω’…πÖ∞πçΩπ—Ö•πÃ†â)=UI91}MQIQe}1I9%9}EUI9Q%9|ÿ‘ÿ‡à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‡ÅΩπîÅçÖπΩπ•çÖ∞ÅçÖ’ÕÖ∞ÅçΩπÕ’µï»à∞Åâ…•ëùîπçΩπ—Ö•πÃ†âëï±•Ÿï…QΩ5ïµïÖ’ÕÖ±1ïÖ…π•πúÿ‘ÿ‡à§ÄòòÅâ…•ëùîπçΩπ—Ö•πÃ†âÖµÖùïΩπ—…Ω±Ö—îππΩ—ï=’—çΩµîà§ÄòòÄÖ©Ω’…πÖ∞πçΩπ—Ö•πÃ†âQÖç—•çM›•—ç°ï»πΩπQ…Öëï±ΩÕïê°±ÖÂï»∞ÅâÖπê∞Å¡π±Aç—1ïÖ…∏§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‡Å]HΩAÅÕ°Ö¡ïÃÅπΩ–Åë•ÕÖâ±ïÃà∞ÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†â•òÄ°›»¯Ù¿∏ƒ‘ÄòòÅ¡ò¯Ù¿∏‘§à§ÄòòÅÕπÖ¡Õ°Ω–πçΩπ—Ö•πÃ†à¿∏‹¿Åï±ÕîÄ¿∏»¿à§ÄòòÅÕ•Èï»πçΩπ—Ö•πÃ†â55}UM1}AI=I59}M!A|ÿ‘ÿ‡à§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘ÿÂ}ç…ΩÕÕ}ÖÕÕï—}çÖ’ÕÖ±}±•ŸïπïÕÕ}•ëïπ—•—Â}ÖëŸ•ÕΩ…}Öπë}ïçΩπΩµ•çÃ†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—°Ω…•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘–¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩπ—…Öç–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±ÕÕï—π—…ÂΩπ—…Öç–ÿ‘‘ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åô•πÖ±•—‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±Q…Öëï•πÖ±•Èïë	’Ãÿ–‘¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖëŸ•ÕΩ»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω’—ΩA•¡ï±•πïëŸ•ÕΩ»ÿ–ÿ»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰Å•µµ’—Öâ±îÅç±ÖÕÃÅ±•ŸïÃÅΩ∏ÅÕïÖ±ïêÅ—•ç≠ï–à∞ÅùÖ—îπçΩπ—Ö•πÃ†âÖÕÕï—±ÖÕÕQÖúà§ÄòòÅçΩπ—…Öç–πçΩπ—Ö•πÃ†âÖÕÕï—±ÖÕÕQÖúÄÙÅçÖπë•ëÖ—îπÖÕÕï—±ÖÕÃπ—Öúà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰Åë•Õ¡Ö—ç†ÅπïŸï»Å…îµëï…•ŸïÃÅç±ÖÕÃÅô…Ω¥Åï·ïç’—Ω»Å±Öπîà∞ÅçΩπ—…Öç–πçΩπ—Ö•πÃ†â•π—ïπ—ÕÕï—±ÖÕÃÿ‘ÿ‰°•π—ïπ–§à§ÄòòÄÖçΩπ—…Öç–πçΩπ—Ö•πÃ†âµÖ…≠ëÖ¡—ï…•Õ¡Ö—ç°Ω»ÿ‘‘ƒ°ÕÕï—±ÖÕÃπô…Ωµ1Öπî°•π—ïπ–πçÖπΩπ•çÖ±1Öπî§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰Å—ï…µ•πÖ∞Å±ïÖ…π•πúÅçÖ……•ïÃÅ•µµ’—Öâ±îÅç±ÖÕÃà∞Åô•πÖ±•—‰πçΩπ—Ö•πÃ†âÖÕÕï—±ÖÕÕQÖúÄÙÅïŸïπ–πÖÕÕï—±ÖÕÕQÖúπ•ô	±Öπ¨à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰Å•π—ïπ–ÅçΩπÕï…ŸÖ—•Ω∏Å•ÃÅï·¡±•ç•–à∞ÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†âë•Õ¡Ö—ç°Iï©ïç–Ùà§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†â¡ïπë•πúÙà§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†â’πï·¡±Ö•πïêÙà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰Å—°…ïîµ›•πëΩ‹Å¡…Ωë’çï»Å±•ŸïπïÕÃÅôÖ’±–à∞ÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†â5I-Q}1MM}1%Y9MM}U1Pà§ÄòòÅÖ’—°Ω…•—‰πçΩπ—Ö•πÃ†âÈï…ºπùï–†§Ä¯ÙÄÕ0à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰ÅÕ¡ïç•Ö±•Õ–ÅÕ•±ïπçîÅΩâÕï…ŸïÃÅ—°…Ω’ù†ÅÕ°Ö…ïêÅÖ’—°Ω…•—‰à∞Åç…Â¡—ºπçΩπ—Ö•πÃ†â=	MIY}MA%1%MQ}M%19|ÿ‘ÿ‰à§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âµÖ…≠ëùIïÖç†ÿ‘––°Õ°Ö…ïëQΩ¨ÿ‘ÿ‰à§ÄòòÄÖç…Â¡—ºπçΩπ—Ö•πÃ†ààâµÖ…≠ŸÖ±’Ö—•Ωπ•Õ¡ΩÕ•—•Ω∏ÿ‘ÿ‹°…ïô…ïÕ°ïê∞Äâ9=}Q%=9	1}MA%1%MQ}M%90à§ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰ÅâΩ’πëïêÅ…Â¡—ºÅÕ°Ö…ïêµ•π—ï±±•ùïπçîÅ›Ω…¨à∞Åç…Â¡—ºπçΩπ—Ö•πÃ†àπ—Ö≠î†»‘§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰ÅÖëŸ•ÕΩ»ÅçÖ’ÕÖ∞µëΩµÖ•∏Å•ÕΩ±Ö—•Ω∏à∞ÅÖëŸ•ÕΩ»πçΩπ—Ö•πÃ†âY%M=I}I=MM}=5%9}5UQQ%=9}	1=-|ÿ‘ÿ‰à§ÄòòÅÖëŸ•ÕΩ»πçΩπ—Ö•πÃ†âIA1e}I%Y9}9QIe}==1=]9}I=11}	-|ÿ‘ÿ‰à§ÄòòÄÖÖëŸ•ÕΩ»πçΩπ—Ö•πÃ†ààâÖπë•ëÖ—î†âïπ—…ÂΩΩ±ëΩ›πMïåà∞Ä¨Ã∏¿ààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î†àÿ‘ÿ‰Å±ïŸï…ÖùïêÅ—ï…µ•πÖ∞Å¡…ΩçïïëÃÅÖπêÅ≈’Ö…Öπ—•πîà∞Åç…Â¡—ºπçΩπ—Ö•πÃ†âÕΩ∞ÄÄÄÄÄÄÄÄÄÄÄÄÄÄÙÄ°¡ΩÃπÕ•ÈïMΩ∞Ä¨Å¡π±MΩ∞§πçΩï…çï—1ïÖÕ–†¿∏¿§à§ÄòòÅ¡Ö¡ï»πçΩπ—Ö•πÃ†â1YI}QI5%91}I%Q!5Q%}%YI9|ÿ‘ÿ‰à§ÄòòÅ¡Ö¡ï»πçΩπ—Ö•πÃ†âAÖ¡ï…1ïÖ…π•πù±•ù•â•±•—‰ÿ‘ƒ‰π…ïçΩ…êà§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿ‘‹¡}ï·ïç’—•Ωπ}Öπë}ï·•—}Ö’—°Ω…•—Â}…ï¡Ö•…}çΩπ—…Öç–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…¨ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±A…•çï5Ö…¨ÿ‘»»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠Ö—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω5Ö…≠’—°Ω…•—Â%π—ïù…•—ÂÖ—îÿ–‰ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÂç±•åÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩÂç±•çQ…Öëïπù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡Õ·ïç’—•Ωππù•πîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ï…¡Õ§ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩAï…¡ÕQ…Öëï…$π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡ΩÕ•—•ΩπÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AΩÕ•—•Ωπ’—°Ω…•—‰ÿ––ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö¡ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…Q…ÖπÕÖç—•Ω∏ÿ–‡ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å±•ŸîÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5Ö…≠ï—Õ1•Ÿï·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅôΩ…ï‡ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩΩ…ï·Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩµµΩë•—•ïÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩΩµµΩë•—•ïÕQ…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åµï—Ö±ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ5ï—Ö±ÕQ…Öëï»π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…¨πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±5Ö…≠A’…¡ΩÕîÿ‘‹¿π=	MIYQ%=9}M=I%9à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ…¨πçΩπ—Ö•πÃ†â…ïÕΩ±Ÿï·ïç’—Öâ±ï…ΩµMΩ’…çïŸ•ëïπçîÿÿƒÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÖπΩπ•çÖ±A…•çï5Ö…≠Iïù•Õ—…‰ÿ‘»»π…ïÕΩ±Ÿï·ïç’—Öâ±ï…ΩµMΩ’…çïŸ•ëïπçîÿÿƒÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠Ö—îπçΩπ—Ö•πÃ†â•Õ=âÕï…ŸÖ—•Ωπ’—°Ω…•—Ö—•Ÿîÿ‘‹¿à§ÄòòÅµÖ…≠Ö—îπçΩπ—Ö•πÃ†â-=QI5%90à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âµÖ…≠ŸÖ±’Ö—•ΩπA…Ωù…ïÕÃÿ‘‹¿°…ïô…ïÕ°ïêà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âµÖ…≠ŸÖ±’Ö—•Ωπ•Õ¡ΩÕ•—•Ω∏ÿ‘ÿ‹°ΩâÕï…ŸïëQΩ¨ÿ‘ÿ‰à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅç…Â¡—ºπçΩπ—Ö•πÃ†âM!I}%9Q11%9}	-1=}=1Mà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—≈’Ö±Ã†ƒ∞ÅIïùï‡†âM!I}%9Q11%9}	-1=}=1M}IEUUà§πô•πë±∞°ç…Â¡—º§πçΩ’π–†§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïù•Õ—…‰πçΩπ—Ö•πÃ†âïŸÖ±’Ö—•Ωπïπï…Ö—•Ω∏ÿÿƒ‘à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âïŸÖ±’Ö—•Ωπ%πô±•ù°–ÿÿƒ‘à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âIeAQ=}Y1}9IQ%=9}=1M|ÿÿƒ‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±•πÖ±M•Èîÿ‘‹¿ÄÙÅçÖπΩπ•çÖ±…Â¡—Ω%π—ïπ–ÿ‘ÿ‘π…ïÕΩ±ŸïëM•Èîà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†âµÖ…≠Ö•±ïê°çÖπΩπ•çÖ±…Â¡—Ω%π—ïπ–ÿ‘ÿ‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çÂç±•åπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±5•π—=çç’¡ÖπçÂIïù•Õ—…‰ÿ–ÿ–π•Õ=¡ï∏à§ÄòòÅçÂç±•åπçΩπ—Ö•πÃ†â•òÄ°•Õ1•Ÿï5Ωëî§ÅïŸ•ëïπçîÄÙÙÅÂç±•çMï±±Öâ•±•—ÂŸ•ëïπçîÿ‘ÿ‹π=9%I5}QIUà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…¡ÃπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±ÕÕï—π—…ÂÖπë•ëÖ—îÿ‘‘ƒà§ÄòòÅ¡ï…¡ÃπçΩπ—Ö•πÃ†âÖÕÕï—±ÖÕÃÄÙÅçΩ¥π±•ôïçÂç±ïâΩ–πïπù•πîπ—…’—†πÕÕï—±ÖÕÃπAIALà§ÄòòÅ¡ï…¡ÃπçΩπ—Ö•πÃ†âÕïÖ±ïëAï…¡%π—ïπ–ÿ‘‹¿à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°¡ï…¡ÃπçΩπ—Ö•πÃ†â…ïçΩ…ëëùπëï—%π—ïπ–ÿ‘ÃÃ†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ï…¡Õ§πçΩπ—Ö•πÃ†âï·ïç’—•Ωπ%π—ïπ–ÿ‘ÿ‘Ä¸ËÅ›°ï∏Ä°¡ï…¡Õëµ•ÕÕ•Ω∏ÿ‘ÿ‘§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡ΩÕ•—•ΩπÃπçΩπ—Ö•πÃ†âô’∏Åï·•—±•ù•â•±•—‰ÿ‘‹¿†à§ÄòòÅ¡Ö¡ï»πçΩπ—Ö•πÃ†âï·•—±•ù•â•±•—‰ÿ‘‹¿°¡ΩÕ•—•Ωπ%ê∞Åµ•π–∞Åï·¡ïç—ïë5ΩëîÄÙÅpâ¡Ö¡ï…pà§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†âï·•—±•ù•â•±•—‰ÿ‘‹¿†à§ÄòòÅ±•ŸîπçΩπ—Ö•πÃ†âï·•—±•ù•â•±•—‰ÿ‘‹¿†à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩµµΩë•—•ïÃπçΩπ—Ö•πÃ†â±ÖÂï…YΩ—ïÕmpâΩµµΩë•—•ïÕM—…Ö—ïùÂpâtÄÙÅë•…ïç—•Ω∏à§ÄòòÅµï—Ö±ÃπçΩπ—Ö•πÃ†â±ÖÂï…YΩ—ïÕmpâ5ï—Ö±ÕM—…Ö—ïùÂpâtÄÙÅë•…ïç—•Ω∏à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ôΩ…ï‡πçΩπ—Ö•πÃ†âÕÕï—±ÖÕÃπ=I`∞ÅpâI]}M%91pàà§§(ÄÄÄÅÙ((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÿƒÕ}çÖ’ÕÖ±}ï·ïç’—•Ωπ}µïµï—…Öëï…}Öπë}ç…Â¡—Ω}°ÖπëΩôô}çΩπ—…Öç–†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…¨ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±A…•çï5Ö…¨ÿ‘»»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ°ïï–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩΩ±≠•—M•ùπÖ±M°ïï–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å¡Ö…—•Ö∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±AÖ¡ï…AÖ…—•Ö±=¡ï…Ö—•Ω∏ÿ‘ƒ¿π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§((ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âÖπΩπ•çÖ±•πÖ±ïç•Õ•Ω∏ÿÿƒÃà§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†â…ïÕΩ±ŸïMïÖ±ïë%π—ïπ–ÿÿƒÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âIMQ=I}11=]}Q%-Q}]%Q!=UQ}	Ue}%M%=8à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âIMQ=I}Q%-Q}%M%=9}%YIM}I=5}M1}à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…¨πçΩπ—Ö•πÃ†â¡…ΩµΩ—ï=âÕï…ŸÖ—•ΩπQΩ·ïç’—Öâ±îÿÿƒÃà§ÄòòÅµÖ…¨πçΩπ—Ö•πÃ†â9=9%1}5%9Q}M=UI}5I-|ÿÿƒÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ï·ïç’—Ω»πçΩπ—Ö•πÃ†â19}a}]%Q!=UQ}M5}19}9=9%1}%9Q9Pà§ÄòòÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â19}a}]%Q!=UQ}M1}}AI=Y99à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ°ïï–πçΩπ—Ö•πÃ†â%9Q9Q}!=-à§ÄòòÅÕ°ïï–πçΩπ—Ö•πÃ†â5I-}!=-à§ÄòòÅÕ°ïï–πçΩπ—Ö•πÃ†âa}!=-à§ÄòòÅÕ°ïï–πçΩπ—Ö•πÃ†â1I9%9}!=-à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°Õ°ïï–πçΩπ—Ö•πÃ†ààâQ15QIe}=91dààà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°¡Ö…—•Ö∞πçΩπ—Ö•πÃ†âQ•ï…M—Ö—îÿÿƒÃà§ÄòòÅ¡Ö…—•Ö∞πçΩπ—Ö•πÃ†âEU9Q%Qe}IMIYà§ÄòòÅ¡Ö…—•Ö∞πçΩπ—Ö•πÃ†â=U9Qà§ÄòòÅ¡Ö…—•Ö∞πçΩπ—Ö•πÃ†â=5A1Qà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â1I9}A=1%e}9Q%Y}19}]%Q}M!A|ÿÿƒÃà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âQQ%}I=QQ}]-}]%Q}M!A|ÿÿƒÃà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°âΩ–πçΩπ—Ö•πÃ†ààââ±Ωç≠IïÖÕΩ∏ÄÙÄâ1I9}A=1%e}YQ=|ÿ‘‰Ãàààà§§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπë•ëÖ—ïM—Öµ¿ÄÙÅç…Â¡—ºπ•πëï·=ò†ààâÕÕï—±ÖÕÃπIeAQ=}1P∞Äâ9%Qàààà§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçÖπΩπ•çÖ±M’âµ•–ÄÙÅç…Â¡—ºπ•πëï·=ò†âÖπΩπ•çÖ±π—…Â’—°Ω…•—‰ÿ‘‘ƒπÕ’âµ•–à∞ÅçÖπë•ëÖ—ïM—Öµ¿§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çÖπë•ëÖ—ïM—Öµ¿Ä¯ÙÄ¿ÄòòÅçÖπΩπ•çÖ±M’âµ•–Ä¯ÅçÖπë•ëÖ—ïM—Öµ¿§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âIeAQ=}1I9}M%i}1==I}9=9iI=|ÿÿƒÃà§ÄòòÅç…Â¡—ºπçΩπ—Ö•πÃ†â—ï…µ•πÖ±•Õ¡ΩÕ•—•Ω∏ÿÿƒÃà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÿƒ—}µïµï}Õ¡ïç•Ö±•Õ—}Ω›πï…}ôëù}µÖ…≠}Öπë}—•ç≠ï—}•ëïπ—•—Â}•Õ}•µµ’—Öâ±î†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅçΩΩ…ë•πÖ—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ1Öπï·ïç’—•ΩπΩΩ…ë•πÖ—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÖ’—†ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQ…Öëï’—°Ω…•Èï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±A…•çï5Ö…¨ÿ‘»»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕ°ïï–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩQΩΩ±≠•—M•ùπÖ±M°ïï–π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â…Ω±ï•—A…•µÖ…‰ÿÿƒ–à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÕ—…ΩπùïÕ—IΩ±îÿÿƒ–à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âïπÕïµâ±ïΩ…ï•–ÿÿƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°çΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†âºÅπΩ–Å…îµï±ïç–Å•–Å°ï…îÅ’Õ•πúÅÕ—Ö—•åà§ÄòòÄÖçΩΩ…ë•πÖ—Ω»πçΩπ—Ö•πÃ†âQIMUIe}I}MA%1%MQ}%IMPà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Ö’—†πçΩπ—Ö•πÃ†â·ïç’—•Ωπ	ΩΩ¨πM!8à§ÄòòÅÖ’—†πçΩπ—Ö•πÃ†â·ïç’—•Ωπ	ΩΩ¨πAI=)Q}M9%AHà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âMA%1%MQ}%9Q9Q}]%Q!=UQ}}=UQ=5à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âÕ¡ïç•Ö±•Õ—Ö’ÕÖ±%êÿÿƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ÃπçΩπ—Ö•πÃ†â…ïô…ïÕ°…Ωµ·ïç’—Öâ±ïQΩ≠ïπ5Ö¿ÿÿƒ–à§ÄòòÅµÖ…≠ÃπçΩπ—Ö•πÃ†âQ=-9}5A}I=UQ}9=Q}aUQ	1à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âµÖ…≠%êÿÿƒ–à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âµÖ…≠Yï…Õ•Ω∏ÿÿƒ–à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âÕïÖ±ïëA…ΩŸïπÖπçîÿÿƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âQ%-Q}IIM!}UQ!=I%Qe}%1UIà§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†âaA%I}Q%-Q}=9=5%}I)Q|ÿÿƒ–à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õ°ïï–πçΩπ—Ö•πÃ†âôëù±±Ω‹Ä¨Åôëù	±Ωç¨ÄÙÙÄ¡0à§ÄòòÅÕ°ïï–πçΩπ—Ö•πÃ†âMA%1%MQ}%9Q9Q}]%Q!=UQ}}=UQ=5Ùà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÿƒ’}±ΩΩ¡}Õ—Ö…ŸÖ—•Ωπ}›Ω…≠}•Õ}Õ•πù±ï}ô±•ù°—}Öπë}ùïπï…Ö—•Ωπ}Ω›πïê†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å›Ω…≠ï»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω5Ö•π—ïπÖπçï]Ω…≠ï»ÿ––‡π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅÕïπ—•πï∞ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω5Ö…≠ï—Ö—ÖA…ΩŸïπÖπçîÿ–‹ƒπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†Ω5Ö…≠’—°Ω…•—Â%π—ïù…•—ÂÖ—îÿ–‰ÿπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Å…ïù•Õ—…‰ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩÂπÖµ•ç±—QΩ≠ïπIïù•Õ—…‰π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åç…Â¡—ºÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ω¡ï…¡ÃΩ…Â¡—Ω±—Q…Öëï»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅùÖ—îÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Öâ±ï=¡ïπÖ—îπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°›Ω…≠ï»πçΩπ—Ö•πÃ†â…’ππ•πù9ÖµïÃÿÿƒ‘πÖëê°πÖµî§à§ÄòòÅ›Ω…≠ï»πçΩπ—Ö•πÃ†â…’ππ•πù9ÖµïÃÿÿƒ‘π…ïµΩŸî°πÖµî§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â…ï≈’ïÕ—!Ω—]Ö—ç°±•Õ—IïâÖ±Öπçîÿÿƒ‘à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†â°Ω—}›Ö—ç°±•Õ—}…ïâÖ±Öπçï|ÿÿƒ‘à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âçÖπΩπ•çÖ±}çÂç±ï}ïπë|ÿÿƒ‘à§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âŸÖ∞ÅµÖ·	Ö—ç°5•±±•ÃÄÙÄ›|‘¿¡0à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°Õïπ—•πï∞πçΩπ—Ö•πÃ†âMïπ—•πï±M—Ö—îÿÿƒ‘à§ÄòòÅÕïπ—•πï∞πçΩπ—Ö•πÃ†â5I-Q}Q}M9Q%91}=1M|ÿÿƒ‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ÃπçΩπ—Ö•πÃ†â5Ö…≠M—Ö—îÿÿƒ‘à§ÄòòÅµÖ…≠ÃπçΩπ—Ö•πÃ†âAAI}5I-}U9!9}=1M|ÿÿƒ‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°…ïù•Õ—…‰πçΩπ—Ö•πÃ†âïŸÖ±’Ö—•Ωπïπï…Ö—•Ω∏ÿÿƒ‘à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âïŸÖ±’Ö—•Ωπ%πô±•ù°–ÿÿƒ‘à§ÄòòÅ…ïù•Õ—…‰πçΩπ—Ö•πÃ†âIeAQ=}Y1}MQ1}=5A1Q%=9}I=AA|ÿÿƒ‘à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ç…Â¡—ºπçΩπ—Ö•πÃ†âM!I}%9Q11%9}	-1=}=1Mà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅIïùï‡†âM!I}%9Q11%9}	-1=}=1M}IEUUà§πô•πë±∞°ç…Â¡—º§πçΩ’π–†§ÄÙÙÄƒ§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°ùÖ—îπçΩπ—Ö•πÃ†âa}=A9}	1=-}9=}aUQ%=9}%9Q9Q|ÿÿƒ‘à§ÄòòÅùÖ—îπçΩπ—Ö•πÃ†â9=}aUQ%=9}%9Q9Pà§§(ÄÄÄÅÙ(((ÄÄÄÅQïÕ–(ÄÄÄÅô’∏ÅX’|¡|ÿÿƒŸ}çÖπΩπ•çÖ±}µÖ…≠}Öπë}Õ’¡ï…Ÿ•ÕΩ…}ùïπï…Ö—•Ωπ}±•ôï—•µï}Ö…ï}çÖ’ÕÖ∞†§ÅÏ(ÄÄÄÄÄÄÄÅŸÖ∞ÅâΩ–ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ	Ω—Mï…Ÿ•çîπ≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞Åï·ïç’—Ω»ÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ·ïç’—Ω»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅŸÖ∞ÅµÖ…≠ÃÄÙÅ©ÖŸÑπ•ºπ•±î†âÕ…åΩµÖ•∏Ω≠Ω—±•∏ΩçΩ¥Ω±•ôïçÂç±ïâΩ–Ωïπù•πîΩ—…’—†ΩÖπΩπ•çÖ±A…•çï5Ö…¨ÿ‘»»π≠–à§π…ïÖëQï·–†§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°µÖ…≠ÃπçΩπ—Ö•πÃ†â…ïÕΩ±Ÿï·ïç’—Öâ±ï…ΩµMΩ’…çïŸ•ëïπçîÿÿƒÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ÃπçΩπ—Ö•πÃ†âM=UI}	M}%9Q%Qe}5%M5Q à§ÄòòÅµÖ…≠ÃπçΩπ—Ö•πÃ†âM=UI}Y%9}MQ1à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅµÖ…≠ÃπçΩπ—Ö•πÃ†â…ï—’…∏Å¡…ΩµΩ—ï=âÕï…ŸÖ—•ΩπQΩ·ïç’—Öâ±îÿÿƒÃ°µ•π–∞ÅπΩ›5Ã§à§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†â…ïÕΩ±Ÿï·ïç’—Öâ±ï…ΩµMΩ’…çïŸ•ëïπçîÿÿƒÿà§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅï·ïç’—Ω»πçΩπ—Ö•πÃ†â…ïÕΩ±Ÿï·ïç’—Öâ±ï…ΩµMΩ’…çïŸ•ëïπçîÿÿƒÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Q…’î°âΩ–πçΩπ—Ö•πÃ†âÕ—Ö…—ïë5ΩπΩ—Ωπ•ç5Ãà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âMÂÕ—ïµ±Ωç¨πï±Ö¡ÕïëIïÖ±—•µî†§à§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†âÕ•πçïA…Ωù…ïÕÃÄ¯ÙÅMUAIY%M=I}1M}AI=IMM}QQ1}5Là§Äòò(ÄÄÄÄÄÄÄÄÄÄÄÅâΩ–πçΩπ—Ö•πÃ†â©Ωà¸π•Õç—•ŸîÄÙÙÅ—…’îà§ÄòòÅâΩ–πçΩπ—Ö•πÃ†âMUAIY%M=I}=I}I1M}II}e=U9|ÿÿƒÿà§§(ÄÄÄÄÄÄÄÅÖÕÕï…—Ö±Õî°âΩ–πçΩπ—Ö•πÃ†âM’¡ï…Ÿ•ÕΩ…1ïÖÕî°µ•π–ÄÙÅµ•π–∞ÅÕ—Ö…—ïë5ÃÄÙÅMÂÕ—ï¥πç’……ïπ—Q•µï5•±±•Ã†§à§§(ÄÄÄÅÙ()Ù