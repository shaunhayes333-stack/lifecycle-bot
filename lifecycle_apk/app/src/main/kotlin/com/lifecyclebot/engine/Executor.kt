package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.NotificationHistory

import com.lifecyclebot.data.*
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.util.pct
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * FIX #3: Rugged contracts blacklist - stores by mint address (not ticker)
 * Persists across restarts. No rebuy after -33% loss.
 */
object RuggedContracts {
    private const val PREFS_NAME = "rugged_contracts"
    private var ctx: Context? = null
    private val blacklist = ConcurrentHashMap<String, Double>()  // mint -> loss%
    
    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info("RuggedContracts", "💀 Loaded ${blacklist.size} blacklisted contracts")
    }
    
    fun add(mint: String, symbol: String, lossPct: Double) {
        blacklist[mint] = lossPct
        save()
        ErrorLogger.info("RuggedContracts", "💀 Blacklisted $symbol ($mint) - lost ${lossPct.toInt()}%")
    }
    
    fun isBlacklisted(mint: String): Boolean = blacklist.containsKey(mint)
    
    fun getCount(): Int = blacklist.size
    
    private fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = org.json.JSONObject()
            blacklist.forEach { (k, v) -> json.put(k, v) }
            prefs.edit().putString("blacklist", json.toString()).apply()
        } catch (_: Exception) {}
    }
    
    private fun load() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("blacklist", null) ?: return
            val obj = org.json.JSONObject(json)
            obj.keys().forEach { key ->
                blacklist[key] = obj.optDouble(key, 0.0)
            }
        } catch (_: Exception) {}
    }
}

/**
 * Executor v3 — SecurityGuard integrated
 *
 * Every live trade now passes through SecurityGuard checks:
 *   1. Pre-flight (buy): circuit breaker, wallet reserve, rate limit,
 *      position cap, price/volume anomaly
 *   2. Quote validation: price impact ≤ 3%, output ≥ 90% expected
 *   3. Sign delay enforced (500ms between sign and broadcast)
 *   4. Post-trade: circuit breaker counters updated
 *   5. Key integrity verified before every tx
 *   6. All log messages sanitised — no keys in logs
 */
class Executor(
    private val cfg: () -> com.lifecyclebot.data.BotConfig,
    private val onLog: (String, String) -> Unit,
    private val onNotify: (String, String, com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType) -> Unit,
    private val onToast: (String) -> Unit = {},  // Toast callback for immediate visual feedback
    val security: SecurityGuard,
    private val sounds: SoundManager? = null,
) {
    // Lazy init to get Jupiter API key from config
    private val jupiter: JupiterApi by lazy { JupiterApi(cfg().jupiterApiKey) }
    var brain: BotBrain? = null
    var tradeDb: TradeDatabase? = null
    var onPaperBalanceChange: ((Double) -> Unit)? = null  // Callback to update paper wallet balance
    private val slippageGuard: SlippageGuard by lazy { SlippageGuard(jupiter) }
    private var lastNewTokenSoundMs = 0L

    // ── position sizing ───────────────────────────────────────────────

    /**
     * Smart position sizing — delegates to SmartSizer.
     * Size scales with wallet balance, conviction, win rate, and drawdown.
     * Returns 0.0 if sizing conditions block the trade (drawdown circuit breaker etc.)
     */
    fun buySizeSol(
        entryScore: Double,
        walletSol: Double,
        currentOpenPositions: Int = 0,
        currentTotalExposure: Double = 0.0,
        walletTotalTrades: Int = 0,
        liquidityUsd: Double = 0.0,
        mcapUsd: Double = 0.0,
        // NEW: AI-driven parameters
        aiConfidence: Double = 50.0,
        phase: String = "unknown",
        source: String = "unknown",
        brain: BotBrain? = null,
        setupQuality: String = "C",    // A+ / B / C from strategy
    ): Double {
        val isPaperMode = cfg().paperMode
        
        // Update session peak (mode-aware to prevent paper stats affecting live)
        SmartSizer.updateSessionPeak(walletSol, isPaperMode)

        val perf = SmartSizer.getPerformanceContext(walletSol, walletTotalTrades, isPaperMode)
        val solPx = try { WalletManager.lastKnownSolPrice } catch (_: Exception) { 130.0 }

        val result = SmartSizer.calculate(
            walletSol            = walletSol,
            entryScore           = entryScore,
            perf                 = perf,
            cfg                  = cfg(),
            openPositionCount    = currentOpenPositions,
            currentTotalExposure = currentTotalExposure,
            liquidityUsd         = liquidityUsd,
            solPriceUsd          = solPx,
            mcapUsd              = mcapUsd,
            aiConfidence         = aiConfidence,
            phase                = phase,
            source               = source,
            brain                = brain,
            setupQuality         = setupQuality,
        )

        if (result.solAmount <= 0.0) {
            onLog("📊 AI Sizer blocked: ${result.explanation}", "sizing")
        } else {
            onLog("📊 AI Sizer: conf=${aiConfidence.toInt()} → ${result.explanation}", "sizing")
        }

        return result.solAmount
    }
    
    /**
     * Calculate buy size for FDG evaluation.
     * Simplified wrapper around buySizeSol for the Final Decision Gate.
     */
    fun calculateBuySize(
        ts: TokenState,
        walletSol: Double,
        totalExposureSol: Double,
        openPositionCount: Int,
        quality: String,
    ): Double {
        return buySizeSol(
            entryScore = ts.entryScore,
            walletSol = walletSol,
            currentOpenPositions = openPositionCount,
            currentTotalExposure = totalExposureSol,
            walletTotalTrades = 0,  // Not critical for size calc
            liquidityUsd = ts.lastLiquidityUsd,
            mcapUsd = ts.lastMcap,
            aiConfidence = 50.0,  // Default confidence for FDG size calc
            phase = ts.phase,
            source = ts.source,
            brain = brain,
            setupQuality = quality,
        )
    }

    // ── top-up sizing ─────────────────────────────────────────────────

    /**
     * Size a top-up (pyramid) add.
     * Each successive top-up is smaller than the one before:
     *   1st top-up: initialSize * multiplier          (e.g. 0.10 * 0.50 = 0.05)
     *   2nd top-up: initialSize * multiplier^2        (e.g. 0.10 * 0.25 = 0.025)
     *   3rd top-up: initialSize * multiplier^3        (e.g. 0.10 * 0.125 = 0.0125)
     *
     * This keeps total exposure bounded while still adding meaningful size
     * into the strongest moves.
     */
    fun topUpSizeSol(
        pos: Position,
        walletSol: Double,
        totalExposureSol: Double,
    ): Double {
        val c          = cfg()
        val topUpNum   = pos.topUpCount + 1  // which top-up this would be
        val initSize   = pos.initialCostSol.coerceAtLeast(c.smallBuySol)
        val multiplier = Math.pow(c.topUpSizeMultiplier, topUpNum.toDouble())
        var size       = initSize * multiplier

        // Top-up cap from config
        val currentTotal  = pos.costSol
        val remainingRoom = c.topUpMaxTotalSol - currentTotal
        size = size.coerceAtMost(remainingRoom)

        // Never exceed wallet exposure cap
        // Wallet room from SmartSizer exposure — unlimited from config side

        // Minimum viable trade
        return size.coerceAtMost(walletSol * 0.15)  // never more than 15% of wallet in one add
               .coerceAtLeast(0.0)
    }

    /**
     * Decides whether to top up an open position.
     *
     * Rules (all must pass):
     *   1. Top-up enabled in config
     *   2. Position is open and profitable
     *   3. Gain has crossed the next top-up threshold
     *   4. Not at max top-up count
     *   5. Cooldown since last top-up has passed
     *   6. EMA fan is bullish (if required by config)
     *   7. Volume is not exhausting (don't add into a dying move)
     *   8. No spike top forming (never add at the top)
     *   9. Sufficient room left in position/wallet caps
     *   10. Exit score is LOW (momentum still healthy)
     */
    fun shouldTopUp(
        ts: TokenState,
        entryScore: Double,
        exitScore: Double,
        emafanAlignment: String,
        volScore: Double,
        exhaust: Boolean,
    ): Boolean {
        val c   = cfg()
        val pos = ts.position

        if (!c.topUpEnabled)   return false
        if (!pos.isOpen)       return false
        if (!c.autoTrade)      return false

        val gainPct   = pct(pos.entryPrice, ts.ref)
        val heldMins  = (System.currentTimeMillis() - pos.entryTime) / 60_000.0

        // Must be profitable — never average down
        if (gainPct <= 0) return false

        // CHANGE 6: High-conviction and long-hold positions pyramid deeper
        // For MOONSHOTS (100x+), allow unlimited top-ups as long as position is healthy
        val nextTopUp = pos.topUpCount + 1
        val gainPctNow = pct(pos.entryPrice, ts.ref)
        val effectiveMax = when {
            gainPctNow >= 10000.0 -> 10    // 100x+ moonshot: up to 10 top-ups
            gainPctNow >= 1000.0  -> 7     // 10x+ strong runner: up to 7 top-ups
            pos.isLongHold || pos.entryScore >= 75.0 -> 5
            else -> c.topUpMaxCount
        }
        if (nextTopUp > effectiveMax) return false

        // CHANGE 3: High-conviction entries pyramid earlier
        // Entry score ≥75 = pre-grad/whale/BULL_FAN confluence — fire at 12% not 25%
        val earlyFirst = pos.entryScore >= 75.0 && pos.topUpCount == 0
        val baseMin    = if (earlyFirst) 12.0 else c.topUpMinGainPct
        val requiredGain = baseMin + (pos.topUpCount * c.topUpGainStepPct)
        if (gainPct < requiredGain) return false

        // Cooldown since last top-up
        if (pos.topUpCount > 0) {
            val minsSinceTopUp = (System.currentTimeMillis() - pos.lastTopUpTime) / 60_000.0
            if (minsSinceTopUp < c.topUpMinCooldownMins) return false
        }

        // EMA fan requirement
        if (c.topUpRequireEmaFan && emafanAlignment != "BULL_FAN") return false

        // Don't add into exhaustion
        if (exhaust) return false

        // Don't add if exit score is very high (momentum dying)
        // Raised threshold from 35 to 50 to allow more top-ups on runners
        if (exitScore >= 50.0) return false

        // Don't add if entry score is very low (market structure weak)
        if (entryScore < 15.0) return false  // was 20.0 - lowered for more aggressive pyramiding

        // Volume must be healthy (but not required to be super strong)
        if (volScore < 25.0) return false  // was 30.0 - lowered

        // ═══════════════════════════════════════════════════════════════════
        // TREASURY-AWARE MAX POSITION SIZE
        // 
        // Higher treasury = can afford larger positions on confirmed runners
        // ScalingMode already handles this, but we add extra room for moonshots
        // ═══════════════════════════════════════════════════════════════════
        val effectiveMaxSol = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            // Scale max position with treasury tier
            when (tier) {
                ScalingMode.Tier.INSTITUTIONAL -> c.topUpMaxTotalSol * 3.0  // 3x max position
                ScalingMode.Tier.SCALED        -> c.topUpMaxTotalSol * 2.0  // 2x max position
                ScalingMode.Tier.GROWTH        -> c.topUpMaxTotalSol * 1.5  // 1.5x max position
                ScalingMode.Tier.STANDARD      -> c.topUpMaxTotalSol * 1.2  // 1.2x max position
                ScalingMode.Tier.MICRO         -> c.topUpMaxTotalSol        // Standard max
            }
        } catch (_: Exception) { c.topUpMaxTotalSol }
        
        // Must have room left (using treasury-adjusted max)
        val remainingRoom = effectiveMaxSol - pos.costSol
        if (remainingRoom < 0.005) return false

        return true
    }

    // ══════════════════════════════════════════════════════════════
    // GRADUATED POSITION BUILDING
    // Split entry into phases: 40% initial, 30% confirm, 30% full
    // ══════════════════════════════════════════════════════════════

    fun graduatedInitialSize(fullSize: Double, quality: String): Double {
        return fullSize * graduatedInitialPct(quality)
    }
    
    fun graduatedInitialPct(quality: String): Double {
        return when (quality) {
            "A+" -> 0.50
            "B"  -> 0.40
            else -> 0.35
        }
    }

    fun shouldGraduatedAdd(pos: Position, currentPrice: Double, volScore: Double): Pair<Double, Int>? {
        if (pos.isFullyBuilt || pos.targetBuildSol <= 0) return null
        if (pos.buildPhase !in listOf(1, 2)) return null
        
        val gainPct = pct(pos.entryPrice, currentPrice)
        val remaining = pos.targetBuildSol - pos.costSol
        val timeSince = System.currentTimeMillis() - pos.entryTime
        
        // Phase 2: 3%+ gain, 30s delay
        if (pos.buildPhase == 1 && gainPct >= 3.0 && timeSince >= 30_000 && volScore >= 35) {
            val add = remaining * 0.50
            if (add >= 0.005) return Pair(add, 2)
        }
        
        // Phase 3: 8%+ gain
        if (pos.buildPhase == 2 && gainPct >= 8.0) {
            val add = remaining.coerceAtLeast(0.005)
            if (add >= 0.005) return Pair(add, 3)
        }
        
        return null
    }

    fun doGraduatedAdd(ts: TokenState, addSol: Double, newPhase: Int) {
        val price = ts.ref
        if (price <= 0 || !ts.position.isOpen) return
        
        val addTokens = addSol / maxOf(price, 1e-12)
        val newQty = ts.position.qtyToken + addTokens
        val newCost = ts.position.costSol + addSol
        
        ts.position = ts.position.copy(
            qtyToken = newQty,
            costSol = newCost,
            buildPhase = newPhase
        )
        
        val trade = Trade("BUY", "paper", addSol, price, System.currentTimeMillis(), score = 0.0)
        ts.trades.add(trade)
        security.recordTrade(trade)
        onPaperBalanceChange?.invoke(-addSol)
        
        val emoji = if (newPhase == 3) "🎯" else "📈"
        onLog("$emoji BUILD P$newPhase | +${addSol.fmt(3)} SOL", ts.mint)
    }

    // ── trailing stop ─────────────────────────────────────────────────
    // V5: SMART RUNNER CAPTURE - Dynamic trailing based on trend health
    
    /**
     * Smart Trailing Floor - Dynamically adjusts based on:
     * 1. Gain percentage (base adjustment)
     * 2. EMA fan health (widening fan = looser trail)
     * 3. Volume trend (increasing = looser trail)
     * 4. Buy pressure (strong = looser trail)
     * 
     * The goal is to ride runners to their full potential while still
     * protecting gains when momentum starts to fade.
     */
    fun trailingFloor(pos: Position, current: Double,
                       modeConf: AutoModeEngine.ModeConfig? = null,
                       // V5: Additional signals for smart trailing
                       emaFanAlignment: String = "FLAT",
                       emaFanWidening: Boolean = false,
                       volScore: Double = 50.0,
                       pressScore: Double = 50.0,
                       exhaust: Boolean = false): Double {
        val base    = modeConf?.trailingStopPct ?: cfg().trailingStopBasePct
        val gainPct = pct(pos.entryPrice, current)
        
        // Trail adjustment after partial sells
        // After taking profits, we can be slightly looser (not tighter!) since we've secured gains
        val partialFactor = when {
            pos.partialSoldPct >= 50.0 -> 0.90   // Sold 50%+ → slightly tighter (was 0.40!)
            pos.partialSoldPct >= 25.0 -> 0.95   // Sold 25%+ → barely tighter (was 0.55!)
            else                       -> 1.0
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // HOUSE MONEY MULTIPLIER — After capital recovered, we can be MUCH looser
        // This is the key insight: if we've already secured our initial investment,
        // the remaining position is "free" — we can let it ride with very loose stops
        // ═══════════════════════════════════════════════════════════════════
        val houseMoneyMultiplier = when {
            pos.profitLocked -> 1.8    // Both capital AND profits locked → very loose
            pos.isHouseMoney -> 1.5    // Capital recovered → looser (playing with house money)
            pos.capitalRecovered -> 1.4  // Capital partially recovered
            else -> 1.0                 // Normal — our money is at risk
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V5: SMART TRAIL MULTIPLIER based on trend health
        // ═══════════════════════════════════════════════════════════════════
        // 
        // If the trend is healthy (EMA fan, volume, pressure), we want LOOSER
        // trails to capture more of the move. If trend is weakening, tighten.
        //
        // Multiplier > 1.0 = looser trail (let it run)
        // Multiplier < 1.0 = tighter trail (protect gains)
        
        var healthMultiplier = 1.0
        
        // EMA Fan Health - MOST IMPORTANT for runners
        // Widening bull fan = strong trend, give it room
        when {
            emaFanAlignment == "BULL_FAN" && emaFanWidening -> {
                healthMultiplier += 0.35  // Very loose - let it RUN
            }
            emaFanAlignment == "BULL_FAN" -> {
                healthMultiplier += 0.20  // Loose
            }
            emaFanAlignment == "BULL_FLAT" -> {
                healthMultiplier += 0.10  // Slightly loose
            }
            emaFanAlignment == "BEAR_FLAT" -> {
                healthMultiplier -= 0.15  // Tighten - trend weakening
            }
            emaFanAlignment == "BEAR_FAN" -> {
                healthMultiplier -= 0.30  // Very tight - trend broken
            }
        }
        
        // Volume Score - High volume supports the move
        when {
            volScore >= 70 -> healthMultiplier += 0.15
            volScore >= 55 -> healthMultiplier += 0.08
            volScore < 35  -> healthMultiplier -= 0.12  // Volume dying
            volScore < 25  -> healthMultiplier -= 0.20  // No volume = exit soon
        }
        
        // Buy Pressure - Strong buyers = trend continuing
        when {
            pressScore >= 65 -> healthMultiplier += 0.12
            pressScore >= 55 -> healthMultiplier += 0.05
            pressScore < 40  -> healthMultiplier -= 0.15  // Sellers taking over
            pressScore < 30  -> healthMultiplier -= 0.25  // Heavy selling
        }
        
        // Exhaustion = immediate tightening
        if (exhaust) {
            healthMultiplier -= 0.30
        }
        
        // Clamp multiplier to reasonable range
        healthMultiplier = healthMultiplier.coerceIn(0.5, 1.6)
        
        // ═══════════════════════════════════════════════════════════════════
        // LEARNING LAYER INFLUENCE ON TRAILING STOP
        // 
        // ExitIntelligence learns optimal trailing stop distance.
        // If we have enough exits (20+), blend learned value with base.
        // ═══════════════════════════════════════════════════════════════════
        val learnedTrailInfluence = if (ExitIntelligence.getTotalExits() >= 20) {
            val learnedStop = ExitIntelligence.getLearnedTrailingStopDistance()
            // If learned stop is larger than base, use it (AI learned to hold longer)
            // Scale: learned 5% default, if AI learned 8% → multiplier = 1.6
            (learnedStop / 5.0).coerceIn(0.8, 2.0)
        } else 1.0  // Not enough data, use default
        
        // ═══════════════════════════════════════════════════════════════════
        // Base trail calculation with health adjustment
        // 
        // MOONSHOT SCALING: Real runners do 10,000,000%+ (SHIB, PEPE, etc.)
        // We need VERY loose trails to capture these generational moves.
        // 
        // The key insight: Once you're up 100x+, a 30% pullback is NOISE.
        // You don't want to exit a 10,000x winner because of a 20% dip.
        // 
        // TRAIL MULTIPLIERS (higher = looser trail = more room to run):
        // ═══════════════════════════════════════════════════════════════════
        
        val baseTrail = when {
            // ════════════════════════════════════════════════════════════════
            // GENERATIONAL WEALTH TERRITORY (1000x+)
            // A 50% pullback on a 10,000x is still 5,000x profit!
            // ════════════════════════════════════════════════════════════════
            gainPct >= 1000000  -> base * 8.0   // 10,000x+ → 8x base (120% trail if base=15%)
            gainPct >= 100000   -> base * 6.0   // 1,000x+ → 6x base (90% trail)
            gainPct >= 10000    -> base * 5.0   // 100x+ → 5x base (75% trail)
            
            // ════════════════════════════════════════════════════════════════
            // MOONSHOT TERRITORY (10x-100x)
            // These are life-changing. Don't exit on minor pullbacks.
            // ════════════════════════════════════════════════════════════════
            gainPct >= 5000     -> base * 4.0   // 50x+ → 4x base (60% trail)
            gainPct >= 2000     -> base * 3.5   // 20x+ → 3.5x base (52.5% trail)
            gainPct >= 1000     -> base * 3.0   // 10x+ → 3x base (45% trail)
            
            // ════════════════════════════════════════════════════════════════
            // STRONG RUNNER (2x-10x)
            // Great trades but not yet moonshot. Still give room.
            // ════════════════════════════════════════════════════════════════
            gainPct >= 500      -> base * 2.5   // 5x+ → 2.5x base (37.5% trail)
            gainPct >= 300      -> base * 2.0   // 3x+ → 2x base (30% trail)
            gainPct >= 200      -> base * 1.7   // 2x+ → 1.7x base (25.5% trail)
            gainPct >= 100      -> base * 1.5   // 100%+ → 1.5x base (22.5% trail)
            
            // ════════════════════════════════════════════════════════════════
            // NORMAL PROFITS (0-2x)
            // Standard trailing - protect these gains normally.
            // ════════════════════════════════════════════════════════════════
            gainPct >= 50       -> base * 1.2   // 50%+ → 1.2x base (18% trail)
            gainPct >= 30       -> base * 1.0   // 30%+ → standard base
            else                -> base * 0.85  // <30% → slightly tighter
        }
        
        // Apply health multiplier - healthy trend = looser trail
        // Also apply learning influence from ExitIntelligence
        // House money multiplier allows looser stops when capital is secured
        var smartTrail = baseTrail * healthMultiplier * partialFactor * learnedTrailInfluence * houseMoneyMultiplier
        
        // ═══════════════════════════════════════════════════════════════════
        // MARKET REGIME AI INFLUENCE ON TRAILING STOP
        // 
        // Bull market = can hold looser (more room for gains)
        // Bear market = tighter stops (protect gains)
        // ═══════════════════════════════════════════════════════════════════
        val regimeTrailMult = try {
            val regime = MarketRegimeAI.getCurrentRegime()
            val confidence = MarketRegimeAI.getRegimeConfidence()
            
            if (confidence >= 40.0) {
                when (regime) {
                    MarketRegimeAI.Regime.STRONG_BULL -> 1.2    // Very bullish: 20% looser trails
                    MarketRegimeAI.Regime.BULL -> 1.1           // Bullish: 10% looser
                    MarketRegimeAI.Regime.NEUTRAL -> 1.0        // Neutral
                    MarketRegimeAI.Regime.CRAB -> 0.95          // Choppy: 5% tighter
                    MarketRegimeAI.Regime.BEAR -> 0.85          // Bearish: 15% tighter
                    MarketRegimeAI.Regime.STRONG_BEAR -> 0.75   // Very bearish: 25% tighter
                    MarketRegimeAI.Regime.HIGH_VOLATILITY -> 0.9 // Volatile: 10% tighter
                }
            } else 1.0
        } catch (_: Exception) { 1.0 }
        
        smartTrail *= regimeTrailMult
        
        // Log significant adjustments for runners (>100% gain)
        if (gainPct >= 100.0 && (healthMultiplier != 1.0 || learnedTrailInfluence != 1.0 || regimeTrailMult != 1.0)) {
            val direction = if (healthMultiplier > 1.0) "LOOSE" else "TIGHT"
            val regimeLabel = try { MarketRegimeAI.getCurrentRegime().label } catch (_: Exception) { "?" }
            ErrorLogger.debug("SmartTrail", "🎯 Runner ${gainPct.toInt()}%: " +
                "health=${healthMultiplier.fmt(2)} ($direction) | " +
                "fan=$emaFanAlignment wide=$emaFanWidening | " +
                "vol=${volScore.toInt()} press=${pressScore.toInt()} | " +
                "learnedMult=${learnedTrailInfluence.fmt(2)} | " +
                "regime=$regimeLabel(${regimeTrailMult.fmt(2)}) | " +
                "trail=${smartTrail.fmt(2)}%")
        }
        
        return pos.highestPrice * (1.0 - smartTrail / 100.0)
    }
    
    /**
     * Backward-compatible version for calls without trend signals.
     * Uses basic gain-based trailing.
     */
    fun trailingFloorBasic(pos: Position, current: Double,
                            modeConf: AutoModeEngine.ModeConfig? = null): Double {
        return trailingFloor(pos, current, modeConf)
    }

    // ── profit lock system ─────────────────────────────────────────────
    
    /**
     * DYNAMIC PROFIT LOCK SYSTEM — Secure capital first, then let house money ride
     * ═══════════════════════════════════════════════════════════════════════════
     * 
     * The problem with wide trailing stops on moonshots:
     * - 75% drawdown at 100x means watching $10K drop to $2.5K
     * - Psychologically devastating, mathematically stupid
     * 
     * SOLUTION: Lock profits in stages, then let remainder ride freely
     * 
     * DYNAMIC THRESHOLDS based on:
     * - Liquidity: Low liq = lock earlier (more rug risk)
     * - Market cap: Low mcap = lock earlier (more volatile)
     * - Volatility: High vol = lock earlier (faster moves)
     * - Entry phase: Early entries = lock earlier (riskier)
     * 
     * BASE LEVELS:
     *   Stage 1: CAPITAL RECOVERY at 2x (+100%)
     *   Stage 2: PROFIT LOCK at 5x (+400%)
     * 
     * ADJUSTED LEVELS (example for low-liq early entry):
     *   Stage 1: CAPITAL RECOVERY at 1.5x (+50%)
     *   Stage 2: PROFIT LOCK at 3x (+200%)
     */
    
    /**
     * Calculate dynamic profit lock thresholds based on token characteristics
     * AND treasury/scaling tier integration
     */
    private fun calculateProfitLockThresholds(ts: TokenState): Pair<Double, Double> {
        val pos = ts.position
        
        // Base thresholds
        var capitalRecoveryMultiple = 2.0  // 2x = +100%
        var profitLockMultiple = 5.0       // 5x = +400%
        
        // ════════════════════════════════════════════════════════════════
        // SCALING TIER ADJUSTMENT — Higher tiers = can let positions run longer
        // Treasury-backed positions have more room to breathe
        // ════════════════════════════════════════════════════════════════
        val treasuryTierAdjustment = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            when (tier) {
                // Higher treasury = more cushion = can wait longer for moonshots
                ScalingMode.Tier.INSTITUTIONAL -> 1.40  // Lock at 2.8x, 7x (patient capital)
                ScalingMode.Tier.SCALED        -> 1.25  // Lock at 2.5x, 6.25x
                ScalingMode.Tier.GROWTH        -> 1.15  // Lock at 2.3x, 5.75x
                ScalingMode.Tier.STANDARD      -> 1.05  // Lock at 2.1x, 5.25x
                ScalingMode.Tier.MICRO         -> 1.00  // Base thresholds (capital preservation critical)
            }
        } catch (_: Exception) { 1.0 }
        
        // ════════════════════════════════════════════════════════════════
        // FACTOR 1: LIQUIDITY — Lower liquidity = lock earlier
        // ════════════════════════════════════════════════════════════════
        val liqUsd = ts.lastLiquidityUsd
        val liqAdjustment = when {
            liqUsd < 5_000   -> 0.70   // Very low liq: lock at 70% of base (1.4x, 3.5x)
            liqUsd < 10_000  -> 0.80   // Low liq: lock at 80% of base (1.6x, 4x)
            liqUsd < 25_000  -> 0.90   // Medium liq: lock at 90% of base
            liqUsd < 50_000  -> 1.00   // Standard liq: base thresholds
            liqUsd < 100_000 -> 1.10   // Good liq: can wait a bit longer
            else             -> 1.20   // High liq: more room to ride (2.4x, 6x)
        }
        
        // ════════════════════════════════════════════════════════════════
        // FACTOR 2: MARKET CAP — Lower mcap = lock earlier (more volatile)
        // ════════════════════════════════════════════════════════════════
        val mcap = ts.lastMcap
        val mcapAdjustment = when {
            mcap < 50_000    -> 0.75   // Micro cap: very aggressive locking
            mcap < 100_000   -> 0.85   // Small cap: aggressive locking
            mcap < 250_000   -> 0.95   // Medium cap: slightly earlier
            mcap < 500_000   -> 1.00   // Standard: base thresholds
            mcap < 1_000_000 -> 1.10   // Larger: more room
            else             -> 1.20   // Big cap: let it ride longer
        }
        
        // ════════════════════════════════════════════════════════════════
        // FACTOR 3: VOLATILITY — Higher volatility = lock earlier
        // ════════════════════════════════════════════════════════════════
        val volatility = ts.meta.rangePct  // Recent price range %
        val volAdjustment = when {
            volatility > 50  -> 0.70   // Extreme volatility: lock fast
            volatility > 30  -> 0.80   // High volatility
            volatility > 20  -> 0.90   // Medium volatility
            volatility > 10  -> 1.00   // Normal volatility
            else             -> 1.10   // Low volatility: can wait
        }
        
        // ════════════════════════════════════════════════════════════════
        // FACTOR 4: ENTRY PHASE — Earlier entries = lock earlier (riskier)
        // ════════════════════════════════════════════════════════════════
        val entryPhase = pos.entryPhase.lowercase()
        val phaseAdjustment = when {
            entryPhase.contains("early") || entryPhase.contains("accumulation") -> 0.80  // Early = risky
            entryPhase.contains("pre_pump") -> 0.85   // Pre-pump = somewhat risky
            entryPhase.contains("markup") || entryPhase.contains("breakout") -> 1.00  // Breakout = confirmed
            entryPhase.contains("momentum") -> 1.05   // Momentum = riding trend
            entryPhase.contains("distribution") -> 0.70  // Distribution = get out fast!
            else -> 0.90  // Unknown = conservative
        }
        
        // ════════════════════════════════════════════════════════════════
        // FACTOR 5: ENTRY QUALITY — Lower quality = lock earlier
        // ════════════════════════════════════════════════════════════════
        val qualityAdjustment = when {
            pos.entryScore >= 80 -> 1.15   // A+ quality: trust the setup
            pos.entryScore >= 70 -> 1.05   // A quality: good setup
            pos.entryScore >= 60 -> 1.00   // B quality: standard
            pos.entryScore >= 50 -> 0.90   // C quality: be careful
            else -> 0.80                    // D quality: lock fast
        }
        
        // ════════════════════════════════════════════════════════════════
        // FACTOR 6: TOKEN TIER — Match scaling mode for the token
        // Higher tier tokens have more established liquidity = safer to hold
        // ════════════════════════════════════════════════════════════════
        val tokenTier = ScalingMode.tierForToken(ts.lastLiquidityUsd, ts.lastMcap)
        val tokenTierAdjustment = when (tokenTier) {
            ScalingMode.Tier.INSTITUTIONAL -> 1.30  // Blue chips: let them run
            ScalingMode.Tier.SCALED        -> 1.20  // Mid caps: good room
            ScalingMode.Tier.GROWTH        -> 1.10  // Growth: some room
            ScalingMode.Tier.STANDARD      -> 1.00  // Standard: base
            ScalingMode.Tier.MICRO         -> 0.85  // Micro: lock earlier (rug risk)
        }
        
        // ════════════════════════════════════════════════════════════════
        // COMBINE ALL FACTORS
        // Use geometric mean for balanced adjustment
        // Include treasury tier to reward building treasury
        // ════════════════════════════════════════════════════════════════
        val product = liqAdjustment * mcapAdjustment * volAdjustment * phaseAdjustment * 
            qualityAdjustment * tokenTierAdjustment * treasuryTierAdjustment
        val combinedAdjustment = kotlin.math.pow(product, 1.0 / 7.0)  // 7th root for geometric mean of 7 factors
            .coerceIn(0.5, 1.8)  // Cap between 50% and 180% of base
        
        capitalRecoveryMultiple *= combinedAdjustment
        profitLockMultiple *= combinedAdjustment
        
        // Ensure minimum thresholds (don't lock below 1.3x for capital, 2.5x for profit)
        capitalRecoveryMultiple = capitalRecoveryMultiple.coerceIn(1.3, 4.0)
        profitLockMultiple = profitLockMultiple.coerceIn(2.5, 10.0)
        
        return Pair(capitalRecoveryMultiple, profitLockMultiple)
    }
    
    fun checkProfitLock(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {
        val c = cfg()
        val pos = ts.position
        if (!pos.isOpen) return false
        
        val currentValue = pos.qtyToken * ts.ref
        val gainMultiple = currentValue / pos.costSol  // 2.0 = 2x, 5.0 = 5x
        val gainPct = (gainMultiple - 1.0) * 100.0
        
        // Get dynamic thresholds based on token characteristics
        val (capitalRecoveryThreshold, profitLockThreshold) = calculateProfitLockThresholds(ts)
        
        // ════════════════════════════════════════════════════════════════
        // STAGE 1: CAPITAL RECOVERY (dynamic threshold)
        // Sell enough to get back initial investment
        // ════════════════════════════════════════════════════════════════
        if (!pos.capitalRecovered && gainMultiple >= capitalRecoveryThreshold) {
            // Calculate how much to sell to recover initial capital
            // At 2x: sell 50%. At 1.5x: sell 66%. At 3x: sell 33%
            val sellFraction = (1.0 / gainMultiple).coerceIn(0.25, 0.70)
            val sellQty = pos.qtyToken * sellFraction
            val sellSol = sellQty * ts.ref
            
            onLog("🔒 CAPITAL RECOVERY: ${ts.symbol} @ ${gainMultiple.fmt(2)}x (threshold: ${capitalRecoveryThreshold.fmt(2)}x) — selling ${(sellFraction*100).toInt()}% to recover initial", ts.mint)
            onNotify("🔒 Capital Recovered!",
                "${ts.symbol} @ ${gainMultiple.fmt(1)}x — initial investment secured",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            sounds?.playMilestone(gainPct)
            
            if (c.paperMode || wallet == null) {
                // Paper mode
                val newQty = pos.qtyToken - sellQty
                val newCost = pos.costSol * (1.0 - sellFraction)
                val pnlSol = sellSol - pos.costSol * sellFraction
                
                ts.position = pos.copy(
                    qtyToken = newQty,
                    costSol = newCost,
                    capitalRecovered = true,
                    capitalRecoveredSol = sellSol,
                    isHouseMoney = true,
                    lockedProfitFloor = sellSol,  // We've secured this much
                )
                
                val trade = Trade("SELL", "paper", sellSol, ts.ref,
                    System.currentTimeMillis(), "capital_recovery_${gainMultiple.fmt(1)}x",
                    pnlSol, gainPct)
                ts.trades.add(trade)
                security.recordTrade(trade)
                onPaperBalanceChange?.invoke(sellSol)
                
                // Record treasury event for capital recovery
                val solPrice = WalletManager.lastKnownSolPrice
                TreasuryManager.recordProfitLockEvent(
                    TreasuryEventType.CAPITAL_RECOVERED,
                    sellSol,
                    ts.symbol,
                    gainMultiple,
                    solPrice
                )
                
                // Lock realized profit to treasury
                if (pnlSol > 0) {
                    TreasuryManager.lockRealizedProfit(pnlSol, solPrice)
                }
                
                onLog("📄 PAPER CAPITAL LOCK: Sold ${sellSol.fmt(4)} SOL @ +${gainPct.toInt()}% — now playing with house money!", ts.mint)
            } else {
                // Live mode
                executeProfitLockSell(ts, wallet, sellFraction, "capital_recovery_${gainMultiple.fmt(1)}x", walletSol)
            }
            return true
        }
        
        // ════════════════════════════════════════════════════════════════
        // STAGE 2: PROFIT LOCK (dynamic threshold)
        // After capital recovered, lock 50% of remaining at profit threshold
        // ════════════════════════════════════════════════════════════════
        if (pos.capitalRecovered && !pos.profitLocked && gainMultiple >= profitLockThreshold) {
            // Sell 50% of remaining position to lock profits
            val sellFraction = 0.50
            val sellQty = pos.qtyToken * sellFraction
            val sellSol = sellQty * ts.ref
            
            onLog("🔐 PROFIT LOCK: ${ts.symbol} @ ${gainMultiple.fmt(2)}x (threshold: ${profitLockThreshold.fmt(2)}x) — locking 50% of remaining profits", ts.mint)
            onNotify("🔐 Profits Locked!",
                "${ts.symbol} @ ${gainMultiple.fmt(1)}x — 50% profits secured",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            sounds?.playMilestone(gainPct)
            
            if (c.paperMode || wallet == null) {
                // Paper mode
                val newQty = pos.qtyToken - sellQty
                val newCost = pos.costSol * (1.0 - sellFraction)
                val pnlSol = sellSol - pos.costSol * sellFraction
                
                ts.position = pos.copy(
                    qtyToken = newQty,
                    costSol = newCost,
                    profitLocked = true,
                    profitLockedSol = sellSol,
                    lockedProfitFloor = pos.lockedProfitFloor + sellSol,
                )
                
                val trade = Trade("SELL", "paper", sellSol, ts.ref,
                    System.currentTimeMillis(), "profit_lock_${gainMultiple.fmt(1)}x",
                    pnlSol, gainPct)
                ts.trades.add(trade)
                security.recordTrade(trade)
                onPaperBalanceChange?.invoke(sellSol)
                
                // Record treasury event for profit lock
                val solPrice = WalletManager.lastKnownSolPrice
                TreasuryManager.recordProfitLockEvent(
                    TreasuryEventType.PROFIT_LOCK_SELL,
                    sellSol,
                    ts.symbol,
                    gainMultiple,
                    solPrice
                )
                
                // Lock realized profit to treasury
                if (pnlSol > 0) {
                    TreasuryManager.lockRealizedProfit(pnlSol, solPrice)
                }
                
                onLog("📄 PAPER PROFIT LOCK: Sold ${sellSol.fmt(4)} SOL @ ${gainMultiple.fmt(1)}x — letting rest ride free!", ts.mint)
            } else {
                // Live mode
                executeProfitLockSell(ts, wallet, sellFraction, "profit_lock_${gainMultiple.fmt(1)}x", walletSol)
            }
            return true
        }
        
        return false
    }
    
    /**
     * Execute a profit lock sell (live mode)
     */
    private fun executeProfitLockSell(
        ts: TokenState,
        wallet: SolanaWallet,
        sellFraction: Double,
        reason: String,
        walletSol: Double,
    ) {
        val c = cfg()
        val pos = ts.position
        
        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair check failed — aborting profit lock sell", ts.mint)
            return
        }
        
        val sellQty = pos.qtyToken * sellFraction
        val sellUnits = (sellQty * 1_000_000_000.0).toLong().coerceAtLeast(1L)
        
        try {
            val quote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT, sellUnits, c.slippageBps, isBuy = false)
            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            security.enforceSignDelay()
            
            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            
            val sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey)
            val solBack = quote.outAmount / 1_000_000_000.0
            val pnlSol = solBack - pos.costSol * sellFraction
            val pnlPct = pct(pos.costSol * sellFraction, solBack)
            val (netPnl, feeSol) = slippageGuard.calcNetPnl(pnlSol, pos.costSol * sellFraction)
            
            // Update position
            val newQty = pos.qtyToken - sellQty
            val newCost = pos.costSol * (1.0 - sellFraction)
            
            val isCapitalRecovery = reason.contains("capital_recovery")
            ts.position = pos.copy(
                qtyToken = newQty,
                costSol = newCost,
                capitalRecovered = if (isCapitalRecovery) true else pos.capitalRecovered,
                capitalRecoveredSol = if (isCapitalRecovery) solBack else pos.capitalRecoveredSol,
                profitLocked = if (!isCapitalRecovery) true else pos.profitLocked,
                profitLockedSol = if (!isCapitalRecovery) solBack else pos.profitLockedSol,
                isHouseMoney = true,
                lockedProfitFloor = pos.lockedProfitFloor + solBack,
            )
            
            val trade = Trade("SELL", "live", solBack, ts.ref,
                System.currentTimeMillis(), reason,
                pnlSol, pnlPct, sig = sig, feeSol = feeSol, netPnlSol = netPnl)
            ts.trades.add(trade)
            security.recordTrade(trade)
            SmartSizer.recordTrade(pnlSol > 0, isPaperMode = false)
            
            // Record treasury event and lock realized profit
            val solPrice = WalletManager.lastKnownSolPrice
            val gainMultiple = (solBack + pos.lockedProfitFloor) / pos.costSol
            
            // Record the profit lock event to treasury
            val eventType = if (isCapitalRecovery) TreasuryEventType.CAPITAL_RECOVERED 
                           else TreasuryEventType.PROFIT_LOCK_SELL
            TreasuryManager.recordProfitLockEvent(eventType, solBack, ts.symbol, gainMultiple, solPrice)
            
            // Lock realized profit to treasury
            if (pnlSol > 0) {
                TreasuryManager.lockRealizedProfit(pnlSol, solPrice)
            }
            
            onLog("✅ LIVE $reason: ${solBack.fmt(4)} SOL | pnl ${pnlSol.fmt(4)} SOL | sig=${sig.take(16)}…", ts.mint)
            onNotify("✅ Profit Locked",
                "${ts.symbol} secured ${solBack.fmt(3)} SOL",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                
        } catch (e: Exception) {
            onLog("❌ Profit lock sell FAILED: ${security.sanitiseForLog(e.message ?: "unknown")} — will retry next tick", ts.mint)
        }
    }

    // ── partial sell ─────────────────────────────────────────────────

    /**
     * v5: MOONSHOT-AWARE Partial sell at milestone gains.
     * 
     * STRATEGY: Take small amounts at each milestone so MOST rides the moonshot.
     * 
     * Default milestones:
     *   +200%   → sell 25% (first partial)
     *   +500%   → sell 25% (second partial)
     *   +2000%  → sell 25% (third partial) - 20x!
     *   +10000% → sell 25% (fourth partial) - 100x! (NEW)
     *   +50000% → sell 25% (fifth partial) - 500x! (NEW)
     * 
     * After all 5 partials: Still holding 25% of original position for infinity!
     * That 25% could become 10,000x+ (SHIB, PEPE territory)
     */
    fun checkPartialSell(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {
        val c   = cfg()
        val pos = ts.position
        if (!c.partialSellEnabled || !pos.isOpen) return false

        val gainPct = pct(pos.entryPrice, ts.ref)
        val soldPct = pos.partialSoldPct

        // Calculate which partial level we're at (0-5)
        val partialLevel = (soldPct / (c.partialSellFraction * 100.0)).toInt()
        
        // Milestones: 200% → 500% → 2000% → 10000% → 50000%
        val milestones = listOf(
            c.partialSellTriggerPct,          // 200% (configurable)
            c.partialSellSecondTriggerPct,    // 500% (configurable)
            c.partialSellThirdTriggerPct,     // 2000% (configurable)
            10000.0,                           // 100x (hardcoded moonshot)
            50000.0,                           // 500x (hardcoded mega moonshot)
        )
        
        // Check if we've hit the next milestone and haven't taken that partial yet
        val nextMilestone = milestones.getOrNull(partialLevel)
        val shouldPartial = nextMilestone != null && gainPct >= nextMilestone
        
        // For 4th and 5th partials, always enabled (moonshot territory)
        val isThirdOrLater = partialLevel >= 2
        if (!shouldPartial) return false
        if (partialLevel == 2 && !c.partialSellThirdEnabled) return false

        // ═══════════════════════════════════════════════════════════════════
        // TREASURY-AWARE PARTIAL SELL FRACTION
        // 
        // Higher treasury = take LESS at each partial (let more ride)
        // Lower treasury = take MORE at each partial (secure profits)
        // 
        // This compounds with moonshot scaling for optimal wealth building
        // ═══════════════════════════════════════════════════════════════════
        val baseFraction = c.partialSellFraction
        val treasuryAdjustedFraction = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            when (tier) {
                // High treasury = profits secured = let more ride
                ScalingMode.Tier.INSTITUTIONAL -> baseFraction * 0.6  // Take 60% of normal (15% instead of 25%)
                ScalingMode.Tier.SCALED        -> baseFraction * 0.7  // Take 70% of normal (17.5% instead of 25%)
                ScalingMode.Tier.GROWTH        -> baseFraction * 0.8  // Take 80% of normal (20% instead of 25%)
                ScalingMode.Tier.STANDARD      -> baseFraction * 0.9  // Take 90% of normal
                ScalingMode.Tier.MICRO         -> baseFraction        // Full amount - need to secure gains
            }
        } catch (_: Exception) { baseFraction }
        
        // Compute position update values BEFORE branching on paper/live
        val sellFraction = treasuryAdjustedFraction
        val sellQty      = pos.qtyToken * sellFraction
        val sellSol      = sellQty * ts.ref
        val newSoldPct   = soldPct + sellFraction * 100.0
        val newQty       = pos.qtyToken - sellQty
        val newCost      = pos.costSol * (1.0 - sellFraction)
        val paperPnlSol  = sellQty * ts.ref - pos.costSol * sellFraction
        val triggerPct   = nextMilestone ?: 0.0
        
        // Log with moonshot-aware messaging
        val milestoneLabel = when (partialLevel) {
            0 -> "1st partial"
            1 -> "2nd partial"
            2 -> "3rd partial (20x!)"
            3 -> "4th partial (100x MOONSHOT!)"
            4 -> "5th partial (500x MEGA MOON!)"
            else -> "${partialLevel + 1}th partial"
        }

        onLog("💰 $milestoneLabel: SELL ${(sellFraction*100).toInt()}% @ +${gainPct.toInt()}% " +
              "(trigger: +${triggerPct.toInt()}%) | ~${sellSol.fmt(4)} SOL", ts.mint)
        onNotify("💰 $milestoneLabel",
                 "${ts.symbol}  +${gainPct.toInt()}%  selling ${(sellFraction*100).toInt()}%",
                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        sounds?.playMilestone(gainPct)

        if (c.paperMode || wallet == null) {
            // ── Paper partial sell ─────────────────────────────────────
            ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
            val trade   = Trade("SELL", "paper", sellSol, ts.ref,
                              System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                              paperPnlSol, pct(pos.costSol * sellFraction, sellQty * ts.ref))
            ts.trades.add(trade); security.recordTrade(trade)
            onLog("PAPER PARTIAL SELL ${(sellFraction*100).toInt()}% | " +
                  "${sellSol.fmt(4)} SOL | pnl ${paperPnlSol.fmt(4)} SOL", ts.mint)
        } else {
            // ── Live partial sell (Jupiter swap) ───────────────────────
            // Idempotency: skip if we already have a tx in-flight for this mint
            if (ts.mint in partialSellInFlight) {
                onLog("⏳ Partial sell already in-flight for ${ts.symbol} — skipping duplicate", ts.mint)
                return true
            }
            try {
                partialSellInFlight.add(ts.mint)
                if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                        c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
                    onLog("🛑 Keypair check failed — aborting partial sell", ts.mint)
                    partialSellInFlight.remove(ts.mint)
                    return true
                }
                val sellUnits = (sellQty * 1_000_000_000.0).toLong().coerceAtLeast(1L)
                val quote     = getQuoteWithSlippageGuard(
                    ts.mint, JupiterApi.SOL_MINT, sellUnits, c.slippageBps, isBuy = false)
                val txResult  = buildTxWithRetry(quote, wallet.publicKeyB58)
                security.enforceSignDelay()
                
                // ⚡ MEV PROTECTION for partial sells (Ultra or Jito)
                val useJito = c.jitoEnabled && !quote.isUltra
                val jitoTip = c.jitoTipLamports
                val ultraReqId = if (quote.isUltra) txResult.requestId else null
                val sig       = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey)
                val solBack   = quote.outAmount / 1_000_000_000.0
                val livePnl   = solBack - pos.costSol * sellFraction
                val liveScore = pct(pos.costSol * sellFraction, solBack)
                val (netPnl, feeSol) = slippageGuard.calcNetPnl(livePnl, pos.costSol * sellFraction)
                // Update position state after confirmed on-chain execution
                ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
                val liveTrade = Trade("SELL", "live", solBack, ts.ref,
                    System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                    livePnl, liveScore, sig = sig, feeSol = feeSol, netPnlSol = netPnl)
                ts.trades.add(liveTrade); security.recordTrade(liveTrade)
                SmartSizer.recordTrade(livePnl > 0, isPaperMode = false)  // Live trade
                partialSellInFlight.remove(ts.mint)
                onLog("LIVE PARTIAL SELL ${(sellFraction*100).toInt()}% @ +${gainPct.toInt()}% | " +
                      "${solBack.fmt(4)}◎ | sig=${sig.take(16)}…", ts.mint)
                onNotify("💰 Live Partial Sell",
                    "${ts.symbol}  +${gainPct.toInt()}%  sold ${(sellFraction*100).toInt()}%",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            } catch (e: Exception) {
                partialSellInFlight.remove(ts.mint)
                onLog("Live partial sell FAILED: ${security.sanitiseForLog(e.message?:"err")} " +
                      "— position NOT updated", ts.mint)
            }
        }
        return true
    }

    // ── risk check ────────────────────────────────────────────────────

    // Track which milestones have already been announced per position
    private val milestonesHit      = mutableMapOf<String, MutableSet<Int>>()
    // Idempotency guard: mints currently executing a partial sell tx
    // Prevents the same partial from firing twice if confirmation is slow
    private val partialSellInFlight = mutableSetOf<String>()

    fun riskCheck(ts: TokenState, modeConf: AutoModeEngine.ModeConfig? = null): String? {
        val pos   = ts.position
        val price = ts.ref
        if (!pos.isOpen || price == 0.0) return null

        pos.highestPrice = maxOf(pos.highestPrice, price)
        // Track lowest price for Exit AI
        if (pos.lowestPrice == 0.0 || price < pos.lowestPrice) {
            pos.lowestPrice = price
        }
        val gainPct  = pct(pos.entryPrice, price)
        val heldSecs = (System.currentTimeMillis() - pos.entryTime) / 1000.0

        // Milestone sounds while holding (50%, 100%, 200%)
        val hitMilestones = milestonesHit.getOrPut(ts.mint) { mutableSetOf() }
        listOf(50, 100, 200).forEach { threshold ->
            if (gainPct >= threshold && !hitMilestones.contains(threshold)) {
                hitMilestones.add(threshold)
                sounds?.playMilestone(gainPct)
                onLog("+${threshold}% milestone on ${ts.symbol}! 🎯", ts.mint)
            }
        }
        // Clear milestones when position closes
        if (!pos.isOpen) milestonesHit.remove(ts.mint)

        // ════════════════════════════════════════════════════════════════
        // AI CROSS-TALK: Check for coordinated dump signal
        // Multiple AIs detecting dump = EMERGENCY EXIT
        // ════════════════════════════════════════════════════════════════
        try {
            if (AICrossTalk.isCoordinatedDump(ts.mint, ts.symbol)) {
                val crossTalkSignal = AICrossTalk.analyzeCrossTalk(ts.mint, ts.symbol, isOpenPosition = true)
                onLog("🔗🚨 CROSSTALK: ${ts.symbol} COORDINATED DUMP | ${crossTalkSignal.participatingAIs.joinToString("+")} | ${crossTalkSignal.reason}", ts.mint)
                TradeStateMachine.startCooldown(ts.mint)
                return "crosstalk_coordinated_dump"
            }
        } catch (_: Exception) {}
        
        // ════════════════════════════════════════════════════════════════
        // EXIT INTELLIGENCE AI - Dynamic exit evaluation
        // ════════════════════════════════════════════════════════════════
        val exitAiState = ExitIntelligence.PositionState(
            mint = ts.mint,
            symbol = ts.symbol,
            entryPrice = pos.entryPrice,
            currentPrice = price,
            highestPrice = pos.highestPrice,
            lowestPrice = pos.lowestPrice,
            pnlPercent = gainPct,
            holdTimeMinutes = (heldSecs / 60.0).toInt(),
            buyPressure = ts.meta.pressScore,
            entryBuyPressure = ts.meta.pressScore,  // Will be tracked by ExitIntelligence
            volume = ts.meta.volScore,
            volatility = ts.meta.avgAtr,
            isDistribution = ts.phase == "distribution" || ts.meta.pressScore < 35,
            rsi = ts.meta.rsi,
            momentum = ts.entryScore,
            qualityGrade = ts.meta.setupQuality,
        )
        val exitAiDecision = ExitIntelligence.evaluateExit(exitAiState)
        
        // Act on Exit AI decision with high urgency
        when (exitAiDecision.action) {
            ExitIntelligence.ExitAction.EMERGENCY_EXIT -> {
                onLog("🤖🚨 EXIT AI: ${ts.symbol} EMERGENCY | ${exitAiDecision.reasons.firstOrNull()}", ts.mint)
                TradeStateMachine.startCooldown(ts.mint)
                return "ai_emergency_${exitAiDecision.reasons.firstOrNull()?.take(15)?.replace(" ", "_") ?: "exit"}"
            }
            ExitIntelligence.ExitAction.FULL_EXIT -> {
                if (exitAiDecision.urgency == ExitIntelligence.Urgency.HIGH || 
                    exitAiDecision.urgency == ExitIntelligence.Urgency.CRITICAL) {
                    onLog("🤖⚠️ EXIT AI: ${ts.symbol} FULL EXIT | ${exitAiDecision.reasons.firstOrNull()}", ts.mint)
                    TradeStateMachine.startCooldown(ts.mint)
                    return "ai_exit_${exitAiDecision.reasons.firstOrNull()?.take(15)?.replace(" ", "_") ?: "signal"}"
                }
            }
            else -> {
                // HOLD, TIGHTEN_STOP, PARTIAL_EXIT - handle below or let other logic run
            }
        }

        // ════════════════════════════════════════════════════════════════
        // V8: Precision Exit Logic - Full evaluation
        // ════════════════════════════════════════════════════════════════
        val exitSignal = PrecisionExitLogic.evaluate(
            ts = ts,
            currentPrice = price,
            entryPrice = pos.entryPrice,
            history = ts.history.toList(),
            exitScore = ts.exitScore,
            stopLossPct = modeConf?.stopLossPct ?: cfg().stopLossPct,
        )
        
        if (exitSignal.shouldExit) {
            val urgencyEmoji = when (exitSignal.urgency) {
                PrecisionExitLogic.Urgency.CRITICAL -> "🚨"
                PrecisionExitLogic.Urgency.HIGH -> "⚠️"
                PrecisionExitLogic.Urgency.MEDIUM -> "📊"
                else -> "ℹ️"
            }
            onLog("$urgencyEmoji V8 EXIT: ${ts.symbol} | ${exitSignal.reason} | ${exitSignal.details}", ts.mint)
            TradeStateMachine.startCooldown(ts.mint)
            return "v8_${exitSignal.reason.lowercase()}"
        }

        // Wick protection: skip stop in first 90s unless extreme loss
        if (heldSecs < 90.0 && gainPct > -cfg().stopLossPct * 1.5) return null

        // LIQUIDITY COLLAPSE DETECTION: Emergency exit if liquidity drops significantly
        val currentLiq = ts.lastLiquidityUsd
        val entryLiq = pos.entryLiquidityUsd
        if (entryLiq > 0 && currentLiq > 0) {
            val liqDropPct = ((entryLiq - currentLiq) / entryLiq) * 100
            if (liqDropPct > 50) {  // Liquidity dropped 50%+
                onLog("🚨 LIQ COLLAPSE: ${ts.symbol} liq dropped ${liqDropPct.toInt()}% | exit NOW", ts.mint)
                return "liquidity_collapse"
            }
            if (liqDropPct > 30 && gainPct < 0) {  // 30% drop AND we're losing
                onLog("⚠️ LIQ DRAIN: ${ts.symbol} liq dropped ${liqDropPct.toInt()}% while losing | exit", ts.mint)
                return "liquidity_drain"
            }
        }
        
        // WHALE/DEV DUMP DETECTION: Exit if seeing heavy sell pressure
        if (ts.history.size >= 3) {
            val recentCandles = ts.history.takeLast(3)
            val totalSells = recentCandles.sumOf { it.sellsH1 }
            val totalBuys = recentCandles.sumOf { it.buysH1 }
            val sellRatio = if (totalBuys + totalSells > 0) totalSells.toDouble() / (totalBuys + totalSells) else 0.0
            
            // If sells > 80% of activity AND price dropping AND we're in profit, protect gains
            if (sellRatio > 0.80 && gainPct > 10 && ts.meta.pressScore < -30) {
                onLog("🐋 WHALE DUMP: ${ts.symbol} sell ratio ${(sellRatio*100).toInt()}% | protecting gains", ts.mint)
                return "whale_dump"
            }
            
            // Large volume spike with mostly sells = likely dev dump
            val avgVol = recentCandles.map { it.volumeH1 }.average()
            val lastVol = recentCandles.last().volumeH1
            if (lastVol > avgVol * 3 && sellRatio > 0.70 && gainPct < 0) {
                onLog("🚨 DEV DUMP: ${ts.symbol} volume spike ${(lastVol/avgVol).toInt()}x with heavy sells", ts.mint)
                return "dev_dump"
            }
        }

        val effectiveStopPct = modeConf?.stopLossPct ?: cfg().stopLossPct
        if (gainPct <= -effectiveStopPct) return "stop_loss"
        
        // V5: Smart trailing with trend health signals
        val smartFloor = trailingFloor(
            pos = pos,
            current = price,
            modeConf = modeConf,
            emaFanAlignment = ts.meta.emafanAlignment,
            emaFanWidening = ts.meta.emafanAlignment == "BULL_FAN" && ts.meta.volScore >= 55,  // Proxy for widening
            volScore = ts.meta.volScore,
            pressScore = ts.meta.pressScore,
            exhaust = ts.meta.exhaustion,
        )
        if (price < smartFloor) return "trailing_stop"
        return null
    }

    // ── dispatch ──────────────────────────────────────────────────────

    fun maybeAct(
        ts: TokenState,
        signal: String,
        entryScore: Double,
        walletSol: Double,
        wallet: SolanaWallet?,
        lastPollMs: Long = System.currentTimeMillis(),
        openPositionCount: Int = 0,
        totalExposureSol: Double = 0.0,
        modeConfig: AutoModeEngine.ModeConfig? = null,
        walletTotalTrades: Int = 0,
    ) {
        // Halt check first — no action if halted
        val cbState = security.getCircuitBreakerState()
        if (cbState.isHalted) {
            onLog("🛑 Halted: ${cbState.haltReason}", ts.mint)
            return
        }

        // Update shadow learning engine with current price
        if (ts.position.isOpen) {
            ShadowLearningEngine.onPriceUpdate(
                mint = ts.mint,
                currentPrice = ts.ref,
                liveStopLossPct = cfg().stopLossPct,
                liveTakeProfitPct = 200.0,  // Default take profit threshold
            )
            
            // ════════════════════════════════════════════════════════════════
            // V8: Precision Exit Logic - Quick check for urgent exits
            // ════════════════════════════════════════════════════════════════
            val quickExit = PrecisionExitLogic.quickCheck(
                mint = ts.mint,
                currentPrice = ts.ref,
                entryPrice = ts.position.entryPrice,
                stopLossPct = cfg().stopLossPct,
            )
            if (quickExit != null && quickExit.shouldExit) {
                onLog("🚨 V8 QUICK EXIT: ${ts.symbol} | ${quickExit.reason} | ${quickExit.details}", ts.mint)
                doSell(ts, "v8_${quickExit.reason.lowercase()}", wallet, walletSol)
                TradeStateMachine.startCooldown(ts.mint)
                return
            }
        }

        // Stale data check
        val freshness = security.checkDataFreshness(lastPollMs)
        if (freshness is GuardResult.Block) {
            onLog("⚠ ${freshness.reason}", ts.mint)
            return
        }

        // ════════════════════════════════════════════════════════════════
        // PROFIT LOCK SYSTEM — Check BEFORE partial sells and risk checks
        // This ensures we secure capital and lock profits at key milestones
        // ════════════════════════════════════════════════════════════════
        if (ts.position.isOpen) {
            if (checkProfitLock(ts, wallet, walletSol)) {
                // Profit lock was executed, skip other sell logic this tick
                return
            }
        }

        // v4.4: Partial sell check — runs before full risk check
        if (ts.position.isOpen) checkPartialSell(ts, wallet, walletSol)

        // Risk rules (mode-aware)
        val reason = riskCheck(ts, modeConfig)
        if (reason != null) { doSell(ts, reason, wallet, walletSol); return }

        if (signal in listOf("SELL", "EXIT") && ts.position.isOpen) {
            doSell(ts, signal.lowercase(), wallet, walletSol); return
        }
        if (ts.position.isOpen && modeConfig != null) {
            val _held = (System.currentTimeMillis() - ts.position.entryTime) / 60_000.0
            val _tf   = ts.candleTimeframeMinutes.toDouble().coerceAtLeast(1.0)
            if (_held > modeConfig.maxHoldMins * _tf) {
                doSell(ts, "mode_maxhold_${modeConfig.mode.name.lowercase()}", wallet, walletSol); return
            }
        }

        // ── Top-up: strategy has already computed all conditions ────
        // ts.meta.topUpReady is set by LifecycleStrategy every tick using
        // full signal access: EMA fan, exit score, exhaust, spike, vol, pressure.
        // We just need to enforce position/wallet caps and cooldown here.
        // ── Long-hold promotion ──────────────────────────────────────────
        // Every tick: check if this open position now qualifies for long-hold.
        // One-way ratchet — promoted positions stay long-hold until closed.
        if (ts.position.isOpen && !ts.position.isLongHold && cfg().longHoldEnabled) {
            val gainPct   = pct(ts.position.entryPrice, ts.ref)
            val c         = cfg()
            val holders   = ts.history.lastOrNull()?.holderCount ?: 0
            // Compute existing long-hold exposure locally — no BotService.instance needed
            // (we already have walletSol and totalExposureSol from maybeAct params)
            val existingLH = 0.0  // conservative default — full check done in strategy

            val meetsConviction = ts.meta.emafanAlignment == "BULL_FAN"
                && gainPct >= c.longHoldMinGainPct
                && ts.lastLiquidityUsd >= c.longHoldMinLiquidityUsd
                && holders >= c.longHoldMinHolders
                && ts.holderGrowthRate >= c.longHoldHolderGrowthMin
                && (!c.longHoldTreasuryGate || TreasuryManager.treasurySol >= 0.01)
                && ts.position.costSol <= walletSol * c.longHoldWalletPct

            if (meetsConviction) {
                ts.position = ts.position.copy(isLongHold = true)
                onLog("🔒 LONG HOLD: ${ts.symbol} promoted — " +
                    "BULL_FAN | ${holders} holders (+${ts.holderGrowthRate.toInt()}%) | " +
                    "$${(ts.lastLiquidityUsd/1000).toInt()}K liq | +${gainPct.toInt()}%", ts.mint)
                onNotify("🔒 Long Hold: ${ts.symbol}",
                    "+${gainPct.toInt()}% | riding trend | max ${c.longHoldMaxDays.toInt()}d",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            }
        }

        if (cfg().autoTrade && ts.position.isOpen && ts.meta.topUpReady) {
            val topUpReady = shouldTopUp(
                ts              = ts,
                entryScore      = entryScore,
                exitScore       = ts.exitScore,
                emafanAlignment = ts.meta.emafanAlignment,  // real value from strategy
                volScore        = ts.meta.volScore,
                exhaust         = ts.meta.exhaustion,
            )
            if (topUpReady) {
                doTopUp(ts, walletSol, wallet, totalExposureSol)
            }
        }

        // GRADUATED BUILDING - check for phase 2/3 adds
        if (cfg().paperMode && ts.position.isOpen && !ts.position.isFullyBuilt) {
            val result = shouldGraduatedAdd(ts.position, ts.ref, ts.meta.volScore)
            if (result != null) {
                val (addSol, newPhase) = result
                doGraduatedAdd(ts, addSol, newPhase)
            }
        }

        // PAPER MODE: ALWAYS trade regardless of autoTrade setting
        // We want maximum trading activity for learning
        val shouldActOnBuy = cfg().paperMode || cfg().autoTrade
        
        // DEBUG: Log why we might not be executing buys
        if (signal == "BUY") {
            ErrorLogger.debug("Executor", "BUY CHECK: ${ts.symbol} | shouldAct=$shouldActOnBuy | posOpen=${ts.position.isOpen} | autoTrade=${cfg().autoTrade} | paper=${cfg().paperMode}")
        }
        
        if (shouldActOnBuy && signal == "BUY" && !ts.position.isOpen) {
            val isPaper = cfg().paperMode
            ErrorLogger.info("Executor", "🔔 BUY signal for ${ts.symbol} | paper=$isPaper | wallet=${walletSol.fmt(4)} | autoTrade=${cfg().autoTrade}")
            
            // ══════════════════════════════════════════════════════════════
            // FIX #3: SEVERE-LOSS QUARANTINE (by contract address)
            // No rebuy after -33% rug - check BEFORE any other logic
            // ══════════════════════════════════════════════════════════════
            val severeLossThreshold = -33.0
            val lastExitPnl = ts.lastExitPnlPct
            if (lastExitPnl < severeLossThreshold) {
                ErrorLogger.info("Executor", "🚫 ${ts.symbol} QUARANTINED: Previous exit was ${lastExitPnl.toInt()}% (< $severeLossThreshold%)")
                onLog("💀 ${ts.symbol}: QUARANTINED (rugged ${lastExitPnl.toInt()}%)", ts.mint)
                // Add to permanent blacklist by contract
                RuggedContracts.add(ts.mint, ts.symbol, lastExitPnl)
                return
            }
            
            // Also check if this contract is already blacklisted
            if (RuggedContracts.isBlacklisted(ts.mint)) {
                ErrorLogger.info("Executor", "🚫 ${ts.symbol} BLACKLISTED: Previously rugged")
                onLog("💀 ${ts.symbol}: Blacklisted contract", ts.mint)
                return
            }
            
            // ════════════════════════════════════════════════════════════════
            // V8: State Machine Integration
            // ════════════════════════════════════════════════════════════════
            val tradeState = TradeStateMachine.getState(ts.mint)
            val isPaperMode = cfg().paperMode
            
            // Check cooldown - SKIP IN PAPER MODE
            // DYNAMIC RE-ENTRY: Allow re-entry if last trade was profitable and conditions improved
            if (!isPaperMode && TradeStateMachine.isInCooldown(ts.mint)) {
                val lastTrade = ts.trades.lastOrNull()
                val wasProfit = lastTrade?.let { it.side == "SELL" && (it.pnlPct ?: 0.0) > 0 } ?: false
                val priceDroppedFromExit = lastTrade?.let { ts.ref < it.price * 0.85 } ?: false  // 15%+ below exit
                val scoreImproved = entryScore >= 50  // Good entry score
                
                // Allow re-entry if: profitable last trade + price dipped + good score
                if (wasProfit && priceDroppedFromExit && scoreImproved) {
                    onLog("🔄 RE-ENTRY: ${ts.symbol} dipped 15%+ from profitable exit, score=$entryScore", ts.mint)
                    TradeStateMachine.clearCooldown(ts.mint)  // Clear cooldown for re-entry
                } else {
                    onLog("⏸️ ${ts.symbol}: In cooldown, skipping", ts.mint)
                    return
                }
            }
            
            // Transition to WATCH state if not already
            if (tradeState.state == TradeState.SCAN) {
                TradeStateMachine.setState(ts.mint, TradeState.WATCH, "BUY signal received")
            }
            
            // Check entry pattern (spike → pullback → re-acceleration)
            // SKIP PATTERN REQUIREMENT IN PAPER MODE - trade immediately to learn
            val priceHistory = ts.history.map { it.priceUsd }
            val optimalEntry = if (isPaperMode) true else TradeStateMachine.detectEntryPattern(ts.mint, ts.ref, priceHistory)
            
            // If we have entry pattern requirement enabled, wait for optimal entry
            // DISABLED IN PAPER MODE
            val c = cfg()
            val requireOptimalEntry = !isPaperMode && c.smallBuySol < 0.1  // Only for small positions in real mode
            
            if (requireOptimalEntry && !optimalEntry && tradeState.entryPattern != EntryPattern.NONE) {
                // We've seen a spike but waiting for pullback+reaccel
                if (tradeState.entryPattern == EntryPattern.FIRST_SPIKE) {
                    onLog("📈 ${ts.symbol}: Spike detected, waiting for pullback...", ts.mint)
                } else if (tradeState.entryPattern == EntryPattern.PULLBACK) {
                    onLog("📉 ${ts.symbol}: Pullback detected, waiting for re-acceleration...", ts.mint)
                }
                return  // Wait for optimal entry
            }
            
            if (optimalEntry && !isPaperMode) {
                onLog("🎯 ${ts.symbol}: OPTIMAL ENTRY - Spike→Pullback→ReAccel pattern!", ts.mint)
            }
            
            // Transition to ENTER state
            TradeStateMachine.setState(ts.mint, TradeState.ENTER, "executing buy")
            
            if (ts.position.isOpen) {
                ErrorLogger.debug("Executor", "Skipping ${ts.symbol} - position already open")
                return
            }
            // No concurrent cap — SmartSizer 70% exposure ceiling is the guard
            if (cfg().scalingLogEnabled) { val _spx=WalletManager.lastKnownSolPrice; val (_tier,_)=ScalingMode.maxPositionForToken(ts.lastLiquidityUsd,ts.lastFdv,TreasuryManager.treasurySol*_spx,_spx); if(_tier!=ScalingMode.Tier.MICRO) onLog("${_tier.icon} ${_tier.label}: ${ts.symbol}", ts.mint) }
            
            // Calculate AI confidence for sizing
            val aiConfidence = try {
                val hist = ts.history.toList()
                val prices = hist.map { it.ref }
                if (hist.size >= 6) {
                    val edgePhase = EdgeOptimizer.detectMarketPhase(hist, prices)
                    val edgeTiming = EdgeOptimizer.checkEntryTiming(edgePhase, hist, prices, ts.meta.pressScore)
                    EdgeOptimizer.calculateConfidence(edgePhase, edgeTiming,
                        EdgeOptimizer.WeightedScore(entryScore, 0.0, emptyMap()))
                } else 50.0
            } catch (e: Exception) { 50.0 }
            
            // WALLET INTELLIGENCE: DataPipeline integration
            // Fetches advanced alpha signals: whale ratio, repeat wallet detection, etc.
            var walletIntelligenceBlocked = false
            val alphaSignals = try {
                runBlocking {
                    withTimeoutOrNull(3000L) {  // 3 second timeout
                        DataPipeline.getAlphaSignals(ts.mint, cfg()) { msg ->
                            ErrorLogger.debug("DataPipeline", msg)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("DataPipeline", "Error fetching alpha signals: ${e.message}")
                null
            }
            
            // Apply wallet intelligence signals to block risky trades
            if (alphaSignals != null && !isPaper) {
                // Block on bot farm detection (repeat wallets across tokens)
                if (alphaSignals.repeatWalletScore > 60.0) {
                    onLog("🤖 WALLET INTEL: Bot farm detected (repeat wallets ${alphaSignals.repeatWalletScore.toInt()}%) — blocking", ts.mint)
                    walletIntelligenceBlocked = true
                }
                // Block on distribution pattern (volume up, price flat)
                if (alphaSignals.volumePriceDivergence > 70.0) {
                    onLog("📉 WALLET INTEL: Distribution detected (vol/price div ${alphaSignals.volumePriceDivergence.toInt()}) — blocking", ts.mint)
                    walletIntelligenceBlocked = true
                }
                // Block on extreme whale concentration
                if (alphaSignals.whaleRatio > 0.6) {
                    onLog("🐋 WALLET INTEL: Whale concentration too high (${(alphaSignals.whaleRatio * 100).toInt()}%) — blocking", ts.mint)
                    walletIntelligenceBlocked = true
                }
                // Log grade for info
                if (alphaSignals.overallGrade in listOf("D", "F")) {
                    onLog("⚠️ WALLET INTEL: Low grade (${alphaSignals.overallGrade}) — ${DataPipeline.formatAlphaSignals(ts.mint, alphaSignals)}", ts.mint)
                }
            }
            
            if (walletIntelligenceBlocked) {
                ErrorLogger.info("Executor", "❌ ${ts.symbol} blocked by wallet intelligence")
                return
            }
            
            // ══════════════════════════════════════════════════════════════
            // FIX #2: HARD CONFIDENCE AND QUALITY GATES
            // No huge buys on C / early_unknown / low confidence
            // ══════════════════════════════════════════════════════════════
            val setupQuality = ts.meta.setupQuality
            val isLowQuality = setupQuality == "C"
            val isUnknownPhase = ts.phase.contains("unknown", ignoreCase = true)
            val isLowConfidence = aiConfidence < 30.0
            
            // Block outright if ALL three red flags present
            if (isLowQuality && isUnknownPhase && isLowConfidence) {
                ErrorLogger.info("Executor", "❌ ${ts.symbol} BLOCKED: C quality + unknown phase + low conf (${aiConfidence.toInt()}%)")
                onLog("🚫 ${ts.symbol}: Blocked (C + unknown + low conf)", ts.mint)
                return
            }
            
            // Severely limit size if any two red flags present
            val redFlagCount = listOf(isLowQuality, isUnknownPhase, isLowConfidence).count { it }
            val qualityPenalty = when (redFlagCount) {
                2 -> 0.25  // 75% size reduction
                1 -> 0.60  // 40% size reduction for single red flag
                else -> 1.0
            }
            
            // AI-DRIVEN SIZING: Pass confidence, phase, source, brain, and setup quality to SmartSizer
            ErrorLogger.info("Executor", "📊 ${ts.symbol} SIZING: wallet=$walletSol | liq=${ts.lastLiquidityUsd} | mcap=${ts.lastFdv} | conf=$aiConfidence | entry=$entryScore | quality=$setupQuality | redFlags=$redFlagCount")
            var size = buySizeSol(
                entryScore = entryScore, 
                walletSol = walletSol, 
                currentOpenPositions = openPositionCount, 
                currentTotalExposure = totalExposureSol,
                walletTotalTrades = walletTotalTrades,
                liquidityUsd = ts.lastLiquidityUsd,
                mcapUsd = ts.lastFdv,
                aiConfidence = aiConfidence,
                phase = ts.phase,
                source = ts.source,
                brain = brain,
                setupQuality = setupQuality,
            )
            
            // Apply quality penalty from hard gates
            if (qualityPenalty < 1.0) {
                val oldSize = size
                size *= qualityPenalty
                ErrorLogger.info("Executor", "📉 ${ts.symbol} size reduced: ${oldSize.fmt(3)} → ${size.fmt(3)} (penalty=${qualityPenalty}x, redFlags=$redFlagCount)")
            }

            // Cross-token correlation guard (FIX 7: tier-aware)
            // Penalise clustering only within the same ScalingMode tier.
            // A MICRO snipe + GROWTH range trade are NOT correlated — different pools,
            // different buyers. Only cluster MICRO-with-MICRO or GROWTH-with-GROWTH.
            if (c.crossTokenGuardEnabled) {
                val windowMs = (c.crossTokenWindowMins * 60_000.0).toLong()
                val cutoff   = System.currentTimeMillis() - windowMs
                val solPxCG  = WalletManager.lastKnownSolPrice
                val trsUsdCG = TreasuryManager.treasurySol * solPxCG
                val thisTier = ScalingMode.tierForToken(ts.lastLiquidityUsd, ts.lastFdv)
                ts.recentEntryTimes.removeIf { it < cutoff }
                // Count only same-tier entries in the window
                val sameTierCount = BotService.status.openPositions.count { other ->
                    other.mint != ts.mint &&
                    ScalingMode.tierForToken(other.lastLiquidityUsd, other.lastFdv) == thisTier &&
                    (System.currentTimeMillis() - other.position.entryTime) < windowMs
                }
                if (sameTierCount >= c.crossTokenMaxCluster) {
                    size *= c.crossTokenSizePenalty
                    onLog("⚠ Cluster guard (${thisTier.label}): ${sameTierCount} same-tier entries " +
                          "— size ${size.fmt(4)} SOL", ts.mint)
                }
                ts.recentEntryTimes.add(System.currentTimeMillis())
            }
            // Apply auto-mode size multiplier
            modeConfig?.let { size = size * it.positionSizeMultiplier }
            
            // BotBrain skip check - DISABLED IN PAPER MODE
            if (!isPaperMode) {
                brain?.let { b ->
                    val emaFan = ts.meta.emafanAlignment
                    if (b.shouldSkipTrade(ts.phase, emaFan, ts.source, entryScore)) {
                        onLog("🧠 Brain SKIP: ${ts.symbol} — too many risk factors", ts.mint)
                        return
                    }
                }
            }
            
            if (size < 0.001) {
                ErrorLogger.error("Executor", "❌ ${ts.symbol} SIZE TOO SMALL: $size | wallet=$walletSol | paper=$isPaperMode | liq=${ts.lastLiquidityUsd}")
                onLog("Insufficient capacity for new position on ${ts.symbol} (size=$size)", ts.mint)
                return
            }
            
            // Size OK - proceed with buy
            ErrorLogger.info("Executor", "✅ ${ts.symbol} SIZE OK: $size SOL - proceeding to doBuy()")

            // Notify shadow learning engine of trade opportunity
            ShadowLearningEngine.onTradeOpportunity(
                mint = ts.mint,
                symbol = ts.symbol,
                currentPrice = ts.ref,
                liveEntryScore = entryScore.toInt(),
                liveEntryThreshold = 42,  // base entry threshold
                liveSizeSol = size,
                phase = ts.phase,
            )

            doBuy(ts, size, entryScore, wallet, walletSol, null, setupQuality)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PRIORITY 2: UNIFIED CANDIDATE DECISION SUPPORT
    // ══════════════════════════════════════════════════════════════════
    
    /**
     * Execute trade action using the unified CandidateDecision.
     * 
     * This method provides a cleaner interface where all scoring and 
     * quality decisions have already been made by the strategy.
     * The Executor simply executes based on the final verdict.
     * 
     * @param ts Token state
     * @param decision Unified decision from strategy's evaluateWithDecision()
     * @param walletSol Available wallet balance
     * @param wallet Solana wallet for live trading
     * @param lastPollMs Last successful data poll timestamp
     * @param openPositionCount Current open position count
     * @param totalExposureSol Current total exposure in SOL
     * @param modeConfig Auto-mode configuration
     * @param fdgApprovedSize Optional FDG-approved size (skips recalculation if provided)
     * @param walletTotalTrades Total trades for this wallet
     */
    fun maybeActWithDecision(
        ts: TokenState,
        decision: CandidateDecision,
        walletSol: Double,
        wallet: SolanaWallet?,
        lastPollMs: Long = System.currentTimeMillis(),
        openPositionCount: Int = 0,
        totalExposureSol: Double = 0.0,
        modeConfig: AutoModeEngine.ModeConfig? = null,
        fdgApprovedSize: Double? = null,
        walletTotalTrades: Int = 0,
        tradeIdentity: TradeIdentity? = null,  // Canonical identity for consistent tracking
        fdgApprovalClass: FinalDecisionGate.ApprovalClass? = null,  // LIVE, PAPER_BENCHMARK, PAPER_EXPLORATION
    ) {
        // ═══════════════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Use identity for consistent mint/symbol throughout
        // ═══════════════════════════════════════════════════════════════════════════
        val identity = tradeIdentity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        // Store approval class in identity for later analytics
        fdgApprovalClass?.let { identity.fdgApprovalClass = it.name }
        
        // Halt check
        val cbState = security.getCircuitBreakerState()
        if (cbState.isHalted) {
            onLog("🛑 Halted: ${cbState.haltReason}", identity.mint)
            return
        }
        
        // Handle open positions (exits, stop-losses, etc.)
        if (ts.position.isOpen) {
            // V8 quick exit check
            val quickExit = PrecisionExitLogic.quickCheck(
                mint = identity.mint,
                currentPrice = ts.ref,
                entryPrice = ts.position.entryPrice,
                stopLossPct = cfg().stopLossPct,
            )
            if (quickExit != null && quickExit.shouldExit) {
                onLog("🚨 V8 QUICK EXIT: ${identity.symbol} | ${quickExit.reason} | ${quickExit.details}", identity.mint)
                doSell(ts, "v8_${quickExit.reason.lowercase()}", wallet, walletSol, identity)
                TradeStateMachine.startCooldown(identity.mint)
                return
            }
            
            // Partial sell check
            checkPartialSell(ts, wallet, walletSol)
            
            // Risk check
            val reason = riskCheck(ts, modeConfig)
            if (reason != null) {
                doSell(ts, reason, wallet, walletSol, identity)
                return
            }
            
            // Explicit sell/exit signal
            if (decision.finalSignal in listOf("SELL", "EXIT")) {
                doSell(ts, decision.finalSignal.lowercase(), wallet, walletSol, identity)
                return
            }
            
            // Mode max hold
            if (modeConfig != null) {
                val held = (System.currentTimeMillis() - ts.position.entryTime) / 60_000.0
                val tf = ts.candleTimeframeMinutes.toDouble().coerceAtLeast(1.0)
                if (held > modeConfig.maxHoldMins * tf) {
                    doSell(ts, "mode_maxhold_${modeConfig.mode.name.lowercase()}", wallet, walletSol, identity)
                    return
                }
            }
            
            // Top-up and graduated building
            if (cfg().autoTrade && decision.meta.topUpReady) {
                val topUpReady = shouldTopUp(
                    ts = ts,
                    entryScore = decision.entryScore,
                    exitScore = decision.exitScore,
                    emafanAlignment = decision.meta.emafanAlignment,
                    volScore = decision.meta.volScore,
                    exhaust = decision.meta.exhaustion,
                )
                if (topUpReady) {
                    doTopUp(ts, walletSol, wallet, totalExposureSol)
                }
            }
            
            if (cfg().paperMode && !ts.position.isFullyBuilt) {
                val result = shouldGraduatedAdd(ts.position, ts.ref, decision.meta.volScore)
                if (result != null) {
                    val (addSol, newPhase) = result
                    doGraduatedAdd(ts, addSol, newPhase)
                }
            }
            
            return  // Position already open, no new entry
        }
        
        // ══════════════════════════════════════════════════════════════════
        // NEW ENTRY LOGIC - Using unified CandidateDecision
        // ══════════════════════════════════════════════════════════════════
        
        // Check if we should act on buys
        // PAPER MODE: ALWAYS trade for learning
        // LIVE MODE: Requires autoTrade to be enabled
        val shouldActOnBuy = cfg().paperMode || cfg().autoTrade
        if (!shouldActOnBuy) {
            ErrorLogger.debug("Executor", "📊 ${ts.symbol}: Buy skipped - autoTrade disabled")
            return
        }
        
        // Early return if decision says no trade
        if (!decision.shouldTrade) {
            if (decision.finalSignal == "BUY" && decision.blockReason.isNotEmpty()) {
                ErrorLogger.debug("Executor", "📊 ${ts.symbol}: Blocked - ${decision.blockReason}")
            }
            return
        }
        
        val isPaper = cfg().paperMode
        ErrorLogger.info("Executor", "🔔 UNIFIED BUY: ${ts.symbol} | " +
            "quality=${decision.finalQuality} | edge=${decision.edgePhase} | " +
            "conf=${decision.aiConfidence.toInt()}% | penalty=${decision.qualityPenalty} | " +
            "paper=$isPaper | autoTrade=${cfg().autoTrade}")
        
        // Rugged contracts check (by mint address)
        if (RuggedContracts.isBlacklisted(ts.mint)) {
            ErrorLogger.info("Executor", "🚫 ${ts.symbol} BLACKLISTED: Previously rugged")
            onLog("💀 ${ts.symbol}: Blacklisted contract", ts.mint)
            return
        }
        
        // State machine cooldown (skip in paper mode)
        if (!isPaper && TradeStateMachine.isInCooldown(ts.mint)) {
            onLog("⏸️ ${ts.symbol}: In cooldown, skipping", ts.mint)
            return
        }
        
        // Transition to ENTER state
        TradeStateMachine.setState(ts.mint, TradeState.ENTER, "executing buy via unified decision")
        
        // Calculate size - use FDG-approved size if available, otherwise calculate
        // NOTE: If fdgApprovedSize is provided, it ALREADY includes mode multiplier and graduated building
        var size = fdgApprovedSize ?: buySizeSol(
            entryScore = decision.entryScore,
            walletSol = walletSol,
            currentOpenPositions = openPositionCount,
            currentTotalExposure = totalExposureSol,
            walletTotalTrades = walletTotalTrades,
            liquidityUsd = ts.lastLiquidityUsd,
            mcapUsd = ts.lastFdv,
            aiConfidence = decision.aiConfidence,
            phase = decision.phase,
            source = ts.source,
            brain = brain,
            setupQuality = decision.setupQuality,
        )
        
        // Only apply these adjustments if we calculated size ourselves (no FDG-approved size)
        if (fdgApprovedSize == null) {
            // Apply quality penalty from unified decision
            if (decision.qualityPenalty < 1.0 && decision.qualityPenalty > 0.0) {
                val oldSize = size
                size *= decision.qualityPenalty
                ErrorLogger.info("Executor", "📉 ${ts.symbol} size reduced: ${oldSize.fmt(3)} → ${size.fmt(3)} " +
                    "(penalty=${decision.qualityPenalty}x, redFlags=${decision.redFlagCount})")
            }
            
            // Apply auto-mode size multiplier
            modeConfig?.let { size *= it.positionSizeMultiplier }
        }
        
        // BotBrain skip check (skip in paper mode)
        if (!isPaper) {
            brain?.let { b ->
                if (b.shouldSkipTrade(decision.phase, decision.meta.emafanAlignment, ts.source, decision.entryScore)) {
                    onLog("🧠 Brain SKIP: ${ts.symbol} — too many risk factors", ts.mint)
                    return
                }
            }
        }
        
        if (size < 0.001) {
            ErrorLogger.error("Executor", "❌ ${ts.symbol} SIZE TOO SMALL: $size | quality=${decision.finalQuality}")
            onLog("Insufficient capacity for ${ts.symbol} (size=$size)", ts.mint)
            return
        }
        
        // Execute buy
        if (isPaper) {
            ErrorLogger.info("Executor", "📄 ${ts.symbol} PAPER BUY: $size SOL - quality=${decision.finalQuality}")
        } else {
            // LIVE MODE: Explicit logging before real trade
            ErrorLogger.info("Executor", "💰 ${ts.symbol} LIVE BUY ATTEMPT: $size SOL - " +
                "quality=${decision.finalQuality} | wallet=$walletSol | autoTrade=${cfg().autoTrade}")
            onLog("💰 LIVE BUY: ${ts.symbol} | ${size.fmt(4)} SOL | quality=${decision.finalQuality}", ts.mint)
        }
        
        // Notify shadow learning
        ShadowLearningEngine.onTradeOpportunity(
            mint = ts.mint,
            symbol = ts.symbol,
            currentPrice = ts.ref,
            liveEntryScore = decision.entryScore.toInt(),
            liveEntryThreshold = 42,
            liveSizeSol = size,
            phase = decision.phase,
        )
        
        // If FDG-approved size was provided, graduated building was already applied
        val skipGraduated = fdgApprovedSize != null
        doBuy(ts, size, decision.entryScore, wallet, walletSol, identity, decision.setupQuality, skipGraduated)
    }

    // ── top-up (pyramid add) ─────────────────────────────────────────

    fun doTopUp(
        ts: TokenState,
        walletSol: Double,
        wallet: SolanaWallet?,
        totalExposureSol: Double,
    ) {
        val pos  = ts.position
        val c    = cfg()
        val size = topUpSizeSol(pos, walletSol, totalExposureSol)

        if (size < 0.001) {
            onLog("⚠ Top-up skipped: size too small (${size})", ts.mint)
            return
        }

        val gainPct = pct(pos.entryPrice, ts.ref)
        onLog("🔺 TOP-UP #${pos.topUpCount + 1}: ${ts.symbol} " +
              "+${gainPct.toInt()}% gain | adding ${size.fmt(4)} SOL " +
              "(total will be ${(pos.costSol + size).fmt(4)} SOL)", ts.mint)

        // Execute the buy — reuses the same buy path with security checks
        if (c.paperMode || wallet == null) {
            paperTopUp(ts, size)
        } else {
            val guard = security.checkBuy(
                mint         = ts.mint,
                symbol       = ts.symbol,
                solAmount    = size,
                walletSol    = walletSol,
                currentPrice = ts.lastPrice,
                currentVol   = ts.history.lastOrNull()?.vol ?: 0.0,
                liquidityUsd = ts.lastLiquidityUsd,
            )
            when (guard) {
                is GuardResult.Block -> onLog("🚫 Top-up blocked: ${guard.reason}", ts.mint)
                is GuardResult.Allow -> liveTopUp(ts, size, wallet, walletSol)
            }
        }
    }

    private fun paperTopUp(ts: TokenState, sol: Double) {
        val pos   = ts.position
        val price = ts.ref
        if (price <= 0) return

        val newQty    = sol / maxOf(price, 1e-12)
        val totalQty  = pos.qtyToken + newQty
        val totalCost = pos.costSol + sol

        ts.position = pos.copy(
            qtyToken       = totalQty,
            entryPrice     = totalCost / totalQty,  // weighted average entry
            costSol        = totalCost,
            topUpCount     = pos.topUpCount + 1,
            topUpCostSol   = pos.topUpCostSol + sol,
            lastTopUpTime  = System.currentTimeMillis(),
            lastTopUpPrice = price,
        )

        val trade = Trade("BUY", "paper", sol, price,
                          System.currentTimeMillis(), "top_up_${pos.topUpCount + 1}")
        ts.trades.add(trade)
        security.recordTrade(trade)

        val gainPct = pct(pos.entryPrice, price)
        onLog("PAPER TOP-UP #${pos.topUpCount + 1} @ ${price.fmt()} | " +
              "${sol.fmt(4)} SOL | running gain was +${gainPct.toInt()}% | " +
              "avg entry now ${ts.position.entryPrice.fmt()}", ts.mint)
        onNotify("🔺 Top-Up #${pos.topUpCount + 1}",
                 "${ts.symbol}  +${gainPct.toInt()}%  adding ${sol.fmt(3)} SOL",
                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        sounds?.playMilestone(gainPct)
    }

    private fun liveTopUp(ts: TokenState, sol: Double,
                           wallet: SolanaWallet, walletSol: Double) {
        val c = cfg()
        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair integrity failure — top-up aborted", ts.mint)
            return
        }
        val lamports = (sol * 1_000_000_000L).toLong()
        try {
            val quote  = getQuoteWithSlippageGuard(JupiterApi.SOL_MINT, ts.mint,
                                                    lamports, c.slippageBps, sol)
            val qGuard = security.validateQuote(quote, isBuy = true, inputSol = sol)
            if (qGuard is GuardResult.Block) {
                onLog("🚫 Top-up quote rejected: ${qGuard.reason}", ts.mint); return
            }
            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            security.enforceSignDelay()
            
            // ⚡ MEV PROTECTION for top-ups (Ultra or Jito)
            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            
            if (quote.isUltra) {
                onLog("🚀 Broadcasting top-up via Jupiter Ultra…", ts.mint)
            } else {
                onLog("Broadcasting top-up tx…", ts.mint)
            }
            val sig    = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey)
            val pos    = ts.position
            val price  = ts.ref
            val newQty = quote.outAmount.toDouble() / tokenScale(quote.outAmount)

            ts.position = pos.copy(
                qtyToken       = pos.qtyToken + newQty,
                entryPrice     = (pos.costSol + sol) / (pos.qtyToken + newQty),
                costSol        = pos.costSol + sol,
                topUpCount     = pos.topUpCount + 1,
                topUpCostSol   = pos.topUpCostSol + sol,
                lastTopUpTime  = System.currentTimeMillis(),
                lastTopUpPrice = price,
            )

            val gainPct = pct(pos.entryPrice, price)
            val trade   = Trade("BUY", "live", sol, price,
                                System.currentTimeMillis(), "top_up_${pos.topUpCount + 1}",
                                sig = sig)
            ts.trades.add(trade)
            security.recordTrade(trade)
            onLog("LIVE TOP-UP #${pos.topUpCount + 1} @ ${price.fmt()} | " +
                  "${sol.fmt(4)} SOL | +${gainPct.toInt()}% gain | sig=${sig.take(16)}…",
                  ts.mint)
            onNotify("🔺 Live Top-Up #${pos.topUpCount + 1}",
                     "${ts.symbol}  +${gainPct.toInt()}%  ${sol.fmt(3)} SOL",
                     com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        } catch (e: Exception) {
            onLog("Live top-up FAILED: ${security.sanitiseForLog(e.message ?: "unknown")}", ts.mint)
        }
    }

    // ── buy ───────────────────────────────────────────────────────────

    private fun doBuy(ts: TokenState, sol: Double, score: Double,
                      wallet: SolanaWallet?, walletSol: Double,
                      identity: TradeIdentity? = null,
                      quality: String = "C",
                      skipGraduated: Boolean = false) {  // Pass through to paperBuy/liveBuy
        // Get or create canonical identity
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        if (cfg().paperMode || wallet == null) {
            paperBuy(ts, sol, score, tradeId, quality, skipGraduated)
        } else {
            // Pre-flight security check
            val guard = security.checkBuy(
                mint         = tradeId.mint,
                symbol       = tradeId.symbol,
                solAmount    = sol,
                walletSol    = walletSol,
                currentPrice = ts.lastPrice,
                currentVol   = ts.history.lastOrNull()?.vol ?: 0.0,
                liquidityUsd = ts.lastLiquidityUsd,
            )
            when (guard) {
                is GuardResult.Block -> {
                    onLog("🚫 Buy blocked: ${guard.reason}", tradeId.mint)
                    // 🎵 Peter Griffin "No no no!"
                    sounds?.playBlockSound()
                    if (guard.fatal) onNotify("🛑 Bot Halted", guard.reason, com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    return
                }
                is GuardResult.Allow -> liveBuy(ts, sol, score, wallet, walletSol, tradeId, quality, skipGraduated)
            }
        }
    }

    fun paperBuy(ts: TokenState, sol: Double, score: Double, identity: TradeIdentity? = null, 
                 quality: String = "C", skipGraduated: Boolean = false) {
        // ═══════════════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Use canonical identity for consistent tracking
        // ═══════════════════════════════════════════════════════════════════════════
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val price = ts.ref
        if (price <= 0) return
        // Single position enforcement
        if (ts.position.isOpen) {
            onLog("⚠ Buy skipped: position already open", tradeId.mint); return
        }
        
        // GRADUATED BUILDING: start with partial size for B+ setups
        // Skip if already applied by BotService (skipGraduated = true)
        val actualSol: Double
        val buildPhase: Int
        val targetBuild: Double
        
        if (skipGraduated || quality == "C") {
            // Size is already final (graduated applied by BotService) or C quality (no graduated)
            actualSol = sol
            buildPhase = if (quality != "C") 1 else 3  // Still track build phase for future adds
            targetBuild = if (quality != "C") sol / graduatedInitialPct(quality) else 0.0
        } else {
            // Apply graduated building here (legacy path without FDG)
            actualSol = graduatedInitialSize(sol, quality)
            buildPhase = 1
            targetBuild = sol
        }
        
        ts.position = Position(
            qtyToken     = actualSol / maxOf(price, 1e-12),
            entryPrice   = price,
            entryTime    = System.currentTimeMillis(),
            costSol      = actualSol,
            highestPrice = price,
            entryPhase   = ts.phase,
            entryScore   = score,
            entryLiquidityUsd = ts.lastLiquidityUsd,
            buildPhase   = buildPhase,
            targetBuildSol = targetBuild,
        )
        val trade = Trade("BUY", "paper", actualSol, price, System.currentTimeMillis(), score = score)
        ts.trades.add(trade)
        security.recordTrade(trade)
        
        // V8: Transition to MONITOR state (use identity.mint)
        TradeStateMachine.setState(tradeId.mint, TradeState.MONITOR, "position opened")
        
        // ═══════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Mark as executed and monitoring
        // ═══════════════════════════════════════════════════════════════════
        tradeId.executed(price, actualSol, isPaper = true)
        tradeId.monitoring()
        
        // ═══════════════════════════════════════════════════════════════════
        // LIFECYCLE: EXECUTED → MONITORING (use identity.mint for consistency)
        // ═══════════════════════════════════════════════════════════════════
        TradeLifecycle.executed(tradeId.mint, price, actualSol)
        TradeLifecycle.monitoring(tradeId.mint, 0.0)
        
        // Update paper wallet balance (deduct buy amount)
        onPaperBalanceChange?.invoke(-actualSol)
        
        // ═══════════════════════════════════════════════════════════════════
        // EDGE LEARNING: Record entry state for later learning
        // ═══════════════════════════════════════════════════════════════════
        EdgeLearning.recordEntry(
            mint = tradeId.mint,
            symbol = tradeId.symbol,
            buyPct = ts.meta.pressScore,
            volumeScore = ts.meta.volScore,
            phase = ts.phase,
            edgeQuality = quality,  // Use canonical quality from decision
            wasVetoed = false,  // If we got here, we weren't vetoed
            vetoReason = null,
            entryPrice = price,
            isPaperMode = true,
        )
        
        // ═══════════════════════════════════════════════════════════════════
        // ENTRY INTELLIGENCE: Record entry conditions for learning
        // ═══════════════════════════════════════════════════════════════════
        val entryConditions = EntryIntelligence.EntryConditions(
            buyPressure = ts.meta.pressScore,
            volumeScore = ts.meta.volScore,
            priceVsEma = ts.meta.posInRange - 50.0,  // Rough approximation
            rsi = ts.meta.rsi,
            momentum = ts.entryScore,
            hourOfDay = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.HOUR_OF_DAY),
            volatility = ts.meta.avgAtr,
            liquidityUsd = ts.lastLiquidityUsd,
            topHolderPct = ts.safety.topHolderPct,
            isNearSupport = ts.meta.posInRange < 25.0,
            isNearResistance = ts.meta.posInRange > 75.0,
            candlePattern = "none",  // Would need to pass from strategy
        )
        EntryIntelligence.recordEntry(tradeId.mint, entryConditions)
        
        // ═══════════════════════════════════════════════════════════════════
        // LIQUIDITY DEPTH AI: Record entry liquidity for change tracking
        // ═══════════════════════════════════════════════════════════════════
        LiquidityDepthAI.recordEntryLiquidity(tradeId.mint, ts.lastLiquidityUsd)
        
        // 🎵 Homer Simpson "Woohoo!" 
        sounds?.playBuySound()
        
        val buildInfo = if (buildPhase == 1) " [BUILD 1/3]" else ""
        onLog("PAPER BUY  @ ${price.fmt()} | ${actualSol.fmt(4)} SOL | score=${score.toInt()}$buildInfo", tradeId.mint)
        onNotify("📈 Paper Buy", "${tradeId.symbol}  ${actualSol.fmt(3)} SOL$buildInfo", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
    }

    private fun liveBuy(ts: TokenState, sol: Double, score: Double,
                        wallet: SolanaWallet, walletSol: Double,
                        identity: TradeIdentity? = null,
                        quality: String = "C",
                        skipGraduated: Boolean = false) {  // skipGraduated for live trades (size already computed)
        // ═══════════════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Use canonical identity for consistent tracking
        // ═══════════════════════════════════════════════════════════════════════════
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val c = cfg()

        // Keypair integrity check
        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair integrity failure — trade aborted", tradeId.mint)
            return
        }

        val lamports = (sol * 1_000_000_000L).toLong()
        try {
            // Get quote
            val quote = getQuoteWithSlippageGuard(JupiterApi.SOL_MINT, ts.mint,
                                                   lamports, c.slippageBps, sol)

            // Validate quote
            val qGuard = security.validateQuote(quote, isBuy = true, inputSol = sol)
            if (qGuard is GuardResult.Block) {
                onLog("🚫 Quote rejected: ${qGuard.reason}", ts.mint)
                return
            }

            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)

            // Simulate before broadcast — catches balance/slippage/program errors
            val simErr = jupiter.simulateSwap(txResult.txBase64, wallet.rpcUrl)
            if (simErr != null) {
                onLog("Swap simulation failed: $simErr", ts.mint)
                throw Exception(simErr)
            }

            // Enforce sign → broadcast delay
            security.enforceSignDelay()

            // Use signSendAndConfirm — wait for on-chain confirmation before
            // recording the position. This prevents ghost positions if tx fails.
            // 
            // ⚡ MEV PROTECTION: 
            // Priority 1: Jupiter Ultra (built-in Beam protection)
            // Priority 2: Jito bundles
            // Priority 3: Normal RPC
            val useJito = c.jitoEnabled && !quote.isUltra  // Don't use Jito if Ultra
            val jitoTip = c.jitoTipLamports
            
            if (quote.isUltra) {
                onLog("🚀 Broadcasting via Jupiter Ultra (Beam MEV protection)…", ts.mint)
            } else if (useJito) {
                onLog("⚡ Broadcasting buy tx via Jito MEV protection…", ts.mint)
            } else {
                onLog("Broadcasting buy tx…", ts.mint)
            }
            
            // Pass Ultra requestId if available for optimal execution
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            val sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey)
            val qty   = quote.outAmount.toDouble() / tokenScale(quote.outAmount)
            val price = ts.ref

            // Single position enforcement (re-check after await)
            if (ts.position.isOpen) {
                onLog("⚠ Position opened during confirmation wait — aborting duplicate", ts.mint); return
            }

            ts.position = Position(
                qtyToken     = qty,
                entryPrice   = price,
                entryTime    = System.currentTimeMillis(),
                costSol      = sol,
                highestPrice = price,
                entryPhase   = ts.phase,
                entryScore   = score,
                entryLiquidityUsd = ts.lastLiquidityUsd,  // Track liquidity for collapse detection
            )
            val trade = Trade("BUY", "live", sol, price, System.currentTimeMillis(),
                              score = score, sig = sig)
            ts.trades.add(trade)
            security.recordTrade(trade)
            
            // 🎵 Homer Simpson "Woohoo!"
            sounds?.playBuySound()
            
            // ═══════════════════════════════════════════════════════════════════
            // TRADE IDENTITY: Mark as executed and monitoring
            // ═══════════════════════════════════════════════════════════════════
            tradeId.executed(price, sol, isPaper = false, signature = sig)
            tradeId.monitoring()
            
            // ═══════════════════════════════════════════════════════════════════
            // LIFECYCLE: EXECUTED → MONITORING (LIVE) - use identity.mint
            // ═══════════════════════════════════════════════════════════════════
            TradeLifecycle.executed(tradeId.mint, price, sol)
            TradeLifecycle.monitoring(tradeId.mint, 0.0)

            // ═══════════════════════════════════════════════════════════════════
            // EDGE LEARNING: Record entry state for later learning
            // ═══════════════════════════════════════════════════════════════════
            EdgeLearning.recordEntry(
                mint = tradeId.mint,
                symbol = tradeId.symbol,
                buyPct = ts.meta.pressScore,
                volumeScore = ts.meta.volScore,
                phase = ts.phase,
                edgeQuality = quality,  // Use canonical quality from decision
                wasVetoed = false,  // If we got here, we weren't vetoed
                vetoReason = null,
                entryPrice = price,
                isPaperMode = false,
            )
            
            // ═══════════════════════════════════════════════════════════════════
            // ENTRY INTELLIGENCE: Record entry conditions for learning
            // ═══════════════════════════════════════════════════════════════════
            val entryConditionsLive = EntryIntelligence.EntryConditions(
                buyPressure = ts.meta.pressScore,
                volumeScore = ts.meta.volScore,
                priceVsEma = ts.meta.posInRange - 50.0,
                rsi = ts.meta.rsi,
                momentum = ts.entryScore,
                hourOfDay = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).get(java.util.Calendar.HOUR_OF_DAY),
                volatility = ts.meta.avgAtr,
                liquidityUsd = ts.lastLiquidityUsd,
                topHolderPct = ts.safety.topHolderPct,
                isNearSupport = ts.meta.posInRange < 25.0,
                isNearResistance = ts.meta.posInRange > 75.0,
                candlePattern = "none",
            )
            EntryIntelligence.recordEntry(tradeId.mint, entryConditionsLive)
            
            // ═══════════════════════════════════════════════════════════════════
            // LIQUIDITY DEPTH AI: Record entry liquidity for change tracking
            // ═══════════════════════════════════════════════════════════════════
            LiquidityDepthAI.recordEntryLiquidity(tradeId.mint, ts.lastLiquidityUsd)
            
            onLog("LIVE BUY  @ ${price.fmt()} | ${sol.fmt(4)} SOL | " +
                  "impact=${quote.priceImpactPct.fmt(2)}% | sig=${sig.take(16)}…", tradeId.mint)
            onNotify("✅ Live Buy", "${tradeId.symbol}  ${sol.fmt(3)} SOL", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            // 🔔 TOAST: Immediate visual feedback for live buy
            onToast("✅ LIVE BUY: ${tradeId.symbol}\n${sol.fmt(4)} SOL @ ${price.fmt()}")

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            ErrorLogger.error("Trade", "Live buy FAILED for ${tradeId.symbol}: $safe", e)
            onLog("Live buy FAILED: $safe", tradeId.mint)
            onNotify("⚠️ Buy Failed", "${tradeId.symbol}: ${safe.take(80)}", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            // 🔔 TOAST: Immediate visual feedback for failed buy
            onToast("❌ BUY FAILED: ${tradeId.symbol}\n${safe.take(50)}")
        }
    }

    // ── sell ──────────────────────────────────────────────────────────

    private fun doSell(ts: TokenState, reason: String,
                       wallet: SolanaWallet?, walletSol: Double,
                       identity: TradeIdentity? = null) {
        // Get or create canonical identity
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        if (cfg().paperMode || wallet == null) paperSell(ts, reason, tradeId)
        else liveSell(ts, reason, wallet, walletSol, tradeId)
    }

    fun paperSell(ts: TokenState, reason: String, identity: TradeIdentity? = null) {
        // ═══════════════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Use canonical identity for consistent tracking
        // ═══════════════════════════════════════════════════════════════════════════
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val pos   = ts.position
        val price = ts.ref
        if (!pos.isOpen || price == 0.0) return
        val value = pos.qtyToken * price
        val pnl   = value - pos.costSol
        val pnlP  = pct(pos.costSol, value)
        val trade = Trade("SELL", "paper", pos.costSol, price,
                          System.currentTimeMillis(), reason, pnl, pnlP)
        ts.trades.add(trade)
        security.recordTrade(trade)
        
        // Update paper wallet balance (add sale proceeds)
        onPaperBalanceChange?.invoke(value)
        
        // Use identity for consistent logging
        onLog("PAPER SELL @ ${price.fmt()} | $reason | pnl ${pnl.fmt(4)} SOL (${pnlP.fmtPct()})", tradeId.mint)
        onNotify("📉 Paper Sell", "${tradeId.symbol}  $reason  PnL ${pnlP.fmtPct()}", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        // Play trade sound
        if (pnl > 0) sounds?.playCashRegister() else sounds?.playWarningSiren()
        // Milestone sounds while still holding (for live mode this fires on sell)
        if (pnl > 0) sounds?.playMilestone(pnlP)
        SmartSizer.recordTrade(pnl > 0, isPaperMode = true)  // Paper trade

        // ═══════════════════════════════════════════════════════════════════
        // TRADE OUTCOME CLASSIFICATION (TIGHTENED)
        // 
        // CRITICAL: Only learn from MEANINGFUL outcomes:
        //   - +5%+ winners = real signal
        //   - -5%+ losers = real signal  
        //   - Everything else = noise, skip learning
        // 
        // BEFORE: -2% to +2% scratch, -2% to -10% small loss (learned from)
        // AFTER: Only learn from ±5%+ trades - no more garbage data
        // ═══════════════════════════════════════════════════════════════════
        
        val holdTimeMins = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
        val tradeClassification = when {
            pnlP >= 5.0 -> "WIN"              // Real winner - LEARN
            pnlP <= -5.0 -> "LOSS"            // Real loser - LEARN
            else -> "SCRATCH"                  // Noise - DO NOT LEARN
        }
        
        // STRICT: Only learn from ±5%+ trades
        val isScratchTrade = tradeClassification == "SCRATCH"
        val shouldLearnAsLoss = tradeClassification == "LOSS"
        val shouldLearnAsWin = tradeClassification == "WIN"
        
        // Use identity for consistent logging
        ErrorLogger.info("Executor", "📊 ${tradeId.symbol} CLASSIFIED: $tradeClassification | " +
            "pnl=${pnlP.toInt()}% | hold=${holdTimeMins.toInt()}min | " +
            "learn=${if(isScratchTrade) "NO (scratch)" else "YES"}")
        
        // Record bad behaviour observations ONLY for meaningful losses
        if (shouldLearnAsLoss) {
            val fanName = ts.meta.emafanAlignment
            val ph      = ts.position.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }

            // Record the phase+ema combo as a bad observation
            tradeDb?.recordBadObservation(
                featureKey    = "phase=${ph}+ema=${fanName}",
                behaviourType = "ENTRY_SIGNAL",
                description   = "Loss on $ph + $fanName — pnl=${pnlP.toInt()}%",
                lossPct       = pnlP,
            )
            // Record source if it contributed
            if (src != "UNKNOWN") tradeDb?.recordBadObservation(
                featureKey    = "source=${src}",
                behaviourType = "SOURCE",
                description   = "Loss from source $src",
                lossPct       = pnlP,
            )
            // Record the exit reason as potential bad pattern
            tradeDb?.recordBadObservation(
                featureKey    = "exit_reason=${reason}",
                behaviourType = "EXIT_PATTERN",
                description   = "Exit via $reason resulted in loss",
                lossPct       = pnlP,
            )
            // Record entry score range
            val scoreRange = when {
                pos.entryScore >= 80 -> "high_80+"
                pos.entryScore >= 65 -> "medium_65-79"
                pos.entryScore >= 50 -> "low_50-64"
                else -> "very_low_<50"
            }
            tradeDb?.recordBadObservation(
                featureKey    = "entry_score_range=${scoreRange}",
                behaviourType = "SCORE_QUALITY",
                description   = "Loss with entry score ${pos.entryScore.toInt()} ($scoreRange)",
                lossPct       = pnlP,
            )
            
            // Update BotBrain in real-time — check if we should blacklist this token
            // Pass additional metrics for rolling memory system
            // PAPER MODE: isLiveTrade = false (1x weight)
            brain?.let { b ->
                val shouldBlacklist = b.learnFromTrade(
                    isWin = false, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    // Rolling memory metrics
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = false,  // Paper trade = 1x weight
                )
                // Paper mode: Still BAN tokens to build the list for live mode
                // but we don't CHECK the list in paper mode (handled in scanner/watchlist)
                if (shouldBlacklist) {
                    // Session blacklist (cleared on restart)
                    TokenBlacklist.block(ts.mint, "2+ losses on ${ts.symbol}")
                    // Permanent ban (persisted across restarts) - ALWAYS add, even in paper mode
                    BannedTokens.ban(ts.mint, "2+ losses on ${ts.symbol}")
                    if (cfg().paperMode) {
                        onLog("📝 PAPER LEARNED: ${ts.symbol} added to ban list (still trading for learning)", ts.mint)
                    } else {
                        onLog("🚫 PERMANENTLY BANNED: ${ts.symbol} after repeated losses", ts.mint)
                        onNotify("🚫 Token Banned", 
                                 "${ts.symbol}: 2+ losses — permanently banned",
                                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    }
                }
            }
            
            // Learn from bad trade in TradingMemory
            TradingMemory.learnFromBadTrade(
                mint = ts.mint,
                symbol = ts.symbol,
                lossPct = pnlP,
                phase = ph,
                emaFan = fanName,
                source = src,
                liquidity = ts.lastLiquidityUsd,
                mcap = ts.lastMcap,
                ageHours = (System.currentTimeMillis() - (ts.history.firstOrNull()?.ts ?: System.currentTimeMillis())) / 3_600_000.0,
                hadSocials = false,  // Not tracked in current model
                isPumpFun = ts.source.contains("pump", ignoreCase = true),
                volumeToLiqRatio = if (ts.lastLiquidityUsd > 0) ts.history.lastOrNull()?.vol?.div(ts.lastLiquidityUsd) ?: 0.0 else 0.0,
            )
            onLog("🤖 AI LEARNED: Loss on ${ts.symbol} | phase=$ph ema=$fanName | Pattern recorded", ts.mint)
        } else if (shouldLearnAsWin) {
            // Meaningful win — let the brain know this pattern is working
            val fanName = ts.meta.emafanAlignment
            val ph      = ts.position.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }
            tradeDb?.recordGoodObservation("phase=${ph}+ema=${fanName}")
            tradeDb?.recordGoodObservation("source=${src}")
            
            // Update BotBrain in real-time
            // Pass additional metrics for rolling memory system
            // PAPER MODE: isLiveTrade = false (1x weight)
            brain?.let { b ->
                b.learnFromTrade(
                    isWin = true, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    // Rolling memory metrics
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = false,  // Paper trade = 1x weight
                )
            }
            
            // Learn from winning trade in TradingMemory
            TradingMemory.learnFromWinningTrade(
                mint = ts.mint,
                symbol = ts.symbol,
                winPct = pnlP,
                phase = ph,
                emaFan = fanName,
                source = src,
                holdTimeMinutes = holdTimeMins,
            )
            onLog("🤖 AI LEARNED: Win on ${ts.symbol} +${pnlP.toInt()}% | Pattern reinforced", ts.mint)
        } else {
            // SCRATCH trade - near breakeven, not meaningful for learning
            onLog("📊 ${ts.symbol}: Scratch trade (${pnlP.toInt()}%) - skipped for learning", ts.mint)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // ADAPTIVE THRESHOLD LEARNING
        // Teach BotBrain what rugcheck/pressure/holder levels lead to wins/losses
        // ═══════════════════════════════════════════════════════════════════
        if (shouldLearnAsWin || shouldLearnAsLoss) {
            brain?.learnThreshold(
                isWin = shouldLearnAsWin,
                rugcheckScore = ts.safety.rugcheckScore,
                buyPressure = ts.meta.pressScore,
                topHolderPct = ts.safety.topHolderPct,
                liquidityUsd = ts.lastLiquidityUsd,
                pnlPct = pnlP,
            )
        }

        // Determine win/loss/scratch for database record
        // Uses the already-defined isScratchTrade from classification above
        val dbIsWin = when {
            isScratchTrade -> null  // Scratch trades are neither win nor loss
            pnlP > 5.0 -> true      // Clear win (using 5% threshold)
            else -> false           // Clear loss
        }
        
        tradeDb?.insertTrade(TradeRecord(
            tsEntry=ts.position.entryTime, tsExit=System.currentTimeMillis(),
            symbol=ts.symbol, mint=ts.mint,
            mode=if(ts.position.entryPhase.contains("pump")) "LAUNCH" else "RANGE",
            entryPrice=ts.position.entryPrice, entryScore=ts.position.entryScore,
            entryPhase=ts.position.entryPhase, emaFan=ts.meta.emafanAlignment,
            volScore=ts.meta.volScore, pressScore=ts.meta.pressScore, momScore=ts.meta.momScore,
            holderCount=ts.history.lastOrNull()?.holderCount?:0,
            holderGrowth=ts.holderGrowthRate, liquidityUsd=ts.lastLiquidityUsd, mcapUsd=ts.lastMcap,
            exitPrice=price, exitPhase=ts.phase, exitReason=reason,
            heldMins=(System.currentTimeMillis()-ts.position.entryTime)/60_000.0,
            topUpCount=ts.position.topUpCount, partialSold=ts.position.partialSoldPct,
            solIn=ts.position.costSol, solOut=value, pnlSol=pnl, pnlPct=pnlP, 
            isWin=dbIsWin,  // null for scratch, true for win, false for loss
            isScratch=isScratchTrade,  // Flag for UI filtering
        ))
        
        // ═══════════════════════════════════════════════════════════════════
        // ADAPTIVE LEARNING: Capture features and learn from trade
        // ═══════════════════════════════════════════════════════════════════
        try {
            val holdMins = (System.currentTimeMillis() - ts.position.entryTime) / 60_000.0
            // Compute token age from when it was added to watchlist (proxy for discovery time)
            val tokenAgeMins = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
            val features = AdaptiveLearningEngine.captureFeatures(
                entryMcapUsd = ts.position.entryLiquidityUsd * 2,  // Approximate
                tokenAgeMinutes = tokenAgeMins,
                buyRatioPct = ts.meta.pressScore,  // Use press score as buy ratio proxy
                volumeUsd = ts.lastLiquidityUsd * ts.meta.volScore / 50.0,  // Approximate
                liquidityUsd = ts.lastLiquidityUsd,
                holderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                topHolderPct = ts.safety.topHolderPct,
                holderGrowthRate = ts.holderGrowthRate,
                devWalletPct = 0.0,  // Not tracked in SafetyReport
                bondingCurveProgress = 100.0,  // Default to graduated
                rugcheckScore = ts.safety.rugcheckScore.toDouble(),
                emaFanState = ts.meta.emafanAlignment,
                entryScore = ts.position.entryScore,
                priceFromAth = if (ts.position.highestPrice > 0) 
                    ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice * 100) else 0.0,
                pnlPct = pnlP,
                maxGainPct = if (ts.position.entryPrice > 0) 
                    ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice * 100) else 0.0,
                maxDrawdownPct = 0.0,  // Not tracked in Position
                timeToPeakMins = holdMins * 0.5,  // Estimate
                holdTimeMins = holdMins,
                exitReason = reason,
                // PRIORITY 3: Pass entry phase for improved classification
                entryPhase = ts.position.entryPhase,
            )
            AdaptiveLearningEngine.learnFromTrade(features)
            
            // Scanner learning - track what discovery characteristics produce wins
            // ONLY record meaningful outcomes, NOT scratch trades
            if (shouldLearnAsWin || shouldLearnAsLoss) {
                val tokenAgeHours = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 3_600_000.0
                ScannerLearning.recordTrade(
                    source = ts.source.ifEmpty { "UNKNOWN" },
                    liqUsd = ts.lastLiquidityUsd,
                    ageHours = tokenAgeHours,
                    isWin = shouldLearnAsWin  // Use classified outcome, not raw pnl
                )
            } else {
                ErrorLogger.debug("ScannerLearning", "Skipped scratch trade for ${ts.symbol} (pnl=${pnlP.toInt()}%)")
            }
        } catch (e: Exception) {
            ErrorLogger.debug("AdaptiveLearning", "Feature capture error: ${e.message}")
        }
        // ═══════════════════════════════════════════════════════════════════
        
        // ═══════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Update canonical identity state
        // ═══════════════════════════════════════════════════════════════════
        val classification = when {
            isScratchTrade -> "SCRATCH"
            shouldLearnAsWin -> "WIN"
            shouldLearnAsLoss -> "LOSS"
            else -> "UNKNOWN"
        }
        
        // Update TradeIdentity with close info
        tradeId.closed(price, pnlP, pnl, reason)
        tradeId.classified(classification, if (isScratchTrade) null else shouldLearnAsWin)
        
        // ═══════════════════════════════════════════════════════════════════
        // LIFECYCLE: CLOSED → CLASSIFIED (use identity.mint for consistency)
        // ═══════════════════════════════════════════════════════════════════
        TradeLifecycle.closed(tradeId.mint, price, pnlP, reason)
        TradeLifecycle.classified(tradeId.mint, classification, if (isScratchTrade) null else shouldLearnAsWin)
        
        // ═══════════════════════════════════════════════════════════════════
        // DISTRIBUTION COOLDOWN: Record if exited due to distribution
        // This prevents the buy→dump→buy→dump loop
        // ═══════════════════════════════════════════════════════════════════
        if (reason.lowercase().contains("distribution")) {
            FinalDecisionGate.recordDistributionExit(tradeId.mint)
            onLog("🚫 Distribution cooldown: ${ts.symbol} blocked for 4min", tradeId.mint)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // EDGE LEARNING: Learn from trade outcome to adjust thresholds
        // ═══════════════════════════════════════════════════════════════════
        EdgeLearning.learnFromOutcome(
            mint = tradeId.mint,
            exitPrice = price,
            pnlPercent = pnlP,
            wasExecuted = true,
        )
        
        // ═══════════════════════════════════════════════════════════════════
        // ENTRY INTELLIGENCE: Learn from trade outcome
        // ═══════════════════════════════════════════════════════════════════
        val holdMinutes = ((System.currentTimeMillis() - ts.position.entryTime) / 60000).toInt()
        EntryIntelligence.learnFromOutcome(tradeId.mint, pnlP, holdMinutes)
        
        // ═══════════════════════════════════════════════════════════════════
        // EXIT INTELLIGENCE: Learn from exit decision quality
        // ═══════════════════════════════════════════════════════════════════
        ExitIntelligence.learnFromExit(tradeId.mint, reason, pnlP, holdMinutes)
        ExitIntelligence.resetPosition(tradeId.mint)
        
        // ═══════════════════════════════════════════════════════════════════
        // NEW AI LAYERS: Record trade outcome for learning
        // ═══════════════════════════════════════════════════════════════════
        
        // WhaleTrackerAI: Learn from whale signal accuracy
        try {
            val wasSignalCorrect = when {
                pnlP > 5.0 -> true   // Win
                pnlP < -5.0 -> false // Loss
                else -> null         // Scratch - don't learn
            }
            if (wasSignalCorrect != null) {
                WhaleTrackerAI.recordSignalOutcome(tradeId.mint, wasSignalCorrect, pnlP)
            }
        } catch (_: Exception) {}
        
        // MarketRegimeAI: Record trade outcome for regime performance tracking
        try {
            if (kotlin.math.abs(pnlP) >= 5.0) {  // Only meaningful trades
                MarketRegimeAI.recordTradeOutcome(pnlP)
            }
        } catch (_: Exception) {}
        
        // MomentumPredictorAI: Learn from momentum prediction accuracy
        try {
            val peakPnlPct = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else 0.0
            MomentumPredictorAI.recordOutcome(tradeId.mint, pnlP, peakPnlPct)
        } catch (_: Exception) {}
        
        // NarrativeDetectorAI: Learn which narratives are performing
        try {
            NarrativeDetectorAI.recordOutcome(ts.symbol, ts.name, pnlP)
        } catch (_: Exception) {}
        
        // TimeOptimizationAI: Learn which hours are most profitable
        try {
            TimeOptimizationAI.recordOutcome(pnlP)
        } catch (_: Exception) {}
        
        // LiquidityDepthAI: Learn which liquidity patterns are profitable
        try {
            LiquidityDepthAI.recordOutcome(ts.mint, pnlP, pnlP > 0)
            LiquidityDepthAI.clearEntryLiquidity(ts.mint)  // Clean up entry reference
        } catch (_: Exception) {}
        
        // AICrossTalk: Learn which correlation patterns are profitable
        try {
            val crossTalkSignal = AICrossTalk.analyzeCrossTalk(ts.mint, ts.symbol, isOpenPosition = false)
            if (crossTalkSignal.signalType != AICrossTalk.SignalType.NO_CORRELATION) {
                AICrossTalk.recordOutcome(crossTalkSignal.signalType, pnlP, pnlP > 0)
            }
        } catch (_: Exception) {}
        
        // ═══════════════════════════════════════════════════════════════════
        // TOKEN WIN MEMORY: Remember winning tokens and learn patterns
        // ═══════════════════════════════════════════════════════════════════
        try {
            val peakPnl = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else pnlP
            
            // Get buy pressure from history
            val latestBuyPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
            
            // Approximate entry mcap from liquidity (typical ratio ~2x)
            val approxEntryMcap = ts.position.entryLiquidityUsd * 2
            
            TokenWinMemory.recordTradeOutcome(
                mint = tradeId.mint,
                symbol = ts.symbol,
                name = ts.name,
                pnlPercent = pnlP,
                peakPnl = peakPnl,
                entryMcap = approxEntryMcap,
                exitMcap = ts.lastMcap,
                entryLiquidity = ts.position.entryLiquidityUsd,
                holdTimeMinutes = holdMinutes,
                buyPercent = latestBuyPct,
                source = ts.source,
                phase = ts.position.entryPhase,
            )
        } catch (_: Exception) {}
        
        ts.position         = Position()
        ts.lastExitTs       = System.currentTimeMillis()
        ts.lastExitPrice    = price
        ts.lastExitPnlPct   = pnlP
        ts.lastExitWasWin   = pnlP > 2.0  // Only clear wins, not scratch trades

        // Notify shadow learning engine - but skip scratch trades
        if (!isScratchTrade) {
            ShadowLearningEngine.onLiveTradeExit(
                mint = tradeId.mint,
                exitPrice = price,
                exitReason = reason,
                livePnlSol = pnl,
                isWin = pnlP > 2.0,
            )
        }
    }

    private fun liveSell(ts: TokenState, reason: String,
                         wallet: SolanaWallet, walletSol: Double,
                         identity: TradeIdentity? = null) {
        // ═══════════════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Use canonical identity for consistent tracking
        // ═══════════════════════════════════════════════════════════════════════════
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val c   = cfg()
        val pos = ts.position
        if (!pos.isOpen) return

        // Keypair integrity check
        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair integrity failure — sell aborted", tradeId.mint)
            return
        }

        val tokenUnits = (pos.qtyToken * 1_000_000_000.0).toLong().coerceAtLeast(1L)

        var pnl  = 0.0   // hoisted — needed after try block
        var pnlP = 0.0

        try {
            val quote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT,
                                                   tokenUnits, c.slippageBps, isBuy = false)

            // Validate quote — for sells, log warning but proceed
            val qGuard = security.validateQuote(quote, isBuy = false, inputSol = pos.costSol)
            if (qGuard is GuardResult.Block) {
                onLog("⚠ Sell quote warning: ${qGuard.reason} — proceeding anyway", ts.mint)
            }

            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            security.enforceSignDelay()
            
            // ⚡ MEV PROTECTION: 
            // Priority 1: Jupiter Ultra (built-in Beam protection)
            // Priority 2: Jito bundles
            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            
            if (quote.isUltra) {
                onLog("🚀 Broadcasting sell via Jupiter Ultra (Beam MEV protection)…", ts.mint)
            } else if (useJito) {
                onLog("⚡ Broadcasting sell tx via Jito MEV protection…", ts.mint)
            } else {
                onLog("Broadcasting sell tx…", ts.mint)
            }
            
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            val sig     = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey)
            val price   = ts.ref
            val solBack = quote.outAmount / 1_000_000_000.0
            pnl  = solBack - pos.costSol
            pnlP = pct(pos.costSol, solBack)
            val (netPnl, feeSol) = slippageGuard.calcNetPnl(pnl, pos.costSol)

            val trade = Trade("SELL", "live", pos.costSol, price,
                              System.currentTimeMillis(), reason, pnl, pnlP, sig = sig,
                              feeSol = feeSol, netPnlSol = netPnl)
            ts.trades.add(trade)
            security.recordTrade(trade)

            SmartSizer.recordTrade(pnl > 0, isPaperMode = false)  // Live trade
            
            // FIX #5: Lock realized profit to treasury (LIVE mode only)
            if (pnl > 0) {
                val solPrice = WalletManager.lastKnownSolPrice
                TreasuryManager.lockRealizedProfit(pnl, solPrice)
            }

            onLog("LIVE SELL @ ${price.fmt()} | $reason | pnl ${pnl.fmt(4)} SOL " +
                  "(${pnlP.fmtPct()}) | sig=${sig.take(16)}…", ts.mint)
            onNotify("✅ Live Sell",
                "${ts.symbol}  $reason  PnL ${pnlP.fmtPct()}",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            // 🔔 TOAST: Immediate visual feedback for live sell
            val emoji = if (pnlP >= 0) "✅" else "📉"
            onToast("$emoji LIVE SELL: ${ts.symbol}\nPnL: ${pnlP.fmtPct()} (${pnl.fmt(4)} SOL)")

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            onLog("Live sell FAILED: $safe — will retry next tick", ts.mint)
            onNotify("⚠️ Sell Failed",
                "${ts.symbol}: ${safe.take(80)}",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            // 🔔 TOAST: Immediate visual feedback for failed sell
            onToast("❌ SELL FAILED: ${ts.symbol}\n${safe.take(50)}")
            return  // don't clear position — retry next tick
        }

        // pnl/pnlP are now valid (try succeeded, otherwise we returned above)
        val exitPrice = ts.ref  // capture before position reset clears it
        
        // ═══════════════════════════════════════════════════════════════════
        // TRADE OUTCOME CLASSIFICATION (TIGHTENED - same as paperSell)
        // Only learn from ±5%+ trades - no more garbage data
        // ═══════════════════════════════════════════════════════════════════
        val holdTimeMins = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
        val tradeClassification = when {
            pnlP >= 5.0 -> "WIN"              // Real winner - LEARN
            pnlP <= -5.0 -> "LOSS"            // Real loser - LEARN
            else -> "SCRATCH"                  // Noise - DO NOT LEARN
        }
        
        val isScratchTradeLive = tradeClassification == "SCRATCH"
        val shouldLearnAsLoss = tradeClassification == "LOSS"
        val shouldLearnAsWin = tradeClassification == "WIN"
        
        // Use tradeId for consistent logging
        ErrorLogger.info("Executor", "📊 LIVE ${tradeId.symbol} CLASSIFIED: $tradeClassification | " +
            "pnl=${pnlP.toInt()}% | hold=${holdTimeMins.toInt()}min | " +
            "learn=${if(isScratchTradeLive) "NO (scratch)" else "YES"}")
        
        // Record bad behaviour observations for MEANINGFUL losing trades only
        if (shouldLearnAsLoss) {
            val fanName = ts.meta.emafanAlignment
            val ph      = pos.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }

            // Record the phase+ema combo as a bad observation
            tradeDb?.recordBadObservation(
                featureKey    = "phase=${ph}+ema=${fanName}",
                behaviourType = "ENTRY_SIGNAL",
                description   = "Loss on $ph + $fanName — pnl=${pnlP.toInt()}%",
                lossPct       = pnlP,
            )
            // Record source if it contributed
            if (src != "UNKNOWN") tradeDb?.recordBadObservation(
                featureKey    = "source=${src}",
                behaviourType = "SOURCE",
                description   = "Loss from source $src",
                lossPct       = pnlP,
            )
            // Record the exit reason as potential bad pattern
            tradeDb?.recordBadObservation(
                featureKey    = "exit_reason=${reason}",
                behaviourType = "EXIT_PATTERN",
                description   = "Exit via $reason resulted in loss",
                lossPct       = pnlP,
            )

            // Update BotBrain — check if we should blacklist this token
            // Pass additional metrics for rolling memory system
            // LIVE MODE: isLiveTrade = true (3x weight!)
            brain?.let { b ->
                val shouldBlacklist = b.learnFromTrade(
                    isWin = false, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    // Rolling memory metrics
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = true,  // LIVE trade = 3x weight!
                )
                // Paper mode: Still BAN tokens to build the list for live mode
                // but we don't CHECK the list in paper mode (handled in scanner/watchlist)
                if (shouldBlacklist) {
                    // Session blacklist (cleared on restart)
                    TokenBlacklist.block(ts.mint, "2+ losses on ${ts.symbol}")
                    // Permanent ban (persisted across restarts) - ALWAYS add, even in paper mode
                    BannedTokens.ban(ts.mint, "2+ losses on ${ts.symbol}")
                    if (cfg().paperMode) {
                        onLog("📝 PAPER LEARNED: ${ts.symbol} added to ban list (still trading for learning)", ts.mint)
                    } else {
                        onLog("🚫 PERMANENTLY BANNED: ${ts.symbol} after repeated losses", ts.mint)
                        onNotify("🚫 Token Banned", 
                                 "${ts.symbol}: 2+ losses — permanently banned",
                                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    }
                }
            }
        } else if (shouldLearnAsWin) {
            // Meaningful win — let the brain know this pattern is working
            val fanName = ts.meta.emafanAlignment
            val ph      = pos.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }
            tradeDb?.recordGoodObservation("phase=${ph}+ema=${fanName}")
            tradeDb?.recordGoodObservation("source=${src}")
            
            // Update BotBrain for winning trade
            // Pass additional metrics for rolling memory system
            // LIVE MODE: isLiveTrade = true (3x weight!)
            brain?.let { b ->
                b.learnFromTrade(
                    isWin = true, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    // Rolling memory metrics
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = true,  // LIVE trade = 3x weight!
                )
            }
        } else {
            // SCRATCH trade - not meaningful for learning
            ErrorLogger.debug("Executor", "LIVE ${ts.symbol}: Scratch trade (${pnlP.toInt()}%) - skipped for learning")
        }

        // Determine win/loss/scratch for database record (uses already-defined isScratchTradeLive)
        val dbIsWinLive = when {
            isScratchTradeLive -> null  // Scratch trades are neither win nor loss
            pnlP > 5.0 -> true      // Clear win (using 5% threshold)
            else -> false           // Clear loss
        }
        
        // Record trade to database
        tradeDb?.insertTrade(TradeRecord(
            tsEntry=pos.entryTime, tsExit=System.currentTimeMillis(),
            symbol=ts.symbol, mint=ts.mint,
            mode=if(pos.entryPhase.contains("pump")) "LAUNCH" else "RANGE",
            entryPrice=pos.entryPrice, entryScore=pos.entryScore,
            entryPhase=pos.entryPhase, emaFan=ts.meta.emafanAlignment,
            volScore=ts.meta.volScore, pressScore=ts.meta.pressScore, momScore=ts.meta.momScore,
            holderCount=ts.history.lastOrNull()?.holderCount?:0,
            holderGrowth=ts.holderGrowthRate, liquidityUsd=ts.lastLiquidityUsd, mcapUsd=ts.lastMcap,
            exitPrice=exitPrice, exitPhase=ts.phase, exitReason=reason,
            heldMins=(System.currentTimeMillis()-pos.entryTime)/60_000.0,
            topUpCount=pos.topUpCount, partialSold=pos.partialSoldPct,
            solIn=pos.costSol, solOut=pnl + pos.costSol, pnlSol=pnl, pnlPct=pnlP, 
            isWin=dbIsWinLive,
            isScratch=isScratchTradeLive,
        ))

        // ═══════════════════════════════════════════════════════════════════
        // ADAPTIVE LEARNING: Capture features and learn from LIVE trade
        // ═══════════════════════════════════════════════════════════════════
        try {
            val holdMins = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
            val tokenAgeMins = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
            val features = AdaptiveLearningEngine.captureFeatures(
                entryMcapUsd = pos.entryLiquidityUsd * 2,
                tokenAgeMinutes = tokenAgeMins,
                buyRatioPct = ts.meta.pressScore,
                volumeUsd = ts.lastLiquidityUsd * ts.meta.volScore / 50.0,
                liquidityUsd = ts.lastLiquidityUsd,
                holderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                topHolderPct = ts.safety.topHolderPct,
                holderGrowthRate = ts.holderGrowthRate,
                devWalletPct = 0.0,  // Not tracked
                bondingCurveProgress = 100.0,
                rugcheckScore = ts.safety.rugcheckScore.toDouble(),
                emaFanState = ts.meta.emafanAlignment,
                entryScore = pos.entryScore,
                priceFromAth = if (pos.highestPrice > 0) 
                    ((pos.highestPrice - pos.entryPrice) / pos.entryPrice * 100) else 0.0,
                pnlPct = pnlP,
                maxGainPct = if (pos.entryPrice > 0) 
                    ((pos.highestPrice - pos.entryPrice) / pos.entryPrice * 100) else 0.0,
                maxDrawdownPct = 0.0,
                timeToPeakMins = holdMins * 0.5,
                holdTimeMins = holdMins,
                exitReason = reason,
                // PRIORITY 3: Pass entry phase for improved classification
                entryPhase = pos.entryPhase,
            )
            AdaptiveLearningEngine.learnFromTrade(features)
            
            // Scanner learning - track what discovery characteristics produce wins
            // ONLY record meaningful outcomes, NOT scratch trades
            if (shouldLearnAsWin || shouldLearnAsLoss) {
                val tokenAgeHours2 = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 3_600_000.0
                ScannerLearning.recordTrade(
                    source = ts.source.ifEmpty { "UNKNOWN" },
                    liqUsd = ts.lastLiquidityUsd,
                    ageHours = tokenAgeHours2,
                    isWin = shouldLearnAsWin  // Use classified outcome, not raw pnl
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug("AdaptiveLearning", "Feature capture error: ${e.message}")
        }
        // ═══════════════════════════════════════════════════════════════════

        // Notify shadow learning engine - skip scratch trades
        if (!isScratchTradeLive) {
            ShadowLearningEngine.onLiveTradeExit(
                mint = tradeId.mint,
                exitPrice = exitPrice,
                exitReason = reason,
                livePnlSol = pnl,
                isWin = pnlP > 2.0,
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Update canonical identity state
        // ═══════════════════════════════════════════════════════════════════
        val classificationLive = when {
            isScratchTradeLive -> "SCRATCH"
            shouldLearnAsWin -> "WIN"
            shouldLearnAsLoss -> "LOSS"
            else -> "UNKNOWN"
        }
        
        // Update TradeIdentity with close info
        tradeId.closed(exitPrice, pnlP, pnl, reason)
        tradeId.classified(classificationLive, if (isScratchTradeLive) null else shouldLearnAsWin)
        
        // ═══════════════════════════════════════════════════════════════════
        // LIFECYCLE: CLOSED → CLASSIFIED (LIVE) - use identity.mint for consistency
        // ═══════════════════════════════════════════════════════════════════
        TradeLifecycle.closed(tradeId.mint, exitPrice, pnlP, reason)
        TradeLifecycle.classified(tradeId.mint, classificationLive, if (isScratchTradeLive) null else shouldLearnAsWin)
        
        // ═══════════════════════════════════════════════════════════════════
        // DISTRIBUTION COOLDOWN: Record if exited due to distribution
        // This prevents the buy→dump→buy→dump loop
        // ═══════════════════════════════════════════════════════════════════
        if (reason.lowercase().contains("distribution")) {
            FinalDecisionGate.recordDistributionExit(tradeId.mint)
            onLog("🚫 Distribution cooldown: ${ts.symbol} blocked for 4min", tradeId.mint)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // ENTRY & EXIT INTELLIGENCE: Learn from live trade outcome
        // ═══════════════════════════════════════════════════════════════════
        val holdMinutesLive = ((System.currentTimeMillis() - ts.position.entryTime) / 60000).toInt()
        EntryIntelligence.learnFromOutcome(tradeId.mint, pnlP, holdMinutesLive)
        ExitIntelligence.learnFromExit(tradeId.mint, reason, pnlP, holdMinutesLive)
        ExitIntelligence.resetPosition(tradeId.mint)
        
        // ═══════════════════════════════════════════════════════════════════
        // NEW AI LAYERS: Record trade outcome for learning (LIVE trades - 3x weight!)
        // ═══════════════════════════════════════════════════════════════════
        
        // WhaleTrackerAI: Learn from whale signal accuracy
        try {
            val wasSignalCorrect = when {
                pnlP > 5.0 -> true   // Win
                pnlP < -5.0 -> false // Loss
                else -> null         // Scratch - don't learn
            }
            if (wasSignalCorrect != null) {
                WhaleTrackerAI.recordSignalOutcome(tradeId.mint, wasSignalCorrect, pnlP)
            }
        } catch (_: Exception) {}
        
        // MarketRegimeAI: Record trade outcome for regime performance tracking
        try {
            if (kotlin.math.abs(pnlP) >= 5.0) {  // Only meaningful trades
                MarketRegimeAI.recordTradeOutcome(pnlP)
            }
        } catch (_: Exception) {}
        
        // MomentumPredictorAI: Learn from momentum prediction accuracy
        try {
            val peakPnlPctLive = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else 0.0
            MomentumPredictorAI.recordOutcome(tradeId.mint, pnlP, peakPnlPctLive)
        } catch (_: Exception) {}
        
        // NarrativeDetectorAI: Learn which narratives are performing (LIVE trades - 3x weight!)
        try {
            // Record 3x for live trades since they're more important
            NarrativeDetectorAI.recordOutcome(ts.symbol, ts.name, pnlP)
            NarrativeDetectorAI.recordOutcome(ts.symbol, ts.name, pnlP)
            NarrativeDetectorAI.recordOutcome(ts.symbol, ts.name, pnlP)
        } catch (_: Exception) {}
        
        // TimeOptimizationAI: Learn which hours are most profitable (LIVE trades - 3x weight!)
        try {
            // Record 3x for live trades
            TimeOptimizationAI.recordOutcome(pnlP)
            TimeOptimizationAI.recordOutcome(pnlP)
            TimeOptimizationAI.recordOutcome(pnlP)
        } catch (_: Exception) {}
        
        // LiquidityDepthAI: Learn which liquidity patterns are profitable (LIVE trades - 3x weight!)
        try {
            // Record 3x for live trades
            LiquidityDepthAI.recordOutcome(ts.mint, pnlP, pnl > 0)
            LiquidityDepthAI.recordOutcome(ts.mint, pnlP, pnl > 0)
            LiquidityDepthAI.recordOutcome(ts.mint, pnlP, pnl > 0)
            LiquidityDepthAI.clearEntryLiquidity(ts.mint)  // Clean up entry reference
        } catch (_: Exception) {}
        
        // AICrossTalk: Learn which correlation patterns are profitable (LIVE trades - 3x weight!)
        try {
            val crossTalkSignal = AICrossTalk.analyzeCrossTalk(ts.mint, ts.symbol, isOpenPosition = false)
            if (crossTalkSignal.signalType != AICrossTalk.SignalType.NO_CORRELATION) {
                // Record 3x for live trades
                AICrossTalk.recordOutcome(crossTalkSignal.signalType, pnlP, pnl > 0)
                AICrossTalk.recordOutcome(crossTalkSignal.signalType, pnlP, pnl > 0)
                AICrossTalk.recordOutcome(crossTalkSignal.signalType, pnlP, pnl > 0)
            }
        } catch (_: Exception) {}
        
        // ═══════════════════════════════════════════════════════════════════
        // TOKEN WIN MEMORY: Remember winning tokens and learn patterns (LIVE - 3x weight!)
        // ═══════════════════════════════════════════════════════════════════
        try {
            val peakPnlLive = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else pnlP
            
            // Get buy pressure from history
            val latestBuyPctLive = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
            
            // Approximate entry mcap from liquidity (typical ratio ~2x)
            val approxEntryMcapLive = ts.position.entryLiquidityUsd * 2
            
            // Record 3x for live trades (more valuable data)
            repeat(3) {
                TokenWinMemory.recordTradeOutcome(
                    mint = tradeId.mint,
                    symbol = ts.symbol,
                    name = ts.name,
                    pnlPercent = pnlP,
                    peakPnl = peakPnlLive,
                    entryMcap = approxEntryMcapLive,
                    exitMcap = ts.lastMcap,
                    entryLiquidity = ts.position.entryLiquidityUsd,
                    holdTimeMinutes = holdMinutesLive,
                    buyPercent = latestBuyPctLive,
                    source = ts.source,
                    phase = ts.position.entryPhase,
                )
            }
        } catch (_: Exception) {}
        
        ts.position         = Position()
        ts.lastExitTs       = System.currentTimeMillis()
        ts.lastExitPrice    = exitPrice
        ts.lastExitPnlPct   = pnlP
        ts.lastExitWasWin   = pnl > 0
    }

    // ── Close all positions (for bot shutdown) ────────────────────────

    /**
     * Emergency close all open positions when bot is stopping.
     * This ensures funds are returned and no positions are left dangling.
     * Called by BotService.stopBot() before shutting down.
     *
     * @param tokens Map of all tracked tokens
     * @param wallet The wallet to use for live sells (null for paper mode)
     * @param walletSol Current wallet balance
     * @param paperMode Whether we're in paper trading mode
     * @return Number of positions closed
     */
    fun closeAllPositions(
        tokens: Map<String, com.lifecyclebot.data.TokenState>,
        wallet: SolanaWallet?,
        walletSol: Double,
        paperMode: Boolean,
    ): Int {
        var closedCount = 0
        val openPositions = tokens.values.filter { it.position.isOpen }
        
        if (openPositions.isEmpty()) {
            onLog("🛑 Bot stopping — no open positions to close", "shutdown")
            return 0
        }
        
        onLog("🛑 Bot stopping — closing ${openPositions.size} open position(s)...", "shutdown")
        onNotify("🛑 Bot Stopping", 
                 "Closing ${openPositions.size} open position(s)",
                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        
        for (ts in openPositions) {
            try {
                val pos = ts.position
                if (!pos.isOpen) continue
                
                val gainPct = if (pos.entryPrice > 0) {
                    ((ts.ref - pos.entryPrice) / pos.entryPrice * 100)
                } else 0.0
                
                onLog("🔴 EMERGENCY CLOSE: ${ts.symbol} @ ${gainPct.toInt()}% gain | reason=bot_shutdown", ts.mint)
                
                if (paperMode || wallet == null) {
                    paperSell(ts, "bot_shutdown")
                } else {
                    liveSell(ts, "bot_shutdown", wallet, walletSol)
                }
                
                closedCount++
                
            } catch (e: Exception) {
                onLog("⚠️ Failed to close ${ts.symbol}: ${e.message}", ts.mint)
                // For paper mode, force close even on error
                if (paperMode) {
                    try {
                        val pos = ts.position
                        val value = pos.qtyToken * ts.ref
                        onPaperBalanceChange?.invoke(value)
                        ts.position = com.lifecyclebot.data.Position()
                        onLog("📝 Force-closed paper position: ${ts.symbol}", ts.mint)
                        closedCount++
                    } catch (_: Exception) {}
                }
            }
        }
        
        onLog("✅ Closed $closedCount/${openPositions.size} positions on shutdown", "shutdown")
        onNotify("✅ Positions Closed", 
                 "Closed $closedCount position(s) on bot shutdown",
                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        
        return closedCount
    }

    // ── Jupiter helpers ───────────────────────────────────────────────

    private fun getQuoteWithSlippageGuard(
        inMint: String, outMint: String, amount: Long, slippageBps: Int,
        inputSol: Double = 0.0,
        isBuy: Boolean = true,
    ): com.lifecyclebot.network.SwapQuote {
        // Dual-quote validation only on buys — sells should execute fast
        // (holding a position while waiting 2s for second quote is risky)
        if (!isBuy) {
            return jupiter.getQuote(inMint, outMint, amount, slippageBps)
        }
        val validated = slippageGuard.validateQuote(inMint, outMint, amount, slippageBps, inputSol)
        if (!validated.isValid) {
            throw Exception(validated.rejectReason)
        }
        return validated.quote
    }

    private fun buildTxWithRetry(
        quote: com.lifecyclebot.network.SwapQuote, pubkey: String,
    ): com.lifecyclebot.network.SwapTxResult {
        return try {
            jupiter.buildSwapTx(quote, pubkey)
        } catch (e: Exception) {
            Thread.sleep(1000)
            jupiter.buildSwapTx(quote, pubkey)
        }
    }

    private fun tokenScale(rawAmount: Long): Double =
        if (rawAmount > 500_000_000L) 1_000_000_000.0 else 1_000_000.0

    // ── Treasury withdrawal ───────────────────────────────────────────

    /**
     * Execute a treasury withdrawal — transfers SOL from bot wallet to destination.
     * SmartSizer automatically excludes treasury from tradeable balance so this
     * just moves the accounting; the SOL was always on-chain.
     */
    fun executeTreasuryWithdrawal(
        requestedSol: Double,
        destinationAddress: String,
        wallet: com.lifecyclebot.network.SolanaWallet?,
        walletSol: Double,
    ): String {
        val solPx  = WalletManager.lastKnownSolPrice
        val result = TreasuryManager.requestWithdrawalAmount(requestedSol, solPx)

        if (!result.approved) {
            onLog("🏦 Withdrawal blocked: ${result.message}", "treasury")
            return "BLOCKED: ${result.message}"
        }

        val approved = result.approvedSol
        onLog("🏦 Treasury withdrawal: ${approved.fmt(4)}◎ → ${destinationAddress.take(16)}…", "treasury")

        if (cfg().paperMode || wallet == null) {
            TreasuryManager.executeWithdrawal(approved, solPx, destinationAddress)
            onLog("PAPER TREASURY WITHDRAWAL: ${approved.fmt(4)}◎", "treasury")
            return "OK_PAPER"
        }

        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                cfg().walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair check failed — withdrawal aborted", "treasury")
            return "BLOCKED: keypair"
        }

        return try {
            val sig = wallet.sendSol(destinationAddress, approved)
            TreasuryManager.executeWithdrawal(approved, solPx, destinationAddress)
            onLog("✅ LIVE TREASURY WITHDRAWAL: ${approved.fmt(4)}◎ | sig=${sig.take(16)}…", "treasury")
            onNotify("🏦 Treasury Withdrawal",
                "Sent ${approved.fmt(4)}◎ → ${destinationAddress.take(12)}…",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            "OK:$sig"
        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            onLog("Treasury withdrawal FAILED: $safe", "treasury")
            "FAILED: $safe"
        }
    }

private fun Double.fmt(d: Int = 6) = "%.${d}f".format(this)
}
private fun Double.fmtPct() = "%+.1f%%".format(this)
