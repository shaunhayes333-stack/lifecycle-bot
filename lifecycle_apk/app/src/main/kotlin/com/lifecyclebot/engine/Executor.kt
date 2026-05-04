package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.engine.NotificationHistory
import com.lifecyclebot.engine.quant.QuantMetrics
import com.lifecyclebot.engine.quant.PortfolioAnalytics
import com.lifecyclebot.v3.scoring.FluidLearningAI
import com.lifecyclebot.v3.scoring.HoldTimeOptimizerAI
import kotlin.math.abs
import kotlin.math.log10
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

        // V5.9.357 — every blacklist event is also a peer-loss signal for
        // PeerAlphaVerificationAI: this instance just lost on this mint, so
        // any future scoring of the same mint within 2h gets the -8 veto.
        try { com.lifecyclebot.v3.scoring.PeerAlphaVerificationAI.markPeerLoss(mint) } catch (_: Exception) {}
        
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
    companion object {
        // V5.7.3: Dual wallet fee system
        private const val TRADING_FEE_WALLET_1 = "A8QPQrPwoc7kxhemPxoUQev67bwA5kVUAuiyU8Vxkkpd"
        private const val TRADING_FEE_WALLET_2 = "82CAPB9HxXKZK97C12pqkWcjvnkbpMLCg2Ex2hPrhygA"
        
        // V5.7.3: Fee percentages
        private const val MEME_TRADING_FEE_PERCENT = 0.005  // 0.5% for meme/spot trades
        private const val PERPS_TRADING_FEE_PERCENT = 0.01  // 1% for leverage/perps trades
        
        // Fee split (50/50 between wallets)
        private const val FEE_SPLIT_RATIO = 0.5
    }
    
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V3.3: RECOVERY SCAN TRACKING
    // Tokens that hit hard/fluid stop go back to watchlist for potential recovery
    // ═══════════════════════════════════════════════════════════════════════════
    data class RecoveryCandidate(
        val mint: String,
        val symbol: String,
        val stopPrice: Double,        // Price at which we stopped out
        val lossPct: Double,          // How much we lost
        val stopTime: Long,           // When we stopped out
        val stopReason: String,       // "hard_floor" or "fluid_stop"
        val targetRecoveryPrice: Double,  // Price we need to hit for breakeven re-entry
    )
    private val recoveryCandidates = mutableMapOf<String, RecoveryCandidate>()
    private val RECOVERY_SCAN_WINDOW_MS = 30 * 60 * 1000L  // 30 minute window for recovery
    
    /**
     * Mark a stopped-out token for potential recovery scan.
     * Instead of cooldown, we keep watching it for a recovery opportunity.
     */
    private fun markForRecoveryScan(ts: TokenState, lossPct: Double, stopReason: String) {
        normalizePositionScaleIfNeeded(ts)
        val currentPrice = getActualPrice(ts)
        if (currentPrice <= 0) return
        
        // Calculate target price for recovery (breakeven + small profit to cover gas)
        val entryPrice = ts.position.entryPrice
        val targetPrice = entryPrice * 1.02  // Need +2% from original entry for breakeven after fees
        
        val candidate = RecoveryCandidate(
            mint = ts.mint,
            symbol = ts.symbol,
            stopPrice = currentPrice,
            lossPct = lossPct,
            stopTime = System.currentTimeMillis(),
            stopReason = stopReason,
            targetRecoveryPrice = targetPrice
        )
        
        recoveryCandidates[ts.mint] = candidate
        
        ErrorLogger.info("Executor", "🔄 RECOVERY CANDIDATE: ${ts.symbol} | " +
            "stopped at ${lossPct.toInt()}% | watching for bounce to \$${String.format("%.8f", targetPrice)}")
    }
    
    /**
     * Check if a token is a recovery candidate that has bounced.
     * Returns true if we should re-enter for recovery trade.
     */
    fun checkRecoveryOpportunity(ts: TokenState): Boolean {
        normalizePositionScaleIfNeeded(ts)
        val candidate = recoveryCandidates[ts.mint] ?: return false
        
        // Check if recovery window expired
        val elapsed = System.currentTimeMillis() - candidate.stopTime
        if (elapsed > RECOVERY_SCAN_WINDOW_MS) {
            recoveryCandidates.remove(ts.mint)
            return false
        }
        
        val currentPrice = getActualPrice(ts)
        if (currentPrice <= 0) return false
        
        // Check if price has bounced above target recovery price
        val bounceFromStop = ((currentPrice - candidate.stopPrice) / candidate.stopPrice) * 100
        
        if (bounceFromStop >= 10.0) {  // 10%+ bounce from stop price
            ErrorLogger.info("Executor", "🚀 RECOVERY BOUNCE: ${ts.symbol} | " +
                "+${bounceFromStop.toInt()}% from stop | ELIGIBLE for recovery entry")
            
            // Check if price approaching target
            if (currentPrice >= candidate.targetRecoveryPrice * 0.95) {  // Within 5% of target
                ErrorLogger.info("Executor", "💰 RECOVERY TARGET HIT: ${ts.symbol} | " +
                    "price approaching recovery target | RE-ENTRY opportunity")
                recoveryCandidates.remove(ts.mint)
                return true
            }
        }
        
        return false
    }
    
    /**
     * Get all active recovery candidates for display/logging.
     */
    fun getRecoveryCandidates(): List<RecoveryCandidate> {
        // Clean up expired candidates
        val now = System.currentTimeMillis()
        recoveryCandidates.entries.removeIf { now - it.value.stopTime > RECOVERY_SCAN_WINDOW_MS }
        return recoveryCandidates.values.toList()
    }

    /**
     * CRITICAL FIX: Dynamic token price normalization.
     *
     * Some feeds leak base-unit scaled prices (lamports / token base units) or
     * mis-route large numeric references into the price path. We normalize using:
     *   1. explicit token decimals when available
     *   2. trade-context inference from quote.outAmount vs SOL in
     *   3. fallback heuristics (6 / 9 decimals)
     *
     * This helper is the single source of truth for all price-based logic.
     */
    fun getActualPricePublic(ts: TokenState): Double = getActualPrice(ts)
    
    private fun getActualPrice(ts: TokenState): Double {
        // V5.7.7 SIMPLIFIED: Use lastPrice directly - it's updated by DexScreener WebSocket
        
        // Primary: DexScreener price (most reliable, real-time)
        val dexPrice = ts.lastPrice.takeIf { it > 0 && it.isFinite() }
        
        // V5.7.8: Cross-validate against entry price to catch decimal errors
        // If price differs from entry by > 10,000x, it's almost certainly bad data
        if (dexPrice != null && ts.position.entryPrice > 0) {
            val ratio = dexPrice / ts.position.entryPrice
            if (ratio > 10_000 || ratio < 0.0001) {
                val candlePrice = ts.history.lastOrNull()?.priceUsd?.takeIf { it > 0 && it.isFinite() }
                if (candlePrice != null) {
                    val candleRatio = candlePrice / ts.position.entryPrice
                    if (candleRatio < 10_000 && candleRatio > 0.0001) {
                        ErrorLogger.warn("Executor", "PRICE FIX: ${ts.symbol} dexPrice=$dexPrice vs entry=${ts.position.entryPrice} (${ratio.toLong()}x) — using candle=$candlePrice instead")
                        return candlePrice
                    }
                }
                ErrorLogger.warn("Executor", "PRICE GUARD: ${ts.symbol} ALL prices ${ratio.toLong()}x vs entry=${ts.position.entryPrice} — using entry as fallback")
                return ts.position.entryPrice
            }
        }
        
        if (dexPrice != null) return dexPrice
        
        // Fallback 1: Latest candle price
        val candlePrice = ts.history.lastOrNull()?.priceUsd?.takeIf { it > 0 && it.isFinite() }
        if (candlePrice != null) return candlePrice
        
        // Fallback 2: Entry price (if position is open and no live price available)
        val entryPrice = ts.position.entryPrice.takeIf { it > 0 && it.isFinite() }
        if (entryPrice != null) return entryPrice
        
        return 0.0
    }

    /**
     * One-shot self-heal for legacy positions whose stored entry/high/low prices
     * were written before the scaling fix. Without this, current normalized price
     * vs legacy raw entry price creates fake million-percent PnL swings.
     */
    private fun normalizePositionScaleIfNeeded(ts: TokenState) {
        val pos = ts.position
        if (!pos.isOpen) return

        val currentPrice = getActualPrice(ts)
        val entryPrice = pos.entryPrice
        if (currentPrice <= 0.0 || entryPrice <= 0.0 || !currentPrice.isFinite() || !entryPrice.isFinite()) return

        val ratio = entryPrice / currentPrice
        val absRatio = kotlin.math.abs(ratio)
        if (absRatio < 100.0) return

        val scale = detectPowerOfTenScale(absRatio)
        if (scale <= 1.0) return

        val divideStored = ratio > 1.0
        fun fix(v: Double): Double {
            if (v <= 0.0 || !v.isFinite()) return v
            return if (divideStored) v / scale else v * scale
        }

        ts.position = pos.copy(
            entryPrice = fix(pos.entryPrice),
            highestPrice = fix(pos.highestPrice),
            lowestPrice = fix(pos.lowestPrice),
            lastTopUpPrice = fix(pos.lastTopUpPrice),
        )

        if (normalizedPositionScale.putIfAbsent(ts.mint, true) == null) {
            val action = if (divideStored) "÷" else "×"
            ErrorLogger.warn(
                "Executor",
                "🛠 PRICE SCALE HEAL: ${ts.symbol} legacy position normalized ($action${scale.toLong()})"
            )
        }
    }

    /**
     * Background monitor helper. Uses normalized prices for recovery / rug checks
     * even when the rest of the strategy loop has not touched the token yet.
     */
    fun updatePositions(activeTokens: List<TokenState>) {
        val now = System.currentTimeMillis()

        activeTokens.forEach { ts ->
            normalizePositionScaleIfNeeded(ts)

            val currentPrice = getActualPrice(ts)
            val entryPrice = ts.position.entryPrice
            if (currentPrice <= 0.0 || entryPrice <= 0.0) return@forEach

            val pnlPct = ((currentPrice - entryPrice) / entryPrice) * 100.0
            if (pnlPct <= -33.0 && !RuggedContracts.isBlacklisted(ts.mint)) {
                ErrorLogger.warn("Executor", "🚨 RUG/STOP LOSS: ${ts.symbol} at ${pnlPct.toInt()}%")
                markForRecoveryScan(ts, pnlPct, "hard_floor")
                RuggedContracts.add(ts.mint, ts.symbol, pnlPct)
            }
        }

        recoveryCandidates.entries.removeIf { now - it.value.stopTime > RECOVERY_SCAN_WINDOW_MS }
    }

    private val normalizedPositionScale = ConcurrentHashMap<String, Boolean>()
    // V5.7.8: Track zero-balance sell retries — force close after 5 attempts
    private val zeroBalanceRetries = ConcurrentHashMap<String, Int>()

    private fun buildPriceVariants(rawPrice: Double, decimals: Int): List<Double> {
        if (!rawPrice.isFinite() || rawPrice <= 0.0) return emptyList()

        val variants = linkedSetOf<Double>()
        variants += rawPrice

        listOf(decimals, 6, 9).distinct().forEach { d ->
            if (d > 0) {
                val scaled = rawPrice / 10.0.pow(d.toDouble())
                if (scaled.isFinite() && scaled > 0.0) variants += scaled
            }
        }
        return variants.toList()
    }

    private fun scorePriceCandidate(candidate: Double, references: List<Double>, mcapUsd: Double): Double {
        if (!candidate.isFinite() || candidate <= 0.0) return Double.MAX_VALUE

        var score = 0.0

        if (candidate > 1_000_000.0) score += 500.0
        if (candidate < 1e-18) score += 500.0

        if (references.isNotEmpty()) {
            val minDistance = references
                .filter { it.isFinite() && it > 0.0 }
                .minOfOrNull { kotlin.math.abs(log10(candidate / it)) }
                ?: 0.0
            score += minDistance
        } else if (candidate > 10_000.0) {
            score += 5.0
        }

        if (mcapUsd in 1.0..30_000_000.0) {
            when {
                candidate < 1.0 -> score -= 0.25
                candidate > 1_000.0 -> score += 2.0
            }
        }

        if (mcapUsd > 0.0 && candidate >= mcapUsd * 0.25) {
            score += 50.0
        }

        return score
    }

    private fun detectPowerOfTenScale(value: Double): Double {
        if (!value.isFinite() || value <= 0.0) return 1.0

        val exponents = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12)
        var bestScale = 1.0
        var bestDistance = Double.MAX_VALUE

        for (exp in exponents) {
            val scale = 10.0.pow(exp.toDouble())
            val distance = kotlin.math.abs(log10(value / scale))
            if (distance < bestDistance) {
                bestDistance = distance
                bestScale = scale
            }
        }

        return if (bestDistance <= 0.25) bestScale else 1.0
    }

    private fun getTokenDecimals(ts: TokenState): Int {
        val reflected = reflectInt(ts, "decimals", "tokenDecimals", "baseDecimals", "mintDecimals")
            ?: reflectInt(ts.meta, "decimals", "tokenDecimals", "baseDecimals")
            ?: reflectInt(ts.position, "decimals", "tokenDecimals")

        return reflected?.coerceAtLeast(0) ?: -1
    }

    private fun rawTokenAmountToUiAmount(
        ts: TokenState,
        rawAmount: Long,
        solAmount: Double = 0.0,
        priceUsd: Double = 0.0,
        explicitDecimals: Int? = null,
    ): Double {
        if (rawAmount <= 0L) return 0.0

        val scale = when {
            explicitDecimals != null && explicitDecimals >= 0 -> 10.0.pow(explicitDecimals.toDouble())
            getTokenDecimals(ts) >= 0 -> 10.0.pow(getTokenDecimals(ts).toDouble())
            solAmount > 0.0 && priceUsd > 0.0 -> inferUiScaleFromTrade(rawAmount, solAmount, priceUsd)
            else -> tokenScale(rawAmount)
        }

        return rawAmount.toDouble() / scale.coerceAtLeast(1.0)
    }

    private fun inferUiScaleFromTrade(rawAmount: Long, solAmount: Double, priceUsd: Double): Double {
        if (rawAmount <= 0L || solAmount <= 0.0 || priceUsd <= 0.0) return 1_000_000_000.0

        val estimatedQty = solAmount / priceUsd
        if (!estimatedQty.isFinite() || estimatedQty <= 0.0) return 1_000_000_000.0

        val observedScale = rawAmount.toDouble() / estimatedQty
        val candidates = listOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0, 100_000.0, 1_000_000.0, 10_000_000.0, 100_000_000.0, 1_000_000_000.0, 1_000_000_000_000.0)

        return candidates.minByOrNull { kotlin.math.abs(log10(observedScale / it)) } ?: 1_000_000_000.0
    }

    private fun resolveSellUnits(ts: TokenState, qty: Double, wallet: SolanaWallet? = null): Long {
        return resolveSellUnitsForMint(
            mint = ts.mint,
            qty = qty,
            wallet = wallet,
            fallbackDecimals = getTokenDecimals(ts).takeIf { it >= 0 }
        )
    }

    private fun resolveSellUnitsForMint(
        mint: String,
        qty: Double,
        wallet: SolanaWallet? = null,
        fallbackDecimals: Int? = null,
    ): Long {
        if (!qty.isFinite() || qty <= 0.0) return 1L

        val decimals = try {
            wallet?.getTokenAccountsWithDecimals()?.get(mint)?.second
        } catch (_: Exception) {
            null
        } ?: fallbackDecimals ?: 9

        val scale = 10.0.pow(decimals.coerceAtLeast(0).toDouble())
        return (qty * scale).toLong().coerceAtLeast(1L)
    }

    private fun reflectInt(target: Any?, vararg names: String): Int? {
        for (name in names) {
            val value = reflectValue(target, name) ?: continue
            when (value) {
                is Number -> return value.toInt()
                is String -> value.toIntOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun reflectDouble(target: Any?, vararg names: String): Double? {
        for (name in names) {
            val value = reflectValue(target, name) ?: continue
            when (value) {
                is Number -> return value.toDouble()
                is String -> value.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun reflectValue(target: Any?, name: String): Any? {
        if (target == null) return null
        val cls = target.javaClass

        try {
            val field = cls.getDeclaredField(name)
            field.isAccessible = true
            return field.get(target)
        } catch (_: Exception) {
        }

        val suffix = if (name.isEmpty()) name else name.substring(0, 1).uppercase() + name.substring(1)
        val methodNames = arrayOf("get$suffix", "is$suffix", name)

        for (methodName in methodNames) {
            try {
                val method = cls.methods.firstOrNull { it.name == methodName && it.parameterCount == 0 } ?: continue
                return method.invoke(target)
            } catch (_: Exception) {
            }
        }

        return null
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
        ts: TokenState? = null,        // V5.9.69: optional — enables PatternClassifier boost
    ): Double {
        val isPaperMode = cfg().paperMode

        // V5.9.69: PatternClassifier boost — additive on aiConfidence plus a
        // sizing multiplier. Paper-only gate enforced inside the classifier
        // (returns 0 / 1.0 in live until it has 50 live samples @ 55% wr).
        var adjustedConfidence = aiConfidence
        var patternSizeMult = 1.0
        if (ts != null) {
            try {
                val feats = PatternClassifier.extract(ts)
                val boost = PatternClassifier.getConfidenceBoost(feats, isPaperMode)
                if (boost != 0) {
                    adjustedConfidence = (aiConfidence + boost).coerceIn(0.0, 100.0)
                }
                patternSizeMult = PatternClassifier.getSizeMultiplier(feats, isPaperMode)
            } catch (_: Exception) {}
        }

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
            aiConfidence         = adjustedConfidence,
            phase                = phase,
            source               = source,
            brain                = brain,
            setupQuality         = setupQuality,
        )

        if (result.solAmount <= 0.0) {
            onLog("📊 AI Sizer blocked: ${result.explanation}", "sizing")
        } else {
            // V5.9.446 — meme WR emergency brake halves sizing when engaged
            // (meme WR < 30% after 500+ trades). Releases automatically at 35%.
            val brakeMult = try {
                com.lifecyclebot.engine.MemeWREmergencyBrake.sizingMultiplier()
            } catch (_: Throwable) { 1.0 }
            val finalSol = result.solAmount * patternSizeMult * brakeMult
            if (patternSizeMult != 1.0) {
                onLog("🧠 Pattern mult: ${"%.2f".format(patternSizeMult)}x " +
                      "(${result.solAmount.fmt(4)} → ${finalSol.fmt(4)} SOL)", "sizing")
            }
            if (brakeMult != 1.0) {
                onLog("🚨 WR-brake mult: ${"%.2f".format(brakeMult)}x (meme WR low — halving risk)", "sizing")
            }
            onLog("📊 AI Sizer: conf=${adjustedConfidence.toInt()} → ${result.explanation}", "sizing")
            return finalSol
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
            ts = ts,  // V5.9.69: enable PatternClassifier
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

        // V5.9.69 PatternClassifier hooks — continuous-feature online learner.
        // BUY: stash features. SELL: run one SGD step on the outcome.
        try {
            if (trade.side == "BUY") {
                PatternClassifier.noteEntry(ts.mint, PatternClassifier.extract(ts))
                // V5.9.380 — capture each of the 26 meme layers' votes at
                // entry. On SELL closeout (below, ~line 803) we replay each
                // vote into PerpsLearningBridge so layers diverge in accuracy
                // based on their OWN opinion, not the bot's aggregate WR.
                try {
                    com.lifecyclebot.learning.LayerVoteSampler.captureAllMemeVotes(ts)
                } catch (_: Exception) {}
            } else if (trade.side == "SELL") {
                PatternClassifier.noteExit(
                    mint = ts.mint,
                    pnlPct = trade.pnlPct,
                    isLive = trade.mode.equals("live", ignoreCase = true)
                )
            }
        } catch (_: Exception) {}

        // V5.9.58: reset BotBrain's drought watchdog on every BUY so the
        // watchdog only eases thresholds if the scanner has truly gone
        // silent for 10+ minutes.
        if (trade.side == "BUY") {
            try { brain?.onBuyFired() } catch (_: Exception) {}
        }
        
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
            // V5.9.390 — MetaCognition is meme-specific (its accuracy stats
            // drive the meme-brain signal quality estimator). Non-meme sells
            // MUST NOT pollute it. Gate inline on tradingMode. Sub-trader
            // layers get their own per-layer maturity via EducationSubLayerAI's
            // slim path (V5.9.388 Fix A).
            try {
                val _mcTm = (ts.position.tradingMode ?: "").uppercase()
                val _mcIsMemeBase = _mcTm.isBlank() || _mcTm !in setOf(
                    "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS",
                    "QUALITY", "BLUECHIP", "BLUE_CHIP",
                    "MOONSHOT", "TREASURY"
                )
                if (_mcIsMemeBase) {
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
                }
            } catch (e: Exception) {
                // Silently ignore - meta-cognition is secondary
            }
            
            // ═══════════════════════════════════════════════════════════════
            // V4.0: Record trade to FluidLearningAI with TIERED WEIGHTS
            // - LIVE trades: 3.0 weight (real money = 3x learning signal) [V5.9.183]
            // - PAPER trades: 1.0 weight (1 close = 1 session trade) [V5.9.183]
            // Also record to BehaviorAI for pattern analysis
            // ═══════════════════════════════════════════════════════════════
            try {
                val pnl = trade.pnlPct
                val isWin = pnl >= 1.0  // V5.9.185: unified win threshold — must beat combined fees (0.5% buy + 0.5% sell)
                val isPaper = cfg().paperMode
                
                // Record to FluidLearningAI with appropriate weight
                // V5.9.388 — FluidLearning meme bucket gate: only pure meme
                // base closes feed the MEME sessionTrades counter. Sub-trader
                // closes (their own traders already called recordSubTraderTrade
                // themselves) must NOT double-count into the meme bucket.
                val _fluidTm = (ts.position.tradingMode ?: "").uppercase()
                val isMemeBaseClose = _fluidTm.isBlank() || _fluidTm !in setOf(
                    "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS",
                    "QUALITY", "BLUECHIP", "BLUE_CHIP",
                    "MOONSHOT", "TREASURY"
                )
                if (isMemeBaseClose) {
                    if (isPaper) {
                        com.lifecyclebot.v3.scoring.FluidLearningAI.recordPaperTrade(isWin)
                    } else {
                        com.lifecyclebot.v3.scoring.FluidLearningAI.recordLiveTrade(isWin)
                    }
                }

                // Record to BehaviorAI for behavior pattern analysis.
                // V5.9.388 — route by asset class so non-meme sub-trader
                // losses don't lock the meme trader into PROTECT / tilt.
                val _behAsset = when (_fluidTm) {
                    "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS" -> "SHITCOIN"
                    "QUALITY"                                         -> "QUALITY"
                    "BLUECHIP", "BLUE_CHIP"                           -> "BLUECHIP"
                    "MOONSHOT"                                        -> "MOONSHOT"
                    "TREASURY"                                        -> "TREASURY"
                    else                                              -> "MEME"
                }
                com.lifecyclebot.v3.scoring.BehaviorAI.recordTradeForAsset(
                    pnlPct = pnl,
                    reason = trade.reason,
                    mint = ts.mint,
                    isPaperMode = isPaper,
                    assetClass = _behAsset,
                )

                // V5.9.388 — feed the meme-base close into the Copilot MEME
                // window so the PROTECT / DEAD directive reflects actual
                // meme-base performance. Sub-trader closes already record to
                // Copilot with their own asset class (see each sub-trader's
                // closePosition), so we only fire here for MEME base to avoid
                // double-counting sub-trader trades.
                if (_behAsset == "MEME") {
                    try {
                        com.lifecyclebot.engine.TradingCopilot.recordTradeForAsset(
                            pnlPct = pnl,
                            isPaper = isPaper,
                            assetClass = "MEME",
                        )
                    } catch (_: Exception) {}
                }

                // V5.9.120: feed closed trade into PersonalityMemoryStore so
                // trait vector, milestone log, and persona bio actually
                // accumulate from real outcomes.
                // V5.9.390 — meme-only. Persona trait vector is calibrated on
                // meme P&L distributions; shitcoin rugs and blue-chip slow
                // grinds would tilt the personality off-distribution.
                if (_behAsset == "MEME") {
                    try {
                        val peak = ts.position.peakGainPct
                        val gaveBack = (peak - pnl).coerceAtLeast(0.0)
                        val heldMs = if (ts.position.entryTime > 0) {
                            System.currentTimeMillis() - ts.position.entryTime
                        } else 0L
                        val heldMin = (heldMs / 60_000L).toInt()
                        PersonalityMemoryStore.recordTradeOutcome(pnl, gaveBack, heldMin)
                        val activePersona = try {
                            com.lifecyclebot.AATEApp.appContextOrNull()?.let {
                                Personalities.getActive(it).id
                            } ?: "aate"
                        } catch (_: Exception) { "aate" }
                        PersonalityMemoryStore.recordPersonaTrade(activePersona, pnl)
                    } catch (_: Exception) { /* non-critical */ }
                }

                // V5.9.123 — feed closed-trade outcome into every new layer that
                // learns from realized results. Each call fails soft.
                // V5.9.390 — SessionEdgeAI + CorrelationHedge remain meme-only.
                // CorrelationHedge tracks cross-token meme correlation; session
                // edge is calibrated on meme session-of-day win distributions.
                if (_behAsset == "MEME") {
                    try {
                        val won = pnl > 0.5
                        com.lifecyclebot.v3.scoring.CorrelationHedgeAI.registerClosed(ts.mint)
                        com.lifecyclebot.v3.scoring.SessionEdgeAI.recordOutcome(
                            com.lifecyclebot.v3.scoring.SessionEdgeAI.currentSession(), won)
                        // OperatorFingerprint: creator is not in TokenState directly — skip
                        // unless we've stashed it in ts.meta. Graceful no-op otherwise.
                    } catch (_: Exception) {}
                }

                // V5.9.380 — per-layer vote replay. Each of the 26 meme layers
                // cast a vote at BUY time (see LayerVoteSampler) and is now
                // graded on ITS OWN opinion. Layers that voted bullish on a
                // winning trade get +1 correct; layers that voted bearish on
                // a losing trade ALSO get +1 correct (for saving us). Layers
                // that abstained get no signal. This is what V5.9.374 lanes
                // should have done from day one.
                //
                // V5.9.388 — ASSET-CLASS GATE (FIX A/2). closeoutMeme feeds
                // the MEME lane of every layer. It was firing on every sell
                // including ShitCoin/Quality/BlueChip/Moonshot/Treasury
                // closes, polluting the meme-lane stats with non-meme
                // outcomes. Now only pure meme base sells (tradingMode
                // empty or one of the ExtendedMode names like SCALP /
                // MOMENTUM / SWING) drive meme vote replay; sub-trader
                // closes route through recordTradeOutcomeForSubTrader
                // instead.
                try {
                    val tm = (ts.position.tradingMode ?: "").uppercase()
                    val isMemeBaseClose = tm.isBlank() ||
                        tm !in setOf(
                            "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS",
                            "QUALITY", "BLUECHIP", "BLUE_CHIP",
                            "MOONSHOT", "TREASURY"
                        )
                    if (isMemeBaseClose) {
                        com.lifecyclebot.learning.LayerVoteStore.closeoutMeme(
                            mint = ts.mint,
                            isWin = isWin,
                            pnlPct = pnl,
                            symbol = ts.symbol,
                        )
                    }
                } catch (_: Exception) { /* non-critical */ }
            } catch (e: Exception) {
                // Silently ignore - behavior tracking is secondary
            }
            
            // ═══════════════════════════════════════════════════════════════
            // V5.2: EMERGENT PATCH - Record trade to RunTracker30D
            // Tracks 30-day proof run with equity curve and investor metrics
            // ═══════════════════════════════════════════════════════════════
            try {
                if (RunTracker30D.isRunActive()) {
                    val holdTimeSec = if (ts.position.entryTime > 0) {
                        (System.currentTimeMillis() - ts.position.entryTime) / 1000
                    } else 0L
                    
                    val mode = try { ModeRouter.classify(ts).tradeType.name } catch (_: Exception) { "UNKNOWN" }
                    val score = ts.trades.lastOrNull { it.side == "BUY" }?.let { 
                        it.score.coerceIn(0.0, 100.0).toInt()  // V5.9.186: was price*100 = always 100 (wrong) 
                    } ?: 50
                    // V5.9.212: use actual entry AI confidence score, not pnlPct (Audit #11)
                    val confidence = ts.entryScore.toInt().coerceIn(0, 100)
                    
                    RunTracker30D.recordTrade(
                        symbol = ts.symbol,
                        mint = ts.mint,
                        entryPrice = ts.position.entryPrice,
                        exitPrice = trade.price,
                        sizeSol = trade.sol,
                        pnlPct = trade.pnlPct ?: 0.0,
                        holdTimeSec = holdTimeSec,
                        mode = mode,
                        score = score,
                        confidence = confidence,
                        decision = trade.reason.ifBlank { "AUTO" }
                    )
                    
                    // Record rate limit
                    EmergentGuardrails.recordTradeExecution()
                }
            } catch (e: Exception) {
                ErrorLogger.debug("Executor", "RunTracker30D record error: ${e.message}")
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
            pos.partialSoldPct >= 50.0 -> 0.90
            pos.partialSoldPct >= 25.0 -> 0.95
            else                       -> 1.0
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // HOUSE MONEY MULTIPLIER — After capital recovered, we can be a TOUCH
        // looser, but never enough to give back 25-35pp from a peak. The old
        // 1.5-1.8× widening plus the old growing baseTrail curve was turning
        // peak +136% into lock +101% (26% of peak value bled away).
        // V5.9.145 — capped to 1.15× max. Profit-locked gets the most
        // breathing room, but not at the expense of giving back a quarter
        // of peak value.
        // ═══════════════════════════════════════════════════════════════════
        val houseMoneyMultiplier = when {
            pos.profitLocked     -> 1.15
            pos.isHouseMoney     -> 1.10
            pos.capitalRecovered -> 1.05
            else                 -> 1.0
        }
        
        var healthMultiplier = 1.0
        
        when {
            emaFanAlignment == "BULL_FAN" && emaFanWidening -> {
                healthMultiplier += 0.35
            }
            emaFanAlignment == "BULL_FAN" -> {
                healthMultiplier += 0.20
            }
            emaFanAlignment == "BULL_FLAT" -> {
                healthMultiplier += 0.10
            }
            emaFanAlignment == "BEAR_FLAT" -> {
                healthMultiplier -= 0.15
            }
            emaFanAlignment == "BEAR_FAN" -> {
                healthMultiplier -= 0.30
            }
        }
        
        when {
            volScore >= 70 -> healthMultiplier += 0.15
            volScore >= 55 -> healthMultiplier += 0.08
            volScore < 35  -> healthMultiplier -= 0.12
            volScore < 25  -> healthMultiplier -= 0.20
        }
        
        when {
            pressScore >= 65 -> healthMultiplier += 0.12
            pressScore >= 55 -> healthMultiplier += 0.05
            pressScore < 40  -> healthMultiplier -= 0.15
            pressScore < 30  -> healthMultiplier -= 0.25
        }
        
        if (exhaust) {
            healthMultiplier -= 0.30
        }
        
        // V5.9.145 — tightened ceiling. Was 1.6x which could re-inflate a
        // ×0.35 peak-locked trail back up to ×0.56 (+60% wider) on a strong
        // BULL_FAN. We want the tight lock to hold; bull-regime conviction
        // can still buy us modest extra room but not erase the trail
        // inversion we just installed.
        healthMultiplier = healthMultiplier.coerceIn(0.70, 1.25)
        
        val learnedTrailInfluence = if (ExitIntelligence.getTotalExits() >= 20) {
            val learnedStop = ExitIntelligence.getLearnedTrailingStopDistance()
            (learnedStop / 5.0).coerceIn(0.8, 2.0)
        } else 1.0
        
        // V5.9.145 — INVERTED trail curve.
        // Previous curve WIDENED as gain grew (base×1.5 at 100%, ×3 at 1000%),
        // which stacked with houseMoneyMultiplier 1.5-1.8× meant the peak
        // +136% MOG position had an effective trail of ~35pp — giving back
        // 26% of peak value before exit triggered.
        //
        // New curve TIGHTENS as gain grows — the bigger the peak, the less
        // of it we're willing to bleed:
        //
        //   peak   <  15%  → base × 1.00   (normal noise room for early moves)
        //   peak  15-30%   → base × 0.85
        //   peak  30-60%   → base × 0.65
        //   peak  60-120%  → base × 0.50   (moon runner: lock ~90% of peak)
        //   peak 120-250%  → base × 0.35   (big runner:  lock ~93% of peak)
        //   peak 250-500%  → base × 0.28
        //   peak 500-2000% → base × 0.25
        //   peak > 2000%   → base × 0.22   (legendary: lock ~96% of peak)
        //
        // Example on alt mode (base=15): a 136% peak now trails at 15×0.35
        // = 5.25pp instead of the old ~23pp+houseMult=35pp. We lock at
        // 130.75% instead of 101%. That's 30pp of profit saved on a single
        // position.
        val baseTrail = when {
            gainPct >= 2000  -> base * 0.22
            gainPct >= 500   -> base * 0.25
            gainPct >= 250   -> base * 0.28
            gainPct >= 120   -> base * 0.35
            gainPct >= 60    -> base * 0.50
            gainPct >= 30    -> base * 0.65
            gainPct >= 15    -> base * 0.85
            else             -> base * 1.00
        }
        
        var smartTrail = baseTrail * healthMultiplier * partialFactor * learnedTrailInfluence * houseMoneyMultiplier
        
        val regimeTrailMult = try {
            val regime = MarketRegimeAI.getCurrentRegime()
            val confidence = MarketRegimeAI.getRegimeConfidence()
            
            if (confidence >= 40.0) {
                when (regime) {
                    MarketRegimeAI.Regime.STRONG_BULL -> 1.2
                    MarketRegimeAI.Regime.BULL -> 1.1
                    MarketRegimeAI.Regime.NEUTRAL -> 1.0
                    MarketRegimeAI.Regime.CRAB -> 0.95
                    MarketRegimeAI.Regime.BEAR -> 0.85
                    MarketRegimeAI.Regime.STRONG_BEAR -> 0.75
                    MarketRegimeAI.Regime.HIGH_VOLATILITY -> 0.9
                }
            } else 1.0
        } catch (_: Exception) { 1.0 }
        
        smartTrail *= regimeTrailMult
        
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
    
    fun trailingFloorBasic(pos: Position, current: Double,
                            modeConf: AutoModeEngine.ModeConfig? = null): Double {
        return trailingFloor(pos, current, modeConf)
    }

    // ── profit lock system ─────────────────────────────────────────────
    
    private fun calculateProfitLockThresholds(ts: TokenState): Pair<Double, Double> {
        val pos = ts.position
        
        var capitalRecoveryMultiple = 2.0
        var profitLockMultiple = 5.0
        
        val treasuryTierAdjustment = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            when (tier) {
                ScalingMode.Tier.INSTITUTIONAL -> 1.40
                ScalingMode.Tier.SCALED        -> 1.25
                ScalingMode.Tier.GROWTH        -> 1.15
                ScalingMode.Tier.STANDARD      -> 1.05
                ScalingMode.Tier.MICRO         -> 1.00
            }
        } catch (_: Exception) { 1.0 }
        
        val liqUsd = ts.lastLiquidityUsd
        val liqAdjustment = when {
            liqUsd < 5_000   -> 0.70
            liqUsd < 10_000  -> 0.80
            liqUsd < 25_000  -> 0.90
            liqUsd < 50_000  -> 1.00
            liqUsd < 100_000 -> 1.10
            else             -> 1.20
        }
        
        val mcap = ts.lastMcap
        val mcapAdjustment = when {
            mcap < 50_000    -> 0.75
            mcap < 100_000   -> 0.85
            mcap < 250_000   -> 0.95
            mcap < 500_000   -> 1.00
            mcap < 1_000_000 -> 1.10
            else             -> 1.20
        }
        
        val volatility = ts.meta.rangePct
        val volAdjustment = when {
            volatility > 50  -> 0.70
            volatility > 30  -> 0.80
            volatility > 20  -> 0.90
            volatility > 10  -> 1.00
            else             -> 1.10
        }
        
        val entryPhase = pos.entryPhase.lowercase()
        val phaseAdjustment = when {
            entryPhase.contains("early") || entryPhase.contains("accumulation") -> 0.80
            entryPhase.contains("pre_pump") -> 0.85
            entryPhase.contains("markup") || entryPhase.contains("breakout") -> 1.00
            entryPhase.contains("momentum") -> 1.05
            entryPhase.contains("distribution") -> 0.70
            else -> 0.90
        }
        
        val qualityAdjustment = when {
            pos.entryScore >= 80 -> 1.15
            pos.entryScore >= 70 -> 1.05
            pos.entryScore >= 60 -> 1.00
            pos.entryScore >= 50 -> 0.90
            else -> 0.80
        }
        
        val tokenTier = ScalingMode.tierForToken(ts.lastLiquidityUsd, ts.lastMcap)
        val tokenTierAdjustment = when (tokenTier) {
            ScalingMode.Tier.INSTITUTIONAL -> 1.30
            ScalingMode.Tier.SCALED        -> 1.20
            ScalingMode.Tier.GROWTH        -> 1.10
            ScalingMode.Tier.STANDARD      -> 1.00
            ScalingMode.Tier.MICRO         -> 0.85
        }
        
        val holdTimeMs = System.currentTimeMillis() - pos.entryTime
        val holdTimeMinutes = holdTimeMs / 60_000.0
        
        val actualPrice = getActualPrice(ts)
        val currentValue = pos.qtyToken * actualPrice
        val gainMultiple = if (pos.costSol > 0) currentValue / pos.costSol else 1.0
        val gainPctPerMinute = if (holdTimeMinutes > 0) {
            ((gainMultiple - 1.0) * 100.0) / holdTimeMinutes
        } else {
            100.0
        }
        
        val timeAdjustment = when {
            holdTimeMinutes < 0.5 && gainMultiple >= 1.5 -> 0.50
            holdTimeMinutes < 1.0 && gainMultiple >= 2.0 -> 0.55
            holdTimeMinutes < 2.0 && gainMultiple >= 2.0 -> 0.65
            gainPctPerMinute > 50  -> 0.60
            gainPctPerMinute > 25  -> 0.70
            gainPctPerMinute > 10  -> 0.85
            holdTimeMinutes < 5    -> 0.90
            holdTimeMinutes < 10   -> 1.00
            holdTimeMinutes < 30   -> 1.10
            holdTimeMinutes < 60   -> 1.20
            holdTimeMinutes < 120  -> 1.30
            else                   -> 1.40
        }
        
        val product = liqAdjustment * mcapAdjustment * volAdjustment * phaseAdjustment * 
            qualityAdjustment * tokenTierAdjustment * treasuryTierAdjustment * timeAdjustment
        val combinedAdjustment = product.pow(1.0 / 8.0).coerceIn(0.5, 1.8)
        
        capitalRecoveryMultiple *= combinedAdjustment
        profitLockMultiple *= combinedAdjustment
        
        capitalRecoveryMultiple = capitalRecoveryMultiple.coerceIn(1.3, 4.0)
        profitLockMultiple = profitLockMultiple.coerceIn(2.5, 10.0)
        
        return Pair(capitalRecoveryMultiple, profitLockMultiple)
    }
    
    fun checkProfitLock(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {
        val c = cfg()
        normalizePositionScaleIfNeeded(ts)
        val pos = ts.position
        if (!pos.isOpen) return false
        
        val actualPrice = getActualPrice(ts)
        val currentValue = pos.qtyToken * actualPrice
        val gainMultiple = currentValue / pos.costSol
        val gainPct = (gainMultiple - 1.0) * 100.0
        
        val (capitalRecoveryThreshold, profitLockThreshold) = calculateProfitLockThresholds(ts)
        
        if (!pos.capitalRecovered && gainMultiple >= capitalRecoveryThreshold) {
            val sellFraction = (1.0 / gainMultiple).coerceIn(0.25, 0.70)
            val sellQty = pos.qtyToken * sellFraction
            val sellSol = sellQty * actualPrice
            
            onLog("🔒 CAPITAL RECOVERY: ${ts.symbol} @ ${gainMultiple.fmt(2)}x (threshold: ${capitalRecoveryThreshold.fmt(2)}x) — selling ${(sellFraction*100).toInt()}% to recover initial", ts.mint)
            onNotify("🔒 Capital Recovered!",
                "${ts.symbol} @ ${gainMultiple.fmt(1)}x — initial investment secured",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            sounds?.playMilestone(gainPct)
            
            if (c.paperMode || wallet == null) {
                val newQty = pos.qtyToken - sellQty
                val newCost = pos.costSol * (1.0 - sellFraction)
                val pnlSol = sellSol - pos.costSol * sellFraction
                
                ts.position = pos.copy(
                    qtyToken = newQty,
                    costSol = newCost,
                    capitalRecovered = true,
                    capitalRecoveredSol = sellSol,
                    isHouseMoney = true,
                    lockedProfitFloor = sellSol,
                )
                
                val trade = Trade("SELL", "paper", sellSol, actualPrice,
                    System.currentTimeMillis(), "capital_recovery_${gainMultiple.fmt(1)}x",
                    pnlSol, gainPct)
                recordTrade(ts, trade)
                security.recordTrade(trade)
                onPaperBalanceChange?.invoke(sellSol)
                
                val solPrice = WalletManager.lastKnownSolPrice
                TreasuryManager.recordProfitLockEvent(
                    TreasuryEventType.CAPITAL_RECOVERED,
                    sellSol,
                    ts.symbol,
                    gainMultiple,
                    solPrice
                )
                
                if (pnlSol > 0) {
                    TreasuryManager.lockRealizedProfit(pnlSol, solPrice)
                }
                
                onLog("📄 PAPER CAPITAL LOCK: Sold ${sellSol.fmt(4)} SOL @ +${gainPct.toInt()}% — now playing with house money!", ts.mint)
            } else {
                executeProfitLockSell(ts, wallet, sellFraction, "capital_recovery_${gainMultiple.fmt(1)}x", walletSol)
            }
            return true
        }
        
        if (pos.capitalRecovered && !pos.profitLocked && gainMultiple >= profitLockThreshold) {
            val sellFraction = 0.50
            val sellQty = pos.qtyToken * sellFraction
            val sellSol = sellQty * actualPrice
            
            onLog("🔐 PROFIT LOCK: ${ts.symbol} @ ${gainMultiple.fmt(2)}x (threshold: ${profitLockThreshold.fmt(2)}x) — locking 50% of remaining profits", ts.mint)
            onNotify("🔐 Profits Locked!",
                "${ts.symbol} @ ${gainMultiple.fmt(1)}x — 50% profits secured",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            sounds?.playMilestone(gainPct)
            
            if (c.paperMode || wallet == null) {
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
                
                val trade = Trade("SELL", "paper", sellSol, actualPrice,
                    System.currentTimeMillis(), "profit_lock_${gainMultiple.fmt(1)}x",
                    pnlSol, gainPct)
                recordTrade(ts, trade)
                security.recordTrade(trade)
                onPaperBalanceChange?.invoke(sellSol)
                
                val solPrice = WalletManager.lastKnownSolPrice
                TreasuryManager.recordProfitLockEvent(
                    TreasuryEventType.PROFIT_LOCK_SELL,
                    sellSol,
                    ts.symbol,
                    gainMultiple,
                    solPrice
                )
                
                if (pnlSol > 0) {
                    TreasuryManager.lockRealizedProfit(pnlSol, solPrice)
                }
                
                onLog("📄 PAPER PROFIT LOCK: Sold ${sellSol.fmt(4)} SOL @ ${gainMultiple.fmt(1)}x — letting rest ride free!", ts.mint)
            } else {
                executeProfitLockSell(ts, wallet, sellFraction, "profit_lock_${gainMultiple.fmt(1)}x", walletSol)
            }
            return true
        }
        
        return false
    }
    
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
        val sellUnits = resolveSellUnits(ts, sellQty, wallet = wallet)
        
        try {
            val sellSlippage = (c.slippageBps * 2).coerceAtMost(500)
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
            
            val trade = Trade("SELL", "live", solBack, getActualPrice(ts),
                System.currentTimeMillis(), reason,
                pnlSol, pnlPct, sig = sig, feeSol = feeSol, netPnlSol = netPnl)
            recordTrade(ts, trade)
            security.recordTrade(trade)
            SmartSizer.recordTrade(pnlSol > 0, isPaperMode = false)
            // V5.9.105: feed live PnL into session drawdown circuit breaker
            LiveSafetyCircuitBreaker.recordTradeResult(netPnl)

            // V5.9.109: FAIRNESS — profit-lock partial sells must pay the
            // same 0.5% fee as full sells. Basis = costSol × sellFraction
            // (portion actually being sold). Previously this path silently
            // skipped the fee split, short-changing the two wallets.
            try {
                val sellBasisSol = pos.costSol * sellFraction
                val feeAmountSol = sellBasisSol * MEME_TRADING_FEE_PERCENT
                if (feeAmountSol >= 0.0001) {
                    val feeWallet1 = feeAmountSol * FEE_SPLIT_RATIO
                    val feeWallet2 = feeAmountSol * (1.0 - FEE_SPLIT_RATIO)
                    if (feeWallet1 >= 0.0001) wallet.sendSol(TRADING_FEE_WALLET_1, feeWallet1)
                    if (feeWallet2 >= 0.0001) wallet.sendSol(TRADING_FEE_WALLET_2, feeWallet2)
                    onLog("💸 TRADING FEE: ${String.format("%.6f", feeAmountSol)} SOL (0.5% of profit-lock) split 50/50", ts.mint)
                    ErrorLogger.info("Executor", "💸 LIVE PROFIT-LOCK FEE: ${feeAmountSol} SOL split to both wallets")
                }
            } catch (feeEx: Exception) {
                ErrorLogger.error("Executor", "🚨 FEE SEND FAILED — PROFIT-LOCK fee NOT sent, will retry next trade: ${feeEx.message}")
                // V5.9.226: Bug #7 — enqueue for retry instead of silently dropping
                val sellBasisSol2 = pos.costSol * sellFraction
                val feeAmt2 = sellBasisSol2 * MEME_TRADING_FEE_PERCENT
                if (feeAmt2 >= 0.0001) {
                    FeeRetryQueue.enqueue(TRADING_FEE_WALLET_1, feeAmt2 * FEE_SPLIT_RATIO, "profit_lock_w1")
                    FeeRetryQueue.enqueue(TRADING_FEE_WALLET_2, feeAmt2 * (1.0 - FEE_SPLIT_RATIO), "profit_lock_w2")
                }
            }

            val solPrice = WalletManager.lastKnownSolPrice
            val gainMultiple = (solBack + pos.lockedProfitFloor) / pos.costSol
            
            val eventType = if (isCapitalRecovery) TreasuryEventType.CAPITAL_RECOVERED 
                           else TreasuryEventType.PROFIT_LOCK_SELL
            TreasuryManager.recordProfitLockEvent(eventType, solBack, ts.symbol, gainMultiple, solPrice)
            
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

    fun checkPartialSell(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {
        val c   = cfg()
        normalizePositionScaleIfNeeded(ts)
        val pos = ts.position
        if (!c.partialSellEnabled || !pos.isOpen) return false

        val actualPrice = getActualPrice(ts)
        val gainPct = pct(pos.entryPrice, actualPrice)
        val soldPct = pos.partialSoldPct

        val partialLevel = (soldPct / (c.partialSellFraction * 100.0)).toInt()
        
        val milestones = listOf(
            c.partialSellTriggerPct,
            c.partialSellSecondTriggerPct,
            c.partialSellThirdTriggerPct,
            10000.0,
            50000.0,
        )
        
        val nextMilestone = milestones.getOrNull(partialLevel)
        val shouldPartial = nextMilestone != null && gainPct >= nextMilestone
        
        val isThirdOrLater = partialLevel >= 2
        if (!shouldPartial) return false
        if (partialLevel == 2 && !c.partialSellThirdEnabled) return false

        val baseFraction = c.partialSellFraction
        val treasuryAdjustedFraction = try {
            val solPrice = WalletManager.lastKnownSolPrice
            val treasuryUsd = TreasuryManager.treasurySol * solPrice
            val tier = ScalingMode.activeTier(treasuryUsd)
            
            when (tier) {
                ScalingMode.Tier.INSTITUTIONAL -> baseFraction * 0.6
                ScalingMode.Tier.SCALED        -> baseFraction * 0.7
                ScalingMode.Tier.GROWTH        -> baseFraction * 0.8
                ScalingMode.Tier.STANDARD      -> baseFraction * 0.9
                ScalingMode.Tier.MICRO         -> baseFraction
            }
        } catch (_: Exception) { baseFraction }
        
        val sellFraction = treasuryAdjustedFraction
        val sellQty      = pos.qtyToken * sellFraction
        val sellSol      = sellQty * actualPrice
        val newSoldPct   = soldPct + sellFraction * 100.0
        val newQty       = pos.qtyToken - sellQty
        val newCost      = pos.costSol * (1.0 - sellFraction)
        val paperPnlSol  = sellQty * actualPrice - pos.costSol * sellFraction
        val triggerPct   = nextMilestone ?: 0.0
        
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
            ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
            val trade   = Trade("SELL", "paper", sellSol, actualPrice,
                              System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                              paperPnlSol, pct(pos.costSol * sellFraction, sellQty * actualPrice))
            recordTrade(ts, trade); security.recordTrade(trade)
            onLog("PAPER PARTIAL SELL ${(sellFraction*100).toInt()}% | " +
                  "${sellSol.fmt(4)} SOL | pnl ${paperPnlSol.fmt(4)} SOL", ts.mint)
        } else {
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
                val sellUnits = resolveSellUnits(ts, sellQty, wallet = wallet)
                val sellSlippage = (c.slippageBps * 2).coerceAtMost(500)
                val quote     = getQuoteWithSlippageGuard(
                    ts.mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false)
                val txResult  = buildTxWithRetry(quote, wallet.publicKeyB58)
                security.enforceSignDelay()
                
                val useJito = c.jitoEnabled && !quote.isUltra
                val jitoTip = c.jitoTipLamports
                val ultraReqId = if (quote.isUltra) txResult.requestId else null
                val sig       = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                val solBack   = quote.outAmount / 1_000_000_000.0
                val livePnl   = solBack - pos.costSol * sellFraction
                val liveScore = pct(pos.costSol * sellFraction, solBack)
                val (netPnl, feeSol) = slippageGuard.calcNetPnl(livePnl, pos.costSol * sellFraction)
                ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
                val liveTrade = Trade("SELL", "live", solBack, actualPrice,
                    System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                    livePnl, liveScore, sig = sig, feeSol = feeSol, netPnlSol = netPnl,
                    mint = ts.mint, tradingMode = pos.tradingMode, tradingModeEmoji = pos.tradingModeEmoji)
                recordTrade(ts, liveTrade); security.recordTrade(liveTrade)
                SmartSizer.recordTrade(livePnl > 0, isPaperMode = false)
                LiveSafetyCircuitBreaker.recordTradeResult(netPnl)  // V5.9.105 session drawdown halt
                // V5.9.109: FAIRNESS — partial sell #1 pays same 0.5% fee
                // (of the portion sold) to both wallets as full sells do.
                try {
                    val feeAmountSol = (pos.costSol * sellFraction) * MEME_TRADING_FEE_PERCENT
                    if (feeAmountSol >= 0.0001) {
                        val feeWallet1 = feeAmountSol * FEE_SPLIT_RATIO
                        val feeWallet2 = feeAmountSol * (1.0 - FEE_SPLIT_RATIO)
                        if (feeWallet1 >= 0.0001) wallet.sendSol(TRADING_FEE_WALLET_1, feeWallet1)
                        if (feeWallet2 >= 0.0001) wallet.sendSol(TRADING_FEE_WALLET_2, feeWallet2)
                        ErrorLogger.info("Executor", "💸 LIVE PARTIAL-SELL FEE: ${feeAmountSol} SOL split to both wallets")
                    }
                } catch (feeEx: Exception) {
                    ErrorLogger.error("Executor", "🚨 FEE SEND FAILED — PARTIAL-SELL fee NOT sent, will retry next trade: ${feeEx.message}")
                    // V5.9.226: Bug #7 — enqueue for retry
                    val feeAmt3 = (pos.costSol * sellFraction) * MEME_TRADING_FEE_PERCENT
                    if (feeAmt3 >= 0.0001) {
                        FeeRetryQueue.enqueue(TRADING_FEE_WALLET_1, feeAmt3 * FEE_SPLIT_RATIO, "partial_sell_w1")
                        FeeRetryQueue.enqueue(TRADING_FEE_WALLET_2, feeAmt3 * (1.0 - FEE_SPLIT_RATIO), "partial_sell_w2")
                    }
                }
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

    private val milestonesHit      = mutableMapOf<String, MutableSet<Int>>()
    private val partialSellInFlight = mutableSetOf<String>()
    // Guard against concurrent exit triggers (e.g. RAPID_HARD_FLOOR + SELL_OPT) both
    // executing a sell on the same position before the first one clears it.
    private val sellInProgress = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun riskCheck(ts: TokenState, modeConf: AutoModeEngine.ModeConfig? = null): String? {
        normalizePositionScaleIfNeeded(ts)
        val pos   = ts.position
        val price = getActualPrice(ts)
        if (!pos.isOpen || price == 0.0) return null

        // ═══════════════════════════════════════════════════════════════════════
        // V5.9.124 — REFLEX AI (sub-second reflex gate). Runs BEFORE every
        // other exit path so meme snipers get hard-exited on catastrophic
        // red candles / liquidity drains without waiting for the
        // full scan tick. PARTIAL_LOCK is informational here — the
        // milestone-partial-sell layer (checkPartialSell) picks up the 2x
        // move on its own.
        // ═══════════════════════════════════════════════════════════════════════
        try {
            val currentLiq = ts.lastLiquidityUsd
            val reflex = com.lifecyclebot.v3.scoring.ReflexAI.evaluate(ts, price, currentLiq)
            when (reflex) {
                com.lifecyclebot.v3.scoring.ReflexAI.Reflex.ABORT -> {
                    onLog("⚡ REFLEX ABORT: ${ts.symbol} - immediate exit", ts.mint)
                    markForRecoveryScan(ts, pct(pos.entryPrice, price), "reflex_abort")
                    TradeStateMachine.startCooldown(ts.mint)
                    return "reflex_abort"
                }
                com.lifecyclebot.v3.scoring.ReflexAI.Reflex.LIQ_DRAIN -> {
                    onLog("⚡ REFLEX LIQ_DRAIN: ${ts.symbol} - liquidity collapse exit", ts.mint)
                    TradeStateMachine.startCooldown(ts.mint)
                    return "reflex_liq_drain"
                }
                com.lifecyclebot.v3.scoring.ReflexAI.Reflex.PARTIAL_LOCK -> {
                    onLog("⚡ REFLEX PARTIAL_LOCK: ${ts.symbol} +2x fast move (milestone partial will fire)", ts.mint)
                    // fall through — riskCheck does not execute partial sells;
                    // the milestone-partial-sell layer handles the actual sell.
                }
                null -> {}
            }
        } catch (_: Exception) {}

        pos.highestPrice = maxOf(pos.highestPrice, price)
        if (pos.lowestPrice == 0.0 || price < pos.lowestPrice) {
            pos.lowestPrice = price
        }
        val gainPct  = pct(pos.entryPrice, price)
        val heldSecs = (System.currentTimeMillis() - pos.entryTime) / 1000.0

        val hitMilestones = milestonesHit.getOrPut(ts.mint) { mutableSetOf() }
        listOf(50, 100, 200).forEach { threshold ->
            if (gainPct >= threshold && !hitMilestones.contains(threshold)) {
                hitMilestones.add(threshold)
                sounds?.playMilestone(gainPct)
                onLog("+${threshold}% milestone on ${ts.symbol}! 🎯", ts.mint)
            }
        }
        if (!pos.isOpen) milestonesHit.remove(ts.mint)

        try {
            if (heldSecs >= 45 && AICrossTalk.isCoordinatedDump(ts.mint, ts.symbol)) {
                val crossTalkSignal = AICrossTalk.analyzeCrossTalk(ts, isOpenPosition = true)
                onLog("🔗🚨 CROSSTALK: ${ts.symbol} COORDINATED DUMP | ${crossTalkSignal.participatingAIs.joinToString("+")} | ${crossTalkSignal.reason}", ts.mint)
                TradeStateMachine.startCooldown(ts.mint)
                return "crosstalk_coordinated_dump"
            }
        } catch (_: Exception) {}
        
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
            entryBuyPressure = ts.meta.pressScore,
            volume = ts.meta.volScore,
            volatility = ts.meta.avgAtr,
            isDistribution = ts.phase == "distribution" && ts.meta.pressScore < 30,
            rsi = ts.meta.rsi,
            momentum = ts.entryScore,
            qualityGrade = ts.meta.setupQuality,
        )
        val exitAiDecision = ExitIntelligence.evaluateExit(exitAiState)
        
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
            else -> {}
        }

        if (!cfg().paperMode && gainPct >= 15) {
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
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("Executor", "Gemini exit advice error: ${e.message}")
            }
        }

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

        val HARD_FLOOR_STOP_PCT = 15.0

        // V5.9.67 ProfitabilityLayer hooks — trailing stop + liquidity drain
        // exit. These run BEFORE the hard floor so in-profit positions get
        // locked in before the −15% emergency cutoff fires.
        ProfitabilityLayer.checkTrailingStop(ts)?.let { reason ->
            onLog("🪢 TRAIL STOP: ${ts.symbol} | $reason", ts.mint)
            TradeStateMachine.startCooldown(ts.mint)
            return reason
        }
        ProfitabilityLayer.checkDrainExit(ts)?.let { reason ->
            onLog("🕳️ DRAIN EXIT: ${ts.symbol} | $reason", ts.mint)
            TradeStateMachine.startCooldown(ts.mint)
            return reason
        }

        if (gainPct <= -HARD_FLOOR_STOP_PCT) {
            onLog("🛑 HARD FLOOR STOP: ${ts.symbol} at ${gainPct.toInt()}% - EMERGENCY EXIT", ts.mint)
            markForRecoveryScan(ts, gainPct, "hard_floor")
            return "hard_floor_stop"
        }
        
        val peakPnlPct = pos.peakGainPct
        val volatility = ts.volatility ?: 50.0
        
        val dynamicStopPct = try {
            val modeDefault = modeConf?.stopLossPct ?: cfg().stopLossPct
            com.lifecyclebot.v3.scoring.FluidLearningAI.getDynamicFluidStop(
                modeDefaultStop = modeDefault,
                currentPnlPct = gainPct,
                peakPnlPct = peakPnlPct,
                holdTimeSeconds = heldSecs,
                volatility = volatility
            )
        } catch (_: Exception) {
            val modeDefault = modeConf?.stopLossPct ?: cfg().stopLossPct
            try {
                -com.lifecyclebot.v3.scoring.FluidLearningAI.getFluidStopLoss(modeDefault)
            } catch (_: Exception) {
                -(modeConf?.stopLossPct ?: cfg().stopLossPct)
            }
        }
        
        if (gainPct <= dynamicStopPct) {
            val stopType = when {
                peakPnlPct > 5.0 -> "trailing_fluid"
                heldSecs < 60 -> "entry_protect"
                else -> "fluid_stop"
            }
            onLog("🛑 DYNAMIC STOP ($stopType): ${ts.symbol} at ${gainPct.toInt()}% (dynamic limit=${dynamicStopPct.toInt()}%)", ts.mint)
            markForRecoveryScan(ts, gainPct, stopType)
            return "${stopType}_loss"
        }

        // V5.9.118: REGRESSION GUARD — if a runner gives back >35% of its peak
        // and the dynamic stop above did NOT fire, something is miswired
        // (this was the UGOR +290%→+50% failure mode that kept regressing).
        // WARN at most once per position so the log screams before we ship.
        if (peakPnlPct >= 100.0 && (peakPnlPct - gainPct) >= 35.0 && !pos.profitFloorRegressionLogged) {
            ErrorLogger.warn("Executor",
                "⚠️ PROFIT-FLOOR REGRESSION: ${ts.symbol} peak=+${peakPnlPct.toInt()}% " +
                "now=+${gainPct.toInt()}% (gave back ${(peakPnlPct - gainPct).toInt()}%) " +
                "but dynamicStop=${dynamicStopPct.toInt()}% didn't fire — locks are misconfigured")
            pos.profitFloorRegressionLogged = true
        }

        if (heldSecs < 90.0) return null

        val currentLiq = ts.lastLiquidityUsd
        val entryLiq = pos.entryLiquidityUsd
        if (entryLiq > 0 && currentLiq > 0) {
            val liqDropPct = ((entryLiq - currentLiq) / entryLiq) * 100
            if (liqDropPct > 50) {
                onLog("🚨 LIQ COLLAPSE: ${ts.symbol} liq dropped ${liqDropPct.toInt()}% | exit NOW", ts.mint)
                return "liquidity_collapse"
            }
            if (liqDropPct > 30 && gainPct < 0) {
                onLog("⚠️ LIQ DRAIN: ${ts.symbol} liq dropped ${liqDropPct.toInt()}% while losing | exit", ts.mint)
                return "liquidity_drain"
            }
        }
        
        if (ts.history.size >= 3 && heldSecs >= 30) {
            val recentCandles = ts.history.takeLast(3)
            val totalSells = recentCandles.sumOf { it.sellsH1 }
            val totalBuys = recentCandles.sumOf { it.buysH1 }
            val sellRatio = if (totalBuys + totalSells > 0) totalSells.toDouble() / (totalBuys + totalSells) else 0.0
            
            if (sellRatio > 0.80 && gainPct > 10 && ts.meta.pressScore < -30) {
                onLog("🐋 WHALE DUMP: ${ts.symbol} sell ratio ${(sellRatio*100).toInt()}% | protecting gains", ts.mint)
                return "whale_dump"
            }
            
            val avgVol = recentCandles.map { it.volumeH1 }.average()
            val lastVol = recentCandles.last().volumeH1
            if (lastVol > avgVol * 3 && sellRatio > 0.70 && gainPct < -5) {
                onLog("🚨 DEV DUMP: ${ts.symbol} volume spike ${(lastVol/avgVol).toInt()}x with heavy sells", ts.mint)
                return "dev_dump"
            }
            
            if (recentCandles.size >= 2 && heldSecs >= 45) {
                val priceStart = recentCandles.first().priceUsd
                val priceEnd = recentCandles.last().priceUsd
                if (priceStart > 0 && priceEnd > 0) {
                    val velocityPct = ((priceEnd - priceStart) / priceStart) * 100
                    
                    if (velocityPct < -15.0 && gainPct < -5) {
                        onLog("⚡ VELOCITY EXIT: ${ts.symbol} price dropping ${velocityPct.toInt()}% rapidly | exit before worse", ts.mint)
                        markForRecoveryScan(ts, gainPct, "velocity_dump")
                        return "velocity_dump"
                    }
                    
                    if (velocityPct < -8.0 && gainPct < -12.0) {
                        onLog("⚡ ACCELERATING LOSS: ${ts.symbol} at ${gainPct.toInt()}% and dropping ${velocityPct.toInt()}%/candle", ts.mint)
                        markForRecoveryScan(ts, gainPct, "accelerating_loss")
                        return "accelerating_loss"
                    }
                }
            }
        }

        val effectiveStopPct = modeConf?.stopLossPct ?: cfg().stopLossPct
        if (gainPct <= -effectiveStopPct) {
            onLog("🛑 BACKUP STOP: ${ts.symbol} at ${gainPct.toInt()}%", ts.mint)
            return "stop_loss"
        }
        
        val trailingStopActive = heldSecs >= 60 || gainPct >= 5.0
        
        val smartFloor = trailingFloor(
            pos = pos,
            current = price,
            modeConf = modeConf,
            emaFanAlignment = ts.meta.emafanAlignment,
            emaFanWidening = ts.meta.emafanAlignment == "BULL_FAN" && ts.meta.volScore >= 55,
            volScore = ts.meta.volScore,
            pressScore = ts.meta.pressScore,
            exhaust = ts.meta.exhaustion,
        )
        
        if (trailingStopActive && price < smartFloor) {
            return "trailing_stop"
        }
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
        normalizePositionScaleIfNeeded(ts)

        val isSellAction = (signal in listOf("SELL", "EXIT")) || 
            (ts.position.isOpen && PrecisionExitLogic.quickCheck(
                mint = ts.mint,
                currentPrice = getActualPrice(ts),
                entryPrice = ts.position.entryPrice,
                stopLossPct = cfg().stopLossPct,
                entryTimeMs = ts.position.entryTime,
            )?.shouldExit == true)
        
        val cbState = security.getCircuitBreakerState()
        if (cbState.isHalted && !ts.position.isOpen) {
            onLog("🛑 Halted (no new buys): ${cbState.haltReason}", ts.mint)
            return
        }
        if (cbState.isHalted && ts.position.isOpen) {
            onLog("⚠️ Halted but allowing sell actions for open position", ts.mint)
        }

        if (ts.position.isOpen) {
            ShadowLearningEngine.onPriceUpdate(
                mint = ts.mint,
                currentPrice = getActualPrice(ts),
                liveStopLossPct = cfg().stopLossPct,
                liveTakeProfitPct = 200.0,
            )
            
            val quickExit = PrecisionExitLogic.quickCheck(
                mint = ts.mint,
                currentPrice = getActualPrice(ts),
                entryPrice = ts.position.entryPrice,
                stopLossPct = cfg().stopLossPct,
                entryTimeMs = ts.position.entryTime,
            )
            if (quickExit != null && quickExit.shouldExit) {
                onLog("🚨 V8 QUICK EXIT: ${ts.symbol} | ${quickExit.reason} | ${quickExit.details}", ts.mint)
                doSell(ts, "v8_${quickExit.reason.lowercase()}", wallet, walletSol)
                TradeStateMachine.startCooldown(ts.mint)
                return
            }
        }

        val freshness = security.checkDataFreshness(lastPollMs)
        if (freshness is GuardResult.Block) {
            onLog("⚠ ${freshness.reason}", ts.mint)
            return
        }

        // V5.9.212: Update highestPrice/lowestPrice UNCONDITIONALLY before any exit path
        // (Audit #4 fix: was only updated inside riskCheck — missed when checkProfitLock returned early)
        if (ts.position.isOpen) {
            val _unconditionalPrice = getActualPrice(ts)
            if (_unconditionalPrice > 0.0) {
                ts.position.highestPrice = maxOf(ts.position.highestPrice, _unconditionalPrice)
                if (ts.position.lowestPrice == 0.0 || _unconditionalPrice < ts.position.lowestPrice)
                    ts.position.lowestPrice = _unconditionalPrice
            }
        }

        if (ts.position.isOpen) {
            if (checkProfitLock(ts, wallet, walletSol)) {
                return
            }
        }

        if (ts.position.isOpen) checkPartialSell(ts, wallet, walletSol)

        val reason = riskCheck(ts, modeConfig)
        if (reason != null) { doSell(ts, reason, wallet, walletSol); return }
        
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

        if (ts.position.isOpen && !ts.position.isLongHold && cfg().longHoldEnabled) {
            val promotionSize = ts.position.costSol
            if (!ts.position.isOpen || promotionSize <= 0.0) {
                ErrorLogger.info("Executor", "[PROMOTION_BLOCKED] ${ts.symbol} | invalid_size_or_closed")
            } else {
                val gainPct   = pct(ts.position.entryPrice, getActualPrice(ts))
                val c         = cfg()
                val holders   = ts.history.lastOrNull()?.holderCount ?: 0
                val existingLH = 0.0

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
        }

        if (cfg().autoTrade && ts.position.isOpen && ts.meta.topUpReady) {
            val topUpReady = shouldTopUp(
                ts              = ts,
                entryScore      = entryScore,
                exitScore       = ts.exitScore,
                emafanAlignment = ts.meta.emafanAlignment,
                volScore        = ts.meta.volScore,
                exhaust         = ts.meta.exhaustion,
            )
            if (topUpReady) {
                doTopUp(ts, walletSol, wallet, totalExposureSol)
            }
        }

        if (cfg().paperMode && ts.position.isOpen && !ts.position.isFullyBuilt) {
            val result = shouldGraduatedAdd(ts.position, getActualPrice(ts), ts.meta.volScore)
            if (result != null) {
                val (addSol, newPhase) = result
                doGraduatedAdd(ts, addSol, newPhase)
            }
        }

        val shouldActOnBuy = cfg().paperMode || cfg().autoTrade
        
        if (signal == "BUY") {
            ErrorLogger.debug("Executor", "BUY CHECK: ${ts.symbol} | shouldAct=$shouldActOnBuy | posOpen=${ts.position.isOpen} | autoTrade=${cfg().autoTrade} | paper=${cfg().paperMode}")
        }
        
        if (signal == "BUY" && !ts.position.isOpen) {
            ErrorLogger.error("Executor", "🚨 LEGACY BUY PATH BLOCKED: ${ts.symbol} | " +
                "All new entries MUST go through FDG. This is a code architecture bug.")
            onLog("⛔ ${ts.symbol}: Legacy buy path blocked - use FDG flow", ts.mint)
            return
        }
        
        if (false && shouldActOnBuy && signal == "BUY" && !ts.position.isOpen) {
            val isPaper = cfg().paperMode
            ErrorLogger.info("Executor", "🔔 BUY signal for ${ts.symbol} | paper=$isPaper | wallet=${walletSol.fmt(4)} | autoTrade=${cfg().autoTrade}")
            
            val severeLossThreshold = -33.0
            val lastExitPnl = ts.lastExitPnlPct
            if (lastExitPnl < severeLossThreshold) {
                ErrorLogger.info("Executor", "🚫 ${ts.symbol} QUARANTINED: Previous exit was ${lastExitPnl.toInt()}% (< $severeLossThreshold%)")
                onLog("💀 ${ts.symbol}: QUARANTINED (rugged ${lastExitPnl.toInt()}%)", ts.mint)
                RuggedContracts.add(ts.mint, ts.symbol, lastExitPnl)
                return
            }
            
            if (RuggedContracts.isBlacklisted(ts.mint)) {
                ErrorLogger.info("Executor", "🚫 ${ts.symbol} BLACKLISTED: Previously rugged")
                onLog("💀 ${ts.symbol}: Blacklisted contract", ts.mint)
                return
            }
            
            val tradeState = TradeStateMachine.getState(ts.mint)
            val isPaperMode = cfg().paperMode
            // V5.9.46: Proven-edge inheritance — once the user has demonstrated
            // paper performance, live mode drops the extra cooldown + pattern
            // strictness (which otherwise makes the live scanner look dead).
            val provenEdge = try {
                TradeHistoryStore.getProvenEdgeCached().hasProvenEdge
            } catch (_: Exception) { false }
            val lenientMode = isPaperMode || provenEdge

            if (!lenientMode && TradeStateMachine.isInCooldown(ts.mint)) {
                val lastTrade = ts.trades.lastOrNull()
                val wasProfit = lastTrade?.let { it.side == "SELL" && (it.pnlPct ?: 0.0) > 0 } ?: false
                val priceDroppedFromExit = lastTrade?.let { getActualPrice(ts) < it.price * 0.85 } ?: false
                val scoreImproved = entryScore >= 50
                
                if (wasProfit && priceDroppedFromExit && scoreImproved) {
                    onLog("🔄 RE-ENTRY: ${ts.symbol} dipped 15%+ from profitable exit, score=$entryScore", ts.mint)
                    TradeStateMachine.clearCooldown(ts.mint)
                } else {
                    onLog("⏸️ ${ts.symbol}: In cooldown, skipping", ts.mint)
                    return
                }
            }
            
            if (tradeState.state == TradeState.SCAN) {
                TradeStateMachine.setState(ts.mint, TradeState.WATCH, "BUY signal received")
            }
            
            val priceHistory = ts.history.map { it.priceUsd }
            val optimalEntry = if (lenientMode) true else TradeStateMachine.detectEntryPattern(ts.mint, getActualPrice(ts), priceHistory)
            
            val c = cfg()
            val requireOptimalEntry = !lenientMode && c.smallBuySol < 0.1
            
            if (requireOptimalEntry && !optimalEntry && tradeState.entryPattern != EntryPattern.NONE) {
                if (tradeState.entryPattern == EntryPattern.FIRST_SPIKE) {
                    onLog("📈 ${ts.symbol}: Spike detected, waiting for pullback...", ts.mint)
                } else if (tradeState.entryPattern == EntryPattern.PULLBACK) {
                    onLog("📉 ${ts.symbol}: Pullback detected, waiting for re-acceleration...", ts.mint)
                }
                return
            }
            
            if (optimalEntry && !isPaperMode) {
                onLog("🎯 ${ts.symbol}: OPTIMAL ENTRY - Spike→Pullback→ReAccel pattern!", ts.mint)
            }
            
            TradeStateMachine.setState(ts.mint, TradeState.ENTER, "executing buy")
            
            if (ts.position.isOpen) {
                ErrorLogger.debug("Executor", "Skipping ${ts.symbol} - position already open")
                return
            }
            if (cfg().scalingLogEnabled) { val _spx=WalletManager.lastKnownSolPrice; val (_tier,_)=ScalingMode.maxPositionForToken(ts.lastLiquidityUsd,ts.lastFdv,TreasuryManager.treasurySol*_spx,_spx); if(_tier!=ScalingMode.Tier.MICRO) onLog("${_tier.icon} ${_tier.label}: ${ts.symbol}", ts.mint) }
            
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
            
            var walletIntelligenceBlocked = false
            val alphaSignals = try {
                runBlocking {
                    withTimeoutOrNull(3000L) {
                        DataPipeline.getAlphaSignals(ts.mint, cfg()) { msg ->
                            ErrorLogger.debug("DataPipeline", msg)
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("DataPipeline", "Error fetching alpha signals: ${e.message}")
                null
            }
            
            if (alphaSignals != null && !isPaper) {
                if (alphaSignals.repeatWalletScore > 60.0) {
                    onLog("🤖 WALLET INTEL: Bot farm detected (repeat wallets ${alphaSignals.repeatWalletScore.toInt()}%) — blocking", ts.mint)
                    walletIntelligenceBlocked = true
                }
                if (alphaSignals.volumePriceDivergence > 70.0) {
                    onLog("📉 WALLET INTEL: Distribution detected (vol/price div ${alphaSignals.volumePriceDivergence.toInt()}) — blocking", ts.mint)
                    walletIntelligenceBlocked = true
                }
                if (alphaSignals.whaleRatio > 0.6) {
                    onLog("🐋 WALLET INTEL: Whale concentration too high (${(alphaSignals.whaleRatio * 100).toInt()}%) — blocking", ts.mint)
                    walletIntelligenceBlocked = true
                }
                if (alphaSignals.overallGrade in listOf("D", "F")) {
                    onLog("⚠️ WALLET INTEL: Low grade (${alphaSignals.overallGrade}) — ${DataPipeline.formatAlphaSignals(ts.mint, alphaSignals)}", ts.mint)
                }
            }
            
            if (walletIntelligenceBlocked) {
                ErrorLogger.info("Executor", "❌ ${ts.symbol} blocked by wallet intelligence")
                return
            }
            
            val setupQuality = ts.meta.setupQuality
            val isLowQuality = setupQuality == "C"
            val isUnknownPhase = ts.phase.contains("unknown", ignoreCase = true)
            val isLowConfidence = aiConfidence < 30.0
            
            // V5.9.174 — the user reported that the meme-trader collapsed from
            // thousands of trades/day to <200. This trinity block (C + unknown
            // + low-conf) was killing every fresh-launch candidate even in
            // paper mode. Paper is for LEARNING — hard-kill is wrong here.
            // In paper we now let the fluid size multiplier + FDG confidence
            // floor handle weak setups instead of bailing outright.
            if (isLowQuality && isUnknownPhase && isLowConfidence && !isPaper) {
                ErrorLogger.info("Executor", "❌ ${ts.symbol} BLOCKED: C quality + unknown phase + low conf (${aiConfidence.toInt()}%) [LIVE]")
                onLog("🚫 ${ts.symbol}: Blocked (C + unknown + low conf)", ts.mint)
                return
            }
            if (isLowQuality && isUnknownPhase && isLowConfidence && isPaper) {
                // Paper: log the warning but LET IT TRADE so the education
                // layer can actually learn what works from this combo.
                ErrorLogger.info("Executor", "🎓 ${ts.symbol} probe-buy: C+unknown+conf${aiConfidence.toInt()}% — size clamped via redFlagCount, NOT blocked")
            }
            
            val redFlagCount = listOf(isLowQuality, isUnknownPhase, isLowConfidence).count { it }
            val qualityPenalty = when (redFlagCount) {
                2 -> 0.25
                1 -> 0.60
                else -> 1.0
            }
            
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
                ts = ts,  // V5.9.69: enable PatternClassifier
            )
            
            if (qualityPenalty < 1.0) {
                val oldSize = size
                size *= qualityPenalty
                ErrorLogger.info("Executor", "📉 ${ts.symbol} size reduced: ${oldSize.fmt(3)} → ${size.fmt(3)} (penalty=${qualityPenalty}x, redFlags=$redFlagCount)")
            }

            if (c.crossTokenGuardEnabled) {
                val windowMs = (c.crossTokenWindowMins * 60_000.0).toLong()
                val cutoff   = System.currentTimeMillis() - windowMs
                val solPxCG  = WalletManager.lastKnownSolPrice
                val trsUsdCG = TreasuryManager.treasurySol * solPxCG
                val thisTier = ScalingMode.tierForToken(ts.lastLiquidityUsd, ts.lastFdv)
                ts.recentEntryTimes.removeIf { it < cutoff }
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
            modeConfig?.let { size = size * it.positionSizeMultiplier }
            
            // V5.9.61: was a live-only `brain.shouldSkipTrade()` block that
            // silently killed trades. Paper didn't apply it, so live was
            // stricter. With V5.9.59's entryThresholdDelta cap at +8 and
            // the drought watchdog, this extra gate is redundant and
            // causes exactly the paper-vs-live divergence users report.
            // Removed entirely — the same brain already influences
            // entryScore / sizing earlier in the pipeline.
            
            if (size < 0.001) {
                ErrorLogger.debug("Executor", "🪫 ${ts.symbol} SIZE TOO SMALL: $size | wallet=$walletSol | paper=$isPaperMode | liq=${ts.lastLiquidityUsd}")
                onLog("Insufficient capacity for new position on ${ts.symbol} (size=$size)", ts.mint)
                return
            }
            
            ErrorLogger.info("Executor", "✅ ${ts.symbol} SIZE OK: $size SOL - proceeding to doBuy()")

            ShadowLearningEngine.onTradeOpportunity(
                mint = ts.mint,
                symbol = ts.symbol,
                currentPrice = getActualPrice(ts),
                liveEntryScore = entryScore.toInt(),
                liveEntryThreshold = 42,
                liveSizeSol = size,
                phase = ts.phase,
            )

            doBuy(ts, size, entryScore, wallet, walletSol, null, setupQuality)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // PRIORITY 2: UNIFIED CANDIDATE DECISION SUPPORT
    // ══════════════════════════════════════════════════════════════════
    
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
        tradeIdentity: TradeIdentity? = null,
        fdgApprovalClass: FinalDecisionGate.ApprovalClass? = null,
    ) {
        normalizePositionScaleIfNeeded(ts)
        val identity = tradeIdentity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        fdgApprovalClass?.let { identity.fdgApprovalClass = it.name }
        
        val cbState = security.getCircuitBreakerState()
        if (cbState.isHalted) {
            onLog("🛑 Halted: ${cbState.haltReason}", identity.mint)
            return
        }
        
        if (ts.position.isOpen) {
            try {
                val currentPnlPct = ((getActualPrice(ts) - ts.position.entryPrice) / ts.position.entryPrice) * 100
                val holdEval = kotlinx.coroutines.runBlocking {
                    HoldingLogicLayer.evaluatePosition(
                        position = ts.position,
                        ts = ts,
                        currentPnlPct = currentPnlPct,
                        isPaperMode = cfg().paperMode,
                    )
                }
                
                if (holdEval.action == HoldingLogicLayer.HoldAction.SWITCH_MODE && 
                    holdEval.modeSwitchRecommendation?.shouldSwitch == true) {
                    val rec = holdEval.modeSwitchRecommendation
                    val oldMode = ts.position.tradingMode
                    val oldEmoji = ts.position.tradingModeEmoji
                    
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
                
                if (holdEval.urgency == HoldingLogicLayer.Urgency.HIGH || 
                    holdEval.urgency == HoldingLogicLayer.Urgency.CRITICAL) {
                    ErrorLogger.debug("HoldingLogic", "${identity.symbol}: ${holdEval.action} - ${holdEval.reason}")
                }
            } catch (e: Exception) {
                ErrorLogger.debug("HoldingLogic", "Evaluation error for ${identity.symbol}: ${e.message}")
            }
            
            try {
                val currentPnl = ((getActualPrice(ts) - ts.position.entryPrice) / ts.position.entryPrice) * 100
                val mcapChange = if (ts.position.entryMcap > 0) {
                    ((ts.lastMcap - ts.position.entryMcap) / ts.position.entryMcap) * 100
                } else 0.0
                val liquidityChange = if (ts.position.entryLiquidityUsd > 0) {
                    ((ts.lastLiquidityUsd - ts.position.entryLiquidityUsd) / ts.position.entryLiquidityUsd) * 100
                } else 0.0
                val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
                val tokenAgeMs = System.currentTimeMillis() - ts.addedToWatchlistAt
                
                if (AICrossTalk.shouldCheckModeSwitch(ts.mint, mcapChange, liquidityChange, currentPnl)) {
                    val modeSwitchSignal = AICrossTalk.evaluateModeSwitchCrossTalk(
                        mint = ts.mint,
                        symbol = identity.symbol,
                        currentMode = ts.position.tradingMode,
                        mcap = ts.lastMcap,
                        liquidity = ts.lastLiquidityUsd,
                        ageMs = tokenAgeMs,
                        currentPnlPct = currentPnl,
                        holdTimeMs = holdTimeMs,
                    )
                    
                    if (modeSwitchSignal.shouldSwitch && modeSwitchSignal.confidence >= 70.0) {
                        val oldMode = ts.position.tradingMode
                        val oldEmoji = ts.position.tradingModeEmoji
                        val newEmoji = HoldingLogicLayer.getModeEmoji(modeSwitchSignal.recommendedMode)
                        
                        ts.position.tradingMode = modeSwitchSignal.recommendedMode
                        ts.position.tradingModeEmoji = newEmoji
                        ts.position.modeHistory = if (ts.position.modeHistory.isEmpty()) {
                            "$oldMode>${modeSwitchSignal.recommendedMode}"
                        } else {
                            "${ts.position.modeHistory}>${modeSwitchSignal.recommendedMode}"
                        }
                        
                        onLog("🔗🔄 CROSSTALK SWITCH: ${identity.symbol} | $oldEmoji $oldMode → $newEmoji ${modeSwitchSignal.recommendedMode} | ${modeSwitchSignal.participatingAIs.joinToString("+")} | ${modeSwitchSignal.reason}", identity.mint)
                        ErrorLogger.info("CrossTalk", "Mode switch applied: ${identity.symbol} $oldMode→${modeSwitchSignal.recommendedMode} (conf=${modeSwitchSignal.confidence.toInt()}%)")
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug("CrossTalk", "Mode switch eval error for ${identity.symbol}: ${e.message}")
            }
            
            val quickExit = PrecisionExitLogic.quickCheck(
                mint = identity.mint,
                currentPrice = getActualPrice(ts),
                entryPrice = ts.position.entryPrice,
                stopLossPct = cfg().stopLossPct,
                entryTimeMs = ts.position.entryTime,
            )
            if (quickExit != null && quickExit.shouldExit) {
                onLog("🚨 V8 QUICK EXIT: ${identity.symbol} | ${quickExit.reason} | ${quickExit.details}", identity.mint)
                doSell(ts, "v8_${quickExit.reason.lowercase()}", wallet, walletSol, identity)
                TradeStateMachine.startCooldown(identity.mint)
                return
            }
            
            checkPartialSell(ts, wallet, walletSol)
            
            val reason = riskCheck(ts, modeConfig)
            if (reason != null) {
                doSell(ts, reason, wallet, walletSol, identity)
                return
            }
            
            if (decision.finalSignal in listOf("SELL", "EXIT")) {
                doSell(ts, decision.finalSignal.lowercase(), wallet, walletSol, identity)
                return
            }
            
            if (modeConfig != null) {
                val held = (System.currentTimeMillis() - ts.position.entryTime) / 60_000.0
                val tf = ts.candleTimeframeMinutes.toDouble().coerceAtLeast(1.0)
                if (held > modeConfig.maxHoldMins * tf) {
                    doSell(ts, "mode_maxhold_${modeConfig.mode.name.lowercase()}", wallet, walletSol, identity)
                    return
                }
            }
            
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
                val result = shouldGraduatedAdd(ts.position, getActualPrice(ts), decision.meta.volScore)
                if (result != null) {
                    val (addSol, newPhase) = result
                    doGraduatedAdd(ts, addSol, newPhase)
                }
            }
            
            return
        }
        
        val shouldActOnBuy = cfg().paperMode || cfg().autoTrade
        if (!shouldActOnBuy) {
            ErrorLogger.debug("Executor", "📊 ${ts.symbol}: Buy skipped - autoTrade disabled")
            return
        }
        
        if (!decision.shouldTrade) {
            val reason = if (decision.blockReason.isNotEmpty()) decision.blockReason else "legacy_shouldTrade=false"
            ErrorLogger.debug("Executor", "📊 ${ts.symbol}: Legacy would block ($reason) - V3 will evaluate")
        }
        
        val isPaper = cfg().paperMode
        ErrorLogger.info("Executor", "🔔 UNIFIED BUY: ${ts.symbol} | " +
            "quality=${decision.finalQuality} | edge=${decision.edgePhase} | " +
            "conf=${decision.aiConfidence.toInt()}% | penalty=${decision.qualityPenalty} | " +
            "paper=$isPaper | autoTrade=${cfg().autoTrade}")

        // V5.9.91: SmartChart veto. If the multi-timeframe scanner reads >=80%
        // bearish with >=70 confidence in the last 2 min, skip the LONG entry.
        // This fixes the contradictory log pattern where SmartChart said
        // "BEARISH (100%)" and V3 still ran EXECUTE_AGGRESSIVE on the same tick.
        run {
            val bearish = SmartChartCache.getBearishConfidence(ts.mint)
            if (bearish != null && bearish >= 80.0) {
                ErrorLogger.info("Executor", "🚫 SMARTCHART_BLOCK: ${ts.symbol} | bearish=${bearish.toInt()}% — skipping LONG entry")
                onLog("🚫 ${ts.symbol}: SmartChart ${bearish.toInt()}% bearish — skip entry", ts.mint)
                return
            }
        }
        
        if (ts.history.size >= 3) {
            val recentCandles = ts.history.takeLast(3)
            val priceStart = recentCandles.first().priceUsd
            val priceEnd = recentCandles.last().priceUsd
            if (priceStart > 0 && priceEnd > 0) {
                val velocityPct = ((priceEnd - priceStart) / priceStart) * 100
                
                if (velocityPct < -5.0) {
                    ErrorLogger.debug("Executor", "⚡ ${ts.symbol} VELOCITY BLOCK: Price dropping ${velocityPct.toInt()}% rapidly")
                    onLog("⚡ ${ts.symbol}: Price dropping ${velocityPct.toInt()}% - waiting for stabilization", ts.mint)
                    return
                }
            }
        }
        
        if (RuggedContracts.isBlacklisted(ts.mint)) {
            ErrorLogger.info("Executor", "🚫 ${ts.symbol} BLACKLISTED: Previously rugged")
            onLog("💀 ${ts.symbol}: Blacklisted contract", ts.mint)
            return
        }
        
        if (!isPaper && TradeStateMachine.isInCooldown(ts.mint)) {
            onLog("⏸️ ${ts.symbol}: In cooldown, skipping", ts.mint)
            return
        }
        
        TradeStateMachine.setState(ts.mint, TradeState.ENTER, "executing buy via unified decision")
        
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
            ts = ts,  // V5.9.69: enable PatternClassifier
        )
        
        if (fdgApprovedSize == null) {
            if (decision.qualityPenalty < 1.0 && decision.qualityPenalty > 0.0) {
                val oldSize = size
                size *= decision.qualityPenalty
                ErrorLogger.info("Executor", "📉 ${ts.symbol} size reduced: ${oldSize.fmt(3)} → ${size.fmt(3)} " +
                    "(penalty=${decision.qualityPenalty}x, redFlags=${decision.redFlagCount})")
            }
            
            modeConfig?.let { size *= it.positionSizeMultiplier }
        }
        
        if (!isPaper) {
            brain?.let { b ->
                if (b.shouldSkipTrade(decision.phase, decision.meta.emafanAlignment, ts.source, decision.entryScore)) {
                    onLog("🧠 Brain SKIP: ${ts.symbol} — too many risk factors", ts.mint)
                    return
                }
            }
        }
        
        if (size < 0.001) {
            ErrorLogger.debug("Executor", "🪫 ${ts.symbol} SIZE TOO SMALL: $size | quality=${decision.finalQuality}")
            onLog("Insufficient capacity for ${ts.symbol} (size=$size)", ts.mint)
            return
        }
        
        if (isPaper) {
            ErrorLogger.info("Executor", "📄 ${ts.symbol} PAPER BUY: $size SOL - quality=${decision.finalQuality}")
        } else {
            ErrorLogger.info("Executor", "💰 ${ts.symbol} LIVE BUY ATTEMPT: $size SOL - " +
                "quality=${decision.finalQuality} | wallet=$walletSol | autoTrade=${cfg().autoTrade}")
            onLog("💰 LIVE BUY: ${ts.symbol} | ${size.fmt(4)} SOL | quality=${decision.finalQuality}", ts.mint)
        }
        
        ShadowLearningEngine.onTradeOpportunity(
            mint = ts.mint,
            symbol = ts.symbol,
            currentPrice = getActualPrice(ts),
            liveEntryScore = decision.entryScore.toInt(),
            liveEntryThreshold = 42,
            liveSizeSol = size,
            phase = decision.phase,
        )
        
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
        normalizePositionScaleIfNeeded(ts)
        val pos  = ts.position
        val c    = cfg()
        val size = topUpSizeSol(pos, walletSol, totalExposureSol)

        if (size < 0.001) {
            onLog("⚠ Top-up skipped: size too small (${size})", ts.mint)
            return
        }

        val gainPct = pct(pos.entryPrice, getActualPrice(ts))
        onLog("🔺 TOP-UP #${pos.topUpCount + 1}: ${ts.symbol} " +
              "+${gainPct.toInt()}% gain | adding ${size.fmt(4)} SOL " +
              "(total will be ${(pos.costSol + size).fmt(4)} SOL)", ts.mint)

        if (c.paperMode || wallet == null) {
            paperTopUp(ts, size)
        } else {
            val guard = security.checkBuy(
                mint         = ts.mint,
                symbol       = ts.symbol,
                solAmount    = size,
                walletSol    = walletSol,
                currentPrice = getActualPrice(ts),
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
        val price = getActualPrice(ts)
        if (price <= 0) return

        val newQty    = sol / maxOf(price, 1e-12)
        val totalQty  = pos.qtyToken + newQty
        val totalCost = pos.costSol + sol

        ts.position = pos.copy(
            qtyToken       = totalQty,
            entryPrice     = totalCost / totalQty,
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
            val price  = getActualPrice(ts)
            val newQty = rawTokenAmountToUiAmount(ts, quote.outAmount, solAmount = sol, priceUsd = price)

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

    internal fun doBuy(ts: TokenState, sol: Double, score: Double,
                      wallet: SolanaWallet?, walletSol: Double,
                      identity: TradeIdentity? = null,
                      quality: String = "C",
                      skipGraduated: Boolean = false) {
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)

        // V5.9.401 — Sentience hook #1: LLM second-opinion veto (cached, fail-open).
        if (!com.lifecyclebot.engine.SentienceHooks.preTradeVeto(
                symbol = ts.symbol, score = score.toInt(), conf = quality.hashCode().rem(100),
                reasons = "src=${ts.source} liq=${ts.lastLiquidityUsd.toInt()}")) {
            onLog("🛑 LLM SENTIENCE VETO: ${ts.symbol} blocked by pre-trade LLM check", tradeId.mint)
            return
        }

        // V5.9.401 — Sentience hook #7: dynamic size scaling (0.5..1.5×, default 1.0).
        val sizeMult = try {
            com.lifecyclebot.engine.SentienceHooks.suggestSizeMultiplier(
                engine = "MEME", symbol = ts.symbol, regime = ts.source
            )
        } catch (_: Throwable) { 1.0 }

        // V5.9.402 — Lab Promoted Feed: proven LLM strategies nudge live entries.
        val labNudge = try {
            com.lifecyclebot.engine.lab.LabPromotedFeed.entryNudge(
                asset = com.lifecyclebot.engine.lab.LabAssetClass.MEME,
                score = score.toInt(),
            )
        } catch (_: Throwable) { null }
        // If a promoted Lab strategy says the score is too weak, skip the entry.
        if (labNudge != null && score.toInt() < labNudge.scoreFloor) {
            onLog("🧪 LAB FLOOR: ${ts.symbol} score=${score.toInt()} < floor ${labNudge.scoreFloor} (${labNudge.strategyName})", tradeId.mint)
            return
        }
        // Real-money guardrail: live mode + un-authorised promoted strategy
        // → queue an approval and bail. Paper mode is unrestricted.
        if (!cfg().paperMode && labNudge != null &&
            com.lifecyclebot.engine.lab.LabPromotedFeed.requireLiveApproval(labNudge.strategyId)) {
            try {
                com.lifecyclebot.engine.lab.LlmLabEngine.requestSingleLiveTrade(
                    strategyId = labNudge.strategyId,
                    symbol = ts.symbol,
                    amountSol = sol,
                    reason = "Lab strategy '${labNudge.strategyName}' wants to spend ${"%.3f".format(sol)}◎ on ${ts.symbol} (score=${score.toInt()}).",
                )
            } catch (_: Throwable) {}
            onLog("🧪 LAB AWAITING APPROVAL: ${labNudge.strategyName} → ${ts.symbol} (live trade queued)", tradeId.mint)
            return
        }
        val labMult = labNudge?.sizeMultiplier ?: 1.0
        val effSol = (sol * sizeMult * labMult).coerceIn(sol * 0.5, sol * 1.75)

        if (cfg().paperMode || wallet == null) {
            paperBuy(ts, effSol, score, tradeId, quality, skipGraduated, wallet, walletSol)
        } else {
            val guard = security.checkBuy(
                mint         = tradeId.mint,
                symbol       = tradeId.symbol,
                solAmount    = effSol,
                walletSol    = walletSol,
                currentPrice = getActualPrice(ts),
                currentVol   = ts.history.lastOrNull()?.vol ?: 0.0,
                liquidityUsd = ts.lastLiquidityUsd,
            )
            when (guard) {
                is GuardResult.Block -> {
                    onLog("🚫 Buy blocked: ${guard.reason}", tradeId.mint)
                    sounds?.playBlockSound()
                    if (guard.fatal) onNotify("🛑 Bot Halted", guard.reason, com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    
                    if (cfg().shadowPaperEnabled) {
                        runShadowPaperBuy(ts, effSol, score, quality, "blocked:${guard.reason.take(20)}", wallet, walletSol)
                    }
                    return
                }
                is GuardResult.Allow -> {
                    // V5.9.9: Cross-trader exposure check
                    if (!WalletPositionLock.canOpen("Meme", effSol, walletSol)) {
                        onLog("🔒 Exposure cap: ${ts.symbol} blocked (wallet ${WalletPositionLock.getExposurePct(walletSol).toInt()}% deployed)", tradeId.mint)
                        if (cfg().shadowPaperEnabled) {
                            runShadowPaperBuy(ts, effSol, score, quality, "exposure_cap", wallet, walletSol)
                        }
                        return
                    }
                    liveBuy(ts, effSol, score, wallet, walletSol, tradeId, quality, skipGraduated)
                    WalletPositionLock.recordOpen("Meme", effSol)
                    
                    if (cfg().shadowPaperEnabled) {
                        runShadowPaperBuy(ts, effSol, score, quality, "parallel", wallet, walletSol)
                    }
                }
            }
        }
    }
    
    private fun runShadowPaperBuy(ts: TokenState, sol: Double, score: Double, 
                                   quality: String, reason: String,
                                   wallet: SolanaWallet? = null, walletSol: Double = 0.0) {
        try {
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
            
            if (shadowPositions.size >= MAX_SHADOW_POSITIONS) {
                val oldest = shadowPositions.values.minByOrNull { it.entryTime }
                oldest?.let { shadowPositions.remove(it.mint) }
            }
            
            if (shadowPositions.containsKey(ts.mint)) return
            
            val price = getActualPrice(ts)
            if (price <= 0) return
            
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
            ErrorLogger.debug("Executor", "Shadow paper buy failed: ${e.message}")
        }
    }
    
    fun checkShadowPositions(tokenStates: Map<String, TokenState>) {
        if (!cfg().shadowPaperEnabled || cfg().paperMode) return
        
        val toRemove = mutableListOf<String>()
        val stopLossPct = cfg().stopLossPct
        val takeProfitPct = 50.0
        
        for ((mint, shadow) in shadowPositions) {
            val ts = tokenStates[mint] ?: continue
            val currentPrice = getActualPrice(ts)
            if (currentPrice <= 0) continue
            
            val pnlPct = ((currentPrice - shadow.entryPrice) / shadow.entryPrice) * 100
            val holdTimeMin = (System.currentTimeMillis() - shadow.entryTime) / 60000
            
            val shouldExit = when {
                pnlPct <= -stopLossPct -> "stop_loss"
                pnlPct >= takeProfitPct -> "take_profit"
                holdTimeMin >= 30 -> "timeout_30min"
                else -> null
            }
            
            if (shouldExit != null) {
                val isWin = pnlPct >= 1.0  // V5.9.225: unified 1% threshold
                val pnlSol = pnlPct * shadow.entrySol / 100
                val shadowHoldMins = (System.currentTimeMillis() - shadow.entryTime) / 60_000.0
                
                brain?.learnFromTrade(
                    isWin = isWin,
                    phase = "shadow_${shadow.quality}",
                    emaFan = "FLAT",
                    source = shadow.source,
                    pnlPct = pnlPct,
                    mint = shadow.mint,
                    rugcheckScore = 50,
                    buyPressure = 50.0,
                    topHolderPct = 10.0,
                    liquidityUsd = 10000.0,
                    isLiveTrade = false,
                    approvalClass = "PAPER_EXPLORATION",
                    holdTimeMinutes = shadowHoldMins,
                    maxGainPct = pnlPct.coerceAtLeast(0.0),
                    exitReason = shouldExit,
                    tokenAgeMinutes = 0.0,
                )
                
                brain?.learnThreshold(
                    isWin = isWin,
                    rugcheckScore = 50,
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
        
        toRemove.forEach { shadowPositions.remove(it) }
    }

    fun paperBuy(ts: TokenState, sol: Double, score: Double, identity: TradeIdentity? = null, 
                 quality: String = "C", skipGraduated: Boolean = false,
                 wallet: SolanaWallet? = null, walletSol: Double = 0.0,
                 layerTag: String = "",            // V5.9.386 — override BUY trade tradingMode for sub-trader journal tagging
                 layerTagEmoji: String = "") {     // V5.9.386 — matching emoji for the sub-trader tag
        
        if (sol <= 0 || sol.isNaN() || sol.isInfinite()) {
            ErrorLogger.warn("Executor", "[EXECUTION/INVALID] Paper buy skipped: invalid size $sol for ${ts.symbol}")
            return
        }
        if (ts.mint.isBlank() || ts.symbol.isBlank()) {
            ErrorLogger.warn("Executor", "[EXECUTION/INVALID] Paper buy skipped: empty mint/symbol")
            return
        }
        if (score < 0 || score.isNaN()) {
            ErrorLogger.warn("Executor", "[EXECUTION/INVALID] Paper buy skipped: invalid score $score for ${ts.symbol}")
            return
        }
        
        PipelineTracer.executorStart(ts.symbol, ts.mint, "PAPER", sol)

        // NOTE: paperBuy() is only reached when cfg().paperMode == true (see doBuy).
        // No live-buy override here — paper mode means paper mode, full stop.
        // The shadow buy path (runShadowPaperBuy) handles the live-override case correctly
        // with an explicit !cfg().paperMode guard.

        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        normalizePositionScaleIfNeeded(ts)
        val price = getActualPrice(ts)
        if (price <= 0) {
            ErrorLogger.debug("Executor", "Paper buy skipped: no valid price for ${tradeId.symbol}")
            return
        }
        if (ts.position.isOpen) {
            onLog("⚠ Buy skipped: position already open", tradeId.mint); return
        }
        
        val currentLayer = "PAPER"
        if (EmergentGuardrails.shouldBlockMultiLayerEntry(tradeId.mint, currentLayer)) {
            onLog("⚠ Buy skipped: ${tradeId.symbol} already open in different layer", tradeId.mint)
            return
        }
        
        val actualSol: Double
        val buildPhase: Int
        val targetBuild: Double
        
        if (skipGraduated || quality == "C") {
            actualSol = sol
            buildPhase = if (quality != "C") 1 else 3
            targetBuild = if (quality != "C") sol / graduatedInitialPct(quality) else 0.0
        } else {
            actualSol = graduatedInitialSize(sol, quality)
            buildPhase = 1
            targetBuild = sol
        }
        
        val simulatedSlippagePct = when {
            ts.lastLiquidityUsd < 5000 -> 3.0
            ts.lastLiquidityUsd < 20000 -> 1.5
            ts.lastLiquidityUsd < 50000 -> 0.8
            else -> 0.4
        }
        val slippageMultiplier = 1.0 + (simulatedSlippagePct / 100.0)
        val effectivePrice = price * slippageMultiplier
        
        val simulatedFeePct = 0.5
        val effectiveSol = actualSol * (1.0 - simulatedFeePct / 100.0)
        
        val currentMode = try {
            val tokenAgeMs = System.currentTimeMillis() - ts.addedToWatchlistAt
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
            try {
                UnifiedModeOrchestrator.getCurrentPrimaryMode()
            } catch (_: Exception) {
                UnifiedModeOrchestrator.ExtendedMode.STANDARD
            }
        }
        
        ts.position = Position(
            qtyToken     = effectiveSol / maxOf(effectivePrice, 1e-12),
            entryPrice   = effectivePrice,
            entryTime    = System.currentTimeMillis(),
            costSol      = actualSol,
            highestPrice = effectivePrice,
            entryPhase   = ts.phase,
            entryScore   = score,
            entryLiquidityUsd = ts.lastLiquidityUsd,
            entryMcap    = ts.lastMcap,
            isPaperPosition = true,
            // V5.9.386 — use sub-trader tag when provided; caller still may
            // overwrite ts.position.tradingMode post-buy (e.g., Quality path
            // overrides "BLUE_CHIP" → "QUALITY"), but default is now correct.
            tradingMode  = if (layerTag.isNotBlank()) layerTag else currentMode.name,
            tradingModeEmoji = if (layerTagEmoji.isNotBlank()) layerTagEmoji else currentMode.emoji,
            buildPhase   = buildPhase,
            targetBuildSol = targetBuild,
        )
        // V5.9.123 — register in CorrelationHedgeAI so other new-entry scoring
        // sees this position as cluster peer pressure.
        try {
            com.lifecyclebot.v3.scoring.CorrelationHedgeAI.registerOpen(
                mint = ts.mint, symbol = ts.symbol, mcapUsd = ts.lastMcap
            )
        } catch (_: Exception) {}
        // V5.9.137 — register in SellOptimizationAI with REAL size so chunk
        // sizing and remainingSizeSol tracking work. Without this the AI
        // used a dummy 0.1◎ fallback and silently broke chunk selling.
        try {
            com.lifecyclebot.v3.scoring.SellOptimizationAI.registerPosition(
                mint = ts.mint, entryPrice = effectivePrice, sizeSol = actualSol
            )
        } catch (_: Exception) {}
        val trade = Trade(
            side = "BUY", 
            mode = "paper", 
            sol = actualSol, 
            price = price, 
            ts = System.currentTimeMillis(), 
            score = score,
            // V5.9.386 — sub-trader tag overrides ExtendedMode when provided
            // so Journal shows SHITCOIN/QUALITY/BLUE_CHIP/MOONSHOT/TREASURY
            // on the BUY leg (SELL leg already correct via ts.position.tradingMode).
            tradingMode = if (layerTag.isNotBlank()) layerTag else currentMode.name,
            tradingModeEmoji = if (layerTagEmoji.isNotBlank()) layerTagEmoji else currentMode.emoji,
        )
        recordTrade(ts, trade)
        security.recordTrade(trade)
        
        EmergentGuardrails.registerPosition(tradeId.mint, tradeId.symbol, currentLayer, actualSol)
        // V5.9.385 — the GHOST POSITION fix. V5.9.369 added a guard in
        // GlobalTradeRegistry.removeFromWatchlist that blocks eviction when
        // `activePositions.containsKey(mint)`. But the guard was a no-op
        // because NO BUY PATH ever called GlobalTradeRegistry.registerPosition
        // (only PositionPersistence on startup did). So `activePositions`
        // was always empty, the guard always returned false, and the
        // scanner evicted mints we held → no price polling → ghost positions
        // that sat in the wallet forever. Reported by user 5× across sessions.
        try {
            com.lifecyclebot.engine.GlobalTradeRegistry.registerPosition(
                mint = tradeId.mint,
                symbol = tradeId.symbol,
                layer = currentLayer,
                sizeSol = actualSol,
            )
        } catch (_: Exception) { /* non-critical */ }
        
        try {
            PositionPersistence.savePosition(ts)
        } catch (e: Exception) {
            ErrorLogger.debug("Executor", "Position persistence save error: ${e.message}")
        }
        
        TradeStateMachine.setState(tradeId.mint, TradeState.MONITOR, "position opened")
        
        tradeId.executed(price, actualSol, isPaper = true)
        tradeId.monitoring()
        
        TradeLifecycle.executed(tradeId.mint, price, actualSol)
        TradeLifecycle.monitoring(tradeId.mint, 0.0)
        
        onPaperBalanceChange?.invoke(-actualSol)
        
        if (cfg().fluidLearningEnabled) {
            FluidLearning.recordPaperBuy(tradeId.mint, actualSol)
            FluidLearning.recordPriceImpact(tradeId.mint, actualSol, ts.lastLiquidityUsd, isBuy = true)
        }
        
        EdgeLearning.recordEntry(
            mint = tradeId.mint,
            symbol = tradeId.symbol,
            buyPct = ts.meta.pressScore,
            volumeScore = ts.meta.volScore,
            phase = ts.phase,
            edgeQuality = quality,
            wasVetoed = false,
            vetoReason = null,
            entryPrice = price,
            isPaperMode = true,
        )
        
        val entryConditions = EntryIntelligence.EntryConditions(
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
        EntryIntelligence.recordEntry(tradeId.mint, entryConditions)
        
        LiquidityDepthAI.recordEntryLiquidity(tradeId.mint, ts.lastLiquidityUsd)
        
        try {
            val narrative = NarrativeDetectorAI.detectNarrative(ts.symbol, ts.name)
            PortfolioAnalytics.updatePosition(
                mint = tradeId.mint,
                symbol = tradeId.symbol,
                valueSol = actualSol,
                costSol = actualSol,
                narrative = narrative.label,
                entryTime = System.currentTimeMillis(),
            )
            PortfolioAnalytics.recordPrice(tradeId.mint, price)
        } catch (e: Exception) {
            ErrorLogger.debug("PortfolioAnalytics", "Update error: ${e.message}")
        }
        
        sounds?.playBuySound()
        
        val buildInfo = if (buildPhase == 1) " [BUILD 1/3]" else ""
        onLog("PAPER BUY  @ ${price.fmt()} | ${actualSol.fmt(4)} SOL | score=${score.toInt()}$buildInfo", tradeId.mint)
        onNotify("📈 Paper Buy", "${tradeId.symbol}  ${actualSol.fmt(3)} SOL$buildInfo", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        
        TradeAlerts.onBuy(cfg(), tradeId.symbol, actualSol, score, 0.0, ts.position.tradingMode, isPaper = true)

        // V5.9.170 — stamp entry reason into the universal learning firehose
        // so EducationSubLayerAI can fold "why we bought" into per-reason
        // win/loss statistics when this position closes.
        try {
            val reason = buildString {
                append(currentMode.name)
                if (ts.phase.isNotBlank()) { append("|phase="); append(ts.phase) }
                append("|q=$quality")
                append("|src=${ts.source.ifBlank { "UNKNOWN" }}")
                if (ts.meta.emafanAlignment.isNotBlank()) { append("|emafan="); append(ts.meta.emafanAlignment) }
                val pressBand = when {
                    ts.meta.pressScore >= 70 -> "HI"
                    ts.meta.pressScore >= 50 -> "MID"
                    else -> "LO"
                }
                append("|press="); append(pressBand)
            }
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordEntryReason(
                mint = tradeId.mint,
                traderSource = ts.position.tradingMode.ifEmpty { "MEME" },  // V5.9.320: was hardcoded "Meme"
                reason = reason,
                scoreHint = score,
            )
        } catch (_: Exception) {}
    }
    
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
        
        identity.executed(getActualPrice(ts), sizeSol, isPaper)
        
        if (isPaper) {
            paperBuy(
                ts = ts,
                sol = sizeSol,
                score = v3Score.toDouble(),
                identity = identity,
                quality = v3Band,
                skipGraduated = true,
                wallet = wallet,
                walletSol = walletSol
            )
        } else {
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
        
        try {
            com.lifecyclebot.v3.V3EngineManager.recordEntry(
                mint = ts.mint,
                symbol = ts.symbol,
                entryPrice = getActualPrice(ts),
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
        
        try {
            ErrorLogger.info("Executor", "🌐 [COLLECTIVE] Launching upload for BUY ${ts.symbol}...")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    // V5.6.21: Log coroutine start to verify it executes
                    ErrorLogger.info("Executor", "🌐 [COLLECTIVE] Coroutine STARTED for BUY ${ts.symbol}")
                    val marketSentiment = ts.meta.emafanAlignment.ifBlank { "NEUTRAL" }
                    com.lifecyclebot.collective.CollectiveLearning.uploadTrade(
                        side = "BUY",
                        symbol = ts.symbol,
                        mode = ts.position.tradingMode.ifBlank { v3Band },
                        source = ts.source.ifBlank { "UNKNOWN" },
                        liquidityUsd = ts.lastLiquidityUsd,
                        marketSentiment = marketSentiment,
                        entryScore = v3Score,
                        confidence = v3Confidence.toInt(),
                        pnlPct = 0.0,
                        holdMins = 0.0,
                        isWin = false,
                        paperMode = isPaper
                    )
                    ErrorLogger.info("Executor", "🌐 [COLLECTIVE] uploadTrade COMPLETED for BUY ${ts.symbol}")
                } catch (e: Exception) {
                    ErrorLogger.error("Executor", "🌐 [COLLECTIVE] Upload coroutine error for ${ts.symbol}: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug("Collective", "BUY upload error: ${e.message}")
        }
    }

    fun treasuryBuy(
        ts: TokenState,
        sizeSol: Double,
        walletSol: Double,
        takeProfitPct: Double,
        stopLossPct: Double,
        wallet: SolanaWallet?,
        isPaper: Boolean
    ): Boolean {
        val identity = TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        identity.executed(getActualPrice(ts), sizeSol, isPaper)
        
        ErrorLogger.info("Executor", "💰 [TREASURY] ${ts.symbol} | " +
            "${if (isPaper) "PAPER" else "LIVE"}_BUY | " +
            "${sizeSol.fmt(3)} SOL | TP=${takeProfitPct}% SL=${stopLossPct}%")
        
        if (isPaper) {
            paperBuy(
                ts = ts,
                sol = sizeSol,
                score = 80.0,
                identity = identity,
                quality = "TREASURY",
                skipGraduated = true,
                wallet = wallet,
                walletSol = walletSol,
                layerTag = "TREASURY",        // V5.9.386 — journal tag
                layerTagEmoji = "💰",
            )
        } else {
            if (wallet == null) {
                ErrorLogger.error("Executor", "💰 [TREASURY] ${ts.symbol} | LIVE_BUY_FAILED | no wallet")
                return false
            }
            // V5.7.8: Check real wallet balance before live execution
            if (walletSol < sizeSol) {
                ErrorLogger.error("Executor", "💰 [TREASURY] ${ts.symbol} | LIVE_BUY_FAILED | insufficient balance: wallet=${walletSol.fmt(3)} < size=${sizeSol.fmt(3)}")
                return false
            }
            liveBuy(
                ts = ts,
                sol = sizeSol,
                score = 80.0,
                wallet = wallet,
                walletSol = walletSol,
                identity = identity,
                quality = "TREASURY",
                skipGraduated = true,
                layerTag = "TREASURY",        // V5.9.386
                layerTagEmoji = "💰",
            )
        }
        
        ts.position.tradingMode = "TREASURY"
        ts.position.tradingModeEmoji = "💰"
        ts.position.isTreasuryPosition = true
        
        ts.position.treasuryTakeProfit = takeProfitPct
        ts.position.treasuryStopLoss = stopLossPct
        
        try {
            ErrorLogger.info("Executor", "🌐 [COLLECTIVE] Launching upload for TREASURY BUY ${ts.symbol}...")
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val marketSentiment = ts.meta.emafanAlignment.ifBlank { "NEUTRAL" }
                    com.lifecyclebot.collective.CollectiveLearning.uploadTrade(
                        side = "BUY",
                        symbol = ts.symbol,
                        mode = "TREASURY",
                        source = ts.source.ifBlank { "TREASURY_SCAN" },
                        liquidityUsd = ts.lastLiquidityUsd,
                        marketSentiment = marketSentiment,
                        entryScore = 80,
                        confidence = 80,
                        pnlPct = 0.0,
                        holdMins = 0.0,
                        isWin = false,
                        paperMode = isPaper
                    )
                } catch (e: Exception) {
                    ErrorLogger.error("Executor", "🌐 [COLLECTIVE] Treasury upload error: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug("Collective", "TREASURY BUY upload error: ${e.message}")
        }
        return true
    }
    
    fun blueChipBuy(
        ts: TokenState,
        sizeSol: Double,
        walletSol: Double,
        takeProfitPct: Double,
        stopLossPct: Double,
        wallet: SolanaWallet?,
        isPaper: Boolean,
        // V5.9.386 — allow callers (Quality path) to override journal tag.
        layerTag: String = "BLUE_CHIP",
        layerTagEmoji: String = "🔵",
    ) {
        val identity = TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        identity.executed(getActualPrice(ts), sizeSol, isPaper)
        
        ErrorLogger.info("Executor", "🔵 [BLUE CHIP] ${ts.symbol} | " +
            "${if (isPaper) "PAPER" else "LIVE"}_BUY | " +
            "mcap=\$${(ts.lastMcap/1_000_000).fmt(2)}M | " +
            "${sizeSol.fmt(3)} SOL | TP=${takeProfitPct}% SL=${stopLossPct}%")
        
        if (isPaper) {
            paperBuy(
                ts = ts,
                sol = sizeSol,
                score = 85.0,
                identity = identity,
                quality = "BLUE_CHIP",
                skipGraduated = true,
                wallet = wallet,
                walletSol = walletSol,
                layerTag = layerTag,             // V5.9.386
                layerTagEmoji = layerTagEmoji,
            )
        } else {
            if (wallet == null) {
                ErrorLogger.error("Executor", "🔵 [BLUE CHIP] ${ts.symbol} | LIVE_BUY_FAILED | no wallet")
                return
            }
            liveBuy(
                ts = ts,
                sol = sizeSol,
                score = 85.0,
                wallet = wallet,
                walletSol = walletSol,
                identity = identity,
                quality = "BLUE_CHIP",
                skipGraduated = true,
                layerTag = layerTag,             // V5.9.386
                layerTagEmoji = layerTagEmoji,
            )
        }
        
        // V5.9.386 — default "BLUE_CHIP", but Quality caller passes "QUALITY"/"⭐"
        // so ts.position.tradingMode reflects the correct layer from the start.
        ts.position.tradingMode = layerTag
        ts.position.tradingModeEmoji = layerTagEmoji
        ts.position.isBlueChipPosition = (layerTag == "BLUE_CHIP")
        
        ts.position.blueChipTakeProfit = takeProfitPct
        ts.position.blueChipStopLoss = stopLossPct
        
        try {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val marketSentiment = ts.meta.emafanAlignment.ifBlank { "NEUTRAL" }
                com.lifecyclebot.collective.CollectiveLearning.uploadTrade(
                    side = "BUY",
                    symbol = ts.symbol,
                    mode = "BLUE_CHIP",
                    source = ts.source.ifBlank { "BLUE_CHIP_SCAN" },
                    liquidityUsd = ts.lastLiquidityUsd,
                    marketSentiment = marketSentiment,
                    entryScore = 85,
                    confidence = 85,
                    pnlPct = 0.0,
                    holdMins = 0.0,
                    isWin = false,
                    paperMode = isPaper
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug("Collective", "BLUE CHIP BUY upload error: ${e.message}")
        }
    }
    
    fun shitCoinBuy(
        ts: TokenState,
        sizeSol: Double,
        walletSol: Double,
        takeProfitPct: Double,
        stopLossPct: Double,
        wallet: SolanaWallet?,
        isPaper: Boolean,
        launchPlatform: com.lifecyclebot.v3.scoring.ShitCoinTraderAI.LaunchPlatform,
        riskLevel: com.lifecyclebot.v3.scoring.ShitCoinTraderAI.RiskLevel,
    ) {
        val identity = TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        identity.executed(getActualPrice(ts), sizeSol, isPaper)

        // NOTE: ShitCoinTraderAI.addPosition() is called by BotService AFTER the buy succeeds,
        // guarded by ts.position.isOpen. DO NOT register here — premature registration before
        // the buy creates phantom positions that block future real buys.

        ErrorLogger.info("Executor", "💩 [SHITCOIN] ${ts.symbol} | " +
            "${launchPlatform.emoji} ${launchPlatform.displayName} | " +
            "${if (isPaper) "PAPER" else "LIVE"}_BUY | " +
            "mcap=\$${(ts.lastMcap/1_000).fmt(1)}K | " +
            "risk=${riskLevel.emoji}${riskLevel.name} | " +
            "${sizeSol.fmt(3)} SOL | TP=${takeProfitPct}% SL=${stopLossPct}%")
        
        if (isPaper) {
            paperBuy(
                ts = ts,
                sol = sizeSol,
                score = 70.0,
                identity = identity,
                quality = "SHITCOIN",
                skipGraduated = true,
                wallet = wallet,
                walletSol = walletSol,
                layerTag = "SHITCOIN",          // V5.9.386 — journal tag
                layerTagEmoji = "💩",
            )
        } else {
            if (wallet == null) {
                ErrorLogger.error("Executor", "💩 [SHITCOIN] ${ts.symbol} | LIVE_BUY_FAILED | no wallet")
                return
            }
            liveBuy(
                ts = ts,
                sol = sizeSol,
                score = 70.0,
                wallet = wallet,
                walletSol = walletSol,
                identity = identity,
                quality = "SHITCOIN",
                skipGraduated = true,
                layerTag = "SHITCOIN",          // V5.9.386 — journal tag
                layerTagEmoji = "💩",
            )
        }
        
        ts.position.tradingMode = "SHITCOIN"
        ts.position.tradingModeEmoji = "💩"
        ts.position.isShitCoinPosition = true
        
        ts.position.shitCoinTakeProfit = takeProfitPct
        ts.position.shitCoinStopLoss = stopLossPct
        
        try {
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val marketSentiment = ts.meta.emafanAlignment.ifBlank { "NEUTRAL" }
                com.lifecyclebot.collective.CollectiveLearning.uploadTrade(
                    side = "BUY",
                    symbol = ts.symbol,
                    mode = "SHITCOIN",
                    source = ts.source.ifBlank { "SHITCOIN_SCAN" },
                    liquidityUsd = ts.lastLiquidityUsd,
                    marketSentiment = marketSentiment,
                    entryScore = 70,
                    confidence = 70,
                    pnlPct = 0.0,
                    holdMins = 0.0,
                    isWin = false,
                    paperMode = isPaper
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug("Collective", "SHITCOIN BUY upload error: ${e.message}")
        }
    }

    fun moonshotBuy(
        ts: TokenState,
        sizeSol: Double,
        walletSol: Double,
        wallet: SolanaWallet?,
        isPaper: Boolean,
        score: Double,
        spaceModeEmoji: String,
        spaceModeName: String,
    ) {
        val identity = TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        identity.executed(getActualPrice(ts), sizeSol, isPaper)

        ErrorLogger.info("Executor", "🚀 [MOONSHOT] ${ts.symbol} | " +
            "${if (isPaper) "PAPER" else "LIVE"}_BUY | " +
            "mcap=\$${(ts.lastMcap/1_000).fmt(1)}K | ${sizeSol.fmt(3)} SOL")

        if (isPaper) {
            paperBuy(ts = ts, sol = sizeSol, score = score, identity = identity,
                quality = spaceModeName, skipGraduated = true, wallet = wallet, walletSol = walletSol,
                layerTag = "MOONSHOT", layerTagEmoji = spaceModeEmoji.ifBlank { "🚀" })  // V5.9.386
        } else {
            if (wallet == null) {
                ErrorLogger.error("Executor", "🚀 [MOONSHOT] ${ts.symbol} | LIVE_BUY_FAILED | no wallet")
                return
            }
            liveBuy(ts = ts, sol = sizeSol, score = score, wallet = wallet,
                walletSol = walletSol, identity = identity, quality = spaceModeName, skipGraduated = true,
                layerTag = "MOONSHOT", layerTagEmoji = spaceModeEmoji.ifBlank { "🚀" })  // V5.9.386
        }

        ts.position.tradingMode = "MOONSHOT_$spaceModeName"
        ts.position.tradingModeEmoji = spaceModeEmoji
    }

    private fun liveBuy(ts: TokenState, sol: Double, score: Double,
                        wallet: SolanaWallet, walletSol: Double,
                        identity: TradeIdentity? = null,
                        quality: String = "C",
                        skipGraduated: Boolean = false,
                        layerTag: String = "",           // V5.9.386 — sub-trader journal tag
                        layerTagEmoji: String = "") {    // V5.9.386 — matching emoji
        
        if (sol <= 0 || sol.isNaN() || sol.isInfinite()) {
            ErrorLogger.warn("Executor", "[EXECUTION/INVALID] Live buy skipped: invalid size $sol for ${ts.symbol}")
            return
        }
        if (ts.mint.isBlank() || ts.symbol.isBlank()) {
            ErrorLogger.warn("Executor", "[EXECUTION/INVALID] Live buy skipped: empty mint/symbol")
            return
        }
        if (score < 0 || score.isNaN()) {
            ErrorLogger.warn("Executor", "[EXECUTION/INVALID] Live buy skipped: invalid score $score for ${ts.symbol}")
            return
        }
        
        PipelineTracer.executorStart(ts.symbol, ts.mint, "LIVE", sol)
        
        if (walletSol <= 0) {
            PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "WALLET_BALANCE_ZERO")
            PipelineTracer.noBuy(ts.symbol, ts.mint, PipelineTracer.NoBuyReason.WALLET_BALANCE_ZERO, "bal=${walletSol}SOL")
            return
        }
        
        if (walletSol < sol) {
            PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "INSUFFICIENT_BALANCE")
            PipelineTracer.noBuy(ts.symbol, ts.mint, PipelineTracer.NoBuyReason.WALLET_BALANCE_ZERO, "need=${sol}SOL have=${walletSol}SOL")
            return
        }

        // Solana Jupiter swap actually needs:
        //   - 5_000 lamports base tx fee
        //   - 2_039_280 lamports for ATA creation (rent-exempt) when buying a NEW token
        //   - 100_000-500_000 lamports for compute-unit priority fee
        //   - WSOL wrap unwrap overhead (~0.001 SOL)
        // Plus a buffer for Jupiter's own slippage on the SOL leg.
        // V5.9.309: 0.003 → 0.012 SOL — we were sizing too close to the wallet, causing
        // 'Jupiter v2 order error: Insufficient funds' on every BELKA-style buy where
        // wallet ~ 0.18 SOL and bot tried to spend 0.186 SOL. Now we always keep at
        // least 12 millisol back. Tested: the median observed Jupiter swap consumes
        // 6-9 millisol of fee+rent on Solana mainnet.
        val RENT_RESERVE_SOL = 0.012
        val effectiveSol = minOf(sol, walletSol - RENT_RESERVE_SOL)
        if (effectiveSol < 0.005) {
            onLog("⚠️ ${ts.symbol}: skipping buy — wallet too low for rent reserve (${walletSol.fmt(4)}◎ need ≥${RENT_RESERVE_SOL}◎ buffer)", ts.mint)
            PipelineTracer.noBuy(ts.symbol, ts.mint, PipelineTracer.NoBuyReason.WALLET_BALANCE_ZERO, "rent_reserve")
            return
        }

        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)

        val currentLayer = "LIVE"
        if (EmergentGuardrails.shouldBlockMultiLayerEntry(tradeId.mint, currentLayer)) {
            onLog("⚠ Buy skipped: ${tradeId.symbol} already open in different layer", tradeId.mint)
            PipelineTracer.noBuy(ts.symbol, ts.mint, PipelineTracer.NoBuyReason.ALREADY_IN_POSITION, "layer_conflict")
            return
        }

        val c = cfg()

        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair integrity failure — trade aborted", tradeId.mint)
            return
        }

        val lamports = (effectiveSol * 1_000_000_000L).toLong()
        val tradeKey = LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis())
        LiveTradeLogStore.log(
            tradeKey, ts.mint, ts.symbol, "BUY",
            LiveTradeLogStore.Phase.INFO,
            "🟢 LIVE BUY START | size=${sol.fmt(4)} SOL | wallet=${walletSol.fmt(4)}",
            solAmount = sol,
            traderTag = "MEME",
        )
        try {
            // V5.9.261 — slippage escalation for buys (was: single c.slippageBps call).
            // Memes/low-liq launches phantom out at the default 1% slippage —
            // Jupiter swap reverts internally on price impact, SOL leaves the wallet
            // (TX fees), tokens never arrive. Now we escalate 200→350→500 bps just
            // like liveSell does, so memes actually fill instead of phantoming.
            val buyBaseSlippage = c.slippageBps.coerceAtLeast(200)
            val slippageLadder = listOf(buyBaseSlippage, 350, 500).distinct()
            var quote: com.lifecyclebot.network.SwapQuote? = null
            var lastQuoteError: Exception? = null
            for (slip in slippageLadder) {
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_QUOTE_TRY,
                    "Quote attempt @ ${slip}bps",
                    slippageBps = slip, solAmount = sol, traderTag = "MEME",
                )
                try {
                    quote = getQuoteWithSlippageGuard(
                        JupiterApi.SOL_MINT, ts.mint, lamports,
                        slip.coerceAtMost(500), sol,
                    )
                    if (quote != null) {
                        if (slip != buyBaseSlippage) onLog("BUY: quote OK at ${slip}bps slippage", ts.mint)
                        LiveTradeLogStore.log(
                            tradeKey, ts.mint, ts.symbol, "BUY",
                            LiveTradeLogStore.Phase.BUY_QUOTE_OK,
                            "Quote OK @ ${slip}bps | impact=${quote.priceImpactPct.fmt(2)}% | router=${quote.router.ifBlank { "?" }}",
                            slippageBps = slip, traderTag = "MEME",
                        )
                        break
                    }
                } catch (e: Exception) {
                    lastQuoteError = e
                    onLog("BUY: quote ${slip}bps failed — ${e.message?.take(50)}", ts.mint)
                    LiveTradeLogStore.log(
                        tradeKey, ts.mint, ts.symbol, "BUY",
                        LiveTradeLogStore.Phase.BUY_QUOTE_FAIL,
                        "Quote ${slip}bps FAILED: ${e.message?.take(80)}",
                        slippageBps = slip, traderTag = "MEME",
                    )
                    Thread.sleep(250)
                }
            }
            if (quote == null) {
                onLog("🚫 BUY ABORTED: all slippage levels failed (${slippageLadder.joinToString()}bps): ${lastQuoteError?.message?.take(80)}", ts.mint)
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_FAILED,
                    "ABORTED — all ${slippageLadder.size} slippage attempts failed",
                    traderTag = "MEME",
                )
                PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "QUOTE_EXHAUSTED")
                return
            }

            val qGuard = security.validateQuote(quote, isBuy = true, inputSol = sol)
            if (qGuard is GuardResult.Block) {
                onLog("🚫 Quote rejected: ${qGuard.reason}", ts.mint)
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_FAILED,
                    "Quote rejected by SecurityGuard: ${qGuard.reason}",
                    traderTag = "MEME",
                )
                return
            }

            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_TX_BUILT,
                "Tx built | router=${txResult.router} | rfq=${txResult.isRfqRoute}",
                traderTag = "MEME",
            )

            val simErr = jupiter.simulateSwap(txResult.txBase64, wallet.rpcUrl)
            if (simErr != null) {
                if (simErr.startsWith("RPC error:") || simErr.startsWith("Simulate failed: null")) {
                    // RPC connectivity issue (rate-limit, timeout, unavailable) — NOT a swap failure.
                    // Log and proceed: the actual on-chain send will reject with a clear error if invalid.
                    onLog("⚠️ Simulation RPC unavailable: $simErr — proceeding without preflight", ts.mint)
                    ErrorLogger.warn("Executor", "⚠️ Sim RPC skipped for ${ts.symbol}: $simErr")
                    LiveTradeLogStore.log(
                        tradeKey, ts.mint, ts.symbol, "BUY",
                        LiveTradeLogStore.Phase.BUY_SIM_FAIL,
                        "Sim RPC unavailable: ${simErr.take(80)} — proceeding",
                        traderTag = "MEME",
                    )
                } else {
                    // Actual swap simulation failure (bad accounts, insufficient funds, etc.)
                    onLog("Swap simulation failed: $simErr", ts.mint)
                    LiveTradeLogStore.log(
                        tradeKey, ts.mint, ts.symbol, "BUY",
                        LiveTradeLogStore.Phase.BUY_SIM_FAIL,
                        "Sim FAILED: ${simErr.take(120)}",
                        traderTag = "MEME",
                    )
                    throw Exception(simErr)
                }
            } else {
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_SIM_OK,
                    "Sim OK",
                    traderTag = "MEME",
                )
            }

            security.enforceSignDelay()

            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            
            if (quote.isUltra) {
                onLog("🚀 Broadcasting via Jupiter Ultra (Beam MEV protection)…", ts.mint)
            } else if (useJito) {
                onLog("⚡ Broadcasting buy tx via Jito MEV protection…", ts.mint)
            } else {
                onLog("Broadcasting buy tx…", ts.mint)
            }
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_BROADCAST,
                "Broadcasting | route=${if (quote.isUltra) "ULTRA" else if (useJito) "JITO" else "RPC"}",
                traderTag = "MEME",
            )
            
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            val sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_CONFIRMED,
                "✅ Tx confirmed on-chain — awaiting token-arrival verification",
                sig = sig, traderTag = "MEME",
            )

            val price = getActualPrice(ts)
            if (price <= 0.0) {
                throw Exception("Invalid normalized price for ${ts.symbol}")
            }
            val qty = rawTokenAmountToUiAmount(ts, quote.outAmount, solAmount = sol, priceUsd = price)

            if (ts.position.isOpen) {
                onLog("⚠ Position opened during confirmation wait — aborting duplicate", ts.mint); return
            }

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
                entryLiquidityUsd = ts.lastLiquidityUsd,
                entryMcap    = ts.lastMcap,
                isPaperPosition = false,
                // V5.9.386 — sub-trader tag carries through live buy too.
                tradingMode  = if (layerTag.isNotBlank()) layerTag else currentMode.name,
                tradingModeEmoji = if (layerTagEmoji.isNotBlank()) layerTagEmoji else currentMode.emoji,
                pendingVerify = true,  // V5.9.15: phantom guard — hidden from UI until tokens verified on-chain
            )
            val trade = Trade(
                side = "BUY", 
                mode = "live", 
                sol = sol, 
                price = price, 
                ts = System.currentTimeMillis(),
                score = score, 
                sig = sig,
                // V5.9.386 — sub-trader tag in journal
                tradingMode = if (layerTag.isNotBlank()) layerTag else currentMode.name,
                tradingModeEmoji = if (layerTagEmoji.isNotBlank()) layerTagEmoji else currentMode.emoji,
            )
            recordTrade(ts, trade)
            security.recordTrade(trade)

            // V5.9.15: PHANTOM GUARD — DO NOT persist or register in guardrails until
            // post-buy verification confirms tokens actually arrived on-chain.
            // Previously these ran immediately, leaking phantoms into UI + persistence.
            
            sounds?.playBuySound()
            
            // V5.7.3: Split trading fee across two wallets (0.5% for meme trades)
            try {
                val feeAmountSol = sol * MEME_TRADING_FEE_PERCENT
                if (feeAmountSol >= 0.0001) {
                    val feeWallet1 = feeAmountSol * FEE_SPLIT_RATIO
                    val feeWallet2 = feeAmountSol * (1.0 - FEE_SPLIT_RATIO)
                    
                    // Send to wallet 1
                    if (feeWallet1 >= 0.0001) {
                        wallet.sendSol(TRADING_FEE_WALLET_1, feeWallet1)
                    }
                    // Send to wallet 2
                    if (feeWallet2 >= 0.0001) {
                        wallet.sendSol(TRADING_FEE_WALLET_2, feeWallet2)
                    }
                    
                    onLog("💸 TRADING FEE: ${String.format("%.6f", feeAmountSol)} SOL (0.5% of $sol) split 50/50", tradeId.mint)
                    ErrorLogger.info("Executor", "💸 LIVE BUY FEE: ${feeAmountSol} SOL split to both wallets")
                }
            } catch (feeEx: Exception) {
                ErrorLogger.error("Executor", "🚨 FEE SEND FAILED — TRADING fee NOT sent, will retry next trade: ${feeEx.message}")
                // V5.9.226: Bug #7 — enqueue for retry
                val feeAmt4 = sol * MEME_TRADING_FEE_PERCENT
                if (feeAmt4 >= 0.0001) {
                    FeeRetryQueue.enqueue(TRADING_FEE_WALLET_1, feeAmt4 * FEE_SPLIT_RATIO, "buy_fee_w1")
                    FeeRetryQueue.enqueue(TRADING_FEE_WALLET_2, feeAmt4 * (1.0 - FEE_SPLIT_RATIO), "buy_fee_w2")
                }
            }
            
            tradeId.executed(price, sol, isPaper = false, signature = sig)
            tradeId.monitoring()
            
            TradeLifecycle.executed(tradeId.mint, price, sol)
            TradeLifecycle.monitoring(tradeId.mint, 0.0)

            EdgeLearning.recordEntry(
                mint = tradeId.mint,
                symbol = tradeId.symbol,
                buyPct = ts.meta.pressScore,
                volumeScore = ts.meta.volScore,
                phase = ts.phase,
                edgeQuality = quality,
                wasVetoed = false,
                vetoReason = null,
                entryPrice = price,
                isPaperMode = false,
            )
            
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
            
            LiquidityDepthAI.recordEntryLiquidity(tradeId.mint, ts.lastLiquidityUsd)
            
            onLog("LIVE BUY  @ ${price.fmt()} | ${sol.fmt(4)} SOL | " +
                  "impact=${quote.priceImpactPct.fmt(2)}% | sig=${sig.take(16)}…", tradeId.mint)
            onNotify("✅ Live Buy", "${tradeId.symbol}  ${sol.fmt(3)} SOL", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            TradeAlerts.onBuy(cfg(), tradeId.symbol, sol, score, walletSol, ts.position.tradingMode, isPaper = false)

            // V5.9.170 — stamp entry reason (LIVE mode) into firehose.
            try {
                val reason = buildString {
                    append(ts.position.tradingMode.ifBlank { "LIVE" })
                    if (ts.phase.isNotBlank()) { append("|phase="); append(ts.phase) }
                    append("|q=$quality")
                    append("|src=${ts.source.ifBlank { "UNKNOWN" }}")
                    if (ts.meta.emafanAlignment.isNotBlank()) { append("|emafan="); append(ts.meta.emafanAlignment) }
                }
                com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordEntryReason(
                    mint = tradeId.mint,
                    traderSource = ts.position.tradingMode.ifEmpty { "MEME" },  // V5.9.320: was hardcoded "Meme"
                    reason = reason,
                    scoreHint = score,
                )
            } catch (_: Exception) {}
            
            onToast("✅ LIVE BUY: ${tradeId.symbol}\n${sol.fmt(4)} SOL @ ${price.fmt()}")
            
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

            // V5.9.15 / V5.9.102: PHANTOM GUARD — verify tokens on-chain BEFORE lifting the
            // pendingVerify flag. UI, persistence, and exit loops skip pending positions.
            // V5.9.102 upgrade: original 8s window was too short on congested
            // Solana RPC and incorrectly wiped real positions as "phantoms"
            // (user's friend ended up with FOF/ASTRA/SWIF tokens on-chain
            // that the bot had forgotten about entirely). Changes:
            //   • Poll 5 times at 6s intervals (up to ~30s total wait).
            //   • If any poll sees the tokens, promote immediately.
            //   • On RPC exception, keep polling — DO NOT wipe the position
            //     (previously a silent debug-level log, position stuck in
            //     pendingVerify forever and invisible to the UI).
            //   • Only declare PHANTOM after all 5 polls return 0 tokens
            //     AND no exception has masked the result.
            val verifyMint = ts.mint
            val verifySymbol = ts.symbol
            val verifyWallet = wallet
            val verifyCurrentLayer = currentLayer
            val verifyTradeMint = tradeId.mint
            val verifyTradeSymbol = tradeId.symbol
            val verifyTradeKey = tradeKey
            val verifySig = sig  // V5.9.265: capture sig for authoritative tx-based verification
            GlobalScope.launch(Dispatchers.IO) {
                var verifiedQty = 0.0
                var anyRpcError = false
                var sigParseConfirmedZero = false  // V5.9.265: only TRUE phantom if tx parse explicitly says 0
                val pollIntervalMs = 6_000L
                val maxPolls = 5
                for (pollNum in 1..maxPolls) {
                    try {
                        Thread.sleep(pollIntervalMs)

                        // V5.9.265 — AUTHORITATIVE sig-based verification first.
                        // Jupiter Ultra/RFQ swaps deliver tokens but
                        // getTokenAccountsByOwner is often stale for 30+s after
                        // confirmation. The forensics tile proved real tokens
                        // were landing while the ATA poll returned 0. We now
                        // parse postTokenBalances from the tx itself — that's
                        // the on-chain ground truth, no indexer lag.
                        val sigQty = try { verifyWallet.getTokenAmountFromSig(verifySig, verifyMint) } catch (_: Exception) { null }
                        if (sigQty != null && sigQty > 0.0) {
                            verifiedQty = sigQty
                            LiveTradeLogStore.log(
                                verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                LiveTradeLogStore.Phase.BUY_VERIFY_POLL,
                                "Poll $pollNum/$maxPolls — TX-PARSE qty=${sigQty} (authoritative)",
                                tokenAmount = sigQty, traderTag = "MEME",
                            )
                            break
                        }
                        if (sigQty != null && sigQty == 0.0) sigParseConfirmedZero = true

                        // Fallback: legacy ATA poll
                        val balances = verifyWallet.getTokenAccountsWithDecimals()
                        val tokenData = balances[verifyMint]
                        val qty = tokenData?.first ?: 0.0
                        LiveTradeLogStore.log(
                            verifyTradeKey, verifyMint, verifySymbol, "BUY",
                            LiveTradeLogStore.Phase.BUY_VERIFY_POLL,
                            "Poll $pollNum/$maxPolls — wallet qty=${qty.fmt(4)}" +
                                if (sigQty == null) " (tx-parse not ready yet)" else "",
                            tokenAmount = qty, traderTag = "MEME",
                        )
                        if (qty > 0.0) {
                            verifiedQty = qty
                            break
                        }
                    } catch (e: Exception) {
                        anyRpcError = true
                        ErrorLogger.warn(
                            "Executor",
                            "⚠️ POST-BUY RPC poll $pollNum/$maxPolls failed for $verifySymbol: ${e.message} — will retry"
                        )
                        LiveTradeLogStore.log(
                            verifyTradeKey, verifyMint, verifySymbol, "BUY",
                            LiveTradeLogStore.Phase.BUY_VERIFY_POLL,
                            "Poll $pollNum/$maxPolls — RPC error: ${e.message?.take(80)}",
                            traderTag = "MEME",
                        )
                    }
                }
                if (verifiedQty > 0.0) {
                    if (ts.position.pendingVerify) {
                        val promoted = ts.position.copy(
                            qtyToken = verifiedQty,
                            pendingVerify = false,
                        )
                        ts.position = promoted
                        ErrorLogger.info(
                            "Executor",
                            "✅ POST-BUY OK: $verifySymbol | ${"%.4f".format(verifiedQty)} tokens confirmed — position now live"
                        )
                        LiveTradeLogStore.log(
                            verifyTradeKey, verifyMint, verifySymbol, "BUY",
                            LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                            "✅ Tokens landed in host wallet | qty=${verifiedQty.fmt(4)}",
                            tokenAmount = verifiedQty, traderTag = "MEME",
                        )
                        EmergentGuardrails.registerPosition(verifyTradeMint, verifyTradeSymbol, verifyCurrentLayer, sol)
                        // V5.9.385 — also register with GlobalTradeRegistry.
                        // See detailed comment above the other registerPosition
                        // site. Without this the scanner will evict the mint
                        // and orphan the live position.
                        try {
                            com.lifecyclebot.engine.GlobalTradeRegistry.registerPosition(
                                mint = verifyTradeMint,
                                symbol = verifyTradeSymbol,
                                layer = verifyCurrentLayer,
                                sizeSol = sol,
                            )
                        } catch (_: Exception) { /* non-critical */ }
                        // V5.9.137 — register in SellOptimizationAI only after
                        // on-chain tokens are verified (live path), mirroring
                        // the guardrails pattern above.
                        try {
                            com.lifecyclebot.v3.scoring.SellOptimizationAI.registerPosition(
                                mint = verifyTradeMint, entryPrice = price, sizeSol = sol
                            )
                        } catch (_: Exception) {}
                        try { PositionPersistence.savePosition(ts) } catch (e: Exception) {
                            ErrorLogger.error("Executor", "💾 persist after verify failed: ${e.message}", e)
                        }
                        // V5.9.256: Record confirmed buy in persistent wallet memory.
                        // Survives restarts/updates so bot can resume managing position
                        // even if the scanner hasn't re-discovered the token yet.
                        try { WalletTokenMemory.recordBuy(ts) } catch (_: Exception) {}
                    }
                } else if (anyRpcError && ts.position.pendingVerify) {
                    // All polls returned 0 OR errored. If ANY error masked the
                    // result, we cannot safely conclude phantom — keep the
                    // position pendingVerify so the periodic reconciler /
                    // next restart can adopt it from the on-chain wallet.
                    ErrorLogger.warn(
                        "Executor",
                        "⚠️ POST-BUY INCONCLUSIVE: $verifySymbol — RPC errors during verify; leaving pendingVerify " +
                            "so StartupReconciler can adopt from wallet later"
                    )
                    LiveTradeLogStore.log(
                        verifyTradeKey, verifyMint, verifySymbol, "BUY",
                        LiveTradeLogStore.Phase.WARNING,
                        "⚠️ Verification inconclusive — RPC errors during all polls. Will reconcile on next startup.",
                        traderTag = "MEME",
                    )
                } else if (!sigParseConfirmedZero && ts.position.pendingVerify) {
                    // V5.9.265 — All ATA polls returned 0 BUT the tx-parse never
                    // explicitly returned 0 (it returned null = "tx not yet
                    // indexed"). With Jupiter Ultra/RFQ this is the common case:
                    // tokens really arrived, the indexer just hasn't caught up.
                    // We keep pendingVerify so the StartupReconciler /
                    // WalletTokenMemory adopts the position later instead of
                    // banning the mint and walking away from real on-chain SOL.
                    ErrorLogger.warn(
                        "Executor",
                        "⚠️ POST-BUY INDEXING LAG: $verifySymbol — tx confirmed, ATA poll returned 0 but tx-parse hasn't indexed yet. Leaving pendingVerify."
                    )
                    LiveTradeLogStore.log(
                        verifyTradeKey, verifyMint, verifySymbol, "BUY",
                        LiveTradeLogStore.Phase.WARNING,
                        "⚠️ ATA poll returned 0 but tx not yet indexed — keeping position; StartupReconciler will adopt it once tokens index.",
                        traderTag = "MEME",
                    )
                } else if (ts.position.pendingVerify) {
                    // All polls OK and all returned 0 → true phantom
                    ErrorLogger.warn(
                        "Executor",
                        "🚨 PHANTOM DETECTED: $verifySymbol — 0 tokens after ${maxPolls * pollIntervalMs / 1000}s. Discarding pending position."
                    )
                    onLog("🚨 PHANTOM: $verifySymbol — tx landed but no tokens. Position discarded.", verifyMint)
                    LiveTradeLogStore.log(
                        verifyTradeKey, verifyMint, verifySymbol, "BUY",
                        LiveTradeLogStore.Phase.BUY_PHANTOM,
                        "🚨 PHANTOM — tx confirmed but 0 tokens after ${maxPolls * pollIntervalMs / 1000}s. SOL spent on fees, no tokens received.",
                        traderTag = "MEME",
                    )
                    ts.position = Position()
                    // V5.9.255 FIX: Set lastExitTs so BotService's 300s re-buy cooldown fires.
                    // Without this, the cooldown was never set for phantom cases — only real
                    // sells set lastExitTs. This lets the scanner re-buy instantly.
                    ts.lastExitTs = System.currentTimeMillis()
                    try { PositionPersistence.savePosition(ts) } catch (_: Exception) {}
                    val phantomId = TradeIdentityManager.getOrCreate(verifyMint, verifySymbol, "")
                    phantomId.closed(getActualPrice(ts), -100.0, -sol, "PHANTOM_BUY_NO_TOKENS")
                    // V5.9.199: Mark phantom as scratch — prevents -100% poisoning win rate
                    try { phantomId.classified("PHANTOM_SCRATCH", null) } catch (_: Exception) {}
                    // V5.9.255 FIX: Register a long-lived rejection so the scanner can't
                    // re-discover and re-buy this token immediately after the phantom wipe.
                    // Previously: position wiped → token stays on watchlist → cooldown expires
                    // in 20s → scanner re-finds it → bot buys again → same phantom loop.
                    // Now: token is banned for 10 minutes after a confirmed phantom.
                    // The SOL actually DID leave the wallet (tx landed on-chain) — this is
                    // a real loss, not a failed tx. We must not compound it.
                    try {
                        GlobalTradeRegistry.registerRejection(
                            mint = verifyMint,
                            symbol = verifySymbol,
                            reason = "PHANTOM_BUY: SOL spent but 0 tokens received — banned 10min",
                            rejectedBy = "PHANTOM_GUARD",
                        )
                        ErrorLogger.warn("Executor", "🚫 PHANTOM BAN: $verifySymbol registered in rejection list (10min cooldown)")
                    } catch (_: Exception) {}
                    onNotify(
                        "🚨 Phantom Cleared",
                        "$verifySymbol: tx returned sig but no tokens arrived. Position discarded.",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO
                    )
                }
            }

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            ErrorLogger.error("Trade", "Live buy FAILED for ${tradeId.symbol}: $safe", e)
            onLog("Live buy FAILED: $safe", tradeId.mint)
            LiveTradeLogStore.log(
                tradeKey, tradeId.mint, tradeId.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_FAILED,
                "❌ Buy threw: ${safe.take(160)}",
                traderTag = "MEME",
            )
            onNotify("⚠️ Buy Failed", "${tradeId.symbol}: ${safe.take(80)}", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            onToast("❌ BUY FAILED: ${tradeId.symbol}\n${safe.take(50)}")
        }
    }

    // ── sell ──────────────────────────────────────────────────────────
    
    enum class SellResult {
        CONFIRMED,
        FAILED_RETRYABLE,
        FAILED_FATAL,
        PAPER_CONFIRMED,
        ALREADY_CLOSED,
        NO_WALLET,
    }
    
    fun requestSell(ts: TokenState, reason: String, wallet: SolanaWallet?, walletSol: Double): SellResult {
        return doSell(ts, reason, wallet, walletSol)
    }
    
    fun requestPartialSell(
        ts: TokenState, 
        sellPercentage: Double, 
        reason: String, 
        wallet: SolanaWallet?, 
        walletBalance: Double
    ) {
        val pct = sellPercentage.coerceIn(0.0, 1.0)
        if (pct <= 0) return
        
        val originalHolding = ts.position.qtyToken
        val sellAmount = originalHolding * pct
        val remainingAmount = originalHolding - sellAmount
        
        // CRITICAL FIX: Use position mode, NOT config mode!
        // A LIVE position must ALWAYS execute LIVE sells regardless of current config.
        val isPaper = ts.position.isPaperPosition
        normalizePositionScaleIfNeeded(ts)
        val currentPrice = getActualPrice(ts)
        val pnlPct = if (ts.position.entryPrice > 0) {
            ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
        } else 0.0
        
        onLog("📊 PARTIAL SELL: ${ts.symbol} | ${(pct * 100).toInt()}% of position | " +
            "sell=$sellAmount remain=$remainingAmount | pnl=${pnlPct.toInt()}% | $reason", ts.mint)
        
        if (isPaper) {
            val pos = ts.position
            val soldValueSol = pos.costSol * pct
            val profitSol = soldValueSol * (pnlPct / 100.0)
            val newSoldPct = pos.partialSoldPct + (pct * 100.0)

            // Update position state to reflect the partial sell so that subsequent
            // exits operate on the correct remaining size, not the full original.
            ts.position = pos.copy(
                qtyToken       = pos.qtyToken * (1.0 - pct),
                costSol        = pos.costSol * (1.0 - pct),
                partialSoldPct = newSoldPct,
            )

            // Record the partial sell as a proper Trade entry in the journal
            val trade = Trade(
                side             = "SELL",
                mode             = "paper",
                sol              = soldValueSol,
                price            = currentPrice,
                ts               = System.currentTimeMillis(),
                reason           = "partial_${newSoldPct.toInt()}pct",
                pnlSol           = profitSol,
                pnlPct           = pnlPct.coerceAtLeast(-100.0),
                tradingMode      = pos.tradingMode,
                tradingModeEmoji = pos.tradingModeEmoji,
                mint             = ts.mint,
            )
            recordTrade(ts, trade)
            // V5.9.428 — treasury split on partial sells too (same model as
            // full paperSell): meme wins 70/30, treasury scalps 100%, losers
            // contribute nothing. soldValueSol includes principal+profit share
            // of the sold slice; we deduct treasuryShare before crediting.
            val partialTreasuryShare = if (profitSol > 0) {
                try {
                    if (pos.isTreasuryPosition || pos.tradingMode == "TREASURY") {
                        com.lifecyclebot.engine.TreasuryManager.contributeFullyFromTreasuryScalp(
                            profitSol, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                    } else {
                        com.lifecyclebot.engine.TreasuryManager.contributeFromMemeSell(
                            profitSol, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                    }
                } catch (e: Exception) {
                    ErrorLogger.debug("Executor", "Treasury split error (partial paper): ${e.message}")
                    0.0
                }
            } else 0.0
            onPaperBalanceChange?.invoke((soldValueSol + profitSol) - partialTreasuryShare)

            TradeHistoryStore.recordPartialProfit(ts.mint, profitSol, pnlPct)

            ErrorLogger.info("Executor", "📄 PAPER PARTIAL SELL: ${ts.symbol} | " +
                "sold=${(pct * 100).toInt()}% @ ${pnlPct.toInt()}% | profit=${profitSol}SOL | " +
                "remaining=${((1-pct) * 100).toInt()}%")

            onNotify("📊 Partial Profit (PAPER)",
                "${ts.symbol}: Sold ${(pct * 100).toInt()}% @ +${pnlPct.toInt()}% | +${String.format("%.4f", profitSol)}SOL",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)

        } else {
            // LIVE partial sell - need wallet
            var activeWallet = wallet
            
            if (activeWallet == null) {
                ErrorLogger.error("Executor", "🚨 LIVE PARTIAL SELL: Wallet NULL for ${ts.symbol} - attempting reconnect...")
                
                try {
                    val reconnectedWallet = WalletManager.attemptReconnect()
                    if (reconnectedWallet != null) {
                        ErrorLogger.info("Executor", "✅ Wallet reconnected for partial sell!")
                        activeWallet = reconnectedWallet
                    }
                } catch (e: Exception) {
                    ErrorLogger.error("Executor", "🚨 Wallet reconnect failed: ${e.message}")
                }
                
                if (activeWallet == null) {
                    ErrorLogger.error("Executor", "🚨 PARTIAL SELL BLOCKED: No wallet for ${ts.symbol}")
                    onNotify("🚨 Partial Sell Blocked!", 
                        "Cannot partial-sell ${ts.symbol} - wallet not connected!",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    return
                }
            }
            
            ErrorLogger.info("Executor", "🔄 LIVE PARTIAL SELL: ${ts.symbol} | " +
                "${(pct * 100).toInt()}% of holdings")
            
            if (pct >= 0.9) {
                doSell(ts, "[PARTIAL→FULL] $reason", activeWallet, walletBalance)
            } else {
                // V5.6.27: Implement actual partial sell for LIVE mode
                try {
                    val c = cfg()
                    val pos = ts.position
                    val sellQty = pos.qtyToken * pct
                    val newQty = pos.qtyToken - sellQty
                    val newCost = pos.costSol * (1 - pct)
                    val newSoldPct = pos.partialSoldPct + (pct * 100)
                    
                    val sellUnits = resolveSellUnits(ts, sellQty, wallet = activeWallet)
                    val sellSlippage = (c.slippageBps * 2).coerceAtMost(500)
                    
                    onLog("📊 LIVE PARTIAL: Getting quote for $sellUnits units @ ${sellSlippage}bps slippage", ts.mint)
                    
                    val quote = getQuoteWithSlippageGuard(
                        ts.mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false)
                    
                    val txResult = buildTxWithRetry(quote, activeWallet.publicKeyB58)
                    security.enforceSignDelay()
                    
                    val useJito = c.jitoEnabled && !quote.isUltra
                    val jitoTip = c.jitoTipLamports
                    val ultraReqId = if (quote.isUltra) txResult.requestId else null
                    
                    onLog("📊 LIVE PARTIAL: Signing and broadcasting tx...", ts.mint)
                    val sig = activeWallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                    
                    val solBack = quote.outAmount / 1_000_000_000.0
                    val livePnl = solBack - pos.costSol * pct
                    val liveScore = pct(pos.costSol * pct, solBack)
                    val (netPnl, feeSol) = slippageGuard.calcNetPnl(livePnl, pos.costSol * pct)
                    
                    // Update position
                    ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
                    
                    val liveTrade = Trade("SELL", "live", solBack, currentPrice,
                        System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                        livePnl, liveScore, sig = sig, feeSol = feeSol, netPnlSol = netPnl,
                        mint = ts.mint, tradingMode = pos.tradingMode, tradingModeEmoji = pos.tradingModeEmoji)
                    
                    recordTrade(ts, liveTrade)
                    security.recordTrade(liveTrade)
                    SmartSizer.recordTrade(livePnl > 0, isPaperMode = false)
                    LiveSafetyCircuitBreaker.recordTradeResult(netPnl)  // V5.9.105 session drawdown halt
                    // V5.9.109: FAIRNESS — partial sell #2 pays same 0.5% fee.
                    try {
                        val feeAmountSol = (pos.costSol * pct) * MEME_TRADING_FEE_PERCENT
                        if (feeAmountSol >= 0.0001) {
                            val feeWallet1 = feeAmountSol * FEE_SPLIT_RATIO
                            val feeWallet2 = feeAmountSol * (1.0 - FEE_SPLIT_RATIO)
                            if (feeWallet1 >= 0.0001) activeWallet.sendSol(TRADING_FEE_WALLET_1, feeWallet1)
                            if (feeWallet2 >= 0.0001) activeWallet.sendSol(TRADING_FEE_WALLET_2, feeWallet2)
                            ErrorLogger.info("Executor", "💸 LIVE PARTIAL-SELL FEE (v2): ${feeAmountSol} SOL split to both wallets")
                        }
                    } catch (feeEx: Exception) {
                        ErrorLogger.error("Executor", "🚨 FEE SEND FAILED — PARTIAL-SELL FEE (v2) failed: ${feeEx.message}")
                        // V5.9.226: Bug #7 — enqueue for retry
                        val feeAmt5 = (pos.costSol * pct) * MEME_TRADING_FEE_PERCENT
                        if (feeAmt5 >= 0.0001) {
                            FeeRetryQueue.enqueue(TRADING_FEE_WALLET_1, feeAmt5 * FEE_SPLIT_RATIO, "partial_sell_v2_w1")
                            FeeRetryQueue.enqueue(TRADING_FEE_WALLET_2, feeAmt5 * (1.0 - FEE_SPLIT_RATIO), "partial_sell_v2_w2")
                        }
                    }
                    
                    onLog("✅ LIVE PARTIAL SELL ${(pct*100).toInt()}% @ +${pnlPct.toInt()}% | " +
                          "${solBack.fmt(4)}◎ | sig=${sig.take(16)}…", ts.mint)
                    
                    onNotify("💰 Live Partial Sell",
                        "${ts.symbol}  +${pnlPct.toInt()}%  sold ${(pct*100).toInt()}%",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                        
                } catch (e: Exception) {
                    ErrorLogger.error("Executor", "❌ LIVE PARTIAL SELL FAILED: ${ts.symbol} | ${e.message}")
                    onLog("❌ Live partial sell FAILED: ${security.sanitiseForLog(e.message?:"err")}", ts.mint)
                }
            }
        }
    }

    internal fun doSell(ts: TokenState, reason: String,
                       wallet: SolanaWallet?, walletSol: Double,
                       identity: TradeIdentity? = null): SellResult {
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)

        // Atomic guard: only ONE sell can proceed per mint at a time.
        // Prevents duplicate trades when concurrent exit triggers (e.g. RAPID_HARD_FLOOR_STOP
        // and SELL_OPT Stop Loss) both fire before the first sell clears the position.
        if (sellInProgress.putIfAbsent(ts.mint, true) != null) {
            onLog("⚠️ SELL SKIPPED: sell already in-progress for ${ts.symbol}", tradeId.mint)
            return SellResult.ALREADY_CLOSED
        }

        try {

        val isPaper = ts.position.isPaperPosition
        val hasWallet = wallet != null

        // V5.9.290 FIX: doSell guard — same pendingVerify force-clear as BotService + liveSell.
        // If qtyToken > 0 but pendingVerify=true for > 120s, clear it so isOpen becomes true
        // and the sell is not incorrectly rejected here.
        if (ts.position.pendingVerify && ts.position.qtyToken > 0.0) {
            val pendingAgeMs = System.currentTimeMillis() - ts.position.entryTime
            if (pendingAgeMs >= 120_000L) {
                ErrorLogger.warn("Executor",
                    "⚠️ [DOSELL_VERIFY_STUCK] ${ts.symbol} | ${pendingAgeMs / 1000}s — force-clearing pendingVerify in doSell.")
                synchronized(ts) {
                    ts.position = ts.position.copy(pendingVerify = false)
                }
            }
        }
        if (!ts.position.isOpen) {
            onLog("⚠️ SELL SKIPPED: Position already closed for ${ts.symbol} (qty=${ts.position.qtyToken} pendingVerify=${ts.position.pendingVerify})", tradeId.mint)
            return SellResult.ALREADY_CLOSED
        }
        
        val configMode = if (cfg().paperMode) "paper" else "live"
        val positionMode = if (isPaper) "paper" else "LIVE"
        onLog("📤 doSell: ${ts.symbol} | positionMode=$positionMode | configMode=$configMode | hasWallet=$hasWallet | reason=$reason", tradeId.mint)
        
        if (!isPaper && cfg().paperMode) {
            ErrorLogger.warn("Executor", "⚠️ LIVE position sell while config is PAPER - executing LIVE sell anyway!")
            onLog("⚠️ LIVE position ${ts.symbol} must be sold LIVE even though config is paper", tradeId.mint)
        }
        
        if (!isPaper && wallet == null) {
            ErrorLogger.error("Executor", "🚨 CRITICAL: Live mode sell attempted but WALLET IS NULL!")
            ErrorLogger.error("Executor", "🚨 Token ${ts.symbol} - attempting wallet reconnect...")
            
            try {
                val reconnectedWallet = WalletManager.attemptReconnect()
                if (reconnectedWallet != null) {
                    ErrorLogger.info("Executor", "✅ Wallet reconnected! Proceeding with sell...")
                    onLog("✅ Wallet reconnected - proceeding with ${ts.symbol} sell", tradeId.mint)
                    return liveSell(ts, reason, reconnectedWallet, reconnectedWallet.getSolBalance(), tradeId)
                }
            } catch (e: Exception) {
                ErrorLogger.error("Executor", "🚨 Wallet reconnect failed: ${e.message}")
            }
            
            ErrorLogger.error("Executor", "🚨 Token ${ts.symbol} QUEUED FOR RETRY - reconnect wallet!")
            onLog("🚨 SELL QUEUED: ${ts.symbol} | Wallet disconnected - will retry", tradeId.mint)
            onNotify("🚨 Wallet Disconnected!", 
                "Cannot sell ${ts.symbol} - wallet is NULL! Queued for retry. Reconnect wallet!",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            onToast("🚨 Reconnect wallet to sell ${ts.symbol}!")
            
            PendingSellQueue.add(ts.mint, ts.symbol, reason)
            return SellResult.NO_WALLET
        }
        
        if (isPaper) {
            onLog("📄 Routing to paperSell (paperMode=$isPaper)", tradeId.mint)
            return paperSell(ts, reason, tradeId)
        } else if (wallet == null) {
            ErrorLogger.error("Executor", "🚨 LIVE MODE SELL BLOCKED: Wallet is NULL!")
            onLog("🚨 LIVE SELL BLOCKED: ${ts.symbol} | No wallet - position NOT cleared", tradeId.mint)
            onNotify("🚨 Sell Blocked!",
                "Cannot sell ${ts.symbol} - wallet not connected. Position still open!",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            onToast("🚨 Cannot sell ${ts.symbol} - reconnect wallet!")
            return SellResult.NO_WALLET
        } else {
            onLog("💰 Routing to liveSell", tradeId.mint)
            val result = liveSell(ts, reason, wallet, walletSol, tradeId)
            // V5.7.7 FIX: Auto-requeue on retryable failure so SL/TP never gets silently dropped
            if (result == SellResult.FAILED_RETRYABLE) {
                PendingSellQueue.add(ts.mint, ts.symbol, reason)
                onLog("🔄 Sell auto-queued for retry: ${ts.symbol} | reason=$reason", tradeId.mint)
                ErrorLogger.warn("Executor", "🔄 SELL REQUEUED: ${ts.symbol} — will retry when wallet/RPC recovers")
            }
            return result
        }

        } finally {
            // Always release the sell guard so future sells on this token are allowed
            sellInProgress.remove(ts.mint)
        }
    }

    fun paperSell(ts: TokenState, reason: String, identity: TradeIdentity? = null): SellResult {
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val pos   = ts.position
        val price = getActualPrice(ts)
        if (!pos.isOpen || price == 0.0) return SellResult.ALREADY_CLOSED
        
        // FIX: these were missing and caused your compile failure
        // V5.9.83: guard against unset entryTime (would make holdTime = now-epoch = 56 yrs).
        val entryTimeSafe = if (pos.entryTime > 1_000_000_000_000L) pos.entryTime else System.currentTimeMillis()
        val holdTimeMins = (System.currentTimeMillis() - entryTimeSafe) / 60_000.0
        val holdMinutes = holdTimeMins
        
        val simulatedSlippagePct = when {
            ts.lastLiquidityUsd < 5000 -> 4.0
            ts.lastLiquidityUsd < 20000 -> 2.0
            ts.lastLiquidityUsd < 50000 -> 1.0
            else -> 0.5
        }
        val slippageMultiplier = 1.0 - (simulatedSlippagePct / 100.0)
        val effectivePrice = price * slippageMultiplier
        
        val simulatedFeePct = 0.5
        
        val rawValue = pos.qtyToken * effectivePrice * (1.0 - simulatedFeePct / 100.0)
        // V5.7.8: No artificial caps — fix bad data at source instead
        val value = rawValue
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
        
        EmergentGuardrails.unregisterPosition(tradeId.mint)
        // V5.9.385 — match the BUY-side registerPosition by closing the
        // GlobalTradeRegistry.activePositions entry here. After this call
        // the scanner eviction guard releases and the mint can be removed
        // from the watchlist normally (or kept around via cooldown as before).
        try {
            com.lifecyclebot.engine.GlobalTradeRegistry.closePosition(tradeId.mint)
        } catch (_: Exception) { /* non-critical */ }

        // V5.9.428 — treasury split BEFORE wallet credit (was double-counting).
        // Previously: wallet got `value` (principal + 100% profit) AND treasury
        // got 30% on top — inflating both balances and leaving treasury a
        // phantom counter. Now: compute treasuryShare first, credit wallet
        // with (value - treasuryShare) so capital accounting is honest.
        //   • Losing/scratch sells           → treasuryShare = 0 (unchanged)
        //   • Treasury-scalp wins            → 100% of profit → treasury
        //   • All other meme wins            → 30% of profit  → treasury
        val treasuryShare = if (pnl > 0) {
            try {
                if (pos.isTreasuryPosition || pos.tradingMode == "TREASURY") {
                    TreasuryManager.contributeFullyFromTreasuryScalp(pnl, WalletManager.lastKnownSolPrice)
                } else {
                    TreasuryManager.contributeFromMemeSell(pnl, WalletManager.lastKnownSolPrice)
                }
            } catch (e: Exception) {
                ErrorLogger.debug("Executor", "Treasury split error (paper): ${e.message}")
                0.0
            }
        } else 0.0

        onPaperBalanceChange?.invoke(value - treasuryShare)
        
        if (cfg().fluidLearningEnabled) {
            FluidLearning.recordPaperSell(tradeId.mint, pos.costSol, pnl)
            FluidLearning.recordPriceImpact(tradeId.mint, pos.costSol, ts.lastLiquidityUsd, isBuy = false)
            FluidLearning.clearPriceImpact(tradeId.mint)
        }
        
        onLog("PAPER SELL @ ${price.fmt()} | $reason | pnl ${pnl.fmt(4)} SOL (${pnlP.fmtPct()})", tradeId.mint)
        onNotify("📉 Paper Sell", "${tradeId.symbol}  $reason  PnL ${pnlP.fmtPct()}", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        
        TradeAlerts.onSell(cfg(), tradeId.symbol, pnl, pnlP, reason, isPaper = true)
        
        if (pnl > 0) sounds?.playCashRegister() else sounds?.playWarningSiren()
        if (pnl > 0) sounds?.playMilestone(pnlP)
        SmartSizer.recordTrade(pnl > 0, isPaperMode = true)

        // V5.9.400 — feed ExecutionCostPredictorAI in paper mode so its
        // per-liq-band history accumulates samples (was 100% zero / DEAD).
        // Synthetic slip = sqrt(size/liq) basis-point model — rough but
        // teaches the layer the relationship between size, liquidity and
        // realized cost. Layer can then refine when live trades run.
        try {
            val liqUsdSlip = ts.lastLiquidityUsd
            val sizeUsd = pos.costSol * WalletManager.lastKnownSolPrice
            if (liqUsdSlip > 0 && sizeUsd > 0) {
                val slipFrac = kotlin.math.sqrt(sizeUsd / liqUsdSlip).coerceAtMost(0.10)
                val quoted = ts.lastPrice
                val realized = quoted * (1.0 - slipFrac)
                com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.learn(
                    liqUsd = liqUsdSlip,
                    quotedPxUsd = quoted,
                    realizedPxUsd = realized,
                )
            }
        } catch (_: Exception) {}

        // V5.9.399 / V5.9.428 — treasury split moved above and integrated with
        // wallet credit so accounting is honest (no double-counting). Losing
        // sells still contribute nothing.

        if (pnl > 0) {
            try {
                val drawdownPct = SmartSizer.getCurrentDrawdownPct(isPaper = true)
                val allocation = AutoCompoundEngine.processWin(pnl, drawdownPct)
                
                if (allocation.toTreasury > 0) {
                    com.lifecyclebot.v3.scoring.CashGenerationAI.addToTreasury(allocation.toTreasury, isPaper = true)
                }
                
                ErrorLogger.info("Executor", "🔄 AUTO-COMPOUND [PAPER]: ${pnl.fmt(4)} SOL profit → " +
                    "Treasury: ${allocation.toTreasury.fmt(3)} | " +
                    "Compound: ${allocation.toCompound.fmt(3)} | " +
                    "Wallet: ${allocation.toWallet.fmt(3)} | " +
                    "Size mult: ${allocation.newSizeMultiplier.fmt(2)}x")
            } catch (e: Exception) {
                ErrorLogger.debug("Executor", "AutoCompound error (paper): ${e.message}")
            }
        }

        try {
            val isWin = pnlP >= 1.0  // V5.9.226: 1% floor — pnl>0 includes fee-wash near-zeros
            
            val treasurySignal = if (isWin) {
                com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT
            } else {
                com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.STOP_LOSS
            }
            com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(ts.mint, price, treasurySignal)
            
            val shitcoinSignal = if (isWin) {
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.TAKE_PROFIT
            } else {
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.STOP_LOSS
            }
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(ts.mint, price, shitcoinSignal)
            
            val bluechipSignal = if (isWin) {
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TAKE_PROFIT
            } else {
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.STOP_LOSS
            }
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.closePosition(ts.mint, price, bluechipSignal)
            
            ErrorLogger.debug("Executor", "✅ Closed all layer positions for ${ts.symbol}")
        } catch (e: Exception) {
            ErrorLogger.error("Executor", "Error closing layer positions: ${e.message}")
        }
        
        try {
            TradeAuthorizer.releasePosition(
                mint = ts.mint,
                reason = "SELL_$reason",
                book = TradeAuthorizer.ExecutionBook.CORE
            )
            ErrorLogger.debug("Executor", "🔓 CORE LOCK RELEASED: ${ts.symbol}")
        } catch (e: Exception) {
            ErrorLogger.debug("Executor", "Failed to release CORE lock: ${e.message}")
        }

        val maxGainPct = if (pos.entryPrice > 0 && pos.highestPrice > 0) {
            ((pos.highestPrice - pos.entryPrice) / pos.entryPrice) * 100.0
        } else 0.0
        
        val tokenAgeMins = if (ts.addedToWatchlistAt > 0) {
            (pos.entryTime - ts.addedToWatchlistAt) / 60_000.0
        } else 0.0
        
        // V5.9.363 — SYMMETRIC SCRATCH ZONE.
        // Old: WIN ≥ +2.0%, LOSS ≤ -3.0%. The asymmetric -3% loss threshold
        // meant winners that retraced from +50% back to +1% got hidden as
        // SCRATCH while -2% losses still showed up as 'real'. With 1960
        // scratches / 209 wins / 581 losses, this asymmetry was distorting
        // the learning signal AND the WR display. Symmetric ±1.5% gives
        // both sides equal weight — fewer scratches, fairer brain training.
        val tradeClassification = when {
            pnlP >= 1.5 -> "WIN"
            pnlP <= -1.5 -> "LOSS"
            else -> "SCRATCH"
        }
        
        val isScratchTrade = tradeClassification == "SCRATCH"
        val shouldLearnAsLoss = tradeClassification == "LOSS"
        val shouldLearnAsWin = tradeClassification == "WIN"
        
        ErrorLogger.info("Executor", "📊 ${tradeId.symbol} CLASSIFIED: $tradeClassification | " +
            "pnl=${pnlP.toInt()}% | hold=${holdTimeMins.toInt()}min | " +
            "learn=${if(isScratchTrade) "NO (scratch)" else "YES"}")
        
        if (shouldLearnAsLoss) {
            val fanName = ts.meta.emafanAlignment
            val ph      = ts.position.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }

            tradeDb?.recordBadObservation(
                featureKey    = "phase=${ph}+ema=${fanName}",
                behaviourType = "ENTRY_SIGNAL",
                description   = "Loss on $ph + $fanName — pnl=${pnlP.toInt()}%",
                lossPct       = pnlP,
            )
            if (src != "UNKNOWN") tradeDb?.recordBadObservation(
                featureKey    = "source=${src}",
                behaviourType = "SOURCE",
                description   = "Loss from source $src",
                lossPct       = pnlP,
            )
            tradeDb?.recordBadObservation(
                featureKey    = "exit_reason=${reason}",
                behaviourType = "EXIT_PATTERN",
                description   = "Exit via $reason resulted in loss",
                lossPct       = pnlP,
            )
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
            
            brain?.let { b ->
                val shouldBlacklist = b.learnFromTrade(
                    isWin = false, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = false,
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "PAPER_BENCHMARK" },
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPct,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMins,
                )
                if (shouldBlacklist) {
                    TokenBlacklist.block(ts.mint, "2+ losses on ${ts.symbol}")
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
                hadSocials = false,
                isPumpFun = ts.source.contains("pump", ignoreCase = true),
                volumeToLiqRatio = if (ts.lastLiquidityUsd > 0) ts.history.lastOrNull()?.vol?.div(ts.lastLiquidityUsd) ?: 0.0 else 0.0,
            )
            onLog("🤖 AI LEARNED: Loss on ${ts.symbol} | phase=$ph ema=$fanName | Pattern recorded", ts.mint)
            
            if (pnlP <= -15.0) {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        com.lifecyclebot.collective.CollectiveLearning.broadcastHotToken(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            pnlPct = pnlP,
                            confidence = 0,
                            liquidityUsd = ts.lastLiquidityUsd,
                            mode = pos.tradingMode.ifBlank { "PAPER" },
                            reason = "AVOID_${(-pnlP).toInt()}%_LOSS"
                        )
                        ErrorLogger.info("Executor", "⚠️ BROADCAST AVOID: ${ts.symbol} ${pnlP.toInt()}% → Network warned!")
                    } catch (e: Exception) {
                        ErrorLogger.debug("Executor", "Broadcast avoid error: ${e.message}")
                    }
                }
            }
        } else if (shouldLearnAsWin) {
            val fanName = ts.meta.emafanAlignment
            val ph      = ts.position.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }
            tradeDb?.recordGoodObservation("phase=${ph}+ema=${fanName}")
            tradeDb?.recordGoodObservation("source=${src}")
            
            brain?.let { b ->
                b.learnFromTrade(
                    isWin = true, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = false,
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "PAPER_BENCHMARK" },
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPct,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMins,
                )
            }
            
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
            
            if (pnlP >= 20.0) {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        com.lifecyclebot.collective.CollectiveLearning.broadcastHotToken(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            pnlPct = pnlP,
                            confidence = pos.entryScore.toInt().coerceIn(0, 100),
                            liquidityUsd = ts.lastLiquidityUsd,
                            mode = pos.tradingMode.ifBlank { "PAPER" },
                            reason = if (pnlP >= 50) "MEGA_WINNER_${pnlP.toInt()}%" else "BIG_WIN_${pnlP.toInt()}%"
                        )
                        ErrorLogger.info("Executor", "📡 BROADCAST TO NETWORK: ${ts.symbol} +${pnlP.toInt()}% → All bots notified!")
                        TradeAlerts.onBigWin(cfg(), ts.symbol, pnl, pnlP, isPaper = true)
                    } catch (e: Exception) {
                        ErrorLogger.debug("Executor", "Broadcast error: ${e.message}")
                    }
                }
            }
        } else {
            onLog("📊 ${ts.symbol}: Scratch trade (${pnlP.toInt()}%) - skipped for learning", ts.mint)
        }
        
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

        val dbIsWin = when {
            isScratchTrade -> null
            pnlP > 5.0 -> true
            else -> false
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
            isWin=dbIsWin,
            isScratch=isScratchTrade,
        ))
        
        try {
            val holdMins = ((System.currentTimeMillis() - ts.position.entryTime) / 60_000.0)
            val tokenAgeMins = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0
            val features = AdaptiveLearningEngine.captureFeatures(
                entryMcapUsd = ts.position.entryLiquidityUsd * 2,
                tokenAgeMinutes = tokenAgeMins,
                buyRatioPct = ts.meta.pressScore,
                volumeUsd = ts.lastLiquidityUsd * ts.meta.volScore / 50.0,
                liquidityUsd = ts.lastLiquidityUsd,
                holderCount = ts.history.lastOrNull()?.holderCount ?: 0,
                topHolderPct = ts.safety.topHolderPct,
                holderGrowthRate = ts.holderGrowthRate,
                devWalletPct = 0.0,
                bondingCurveProgress = 100.0,
                rugcheckScore = ts.safety.rugcheckScore.toDouble(),
                emaFanState = ts.meta.emafanAlignment,
                entryScore = ts.position.entryScore,
                priceFromAth = if (ts.position.highestPrice > 0) 
                    ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice * 100) else 0.0,
                pnlPct = pnlP,
                maxGainPct = if (ts.position.entryPrice > 0) 
                    ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice * 100) else 0.0,
                maxDrawdownPct = 0.0,
                timeToPeakMins = holdMins * 0.5,
                holdTimeMins = holdMins,
                exitReason = reason,
                entryPhase = ts.position.entryPhase,
            )
            AdaptiveLearningEngine.learnFromTrade(features)
            
            if (shouldLearnAsWin || shouldLearnAsLoss) {
                val tokenAgeHours = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 3_600_000.0
                ScannerLearning.recordTrade(
                    source = ts.source.ifEmpty { "UNKNOWN" },
                    liqUsd = ts.lastLiquidityUsd,
                    ageHours = tokenAgeHours,
                    isWin = shouldLearnAsWin
                )
                
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
                
                ModeLearning.selfHealingCheckForMode(tradingMode)
            } else {
                ErrorLogger.debug("ScannerLearning", "Skipped scratch trade for ${ts.symbol} (pnl=${pnlP.toInt()}%)")
            }
        } catch (e: Exception) {
            ErrorLogger.debug("AdaptiveLearning", "Feature capture error: ${e.message}")
        }
        
        try {
            val holdMins = ((System.currentTimeMillis() - ts.position.entryTime) / 60_000.0).toInt()
            val hourOfDay = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val dayOfWeek = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            
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
            
            val volumeSignal = when {
                ts.meta.volScore > 80 -> "SURGE"
                ts.meta.volScore > 60 -> "INCREASING"
                ts.meta.volScore > 40 -> "NORMAL"
                ts.meta.volScore > 20 -> "DECREASING"
                else -> "LOW"
            }
            
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
            
            if (com.lifecyclebot.collective.CollectiveLearning.isEnabled()) {
                ErrorLogger.info("Executor", "🌐 [COLLECTIVE] SELL: isEnabled=true, launching upload for ${ts.symbol}")
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        ErrorLogger.info("Executor", "🌐 [COLLECTIVE] SELL coroutine STARTED for ${ts.symbol}")
                        val liquidityBucket = when {
                            ts.lastLiquidityUsd < 5_000 -> "MICRO"
                            ts.lastLiquidityUsd < 25_000 -> "SMALL"
                            ts.lastLiquidityUsd < 100_000 -> "MID"
                            else -> "LARGE"
                        }
                        
                        ErrorLogger.info("Executor", "🌐 [COLLECTIVE] Calling uploadPatternOutcome for ${ts.symbol}...")
                        com.lifecyclebot.collective.CollectiveLearning.uploadPatternOutcome(
                            patternType = "${ts.position.entryPhase}_${ts.position.tradingMode.ifEmpty { "STANDARD" }}",
                            discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                            liquidityBucket = liquidityBucket,
                            emaTrend = marketSentiment,
                            isWin = shouldLearnAsWin,
                            pnlPct = pnlP,
                            holdMins = holdMins.toDouble()
                        )
                        ErrorLogger.info("Executor", "🌐 [COLLECTIVE] uploadPatternOutcome DONE for ${ts.symbol}")
                        
                        ErrorLogger.info("Executor", "🌐 [COLLECTIVE] Calling uploadTrade SELL for ${ts.symbol}...")
                        com.lifecyclebot.collective.CollectiveLearning.uploadTrade(
                            side = "SELL",
                            symbol = ts.symbol,
                            mode = ts.position.tradingMode.ifEmpty { "STANDARD" },
                            source = ts.source.ifEmpty { "UNKNOWN" },
                            liquidityUsd = ts.lastLiquidityUsd,
                            marketSentiment = marketSentiment,
                            entryScore = ts.position.entryScore.toInt(),
                            confidence = 50,
                            pnlPct = pnlP,
                            holdMins = holdMins.toDouble(),
                            isWin = shouldLearnAsWin,
                            paperMode = cfg().paperMode
                        )
                        ErrorLogger.info("Executor", "🌐 [COLLECTIVE] uploadTrade SELL DONE for ${ts.symbol}")
                        
                        CollectiveAnalytics.recordPatternUpload()
                        
                        ErrorLogger.debug("CollectiveLearning", 
                            "📤 Uploaded: ${ts.symbol} | ${if(shouldLearnAsWin) "WIN" else "LOSS"} | ${pnlP.toInt()}%")
                    } catch (e: Exception) {
                        ErrorLogger.error("CollectiveLearning", "Upload error for ${ts.symbol}: ${e.message}", e)
                    }
                }
            } else {
                ErrorLogger.warn("Executor", "🌐 [COLLECTIVE] SELL: isEnabled=false, skipping upload for ${ts.symbol}")
            }
        } catch (e: Exception) {
            ErrorLogger.debug("BehaviorLearning", "recordTrade error: ${e.message}")
        }
        
        val classification = when {
            isScratchTrade -> "SCRATCH"
            shouldLearnAsWin -> "WIN"
            shouldLearnAsLoss -> "LOSS"
            else -> "UNKNOWN"
        }
        
        tradeId.closed(price, pnlP, pnl, reason)
        tradeId.classified(classification, if (isScratchTrade) null else shouldLearnAsWin)
        WalletPositionLock.recordClose("Meme", ts.position.costSol)
        
        // V5.9.9: Sentient personality reacts
        try {
            if (shouldLearnAsWin) SentientPersonality.onTradeWin(tradeId.symbol, pnlP, ts.position.tradingMode, (System.currentTimeMillis() - ts.position.entryTime) / 1000)
            else if (shouldLearnAsLoss) SentientPersonality.onTradeLoss(tradeId.symbol, pnlP, ts.position.tradingMode, reason)
        } catch (_: Exception) {}
        
        TradeLifecycle.closed(tradeId.mint, price, pnlP, reason)
        TradeLifecycle.classified(tradeId.mint, classification, if (isScratchTrade) null else shouldLearnAsWin)
        TradeLifecycle.clearProposalTracking(tradeId.mint)

        // V5.9.9: Feed meme paper trade into V4 TradeLessonRecorder → StrategyTrustAI
        try {
            val tradingMode = ts.position.tradingMode.ifBlank { "STANDARD" }
            val lessonCtx = com.lifecyclebot.v4.meta.TradeLessonRecorder.TradeLessonContext(
                strategy = tradingMode, market = "MEME", symbol = tradeId.symbol,
                entryRegime = com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_ON,
                entrySession = com.lifecyclebot.v4.meta.SessionContext.OFF_HOURS,
                trustScore = 0.5, fragilityScore = 0.3,
                narrativeHeat = ts.meta.pressScore / 100.0, portfolioHeat = 0.3,
                leverageUsed = 1.0, executionConfidence = ts.entryScore / 100.0,
                leadSource = null, expectedDelaySec = null,
                expectedFillPrice = ts.position.entryPrice,
                executionRoute = "JUPITER_V6",
                captureTime = ts.position.entryTime
            )
            com.lifecyclebot.v4.meta.TradeLessonRecorder.completeLesson(
                context = lessonCtx, outcomePct = pnlP,
                mfePct = if (ts.position.highestPrice > 0 && ts.position.entryPrice > 0)
                    ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice * 100) else pnlP.coerceAtLeast(0.0),
                maePct = pnlP.coerceAtMost(0.0),
                holdSec = ((System.currentTimeMillis() - ts.position.entryTime) / 1000).toInt(),
                exitReason = reason, actualFillPrice = price
            )
        } catch (_: Exception) {}
        
        if (reason.lowercase().contains("distribution")) {
            FinalDecisionGate.recordDistributionExit(tradeId.mint)
            onLog("🚫 Distribution cooldown: ${ts.symbol} blocked for 20-60s", tradeId.mint)
        }
        
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
        
        // V5.9.307: Only meaningful losses trigger ReentryGuard. Scratch trades
        // (-1%..0%) are noise and shouldn't lock out future re-entries.
        if (pnlP <= -1.0) {
            ReentryGuard.onTradeLoss(tradeId.mint, pnlP)
        }
        
        EdgeLearning.learnFromOutcome(
            mint = tradeId.mint,
            exitPrice = price,
            pnlPercent = pnlP,
            wasExecuted = true,
        )
        
        // V5.9.390 — everything below is meme-brain-specific (Whale /
        // Regime / Momentum / Narrative / TimeOpt / CrossTalk / HoldTime /
        // LiquidityDepth / EntryIntelligence / ExitIntelligence all
        // calibrated on meme-base outcomes). Sub-trader closes must NOT
        // pollute them. Computed once up-front, gated below.
        val _psTm = (ts.position.tradingMode ?: "").uppercase()
        val _psIsMemeBase = _psTm.isBlank() || _psTm !in setOf(
            "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS",
            "QUALITY", "BLUECHIP", "BLUE_CHIP",
            "MOONSHOT", "TREASURY"
        )
        if (_psIsMemeBase) {
        EntryIntelligence.learnFromOutcome(tradeId.mint, pnlP, holdMinutes.toInt())
        
        ExitIntelligence.learnFromExit(tradeId.mint, reason, pnlP, holdMinutes.toInt())
        ExitIntelligence.resetPosition(tradeId.mint)
        
        try {
            val wasSignalCorrect = when {
                pnlP > 5.0 -> true
                pnlP < -5.0 -> false
                else -> null
            }
            if (wasSignalCorrect != null) {
                WhaleTrackerAI.recordSignalOutcome(tradeId.mint, wasSignalCorrect, pnlP)
            }
        } catch (_: Exception) {}
        
        try {
            if (abs(pnlP) >= 5.0) {
                MarketRegimeAI.recordTradeOutcome(pnlP)
            }
        } catch (_: Exception) {}
        
        try {
            val peakPnlPct = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else 0.0
            MomentumPredictorAI.recordOutcome(tradeId.mint, pnlP, peakPnlPct)
        } catch (_: Exception) {}
        
        try {
            NarrativeDetectorAI.recordOutcome(ts.symbol, ts.name, pnlP)
        } catch (_: Exception) {}
        
        try {
            TimeOptimizationAI.recordOutcome(pnlP)
        } catch (_: Exception) {}
        
        try {
            TimeModeScheduler.recordTradeOutcome(
                mode = ts.position.tradingMode.ifEmpty { "SMART_SNIPER" },
                pnlPct = pnlP
            )
        } catch (_: Exception) {}
        
        try {
        } catch (_: Exception) {}
        
        try {
            LiquidityDepthAI.recordOutcome(ts.mint, pnlP, pnlP > 0)
            LiquidityDepthAI.clearEntryLiquidity(ts.mint)
        } catch (_: Exception) {}
        
        try {
            val crossTalkSignal = AICrossTalk.analyzeCrossTalk(ts, isOpenPosition = false)
            if (crossTalkSignal.signalType != AICrossTalk.SignalType.NO_CORRELATION) {
                AICrossTalk.recordOutcome(crossTalkSignal.signalType, pnlP, pnlP > 0)
            }
        } catch (_: Exception) {}
        } // end _psIsMemeBase gate (V5.9.390)
        
        try {
            val setupQuality = when {
                ts.position.entryScore > 70 -> "A+"
                ts.position.entryScore > 60 -> "A"
                ts.position.entryScore > 50 -> "B"
                else -> "C"
            }
            
            HoldTimeOptimizerAI.recordOutcome(
                mint = tradeId.mint,
                actualHoldMinutes = holdMinutes.toInt(),
                pnlPct = pnlP,
                setupQuality = setupQuality
            )
            
            ErrorLogger.debug("Executor", "📊 HoldTimeAI learned: ${ts.symbol} " +
                "${holdMinutes.toInt()}min hold | ${pnlP.toInt()}% PnL | $setupQuality setup")
        } catch (e: Exception) {
            ErrorLogger.warn("Executor", "HoldTimeAI recordOutcome failed: ${e.message}")
        }
        
        try {
            val peakPnlLive = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else pnlP
            
            val latestBuyPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
            val approxEntryMcap = ts.position.entryLiquidityUsd * 2
            
            TokenWinMemory.recordTradeOutcome(
                mint = tradeId.mint,
                symbol = ts.symbol,
                name = ts.name,
                pnlPercent = pnlP,
                peakPnl = peakPnlLive,
                entryMcap = approxEntryMcap,
                exitMcap = ts.lastMcap,
                entryLiquidity = ts.position.entryLiquidityUsd,
                holdTimeMinutes = holdMinutes.toInt(),
                buyPercent = latestBuyPct,
                source = ts.source,
                phase = ts.position.entryPhase,
            )
        } catch (_: Exception) {}
        
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
                holdTimeMinutes = holdMinutes.toInt(),
                exitReason = reason,
                entryPhase = ts.position.entryPhase.ifEmpty { "UNKNOWN" },
                tradingMode = ts.position.tradingMode.ifEmpty { "STANDARD" },
                discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                liquidityUsd = ts.lastLiquidityUsd,
                emaTrend = marketSentiment
            )
            com.lifecyclebot.v3.V3EngineManager.onPositionClosed(tradeId.mint)
            // V5.9.137 — mirror the close to SellOptimizationAI so its
            // activePositions map can't rot with stale peak / chunksSold
            // between exits. Previously close was only called inside one
            // branch in BotService (HIGH/CRITICAL urgency), so any exit
            // via stop-loss, trailing, signal flip, manual, TP/SL, etc.
            // left the map dirty and broke re-entries on the same mint.
            try {
                com.lifecyclebot.v3.scoring.SellOptimizationAI.closePosition(tradeId.mint, pnlP)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
        
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
            
            PortfolioAnalytics.removePosition(ts.mint)
            
        } catch (e: Exception) {
            ErrorLogger.debug("QuantMetrics", "Recording error: ${e.message}")
        }
        
        try {
            val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
            val isWin = pnlP >= 1.0  // V5.9.185: unified 1% floor
            val modeStr = ts.position.tradingMode
            
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
            
            val outcomeStr = if (isWin) "WIN" else if (pnlP < -2.0) "LOSS" else "SCRATCH"
            SuperBrainEnhancements.updateInsightOutcome(ts.mint, outcomeStr, pnlP)
        } catch (e: Exception) {
            ErrorLogger.debug("ModeOrchestrator", "Recording error: ${e.message}")
        }
        
        try {
            val treasuryExitSignal = when {
                reason.lowercase().contains("profit") || reason.lowercase().contains("target") -> 
                    com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT
                reason.lowercase().contains("stop") || reason.lowercase().contains("loss") -> 
                    com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.STOP_LOSS
                reason.lowercase().contains("trail") -> 
                    com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TRAILING_STOP
                reason.lowercase().contains("time") || reason.lowercase().contains("hold") -> 
                    com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TIME_EXIT
                else -> com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.HOLD
            }
            com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(tradeId.mint, price, treasuryExitSignal)
        } catch (_: Exception) {}
        
        try {
            val blueChipExitSignal = when {
                reason.lowercase().contains("profit") || reason.lowercase().contains("target") -> 
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TAKE_PROFIT
                reason.lowercase().contains("stop") || reason.lowercase().contains("loss") -> 
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.STOP_LOSS
                reason.lowercase().contains("trail") -> 
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TRAILING_STOP
                reason.lowercase().contains("time") || reason.lowercase().contains("hold") -> 
                    com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TIME_EXIT
                else -> com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.HOLD
            }
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.closePosition(tradeId.mint, price, blueChipExitSignal)
        } catch (_: Exception) {}
        
        ts.position         = Position()
        ts.lastExitTs       = System.currentTimeMillis()
        ts.lastExitPrice    = price
        ts.lastExitPnlPct   = pnlP
        ts.lastExitWasWin   = pnlP >= 1.0  // V5.9.185
        // V5.9.256: Mark closed in persistent wallet memory
        try { WalletTokenMemory.recordExit(ts.mint, ts.symbol, price, pnlP, reason) } catch (_: Exception) {}
        
        try {
            PositionPersistence.savePosition(ts)
        } catch (e: Exception) {
            ErrorLogger.debug("Executor", "Position persistence removal error: ${e.message}")
        }

        if (!isScratchTrade) {
            ShadowLearningEngine.onLiveTradeExit(
                mint = tradeId.mint,
                exitPrice = price,
                exitReason = reason,
                livePnlSol = pnl,
                isWin = pnlP >= 1.0  // V5.9.185: unified 1% floor,
            )
        }
        
        try {
            val setupQualityStr = when (tradeClassification) {
                "RUNNER" -> "EXCELLENT"
                "BIG_WIN" -> "EXCELLENT"
                "WIN" -> "GOOD"
                "SCRATCH" -> "NEUTRAL"
                "LOSS" -> "POOR"
                "BAD" -> "BAD"
                else -> "NEUTRAL"
            }
            
            val currentHolderCount = ts.history.lastOrNull()?.holderCount ?: 0
            val currentVolume = ts.history.lastOrNull()?.vol ?: 0.0
            // V5.9.127: guard against unset entryTime (raw epoch leak → 56-year hold)
            val entryTimeSafeEdu = if (ts.position.entryTime > 1_000_000_000_000L)
                ts.position.entryTime else System.currentTimeMillis()
            val holdTimeDouble = (System.currentTimeMillis() - entryTimeSafeEdu) / 60000.0
            val approxTokenAgeMinutes = holdTimeDouble + 5.0
            val peakPnl = if (ts.position.entryPrice > 0 && ts.position.highestPrice > 0) {
                ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100.0
            } else pnlP
            
            val outcomeData = com.lifecyclebot.v3.scoring.EducationSubLayerAI.TradeOutcomeData(
                mint = tradeId.mint,
                symbol = ts.symbol,
                tokenName = ts.name,
                pnlPct = pnlP,
                holdTimeMinutes = holdTimeDouble,
                exitReason = reason,
                entryPhase = ts.position.entryPhase.ifEmpty { "UNKNOWN" },
                tradingMode = ts.position.tradingMode.ifEmpty { "STANDARD" },
                discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                setupQuality = setupQualityStr,
                entryMcapUsd = ts.position.entryMcap.takeIf { it > 0 } ?: (ts.position.entryLiquidityUsd * 2),
                exitMcapUsd = ts.lastMcap,
                tokenAgeMinutes = approxTokenAgeMinutes,
                buyRatioPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0,
                volumeUsd = currentVolume,
                liquidityUsd = ts.lastLiquidityUsd,
                holderCount = currentHolderCount,
                topHolderPct = ts.topHolderPct ?: 0.0,
                holderGrowthRate = ts.holderGrowthRate,
                devWalletPct = 0.0,
                bondingCurveProgress = 0.0,
                rugcheckScore = ts.safety.rugcheckScore.toDouble().coerceAtLeast(0.0),
                emaFanState = ts.meta.emafanAlignment.ifEmpty { "UNKNOWN" },
                entryScore = ts.entryScore,
                priceFromAth = 0.0,
                maxGainPct = peakPnl,
                maxDrawdownPct = ts.position.lowestPrice.let { low ->
                    if (low > 0 && ts.position.entryPrice > 0) {
                        ((low - ts.position.entryPrice) / ts.position.entryPrice) * 100.0
                    } else 0.0
                },
                timeToPeakMins = holdTimeDouble * 0.5,
                // V5.9.320: derive traderSource from actual trading mode, not hardcoded
                traderSource = when {
                    ts.position.tradingMode.startsWith("MOONSHOT") -> "MOONSHOT"
                    ts.position.tradingMode == "SHITCOIN"   -> "SHITCOIN"
                    ts.position.tradingMode == "BLUE_CHIP"  -> "BLUECHIP"
                    ts.position.tradingMode == "QUALITY"    -> "QUALITY"
                    ts.position.tradingMode == "TREASURY"   -> "TREASURY"
                    ts.position.tradingMode == "DIP_HUNTER" -> "DIP_HUNTER"
                    ts.position.tradingMode == "MANIPULATED" -> "MANIPULATED"
                    else -> "MEME"
                },
                lossReason = if (pnlP < -2.0) reason else "",
            )
            
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordTradeOutcomeAcrossAllLayers(outcomeData)
            ErrorLogger.info("Executor", "🎓 HARVARD BRAIN: Recorded outcome for ${ts.symbol} | PnL=${pnlP.toInt()}% | Active layers will increase")
        } catch (e: Exception) {
            ErrorLogger.warn("Executor", "🎓 Harvard Brain recording failed: ${e.message}")
        }
        
        // V5.9.248: stamp cooldown so universal gate blocks immediate re-entry
        com.lifecyclebot.engine.BotService.recentlyClosedMs[ts.mint] = System.currentTimeMillis()

        return SellResult.PAPER_CONFIRMED
    }

    private fun liveSell(ts: TokenState, reason: String,
                         wallet: SolanaWallet, walletSol: Double,
                         identity: TradeIdentity? = null): SellResult {
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val c   = cfg()
        val pos = ts.position
        // V5.9.262 — group all SELL events with the BUY events for the same trade
        // by reusing the entryTime as the keystone.
        val sellTradeKey = LiveTradeLogStore.keyFor(ts.mint, pos.entryTime)
        
        onLog("🔄 SELL START: ${ts.symbol} | reason=$reason | pos.isOpen=${pos.isOpen} | pos.qtyToken=${pos.qtyToken} | pos.costSol=${pos.costSol}", tradeId.mint)
        LiveTradeLogStore.log(
            sellTradeKey, ts.mint, ts.symbol, "SELL",
            LiveTradeLogStore.Phase.SELL_START,
            "🔴 LIVE SELL START | reason=$reason | qty=${pos.qtyToken.fmt(4)} | wallet=${walletSol.fmt(4)} SOL",
            tokenAmount = pos.qtyToken, traderTag = "MEME",
        )
        
        // V5.9.290 FIX: If pendingVerify is still true but tokens exist (qtyToken > 0)
        // AND it's been > 120s since entry, force-clear pendingVerify here before the
        // isOpen guard. This mirrors the BotService fix: the verify coroutine runs for
        // ≤30s — if we're still pending at 120s it's definitively stuck, and we must
        // not abort an urgent sell (rug, stop-loss) just because verification stalled.
        // This is the LAST LINE OF DEFENCE — BotService should have already cleared it.
        if (pos.pendingVerify && pos.qtyToken > 0.0) {
            val pendingAgeMs = System.currentTimeMillis() - pos.entryTime
            if (pendingAgeMs >= 120_000L) {
                ErrorLogger.warn("Executor",
                    "⚠️ [LIVESELL_VERIFY_STUCK] ${ts.symbol} | ${pendingAgeMs / 1000}s — force-clearing pendingVerify in liveSell. " +
                    "Proceeding with sell to protect position.")
                synchronized(ts) {
                    ts.position = ts.position.copy(pendingVerify = false)
                }
            }
        }
        // Re-read pos after potential pendingVerify clear above
        val posAfterVerifyCheck = ts.position
        if (!posAfterVerifyCheck.isOpen) {
            // Also handles qtyToken == 0 (genuinely nothing to sell)
            onLog("🛑 SELL ABORTED: Position not open (qtyToken=${posAfterVerifyCheck.qtyToken}, pendingVerify=${posAfterVerifyCheck.pendingVerify})", tradeId.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_FAILED,
                "ABORTED — pos.isOpen=false (qty=${posAfterVerifyCheck.qtyToken} pendingVerify=${posAfterVerifyCheck.pendingVerify})",
                traderTag = "MEME",
            )
            return SellResult.ALREADY_CLOSED
        }

        val integrityOk = security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })
        
        if (!integrityOk) {
            ErrorLogger.warn("Executor", "⚠️ Keypair integrity mismatch for SELL - attempting reload...")
            
            try {
                val reloadedWallet = WalletManager.attemptReconnect()
                if (reloadedWallet != null) {
                    val retryIntegrity = security.verifyKeypairIntegrity(
                        reloadedWallet.publicKeyB58,
                        c.walletAddress.ifBlank { reloadedWallet.publicKeyB58 }
                    )
                    if (retryIntegrity) {
                        ErrorLogger.info("Executor", "✅ Keypair reloaded successfully, proceeding with sell")
                        return liveSell(ts, reason, reloadedWallet, reloadedWallet.getSolBalance(), tradeId)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.warn("Executor", "⚠️ Keypair reload attempt failed: ${e.message}")
            }
            
            onLog("⚠️ SELL PROCEEDING DESPITE INTEGRITY WARNING: ${ts.symbol}", tradeId.mint)
            ErrorLogger.warn("Executor", "⚠️ SELL PROCEEDING: Integrity failed but attempting anyway for ${ts.symbol}")
        }

        var tokenUnits = resolveSellUnits(ts, pos.qtyToken)
        onLog("📊 SELL DEBUG: Initial tokenUnits from tracker = $tokenUnits", tradeId.mint)

        try {
            onLog("📊 SELL DEBUG: Fetching on-chain token balances...", tradeId.mint)
            val onChainBalances = wallet.getTokenAccountsWithDecimals()
            val tokenData = onChainBalances[ts.mint]
            
            if (tokenData == null || tokenData.first <= 0.0) {
                // V5.9.72 CRITICAL FIX: previous logic force-closed the position
                // after 5 "zero balance" reads, calling tradeId.closed(...) and
                // returning CONFIRMED even though NO swap was broadcast. If the
                // RPC returned an empty/errored map (rate-limit, slow sync),
                // the tokens were still in the user's wallet but the bot
                // marked them "sold at -100%", ate the PnL accounting, and
                // walked away. User saw: tokens remain on-chain, no SOL back.
                //
                // New behaviour:
                //   - If the on-chain map came back NON-EMPTY and this mint is
                //     simply absent/zero → tokens are genuinely gone (rug,
                //     honeypot, external sell). We surface that as an orphan
                //     alert, leave the position open, and let the user clear
                //     it manually via the UI. We do NOT claim a sell PnL.
                //   - If the map is EMPTY / threw → RPC is broken, tokens
                //     are almost certainly still there. Retry with backoff,
                //     never close.
                val mapEmpty = onChainBalances.isEmpty()
                val retryCount = zeroBalanceRetries.merge(ts.mint, 1) { old, _ -> old + 1 } ?: 1

                if (!mapEmpty && retryCount >= 20) {
                    onLog("🚨 ORPHAN ALERT: ${ts.symbol} — on-chain balance has been 0 for $retryCount " +
                          "consecutive reads. Tokens likely rugged / externally sold. Position kept OPEN for " +
                          "manual release.", tradeId.mint)
                    onNotify("🚨 Orphan Position",
                        "${ts.symbol}: on-chain balance 0 for $retryCount polls. No swap was sent. " +
                        "Clear manually from the positions panel.",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    // IMPORTANT: do NOT call tradeId.closed(...) here. The bot
                    // claiming a trade it never made is what burned the user
                    // in V5.9.71 and earlier.
                    return SellResult.FAILED_RETRYABLE
                }

                onLog("SELL BLOCKED: ${if (mapEmpty) "RPC returned empty balance map" else "balance=0"} " +
                      "for ${ts.symbol} (retry $retryCount/20)", tradeId.mint)
                ErrorLogger.warn("Executor", "LIVE SELL BLOCKED: ${ts.symbol} " +
                    "${if (mapEmpty) "RPC_EMPTY_MAP" else "ONCHAIN_ZERO"} (retry $retryCount/20)")
                return SellResult.FAILED_RETRYABLE
            }
            
            val actualBalanceUi = tokenData.first
            val actualDecimals = tokenData.second
            onLog("📊 SELL DEBUG: On-chain balance = $actualBalanceUi | decimals=$actualDecimals | mint=${ts.mint.take(8)}...", tradeId.mint)
            
            // V5.7.8: If balance is dust AND position is deep in loss, force close
            // Jupiter can't swap tiny amounts — don't keep retrying forever
            val entryValueSol = pos.costSol
            val currentValueEstimate = actualBalanceUi * (getActualPrice(ts))
            val isDeepLoss = entryValueSol > 0 && currentValueEstimate < entryValueSol * 0.01 // Worth < 1% of entry
            val isDust = actualBalanceUi < 1.0 || currentValueEstimate < 0.001 // Less than 1 token or < 0.001 SOL
            
            if (isDust && isDeepLoss) {
                // V5.9.72 CRITICAL FIX: previously this branch called
                // tradeId.closed(-100%) and returned CONFIRMED — claiming a
                // sell that never happened. The tokens remained in the
                // wallet. Instead we now alert the user and pause retries
                // on this mint; the position stays OPEN until the user
                // manually releases it or Jupiter becomes quotable again.
                onLog("🚨 STUCK POSITION: ${ts.symbol} — balance=$actualBalanceUi (~${String.format("%.6f", currentValueEstimate)} SOL) " +
                      "too small for Jupiter. NOT sold — tokens remain on-chain.", tradeId.mint)
                onNotify("🚨 Stuck Position",
                    "${ts.symbol}: dust-sized balance, Jupiter can't route. Tokens remain in wallet. " +
                    "Clear manually from the positions panel.",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                ErrorLogger.warn("Executor", "STUCK POSITION: ${ts.symbol} — dust (${actualBalanceUi} tokens) — " +
                    "left open, no swap broadcast.")
                return SellResult.FAILED_RETRYABLE
            }
            
            val multiplier = 10.0.pow(actualDecimals.toDouble())
            val actualRawUnits = (actualBalanceUi * multiplier).toLong()
            
            onLog("📊 SELL DEBUG: tracked=$tokenUnits | on-chain=$actualRawUnits (${actualDecimals}dec)", tradeId.mint)
            
            val diffPct = if (tokenUnits > 0) abs((actualRawUnits - tokenUnits).toDouble()) / tokenUnits * 100 else 0.0
            if (diffPct > 1.0) {
                onLog("⚠️ Balance adjustment: using on-chain balance ($actualRawUnits) instead of tracked ($tokenUnits)", tradeId.mint)
            }
            
            tokenUnits = actualRawUnits.coerceAtLeast(1L)
            onLog("📊 SELL DEBUG: Final tokenUnits to sell = $tokenUnits", tradeId.mint)
            
        } catch (e: Exception) {
            onLog("⚠️ SELL DEBUG: Balance check failed: ${e.message?.take(60)}", tradeId.mint)
            onLog("   Proceeding with tracked qty: $tokenUnits", tradeId.mint)
        }

        var pnl  = 0.0
        var pnlP = 0.0

        try {
            val sellSlippage = (c.slippageBps * 2).coerceAtMost(500)
            onLog("📊 SELL DEBUG: Requesting quote | slippage=${sellSlippage}bps | tokenUnits=$tokenUnits", tradeId.mint)
            
            var quote: com.lifecyclebot.network.SwapQuote? = null
            var lastError: Exception? = null
            
            // V5.7.8: Aggressive sell — try normal slippage, then 2x, then 5x, then max
            // V5.9.103: all slippage levels hard-capped at 500 bps (5%).
            // Previous panic-escalation went to 5000 bps (50%) which would
            // accept selling a rugged token at half price — unacceptable for
            // a fee-paying swap. If 5% can't fill, abort the sell and let
            // the reconciler handle the stuck position on next startup.
            val slippageLevels = listOf(sellSlippage, 300, 500).distinct()
            
            for (slipLevel in slippageLevels) {
                for (attempt in 1..2) {
                    try {
                        onLog("SELL: Quote attempt slippage=${slipLevel}bps try=$attempt...", tradeId.mint)
                        quote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT,
                                                           tokenUnits, slipLevel.coerceAtMost(500), isBuy = false)
                        onLog("SELL: Quote OK | out=${quote.outAmount} | impact=${quote.priceImpactPct}%", tradeId.mint)
                        break
                    } catch (e: Exception) {
                        lastError = e
                        onLog("SELL: Quote failed slippage=${slipLevel}bps: ${e.message?.take(50)}", ts.mint)
                        if (attempt < 2) Thread.sleep(300)
                    }
                }
                if (quote != null) break
            }
            
            // V5.9.72 CRITICAL FIX: If ALL quote attempts failed, KEEP the
            // position open. Previously this force-closed with -100% PnL and
            // claimed a sell that never happened; the tokens stayed in the
            // user's wallet. A failed Jupiter quote is almost always
            // recoverable (rate-limit, indexer lag, momentary thin pool).
            if (quote == null) {
                onLog("🚨 SELL STUCK: ${ts.symbol} — all ${slippageLevels.size * 2} quote attempts failed. " +
                      "Position kept OPEN; will retry next cycle.", tradeId.mint)
                onNotify("🚨 Jupiter Can't Quote",
                    "${ts.symbol}: no route from Jupiter right now. Bot will retry. " +
                    "If persistent, clear manually from the positions panel.",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                ErrorLogger.warn("Executor", "SELL STUCK: ${ts.symbol} — Jupiter quote exhausted; " +
                    "NOT closing position, NOT claiming sell PnL.")
                return SellResult.FAILED_RETRYABLE
            }

            val qGuard = security.validateQuote(quote, isBuy = false, inputSol = pos.costSol)
            if (qGuard is GuardResult.Block) {
                onLog("⚠ Sell quote warning: ${qGuard.reason} — proceeding anyway", ts.mint)
            }

            onLog("📊 SELL DEBUG: Building transaction...", tradeId.mint)
            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            onLog("📊 SELL DEBUG: Transaction built | requestId=${txResult.requestId?.take(16) ?: "none"}", tradeId.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_TX_BUILT,
                "Tx built | router=${txResult.router} | rfq=${txResult.isRfqRoute}",
                traderTag = "MEME",
            )
            security.enforceSignDelay()
            
            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            
            if (quote.isUltra) {
                onLog("🚀 Broadcasting sell via Jupiter Ultra (Beam MEV protection)…", ts.mint)
            } else if (useJito) {
                onLog("⚡ Broadcasting sell tx via Jito MEV protection…", ts.mint)
            } else {
                onLog("Broadcasting sell tx…", ts.mint)
            }
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_BROADCAST,
                "Broadcasting | route=${if (quote.isUltra) "ULTRA" else if (useJito) "JITO" else "RPC"}",
                traderTag = "MEME",
            )
            
            onLog("📊 SELL DEBUG: Signing and broadcasting (router=${txResult.router}, rfq=${txResult.isRfqRoute})...", tradeId.mint)
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            val sig     = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
            onLog("📊 SELL DEBUG: Transaction confirmed! sig=${sig.take(20)}...", tradeId.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_CONFIRMED,
                "✅ Sell tx confirmed on-chain",
                sig = sig, traderTag = "MEME",
            )
            
            try {
                // V5.9.455 — FEE FAIRNESS FIX.
                // Previously this computed fee as 0.5% of the SELL PROCEEDS
                // (quote.outAmount), which meant losing sells paid far less
                // than winning sells and total-loss rugs paid ≈0 SOL because
                // the feeAmountSol >= 0.0001 guard rounded it away. Every
                // other fee path (profit-lock, partial-sell v1/v2, buy)
                // already uses pos.costSol as the basis. Align this path:
                // fee = 0.5% of ENTRY BASIS, applied to every live sell
                // whether it was profitable or not. Operator directive:
                // "live transaction fees should be on all sells profitable
                // or not."
                val feeAmountSol = pos.costSol * MEME_TRADING_FEE_PERCENT
                if (feeAmountSol >= 0.0001) {
                    val feeWallet1 = feeAmountSol * FEE_SPLIT_RATIO
                    val feeWallet2 = feeAmountSol * (1.0 - FEE_SPLIT_RATIO)
                    
                    // Send to wallet 1
                    if (feeWallet1 >= 0.0001) {
                        wallet.sendSol(TRADING_FEE_WALLET_1, feeWallet1)
                    }
                    // Send to wallet 2
                    if (feeWallet2 >= 0.0001) {
                        wallet.sendSol(TRADING_FEE_WALLET_2, feeWallet2)
                    }
                    
                    onLog("💸 TRADING FEE: ${String.format("%.6f", feeAmountSol)} SOL (0.5% of entry) split 50/50", tradeId.mint)
                    ErrorLogger.info("Executor", "💸 LIVE SELL FEE: ${feeAmountSol} SOL split to both wallets (entry-basis, pnl-agnostic)")
                }
            } catch (feeEx: Exception) {
                ErrorLogger.error("Executor", "🚨 FEE SEND FAILED — TRADING fee NOT sent, will retry next trade: ${feeEx.message}")
                // V5.9.226: Bug #7 — enqueue for retry (recalculate — feeAmountSol not in catch scope)
                val feeAmtSellRetry = (pos.costSol * MEME_TRADING_FEE_PERCENT).coerceAtLeast(0.0)
                if (feeAmtSellRetry >= 0.0001) {
                    FeeRetryQueue.enqueue(TRADING_FEE_WALLET_1, feeAmtSellRetry * FEE_SPLIT_RATIO, "sell_fee_w1")
                    FeeRetryQueue.enqueue(TRADING_FEE_WALLET_2, feeAmtSellRetry * (1.0 - FEE_SPLIT_RATIO), "sell_fee_w2")
                }
            }
            
            try {
                Thread.sleep(1500)
                val postSellBalances = wallet.getTokenAccountsWithDecimals()
                val remainingTokens = postSellBalances[ts.mint]?.first ?: 0.0
                
                val originalTokens = pos.qtyToken
                if (originalTokens > 0 && remainingTokens > originalTokens * 0.01) {
                    val remainingPct = (remainingTokens / originalTokens * 100).toInt()
                    onLog("🚨 SELL INCOMPLETE: Still holding ${remainingPct}% of tokens!", tradeId.mint)
                    onLog("   Original: $originalTokens | Remaining: $remainingTokens", tradeId.mint)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_FAILED,
                        "🚨 Sell incomplete — ${remainingPct}% (${remainingTokens.fmt(4)}) still in wallet",
                        tokenAmount = remainingTokens, traderTag = "MEME",
                    )
                    
                    if (remainingTokens > 0.01) {
                        onLog("🧹 DUST-BUSTER: Attempting to sell remaining $remainingTokens tokens...", tradeId.mint)
                        try {
                            val remainingUnits = resolveSellUnits(ts, remainingTokens, wallet = wallet)
                            val dustQuote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT,
                                                                       remainingUnits, 1500, isBuy = false)
                            val dustTx = buildTxWithRetry(dustQuote, wallet.publicKeyB58)
                            val dustSig = wallet.signSendAndConfirm(dustTx.txBase64, c.jitoEnabled, c.jitoTipLamports, 
                                if (dustQuote.isUltra) dustTx.requestId else null, c.jupiterApiKey, dustTx.isRfqRoute)
                            
                            onLog("🧹 DUST-BUSTER SUCCESS: Sold remaining tokens | sig=${dustSig.take(20)}...", tradeId.mint)
                            
                            Thread.sleep(1500)
                            val finalBalances = wallet.getTokenAccountsWithDecimals()
                            val finalRemaining = finalBalances[ts.mint]?.first ?: 0.0
                            onLog("🧹 DUST-BUSTER: Final balance = $finalRemaining tokens", tradeId.mint)
                        } catch (dustEx: Exception) {
                            onLog("⚠️ DUST-BUSTER FAILED: ${dustEx.message?.take(60)}", tradeId.mint)
                            onNotify("🚨 Sell Incomplete!",
                                "${ts.symbol}: ${remainingPct}% tokens still in wallet — sell queued for retry.",
                                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                            // V5.9.52: re-throw so position is NOT cleared and the sell is re-queued
                            throw RuntimeException("Sell incomplete: $remainingPct% tokens remain after dust-buster failed (${dustEx.message?.take(40)})")
                        }
                    }
                } else {
                    onLog("✅ SELL VERIFIED: Token balance is now ${remainingTokens} (was $originalTokens)", tradeId.mint)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_VERIFY_TOKEN_GONE,
                        "✅ Token left host wallet | remaining=${remainingTokens.fmt(4)} (was ${originalTokens.fmt(4)})",
                        tokenAmount = remainingTokens, traderTag = "MEME",
                    )
                    // Verify SOL returned by checking balance bump
                    try {
                        Thread.sleep(800)
                        val newSol = wallet.getSolBalance()
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_VERIFY_SOL_RETURNED,
                            "💰 SOL balance after sell: ${newSol.fmt(4)} (was ${walletSol.fmt(4)})",
                            solAmount = newSol, traderTag = "MEME",
                        )
                    } catch (_: Exception) {}
                }
            } catch (verifyEx: RuntimeException) {
                throw verifyEx
            } catch (e: Exception) {
                onLog("⚠️ SELL VERIFICATION: Balance check failed (${e.message?.take(40)})", tradeId.mint)
                
                try {
                    Thread.sleep(2000)
                    val retryBalances = wallet.getTokenAccountsWithDecimals()
                    val retryRemaining = retryBalances[ts.mint]?.first ?: 0.0
                    
                    if (retryRemaining > pos.qtyToken * 0.01) {
                        val retryPct = (retryRemaining / pos.qtyToken * 100).toInt()
                        onLog("🚨 SELL VERIFICATION RETRY: Still holding ${retryPct}% of tokens!", tradeId.mint)
                        
                        if (retryRemaining > 0.01) {
                            onLog("🧹 DUST-BUSTER (retry): Attempting to sell remaining $retryRemaining tokens...", tradeId.mint)
                            try {
                                val retryUnits = resolveSellUnits(ts, retryRemaining, wallet = wallet)
                                val dustQuote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT,
                                                                           retryUnits, 2000, isBuy = false)
                                val dustTx = buildTxWithRetry(dustQuote, wallet.publicKeyB58)
                                val dustSig = wallet.signSendAndConfirm(dustTx.txBase64, c.jitoEnabled, c.jitoTipLamports,
                                    if (dustQuote.isUltra) dustTx.requestId else null, c.jupiterApiKey, dustTx.isRfqRoute)
                                onLog("🧹 DUST-BUSTER (retry) SUCCESS: sig=${dustSig.take(20)}...", tradeId.mint)
                            } catch (dustEx: Exception) {
                                onLog("⚠️ DUST-BUSTER (retry) FAILED: ${dustEx.message?.take(60)}", tradeId.mint)
                            }
                        }
                        
                        onNotify("🚨 Sell Incomplete!",
                            "${ts.symbol}: ${retryPct}% tokens still in wallet after retry!",
                            com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                        throw RuntimeException("Sell verification retry failed: still holding ${retryPct}% tokens")
                    } else {
                        onLog("✅ SELL VERIFIED on retry: Token balance is now ${retryRemaining}", tradeId.mint)
                    }
                } catch (retryEx: RuntimeException) {
                    throw retryEx
                } catch (retryE: Exception) {
                    onLog("🚨 CRITICAL: Cannot verify sell completion - keeping position active!", tradeId.mint)
                    onNotify("🚨 Sell Unverified!",
                        "${ts.symbol}: Cannot verify on-chain. Position NOT cleared. Check manually!",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    onToast("🚨 SELL UNVERIFIED: ${ts.symbol}\nCannot confirm on-chain. Manual check required!")
                    throw RuntimeException("Sell unverifiable: balance check failed twice (${retryE.message})")
                }
            }
            
            val price   = getActualPrice(ts)
            // V5.9.196: Use actual on-chain SOL balance delta for PnL, not the quoted estimate.
            // Jupiter quote.outAmount is estimated output before MEV/slippage. We read the
            // live SOL balance (after awaitConfirmation confirmed the tx) and diff against the
            // pre-sell walletSol to get the actual SOL received into the connected wallet.
            val solBack: Double = try {
                val actualBalance = wallet.getSolBalance()
                val delta = actualBalance - walletSol
                if (delta > 0.001) {
                    onLog("📊 SELL PnL (actual): received=${delta.fmt(6)} SOL | quoted=${(quote.outAmount / 1_000_000_000.0).fmt(6)} SOL", tradeId.mint)
                    pos.costSol + delta  // costSol + delta = total back (delta = net SOL gain/loss vs cost)
                } else {
                    // RPC lag or fee deduction made delta look tiny — fall back to quote
                    onLog("📊 SELL PnL (quoted fallback): delta=${delta.fmt(6)} | outAmount=${quote.outAmount}", tradeId.mint)
                    quote.outAmount / 1_000_000_000.0
                }
            } catch (balEx: Exception) {
                onLog("📊 SELL PnL: balance read failed (${balEx.message?.take(40)}), using quote", tradeId.mint)
                quote.outAmount / 1_000_000_000.0
            }
            pnl  = solBack - pos.costSol
            pnlP = pct(pos.costSol, solBack)
            val (netPnl, feeSol) = slippageGuard.calcNetPnl(pnl, pos.costSol)

            // V5.9.357 — feed real Jupiter quote-vs-realized slip into
            // ExecutionCostPredictorAI so its per-liquidity-band history
            // finally accumulates samples. quote.outAmount = optimistic
            // estimate; solBack = actual SOL received post-fees/MEV.
            try {
                val quotedSol = quote.outAmount / 1_000_000_000.0
                if (quotedSol > 0.0 && solBack > 0.0) {
                    val liqUsd = ts.lastLiquidityUsd
                    com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.learn(
                        liqUsd = liqUsd,
                        quotedPxUsd = quotedSol,
                        realizedPxUsd = solBack,
                    )
                }
            } catch (_: Exception) {}

            // V5.9.72: clear retry counters on a genuine successful swap so
            // the mint starts fresh next time.
            zeroBalanceRetries.remove(ts.mint)
            zeroBalanceRetries.remove(ts.mint + "_broadcast")
            
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
            
            EmergentGuardrails.unregisterPosition(tradeId.mint)
            // V5.9.385 — live-path close: release the scanner eviction guard.
            try {
                com.lifecyclebot.engine.GlobalTradeRegistry.closePosition(tradeId.mint)
            } catch (_: Exception) { /* non-critical */ }

            SmartSizer.recordTrade(pnl > 0, isPaperMode = false)
            LiveSafetyCircuitBreaker.recordTradeResult(netPnl)  // V5.9.105 session drawdown halt

            // V5.9.399 / V5.9.428 — treasury split (live-mode mirror of paperSell).
            // Live can't retroactively deduct from the on-chain wallet (SOL
            // already returned by Jupiter at full value); this is bookkeeping
            // only until an on-chain transfer to a dedicated treasury wallet
            // is wired up. Meme wins → 30%. Treasury scalps → 100%. Losing
            // sells contribute nothing.
            if (pnl > 0) {
                try {
                    if (ts.position.isTreasuryPosition || ts.position.tradingMode == "TREASURY") {
                        TreasuryManager.contributeFullyFromTreasuryScalp(pnl, WalletManager.lastKnownSolPrice)
                    } else {
                        TreasuryManager.contributeFromMemeSell(pnl, WalletManager.lastKnownSolPrice)
                    }
                } catch (e: Exception) {
                    ErrorLogger.debug("Executor", "Treasury split error (live): ${e.message}")
                }
            }
            
            if (pnl > 0) {
                try {
                    val drawdownPct = SmartSizer.getCurrentDrawdownPct(isPaper = false)
                    val allocation = AutoCompoundEngine.processWin(pnl, drawdownPct)
                    
                    if (allocation.toTreasury > 0) {
                        com.lifecyclebot.v3.scoring.CashGenerationAI.addToTreasury(allocation.toTreasury, isPaper = false)
                    }
                    
                    val solPrice = WalletManager.lastKnownSolPrice
                    TreasuryManager.lockRealizedProfit(allocation.toTreasury, solPrice)
                    
                    ErrorLogger.info("Executor", "🔄 AUTO-COMPOUND [LIVE]: ${pnl.fmt(4)} SOL profit → " +
                        "Treasury: ${allocation.toTreasury.fmt(3)} | " +
                        "Compound: ${allocation.toCompound.fmt(3)} | " +
                        "Wallet: ${allocation.toWallet.fmt(3)} | " +
                        "Size mult: ${allocation.newSizeMultiplier.fmt(2)}x")
                } catch (e: Exception) {
                    ErrorLogger.debug("Executor", "AutoCompound error (live): ${e.message}")
                    val solPrice = WalletManager.lastKnownSolPrice
                    // V5.9.225: Use conservative 20% treasury fraction on AutoCompound failure (was full pnl — over-counted)
                    TreasuryManager.lockRealizedProfit(pnl * 0.20, solPrice)
                }
            }

            onLog("✅ LIVE SELL COMPLETE @ ${price.fmt()} | $reason | pnl ${pnl.fmt(4)} SOL " +
                  "(${pnlP.fmtPct()}) | sig=${sig.take(16)}…", ts.mint)
            onNotify("✅ Live Sell",
                "${ts.symbol}  $reason  PnL ${pnlP.fmtPct()}",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            
            TradeAlerts.onSell(cfg(), ts.symbol, pnl, pnlP, reason, isPaper = false)
            
            if (pnlP >= 20.0) {
                TradeAlerts.onBigWin(cfg(), ts.symbol, pnl, pnlP, isPaper = false)
            }
            
            val emoji = if (pnlP >= 0) "✅" else "📉"
            onToast("$emoji LIVE SELL: ${ts.symbol}\nPnL: ${pnlP.fmtPct()} (${pnl.fmt(4)} SOL)")

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            onLog("SELL EXCEPTION: ${e.javaClass.simpleName} | ${safe}", tradeId.mint)
            onLog("   Stack: ${e.stackTrace.take(3).joinToString(" → ") { "${it.fileName}:${it.lineNumber}" }}", tradeId.mint)
            
            // V5.9.72 CRITICAL FIX: broadcast failures do NOT mean the tokens
            // are gone or the sell happened. Previously this silently closed
            // the position after 5 exceptions and returned CONFIRMED, even
            // though NO successful signature ever existed. Tokens stayed in
            // wallet, bot forgot them. Now: always return FAILED_RETRYABLE so
            // the position stays open and the next tick gets another chance.
            val broadcastRetries = zeroBalanceRetries.merge(ts.mint + "_broadcast", 1) { old, _ -> old + 1 } ?: 1
            if (broadcastRetries >= 5 && broadcastRetries % 5 == 0) {
                // Louder notification every 5 consecutive failures — but we
                // still don't close. User decides when to give up.
                onLog("🚨 SELL BROADCAST STUCK: ${ts.symbol} — $broadcastRetries consecutive " +
                      "broadcast failures. Tokens still in wallet. Position remains OPEN.", tradeId.mint)
                onNotify("🚨 Sell Stuck",
                    "${ts.symbol}: $broadcastRetries broadcast attempts failed. Tokens remain. " +
                    "Retry continues; clear manually if you want to stop.",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                ErrorLogger.warn("Executor", "SELL BROADCAST STUCK: ${ts.symbol} after $broadcastRetries retries — " +
                    "position kept OPEN, no fake CONFIRMED.")
            }

            onNotify("Sell Failed",
                "${ts.symbol}: ${safe.take(80)} (retry $broadcastRetries, position still open)",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            onToast("SELL FAILED: ${ts.symbol} (attempt $broadcastRetries)")
            return SellResult.FAILED_RETRYABLE
        }

        val exitPrice = getActualPrice(ts)
        
        val holdTimeMins = (System.currentTimeMillis() - pos.entryTime) / 60_000.0
        
        val maxGainPctLive = if (pos.entryPrice > 0 && pos.highestPrice > 0) {
            ((pos.highestPrice - pos.entryPrice) / pos.entryPrice) * 100.0
        } else 0.0
        
        val tokenAgeMinsLive = if (ts.addedToWatchlistAt > 0) {
            (pos.entryTime - ts.addedToWatchlistAt) / 60_000.0
        } else 0.0
        
        // V5.9.363 — SYMMETRIC SCRATCH ZONE (live mirror of paper-mode change).
        val tradeClassification = when {
            pnlP >= 1.5 -> "WIN"
            pnlP <= -1.5 -> "LOSS"
            else -> "SCRATCH"
        }
        
        val isScratchTradeLive = tradeClassification == "SCRATCH"
        val shouldLearnAsLoss = tradeClassification == "LOSS"
        val shouldLearnAsWin = tradeClassification == "WIN"
        
        ErrorLogger.info("Executor", "📊 LIVE ${tradeId.symbol} CLASSIFIED: $tradeClassification | " +
            "pnl=${pnlP.toInt()}% | hold=${holdTimeMins.toInt()}min | " +
            "learn=${if(isScratchTradeLive) "NO (scratch)" else "YES"}")
        
        if (shouldLearnAsLoss) {
            val fanName = ts.meta.emafanAlignment
            val ph      = pos.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }

            tradeDb?.recordBadObservation(
                featureKey    = "phase=${ph}+ema=${fanName}",
                behaviourType = "ENTRY_SIGNAL",
                description   = "Loss on $ph + $fanName — pnl=${pnlP.toInt()}%",
                lossPct       = pnlP,
            )
            if (src != "UNKNOWN") tradeDb?.recordBadObservation(
                featureKey    = "source=${src}",
                behaviourType = "SOURCE",
                description   = "Loss from source $src",
                lossPct       = pnlP,
            )
            tradeDb?.recordBadObservation(
                featureKey    = "exit_reason=${reason}",
                behaviourType = "EXIT_PATTERN",
                description   = "Exit via $reason resulted in loss",
                lossPct       = pnlP,
            )

            brain?.let { b ->
                val shouldBlacklist = b.learnFromTrade(
                    isWin = false, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = true,
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "LIVE" },
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPctLive,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMinsLive,
                )
                if (shouldBlacklist) {
                    TokenBlacklist.block(ts.mint, "2+ losses on ${ts.symbol}")
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
            val fanName = ts.meta.emafanAlignment
            val ph      = pos.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }
            tradeDb?.recordGoodObservation("phase=${ph}+ema=${fanName}")
            tradeDb?.recordGoodObservation("source=${src}")
            
            brain?.let { b ->
                b.learnFromTrade(
                    isWin = true, 
                    phase = ph, 
                    emaFan = fanName, 
                    source = src, 
                    pnlPct = pnlP, 
                    mint = ts.mint,
                    rugcheckScore = ts.safety.rugcheckScore,
                    buyPressure = ts.meta.pressScore,
                    topHolderPct = ts.safety.topHolderPct,
                    liquidityUsd = ts.lastLiquidityUsd,
                    isLiveTrade = true,
                    approvalClass = tradeId.fdgApprovalClass.ifEmpty { "LIVE" },
                    holdTimeMinutes = holdTimeMins,
                    maxGainPct = maxGainPctLive,
                    exitReason = reason,
                    tokenAgeMinutes = tokenAgeMinsLive,
                )
            }
        } else {
            ErrorLogger.debug("Executor", "LIVE ${ts.symbol}: Scratch trade (${pnlP.toInt()}%) - skipped for learning")
        }

        val dbIsWinLive = when {
            isScratchTradeLive -> null
            pnlP > 5.0 -> true
            else -> false
        }
        
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
                devWalletPct = 0.0,
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
                entryPhase = pos.entryPhase,
            )
            AdaptiveLearningEngine.learnFromTrade(features)
            
            if (shouldLearnAsWin || shouldLearnAsLoss) {
                val tokenAgeHours2 = (System.currentTimeMillis() - ts.addedToWatchlistAt) / 3_600_000.0
                ScannerLearning.recordTrade(
                    source = ts.source.ifEmpty { "UNKNOWN" },
                    liqUsd = ts.lastLiquidityUsd,
                    ageHours = tokenAgeHours2,
                    isWin = shouldLearnAsWin
                )
            }
        } catch (e: Exception) {
            ErrorLogger.debug("AdaptiveLearning", "Feature capture error: ${e.message}")
        }

        if (!isScratchTradeLive) {
            ShadowLearningEngine.onLiveTradeExit(
                mint = tradeId.mint,
                exitPrice = exitPrice,
                exitReason = reason,
                livePnlSol = pnl,
                isWin = pnlP >= 1.0  // V5.9.185: unified 1% floor,
            )
        }
        
        val classificationLive = when {
            isScratchTradeLive -> "SCRATCH"
            shouldLearnAsWin -> "WIN"
            shouldLearnAsLoss -> "LOSS"
            else -> "UNKNOWN"
        }
        
        tradeId.closed(exitPrice, pnlP, pnl, reason)
        tradeId.classified(classificationLive, if (isScratchTradeLive) null else shouldLearnAsWin)
        
        TradeLifecycle.closed(tradeId.mint, exitPrice, pnlP, reason)
        TradeLifecycle.classified(tradeId.mint, classificationLive, if (isScratchTradeLive) null else shouldLearnAsWin)
        TradeLifecycle.clearProposalTracking(tradeId.mint)

        // V5.9.9: Feed meme trade into V4 TradeLessonRecorder → StrategyTrustAI
        try {
            val tradingMode = ts.position.tradingMode.ifBlank { "STANDARD" }
            val lessonCtx = com.lifecyclebot.v4.meta.TradeLessonRecorder.TradeLessonContext(
                strategy = tradingMode, market = "MEME", symbol = tradeId.symbol,
                entryRegime = com.lifecyclebot.v4.meta.GlobalRiskMode.RISK_ON,
                entrySession = com.lifecyclebot.v4.meta.SessionContext.OFF_HOURS,
                trustScore = 0.5, fragilityScore = 0.3,
                narrativeHeat = ts.meta.pressScore / 100.0, portfolioHeat = 0.3,
                leverageUsed = 1.0, executionConfidence = ts.entryScore / 100.0,
                leadSource = null, expectedDelaySec = null,
                expectedFillPrice = ts.position.entryPrice,
                executionRoute = "JUPITER_V6",
                captureTime = ts.position.entryTime
            )
            com.lifecyclebot.v4.meta.TradeLessonRecorder.completeLesson(
                context = lessonCtx, outcomePct = pnlP,
                mfePct = if (ts.position.highestPrice > 0 && ts.position.entryPrice > 0)
                    ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice * 100) else pnlP.coerceAtLeast(0.0),
                maePct = pnlP.coerceAtMost(0.0),
                holdSec = ((System.currentTimeMillis() - ts.position.entryTime) / 1000).toInt(),
                exitReason = reason, actualFillPrice = exitPrice
            )
        } catch (_: Exception) {}
        
        if (reason.lowercase().contains("distribution")) {
            FinalDecisionGate.recordDistributionExit(tradeId.mint)
            onLog("🚫 Distribution cooldown: ${ts.symbol} blocked for 20-60s", tradeId.mint)
        }
        
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
        
        // V5.9.307: Only meaningful losses trigger ReentryGuard (live close path)
        if (pnlP <= -1.0) {
            ReentryGuard.onTradeLoss(tradeId.mint, pnlP)
        }
        
        // V5.9.83: guard against unset entryTime
        val liveEntryTimeSafe = if (ts.position.entryTime > 1_000_000_000_000L) ts.position.entryTime else System.currentTimeMillis()
        val holdMinutesLive = ((System.currentTimeMillis() - liveEntryTimeSafe) / 60000).toInt()
        EntryIntelligence.learnFromOutcome(tradeId.mint, pnlP, holdMinutesLive)
        ExitIntelligence.learnFromExit(tradeId.mint, reason, pnlP, holdMinutesLive)
        ExitIntelligence.resetPosition(tradeId.mint)

        // V5.9.390 — meme-only learning gate for the LIVE sell path. Sub-trader
        // live closes must not pollute BehaviorLearning / Whale / Regime /
        // Momentum / Narrative / TimeOpt / LiquidityDepth / CrossTalk /
        // TokenWinMemory. Computed once; each downstream try-block checks it.
        val _lsTm = (ts.position.tradingMode ?: "").uppercase()
        val _lsIsMemeBase = _lsTm.isBlank() || _lsTm !in setOf(
            "SHITCOIN", "SHITCOIN_EXPRESS", "SHITCOINEXPRESS",
            "QUALITY", "BLUECHIP", "BLUE_CHIP",
            "MOONSHOT", "TREASURY"
        )

        // V5.9.320 FIX: EdgeLearning was NEVER called in live path (paper-only).
        // Now mirrors the paper doSell path so EdgeLearning's threshold
        // auto-tuner (buy%/volume/phase gates) learns from real money outcomes.
        if (_lsIsMemeBase) EdgeLearning.learnFromOutcome(
            mint = tradeId.mint,
            exitPrice = exitPrice,
            pnlPercent = pnlP,
            wasExecuted = true,
        )

        // V5.9.320 FIX: BehaviorLearning (engine) was NEVER called in live path.
        // Now fed the full rich context so pattern DB learns from live outcomes.
        try {
            val liveVolatilityLevel = when {
                ts.meta.volScore > 80 -> "EXTREME"
                ts.meta.volScore > 60 -> "HIGH"
                ts.meta.volScore > 40 -> "MEDIUM"
                else -> "LOW"
            }
            val liveVolumeSignal = when {
                ts.meta.volScore > 80 -> "SURGE"
                ts.meta.volScore > 60 -> "INCREASING"
                ts.meta.volScore > 40 -> "NORMAL"
                ts.meta.volScore > 20 -> "DECREASING"
                else -> "LOW"
            }
            val liveSentiment = when {
                ts.meta.emafanAlignment.contains("BULL") -> "BULL"
                ts.meta.emafanAlignment.contains("BEAR") -> "BEAR"
                else -> "NEUTRAL"
            }
            val liveHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            val liveDay  = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        if (_lsIsMemeBase) {
            BehaviorLearning.recordTrade(
                entryScore     = pos.entryScore.toInt(),
                entryPhase     = pos.entryPhase.ifEmpty { "UNKNOWN" },
                setupQuality   = when {
                    pos.entryScore >= 90 -> "A+"
                    pos.entryScore >= 80 -> "A"
                    pos.entryScore >= 70 -> "B"
                    else                 -> "C"
                },
                tradingMode    = pos.tradingMode.ifEmpty { "LIVE" },
                marketSentiment = liveSentiment,
                volatilityLevel = liveVolatilityLevel,
                volumeSignal   = liveVolumeSignal,
                liquidityUsd   = ts.lastLiquidityUsd,
                mcapUsd        = ts.lastMcap,
                holderTopPct   = ts.safety.topHolderPct.toDouble(),
                rugcheckScore  = ts.safety.rugcheckScore,
                hourOfDay      = liveHour,
                dayOfWeek      = liveDay,
                holdTimeMinutes = holdMinutesLive,
                pnlPct         = pnlP,
            )
            }  // V5.9.390 — end _lsIsMemeBase gate
        } catch (_: Exception) {}
        
        // V5.9.390 — gate the remainder of meme-specific learning on asset class.
        if (_lsIsMemeBase) {
        try {
            val wasSignalCorrect = when {
                pnlP > 5.0 -> true
                pnlP < -5.0 -> false
                else -> null
            }
            if (wasSignalCorrect != null) {
                WhaleTrackerAI.recordSignalOutcome(tradeId.mint, wasSignalCorrect, pnlP)
            }
        } catch (_: Exception) {}
        
        try {
            if (abs(pnlP) >= 5.0) {
                MarketRegimeAI.recordTradeOutcome(pnlP)
            }
        } catch (_: Exception) {}
        
        try {
            val peakPnlPctLive = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else 0.0
            MomentumPredictorAI.recordOutcome(tradeId.mint, pnlP, peakPnlPctLive)
        } catch (_: Exception) {}
        
        try {
            NarrativeDetectorAI.recordOutcome(ts.symbol, ts.name, pnlP)  // V5.9.195: was 3x (inflated stats)
        } catch (_: Exception) {}
        
        try {
            TimeOptimizationAI.recordOutcome(pnlP)  // V5.9.195: was 3x (inflated stats)
        } catch (_: Exception) {}
        
        try {
            LiquidityDepthAI.recordOutcome(ts.mint, pnlP, pnl > 0)  // V5.9.195: was 3x (inflated stats)
            LiquidityDepthAI.clearEntryLiquidity(ts.mint)
        } catch (_: Exception) {}
        
        try {
            val crossTalkSignal = AICrossTalk.analyzeCrossTalk(ts, isOpenPosition = false)
            if (crossTalkSignal.signalType != AICrossTalk.SignalType.NO_CORRELATION) {
                AICrossTalk.recordOutcome(crossTalkSignal.signalType, pnlP, pnl > 0)  // V5.9.195: was 3x
            }
        } catch (_: Exception) {}
        
        try {
            val peakPnlLive = if (ts.position.entryPrice > 0) {
                com.lifecyclebot.util.pct(ts.position.entryPrice, ts.position.highestPrice)
            } else pnlP
            
            val latestBuyPctLive = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0
            val approxEntryMcapLive = ts.position.entryLiquidityUsd * 2
            
            repeat(1) {  // V5.9.195: was repeat(3) — inflated win memory stats
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
        }  // V5.9.390 — end _lsIsMemeBase block
        
        try {
            val holdTimeMs = System.currentTimeMillis() - ts.position.entryTime
            val isWin = pnlP >= 1.0  // V5.9.185: unified 1% floor
            val modeStr = ts.position.tradingMode
            
            val extMode = try {
                UnifiedModeOrchestrator.ExtendedMode.valueOf(modeStr)
            } catch (e: Exception) {
                UnifiedModeOrchestrator.ExtendedMode.STANDARD
            }
            
            repeat(1) {  // V5.9.195: was repeat(3) — inflated mode stats
                UnifiedModeOrchestrator.recordTrade(
                    mode = extMode,
                    isWin = isWin,
                    pnlPct = pnlP,
                    holdTimeMs = holdTimeMs,
                )
            }
            
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
        // V5.9.256: Mark closed in persistent wallet memory
        try { WalletTokenMemory.recordExit(ts.mint, ts.symbol, exitPrice, pnlP, "PAPER_EXIT") } catch (_: Exception) {}
        
        try {
            PositionPersistence.savePosition(ts)
            ErrorLogger.info("Executor", "💾 LIVE position removed from persistence: ${ts.symbol}")
        } catch (e: Exception) {
            ErrorLogger.error("Executor", "💾 Position persistence removal error: ${e.message}", e)
        }
        
        try {
            val isWin = pnlP >= 1.0  // V5.9.226: 1% floor — pnl>0 includes fee-wash near-zeros
            
            val treasurySignal = if (isWin) {
                com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.TAKE_PROFIT
            } else {
                com.lifecyclebot.v3.scoring.CashGenerationAI.ExitSignal.STOP_LOSS
            }
            com.lifecyclebot.v3.scoring.CashGenerationAI.closePosition(tradeId.mint, exitPrice, treasurySignal)
            
            val shitcoinSignal = if (isWin) {
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.TAKE_PROFIT
            } else {
                com.lifecyclebot.v3.scoring.ShitCoinTraderAI.ExitSignal.STOP_LOSS
            }
            com.lifecyclebot.v3.scoring.ShitCoinTraderAI.closePosition(tradeId.mint, exitPrice, shitcoinSignal)
            
            val bluechipSignal = if (isWin) {
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.TAKE_PROFIT
            } else {
                com.lifecyclebot.v3.scoring.BlueChipTraderAI.ExitSignal.STOP_LOSS
            }
            com.lifecyclebot.v3.scoring.BlueChipTraderAI.closePosition(tradeId.mint, exitPrice, bluechipSignal)
            
            TradeAuthorizer.releasePosition(
                mint = tradeId.mint,
                reason = "SELL_$reason",
                book = TradeAuthorizer.ExecutionBook.CORE
            )
            
            ErrorLogger.debug("Executor", "🔓 LIVE SELL: Released all locks for ${ts.symbol}")
        } catch (e: Exception) {
            ErrorLogger.debug("Executor", "Error releasing locks in liveSell: ${e.message}")
        }
        
        try {
            val tradeClassification = when {
                pnlP >= 50.0 -> "RUNNER"
                pnlP >= 15.0 -> "BIG_WIN"
                pnlP >= 2.0 -> "WIN"
                pnlP >= -2.0 -> "SCRATCH"
                pnlP >= -10.0 -> "LOSS"
                else -> "BAD"
            }
            
            val setupQualityStr = when (tradeClassification) {
                "RUNNER" -> "EXCELLENT"
                "BIG_WIN" -> "EXCELLENT"
                "WIN" -> "GOOD"
                "SCRATCH" -> "NEUTRAL"
                "LOSS" -> "POOR"
                "BAD" -> "BAD"
                else -> "NEUTRAL"
            }
            
            // V5.9.83: guard against unset entryTime (raw epoch leak bug)
            val entryTimeSafe3 = if (pos.entryTime > 1_000_000_000_000L) pos.entryTime else System.currentTimeMillis()
            val holdMinutes = (System.currentTimeMillis() - entryTimeSafe3) / 60000.0
            val peakPnlPct = if (pos.entryPrice > 0 && pos.highestPrice > 0) {
                ((pos.highestPrice - pos.entryPrice) / pos.entryPrice) * 100
            } else pnlP
            
            val currentHolderCount = ts.history.lastOrNull()?.holderCount ?: 0
            val currentVolume = ts.history.lastOrNull()?.vol ?: 0.0
            val approxTokenAgeMinutes = holdMinutes + 5.0
            
            val outcomeData = com.lifecyclebot.v3.scoring.EducationSubLayerAI.TradeOutcomeData(
                mint = tradeId.mint,
                symbol = ts.symbol,
                tokenName = ts.name,
                pnlPct = pnlP,
                holdTimeMinutes = holdMinutes,
                exitReason = reason,
                entryPhase = pos.entryPhase.ifEmpty { "UNKNOWN" },
                tradingMode = pos.tradingMode.ifEmpty { "LIVE" },
                discoverySource = ts.source.ifEmpty { "UNKNOWN" },
                setupQuality = setupQualityStr,
                entryMcapUsd = pos.entryMcap.takeIf { it > 0 } ?: (pos.entryLiquidityUsd * 2),
                exitMcapUsd = ts.lastMcap,
                tokenAgeMinutes = approxTokenAgeMinutes,
                buyRatioPct = ts.history.lastOrNull()?.buyRatio?.times(100) ?: 50.0,
                volumeUsd = currentVolume,
                liquidityUsd = ts.lastLiquidityUsd,
                holderCount = currentHolderCount,
                topHolderPct = ts.topHolderPct ?: 0.0,
                holderGrowthRate = ts.holderGrowthRate,
                devWalletPct = 0.0,
                bondingCurveProgress = 0.0,
                rugcheckScore = ts.safety.rugcheckScore.toDouble().coerceAtLeast(0.0),
                emaFanState = ts.meta.emafanAlignment.ifEmpty { "UNKNOWN" },
                entryScore = ts.entryScore,
                priceFromAth = 0.0,
                maxGainPct = peakPnlPct,
                maxDrawdownPct = pos.lowestPrice.let { low ->
                    if (low > 0 && pos.entryPrice > 0) {
                        ((low - pos.entryPrice) / pos.entryPrice) * 100
                    } else 0.0
                },
                timeToPeakMins = holdMinutes * 0.5,
                // V5.9.320: derive traderSource from actual trading mode — was hardcoded "Meme"
                traderSource = when {
                    pos.tradingMode.startsWith("MOONSHOT") -> "MOONSHOT"
                    pos.tradingMode == "SHITCOIN"   -> "SHITCOIN"
                    pos.tradingMode == "BLUE_CHIP"  -> "BLUECHIP"
                    pos.tradingMode == "QUALITY"    -> "QUALITY"
                    pos.tradingMode == "TREASURY"   -> "TREASURY"
                    pos.tradingMode == "DIP_HUNTER" -> "DIP_HUNTER"
                    pos.tradingMode == "MANIPULATED" -> "MANIPULATED"
                    else -> "MEME"
                },
                lossReason = if (pnlP < -2.0) reason else "",
            )
            
            com.lifecyclebot.v3.scoring.EducationSubLayerAI.recordTradeOutcomeAcrossAllLayers(outcomeData)
            ErrorLogger.info("Executor", "🎓 HARVARD BRAIN (LIVE): Recorded outcome for ${ts.symbol} | PnL=${pnlP.toInt()}%")
        } catch (e: Exception) {
            ErrorLogger.warn("Executor", "🎓 Harvard Brain recording failed: ${e.message}")
        }
        
        // V5.9.284: Clear any pending sell queue entry on confirmed exit — prevents
        // duplicate SELL_START on the next BotService loop when the sell succeeded
        // but a stale entry was still sitting in PendingSellQueue (enqueued by an
        // earlier FAILED_RETRYABLE attempt that preceded the successful sell).
        try { PendingSellQueue.remove(ts.mint) } catch (_: Exception) {}

        // V5.9.291 FIX: CRITICAL — liveSell never cleared ts.position.
        // Without this, ts.position.isOpen stayed true after a confirmed swap,
        // so BotService re-entered the exit block on the NEXT tick and called
        // requestSell again on a position that was already sold on-chain.
        // paperSell always had this clear; liveSell was missing it entirely.
        synchronized(ts) {
            ts.position      = Position()
            ts.lastExitTs    = System.currentTimeMillis()
            ts.lastExitPrice = exitPrice
            ts.lastExitPnlPct = pnlP
            ts.lastExitWasWin = pnl > 0
        }
        // V5.9.256: Mark closed in persistent wallet memory
        try { WalletTokenMemory.recordExit(ts.mint, ts.symbol, exitPrice, pnlP, reason) } catch (_: Exception) {}
        try { PositionPersistence.savePosition(ts) } catch (_: Exception) {}

        onLog("✅ LIVE_EXIT_CONFIRMED: ${ts.symbol} | reason=$reason | PnL=${pnlP.toInt()}%", tradeId.mint)
        ErrorLogger.info("Executor", "✅ LIVE_EXIT_CONFIRMED: ${ts.symbol} | reason=$reason | PnL=${pnlP.toInt()}%")

        // V5.9.248: stamp cooldown so universal gate blocks immediate re-entry
        // NOTE: also stamped above in synchronized block via lastExitTs, this is belt-and-suspenders.
        com.lifecyclebot.engine.BotService.recentlyClosedMs[ts.mint] = System.currentTimeMillis()
        
        return SellResult.CONFIRMED
    }

    // ── Close all positions (for bot shutdown) ────────────────────────

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
                    ((getActualPrice(ts) - pos.entryPrice) / pos.entryPrice * 100)
                } else 0.0
                
                onLog("🔴 EMERGENCY CLOSE: ${ts.symbol} @ ${gainPct.toInt()}% gain | reason=bot_shutdown", ts.mint)
                
                if (paperMode || wallet == null) {
                    paperSell(ts, "bot_shutdown")
                } else {
                    liveSell(ts, "bot_shutdown", wallet, walletSol)
                }
                
                closedCount++
                
            } catch (e: Exception) {
                onLog("Failed to close ${ts.symbol}: ${e.message}", ts.mint)
                // V5.7.8: Force close on ANY failure during shutdown — don't leave ghosts
                try {
                    val tradeId = com.lifecyclebot.engine.TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
                    tradeId.closed(getActualPrice(ts), 
                        if (ts.position.entryPrice > 0) ((getActualPrice(ts) - ts.position.entryPrice) / ts.position.entryPrice * 100) else -100.0,
                        -(ts.position.costSol), "SHUTDOWN_FORCE_CLOSE")
                    onLog("Force-closed on shutdown: ${ts.symbol}", ts.mint)
                    closedCount++
                } catch (_: Exception) {
                    if (paperMode) {
                        try {
                            val pos = ts.position
                            val value = pos.qtyToken * getActualPrice(ts)
                            onPaperBalanceChange?.invoke(value)
                            ts.position = com.lifecyclebot.data.Position()
                            onLog("Force-closed paper position: ${ts.symbol}", ts.mint)
                            closedCount++
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        
        onLog("✅ Closed $closedCount/${openPositions.size} positions on shutdown", "shutdown")
        onNotify("✅ Positions Closed", 
                 "Closed $closedCount position(s) on bot shutdown",
                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        
        return closedCount
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.9.261 — LIVE WALLET SWEEP (the 1-month chronic bug fix)
    //
    // Layer traders (ShitCoin/Moonshot/BlueChip/Manip/Express/Quality) only
    // do in-memory PnL accounting in their `closePosition()` paths. They
    // NEVER broadcast a Jupiter sell. Result: when the user hit STOP BOT,
    // every meme/altcoin the bot bought stayed on-chain in the wallet
    // forever and had to be cleared manually via Phantom.
    //
    // This sweep enumerates EVERY non-stablecoin SPL holding in the
    // wallet, gets a fresh Jupiter quote, and broadcasts a real
    // swap-to-SOL with the same slippage-escalation logic liveSell uses.
    // Stablecoins (USDC/USDT) and SOL are preserved.
    //
    // Called by BotService.stopBot() in live mode. Idempotent — running
    // it twice has no effect once the wallet is empty of trade tokens.
    // ═══════════════════════════════════════════════════════════════════════════
    fun liveSweepWalletTokens(
        wallet: SolanaWallet,
        walletSol: Double,
        additionalPreservedMints: Set<String> = emptySet(),
    ): Int {
        // Stablecoins / SOL we never auto-sell on shutdown
        val PRESERVED_MINTS = setOf(
            JupiterApi.SOL_MINT,                                              // wSOL
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v",                  // USDC
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB",                  // USDT
            "So11111111111111111111111111111111111111112",                    // wSOL alt
        ) + additionalPreservedMints  // V5.9.318: preserve active V3 positions during periodic reconcile
        var soldCount = 0
        val c = cfg()

        val onChain = try {
            // V5.9.318: Retry RPC up to 3 times with backoff. The 1-month
            // chronic 'STOP BOT not clearing live tokens' bug had this as a
            // root cause: a single failing RPC call would silently abort the
            // entire sweep, leaving every leaked token stranded on-chain.
            var lastErr: Exception? = null
            var result: Map<String, Pair<Double, Int>>? = null
            for (attempt in 1..3) {
                try {
                    result = wallet.getTokenAccountsWithDecimals()
                    break
                } catch (e: Exception) {
                    lastErr = e
                    onLog("⚠️ SHUTDOWN SWEEP: RPC attempt $attempt/3 failed — ${e.message?.take(60)}", "shutdown")
                    if (attempt < 3) try { Thread.sleep((attempt * 1500L).coerceAtLeast(500L)) } catch (_: Exception) {}
                }
            }
            result ?: run {
                onLog("⚠️ SHUTDOWN SWEEP: all 3 RPC attempts failed — ${lastErr?.message?.take(80)}", "shutdown")
                return 0
            }
        } catch (e: Exception) {
            onLog("⚠️ SHUTDOWN SWEEP: failed to enumerate wallet — ${e.message?.take(80)}", "shutdown")
            return 0
        }

        if (onChain.isEmpty()) {
            onLog("✅ SHUTDOWN SWEEP: wallet empty — nothing to liquidate", "shutdown")
            return 0
        }

        val sellable = onChain
            .filter { (mint, data) -> mint !in PRESERVED_MINTS && data.first > 0.0 }

        if (sellable.isEmpty()) {
            onLog("✅ SHUTDOWN SWEEP: 0 non-stablecoin holdings — wallet is clean", "shutdown")
            LiveTradeLogStore.log(
                "sweep:${System.currentTimeMillis()}", "wallet", "sweep", "SWEEP",
                LiveTradeLogStore.Phase.SWEEP_DONE,
                "Wallet already clean (0 sellable holdings)",
                traderTag = "SWEEP",
            )
            return 0
        }

        val sweepKey = "sweep:${System.currentTimeMillis()}"
        onLog("🛑 SHUTDOWN SWEEP: liquidating ${sellable.size} on-chain holding(s) to SOL…", "shutdown")
        LiveTradeLogStore.log(
            sweepKey, "wallet", "sweep", "SWEEP",
            LiveTradeLogStore.Phase.SWEEP_START,
            "Sweep started — ${sellable.size} non-stablecoin holdings",
            traderTag = "SWEEP",
        )
        onNotify(
            "🛑 Sweeping wallet",
            "Selling ${sellable.size} token(s) back to SOL before shutdown",
            com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO,
        )

        for ((mint, data) in sellable) {
            val balanceUi  = data.first
            val decimals   = data.second
            val symbol     = try {
                TradeIdentityManager.getOrCreate(mint, mint.take(6), "shutdown_sweep").symbol
            } catch (_: Exception) { mint.take(6) }
            val tokenSweepKey = "$sweepKey:$mint"
            LiveTradeLogStore.log(
                tokenSweepKey, mint, symbol, "SWEEP",
                LiveTradeLogStore.Phase.SWEEP_TOKEN_TRY,
                "Trying $symbol | qty=${balanceUi.fmt(4)} | dec=$decimals",
                tokenAmount = balanceUi, traderTag = "SWEEP",
            )

            try {
                val multiplier = 10.0.pow(decimals.toDouble())
                val rawUnits = (balanceUi * multiplier).toLong().coerceAtLeast(1L)

                // Skip dust that Jupiter cannot route (< ~0.001 SOL of value).
                // We don't have a live price for arbitrary mints here, so
                // use a conservative raw-units floor.
                if (rawUnits < 1L) {
                    onLog("⏭️ SWEEP SKIP $symbol: dust qty", mint); continue
                }

                // Slippage escalation: 200, 350, 500 bps (5% hard cap).
                val slippageLevels = listOf(200, 350, 500)
                var quote: com.lifecyclebot.network.SwapQuote? = null
                for (slip in slippageLevels) {
                    try {
                        quote = jupiter.getQuote(mint, JupiterApi.SOL_MINT, rawUnits, slip)
                        if (quote != null) break
                    } catch (e: Exception) {
                        onLog("⚠️ SWEEP $symbol: quote ${slip}bps failed — ${e.message?.take(50)}", mint)
                        Thread.sleep(250)
                    }
                }
                if (quote == null) {
                    onLog("🚨 SWEEP $symbol: Jupiter has no route. Token left in wallet.", mint)
                    continue
                }

                val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
                security.enforceSignDelay()
                val useJito = c.jitoEnabled && !quote.isUltra
                val ultraReqId = if (quote.isUltra) txResult.requestId else null
                val sig = wallet.signSendAndConfirm(
                    txResult.txBase64, useJito, c.jitoTipLamports,
                    ultraReqId, c.jupiterApiKey, txResult.isRfqRoute,
                )
                onLog("✅ SWEEP SOLD $symbol: ${balanceUi.fmt(4)} → SOL | sig=${sig.take(16)}…", mint)
                LiveTradeLogStore.log(
                    tokenSweepKey, mint, symbol, "SWEEP",
                    LiveTradeLogStore.Phase.SWEEP_TOKEN_DONE,
                    "✅ Sold ${balanceUi.fmt(4)} → SOL",
                    sig = sig, tokenAmount = balanceUi, traderTag = "SWEEP",
                )
                soldCount++
            } catch (e: Exception) {
                onLog("🚨 SWEEP $symbol FAILED: ${e.message?.take(80)} — token remains in wallet", mint)
                LiveTradeLogStore.log(
                    tokenSweepKey, mint, symbol, "SWEEP",
                    LiveTradeLogStore.Phase.SWEEP_TOKEN_FAILED,
                    "🚨 Sweep failed: ${e.message?.take(120)} — token remains in wallet",
                    traderTag = "SWEEP",
                )
                ErrorLogger.error("Executor", "Shutdown sweep failed for $mint: ${e.message}", e)
            }
        }

        onLog("✅ SHUTDOWN SWEEP COMPLETE: sold $soldCount/${sellable.size} holdings to SOL", "shutdown")
        LiveTradeLogStore.log(
            sweepKey, "wallet", "sweep", "SWEEP",
            LiveTradeLogStore.Phase.SWEEP_DONE,
            "Sweep complete — $soldCount/${sellable.size} sold to SOL",
            traderTag = "SWEEP",
        )
        onNotify(
            "✅ Wallet swept",
            "Sold $soldCount of ${sellable.size} holdings back to SOL",
            com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO,
        )
        return soldCount
    }

    // ── Jupiter helpers ───────────────────────────────────────────────

    private fun getQuoteWithSlippageGuard(
        inMint: String, outMint: String, amount: Long, slippageBps: Int,
        inputSol: Double = 0.0,
        isBuy: Boolean = true,
    ): com.lifecyclebot.network.SwapQuote {
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

    private fun tokenScale(rawAmount: Long): Double {
        return when {
            rawAmount >= 1_000_000_000_000L -> 1_000_000_000_000.0
            rawAmount >= 1_000_000_000L -> 1_000_000_000.0
            else -> 1_000_000.0
        }
    }

    // ── Treasury withdrawal ───────────────────────────────────────────

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

    fun sellOrphanedToken(mint: String, qty: Double, wallet: SolanaWallet): Boolean {
        val c = cfg()
        
        if (c.paperMode) {
            onLog("🧹 Orphan sell skipped (paper mode): $mint", mint)
            return false
        }
        
        return try {
            onLog("🧹 Attempting orphan sell: $mint ($qty tokens)", mint)
            
            val sellUnits = resolveSellUnitsForMint(mint, qty, wallet = wallet)
            val sellSlippage = (c.slippageBps * 3).coerceAtMost(500)   // V5.9.103: hard cap 5%
            
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
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V5.7.3: NETWORK SIGNAL AUTO-BUY
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * Queue a network signal auto-buy
     * Called by NetworkSignalAutoBuyer when a signal triggers
     * 
     * TODO: Full implementation pending - currently returns false (disabled)
     */
    fun queueNetworkSignalBuy(
        mint: String,
        symbol: String,
        sizePct: Double,
        reason: String,
        isPaper: Boolean,
    ): Boolean {
        val c = cfg()

        // Safety: force paper mode if user hasn't opted into live but signal requested live
        if (!isPaper && c.paperMode) {
            onLog("📡 Network signal buy rejected: paper mode only", mint)
            return false
        }

        // V5.9.451 — REAL IMPLEMENTATION (was a stub returning false since V5.7.3).
        // Looks up the current TokenState from BotService's watchlist so we
        // piggyback on every existing paperBuy/live-buy safety rail
        // (cap, duplicate-open guard, journal, sizing, anti-wash, etc.).
        val ts: TokenState? = try {
            val s = com.lifecyclebot.engine.BotService.status
            synchronized(s.tokens) { s.tokens[mint] }
        } catch (_: Exception) { null }

        if (ts == null) {
            onLog("📡 Network signal buy rejected: $symbol not in watchlist yet", mint)
            return false
        }
        if (ts.position.isOpen) {
            onLog("📡 Network signal buy skipped: $symbol already open", mint)
            return false
        }
        if (ts.lastPrice <= 0.0) {
            onLog("📡 Network signal buy skipped: $symbol has no price", mint)
            return false
        }

        // Resolve wallet balance for sizing (paper uses unified wallet)
        val walletSol = try {
            com.lifecyclebot.engine.BotService.status.getEffectiveBalance(isPaper)
        } catch (_: Exception) { 0.0 }
        val sizeSol = (walletSol * (sizePct / 100.0)).coerceAtLeast(0.001)
        if (sizeSol > walletSol) {
            onLog("📡 Network signal buy skipped: insufficient balance ${walletSol.fmt(3)} < ${sizeSol.fmt(3)}", mint)
            return false
        }

        return try {
            if (isPaper) {
                paperBuy(
                    ts = ts, sol = sizeSol, score = 60.0,
                    layerTag = "NETWORK_SIGNAL",
                    layerTagEmoji = "📡",
                )
            } else {
                // Live fallthrough via standard doBuy path (handles wallet + safety gates).
                doBuy(
                    ts = ts, sol = sizeSol, score = 60.0,
                    wallet = null, walletSol = walletSol,
                )
            }
            onLog("📡 NETWORK SIGNAL AUTO-BUY EXECUTED: $symbol size=${sizeSol.fmt(3)}◎ | $reason", mint)
            onNotify(
                "📡 Network Signal",
                "$symbol auto-buy ${sizeSol.fmt(3)}◎",
                NotificationHistory.NotifEntry.NotifType.INFO,
            )
            true
        } catch (e: Exception) {
            ErrorLogger.error("Executor", "Network signal buy failed for $symbol: ${e.message}", e)
            false
        }
    }

    private fun Double.fmt(d: Int = 6) = "%.${d}f".format(this)
}
private fun Double.fmtPct() = "%+.1f%%".format(this)

