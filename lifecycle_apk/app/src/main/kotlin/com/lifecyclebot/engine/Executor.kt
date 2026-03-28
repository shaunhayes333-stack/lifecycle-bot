package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.NotificationHistory
import com.lifecyclebot.engine.quant.QuantMetrics
import com.lifecyclebot.engine.quant.PortfolioAnalytics
import kotlin.math.abs
import kotlin.math.pow

import com.lifecyclebot.data.*
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.util.pct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
        
        // Report to Collective Learning hive mind (async)
        if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val reason = when {
                        lossPct <= -50 -> "RUG_PULL"
                        lossPct <= -33 -> "SEVERE_LOSS"
                        else -> "LOSS"
                    }
                    val severity = when {
                        lossPct <= -70 -> 5
                        lossPct <= -50 -> 4
                        lossPct <= -33 -> 3
                        else -> 2
                    }
                    com.lifecyclebot.collective.CollectiveLearning.reportBlacklistedToken(
                        mint = mint,
                        symbol = symbol,
                        reason = reason,
                        severity = severity
                    )
                    
                    // Track contribution for analytics dashboard
                    CollectiveAnalytics.recordBlacklistReport()
                    
                    ErrorLogger.info("RuggedContracts", "🌐 Reported $symbol to collective blacklist")
                } catch (e: Exception) {
                    ErrorLogger.debug("RuggedContracts", "Collective report error: ${e.message}")
                }
            }
        }
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW PAPER POSITIONS
    // Track shadow positions separately from live/paper positions.
    // These are monitored for learning but don't affect real balance.
    // ═══════════════════════════════════════════════════════════════════════════
    data class ShadowPosition(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val quality: String,
        val entryScore: Double,
        val source: String,
    )
    private val shadowPositions = mutableMapOf<String, ShadowPosition>()
    private val MAX_SHADOW_POSITIONS = 20  // Limit to prevent memory bloat

    /**
     * CRITICAL FIX: Get actual token PRICE, not market cap
     * 
     * ts.ref = if (lastMcap > 0) lastMcap else lastPrice ← CAN BE MARKET CAP!
     * ts.lastPrice = actual token price in USD
     * 
     * Use this helper everywhere we need current price for:
     * - P&L calculations
     * - Entry price recording
     * - Exit value calculations
     */
    private fun getActualPrice(ts: TokenState): Double {
        return ts.lastPrice.takeIf { it > 0 } 
            ?: ts.history.lastOrNull()?.priceUsd 
            ?: ts.position.entryPrice.takeIf { it > 0 }
            ?: 0.0
    }

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
    
    /**
     * Record a trade to both TokenState and persistent TradeHistoryStore
     */
    private fun recordTrade(ts: TokenState, trade: Trade) {
        // Ensure trade has mint set
        val tradeWithMint = if (trade.mint.isBlank()) trade.copy(mint = ts.mint) else trade
        ts.trades.add(tradeWithMint)
        TradeHistoryStore.recordTrade(tradeWithMint)
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2: Record losses to ToxicModeCircuitBreaker
        // This enables automatic mode freezing after catastrophic losses
        // ═══════════════════════════════════════════════════════════════════
        if (trade.side == "SELL" && (trade.pnlPct ?: 0.0) < 0) {
            try {
                val mode = ModeRouter.classify(ts).tradeType.name
                ToxicModeCircuitBreaker.recordLoss(
                    mode = mode,
                    pnlPct = trade.pnlPct ?: 0.0,
                    mint = ts.mint,
                    symbol = ts.symbol
                )
            } catch (e: Exception) {
                // Silently ignore - circuit breaker is secondary
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2: Record trade outcome to MetaCognitionAI
        // This enables the self-aware learning loop:
        //   AI predictions at entry → Trade outcome → Update layer accuracy
        // ═══════════════════════════════════════════════════════════════════
        if (trade.side == "SELL") {
            try {
                val holdTimeMs = if (ts.position.entryTime > 0) {
                    System.currentTimeMillis() - ts.position.entryTime
                } else {
                    0L
                }
                
                com.lifecyclebot.v3.scoring.MetaCognitionAI.recordTradeOutcome(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    pnlPct = trade.pnlPct,
                    holdTimeMs = holdTimeMs,
                    exitReason = trade.reason.ifBlank { "unknown" }
                )
            } catch (e: Exception) {
                // Silently ignore - meta-cognition is secondary
            }
        }
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

        // CRITICAL FIX: Use actual price, not market cap
        val currentPrice = getActualPrice(ts)
        val gainPct   = pct(pos.entryPrice, currentPrice)
        val heldMins  = (System.currentTimeMillis() - pos.entryTime) / 60_000.0

        // Must be profitable — never average down
        if (gainPct <= 0) return false

        // CHANGE 6: High-conviction and long-hold positions pyramid deeper
        // For MOONSHOTS (100x+), allow unlimited top-ups as long as position is healthy
        val nextTopUp = pos.topUpCount + 1
        val gainPctNow = pct(pos.entryPrice, currentPrice)
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
        val price = getActualPrice(ts)  // CRITICAL FIX: Use actual price, not market cap
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
        recordTrade(ts, trade)
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
        // FACTOR 7: TIME IN TRADE — Critical for distinguishing pumps vs organic
        // +200% in 30 seconds = pump & dump, lock IMMEDIATELY
        // +200% in 20 minutes = organic growth, can be patient
        // ════════════════════════════════════════════════════════════════
        val holdTimeMs = System.currentTimeMillis() - pos.entryTime
        val holdTimeMinutes = holdTimeMs / 60_000.0
        
        // Calculate gain velocity: how fast did we reach current gain?
        // CRITICAL FIX: Use actual price, not market cap
        val actualPrice = getActualPrice(ts)
        val currentValue = pos.qtyToken * actualPrice
        val gainMultiple = if (pos.costSol > 0) currentValue / pos.costSol else 1.0
        val gainPctPerMinute = if (holdTimeMinutes > 0) {
            ((gainMultiple - 1.0) * 100.0) / holdTimeMinutes
        } else {
            100.0  // Instant gain = treat as very fast
        }
        
        val timeAdjustment = when {
            // ULTRA FAST GAINS — Almost certainly a pump, lock immediately
            holdTimeMinutes < 0.5 && gainMultiple >= 1.5 -> 0.50   // <30 sec @ 1.5x+ = LOCK NOW
            holdTimeMinutes < 1.0 && gainMultiple >= 2.0 -> 0.55   // <1 min @ 2x+ = very aggressive
            holdTimeMinutes < 2.0 && gainMultiple >= 2.0 -> 0.65   // <2 min @ 2x+ = aggressive
            
            // FAST GAINS — Suspicious velocity, lock earlier
            gainPctPerMinute > 50  -> 0.60   // >50% gain per minute = pump territory
            gainPctPerMinute > 25  -> 0.70   // >25% per minute = fast
            gainPctPerMinute > 10  -> 0.85   // >10% per minute = somewhat fast
            
            // MODERATE PACE — Normal trading
            holdTimeMinutes < 5    -> 0.90   // <5 min hold, slightly cautious
            holdTimeMinutes < 10   -> 1.00   // 5-10 min, standard
            holdTimeMinutes < 30   -> 1.10   // 10-30 min, established position
            
            // SLOW & STEADY — Organic growth, patient
            holdTimeMinutes < 60   -> 1.20   // 30-60 min, very established
            holdTimeMinutes < 120  -> 1.30   // 1-2 hours, strong conviction
            else                   -> 1.40   // 2+ hours, maximum patience
        }
        
        // ════════════════════════════════════════════════════════════════
        // COMBINE ALL FACTORS (now 8 factors)
        // Use geometric mean for balanced adjustment
        // Include treasury tier to reward building treasury
        // ════════════════════════════════════════════════════════════════
        val product = liqAdjustment * mcapAdjustment * volAdjustment * phaseAdjustment * 
            qualityAdjustment * tokenTierAdjustment * treasuryTierAdjustment * timeAdjustment
        val combinedAdjustment = product.pow(1.0 / 8.0).coerceIn(0.5, 1.8)  // 8th root, capped 50%-180%
        
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
        
        // CRITICAL FIX: Use actual price, not market cap
        val actualPrice = getActualPrice(ts)
        val currentValue = pos.qtyToken * actualPrice
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
            val sellSol = sellQty * actualPrice  // CRITICAL FIX: Use actual price
            
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
                
                val trade = Trade("SELL", "paper", sellSol, actualPrice,  // CRITICAL FIX
                    System.currentTimeMillis(), "capital_recovery_${gainMultiple.fmt(1)}x",
                    pnlSol, gainPct)
                recordTrade(ts, trade)
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
            val sellSol = sellQty * actualPrice  // CRITICAL FIX: Use actual price
            
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
                
                val trade = Trade("SELL", "paper", sellSol, actualPrice,  // CRITICAL FIX
                    System.currentTimeMillis(), "profit_lock_${gainMultiple.fmt(1)}x",
                    pnlSol, gainPct)
                recordTrade(ts, trade)
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
            // Use 2x slippage for sells - meme coins need more room
            val sellSlippage = (c.slippageBps * 2).coerceAtMost(1000)
            val quote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false)
            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            security.enforceSignDelay()
            
            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            
            val sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
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
            
            val trade = Trade("SELL", "live", solBack, getActualPrice(ts),  // CRITICAL FIX: Use actual price
                System.currentTimeMillis(), reason,
                pnlSol, pnlPct, sig = sig, feeSol = feeSol, netPnlSol = netPnl)
            recordTrade(ts, trade)
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

        // CRITICAL FIX: Use actual price, not market cap
        val actualPrice = getActualPrice(ts)
        val gainPct = pct(pos.entryPrice, actualPrice)
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
        val sellSol      = sellQty * actualPrice  // CRITICAL FIX: Use actual price
        val newSoldPct   = soldPct + sellFraction * 100.0
        val newQty       = pos.qtyToken - sellQty
        val newCost      = pos.costSol * (1.0 - sellFraction)
        val paperPnlSol  = sellQty * actualPrice - pos.costSol * sellFraction  // CRITICAL FIX
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
            val trade   = Trade("SELL", "paper", sellSol, actualPrice,  // CRITICAL FIX
                              System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                              paperPnlSol, pct(pos.costSol * sellFraction, sellQty * actualPrice))  // CRITICAL FIX
            recordTrade(ts, trade); security.recordTrade(trade)
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
                // Use 2x slippage for sells
                val sellSlippage = (c.slippageBps * 2).coerceAtMost(1000)
                val quote     = getQuoteWithSlippageGuard(
                    ts.mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false)
                val txResult  = buildTxWithRetry(quote, wallet.publicKeyB58)
                security.enforceSignDelay()
                
                // ⚡ MEV PROTECTION for partial sells (Ultra or Jito)
                val useJito = c.jitoEnabled && !quote.isUltra
                val jitoTip = c.jitoTipLamports
                val ultraReqId = if (quote.isUltra) txResult.requestId else null
                val sig       = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                val solBack   = quote.outAmount / 1_000_000_000.0
                val livePnl   = solBack - pos.costSol * sellFraction
                val liveScore = pct(pos.costSol * sellFraction, solBack)
                val (netPnl, feeSol) = slippageGuard.calcNetPnl(livePnl, pos.costSol * sellFraction)
                // Update position state after confirmed on-chain execution
                ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
                val liveTrade = Trade("SELL", "live", solBack, actualPrice,  // CRITICAL FIX
                    System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                    livePnl, liveScore, sig = sig, feeSol = feeSol, netPnlSol = netPnl,
                    mint = ts.mint, tradingMode = ts.tradingMode, tradingModeEmoji = ts.tradingModeEmoji)
                recordTrade(ts, liveTrade); security.recordTrade(liveTrade)
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
        // CRITICAL FIX: Use actual price, not market cap
        val price = getActualPrice(ts)
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
        // GEMINI AI EXIT ADVISOR (Live Mode Only)
        // 
        // Uses Gemini to provide intelligent exit recommendations based on:
        // - Current P&L vs peak P&L (round-trip risk)
        // - Hold time and momentum
        // - Recent price action patterns
        // ════════════════════════════════════════════════════════════════
        if (!cfg().paperMode && gainPct >= 15) {  // Only consult Gemini for meaningful gains
            try {
                val recentPrices = ts.history.takeLast(10).map { it.priceUsd }
                val geminiAdvice = GeminiCopilot.getExitAdvice(
                    ts = ts,
                    currentPnlPct = gainPct,
                    holdTimeMinutes = heldSecs / 60.0,
                    peakPnlPct = pos.highestPrice.let { if (it > 0) ((it - pos.entryPrice) / pos.entryPrice) * 100 else gainPct },
                    recentPriceAction = recentPrices,
                )
                
                if (geminiAdvice != null) {
                    when (geminiAdvice.exitUrgency) {
                        "IMMEDIATE" -> {
                            if (geminiAdvice.confidenceScore >= 70) {
                                onLog("🤖🚨 GEMINI EXIT: ${ts.symbol} IMMEDIATE | ${geminiAdvice.reasoning.take(60)}", ts.mint)
                                TradeStateMachine.startCooldown(ts.mint)
                                return "gemini_immediate_exit"
                            }
                        }
                        "SOON" -> {
                            if (geminiAdvice.confidenceScore >= 80 && gainPct >= 30) {
                                onLog("🤖⚠️ GEMINI EXIT: ${ts.symbol} SOON | ${geminiAdvice.reasoning.take(60)}", ts.mint)
                                TradeStateMachine.startCooldown(ts.mint)
                                return "gemini_exit_soon"
                            } else {
                                onLog("🤖 GEMINI: ${ts.symbol} suggests exit soon (conf=${geminiAdvice.confidenceScore.toInt()}%)", ts.mint)
                            }
                        }
                        "RIDE" -> {
                            onLog("🤖✨ GEMINI: ${ts.symbol} ride it! target=${geminiAdvice.targetPrice}", ts.mint)
                        }
                        else -> {} // HOLD - do nothing
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("Executor", "Gemini exit advice error: ${e.message}")
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
        // ════════════════════════════════════════════════════════════════
        // CRITICAL: SELLS MUST ALWAYS BE ALLOWED - even when halted!
        // Check if this is a sell action BEFORE halt check
        // ════════════════════════════════════════════════════════════════
        val isSellAction = (signal in listOf("SELL", "EXIT")) || 
            (ts.position.isOpen && PrecisionExitLogic.quickCheck(
                mint = ts.mint,
                currentPrice = getActualPrice(ts),  // CRITICAL FIX: Use actual price
                entryPrice = ts.position.entryPrice,
                stopLossPct = cfg().stopLossPct,
            )?.shouldExit == true)
        
        // Halt check - blocks new buys but NOT sells
        val cbState = security.getCircuitBreakerState()
        if (cbState.isHalted && !ts.position.isOpen) {
            // Only block if no position (i.e., this would be a buy)
            onLog("🛑 Halted (no new buys): ${cbState.haltReason}", ts.mint)
            return
        }
        if (cbState.isHalted && ts.position.isOpen) {
            onLog("⚠️ Halted but allowing sell actions for open position", ts.mint)
            // Continue to sell logic below
        }

        // Update shadow learning engine with current price
        if (ts.position.isOpen) {
            ShadowLearningEngine.onPriceUpdate(
                mint = ts.mint,
                currentPrice = getActualPrice(ts),  // CRITICAL FIX: Use actual price
                liveStopLossPct = cfg().stopLossPct,
                liveTakeProfitPct = 200.0,  // Default take profit threshold
            )
            
            // ════════════════════════════════════════════════════════════════
            // V8: Precision Exit Logic - Quick check for urgent exits
            // ════════════════════════════════════════════════════════════════
            val quickExit = PrecisionExitLogic.quickCheck(
                mint = ts.mint,
                currentPrice = getActualPrice(ts),  // CRITICAL FIX: Use actual price
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
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2: TOXIC MODE CIRCUIT BREAKER - FORCE FULL EXIT
        // 
        // When collapse conditions are met, exit IMMEDIATELY and FULLY.
        // No partial sells, no waiting. Get out now.
        // 
        // This catches: liquidity collapse + copy invalidation + whale stopped
        // ═══════════════════════════════════════════════════════════════════
        if (ts.position.isOpen) {
            val liqSignal = try { LiquidityDepthAI.getSignal(ts.mint, ts.symbol, isOpenPosition = true) } catch (_: Exception) { null }
            val liquidityCollapsing = liqSignal?.signal in listOf(
                LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE,
                LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING
            )
            val depthDangerous = liqSignal?.depthQuality in listOf(
                LiquidityDepthAI.DepthQuality.POOR,
                LiquidityDepthAI.DepthQuality.DANGEROUS
            )
            
            // Check if whales/copy stopped (from meta or signals)
            // Use velocityScore as proxy for whale activity (high velocity = active whales)
            val whaleActivity = ts.meta.velocityScore
            val whalesStopped = whaleActivity < 20 && ts.meta.whaleSummary.isBlank()
            val classification = ModeRouter.classify(ts)
            val copyInvalidated = classification.tradeType.name.contains("COPY") && whalesStopped
            val buyPressureCollapsing = ts.meta.pressScore < 30
            
            val tradingMode = classification.tradeType.name
            
            val shouldForceExit = ToxicModeCircuitBreaker.shouldForceFullExit(
                liquidityCollapsing = liquidityCollapsing,
                depthDangerous = depthDangerous,
                whalesStopped = whalesStopped,
                copyInvalidated = copyInvalidated,
                buyPressureCollapsing = buyPressureCollapsing,
                mode = tradingMode
            )
            
            if (shouldForceExit) {
                onLog("🚨 CIRCUIT BREAKER FORCE EXIT: ${ts.symbol} | mode=$tradingMode | liq=$liquidityCollapsing whale=$whalesStopped copy=$copyInvalidated", ts.mint)
                doSell(ts, "circuit_breaker_force_exit", wallet, walletSol)
                return
            }
        }

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
            val gainPct   = pct(ts.position.entryPrice, getActualPrice(ts))  // CRITICAL FIX: Use actual price
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
            val result = shouldGraduatedAdd(ts.position, getActualPrice(ts), ts.meta.volScore)  // CRITICAL FIX: Use actual price
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
        
        // ════════════════════════════════════════════════════════════════════════
        // CRITICAL FIX: BLOCK ALL BUY SIGNALS IN LEGACY maybeAct() PATH
        // 
        // This function is the LEGACY entry path that bypasses FDG.
        // ALL new entries MUST go through BotService → FDG → maybeActWithDecision().
        // 
        // If you see this log, there's a code path calling maybeAct() for buys
        // that needs to be migrated to the unified FDG flow.
        // ════════════════════════════════════════════════════════════════════════
        if (signal == "BUY" && !ts.position.isOpen) {
            ErrorLogger.error("Executor", "🚨 LEGACY BUY PATH BLOCKED: ${ts.symbol} | " +
                "All new entries MUST go through FDG. This is a code architecture bug.")
            onLog("⛔ ${ts.symbol}: Legacy buy path blocked - use FDG flow", ts.mint)
            return
        }
        
        // The old buy logic below is now DEAD CODE but kept for reference.
        // Remove in future cleanup once we verify no callers use this path.
        if (false && shouldActOnBuy && signal == "BUY" && !ts.position.isOpen) {
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
                val priceDroppedFromExit = lastTrade?.let { getActualPrice(ts) < it.price * 0.85 } ?: false  // 15%+ below exit
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
            val optimalEntry = if (isPaperMode) true else TradeStateMachine.detectEntryPattern(ts.mint, getActualPrice(ts), priceHistory)  // CRITICAL FIX: Use actual price
            
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
                currentPrice = getActualPrice(ts),  // CRITICAL FIX: Use actual price
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
            // ═══════════════════════════════════════════════════════════════
            // HOLDING LOGIC LAYER - Dynamic position management
            // Evaluates position and can recommend mode switches
            // ═══════════════════════════════════════════════════════════════
            try {
                val currentPnlPct = ((getActualPrice(ts) - ts.position.entryPrice) / ts.position.entryPrice) * 100  // CRITICAL FIX: Use actual price
                val holdEval = kotlinx.coroutines.runBlocking {
                    HoldingLogicLayer.evaluatePosition(
                        position = ts.position,
                        ts = ts,
                        currentPnlPct = currentPnlPct,
                        isPaperMode = cfg().paperMode,
                    )
                }
                
                // Handle mode switch recommendation
                if (holdEval.action == HoldingLogicLayer.HoldAction.SWITCH_MODE && 
                    holdEval.modeSwitchRecommendation?.shouldSwitch == true) {
                    val rec = holdEval.modeSwitchRecommendation
                    val oldMode = ts.position.tradingMode
                    val oldEmoji = ts.position.tradingModeEmoji
                    
                    // Apply mode switch
                    ts.position.tradingMode = rec.newMode
                    ts.position.tradingModeEmoji = rec.newModeEmoji
                    ts.position.modeHistory = if (ts.position.modeHistory.isEmpty()) {
                        "$oldMode>${rec.newMode}"
                    } else {
                        "${ts.position.modeHistory}>${rec.newMode}"
                    }
                    
                    onLog("🔄 MODE SWITCH: ${identity.symbol} | $oldEmoji $oldMode → ${rec.newModeEmoji} ${rec.newMode} | ${rec.reason}", identity.mint)
                    ErrorLogger.info("HoldingLogic", "Mode switch: ${identity.symbol} $oldMode→${rec.newMode} (conf=${rec.confidence.toInt()}%)")
                }
                
                // Log significant holding evaluations
                if (holdEval.urgency == HoldingLogicLayer.Urgency.HIGH || 
                    holdEval.urgency == HoldingLogicLayer.Urgency.CRITICAL) {
                    ErrorLogger.debug("HoldingLogic", "${identity.symbol}: ${holdEval.action} - ${holdEval.reason}")
                }
            } catch (e: Exception) {
                ErrorLogger.debug("HoldingLogic", "Evaluation error for ${identity.symbol}: ${e.message}")
            }
            
            // V8 quick exit check
            val quickExit = PrecisionExitLogic.quickCheck(
                mint = identity.mint,
                currentPrice = getActualPrice(ts),  // CRITICAL FIX: Use actual price
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
                val result = shouldGraduatedAdd(ts.position, getActualPrice(ts), decision.meta.volScore)  // CRITICAL FIX: Use actual price
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
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 MIGRATION: Legacy `shouldTrade` check is now ADVISORY ONLY
        // 
        // Old behavior: `if (!decision.shouldTrade) return` → hard block
        // New behavior: Log for comparison, but don't block
        // 
        // WHY: V3 is the single source of truth. If V3 says EXECUTE,
        // we execute. The legacy `shouldTrade` is for comparison tracking only.
        // ═══════════════════════════════════════════════════════════════════
        if (!decision.shouldTrade) {
            // Log for V3 vs Legacy comparison (don't block!)
            val reason = if (decision.blockReason.isNotEmpty()) decision.blockReason else "legacy_shouldTrade=false"
            ErrorLogger.debug("Executor", "📊 ${ts.symbol}: Legacy would block ($reason) - V3 will evaluate")
            // NOTE: Removed `return` - V3 controls execution now
        }
        
        val isPaper = cfg().paperMode
        ErrorLogger.info("Executor", "🔔 UNIFIED BUY: ${ts.symbol} | " +
            "quality=${decision.finalQuality} | edge=${decision.edgePhase} | " +
            "conf=${decision.aiConfidence.toInt()}% | penalty=${decision.qualityPenalty} | " +
            "paper=$isPaper | autoTrade=${cfg().autoTrade}")
        
        // Rugged contracts check (by mint address) - FATAL, always block
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
            currentPrice = getActualPrice(ts),  // CRITICAL FIX: Use actual price
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

        val gainPct = pct(pos.entryPrice, getActualPrice(ts))  // CRITICAL FIX: Use actual price
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
        val price = getActualPrice(ts)  // CRITICAL FIX: Use actual price
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
        recordTrade(ts, trade)
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
            val sig    = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
            val pos    = ts.position
            val price  = getActualPrice(ts)  // CRITICAL FIX: Use actual price
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
            recordTrade(ts, trade)
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
            paperBuy(ts, sol, score, tradeId, quality, skipGraduated, wallet, walletSol)
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
                    
                    // SHADOW PAPER: Run paper trade for learning even when live is blocked
                    if (cfg().shadowPaperEnabled) {
                        runShadowPaperBuy(ts, sol, score, quality, "blocked:${guard.reason.take(20)}", wallet, walletSol)
                    }
                    return
                }
                is GuardResult.Allow -> {
                    liveBuy(ts, sol, score, wallet, walletSol, tradeId, quality, skipGraduated)
                    
                    // SHADOW PAPER: Also run shadow paper for parallel learning
                    if (cfg().shadowPaperEnabled) {
                        runShadowPaperBuy(ts, sol, score, quality, "parallel", wallet, walletSol)
                    }
                }
            }
        }
    }
    
    /**
     * SHADOW PAPER TRADING
     * 
     * Runs paper trades in background during live mode for accelerated learning.
     * Shadow trades:
     *   - Do NOT affect live balance or positions
     *   - Do NOT show in main trade journal
     *   - DO feed learning data to all AI layers
     *   - Allow brain to learn from more scenarios
     */
    private fun runShadowPaperBuy(ts: TokenState, sol: Double, score: Double, 
                                   quality: String, reason: String,
                                   wallet: SolanaWallet? = null, walletSol: Double = 0.0) {
        try {
            // ═══════════════════════════════════════════════════════════════════
            // MOONSHOT OVERRIDE FOR SHADOW MODE
            // 
            // Even in shadow mode, don't miss a moonshot! If this looks like
            // a massive opportunity, convert to LIVE BUY immediately.
            // ═══════════════════════════════════════════════════════════════════
            val isMoonshot = cfg().moonshotOverrideEnabled &&
                             score >= 85 && 
                             quality in listOf("A", "B") && 
                             ts.lastLiquidityUsd >= 5000 &&
                             ts.meta.pressScore >= 70
            
            if (isMoonshot && wallet != null && walletSol > 0 && !cfg().paperMode) {
                if (walletSol >= sol * 1.1) {
                    onLog("🌙🚀 MOONSHOT in shadow mode! Score=${score.toInt()} Quality=$quality → CONVERTING TO LIVE!", ts.mint)
                    onNotify("🌙 Shadow → Live!", "${ts.symbol} moonshot detected!", 
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    sounds?.playMilestone(100.0)
                    
                    val tradeId = TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
                    liveBuy(ts, sol, score, wallet, walletSol, tradeId, quality)
                    return
                }
            }
            
            // Limit shadow positions to prevent memory bloat
            if (shadowPositions.size >= MAX_SHADOW_POSITIONS) {
                // Remove oldest shadow position
                val oldest = shadowPositions.values.minByOrNull { it.entryTime }
                oldest?.let { shadowPositions.remove(it.mint) }
            }
            
            // Skip if we already have a shadow position for this token
            if (shadowPositions.containsKey(ts.mint)) return
            
            // CRITICAL FIX: Use lastPrice, NOT ref (which can be market cap!)
            val price = ts.lastPrice.takeIf { it > 0 } ?: ts.history.lastOrNull()?.priceUsd ?: 0.0
            if (price <= 0) return
            
            // Create shadow position
            val shadowPos = ShadowPosition(
                mint = ts.mint,
                symbol = ts.symbol,
                entryPrice = price,
                entrySol = sol,
                entryTime = System.currentTimeMillis(),
                quality = quality,
                entryScore = score,
                source = ts.source,
            )
            shadowPositions[ts.mint] = shadowPos
            
            onLog("👻 SHADOW BUY: ${ts.symbol} | $reason | ${sol.toString().take(6)} SOL @ ${price.toString().take(8)} | tracking=${shadowPositions.size}", ts.mint)
            
        } catch (e: Exception) {
            // Shadow trades should never crash the main bot
            ErrorLogger.debug("Executor", "Shadow paper buy failed: ${e.message}")
        }
    }
    
    /**
     * Check shadow positions and record exits for learning.
     * Called periodically during main bot loop.
     */
    fun checkShadowPositions(tokenStates: Map<String, TokenState>) {
        if (!cfg().shadowPaperEnabled || cfg().paperMode) return
        
        val toRemove = mutableListOf<String>()
        val stopLossPct = cfg().stopLossPct
        val takeProfitPct = 50.0  // Shadow takes profit at 50% for learning
        
        for ((mint, shadow) in shadowPositions) {
            val ts = tokenStates[mint] ?: continue
            val currentPrice = getActualPrice(ts)  // CRITICAL FIX: Use actual price
            if (currentPrice <= 0) continue
            
            val pnlPct = ((currentPrice - shadow.entryPrice) / shadow.entryPrice) * 100
            val holdTimeMin = (System.currentTimeMillis() - shadow.entryTime) / 60000
            
            // Check exit conditions
            val shouldExit = when {
                pnlPct <= -stopLossPct -> "stop_loss"
                pnlPct >= takeProfitPct -> "take_profit"
                holdTimeMin >= 30 -> "timeout_30min"  // Force exit after 30 min for learning
                else -> null
            }
            
            if (shouldExit != null) {
                val isWin = pnlPct > 0
                val pnlSol = pnlPct * shadow.entrySol / 100
                val shadowHoldMins = (System.currentTimeMillis() - shadow.entryTime) / 60_000.0
                
                // Record outcome for learning - THIS IS THE KEY PART
                // Shadow trades are EXPLORATION quality (bypassed live rules)
                // They get 0.3x weight to avoid polluting learning data
                brain?.learnFromTrade(
                    isWin = isWin,
                    phase = "shadow_${shadow.quality}",  // Track quality in phase
                    emaFan = "FLAT",  // Shadow trades don't track EMA fan
                    source = shadow.source,
                    pnlPct = pnlPct,
                    mint = shadow.mint,
                    // Default safety metrics for shadow trades
                    rugcheckScore = 50,
                    buyPressure = 50.0,
                    topHolderPct = 10.0,
                    liquidityUsd = 10000.0,
                    isLiveTrade = false,  // Shadow trades are NOT live
                    approvalClass = "PAPER_EXPLORATION",  // Shadow = exploration quality (0.3x weight)
                    // NEW: Execution quality metrics
                    holdTimeMinutes = shadowHoldMins,
                    maxGainPct = pnlPct.coerceAtLeast(0.0),  // Shadow doesn't track peak, use current
                    exitReason = shouldExit,
                    tokenAgeMinutes = 0.0,  // Shadow doesn't track token age
                )
                
                // Update adaptive thresholds based on shadow outcome
                brain?.learnThreshold(
                    isWin = isWin,
                    rugcheckScore = 50,  // Default for shadow
                    buyPressure = 50.0,
                    topHolderPct = 10.0,
                    liquidityUsd = 10000.0,
                    pnlPct = pnlPct,
                )
                
                val emoji = if (isWin) "✅" else "❌"
                onLog("👻 SHADOW EXIT: ${shadow.symbol} | $shouldExit | ${pnlPct.toInt()}% | ${pnlSol.toString().take(6)} SOL | $emoji ${if(isWin) "WIN" else "LOSS"} → LEARNING", mint)
                
                toRemove.add(mint)
            }
        }
        
        // Remove exited shadow positions
        toRemove.forEach { shadowPositions.remove(it) }
    }

    fun paperBuy(ts: TokenState, sol: Double, score: Double, identity: TradeIdentity? = null, 
                 quality: String = "C", skipGraduated: Boolean = false,
                 wallet: SolanaWallet? = null, walletSol: Double = 0.0) {
        // ═══════════════════════════════════════════════════════════════════════════
        // MOONSHOT OVERRIDE: Don't miss potential moonshots even in paper mode!
        // 
        // If ALL of these conditions are met, execute a LIVE BUY instead:
        // 1. Score >= 85 (very high confidence)
        // 2. Quality is A or B (top tier setup)
        // 3. Liquidity >= $5,000 (enough to exit safely)
        // 4. Buy pressure >= 70% (strong demand)
        // 5. We have a connected wallet with balance
        // 6. Moonshot override is enabled in config
        // ═══════════════════════════════════════════════════════════════════════════
        val isMoonshot = cfg().moonshotOverrideEnabled &&
                         score >= 85 && 
                         quality in listOf("A", "B") && 
                         ts.lastLiquidityUsd >= 5000 &&
                         ts.meta.pressScore >= 70
        
        if (isMoonshot && wallet != null && walletSol > 0) {
            if (walletSol >= sol * 1.1) {  // Have enough + 10% buffer
                onLog("🌙🚀 MOONSHOT DETECTED in paper mode! Score=${score.toInt()} Quality=$quality → LIVE BUY OVERRIDE", ts.mint)
                onNotify("🌙 Moonshot Override!", "${ts.symbol} score=${score.toInt()}% → Going LIVE!", 
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                sounds?.playMilestone(100.0)
                
                // Execute live buy instead of paper
                val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
                liveBuy(ts, sol, score, wallet, walletSol, tradeId, quality)
                return
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Use canonical identity for consistent tracking
        // ═══════════════════════════════════════════════════════════════════════════
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        // CRITICAL FIX: Use lastPrice, NOT ref (which can be market cap!)
        // ts.ref = if (lastMcap > 0) lastMcap else lastPrice ← WRONG for entry price
        // ts.lastPrice = actual token price
        val price = ts.lastPrice.takeIf { it > 0 } ?: ts.history.lastOrNull()?.priceUsd ?: 0.0
        if (price <= 0) {
            ErrorLogger.debug("Executor", "Paper buy skipped: no valid price for ${tradeId.symbol}")
            return
        }
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
        
        // ═══════════════════════════════════════════════════════════════════
        // REALISTIC PAPER SIMULATION: Apply slippage & fees like live trading
        // This teaches realistic expectations before going live
        // ═══════════════════════════════════════════════════════════════════
        val simulatedSlippagePct = when {
            ts.lastLiquidityUsd < 5000 -> 3.0   // Low liquidity = high slippage
            ts.lastLiquidityUsd < 20000 -> 1.5  // Medium liquidity
            ts.lastLiquidityUsd < 50000 -> 0.8  // Good liquidity
            else -> 0.4                          // High liquidity
        }
        val slippageMultiplier = 1.0 + (simulatedSlippagePct / 100.0)
        val effectivePrice = price * slippageMultiplier  // You pay MORE due to slippage
        
        // Simulate transaction fee (~0.5% average for Solana DEX trades)
        val simulatedFeePct = 0.5
        val effectiveSol = actualSol * (1.0 - simulatedFeePct / 100.0)  // Less SOL after fee
        
        // Get recommended trading mode for THIS TOKEN based on its characteristics
        // FIX: Use token-specific mode recommendation, not just global primary mode
        val currentMode = try {
            // Calculate age from when token was added to watchlist
            val tokenAgeMs = System.currentTimeMillis() - ts.addedToWatchlistAt
            // Check for whale activity from summary (e.g., "🐋 Large buy detected")
            val hasWhales = ts.meta.whaleSummary.isNotBlank()
            
            val recommendedMode = UnifiedModeOrchestrator.recommendModeForToken(
                liquidity = ts.lastLiquidityUsd,
                mcap = ts.lastMcap,
                ageMs = tokenAgeMs,
                volScore = ts.meta.volScore,
                momScore = ts.meta.momScore,
                source = ts.source,
                emafanAlignment = ts.meta.emafanAlignment,
                holderConcentration = ts.safety.topHolderPct,
                isRevival = ts.source.contains("REVIVAL", ignoreCase = true),
                hasWhaleActivity = hasWhales,
            )
            ErrorLogger.debug("Executor", "Mode selected for ${ts.symbol}: ${recommendedMode.emoji} ${recommendedMode.name}")
            recommendedMode
        } catch (e: Exception) {
            // Fallback to global primary mode
            try {
                UnifiedModeOrchestrator.getCurrentPrimaryMode()
            } catch (_: Exception) {
                UnifiedModeOrchestrator.ExtendedMode.STANDARD
            }
        }
        
        ts.position = Position(
            qtyToken     = effectiveSol / maxOf(effectivePrice, 1e-12),  // Get fewer tokens due to slippage
            entryPrice   = effectivePrice,  // Record slipped price
            entryTime    = System.currentTimeMillis(),
            costSol      = actualSol,  // Track original cost
            highestPrice = effectivePrice,
            entryPhase   = ts.phase,
            entryScore   = score,
            entryLiquidityUsd = ts.lastLiquidityUsd,
            tradingMode  = currentMode.name,
            tradingModeEmoji = currentMode.emoji,
            buildPhase   = buildPhase,
            targetBuildSol = targetBuild,
        )
        val trade = Trade(
            side = "BUY", 
            mode = "paper", 
            sol = actualSol, 
            price = price, 
            ts = System.currentTimeMillis(), 
            score = score,
            tradingMode = currentMode.name,
            tradingModeEmoji = currentMode.emoji,
        )
        recordTrade(ts, trade)
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
        // FLUID LEARNING: Track this paper buy for realistic balance simulation
        // ═══════════════════════════════════════════════════════════════════
        if (cfg().fluidLearningEnabled) {
            FluidLearning.recordPaperBuy(tradeId.mint, actualSol)
            // Record price impact - your buy pushes price UP
            FluidLearning.recordPriceImpact(tradeId.mint, actualSol, ts.lastLiquidityUsd, isBuy = true)
        }
        
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
        
        // ═══════════════════════════════════════════════════════════════════
        // PORTFOLIO ANALYTICS: Track position for heat map & correlation
        // Updated: Use narrative.label from enum
        // ═══════════════════════════════════════════════════════════════════
        try {
            val narrative = NarrativeDetectorAI.detectNarrative(ts.symbol, ts.name)
            PortfolioAnalytics.updatePosition(
                mint = tradeId.mint,
                symbol = tradeId.symbol,
                valueSol = actualSol,
                costSol = actualSol,
                narrative = narrative.label,  // Use .label from enum
                entryTime = System.currentTimeMillis(),
            )
            PortfolioAnalytics.recordPrice(tradeId.mint, price)
        } catch (e: Exception) {
            ErrorLogger.debug("PortfolioAnalytics", "Update error: ${e.message}")
        }
        
        // 🎵 Homer Simpson "Woohoo!" 
        sounds?.playBuySound()
        
        val buildInfo = if (buildPhase == 1) " [BUILD 1/3]" else ""
        onLog("PAPER BUY  @ ${price.fmt()} | ${actualSol.fmt(4)} SOL | score=${score.toInt()}$buildInfo", tradeId.mint)
        onNotify("📈 Paper Buy", "${tradeId.symbol}  ${actualSol.fmt(3)} SOL$buildInfo", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V3 CLEAN RUNTIME: Execute buy through V3 decision
    // 
    // This is the V3-native buy path. V3 has already decided to EXECUTE,
    // provided the size, and we just need to execute the trade.
    // No legacy quality/edge/shouldTrade checks - V3 already scored everything.
    // ═══════════════════════════════════════════════════════════════════════════
    fun v3Buy(
        ts: TokenState,
        sizeSol: Double,
        walletSol: Double,
        v3Score: Int,
        v3Band: String,
        v3Confidence: Double,
        wallet: SolanaWallet?,
        lastSuccessfulPollMs: Long,
        openPositionCount: Int,
        totalExposureSol: Double
    ) {
        val isPaper = cfg().paperMode
        val identity = TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        // Mark as executed in identity
        identity.executed(getActualPrice(ts), sizeSol, isPaper)  // CRITICAL FIX: Use actual price
        
        if (isPaper) {
            // Paper buy with V3 metadata
            paperBuy(
                ts = ts,
                sol = sizeSol,
                score = v3Score.toDouble(),
                identity = identity,
                quality = v3Band,  // Use V3 band as quality
                skipGraduated = true,  // V3 already sized
                wallet = wallet,
                walletSol = walletSol
            )
        } else {
            // Live buy
            if (wallet == null) {
                ErrorLogger.error("Executor", "[V3] ${ts.symbol} | LIVE_BUY_FAILED | no wallet")
                return
            }
            liveBuy(
                ts = ts,
                sol = sizeSol,
                score = v3Score.toDouble(),
                wallet = wallet,
                walletSol = walletSol,
                identity = identity,
                quality = v3Band,
                skipGraduated = true
            )
        }
        
        // Record V3 entry for learning
        try {
            com.lifecyclebot.v3.V3EngineManager.recordEntry(
                mint = ts.mint,
                symbol = ts.symbol,
                entryPrice = getActualPrice(ts),  // CRITICAL FIX: Use actual price
                sizeSol = sizeSol,
                v3Score = v3Score,
                v3Band = v3Band,
                v3Confidence = v3Confidence,
                source = ts.source,
                liquidityUsd = ts.lastLiquidityUsd,
                isPaper = isPaper
            )
        } catch (e: Exception) {
            ErrorLogger.debug("Executor", "[V3] Learning record error: ${e.message}")
        }
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
            
            // Pass Ultra requestId and RFQ route flag for optimal execution
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            val sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
            val qty   = quote.outAmount.toDouble() / tokenScale(quote.outAmount)
            val price = getActualPrice(ts)  // CRITICAL FIX: Use actual price

            // Single position enforcement (re-check after await)
            if (ts.position.isOpen) {
                onLog("⚠ Position opened during confirmation wait — aborting duplicate", ts.mint); return
            }

            // Get recommended trading mode for THIS TOKEN based on its characteristics
            val tokenAgeMs = System.currentTimeMillis() - ts.addedToWatchlistAt
            val hasWhales = ts.meta.whaleSummary.isNotBlank()
            val currentMode = try {
                UnifiedModeOrchestrator.recommendModeForToken(
                    liquidity = ts.lastLiquidityUsd,
                    mcap = ts.lastMcap,
                    ageMs = tokenAgeMs,
                    volScore = ts.meta.volScore,
                    momScore = ts.meta.momScore,
                    source = ts.source,
                    emafanAlignment = ts.meta.emafanAlignment,
                    holderConcentration = ts.safety.topHolderPct,
                    isRevival = ts.source.contains("REVIVAL", ignoreCase = true),
                    hasWhaleActivity = hasWhales,
                )
            } catch (e: Exception) {
                try { UnifiedModeOrchestrator.getCurrentPrimaryMode() } 
                catch (_: Exception) { UnifiedModeOrchestrator.ExtendedMode.STANDARD }
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
                tradingMode  = currentMode.name,
                tradingModeEmoji = currentMode.emoji,
            )
            val trade = Trade(
                side = "BUY", 
                mode = "live", 
                sol = sol, 
                price = price, 
                ts = System.currentTimeMillis(),
                score = score, 
                sig = sig,
                tradingMode = currentMode.name,
                tradingModeEmoji = currentMode.emoji,
            )
            recordTrade(ts, trade)
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
            
            // ═══════════════════════════════════════════════════════════════════
            // GEMINI TRADE REASONING: Generate human-readable explanation
            // Runs async to not block trade execution
            // ═══════════════════════════════════════════════════════════════════
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val aiLayers = mapOf(
                        "Entry Score" to "${score.toInt()}/100",
                        "Phase" to ts.phase,
                        "Quality" to quality,
                        "Buy Pressure" to "${ts.meta.pressScore.toInt()}%",
                        "Volume" to "${ts.meta.volScore.toInt()}%",
                    )
                    val reasoning = GeminiCopilot.explainTrade(
                        ts = ts,
                        action = "BUY",
                        entryScore = score,
                        exitScore = ts.exitScore,
                        aiLayers = aiLayers,
                    )
                    if (reasoning != null) {
                        onLog("🤖 GEMINI: ${reasoning.humanSummary.take(100)}", tradeId.mint)
                    }
                } catch (_: Exception) {}
            }

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
    
    /**
     * Public method to request a sell for a token position.
     * Used by BotService for retrying pending sells.
     */
    fun requestSell(ts: TokenState, reason: String, wallet: SolanaWallet?, walletSol: Double) {
        doSell(ts, reason, wallet, walletSol)
    }

    private fun doSell(ts: TokenState, reason: String,
                       wallet: SolanaWallet?, walletSol: Double,
                       identity: TradeIdentity? = null) {
        // Get or create canonical identity
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val isPaper = cfg().paperMode
        val hasWallet = wallet != null
        onLog("📤 doSell: ${ts.symbol} | paperMode=$isPaper | hasWallet=$hasWallet | reason=$reason", tradeId.mint)
        
        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX #1: WALLET NULL - RETRY/RECONNECT INSTEAD OF ABORTING
        // 
        // Previous behavior: Log error and return, leaving tokens stuck
        // New behavior: Attempt reconnect, queue for retry, alert user loudly
        // ═══════════════════════════════════════════════════════════════════
        if (!isPaper && wallet == null) {
            ErrorLogger.error("Executor", "🚨 CRITICAL: Live mode sell attempted but WALLET IS NULL!")
            ErrorLogger.error("Executor", "🚨 Token ${ts.symbol} - attempting wallet reconnect...")
            
            // Attempt wallet reconnect via WalletManager
            try {
                val reconnectedWallet = WalletManager.attemptReconnect()
                if (reconnectedWallet != null) {
                    ErrorLogger.info("Executor", "✅ Wallet reconnected! Proceeding with sell...")
                    onLog("✅ Wallet reconnected - proceeding with ${ts.symbol} sell", tradeId.mint)
                    liveSell(ts, reason, reconnectedWallet, reconnectedWallet.getSolBalance(), tradeId)
                    return
                }
            } catch (e: Exception) {
                ErrorLogger.error("Executor", "🚨 Wallet reconnect failed: ${e.message}")
            }
            
            // Reconnect failed - queue for retry and alert user
            ErrorLogger.error("Executor", "🚨 Token ${ts.symbol} QUEUED FOR RETRY - reconnect wallet!")
            onLog("🚨 SELL QUEUED: ${ts.symbol} | Wallet disconnected - will retry", tradeId.mint)
            onNotify("🚨 Wallet Disconnected!", 
                "Cannot sell ${ts.symbol} - wallet is NULL! Queued for retry. Reconnect wallet!",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            onToast("🚨 Reconnect wallet to sell ${ts.symbol}!")
            
            // Queue the sell for retry (will be picked up when wallet reconnects)
            PendingSellQueue.add(ts.mint, ts.symbol, reason)
            return
        }
        
        if (isPaper || wallet == null) {
            onLog("📄 Routing to paperSell (paperMode=$isPaper, wallet=${if(hasWallet) "present" else "NULL"})", tradeId.mint)
            paperSell(ts, reason, tradeId)
        } else {
            onLog("💰 Routing to liveSell", tradeId.mint)
            liveSell(ts, reason, wallet, walletSol, tradeId)
        }
    }

    fun paperSell(ts: TokenState, reason: String, identity: TradeIdentity? = null) {
        // ═══════════════════════════════════════════════════════════════════════════
        // TRADE IDENTITY: Use canonical identity for consistent tracking
        // ═══════════════════════════════════════════════════════════════════════════
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val pos   = ts.position
        val price = getActualPrice(ts)  // CRITICAL FIX: Use actual price
        if (!pos.isOpen || price == 0.0) return
        
        // ═══════════════════════════════════════════════════════════════════
        // REALISTIC PAPER SIMULATION: Apply slippage & fees like live trading
        // Sells typically have WORSE slippage than buys (you receive LESS)
        // ═══════════════════════════════════════════════════════════════════
        val simulatedSlippagePct = when {
            ts.lastLiquidityUsd < 5000 -> 4.0   // Low liquidity = high slippage (worse on sells)
            ts.lastLiquidityUsd < 20000 -> 2.0  // Medium liquidity
            ts.lastLiquidityUsd < 50000 -> 1.0  // Good liquidity
            else -> 0.5                          // High liquidity
        }
        val slippageMultiplier = 1.0 - (simulatedSlippagePct / 100.0)
        val effectivePrice = price * slippageMultiplier  // You receive LESS due to slippage
        
        // Simulate transaction fee (~0.5% average)
        val simulatedFeePct = 0.5
        
        val value = pos.qtyToken * effectivePrice * (1.0 - simulatedFeePct / 100.0)  // Less after fee
        val pnl   = value - pos.costSol
        val pnlP  = pct(pos.costSol, value)
        val trade = Trade(
            side = "SELL", 
            mode = "paper", 
            sol = pos.costSol, 
            price = price,
            ts = System.currentTimeMillis(), 
            reason = reason, 
            pnlSol = pnl, 
            pnlPct = pnlP,
            tradingMode = pos.tradingMode,
            tradingModeEmoji = pos.tradingModeEmoji,
        )
        recordTrade(ts, trade)
        security.recordTrade(trade)
        
        // Update paper wallet balance (add sale proceeds)
        onPaperBalanceChange?.invoke(value)
        
        // ═══════════════════════════════════════════════════════════════════
        // FLUID LEARNING: Track paper sell for realistic balance simulation
        // ═══════════════════════════════════════════════════════════════════
        if (cfg().fluidLearningEnabled) {
            FluidLearning.recordPaperSell(tradeId.mint, pos.costSol, pnl)
            // Record price impact - your sell pushes price DOWN
            FluidLearning.recordPriceImpact(tradeId.mint, pos.costSol, ts.lastLiquidityUsd, isBuy = false)
            // Clear cumulative impact when position fully closed
            FluidLearning.clearPriceImpact(tradeId.mint)
        }
        
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
        
        // Calculate max gain (peak P&L during hold)
        val maxGainPct = if (pos.entryPrice > 0 && pos.highestPrice > 0) {
            ((pos.highestPrice - pos.entryPrice) / pos.entryPrice) * 100.0
        } else 0.0
        
        // Calculate token age at entry (how old was the token when we bought)
        val tokenAgeMins = if (ts.addedToWatchlistAt > 0) {
            (pos.entryTime - ts.addedToWatchlistAt) / 60_000.0
        } else 0.0
        
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
            // PAPER MODE: Use approval class for quality-based weighting
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
                    isLiveTrade = false,  // Paper trade
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "PAPER_BENCHMARK" },  // Quality-based weight
                    // NEW: Execution quality metrics
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPct,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMins,
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
            // PAPER MODE: Use approval class for quality-based weighting
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
                    isLiveTrade = false,  // Paper trade
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "PAPER_BENCHMARK" },  // Quality-based weight
                    // NEW: Execution quality metrics
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPct,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMins,
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
                
                // MODE-SPECIFIC LEARNING - each trading mode learns independently
                val tradingMode = ts.position.tradingMode.ifEmpty { "STANDARD" }
                val hourOfDayForMode = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
                
                ModeLearning.recordTrade(
                    mode = tradingMode,
                    isWin = shouldLearnAsWin,
                    pnlPct = pnlP,
                    holdTimeMs = holdTimeMs,
                    entryPhase = ts.position.entryPhase,
                    liquidityUsd = ts.lastLiquidityUsd,
                    source = ts.source.ifEmpty { "UNKNOWN" },
                    hourOfDay = hourOfDayForMode,
                )
                
                // Self-healing check for this mode
                ModeLearning.selfHealingCheckForMode(tradingMode)
            } else {
                ErrorLogger.debug("ScannerLearning", "Skipped scratch trade for ${ts.symbol} (pnl=${pnlP.toInt()}%)")
            }
        } catch (e: Exception) {
            ErrorLogger.debug("AdaptiveLearning", "Feature capture error: ${e.message}")
        }
        // ═══════════════════════════════════════════════════════════════════
        
        // ═══════════════════════════════════════════════════════════════════
        // BEHAVIOR LEARNING: Separate good vs bad behavior layers
        // ═══════════════════════════════════════════════════════════════════
        try {
            val holdMins = ((System.currentTimeMillis() - ts.position.entryTime) / 60_000.0).toInt()
            val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            
            // Determine volatility level from price swings
            val volatilityLevel = when {
                ts.position.highestPrice > 0 && ts.position.entryPrice > 0 -> {
                    val swing = ((ts.position.highestPrice - ts.position.lowestPrice) / ts.position.entryPrice * 100)
                    when {
                        swing > 50 -> "HIGH"
                        swing > 20 -> "MEDIUM"
                        else -> "LOW"
                    }
                }
                else -> "MEDIUM"
            }
            
            // Determine volume signal from volScore
            val volumeSignal = when {
                ts.meta.volScore > 80 -> "SURGE"
                ts.meta.volScore > 60 -> "INCREASING"
                ts.meta.volScore > 40 -> "NORMAL"
                ts.meta.volScore > 20 -> "DECREASING"
                else -> "LOW"
            }
            
            // Determine market sentiment from EMA fan
            val marketSentiment = when {
                ts.meta.emafanAlignment.contains("BULL") -> "BULL"
                ts.meta.emafanAlignment.contains("BEAR") -> "BEAR"
                else -> "NEUTRAL"
            }
            
            BehaviorLearning.recordTrade(
                entryScore = ts.position.entryScore.toInt(),
                entryPhase = ts.position.entryPhase,
                setupQuality = when {
                    ts.position.entryScore >= 90 -> "A+"
                    ts.position.entryScore >= 80 -> "A"
                    ts.position.entryScore >= 70 -> "B"
                    else -> "C"
                },
                tradingMode = ts.position.tradingMode.ifEmpty { "SMART_SNIPER" },
                marketSentiment = marketSentiment,
                volatilityLevel = volatilityLevel,
                volumeSignal = volumeSignal,
                liquidityUsd = ts.lastLiquidityUsd,
                mcapUsd = ts.lastMcap,
                holderTopPct = ts.safety.topHolderPct,
                rugcheckScore = ts.safety.rugcheckScore,
                hourOfDay = hourOfDay,
                dayOfWeek = dayOfWeek,
                holdTimeMinutes = holdMins,
                pnlPct = pnlP,
            )
            
            // ═══════════════════════════════════════════════════════════════════
            // COLLECTIVE LEARNING: Upload to Turso hive mind (async, non-blocking)
            // This shares our trade outcome with all AATE instances worldwide
            // ═══════════════════════════════════════════════════════════════════
            if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        // Determine liquidity bucket for collective pattern
                        val liquidityBucket = when {
                            ts.lastLiquidityUsd < 5_000 -> "MICRO"
                            ts.lastLiquidityUsd < 25_000 -> "SMALL"
                            ts.lastLiquidityUsd < 100_000 -> "MID"
                            else -> "LARGE"
                        }
                        
                        // Upload pattern outcome to collective
                        com.lifecyclebot.collective.CollectiveLearning.uploadPatternOutcome(
                            patternType = "${ts.position.entryPhase}_${ts.position.tradingMode.ifEmpty { "STANDARD" }}",
                            discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                            liquidityBucket = liquidityBucket,
                            emaTrend = marketSentiment,
                            isWin = shouldLearnAsWin,
                            pnlPct = pnlP,
                            holdMins = holdMins.toDouble()
                        )
                        
                        // Track contribution for analytics dashboard
                        CollectiveAnalytics.recordPatternUpload()
                        
                        ErrorLogger.debug("CollectiveLearning", 
                            "📤 Uploaded: ${ts.symbol} | ${if(shouldLearnAsWin) "WIN" else "LOSS"} | ${pnlP.toInt()}%")
                    } catch (e: Exception) {
                        ErrorLogger.debug("CollectiveLearning", "Upload error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BehaviorLearning", "recordTrade error: ${e.message}")
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
        TradeLifecycle.clearProposalTracking(tradeId.mint)  // Allow future re-proposals
        
        // ═══════════════════════════════════════════════════════════════════
        // DISTRIBUTION COOLDOWN: Record if exited due to distribution
        // This prevents the buy→dump→buy→dump loop
        // ═══════════════════════════════════════════════════════════════════
        if (reason.lowercase().contains("distribution")) {
            FinalDecisionGate.recordDistributionExit(tradeId.mint)
            onLog("🚫 Distribution cooldown: ${ts.symbol} blocked for 20-60s", tradeId.mint)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // REENTRY GUARD: Hard block bad tokens from re-entry
        // ═══════════════════════════════════════════════════════════════════
        val reasonLower = reason.lowercase()
        when {
            reasonLower.contains("collapse") || reasonLower.contains("liq_drain") -> {
                ReentryGuard.onLiquidityCollapse(tradeId.mint)
                onLog("🔒 REENTRY BLOCKED: ${ts.symbol} - liquidity collapse (5min)", tradeId.mint)
            }
            reasonLower.contains("distribution") || reasonLower.contains("whale_dump") || reasonLower.contains("dev_dump") -> {
                ReentryGuard.onDistributionDetected(tradeId.mint)
                onLog("🔒 REENTRY BLOCKED: ${ts.symbol} - distribution pattern (3min)", tradeId.mint)
            }
            reasonLower.contains("stop_loss") -> {
                ReentryGuard.onStopLossHit(tradeId.mint, pnlP)
                onLog("🔒 REENTRY BLOCKED: ${ts.symbol} - stop loss hit (2min)", tradeId.mint)
            }
        }
        
        // Track losses for multi-loss detection
        if (pnlP < 0) {
            ReentryGuard.onTradeLoss(tradeId.mint, pnlP)
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
            if (abs(pnlP) >= 5.0) {  // Only meaningful trades
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
        
        // TimeModeScheduler: Learn which modes work best at which times
        try {
            TimeModeScheduler.recordTradeOutcome(
                mode = ts.position.tradingMode.ifEmpty { "SMART_SNIPER" },
                pnlPct = pnlP
            )
        } catch (_: Exception) {}
        
        // WhaleWalletTracker: Record if we followed a whale and the outcome
        try {
            // TODO: Track whale follow outcomes when implemented
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
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 ENGINE: Record outcome for learning
        // ═══════════════════════════════════════════════════════════════════
        try {
            val marketSentiment = ts.meta.emafanAlignment.let { ema ->
                when {
                    ema.contains("BULL") -> "BULL"
                    ema.contains("BEAR") -> "BEAR"
                    else -> "NEUTRAL"
                }
            }
            
            com.lifecyclebot.v3.V3EngineManager.recordOutcome(
                mint = tradeId.mint,
                symbol = ts.symbol,
                pnlPct = pnlP,
                holdTimeMinutes = holdMinutes,
                exitReason = reason,
                // Extra context for collective learning
                entryPhase = ts.position.entryPhase.ifEmpty { "UNKNOWN" },
                tradingMode = ts.position.tradingMode.ifEmpty { "STANDARD" },
                discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                liquidityUsd = ts.lastLiquidityUsd,
                emaTrend = marketSentiment
            )
            com.lifecyclebot.v3.V3EngineManager.onPositionClosed(tradeId.mint)
        } catch (_: Exception) {}
        
        // ═══════════════════════════════════════════════════════════════════
        // QUANT METRICS: Record trade for professional analytics
        // Sharpe, Sortino, Profit Factor, Drawdown tracking
        // ═══════════════════════════════════════════════════════════════════
        try {
            QuantMetrics.recordTrade(
                symbol = ts.symbol,
                mint = ts.mint,
                pnlSol = pnl,
                pnlPct = pnlP,
                holdTimeMinutes = holdTimeMins,
                entryPhase = ts.position.entryPhase,
                quality = tradeClassification,
            )
            
            // Remove from portfolio analytics
            PortfolioAnalytics.removePosition(ts.mint)
            
        } catch (e: Exception) {
            ErrorLogger.debug("QuantMetrics", "Recording error: ${e.message}")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // MODE ORCHESTRATOR: Record trade outcome for mode performance tracking
        // ═══════════════════════════════════════════════════════════════════
        try {
            val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
            val isWin = pnlP > 2.0  // Win if > 2% profit
            val modeStr = ts.position.tradingMode
            
            // Convert string mode to enum and record
            val extMode = try {
                UnifiedModeOrchestrator.ExtendedMode.valueOf(modeStr)
            } catch (e: Exception) {
                UnifiedModeOrchestrator.ExtendedMode.STANDARD
            }
            
            UnifiedModeOrchestrator.recordTrade(
                mode = extMode,
                isWin = isWin,
                pnlPct = pnlP,
                holdTimeMs = holdTimeMs,
            )
            
            // Also update chart insights outcome
            val outcomeStr = if (isWin) "WIN" else if (pnlP < -2.0) "LOSS" else "SCRATCH"
            SuperBrainEnhancements.updateInsightOutcome(ts.mint, outcomeStr, pnlP)
        } catch (e: Exception) {
            ErrorLogger.debug("ModeOrchestrator", "Recording error: ${e.message}")
        }
        
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
        
        // ═══════════════════════════════════════════════════════════════════
        // DEBUG: Log sell attempt start
        // ═══════════════════════════════════════════════════════════════════
        onLog("🔄 SELL START: ${ts.symbol} | reason=$reason | pos.isOpen=${pos.isOpen} | pos.qtyToken=${pos.qtyToken} | pos.costSol=${pos.costSol}", tradeId.mint)
        
        if (!pos.isOpen) {
            onLog("🛑 SELL ABORTED: Position not open", tradeId.mint)
            return
        }

        // ═══════════════════════════════════════════════════════════════════
        // CRITICAL FIX #2: KEYPAIR INTEGRITY - WARN BUT PROCEED FOR SELLS
        // 
        // Previous behavior: Hard-block sell on integrity failure
        // Problem: Tokens get stuck if keypair reload has minor issue
        // 
        // New behavior for SELLS: Log warning, attempt keypair reload, 
        // and proceed anyway. Better to attempt the sell than leave tokens stuck.
        // (Entry blocking remains strict - that prevents buying with bad keys)
        // ═══════════════════════════════════════════════════════════════════
        val integrityOk = security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })
        
        if (!integrityOk) {
            ErrorLogger.warn("Executor", "⚠️ Keypair integrity mismatch for SELL - attempting reload...")
            
            // Attempt to reload keypair from secure storage
            try {
                val reloadedWallet = WalletManager.attemptReconnect()
                if (reloadedWallet != null) {
                    val retryIntegrity = security.verifyKeypairIntegrity(
                        reloadedWallet.publicKeyB58,
                        c.walletAddress.ifBlank { reloadedWallet.publicKeyB58 }
                    )
                    if (retryIntegrity) {
                        ErrorLogger.info("Executor", "✅ Keypair reloaded successfully, proceeding with sell")
                        // Use reloaded wallet for this sell
                        liveSell(ts, reason, reloadedWallet, reloadedWallet.getSolBalance(), tradeId)
                        return
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.warn("Executor", "⚠️ Keypair reload attempt failed: ${e.message}")
            }
            
            // Integrity still failed after reload - proceed anyway with warning
            // Better to attempt the sell than leave tokens stuck
            onLog("⚠️ SELL PROCEEDING DESPITE INTEGRITY WARNING: ${ts.symbol}", tradeId.mint)
            ErrorLogger.warn("Executor", "⚠️ SELL PROCEEDING: Integrity failed but attempting anyway for ${ts.symbol}")
        }

        var tokenUnits = (pos.qtyToken * 1_000_000_000.0).toLong().coerceAtLeast(1L)
        onLog("📊 SELL DEBUG: Initial tokenUnits from tracker = $tokenUnits", tradeId.mint)

        // ═══════════════════════════════════════════════════════════════════
        // ON-CHAIN BALANCE VERIFICATION
        // Check actual token balance before sell to prevent "insufficient funds"
        // This handles cases where:
        //   - Buy confirmation was incomplete
        //   - Tokens were transferred/sold externally
        //   - Position tracker is out of sync
        //   - Token decimals mismatch (6 vs 9 decimals)
        // ═══════════════════════════════════════════════════════════════════
        try {
            onLog("📊 SELL DEBUG: Fetching on-chain token balances...", tradeId.mint)
            val onChainBalances = wallet.getTokenAccountsWithDecimals()
            val tokenData = onChainBalances[ts.mint]
            
            if (tokenData == null || tokenData.first <= 0.0) {
                // No tokens on-chain - position is stale
                onLog("⚠️ SELL SKIPPED: No tokens on-chain for ${ts.symbol}", tradeId.mint)
                onLog("   Expected: ${pos.qtyToken} | Found: 0 | Clearing stale position", tradeId.mint)
                ts.position = Position() // Clear stale position
                return
            }
            
            val actualBalanceUi = tokenData.first
            val actualDecimals = tokenData.second
            onLog("📊 SELL DEBUG: On-chain balance = $actualBalanceUi | decimals=$actualDecimals | mint=${ts.mint.take(8)}...", tradeId.mint)
            
            // Convert UI amount to raw units using ACTUAL decimals from chain
            val multiplier = 10.0.pow(actualDecimals.toDouble())
            val actualRawUnits = (actualBalanceUi * multiplier).toLong()
            
            onLog("📊 SELL DEBUG: tracked=$tokenUnits | on-chain=$actualRawUnits (${actualDecimals}dec)", tradeId.mint)
            
            // Log if there's a significant difference
            val diffPct = if (tokenUnits > 0) abs((actualRawUnits - tokenUnits).toDouble()) / tokenUnits * 100 else 0.0
            if (diffPct > 1.0) {
                onLog("⚠️ Balance adjustment: using on-chain balance ($actualRawUnits) instead of tracked ($tokenUnits)", tradeId.mint)
            }
            
            tokenUnits = actualRawUnits.coerceAtLeast(1L)
            onLog("📊 SELL DEBUG: Final tokenUnits to sell = $tokenUnits", tradeId.mint)
            
        } catch (e: Exception) {
            onLog("⚠️ SELL DEBUG: Balance check failed: ${e.message?.take(60)}", tradeId.mint)
            onLog("   Proceeding with tracked qty: $tokenUnits", tradeId.mint)
            // Continue with tracked quantity if balance check fails
        }

        var pnl  = 0.0   // hoisted — needed after try block
        var pnlP = 0.0

        try {
            // Use 2x slippage for sells - meme coins need more wiggle room on exits
            val sellSlippage = (c.slippageBps * 2).coerceAtMost(1000)  // Max 10%
            onLog("📊 SELL DEBUG: Requesting quote | slippage=${sellSlippage}bps | tokenUnits=$tokenUnits", tradeId.mint)
            
            // Retry quote up to 3 times for sells (network issues, rate limits)
            var quote: com.lifecyclebot.network.SwapQuote? = null
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    onLog("📊 SELL DEBUG: Quote attempt $attempt/3...", tradeId.mint)
                    quote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT,
                                                       tokenUnits, sellSlippage, isBuy = false)
                    onLog("📊 SELL DEBUG: Quote SUCCESS | outAmount=${quote.outAmount} | impact=${quote.priceImpactPct}%", tradeId.mint)
                    break // success
                } catch (e: Exception) {
                    lastError = e
                    onLog("⚠ Sell quote attempt $attempt/3 failed: ${e.message?.take(60)}", ts.mint)
                    if (attempt < 3) Thread.sleep(500L * attempt)
                }
            }
            if (quote == null) {
                onLog("🛑 SELL FAILED: Could not get quote after 3 attempts", tradeId.mint)
                throw lastError ?: RuntimeException("Failed to get sell quote after 3 attempts")
            }

            // Validate quote — for sells, log warning but proceed
            val qGuard = security.validateQuote(quote, isBuy = false, inputSol = pos.costSol)
            if (qGuard is GuardResult.Block) {
                onLog("⚠ Sell quote warning: ${qGuard.reason} — proceeding anyway", ts.mint)
            }

            onLog("📊 SELL DEBUG: Building transaction...", tradeId.mint)
            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            onLog("📊 SELL DEBUG: Transaction built | requestId=${txResult.requestId?.take(16) ?: "none"}", tradeId.mint)
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
            
            onLog("📊 SELL DEBUG: Signing and broadcasting (router=${txResult.router}, rfq=${txResult.isRfqRoute})...", tradeId.mint)
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            val sig     = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
            onLog("📊 SELL DEBUG: Transaction confirmed! sig=${sig.take(20)}...", tradeId.mint)
            
            // ═══════════════════════════════════════════════════════════════════
            // CRITICAL FIX: Verify tokens were actually sold by checking on-chain balance
            // This catches cases where tx was "confirmed" but didn't execute properly
            // ═══════════════════════════════════════════════════════════════════
            try {
                Thread.sleep(1500)  // Wait for chain state to propagate
                val postSellBalances = wallet.getTokenAccountsWithDecimals()
                val remainingTokens = postSellBalances[ts.mint]?.first ?: 0.0
                
                // If we still have significant tokens (>1% of original), the sell failed silently
                val originalTokens = pos.qtyToken
                if (originalTokens > 0 && remainingTokens > originalTokens * 0.01) {
                    val remainingPct = (remainingTokens / originalTokens * 100).toInt()
                    onLog("🚨 SELL VERIFICATION FAILED: Still holding ${remainingPct}% of tokens!", tradeId.mint)
                    onLog("   Original: $originalTokens | Remaining: $remainingTokens", tradeId.mint)
                    onLog("   Transaction sig=${sig.take(20)}... may have failed on-chain", tradeId.mint)
                    onNotify("🚨 Sell Incomplete!",
                        "${ts.symbol}: ${remainingPct}% tokens still in wallet! Check tx: ${sig.take(16)}",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    onToast("🚨 SELL INCOMPLETE: ${ts.symbol}\nStill holding ${remainingPct}% tokens!")
                    
                    // Don't clear the position - tokens are still there!
                    throw RuntimeException("Sell verification failed: still holding ${remainingPct}% tokens (${remainingTokens})")
                } else {
                    onLog("✅ SELL VERIFIED: Token balance is now ${remainingTokens} (was $originalTokens)", tradeId.mint)
                }
            } catch (verifyEx: RuntimeException) {
                throw verifyEx  // Re-throw sell verification failures
            } catch (e: Exception) {
                // Balance check failed but tx was confirmed
                // CRITICAL FIX: Don't blindly proceed - verify tx on solscan or retry balance check
                onLog("⚠️ SELL VERIFICATION: Balance check failed (${e.message?.take(40)})", tradeId.mint)
                
                // Retry balance check with fresh RPC call
                try {
                    Thread.sleep(2000)  // Wait a bit longer for propagation
                    val retryBalances = wallet.getTokenAccountsWithDecimals()
                    val retryRemaining = retryBalances[ts.mint]?.first ?: 0.0
                    
                    if (retryRemaining > pos.qtyToken * 0.01) {
                        val retryPct = (retryRemaining / pos.qtyToken * 100).toInt()
                        onLog("🚨 SELL VERIFICATION RETRY: Still holding ${retryPct}% of tokens!", tradeId.mint)
                        onNotify("🚨 Sell Incomplete!",
                            "${ts.symbol}: ${retryPct}% tokens still in wallet after retry!",
                            com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                        throw RuntimeException("Sell verification retry failed: still holding ${retryPct}% tokens")
                    } else {
                        onLog("✅ SELL VERIFIED on retry: Token balance is now ${retryRemaining}", tradeId.mint)
                    }
                } catch (retryEx: RuntimeException) {
                    throw retryEx  // Re-throw verification failures
                } catch (retryE: Exception) {
                    // Both balance checks failed - DO NOT PROCEED, keep position
                    onLog("🚨 CRITICAL: Cannot verify sell completion - keeping position active!", tradeId.mint)
                    onNotify("🚨 Sell Unverified!",
                        "${ts.symbol}: Cannot verify on-chain. Position NOT cleared. Check manually!",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    onToast("🚨 SELL UNVERIFIED: ${ts.symbol}\nCannot confirm on-chain. Manual check required!")
                    throw RuntimeException("Sell unverifiable: balance check failed twice (${retryE.message})")
                }
            }
            
            val price   = getActualPrice(ts)  // CRITICAL FIX: Use actual price
            val solBack = quote.outAmount / 1_000_000_000.0
            pnl  = solBack - pos.costSol
            pnlP = pct(pos.costSol, solBack)
            val (netPnl, feeSol) = slippageGuard.calcNetPnl(pnl, pos.costSol)
            
            onLog("📊 SELL DEBUG: solBack=${solBack.fmt(6)} | costSol=${pos.costSol.fmt(6)} | pnl=${pnl.fmt(6)} | pnlPct=${pnlP.fmtPct()}", tradeId.mint)

            val trade = Trade(
                side = "SELL", 
                mode = "live", 
                sol = pos.costSol, 
                price = price,
                ts = System.currentTimeMillis(), 
                reason = reason, 
                pnlSol = pnl, 
                pnlPct = pnlP, 
                sig = sig,
                feeSol = feeSol, 
                netPnlSol = netPnl,
                tradingMode = pos.tradingMode,
                tradingModeEmoji = pos.tradingModeEmoji,
            )
            recordTrade(ts, trade)
            security.recordTrade(trade)

            SmartSizer.recordTrade(pnl > 0, isPaperMode = false)  // Live trade
            
            // FIX #5: Lock realized profit to treasury (LIVE mode only)
            if (pnl > 0) {
                val solPrice = WalletManager.lastKnownSolPrice
                TreasuryManager.lockRealizedProfit(pnl, solPrice)
            }

            onLog("✅ LIVE SELL COMPLETE @ ${price.fmt()} | $reason | pnl ${pnl.fmt(4)} SOL " +
                  "(${pnlP.fmtPct()}) | sig=${sig.take(16)}…", ts.mint)
            onNotify("✅ Live Sell",
                "${ts.symbol}  $reason  PnL ${pnlP.fmtPct()}",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            // 🔔 TOAST: Immediate visual feedback for live sell
            val emoji = if (pnlP >= 0) "✅" else "📉"
            onToast("$emoji LIVE SELL: ${ts.symbol}\nPnL: ${pnlP.fmtPct()} (${pnl.fmt(4)} SOL)")

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            onLog("🛑 SELL EXCEPTION: ${e.javaClass.simpleName} | ${safe}", tradeId.mint)
            onLog("   Stack: ${e.stackTrace.take(3).joinToString(" → ") { "${it.fileName}:${it.lineNumber}" }}", tradeId.mint)
            onNotify("⚠️ Sell Failed",
                "${ts.symbol}: ${safe.take(80)}",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            // 🔔 TOAST: Immediate visual feedback for failed sell
            onToast("❌ SELL FAILED: ${ts.symbol}\n${safe.take(50)}")
            return  // don't clear position — retry next tick
        }

        // pnl/pnlP are now valid (try succeeded, otherwise we returned above)
        val exitPrice = getActualPrice(ts)  // CRITICAL FIX: Use actual price - capture before position reset
        
        // ═══════════════════════════════════════════════════════════════════
        // TRADE OUTCOME CLASSIFICATION (TIGHTENED - same as paperSell)
        // Only learn from ±5%+ trades - no more garbage data
        // ═══════════════════════════════════════════════════════════════════
        val holdTimeMins = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
        
        // Calculate max gain (peak P&L during hold) - LIVE mode
        val maxGainPctLive = if (pos.entryPrice > 0 && pos.highestPrice > 0) {
            ((pos.highestPrice - pos.entryPrice) / pos.entryPrice) * 100.0
        } else 0.0
        
        // Calculate token age at entry (how old was the token when we bought) - LIVE mode
        val tokenAgeMinsLive = if (ts.addedToWatchlistAt > 0) {
            (pos.entryTime - ts.addedToWatchlistAt) / 60_000.0
        } else 0.0
        
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
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "LIVE" },  // Live mode approval
                    // NEW: Execution quality metrics
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPctLive,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMinsLive,
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
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "LIVE" },  // Live mode approval
                    // NEW: Execution quality metrics
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPctLive,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMinsLive,
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
        TradeLifecycle.clearProposalTracking(tradeId.mint)  // Allow future re-proposals
        
        // ═══════════════════════════════════════════════════════════════════
        // DISTRIBUTION COOLDOWN: Record if exited due to distribution
        // This prevents the buy→dump→buy→dump loop
        // ═══════════════════════════════════════════════════════════════════
        if (reason.lowercase().contains("distribution")) {
            FinalDecisionGate.recordDistributionExit(tradeId.mint)
            onLog("🚫 Distribution cooldown: ${ts.symbol} blocked for 20-60s", tradeId.mint)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // REENTRY GUARD: Hard block bad tokens from re-entry (LIVE)
        // ═══════════════════════════════════════════════════════════════════
        val reasonLowerLive = reason.lowercase()
        when {
            reasonLowerLive.contains("collapse") || reasonLowerLive.contains("liq_drain") -> {
                ReentryGuard.onLiquidityCollapse(tradeId.mint)
                onLog("🔒 REENTRY BLOCKED: ${ts.symbol} - liquidity collapse (5min)", tradeId.mint)
            }
            reasonLowerLive.contains("distribution") || reasonLowerLive.contains("whale_dump") || reasonLowerLive.contains("dev_dump") -> {
                ReentryGuard.onDistributionDetected(tradeId.mint)
                onLog("🔒 REENTRY BLOCKED: ${ts.symbol} - distribution pattern (3min)", tradeId.mint)
            }
            reasonLowerLive.contains("stop_loss") -> {
                ReentryGuard.onStopLossHit(tradeId.mint, pnlP)
                onLog("🔒 REENTRY BLOCKED: ${ts.symbol} - stop loss hit (2min)", tradeId.mint)
            }
        }
        
        // Track losses for multi-loss detection
        if (pnlP < 0) {
            ReentryGuard.onTradeLoss(tradeId.mint, pnlP)
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
            if (abs(pnlP) >= 5.0) {  // Only meaningful trades
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
        
        // ═══════════════════════════════════════════════════════════════════
        // MODE ORCHESTRATOR: Record LIVE trade outcome for mode performance tracking
        // ═══════════════════════════════════════════════════════════════════
        try {
            val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
            val isWin = pnlP > 2.0  // Win if > 2% profit
            val modeStr = ts.position.tradingMode
            
            // Convert string mode to enum and record
            val extMode = try {
                UnifiedModeOrchestrator.ExtendedMode.valueOf(modeStr)
            } catch (e: Exception) {
                UnifiedModeOrchestrator.ExtendedMode.STANDARD
            }
            
            // LIVE trades get 3x weight for mode tracking too
            repeat(3) {
                UnifiedModeOrchestrator.recordTrade(
                    mode = extMode,
                    isWin = isWin,
                    pnlPct = pnlP,
                    holdTimeMs = holdTimeMs,
                )
            }
            
            // Also update chart insights outcome
            val outcomeStr = if (isWin) "WIN" else if (pnlP < -2.0) "LOSS" else "SCRATCH"
            SuperBrainEnhancements.updateInsightOutcome(ts.mint, outcomeStr, pnlP)
        } catch (e: Exception) {
            ErrorLogger.debug("ModeOrchestrator", "LIVE Recording error: ${e.message}")
        }
        
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
                    ((getActualPrice(ts) - pos.entryPrice) / pos.entryPrice * 100)  // CRITICAL FIX: Use actual price
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
                        val value = pos.qtyToken * getActualPrice(ts)  // CRITICAL FIX: Use actual price
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

    /**
     * Sell an orphaned token that the bot doesn't track.
     * Used by StartupReconciler to clean up tokens from crashed trades.
     * Returns true if sell succeeded, false otherwise.
     */
    fun sellOrphanedToken(mint: String, qty: Double, wallet: SolanaWallet): Boolean {
        val c = cfg()
        
        // Don't sell in paper mode
        if (c.paperMode) {
            onLog("🧹 Orphan sell skipped (paper mode): $mint", mint)
            return false
        }
        
        return try {
            onLog("🧹 Attempting orphan sell: $mint ($qty tokens)", mint)
            
            val sellUnits = (qty * 1_000_000_000.0).toLong().coerceAtLeast(1L)
            val sellSlippage = (c.slippageBps * 3).coerceAtMost(2000)  // Higher slippage for orphans
            
            val quote = getQuoteWithSlippageGuard(
                mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false)
            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            
            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            
            val sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
            val solBack = quote.outAmount / 1_000_000_000.0
            
            onLog("✅ Orphan sold: $mint → ${solBack.fmt(4)} SOL | sig=${sig.take(16)}…", mint)
            onNotify("🧹 Orphan Cleanup",
                "Sold leftover tokens → ${solBack.fmt(4)} SOL",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            true
        } catch (e: Exception) {
            onLog("❌ Orphan sell failed for $mint: ${e.message}", mint)
            false
        }
    }

private fun Double.fmt(d: Int = 6) = "%.${d}f".format(this)
}
private fun Double.fmtPct() = "%+.1f%%".format(this)
