package com.lifecyclebot.engine

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.438 — LEARNING PERSISTENCE
 *
 * User report (Feb 2026): "absolutely every learnt edge behaviour
 * sentience llm symbolic reasoning is all meant to be persistent."
 *
 * V5.9.435/436 shipped three outcome-attribution trackers
 * (ScoreExpectancyTracker, HoldDurationTracker, ExitReasonTracker) but
 * they held their rolling windows purely in memory — a reboot wiped
 * days of edge. This layer mirrors all three to a tiny SQLite kv table.
 *
 * Design:
 *   - One row per (tracker, key) — value is a JSON array of pnlPct doubles.
 *   - saveAll() called every N records + on orderly shutdown.
 *   - loadAll() called at boot from TradeHistoryStore.init().
 *
 * Fail-open everywhere: persistence errors NEVER block trading.
 */
object LearningPersistence {

    private const val TAG = "LearningPersist"

    /** Save to disk after this many total tracker record() calls. */
    private const val SAVE_EVERY_N = 50

    private val recordCounter = AtomicInteger(0)

    private var db: SQLiteDatabase? = null
    private var appCtx: Context? = null   // V5.9.1353 — stashed for resetAll() (FluidLearningAI needs a Context)

    private class KvHelper(ctx: Context) :
        SQLiteOpenHelper(ctx, "learning_kv.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS kv (
                    tracker TEXT NOT NULL,
                    bucket  TEXT NOT NULL,
                    payload TEXT NOT NULL,
                    updated INTEGER NOT NULL,
                    PRIMARY KEY (tracker, bucket)
                )
            """.trimIndent())
        }
        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {}
    }

    /** Init DB and restore every tracker's rolling window state. */
    fun init(ctx: Context) {
        // V5.9.1036 — operator V5.9.1034b ANR triage: top main-thread offender
        // (14 ANR samples, ~3000-lesson JSON parse) was TradeLessonRecorder
        // .importState called synchronously from loadAll() during onCreate.
        // Split open-DB (synchronous, ~5-10ms — required so callers' putBlob
        // /getBlob calls work immediately) from loadAll() (background — every
        // brain consumer guards a not-yet-loaded state via try/?.let so
        // missing blobs return empty defaults until restoration completes).
        appCtx = ctx.applicationContext
        try {
            db = KvHelper(ctx).writableDatabase
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "init db error: ${e.message}")
            return
        }
        GlobalScope.launch(Dispatchers.IO) {
            try {
                loadAll()
                ErrorLogger.info(TAG, "✅ Learning persistence ready — trackers restored from disk (off-main).")
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "init loadAll error: ${e.message}")
            }
        }
    }

    /**
     * Invoked by each tracker's record(...) call. When counter crosses
     * SAVE_EVERY_N we flush all trackers to disk. Non-blocking for
     * callers beyond the occasional SQLite write (millisecond-scale).
     */
    /**
     * V5.9.999 — operator triage: ANR storm fix.
     * Pre-V5.9.999 onRecord() called saveAll() synchronously on the caller's
     * thread. Every Nth record this would:
     *   - 27 exportState() calls (JSON serialize each AI's brain state)
     *   - 27 putBlob() SQLite writes inside a transaction
     *   - Plus 6 sub-trader save(force=true) calls
     * The pipeline dump captured this taking 682 ms on the main thread alone,
     * driving the operator's 33-ANR storm + 'stopBot did not complete in 30s'
     * timeout. saveAll IS already fail-safe (try/catch + endTransaction in
     * finally) so it is safe to dispatch to a background IO coroutine. The
     * shutdownSave() path (called from BotService.stopBot) intentionally
     * still calls saveAll() directly so persistence completes before exit.
     */
    private val saveMutex = Mutex()
    fun onRecord() {
        val n = recordCounter.incrementAndGet()
        if (n % SAVE_EVERY_N == 0) {
            GlobalScope.launch(Dispatchers.IO) {
                if (saveMutex.tryLock()) {
                    try { saveAll() } catch (_: Throwable) {}
                    finally { saveMutex.unlock() }
                }
                // If a save is already in flight we just skip this tick;
                // the next Nth record will catch up. Prevents overlapping
                // SQLite transactions if onRecord fires faster than the
                // background save can finish.
            }
        }
    }

    /** Force-flush all trackers. Call on onDestroy / shutdown. */
    fun saveAll() {
        val d = db ?: return
        try {
            d.beginTransaction()
            saveTracker(d, "SCORE", ScoreExpectancyTracker.exportState())
            saveTracker(d, "HOLD",  HoldDurationTracker.exportState())
            saveTracker(d, "EXIT",  ExitReasonTracker.exportState())
            // V5.9.439 — generic brain-state blobs.
            putBlob("FLUID_LEARNING", com.lifecyclebot.v3.scoring.FluidLearningAI.exportState())
            putBlob("SENTIENCE",      com.lifecyclebot.engine.SentienceOrchestrator.exportState())
            try { putBlob("DAMAGE_CONTROL_WINDOW", com.lifecyclebot.engine.runtime.DamageControlGate.exportState()) } catch (_: Throwable) {}  // V5.9.1357
            // V5.9.949 — persist the rest of the brain. Each was previously
            // wiped on every restart, forcing the bot to relearn from zero.
            try { putBlob("BEHAVIOR_LEARNING", com.lifecyclebot.engine.BehaviorLearning.exportState()) } catch (_: Throwable) {}
            try { putBlob("LAYER_READINESS",   com.lifecyclebot.engine.LayerReadinessRegistry.exportState()) } catch (_: Throwable) {}
            try { putBlob("CANONICAL_COUNTERS", com.lifecyclebot.engine.CanonicalLearningCounters.exportState()) } catch (_: Throwable) {}
            try { putBlob("CREATOR_HISTORY", com.lifecyclebot.network.HeliusCreatorHistory.exportState()) } catch (_: Throwable) {}
            try { putBlob("META_COGNITION",  com.lifecyclebot.v3.scoring.MetaCognitionAI.exportState()) } catch (_: Throwable) {}
            try { putBlob("AUTONOMOUS_META_POLICY", com.lifecyclebot.engine.AutonomousMetaPolicy.exportState()) } catch (_: Throwable) {}  // V5.9.1260
            try { putBlob("FORWARD_OUTCOME_MODEL", com.lifecyclebot.engine.ForwardOutcomeModel.exportState()) } catch (_: Throwable) {}  // V5.9.1261
            try { putBlob("AUTO_COMPOUND", com.lifecyclebot.engine.AutoCompoundEngine.exportState()) } catch (_: Throwable) {}  // V5.9.1481
            try { putBlob("SIGNAL_QUALITY", com.lifecyclebot.engine.SignalQualityTracker.exportState()) } catch (_: Throwable) {}  // V5.9.1271
            try { putBlob("UNIFIED_POLICY_HEAD", com.lifecyclebot.engine.UnifiedPolicyHead.exportState()) } catch (_: Throwable) {}  // V5.9.1262
            try { putBlob("STRATEGY_HYPOTHESIS", com.lifecyclebot.engine.StrategyHypothesisEngine.exportState()) } catch (_: Throwable) {}  // V5.9.1263
            try { putBlob("ASYNC_STRATEGY_LAB", com.lifecyclebot.engine.AsyncStrategyLab.exportState()) } catch (_: Throwable) {}  // V5.0.4236
            try { putBlob("SEMANTIC_PATTERN_GRAPH", com.lifecyclebot.engine.SemanticPatternGraph.exportState()) } catch (_: Throwable) {}  // V5.0.4238
            try { putBlob("COUNTERFACTUAL_REPLAY", com.lifecyclebot.engine.CounterfactualReplayEngine.exportState()) } catch (_: Throwable) {}  // V5.0.4239
            try { putBlob("RESEARCH_SCOUT", com.lifecyclebot.engine.ResearchScout.exportState()) } catch (_: Throwable) {}  // V5.0.4240
            try { putBlob("REFLECTIVE_OPTIMIZER_GEPA", com.lifecyclebot.engine.ReflectiveOptimizerGEPA.exportState()) } catch (_: Throwable) {}  // V5.0.4243
            try { putBlob("MULTIPLIER_ATTRIBUTION", com.lifecyclebot.engine.MultiplierAttributionLedger.exportState()) } catch (_: Throwable) {}  // V5.0.4272
            try { putBlob("EXIT_COST_MICROBRAIN", com.lifecyclebot.engine.ExitCostMicrobrain.exportState()) } catch (_: Throwable) {}  // V5.0.4275
            try { putBlob("CAPITAL_EFFICIENCY", com.lifecyclebot.engine.CapitalEfficiencyBrain.exportState()) } catch (_: Throwable) {}  // V5.0.4281
            try { putBlob("SOURCE_FAMILY_SCORECARD", com.lifecyclebot.engine.SourceFamilyOpportunityScorecard.exportState()) } catch (_: Throwable) {}  // V5.0.4287
            try { putBlob("RUNNER_EXIT_SHADOW_LEDGER", com.lifecyclebot.engine.RunnerExitShadowLedger.exportState()) } catch (_: Throwable) {}  // V5.0.4289
            try { putBlob("LIVE_WALLET_GROWTH_GOVERNOR", com.lifecyclebot.engine.LiveWalletGrowthGovernorReport.exportState()) } catch (_: Throwable) {}  // V5.0.4290
            // V5.9.984 — persist CollectiveIntelligenceAI counters + thresholds.
            try { putBlob("COLLECTIVE_INTEL", com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.exportState()) } catch (_: Throwable) {}
            // V5.9.985 — close DipHunterAI + SolanaArbAI amnesia.
            try { putBlob("DIP_HUNTER",     com.lifecyclebot.v3.scoring.DipHunterAI.exportState()) } catch (_: Throwable) {}
            try { putBlob("SOLANA_ARB",     com.lifecyclebot.v3.scoring.SolanaArbAI.exportState()) } catch (_: Throwable) {}

            // V5.0.4205 — Tier 2A amnesia close for live v3 scorer brains.
            // These classes had saveToJson/loadFromJson hooks but no callers,
            // while their analyze()/score() outputs now feed UnifiedScorer and
            // AICrossTalk. Persist them here so restart does not erase order
            // flow, smart-money, volatility, liquidity-cycle, or hold-time edge.
            try { putBlob("HOLD_TIME_OPTIMIZER", com.lifecyclebot.v3.scoring.HoldTimeOptimizerAI.saveToJson().toString()) } catch (_: Throwable) {}
            try { putBlob("ORDER_FLOW_IMBALANCE", com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.saveToJson().toString()) } catch (_: Throwable) {}
            try { putBlob("SMART_MONEY_DIVERGENCE", com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.saveToJson().toString()) } catch (_: Throwable) {}
            try { putBlob("VOLATILITY_REGIME", com.lifecyclebot.v3.scoring.VolatilityRegimeAI.saveToJson().toString()) } catch (_: Throwable) {}
            try { putBlob("LIQUIDITY_CYCLE", com.lifecyclebot.v3.scoring.LiquidityCycleAI.saveToJson().toString()) } catch (_: Throwable) {}

            // V5.0.4208 — compact persistence for narrative/exit learners that
            // now influence entry/exit scoring but previously reset on restart.
            try { putBlob("MEME_NARRATIVE", com.lifecyclebot.v3.scoring.MemeNarrativeAI.exportState()) } catch (_: Throwable) {}
            try { putBlob("CULT_MOMENTUM", com.lifecyclebot.v3.scoring.CultMomentumAI.exportState()) } catch (_: Throwable) {}
            try { putBlob("SELL_OPTIMIZATION", com.lifecyclebot.v3.scoring.SellOptimizationAI.exportState()) } catch (_: Throwable) {}
            try { putBlob("REGIME_TRANSITION", com.lifecyclebot.v3.scoring.RegimeTransitionAI.exportState()) } catch (_: Throwable) {}
            try { putBlob("REFLEX_AI", com.lifecyclebot.v3.scoring.ReflexAI.exportState()) } catch (_: Throwable) {}
            try { putBlob("INSIDER_TRACKER", com.lifecyclebot.v3.scoring.InsiderTrackerAI.exportState()) } catch (_: Throwable) {}

            // V5.9.988 — final amnesia close: 5 learners (Doctrine #25)
            // SentienceHooks + NetworkSignalAutoBuyer are SAFETY-FIRST per
            // Doctrine #3.36 (paused-strategy list + daily auto-buy budget
            // must survive process restarts).
            try { putBlob("SENTIENCE_HOOKS",   com.lifecyclebot.engine.SentienceHooks.exportState()) } catch (_: Throwable) {}
            try { putBlob("NET_AUTO_BUYER",    com.lifecyclebot.perps.NetworkSignalAutoBuyer.exportState()) } catch (_: Throwable) {}
            try { putBlob("SHADOW_LEARNING",   com.lifecyclebot.engine.ShadowLearningEngine.exportState()) } catch (_: Throwable) {}
            try { putBlob("EFFICIENCY_LAYER",  com.lifecyclebot.engine.EfficiencyLayer.exportState()) } catch (_: Throwable) {}
            try { putBlob("PERPS_REPLAY",      com.lifecyclebot.perps.PerpsAutoReplayLearner.exportState()) } catch (_: Throwable) {}
            // V5.9.991 — TradeLessonRecorder causal-chain learning corpus
            try { putBlob("TRADE_LESSONS",     com.lifecyclebot.v4.meta.TradeLessonRecorder.exportState()) } catch (_: Throwable) {}
            try { putBlob("LANE_EXIT_TUNER",   com.lifecyclebot.engine.learning.LaneExitTuner.exportState()) } catch (_: Throwable) {}  // V5.9.1379
            try { putBlob("COLD_STREAK_DAMPER", com.lifecyclebot.engine.runtime.ColdStreakDamper.exportState()) } catch (_: Throwable) {}  // V5.9.1381
            // V5.9.964 — wire the 6 theatrical-persistence V3 trader lanes.
            // Pre-V5.9.964 these had save()/restore() defined and init() wired
            // so restore() ran at boot, but save() was NEVER called. Lifetime
            // milestones (10x/100x/1000x counts, lifetime PnL, learning
            // progress) accumulated in memory and died on every restart.
            // Force=true bypasses the per-trader throttle so we always flush
            // alongside the global save cadence.
            try { com.lifecyclebot.v3.scoring.MoonshotTraderAI.save(force = true) } catch (_: Throwable) {}
            try { com.lifecyclebot.v3.scoring.ShitCoinTraderAI.save(force = true) } catch (_: Throwable) {}
            try { com.lifecyclebot.v3.scoring.BlueChipTraderAI.save(force = true) } catch (_: Throwable) {}
            try { com.lifecyclebot.v3.scoring.ProjectSniperAI.save(force = true) } catch (_: Throwable) {}
            try { com.lifecyclebot.v3.scoring.ShitCoinExpress.save(force = true) } catch (_: Throwable) {}
            try { com.lifecyclebot.v3.scoring.QualityTraderAI.save(force = true) } catch (_: Throwable) {}
            d.setTransactionSuccessful()
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "saveAll error: ${e.message}")
        } finally {
            try { d.endTransaction() } catch (_: Exception) {}
        }
    }

    /** Restore every tracker from the kv table. */
    private fun loadAll() {
        val d = db ?: return
        ScoreExpectancyTracker.importState(loadTracker(d, "SCORE"))
        HoldDurationTracker.importState(loadTracker(d, "HOLD"))
        ExitReasonTracker.importState(loadTracker(d, "EXIT"))
        // V5.9.439 — restore every brain-state blob.
        getBlob("FLUID_LEARNING")?.let { com.lifecyclebot.v3.scoring.FluidLearningAI.importState(it) }
        getBlob("SENTIENCE")?.let      { com.lifecyclebot.engine.SentienceOrchestrator.importState(it) }
        try { getBlob("DAMAGE_CONTROL_WINDOW")?.let { com.lifecyclebot.engine.runtime.DamageControlGate.importState(it) } } catch (_: Throwable) {}  // V5.9.1357
        try { getBlob("AUTONOMOUS_META_POLICY")?.let { com.lifecyclebot.engine.AutonomousMetaPolicy.importState(it) } } catch (_: Throwable) {}  // V5.9.1260
        try { getBlob("FORWARD_OUTCOME_MODEL")?.let { com.lifecyclebot.engine.ForwardOutcomeModel.importState(it) } } catch (_: Throwable) {}  // V5.9.1261
        try { getBlob("AUTO_COMPOUND")?.let { com.lifecyclebot.engine.AutoCompoundEngine.importState(it) } } catch (_: Throwable) {}  // V5.9.1481
        try { getBlob("SIGNAL_QUALITY")?.let { com.lifecyclebot.engine.SignalQualityTracker.importState(it) } } catch (_: Throwable) {}  // V5.9.1271
        try { getBlob("UNIFIED_POLICY_HEAD")?.let { com.lifecyclebot.engine.UnifiedPolicyHead.importState(it) } } catch (_: Throwable) {}  // V5.9.1262
        try { getBlob("STRATEGY_HYPOTHESIS")?.let { com.lifecyclebot.engine.StrategyHypothesisEngine.importState(it) } } catch (_: Throwable) {}  // V5.9.1263
        try { getBlob("ASYNC_STRATEGY_LAB")?.let { com.lifecyclebot.engine.AsyncStrategyLab.importState(it) } } catch (_: Throwable) {}  // V5.0.4236
        try { getBlob("SEMANTIC_PATTERN_GRAPH")?.let { com.lifecyclebot.engine.SemanticPatternGraph.importState(it) } } catch (_: Throwable) {}  // V5.0.4238
        try { getBlob("COUNTERFACTUAL_REPLAY")?.let { com.lifecyclebot.engine.CounterfactualReplayEngine.importState(it) } } catch (_: Throwable) {}  // V5.0.4239
        try { getBlob("RESEARCH_SCOUT")?.let { com.lifecyclebot.engine.ResearchScout.importState(it) } } catch (_: Throwable) {}  // V5.0.4240
        try { getBlob("REFLECTIVE_OPTIMIZER_GEPA")?.let { com.lifecyclebot.engine.ReflectiveOptimizerGEPA.importState(it) } } catch (_: Throwable) {}  // V5.0.4243
        try { getBlob("MULTIPLIER_ATTRIBUTION")?.let { com.lifecyclebot.engine.MultiplierAttributionLedger.importState(it) } } catch (_: Throwable) {}  // V5.0.4272
        try { getBlob("EXIT_COST_MICROBRAIN")?.let { com.lifecyclebot.engine.ExitCostMicrobrain.importState(it) } } catch (_: Throwable) {}  // V5.0.4275
        try { getBlob("CAPITAL_EFFICIENCY")?.let { com.lifecyclebot.engine.CapitalEfficiencyBrain.importState(it) } } catch (_: Throwable) {}  // V5.0.4281
        try { getBlob("SOURCE_FAMILY_SCORECARD")?.let { com.lifecyclebot.engine.SourceFamilyOpportunityScorecard.importState(it) } } catch (_: Throwable) {}  // V5.0.4287
        try { getBlob("RUNNER_EXIT_SHADOW_LEDGER")?.let { com.lifecyclebot.engine.RunnerExitShadowLedger.importState(it) } } catch (_: Throwable) {}  // V5.0.4289
        try { getBlob("LIVE_WALLET_GROWTH_GOVERNOR")?.let { com.lifecyclebot.engine.LiveWalletGrowthGovernorReport.importState(it) } } catch (_: Throwable) {}  // V5.0.4290
        // V5.9.949 — restore the rest of the brain.
        try { getBlob("BEHAVIOR_LEARNING")?.let { com.lifecyclebot.engine.BehaviorLearning.importState(it) } } catch (_: Throwable) {}
        try { getBlob("LAYER_READINESS")?.let { com.lifecyclebot.engine.LayerReadinessRegistry.importState(it) } } catch (_: Throwable) {}
        try { getBlob("CANONICAL_COUNTERS")?.let { com.lifecyclebot.engine.CanonicalLearningCounters.importState(it) } } catch (_: Throwable) {}
        try { getBlob("CREATOR_HISTORY")?.let { com.lifecyclebot.network.HeliusCreatorHistory.importState(it) } } catch (_: Throwable) {}
        try { getBlob("META_COGNITION")?.let { com.lifecyclebot.v3.scoring.MetaCognitionAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("COLLECTIVE_INTEL")?.let { com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("DIP_HUNTER")?.let     { com.lifecyclebot.v3.scoring.DipHunterAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("SOLANA_ARB")?.let     { com.lifecyclebot.v3.scoring.SolanaArbAI.importState(it) } } catch (_: Throwable) {}

        // V5.0.4205 — restore v3 scorer-brain blobs before runtime scoring.
        try { getBlob("HOLD_TIME_OPTIMIZER")?.let { com.lifecyclebot.v3.scoring.HoldTimeOptimizerAI.loadFromJson(JSONObject(it)) } } catch (_: Throwable) {}
        try { getBlob("ORDER_FLOW_IMBALANCE")?.let { com.lifecyclebot.v3.scoring.OrderFlowImbalanceAI.loadFromJson(JSONObject(it)) } } catch (_: Throwable) {}
        try { getBlob("SMART_MONEY_DIVERGENCE")?.let { com.lifecyclebot.v3.scoring.SmartMoneyDivergenceAI.loadFromJson(JSONObject(it)) } } catch (_: Throwable) {}
        try { getBlob("VOLATILITY_REGIME")?.let { com.lifecyclebot.v3.scoring.VolatilityRegimeAI.loadFromJson(JSONObject(it)) } } catch (_: Throwable) {}
        try { getBlob("LIQUIDITY_CYCLE")?.let { com.lifecyclebot.v3.scoring.LiquidityCycleAI.loadFromJson(JSONObject(it)) } } catch (_: Throwable) {}

        // V5.0.4208 — restore narrative/exit learner state.
        try { getBlob("MEME_NARRATIVE")?.let { com.lifecyclebot.v3.scoring.MemeNarrativeAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("CULT_MOMENTUM")?.let { com.lifecyclebot.v3.scoring.CultMomentumAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("SELL_OPTIMIZATION")?.let { com.lifecyclebot.v3.scoring.SellOptimizationAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("REGIME_TRANSITION")?.let { com.lifecyclebot.v3.scoring.RegimeTransitionAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("REFLEX_AI")?.let { com.lifecyclebot.v3.scoring.ReflexAI.importState(it) } } catch (_: Throwable) {}
        try { getBlob("INSIDER_TRACKER")?.let { com.lifecyclebot.v3.scoring.InsiderTrackerAI.importState(it) } } catch (_: Throwable) {}

        // V5.9.988 — SAFETY-FIRST restore order (Doctrine #3.36):
        // Pause list + daily auto-buy budget restored BEFORE other learners
        // so any start-time decision path sees the prior-process safety state.
        try { getBlob("SENTIENCE_HOOKS")?.let  { com.lifecyclebot.engine.SentienceHooks.importState(it) } } catch (_: Throwable) {}
        try { getBlob("NET_AUTO_BUYER")?.let   { com.lifecyclebot.perps.NetworkSignalAutoBuyer.importState(it) } } catch (_: Throwable) {}
        try { getBlob("SHADOW_LEARNING")?.let  { com.lifecyclebot.engine.ShadowLearningEngine.importState(it) } } catch (_: Throwable) {}
        try { getBlob("EFFICIENCY_LAYER")?.let { com.lifecyclebot.engine.EfficiencyLayer.importState(it) } } catch (_: Throwable) {}
        try { getBlob("PERPS_REPLAY")?.let     { com.lifecyclebot.perps.PerpsAutoReplayLearner.importState(it) } } catch (_: Throwable) {}
        // V5.9.991 — TradeLessonRecorder causal-chain learning corpus
        try { getBlob("TRADE_LESSONS")?.let    { com.lifecyclebot.v4.meta.TradeLessonRecorder.importState(it) } } catch (_: Throwable) {}
        try { getBlob("LANE_EXIT_TUNER")?.let  { com.lifecyclebot.engine.learning.LaneExitTuner.importState(it) } } catch (_: Throwable) {}  // V5.9.1379
        try { getBlob("COLD_STREAK_DAMPER")?.let { com.lifecyclebot.engine.runtime.ColdStreakDamper.importState(it) } } catch (_: Throwable) {}  // V5.9.1381
    }

    // ═════════════════════════════════════════════════════════════════
    // V5.9.439 — GENERIC BRAIN-STATE KV API
    //   Any object can mirror its in-memory state to disk by providing
    //   a JSON string via exportState()/importState() and piggy-backing
    //   on this shared kv table instead of its own SQLiteOpenHelper.
    //   Used for FluidLearningAI meme counters + SentienceOrchestrator
    //   reflection log so the full brain survives reboots.
    // ═════════════════════════════════════════════════════════════════

    /** V5.9.1321 — public KV save (delegates to internal blob put). */
    fun save(key: String, json: String) = putBlob(key, json)

    /** V5.9.1321 — public KV load (delegates to internal blob get). */
    fun load(key: String): String? = getBlob(key)

    private fun putBlob(name: String, json: String) {
        val d = db ?: return
        if (Looper.getMainLooper().thread == Thread.currentThread() && !d.inTransaction()) {
            GlobalScope.launch(Dispatchers.IO) { putBlob(name, json) }
            try { PipelineHealthCollector.labelInc("LEARNING_PERSISTENCE_PUTBLOB_OFFMAIN") } catch (_: Throwable) {}
            return
        }
        try {
            d.execSQL("DELETE FROM kv WHERE tracker = 'BLOB' AND bucket = ?", arrayOf(name))
            d.execSQL(
                "INSERT OR REPLACE INTO kv (tracker, bucket, payload, updated) VALUES ('BLOB',?,?,?)",
                arrayOf(name, json, System.currentTimeMillis()),
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "putBlob($name) error: ${e.message}")
        }
    }

    private fun getBlob(name: String): String? {
        val d = db ?: return null
        try {
            d.rawQuery("SELECT payload FROM kv WHERE tracker = 'BLOB' AND bucket = ?", arrayOf(name)).use { c ->
                if (c.moveToNext()) return c.getString(0)
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "getBlob($name) error: ${e.message}")
        }
        return null
    }

    private fun saveTracker(d: SQLiteDatabase, tracker: String, state: Map<String, List<Double>>) {
        // Clear tracker's rows then insert all buckets. Single TX = atomic.
        d.execSQL("DELETE FROM kv WHERE tracker = ?", arrayOf(tracker))
        val now = System.currentTimeMillis()
        state.forEach { (bucket, pnls) ->
            val payload = JSONArray()
            pnls.forEach { payload.put(it) }
            d.execSQL(
                "INSERT OR REPLACE INTO kv (tracker, bucket, payload, updated) VALUES (?,?,?,?)",
                arrayOf(tracker, bucket, payload.toString(), now),
            )
        }
    }

    private fun loadTracker(d: SQLiteDatabase, tracker: String): Map<String, List<Double>> {
        val out = mutableMapOf<String, List<Double>>()
        try {
            d.rawQuery("SELECT bucket, payload FROM kv WHERE tracker = ?", arrayOf(tracker)).use { c ->
                while (c.moveToNext()) {
                    val bucket = c.getString(0)
                    val payload = c.getString(1)
                    val arr = JSONArray(payload)
                    val pnls = ArrayList<Double>(arr.length())
                    for (i in 0 until arr.length()) pnls.add(arr.getDouble(i))
                    out[bucket] = pnls
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "loadTracker($tracker) error: ${e.message}")
        }
        return out
    }

    /**
     * V5.9.1353 — TRUE RESET ALL LEARNING (single source of truth).
     *
     * ROOT-CAUSE FIX: "Reset Learning" only called FluidLearningAI.resetAllLearning()
     * + BehaviorAI.reset() — leaving the canonical counters (1755), the outcome bus,
     * the layer-readiness registry (n=1200 layers), and ~25 other persisted stores
     * untouched on disk. On next boot loadAll() reloaded them all, so the totals
     * came straight back and new trades stacked on top ("double counting").
     *
     * This wipes BOTH halves so they can never drift again:
     *   (1) the entire kv table on disk (every blob + tracker), and
     *   (2) the in-memory state of every store that saveAll() persists.
     * The store list below MIRRORS saveAll() exactly — keep them in lockstep.
     */
    fun resetAll() {
        // ── (1) wipe disk so a reboot can't resurrect old totals ──
        try {
            db?.execSQL("DELETE FROM kv")
            ErrorLogger.info(TAG, "🧹 resetAll: kv table cleared")
        } catch (e: Exception) { ErrorLogger.warn(TAG, "resetAll db wipe error: ${e.message}") }

        // ── (2) zero every in-memory store (mirrors saveAll order) ──
        fun z(name: String, block: () -> Unit) { try { block() } catch (e: Throwable) { ErrorLogger.warn(TAG, "resetAll $name: ${e.message}") } }
        z("SCORE") { ScoreExpectancyTracker.reset() }
        z("HOLD")  { HoldDurationTracker.reset() }
        z("EXIT")  { ExitReasonTracker.reset() }
        z("FLUID") { com.lifecyclebot.v3.scoring.FluidLearningAI.resetAllLearning(appCtx ?: return@z) }
        z("BEHAVIOR_LEARNING") { com.lifecyclebot.engine.BehaviorLearning.clear() }
        z("LAYER_READINESS")   { com.lifecyclebot.engine.LayerReadinessRegistry.reset() }
        z("CANONICAL_COUNTERS"){ com.lifecyclebot.engine.CanonicalLearningCounters.reset() }
        z("CANONICAL_BUS")     { com.lifecyclebot.engine.CanonicalOutcomeBus.reset() }
        z("META_COGNITION")    { com.lifecyclebot.v3.scoring.MetaCognitionAI.reset() }
        z("AUTONOMOUS_META")   { com.lifecyclebot.engine.AutonomousMetaPolicy.reset() }
        z("FORWARD_OUTCOME")   { com.lifecyclebot.engine.ForwardOutcomeModel.reset() }
        z("SIGNAL_QUALITY")    { com.lifecyclebot.engine.SignalQualityTracker.reset() }
        z("STRATEGY_HYPOTHESIS"){ com.lifecyclebot.engine.StrategyHypothesisEngine.reset() }
        z("ASYNC_STRATEGY_LAB"){ com.lifecyclebot.engine.AsyncStrategyLab.reset() }
        z("SEMANTIC_PATTERN_GRAPH"){ com.lifecyclebot.engine.SemanticPatternGraph.reset() }
        z("COUNTERFACTUAL_REPLAY"){ com.lifecyclebot.engine.CounterfactualReplayEngine.reset() }
        z("RESEARCH_SCOUT"){ com.lifecyclebot.engine.ResearchScout.reset() }
        z("REFLECTIVE_OPTIMIZER_GEPA"){ com.lifecyclebot.engine.ReflectiveOptimizerGEPA.reset() }
        z("MULTIPLIER_ATTRIBUTION"){ com.lifecyclebot.engine.MultiplierAttributionLedger.reset() }
        z("EXIT_COST_MICROBRAIN"){ com.lifecyclebot.engine.ExitCostMicrobrain.reset() }
        z("RUNNER_RETENTION_OPTIMIZER"){ com.lifecyclebot.engine.RunnerRetentionOptimizer.reset() }
        z("CAPITAL_EFFICIENCY"){ com.lifecyclebot.engine.CapitalEfficiencyBrain.reset() }
        z("SOURCE_FAMILY_SCORECARD"){ com.lifecyclebot.engine.SourceFamilyOpportunityScorecard.reset() }
        z("RUNNER_EXIT_SHADOW_LEDGER"){ com.lifecyclebot.engine.RunnerExitShadowLedger.reset() }
        z("LIVE_WALLET_GROWTH_GOVERNOR"){ com.lifecyclebot.engine.LiveWalletGrowthGovernorReport.reset() }
        z("COLLECTIVE_INTEL")  { com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.reset() }
        // UnifiedPolicyHead has no reset(): clear its persisted blob so the next
        // boot re-initialises fresh weights; in-memory weights keep drifting from
        // a fresh-trade baseline which is acceptable (they re-train immediately).
        ErrorLogger.info(TAG, "✅ resetAll: all learning stores zeroed (disk + memory)")
    }

}
