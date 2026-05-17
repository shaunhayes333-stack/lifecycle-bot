package com.lifecyclebot.v3.scoring

import com.lifecyclebot.perps.PriceAggregator
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * FluidLearningAI - Layer 23: Centralized Fluidity Control
 * 
 * PHILOSOPHY: The AI should start LOOSE and tighten as it learns.
 * This layer is the SINGLE SOURCE OF TRUTH for all fluid thresholds.
 * 
 * All components (FDG, Scanners, MarketStructureRouter, CashGenerationAI, etc.)
 * should query this layer for their thresholds instead of using hardcoded values.
 * 
 * LEARNING CURVE:
 *   0 trades   → 0% learned   → Use BOOTSTRAP values (very loose)
 *   125 trades → 25% learned  → Slightly tighter
 *   250 trades → 50% learned  → Moderate thresholds
 *   375 trades → 75% learned  → Getting strict
 *   500 trades → 100% learned → Use MATURE values (full strictness)
 * 
 * Win rate also affects learning speed:
 *   - High win rate (>60%) → Learn faster (+10% bonus)
 *   - Low win rate (<40%)  → Learn slower (-10% penalty)
 */
object FluidLearningAI {
    
    private const val TAG = "FluidLearningAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES (defined at top for visibility)
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class HeuristicSignal(
        val shouldEnter: Boolean,
        val confidence: Double,
        val reason: String,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile
    private var isInitialized = false
    
    fun init() {
        // V4.0 CRITICAL: Guard against re-initialization
        if (isInitialized) {
            ErrorLogger.warn(TAG, "⚠️ init() called again - BLOCKED (already initialized)")
            return
        }
        isInitialized = true
        // V5.9.695 — snap lifetime sells as session baseline
        sessionLifetimeBaseline = try {
            TradeHistoryStore.getLifetimeStats().totalSells
        } catch (_: Exception) { 0 }
        ErrorLogger.info(TAG, "🧠 FluidLearningAI initialized (ONE-TIME) | " +
            "bootstrap=0-$BOOTSTRAP_PHASE_END | mature=$BOOTSTRAP_PHASE_END-$MATURE_PHASE_END | continuous=$MATURE_PHASE_END+ | " +
            "sessionBaseline=$sessionLifetimeBaseline currentProgress=${(getLearningProgress() * 100).toInt()}%")
    }

    /**
     * V5.8.0: Call from BotService.onCreate() AFTER init().
     * Loads persisted Markets trade/win counts so the progress bar
     * survives app restarts and doesn't permanently show "Initializing".
     */
    fun initMarketsPrefs(context: android.content.Context) {
        val prefs = context.getSharedPreferences(MARKETS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        marketsPrefs = prefs
        val savedTrades = prefs.getInt(KEY_MARKETS_TRADES, 0)
        val savedWins   = prefs.getInt(KEY_MARKETS_WINS,   0)
        if (savedTrades > 0) {
            marketsSessionTrades.set(savedTrades)
            marketsSessionWins.set(savedWins)
            ErrorLogger.info(TAG, "📊 Markets counters restored: $savedTrades trades, $savedWins wins")
        }
    }

    /** Save Markets counters to SharedPreferences. */
    fun saveMarketsPrefs() {
        marketsPrefs?.edit()
            ?.putInt(KEY_MARKETS_TRADES, marketsSessionTrades.get())
            ?.putInt(KEY_MARKETS_WINS,   marketsSessionWins.get())
            ?.apply()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING PROGRESS TRACKING
    // V5.7.6b: SEPARATE counters for Meme and Markets to prevent cross-contamination
    // ═══════════════════════════════════════════════════════════════════════════
    
    // MEME MODE counters (used by BotService, Executor, FDG, Scanners)
    private val sessionTrades = AtomicInteger(0)
    private val sessionWins = AtomicInteger(0)
    // V5.9.695 — snapshot of TradeHistoryStore.lifetimeSells at session start.
    // Prevents getTotalTradeCount() from double-counting in-session: lifetimeSells
    // bumps immediately on every close (bumpLifetimeFor) AND sessionTrades increments
    // simultaneously → old formula counted each session close twice.
    // Fix: lock lifetime anchor at session start; only add session delta on top.
    @Volatile private var sessionLifetimeBaseline: Int = 0
    private val lastProgressUpdate = AtomicLong(0)
    private var cachedProgress = 0.0
    
    // MARKETS MODE counters (used by TokenizedStockTrader, CommoditiesTrader, etc.)
    private val marketsSessionTrades = AtomicInteger(0)
    private val marketsSessionWins = AtomicInteger(0)
    private val marketsLastProgressUpdate = AtomicLong(0)
    private var marketsCachedProgress = 0.0

    // V5.8.0: Persistent storage for Markets trade/win counts (survives app restart)
    @Volatile private var marketsPrefs: android.content.SharedPreferences? = null
    private const val MARKETS_PREFS_NAME = "fluid_learning_markets"
    private const val KEY_MARKETS_TRADES = "markets_trades"
    private const val KEY_MARKETS_WINS   = "markets_wins"
    
    // V5.6: EXTENDED Learning curve - Never fully closes
    //   Phase 1 Bootstrap (0-1000 trades):   progress 0.0→0.5 (permissive thresholds, learning mode)
    //   Phase 2 Mature   (1000-3000 trades): progress 0.5→0.8 (moderately tightening)
    //   Phase 3 Continuous (3000+ trades):   progress 0.8 MAX (never fully restricts - always trades)
    // 
    // V5.6 FIX: Bot was getting too restrictive after 2000 trades because:
    //   - Old: Matured to 1.0 (100%) at 1000 trades → stopped trading
    //   - New: Caps at 0.8 (80%) so thresholds never fully close
    //   - This ensures CONTINUOUS trading even with 10,000+ trades
    //
    // V5.9.149 — PHASE BOUNDARIES RESTORED.
    //
    // V5.9.8 had cut these 5× (1000→200, 3000→500, 5000→2000). On paper it
    // sounded nice ("learn faster"), in reality it starves a meme-coin bot
    // that needs THOUSANDS of shots to learn a pattern.
    //
    // Field evidence (user screenshot, 04-23):
    //   • 135 session trades, ~200 lifetime → Progress to Live = 52%
    //   • That 52% mutates every single fluid gate:
    //       getExecuteFloor       : 25  → 35  (rejects score<35 outright)
    //       fluidMinConfForExecute: 25  → 38
    //       cGradeConfFloor       : 20  → 33
    //       standardConfFloor B   : 35  → 40  (pushed by min-conf+10)
    //   • Pippin (scanner score=32), TROLL (17) both REJECTED that used to
    //     buy easily → volume collapsed from 1000+/hr to a trickle.
    //
    // Restoring the original 1000/3000/5000 ramp keeps the bot in a
    // near-bootstrap posture for 1000 trades (roughly a full day of scanning)
    // and transitions to mature over the subsequent 2000-5000 trades — the
    // cadence that produced the 1000+/hr regime the user is trying to get
    // back. MAX_LEARNING_PROGRESS cap (1.0) is unchanged so the mature end
    // still behaves identically once the bot actually earns it.
    // V5.9.169 — user directive "nothing should be mature at 50 trades",
    // "scaling to 80% theoretical rate past 10,000 trades". Bootstrap
    // extended 3× so 50 trades is <2% progress (deep bootstrap), mature
    // (80%+) lands at the 10,000-trade target the user specified.
    private const val BOOTSTRAP_PHASE_END = 1000  // V5.9.217: restored — bootstrap is 0-1000 trades
    private const val MATURE_PHASE_END = 3000   // V5.9.217: restored — learning is 1000-3000 trades
    private const val EXPERT_PHASE_END = 5000   // V5.9.217: restored — maturity at 5000+ trades
    private const val MAX_LEARNING_PROGRESS = 1.0  // V5.9: Full expert at 5000+ trades
    
    // V5.9.179 — bootstrap floor dropped from 75 → 5. The old value was
    // unreachable for fresh meme/V3 launches whose scores run 14-40. Combined
    // with the secondary gate (effectiveFloor below) this was the primary
    // reason < 200 trades/day during bootstrap. 5 = "anything above dust".
    private const val EARLY_BOOTSTRAP_TRADES = 50
    private const val EARLY_BOOTSTRAP_MIN_SCORE = 5
    
    /**
     * V5.2: Reset all learning progress.
     * WARNING: This clears ALL learned data - use with caution!
     * Called from BehaviorActivity reset button.
     */
    fun resetAllLearning(context: android.content.Context) {
        // Reset session counters
        sessionTrades.set(0)
        sessionWins.set(0)
        cachedProgress = 0.0
        lastProgressUpdate.set(0)
        
        // Reset behavior modifier
        behaviorModifier = 0.0
        aggressionModifier = 0.0
        
        // Clear historical trade stats AND lifetime counters (explicit reset only).
        try {
            TradeHistoryStore.fullResetIncludingLifetime()
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to clear TradeHistoryStore: ${e.message}")
        }
        
        // Clear any other learning-related storage
        try {
            val prefs = context.getSharedPreferences("fluid_learning", android.content.Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
        } catch (_: Exception) {}
        
        ErrorLogger.warn(TAG, "🔄 ALL LEARNING RESET | Progress now 0%")
    }
    
    /**
     * Get total trade count (historical + session).
     * Used for bootstrap calculations and logging.
     *
     * V5.9.115: Uses lifetime counters that survive journal clears so the
     * user can decluter the journal without wiping learned progress.
     *
     * V5.9.801: operator audit Fix E — force the legacy session counter
     * onto the canonical bus when canonical has observed at least one
     * outcome. Pre-V5.9.801 used the local `sessionTrades` AtomicInteger
     * which is incremented in `recordLiveTrade`/`recordPaperTrade`. The
     * canonical bus (`CanonicalLearningCounters.canonicalOutcomesTotal`)
     * is incremented in `CanonicalLearning.normaliseAndDispatch` at the
     * single SoT for trade outcomes. Any path that recorded directly to
     * FluidLearning without also publishing to the canonical bus (or
     * vice-versa) would drift — the bot's "trades observed" number on
     * the brain side would diverge from the canonical journal. Sourcing
     * from canonical eliminates the drift entirely. The local counter is
     * kept as a fallback for the very first boot before canonical has
     * fired (so existing bootstrap progress doesn't snap to zero).
     */
    fun getTotalTradeCount(): Int {
        // V5.9.695 — use (baseline + session delta) not (lifetimeSells + sessionTrades).
        // lifetimeSells grows in-session as trades close (bumpLifetimeFor fires immediately),
        // so the old formula counted every session close twice. Correct total =
        // sessionLifetimeBaseline (snapped at boot) + sessionTrades (delta only).
        val canonicalDelta = try {
            com.lifecyclebot.engine.CanonicalLearningCounters.canonicalOutcomesTotal.get().toInt()
        } catch (_: Throwable) { 0 }
        val sessionDelta = if (canonicalDelta > 0) canonicalDelta else sessionTrades.get()
        return sessionLifetimeBaseline + sessionDelta
    }

    /** V5.9.719 — session-only trade count (excludes Turso historical baseline).
     *  Use this for drift comparison against the canonical bus (which also counts
     *  only session outcomes). getTotalTradeCount() includes the Turso baseline
     *  which makes the Δ display look like massive fragmentation when it's just history. */
    fun getSessionTradeCount(): Int = sessionTrades.get()

    /** V5.9.719 — the Turso history baseline loaded at boot. */
    fun getHistoricalBaseline(): Int = sessionLifetimeBaseline
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BEHAVIOR MODIFIER (From BehaviorAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Behavior modifier: -1.0 to +1.0
    // Negative = tighten thresholds (bad behavior like loss streaks)
    // Positive = loosen thresholds (good behavior like win streaks, 10x runs)
    @Volatile
    private var behaviorModifier = 0.0
    
    // V5.2: Aggression modifier from BehaviorAI dashboard (0-11 scale → -0.5 to +0.5)
    private var aggressionModifier = 0.0
    
    /**
     * Apply a behavior modifier from BehaviorAI.
     * This affects all fluid thresholds.
     * 
     * @param modifier -1.0 (very bad behavior) to +1.0 (excellent behavior)
     */
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.1 COLD-START FIX: BOOTSTRAP ENTRY OVERRIDE
    // 
    // Problem: No trades → no data → no learning → no confidence → no trades
    // This is a classic cold-start deadlock.
    //
    // Solution: Force controlled entries during bootstrap to break the deadlock:
    // V5.2: MORE LENIENT conditions during learning - we NEED trades to learn!
    // 1. Minimum age 1 minute (quick price discovery)
    // 2. Buy pressure >= 45% (decent demand)
    // 3. Score >= 50 (reasonable setup - not too strict!)
    // 4. Liquidity >= $2K (basic safety)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // V5.9.65: age floor 1.0min → 0.25min (15s). User's log showed
    // multiple fresh pump.fun grads (FOF age=0.72m, PEPEv27 age=0.92m,
    // Chihuahua age=0.94m) all skipped at the 1-min floor — but the
    // best pump.fun plays ARE the first 60s. Let the bot see them.
    private const val MIN_TOKEN_AGE_BOOTSTRAP = 0.25  // 15 seconds
    // V5.9.300: V5.9.198 ARCHITECTURE RESTORATION — Global floors LOW, per-trader floors HIGH.
    // The previous topology (global tight, per-trader loose) inverted the V5.9.198 design that hit 50%+ WR.
    // Now: global gates open the floodgates; ShitCoin/BlueChip/Markets enforce strict quality at the trader layer.
    private const val MIN_BUY_PRESSURE_BOOTSTRAP = 30.0  // V5.9.328: 5→30 — 5% was noise-level; need real buy momentum
    private const val MIN_SCORE_BOOTSTRAP = 20           // V5.9.328: 5→20 — score=5 lets garbage through after V5.9.316 removed per-trader hard gates
    private const val MIN_LIQUIDITY_BOOTSTRAP = 2000.0   // V5.9.328: 1000→2000 — require minimal real pool depth
    
    /**
     * Check if we should force a bootstrap entry to break the cold-start deadlock.
     * V5.2: MORE LENIENT to get more trades for learning
     */
    fun shouldForceBootstrapEntry(
        score: Int,
        liquidityUsd: Double,
        tokenAgeMinutes: Double,
        buyPressurePct: Double,
        isPaper: Boolean
    ): Boolean {
        // V5.9.322: Bootstrap assist ends at BOOTSTRAP_PHASE_END (1000 trades) — hard raw count.
        // Previously used getLearningProgress() >= 0.70, but with 11% WR the adaptive
        // regression pulls progress down to ~0.56 at 3000+ trades, keeping bootstrap assist
        // alive and letting score=5 / buyPressure=5% garbage through all quality gates.
        // Raw trade count is immune to the adaptive regression feedback loop.
        val rawTotalTrades = getTotalTradeCount()
        // V5.9.606 — paper learning was starving at ~3000 trades: free-range
        // had re-opened, but this bootstrap assist still hard-stopped at 1000,
        // so Treasury/bridge had no force-entry path despite WR < target.
        // Keep paper assist alive while FreeRangeMode says the bot still needs
        // exposure. Live remains on the original stricter 1000-trade cutoff.
        val freeRangeLearning = try { com.lifecyclebot.engine.FreeRangeMode.isWideOpen() } catch (_: Throwable) { false }
        if (!isPaper && rawTotalTrades >= BOOTSTRAP_PHASE_END) return false
        if (isPaper && !freeRangeLearning && rawTotalTrades >= BOOTSTRAP_PHASE_END) return false
        if (!freeRangeLearning && getLearningProgress() >= 0.60) return false           // secondary progress check
        
        // V5.2: Quick age check - only wait 1 minute
        if (tokenAgeMinutes < MIN_TOKEN_AGE_BOOTSTRAP) {
            ErrorLogger.debug(TAG, "⏳ Bootstrap skip: age=${tokenAgeMinutes}m < ${MIN_TOKEN_AGE_BOOTSTRAP}m min")
            return false
        }
        
        // V5.2: Looser buy pressure threshold
        if (buyPressurePct < MIN_BUY_PRESSURE_BOOTSTRAP) {
            ErrorLogger.debug(TAG, "📉 Bootstrap skip: buy%=$buyPressurePct < $MIN_BUY_PRESSURE_BOOTSTRAP min")
            return false
        }
        
        // Paper mode: MORE LENIENT to generate learning data
        if (isPaper) {
            return score >= MIN_SCORE_BOOTSTRAP && 
                   liquidityUsd >= MIN_LIQUIDITY_BOOTSTRAP &&  // $5K minimum
                   tokenAgeMinutes >= MIN_TOKEN_AGE_BOOTSTRAP &&
                   buyPressurePct >= MIN_BUY_PRESSURE_BOOTSTRAP
        }
        
        // Live mode: slightly stricter but still reasonable
        return score >= 55 &&  // V5.2 FIX: Lowered from 60
               liquidityUsd >= MIN_LIQUIDITY_BOOTSTRAP &&  // $5K minimum
               tokenAgeMinutes >= MIN_TOKEN_AGE_BOOTSTRAP &&
               buyPressurePct >= 45  // V5.2 FIX: Lowered from 50
    }
    
    /**
     * Get synthetic confidence boost for bootstrap mode.
     * This simulates early confidence until real learning kicks in.
     * 
     * Formula: bootstrapBoost = min(20%, tradeCount * 0.5%)
     * 
     * At 0 trades: +0%
     * At 10 trades: +5%
     * At 20 trades: +10%
     * At 40+ trades: +20% (capped)
     */
    fun getBootstrapConfidenceBoost(): Double {
        val tradeCount = getTotalTradeCount()
        val boost = (tradeCount * 0.5).coerceAtMost(25.0)
        return boost
    }
    
    /**
     * Get adjusted confidence including bootstrap boost.
     * Use this instead of raw confidence during bootstrap.
     */
    fun getAdjustedConfidence(rawConfidence: Double, isPaper: Boolean): Double {
        val progress = getLearningProgress()
        
        // Apply bootstrap boost only during early learning
        val boost = if (progress < 0.3) {
            getBootstrapConfidenceBoost()
        } else {
            0.0
        }
        
        // Apply behavior modifier
        val behaviorAdjustment = rawConfidence * behaviorModifier * 0.1

        // V5.9.11: Symbolic edge bonus — strong symbolic edge lifts confidence
        // up to +8%; weak edge subtracts up to -5%.
        val symAdjust = try {
            val edge = com.lifecyclebot.engine.SymbolicContext.edgeStrength
            val risk = com.lifecyclebot.engine.SymbolicContext.overallRisk
            ((edge - 0.5) * 16.0) - ((risk - 0.5).coerceAtLeast(0.0) * 10.0)
        } catch (_: Exception) { 0.0 }

        return (rawConfidence + boost + behaviorAdjustment + symAdjust).coerceIn(0.0, 100.0)
    }
    
    /**
     * Get bootstrap position size multiplier.
     * During bootstrap, use micro-positions (0.01-0.05 SOL).
     */
    fun getBootstrapSizeMultiplier(): Double {
        val progress = getLearningProgress()
        
        val baseMult = when {
            progress < 0.05 -> 0.50  // V5.9.182: was 10%
            progress < 0.15 -> 0.60  // V5.9.182: was 25%
            progress < 0.30 -> 0.75  // V5.9.182: was 50%
            progress < 0.50 -> 0.90  // V5.9.182: was 75%
            else -> 1.0               // Full size when mature
        }

        // V5.9.11: Symbolic bias — mood + edge strength shape size
        // PANIC/FEARFUL shrinks further, EUPHORIC/confident boosts (clamped)
        val symMult = try {
            com.lifecyclebot.engine.SymbolicContext.getSizeAdjustment()
        } catch (_: Exception) { 1.0 }
        return (baseMult * symMult).coerceIn(0.05, 1.25)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.7: BOOTSTRAP SCORE GATE
    // 
    // During first 50 trades, require higher confidence to prevent drawdown
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if a trade should be blocked due to bootstrap score requirements.
     * During the first 50 trades, only allow scores >= 75 to prevent early drawdown.
     * 
     * @param score The signal score (0-100)
     * @return true if trade should be BLOCKED, false if allowed
     */
    fun shouldBlockBootstrapTrade(score: Int): Boolean {
        // V5.9.408 — free-range learning mode: never block a candidate
        // during the first 3000 trades (and up to 5000 if not yet healthy).
        if (com.lifecyclebot.engine.FreeRangeMode.isWideOpen()) return false

        val totalTrades = getTotalTradeCount()

        // V5.9.65: Bypass entirely if the 30-Day Proof Run already has
        // meaningful trade history. The FluidLearningAI counter is
        // per-session and drifts vs the proof run (user reported 40/50
        // bootstrap while RunTracker30D showed 1550 trades). If we have
        // ANY real multi-trade history we're past cold-start.
        try {
            if (com.lifecyclebot.engine.RunTracker30D.totalTrades >= EARLY_BOOTSTRAP_TRADES) return false
        } catch (_: Throwable) {}

        // After first 50 trades, no blocking
        if (totalTrades >= EARLY_BOOTSTRAP_TRADES) return false

        // V5.9.179 — effectiveFloor dropped 25 → 5 so fresh launches with
        // V3 scores in the 14-24 band aren't silently shadowed during
        // bootstrap. Paper must LEARN from these — user explicitly wants
        // every D+ candidate to get a shot during the first 50 trades.
        val effectiveFloor = 5
        val shouldBlock = score > 0 && score < effectiveFloor

        if (shouldBlock) {
            ErrorLogger.debug(TAG, "🚫 BOOTSTRAP GATE: Trade #${totalTrades + 1}/$EARLY_BOOTSTRAP_TRADES blocked (score $score < $effectiveFloor)")
        }

        return shouldBlock
    }
    
    /**
     * Get bootstrap status info for logging
     */
    fun getBootstrapStatus(): String {
        val trades = getTotalTradeCount()
        return if (trades < EARLY_BOOTSTRAP_TRADES) {
            "BOOTSTRAP ${trades}/$EARLY_BOOTSTRAP_TRADES (min score: $EARLY_BOOTSTRAP_MIN_SCORE)"
        } else {
            "NORMAL (trades: $trades)"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.1: HEURISTIC FALLBACK (When patterns = 0)
    // 
    // When CollectiveAI has no patterns, use a simple heuristic model
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Simple heuristic model for when AI has no learned patterns.
     * Uses basic market signals: buy pressure, liquidity, age, momentum.
     */
    fun getHeuristicSignal(
        buyPressurePct: Double,
        liquidityUsd: Double,
        tokenAgeMinutes: Double,
        momentum: Double,
        score: Int,
    ): HeuristicSignal {
        var confidence = 20.0  // Base confidence
        val reasons = mutableListOf<String>()
        
        // Buy pressure signal
        when {
            buyPressurePct >= 70 -> { confidence += 25; reasons.add("strong_buy_pressure") }
            buyPressurePct >= 55 -> { confidence += 15; reasons.add("good_buy_pressure") }
            buyPressurePct >= 45 -> { confidence += 5; reasons.add("neutral_pressure") }
            else -> { confidence -= 10; reasons.add("weak_pressure") }
        }
        
        // Liquidity signal
        when {
            liquidityUsd >= 10000 -> { confidence += 15; reasons.add("high_liquidity") }
            liquidityUsd >= 5000 -> { confidence += 10; reasons.add("good_liquidity") }
            liquidityUsd >= 2000 -> { confidence += 5; reasons.add("ok_liquidity") }
            else -> { confidence -= 5; reasons.add("low_liquidity") }
        }
        
        // Age signal (fresher = better for momentum plays)
        when {
            tokenAgeMinutes <= 5 -> { confidence += 15; reasons.add("very_fresh") }
            tokenAgeMinutes <= 15 -> { confidence += 10; reasons.add("fresh") }
            tokenAgeMinutes <= 30 -> { confidence += 5; reasons.add("young") }
            else -> { confidence -= 5; reasons.add("older") }
        }
        
        // Momentum signal
        when {
            momentum >= 20 -> { confidence += 15; reasons.add("strong_momentum") }
            momentum >= 10 -> { confidence += 10; reasons.add("good_momentum") }
            momentum >= 0 -> { confidence += 5; reasons.add("neutral_momentum") }
            else -> { confidence -= 10; reasons.add("negative_momentum") }
        }
        
        // Score signal
        when {
            score >= 80 -> { confidence += 10; reasons.add("high_score") }
            score >= 50 -> { confidence += 5; reasons.add("good_score") }
            else -> {}
        }
        
        confidence = confidence.coerceIn(0.0, 100.0)
        
        // Threshold for entry
        val shouldEnter = confidence >= 45  // Lower threshold for heuristic mode
        
        return HeuristicSignal(
            shouldEnter = shouldEnter,
            confidence = confidence,
            reason = reasons.joinToString(", ")
        )
    }
    
    fun applyBehaviorModifier(modifier: Double) {
        behaviorModifier = modifier.coerceIn(-1.0, 1.0)
        ErrorLogger.info(TAG, "🧠 Behavior modifier: ${if (modifier >= 0) "+" else ""}${(modifier * 100).toInt()}%")
    }
    
    /**
     * Get the current behavior modifier.
     */
    fun getBehaviorModifier(): Double = behaviorModifier
    
    /**
     * V5.2: Set aggression modifier from BehaviorAI dashboard.
     * Range: -0.5 (ultra defensive) to +0.5 (goes to 11)
     */
    fun setAggressionModifier(modifier: Double) {
        aggressionModifier = modifier.coerceIn(-0.5, 0.5)
        ErrorLogger.info(TAG, "🎚️ Aggression modifier: ${if (modifier >= 0) "+" else ""}${(modifier * 100).toInt()}%")
    }
    
    fun getAggressionModifier(): Double = aggressionModifier
    
    /**
     * Get current learning progress (0.0 = brand new, 1.0 = fully mature).
     * Cached for 10 seconds to avoid expensive recalculations.
     */
    fun getLearningProgress(): Double {
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdate.get() < 10_000 && cachedProgress > 0) {
            return cachedProgress
        }
        
        // Get historical + session trades
        // V5.9.115: Use lifetime stats so journal clears don't wipe progress.
        val lifetime = try {
            TradeHistoryStore.getLifetimeStats()
        } catch (_: Exception) { null }

        val historicalTrades = lifetime?.totalSells ?: 0
        val historicalWinRate = lifetime?.winRate ?: 50.0
        
        val totalTrades = historicalTrades + sessionTrades.get()
        val sessionWinRate = if (sessionTrades.get() > 0) {
            sessionWins.get().toDouble() / sessionTrades.get() * 100
        } else historicalWinRate
        
        // Blend historical and session win rates
        val blendedWinRate = if (historicalTrades > 0 && sessionTrades.get() > 0) {
            (historicalWinRate * historicalTrades + sessionWinRate * sessionTrades.get()) / 
            (historicalTrades + sessionTrades.get())
        } else if (historicalTrades > 0) {
            historicalWinRate
        } else {
            sessionWinRate
        }
        
        // V5.9: 4-Phase learning curve scaling to 5000 trades
        val baseProgress = when {
            totalTrades <= BOOTSTRAP_PHASE_END ->
                // Phase 1 Bootstrap (0-1000 trades): 0.0 → 0.50
                (totalTrades.toDouble() / BOOTSTRAP_PHASE_END) * 0.50
            totalTrades <= MATURE_PHASE_END ->
                // Phase 2 Mature (1000-3000 trades): 0.50 → 0.80
                0.50 + ((totalTrades - BOOTSTRAP_PHASE_END).toDouble() / (MATURE_PHASE_END - BOOTSTRAP_PHASE_END)) * 0.30
            totalTrades <= EXPERT_PHASE_END ->
                // Phase 3 Expert (3000-5000 trades): 0.80 → 1.0
                0.80 + ((totalTrades - MATURE_PHASE_END).toDouble() / (EXPERT_PHASE_END - MATURE_PHASE_END)) * 0.20
            else ->
                // Phase 4 Master (5000+ trades): Full 1.0 — maximum selectivity
                MAX_LEARNING_PROGRESS
        }

        // V5.9.370 — EARNED-WR PROGRESS CEILING.
        //
        // BUG (user, after 5000-trade run + restart): "wouldn't trade on the
        //   memetrader at all". After totalTrades >= 5000 baseProgress = 1.0
        //   (Master phase, max selectivity), pushing all dynamic floors
        //   (V3 techFloor=60, blendedFloor=55, FDG paper floor 45→max etc.)
        //   to their maximum — almost no incoming token clears the bar.
        //
        // ROOT CAUSE: the Master phase implicitly assumes mastery was earned
        //   with a profitable WR. A 5000-trade bot at 35% WR is *not* a
        //   master — it's an over-fitted unprofitable bot. Locking it at
        //   1.0 selectivity stops trading entirely.
        //
        // FIX: cap baseProgress against an "earned" ceiling derived from
        //   blended WR. Bot only graduates to higher selectivity once it
        //   has actually demonstrated higher WR.
        val earnedCeiling = when {
            blendedWinRate >= 60 -> MAX_LEARNING_PROGRESS  // ≥60% WR → full Master
            blendedWinRate >= 50 -> 0.85                    // 50-60% → high Expert
            blendedWinRate >= 40 -> 0.75                    // 40-50% → mid Mature
            blendedWinRate >= 30 -> 0.65                    // 30-40% → low Mature
            else                 -> 0.50                    // <30%   → Bootstrap ceiling
        }
        val cappedBaseProgress = baseProgress.coerceAtMost(earnedCeiling)

        val progress = when {
            totalTrades > MATURE_PHASE_END -> {
                // V5.9.322: CLOSED FEEDBACK LOOP — low WR no longer loosens gates at mature/expert.
                //
                // OLD LOGIC: <40% WR → progress -= 0.25 (loosens everything). This created a
                // runaway loop at 3000+ trades: bad WR → loose gates → more bad trades → worse WR.
                // At 3088 trades / 11% WR: progress regressed to 0.56, re-enabling bootstrap assist.
                //
                // NEW LOGIC: WR < 30% → TIGHTEN gates (bad entries are the problem, not lack of data).
                //            WR 30-50% → hold position (don't loosen, don't tighten aggressively).
                //            WR 50%+   → hold position (on target).
                // Floor raised to 0.65 at mature, 0.70 at expert so bootstrap assist NEVER re-enables.
                // V5.9.370 — uses cappedBaseProgress (earned-WR ceiling applied) so 5000-trade
                // bots at <50% WR aren't locked at full Master selectivity.
                val adaptiveFloor = if (totalTrades > EXPERT_PHASE_END) 0.70 else 0.65
                when {
                    blendedWinRate >= 50 -> cappedBaseProgress                                              // On target — hold selectivity
                    blendedWinRate < 20  -> (cappedBaseProgress + 0.05).coerceAtMost(MAX_LEARNING_PROGRESS)  // Very bad WR → tighten further
                    blendedWinRate < 30  -> cappedBaseProgress                                              // Bad WR → hold, don't loosen
                    blendedWinRate < 40  -> (cappedBaseProgress - 0.05).coerceAtLeast(adaptiveFloor)        // Below average → tiny loosen (was -0.25)
                    blendedWinRate < 50  -> (cappedBaseProgress - 0.05).coerceAtLeast(adaptiveFloor + 0.05) // Slightly below → minor loosen
                    else -> cappedBaseProgress
                }
            }
            else -> {
                // Phase 1-2: Win rate speeds/slows learning progression
                when {
                    blendedWinRate > 85 -> (cappedBaseProgress * 1.15).coerceAtMost(MAX_LEARNING_PROGRESS) // V5.9.184: >85% WR = elite, accelerate, capped
                    blendedWinRate < 25 -> cappedBaseProgress * 0.80                       // V5.9.184: <25% is bad; slow gates — target 25-50% struggling
                    else -> cappedBaseProgress
                }
            }
        }.coerceAtMost(MAX_LEARNING_PROGRESS)  // V5.9: Hard cap at 1.0 (full expert)

        cachedProgress = progress
        lastProgressUpdate.set(now)
        
        return progress
    }
    
    /**
     * Record a trade for learning progress.
     * V4.0: Now uses tiered weights based on trade type.
     * Use recordLiveTrade(), recordPaperTrade(), or recordShadowTrade() for proper weighting.
     * 
     * @deprecated Use the specific trade type methods instead
     */
    fun recordTrade(isWin: Boolean) {
        // Legacy method - defaults to paper weight for backwards compatibility
        recordPaperTrade(isWin)
    }

    /**
     * V5.9.495z21 — mint-aware overload. Short-circuits when the target
     * token never actually landed in the wallet (partial bridge / output
     * mismatch / recovery). Keeps the learning-progress counter honest
     * by not counting phantom trades that didn't really happen.
     */
    fun recordTrade(mint: String, isWin: Boolean) {
        if (!com.lifecyclebot.engine.execution.ExecutionStatusRegistry.shouldTrainStrategy(mint)) return
        recordPaperTrade(isWin)
    }
    
    /**
     * Record that a trade has started (for bootstrap counting).
     * Call this when any layer opens a position.
     */
    fun recordTradeStart() {
        // V5.9.9: Do NOT increment sessionTrades here — only closed trades count
        // Trade opens are counted by TradeHistoryStore via recordTradeStart intent
        lastProgressUpdate.set(0)  // Force progress recalculation
        ErrorLogger.debug(TAG, "📊 Trade started | total=${getTotalTradeCount()} | progress=${(getLearningProgress()*100).toInt()}%")
    }
    
    /**
     * V5.7.6b: Record a Markets trade start (separate from Meme).
     * Call this when Markets traders open a position.
     */
    fun recordMarketsTradeStart() {
        // V5.9.8: Count every position open as a full learning trade
        // The readiness bar must move visibly as the trader is actively trading
        // V5.9.9: Do NOT increment marketsSessionTrades here — only closed trades count
        marketsCachedProgress = 0.0  // Force recalculation
        marketsLastProgressUpdate.set(0)
        saveMarketsPrefs()
        ErrorLogger.debug(TAG, "📊 Markets trade started | total=${getMarketsTradeCount()} | progress=${(getMarketsLearningProgress()*100).toInt()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.0: TIERED LEARNING WEIGHT SYSTEM
    // 
    // Different trade types contribute differently to maturity:
    // - LIVE trades:   0.5 weight (real money, real consequences - most valuable)
    // - PAPER trades:  0.1 weight (real decisions, simulated consequences)
    // - SHADOW trades: 0.025 weight (simulated decisions, simulated consequences)
    // 
    // This ensures the bot learns appropriately from each type:
    // - 200 live trades = full contribution
    // - 1000 paper trades = full contribution
    // - 4000 shadow trades = full contribution
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val LIVE_LEARNING_WEIGHT = 3.0      // V5.9.183: 1 live close = 3 session trades (was 1.0)
    private const val PAPER_LEARNING_WEIGHT = 1.0     // V5.9.183: 1 paper close = 1 session trade (was 0.3/0.1)
    private const val SHADOW_LEARNING_WEIGHT = 0.025  // 2.5% weight - simulations help slowly
    
    // Accumulators for fractional trade progress
    private var liveTradeAccumulator = 0.0
    private var paperTradeAccumulator = 0.0
    private var shadowTradeAccumulator = 0.0
    private val tradeAccumulatorLock = Any()
    
    /**
     * Record a LIVE trade with highest learning weight (0.5 per trade).
     * 200 live trades = 100% maturity contribution from live.
     * Real money, real consequences - this is the gold standard for learning.
     */
    fun recordLiveTrade(isWin: Boolean, pnlPct: Double = 0.0) {
        // V5.9.187: WEIGHT=3 = progress speed, NOT win multiplier.
        // Old bug: looped 3x, sessionWins += 3 per real win = 3x WR inflation.
        sessionTrades.incrementAndGet()
        if (isWin) sessionWins.incrementAndGet()
        synchronized(tradeAccumulatorLock) {
            liveTradeAccumulator += LIVE_LEARNING_WEIGHT - 1.0
            while (liveTradeAccumulator >= 1.0) {
                sessionTrades.incrementAndGet()  // progress tick only — no win count
                liveTradeAccumulator -= 1.0
            }
        }
        lastProgressUpdate.set(0)
    }
    
    /**
     * Record a PAPER trade with medium learning weight (0.1 per trade).
     * 1000 paper trades = 100% maturity contribution from paper.
     * Real decisions, simulated consequences - valuable for learning patterns.
     */
    // V5.9.694 — dedup guard keyed by canonical tradeId (or mint+minute bucket).
    // Prevents the bus subscriber and any legacy direct caller from both
    // incrementing sessionTrades for the same close event.
    private val fluidSeenKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun recordPaperTrade(isWin: Boolean, pnlPct: Double = 0.0, dedupKey: String = "") {
        // If a dedup key is supplied, enforce once-only semantics.
        if (dedupKey.isNotBlank()) {
            if (fluidSeenKeys.putIfAbsent(dedupKey, System.currentTimeMillis()) != null) {
                ErrorLogger.debug(TAG, "⚡ FluidLearning DEDUP skip (paper): $dedupKey")
                return
            }
            if (fluidSeenKeys.size > 3000) {
                val cutoff = System.currentTimeMillis() - 120_000L
                fluidSeenKeys.entries.removeIf { it.value < cutoff }
            }
        }
        // V5.9.187: PAPER_WEIGHT=1.0 exactly. 1 trade = 1 count.
        sessionTrades.incrementAndGet()
        if (isWin) sessionWins.incrementAndGet()
        lastProgressUpdate.set(0)
    }

    // V5.9.388 — per-sub-trader counters so the MEME bucket (sessionTrades)
    // stays pure-meme. Before this, ShitCoin / Quality / BlueChip / Moonshot
    // / Treasury / DipHunter / ProjectSniper / SolanaArb / ShitCoinExpress
    // all called recordPaperTrade(), dumping into the MEME bucket and
    // inflating meme maturity with non-meme trades. Sub-traders now call
    // recordSubTraderTrade(); meme base keeps calling recordPaperTrade().
    private val subTraderSessionTrades = AtomicInteger(0)
    private val subTraderSessionWins   = AtomicInteger(0)

    /**
     * V5.9.388 — sub-trader (ShitCoin / Quality / BlueChip / Moonshot /
     * Treasury / DipHunter / ProjectSniper / SolanaArb / ShitCoinExpress)
     * trade counter. Does NOT feed the MEME meme-bucket.
     */
    fun recordSubTraderTrade(isWin: Boolean, @Suppress("UNUSED_PARAMETER") subtrader: String = "") {
        subTraderSessionTrades.incrementAndGet()
        if (isWin) subTraderSessionWins.incrementAndGet()
    }

    fun getSubTraderTradeCount(): Int = subTraderSessionTrades.get()
    fun getSubTraderWinCount():   Int = subTraderSessionWins.get()
    
    /**
     * Record a SHADOW trade with lowest learning weight (0.025 per trade).
     * 4000 shadow trades = 100% maturity contribution from shadow.
     * Simulated everything - helps learn slowly without inflating maturity.
     */
    fun recordShadowTrade(isWin: Boolean) {
        synchronized(tradeAccumulatorLock) {
            shadowTradeAccumulator += SHADOW_LEARNING_WEIGHT
            
            // When accumulator reaches 1.0, count as one full trade
            while (shadowTradeAccumulator >= 1.0) {
                sessionTrades.incrementAndGet()
                if (isWin) sessionWins.incrementAndGet()
                shadowTradeAccumulator -= 1.0
            }
            
            cachedProgress = 0.0  // Force recalculation
        }
        
        ErrorLogger.debug(TAG, "🧠 SHADOW trade recorded (${SHADOW_LEARNING_WEIGHT}x weight) | " +
            "Progress: ${(getLearningProgress()*100).toInt()}%")
    }
    
    /**
     * Get current learning weights for display/debugging.
     */
    fun getLearningWeights(): Triple<Double, Double, Double> = 
        Triple(LIVE_LEARNING_WEIGHT, PAPER_LEARNING_WEIGHT, SHADOW_LEARNING_WEIGHT)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.6b: MARKETS-SPECIFIC TRADE RECORDING
    // Completely separate from Meme mode to prevent cross-contamination
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var marketsLiveAccumulator = 0.0
    private var marketsPaperAccumulator = 0.0
    private val marketsAccumulatorLock = Any()
    
    /**
     * Record a MARKETS paper trade (TokenizedStocks, Commodities, Metals, Forex).
     * Uses Markets-specific counters - does NOT affect Meme mode thresholds.
     */
    fun recordMarketsPaperTrade(isWin: Boolean, pnlPct: Double = 0.0) {
        synchronized(marketsAccumulatorLock) {
            marketsPaperAccumulator += PAPER_LEARNING_WEIGHT
            while (marketsPaperAccumulator >= 1.0) {
                marketsSessionTrades.incrementAndGet()
                if (isWin) marketsSessionWins.incrementAndGet()
                marketsPaperAccumulator -= 1.0
            }
        }
    }
    
    /**
     * Record a MARKETS live trade.
     * Uses Markets-specific counters - does NOT affect Meme mode thresholds.
     */
    fun recordMarketsLiveTrade(isWin: Boolean, pnlPct: Double = 0.0) {
        synchronized(marketsAccumulatorLock) {
            marketsLiveAccumulator += LIVE_LEARNING_WEIGHT
            while (marketsLiveAccumulator >= 1.0) {
                marketsSessionTrades.incrementAndGet()
                if (isWin) marketsSessionWins.incrementAndGet()
                marketsLiveAccumulator -= 1.0
            }
        }
    }
    
    /**
     * Get Markets-specific trade count (separate from Meme trades).
     */
    fun getMarketsTradeCount(): Int = marketsSessionTrades.get()
    
    /**
     * Get Markets-specific learning progress (0.0 - 1.0).
     * Completely separate from Meme mode progress.
     * Uses same phase curve (0-5000 trades) but separate counters.
     */
    fun getMarketsLearningProgress(): Double {
        val now = System.currentTimeMillis()
        if (now - marketsLastProgressUpdate.get() < 10_000 && marketsCachedProgress > 0) {
            return marketsCachedProgress
        }
        
        val totalTrades = marketsSessionTrades.get()
        val winRate = if (totalTrades > 0) {
            marketsSessionWins.get().toDouble() / totalTrades * 100
        } else 50.0
        
        // Same 4-phase learning curve as Meme, but separate counter
        val baseProgress = when {
            totalTrades <= BOOTSTRAP_PHASE_END ->
                (totalTrades.toDouble() / BOOTSTRAP_PHASE_END) * 0.50
            totalTrades <= MATURE_PHASE_END ->
                0.50 + ((totalTrades - BOOTSTRAP_PHASE_END).toDouble() / (MATURE_PHASE_END - BOOTSTRAP_PHASE_END)) * 0.30
            totalTrades <= EXPERT_PHASE_END ->
                0.80 + ((totalTrades - MATURE_PHASE_END).toDouble() / (EXPERT_PHASE_END - MATURE_PHASE_END)) * 0.20
            else ->
                MAX_LEARNING_PROGRESS
        }
        
        // Adjust based on win rate
        val progress = when {
            winRate > 60 -> (baseProgress * 1.1).coerceAtMost(MAX_LEARNING_PROGRESS)
            winRate < 40 -> baseProgress * 0.85
            else -> baseProgress
        }.coerceAtMost(MAX_LEARNING_PROGRESS)
        
        marketsCachedProgress = progress
        marketsLastProgressUpdate.set(now)
        
        return progress
    }
    
    /**
     * V5.7.6b: Lerp using MARKETS-specific progress.
     * Ensures Markets thresholds don't affect Meme mode and vice versa.
     */
    fun lerpMarkets(bootstrap: Double, mature: Double): Double {
        val progress = getMarketsLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INTERPOLATION HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Linear interpolation between bootstrap (loose) and mature (strict) values.
     * Now includes behavior modifier:
     *   - Negative behavior → pushes toward MATURE (stricter)
     *   - Positive behavior → pushes toward BOOTSTRAP (looser)
     */
    fun lerp(bootstrap: Double, mature: Double): Double {
        var progress = getLearningProgress()
        
        // Apply behavior modifier
        // Negative behavior (loss streaks) → increase progress (tighter thresholds)
        // Positive behavior (win streaks, 10x) → decrease progress (looser thresholds)
        if (behaviorModifier != 0.0) {
            val behaviorEffect = -behaviorModifier * 0.15  // Max 15% adjustment
            progress = (progress + behaviorEffect).coerceIn(0.0, 1.0)
        }
        
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /**
     * Raw lerp without behavior modifier (for display/comparison).
     */
    fun lerpRaw(bootstrap: Double, mature: Double): Double {
        val progress = getLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }
    
    /**
     * Inverse lerp - returns HIGHER value in bootstrap (for bonuses/scores).
     * Also includes behavior modifier.
     */
    fun lerpInverse(bootstrap: Double, mature: Double): Double {
        var progress = getLearningProgress()
        
        // Apply behavior modifier (inverse effect)
        if (behaviorModifier != 0.0) {
            val behaviorEffect = -behaviorModifier * 0.15
            progress = (progress + behaviorEffect).coerceIn(0.0, 1.0)
        }
        
        return mature + (bootstrap - mature) * (1.0 - progress)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIQUIDITY THRESHOLDS (Used by FDG, Scanners, Eligibility)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Watchlist floor: minimum liquidity to even consider a token
    private const val LIQ_WATCHLIST_BOOTSTRAP = 500.0    // $500 in bootstrap
    private const val LIQ_WATCHLIST_MATURE = 2000.0      // $2000 when mature
    
    // Execution floor: minimum liquidity to actually trade
    // V4.1.1: LOWERED bootstrap from $1500 to $800 to allow learning on smaller tokens
    // V5.9.803: operator dump "fdg needs to start working from trade 1" —
    // lowered bootstrap from $800 → $300 so the BLUECHIP/TREASURY FDG paths
    // can collect first-trade samples on pump.fun bonding-curve tokens
    // (after V5.9.803 dropped the FDG SHITCOIN-only restriction on the
    // exitCapacity bootstrap fallback). MATURE floor unchanged at $10k —
    // once the bot has learned, BLUECHIP/TREASURY return to demanding
    // deep liquidity for live execution.
    private const val LIQ_EXECUTION_BOOTSTRAP = 300.0    // $300 in bootstrap - allow trade-1 learning
    private const val LIQ_EXECUTION_MATURE = 10000.0     // $10000 when mature
    
    // Scanner minimum: for fresh token discovery
    private const val LIQ_SCANNER_BOOTSTRAP = 300.0      // $300 in bootstrap
    private const val LIQ_SCANNER_MATURE = 1500.0        // $1500 when mature
    
    fun getWatchlistFloor(): Double = lerp(LIQ_WATCHLIST_BOOTSTRAP, LIQ_WATCHLIST_MATURE)
    fun getExecutionFloor(): Double = lerp(LIQ_EXECUTION_BOOTSTRAP, LIQ_EXECUTION_MATURE)
    fun getScannerMinLiquidity(): Double = lerp(LIQ_SCANNER_BOOTSTRAP, LIQ_SCANNER_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIDENCE THRESHOLDS (Used by FDG, CashGenerationAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val CONF_BOOTSTRAP = 22.0    // V5.9.266: moderate (was 18 at V5.9.263)
    private const val CONF_MATURE = 75.0       // 75% confidence when mature
    
    private const val CONF_PAPER_BOOTSTRAP = 3.0    // V5.9.174: was 15 — user demand
    //   "watchlist should process tokens above D+ and trade anything above D+ that passes".
    //   The old 15% floor was strangling paper — with thousands of fresh launches
    //   pricing at 5-14% conf in the first minutes, a 15% floor blocked 90%+ of
    //   candidates. 3% matches FDG's BOOTSTRAP_MIN_CONFIDENCE so the fluid gates
    //   downstream (learning floor, behavior modifiers) decide who wins — not a
    //   hardcoded number. Trade rate should return to the thousands/day seen
    //   previously. Paper mature still tightens back to 45.
    private const val CONF_PAPER_MATURE = 45.0      // Paper mode target
    
    fun getLiveConfidenceFloor(): Double = lerp(CONF_BOOTSTRAP, CONF_MATURE)
    fun getPaperConfidenceFloor(): Double = lerp(CONF_PAPER_BOOTSTRAP, CONF_PAPER_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORE THRESHOLDS (Used by V3 Scoring, CashGenerationAI)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val SCORE_BOOTSTRAP = 15     // V5.9.266: moderate (was 12 at V5.9.263)
    private const val SCORE_MATURE = 40  // V5.9.184: raised to target 50%+ WR in mature phase
    
    fun getMinScoreThreshold(): Int = lerp(SCORE_BOOTSTRAP.toDouble(), SCORE_MATURE.toDouble()).toInt()

    /**
     * V5.8: Get minimum score required for EXECUTE_STANDARD.
     * Lerps from 25 (bootstrap) to 30 (mature). Hard cap at 40 prevents drift starvation.
     */
    fun getExecuteFloor(): Int {
        // V5.9.152: bootstrap = WIDE OPEN so the 41 layers collect data.
        //   Bootstrap (0%):  15  — take almost every shot, it's the only way to learn
        //   Mature   (80%):  38  — tighter, known patterns only
        //   Expert  (100%):  45  — expert selectivity
        // Old curve (25→45) left the bot at floor=31 at 32% maturity with
        // 40%+ of scanner tokens still below — not enough volume to move
        // any layer's accuracy off the default.
        val lerped = lerp(15.0, 45.0).toInt()
        return lerped.coerceIn(15, 45)
    }

    // Score bonuses are HIGHER in bootstrap to encourage more mode diversity
    fun getScoreBonus(baseBonus: Double): Double = lerpInverse(baseBonus * 1.8, baseBonus)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRADING MODE THRESHOLDS (Used by MarketStructureRouter)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Fresh token age threshold
    private const val FRESH_AGE_BOOTSTRAP = 120.0  // 2 hours = fresh in bootstrap
    private const val FRESH_AGE_MATURE = 30.0      // 30 min = fresh when mature
    
    // History candles required
    private const val MIN_HISTORY_BOOTSTRAP = 5
    private const val MIN_HISTORY_MATURE = 30
    
    // Breakout detection threshold (% of recent high)
    private const val BREAKOUT_BOOTSTRAP = 0.75    // 75% of high in bootstrap
    private const val BREAKOUT_MATURE = 0.92       // 92% of high when mature
    
    fun getFreshTokenAgeMinutes(): Double = lerp(FRESH_AGE_BOOTSTRAP, FRESH_AGE_MATURE)
    fun getMinHistoryCandles(): Int = lerp(MIN_HISTORY_BOOTSTRAP.toDouble(), MIN_HISTORY_MATURE.toDouble()).toInt()
    fun getBreakoutThreshold(): Double = lerp(BREAKOUT_BOOTSTRAP, BREAKOUT_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TREASURY MODE THRESHOLDS (Used by CashGenerationAI)
    // 
    // V4.0 FIX: Treasury was TOO LOOSE at bootstrap - 30 losing trades in 2 mins.
    // Now starts with REASONABLE quality requirements that loosen gradually.
    // Treasury still uses looser thresholds than V3 because:
    // - Tight stop losses (-2%)
    // - Quick exits (5-10% TP)
    // - Short hold times (max 8 min)
    // But it should NOT take garbage trades on first startup!
    // V5.0: RAISED bootstrap thresholds - too much garbage was getting through!
    // V5.1: SLIGHTLY LOWERED to allow more trading during bootstrap
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val TREASURY_CONF_BOOTSTRAP = 22   // V5.9.266: moderate (was 18 at V5.9.263)
    private const val TREASURY_CONF_MATURE = 45      // Raise as we learn (normal progression)
    
    private const val TREASURY_LIQ_BOOTSTRAP = 2000.0    // V5.9.266: moderate (was 1500 at V5.9.263)
    private const val TREASURY_LIQ_MATURE = 2500.0       // V5.9.452: 10000→2500 — user reported universe-wide silence, log showed 'TREASURY SKIP: HEXOR | liq=$3274<$6000'. Pump.fun memes sit $2-8K, $10K mature was over-choking.
    
    private const val TREASURY_TOP_HOLDER_BOOTSTRAP = 45.0  // V5.9.266: moderate (was 50 at V5.9.263)
    private const val TREASURY_TOP_HOLDER_MATURE = 25.0     // Tighten as we learn
    
    private const val TREASURY_BUY_PRESSURE_BOOTSTRAP = 25.0  // V5.9.266: moderate (was 20 at V5.9.263)
    private const val TREASURY_BUY_PRESSURE_MATURE = 50.0     // Raise as we learn
    
    private const val TREASURY_SCORE_BOOTSTRAP = 11    // V5.9.266: moderate (was 9 at V5.9.263)
    private const val TREASURY_SCORE_MATURE = 25       // V5.9.442: 32→25 — user reported CashGen/Treasury rarely firing
    
    fun getTreasuryConfidenceThreshold(): Int = lerp(TREASURY_CONF_BOOTSTRAP.toDouble(), TREASURY_CONF_MATURE.toDouble()).toInt()
    fun getTreasuryMinLiquidity(): Double = lerp(TREASURY_LIQ_BOOTSTRAP, TREASURY_LIQ_MATURE)
    fun getTreasuryMaxTopHolder(): Double = lerp(TREASURY_TOP_HOLDER_BOOTSTRAP, TREASURY_TOP_HOLDER_MATURE)
    fun getTreasuryMinBuyPressure(): Double = lerp(TREASURY_BUY_PRESSURE_BOOTSTRAP, TREASURY_BUY_PRESSURE_MATURE)
    fun getTreasuryScoreThreshold(): Int = lerp(TREASURY_SCORE_BOOTSTRAP.toDouble(), TREASURY_SCORE_MATURE.toDouble()).toInt()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-MARKET TRADING THRESHOLDS (Used by TokenizedStockTrader, CommoditiesTrader, etc.)
    // V5.7.6: Dynamic thresholds for SPOT and LEVERAGE trading
    // V5.7.6b: BROADENED to match Meme trader philosophy - loose bootstrap, tight mature
    // ═══════════════════════════════════════════════════════════════════════════
    
    // SPOT trading thresholds — V5.9.300: floor inversion — per-trader gates strict (was 28/30 at V5.9.266)
    // V5.9.432 — raised bootstrap from 30/30 → 50/45. At 30/30 the lerp let
    // noise signals through in early BOOTSTRAP which produced the alt
    // scratch-spam (AXS -0.07% AI:94, VIRTUAL -0.09% AI:100 etc). 50/45
    // means a token must be at least half-conviction before capital goes in;
    // mature 60/65 unchanged.
    private const val MARKETS_SPOT_SCORE_BOOTSTRAP = 50
    private const val MARKETS_SPOT_SCORE_MATURE = 60
    private const val MARKETS_SPOT_CONF_BOOTSTRAP = 45
    private const val MARKETS_SPOT_CONF_MATURE = 65
    
    // LEVERAGE trading thresholds — V5.9.300: floor inversion — leverage demands tighter gate (was 30/32)
    private const val MARKETS_LEV_SCORE_BOOTSTRAP = 32
    private const val MARKETS_LEV_SCORE_MATURE = 70
    private const val MARKETS_LEV_CONF_BOOTSTRAP = 32
    private const val MARKETS_LEV_CONF_MATURE = 70
    
    // Take Profit targets - WIDER range for learning
    private const val MARKETS_TP_BOOTSTRAP = 8.0    // V5.9.229: 4→8% — tiny wins were killing net PnL; alts need room to breathe
    private const val MARKETS_TP_MATURE = 25.0   // V5.9.8: was 8% — was capping legitimate big moves
    
    // Stop Loss targets - WIDER at bootstrap for learning
    private const val MARKETS_SL_BOOTSTRAP = -3.0   // was -12% — catastrophic, burned balance fast
    private const val MARKETS_SL_MATURE = -4.0      // tight stops = more losses cut early
    
    // Position size as % of balance - MORE AGGRESSIVE
    private const val MARKETS_SIZE_BOOTSTRAP = 2.0  // V5.7.6b: Was 3%, start smaller for safety
    private const val MARKETS_SIZE_MATURE = 8.0     // V5.7.6b: Was 5%, scale up with confidence
    
    /** Get fluid score threshold for SPOT trades - V5.7.6b: Uses Markets-specific progress */
    fun getMarketsSpotScoreThreshold(): Int = lerpMarkets(MARKETS_SPOT_SCORE_BOOTSTRAP.toDouble(), MARKETS_SPOT_SCORE_MATURE.toDouble()).toInt()
    
    /** Get fluid confidence threshold for SPOT trades - V5.7.6b: Uses Markets-specific progress */
    fun getMarketsSpotConfThreshold(): Int = lerpMarkets(MARKETS_SPOT_CONF_BOOTSTRAP.toDouble(), MARKETS_SPOT_CONF_MATURE.toDouble()).toInt()
    
    /** Get fluid score threshold for LEVERAGE trades - V5.7.6b: Uses Markets-specific progress */
    fun getMarketsLeverageScoreThreshold(): Int = lerpMarkets(MARKETS_LEV_SCORE_BOOTSTRAP.toDouble(), MARKETS_LEV_SCORE_MATURE.toDouble()).toInt()
    
    /** Get fluid confidence threshold for LEVERAGE trades - V5.7.6b: Uses Markets-specific progress */
    fun getMarketsLeverageConfThreshold(): Int = lerpMarkets(MARKETS_LEV_CONF_BOOTSTRAP.toDouble(), MARKETS_LEV_CONF_MATURE.toDouble()).toInt()
    
    /** Get fluid take profit target for Markets trading - V5.7.6b: Uses Markets-specific progress */
    fun getMarketsTakeProfitPct(): Double = lerpMarkets(MARKETS_TP_BOOTSTRAP, MARKETS_TP_MATURE)

    /** V5.9.8: Spot trades — moderate ceiling, markets move slower than meme coins */
    fun getMarketsSpotTpPct(): Double = lerpMarkets(MARKETS_TP_BOOTSTRAP, MARKETS_TP_MATURE)

    /** V5.9.8: Leverage trades — higher TP ceiling to justify the leverage risk */
    fun getMarketsLevTpPct(): Double = lerpMarkets(MARKETS_TP_BOOTSTRAP * 1.5, MARKETS_TP_MATURE * 1.5)

    /** V5.9.8: Never cap TP — if a signal has a higher target, honour it */
    fun getMarketsUncappedTpPct(signalTp: Double): Double = maxOf(signalTp, getMarketsSpotTpPct())

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.31: FLUID SCANNER LIQUIDITY FLOOR
    // Was hard-coded at \$5k (and previously \$10k paper / \$25k live). User feedback:
    // "everything is meant to be fluid and adaptive". Scales with learning so the bot
    // starts wide (catches long-tail fresh launches) and tightens as it matures.
    //   Bootstrap (0%):   \$1_500  — wide net, gather data
    //   Mature   (80%):   \$6_000  — filter unproven low-liq noise
    //   Expert  (100%):   \$8_000  — expert-only selectivity
    // Post-hook: FluidLearningAI itself mutates learning progress based on realized PnL,
    // so this floor auto-drifts with winrate.
    // ═══════════════════════════════════════════════════════════════════════════
    private const val SCANNER_LIQ_FLOOR_BOOTSTRAP = 1_000.0  // V5.9.300: 1500→1000 (open scanner gate; per-trader liq floors filter)
    private const val SCANNER_LIQ_FLOOR_MATURE = 8_000.0
    fun getScannerLiqFloor(): Double = lerp(SCANNER_LIQ_FLOOR_BOOTSTRAP, SCANNER_LIQ_FLOOR_MATURE)

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.31: FLUID FLAT-TRADE CAP
    // V5.9.304: V5.9.190-198 ERA RESTORATION — band 2.5%→1.0% tolerance 10→15 min.
    // Old config was force-exiting ANY trade hovering in ±2.5% after 10 min as a
    // "flat" scratch. Real moves frequently spend 10-15 min in ±1.5% before
    // breaking out — 47% of trades in V5.9.303 were getting killed right here
    // in this band. Tightening the band to ±1.0% lets normal price action
    // breathe; the genuinely dead trades (within ±1%) still get evicted.
    //   Bootstrap (0%):   15 min tolerance, |pnl|<1.0% — patient, real moves develop
    //   Mature   (80%):    5 min tolerance, |pnl|<0.6% — sharper, faster cuts
    //   Expert  (100%):    3 min tolerance, |pnl|<0.4% — expert cuts noise fast
    // ═══════════════════════════════════════════════════════════════════════════
    fun getFlatTradeToleranceMin(): Double = lerp(15.0, 3.0)
    fun getFlatTradeBandPct(): Double = lerp(1.0, 0.4)
    fun getFlatTradeMaxHoldMin(): Double = lerp(30.0, 15.0)
    
    /** Get fluid stop loss target for Markets trading - V5.7.6b: Uses Markets-specific progress */
    fun getMarketsStopLossPct(): Double = lerpMarkets(MARKETS_SL_BOOTSTRAP, MARKETS_SL_MATURE)
    
    /** Get fluid position size for Markets trading - V5.7.6b: Uses Markets-specific progress */
    fun getMarketsPositionSizePct(): Double = lerpMarkets(MARKETS_SIZE_BOOTSTRAP, MARKETS_SIZE_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RUG FILTER THRESHOLDS (Used by HardRugPreFilter)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val RUG_LIQ_FRESH_BOOTSTRAP = 300.0    // $300 for fresh tokens
    private const val RUG_LIQ_FRESH_MATURE = 1500.0
    
    private const val RUG_LIQ_YOUNG_BOOTSTRAP = 500.0    // $500 for 5-30min tokens
    private const val RUG_LIQ_YOUNG_MATURE = 2500.0
    
    private const val RUG_LIQ_ESTABLISHED_BOOTSTRAP = 800.0
    private const val RUG_LIQ_ESTABLISHED_MATURE = 3500.0
    
    fun getRugFilterLiqFresh(): Double = lerp(RUG_LIQ_FRESH_BOOTSTRAP, RUG_LIQ_FRESH_MATURE)
    fun getRugFilterLiqYoung(): Double = lerp(RUG_LIQ_YOUNG_BOOTSTRAP, RUG_LIQ_YOUNG_MATURE)
    fun getRugFilterLiqEstablished(): Double = lerp(RUG_LIQ_ESTABLISHED_BOOTSTRAP, RUG_LIQ_ESTABLISHED_MATURE)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // FLUID STOP LOSS & TAKE PROFIT (Per Mode)
    // ═══════════════════════════════════════════════════════════════════════════
    // 
    // PHILOSOPHY: While learning, use WIDE stop losses and TIGHT take profits.
    // - Wide SL (-10% to -15%) prevents learning from noise during bootstrap
    // - Tight TP (+5% to +8%) captures wins early while learning what works
    // 
    // As bot matures:
    // - SL tightens (-3% to -8%) to protect gains
    // - TP widens (+15% to +50%) to let winners run
    // 
    // Each mode has its own fluid parameters that intertwine with mode strategy.
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get fluid stop loss percentage for a trading mode.
     * V5.1: TIGHTER bootstrap stops - don't let losses run while learning
     * Bootstrap: Use tighter stops (-6% max) to protect capital while learning
     * Mature: Use mode-specific stops (can be wider with experience)
     */
    fun getFluidStopLoss(modeDefaultStop: Double): Double {
        val progress = getLearningProgress()

        // V5.9.495z11 — BOOTSTRAP STOP-LOSS SIGN FIX.
        // Pre-fix: `maxOf(modeDefaultStop, 4.0)` where modeDefaultStop is
        // a NEGATIVE percent (e.g. -25.0) returned +4.0, i.e. a POSITIVE
        // floor. The sweep then fired `pnlPct <= 4.0` on essentially
        // every open trade (any position not up >4%) — the user observed
        // this as "rapid stop fire knifing the wallet", with every
        // position closing at −2% to −3% and the live SWEEP_FLUID_FLOOR_3
        // reason emitted on every close.
        //
        // Post-fix: cap the bootstrap stop at −15% (user mandate: "-15% is
        // ok"). For mode stops tighter than −15% (treasury −5%, shitcoin
        // −10%, etc.) the mode value wins because it is already less
        // negative than −15%. For wider mode stops (moonshot −25%) we
        // clamp to −15% during bootstrap so the bot doesn't bleed 25% on
        // every learning trade. Signs are now correct throughout.
        val bootstrapStop = maxOf(modeDefaultStop, -15.0)  // never looser than −15% during bootstrap
        val matureStop = modeDefaultStop                   // full mode stop once mature

        return lerp(bootstrapStop, matureStop)
    }
    
    /**
     * Get fluid take profit percentage for a trading mode.
     * Bootstrap: TIGHT take profits (secure wins while learning)
     * Mature: WIDE take profits (let winners run with confidence)
     */
    fun getFluidTakeProfit(modeDefaultTp: Double, tradingMode: String = ""): Double {
        // V5.9.8: Moonshot/Treasury/BlueChip modes must never be TP-capped
        // Capping at 15% during bootstrap was killing 50-200% winners
        val isHighUpsideMode = tradingMode.contains("MOONSHOT", ignoreCase = true)
            || tradingMode.contains("TREASURY", ignoreCase = true)
            || tradingMode.contains("BLUE", ignoreCase = true)
        if (isHighUpsideMode) return modeDefaultTp

        // Standard modes: lerp from 15% bootstrap cap → full TP at maturity
        val bootstrapTp = minOf(modeDefaultTp, 15.0)
        return lerp(bootstrapTp, modeDefaultTp)
    }
    
    /**
     * Get fluid trailing stop percentage.
     * Bootstrap: No trailing stops (too tight, gets stopped out during learning)
     * Mature: Use mode's trailing stop
     */
    fun getFluidTrailingStop(modeDefaultTrailing: Double): Double {
        val progress = getLearningProgress()
        
        // Trailing stops only activate after 50% learning progress
        if (progress < 0.5) return 0.0
        
        // Scale from 0 to full trailing as we mature
        val scaledProgress = (progress - 0.5) * 2.0  // 0.5-1.0 → 0.0-1.0
        return modeDefaultTrailing * scaledProgress
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V3.3: DYNAMIC FLUID STOP LOSS
    // 
    // The stop loss MOVES with the token position as it develops:
    // 1. Entry phase: Wide stop (allow for normal volatility/retraces)
    // 2. Profit phase: Trailing stop that ratchets up with price
    // 3. Learning-aware: Tighter stops as bot learns what works
    // 
    // This prevents getting stopped out before pump completes,
    // while protecting gains as they accumulate.
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * V3.3: Get dynamic fluid stop loss that moves with position.
     * 
     * @param modeDefaultStop Base stop loss % for this trading mode
     * @param currentPnlPct Current P&L percentage of position
     * @param peakPnlPct Highest P&L achieved this position
     * @param holdTimeSeconds How long position has been held
     * @param volatility Current volatility estimate (0-100)
     * @return Dynamic stop loss percentage (negative, e.g., -8.0 means exit at -8%)
     */
    /**
     * V5.9.169 — Continuous fluid profit-lock floor.
     * Returns the MINIMUM PnL% the position is allowed to give back to.
     * Smooth logarithmic curve — no stair-step rungs.
     *   peak=+8%   → lock ≈ +3%
     *   peak=+20%  → lock ≈ +10%
     *   peak=+100% → lock ≈ +71%
     *   peak=+1000% → lock ≈ +920%
     *   peak=+10000% → lock ≈ +9700%
     * Same curve as getDynamicFluidStop; exposed here so every trader AI
     * can use it for its own checkExit floor-lock without duplicating the
     * formula.
     */
    fun fluidProfitFloor(peakPnlPct: Double, volatility: Double = 50.0, holdSeconds: Double = 0.0): Double {
        // V5.9.190: TIGHTER profit floor — give back POINTS not PERCENTAGE.
        // Old formula: floor = peak * 0.50 → at peak=38%, lock=19%, give_back=19pts. TOO LOOSE.
        // New formula: floor = peak - allowance, where allowance is a small log-scaled number of POINTS.
        //
        // Examples (what gets locked in at each peak):
        //   peak=+10%  → lock ≈ +7%   (give back max 3pts)
        //   peak=+20%  → lock ≈ +14%  (give back max 6pts)
        //   peak=+38%  → lock ≈ +31%  (give back max 7pts)
        //   peak=+50%  → lock ≈ +42%  (give back max 8pts)
        //   peak=+100% → lock ≈ +91%  (give back max 9pts)
        //   peak=+500% → lock ≈ +487% (give back max 13pts)
        //
        // High-volatility markets get +2pts extra allowance. Calm markets: tighter.
        if (peakPnlPct < 5.0) return Double.NEGATIVE_INFINITY
        val volAdjust = when {
            volatility > 70 ->  2.0   // wild market — allow a little more
            volatility < 30 -> -1.0   // calm market — lock tighter
            else -> 0.0
        }
        val logFactor = kotlin.math.log10(kotlin.math.max(1.0, peakPnlPct / 5.0))
        val allowance = (3.0 + 5.0 * logFactor + volAdjust).coerceIn(1.5, 15.0)
        val floor = peakPnlPct - allowance
        // Safety net: never below 70% of peak (protects against very small peaks)
        return kotlin.math.max(floor, peakPnlPct * 0.70)
    }

    /**
     * V5.9.169 — Continuous fluid trailing-stop pct (% from high-water-mark
     * that we tolerate before exiting). Tighter at higher gains.
     *   pnl=+20%   → trail ≈ 12%
     *   pnl=+100%  → trail ≈ 8%
     *   pnl=+1000% → trail ≈ 4%
     *   pnl=+10000% → trail ≈ 2.5%
     */
    fun fluidTrailPct(pnlPct: Double): Double {
        if (pnlPct <= 0.0) return 15.0
        val logPnl = kotlin.math.log10(1.0 + pnlPct / 10.0)
        val logMax = kotlin.math.log10(1001.0)
        val trail = 15.0 - 12.5 * (logPnl / logMax)
        return trail.coerceIn(2.5, 15.0)
    }

    fun getDynamicFluidStop(
        modeDefaultStop: Double,
        currentPnlPct: Double,
        peakPnlPct: Double,
        holdTimeSeconds: Double,
        volatility: Double = 50.0
    ): Double {
        val progress = getLearningProgress()
        
        // Base stop from mode default, adjusted by learning
        val baseStop = getFluidStopLoss(modeDefaultStop)
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 1: ENTRY PROTECTION (first 60s)
        // Allow wider stops during entry volatility - tokens often wick
        // down 5-10% before pumping. Don't get shaken out early.
        // ═══════════════════════════════════════════════════════════════
        if (holdTimeSeconds < 60) {
            // Bootstrap: Even wider entry protection (15%)
            // Mature: Tighter entry protection (12%)
            val entryProtection = lerp(15.0, 12.0)
            return -entryProtection
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 2: VOLATILITY ADJUSTMENT
        // High volatility = wider stops to avoid noise exits
        // Low volatility = tighter stops for quick protection
        // ═══════════════════════════════════════════════════════════════
        val volatilityMult = when {
            volatility > 70 -> 1.3   // High vol: 30% wider stops
            volatility > 50 -> 1.1   // Med vol: 10% wider
            volatility < 30 -> 0.85  // Low vol: 15% tighter
            else -> 1.0
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 3: PROFIT TRAILING (V4.1: MORE AGGRESSIVE)
        // User feedback: "fluid stop loss should move UP as coin makes profit
        // to lock in those gains and tighten to ensure losses are mitigated"
        // ═══════════════════════════════════════════════════════════════
        if (currentPnlPct > 0 && peakPnlPct > 3.0) {  // V4.1: Start trailing earlier (at 3% not 5%)
            // V5.9.118: REWRITTEN — the old code had TWO bugs:
            //   (1) ascending threshold order meant `if (peak>8)` caught every
            //       case and the 15% / 25% lock tiers were dead code.
            //   (2) the profit-trailing branches returned a NEGATED stop value
            //       (e.g. peak=377% → `-maxOf(2, 301.6) = -301.6`), so the
            //       exit check `gainPct <= dynamicStop` compared +251 <= -301
            //       which is NEVER true. Runners like UGOR +290% → +50% never
            //       fired any lock. This is the profit-floor regression that
            //       has been "fixed" 3 times without taking hold.
            //
            // NEW SEMANTICS:
            //   - While in profit, return a POSITIVE trailing stop level
            //     (the exit fires when gainPct falls TO that level).
            //   - Tiers are checked DESCENDING so the biggest-runner rule wins.
            //   - Peak-drawdown hard floor: once peak >= 100%, force exit if
            //     current gain has given back >= 35% of the peak. No runner
            //     survives handing back a third of its high.
            //
            // ═══════════════════════════════════════════════════════════════
            // V5.9.169 — CONTINUOUS FLUID PROFIT-LOCK (user-directed rewrite)
            // User: "it should slide closer to the current price as it goes
            // up to capture the most profit".
            //
            // Replaced the discrete stair-step ladder with a SMOOTH CURVE
            // where keepRatio (fraction of peak that stays locked) climbs
            // continuously from ~0.40 at +8% peak to ~0.97 at +10000% peak.
            // The slide TIGHTENS every single tick as price advances —
            // never jumps in stairs, never gives back whole rungs.
            //
            // Formula (base keepRatio):
            //   r_base = 0.40 + 0.57 * log10(1 + peak/10) / log10(1001)
            //   → +8%  peak → 0.40   (loose; token just started running)
            //   → +20% peak → 0.52
            //   → +50% peak → 0.62
            //   → +100% peak → 0.71
            //   → +300% peak → 0.82
            //   → +1000% peak → 0.92
            //   → +10000% peak → 0.97
            //
            // Then modulated by:
            //   - learningProgress (±5% — tightens as bot matures)
            //   - volatility       (±8% — tightens in calm markets)
            //   - holdTime         (+3% after 5 min of HWM idle — it's
            //                       topping out, lock more)
            // V5.9.190: Use same tight allowance formula as fluidProfitFloor.
            // Old: floor = peak × ratio (gave back HALF the gain at peak=38%)
            // New: floor = peak − allowance (fixed points, not a ratio)
            val peakClamped = peakPnlPct.coerceAtLeast(0.0)
            val dynVolAdj = when {
                volatility > 70 ->  2.0   // high vol → 2 more points grace
                volatility < 30 -> -1.0   // calm → 1 point tighter
                else -> 0.0
            }
            val holdMinutes = holdTimeSeconds / 60.0
            val holdAdj = if (holdMinutes > 5.0) -0.5 else 0.0  // stale HWM → tighter
            val dynLogFactor = kotlin.math.log10(kotlin.math.max(1.0, peakClamped / 5.0))
            val dynAllowance = (3.0 + 5.0 * dynLogFactor + dynVolAdj + holdAdj).coerceIn(1.5, 15.0)
            val continuousLock = kotlin.math.max(peakClamped - dynAllowance, peakClamped * 0.70)

            // Absolute safety floor: once peak >= +8%, never go back to entry
            val breakEvenFloor = if (peakClamped >= 8.0) 1.0 else Double.NEGATIVE_INFINITY

            // Big-runner hard give-back: once peak >= 100%, max 12pts giveaway
            // (tighter than old 35pts — matches the new allowance formula)
            val peakDrawdownFloor = if (peakClamped >= 100.0) peakClamped - 12.0 else Double.NEGATIVE_INFINITY

            // Return POSITIVE trailing stop level.
            return maxOf(continuousLock, breakEvenFloor, peakDrawdownFloor)
        }
        
        // ═══════════════════════════════════════════════════════════════
        // PHASE 4: RETRACEMENT ALLOWANCE (allowing pre-pump retrace)
        // If we're slightly negative but within normal volatility,
        // give extra room to allow for typical pump patterns.
        // ═══════════════════════════════════════════════════════════════
        if (currentPnlPct > -5.0 && currentPnlPct < 0) {
            // In "retrace zone" - allow wider stop to not exit before pump
            // Bootstrap: 12% retrace allowance
            // Mature: 8% retrace allowance (tighter as we learn patterns)
            val retraceAllowance = lerp(12.0, 8.0)
            return -(retraceAllowance * volatilityMult)
        }
        
        // ═══════════════════════════════════════════════════════════════
        // DEFAULT: Use base fluid stop with volatility adjustment
        // ═══════════════════════════════════════════════════════════════
        return -(baseStop * volatilityMult)
    }
    
    /**
     * Mode-specific fluid parameters container.
     */
    data class FluidModeParams(
        val modeName: String,
        val stopLossPct: Double,      // Current fluid stop loss
        val takeProfitPct: Double,    // Current fluid take profit
        val trailingStopPct: Double,  // Current fluid trailing stop
        val learningProgress: Double,
    ) {
        fun summary(): String = "SL=${stopLossPct.toInt()}% TP=${takeProfitPct.toInt()}% " +
            "Trail=${trailingStopPct.toInt()}% [${(learningProgress*100).toInt()}% learned]"
    }
    
    /**
     * Get all fluid parameters for a specific trading mode.
     * This is the main API for BotService/Executor to use.
     */
    fun getModeParams(
        modeName: String,
        defaultStopPct: Double,
        defaultTpPct: Double,
        defaultTrailingPct: Double
    ): FluidModeParams {
        return FluidModeParams(
            modeName = modeName,
            stopLossPct = getFluidStopLoss(defaultStopPct),
            takeProfitPct = getFluidTakeProfit(defaultTpPct),
            trailingStopPct = getFluidTrailingStop(defaultTrailingPct),
            learningProgress = getLearningProgress()
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS & DEBUGGING
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class FluidState(
        val learningProgressPct: Int,
        val totalTrades: Int,
        val sessionTrades: Int,
        val sessionWinRate: Double,
        val isBootstrap: Boolean,
        val watchlistFloor: Int,
        val executionFloor: Int,
        val scannerMinLiq: Int,
        val confidenceFloor: Int,
        val scoreThreshold: Int,
        val freshAgeMinutes: Int,
        // V3.3: Fluid SL/TP status
        val exampleStopLoss: Double,   // Example using 20% mode default
        val exampleTakeProfit: Double, // Example using 40% mode default
        val exampleTrailing: Double,   // Example using 15% mode default
    ) {
        fun summary(): String = buildString {
            append("🧠 FLUID AI: ${learningProgressPct}% learned ($totalTrades trades)")
            if (isBootstrap) append(" [BOOTSTRAP]")
            append("\n  Liq: watch≥\$$watchlistFloor exec≥\$$executionFloor scan≥\$$scannerMinLiq")
            append("\n  Conf≥${confidenceFloor}% Score≥$scoreThreshold Fresh≤${freshAgeMinutes}min")
            append("\n  SL=${exampleStopLoss.toInt()}% TP=${exampleTakeProfit.toInt()}% Trail=${exampleTrailing.toInt()}%")
        }
    }
    
    fun getState(): FluidState {
        val progress = getLearningProgress()
        // V5.9.115: Lifetime counter survives journal clears.
        val lifetime = try { TradeHistoryStore.getLifetimeStats() } catch (_: Exception) { null }
        val historicalTrades = lifetime?.totalSells ?: 0
        val totalTrades = historicalTrades + sessionTrades.get()
        val sessionWinRate = if (sessionTrades.get() > 0) {
            sessionWins.get().toDouble() / sessionTrades.get() * 100
        } else 0.0
        
        // Example fluid SL/TP using typical meme mode values
        val exampleParams = getModeParams("MEME_BREAKOUT", 20.0, 40.0, 15.0)
        
        return FluidState(
            learningProgressPct = (progress * 100).toInt(),
            totalTrades = totalTrades,
            sessionTrades = sessionTrades.get(),
            sessionWinRate = sessionWinRate,
            isBootstrap = progress < 0.40,  // V5.9.166: aligned to global 0.40 threshold
            watchlistFloor = getWatchlistFloor().toInt(),
            executionFloor = getExecutionFloor().toInt(),
            scannerMinLiq = getScannerMinLiquidity().toInt(),
            confidenceFloor = getLiveConfidenceFloor().toInt(),
            scoreThreshold = getMinScoreThreshold(),
            freshAgeMinutes = getFreshTokenAgeMinutes().toInt(),
            exampleStopLoss = exampleParams.stopLossPct,
            exampleTakeProfit = exampleParams.takeProfitPct,
            exampleTrailing = exampleParams.trailingStopPct,
        )
    }
    
    fun logState() {
        val state = getState()
        ErrorLogger.info(TAG, state.summary())
    }
    
    /**
     * Get maturity as percentage (0-100) for UI display
     */
    fun getMaturityPercent(): Double {
        return getLearningProgress() * 100.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: FLUID HOLDING TIMES (Per Layer)
    // 
    // Each trading layer has optimal hold times that FLUID learns over time:
    // 
    // LAYER          BOOTSTRAP       MATURE          RATIONALE
    // ─────────────────────────────────────────────────────────────────────────
    // Treasury       3-8 min         1-5 min         Quick scalps, tighten with experience
    // ShitCoin       5-15 min        3-10 min        Fast plays, learn optimal timing
    // V3 Quality     15-60 min       10-45 min       Quality setups need time
    // Blue Chip      30-120 min      20-90 min       Let winners ride longer
    // Moonshot       30-180 min      45-240 min      Big moves need patience
    // 
    // Bootstrap: WIDER hold windows (learning what works)
    // Mature: TIGHTER windows (optimized from data)
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Treasury Layer - Quick scalps
    private const val TREASURY_MIN_HOLD_BOOTSTRAP = 3.0     // 3 min minimum
    private const val TREASURY_MIN_HOLD_MATURE = 1.0        // 1 min minimum (fast exits OK)
    private const val TREASURY_MAX_HOLD_BOOTSTRAP = 12.0    // 12 min max during learning
    private const val TREASURY_MAX_HOLD_MATURE = 8.0        // 8 min max when optimized
    
    // ShitCoin Layer - V5.2: NO HOLD TIME RESTRICTIONS - exit on signals only!
    // ShitCoins exit on: SL, TP, trailing stop, momentum death, rug detection
    // NOT on time - memes can moon or dump at any moment
    private const val SHITCOIN_MIN_HOLD_BOOTSTRAP = 0.0     // V5.2: No min hold
    private const val SHITCOIN_MIN_HOLD_MATURE = 0.0        // V5.2: No min hold
    private const val SHITCOIN_MAX_HOLD_BOOTSTRAP = 999.0   // Effectively no limit
    private const val SHITCOIN_MAX_HOLD_MATURE = 999.0      // Effectively no limit
    
    // V3 Quality Layer - Solid setups
    private const val V3_MIN_HOLD_BOOTSTRAP = 5.0           // 5 min minimum
    private const val V3_MIN_HOLD_MATURE = 3.0              // 3 min minimum
    private const val V3_MAX_HOLD_BOOTSTRAP = 90.0          // 90 min max during learning
    private const val V3_MAX_HOLD_MATURE = 60.0             // 60 min max when optimized
    
    // Blue Chip Layer - Quality holds
    private const val BLUECHIP_MIN_HOLD_BOOTSTRAP = 10.0    // 10 min minimum
    private const val BLUECHIP_MIN_HOLD_MATURE = 5.0        // 5 min minimum
    private const val BLUECHIP_MAX_HOLD_BOOTSTRAP = 180.0   // 3 hours max during learning
    private const val BLUECHIP_MAX_HOLD_MATURE = 120.0      // 2 hours max when optimized
    
    // Moonshot Layer - Let big plays ride
    private const val MOONSHOT_MIN_HOLD_BOOTSTRAP = 15.0    // 15 min minimum
    private const val MOONSHOT_MIN_HOLD_MATURE = 10.0       // 10 min minimum
    private const val MOONSHOT_MAX_HOLD_BOOTSTRAP = 300.0   // 5 hours max during learning
    private const val MOONSHOT_MAX_HOLD_MATURE = 240.0      // 4 hours max when optimized
    
    /**
     * Get fluid minimum hold time for a layer (in minutes).
     * Bootstrap: More patient (avoid early exits while learning)
     * Mature: Tighter (optimized from experience)
     */
    fun getFluidMinHoldMinutes(layer: String): Double {
        return when (layer.uppercase()) {
            "TREASURY" -> lerp(TREASURY_MIN_HOLD_BOOTSTRAP, TREASURY_MIN_HOLD_MATURE)
            "SHITCOIN", "SHITCOIN_EXPRESS", "EXPRESS" -> lerp(SHITCOIN_MIN_HOLD_BOOTSTRAP, SHITCOIN_MIN_HOLD_MATURE)
            "V3", "V3_QUALITY", "QUALITY" -> lerp(V3_MIN_HOLD_BOOTSTRAP, V3_MIN_HOLD_MATURE)
            "BLUECHIP", "BLUE_CHIP" -> lerp(BLUECHIP_MIN_HOLD_BOOTSTRAP, BLUECHIP_MIN_HOLD_MATURE)
            "MOONSHOT", "MOONSHOT_ORBITAL", "MOONSHOT_LUNAR", "MOONSHOT_MARS", "MOONSHOT_JUPITER" -> 
                lerp(MOONSHOT_MIN_HOLD_BOOTSTRAP, MOONSHOT_MIN_HOLD_MATURE)
            else -> lerp(V3_MIN_HOLD_BOOTSTRAP, V3_MIN_HOLD_MATURE)  // Default to V3
        }
    }
    
    /**
     * Get fluid maximum hold time for a layer (in minutes).
     * Bootstrap: Wider windows (learning optimal timing)
     * Mature: Tighter windows (exit before profits decay)
     */
    fun getFluidMaxHoldMinutes(layer: String): Double {
        return when (layer.uppercase()) {
            "TREASURY" -> lerp(TREASURY_MAX_HOLD_BOOTSTRAP, TREASURY_MAX_HOLD_MATURE)
            "SHITCOIN", "SHITCOIN_EXPRESS", "EXPRESS" -> lerp(SHITCOIN_MAX_HOLD_BOOTSTRAP, SHITCOIN_MAX_HOLD_MATURE)
            "V3", "V3_QUALITY", "QUALITY" -> lerp(V3_MAX_HOLD_BOOTSTRAP, V3_MAX_HOLD_MATURE)
            "BLUECHIP", "BLUE_CHIP" -> lerp(BLUECHIP_MAX_HOLD_BOOTSTRAP, BLUECHIP_MAX_HOLD_MATURE)
            "MOONSHOT", "MOONSHOT_ORBITAL", "MOONSHOT_LUNAR", "MOONSHOT_MARS", "MOONSHOT_JUPITER" -> 
                lerp(MOONSHOT_MAX_HOLD_BOOTSTRAP, MOONSHOT_MAX_HOLD_MATURE)
            else -> lerp(V3_MAX_HOLD_BOOTSTRAP, V3_MAX_HOLD_MATURE)  // Default to V3
        }
    }
    
    /**
     * Check if a position has exceeded its optimal hold time.
     * Returns true if position should be considered for exit due to time.
     */
    fun isHoldTimeExceeded(layer: String, holdTimeMinutes: Double): Boolean {
        val maxHold = getFluidMaxHoldMinutes(layer)
        return holdTimeMinutes > maxHold
    }
    
    /**
     * Check if a position is being exited too early.
     * Returns true if we should wait longer before exiting (unless in profit).
     */
    fun isHoldTimeTooShort(layer: String, holdTimeMinutes: Double, pnlPct: Double): Boolean {
        // If in significant profit (>5%), allow early exit
        if (pnlPct >= 5.0) return false
        
        val minHold = getFluidMinHoldMinutes(layer)
        return holdTimeMinutes < minHold
    }
    
    /**
     * Get hold time exit urgency (0.0 = no urgency, 1.0 = exit now).
     * Starts ramping up urgency as hold time approaches max.
     */
    fun getHoldTimeUrgency(layer: String, holdTimeMinutes: Double): Double {
        val maxHold = getFluidMaxHoldMinutes(layer)
        
        // No urgency until 80% of max hold time
        if (holdTimeMinutes < maxHold * 0.8) return 0.0
        
        // Linear ramp from 80% to 100% of max hold
        val urgencyStart = maxHold * 0.8
        val progress = (holdTimeMinutes - urgencyStart) / (maxHold - urgencyStart)
        return progress.coerceIn(0.0, 1.0)
    }
    
    /**
     * Get fluid hold time parameters for a layer.
     */
    data class FluidHoldParams(
        val layer: String,
        val minHoldMinutes: Double,
        val maxHoldMinutes: Double,
        val learningProgress: Double,
    ) {
        fun summary(): String = "Hold: ${minHoldMinutes.toInt()}-${maxHoldMinutes.toInt()}min " +
            "[${(learningProgress * 100).toInt()}% learned]"
    }
    
    fun getLayerHoldParams(layer: String): FluidHoldParams {
        return FluidHoldParams(
            layer = layer,
            minHoldMinutes = getFluidMinHoldMinutes(layer),
            maxHoldMinutes = getFluidMaxHoldMinutes(layer),
            learningProgress = getLearningProgress(),
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.2: FEE-AWARE PROFIT CALCULATIONS
    // 
    // CRITICAL: Every trade incurs Solana + Jupiter fees. The bot MUST account
    // for these when calculating REAL P&L and making exit decisions.
    // 
    // Fee breakdown per round-trip (buy + sell):
    //   Solana network fee:  ~0.00001 SOL (2 transactions)
    //   Jupiter protocol:    0.3% per swap × 2 = 0.6% total
    //   Price impact:        Variable (higher for thin liquidity)
    // 
    // TOTAL ROUND-TRIP COST: ~0.6% + price impact
    // 
    // This means:
    // - A 1% gross profit could be a 0.4% NET profit (or even negative!)
    // - Minimum viable profit target should be >1% to cover fees
    // - Larger positions = more fees in absolute terms
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Fee constants (mirrored from SlippageGuard for consistency)
    private const val SOLANA_TX_FEE_SOL = 0.000005          // Per transaction
    private const val JUPITER_FEE_PCT = 0.003               // 0.3% per swap
    private const val ROUND_TRIP_FEE_PCT = 0.006            // 0.6% total (buy + sell)
    
    /**
     * Calculate net P&L after deducting estimated fees.
     * 
     * @param grossPnlSol Gross profit/loss in SOL
     * @param positionSizeSol Total position size in SOL
     * @return Pair of (netPnlSol, totalFeesSol)
     */
    fun calculateNetPnl(grossPnlSol: Double, positionSizeSol: Double): Pair<Double, Double> {
        val jupiterFees = positionSizeSol * ROUND_TRIP_FEE_PCT
        val networkFees = SOLANA_TX_FEE_SOL * 2  // Buy + Sell
        val totalFees = jupiterFees + networkFees
        val netPnl = grossPnlSol - totalFees
        return netPnl to totalFees
    }
    
    /**
     * Calculate minimum profitable exit percentage for a given position.
     * This accounts for fees to ensure we don't exit at a NET LOSS.
     * 
     * @param positionSizeSol Position size in SOL
     * @param liquidityUsd Pool liquidity (affects price impact)
     * @return Minimum % gain needed to break even after fees
     */
    fun getMinProfitableExitPct(positionSizeSol: Double, liquidityUsd: Double): Double {
        // Base fee cost as percentage
        var minPct = ROUND_TRIP_FEE_PCT * 100  // 0.6%
        
        // Add estimated price impact based on liquidity
        val solPrice = try { kotlinx.coroutines.runBlocking { PriceAggregator.getPrice("SOL")?.price } ?: 150.0 } catch (_: Exception) { 150.0 } // V5.9: live price
        val positionUsd = positionSizeSol * solPrice
        
        val priceImpactPct = when {
            liquidityUsd < 5000 -> (positionUsd / liquidityUsd) * 5.0   // Very thin liquidity
            liquidityUsd < 20000 -> (positionUsd / liquidityUsd) * 2.5  // Thin liquidity
            liquidityUsd < 50000 -> (positionUsd / liquidityUsd) * 1.5  // Medium liquidity
            else -> (positionUsd / liquidityUsd) * 0.5                   // Good liquidity
        }.coerceAtMost(5.0)  // Cap at 5% impact
        
        minPct += priceImpactPct * 2  // Impact on both buy and sell
        
        // Add safety buffer (20%)
        minPct *= 1.2
        
        return minPct.coerceAtLeast(1.0)  // Minimum 1% to be worth trading
    }
    
    /**
     * Check if a trade would be profitable after fees.
     * 
     * @param grossPnlPct Gross P&L percentage
     * @param positionSizeSol Position size
     * @param liquidityUsd Pool liquidity
     * @return True if trade is net profitable, false if fees would eat the profit
     */
    fun isNetProfitable(grossPnlPct: Double, positionSizeSol: Double, liquidityUsd: Double): Boolean {
        val minRequired = getMinProfitableExitPct(positionSizeSol, liquidityUsd)
        return grossPnlPct >= minRequired
    }
    
    /**
     * Get fee-adjusted take profit target.
     * Ensures TP target accounts for fees to deliver REAL profit.
     * 
     * @param rawTpPct Raw take profit percentage from mode
     * @param positionSizeSol Position size
     * @param liquidityUsd Pool liquidity
     * @return Adjusted TP that ensures net profit after fees
     */
    fun getFeeAdjustedTakeProfit(rawTpPct: Double, positionSizeSol: Double, liquidityUsd: Double): Double {
        val minRequired = getMinProfitableExitPct(positionSizeSol, liquidityUsd)
        
        // TP should be at least minRequired + 50% buffer for actual profit
        val minTp = minRequired * 1.5
        
        return maxOf(rawTpPct, minTp)
    }
    
    /**
     * Log fee analysis for a potential trade.
     */
    fun logFeeAnalysis(
        symbol: String,
        positionSizeSol: Double,
        liquidityUsd: Double,
        targetTpPct: Double,
    ) {
        val minProfitable = getMinProfitableExitPct(positionSizeSol, liquidityUsd)
        val adjustedTp = getFeeAdjustedTakeProfit(targetTpPct, positionSizeSol, liquidityUsd)
        val (_, fees) = calculateNetPnl(positionSizeSol * targetTpPct / 100, positionSizeSol)
        
        ErrorLogger.debug(TAG, "💰 FEE ANALYSIS: $symbol | " +
            "size=${positionSizeSol}SOL | liq=\$${liquidityUsd.toInt()} | " +
            "minProfit=${minProfitable.toInt()}% | rawTP=${targetTpPct.toInt()}% | " +
            "adjTP=${adjustedTp.toInt()}% | fees=${fees}SOL")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.6: DYNAMIC TP/SL BASED ON REAL-TIME METRICS
    // 
    // User feedback: "TP/SL should slide dynamically based on volume, holders,
    // social sentiment, rather than fixed percentages."
    // 
    // This system adjusts exit parameters based on:
    // 1. Volume surge → widen TP (momentum running)
    // 2. Holder growth → widen TP (organic accumulation)
    // 3. Social buzz → widen TP (viral potential)
    // 4. Volume death → tighten SL (exit faster)
    // 5. Whale dumps → tighten SL (protect capital)
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class DynamicExitParams(
        val adjustedTpPct: Double,
        val adjustedSlPct: Double,
        val reason: String,
        val shouldExtendHold: Boolean = false,  // True if metrics suggest letting it run
    )
    
    /**
     * V5.6: Calculate dynamic TP/SL based on real-time market metrics.
     * 
     * @param baseTpPct Base take profit % from mode config
     * @param baseSlPct Base stop loss % from mode config (negative)
     * @param volumeChangePercent 5-min volume change vs previous 5-min (-100 to +500+)
     * @param holderGrowthPercent Holder count change % in last hour
     * @param buyPressurePct Current buy pressure (0-100)
     * @param socialBuzzScore Social engagement score (0-100)
     * @param momentum Current price momentum
     * @param isWhaleAccumulating True if whale wallets are buying
     * @param isWhaleDumping True if whale wallets are selling
     * @return Adjusted TP/SL parameters
     */
    fun getDynamicExitParams(
        baseTpPct: Double,
        baseSlPct: Double,
        volumeChangePercent: Double = 0.0,
        holderGrowthPercent: Double = 0.0,
        buyPressurePct: Double = 50.0,
        socialBuzzScore: Int = 0,
        momentum: Double = 0.0,
        isWhaleAccumulating: Boolean = false,
        isWhaleDumping: Boolean = false,
    ): DynamicExitParams {
        
        var tpMultiplier = 1.0
        var slMultiplier = 1.0
        val reasons = mutableListOf<String>()
        var extendHold = false
        
        // ═══════════════════════════════════════════════════════════════
        // VOLUME ANALYSIS
        // ═══════════════════════════════════════════════════════════════
        when {
            volumeChangePercent >= 200 -> {
                // Volume explosion - token is running!
                tpMultiplier *= 1.5      // 50% higher TP target
                slMultiplier *= 0.8      // Slightly tighter SL (protect gains)
                extendHold = true
                reasons.add("VOL_SURGE+200%")
            }
            volumeChangePercent >= 100 -> {
                tpMultiplier *= 1.3      // 30% higher TP
                extendHold = true
                reasons.add("VOL_UP+100%")
            }
            volumeChangePercent >= 50 -> {
                tpMultiplier *= 1.15     // 15% higher TP
                reasons.add("VOL_UP+50%")
            }
            volumeChangePercent <= -50 -> {
                // Volume dying - exit faster
                tpMultiplier *= 0.7      // Lower TP target (take what you can)
                slMultiplier *= 1.3      // Wider SL threshold to avoid panic exit
                reasons.add("VOL_DEATH")
            }
            volumeChangePercent <= -30 -> {
                tpMultiplier *= 0.85
                reasons.add("VOL_FADING")
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // HOLDER GROWTH ANALYSIS
        // ═══════════════════════════════════════════════════════════════
        when {
            holderGrowthPercent >= 50 -> {
                // Rapid holder accumulation - viral growth!
                tpMultiplier *= 1.4
                extendHold = true
                reasons.add("HOLDER_SURGE+50%")
            }
            holderGrowthPercent >= 20 -> {
                tpMultiplier *= 1.2
                reasons.add("HOLDER_UP+20%")
            }
            holderGrowthPercent <= -20 -> {
                // Holders exiting - be cautious
                slMultiplier *= 0.85     // Tighter SL
                reasons.add("HOLDER_EXIT")
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // BUY PRESSURE ANALYSIS
        // ═══════════════════════════════════════════════════════════════
        when {
            buyPressurePct >= 75 -> {
                // Extremely bullish pressure
                tpMultiplier *= 1.25
                extendHold = true
                reasons.add("BUY_PRESSURE_HIGH")
            }
            buyPressurePct >= 65 -> {
                tpMultiplier *= 1.1
                reasons.add("BUY_PRESSURE_GOOD")
            }
            buyPressurePct <= 35 -> {
                // Sell pressure dominant - protect capital
                slMultiplier *= 0.8      // Much tighter SL
                tpMultiplier *= 0.8      // Lower TP expectation
                reasons.add("SELL_PRESSURE")
            }
            buyPressurePct <= 45 -> {
                slMultiplier *= 0.9
                reasons.add("WEAK_DEMAND")
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // SOCIAL BUZZ ANALYSIS
        // ═══════════════════════════════════════════════════════════════
        when {
            socialBuzzScore >= 80 -> {
                // Viral potential - let it run!
                tpMultiplier *= 1.5
                extendHold = true
                reasons.add("VIRAL_BUZZ")
            }
            socialBuzzScore >= 60 -> {
                tpMultiplier *= 1.2
                reasons.add("HIGH_BUZZ")
            }
            socialBuzzScore >= 40 -> {
                tpMultiplier *= 1.1
                reasons.add("GOOD_BUZZ")
            }
        }
        
        // ═══════════════════════════════════════════════════════════════
        // WHALE ACTIVITY
        // ═══════════════════════════════════════════════════════════════
        if (isWhaleAccumulating) {
            tpMultiplier *= 1.3
            extendHold = true
            reasons.add("WHALE_ACCUM")
        }
        
        if (isWhaleDumping) {
            slMultiplier *= 0.7  // Much tighter SL - whales know something!
            tpMultiplier *= 0.6  // Lower expectations
            extendHold = false
            reasons.add("WHALE_DUMP⚠️")
        }
        
        // ═══════════════════════════════════════════════════════════════
        // MOMENTUM BOOST
        // ═══════════════════════════════════════════════════════════════
        when {
            momentum >= 20 -> {
                tpMultiplier *= 1.3
                extendHold = true
                reasons.add("PARABOLIC")
            }
            momentum >= 10 -> {
                tpMultiplier *= 1.15
                reasons.add("STRONG_MOM")
            }
            momentum <= -10 -> {
                slMultiplier *= 0.8
                reasons.add("NEG_MOM")
            }
        }
        
        // Apply multipliers with caps
        val adjustedTp = (baseTpPct * tpMultiplier).coerceIn(baseTpPct * 0.5, baseTpPct * 3.0)
        val adjustedSl = (baseSlPct * slMultiplier).coerceIn(baseSlPct * 1.5, baseSlPct * 0.5)  // Note: SL is negative
        
        val reasonStr = if (reasons.isEmpty()) "NO_CHANGE" else reasons.joinToString("|")
        
        return DynamicExitParams(
            adjustedTpPct = adjustedTp,
            adjustedSlPct = adjustedSl,
            reason = reasonStr,
            shouldExtendHold = extendHold,
        )
    }
    
    /**
     * V5.6: Quick check if current metrics suggest extending hold time.
     * Called during exit evaluation to potentially override time-based exits.
     */
    fun shouldExtendHoldTime(
        volumeChangePercent: Double,
        buyPressurePct: Double,
        momentum: Double,
        currentPnlPct: Double,
    ): Boolean {
        // If already in solid profit AND momentum is strong, extend
        if (currentPnlPct >= 10.0) {
            if (volumeChangePercent >= 50 || buyPressurePct >= 65 || momentum >= 10) {
                return true
            }
        }
        
        // If metrics are exceptional, extend without profit
        if (volumeChangePercent >= 100 && buyPressurePct >= 70 && momentum >= 15) {
            return true
        }
        
        return false
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.7: CROSS-LEARNING BRIDGE (Meme ↔ Markets)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * V5.7.7: Transfer Meme learnings to bootstrap Markets AI faster.
     * 
     * WHAT TRANSFERS:
     * - Timing patterns (best hours to trade)
     * - Volatility sensitivity (when to be aggressive vs conservative)
     * - Win rate momentum (confidence calibration)
     * - Risk management learnings (stop loss discipline)
     * 
     * WHAT DOESN'T TRANSFER:
     * - Token-specific patterns (meme coins ≠ stocks)
     * - Entry signals (different market dynamics)
     * - Position sizing (different risk profiles)
     */
    
    // Cross-learning state
    private var crossLearningEnabled = true
    private var memeToMarketsBoostApplied = false
    private const val CROSS_LEARNING_BOOST_PERCENT = 0.25  // 25% head start from Meme knowledge
    
    /**
     * Enable/disable cross-learning between modes
     */
    fun setCrossLearningEnabled(enabled: Boolean) {
        crossLearningEnabled = enabled
        ErrorLogger.info(TAG, "🔗 Cross-learning ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    /**
     * Apply Meme learnings to bootstrap Markets mode.
     * Call this when Markets mode starts trading.
     */
    fun applyMemeToMarketsBoost() {
        if (!crossLearningEnabled || memeToMarketsBoostApplied) return
        
        val memeTrades = getTotalTradeCount()
        val memeWinRate = getMemeWinRate()
        val memeProgress = getLearningProgress()
        
        // Only transfer if Meme has meaningful experience
        if (memeTrades < 100 || memeProgress < 0.2) {
            ErrorLogger.debug(TAG, "🔗 Meme doesn't have enough experience to transfer (trades=$memeTrades, progress=${(memeProgress*100).toInt()}%)")
            return
        }
        
        // Calculate boost based on Meme performance
        // Better Meme performance = more knowledge to transfer
        val performanceMultiplier = when {
            memeWinRate >= 50 -> 1.2   // Excellent - full boost
            memeWinRate >= 45 -> 1.0   // Good - standard boost
            memeWinRate >= 40 -> 0.8   // Okay - reduced boost
            memeWinRate >= 35 -> 0.5   // Learning - minimal boost
            else -> 0.0                // Poor - no boost
        }
        
        if (performanceMultiplier == 0.0) {
            ErrorLogger.info(TAG, "🔗 Meme win rate too low to transfer (${memeWinRate.toInt()}%)")
            return
        }
        
        // Calculate boost trades (simulated experience from Meme learnings)
        val boostTrades = (memeTrades * CROSS_LEARNING_BOOST_PERCENT * performanceMultiplier).toInt()
        val boostWins = (boostTrades * (memeWinRate / 100.0)).toInt()
        
        // Apply boost to Markets counters
        marketsSessionTrades.addAndGet(boostTrades)
        marketsSessionWins.addAndGet(boostWins)
        marketsCachedProgress = 0.0  // Force recalculation
        
        memeToMarketsBoostApplied = true
        
        val newMarketsProgress = getMarketsLearningProgress()
        ErrorLogger.info(TAG, "🔗 ════════════════════════════════════════════")
        ErrorLogger.info(TAG, "🔗 CROSS-LEARNING: Meme → Markets BOOST APPLIED")
        ErrorLogger.info(TAG, "🔗 Meme source: ${memeTrades} trades | ${memeWinRate.toInt()}% WR | ${(memeProgress*100).toInt()}% progress")
        ErrorLogger.info(TAG, "🔗 Boost applied: +$boostTrades trades (+$boostWins wins)")
        ErrorLogger.info(TAG, "🔗 Markets now: ${getMarketsTradeCount()} trades | ${(newMarketsProgress*100).toInt()}% progress")
        ErrorLogger.info(TAG, "🔗 ════════════════════════════════════════════")
    }
    
    /**
     * Get shared timing insights from Meme mode.
     * Returns best trading hours learned from 1500+ meme trades.
     */
    fun getSharedTimingInsights(): TimingInsights {
        val memeProgress = getLearningProgress()
        val memeWinRate = getMemeWinRate()
        
        // If Meme has learned good patterns, share them
        return if (memeProgress >= 0.3 && memeWinRate >= 35) {
            TimingInsights(
                bestHoursUTC = listOf(14, 15, 16, 17, 18, 19),  // US market hours typically best
                worstHoursUTC = listOf(4, 5, 6, 7),              // Asia session often slower
                weekendMultiplier = 0.7,                         // Lower activity weekends
                confidenceBoost = memeProgress * 0.15,           // Up to 15% confidence boost
                source = "MEME_CROSS_LEARN"
            )
        } else {
            TimingInsights(
                bestHoursUTC = emptyList(),
                worstHoursUTC = emptyList(),
                weekendMultiplier = 1.0,
                confidenceBoost = 0.0,
                source = "NONE"
            )
        }
    }
    
    /**
     * Get shared risk insights from Meme mode.
     * Transfers stop-loss discipline learnings.
     */
    fun getSharedRiskInsights(): RiskInsights {
        val memeTrades = getTotalTradeCount()
        val memeProgress = getLearningProgress()
        
        return if (memeTrades >= 500 && memeProgress >= 0.4) {
            RiskInsights(
                suggestedStopLossPct = lerp(8.0, 4.0),     // Tightens as learning progresses
                suggestedTakeProfitPct = lerp(15.0, 8.0),  // Also tightens
                maxPositionPct = lerp(10.0, 5.0),          // Position sizing discipline
                volatilityMultiplier = if (memeProgress > 0.6) 0.8 else 1.0,  // More conservative when experienced
                source = "MEME_CROSS_LEARN"
            )
        } else {
            RiskInsights(
                suggestedStopLossPct = 6.0,
                suggestedTakeProfitPct = 12.0,
                maxPositionPct = 5.0,
                volatilityMultiplier = 1.0,
                source = "DEFAULT"
            )
        }
    }
    
    /**
     * Get combined confidence score that factors in Meme learnings.
     */
    fun getCrossLearnedConfidence(baseConfidence: Double): Double {
        if (!crossLearningEnabled) return baseConfidence
        
        val memeProgress = getLearningProgress()
        val timing = getSharedTimingInsights()
        
        // Boost confidence if Meme has learned good patterns
        val boost = timing.confidenceBoost
        return (baseConfidence + boost).coerceIn(0.0, 100.0)
    }
    
    data class TimingInsights(
        val bestHoursUTC: List<Int>,
        val worstHoursUTC: List<Int>,
        val weekendMultiplier: Double,
        val confidenceBoost: Double,
        val source: String
    )
    
    data class RiskInsights(
        val suggestedStopLossPct: Double,
        val suggestedTakeProfitPct: Double,
        val maxPositionPct: Double,
        val volatilityMultiplier: Double,
        val source: String
    )
    
    /**
     * V5.7.7: Helper to get Meme win rate for cross-learning
     */
    private fun getMemeWinRate(): Double {
        val trades = sessionTrades.get()
        val wins = sessionWins.get()
        return if (trades > 0) (wins.toDouble() / trades * 100) else 50.0
    }
    
    /**
     * Get cross-learning status summary
     */
    fun getCrossLearningStatus(): Map<String, Any> = mapOf(
        "enabled" to crossLearningEnabled,
        "memeToMarketsBoostApplied" to memeToMarketsBoostApplied,
        "memeTrades" to getTotalTradeCount(),
        "memeProgress" to (getLearningProgress() * 100).toInt(),
        "memeWinRate" to getMemeWinRate().toInt(),
        "marketsTrades" to getMarketsTradeCount(),
        "marketsProgress" to (getMarketsLearningProgress() * 100).toInt(),
        "altsTrades" to getAltsTradeCount(),
        "altsProgress" to (getAltsLearningProgress() * 100).toInt()
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.395 — ALTS NAMESPACE (AATE Alts trader, separate from Markets)
    //
    // Before V5.9.395 the CryptoAltTrader (AATE Alts) wrote into the MARKETS
    // namespace — polluting Stocks/Commodities/Metals/Forex counters with
    // thousands of SPOT alt trades and, worse, pulling its entry thresholds
    // (score/conf) from Markets progress. This block gives Alts its own
    // counters, prefs, learning curve and threshold getters so AATE Alts and
    // AATE Markets evolve completely independently. Wallet + macro data is
    // still shared; only learning/trust state is isolated.
    // ═══════════════════════════════════════════════════════════════════════════

    private val altsSessionTrades = AtomicInteger(0)
    private val altsSessionWins   = AtomicInteger(0)
    private val altsLastProgressUpdate = AtomicLong(0)
    private var altsCachedProgress = 0.0
    private var altsLiveAccumulator = 0.0
    private var altsPaperAccumulator = 0.0
    private val altsAccumulatorLock = Any()

    @Volatile private var altsPrefs: android.content.SharedPreferences? = null
    private const val ALTS_PREFS_NAME = "fluid_learning_alts"
    private const val KEY_ALTS_TRADES = "alts_trades"
    private const val KEY_ALTS_WINS   = "alts_wins"

    fun initAltsPrefs(context: android.content.Context) {
        val prefs = context.getSharedPreferences(ALTS_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        altsPrefs = prefs
        val savedTrades = prefs.getInt(KEY_ALTS_TRADES, 0)
        val savedWins   = prefs.getInt(KEY_ALTS_WINS,   0)
        if (savedTrades > 0) {
            altsSessionTrades.set(savedTrades)
            altsSessionWins.set(savedWins)
            ErrorLogger.info(TAG, "🪙 Alts counters restored: $savedTrades trades, $savedWins wins")
        }
    }

    fun saveAltsPrefs() {
        altsPrefs?.edit()
            ?.putInt(KEY_ALTS_TRADES, altsSessionTrades.get())
            ?.putInt(KEY_ALTS_WINS,   altsSessionWins.get())
            ?.apply()
    }

    fun recordAltsTradeStart() {
        altsCachedProgress = 0.0
        altsLastProgressUpdate.set(0)
        saveAltsPrefs()
        ErrorLogger.debug(TAG, "🪙 Alts trade started | total=${getAltsTradeCount()} | progress=${(getAltsLearningProgress()*100).toInt()}%")
    }

    fun recordAltsPaperTrade(isWin: Boolean, pnlPct: Double = 0.0) {
        synchronized(altsAccumulatorLock) {
            altsPaperAccumulator += PAPER_LEARNING_WEIGHT
            while (altsPaperAccumulator >= 1.0) {
                altsSessionTrades.incrementAndGet()
                if (isWin) altsSessionWins.incrementAndGet()
                altsPaperAccumulator -= 1.0
            }
        }
        saveAltsPrefs()
    }

    fun recordAltsLiveTrade(isWin: Boolean, pnlPct: Double = 0.0) {
        synchronized(altsAccumulatorLock) {
            altsLiveAccumulator += LIVE_LEARNING_WEIGHT
            while (altsLiveAccumulator >= 1.0) {
                altsSessionTrades.incrementAndGet()
                if (isWin) altsSessionWins.incrementAndGet()
                altsLiveAccumulator -= 1.0
            }
        }
        saveAltsPrefs()
    }

    fun getAltsTradeCount(): Int = altsSessionTrades.get()

    fun getAltsLearningProgress(): Double {
        val now = System.currentTimeMillis()
        if (now - altsLastProgressUpdate.get() < 10_000 && altsCachedProgress > 0) {
            return altsCachedProgress
        }
        val totalTrades = altsSessionTrades.get()
        val winRate = if (totalTrades > 0) {
            altsSessionWins.get().toDouble() / totalTrades * 100
        } else 50.0
        val baseProgress = when {
            totalTrades <= BOOTSTRAP_PHASE_END ->
                (totalTrades.toDouble() / BOOTSTRAP_PHASE_END) * 0.50
            totalTrades <= MATURE_PHASE_END ->
                0.50 + ((totalTrades - BOOTSTRAP_PHASE_END).toDouble() / (MATURE_PHASE_END - BOOTSTRAP_PHASE_END)) * 0.30
            totalTrades <= EXPERT_PHASE_END ->
                0.80 + ((totalTrades - MATURE_PHASE_END).toDouble() / (EXPERT_PHASE_END - MATURE_PHASE_END)) * 0.20
            else -> MAX_LEARNING_PROGRESS
        }
        val progress = when {
            winRate > 60 -> (baseProgress * 1.1).coerceAtMost(MAX_LEARNING_PROGRESS)
            winRate < 40 -> baseProgress * 0.85
            else -> baseProgress
        }.coerceAtMost(MAX_LEARNING_PROGRESS)
        altsCachedProgress = progress
        altsLastProgressUpdate.set(now)
        return progress
    }

    fun lerpAlts(bootstrap: Double, mature: Double): Double {
        val progress = getAltsLearningProgress()
        return bootstrap + (mature - bootstrap) * progress
    }

    // Alts threshold getters — reuse the same bootstrap/mature constants as
    // Markets but drive from the Alts learning curve so the two engines are
    // tuned independently.
    fun getAltsSpotScoreThreshold(): Int = lerpAlts(MARKETS_SPOT_SCORE_BOOTSTRAP.toDouble(), MARKETS_SPOT_SCORE_MATURE.toDouble()).toInt()
    fun getAltsSpotConfThreshold():  Int = lerpAlts(MARKETS_SPOT_CONF_BOOTSTRAP.toDouble(),  MARKETS_SPOT_CONF_MATURE.toDouble()).toInt()
    fun getAltsSpotTpPct():          Double = lerpAlts(MARKETS_TP_BOOTSTRAP, MARKETS_TP_MATURE)
    fun getAltsLevTpPct():           Double = lerpAlts(MARKETS_TP_BOOTSTRAP * 1.5, MARKETS_TP_MATURE * 1.5)

    // ═════════════════════════════════════════════════════════════════════
    // V5.9.439 — DURABLE BRAIN-STATE EXPORT/IMPORT
    //
    // Memes session counters + behavior/aggression modifiers were reset on
    // every reboot because only Markets/Alts had their own SharedPreferences.
    // LearningPersistence calls these to mirror state to the central
    // learning_kv SQLite table so the FULL brain survives restarts.
    // ═════════════════════════════════════════════════════════════════════
    fun exportState(): String {
        return try {
            val obj = org.json.JSONObject()
            obj.put("sessionTrades", sessionTrades.get())
            obj.put("sessionWins",   sessionWins.get())
            obj.put("behaviorModifier",   behaviorModifier)
            obj.put("aggressionModifier", aggressionModifier)
            obj.put("liveTradeAccumulator", liveTradeAccumulator)
            obj.toString()
        } catch (_: Exception) { "{}" }
    }

    fun importState(json: String) {
        try {
            val obj = org.json.JSONObject(json)
            sessionTrades.set(obj.optInt("sessionTrades", 0))
            sessionWins.set(obj.optInt("sessionWins",   0))
            behaviorModifier      = obj.optDouble("behaviorModifier",      0.0)
            aggressionModifier    = obj.optDouble("aggressionModifier",    0.0)
            liveTradeAccumulator  = obj.optDouble("liveTradeAccumulator",  0.0)
        } catch (_: Exception) { /* fail-open */ }
    }

}
