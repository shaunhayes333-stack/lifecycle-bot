package com.lifecyclebot.v3.scoring

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🎯 PROJECT SNIPER AI - V5.6.29d
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * MISSION: Detect and snipe fresh project launches before the initial pump fades.
 * 
 * STRATEGY:
 * - TARGET ACQUISITION: Find tokens < 3 minutes old (fresh launches)
 * - THREAT ASSESSMENT: Check if price is stable/rising (not dump)
 * - ENGAGE: Quick entry with tight parameters
 * - EXTRACT: Ride initial hype, exit on momentum fade
 * 
 * RULES OF ENGAGEMENT:
 * - Only engage tokens that survived first 60 seconds without dumping
 * - Must have positive price action (green candles)
 * - Must have buy pressure > sells (accumulation phase)
 * - Exit immediately if dump detected (abort mission)
 * 
 * RANKING SYSTEM:
 * 🎖️ GENERAL    - 100%+ gain (legendary snipe)
 * 🎖️ COLONEL    - 50-100% gain (elite performance)
 * 🎖️ MAJOR      - 25-50% gain (solid execution)
 * 🎖️ CAPTAIN    - 10-25% gain (mission success)
 * 🎖️ LIEUTENANT - 0-10% gain (minimal victory)
 * 💀 KIA        - Loss (mission failed)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object ProjectSniperAI {
    
    private const val TAG = "🎯Sniper"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MISSION PARAMETERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Target acquisition window
    private const val MAX_TOKEN_AGE_SECONDS = 180      // Only snipe tokens < 3 min old
    private const val MIN_TOKEN_AGE_SECONDS = 30       // Wait 30s to confirm not instant rug
    
    // Market requirements
    private const val MIN_LIQUIDITY_USD = 3_000.0      // Minimum liquidity to engage
    private const val MAX_LIQUIDITY_USD = 100_000.0    // Too big = already pumped
    private const val MIN_MCAP_USD = 5_000.0           // Floor
    private const val MAX_MCAP_USD = 200_000.0         // Ceiling - catch them early
    
    // Entry requirements
    private const val MIN_BUY_PRESSURE_PCT = 52.0      // Must have more buys than sells
    private const val MIN_PRICE_CHANGE_PCT = -5.0      // Can't be dumping hard
    private const val MAX_PRICE_CHANGE_PCT = 50.0      // Can't have already mooned
    
    // Position sizing
    private const val BASE_POSITION_SOL = 0.08         // Base snipe size
    private const val MAX_POSITION_SOL = 0.25          // Max per snipe
    private const val MAX_CONCURRENT_MISSIONS = 5      // Max active snipes
    
    // Exit parameters - TIGHT for launch plays
    private const val STOP_LOSS_PCT = -12.0            // Abort if down 12%
    private const val TAKE_PROFIT_1_PCT = 15.0         // First extract at 15%
    private const val TAKE_PROFIT_2_PCT = 35.0         // Second extract at 35%
    private const val TAKE_PROFIT_3_PCT = 75.0         // Final extract at 75%
    private const val MOONSHOT_PCT = 150.0             // Let it run if mooning
    
    // Daily limits
    private const val DAILY_MAX_MISSIONS = 50          // Max snipes per day
    private const val DAILY_MAX_LOSS_SOL = 1.5         // Daily loss cap
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    @Volatile var isPaperMode: Boolean = true
    
    // Daily stats
    private val dailyMissions = AtomicInteger(0)
    private val dailyKills = AtomicInteger(0)          // Wins
    private val dailyKIA = AtomicInteger(0)            // Losses
    private val dailyPnlSolBps = AtomicLong(0)
    
    // Lifetime stats
    private val lifetimeGenerals = AtomicInteger(0)    // 100%+ wins
    private val lifetimeColonels = AtomicInteger(0)    // 50-100% wins
    private val lifetimeMajors = AtomicInteger(0)      // 25-50% wins
    
    // Active missions
    private val activeMissions = ConcurrentHashMap<String, SniperMission>()
    
    // Cooldown tracking - don't re-snipe same token
    private val recentTargets = ConcurrentHashMap<String, Long>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE - V5.6.29d
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    private var lastSaveTime = 0L
    private const val SAVE_THROTTLE_MS = 10_000L
    
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("project_sniper_ai", android.content.Context.MODE_PRIVATE)
        restore()
        ErrorLogger.info(TAG, "🎯 ProjectSniperAI ONLINE - Ready for target acquisition")
    }
    
    private fun restore() {
        val p = prefs ?: return
        
        lifetimeGenerals.set(p.getInt("lifetimeGenerals", 0))
        lifetimeColonels.set(p.getInt("lifetimeColonels", 0))
        lifetimeMajors.set(p.getInt("lifetimeMajors", 0))
        
        val savedDay = p.getLong("savedDay", 0)
        val currentDay = System.currentTimeMillis() / (24 * 60 * 60 * 1000)
        if (savedDay == currentDay) {
            dailyMissions.set(p.getInt("dailyMissions", 0))
            dailyKills.set(p.getInt("dailyKills", 0))
            dailyKIA.set(p.getInt("dailyKIA", 0))
            dailyPnlSolBps.set(p.getLong("dailyPnlSolBps", 0))
        }
        
        ErrorLogger.info(TAG, "🎯 RESTORED: Generals=${lifetimeGenerals.get()} Colonels=${lifetimeColonels.get()} " +
            "Majors=${lifetimeMajors.get()} | Today: ${dailyKills.get()}W/${dailyKIA.get()}L")
    }
    
    fun save(force: Boolean = false) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSaveTime < SAVE_THROTTLE_MS) return
        lastSaveTime = now
        
        p.edit().apply {
            putInt("lifetimeGenerals", lifetimeGenerals.get())
            putInt("lifetimeColonels", lifetimeColonels.get())
            putInt("lifetimeMajors", lifetimeMajors.get())
            putLong("savedDay", now / (24 * 60 * 60 * 1000))
            putInt("dailyMissions", dailyMissions.get())
            putInt("dailyKills", dailyKills.get())
            putInt("dailyKIA", dailyKIA.get())
            putLong("dailyPnlSolBps", dailyPnlSolBps.get())
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class SniperMission(
        val mint: String,
        val symbol: String,
        val entryPrice: Double,
        val entrySol: Double,
        val entryTime: Long,
        val tokenAgeSecs: Int,
        val entryLiquidity: Double,
        val entryMcap: Double,
        val entryBuyPressure: Double,
        var highestPrice: Double,
        var extractedPct: Int = 0,     // How much profit already taken (0, 33, 66, 100)
        var rank: SniperRank = SniperRank.PENDING,
    )
    
    enum class SniperRank(val emoji: String, val title: String) {
        PENDING("🎯", "ENGAGED"),
        LIEUTENANT("🎖️", "LIEUTENANT"),
        CAPTAIN("🎖️", "CAPTAIN"),
        MAJOR("🎖️", "MAJOR"),
        COLONEL("🎖️", "COLONEL"),
        GENERAL("⭐", "GENERAL"),
        KIA("💀", "KIA"),
    }
    
    data class TargetAssessment(
        val shouldEngage: Boolean,
        val reason: String,
        val threatLevel: ThreatLevel,
        val positionSizeSol: Double,
        val confidence: Int,
        val tokenAgeSecs: Int,
    )
    
    enum class ThreatLevel(val emoji: String) {
        GREEN("🟢"),      // Safe to engage
        YELLOW("🟡"),     // Proceed with caution
        RED("🔴"),        // Do not engage
    }
    
    data class ExitSignal(
        val shouldExit: Boolean,
        val exitPct: Int,             // 0, 33, 50, 100
        val reason: String,
        val rank: SniperRank,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TARGET ACQUISITION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun assessTarget(
        ts: TokenState,
        currentPrice: Double,
    ): TargetAssessment {
        
        val tokenAgeSecs = ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 1000).toInt()
        
        // ═══════════════════════════════════════════════════════════════════
        // PRE-FLIGHT CHECKS
        // ═══════════════════════════════════════════════════════════════════
        
        // Daily limits
        if (dailyMissions.get() >= DAILY_MAX_MISSIONS) {
            return noEngage("DAILY_LIMIT: ${dailyMissions.get()}/$DAILY_MAX_MISSIONS missions", tokenAgeSecs)
        }
        
        val dailyPnl = dailyPnlSolBps.get() / 100.0
        if (dailyPnl <= -DAILY_MAX_LOSS_SOL) {
            return noEngage("DAILY_LOSS_CAP: ${dailyPnl.fmt(2)}◎", tokenAgeSecs)
        }
        
        // Max concurrent
        if (activeMissions.size >= MAX_CONCURRENT_MISSIONS) {
            return noEngage("MAX_MISSIONS: ${activeMissions.size}/$MAX_CONCURRENT_MISSIONS active", tokenAgeSecs)
        }
        
        // Already engaged?
        if (activeMissions.containsKey(ts.mint)) {
            return noEngage("ALREADY_ENGAGED", tokenAgeSecs)
        }
        
        // Recently targeted?
        val lastTarget = recentTargets[ts.mint]
        if (lastTarget != null && System.currentTimeMillis() - lastTarget < 30 * 60 * 1000) {
            return noEngage("RECENT_TARGET: cooldown", tokenAgeSecs)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // AGE CHECK - Must be fresh launch
        // ═══════════════════════════════════════════════════════════════════
        
        if (tokenAgeSecs > MAX_TOKEN_AGE_SECONDS) {
            return noEngage("TOO_OLD: ${tokenAgeSecs}s > ${MAX_TOKEN_AGE_SECONDS}s", tokenAgeSecs)
        }
        
        if (tokenAgeSecs < MIN_TOKEN_AGE_SECONDS) {
            return noEngage("TOO_FRESH: ${tokenAgeSecs}s < ${MIN_TOKEN_AGE_SECONDS}s (waiting)", tokenAgeSecs)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // MARKET ASSESSMENT
        // ═══════════════════════════════════════════════════════════════════
        
        // Liquidity check
        if (ts.lastLiquidityUsd < MIN_LIQUIDITY_USD) {
            return noEngage("LOW_LIQ: \$${ts.lastLiquidityUsd.toInt()} < \$${MIN_LIQUIDITY_USD.toInt()}", tokenAgeSecs)
        }
        if (ts.lastLiquidityUsd > MAX_LIQUIDITY_USD) {
            return noEngage("HIGH_LIQ: \$${ts.lastLiquidityUsd.toInt()} > \$${MAX_LIQUIDITY_USD.toInt()}", tokenAgeSecs)
        }
        
        // Market cap check
        if (ts.lastMcap < MIN_MCAP_USD) {
            return noEngage("LOW_MCAP: \$${ts.lastMcap.toInt()}", tokenAgeSecs)
        }
        if (ts.lastMcap > MAX_MCAP_USD) {
            return noEngage("HIGH_MCAP: \$${(ts.lastMcap/1000).toInt()}K > \$${(MAX_MCAP_USD/1000).toInt()}K", tokenAgeSecs)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // THREAT ASSESSMENT - Is it dumping?
        // ═══════════════════════════════════════════════════════════════════
        
        // Calculate price change from first candle
        val priceChange = if (ts.history.isNotEmpty()) {
            val firstPrice = ts.history.firstOrNull()?.priceUsd ?: currentPrice
            if (firstPrice > 0) ((currentPrice - firstPrice) / firstPrice * 100) else 0.0
        } else 0.0
        
        if (priceChange < MIN_PRICE_CHANGE_PCT) {
            return noEngage("DUMPING: ${priceChange.fmt(1)}% < ${MIN_PRICE_CHANGE_PCT}%", tokenAgeSecs, ThreatLevel.RED)
        }
        
        if (priceChange > MAX_PRICE_CHANGE_PCT) {
            return noEngage("ALREADY_PUMPED: ${priceChange.fmt(1)}% > ${MAX_PRICE_CHANGE_PCT}%", tokenAgeSecs)
        }
        
        // Buy pressure check
        if (ts.lastBuyPressurePct < MIN_BUY_PRESSURE_PCT) {
            return noEngage("WEAK_BUYS: ${ts.lastBuyPressurePct.toInt()}% < ${MIN_BUY_PRESSURE_PCT.toInt()}%", tokenAgeSecs, ThreatLevel.YELLOW)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // CONFIDENCE SCORING
        // ═══════════════════════════════════════════════════════════════════
        
        var confidence = 50  // Base confidence
        
        // Age bonus (fresher = better)
        confidence += when {
            tokenAgeSecs < 60 -> 20   // Very fresh
            tokenAgeSecs < 90 -> 15
            tokenAgeSecs < 120 -> 10
            else -> 5
        }
        
        // Price action bonus
        confidence += when {
            priceChange > 20 -> 15    // Strong pump
            priceChange > 10 -> 10
            priceChange > 5 -> 5
            priceChange > 0 -> 3
            else -> 0
        }
        
        // Buy pressure bonus
        confidence += when {
            ts.lastBuyPressurePct >= 70 -> 15
            ts.lastBuyPressurePct >= 60 -> 10
            ts.lastBuyPressurePct >= 55 -> 5
            else -> 0
        }
        
        // Liquidity bonus (sweet spot)
        confidence += when {
            ts.lastLiquidityUsd in 10_000.0..30_000.0 -> 10  // Ideal range
            ts.lastLiquidityUsd in 5_000.0..50_000.0 -> 5
            else -> 0
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // POSITION SIZING
        // ═══════════════════════════════════════════════════════════════════
        
        val sizeMultiplier = when {
            confidence >= 80 -> 1.5
            confidence >= 70 -> 1.2
            confidence >= 60 -> 1.0
            else -> 0.8
        }
        
        val positionSol = (BASE_POSITION_SOL * sizeMultiplier).coerceIn(BASE_POSITION_SOL, MAX_POSITION_SOL)
        
        val threatLevel = when {
            confidence >= 70 -> ThreatLevel.GREEN
            confidence >= 55 -> ThreatLevel.YELLOW
            else -> ThreatLevel.RED
        }
        
        // Final gate
        if (confidence < 50) {
            return noEngage("LOW_CONFIDENCE: $confidence < 50", tokenAgeSecs, threatLevel)
        }
        
        ErrorLogger.info(TAG, "🎯 TARGET ACQUIRED: ${ts.symbol} | age=${tokenAgeSecs}s | " +
            "price=${priceChange.fmt(1)}% | buy%=${ts.lastBuyPressurePct.toInt()} | conf=$confidence")
        
        return TargetAssessment(
            shouldEngage = true,
            reason = "TARGET_LOCKED",
            threatLevel = threatLevel,
            positionSizeSol = positionSol,
            confidence = confidence,
            tokenAgeSecs = tokenAgeSecs,
        )
    }
    
    private fun noEngage(reason: String, tokenAgeSecs: Int, threat: ThreatLevel = ThreatLevel.RED): TargetAssessment {
        return TargetAssessment(
            shouldEngage = false,
            reason = reason,
            threatLevel = threat,
            positionSizeSol = 0.0,
            confidence = 0,
            tokenAgeSecs = tokenAgeSecs,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MISSION EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun engageMission(
        mint: String,
        symbol: String,
        entryPrice: Double,
        entrySol: Double,
        assessment: TargetAssessment,
        liquidity: Double,
        mcap: Double,
        buyPressure: Double,
    ) {
        val mission = SniperMission(
            mint = mint,
            symbol = symbol,
            entryPrice = entryPrice,
            entrySol = entrySol,
            entryTime = System.currentTimeMillis(),
            tokenAgeSecs = assessment.tokenAgeSecs,
            entryLiquidity = liquidity,
            entryMcap = mcap,
            entryBuyPressure = buyPressure,
            highestPrice = entryPrice,
        )
        
        activeMissions[mint] = mission
        dailyMissions.incrementAndGet()
        recentTargets[mint] = System.currentTimeMillis()
        
        ErrorLogger.info(TAG, "🎯 MISSION ENGAGED: $symbol | " +
            "${assessment.threatLevel.emoji} ${assessment.threatLevel.name} | " +
            "size=${entrySol.fmt(3)}◎ | conf=${assessment.confidence}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXIT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun checkExit(mint: String, currentPrice: Double, currentBuyPressure: Double): ExitSignal {
        val mission = activeMissions[mint] ?: return ExitSignal(false, 0, "NO_MISSION", SniperRank.PENDING)
        
        // Update highest price
        if (currentPrice > mission.highestPrice) {
            mission.highestPrice = currentPrice
        }
        
        val pnlPct = ((currentPrice - mission.entryPrice) / mission.entryPrice * 100)
        val drawdownFromHigh = ((mission.highestPrice - currentPrice) / mission.highestPrice * 100)
        
        // ═══════════════════════════════════════════════════════════════════
        // STOP LOSS - Abort mission
        // ═══════════════════════════════════════════════════════════════════
        
        if (pnlPct <= STOP_LOSS_PCT) {
            return ExitSignal(true, 100, "STOP_LOSS: ${pnlPct.fmt(1)}%", SniperRank.KIA)
        }
        
        // Trailing stop from high
        val trailingStopPct = when {
            pnlPct > 50 -> 15.0   // Lock in gains
            pnlPct > 25 -> 12.0
            pnlPct > 10 -> 10.0
            else -> 20.0          // Wider trailing when small gains
        }
        
        if (drawdownFromHigh > trailingStopPct && pnlPct > 5) {
            return ExitSignal(true, 100, "TRAILING_STOP: -${drawdownFromHigh.fmt(1)}% from high", getRank(pnlPct))
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // TAKE PROFIT TIERS
        // ═══════════════════════════════════════════════════════════════════
        
        // Moonshot - let it run but take some
        if (pnlPct >= MOONSHOT_PCT && mission.extractedPct < 66) {
            return ExitSignal(true, 33, "MOONSHOT: +${pnlPct.fmt(0)}%! Extracting 33%", SniperRank.GENERAL)
        }
        
        // TP3 - 75%
        if (pnlPct >= TAKE_PROFIT_3_PCT && mission.extractedPct < 66) {
            return ExitSignal(true, 33, "TP3: +${pnlPct.fmt(0)}%", SniperRank.COLONEL)
        }
        
        // TP2 - 35%
        if (pnlPct >= TAKE_PROFIT_2_PCT && mission.extractedPct < 33) {
            return ExitSignal(true, 33, "TP2: +${pnlPct.fmt(0)}%", SniperRank.MAJOR)
        }
        
        // TP1 - 15%
        if (pnlPct >= TAKE_PROFIT_1_PCT && mission.extractedPct == 0) {
            return ExitSignal(true, 33, "TP1: +${pnlPct.fmt(0)}%", SniperRank.CAPTAIN)
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // MOMENTUM FADE DETECTION
        // ═══════════════════════════════════════════════════════════════════
        
        // If buy pressure drops significantly, exit
        if (currentBuyPressure < 45 && pnlPct > 0) {
            return ExitSignal(true, 100, "MOMENTUM_FADE: buy%=${currentBuyPressure.toInt()}", getRank(pnlPct))
        }
        
        // Hold time check - don't hold launch plays too long
        val holdTimeSecs = (System.currentTimeMillis() - mission.entryTime) / 1000
        if (holdTimeSecs > 600 && pnlPct < 10) {  // 10 mins with < 10% gain
            return ExitSignal(true, 100, "TIME_LIMIT: ${holdTimeSecs/60}min, only +${pnlPct.fmt(1)}%", 
                if (pnlPct > 0) SniperRank.LIEUTENANT else SniperRank.KIA)
        }
        
        return ExitSignal(false, 0, "HOLD: +${pnlPct.fmt(1)}%", SniperRank.PENDING)
    }
    
    private fun getRank(pnlPct: Double): SniperRank {
        return when {
            pnlPct >= 100 -> SniperRank.GENERAL
            pnlPct >= 50 -> SniperRank.COLONEL
            pnlPct >= 25 -> SniperRank.MAJOR
            pnlPct >= 10 -> SniperRank.CAPTAIN
            pnlPct >= 0 -> SniperRank.LIEUTENANT
            else -> SniperRank.KIA
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MISSION COMPLETE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun completeMission(mint: String, exitPrice: Double, exitSignal: ExitSignal) {
        val mission = activeMissions.remove(mint) ?: return
        
        val pnlPct = ((exitPrice - mission.entryPrice) / mission.entryPrice * 100)
        val pnlSol = mission.entrySol * (pnlPct / 100)
        
        // Update stats
        dailyPnlSolBps.addAndGet((pnlSol * 100).toLong())
        
        if (pnlPct >= 0) {
            dailyKills.incrementAndGet()
            when (exitSignal.rank) {
                SniperRank.GENERAL -> lifetimeGenerals.incrementAndGet()
                SniperRank.COLONEL -> lifetimeColonels.incrementAndGet()
                SniperRank.MAJOR -> lifetimeMajors.incrementAndGet()
                else -> {}
            }
        } else {
            dailyKIA.incrementAndGet()
        }
        
        // Record to FluidLearning
        try {
            FluidLearningAI.recordTrade(pnlPct >= 0)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "FluidLearning record failed: ${e.message}")
        }
        
        save()
        
        val emoji = exitSignal.rank.emoji
        val title = exitSignal.rank.title
        ErrorLogger.info(TAG, "$emoji MISSION COMPLETE: ${mission.symbol} | " +
            "$title | ${if (pnlPct >= 0) "+" else ""}${pnlPct.fmt(1)}% | " +
            "${pnlSol.fmt(4)}◎ | hold=${(System.currentTimeMillis() - mission.entryTime)/1000}s")
    }
    
    fun updateExtracted(mint: String, pct: Int) {
        activeMissions[mint]?.let { it.extractedPct = pct }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS & QUERIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun hasMission(mint: String): Boolean = activeMissions.containsKey(mint)
    
    fun getMission(mint: String): SniperMission? = activeMissions[mint]
    
    fun getActiveMissions(): List<SniperMission> = activeMissions.values.toList()
    
    fun getActiveMissionCount(): Int = activeMissions.size
    
    fun isEnabled(): Boolean = true  // Always enabled
    
    fun getDailyStats(): DailyStats {
        return DailyStats(
            missions = dailyMissions.get(),
            kills = dailyKills.get(),
            kia = dailyKIA.get(),
            pnlSol = dailyPnlSolBps.get() / 100.0,
            winRate = if (dailyKills.get() + dailyKIA.get() > 0) {
                dailyKills.get() * 100 / (dailyKills.get() + dailyKIA.get())
            } else 0,
        )
    }
    
    fun getLifetimeStats(): LifetimeStats {
        return LifetimeStats(
            generals = lifetimeGenerals.get(),
            colonels = lifetimeColonels.get(),
            majors = lifetimeMajors.get(),
        )
    }
    
    data class DailyStats(
        val missions: Int,
        val kills: Int,
        val kia: Int,
        val pnlSol: Double,
        val winRate: Int,
    )
    
    data class LifetimeStats(
        val generals: Int,
        val colonels: Int,
        val majors: Int,
    )
    
    fun clearAllMissions() {
        activeMissions.clear()
        ErrorLogger.info(TAG, "🎯 All missions cleared")
    }
    
    private fun Double.fmt(decimals: Int) = String.format("%.${decimals}f", this)
}
