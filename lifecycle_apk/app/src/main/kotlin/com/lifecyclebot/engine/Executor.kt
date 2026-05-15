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
/**
 * V5.9.722 — WR-Recovery Partial Sell Manager
 *
 * When current session WR is meaningfully below the phase target, the first
 * partial-sell milestone is lowered so the bot locks a win sooner.  The rest
 * of the position still rides with normal trail/SL logic — this is purely
 * an earlier first-lock, not a choke on runners.
 *
 * Recovery mode activates when:
 *   currentWR < phaseTargetWR * WR_RECOVERY_THRESHOLD  (default 0.85)
 *   AND partialLevel == 0 (first sell only — don't interfere with later rungs)
 *   AND gainPct >= MIN_PARTIAL_GAIN_PCT (never micro-lock at noise levels)
 *   AND position is NOT already profit-locked (already safe — let it ride)
 *
 * The override trigger is clamped to:
 *   max(MIN_PARTIAL_GAIN_PCT, normalTrigger * RECOVERY_TRIGGER_SCALE)
 * e.g. normal trigger = 15% → recovery trigger = max(5%, 15%*0.60) = 9%
 */
object WrRecoveryPartial {
    private const val WR_RECOVERY_THRESHOLD  = 0.85   // fire below 85% of phase target
    private const val MIN_PARTIAL_GAIN_PCT   = 5.0    // never lock below 5% gain
    // V5.9.755 — recovery is ABSOLUTE not relative. When V5.9.722 shipped,
    // partialSellTriggerPct defaulted to 15% and `normal * 0.60 = 9%` made sense.
    // Today the tuner can drive partialSellTriggerPct anywhere from 50%–500%,
    // so a relative scale gives recovery triggers of 30%–300% — far too high
    // to ever fire on the kind of stop-loss-heavy ladder a recovering bot sees.
    // Operator screenshot 2026-05-15 02:58: WR=29% with 0 partials firing because
    // tokens died at -12% / -21% / -37% long before reaching the +120% trigger.
    // V5.9.755 uses a fixed 9% first-partial trigger when recovery is active —
    // matches the spec example in WR-Recovery rule (Memory #31) and ensures
    // the lock-in actually fires on the bot's typical trade trajectory.
    private const val ABSOLUTE_RECOVERY_TRIGGER = 9.0  // hard recovery trigger %

    /**
     * Returns the effective first-partial trigger pct.
     * Returns [normalTrigger] unchanged when recovery mode is not active.
     */
    fun effectiveTrigger(normalTrigger: Double, gainPct: Double, partialLevel: Int, profitLockTriggered: Boolean): Double {
        if (partialLevel != 0) return normalTrigger          // only override first rung
        if (profitLockTriggered) return normalTrigger        // already locked — let it ride

        val wins   = CanonicalLearningCounters.settledWins.get().toDouble()
        val losses = CanonicalLearningCounters.settledLosses.get().toDouble()
        val total  = wins + losses
        if (total < 50.0) return normalTrigger              // too few trades for reliable WR signal

        val currentWR = if (total > 0) (wins / total) * 100.0 else 0.0
        val targetWR  = try {
            val trades = (wins + losses).toInt()
            com.lifecyclebot.engine.FreeRangeMode.phaseTargetWr(trades)
        } catch (_: Exception) { 30.0 }

        if (targetWR <= 0.0) return normalTrigger           // phase has no WR target yet
        if (currentWR >= targetWR * WR_RECOVERY_THRESHOLD) return normalTrigger  // on-target → no override

        // Below target → use the absolute recovery trigger.
        // Clamp to at most the normal trigger so we never RAISE it.
        val recoveryTrigger = ABSOLUTE_RECOVERY_TRIGGER
            .coerceAtLeast(MIN_PARTIAL_GAIN_PCT)
            .coerceAtMost(normalTrigger)
        // Only log when we're actually going to act (token has reached the new trigger)
        if (gainPct >= recoveryTrigger) {
            ErrorLogger.info("WrRecovery", "📉 WR RECOVERY PARTIAL FIRING: WR=${"%.1f".format(currentWR)}% < target=${targetWR.toInt()}%×0.85 → ${normalTrigger.toInt()}%→${recoveryTrigger.toInt()}% (gain=${gainPct.toInt()}%)")
        }
        return recoveryTrigger
    }

    /** Human-readable status for logs */
    fun statusTag(): String {
        val wins   = CanonicalLearningCounters.settledWins.get().toDouble()
        val losses = CanonicalLearningCounters.settledLosses.get().toDouble()
        val total  = wins + losses
        if (total < 50) return "WR_RECOVERY:insufficient_data"
        val wr     = (wins / total) * 100.0
        val target = try { com.lifecyclebot.engine.FreeRangeMode.phaseTargetWr(total.toInt()) } catch (_: Exception) { 30.0 }
        val active = wr < target * WR_RECOVERY_THRESHOLD
        return if (active) "WR_RECOVERY:ACTIVE(wr=${wr.toInt()}%,target=${target.toInt()}%)" else "WR_RECOVERY:off"
    }
}

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
        // V5.9.719: Paper sell lock — prevents double-exit race condition where
        // CASHGEN_STOP_LOSS and STALE_LIVE_PRICE_RUG_ESCAPE fire simultaneously
        // for the same paper position. Both see isOpen=true before either closes it.
        private val paperSellLocks = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicBoolean>()
        fun acquirePaperSellLock(mint: String): Boolean =
            paperSellLocks.getOrPut(mint) { java.util.concurrent.atomic.AtomicBoolean(false) }
                .compareAndSet(false, true)
        fun releasePaperSellLock(mint: String) {
            paperSellLocks.remove(mint)
        }
        /** V5.9.720: force-clear ALL paper sell locks — called on bot stop so shutdown
         *  close can never be blocked by a stale lock from a crashed sell path. */
        fun clearAllPaperSellLocks() {
            paperSellLocks.clear()
        }
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
        // V5.9.744 — POOL/SOURCE-AWARE PRICE RESOLVER.
        // ═══════════════════════════════════════════════════════════════
        // The pre-744 implementation returned `ts.lastPrice` as-is, with
        // a >100x rejection for what looked like feed glitches. But the
        // "glitch" was often NOT a glitch — it was a REAL price on a
        // DIFFERENT BASIS. A Pump.Fun BC token priced via mcap/1B at
        // entry, then graduated to Raydium and DexScreener WS quoted the
        // REAL pool price (different per-token basis), would look like
        // a 38x jump and either get rejected (V5.9.734 reject path) or
        // logged as phantom +3960% PnL on the journal (pre-734 substitute
        // path). Same mint, same wallet state — two prices on two
        // incompatible synthesizer bases.
        //
        // The operator-mandated fix: at buy-time we stamped the entry
        // pricing source / pool / DEX into Position. Here we:
        //   1. Read the current ts.lastPrice + ts.lastPriceSource.
        //   2. If we have an open position and the source has CHANGED
        //      from what was stamped at entry, REBASE entryPrice once
        //      using the candle history pivot — this makes the entry
        //      and current quote comparable on the new source's basis.
        //   3. After that single rebase, return ts.lastPrice unmodified.
        //   4. No rejections. No clamping. No simulation. Real data only.
        //
        // The rebase only ever fires ONCE per position (gated by
        // pos.priceBasisRescaled). After it fires, all future ticks on
        // the new source feed normally into PnL.

        val livePrice = ts.lastPrice.takeIf { it > 0 && it.isFinite() }
        val pos = ts.position

        // Detect source-basis switch on an open position.
        // Three conditions must ALL hold:
        //   (a) we have a live price,
        //   (b) the position is open with a real entryPrice,
        //   (c) the source at entry differs from current source, AND
        //   (d) we haven't already rebased once.
        // V5.9.747 — LIVE POSITIONS NEVER REBASE.
        // Operator report: 'live buys have gone weird. very messy.'
        // Root cause: V5.9.744 rebase fires on ANY position when the price
        // source changes from entry source. But LIVE positions have a real
        // on-chain entry price from the Jupiter swap (SOL paid / tokens
        // received) — that's ground truth. Rebasing that based on off-chain
        // mcap pivots produces a fictional entry price, which then breaks
        // displayed PnL, SL/TP triggers, partial-sell levels, and exit gates
        // for the entire life of the live position. The rebase was designed
        // for PAPER positions where the entry was a synthesized quote (and
        // therefore vulnerable to basis switches at graduation). Gate the
        // rebase block on isPaperPosition so live entries stay sacred.
        if (livePrice != null && pos.isOpen && pos.entryPrice > 0 &&
            pos.isPaperPosition &&  // V5.9.747 — live positions never rebase
            !pos.priceBasisRescaled &&
            pos.entryPriceSource.isNotBlank() &&
            ts.lastPriceSource.isNotBlank() &&
            pos.entryPriceSource != ts.lastPriceSource) {

            // The ratio between the entry's basis price and the new source's
            // price ON THE SAME MOMENT (we approximate with the first cross-
            // source tick we see) is the multiplicative rescale factor.
            // Concretely: if entry was on PUMP_FUN_BC at $0.000003 (mcap/1B)
            // and DexScreener WS just reported $0.00012 for the same token at
            // graduation moment, the BC-basis equivalent of the new price
            // is what we want entryPrice scaled INTO so PnL = livePrice/scaled-
            // entryPrice reflects real percent change post-graduation.
            //
            // Without a synchronized cross-source quote (which we don't have),
            // the most honest default is to NOT rescale on the very first
            // post-switch tick and instead use ts.history's most recent
            // candle on the NEW source as the rescale pivot. Pump.Fun
            // graduation always produces a fresh Raydium pool price; that
            // first new-source candle is the pivot.
            val newSourceCandle = ts.history.lastOrNull { c ->
                c.priceUsd > 0 && c.priceUsd.isFinite()
            }
            if (newSourceCandle != null && newSourceCandle.priceUsd > 0) {
                // Rescale factor: how much did the basis itself change?
                // Best proxy available: the ratio of MCAP at entry to MCAP now,
                // which is invariant across supply-assumption changes (mcap is
                // dollar-denominated, not per-token).
                val entryMcap = pos.entryMcap.takeIf { it > 0 }
                val currentMcap = ts.lastMcap.takeIf { it > 0 } ?: newSourceCandle.marketCap.takeIf { it > 0 }
                if (entryMcap != null && currentMcap != null && currentMcap > 0) {
                    // Equivalent entry price on the NEW basis is whatever
                    // entryPrice would have been if we had measured it via
                    // the new source's per-token price at the same mcap level.
                    // = livePrice * (entryMcap / currentMcap)
                    val rebasedEntry = livePrice * (entryMcap / currentMcap)
                    if (rebasedEntry > 0 && rebasedEntry.isFinite()) {
                        val factor = rebasedEntry / pos.entryPrice
                        if (factor.isFinite() && factor > 0 && factor < 1e9) {
                            ErrorLogger.warn("Executor",
                                "🔄 PRICE_BASIS_REBASE ${ts.symbol}: source ${pos.entryPriceSource}→${ts.lastPriceSource} " +
                                "| entry ${pos.entryPrice}→${rebasedEntry} (×${"%.4g".format(factor)}) " +
                                "| mcap ${entryMcap}→${currentMcap} | live ${livePrice}")
                            ts.position = pos.copy(
                                entryPrice = rebasedEntry,
                                highestPrice = pos.highestPrice * factor,
                                lowestPrice = if (pos.lowestPrice > 0) pos.lowestPrice * factor else 0.0,
                                lastTopUpPrice = if (pos.lastTopUpPrice > 0) pos.lastTopUpPrice * factor else 0.0,
                                priceBasisRescaled = true,
                                priceBasisRescaleFactor = factor,
                            )
                        }
                    }
                } else {
                    // No mcap data → mark rescaled anyway so we don't loop
                    // every tick logging the same warning. The position will
                    // measure PnL on the new source's raw scale; any drift
                    // is bounded by the position's own size-cap from sizer.
                    ErrorLogger.warn("Executor",
                        "🔄 PRICE_BASIS_REBASE ${ts.symbol}: source ${pos.entryPriceSource}→${ts.lastPriceSource} " +
                        "| no mcap pivot available, accepting new-source price as-is (PnL may show one-time step)")
                    ts.position = pos.copy(priceBasisRescaled = true)
                }
            }
        }

        // After (possibly) rebasing, return the live price directly.
        if (livePrice != null) return livePrice

        // Fallback 1: latest candle price.
        val candlePrice = ts.history.lastOrNull()?.priceUsd?.takeIf { it > 0 && it.isFinite() }
        if (candlePrice != null) return candlePrice

        // Fallback 2: entry price (so callers see SOMETHING non-zero rather
        // than treating the eval as failed). Exit gates handle stale price
        // via their own time-since-update checks.
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

    /**
     * V5.9.491 — ON-CHAIN REHYDRATE (last-resort).
     *
     * Operator: 'wrong the tokens are in my wallets' — the rapid-stop
     * monitor saw isOpen=true at filter time, but by the time
     * requestSell ran, ts.position was empty AND none of the sub-trader
     * stores (V5.9.475) had the mint. Result: ABORT — pos.isOpen=false
     * (qty=0.0) … with the actual tokens still sitting in the wallet.
     *
     * The original V5.9.475 rehydration only scanned 5 known sub-trader
     * stores. Anything bought via a different path (Markets lane, manual
     * deposit, an old build, an external wallet drop) is invisible to it.
     *
     * This helper is the FINAL safety net: if all sub-trader stores miss,
     * we read the live wallet's `getTokenAccountsWithDecimals()` for the
     * mint. If a non-zero balance exists, we stamp ts.position with that
     * qty so the sell pipeline can dump it. Entry price defaults to the
     * current observed price (so PnL math books at break-even — not
     * accurate, but the goal is GET THE BAG OUT, not journal precision).
     *
     * Returns true if a wallet-rehydrate occurred.
     */
    private fun rehydratePositionFromWallet(ts: TokenState, wallet: SolanaWallet?): Boolean {
        if (ts.position.qtyToken > 0.0) return false
        if (wallet == null) return false
        val mint = ts.mint
        val accounts = try {
            wallet.getTokenAccountsWithDecimals()
        } catch (e: Exception) {
            ErrorLogger.warn("Executor", "rehydrateFromWallet: getTokenAccounts failed: ${e.message}")
            return false
        }
        val entry = accounts[mint] ?: return false
        val (qty, _) = entry
        if (qty <= 0.0) return false
        // Best-effort entry price: current price if we have it, else 0
        // (which leaves entryPrice unchanged — caller can still compute
        // qty in lamports for the SELL since qtyToken is what matters).
        val entryPrice = ts.lastPrice.takeIf { it > 0.0 }
            ?: ts.history.lastOrNull()?.priceUsd
            ?: ts.position.entryPrice.takeIf { it > 0.0 }
            ?: 0.0
        val entrySol = if (entryPrice > 0.0) qty * entryPrice else 0.0
        synchronized(ts) {
            ts.position = ts.position.copy(
                qtyToken       = qty,
                costSol        = entrySol.takeIf { it > 0.0 } ?: ts.position.costSol,
                entryPrice     = entryPrice.takeIf { it > 0.0 } ?: ts.position.entryPrice,
                entryTime      = if (ts.position.entryTime > 0L) ts.position.entryTime else System.currentTimeMillis(),
                isPaperPosition= false,  // wallet has on-chain tokens → live by definition
                pendingVerify  = false,
            )
        }
        ErrorLogger.warn("Executor",
            "🩹 ON-CHAIN REHYDRATE [Wallet] ${ts.symbol} (${mint.take(8)}…): qty=$qty " +
            "(entryPrice=${entryPrice.takeIf { it > 0 } ?: "unknown"}) — " +
            "no sub-trader had this position; reading from wallet directly")
        return true
    }

    /**
     * V5.9.475 — POSITION-STORE REHYDRATION (P1 sell-kill fix).
     *
     * Operator-reported bug: 'nothing sell wise in the meme trader fires in
     * the host wallet. the transaction has never made it to that point.'
     *
     * Root cause: CashGenerationAI / ShitCoinTraderAI / QualityTraderAI /
     * BlueChipTraderAI / MoonshotTraderAI all keep positions in their OWN
     * private maps and do NOT write back to ts.position when they take
     * ownership. So when an auto-exit fires (Treasury TP, profit-lock,
     * normal exit, manual sell), the sell path checks
     * ts.position.isOpen → qtyToken=0 → returns ALREADY_CLOSED. The actual
     * Jupiter swap never gets called. Tokens stay in the host wallet
     * forever and the bot reports the position 'closed' with no PnL.
     *
     * This helper scans every sub-trader store. If any has the mint AND
     * ts.position is empty, we copy the cost basis + entry price + paper
     * flag back onto ts.position and synthesise qtyToken = entrySol /
     * entryPrice. After this, the isOpen guard passes and the sell flows
     * end-to-end through the existing live-sell pipeline (V5.9.467 RPC
     * rescue → V5.9.468 binding-order quote → V5.9.470/472 slippage
     * escalation → V5.9.474 forensics) just like a non-rehydrated sell.
     *
     * Returns true if a rehydration occurred. Safe to call repeatedly —
     * skips work if ts.position already has positive qty.
     */
    private fun rehydratePositionFromSubTraders(ts: TokenState): Boolean {
        // Already valid — nothing to do.
        if (ts.position.qtyToken > 0.0) return false

        val mint = ts.mint
        // Local helper: stamp the rehydrated values onto ts.position.
        fun applyRehydrate(entrySol: Double, entryPrice: Double, entryTime: Long, isPaper: Boolean, sourceTag: String): Boolean {
            if (entrySol <= 0.0 || entryPrice <= 0.0) return false
            val qty = entrySol / entryPrice
            if (qty <= 0.0 || !qty.isFinite()) return false
            synchronized(ts) {
                ts.position = ts.position.copy(
                    qtyToken = qty,
                    costSol = entrySol,
                    entryPrice = entryPrice,
                    entryTime = entryTime,
                    isPaperPosition = isPaper,
                    pendingVerify = false,
                )
            }
            ErrorLogger.info("Executor",
                "🩹 REHYDRATE [${sourceTag}] ${ts.symbol}: qty=$qty entrySol=$entrySol entryPrice=$entryPrice " +
                "(isPaper=$isPaper) — sub-trader had position but ts.position was empty")
            return true
        }

        // Treasury (CashGenerationAI) — most common case for the operator's bug.
        try {
            val tp = com.lifecyclebot.v3.scoring.CashGenerationAI.getActivePosition(mint)
            if (tp != null && applyRehydrate(tp.entrySol, tp.entryPrice, tp.entryTime, tp.isPaper, "Treasury")) {
                return true
            }
        } catch (_: Exception) {}

        // ShitCoin
        try {
            val sp = com.lifecyclebot.v3.scoring.ShitCoinTraderAI.getActivePositions().find { it.mint == mint }
            if (sp != null && applyRehydrate(sp.entrySol, sp.entryPrice, sp.entryTime, sp.isPaper, "ShitCoin")) {
                return true
            }
        } catch (_: Exception) {}

        // Quality (no isPaper field — infer from cfg().paperMode)
        try {
            val qp = com.lifecyclebot.v3.scoring.QualityTraderAI.getActivePositions().find { it.mint == mint }
            if (qp != null && applyRehydrate(qp.entrySol, qp.entryPrice, qp.entryTime, cfg().paperMode, "Quality")) {
                return true
            }
        } catch (_: Exception) {}

        // BlueChip
        try {
            val bp = com.lifecyclebot.v3.scoring.BlueChipTraderAI.getActivePositions().find { it.mint == mint }
            if (bp != null && applyRehydrate(bp.entrySol, bp.entryPrice, bp.entryTime, bp.isPaper, "BlueChip")) {
                return true
            }
        } catch (_: Exception) {}

        // Moonshot (uses isPaperMode field name — same semantics)
        try {
            val mp = com.lifecyclebot.v3.scoring.MoonshotTraderAI.getActivePositions().find { it.mint == mint }
            if (mp != null && applyRehydrate(mp.entrySol, mp.entryPrice, mp.entryTime, mp.isPaperMode, "Moonshot")) {
                return true
            }
        } catch (_: Exception) {}

        return false
    }

    private fun resolveSellUnitsForMint(
        mint: String,
        qty: Double,
        wallet: SolanaWallet? = null,
        fallbackDecimals: Int? = null,
    ): Long {
        if (!qty.isFinite() || qty <= 0.0) return 1L

        // V5.9.495l — WALLET-CAP SAFETY NET. Operator (06 May 2026): when
        // a position records inflated qty (e.g. PUMP-FIRST estimate from a
        // stale getActualPrice baked 1000× too many tokens into Position),
        // sells try to dump tokens that aren't there. Jupiter quotes still
        // come back proportionally large → fake +62000% gains in journal.
        // Cap the sell qty at whatever the wallet ACTUALLY holds so we
        // never sell phantom inventory.
        val walletAccounts = try { wallet?.getTokenAccountsWithDecimals() } catch (_: Exception) { null }
        val walletEntry = walletAccounts?.get(mint)
        val walletQty = walletEntry?.first ?: 0.0
        val cappedQty = if (walletQty > 0.0 && walletQty < qty) {
            ErrorLogger.info("Executor",
                "🛡 WALLET-CAP: ${mint.take(8)}… requested qty=$qty but wallet has $walletQty — capping sell to wallet")
            walletQty
        } else qty

        val decimals = walletEntry?.second ?: fallbackDecimals ?: 9
        val scale = 10.0.pow(decimals.coerceAtLeast(0).toDouble())
        return (cappedQty * scale).toLong().coerceAtLeast(1L)
    }

    private data class ConfirmedSellAmount(
        val requestedRaw: Long,
        val requestedUi: Double,
        val walletRaw: Long,
        val walletUi: Double,
        val decimals: Int,
    )

    /** V5.9.601: confirmed wallet balance only. RPC-empty-map means no broadcast. */
    private fun resolveConfirmedSellAmountOrNull(
        ts: TokenState,
        wallet: SolanaWallet,
        requestedUiQty: Double,
        fraction: Double? = null,
        sellTradeKey: String? = null,
        traderTag: String = "MEME",
    ): ConfirmedSellAmount? {
        if (!requestedUiQty.isFinite() || requestedUiQty <= 0.0) return null
        val accounts = try { wallet.getTokenAccountsWithDecimals() } catch (e: Exception) {
            ErrorLogger.warn("Executor", "BALANCE_UNKNOWN ${ts.symbol}: token-account read failed: ${e.message}")
            null
        }
        if (accounts == null || accounts.isEmpty()) {
            LiveTradeLogStore.log(
                sellTradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                "RPC-EMPTY-MAP → BALANCE_UNKNOWN — sell broadcast blocked; caller tokenUnits/cache not authoritative",
                traderTag = traderTag,
            )
            return null
        }
        val entry = accounts[ts.mint]
        if (entry == null) {
            LiveTradeLogStore.log(
                sellTradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                "BALANCE_UNKNOWN — exact owner+mint token account missing; sell broadcast blocked",
                traderTag = traderTag,
            )
            return null
        }
        val (walletUi, dec) = entry
        if (!walletUi.isFinite() || walletUi <= 0.0) return null
        val decimals = dec.coerceAtLeast(0)
        val scale = 10.0.pow(decimals.toDouble())
        val walletRaw = (walletUi * scale).toLong().coerceAtLeast(0L)
        if (walletRaw <= 0L) return null
        val requestedUi = when {
            fraction != null -> walletUi * fraction.coerceIn(0.0, 1.0)
            requestedUiQty > walletUi -> walletUi
            else -> requestedUiQty
        }
        val requestedRaw = (requestedUi * scale).toLong().coerceIn(1L, walletRaw)
        return ConfirmedSellAmount(requestedRaw, requestedRaw.toDouble() / scale, walletRaw, walletUi, decimals)
    }

    private fun blockIfSellInFlight(ts: TokenState, reason: String, tradeKey: String? = null): Boolean {
        val stateReason = HostWalletTokenTracker.sellBlockReason(ts.mint)
        if (stateReason != null && !com.lifecyclebot.engine.sell.SellSafetyPolicy.isManualEmergency(reason)) {
            LiveTradeLogStore.log(
                tradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                "SELL_BLOCKED_ALREADY_IN_PROGRESS state=$stateReason reason=$reason", traderTag = "MEME",
            )
            return true
        }
        if (com.lifecyclebot.engine.sell.SellExecutionLocks.isLocked(ts.mint)) {
            LiveTradeLogStore.log(
                tradeKey ?: LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime),
                ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                "SELL_BLOCKED_ALREADY_IN_PROGRESS lock=true reason=$reason", traderTag = "MEME",
            )
            return true
        }
        return false
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
        laneMode: String = "",         // V5.9.718 — trading lane for phase-aware size scaling
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
            laneMode             = laneMode,
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
            // V5.9.486 — fire-and-forget LLM exit narration. Opt-in (no-op
            // unless operator pasted a personal sk-ant-… key). The default
            // fail-open string ("Exited X on REASON (Y%)") is what shows up
            // when LLM is disabled or errored — same operator-friendly format
            // the in-app log viewer expects.
            try {
                val holdMin = if (ts.position.entryTime > 0)
                    ((System.currentTimeMillis() - ts.position.entryTime) / 60_000L).toInt()
                else 0
                com.lifecyclebot.network.EmergentLlmClient.narrateExitAsync(
                    symbol      = ts.symbol,
                    reason      = trade.reason.ifBlank { "unknown" },
                    pnlPct      = trade.pnlPct,
                    holdMinutes = holdMin,
                ) { narration ->
                    try { onLog("🪶 ${ts.symbol}: $narration", ts.mint) } catch (_: Exception) {}
                }
            } catch (_: Throwable) {}

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
                    // V5.9.495z21 — strategy training gate at this direct
                    // FluidLearningAI call site (parallel to the gate inside
                    // EducationSubLayerAI.recordTradeOutcomeAcrossAllLayers).
                    // Skip if the mint was flagged as a partial-bridge /
                    // output-mismatch event by the execution pipeline.
                    // V5.9.694 — REMOVED direct FluidLearningAI call here.
                    // FluidLearningAI is now fed exclusively through CanonicalOutcomeBus
                    // → CanonicalSubscribers, which has a per-tradeId recordOnce() guard.
                    // Having both paths active caused sessionTrades to grow 2x per close,
                    // inflating FluidLearning from ~95 canonical trades to 3266+.
                    // CanonicalSubscribers handles both PAPER and LIVE environments.
                    // (shouldTrainStrategy gate is also applied in CanonicalSubscribers)
                    if (false) { /* decommissioned — bus is sole source of truth */ }
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
                if (_behAsset == "MEME" && trade.reason != "DEAD_TOKEN_NO_PRICE_EXIT") {
                    // V5.9.723 — DEAD_TOKEN_NO_PRICE_EXIT closes are unpriced
                    // bonding-curve ghosts (pnl=0 always). Feeding them to the
                    // Copilot window inflates the trade count without signal,
                    // can mask real losing streaks, and dilutes regime detection.
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

            // V5.9.495z9 — RICH CANONICAL PUBLISH. Operator: 'wire AdaptiveLearning,
            // RunTracker30D, BehaviorLearning, MetaCognitionAI at their feature-rich
            // emit sites'. We are at the universal trade-close site with ts/trade
            // both in scope, so capture mode/score/conf/holdTime/exitReason and
            // publish a fully-populated CanonicalTradeOutcome. Marks the tradeId
            // so the TradeHistoryStore legacy bridge skips it (no double count).
            try {
                if (trade.side.equals("SELL", ignoreCase = true)) {
                    val tradeId = "${trade.mint}_${trade.ts}"
                    val isPaperEnv = cfg().paperMode
                    val pnl = trade.pnlPct
                    val resultEnum = when {
                        pnl >= 1.0 -> com.lifecyclebot.engine.TradeResult.WIN
                        pnl <= -1.0 -> com.lifecyclebot.engine.TradeResult.LOSS
                        else -> com.lifecyclebot.engine.TradeResult.BREAKEVEN
                    }
                    val executionEnum = if (trade.sig.isNotBlank() || isPaperEnv)
                        com.lifecyclebot.engine.ExecutionResult.EXECUTED
                    else com.lifecyclebot.engine.ExecutionResult.UNKNOWN
                    val rawMode = try { ModeRouter.classify(ts).tradeType.name } catch (_: Throwable) { ts.position.tradingMode }
                    val modeEnum = com.lifecyclebot.engine.CanonicalOutcomeNormalizer.normalizeMode(rawMode)
                    val sourceEnum = when {
                        ts.position.isShitCoinPosition -> com.lifecyclebot.engine.TradeSource.SHITCOIN
                        ts.position.isBlueChipPosition -> com.lifecyclebot.engine.TradeSource.BLUECHIP
                        ts.position.isTreasuryPosition -> com.lifecyclebot.engine.TradeSource.TREASURY
                        else -> com.lifecyclebot.engine.TradeSource.V3
                    }
                    val assetClassEnum = when (sourceEnum) {
                        com.lifecyclebot.engine.TradeSource.BLUECHIP -> com.lifecyclebot.engine.AssetClass.BLUECHIP
                        else -> com.lifecyclebot.engine.AssetClass.MEME
                    }
                    val holdSec = if (ts.position.entryTime > 0) (System.currentTimeMillis() - ts.position.entryTime) / 1000 else null
                    val features = mapOf(
                        "entryScore" to (ts.entryScore.toDouble().takeIf { it.isFinite() } ?: 0.0),
                        "entryConfidence" to (ts.position.entryScore.takeIf { it.isFinite() } ?: 0.0),
                        "tradeSize" to trade.sol,
                        "holdSec" to (holdSec?.toDouble() ?: 0.0),
                    )
                    val rich = com.lifecyclebot.engine.CanonicalTradeOutcome(
                        tradeId = tradeId,
                        mint = ts.mint,
                        symbol = ts.symbol,
                        assetClass = assetClassEnum,
                        mode = modeEnum,
                        source = sourceEnum,
                        environment = if (isPaperEnv) com.lifecyclebot.engine.TradeEnvironment.PAPER else com.lifecyclebot.engine.TradeEnvironment.LIVE,
                        entryTimeMs = ts.position.entryTime,
                        exitTimeMs = trade.ts,
                        entryPrice = ts.position.entryPrice,
                        exitPrice = trade.price,
                        entrySol = ts.position.costSol.takeIf { it > 0.0 },
                        exitSol = trade.sol,
                        realizedPnlSol = trade.netPnlSol.takeIf { it != 0.0 } ?: trade.pnlSol,
                        realizedPnlPct = pnl,
                        maxGainPct = if (ts.position.entryPrice > 0 && ts.position.highestPrice > 0)
                            ((ts.position.highestPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100.0 else null,
                        maxDrawdownPct = if (ts.position.entryPrice > 0 && ts.position.lowestPrice > 0)
                            ((ts.position.lowestPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100.0 else null,
                        holdSeconds = holdSec,
                        result = resultEnum,
                        executionResult = executionEnum,
                        closeReason = trade.reason.ifBlank { null },
                        featuresAtEntry = features,
                    )
                    com.lifecyclebot.engine.CanonicalOutcomeBus.markRichPublished(tradeId)
                    com.lifecyclebot.engine.CanonicalOutcomeBus.publish(rich)
                }
            } catch (e: Exception) {
                ErrorLogger.debug("Executor", "Canonical rich publish error: ${e.message?.take(80)}")
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
            
            // V5.9.751b — route on POSITION isPaper, not config. Previously
            // a live position with a transient wallet=null silently booked a
            // PAPER capital-recovery sell, leaving the real tokens on-chain
            // while the position numbers updated as if they were sold. The
            // wallet=null branch now defers (returns false) so the engine
            // retries on the next monitor tick once the wallet reconnects.
            if (!pos.isPaperPosition && wallet == null) {
                ErrorLogger.warn("Executor",
                    "🚫 CAPITAL_RECOVERY_DEFERRED: ${ts.symbol} — live position but wallet=null. Will retry next tick.")
                return false
            }
            if (pos.isPaperPosition) {
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
                // V5.9.751b — wallet is guaranteed non-null here by the
                // CAPITAL_RECOVERY_DEFERRED guard above; assert for smart cast.
                executeProfitLockSell(ts, wallet!!, sellFraction, "capital_recovery_${gainMultiple.fmt(1)}x", walletSol)
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
            
            // V5.9.751b — see B1 note. Route on position isPaper, defer if
            // live-position + wallet=null.
            if (!pos.isPaperPosition && wallet == null) {
                ErrorLogger.warn("Executor",
                    "🚫 PROFIT_LOCK_DEFERRED: ${ts.symbol} — live position but wallet=null. Will retry next tick.")
                return false
            }
            if (pos.isPaperPosition) {
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
                // V5.9.751b — wallet guaranteed non-null by PROFIT_LOCK_DEFERRED guard.
                executeProfitLockSell(ts, wallet!!, sellFraction, "profit_lock_${gainMultiple.fmt(1)}x", walletSol)
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
        // V5.9.475 — rehydrate ts.position from sub-trader maps if it's
        // empty. Sub-traders (Treasury, ShitCoin, etc.) keep positions in
        // private maps and don't always mirror to ts.position. Without
        // this, profit-lock sells would compute sellQty = 0 * fraction =
        // 0 and silently no-op the entire sell.
        rehydratePositionFromSubTraders(ts)
        val pos = ts.position
        
        // V5.9.495y — DUPLICATE-EXIT GUARD (spec item 9). Refuse to start a
        // second sell if there's already a verified-pending sig in flight
        // for this mint. Prevents stacked broadcasts when the previous sell
        // is in INCONCLUSIVE_PENDING.
        TradeVerifier.activeSellSig(ts.mint)?.let { existingSig ->
            onLog("⏳ SELL DEDUPE: ${ts.symbol} — existing sell sig=${existingSig.take(16)}… still verifying. Skipping new $reason exit attempt.", ts.mint)
            return
        }
        
        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair check failed — aborting profit lock sell", ts.mint)
            return
        }
        
        // V5.9.495z47 — outer vars so PartialSellMismatchDetector can verify
        // post-sell wallet drop ≤ expected after broadcast confirms.
        var preSellRawForAudit: java.math.BigInteger = java.math.BigInteger.ZERO
        var expectedConsumedRawForAudit: java.math.BigInteger = java.math.BigInteger.ZERO
        var decimalsForAudit: Int = 0
        val sellQty = run {
            // V5.9.495z46 P0 — operator spec items C/E (forensics 0508_143519):
            // replace `pos.qtyToken * sellFraction` ad-hoc math with the
            // verified-remaining clamp from PartialSellSizer + SellAmountAuthority.
            // pos.qtyToken can be stale (cached from initial buy result);
            // the wallet's chain-confirmed remaining balance is the truth.
            val resolution = try {
                com.lifecyclebot.engine.sell.SellAmountAuthority.resolve(ts.mint, wallet)
            } catch (_: Throwable) { null }
            val confirmed = resolution as? com.lifecyclebot.engine.sell.SellAmountAuthority.Resolution.Confirmed
            if (confirmed != null) {
                preSellRawForAudit = confirmed.rawAmount
                decimalsForAudit = confirmed.decimals
                val sized = com.lifecyclebot.engine.sell.PartialSellSizer.size(
                    intendedFraction = sellFraction,
                    verifiedRemainingRaw = confirmed.rawAmount,
                )
                if (sized != null) {
                    expectedConsumedRawForAudit = sized.rawAmount
                    val ui = java.math.BigDecimal(sized.rawAmount)
                        .movePointLeft(confirmed.decimals)
                        .toDouble()
                    onLog("📐 PartialSellSizer ${ts.symbol}: " +
                          "verifiedRaw=${confirmed.rawAmount} fraction=${sellFraction} " +
                          "→ rawAmount=${sized.rawAmount} (${"%.6f".format(ui)} ui) " +
                          "vs cached pos.qtyToken=${pos.qtyToken}", ts.mint)
                    ui
                } else {
                    onLog("⚠️ PartialSellSizer dust-rejected for ${ts.symbol} — falling back to cached qty.", ts.mint)
                    pos.qtyToken * sellFraction
                }
            } else {
                onLog("⚠️ SellAmountAuthority UNKNOWN/ZERO for ${ts.symbol} — falling back to cached qty (pos.qtyToken=${pos.qtyToken}).", ts.mint)
                pos.qtyToken * sellFraction
            }
        }
        val sellUnits = resolveSellUnits(ts, sellQty, wallet = wallet)

        // V5.9.495z29 — operator spec item 4: ExecutableQuoteGate.
        // Profit-lock is only allowed when a fresh executable Jupiter quote
        // for the EXACT wallet token amount produces a slippage-adjusted
        // net SOL output above the entry basis recorded in
        // TokenLifecycleTracker. Refuses on stale price / zero balance /
        // missing entry basis / extreme price impact / route-not-found.
        // Capital-recovery exits (which run at the same call site for
        // 'capital_recovery_*' reasons) DELIBERATELY skip the gate — they
        // exit on risk grounds even at a small loss. So we only gate
        // pure profit-lock paths.
        val isProfitLockReason = reason.startsWith("profit_lock") ||
                                 reason.startsWith("partial_profit_lock") ||
                                 reason.startsWith("take_profit")
        if (isProfitLockReason && sellUnits > 0L) {
            val lifecycle = TokenLifecycleTracker.get(ts.mint)
            val entryBasis = lifecycle?.entrySolSpent ?: 0.0
            // entryBasis falls back to 0 if the lifecycle tracker hasn't
            // been populated yet (older positions pre-z28). In that case
            // we let the profit-lock proceed (operator spec: 'do not
            // block on missing data, log clearly').
            if (entryBasis > 0.0) {
                val gateSlip = SellSlippageProfile.forTier(
                    SellSlippageProfile.Tier.NORMAL_PROFIT_LOCK
                ).initialBps
                val verdict = ExecutableQuoteGate.evaluate(
                    tokenMint = ts.mint,
                    currentWalletTokenRaw = sellUnits,
                    entrySolSpent = entryBasis,
                    minNetProfitFraction = 0.10,   // ≥10% net required
                    slippageBps = gateSlip,
                    jupiter = jupiter,
                )
                when (verdict) {
                    is ExecutableQuoteGate.Verdict.Rejected -> {
                        onLog(
                            "🚫 PROFIT_LOCK_BLOCKED ${ts.symbol} " +
                            "${verdict.code}: ${verdict.reason}", ts.mint,
                        )
                        LiveTradeLogStore.log(
                            LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis()),
                            ts.mint, ts.symbol, "INFO",
                            LiveTradeLogStore.Phase.WARNING,
                            "Profit-lock blocked: ${verdict.code} | ${verdict.reason}",
                            traderTag = "MEME",
                        )
                        return
                    }
                    is ExecutableQuoteGate.Verdict.Approved -> {
                        ErrorLogger.info("Executor",
                            "✅ PROFIT_LOCK_APPROVED ${ts.symbol} " +
                            "netSol=${"%.6f".format(verdict.expectedSolOutNet)} " +
                            "impact=${"%.2f".format(verdict.priceImpactPct)}%")
                    }
                }
            }
        }

        // V5.9.474 — full forensics + V5.9.468 binding-order + V5.9.472 cap removal
        // for the profit-lock / capital-recovery sell path. Operator's screenshot
        // showed sells labelled 'profit_lock_86.2x' / 'capital_recovery_86.2x'
        // executing but never appearing in Live Trade Forensics — this entire
        // function was emitting onLog only and bypassed LiveTradeLogStore.
        val sellTradeKey = LiveTradeLogStore.keyFor(ts.mint, pos.entryTime)
        if (blockIfSellInFlight(ts, reason, sellTradeKey)) return
        if (!com.lifecyclebot.engine.sell.SellExecutionLocks.tryAcquire(ts.mint)) {
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                "SELL_BLOCKED_ALREADY_IN_PROGRESS profit-lock reason=$reason",
                traderTag = "MEME",
            )
            return
        }
        val sellSlippage = com.lifecyclebot.engine.sell.SellSafetyPolicy.initialSlippageBps(reason)

        // V5.9.479 — DRAIN-EXIT detection + in-line slippage ladder mirroring
        // V5.9.478 in liveSell. Profit-lock sells previously did a single
        // broadcast attempt and on 0x1788 only escalated across ticks (slow).
        // Now: a profit-lock fail at 200bps immediately re-tries at the next
        // tier in this same call.
        // V5.9.495v — operator triage 06 May 2026: WCOR capital_recovery_85.1x
        // failed every slippage tier 200/400/600/1000/2000bps because the
        // reason string didn't match any drain keyword and got the
        // CONSERVATIVE ladder. (Re-evaluated below — see z38.)
        // V5.9.495z38 — operator-reported real-money safety bug:
        // PROFIT-LOCK sell ran at 75% slippage and consumed ~3.6× the
        // intended quantity. Root cause: profit_lock and
        // capital_recovery were being treated as DRAIN-EXIT, allowing
        // the 5000→7500→9999bps escalation. This is wrong: those
        // exits are "we've already made our money, want out cleanly",
        // NOT "rug emergency". Per operator spec:
        //   - PROFIT_LOCK         max 500–800 bps
        //   - CAPITAL_RECOVERY    max 1000–1500 bps
        //   - 7500/9999 bps is RUG_DRAIN territory only.
        val isDrainExit = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason) > 1200 &&
                          (com.lifecyclebot.engine.sell.SellSafetyPolicy.isHardRug(reason) ||
                           com.lifecyclebot.engine.sell.SellSafetyPolicy.isManualEmergency(reason))
        val isProfitLock      = reason.contains("profit_lock", ignoreCase = true)
        val isCapitalRecovery = reason.contains("capital_recovery", ignoreCase = true)
        val broadcastSlipLadder = com.lifecyclebot.engine.sell.SellSafetyPolicy.ladder(reason)
        // Forensic guard — if we ever try to take a profit_lock/cap_rec
        // sell with slippage above its cap, surface a SELL_AMOUNT_VIOLATION
        // event so the operator sees it in forensics.
        run {
            val maxAllowed = when {
                isProfitLock      -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
                isCapitalRecovery -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
                isDrainExit       -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
                else              -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
            }
            if (sellSlippage > maxAllowed) {
                ErrorLogger.warn("Executor",
                    "🚨 SLIPPAGE_CAP_VIOLATION: ${ts.symbol} reason=$reason slippage=${sellSlippage}bps > max=${maxAllowed}bps — clamping.")
            }
        }
        // Tail-clamp the ladder so the broadcast loop physically cannot
        // escalate past the cap.
        val maxLadderBps = when {
            isDrainExit       -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
            isCapitalRecovery -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
            isProfitLock      -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
            else              -> com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason)
        }
        val broadcastSlipLadderCapped = broadcastSlipLadder.map { it.coerceAtMost(maxLadderBps) }.distinct()

        LiveTradeLogStore.log(
            sellTradeKey, ts.mint, ts.symbol, "SELL",
            LiveTradeLogStore.Phase.SELL_START,
            "executeProfitLockSell: $reason | sellFraction=${(sellFraction*100).toInt()}% | qty=$sellUnits | slip=${sellSlippage}bps | ladder=${broadcastSlipLadderCapped.joinToString(",")}${if (isDrainExit) " [DRAIN-EXIT]" else if (isProfitLock) " [PROFIT-LOCK]" else if (isCapitalRecovery) " [CAPITAL-RECOVERY]" else ""}",
            tokenAmount = sellQty,
            slippageBps = sellSlippage,
            traderTag = "MEME",
        )

        try {
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_QUOTE_TRY,
                "Quote try @ ${sellSlippage}bps",
                slippageBps = sellSlippage,
                traderTag = "MEME",
            )
            // V5.9.468 — pass wallet pubkey as taker so the quote is a binding
            // Ultra-v2 order. RFQ rejections surface here, not silently in build.
            var quote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT, sellUnits,
                                                   sellSlippage, isBuy = false,
                                                   sellTaker = wallet.publicKeyB58)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_QUOTE_OK,
                "Quote OK | out=${quote.outAmount} | impact=${"%.2f".format(quote.priceImpactPct)}% | router=${quote.router}${if (quote.isRfqRoute) " (RFQ)" else ""}${if (quote.ultraRejectedReason.isNotBlank()) " ⚠ Ultra REJECTED → Metis fallback (${quote.ultraRejectedReason.take(60)})" else ""}",
                slippageBps = sellSlippage,
                traderTag = "MEME",
            )

            // V5.9.479 — IN-LINE broadcast escalation loop (matches V5.9.478 in liveSell)
            var sig: String? = null
            var lastBroadcastException: Exception? = null
            var broadcastAttempts = 0

            // V5.9.495 — PUMP-FIRST routing for profit-lock partial sells.
            // Same universal-auto routing as liveSell: try PumpPortal direct
            // FIRST (fast path), fall through to Jupiter ladder on failure;
            // a final PUMP-RESCUE retry at higher slip fires after Jupiter
            // exhaustion if PUMP-FIRST was rejected the first time.
            //
            // Inlined (no `run { }` lambda) so Kotlin smart-cast on `sig`
            // still works downstream — closures break flow analysis.
            val pfPumpSlip = if (isDrainExit) 75 else 30
            val pfPumpJito = c.jitoEnabled
            val pfPumpTip = com.lifecyclebot.network.JitoTipFetcher
                .getDynamicTip(c.jitoTipLamports)
                .let { if (isDrainExit) (it * 2).coerceAtMost(1_000_000L) else it }
            sig = tryPumpPortalSell(
                ts = ts,
                wallet = wallet,
                tokenUnits = sellUnits,
                slipPct = pfPumpSlip,
                priorityFeeSol = if (isDrainExit) 0.0005 else 0.0001,
                useJito = pfPumpJito,
                jitoTipLamports = pfPumpTip,
                sellTradeKey = sellTradeKey,
                traderTag = "MEME",
                labelTag = "PROFIT-LOCK",
            )
            // V5.9.495 — skip Jupiter ladder if PumpPortal already landed.
            // V5.9.495d — Ultra-first fallback signal. liveSell uses
            // getQuoteWithTaker which prefers Jupiter Ultra v2 then v6
            // Metis on RFQ rejection. Log the intent so operator sees the
            // escalation chain end-to-end on the forensics tile.
            if (sig == null) {
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.INFO,
                    "↩ FALLBACK → Jupiter Ultra (v2) primary, v6 Metis secondary | ladder=${broadcastSlipLadder.joinToString("/")}bps",
                    traderTag = "MEME",
                )
            }
            for (currentSlip in if (sig != null) emptyList() else broadcastSlipLadder) {
                broadcastAttempts++
                try {
                    if (broadcastAttempts > 1) {
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_QUOTE_TRY,
                            "⚡ ESCALATION re-quote @ ${currentSlip}bps (attempt $broadcastAttempts)${if (isDrainExit) " [DRAIN-EXIT]" else ""}",
                            slippageBps = currentSlip, traderTag = "MEME",
                        )
                        try {
                            quote = getQuoteWithSlippageGuard(
                                ts.mint, JupiterApi.SOL_MINT, sellUnits, currentSlip,
                                isBuy = false, sellTaker = wallet.publicKeyB58)
                            // V5.9.495u — operator triage 06 May 2026: "are
                            // we using ultra the way its meant to be?". We
                            // ARE — getQuoteWithTaker double-taps Ultra
                            // before falling to v6 Metis on every escalation
                            // — but the previous log line dropped the
                            // ultraRejectedReason suffix, so forensics
                            // looked like we'd silently abandoned Ultra.
                            // Log it explicitly here on every re-quote so
                            // the operator can see Ultra-vs-Metis routing
                            // each tier of the slippage ladder.
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_QUOTE_OK,
                                "Re-quote OK @ ${currentSlip}bps | out=${quote.outAmount} | router=${quote.router}${if (quote.isRfqRoute) " (RFQ)" else ""}${if (quote.ultraRejectedReason.isNotBlank()) " ⚠ Ultra REJECTED → Metis (${quote.ultraRejectedReason.take(60)})" else if (quote.isUltra) " ✅ ULTRA accepted" else ""}",
                                slippageBps = currentSlip, traderTag = "MEME",
                            )
                        } catch (qex: Exception) {
                            lastBroadcastException = qex
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_QUOTE_FAIL,
                                "Re-quote ${currentSlip}bps FAILED: ${qex.message?.take(80) ?: "?"}",
                                slippageBps = currentSlip, traderTag = "MEME",
                            )
                            continue
                        }
                    }
                    val dynSlipCap = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason).coerceAtLeast(currentSlip)
                    val txResult = buildTxWithRetry(quote, wallet.publicKeyB58, dynamicSlippageMaxBps = dynSlipCap)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_TX_BUILT,
                        "Tx built | router=${txResult.router} rfq=${txResult.isRfqRoute} | slip=${currentSlip}bps (attempt $broadcastAttempts)" + (if (txResult.dynSlipPickedBps >= 0) " | dyn-slip picked=${txResult.dynSlipPickedBps}bps incurred=${txResult.dynSlipIncurredBps}bps" else ""),
                        traderTag = "MEME",
                    )
                    security.enforceSignDelay()

                    val useJito = c.jitoEnabled && !quote.isUltra
                    // V5.9.483 — dynamic Jito tip from bundles.jito.wtf 75th percentile.
                    // Static c.jitoTipLamports (10_000) was too low during congestion;
                    // drain-exit doubles the tip so we don't lose the position to a slow land.
                    val jitoTip = com.lifecyclebot.network.JitoTipFetcher
                        .getDynamicTip(c.jitoTipLamports)
                        .let { if (isDrainExit) (it * 2).coerceAtMost(1_000_000L) else it }
                    val ultraReqId = if (quote.isUltra) txResult.requestId else null

                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_BROADCAST,
                        "Broadcasting @ ${currentSlip}bps | route=${if (quote.isUltra) "ULTRA" else if (useJito) "JITO" else "RPC"} (attempt $broadcastAttempts)",
                        traderTag = "MEME",
                    )
                    sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_CONFIRMED,
                        "✅ Sig confirmed @ ${currentSlip}bps (attempt $broadcastAttempts): ${sig.take(20)}…",
                        sig = sig, traderTag = "MEME",
                    )
                    break
                } catch (broadcastEx: Exception) {
                    lastBroadcastException = broadcastEx
                    val safe = security.sanitiseForLog(broadcastEx.message ?: "unknown")
                    val isSlippage = safe.contains("0x1788", ignoreCase = true) ||
                                     safe.contains("0x1789", ignoreCase = true) ||
                                     safe.contains("TooLittleSolReceived", ignoreCase = true) ||
                                     safe.contains("Slippage", ignoreCase = true) ||
                                     safe.contains("SlippageToleranceExceeded", ignoreCase = true)
                    if (!isSlippage) throw broadcastEx
                    val brc = zeroBalanceRetries.merge(ts.mint + "_broadcast", 1) { old, _ -> old + 1 } ?: 1
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_FAILED,
                        "SLIPPAGE @ ${currentSlip}bps (in-line attempt $broadcastAttempts) — escalating${if (isDrainExit) " [DRAIN-EXIT]" else ""} (lifetime=$brc)",
                        slippageBps = currentSlip, traderTag = "MEME",
                    )
                }
            }
            if (sig == null) {
                // V5.9.495 — PUMP DIRECT FALLBACK after Jupiter ladder
                // exhausted. Profit-lock always tries PUMP-FIRST at moderate
                // slip; this final attempt bumps slippage so a tighter market
                // gets a second shot via PumpPortal at e.g. 75% (drain) or
                // 50% (normal).
                val pumpSlip = if (isDrainExit) 90 else 50
                val pumpJito = c.jitoEnabled
                val pumpTip = com.lifecyclebot.network.JitoTipFetcher
                    .getDynamicTip(c.jitoTipLamports)
                    .let { if (isDrainExit) (it * 2).coerceAtMost(1_000_000L) else it }
                sig = tryPumpPortalSell(
                    ts = ts,
                    wallet = wallet,
                    tokenUnits = sellUnits,
                    slipPct = pumpSlip,
                    priorityFeeSol = if (isDrainExit) 0.0008 else 0.0003,
                    useJito = pumpJito,
                    jitoTipLamports = pumpTip,
                    sellTradeKey = sellTradeKey,
                    traderTag = "MEME",
                    labelTag = "PROFIT-LOCK-RESCUE",
                )
            }
            if (sig == null) {
                throw lastBroadcastException ?: RuntimeException(
                    "${ts.symbol}: profit-lock in-line broadcast retries exhausted at ${broadcastSlipLadder.last()}bps")
            }
            // Reset broadcast retry counter on success
            zeroBalanceRetries.remove(ts.mint + "_broadcast")
            // V5.9.479 — capture non-null sig for downstream Trade record + log calls
            val finalSig: String = sig!!
            // V5.9.495y — register with TradeVerifier dedupe guard immediately after broadcast accepts.
            if (!finalSig.startsWith("PHANTOM_")) TradeVerifier.beginSell(ts.mint, finalSig, reason)

            // V5.9.495z47 — operator P0 (forensics 0508_143519):
            // PARTIAL_SELL_AMOUNT_MISMATCH detection. If the wallet drop
            // exceeds the expected consumed raw by >5%, the detector locks
            // the mint via SellAmountAuditor (which executor.liveSell early-
            // returns on). Fail-soft: never block the success path.
            if (!finalSig.startsWith("PHANTOM_")) {
                try {
                    if (decimalsForAudit > 0 &&
                        expectedConsumedRawForAudit.signum() > 0 &&
                        preSellRawForAudit.signum() > 0) {
                        com.lifecyclebot.engine.sell.PartialSellMismatchDetector.verifyAndMaybeLock(
                            mint = ts.mint,
                            symbol = ts.symbol,
                            decimals = decimalsForAudit,
                            expectedConsumedRaw = expectedConsumedRawForAudit,
                            preSellWalletRaw = preSellRawForAudit,
                            wallet = wallet,
                        )
                    }
                } catch (_: Throwable) { /* fail-soft */ }
            }

            // V5.9.495s — POST-BROADCAST SELL VERIFY. Operator (06 May 2026):
            // "the capital recovery/profit lock sells in live in memes aren't
            // working correctly. its assuming it sells then leaving the full
            // amount in the host wallet". Previously this path trusted the
            // PumpPortal/Jupiter signature and reduced ts.position.qtyToken
            // immediately, even if the broadcast never settled on-chain
            // (slippage, no liq, MEV bundle dropped). Now: read the wallet
            // token balance for up to 12s after broadcast; require ≥60% of
            // sellQty to actually leave the wallet before we mutate the
            // position record. Synthetic PHANTOM_ sentinels (dust/local
            // closes) skip this gate. Real sigs that didn't settle now
            // bail out cleanly with a SELL_FAILED forensics entry and the
            // position stays intact for the next exit attempt.
            // V5.9.495y/z — TRADE-VERIFIER AUTHORITATIVE SELL VERIFY (spec
            // items 6, 8). Replaces the V5.9.495s/t wallet-token-poll
            // heuristic — which was producing false SELL_FAILED on
            // RPC empty-map even when the chain had settled. Now: use
            // getSignatureStatuses + getTransaction tx-parse as the
            // single authority. LANDED requires BOTH tokens cleared AND
            // SOL received (operator: "I still want the sell verified and
            // that the tokens clear the wallet and return the sol").
            // INCONCLUSIVE_PENDING leaves the position open & qty
            // unchanged so the next exit tick can retry naturally.
            // V5.9.495z — when LANDED, capture the verifier's solReceived
            // (real on-chain SOL delta) into a closure-mutable holder so
            // the journal-write below uses on-chain truth instead of the
            // pre-trade quote.outAmount estimate.
            var verifiedSolReceived: Long = -1L
            run {
                if (finalSig.startsWith("PHANTOM_")) return@run  // dust/local close — skip verify
                val vsr = try {
                    TradeVerifier.verifySell(wallet, finalSig, ts.mint, timeoutMs = 60_000L)
                } catch (vfx: Throwable) {
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.WARNING,
                        "⚠ TradeVerifier threw (${vfx.message?.take(60)}) — leaving position pending",
                        sig = finalSig, traderTag = "MEME",
                    )
                    null
                }
                when (vsr?.outcome) {
                    TradeVerifier.Outcome.LANDED -> {
                        verifiedSolReceived = vsr.solReceivedLamports
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_TX_PARSE_OK,
                            "✅ SELL LANDED [$reason]: rawConsumed=${vsr.rawTokenConsumed} ui=${vsr.uiTokenConsumed.fmt(4)} solReceived=${vsr.solReceivedLamports} lamports${if (vsr.tokenAccountClosedFullExit) " (ATA closed = full exit)" else ""}",
                            sig = finalSig, tokenAmount = vsr.uiTokenConsumed, traderTag = "MEME",
                        )
                        // Fall through to position mutation + journal write.
                    }
                    TradeVerifier.Outcome.FAILED_CONFIRMED -> {
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_FAILED_CONFIRMED,
                            "🚨 SELL_FAILED_CONFIRMED [$reason]: meta.err=${vsr.txErr} — position UNCHANGED, no treasury credit.",
                            sig = finalSig, tokenAmount = sellQty, traderTag = "MEME",
                        )
                        onLog("🚨 SELL meta.err [$reason] ${ts.symbol}: ${vsr.txErr} — position unchanged.", ts.mint)
                        TradeVerifier.endSell(ts.mint)
                        com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
                        return
                    }
                    TradeVerifier.Outcome.INCONCLUSIVE_PENDING,
                    TradeVerifier.Outcome.VERIFICATION_ERROR -> {
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                            "⏳ SELL VERIFY INCONCLUSIVE [$reason]: tokens cleared AND SOL returned not BOTH proven within 60s — leaving position open & qty unchanged. Next tick will retry. Reconciler will adopt later.",
                            sig = finalSig, tokenAmount = sellQty, traderTag = "MEME",
                        )
                        TradeVerifier.endSell(ts.mint)
                        com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
                        return
                    }
                    null -> {
                        // verifier threw — for safety, decline to mutate position; operator can re-trigger exit.
                        TradeVerifier.endSell(ts.mint)
                        com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
                        return
                    }
                }
            }

            // V5.9.495y — clear the duplicate-exit guard once we've reached the success-mutation path.
            TradeVerifier.endSell(ts.mint)

            // V5.9.495z — solBack derives from REAL on-chain SOL delta when
            // verifier proved it. Falls back to quote only for PHANTOM
            // sentinels. This kills the +100277% / -100% phantom-PnL
            // entries from V5.9.495w that used quote.outAmount (inflated).
            val solBack: Double = when {
                sig.startsWith("PHANTOM_") -> 0.0
                verifiedSolReceived > 0L -> verifiedSolReceived / 1_000_000_000.0
                else -> quote.outAmount / 1_000_000_000.0  // legacy fallback (rare; verifier should always populate)
            }

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
                pnlSol, pnlPct, sig = finalSig, feeSol = feeSol, netPnlSol = netPnl)
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

            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_VERIFY_SOL_RETURNED,
                "Profit lock landed | ${solBack.fmt(4)} SOL | net ${netPnl.fmt(4)} | ${if (pnlSol > 0) "+" else ""}${pnlPct.fmt(2)}%",
                sig = finalSig,
                solAmount = solBack,
                traderTag = "MEME",
            )
            // V5.9.495z28 (operator spec items 1+2+3): TWO-SIDE verification.
            // Don't trust SOL-returned alone — reconcile the wallet token
            // balance and only mark CLEARED when it's ≤ dust. Otherwise the
            // lifecycle tracker keeps the position in PARTIAL_SELL /
            // RESIDUAL_HELD so subsequent sweeps can finish the job.
            try {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val reading = TokenLifecycleTracker.reconcileWalletBalance(wallet, ts.mint)
                    val walletAfter = when (reading) {
                        is TokenLifecycleTracker.Reading.Confirmed -> reading.uiAmount
                        TokenLifecycleTracker.Reading.Empty -> null
                    }
                    TokenLifecycleTracker.onSellSettled(
                        mint = ts.mint, sig = finalSig,
                        solReceived = solBack, walletTokenAfter = walletAfter,
                    )
                    val phase = when {
                        walletAfter == null -> LiveTradeLogStore.Phase.SELL_STUCK
                        walletAfter > 0.000_001 -> LiveTradeLogStore.Phase.SELL_VERIFY_TOKEN_GONE  // misleading legacy name; partial
                        else -> LiveTradeLogStore.Phase.SELL_VERIFY_TOKEN_GONE
                    }
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL", phase,
                        "Wallet reconcile: " + when {
                            walletAfter == null -> "RPC empty — RESIDUAL_HELD, watchdog will retry"
                            walletAfter > 0.000_001 -> "PARTIAL — ${walletAfter} ui remain"
                            else -> "CLEARED (token gone, SOL returned)"
                        },
                        traderTag = "MEME",
                    )
                    com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
                }
            } catch (_: Throwable) {
                com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
                /* never break the live path on reconcile */
            }
            onLog("✅ LIVE $reason: ${solBack.fmt(4)} SOL | pnl ${pnlSol.fmt(4)} SOL | sig=${finalSig.take(16)}…", ts.mint)
            onNotify("✅ Profit Locked",
                "${ts.symbol} secured ${solBack.fmt(3)} SOL",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                
        } catch (e: Exception) {
            // V5.9.474 — classified post-quote forensics for profit-lock catch
            // (mirrors V5.9.468 outer-catch in liveSell). Was: onLog-only silent kill.
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            val broadcastRetries = zeroBalanceRetries.merge(ts.mint + "_broadcast", 1) { old, _ -> old + 1 } ?: 1
            val failureClass = when {
                safe.contains("0x1788", ignoreCase = true) ||
                safe.contains("0x1789", ignoreCase = true) ||
                safe.contains("TooLittleSolReceived", ignoreCase = true) ||
                safe.contains("Slippage", ignoreCase = true) -> "SLIPPAGE_EXCEEDED"
                safe.contains("simulation failed", ignoreCase = true) -> "SIM_FAILED"
                safe.contains("RFQ", ignoreCase = true) -> "RFQ_ROUTE_FAILED"
                safe.contains("blockhash", ignoreCase = true) ||
                safe.contains("expired", ignoreCase = true) -> "TX_EXPIRED"
                safe.contains("rate limit", ignoreCase = true) ||
                safe.contains("429", ignoreCase = true) -> "RATE_LIMITED"
                safe.contains("timeout", ignoreCase = true) -> "TIMEOUT"
                safe.contains("InsufficientFunds", ignoreCase = true) ||
                safe.contains("insufficient lamports", ignoreCase = true) ||
                safe.contains("rent", ignoreCase = true) -> "INSUFFICIENT_FUNDS"
                safe.contains("buildSwapTx", ignoreCase = true) ||
                safe.contains("transactionBase64", ignoreCase = true) -> "JUPITER_BUILD_FAILED"
                else -> "BROADCAST_FAILED"
            }
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_FAILED,
                "$failureClass — ${e.javaClass.simpleName}: ${safe.take(120)} (attempt $broadcastRetries)",
                traderTag = "MEME",
            )
            com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
            ErrorLogger.warn("Executor", "PROFIT-LOCK SELL POST-QUOTE FAILURE: ${ts.symbol} class=$failureClass exc=${e.javaClass.simpleName} retry=$broadcastRetries")
            onLog("❌ Profit lock sell FAILED: $failureClass — ${safe.take(80)} (retry $broadcastRetries)", ts.mint)
        }
    }

    // ── partial sell ─────────────────────────────────────────────────

    /**
     * V5.9.494 — public manage-only path used by BotService.sweepUniversalExits.
     * Runs the in-position management triad on EVERY open position once
     * per loop tick, regardless of scanner visibility:
     *   1) checkProfitLock — dynamic profit-lock + trailing
     *   2) checkPartialSell — TP1/TP2 partial profit-taking
     *   3) Catastrophic floor — last-resort SL via FluidLearning floor
     *
     * Idempotent: if no trigger fires, this is three cheap conditionals.
     * Operator: 'where are the fluid stops and dynamic profit lockers?
     * nothing should rip 35% out of us!' — this guarantees they fire
     * even when DexScreener misses a tick.
     */
    fun runManageOnly(ts: TokenState, wallet: SolanaWallet?, walletSol: Double) {
        if (!ts.position.isOpen) return
        val currentPrice = getActualPrice(ts)
        if (currentPrice > 0.0) {
            ts.position.highestPrice = maxOf(ts.position.highestPrice, currentPrice)
            if (ts.position.lowestPrice == 0.0 || currentPrice < ts.position.lowestPrice)
                ts.position.lowestPrice = currentPrice
        }

        // V5.9.495z5 — STRICT PER-TRADER SL FIRES UNIVERSALLY (no settle-in
        // grace). Operator (06 May 2026, COMPANY -16.5% with SL -5% never
        // firing): "stop losses on treasury trades are meant to be tight
        // and we are meant to have the dynamic stop loss and profit locks
        // working in a fluid state on all meme trades no matter which
        // trader is carrying out the trade."
        //
        // Previous behaviour (V5.9.495i) returned on settle-in BEFORE
        // STRICT_SL — meaning a fresh entry could bleed past its configured
        // SL during the first 45s and we'd still hold. The settle-in was
        // added to prevent kneejerk exits on noise, but the operator's
        // explicit mandate is: SL is tight, period. We keep settle-in for
        // FLUID exits (dynamic floor, partial-sell, profit-lock unlock) but
        // STRICT_SL_FLOOR runs first regardless of age and applies to every
        // open position no matter which sub-trader entered it.
        run {
            val pos = ts.position
            val rawSL = when {
                pos.isShitCoinPosition && pos.shitCoinStopLoss > 0.0 -> pos.shitCoinStopLoss
                pos.isBlueChipPosition && pos.blueChipStopLoss > 0.0 -> pos.blueChipStopLoss
                pos.isTreasuryPosition && pos.treasuryStopLoss > 0.0 -> pos.treasuryStopLoss
                else -> cfg().stopLossPct.takeIf { it > 0.0 } ?: 20.0
            }
            // Convert to negative threshold; never wider than -50%.
            val hardFloor = -rawSL.coerceIn(3.0, 50.0)
            val pnlPctNow = if (pos.entryPrice > 0)
                ((currentPrice - pos.entryPrice) / pos.entryPrice) * 100
            else 0.0
            if (currentPrice > 0.0 && pnlPctNow <= hardFloor) {
                onLog("🛑 STRICT SL: ${ts.symbol} pnl=${pnlPctNow.toInt()}% ≤ ${hardFloor.toInt()}% (trader=${
                    when {
                        pos.isShitCoinPosition -> "SHITCOIN"
                        pos.isBlueChipPosition -> "BLUECHIP"
                        pos.isTreasuryPosition -> "TREASURY"
                        else -> "DEFAULT"
                    }
                }) — force-exit", ts.mint)
                doSell(ts, "STRICT_SL_${hardFloor.toInt()}", wallet, walletSol)
                return
            }
            // V5.9.495z5 forensics — when SL would have fired but the price
            // resolution failed, surface it so we can fix the data path
            // instead of silently bleeding.
            if (currentPrice <= 0.0 && pos.entryPrice > 0.0) {
                ErrorLogger.debug(
                    "Executor",
                    "⚠ STRICT SL skipped on ${ts.symbol}: currentPrice=$currentPrice (no live tick) — relying on next price resolve"
                )
            }
        }

        // V5.9.495i — POST-BUY SETTLE-IN GRACE for the FLUID exit predicates
        // (partial-sell, profit-lock unlock, fluid floor). Operator: "it
        // buys them then 5 seconds later it sells them". 45s breathing room
        // for fluid logic only — strict SL above already enforced tightly.
        val posAgeMs = System.currentTimeMillis() - ts.position.entryTime
        val SETTLE_IN_MS = 45_000L
        if (posAgeMs < SETTLE_IN_MS) {
            return  // silent grace for fluid path — strict SL already ran
        }

        // V5.9.723 — DEAD_TOKEN_EARLY_EXIT
        // Pump.fun bonding-curve tokens with no live price feed fall back to entryPrice
        // every tick → pnl stays at exactly 0%, peak stays at 0%, position never exits.
        // These ghost positions hold for 60min then exit as MID_FLAT_CHOP, burning
        // the position slot and contributing nothing to learning.
        //
        // Trigger: position open >= 15min AND highest price never exceeded entry + 0.5%
        // (i.e. getActualPrice always resolved to entryPrice via fallback).
        // Exempt: any position that ever moved (highestPrice > entryPrice * 1.005).
        // Exit: force sell via requestSell as DEAD_TOKEN_NO_PRICE_EXIT.
        val pos = ts.position
        if (posAgeMs >= 15 * 60_000L) {
            val entryPx = pos.entryPrice
            val peakPx  = pos.highestPrice
            val isDeadNoFeed = entryPx > 0.0 &&
                currentPrice > 0.0 &&
                currentPrice == entryPx &&           // still at entry — fallback path
                peakPx <= entryPx * 1.005            // never moved more than 0.5%
            if (isDeadNoFeed) {
                ErrorLogger.info("Executor", "🪦 DEAD_TOKEN_NO_PRICE_EXIT: ${ts.symbol} | held=${posAgeMs/60_000}min | peak=${((peakPx/entryPx-1)*100).toInt()}% | freeing slot")
                requestSell(
                    ts       = ts,
                    reason   = "DEAD_TOKEN_NO_PRICE_EXIT",
                    wallet   = wallet,
                    walletSol = walletSol,
                )
                return
            }
        }

        if (checkProfitLock(ts, wallet, walletSol)) return
        if (ts.position.isOpen) checkPartialSell(ts, wallet, walletSol)
        if (ts.position.isOpen) {
            val pnlPct = if (ts.position.entryPrice > 0)
                ((currentPrice - ts.position.entryPrice) / ts.position.entryPrice) * 100
            else 0.0

            // V5.9.684 — SWEEP TAKE-PROFIT.
            // checkProfitLock handles partial milestone locks but never fires a
            // full-position TP exit. Sub-trader TP fields (shitCoinTakeProfit,
            // blueChipTakeProfit, treasuryTakeProfit) are stored on the position
            // but only checked inside processTokenCycle when the token has a
            // fresh scanner tick. If the scanner skips the mint (deferred,
            // cooldown, no fresh pair) the TP fires here instead.
            // Falls back to cfg.exitScoreThreshold-driven default TP (20%) if
            // no sub-trader TP was ever stored.
            if (currentPrice > 0.0) {
                val tpPct = when {
                    ts.position.isShitCoinPosition && ts.position.shitCoinTakeProfit > 0.0 ->
                        ts.position.shitCoinTakeProfit
                    ts.position.isBlueChipPosition && ts.position.blueChipTakeProfit > 0.0 ->
                        ts.position.blueChipTakeProfit
                    ts.position.isTreasuryPosition && ts.position.treasuryTakeProfit > 0.0 ->
                        ts.position.treasuryTakeProfit
                    else -> {
                        // Generic meme: use fluid TP — lerps from 15% bootstrap to
                        // cfg default as learning matures.
                        try {
                            com.lifecyclebot.v3.scoring.FluidLearningAI
                                .getFluidTakeProfit(cfg().exitScoreThreshold.coerceAtLeast(20.0))
                        } catch (_: Throwable) { 20.0 }
                    }
                }
                if (pnlPct >= tpPct) {
                    onLog("🎯 SWEEP_TAKE_PROFIT: ${ts.symbol} pnl=${pnlPct.toInt()}% ≥ tp=${tpPct.toInt()}%", ts.mint)
                    doSell(ts, "SWEEP_TAKE_PROFIT_${tpPct.toInt()}", wallet, walletSol)
                    return
                }
            }

            // Fluid stop floor — already wired but make the log more visible
            val floor = try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.getFluidStopLoss(-25.0)
            } catch (_: Throwable) { -25.0 }
            if (pnlPct <= floor && currentPrice > 0.0) {
                onLog("🛑 SWEEP_FLUID_FLOOR: ${ts.symbol} pnl=${pnlPct.toInt()}% ≤ floor=${floor.toInt()}%", ts.mint)
                doSell(ts, "SWEEP_FLUID_FLOOR_${floor.toInt()}", wallet, walletSol)
            }
        }
    }

    fun checkPartialSell(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {
        val c   = cfg()
        normalizePositionScaleIfNeeded(ts)
        val pos = ts.position
        if (!c.partialSellEnabled || !pos.isOpen) return false

        val actualPrice = getActualPrice(ts)
        val gainPct = pct(pos.entryPrice, actualPrice)
        val soldPct = pos.partialSoldPct

        val partialLevel = (soldPct / (c.partialSellFraction * 100.0)).toInt()
        
        // V5.9.722 — WR-recovery partial: lower the first milestone when WR is below phase target.
        // Second/third milestones are unchanged — runners are never capped by recovery mode.
        val firstTrigger = WrRecoveryPartial.effectiveTrigger(
            normalTrigger      = c.partialSellTriggerPct,
            gainPct            = gainPct,
            partialLevel       = partialLevel,
            profitLockTriggered = pos.profitLocked,
        )
        val milestones = listOf(
            firstTrigger,
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
        // V5.9.495z47 P0 — operator spec items C/E (forensics 0508_143519):
        // checkPartialSell is the second partial-sell call site.
        // Replace `pos.qtyToken * sellFraction` ad-hoc math with the verified-
        // remaining clamp from SellAmountAuthority + PartialSellSizer, and
        // capture pre-sell raw / decimals so PartialSellMismatchDetector can
        // verify the post-sell wallet drop.
        var preSellRawForAudit: java.math.BigInteger = java.math.BigInteger.ZERO
        var expectedConsumedRawForAudit: java.math.BigInteger = java.math.BigInteger.ZERO
        var decimalsForAudit: Int = 0
        val sellQty: Double = run {
            val resolution = try {
                com.lifecyclebot.engine.sell.SellAmountAuthority.resolve(ts.mint, wallet)
            } catch (_: Throwable) { null }
            val confirmed = resolution as? com.lifecyclebot.engine.sell.SellAmountAuthority.Resolution.Confirmed
            if (confirmed != null) {
                preSellRawForAudit = confirmed.rawAmount
                decimalsForAudit = confirmed.decimals
                val sized = com.lifecyclebot.engine.sell.PartialSellSizer.size(
                    intendedFraction = sellFraction,
                    verifiedRemainingRaw = confirmed.rawAmount,
                )
                if (sized != null) {
                    expectedConsumedRawForAudit = sized.rawAmount
                    val ui = java.math.BigDecimal(sized.rawAmount)
                        .movePointLeft(confirmed.decimals).toDouble()
                    onLog("📐 PartialSellSizer ${ts.symbol}: verifiedRaw=${confirmed.rawAmount} " +
                          "fraction=$sellFraction → rawAmount=${sized.rawAmount} (${"%.6f".format(ui)} ui) " +
                          "vs cached pos.qtyToken=${pos.qtyToken}", ts.mint)
                    ui
                } else {
                    onLog("⚠️ PartialSellSizer dust-rejected for ${ts.symbol} — falling back to cached qty.", ts.mint)
                    pos.qtyToken * sellFraction
                }
            } else {
                onLog("⚠️ SellAmountAuthority UNKNOWN/ZERO for ${ts.symbol} — falling back to cached qty (pos.qtyToken=${pos.qtyToken}).", ts.mint)
                pos.qtyToken * sellFraction
            }
        }
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

        val wrRecovTag = if (partialLevel == 0) " [${WrRecoveryPartial.statusTag()}]" else ""
        onLog("💰 $milestoneLabel: SELL ${(sellFraction*100).toInt()}% @ +${gainPct.toInt()}%$wrRecovTag " +
              "(trigger: +${triggerPct.toInt()}%) | ~${sellSol.fmt(4)} SOL", ts.mint)
        onNotify("💰 $milestoneLabel",
                 "${ts.symbol}  +${gainPct.toInt()}%  selling ${(sellFraction*100).toInt()}%",
                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        sounds?.playMilestone(gainPct)

        // V5.9.751b — route on position isPaper, not config. Previously a
        // live position with transient wallet=null silently booked a PAPER
        // partial sell here while the real tokens stayed on-chain. Defer
        // and retry next tick when wallet is null on a live position.
        if (!pos.isPaperPosition && wallet == null) {
            ErrorLogger.warn("Executor",
                "🚫 PARTIAL_SELL_DEFERRED: ${ts.symbol} — live position but wallet=null. Will retry next tick.")
            return false
        }
        if (pos.isPaperPosition) {
            ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
            val trade   = Trade("SELL", "paper", sellSol, actualPrice,
                              System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                              paperPnlSol, pct(pos.costSol * sellFraction, sellQty * actualPrice))
            recordTrade(ts, trade); security.recordTrade(trade)
            // V5.9.743 — wire 70/30 treasury siphon onto the AUTONOMOUS partial-
            // sell ladder. Previously only the manual requestPartialSell entry
            // point siphoned (V5.9.428 wired that one). checkPartialSell fires
            // at +200%/+500%/+2000% milestones every monitor tick and is the
            // PRIMARY meme partial path — leaving it un-wired meant Treasury
            // saw 0% of the realised profit on every TP rung until the
            // position fully closed. Operator spec: 'treasury accumulates 30%
            // of all memetrader profit'.
            // Treasury-tagged positions deposit 100% (cash-gen scalp pattern).
            if (paperPnlSol > 0.0) {
                try {
                    if (pos.isTreasuryPosition || pos.tradingMode == "TREASURY") {
                        com.lifecyclebot.engine.TreasuryManager.contributeFullyFromTreasuryScalp(
                            paperPnlSol, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                    } else {
                        com.lifecyclebot.engine.TreasuryManager.contributeFromMemeSell(
                            paperPnlSol, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                    }
                } catch (e: Exception) {
                    ErrorLogger.debug("Executor", "Treasury split error (checkPartial paper): ${e.message}")
                }
            }
            onLog("PAPER PARTIAL SELL ${(sellFraction*100).toInt()}% | " +
                  "${sellSol.fmt(4)} SOL | pnl ${paperPnlSol.fmt(4)} SOL", ts.mint)
        } else {
            // V5.9.751b — wallet guaranteed non-null by PARTIAL_SELL_DEFERRED guard above.
            // Shadow the nullable param with a non-null binding so the rest of
            // this else branch keeps its previous unchanged code (V5.9.495 etc).
            @Suppress("NAME_SHADOWING")
            val wallet: SolanaWallet = wallet!!
            if (!acquirePartialSellLock(ts.mint)) {
                onLog("⏳ Partial sell already in-flight for ${ts.symbol} — skipping duplicate", ts.mint)
                return true
            }
            try {
                if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                        c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
                    onLog("🛑 Keypair check failed — aborting partial sell", ts.mint)
                    releasePartialSellLock(ts.mint)
                    return true
                }
                val sellUnits = resolveSellUnits(ts, sellQty, wallet = wallet)
                val sellSlippage = (c.slippageBps * 2).coerceAtMost(500)

                // V5.9.495 — PUMP-FIRST routing for partial TPs.
                // Try PumpPortal direct FIRST. If it lands, we use the pre-trade
                // mark-to-market estimate as solBack proxy (PumpPortal does not
                // return outAmount; we'd need a balance-delta read which adds
                // ~2s and isn't critical for partial PnL booking — the same
                // approximation `quote.outAmount` already gives is just a
                // forecast, not a settlement).
                val partialJito = c.jitoEnabled
                val partialTip = c.jitoTipLamports
                val pumpSig = tryPumpPortalSell(
                    ts = ts,
                    wallet = wallet,
                    tokenUnits = sellUnits,
                    slipPct = 30,
                    priorityFeeSol = 0.0001,
                    useJito = partialJito,
                    jitoTipLamports = partialTip,
                    sellTradeKey = LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis()),
                    traderTag = "MEME",
                    labelTag = "PARTIAL-${(sellFraction*100).toInt()}%",
                )

                val sig: String
                val solBack: Double
                val feeSol: Double
                val netPnl: Double
                val livePnl: Double
                val liveScore: Double
                if (pumpSig != null) {
                    sig = pumpSig
                    // Estimate solBack from current mark + sold quantity.
                    solBack = sellQty * actualPrice
                    livePnl = solBack - pos.costSol * sellFraction
                    liveScore = pct(pos.costSol * sellFraction, solBack)
                    val pair = slippageGuard.calcNetPnl(livePnl, pos.costSol * sellFraction)
                    netPnl = pair.first
                    feeSol = pair.second
                } else {
                    // Fallback: full Jupiter Ultra → Metis ladder (single shot
                    // here; Jupiter dynamicSlippage handles in-route escalation).
                    val quote     = getQuoteWithSlippageGuard(
                        ts.mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false)
                    val txResult  = buildTxWithRetry(quote, wallet.publicKeyB58)
                    security.enforceSignDelay()

                    val useJito = c.jitoEnabled && !quote.isUltra
                    val jitoTip = c.jitoTipLamports
                    val ultraReqId = if (quote.isUltra) txResult.requestId else null
                    sig = try {
                        wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                    } catch (jupEx: Exception) {
                        // Jupiter exhausted too — final PumpPortal retry as last resort.
                        val rescue = tryPumpPortalSell(
                            ts = ts,
                            wallet = wallet,
                            tokenUnits = sellUnits,
                            slipPct = 50,
                            priorityFeeSol = 0.0002,
                            useJito = partialJito,
                            jitoTipLamports = partialTip,
                            sellTradeKey = LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis()),
                            traderTag = "MEME",
                            labelTag = "PARTIAL-RESCUE-${(sellFraction*100).toInt()}%",
                        )
                        rescue ?: throw jupEx
                    }
                    // V5.9.495k — PHANTOM-aware solBack: zero out fake gains
                    // when the rescue helper returned a PHANTOM_* sentinel sig.
                    solBack = if (sig.startsWith("PHANTOM_")) 0.0 else quote.outAmount / 1_000_000_000.0
                    livePnl = solBack - pos.costSol * sellFraction
                    liveScore = pct(pos.costSol * sellFraction, solBack)
                    val pair = slippageGuard.calcNetPnl(livePnl, pos.costSol * sellFraction)
                    netPnl = pair.first
                    feeSol = pair.second
                }
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
                releasePartialSellLock(ts.mint)
                // V5.9.495z47 — operator P0 (forensics 0508_143519):
                // Verify post-sell wallet drop ≤ expected. If actual > expected
                // by >5%, lock the mint via SellAmountAuditor (executor.liveSell
                // early-returns on locked mints). Skip on PHANTOM_ sentinel
                // sigs and fail-soft so we never block the success path.
                if (!sig.startsWith("PHANTOM_")) {
                    try {
                        if (decimalsForAudit > 0 &&
                            expectedConsumedRawForAudit.signum() > 0 &&
                            preSellRawForAudit.signum() > 0) {
                            com.lifecyclebot.engine.sell.PartialSellMismatchDetector.verifyAndMaybeLock(
                                mint = ts.mint,
                                symbol = ts.symbol,
                                decimals = decimalsForAudit,
                                expectedConsumedRaw = expectedConsumedRawForAudit,
                                preSellWalletRaw = preSellRawForAudit,
                                wallet = wallet,
                            )
                        }
                    } catch (_: Throwable) { /* fail-soft */ }
                }
                onLog("LIVE PARTIAL SELL ${(sellFraction*100).toInt()}% @ +${gainPct.toInt()}% | " +
                      "${solBack.fmt(4)}◎ | sig=${sig.take(16)}…", ts.mint)
                onNotify("💰 Live Partial Sell",
                    "${ts.symbol}  +${gainPct.toInt()}%  sold ${(sellFraction*100).toInt()}%",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                // V5.9.743 — wire 70/30 treasury siphon onto LIVE autonomous
                // partial-sell ladder. Use netPnl (post-fee) — same figure
                // liveSell uses. Treasury-tagged positions deposit 100%.
                // Bookkeeping only in live mode (the SOL has already returned
                // from Jupiter at full face value); a separate on-chain
                // trading→treasury transfer is fired by
                // TreasuryManager.triggerOnChainTransferIfLive.
                if (netPnl > 0.0) {
                    try {
                        if (pos.isTreasuryPosition || pos.tradingMode == "TREASURY") {
                            com.lifecyclebot.engine.TreasuryManager.contributeFullyFromTreasuryScalp(
                                netPnl, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                        } else {
                            com.lifecyclebot.engine.TreasuryManager.contributeFromMemeSell(
                                netPnl, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                        }
                    } catch (e: Exception) {
                        ErrorLogger.debug("Executor", "Treasury split error (checkPartial live): ${e.message}")
                    }
                }
            } catch (e: Exception) {
                releasePartialSellLock(ts.mint)
                onLog("Live partial sell FAILED: ${security.sanitiseForLog(e.message?:"err")} " +
                      "— position NOT updated", ts.mint)
            }
        }
        return true
    }

    private val milestonesHit      = mutableMapOf<String, MutableSet<Int>>()
    // V5.9.753 — Emergent ticket item #5: sell-lock TTL. Previously a stuck
    // pendingVerify sell could hold the in-flight lock forever, spamming
    // SELL_BLOCKED_ALREADY_IN_PROGRESS every monitor tick. Now backed by a
    // timestamped map; cleared automatically after PARTIAL_SELL_LOCK_TTL_MS.
    private val PARTIAL_SELL_LOCK_TTL_MS = 90_000L
    private val partialSellInFlight = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun acquirePartialSellLock(mint: String): Boolean {
        val now = System.currentTimeMillis()
        val existing = partialSellInFlight[mint]
        if (existing != null && (now - existing) < PARTIAL_SELL_LOCK_TTL_MS) {
            return false
        }
        if (existing != null) {
            ErrorLogger.warn("Executor",
                "🔓 PARTIAL_SELL_LOCK_EXPIRED mint=${mint.take(10)}… ageMs=${now - existing} — releasing stale lock")
        }
        partialSellInFlight[mint] = now
        return true
    }
    private fun releasePartialSellLock(mint: String) {
        partialSellInFlight.remove(mint)
    }
    // V5.9.756 — Emergent CRITICAL ticket item #3: sell-lock deadlock.
    // Previously `sellInProgress` was `ConcurrentHashMap<String, Boolean>`
    // with no timestamp. If a sell got stuck in pendingVerify or RPC empty-map,
    // the lock held forever — every future sell triggered
    // SELL_BLOCKED_ALREADY_IN_PROGRESS and the position became unsellable
    // (SPARTA/Thucydides/CHING evidence). Now a timestamped map with a 20 s
    // stale-watchdog and `acquire`/`release` helpers. Forensics fire
    // SELL_LOCK_STALE_FORCE_RELEASED on auto-recovery.
    //
    // The full operator-spec state machine (IDLE / SELLING / VERIFYING /
    // RETRY_SCHEDULED / CONFIRMED_SOLD / FAILED_RETRYABLE / FAILED_FINAL /
    // MANUAL_ATTENTION) is the right long-term shape but a multi-day refactor.
    // This patch closes the DEADLOCK symptom today.
    private val SELL_LOCK_STALE_MS = 20_000L
    private val sellInProgress = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun acquireSellLock(mint: String): Boolean {
        val now = System.currentTimeMillis()
        val existing = sellInProgress[mint]
        if (existing != null && (now - existing) < SELL_LOCK_STALE_MS) {
            return false
        }
        if (existing != null) {
            ErrorLogger.warn("Executor",
                "🔓 SELL_LOCK_STALE_FORCE_RELEASED mint=${mint.take(10)}… ageMs=${now - existing} — auto-recover, allowing new sell")
            try {
                val tradeKey = LiveTradeLogStore.keyFor(mint, now)
                LiveTradeLogStore.log(
                    tradeKey, mint, mint.take(6), "SELL",
                    LiveTradeLogStore.Phase.INFO,
                    "SELL_LOCK_STALE_FORCE_RELEASED ageMs=${now - existing}",
                    traderTag = "MEME",
                )
                // V5.9.764 — EMERGENT item D forensic counter.
                ForensicLogger.lifecycle(
                    "SELL_LOCK_STALE_FORCE_RELEASED",
                    "mint=${mint.take(10)} ageMs=${now - existing}",
                )
            } catch (_: Throwable) {}
        }
        sellInProgress[mint] = now
        try {
            // V5.9.764 — EMERGENT item D forensic counter.
            ForensicLogger.lifecycle(
                "SELL_LOCK_SET",
                "mint=${mint.take(10)} ts=$now",
            )
        } catch (_: Throwable) {}
        return true
    }
    private fun releaseSellLock(mint: String) {
        val removed = sellInProgress.remove(mint)
        if (removed != null) {
            try {
                // V5.9.764 — EMERGENT item D forensic counter.
                ForensicLogger.lifecycle(
                    "SELL_LOCK_RELEASED",
                    "mint=${mint.take(10)} heldMs=${System.currentTimeMillis() - removed}",
                )
            } catch (_: Throwable) {}
        }
    }
    /** Operator/test diagnostic — number of currently held sell locks. */
    fun sellLockHeldCount(): Int = sellInProgress.size

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
        
        // V5.9.495z15 — operator-mandated unchoke. Pre-fix the trailing
        // stop activated after 60s held OR 5% gain. The 60s clause was
        // the silent killer: every trade that went -1% in the first
        // 90 seconds (the vast majority) tripped it the moment the
        // smartFloor fell just below the current price, producing the
        // "trailing_stop pnl=-1%" exits seen across hundreds of trades.
        // Trailing stops must protect PROFIT, never harvest losses.
        // Now: only activate trailing after meaningful unrealized gain.
        // Bootstrap: 5% gain. Mature: 3% (more aggressive once profitable).
        val trailingActivationPct = run {
            val mature = try {
                com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() >= 0.6
            } catch (_: Throwable) { false }
            if (mature) 3.0 else 5.0
        }
        val trailingStopActive = gainPct >= trailingActivationPct
        
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
                // V5.9.676 — surface the actual mode + held time + threshold
                // into the sell reason. Operator's V5.9.675 dump showed 10
                // positions force-sold on session start with reason
                // mode_maxhold_paused but no visibility into WHY the bot
                // was in PAUSED mode (default UTC window is 04-06 and the
                // local clock was 15:24 = ~03 UTC in NZST, NOT in window).
                // Likely modeConfig was stale from before restart. Adding
                // utcHour + threshold lets us see the actual trigger.
                val _utcHour = try {
                    java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                        .get(java.util.Calendar.HOUR_OF_DAY)
                } catch (_: Throwable) { -1 }
                val _modeNameLc = modeConfig.mode.name.lowercase()
                val _reason = "mode_maxhold_$_modeNameLc held=${_held.toInt()}m max=${(modeConfig.maxHoldMins * _tf).toInt()}m utc=${_utcHour}h"
                ErrorLogger.info(
                    "Executor",
                    "🚪 mode_maxhold: ${ts.symbol} mode=${modeConfig.mode.name} held=${_held.toInt()}min > ${(modeConfig.maxHoldMins * _tf).toInt()}min utc=${_utcHour}:00"
                )
                doSell(ts, _reason, wallet, walletSol); return
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
            // V5.9.718 — derive lane for phase-aware size multiplier
            val _laneMode = try { ModeRouter.classify(ts).tradeType.name } catch (_: Throwable) { ts.source }
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
                laneMode = _laneMode,
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
                    // V5.9.676 — same forensic surface as the primary maxhold
                    // gate above. See comment block at line ~3395 for context.
                    val _utcHour2 = try {
                        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                            .get(java.util.Calendar.HOUR_OF_DAY)
                    } catch (_: Throwable) { -1 }
                    val _reason2 = "mode_maxhold_${modeConfig.mode.name.lowercase()} held=${held.toInt()}m max=${(modeConfig.maxHoldMins * tf).toInt()}m utc=${_utcHour2}h"
                    ErrorLogger.info(
                        "Executor",
                        "🚪 mode_maxhold(v3): ${ts.symbol} mode=${modeConfig.mode.name} held=${held.toInt()}min > ${(modeConfig.maxHoldMins * tf).toInt()}min utc=${_utcHour2}:00"
                    )
                    doSell(ts, _reason2, wallet, walletSol, identity)
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

            // V5.9.495z33 — executive hook for EntryWaitOverrideGate.
            // Operator directive: "we shouldn't block at 39% confidence —
            // we should wait to see if it changes." When the legacy path
            // would reject and the gate verdict is FDG_DEFER_ENTRY_WAIT,
            // explicitly keep the candidate alive in the watchlist with
            // its TTL refreshed. The V3 engine's own Watch semantic still
            // applies — this just guarantees the rejection branch never
            // silently purges a deferred candidate.
            try {
                val isWait = reason.contains("WAIT", ignoreCase = true) ||
                             reason.contains("entry_wait", ignoreCase = true)
                val isHighRisk = reason.contains("HIGH", ignoreCase = true) ||
                                 reason.contains("risk_high", ignoreCase = true)
                val gate = com.lifecyclebot.engine.EntryWaitOverrideGate.evaluate(
                    entryWait = isWait,
                    riskHigh = isHighRisk,
                    moonshotOverride = false,
                    confidence = decision.aiConfidence.toInt(),
                )
                if (gate.verdict == com.lifecyclebot.engine.EntryWaitOverrideGate.Verdict.FDG_DEFER_ENTRY_WAIT) {
                    com.lifecyclebot.engine.WatchlistTtlPolicy.mark(
                        ts.symbol, decision.aiConfidence.toInt()
                    )
                    com.lifecyclebot.engine.DeferActivityTracker.record(
                        com.lifecyclebot.engine.DeferActivityTracker.Kind.DEFERRED,
                        ts.symbol,
                    )
                    ErrorLogger.info("Executor",
                        "🛡 ${ts.symbol}: ${gate.reason} — kept in watchlist (TTL refreshed)")
                }
            } catch (_: Throwable) { /* best-effort */ }
        }
        
        val isPaper = cfg().paperMode
        // V5.9.495z32 — publish executor mode + mark hot tick so the
        // RuntimeModeAuthority sees it and background lanes yield.
        com.lifecyclebot.engine.RuntimeModeAuthority.publishExecutorMode(isPaper)
        com.lifecyclebot.engine.HotPathLaneGate.markHotTick()
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

        // V5.9.751b — refuse paper fallback when config is live (see A1 note).
        if (c.paperMode) {
            paperTopUp(ts, size)
        } else if (wallet == null) {
            ErrorLogger.error("Executor",
                "🚫 LIVE_TOPUP_REFUSED: ${ts.symbol} — config is LIVE but wallet is NULL. Refusing paperTopUp fallback.")
            onLog("🚫 Live top-up blocked: ${ts.symbol} — wallet disconnected.", ts.mint)
            return
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

        // V5.9.756 — Emergent CRITICAL ticket. liveTopUp was the bypass route:
        // SPARTA / Thucydides / CHING landed live via this path because it
        // routes through tryPumpPortalBuy directly without a safety re-check.
        // A top-up is a fresh live tx on the same mint — it MUST pass the
        // same admission gate as a fresh buy. Same mint can degrade between
        // entry and top-up (LP pulled, dev dumps, freeze authority enabled).
        run {
            val decision = com.lifecyclebot.engine.sell.LiveBuyAdmissionGate.requireApprovedLiveBuy(
                ts = ts,
                callSite = "liveTopUp",
                onLog = onLog,
                onNotify = onNotify,
            )
            if (decision is com.lifecyclebot.engine.sell.LiveBuyAdmissionGate.Decision.Blocked) {
                onLog("🛡 LIVE TOP-UP BLOCKED [${ts.symbol}]: ${'$'}{decision.reasonCode}", ts.mint)
                return
            }
        }

        val lamports = (sol * 1_000_000_000L).toLong()
        val tradeKey = LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis())
        val pos = ts.position
        try {
            // V5.9.603 — live top-ups must be wallet-delta verified before
            // local qty/cost is inflated. Otherwise a confirmed tx with no
            // token arrival creates a fake larger bag and corrupts PnL/exits.
            val preTopUpQty = try { wallet.getTokenAccountsWithDecimals()[ts.mint]?.first ?: pos.qtyToken } catch (_: Throwable) { pos.qtyToken }
            // V5.9.495 — PUMP-FIRST routing for top-ups (add-to-position).
            // Same universal-auto routing the entry/exit ladder uses.
            val ppResult = tryPumpPortalBuy(
                ts = ts,
                wallet = wallet,
                solAmount = sol,
                slipPct = 10,
                priorityFeeSol = 0.0001,
                useJito = c.jitoEnabled,
                jitoTipLamports = c.jitoTipLamports,
                tradeKey = tradeKey,
                traderTag = "MEME",
            )

            val price  = getActualPrice(ts)
            val sig: String
            val newQty: Double
            if (ppResult != null) {
                sig = ppResult.first
                newQty = ppResult.second
                onLog("🔺 LIVE TOP-UP (PUMP-FIRST) #${pos.topUpCount + 1}: ${ts.symbol} | sig=${sig.take(16)}…", ts.mint)
            } else {
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
                sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                newQty = rawTokenAmountToUiAmount(ts, quote.outAmount, solAmount = sol, priceUsd = price)
            }

            val verifiedDelta = try {
                var delta = 0.0
                for (attempt in 0 until 5) {
                    if (attempt > 0) Thread.sleep(2_000)
                    val cur = wallet.getTokenAccountsWithDecimals()[ts.mint]?.first ?: 0.0
                    delta = (cur - preTopUpQty).coerceAtLeast(0.0)
                    if (delta > 0.0) break
                }
                delta
            } catch (_: Throwable) { 0.0 }

            val effectiveNewQty = if (verifiedDelta > 0.0) verifiedDelta else 0.0
            if (effectiveNewQty <= 0.0) {
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_FAILED,
                    "⚠️ TOP-UP TX confirmed but wallet token delta not verified — local position NOT inflated",
                    sig = sig, solAmount = sol, traderTag = "MEME",
                )
                onLog("⚠️ LIVE TOP-UP not credited: tx confirmed but token delta not verified", ts.mint)
                return
            }

            ts.position = pos.copy(
                qtyToken       = pos.qtyToken + effectiveNewQty,
                entryPrice     = (pos.costSol + sol) / (pos.qtyToken + effectiveNewQty),
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

        // V5.9.485 — Final pre-trade gate via EmergentLlmClient (Claude
        // Sonnet 4.5). OPT-IN: only fires when operator pasted a personal
        // sk-ant-… key into BotConfig.geminiApiKey; otherwise this whole
        // block is a no-op (`isEnabled() == false` → "PROCEED"). Calls have
        // an 8s read timeout so a slow LLM response cannot stall the bot.
        try {
            if (com.lifecyclebot.network.EmergentLlmClient.isEnabled()) {
                val recentPnl = try {
                    val s = com.lifecyclebot.engine.TradeHistoryStore.getStats()
                    // Use 24h win-rate as the "recent performance %" the LLM
                    // sees — directly comparable across sessions, and the
                    // validator prompt uses it as a sanity check on whether
                    // we're on a hot streak vs a losing run.
                    s.winRate24h.toDouble()
                } catch (_: Exception) { 50.0 }
                val verdict = com.lifecyclebot.network.EmergentLlmClient.validateTradeSignal(
                    symbol      = ts.symbol,
                    side        = "BUY",
                    confidence  = quality.hashCode().rem(100),
                    score       = score.toInt(),
                    traderTag   = "MEME/${ts.source}",
                    sizeSol     = sol,
                    recentPnlPct= recentPnl,
                )
                when {
                    verdict.startsWith("BLOCK", ignoreCase = true) -> {
                        onLog("🧠 LLM BLOCK: ${ts.symbol} | ${verdict.removePrefix("BLOCK:").trim().take(60)}", tradeId.mint)
                        return
                    }
                    verdict.startsWith("CAUTION", ignoreCase = true) -> {
                        onLog("⚠️ LLM CAUTION: ${ts.symbol} | ${verdict.removePrefix("CAUTION:").trim().take(60)}", tradeId.mint)
                        // Fall through — caution is informational only.
                    }
                    // PROCEED or unrecognised → allow.
                }
            }
        } catch (_: Throwable) { /* fail open */ }

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

        // V5.9.642: spine log uses a separate val so the compiler keeps
        // its smart cast on `wallet` inside the else branch (non-null guaranteed).
        // V5.9.751b — Hard rule: if config is LIVE, never silently fall through
        // to paperBuy when the wallet is missing. Operator forensics showed
        // paper trades being booked during live runs because a transient
        // wallet=null at this call site silently flipped the route.
        // Mirror the V5.9.738 dipHunterBuy pattern: refuse cleanly.
        val isPaperMode = cfg().paperMode
        val spineRoute = when {
            isPaperMode -> "PAPER"
            wallet == null -> "LIVE_REFUSED_NO_WALLET"
            else -> "LIVE_PRECHECK"
        }
        ErrorLogger.info("Executor", "🧬 MEME_SPINE DO_BUY_ROUTE ${ts.symbol} | route=$spineRoute | cfgPaper=$isPaperMode | walletLoaded=${wallet != null} | size=${effSol.fmt(4)} | walletSol=${walletSol.fmt(4)}")
        if (isPaperMode) {
            paperBuy(ts, effSol, score, tradeId, quality, skipGraduated, wallet, walletSol)
        } else if (wallet == null) {
            ErrorLogger.error("Executor",
                "🚫 MEME_SPINE LIVE_BUY_REFUSED: ${ts.symbol} — config is LIVE but wallet is NULL. Refusing to fall back to paperBuy.")
            onLog("🚫 Live buy blocked: ${ts.symbol} — wallet disconnected. Reconnect to resume live trading.", tradeId.mint)
            onNotify("🚫 Wallet Disconnected",
                "Cannot execute live buy on ${ts.symbol} — wallet is not connected. Paper-trade fallback refused.",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            return
        } else {
            // V5.9.643 — capture non-null wallet here so Kotlin smart-cast
            // survives through the when(guard) branches without losing nullability
            // info (the compiler loses the smart cast after seeing wallet passed
            // to runShadowPaperBuy which accepts SolanaWallet?, re-widening it).
            val safeWallet = wallet  // wallet is guaranteed non-null in this branch
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
                    ErrorLogger.info("Executor", "🧬 MEME_SPINE LIVE_PRECHECK_BLOCK ${ts.symbol} | reason=${guard.reason} | fatal=${guard.fatal}")
                    onLog("🚫 Buy blocked: ${guard.reason}", tradeId.mint)
                    sounds?.playBlockSound()
                    if (guard.fatal) onNotify("🛑 Bot Halted", guard.reason, com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    
                    if (cfg().shadowPaperEnabled) {
                        runShadowPaperBuy(ts, effSol, score, quality, "blocked:${guard.reason.take(20)}", safeWallet, walletSol)
                    }
                    return
                }
                is GuardResult.Allow -> {
                    // V5.9.9: Cross-trader exposure check
                    if (!WalletPositionLock.canOpen("Meme", effSol, walletSol)) {
                        onLog("🔒 Exposure cap: ${ts.symbol} blocked (wallet ${WalletPositionLock.getExposurePct(walletSol).toInt()}% deployed)", tradeId.mint)
                        if (cfg().shadowPaperEnabled) {
                            runShadowPaperBuy(ts, effSol, score, quality, "exposure_cap", safeWallet, walletSol)
                        }
                        return
                    }
                    ErrorLogger.info("Executor", "🧬 MEME_SPINE LIVE_PRECHECK_ALLOW ${ts.symbol} | size=${effSol.fmt(4)} | wallet=${walletSol.fmt(4)}")
                    liveBuy(ts, effSol, score, safeWallet, walletSol, tradeId, quality, skipGraduated)
                    WalletPositionLock.recordOpen("Meme", effSol)
                    
                    if (cfg().shadowPaperEnabled) {
                        runShadowPaperBuy(ts, effSol, score, quality, "parallel", safeWallet, walletSol)
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
        ErrorLogger.info("Executor", "🧬 MEME_SPINE V3_BUY_ROUTE ${ts.symbol} | route=${if (isPaper) "PAPER" else "LIVE_JUPITER"} | cfgPaper=$isPaper | walletLoaded=${wallet != null} | size=${sizeSol.fmt(4)}")
        
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

    // ═══════════════════════════════════════════════════════════════════════
    // V5.9.738 — PAPER-MODE LEAK FIX
    //
    // dipHunterBuy / sniperSell are public router helpers added to plug two
    // historical bugs where the BotService call sites invoked paperBuy() /
    // paperSell() UNCONDITIONALLY, ignoring cfg().paperMode entirely. In
    // live mode this caused: (a) paper positions opened against live wallet,
    // (b) operator log saying "LIVE" while the position was stamped
    // isPaperPosition=true, (c) all subsequent sells running through the
    // paper branch — operator wallet never moved.
    //
    // Operator report (Bernard Griffin, messenger 18:43): "its live but
    // making paper trades. nothing should be trading paper if the bot is
    // launched in live."
    //
    // These helpers enforce the same paper-vs-live routing v3Buy already
    // does for the MEME_SPINE path. Single source of truth: cfg().paperMode.
    // ═══════════════════════════════════════════════════════════════════════

    fun dipHunterBuy(
        ts: TokenState,
        sizeSol: Double,
        score: Double,
        wallet: SolanaWallet?,
        walletSol: Double,
        identity: TradeIdentity? = null,
    ) {
        val isPaper = cfg().paperMode
        val id = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        ErrorLogger.info("Executor",
            "📉🎯 DIP_BUY_ROUTE ${ts.symbol} | route=${if (isPaper) "PAPER" else "LIVE_JUPITER"} | " +
            "cfgPaper=$isPaper | walletLoaded=${wallet != null} | size=${sizeSol.fmt(4)}")
        if (isPaper) {
            paperBuy(
                ts = ts, sol = sizeSol, score = score, identity = id,
                quality = "DIP_HUNTER", skipGraduated = true,
                wallet = wallet, walletSol = walletSol,
                layerTag = "DIP_HUNTER", layerTagEmoji = "📉",
            )
        } else {
            if (wallet == null) {
                ErrorLogger.error("Executor",
                    "📉🎯 DIP ${ts.symbol} | LIVE_BUY_FAILED | no wallet — refusing to fall back to paperBuy")
                return
            }
            liveBuy(
                ts = ts, sol = sizeSol, score = score,
                wallet = wallet, walletSol = walletSol,
                identity = id, quality = "DIP_HUNTER", skipGraduated = true,
                layerTag = "DIP_HUNTER", layerTagEmoji = "📉",
            )
        }
    }

    fun sniperSell(
        ts: TokenState,
        reason: String,
        wallet: SolanaWallet?,
        walletSol: Double,
    ): SellResult {
        val isPaper = cfg().paperMode
        ErrorLogger.info("Executor",
            "🎯 SNIPER_SELL_ROUTE ${ts.symbol} | route=${if (isPaper) "PAPER" else "LIVE_JUPITER"} | " +
            "cfgPaper=$isPaper | reason=$reason")
        return if (isPaper) {
            paperSell(ts, reason)
        } else {
            // Live route: requestSell branches internally on isPaperPosition,
            // so live-opened positions will correctly fire a Jupiter swap.
            // If the position was somehow opened in paper (e.g. mode flipped
            // mid-position), requestSell still routes via the paper branch
            // honoring isPaperPosition — no spurious live swap on a paper bag.
            requestSell(ts, reason, wallet, walletSol)
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

        // V5.9.751 — Base44 ticket item #3: USDC / WSOL / USDT / mSOL / etc.
        // must NEVER be entered as a meme-target buy. Forensic report
        // showed mint=EPjFWdd5… (USDC) flowing into LIVE_BUY_START with
        // traderTag=MEME. Hard abort BEFORE any tx work happens.
        if (com.lifecyclebot.engine.execution.MintIntegrityGate.isSystemOrStablecoinMint(ts.mint)) {
            ErrorLogger.warn("Executor",
                "[EXECUTION/WRONG_TARGET_MINT] Live MEME buy blocked: ${ts.symbol} mint=${ts.mint.take(12)}… is a stablecoin/system mint")
            try {
                val tradeKey = LiveTradeLogStore.keyFor(ts.mint, System.currentTimeMillis())
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_FAILED,
                    "WRONG_TARGET_MINT — ${ts.symbol} is a stablecoin/bridge/system mint, refusing buy",
                    traderTag = "MEME",
                )
            } catch (_: Throwable) {}
            PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "WRONG_TARGET_MINT")
            return
        }

        if (score < 0 || score.isNaN()) {
            ErrorLogger.warn("Executor", "[EXECUTION/INVALID] Live buy skipped: invalid score $score for ${ts.symbol}")
            return
        }
        
        // V5.9.756 — extracted to LiveBuyAdmissionGate (Emergent CRITICAL ticket).
        // The V5.9.753 inline gate was only wired into liveBuy. liveTopUp
        // (which routes through tryPumpPortalBuy at line 4523) bypassed it,
        // letting SPARTA / Thucydides / CHING land live without a safety
        // re-check. All live-buy paths now share one admission gate.
        run {
            val decision = com.lifecyclebot.engine.sell.LiveBuyAdmissionGate.requireApprovedLiveBuy(
                ts = ts,
                callSite = "liveBuy.main",
                onLog = onLog,
                onNotify = onNotify,
            )
            if (decision is com.lifecyclebot.engine.sell.LiveBuyAdmissionGate.Decision.Blocked) return
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

        // V5.9.611 — LiveExecutionGate is now settlement-pressure only.
        // Daily quota / concurrent ceiling / min spacing are NOT buy blockers:
        // paper is training for live, so live must express the same fluid gates.
        // V5.9.495z52 — use liveMemeOpenCount() (filters out auto-imported
        // wallet dust ATAs, cross-lane CryptoUniverse / Markets positions,
        // and dust-balance residuals) instead of openCount() which counted
        // all 34 phantom records and falsely tripped concurrent_cap=12.
        val openLive = try { TokenLifecycleTracker.liveMemeOpenCount() } catch (_: Throwable) { 0 }
        when (val decision = LiveExecutionGate.tryAcquireBuy(openLive)) {
            is LiveExecutionGate.Decision.Blocked -> {
                ErrorLogger.warn("Executor",
                    "🚦 GATE_BLOCK ${ts.symbol} ${decision.code} | ${decision.reason}")
                PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "GATE_${decision.code}")
                return
            }
            LiveExecutionGate.Decision.Allowed -> { /* proceed */ }
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
            // V5.9.495 — PUMP-FIRST routing for live buys.
            // PumpPortal Lightning's pool="auto" routes through pump.fun
            // bonding curve, PumpSwap AMM, AND Raydium pools. We try it
            // FIRST for every buy. If it 400s (un-routable mint, deep-
            // graduated to Orca/Meteora only) we fall through to the
            // Jupiter Ultra → Metis ladder.
            val pumpVenue = if (com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(ts.mint))
                "pump.fun" else "universal-auto"

            val pumpFirstResult: Pair<String, Double>? = tryPumpPortalBuy(
                ts = ts,
                wallet = wallet,
                solAmount = effectiveSol,
                slipPct = 10,
                priorityFeeSol = 0.0001,
                useJito = c.jitoEnabled,
                jitoTipLamports = c.jitoTipLamports,
                tradeKey = tradeKey,
                traderTag = "MEME",
            )

            // V5.9.495 — Jupiter ladder runs ONLY if PUMP-FIRST failed.
            // V5.9.261 — slippage escalation for buys (was: single c.slippageBps call).
            // Memes/low-liq launches phantom out at the default 1% slippage —
            // Jupiter swap reverts internally on price impact, SOL leaves the wallet
            // (TX fees), tokens never arrive. Now we escalate 200→350→500 bps just
            // like liveSell does, so memes actually fill instead of phantoming.
            val buyBaseSlippage = c.slippageBps.coerceAtLeast(200)
            val slippageLadder = listOf(buyBaseSlippage, 350, 500).distinct()
            var quote: com.lifecyclebot.network.SwapQuote? = null
            var lastQuoteError: Exception? = null
            var txResult: com.lifecyclebot.network.SwapTxResult? = null
            var useJito = false
            var jitoTip = 0L
            if (pumpFirstResult == null) {
            // V5.9.495d — explicit Ultra-first signal for forensics.
            // getQuoteWithSlippageGuard internally tries Jupiter Ultra v2
            // first then falls back to v6 Metis on RFQ rejection — log
            // that intent so the operator can see we're escalating from
            // PUMP-FIRST → JUPITER ULTRA, not silently jumping straight
            // to Metis. Operator: "should always revert to ultra".
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.INFO,
                "↩ FALLBACK → Jupiter Ultra (v2) primary, v6 Metis secondary | ladder=${slippageLadder.joinToString("/")}bps",
                solAmount = sol, traderTag = "MEME",
            )
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

            val txResultLocal = buildTxWithRetry(quote, wallet.publicKeyB58)
            txResult = txResultLocal
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_TX_BUILT,
                "Tx built | router=${txResultLocal.router} | rfq=${txResultLocal.isRfqRoute}",
                traderTag = "MEME",
            )

            val simErr = jupiter.simulateSwap(txResultLocal.txBase64, wallet.rpcUrl)
            if (simErr != null) {
                if (simErr.startsWith("RPC error:") || simErr.startsWith("Simulate failed: null")) {
                    // V5.9.753 — Emergent ticket item #2. PREVIOUSLY this branch
                    // proceeded with the broadcast on RPC simulation failure (the
                    // "Sim RPC unavailable — proceeding" path). Operator forensics
                    // showed real funds being committed on the back of a missing
                    // preflight. Live mode must NEVER skip simulation — abort.
                    onLog("🛑 LIVE BUY ABORTED [${ts.symbol}]: simulation RPC unavailable ($simErr)", ts.mint)
                    ErrorLogger.warn("Executor",
                        "[EXECUTION/LIVE_BUY_BLOCKED_SIM_UNAVAILABLE] ${ts.symbol}: $simErr — refusing to broadcast without preflight")
                    LiveTradeLogStore.log(
                        tradeKey, ts.mint, ts.symbol, "BUY",
                        LiveTradeLogStore.Phase.BUY_SIM_FAIL,
                        "LIVE_BUY_BLOCKED_SIM_UNAVAILABLE — ${simErr.take(80)}",
                        traderTag = "MEME",
                    )
                    onNotify("🛑 Live buy aborted",
                        "${ts.symbol} — simulation RPC unavailable",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    PipelineTracer.executorFailed(ts.symbol, ts.mint, "LIVE", "LIVE_BUY_BLOCKED_SIM_UNAVAILABLE")
                    throw Exception("LIVE_BUY_BLOCKED_SIM_UNAVAILABLE: $simErr")
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

            useJito = c.jitoEnabled && !quote.isUltra
            jitoTip = c.jitoTipLamports
            
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
            }  // end if (pumpFirstResult == null) — Jupiter pipeline only runs when PUMP-FIRST didn't land
            
            val sig: String
            val qty: Double
            val priceImpactPct: Double
            val routerLabel: String
            if (pumpFirstResult != null) {
                // V5.9.495 — PUMP-FIRST landed; skip the entire Jupiter pipeline.
                sig = pumpFirstResult.first
                qty = pumpFirstResult.second
                // V5.9.602 — Pump-first gets the same lifecycle ledger as
                // Jupiter: pending intent, confirmed tx, then HELD only after
                // the verifier proves tokens landed.
                try {
                    TokenLifecycleTracker.onBuyPending(ts.mint, ts.symbol, pumpVenue, effectiveSol)
                    TokenLifecycleTracker.onBuyConfirmed(ts.mint, sig)
                } catch (_: Throwable) {}
                priceImpactPct = 0.0  // PumpPortal does not surface impact
                routerLabel = "PUMP_DIRECT [$pumpVenue]"
                onLog("LIVE BUY (PUMP-FIRST): ${ts.symbol} | sig=${sig.take(16)}…", tradeId.mint)
            } else {
                val q = quote!!
                val tx = txResult!!
                val ultraReqId = if (q.isUltra) tx.requestId else null
                sig = wallet.signSendAndConfirm(tx.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, tx.isRfqRoute)
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_CONFIRMED,
                    "✅ Tx confirmed on-chain — awaiting token-arrival verification",
                    sig = sig, traderTag = "MEME",
                )
                // V5.9.495z28 (operator spec item 3): record into authoritative
                // lifecycle ledger keyed by mint. onBuyPending is called BEFORE
                // broadcast (registers the intent); onBuyConfirmed flips it
                // once the tx signature is on-chain. The wallet reconciler
                // then verifies token arrival → onTokenLanded → HELD.
                try {
                    TokenLifecycleTracker.onBuyPending(ts.mint, ts.symbol, "pump.fun", sol)
                    TokenLifecycleTracker.onBuyConfirmed(ts.mint, sig)
                } catch (_: Throwable) {}
                priceImpactPct = q.priceImpactPct
                routerLabel = q.router
                // qty derived from quote.outAmount AFTER price check below
                qty = -1.0  // sentinel; recalculated below
            }

            val price = getActualPrice(ts)
            if (price <= 0.0) {
                throw Exception("Invalid normalized price for ${ts.symbol}")
            }
            val finalQty: Double = if (pumpFirstResult != null) qty
                else rawTokenAmountToUiAmount(ts, quote!!.outAmount, solAmount = sol, priceUsd = price)

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
                qtyToken     = finalQty,
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
                // V5.9.602 — ALL live buys, including Pump-first, remain
                // pending until tx/wallet verification proves token arrival.
                // A submitted/confirmed signature alone is not enough; Solana
                // txs can confirm with no desired token delta or index late.
                pendingVerify = true,
                // V5.9.744 — pricing-context snapshot. See paperBuy for the
                // full diagnosis. For LIVE buys this matters even more: a
                // position opened against a PUMP_FUN_BC_SYNTHETIC quote then
                // measured against a DEXSCREENER_WS pool quote at exit time
                // produces real-money phantom-PnL prints. Snapshot + rebase
                // logic in getActualPrice keeps PnL honest across the BC→pool
                // graduation jump.
                entryPriceSource = ts.lastPriceSource.ifBlank { "UNKNOWN" },
                entryPoolAddress = ts.lastPricePoolAddr.ifBlank { ts.pairAddress },
                entryDex         = ts.lastPriceDex.ifBlank { "UNKNOWN" },
                entrySupplyAssumed = if (ts.lastPriceSource == "PUMP_FUN_BC_SYNTHETIC" ||
                                          ts.lastPriceSource == "PUMP_FUN_FRONTEND_API")
                    1_000_000_000.0 else 0.0,
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

            // V5.9.602 — no route gets inline HELD registration here.
            // Pump-first and Jupiter both create a pending live position, then
            // the common verifier below promotes it only after tx/wallet proof
            // shows tokens actually landed. This prevents ghost positions where
            // a submitted/confirmed tx signature produced no wallet token delta.

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
                  "impact=${priceImpactPct.fmt(2)}% | router=${routerLabel} | sig=${sig.take(16)}…", tradeId.mint)
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
                // V5.9.750 — extended verify window. Operator screenshots
                // 23:36 show multiple live buys reaching BUY_CONFIRMED (tx
                // mined on-chain) but never reaching BUY_VERIFIED_LANDED.
                // Tokens DID arrive in the host wallet (Phantom screenshot
                // confirms Fartcoin / 8hPe…pump / 8RT6…TdhL / CwhP…vWdN
                // all present), so the failure mode is RPC indexer lag
                // beyond the original 30s window (5 polls × 6s). pump.fun
                // mints in particular hit indexer lag past 45s during hot
                // scanner storms, AND Helius rate-limit responses cause
                // tx-parse fallback to fail too. Extend to 10 polls × 6s
                // = 60s. Still well under the 90s periodic reconciler so
                // the safety net stays consistent. If verify still fails,
                // the position stays pendingVerify and the V5.9.748
                // RECONCILE-PROMOTE path picks it up at the next 90s tick.
                val pollIntervalMs = 6_000L
                val maxPolls = 10
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
                        // V5.9.751 — Base44 ticket item #4: assert host_tracker
                        // actually opens a position. Previously this was a
                        // silent try/catch; operator saw BUY_VERIFIED_LANDED
                        // but host_tracker.open_count=0 with no signal as to
                        // why. Now: log success/failure + emit LIVE_BUY_LANDED.
                        try {
                            val beforeOpen = HostWalletTokenTracker.getOpenCount()
                            HostWalletTokenTracker.recordBuyConfirmed(ts, verifySig)
                            val afterOpen = HostWalletTokenTracker.getOpenCount()
                            val opened = HostWalletTokenTracker.hasOpenPosition(ts.mint)
                            LiveTradeLogStore.log(
                                verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                "LIVE_BUY_LANDED — host_tracker open=$beforeOpen→$afterOpen | mint_tracked=$opened",
                                tokenAmount = ts.position.qtyToken, traderTag = "MEME",
                            )
                            if (!opened) {
                                ErrorLogger.warn("Executor",
                                    "⚠ HOST_TRACKER_NOT_OPENED: ${ts.symbol} mint=${ts.mint.take(12)}… recordBuyConfirmed returned but isOpenTracked=false (isOpen=${ts.position.isOpen} qty=${ts.position.qtyToken} pendingVerify=${ts.position.pendingVerify})")
                            }
                        } catch (e: Exception) {
                            ErrorLogger.error("Executor",
                                "🚨 HOST_TRACKER_RECORD_FAILED: ${ts.symbol} — ${e.javaClass.simpleName}: ${e.message?.take(120)}", e)
                            try {
                                LiveTradeLogStore.log(
                                    verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                    LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                    "⚠ HOST_TRACKER_RECORD_FAILED — ${e.javaClass.simpleName}: ${e.message?.take(80)}",
                                    traderTag = "MEME",
                                )
                            } catch (_: Throwable) {}
                        }
                        try { TokenLifecycleTracker.onTokenLanded(verifyMint, verifiedQty) } catch (_: Throwable) {}
                        // V5.9.602 — capture entry metadata only after token
                        // arrival is verified. This keeps pool/price data from
                        // being stamped onto ghost buys that never hit wallet.
                        try {
                            val decimals = verifyWallet.getTokenAccountsWithDecimals()[verifyMint]?.second ?: 9
                            val entryRaw = if (decimals > 0)
                                java.math.BigDecimal(verifiedQty).movePointRight(decimals).toBigInteger()
                            else java.math.BigInteger.ZERO
                            val priceSolPerToken = if (verifiedQty > 0.0) sol / verifiedQty else 0.0
                            val priceUsd = priceSolPerToken * (try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 })
                            TokenLifecycleTracker.recordEntryMetadata(
                                mint = verifyMint,
                                entryPriceSol = priceSolPerToken,
                                entryPriceUsd = priceUsd,
                                entryDecimals = decimals,
                                entryTokenRawConfirmed = entryRaw,
                                poolLiquidityUsd = ts.lastLiquidityUsd,
                            )
                        } catch (e: Throwable) {
                            ErrorLogger.warn("Executor", "recordEntryMetadata after verify failed (non-fatal): ${e.message}")
                        }
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
                    // V5.9.495s — operator: "every buy lands as a phaeton".
                    // V5.9.495t — operator triage: "still logging everything
                    // as phantom buys". Root cause: RPC empty-map blip during
                    // verification = "no entries returned for ANY token" ≠
                    // "wallet has no tokens of this mint". Read the WHOLE
                    // map; if it's empty, treat as inconclusive (do NOT
                    // wipe). If non-empty and the mint truly is absent
                    // → real phantom.
                    val lastChanceBalances: Map<String, Pair<Double, Int>>? = try {
                        verifyWallet.getTokenAccountsWithDecimals()
                    } catch (_: Throwable) { null }
                    val lastChanceQty: Double = lastChanceBalances?.get(verifyMint)?.first ?: 0.0
                    val lastChanceMapEmpty = lastChanceBalances?.isEmpty() == true
                    if (lastChanceQty > 0.0) {
                        val rescued = ts.position.copy(qtyToken = lastChanceQty, pendingVerify = false)
                        ts.position = rescued
                        ErrorLogger.warn(
                            "Executor",
                            "🛟 PHANTOM RESCUE: $verifySymbol — last-chance ATA found ${lastChanceQty.fmt(4)} tokens after polls returned 0. Promoting position instead of wiping."
                        )
                        LiveTradeLogStore.log(
                            verifyTradeKey, verifyMint, verifySymbol, "BUY",
                            LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                            "🛟 PHANTOM RESCUE: late-indexed buy salvaged | qty=${lastChanceQty.fmt(4)}",
                            tokenAmount = lastChanceQty, sig = verifySig, traderTag = "MEME",
                        )
                        try { PositionPersistence.savePosition(ts) } catch (_: Exception) {}
                        try { WalletTokenMemory.recordBuy(ts) } catch (_: Exception) {}
                        // V5.9.751 — Base44 ticket item #4: assert host_tracker
                        // actually opens a position. Previously this was a
                        // silent try/catch; operator saw BUY_VERIFIED_LANDED
                        // but host_tracker.open_count=0 with no signal as to
                        // why. Now: log success/failure + emit LIVE_BUY_LANDED.
                        try {
                            val beforeOpen = HostWalletTokenTracker.getOpenCount()
                            HostWalletTokenTracker.recordBuyConfirmed(ts, verifySig)
                            val afterOpen = HostWalletTokenTracker.getOpenCount()
                            val opened = HostWalletTokenTracker.hasOpenPosition(ts.mint)
                            LiveTradeLogStore.log(
                                verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                "LIVE_BUY_LANDED — host_tracker open=$beforeOpen→$afterOpen | mint_tracked=$opened",
                                tokenAmount = ts.position.qtyToken, traderTag = "MEME",
                            )
                            if (!opened) {
                                ErrorLogger.warn("Executor",
                                    "⚠ HOST_TRACKER_NOT_OPENED: ${ts.symbol} mint=${ts.mint.take(12)}… recordBuyConfirmed returned but isOpenTracked=false (isOpen=${ts.position.isOpen} qty=${ts.position.qtyToken} pendingVerify=${ts.position.pendingVerify})")
                            }
                        } catch (e: Exception) {
                            ErrorLogger.error("Executor",
                                "🚨 HOST_TRACKER_RECORD_FAILED: ${ts.symbol} — ${e.javaClass.simpleName}: ${e.message?.take(120)}", e)
                            try {
                                LiveTradeLogStore.log(
                                    verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                    LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                    "⚠ HOST_TRACKER_RECORD_FAILED — ${e.javaClass.simpleName}: ${e.message?.take(80)}",
                                    traderTag = "MEME",
                                )
                            } catch (_: Throwable) {}
                        }
                        try { TokenLifecycleTracker.onTokenLanded(verifyMint, lastChanceQty) } catch (_: Throwable) {}
                        return@launch
                    }
                    if (lastChanceMapEmpty || lastChanceBalances == null) {
                        ErrorLogger.warn(
                            "Executor",
                            "⚠️ POST-BUY INCONCLUSIVE (RPC-EMPTY): $verifySymbol — last-chance map was empty/null. Leaving pendingVerify; StartupReconciler / WalletTokenMemory will adopt later."
                        )
                        LiveTradeLogStore.log(
                            verifyTradeKey, verifyMint, verifySymbol, "BUY",
                            LiveTradeLogStore.Phase.WARNING,
                            "⚠️ POST-BUY: RPC empty-map at end of verify — position kept pending, no wipe.",
                            traderTag = "MEME",
                        )
                        return@launch
                    }
                    // V5.9.495y — TRADE-VERIFIER FINAL AUTHORITY (spec items 1, 8).
                    // Before destructively wiping a position, ask
                    // TradeVerifier (getSignatureStatuses + getTransaction
                    // tx-parse). LANDED → rescue. FAILED_CONFIRMED → wipe.
                    // INCONCLUSIVE_PENDING → keep position, leave it for
                    // the reconciler.
                    try {
                        val vr = TradeVerifier.verifyBuy(verifyWallet, verifySig, verifyMint, timeoutMs = 30_000L)
                        when (vr.outcome) {
                            TradeVerifier.Outcome.LANDED -> {
                                val rescued = ts.position.copy(qtyToken = vr.uiTokenDelta, pendingVerify = false)
                                ts.position = rescued
                                LiveTradeLogStore.log(
                                    verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                    LiveTradeLogStore.Phase.BUY_TX_PARSE_OK,
                                    "🛟 TX-PARSE RESCUE: tokens proven on-chain | rawDelta=${vr.rawTokenDelta} ui=${vr.uiTokenDelta.fmt(4)} dec=${vr.decimals}",
                                    tokenAmount = vr.uiTokenDelta, sig = verifySig, traderTag = "MEME",
                                )
                                try { PositionPersistence.savePosition(ts) } catch (_: Exception) {}
                                try { WalletTokenMemory.recordBuy(ts) } catch (_: Exception) {}
                                // V5.9.751 — Base44 ticket item #4: assert host_tracker
                        // actually opens a position. Previously this was a
                        // silent try/catch; operator saw BUY_VERIFIED_LANDED
                        // but host_tracker.open_count=0 with no signal as to
                        // why. Now: log success/failure + emit LIVE_BUY_LANDED.
                        try {
                            val beforeOpen = HostWalletTokenTracker.getOpenCount()
                            HostWalletTokenTracker.recordBuyConfirmed(ts, verifySig)
                            val afterOpen = HostWalletTokenTracker.getOpenCount()
                            val opened = HostWalletTokenTracker.hasOpenPosition(ts.mint)
                            LiveTradeLogStore.log(
                                verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                "LIVE_BUY_LANDED — host_tracker open=$beforeOpen→$afterOpen | mint_tracked=$opened",
                                tokenAmount = ts.position.qtyToken, traderTag = "MEME",
                            )
                            if (!opened) {
                                ErrorLogger.warn("Executor",
                                    "⚠ HOST_TRACKER_NOT_OPENED: ${ts.symbol} mint=${ts.mint.take(12)}… recordBuyConfirmed returned but isOpenTracked=false (isOpen=${ts.position.isOpen} qty=${ts.position.qtyToken} pendingVerify=${ts.position.pendingVerify})")
                            }
                        } catch (e: Exception) {
                            ErrorLogger.error("Executor",
                                "🚨 HOST_TRACKER_RECORD_FAILED: ${ts.symbol} — ${e.javaClass.simpleName}: ${e.message?.take(120)}", e)
                            try {
                                LiveTradeLogStore.log(
                                    verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                    LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                    "⚠ HOST_TRACKER_RECORD_FAILED — ${e.javaClass.simpleName}: ${e.message?.take(80)}",
                                    traderTag = "MEME",
                                )
                            } catch (_: Throwable) {}
                        }
                                return@launch
                            }
                            TradeVerifier.Outcome.INCONCLUSIVE_PENDING,
                            TradeVerifier.Outcome.VERIFICATION_ERROR -> {
                                LiveTradeLogStore.log(
                                    verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                    LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                                    "⏳ TRADE-VERIFIER INCONCLUSIVE — leaving position pending, NO wipe (reconciler will adopt later).",
                                    sig = verifySig, traderTag = "MEME",
                                )
                                return@launch
                            }
                            TradeVerifier.Outcome.FAILED_CONFIRMED -> {
                                LiveTradeLogStore.log(
                                    verifyTradeKey, verifyMint, verifySymbol, "BUY",
                                    LiveTradeLogStore.Phase.BUY_FAILED,
                                    "🚨 TRADE-VERIFIER FAILED_CONFIRMED — meta.err=${vr.txErr}. Wiping position.",
                                    sig = verifySig, traderTag = "MEME",
                                )
                                // Fall through to wipe block below.
                            }
                        }
                    } catch (vfx: Throwable) {
                        ErrorLogger.warn("Executor", "TradeVerifier.verifyBuy threw, falling back to legacy wipe path: ${vfx.message?.take(80)}")
                    }
                    ErrorLogger.warn(
                        "Executor",
                        "🚨 PHANTOM DETECTED: $verifySymbol — 0 tokens after ${maxPolls * pollIntervalMs / 1000}s + last-chance check (map non-empty, mint absent). Discarding pending position."
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
        if (!ts.position.isPaperPosition && blockIfSellInFlight(ts, reason)) return SellResult.FAILED_RETRYABLE
        // V5.9.495z28 (operator spec items 1+3): mark the lifecycle record
        // as SELL_PENDING the moment any sell is requested. The downstream
        // SOL+token verification path (post-broadcast) flips it to CLEARED
        // / PARTIAL_SELL / RESIDUAL_HELD based on the wallet reading.
        try { TokenLifecycleTracker.onSellPending(ts.mint) } catch (_: Throwable) {}
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
        
        // V5.9.475 — rehydrate ts.position from sub-trader maps if empty.
        // Without this, partial-sell would compute originalHolding = 0
        // because sub-traders keep positions in private maps and don't
        // mirror back to ts.position.qtyToken.
        rehydratePositionFromSubTraders(ts)

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
                val lockTradeKeyPre = LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime)
                if (blockIfSellInFlight(ts, reason, lockTradeKeyPre)) return
                if (!com.lifecyclebot.engine.sell.SellExecutionLocks.tryAcquire(ts.mint)) {
                    LiveTradeLogStore.log(lockTradeKeyPre, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                        "SELL_BLOCKED_ALREADY_IN_PROGRESS partial reason=$reason", traderTag = "MEME")
                    return
                }
                // V5.6.27: Implement actual partial sell for LIVE mode
                try {
                    val c = cfg()
                    val pos = ts.position
                    val sellQty = pos.qtyToken * pct
                    val newQty = pos.qtyToken - sellQty
                    val newCost = pos.costSol * (1 - pct)
                    val newSoldPct = pos.partialSoldPct + (pct * 100)
                    
                    val sellTradeKey = LiveTradeLogStore.keyFor(ts.mint, pos.entryTime)
                    val confirmedSell = resolveConfirmedSellAmountOrNull(ts, activeWallet, sellQty, pct, sellTradeKey)
                        ?: run {
                            com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
                            onLog("🛑 PARTIAL SELL BLOCKED: ${ts.symbol} BALANCE_UNKNOWN — refusing cached qty broadcast", ts.mint)
                            return
                        }
                    val sellUnits = confirmedSell.requestedRaw
                    val sellSlippage = com.lifecyclebot.engine.sell.SellSafetyPolicy.initialSlippageBps(reason)
                    val broadcastSlipLadder = com.lifecyclebot.engine.sell.SellSafetyPolicy.ladder(reason)
                    val isDrainExit = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason) > 1200 &&
                                      (com.lifecyclebot.engine.sell.SellSafetyPolicy.isHardRug(reason) ||
                                       com.lifecyclebot.engine.sell.SellSafetyPolicy.isManualEmergency(reason))

                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_START,
                        "requestPartialSell LIVE: ${(pct*100).toInt()}% | qty=$sellUnits | slip=${sellSlippage}bps | ladder=${broadcastSlipLadder.joinToString(",")}${if (isDrainExit) " [DRAIN-EXIT]" else ""}",
                        tokenAmount = sellQty,
                        slippageBps = sellSlippage,
                        traderTag = "MEME",
                    )
                    
                    onLog("📊 LIVE PARTIAL: Getting quote for $sellUnits units @ ${sellSlippage}bps slippage", ts.mint)
                    
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_QUOTE_TRY,
                        "Quote try @ ${sellSlippage}bps",
                        slippageBps = sellSlippage,
                        traderTag = "MEME",
                    )
                    var quote = getQuoteWithSlippageGuard(
                        ts.mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false,
                        sellTaker = activeWallet.publicKeyB58)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_QUOTE_OK,
                        "Quote OK | out=${quote.outAmount} | impact=${"%.2f".format(quote.priceImpactPct)}% | router=${quote.router}${if (quote.isRfqRoute) " (RFQ)" else ""}${if (quote.ultraRejectedReason.isNotBlank()) " ⚠ Ultra REJECTED → Metis fallback (${quote.ultraRejectedReason.take(60)})" else ""}",
                        slippageBps = sellSlippage,
                        traderTag = "MEME",
                    )

                    // V5.9.479 — IN-LINE broadcast escalation loop (mirrors V5.9.478 in liveSell).
                    var sig: String? = null
                    var lastBroadcastException: Exception? = null
                    var broadcastAttempts = 0
                    for (currentSlip in broadcastSlipLadder) {
                        broadcastAttempts++
                        try {
                            if (broadcastAttempts > 1) {
                                LiveTradeLogStore.log(
                                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                                    LiveTradeLogStore.Phase.SELL_QUOTE_TRY,
                                    "⚡ ESCALATION re-quote @ ${currentSlip}bps (attempt $broadcastAttempts)${if (isDrainExit) " [DRAIN-EXIT]" else ""}",
                                    slippageBps = currentSlip, traderTag = "MEME",
                                )
                                try {
                                    quote = getQuoteWithSlippageGuard(
                                        ts.mint, JupiterApi.SOL_MINT, sellUnits, currentSlip,
                                        isBuy = false, sellTaker = activeWallet.publicKeyB58)
                                    // V5.9.495u — show Ultra status on each escalation
                                    LiveTradeLogStore.log(
                                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                                        LiveTradeLogStore.Phase.SELL_QUOTE_OK,
                                        "Re-quote OK @ ${currentSlip}bps | out=${quote.outAmount} | router=${quote.router}${if (quote.isRfqRoute) " (RFQ)" else ""}${if (quote.ultraRejectedReason.isNotBlank()) " ⚠ Ultra REJECTED → Metis (${quote.ultraRejectedReason.take(60)})" else if (quote.isUltra) " ✅ ULTRA accepted" else ""}",
                                        slippageBps = currentSlip, traderTag = "MEME",
                                    )
                                } catch (qex: Exception) {
                                    lastBroadcastException = qex
                                    LiveTradeLogStore.log(
                                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                                        LiveTradeLogStore.Phase.SELL_QUOTE_FAIL,
                                        "Re-quote ${currentSlip}bps FAILED: ${qex.message?.take(80) ?: "?"}",
                                        slippageBps = currentSlip, traderTag = "MEME",
                                    )
                                    continue
                                }
                            }
                            val dynSlipCap = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason).coerceAtLeast(currentSlip)
                            val txResult = buildTxWithRetry(quote, activeWallet.publicKeyB58, dynamicSlippageMaxBps = dynSlipCap)
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_TX_BUILT,
                                "Tx built | router=${txResult.router} rfq=${txResult.isRfqRoute} | slip=${currentSlip}bps (attempt $broadcastAttempts)" + (if (txResult.dynSlipPickedBps >= 0) " | dyn-slip picked=${txResult.dynSlipPickedBps}bps incurred=${txResult.dynSlipIncurredBps}bps" else ""),
                                traderTag = "MEME",
                            )
                            security.enforceSignDelay()

                            val useJito = c.jitoEnabled && !quote.isUltra
                            // V5.9.483 — dynamic Jito tip (see JitoTipFetcher).
                            val jitoTip = com.lifecyclebot.network.JitoTipFetcher
                                .getDynamicTip(c.jitoTipLamports)
                                .let { if (isDrainExit) (it * 2).coerceAtMost(1_000_000L) else it }
                            val ultraReqId = if (quote.isUltra) txResult.requestId else null

                            onLog("📊 LIVE PARTIAL: Signing and broadcasting @ ${currentSlip}bps (attempt $broadcastAttempts)...", ts.mint)
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_BROADCAST,
                                "Broadcasting @ ${currentSlip}bps | route=${if (quote.isUltra) "ULTRA" else if (useJito) "JITO" else "RPC"} (attempt $broadcastAttempts)",
                                traderTag = "MEME",
                            )
                            sig = activeWallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_CONFIRMED,
                                "✅ Sig confirmed @ ${currentSlip}bps (attempt $broadcastAttempts): ${sig.take(20)}…",
                                sig = sig, traderTag = "MEME",
                            )
                            break
                        } catch (broadcastEx: Exception) {
                            lastBroadcastException = broadcastEx
                            val safe = security.sanitiseForLog(broadcastEx.message ?: "unknown")
                            val isSlippage = safe.contains("0x1788", ignoreCase = true) ||
                                             safe.contains("TooLittleSolReceived", ignoreCase = true) ||
                                             safe.contains("Slippage", ignoreCase = true) ||
                                             safe.contains("SlippageToleranceExceeded", ignoreCase = true)
                            if (!isSlippage) throw broadcastEx
                            val brc = zeroBalanceRetries.merge(ts.mint + "_broadcast_partial", 1) { old, _ -> old + 1 } ?: 1
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_FAILED,
                                "SLIPPAGE @ ${currentSlip}bps (in-line attempt $broadcastAttempts) — escalating${if (isDrainExit) " [DRAIN-EXIT]" else ""} (lifetime=$brc)",
                                slippageBps = currentSlip, traderTag = "MEME",
                            )
                        }
                    }
                    if (sig == null) {
                        throw lastBroadcastException ?: RuntimeException(
                            "${ts.symbol}: partial-sell in-line broadcast retries exhausted at ${broadcastSlipLadder.last()}bps")
                    }
                    zeroBalanceRetries.remove(ts.mint + "_broadcast_partial")
                    // V5.9.479 — capture non-null sig for downstream Trade record + log calls
                    val finalSig: String = sig!!
                    // V5.9.495y — register with TradeVerifier dedupe guard.
                    if (!finalSig.startsWith("PHANTOM_")) TradeVerifier.beginSell(ts.mint, finalSig, "partial_${(pct*100).toInt()}pct")

                    // V5.9.495y/z — TRADE-VERIFIER AUTHORITATIVE PARTIAL-SELL
                    // VERIFY (spec items 6, 8). Replaces V5.9.495s wallet-poll
                    // heuristic. Requires BOTH tokens cleared AND SOL received
                    // (operator: "I still want the sell verified and that the
                    // tokens clear the wallet and return the sol").
                    var verifiedSolReceived: Long = -1L
                    run {
                        if (finalSig.startsWith("PHANTOM_")) return@run
                        val sellQtyHere = pos.qtyToken * pct
                        val vsr = try {
                            TradeVerifier.verifySell(activeWallet, finalSig, ts.mint, timeoutMs = 60_000L)
                        } catch (vfx: Throwable) {
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.WARNING,
                                "⚠ TradeVerifier threw (${vfx.message?.take(60)}) — leaving position pending",
                                sig = finalSig, traderTag = "MEME",
                            )
                            null
                        }
                        when (vsr?.outcome) {
                            TradeVerifier.Outcome.LANDED -> {
                                verifiedSolReceived = vsr.solReceivedLamports
                                LiveTradeLogStore.log(
                                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                                    LiveTradeLogStore.Phase.SELL_TX_PARSE_OK,
                                    "✅ PARTIAL LANDED: rawConsumed=${vsr.rawTokenConsumed} ui=${vsr.uiTokenConsumed.fmt(4)} solReceived=${vsr.solReceivedLamports} lamports",
                                    sig = finalSig, tokenAmount = vsr.uiTokenConsumed, traderTag = "MEME",
                                )
                            }
                            TradeVerifier.Outcome.FAILED_CONFIRMED -> {
                                LiveTradeLogStore.log(
                                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                                    LiveTradeLogStore.Phase.SELL_FAILED_CONFIRMED,
                                    "🚨 PARTIAL_FAILED_CONFIRMED: meta.err=${vsr.txErr} — position UNCHANGED.",
                                    sig = finalSig, tokenAmount = sellQtyHere, traderTag = "MEME",
                                )
                                onLog("🚨 PARTIAL SELL meta.err ${ts.symbol}: ${vsr.txErr} — position unchanged.", ts.mint)
                                TradeVerifier.endSell(ts.mint)
                                return
                            }
                            TradeVerifier.Outcome.INCONCLUSIVE_PENDING,
                            TradeVerifier.Outcome.VERIFICATION_ERROR -> {
                                LiveTradeLogStore.log(
                                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                                    LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                                    "⏳ PARTIAL VERIFY INCONCLUSIVE: tokens-clear AND SOL-return not BOTH proven within 60s — leaving position open & qty unchanged. Reconciler will adopt later.",
                                    sig = finalSig, tokenAmount = sellQtyHere, traderTag = "MEME",
                                )
                                TradeVerifier.endSell(ts.mint)
                                return
                            }
                            null -> {
                                TradeVerifier.endSell(ts.mint)
                                return
                            }
                        }
                    }
                    TradeVerifier.endSell(ts.mint)
                    
                    val solBack: Double = when {
                        finalSig.startsWith("PHANTOM_") -> 0.0
                        verifiedSolReceived > 0L -> verifiedSolReceived / 1_000_000_000.0
                        else -> quote.outAmount / 1_000_000_000.0
                    }
                    val livePnl = solBack - pos.costSol * pct
                    val liveScore = pct(pos.costSol * pct, solBack)
                    val (netPnl, feeSol) = slippageGuard.calcNetPnl(livePnl, pos.costSol * pct)
                    
                    // Update position
                    ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = newSoldPct)
                    
                    val liveTrade = Trade("SELL", "live", solBack, currentPrice,
                        System.currentTimeMillis(), "partial_${newSoldPct.toInt()}pct",
                        livePnl, liveScore, sig = finalSig, feeSol = feeSol, netPnlSol = netPnl,
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

                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_VERIFY_SOL_RETURNED,
                        "Partial sold ${(pct*100).toInt()}% | got ${solBack.fmt(4)} SOL | net ${netPnl.fmt(4)}",
                        sig = finalSig,
                        solAmount = solBack,
                        traderTag = "MEME",
                    )
                    com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)

                    // V5.9.743 — wire 70/30 treasury siphon onto the LIVE branch
                    // of the manual requestPartialSell entry point. The PAPER
                    // branch of this same function already siphons (V5.9.428);
                    // the LIVE branch was missing the call, so any external
                    // partial-close request (UI button / API) deposited 0%
                    // to Treasury in live mode. Use netPnl (post-fee).
                    if (netPnl > 0.0) {
                        try {
                            if (pos.isTreasuryPosition || pos.tradingMode == "TREASURY") {
                                com.lifecyclebot.engine.TreasuryManager.contributeFullyFromTreasuryScalp(
                                    netPnl, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                            } else {
                                com.lifecyclebot.engine.TreasuryManager.contributeFromMemeSell(
                                    netPnl, com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
                            }
                        } catch (e: Exception) {
                            ErrorLogger.debug("Executor", "Treasury split error (reqPartial live): ${e.message}")
                        }
                    }

                    onLog("✅ LIVE PARTIAL SELL ${(pct*100).toInt()}% @ +${pnlPct.toInt()}% | " +
                          "${solBack.fmt(4)}◎ | sig=${finalSig.take(16)}…", ts.mint)
                    
                    onNotify("💰 Live Partial Sell",
                        "${ts.symbol}  +${pnlPct.toInt()}%  sold ${(pct*100).toInt()}%",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                        
                } catch (e: Exception) {
                    // V5.9.474 — classified post-quote forensics for partial-sell catch
                    val sellTradeKey2 = LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime)
                    val safe = security.sanitiseForLog(e.message ?: "unknown")
                    val broadcastRetries = zeroBalanceRetries.merge(ts.mint + "_broadcast_partial", 1) { old, _ -> old + 1 } ?: 1
                    val failureClass = when {
                        safe.contains("0x1788", ignoreCase = true) ||
                        safe.contains("0x1789", ignoreCase = true) ||
                        safe.contains("TooLittleSolReceived", ignoreCase = true) ||
                        safe.contains("Slippage", ignoreCase = true) -> "SLIPPAGE_EXCEEDED"
                        safe.contains("simulation failed", ignoreCase = true) -> "SIM_FAILED"
                        safe.contains("RFQ", ignoreCase = true) -> "RFQ_ROUTE_FAILED"
                        safe.contains("blockhash", ignoreCase = true) ||
                        safe.contains("expired", ignoreCase = true) -> "TX_EXPIRED"
                        safe.contains("rate limit", ignoreCase = true) ||
                        safe.contains("429", ignoreCase = true) -> "RATE_LIMITED"
                        safe.contains("timeout", ignoreCase = true) -> "TIMEOUT"
                        safe.contains("InsufficientFunds", ignoreCase = true) ||
                        safe.contains("insufficient lamports", ignoreCase = true) ||
                        safe.contains("rent", ignoreCase = true) -> "INSUFFICIENT_FUNDS"
                        safe.contains("buildSwapTx", ignoreCase = true) ||
                        safe.contains("transactionBase64", ignoreCase = true) -> "JUPITER_BUILD_FAILED"
                        else -> "BROADCAST_FAILED"
                    }
                    LiveTradeLogStore.log(
                        sellTradeKey2, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_FAILED,
                        "$failureClass — ${e.javaClass.simpleName}: ${safe.take(120)} (partial-sell attempt $broadcastRetries)",
                        traderTag = "MEME",
                    )
                    com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
                    ErrorLogger.error("Executor", "❌ LIVE PARTIAL SELL FAILED: ${ts.symbol} | class=$failureClass | ${e.message}")
                    onLog("❌ Live partial sell FAILED: $failureClass — ${safe.take(80)} (retry $broadcastRetries)", ts.mint)
                }
            }
        }
    }

    internal fun doSell(ts: TokenState, reason: String,
                       wallet: SolanaWallet?, walletSol: Double,
                       identity: TradeIdentity? = null): SellResult {
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)

        // Atomic guard: only ONE sell can proceed per mint at a time.
        // V5.9.756 — TTL-backed acquire (20 s stale-release watchdog).
        if (!acquireSellLock(ts.mint)) {
            onLog("⚠️ SELL SKIPPED: sell already in-progress for ${ts.symbol}", tradeId.mint)
            return SellResult.ALREADY_CLOSED
        }

        try {

        // V5.9.475 — REHYDRATE before the isOpen check so sub-trader positions
        // (Treasury, ShitCoin, Quality, BlueChip, Moonshot) that never wrote
        // back to ts.position can still be sold. Was the primary cause of
        // 'meme sells never reach the host wallet': sub-traders kept positions
        // in private maps, ts.position.isOpen=false, doSell returned
        // ALREADY_CLOSED, swap never fired.
        rehydratePositionFromSubTraders(ts)
        // V5.9.491 — final on-chain fallback: if no sub-trader had it but
        // the wallet on-chain DOES hold the mint, rehydrate from there.
        // Operator forensics: 'wrong the tokens are in my wallets' — the
        // bot was aborting sells with qty=0 while the bag was sitting in
        // the wallet. This guarantees the sell pipeline can ALWAYS dump
        // any token the wallet actually owns.
        if (ts.position.qtyToken <= 0.0) {
            rehydratePositionFromWallet(ts, wallet)
        }

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
            if (blockIfSellInFlight(ts, reason, LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime))) {
                return SellResult.FAILED_RETRYABLE
            }
            if (!com.lifecyclebot.engine.sell.SellExecutionLocks.tryAcquire(ts.mint)) {
                LiveTradeLogStore.log(
                    LiveTradeLogStore.keyFor(ts.mint, ts.position.entryTime), ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                    "SELL_BLOCKED_ALREADY_IN_PROGRESS full reason=$reason", traderTag = "MEME")
                return SellResult.FAILED_RETRYABLE
            }
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
            // Always release the sell guards after the sell/verify lifecycle returns.
            releaseSellLock(ts.mint)
            com.lifecyclebot.engine.sell.SellExecutionLocks.release(ts.mint)
        }
    }

    fun paperSell(ts: TokenState, reason: String, identity: TradeIdentity? = null): SellResult {
        val tradeId = identity ?: TradeIdentityManager.getOrCreate(ts.mint, ts.symbol, ts.source)
        
        val pos   = ts.position
        val price = getActualPrice(ts)
        if (!pos.isOpen || price == 0.0) return SellResult.ALREADY_CLOSED
        // V5.9.719: acquire paper sell lock to prevent double-exit race.
        // If another sell request is already in-flight for this mint, reject this one.
        if (!acquirePaperSellLock(ts.mint)) {
            ErrorLogger.debug("Executor", "🔒 PAPER_DOUBLE_SELL_BLOCKED: ${ts.symbol} reason=$reason already selling")
            return SellResult.ALREADY_CLOSED
        }
        // V5.9.720: try/finally ensures lock is ALWAYS released — even on exception.
        // Without this, a crash mid-sell leaves the lock set forever, causing
        // bot-stop closeAllPositions() to see ALREADY_CLOSED and skip the position.
        try {
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
        
        // V5.9.720: BOT-SHUTDOWN FAST PATH.
        // For bot_shutdown sells, skip all 44-layer AI learning — it's a forced
        // exit, not a real signal. The position is closed and wallet is credited
        // above; learning from a shutdown sell is pure noise and takes 2-4s/position.
        // With 17+ open positions this was causing the 60-90s freeze on restart.
        if (reason == "bot_shutdown") {
            val shutdownCostSol = pos.costSol  // capture BEFORE position reset
            tradeId.closed(price, pnlP, pnl, reason)
            try { GlobalTradeRegistry.closePosition(tradeId.mint) } catch (_: Exception) {}
            try { EmergentGuardrails.unregisterPosition(tradeId.mint) } catch (_: Exception) {}
            ts.position      = Position()
            ts.lastExitTs    = System.currentTimeMillis()
            ts.lastExitPrice = price
            ts.lastExitPnlPct = pnlP
            ts.lastExitWasWin = pnlP >= 1.0
            try { PositionPersistence.savePosition(ts) } catch (_: Exception) {}
            try { WalletPositionLock.recordClose("Meme", shutdownCostSol) } catch (_: Exception) {}
            try { TradeAuthorizer.releasePosition(ts.mint, "SELL_$reason", TradeAuthorizer.ExecutionBook.CORE) } catch (_: Exception) {}
            onLog("🛑 SHUTDOWN CLOSE: ${ts.symbol} @ ${pnlP.toInt()}% (learning skipped)", tradeId.mint)
            return SellResult.PAPER_CONFIRMED
        }

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
        try { HostWalletTokenTracker.recordSellConfirmed(ts.mint, ts.symbol, price, pnlP, reason) } catch (_: Exception) {}
        
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
        } finally {
            // V5.9.720: release under ALL exit paths (normal + exception + shutdown)
            releasePaperSellLock(ts.mint)
        }
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
        
        // V5.9.495y — DUPLICATE-EXIT GUARD (spec item 9).
        TradeVerifier.activeSellSig(ts.mint)?.let { existingSig ->
            onLog("⏳ SELL DEDUPE: ${ts.symbol} — existing sell sig=${existingSig.take(16)}… still verifying. Skipping new $reason exit.", ts.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                "⏳ DEDUPE: existing sell sig=${existingSig.take(16)}… still in flight. Skipping duplicate $reason exit.",
                sig = existingSig, traderTag = "MEME",
            )
            return SellResult.FAILED_RETRYABLE
        }
        
        onLog("🔄 SELL START: ${ts.symbol} | reason=$reason | pos.isOpen=${pos.isOpen} | pos.qtyToken=${pos.qtyToken} | pos.costSol=${pos.costSol}", tradeId.mint)
        LiveTradeLogStore.log(
            sellTradeKey, ts.mint, ts.symbol, "SELL",
            LiveTradeLogStore.Phase.SELL_START,
            "🔴 LIVE SELL START | reason=$reason | qty=${pos.qtyToken.fmt(4)} | wallet=${walletSol.fmt(4)} SOL",
            tokenAmount = pos.qtyToken, traderTag = "MEME",
        )

        // V5.9.495z39 — operator spec item 5:
        // "Treasury recovery / re-registration must NOT trigger an
        //  immediate sell until on-chain basis is confirmed."
        // RecoveryLockTracker holds the mint when a position was
        // re-registered (e.g. by treasury sweep) so the executor cannot
        // sell into stale UI prices before chain basis is loaded.
        // V5.9.495z41 — opportunistically attempt unlock first (rate-limited
        // 30s/mint, runs on IO). If this attempt fires AND chain basis is
        // now provable + a profitable quote is available, the lock is
        // cleared synchronously and we fall through to the normal sell
        // path. Otherwise the early-return below still kicks in.
        try {
            com.lifecyclebot.engine.sell.RecoveryLockUnlocker.maybeAttemptUnlock(
                mint = ts.mint,
                symbol = ts.symbol,
                wallet = wallet,
                jupiterApiKey = c.jupiterApiKey,
            )
        } catch (_: Throwable) { /* never break the sell tick */ }
        if (com.lifecyclebot.engine.sell.RecoveryLockTracker.isLockedAwaitingChainBasis(ts.mint)) {
            onLog("🔒 SELL DEFERRED: ${ts.symbol} — RECOVERY_POSITION_LOCKED_UNTIL_CHAIN_BASIS_CONFIRMED.", tradeId.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                "🔒 RECOVERY_LOCK: skipping $reason exit until chain basis loaded.",
                traderTag = "MEME",
            )
            return SellResult.FAILED_RETRYABLE
        }

        // V5.9.495z39 — operator spec item 1: amount-violation lock.
        // SellAmountAuditor flagged this mint after a previous sell consumed
        // materially more than requested. Refuse new sells until the
        // reconciler clears the lock — the next forensic export will tell
        // operator what happened.
        if (com.lifecyclebot.engine.sell.SellAmountAuditor.isLocked(ts.mint)) {
            val v = com.lifecyclebot.engine.sell.SellAmountAuditor.getViolation(ts.mint)
            onLog("🔒 SELL BLOCKED: ${ts.symbol} — SELL_AMOUNT_VIOLATION lock " +
                  "(over=${v?.overconsumedRaw} ${"%.1f".format(v?.overconsumedPct ?: 0.0)}%).", tradeId.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_FAILED,
                "🔒 SELL_AMOUNT_VIOLATION_LOCK active — manual reconciler unlock required.",
                traderTag = "MEME",
            )
            return SellResult.FAILED_RETRYABLE
        }

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

        // V5.9.458 — SELL LATENCY FIX (operator directive: 'meme trader
        // should make live sells as fast as it makes live buys').
        // Buys go straight to quote; sells previously did a synchronous
        // wallet.getTokenAccountsWithDecimals() network call first and,
        // on a stale RPC indexer, silently bailed via the zero-balance
        // gate — turning a TP/SL trigger into an orphan loop.
        //
        // Trust the tracker qty for the first 60s after a verified BUY.
        // The buy pipeline already verified token arrival via
        // TX-PARSE (BUY_VERIFIED_LANDED), so tokens are definitively
        // on-chain; a zero reading this early is indexer lag, not
        // reality. After 60s we fall back to the on-chain sanity
        // check to keep orphan protection for genuinely rugged tokens.
        val ageMs = System.currentTimeMillis() - pos.entryTime
        // V5.9.601: live sells may not skip exact owner+mint balance verification.
        val skipOnChainCheck = false
        if (skipOnChainCheck) {
            onLog("⚡ FAST PATH: tokens verified on buy ${ageMs / 1000}s ago — skipping on-chain " +
                  "balance pre-check (tracker qty=$tokenUnits)", tradeId.mint)
        }

        if (!skipOnChainCheck) try {
            onLog("📊 SELL DEBUG: Fetching on-chain token balances...", tradeId.mint)
            // V5.9.751 — `var` so the RPC-RECOVERY refresh below can rebind
            // if the first read returned an empty map.
            var onChainBalances = wallet.getTokenAccountsWithDecimals()
            var tokenData = onChainBalances[ts.mint]
            var mapEmpty = onChainBalances.isEmpty()

            // V5.9.467 — RPC-EMPTY RESCUE (operator-reported sell-kill bug).
            //
            // Symptom from user forensics: BUY_VERIFIED_LANDED qty=587.4548
            // at 23:37:19 → LIVE SELL START 23:39:19 → SELL_FAILED "RPC
            // returned EMPTY balance map (retry 1/20) — will retry next
            // tick" for 20+ retries. Tokens were definitively on-chain
            // (verified via TX-PARSE at buy) but every sell attempt was
            // silently blocked because the RPC's getTokenAccountsByOwner
            // call returned no accounts at all (not a zero for this mint,
            // the entire map was empty — classic Helius/Triton overload
            // response).
            //
            // Old behaviour: mapEmpty=true → BLOCK SELL → retry next tick
            // (forever, across app restarts). This silently strands the
            // position across any RPC blip longer than the 60s tracker
            // trust window.
            //
            // New behaviour: mapEmpty=true AND tokenUnits>0 (from verified
            // buy) → proceed with tracker qty. The RPC being broken does
            // NOT mean the tokens are gone. The Jupiter quote + swap call
            // will fail cleanly if the tokens are genuinely missing, so
            // we don't risk false sells. The orphan-alert path (line
            // below) still fires when the map is NON-EMPTY but this mint
            // is absent — that's the real "externally sold/rugged" case.
            val rpcRescue = false
            if (mapEmpty) {
                // V5.9.751 — Base44 ticket item #5. Previously we returned
                // FAILED_RETRYABLE immediately and spammed SELL_BLOCKED to
                // forensics on every tick — operator could not tell if the
                // retry was actually queued. Now: do ONE explicit refresh,
                // then emit SELL_RETRY_SCHEDULED at-most-once per stuck
                // window. Still no broadcast against unknown state (the
                // V5.9.467 invariant); just better signal/noise.
                val retryCountKey = ts.mint + "_balance_unknown"
                val retryCount = zeroBalanceRetries.merge(retryCountKey, 1) { old, _ -> old + 1 } ?: 1
                var recovered = false
                try {
                    Thread.sleep(150)  // brief breathing room before re-poll
                    val retryBalances = wallet.getTokenAccountsWithDecimals()
                    if (retryBalances.isNotEmpty()) {
                        // Rebind so the rest of this block sees the recovered map.
                        onChainBalances = retryBalances
                        tokenData = retryBalances[ts.mint]
                        mapEmpty = false
                        recovered = true
                        onLog("♻ RPC-RECOVERY: ${ts.symbol} — refresh returned ${retryBalances.size} accounts (was empty)", tradeId.mint)
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                            "RPC-RECOVERY — refresh returned ${retryBalances.size} accounts; proceeding with sell evaluation",
                            traderTag = "MEME",
                        )
                        zeroBalanceRetries.remove(retryCountKey)
                    }
                } catch (e: Throwable) {
                    ErrorLogger.warn("Executor", "RPC refresh failed for ${ts.symbol}: ${e.message?.take(80)}")
                }
                if (!recovered) {
                    // Refresh confirmed empty. Emit SELL_RETRY_SCHEDULED
                    // exactly once per stuck window (first retry only).
                    val firstTime = retryCount == 1
                    if (firstTime) {
                        onLog("🛑 SELL_RETRY_SCHEDULED: ${ts.symbol} RPC-EMPTY-MAP — refresh confirmed empty. Will retry on next sell tick.", tradeId.mint)
                        ErrorLogger.warn("Executor",
                            "SELL_RETRY_SCHEDULED ${ts.symbol} RPC-EMPTY-MAP after one refresh — retry armed")
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                            "SELL_RETRY_SCHEDULED — RPC empty after one refresh; will retry next sell tick (count=$retryCount)",
                            traderTag = "MEME",
                        )
                    } else if (retryCount % 10 == 0) {
                        // Heartbeat every 10 retries so the operator can see it
                        // hasn't quietly died, but without the per-tick spam.
                        onLog("🛑 SELL still blocked: ${ts.symbol} RPC-EMPTY-MAP (retry $retryCount) — RPC still empty.", tradeId.mint)
                    }
                    return SellResult.FAILED_RETRYABLE
                }
                // Recovered — fall through to the normal tokenData checks
                // below (which run against the refreshed map).
            }
            if (tokenData == null || tokenData.first <= 0.0) {
                // V5.9.72 CRITICAL FIX: previous logic force-closed the position
                // after 5 "zero balance" reads, calling tradeId.closed(...) and
                // returning CONFIRMED even though NO swap was broadcast. If the
                // RPC returned an empty/errored map (rate-limit, slow sync),
                // the tokens were still in the user's wallet but the bot
                // marked them "sold at -100%", ate the PnL accounting, and
                // walked away. User saw: tokens remain on-chain, no SOL back.
                //
                // V5.9.467: the map-empty branch is now handled above by
                // RPC-RESCUE. Only reach this path when the RPC returned
                // a NON-EMPTY map and this specific mint was absent/zero
                // — i.e. tokens genuinely gone (rug, honeypot, external
                // sell). Surface as orphan alert, leave position OPEN,
                // never claim a sell PnL.
                val retryCount = zeroBalanceRetries.merge(ts.mint, 1) { old, _ -> old + 1 } ?: 1

                if (retryCount >= 20) {
                    onLog("🚨 ORPHAN ALERT: ${ts.symbol} — on-chain balance has been 0 for $retryCount " +
                          "consecutive reads (map non-empty, mint absent). Tokens likely rugged / externally sold. " +
                          "Position kept OPEN for manual release.", tradeId.mint)
                    onNotify("🚨 Orphan Position",
                        "${ts.symbol}: on-chain balance 0 for $retryCount polls. No swap was sent. " +
                        "Clear manually from the positions panel.",
                        com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_FAILED,
                        "ORPHAN — on-chain balance 0 for $retryCount polls. Tokens likely rugged. Position kept OPEN for manual release.",
                        traderTag = "MEME",
                    )
                    return SellResult.FAILED_RETRYABLE
                }

                onLog("SELL BLOCKED: on-chain balance=0 for ${ts.symbol} (retry $retryCount/20) — mint absent from non-empty map",
                      tradeId.mint)
                ErrorLogger.warn("Executor", "LIVE SELL BLOCKED: ${ts.symbol} ONCHAIN_ZERO (retry $retryCount/20)")
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_FAILED,
                    "On-chain balance = 0 (retry $retryCount/20) — will retry next tick",
                    traderTag = "MEME",
                )
                return SellResult.FAILED_RETRYABLE
            } else {
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
                // V5.9.458 — forensics: surface dust-position aborts so the
                // user sees why a sell never broadcast.
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_FAILED,
                    "DUST+DEEP_LOSS — balance=${"%.4f".format(actualBalanceUi)} (~${"%.6f".format(currentValueEstimate)} SOL), Jupiter can't route. Tokens remain on-chain.",
                    tokenAmount = actualBalanceUi, traderTag = "MEME",
                )
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
            // V5.9.764 — EMERGENT CRITICAL item E: qty-shrink safety assertion.
            // Operator forensics_20260515_151634.json showed HIM emergency
            // retries using qty=1331 against a wallet balance of 122210 (1.1%),
            // and CABAL retries at qty=88 against 7330 (1.2%). For any
            // FULL_EXIT-class reason (RUG / HARD_STOP / STOP_LOSS / etc.) we
            // refuse to broadcast a qty that's less than 90% of the
            // authoritative on-chain balance, and rebuild with the full
            // wallet balance instead. This blocks the exact 1-2% corruption
            // pattern without breaking partial-take-profit sells.
            val guardedUnits = com.lifecyclebot.engine.sell.SellQtyGuard.guard(
                mint = ts.mint,
                symbol = ts.symbol,
                reason = reason,
                qtyToSell = tokenUnits,
                authoritativeWalletQty = actualRawUnits,
            )
            if (guardedUnits != tokenUnits) {
                onLog(
                    "🛡 SELL QTY GUARD: ${ts.symbol} $reason qty rebuilt $tokenUnits → $guardedUnits (full wallet balance)",
                    tradeId.mint,
                )
                tokenUnits = guardedUnits
            }
            // V5.9.764 — register / refresh the SellJob (item A state-machine)
            // so the reconciler + lock TTL pipeline have authoritative state
            // for this attempt. For FULL_EXIT reasons we anchor on the
            // on-chain balance; partial sells (PARTIAL_TAKE_PROFIT / PROFIT_LOCK)
            // pass through unchanged.
            try {
                val sjMode = if (com.lifecyclebot.engine.sell.SellQtyGuard.isFullExitReason(reason))
                    com.lifecyclebot.engine.sell.SellJobMode.FULL_EXIT
                else
                    com.lifecyclebot.engine.sell.SellJobMode.PARTIAL_EXIT
                com.lifecyclebot.engine.sell.SellJobRegistry.getOrCreate(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    reason = reason,
                    requestedQty = tokenUnits,
                    walletQtyAtStart = actualRawUnits,
                    mode = sjMode,
                )
            } catch (_: Throwable) {}
            } // end else (tokenData non-null refinement branch) — V5.9.467
            
        } catch (e: Exception) {
            onLog("🛑 SELL BLOCKED: ${ts.symbol} BALANCE_UNKNOWN after balance-check failure: ${e.message?.take(60)}", tradeId.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL", LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                "BALANCE_UNKNOWN after balance-check failure — broadcast blocked; cached tokenUnits not authoritative",
                traderTag = "MEME",
            )
            return SellResult.FAILED_RETRYABLE
        }

        var pnl  = 0.0
        var pnlP = 0.0

        try {
            // V5.9.470 — slippage escalation across BROADCAST retries (not just quote retries).
            // Operator forensics on TROLLA (pump.fun graduated meme) showed:
            //   SELL_QUOTE_OK @ 200bps (quote accepted by Metis aggregator)
            //   SELL_TX_BUILT → SELL_BROADCAST → SELL_FAILED 0x1788 (slippage)
            //   ... attempt 2: same 200bps quote, same 0x1788 broadcast failure
            // Pump.fun bonding-curve memes are volatile; the price moves between
            // quote acceptance and on-chain execution, so the original 200bps
            // tolerance gets exceeded at the swap program. Old loop only
            // escalated slippage on QUOTE failure, never on BROADCAST failure,
            // so subsequent ticks kept retrying at 200bps forever.
            // Now: per-mint broadcastRetries counter increases the BASE slippage
            // for retries (200 → 300 → 500 → 800 → 1000bps cap) so the 3rd+
            // attempt has a real chance against pump.fun-class price drift.
            val sellSlippage = com.lifecyclebot.engine.sell.SellSafetyPolicy.initialSlippageBps(reason)
            val priorBroadcastRetries = zeroBalanceRetries[ts.mint + "_broadcast"] ?: 0
            onLog("📊 SELL DEBUG: Requesting quote | slippage=${sellSlippage}bps | tokenUnits=$tokenUnits | broadcastRetries=$priorBroadcastRetries", tradeId.mint)
            
            var quote: com.lifecyclebot.network.SwapQuote? = null
            var lastError: Exception? = null
            
            // V5.7.8: Aggressive sell — try normal slippage, then 2x, then 5x, then max
            // V5.9.103: previously hard-capped at 500 bps. V5.9.470: lifted to
            // 1000bps but ONLY for tokens that have already failed broadcast
            // multiple times — first attempts still use the safe 200bps and
            // escalate within this loop only if the quote itself can't fill.
            // This preserves the V5.9.103 'don't sell rugs at half price' intent
            // while letting genuinely volatile pump.fun memes complete after 2-3
            // 0x1788 retries.
            val slippageLevels = com.lifecyclebot.engine.sell.SellSafetyPolicy.ladder(reason)
            
            for (slipLevel in slippageLevels) {
                for (attempt in 1..2) {
                    try {
                        onLog("SELL: Quote attempt slippage=${slipLevel}bps try=$attempt...", tradeId.mint)
                        // V5.9.456 — forensics: emit SELL_QUOTE_TRY before Jupiter
                        // call so the Live Trade Forensics no longer goes dark
                        // for 30-90s between SELL_START and SELL_TX_BUILT.
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_QUOTE_TRY,
                            "Quote attempt @ ${slipLevel}bps try=$attempt",
                            slippageBps = slipLevel,
                            traderTag = "MEME",
                        )
                        quote = getQuoteWithSlippageGuard(ts.mint, JupiterApi.SOL_MINT,
                                                           tokenUnits, slipLevel,
                                                           isBuy = false,
                                                           sellTaker = wallet.publicKeyB58)
                        onLog("SELL: Quote OK | out=${quote.outAmount} | impact=${quote.priceImpactPct}% | router=${quote.router} rfq=${quote.isRfqRoute} | reqId=${quote.requestId.take(12)}", tradeId.mint)
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_QUOTE_OK,
                            "Quote OK @ ${slipLevel}bps | out=${quote.outAmount} | impact=${"%.2f".format(quote.priceImpactPct)}% | router=${quote.router}${if (quote.isRfqRoute) " (RFQ)" else ""}${if (quote.ultraRejectedReason.isNotBlank()) " ⚠ Ultra REJECTED → Metis fallback" else ""}",
                            slippageBps = slipLevel,
                            traderTag = "MEME",
                        )
                        break
                    } catch (e: Exception) {
                        lastError = e
                        onLog("SELL: Quote failed slippage=${slipLevel}bps: ${e.message?.take(50)}", ts.mint)
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_QUOTE_FAIL,
                            "Quote ${slipLevel}bps FAILED: ${e.message?.take(80) ?: "?"}",
                            slippageBps = slipLevel,
                            traderTag = "MEME",
                        )
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
                // V5.9.458 — terminal forensics so the user sees the sell died on quote exhaustion.
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_FAILED,
                    "QUOTE EXHAUSTED — all ${slippageLevels.size * 2} attempts failed. Position kept OPEN.",
                    traderTag = "MEME",
                )
                return SellResult.FAILED_RETRYABLE
            }

            val qGuard = security.validateQuote(quote, isBuy = false, inputSol = pos.costSol)
            if (qGuard is GuardResult.Block) {
                onLog("⚠ Sell quote warning: ${qGuard.reason} — proceeding anyway", ts.mint)
            }

            // V5.9.478 — IN-LINE BROADCAST RETRY for 0x1788 / SLIPPAGE_EXCEEDED.
            // V5.9.479 — DRAIN-EXIT EMERGENCY MODE + raised default cap.
            //
            // Operator directive (V5.9.478): "80 seconds is dumb and way to
            // long the price will always have moved plus we will miss quick
            // pump spikes it needs to be as fast as the buys are aka instant".
            //
            // Operator forensics (V5.9.479, Goonman/3gV9Yghi…): a token in
            // 'drain_exit_liquidity_collapse' kept failing 0x1788 even at
            // 1000bps. The token's price was collapsing >10% per second so
            // even 10% slippage tolerance was insufficient. For drain / rug
            // / collapse exits we accept catastrophic slippage to escape
            // a position that may be worth $0 in 30 seconds.
            //
            // Default ladder cap also bumped from 1000 → 2000bps because
            // pump.fun memes routinely move 8-15% per tx in normal (non-rug)
            // conditions and the V5.9.478 1000bps cap was tripping on
            // healthy positions too.
            //
            // When broadcast fails with SLIPPAGE_EXCEEDED, re-quote at the
            // next slippage tier IN-LINE within the same liveSell call.
            // ~3-5 seconds between tiers (Jupiter quote latency only). All
            // tiers complete within ~15-25s vs 4 minutes through the queue.
            // All other failure modes (SIM, RFQ, RATE_LIMITED, TX_EXPIRED,
            // INSUFFICIENT_FUNDS, JUPITER_BUILD_FAILED) re-throw to the outer
            // catch unchanged — only SLIPPAGE escalates inline.
            // zeroBalanceRetries[mint+"_broadcast"] is still bumped per
            // failure for forensics + cross-call state.
            val isDrainExit = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason) > 1200 &&
                              (com.lifecyclebot.engine.sell.SellSafetyPolicy.isHardRug(reason) ||
                               com.lifecyclebot.engine.sell.SellSafetyPolicy.isManualEmergency(reason))
            val broadcastSlipLadder = com.lifecyclebot.engine.sell.SellSafetyPolicy.ladder(reason)
            if (isDrainExit) {
                onLog("🚨 DRAIN-EXIT mode for ${ts.symbol} ($reason): ladder=${broadcastSlipLadder.joinToString(",")}bps", tradeId.mint)
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_START,
                    "🚨 DRAIN-EXIT mode | ladder=${broadcastSlipLadder.joinToString(",")}bps",
                    traderTag = "MEME",
                )
            }
            var sig: String? = null
            var lastBroadcastException: Exception? = null
            var broadcastAttempts = 0
            // V5.9.495 — UNIVERSAL PUMP-FIRST routing (inlined — no `run { }`
            // lambda so Kotlin smart-cast on `sig` still works downstream).
            // V5.9.492 added PUMP-FIRST for pump.fun mints only. Operator
            // directive (Feb 2026): "we now know what works ie pumpfun for
            // the entire sol network basically the Jupiter Ultra then the
            // other callbacks". PumpPortal Lightning's pool="auto" routes
            // through pump.fun bonding curve, PumpSwap AMM, AND Raydium —
            // covering most SPL tokens with venue support, not just `pump`
            // suffix mints. We try PumpPortal FIRST for every sell. If it
            // 400s (un-routable: deep-graduated Orca-only, insufficient
            // liquidity, etc.) we fall through to the Jupiter Ultra → Metis
            // ladder, then to a final PUMP-RESCUE retry at higher slip.
            val lsPumpSlip = if (isDrainExit) 75 else 30
            val lsPumpJito = c.jitoEnabled
            val lsPumpTip = com.lifecyclebot.network.JitoTipFetcher
                .getDynamicTip(c.jitoTipLamports)
                .let { if (isDrainExit) (it * 2).coerceAtMost(1_000_000L) else it }
            sig = tryPumpPortalSell(
                ts = ts,
                wallet = wallet,
                tokenUnits = tokenUnits,
                slipPct = lsPumpSlip,
                priorityFeeSol = if (isDrainExit) 0.0005 else 0.0001,
                useJito = lsPumpJito,
                jitoTipLamports = lsPumpTip,
                sellTradeKey = sellTradeKey,
                traderTag = "MEME",
                labelTag = if (isDrainExit) "EXIT-DRAIN" else "EXIT",
            )
            // V5.9.492 — skip Jupiter ladder entirely if PUMP-FIRST landed.
            // V5.9.495d — Ultra-first fallback signal. liveSell uses
            // getQuoteWithTaker which prefers Jupiter Ultra v2 then v6
            // Metis on RFQ rejection. Log the intent so operator sees the
            // escalation chain end-to-end on the forensics tile.
            if (sig == null) {
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.INFO,
                    "↩ FALLBACK → Jupiter Ultra (v2) primary, v6 Metis secondary | ladder=${broadcastSlipLadder.joinToString("/")}bps",
                    traderTag = "MEME",
                )
            }
            for (currentSlip in if (sig != null) emptyList() else broadcastSlipLadder) {
                broadcastAttempts++
                try {
                    // Re-quote at the new slippage tier on attempts 2+. The
                    // first attempt re-uses the quote obtained by the quote
                    // loop above (no extra Jupiter roundtrip).
                    if (broadcastAttempts > 1) {
                        onLog("⚡ SLIPPAGE ESCALATION: re-quoting ${ts.symbol} @ ${currentSlip}bps (attempt $broadcastAttempts)…", tradeId.mint)
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_QUOTE_TRY,
                            "⚡ ESCALATION re-quote @ ${currentSlip}bps (attempt $broadcastAttempts)",
                            slippageBps = currentSlip, traderTag = "MEME",
                        )
                        try {
                            quote = getQuoteWithSlippageGuard(
                                ts.mint, JupiterApi.SOL_MINT, tokenUnits, currentSlip,
                                isBuy = false, sellTaker = wallet.publicKeyB58)
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_QUOTE_OK,
                                "Re-quote OK @ ${currentSlip}bps | out=${quote.outAmount} | router=${quote.router}${if (quote.isRfqRoute) " (RFQ)" else ""}${if (quote.ultraRejectedReason.isNotBlank()) " ⚠ Ultra REJECTED" else ""}",
                                slippageBps = currentSlip, traderTag = "MEME",
                            )
                        } catch (qex: Exception) {
                            lastBroadcastException = qex
                            onLog("⚠️ Re-quote failed @ ${currentSlip}bps: ${qex.message?.take(50)}", tradeId.mint)
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_QUOTE_FAIL,
                                "Re-quote ${currentSlip}bps FAILED: ${qex.message?.take(80) ?: "?"}",
                                slippageBps = currentSlip, traderTag = "MEME",
                            )
                            continue
                        }
                    }

                    onLog("📊 SELL DEBUG: Building transaction (slip=${currentSlip}bps attempt=$broadcastAttempts)...", tradeId.mint)
                    // V5.9.482 — widen dynamicSlippageMaxBps so Jupiter's simulation has
                    // real room to pick a sensible slippageBps. Static currentSlip
                    // (e.g. 200bps) was too tight for Jupiter to encode anything
                    // useful — every tier failed at simulation. Now: 2000bps cap
                    // on normal sells, 9999bps on drain-exit, ramping up across
                    // in-line retries. Jupiter's simulation picks the actual
                    // value within bounds based on real pool state.
                    val dynSlipCap = com.lifecyclebot.engine.sell.SellSafetyPolicy.maxSlippageBps(reason).coerceAtLeast(currentSlip)
                    val txResult = buildTxWithRetry(quote!!, wallet.publicKeyB58, dynamicSlippageMaxBps = dynSlipCap)
                    onLog("📊 SELL DEBUG: Transaction built | requestId=${txResult.requestId?.take(16) ?: "none"}", tradeId.mint)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_TX_BUILT,
                        "Tx built | router=${txResult.router} | rfq=${txResult.isRfqRoute} | slip=${currentSlip}bps (attempt $broadcastAttempts)" + (if (txResult.dynSlipPickedBps >= 0) " | dyn-slip picked=${txResult.dynSlipPickedBps}bps incurred=${txResult.dynSlipIncurredBps}bps" else ""),
                        traderTag = "MEME",
                    )
                    // V5.9.767 — drive SellJobRegistry state machine end-to-end.
                    try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.BUILDING) } catch (_: Throwable) {}
                    security.enforceSignDelay()

                    val useJito = c.jitoEnabled && !quote!!.isUltra
                    // V5.9.483 — dynamic Jito tip from bundles.jito.wtf 75th percentile.
                    val jitoTip = com.lifecyclebot.network.JitoTipFetcher
                        .getDynamicTip(c.jitoTipLamports)
                        .let { if (isDrainExit) (it * 2).coerceAtMost(1_000_000L) else it }

                    if (quote!!.isUltra) {
                        onLog("🚀 Broadcasting sell via Jupiter Ultra (Beam MEV protection) @ ${currentSlip}bps…", ts.mint)
                    } else if (useJito) {
                        onLog("⚡ Broadcasting sell tx via Jito MEV protection @ ${currentSlip}bps…", ts.mint)
                    } else {
                        onLog("Broadcasting sell tx @ ${currentSlip}bps…", ts.mint)
                    }
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_BROADCAST,
                        "Broadcasting @ ${currentSlip}bps | route=${if (quote!!.isUltra) "ULTRA" else if (useJito) "JITO" else "RPC"} (attempt $broadcastAttempts)",
                        traderTag = "MEME",
                    )
                    // V5.9.767 — drive SellJobRegistry state machine end-to-end.
                    try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.BROADCASTING) } catch (_: Throwable) {}

                    onLog("📊 SELL DEBUG: Signing and broadcasting (router=${txResult.router}, rfq=${txResult.isRfqRoute})...", tradeId.mint)
                    val ultraReqId = if (quote!!.isUltra) txResult.requestId else null
                    sig = wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
                    onLog("📊 SELL DEBUG: Transaction confirmed! sig=${sig.take(20)}...", tradeId.mint)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_CONFIRMED,
                        "✅ Sell tx confirmed on-chain @ ${currentSlip}bps (attempt $broadcastAttempts)",
                        sig = sig, traderTag = "MEME",
                    )
                    // V5.9.767 — drive SellJobRegistry state machine end-to-end.
                    try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.CONFIRMING) } catch (_: Throwable) {}
                    break  // success — exit slippage ladder
                } catch (broadcastEx: Exception) {
                    lastBroadcastException = broadcastEx
                    val safe = security.sanitiseForLog(broadcastEx.message ?: "unknown")
                    val isSlippage = safe.contains("0x1788", ignoreCase = true) ||
                                     safe.contains("0x1789", ignoreCase = true) ||
                                     safe.contains("TooLittleSolReceived", ignoreCase = true) ||
                                     safe.contains("Slippage", ignoreCase = true) ||
                                     safe.contains("SlippageToleranceExceeded", ignoreCase = true)
                    if (!isSlippage) {
                        // Non-slippage failure (SIM, RFQ, RATE_LIMITED, TX_EXPIRED,
                        // INSUFFICIENT_FUNDS, JUPITER_BUILD_FAILED, …). Re-quoting
                        // at higher slippage won't help — re-throw to the outer
                        // catch for classification and retry-queue handoff.
                        throw broadcastEx
                    }
                    // Slippage error — bump the lifetime counter (forensics +
                    // cross-call escalation), log, and try the next tier.
                    val brc = zeroBalanceRetries.merge(ts.mint + "_broadcast", 1) { old, _ -> old + 1 } ?: 1
                    onLog("⚡ SLIPPAGE @ ${currentSlip}bps — escalating IMMEDIATELY (lifetime broadcast retries=$brc)…", tradeId.mint)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.SELL_FAILED,
                        "SLIPPAGE @ ${currentSlip}bps (in-line attempt $broadcastAttempts) — escalating",
                        slippageBps = currentSlip, traderTag = "MEME",
                    )
                    // Loop continues to next slippage tier
                }
            }

            // V5.9.488 — PUMP.FUN DIRECT FALLBACK.
            //
            // If all Jupiter slippage tiers failed AND this is a pump.fun
            // mint (suffix 'pump'), try one final direct sell via PumpPortal
            // Lightning local-trading API. This bypasses Jupiter entirely
            // and routes through pump.fun's own bonding curve / PumpSwap
            // AMM — the venue that originally minted and listed the token.
            //
            // Why this works when Jupiter doesn't: pump.fun's program
            // accepts the SELL side natively (Jupiter RFQ providers refuse
            // it, and Metis dynamicSlippage simulator consistently lowballs
            // the picked slip on illiquid memes). The pump.fun program
            // computes minSolOut = expected * (1 - slippagePercent/100)
            // and that's the only on-chain bound — no oracle, no
            // simulation gap.
            //
            // We use a HIGH slippage percent (drain-exit: 75%, normal: 30%)
            // because the operator has just exhausted 5 Jupiter attempts
            // and is far past caring about "fair" execution — they need
            // the bag out of the wallet at any price.
            // V5.9.488 — PUMP.FUN / PUMPSWAP DIRECT FALLBACK (all mints).
            //
            // V5.9.493: dropped the .endsWith("pump") gate. Operator insight:
            // 'most of solana can be accessed and traded via pumpfun and
            // pumpswap these days so may be able to use that to our
            // advantage'. PumpPortal Lightning's pool='auto' routes through
            // pump.fun bonding curve, PumpSwap AMM, AND Raydium pools — so
            // it can dump graduated tokens (CORE), Raydium-only memes
            // (ISLAND, AALIEN, JOURNEY), even SHARPIE. Jupiter exhausting
            // means the bag is sticky AT JUPITER's routes; PumpPortal's
            // separate router pool may still find a way out.
            //
            // If all Jupiter slippage tiers failed and PUMP-FIRST didn't
            // already try (V5.9.492), invoke the direct-route fallback as
            // a last attempt. Drain-exit gets 75% slippage, normal 30%.
            // V5.9.495 — PUMP RESCUE retry. If both PUMP-FIRST and the
            // entire Jupiter ladder failed, take one more shot at PumpPortal
            // with bumped slippage (50% normal / 90% drain). PumpPortal's
            // pool="auto" router covers bonding curve + PumpSwap + Raydium,
            // so a tighter market might land here when Jupiter and the
            // initial PUMP-FIRST attempt couldn't.
            if (sig == null) {
                val rescueSlip = if (isDrainExit) 90 else 50
                val rescueJito = c.jitoEnabled
                val rescueTip = com.lifecyclebot.network.JitoTipFetcher
                    .getDynamicTip(c.jitoTipLamports)
                    .let { if (isDrainExit) (it * 2).coerceAtMost(1_000_000L) else it }
                sig = tryPumpPortalSell(
                    ts = ts,
                    wallet = wallet,
                    tokenUnits = tokenUnits,
                    slipPct = rescueSlip,
                    priorityFeeSol = if (isDrainExit) 0.0008 else 0.0003,
                    useJito = rescueJito,
                    jitoTipLamports = rescueTip,
                    sellTradeKey = sellTradeKey,
                    traderTag = "MEME",
                    labelTag = if (isDrainExit) "EXIT-DRAIN-RESCUE" else "EXIT-RESCUE",
                )
            }

            if (sig == null) {
                // All in-line slippage tiers exhausted — re-throw to outer
                // catch so we get a classified SELL_FAILED + PendingSellQueue
                // hand-off so it retries again in seconds (V5.9.478 also
                // bumps the queue from %10 → %1 so it's almost immediate).
                throw lastBroadcastException ?: RuntimeException(
                    "${ts.symbol}: all in-line broadcast retries exhausted at ${broadcastSlipLadder.last()}bps")
            }

            // V5.9.478 — capture the final non-null quote (the one whose
            // broadcast actually landed) so downstream PnL maths and
            // logging don't trip Kotlin's nullable-receiver checks.
            val finalQuote: com.lifecyclebot.network.SwapQuote = quote!!
            
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
            
            // V5.9.495y/z — TRADE-VERIFIER FULL-SELL AUTHORITY (spec items
            // 6, 8). Run tx-parse first; if LANDED we skip the legacy
            // wallet-poll heuristics + dust-buster entirely. Operator:
            // "I still want the sell verified and that the tokens clear
            // the wallet and return the sol" — verifier requires BOTH
            // tokens cleared AND SOL received before declaring LANDED.
            var fullSellVerifiedSol: Long = -1L
            var verifierLanded = false
            run {
                if (sig.isBlank() || sig.startsWith("PHANTOM_")) return@run
                // V5.9.767 — drive SellJobRegistry state machine end-to-end.
                try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.VERIFYING) } catch (_: Throwable) {}
                val vsr = try {
                    TradeVerifier.verifySell(wallet, sig, ts.mint, timeoutMs = 60_000L)
                } catch (vfx: Throwable) {
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.WARNING,
                        "⚠ TradeVerifier(full-sell) threw: ${vfx.message?.take(60)}",
                        sig = sig, traderTag = "MEME",
                    )
                    null
                }
                when (vsr?.outcome) {
                    TradeVerifier.Outcome.LANDED -> {
                        fullSellVerifiedSol = vsr.solReceivedLamports
                        verifierLanded = true
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_TX_PARSE_OK,
                            "✅ FULL SELL LANDED: rawConsumed=${vsr.rawTokenConsumed} ui=${vsr.uiTokenConsumed.fmt(4)} solReceived=${vsr.solReceivedLamports} lam${if (vsr.tokenAccountClosedFullExit) " (ATA closed)" else ""}",
                            sig = sig, tokenAmount = vsr.uiTokenConsumed, traderTag = "MEME",
                        )
                        // V5.9.767 — terminal LANDED state. Idempotent; reconciler
                        // also calls markLanded when on-chain balance reaches zero.
                        try { com.lifecyclebot.engine.sell.SellJobRegistry.markLanded(ts.mint, sig) } catch (_: Throwable) {}
                    }
                    TradeVerifier.Outcome.FAILED_CONFIRMED -> {
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_FAILED_CONFIRMED,
                            "🚨 FULL SELL FAILED_CONFIRMED: meta.err=${vsr.txErr}",
                            sig = sig, traderTag = "MEME",
                        )
                        // V5.9.767 — terminal FAILED_FINAL state.
                        try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.FAILED_FINAL) } catch (_: Throwable) {}
                        TradeVerifier.endSell(ts.mint)
                        throw RuntimeException("Sell failed on-chain: ${vsr.txErr}")
                    }
                    TradeVerifier.Outcome.INCONCLUSIVE_PENDING,
                    TradeVerifier.Outcome.VERIFICATION_ERROR -> {
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                            "⏳ FULL SELL INCONCLUSIVE — falling through to legacy wallet-poll + dust-buster",
                            sig = sig, traderTag = "MEME",
                        )
                    }
                    null -> {}
                }
            }
            
            try {
                if (verifierLanded) {
                    // Verifier proved both sides on-chain — skip legacy wallet-poll/dust-buster.
                    onLog("✅ FULL SELL VERIFIED VIA TX-PARSE: ${ts.symbol} sig=${sig.take(20)}…", tradeId.mint)
                } else {
                    Thread.sleep(1500)
                val postSellBalances = wallet.getTokenAccountsWithDecimals()
                val postSellMapEmpty = postSellBalances.isEmpty()
                val remainingTokens = postSellBalances[ts.mint]?.first ?: 0.0

                // V5.9.495z39 — operator spec items 1/4/6/7/8 wiring.
                // SellFinalizationCoordinator runs the auditor + tx-meta
                // finalizer + proportional-PnL calc + wallet refresh +
                // canonical SELL_LANDED forensics in a single pass.
                // Best-effort; never breaks the sell flow.
                try {
                    val decimals = postSellBalances[ts.mint]?.second
                        ?: com.lifecyclebot.engine.TokenLifecycleTracker.getEntryMetadata(ts.mint)?.entryDecimals
                        ?: 9
                    val preTokenRaw = run {
                        val ui = pos.qtyToken
                        if (ui > 0.0 && decimals > 0) java.math.BigDecimal(ui).movePointRight(decimals).toBigInteger()
                        else java.math.BigInteger.ZERO
                    }
                    val postTokenRaw = run {
                        val ui = remainingTokens
                        if (decimals > 0) java.math.BigDecimal(ui).movePointRight(decimals).toBigInteger()
                        else java.math.BigInteger.ZERO
                    }
                    val walletPollRaw = if (postSellMapEmpty) null else postTokenRaw
                    val entryMeta = com.lifecyclebot.engine.TokenLifecycleTracker.getEntryMetadata(ts.mint)
                    val entrySolSpent = entryMeta?.entrySolSpent?.takeIf { it > 0.0 } ?: pos.costSol
                    val entryTokenRaw = entryMeta?.entryTokenRawConfirmed?.takeIf { it.signum() > 0 } ?: preTokenRaw
                    val intent = com.lifecyclebot.engine.sell.SellIntent.build(
                        mint = ts.mint,
                        symbol = ts.symbol,
                        // liveSell is the FULL-balance path. Partial-class
                        // reasons (PROFIT_LOCK / CAPITAL_RECOVERY /
                        // PARTIAL_TAKE_PROFIT) are forbidden by SellIntent
                        // when fraction=10_000 + drain=true; they must be
                        // promoted to HARD_STOP / RUG_DRAIN here.
                        reason = com.lifecyclebot.engine.sell.SellReasonClassifier.fullExitFromString(reason),
                        requestedFractionBps = 10_000,    // liveSell is full-balance path
                        confirmedWalletRaw = preTokenRaw.max(java.math.BigInteger.ONE),
                        decimals = decimals,
                        slippageBps = 0,                  // bookkeeping only — actual slip lives in profile
                        emergencyDrain = true,
                        entrySolSpent = entrySolSpent.coerceAtLeast(0.0),
                        entryTokenRaw = entryTokenRaw.max(java.math.BigInteger.ONE),
                    )
                    com.lifecyclebot.engine.sell.SellFinalizationCoordinator.finalize(
                        intent = intent,
                        preTokenBalanceRaw = preTokenRaw,
                        postTokenBalanceRaw = postTokenRaw,
                        walletPollRaw = walletPollRaw,
                        solReceivedLamports = 0L,           // unknown here; coordinator will use sellSolReceived
                        sellSolReceived = 0.0,              // realised SOL is logged elsewhere; pass 0 to mark degenerate
                        feesSol = 0.0,
                        decimals = decimals,
                        slippageUsedBps = 0,
                        sellSig = sig,
                        traderTag = "MEME",
                    )
                } catch (e: Throwable) {
                    com.lifecyclebot.engine.ErrorLogger.warn("Executor",
                        "SellFinalizationCoordinator.finalize threw (non-fatal): ${e.message}")
                }

                val originalTokens = pos.qtyToken
                if (postSellMapEmpty) {
                    // V5.9.495w — operator triage 06 May 2026: SHELTERCOIN
                    // sell DID succeed on-chain (coins left wallet, SOL
                    // returned), but my V5.9.495t threw RuntimeException
                    // here on RPC empty-map → bot retried a sell that had
                    // already settled. Reverse the polarity: when the
                    // broadcast signature has already been logged as
                    // SELL_CONFIRMED (sig != null, came back from
                    // tryPumpPortalSell), we TRUST the sig and let the
                    // async watchdog reconcile in the background.
                    // Throwing on empty-map produced false SELL_FAILED +
                    // SELL_STUCK forensics on a real on-chain success.
                    onLog("⚠️ POST-SELL VERIFY: RPC empty-map — trusting confirmed sig (async watchdog will reconcile)", tradeId.mint)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.WARNING,
                        "⚠️ POST-SELL: RPC empty-map blip — trusting SELL_CONFIRMED sig optimistically. Async watchdog continues.",
                        traderTag = "MEME",
                    )
                    // Fall through to normal success path. If the chain
                    // really didn't settle, the async watchdog logs the
                    // gap and PendingSellQueue retries naturally.
                } else if (originalTokens > 0 && remainingTokens > originalTokens * 0.01) {
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
                                                                       remainingUnits, 1500, isBuy = false,
                                                                       sellTaker = wallet.publicKeyB58)
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
                    // V5.9.767 — terminal LANDED state via legacy verify path.
                    try { com.lifecyclebot.engine.sell.SellJobRegistry.markLanded(ts.mint, sig) } catch (_: Throwable) {}
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
                } // end else (verifierLanded == false → legacy path)
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
                                                                           retryUnits, 2000, isBuy = false,
                                                                           sellTaker = wallet.publicKeyB58)
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
            // V5.9.495x — operator triage 06 May 2026 (Trade Journal screenshot):
            // pippin capital_recovery_90.0x recorded +100277.7% / +A$ 4,485.12 — a
            // false journal entry. Root cause: when the post-sell SOL delta
            // came back ≤ 0.001 (chain didn't settle, or RPC lag, or fees ate
            // it), the fallback used `finalQuote.outAmount` — Jupiter's
            // PRE-trade estimate — which on illiquid pumped tokens is
            // wildly inflated. Same path produced -100% HKF3ZGpx losses
            // (sell broadcast but no SOL came back → 0 delta → quote
            // inflated → eventual subtraction → -100%).
            // Fix: retry SOL balance read 5×3s with backoff. If we still
            // see no delta, write a SCRATCH entry (solBack = costSol, 0%
            // PnL) instead of credulously trusting the inflated quote.
            val solBack: Double = if (fullSellVerifiedSol > 0L) {
                // V5.9.495z — when TradeVerifier proved the on-chain SOL
                // delta on this same sig, use that as the authoritative
                // value. Bypasses the SOL-balance-poll race that produced
                // the +100277% pippin / -100% HKF3ZGpx phantom journal
                // entries. Operator: "I still want the sell verified and
                // that the tokens clear the wallet and return the sol".
                val verifiedSol = fullSellVerifiedSol / 1_000_000_000.0
                onLog("📊 SELL PnL (verifier): received=${verifiedSol.fmt(6)} SOL (tx-parse authoritative)", tradeId.mint)
                pos.costSol + verifiedSol
            } else try {
                var actualBalance = wallet.getSolBalance()
                var delta = actualBalance - walletSol
                if (delta <= 0.001) {
                    // Retry up to 5×3s (15s window) — handles RPC lag.
                    var attempt = 1
                    while (attempt <= 5 && delta <= 0.001) {
                        try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                        actualBalance = try { wallet.getSolBalance() } catch (_: Throwable) { actualBalance }
                        delta = actualBalance - walletSol
                        attempt++
                    }
                }
                if (delta > 0.001) {
                    onLog("📊 SELL PnL (actual): received=${delta.fmt(6)} SOL | quoted=${(finalQuote.outAmount / 1_000_000_000.0).fmt(6)} SOL", tradeId.mint)
                    pos.costSol + delta  // costSol + delta = total back (delta = net SOL gain/loss vs cost)
                } else {
                    // V5.9.495x — no on-chain SOL delta after 15s. Refuse
                    // to trust the inflated `quote.outAmount`; record as
                    // SCRATCH so the journal isn't poisoned by phantom
                    // wins/losses on tx that didn't actually settle.
                    onLog("📊 SELL PnL: no SOL delta after 15s retry — recording as SCRATCH (no quote-inflation in journal)", tradeId.mint)
                    LiveTradeLogStore.log(
                        sellTradeKey, ts.mint, ts.symbol, "SELL",
                        LiveTradeLogStore.Phase.WARNING,
                        "📊 SELL PnL=SCRATCH: no on-chain SOL delta after 15s retry. Journal entry will show 0% — refusing to use inflated quote.outAmount.",
                        traderTag = "MEME",
                    )
                    pos.costSol  // SCRATCH: solBack == costSol → 0% PnL
                }
            } catch (balEx: Exception) {
                // Even SOL balance read failed entirely — record SCRATCH
                // rather than gamble with the quote.
                onLog("📊 SELL PnL: balance read failed (${balEx.message?.take(40)}) — recording as SCRATCH", tradeId.mint)
                pos.costSol
            }
            pnl  = solBack - pos.costSol
            pnlP = pct(pos.costSol, solBack)
            val (netPnl, feeSol) = slippageGuard.calcNetPnl(pnl, pos.costSol)

            // V5.9.357 — feed real Jupiter quote-vs-realized slip into
            // ExecutionCostPredictorAI so its per-liquidity-band history
            // finally accumulates samples. quote.outAmount = optimistic
            // estimate; solBack = actual SOL received post-fees/MEV.
            try {
                val quotedSol = finalQuote.outAmount / 1_000_000_000.0
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

            // V5.9.495z43 operator spec item E — record sell signature +
            // schedule a reconciler pass after every confirmed sell so the
            // host_tracker.last_sell_signature stops being "" forever.
            try {
                com.lifecyclebot.engine.sell.LiveWalletReconciler.recordSellSignature(ts.mint, sig)
                com.lifecyclebot.engine.sell.LiveWalletReconciler.reconcileNow(wallet, "sell_confirmed_${ts.mint.take(6)}")
            } catch (_: Throwable) { /* fail-soft */ }


            // V5.9.495z39 — operator spec items 4 / 8 / 9 wiring.
            // Now that solBack/feeSol/netPnl are known, emit the canonical
            // SELL_LANDED forensics row with chain-confirmed proportional
            // cost-basis PnL. Belt-and-braces with the existing journal —
            // this row uses the new field names the operator asked for.
            try {
                val decFinal = com.lifecyclebot.engine.TokenLifecycleTracker
                    .getEntryMetadata(ts.mint)?.entryDecimals ?: 9
                val entryMetaFinal = com.lifecyclebot.engine.TokenLifecycleTracker
                    .getEntryMetadata(ts.mint)
                val entrySolSpentFinal = entryMetaFinal?.entrySolSpent
                    ?.takeIf { it > 0.0 } ?: pos.costSol
                val entryTokenRawFinal = entryMetaFinal?.entryTokenRawConfirmed
                    ?.takeIf { it.signum() > 0 }
                    ?: java.math.BigDecimal(pos.qtyToken).movePointRight(decFinal).toBigInteger()
                        .max(java.math.BigInteger.ONE)
                val preTokenRawFinal = java.math.BigDecimal(pos.qtyToken)
                    .movePointRight(decFinal).toBigInteger().max(java.math.BigInteger.ONE)
                val intentFinal = com.lifecyclebot.engine.sell.SellIntent.build(
                    mint = ts.mint,
                    symbol = ts.symbol,
                    reason = com.lifecyclebot.engine.sell.SellReasonClassifier.fullExitFromString(reason),
                    requestedFractionBps = 10_000,
                    confirmedWalletRaw = preTokenRawFinal,
                    decimals = decFinal,
                    slippageBps = 0,
                    emergencyDrain = true,
                    entrySolSpent = entrySolSpentFinal.coerceAtLeast(0.0),
                    entryTokenRaw = entryTokenRawFinal,
                )
                com.lifecyclebot.engine.sell.SellFinalizationCoordinator.finalize(
                    intent = intentFinal,
                    preTokenBalanceRaw = preTokenRawFinal,
                    postTokenBalanceRaw = java.math.BigInteger.ZERO,
                    walletPollRaw = java.math.BigInteger.ZERO,
                    solReceivedLamports = (solBack * 1_000_000_000.0).toLong().coerceAtLeast(0L),
                    sellSolReceived = solBack,
                    feesSol = feeSol,
                    decimals = decFinal,
                    slippageUsedBps = 0,
                    sellSig = sig,
                    traderTag = "MEME",
                )
            } catch (e: Throwable) {
                com.lifecyclebot.engine.ErrorLogger.warn("Executor",
                    "SellFinalizationCoordinator [final] threw (non-fatal): ${e.message}")
            }

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

            // V5.9.468 — CRITICAL FORENSICS FIX (operator-reported sell silent-kill).
            //
            // Operator screenshot showed SELL_QUOTE_OK firing repeatedly with
            // no SELL_TX_BUILT / SELL_BROADCAST / SELL_FAILED phase ever
            // logged after it — only a toast 'SELL FAILED: Normie (attempt 3)'.
            // Root cause: this catch handles every post-quote failure (Jupiter
            // build-tx / sign / Ultra execute / RFQ-only-path / awaitConfirm)
            // but only emitted onLog + onToast, never a LiveTradeLogStore phase.
            // The forensics tile therefore froze at SELL_QUOTE_OK and the user
            // had no way to see WHY the sell died. Real silent-kill bug.
            //
            // Now: every post-quote failure surfaces a SELL_FAILED phase with
            // the exception class + sanitised message so the forensics card
            // ALWAYS terminates in a visible failure reason. The bot still
            // returns FAILED_RETRYABLE (position stays open, no fake CONFIRMED)
            // — only the visibility was missing.
            //
            // We also extract whether the failure was likely a Jupiter API
            // build/execute failure vs an RPC simulation failure vs a rent /
            // insufficient-funds failure, so the user can immediately see
            // the class of problem.
            val failureClass = when {
                // V5.9.470 — fix misclassification observed in operator forensics:
                // pump.fun / Raydium bonding-curve programs throw 0x1788 (6024 =
                // 'TooLittleSolReceived' / 'SlippageExceeded') when on-chain
                // price drifts past the quote tolerance. Was previously bucketed
                // as INSUFFICIENT_FUNDS (because the generic '0x1' substring
                // match was too greedy) which sent the user looking at their
                // wallet balance instead of the slippage cap.
                safe.contains("0x1788", ignoreCase = true) ||
                safe.contains("0x1789", ignoreCase = true) ||
                safe.contains("TooLittleSolReceived", ignoreCase = true) ||
                safe.contains("Slippage", ignoreCase = true) -> "SLIPPAGE_EXCEEDED"

                safe.contains("simulation failed", ignoreCase = true) -> "SIM_FAILED"
                safe.contains("RFQ", ignoreCase = true) -> "RFQ_ROUTE_FAILED"
                safe.contains("blockhash", ignoreCase = true) ||
                safe.contains("expired", ignoreCase = true) -> "TX_EXPIRED"
                safe.contains("rate limit", ignoreCase = true) ||
                safe.contains("429", ignoreCase = true) -> "RATE_LIMITED"
                safe.contains("timeout", ignoreCase = true) -> "TIMEOUT"
                // Insufficient SOL for tx fees / rent. Be more specific than
                // before — only match on actual rent / 0x1 (token program
                // 'InsufficientFunds') / explicit 'insufficient' wording.
                safe.contains("InsufficientFunds", ignoreCase = true) ||
                safe.contains("insufficient lamports", ignoreCase = true) ||
                safe.contains("rent", ignoreCase = true) -> "INSUFFICIENT_FUNDS"
                safe.contains("buildSwapTx", ignoreCase = true) ||
                safe.contains("transactionBase64", ignoreCase = true) -> "JUPITER_BUILD_FAILED"
                else -> "BROADCAST_FAILED"
            }
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_FAILED,
                "$failureClass — ${e.javaClass.simpleName}: ${safe.take(120)} (attempt $broadcastRetries)",
                traderTag = "MEME",
            )
            ErrorLogger.warn("Executor", "SELL POST-QUOTE FAILURE: ${ts.symbol} class=$failureClass " +
                "exc=${e.javaClass.simpleName} msg=${safe.take(80)} retry=$broadcastRetries")

            if (broadcastRetries >= 5 && broadcastRetries % 5 == 0) {
                // Louder notification every 5 consecutive failures — but we
                // still don't close. User decides when to give up.
                onLog("🚨 SELL BROADCAST STUCK: ${ts.symbol} — $broadcastRetries consecutive " +
                      "broadcast failures. Tokens still in wallet. Position remains OPEN.", tradeId.mint)
                onNotify("🚨 Sell Stuck",
                    "${ts.symbol}: $broadcastRetries broadcast attempts failed ($failureClass). " +
                    "Tokens remain. Retry continues; clear manually if you want to stop.",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                ErrorLogger.warn("Executor", "SELL BROADCAST STUCK: ${ts.symbol} after $broadcastRetries retries — " +
                    "position kept OPEN, no fake CONFIRMED.")
            }

            onNotify("Sell Failed",
                "${ts.symbol}: $failureClass — ${safe.take(80)} (retry $broadcastRetries)",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            onToast("SELL FAILED: ${ts.symbol} ($failureClass · attempt $broadcastRetries)")
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
        try { HostWalletTokenTracker.recordSellConfirmed(ts.mint, ts.symbol, exitPrice, pnlP, "PAPER_EXIT") } catch (_: Exception) {}
        
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
        
        // V5.9.720: clear any stale paper sell locks BEFORE iterating positions.
        // A lock left over from a crashed mid-sell (exception before finally block fires,
        // or from a session started before the try/finally fix) would cause paperSell()
        // to return ALREADY_CLOSED for every position → wallet never refunded on stop.
        clearAllPaperSellLocks()

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
                
                // V5.9.751b — route on POSITION isPaper, not config. Previously
                // a live position with wallet=null during shutdown silently
                // booked a paperSell (phantom close: position numbers updated
                // but tokens stayed on-chain). Now: paper positions paperSell,
                // live positions liveSell. If a live position has no wallet,
                // skip the close so the next session reconciler can adopt it.
                val isPaperPos = pos.isPaperPosition
                when {
                    isPaperPos -> {
                        paperSell(ts, "bot_shutdown")
                        closedCount++
                    }
                    wallet != null -> {
                        liveSell(ts, "bot_shutdown", wallet, walletSol)
                        closedCount++
                    }
                    else -> {
                        ErrorLogger.warn("Executor",
                            "🚫 SHUTDOWN_CLOSE_DEFERRED: ${ts.symbol} — live position with no wallet. " +
                            "Position left OPEN for next-session reconciler to adopt.")
                        onLog("⏸ Live ${ts.symbol} left open — wallet disconnected at shutdown.", ts.mint)
                    }
                }
                
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

                // V5.9.478 — match the liveSell escalation ladder so the
                // shutdown sweep doesn't fail-stuck on volatile pump.fun memes
                // at 500bps when liveSell escalates up to 1000bps elsewhere.
                // Operator: 'when you stop the bot all tokens remain in the
                // host wallet'. Root cause: this sweep capped at 500bps and
                // bypassed getQuoteWithSlippageGuard (so RFQ routes weren't
                // taker-bound, broadcast hit 0x1788 and silently failed at
                // line 7641). Now: 200/400/600/1000bps, taker-bound binding
                // sell quote (RFQ-aware), and broadcast failures within the
                // ladder loop fall through to the next tier instead of
                // bubbling up to the outer 'token left in wallet' catch.
                val slippageLevels = listOf(200, 400, 600, 1000)
                var quote: com.lifecyclebot.network.SwapQuote? = null
                var sweepSig: String? = null
                var sweepLastEx: Exception? = null
                sweep@ for (slip in slippageLevels) {
                    try {
                        quote = getQuoteWithSlippageGuard(
                            mint, JupiterApi.SOL_MINT, rawUnits, slip,
                            isBuy = false, sellTaker = wallet.publicKeyB58)
                    } catch (e: Exception) {
                        sweepLastEx = e
                        onLog("⚠️ SWEEP $symbol: quote ${slip}bps failed — ${e.message?.take(50)}", mint)
                        Thread.sleep(250)
                        continue@sweep
                    }
                    // Got a quote at this tier — try to broadcast.
                    try {
                        val txResultSweep = buildTxWithRetry(quote, wallet.publicKeyB58, dynamicSlippageMaxBps = (slip * 5).coerceIn(slip, 9999))
                        security.enforceSignDelay()
                        val useJito = c.jitoEnabled && !quote.isUltra
                        val ultraReqId = if (quote.isUltra) txResultSweep.requestId else null
                        sweepSig = wallet.signSendAndConfirm(
                            txResultSweep.txBase64, useJito, com.lifecyclebot.network.JitoTipFetcher.getDynamicTip(c.jitoTipLamports),
                            ultraReqId, c.jupiterApiKey, txResultSweep.isRfqRoute,
                        )
                        break@sweep
                    } catch (bex: Exception) {
                        sweepLastEx = bex
                        val safeSweep = security.sanitiseForLog(bex.message ?: "unknown")
                        val isSweepSlip = safeSweep.contains("0x1788", ignoreCase = true) ||
                                          safeSweep.contains("0x1789", ignoreCase = true) ||
                                          safeSweep.contains("TooLittleSolReceived", ignoreCase = true) ||
                                          safeSweep.contains("Slippage", ignoreCase = true)
                        if (isSweepSlip) {
                            onLog("⚡ SWEEP $symbol: SLIPPAGE @ ${slip}bps — escalating to next tier", mint)
                            continue@sweep
                        }
                        // Non-slippage broadcast failure — bubble out of the loop.
                        throw bex
                    }
                }
                if (quote == null || sweepSig == null) {
                    onLog("🚨 SWEEP $symbol: ladder exhausted (${sweepLastEx?.message?.take(60) ?: "no quote"}). Token left in wallet.", mint)
                    continue
                }
                onLog("✅ SWEEP SOLD $symbol: ${balanceUi.fmt(4)} → SOL | sig=${sweepSig.take(16)}…", mint)
                LiveTradeLogStore.log(
                    tokenSweepKey, mint, symbol, "SWEEP",
                    LiveTradeLogStore.Phase.SWEEP_TOKEN_DONE,
                    "✅ Sold ${balanceUi.fmt(4)} → SOL",
                    sig = sweepSig, tokenAmount = balanceUi, traderTag = "SWEEP",
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
        sellTaker: String? = null,  // V5.9.468 — pubkey for taker-bound binding sell order
    ): com.lifecyclebot.network.SwapQuote {
        if (!isBuy) {
            // V5.9.468 — RCA fix: previously called getQuote() (non-binding /order
            // without taker) which always succeeded for RFQ providers even when
            // they would reject the binding order, leaving the sell to fail
            // silently in buildSwapTx. Now we request the binding order at
            // quote time when we have the taker pubkey, so RFQ rejections
            // surface as SELL_QUOTE_FAIL rather than ghosting the trade.
            return if (!sellTaker.isNullOrBlank()) {
                jupiter.getQuoteWithTaker(inMint, outMint, amount, slippageBps, sellTaker)
            } else {
                // Legacy callers that don't have the taker — keep old behaviour
                // (quote-only). Adds no regression risk.
                jupiter.getQuote(inMint, outMint, amount, slippageBps)
            }
        }
        val validated = slippageGuard.validateQuote(inMint, outMint, amount, slippageBps, inputSol)
        if (!validated.isValid) {
            throw Exception(validated.rejectReason)
        }
        return validated.quote
    }

    private fun buildTxWithRetry(
        quote: com.lifecyclebot.network.SwapQuote, pubkey: String,
        dynamicSlippageMaxBps: Int? = null,
    ): com.lifecyclebot.network.SwapTxResult {
        return try {
            jupiter.buildSwapTx(quote, pubkey, dynamicSlippageMaxBps)
        } catch (e: Exception) {
            ErrorLogger.warn("Executor", "⚠️ buildSwapTx attempt 1 failed: ${e.javaClass.simpleName} | ${e.message?.take(120)}")
            Thread.sleep(1000)
            try {
                jupiter.buildSwapTx(quote, pubkey, dynamicSlippageMaxBps)
            } catch (e2: Exception) {
                ErrorLogger.error("Executor", "❌ buildSwapTx attempt 2 ALSO failed: ${e2.javaClass.simpleName} | ${e2.message?.take(120)}")
                throw e2
            }
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

        // V5.9.751b — refuse paper fallback on treasury withdrawal when live.
        if (cfg().paperMode) {
            TreasuryManager.executeWithdrawal(approved, solPx, destinationAddress)
            onLog("PAPER TREASURY WITHDRAWAL: ${approved.fmt(4)}◎", "treasury")
            return "OK_PAPER"
        }
        if (wallet == null) {
            ErrorLogger.error("Executor",
                "🚫 LIVE_TREASURY_WITHDRAWAL_REFUSED: wallet is NULL. Refusing paper-fallback withdrawal.")
            onLog("🚫 Treasury withdrawal blocked: wallet disconnected. Reconnect to continue.", "treasury")
            return "BLOCKED: wallet_disconnected"
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

            // V5.9.495 — PUMP-FIRST routing for orphan sweep.
            // Synthesise a TokenState shim so tryPumpPortalSell can log
            // with the right symbol/mint. We pass a minimal stub.
            val orphanTs = TokenState(mint = mint, symbol = "ORPHAN-${mint.take(4)}")
            val orphanKey = LiveTradeLogStore.keyFor(mint, System.currentTimeMillis())
            val pumpSig = tryPumpPortalSell(
                ts = orphanTs,
                wallet = wallet,
                tokenUnits = sellUnits,
                slipPct = 30,
                priorityFeeSol = 0.0001,
                useJito = c.jitoEnabled,
                jitoTipLamports = c.jitoTipLamports,
                sellTradeKey = orphanKey,
                traderTag = "ORPHAN",
                labelTag = "ORPHAN-SWEEP",
            )

            if (pumpSig != null) {
                onLog("✅ Orphan sold via PumpPortal: $mint | sig=${pumpSig.take(16)}…", mint)
                onNotify("🧹 Orphan Cleanup",
                    "Sold leftover tokens via PumpPortal",
                    com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                true
            } else {

            // Fallback: Jupiter Ultra → Metis ladder.
            val quote = getQuoteWithSlippageGuard(
                mint, JupiterApi.SOL_MINT, sellUnits, sellSlippage, isBuy = false)
            val txResult = buildTxWithRetry(quote, wallet.publicKeyB58)
            
            val useJito = c.jitoEnabled && !quote.isUltra
            val jitoTip = c.jitoTipLamports
            val ultraReqId = if (quote.isUltra) txResult.requestId else null
            
            val sig = try {
                wallet.signSendAndConfirm(txResult.txBase64, useJito, jitoTip, ultraReqId, c.jupiterApiKey, txResult.isRfqRoute)
            } catch (jupEx: Exception) {
                // Final PumpPortal retry at higher slip if Jupiter died too.
                val rescueKey = LiveTradeLogStore.keyFor(mint, System.currentTimeMillis())
                tryPumpPortalSell(
                    ts = orphanTs,
                    wallet = wallet,
                    tokenUnits = sellUnits,
                    slipPct = 50,
                    priorityFeeSol = 0.0002,
                    useJito = c.jitoEnabled,
                    jitoTipLamports = c.jitoTipLamports,
                    sellTradeKey = rescueKey,
                    traderTag = "ORPHAN",
                    labelTag = "ORPHAN-RESCUE",
                ) ?: throw jupEx
            }
            val solBack = if (sig.startsWith("PHANTOM_")) 0.0 else quote.outAmount / 1_000_000_000.0
            
            onLog("✅ Orphan sold: $mint → ${solBack.fmt(4)} SOL | sig=${sig.take(16)}…", mint)
            onNotify("🧹 Orphan Cleanup",
                "Sold leftover tokens → ${solBack.fmt(4)} SOL",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            true
            }
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

    // ═══════════════════════════════════════════════════════════════════
    // V5.9.495 — UNIVERSAL PUMP-FIRST HELPERS
    //
    // Operator directive (Feb 2026): "we now know what works ie pumpfun
    // for the entire sol network basically the Jupiter Ultra then the
    // other callbacks. all buying and selling tools modes traders and
    // sub traders must be checked including the partial take profits and
    // the add to positions and the manual buy and sell buttons."
    //
    // PumpPortal Lightning's pool="auto" routes BUY/SELL through pump.fun
    // bonding curve, PumpSwap AMM, AND Raydium pools — covering most
    // liquid SPL tokens on Solana, not just `pump`-suffix mints.
    //
    // Routing order (every call site uses this ladder):
    //   1) tryPumpPortalSell / tryPumpPortalBuy   ← PumpPortal direct (fast, ~3s)
    //   2) Jupiter Ultra → Metis v6 ladder        ← existing JupiterApi path
    //   3) Final PumpPortal fallback              ← only on sells, after Jupiter ladder
    //
    // Helpers return the signature string on success, or null on any
    // failure (HTTP 400/500, network blip, sign error). Callers MUST
    // fall through to Jupiter on null.
    // ═══════════════════════════════════════════════════════════════════

    /**
     * V5.9.495 — try PumpPortal-FIRST sell. Returns sig on success, null
     * on any failure (caller falls through to Jupiter). Logs to
     * LiveTradeLogStore + onLog with the supplied trader/journal tags.
     */
    private fun tryPumpPortalSell(
        ts: TokenState,
        wallet: SolanaWallet,
        tokenUnits: Long?,
        slipPct: Int,
        priorityFeeSol: Double,
        useJito: Boolean,
        jitoTipLamports: Long,
        sellTradeKey: String,
        traderTag: String,
        labelTag: String,
    ): String? {
        return try {
            val pumpVenue = if (com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(ts.mint))
                "pump.fun" else "universal-auto"

            if (labelTag.contains("PROFIT", ignoreCase = true) ||
                labelTag.contains("PARTIAL", ignoreCase = true) ||
                labelTag.contains("RESCUE", ignoreCase = true) ||
                labelTag.contains("TREASURY", ignoreCase = true) ||
                labelTag.contains("RECOVERY", ignoreCase = true) ||
                labelTag.contains("TAKE_PROFIT", ignoreCase = true) ||
                labelTag.contains("SWEEP", ignoreCase = true)) {
                // V5.9.495z43 operator spec item B — PumpPortal partial sell
                // hard ban. Forensics 20260508_071749 showed Winston/Goldie/
                // GREMLIN consumed 547k–1.25M tokens on supposed 25% sweeps.
                // Until exact-amount semantics are proven, ALL non-full-exit
                // sells route Jupiter exact-in only.
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_ROUTE_FAILED_NO_SIGNATURE,
                    "🚫 SEV_PUMPPORTAL_PARTIAL_BLOCKED label=$labelTag — Jupiter exact-in only.",
                    traderTag = traderTag,
                )
                com.lifecyclebot.engine.sell.PumpPortalKillSwitch.recordPartialAttempt(
                    mint = ts.mint, symbol = ts.symbol, labelTag = labelTag,
                )
                return null
            }
            // V5.9.495z43 operator spec item B — even for "full exit" labels,
            // physically verify the requested fraction is >= 95% of the
            // chain-confirmed wallet balance before allowing PumpPortal.
            if (tokenUnits != null && tokenUnits > 0L) {
                val verifiedRaw = try {
                    val bal = wallet.getTokenAccountsWithDecimals()[ts.mint]
                    val ui = bal?.first ?: 0.0
                    val dec = bal?.second ?: 9
                    if (ui > 0.0) java.math.BigDecimal(ui).movePointRight(dec).toBigInteger() else java.math.BigInteger.ZERO
                } catch (_: Throwable) { java.math.BigInteger.ZERO }
                if (verifiedRaw.signum() > 0) {
                    val requested = java.math.BigInteger.valueOf(tokenUnits)
                    // requested * 100 / verifiedRaw < 95  →  partial sell, reject.
                    val pctTimes100 = requested.multiply(java.math.BigInteger.valueOf(100))
                        .divide(verifiedRaw)
                    if (pctTimes100 < java.math.BigInteger.valueOf(95)) {
                        LiveTradeLogStore.log(
                            sellTradeKey, ts.mint, ts.symbol, "SELL",
                            LiveTradeLogStore.Phase.SELL_ROUTE_FAILED_NO_SIGNATURE,
                            "🚫 SEV_PUMPPORTAL_FRACTION_BLOCKED requested=$tokenUnits verified=$verifiedRaw " +
                            "(${pctTimes100}%) < 95% — Jupiter exact-in only.",
                            traderTag = traderTag,
                        )
                        com.lifecyclebot.engine.sell.PumpPortalKillSwitch.recordPartialAttempt(
                            mint = ts.mint, symbol = ts.symbol,
                            labelTag = "${labelTag}_FRACTION_${pctTimes100}",
                        )
                        return null
                    }
                }
            }

            // V5.9.495d — DEEP FORENSICS for sells. Snapshot wallet SOL +
            // token balance pre-broadcast so the operator can see the
            // before/after at the trade screen. Async post-broadcast watcher
            // logs SELL_VERIFY_TOKEN_GONE / SELL_VERIFY_SOL_RETURNED when
            // the chain catches up — turns silent sells into a visible audit
            // trail end-to-end.
            // V5.9.495t — RPC EMPTY-MAP DETECT (operator triage 06 May 2026:
            // "every buy lands as a phantom" + "sells appear to process but
            // tokens are still in wallet"). Helius/Triton overload returns
            // an empty getTokenAccountsByOwner map; before this fix the
            // PRE-SELL dust check below interpreted that as preTokenQty=0
            // and bailed PHANTOM — even though the wallet held thousands of
            // tokens. We now read the WHOLE map separately, detect
            // emptiness, and bail/proceed accordingly.
            val preBalances: Map<String, Pair<Double, Int>>? = try {
                wallet.getTokenAccountsWithDecimals()
            } catch (_: Throwable) { null }
            val preBalancesEmpty = preBalances?.isEmpty() == true
            val preWalletSol: Double = try { wallet.getSolBalance() } catch (_: Throwable) { -1.0 }
            val preTokenQty: Double = preBalances?.get(ts.mint)?.first ?: -1.0
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                "📋 PRE-SELL: walletSol=${"%.4f".format(preWalletSol)} | tokenBal=${if (preTokenQty < 0) "RPC?" else "%.4f".format(preTokenQty)}${if (preBalancesEmpty) " ⚠ RPC-EMPTY-MAP" else ""} | venue=$pumpVenue",
                traderTag = traderTag,
            )

            // V5.9.495k — PHANTOM-POSITION GUARD with WIDENED dust threshold.
            // V5.9.495f used `preTokenQty in 0.0..1e-9` which was too tight:
            // a wallet with e.g. 1e-5 tokens (formatted as "0.0000" in the
            // forensics tile) slipped past the guard, tried to sell the
            // recorded 4 trillion-token position size, and burned 6 retries
            // for a +62663% phantom gain in the Trade Journal. Anything
            // below 0.0001 UI tokens is dust — treat as phantom.
            // V5.9.495t — but ONLY when the RPC actually returned a map.
            // An empty-map RPC blip is NOT a confirmation of dust; it's an
            // RPC failure. If the bot has tokenUnits from a verified buy
            // (caller resolveSellUnits trusts the tracker), proceed with
            // broadcast — Jupiter/PumpPortal will reject cleanly if the
            // wallet truly is empty.
            if (!preBalancesEmpty && preTokenQty in 0.0..0.0001) {
                onLog("👻 PHANTOM detected: ${ts.symbol} bot thinks open but wallet ATA = $preTokenQty (dust) — skipping sell ladder", ts.mint)
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_FAILED,
                    "👻 PHANTOM: wallet ATA dust (preTokenBal=${"%.6f".format(preTokenQty)}) — local close, no broadcast",
                    traderTag = traderTag,
                )
                // Return a synthetic sentinel sig so the caller treats it
                // as 'sold' and the position records close locally. The
                // string starts with 'PHANTOM_' so downstream auditors can
                // distinguish from real on-chain sells in TradeHistoryStore
                // and zero out the solBack computation (no real recovery).
                return "PHANTOM_${System.currentTimeMillis().toString(16)}"
            }
            if (preBalancesEmpty) {
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_BALANCE_CHECK,
                    "RPC-EMPTY-MAP → BALANCE_UNKNOWN — PumpPortal broadcast blocked; caller tokenUnits/cache not authoritative",
                    traderTag = traderTag,
                )
                return null
            }

            onLog("🚀 PUMP-FIRST [$labelTag/$pumpVenue]: ${ts.symbol} → PumpPortal @ ${slipPct}% slip", ts.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_QUOTE_TRY,
                "🚀 PUMP-FIRST [$labelTag] @ ${slipPct}% slip | priorityFee=${priorityFeeSol}◎ | tip=${jitoTipLamports}lam",
                traderTag = traderTag,
            )
            val built = com.lifecyclebot.network.PumpFunDirectApi.buildSellTx(
                publicKeyB58    = wallet.publicKeyB58,
                mint            = ts.mint,
                tokenAmount     = tokenUnits,
                slippagePercent = slipPct,
                priorityFeeSol  = priorityFeeSol,
            )
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_TX_BUILT,
                "Tx built | router=PUMP_DIRECT [$labelTag] | slip=${slipPct}% | size=${tokenUnits ?: "ALL"}",
                traderTag = traderTag,
            )
            // V5.9.767 — drive SellJobRegistry state machine end-to-end (pump path).
            try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.BUILDING) } catch (_: Throwable) {}
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_BROADCAST,
                "Broadcasting PUMP-FIRST [$labelTag] @ ${slipPct}% | route=${if (useJito) "JITO" else "RPC"}",
                traderTag = traderTag,
            )
            try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.BROADCASTING) } catch (_: Throwable) {}
            // V5.9.603 — PumpPortal sell must wait for on-chain confirmation.
            // A raw sendTransaction signature only means RPC/Jito accepted the
            // packet; it can still expire/fail before landing. Wallet polling
            // below remains the authority for token-gone/SOL-returned proof.
            val sig = wallet.signSendAndConfirm(built.txBase64, useJito, jitoTipLamports)
            if (sig.isBlank()) {
                LiveTradeLogStore.log(
                    sellTradeKey, ts.mint, ts.symbol, "SELL",
                    LiveTradeLogStore.Phase.SELL_FAILED,
                    "PUMP-FIRST [$labelTag]: blank confirmed signature — falling back to Jupiter",
                    traderTag = traderTag,
                )
                return null
            }
            onLog("✅ PUMP-FIRST [$labelTag] SELL CONFIRMED: sig=${sig.take(20)}… (verifying wallet settlement)", ts.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_CONFIRMED,
                "✅ Tx confirmed via PumpPortal [$labelTag] @ ${slipPct}% slip — wallet settlement verify scheduled",
                sig = sig, traderTag = traderTag,
            )
            // V5.9.767 — drive SellJobRegistry state machine end-to-end (pump path).
            // Final LANDED transition is asserted by the reconciler when on-chain
            // balance reaches zero (the bg verify launches below; we cannot
            // synchronously markLanded from this path).
            try { com.lifecyclebot.engine.sell.SellJobRegistry.transitionTo(ts.mint, com.lifecyclebot.engine.sell.SellJobStatus.CONFIRMING) } catch (_: Throwable) {}

            // V5.9.495d — BACKGROUND SELL VERIFY. Polls wallet for token
            // disappearance + SOL return, logs both to the forensics tile.
            // This makes "did the sell actually land?" a visible answer
            // instead of a silent inference. Failure to verify within
            // ~45s emits a SELL_STUCK warning that the operator will see.
            try {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var seenTokenGone = false
                    var seenSolReturned = false
                    val solBumpThreshold = 0.001  // ignore noise from rent/fees fluctuations
                    val deadlineMs = System.currentTimeMillis() + 45_000L
                    var poll = 0
                    while (System.currentTimeMillis() < deadlineMs && (!seenTokenGone || !seenSolReturned)) {
                        kotlinx.coroutines.delay(3_000)
                        poll++
                        val curSol = try { wallet.getSolBalance() } catch (_: Throwable) { -1.0 }
                        val curTok = try {
                            wallet.getTokenAccountsWithDecimals()[ts.mint]?.first ?: 0.0
                        } catch (_: Throwable) { -1.0 }
                        if (!seenTokenGone && preTokenQty > 0.0 && curTok in 0.0..(preTokenQty * 0.05)) {
                            seenTokenGone = true
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_VERIFY_TOKEN_GONE,
                                "✅ Token cleared from wallet: ${"%.4f".format(preTokenQty)} → ${"%.4f".format(curTok)} (poll #$poll)",
                                sig = sig, traderTag = traderTag,
                            )
                            onLog("✅ PUMP [$labelTag] token cleared on-chain (poll #$poll)", ts.mint)
                        }
                        if (!seenSolReturned && preWalletSol >= 0.0 && curSol >= 0.0 && (curSol - preWalletSol) > solBumpThreshold) {
                            seenSolReturned = true
                            val gain = curSol - preWalletSol
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_VERIFY_SOL_RETURNED,
                                "✅ SOL returned: ${"%.4f".format(preWalletSol)} → ${"%.4f".format(curSol)} (Δ +${"%.4f".format(gain)}◎, poll #$poll)",
                                sig = sig, solAmount = gain, traderTag = traderTag,
                            )
                            onLog("✅ PUMP [$labelTag] SOL returned +${"%.4f".format(gain)}◎ (poll #$poll)", ts.mint)
                        }
                    }
                    if (!seenTokenGone && !seenSolReturned) {
                        // V5.9.495z4 — operator spec: SELL_STUCK must NOT be
                        // emitted if the trade has already been authoritatively
                        // resolved by tx-parse (TradeVerifier path) on the same
                        // sig / tradeKey. The monotonic guard inside
                        // LiveTradeLogStore will also suppress this if it slips
                        // through, but short-circuiting here saves a wasted
                        // poll loop and keeps forensics quiet on real successes.
                        if (LiveTradeLogStore.isTerminallyResolved(sellTradeKey, sig)) {
                            ErrorLogger.debug(
                                "Executor",
                                "PUMP-FIRST [$labelTag] 45s watchdog: trade already terminally resolved (sig=${sig.take(16)}) — skipping SELL_STUCK"
                            )
                        } else {
                            LiveTradeLogStore.log(
                                sellTradeKey, ts.mint, ts.symbol, "SELL",
                                LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                                "⏳ 45s post-broadcast: no on-chain confirmation yet — registering for 2/5/10 min reconcile sig=${sig.take(16)}",
                                sig = sig, traderTag = traderTag,
                            )
                            try {
                                PendingReconcileQueue.registerSell(
                                    sig = sig,
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    tradeKey = sellTradeKey,
                                    traderTag = traderTag,
                                    wallet = wallet,
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Exception) {}
            sig
        } catch (pumpEx: Exception) {
            val safe = security.sanitiseForLog(pumpEx.message ?: "unknown")
            onLog("⚠️ PUMP-FIRST [$labelTag] failed (${safe.take(180)}) — falling through to Jupiter Ultra", ts.mint)
            LiveTradeLogStore.log(
                sellTradeKey, ts.mint, ts.symbol, "SELL",
                LiveTradeLogStore.Phase.SELL_FAILED,
                "PUMP-FIRST [$labelTag] failed: ${safe.take(240)} — falling back to Jupiter Ultra",
                traderTag = traderTag,
            )
            null
        }
    }

    /**
     * V5.9.495 — try PumpPortal-FIRST buy. Returns Pair(sig, qtyTokenUi)
     * on success, null on any TRUE failure (caller falls through to
     * Jupiter Ultra). Logs to LiveTradeLogStore and onLog.
     *
     * V5.9.602 — DO NOT TRUST SUBMISSION ALONE. If `signAndSend` returns a non-blank
     * sig, the tx was only accepted by Jito/RPC for inclusion. Earlier versions
     * polled the wallet for a token-balance delta 1.8s after broadcast and
     * returned null on 0 delta — but `getTokenAccountsByOwner` lags 10–30s
     * behind on commodity RPCs, so a real successful buy would falsely
     * "fail" and the caller would fall through to Jupiter, double-spending
     * the SOL. Operator forensics (06 May 2026 screenshot HALhUaqt…rSpump):
     * PumpPortal landed sig 3ZWryZtdW5m… but bot fell back to Jupiter,
     * which then errored "Insufficient funds" because the SOL had already
     * left the wallet. Now we estimate qty from the spent SOL ÷ live price
     * and treat the buy as chain-confirmed only after signSendAndConfirm.
     * The common pendingVerify safeguards downstream are still the authority
     * for whether tokens actually landed in the wallet.
     */
    private fun tryPumpPortalBuy(
        ts: TokenState,
        wallet: SolanaWallet,
        solAmount: Double,
        slipPct: Int,
        priorityFeeSol: Double,
        useJito: Boolean,
        jitoTipLamports: Long,
        tradeKey: String,
        traderTag: String,
    ): Pair<String, Double>? {
        return try {
            val pumpVenue = if (com.lifecyclebot.network.PumpFunDirectApi.isPumpFunMint(ts.mint))
                "pump.fun" else "universal-auto"

            // V5.9.495d — DEEP FORENSICS for buys. Snapshot wallet SOL +
            // existing token balance pre-broadcast so the operator can see
            // the before/after at the trade screen. Async post-broadcast
            // watcher logs BUY_VERIFIED_LANDED when the chain catches up.
            val preWalletSol: Double = try { wallet.getSolBalance() } catch (_: Throwable) { -1.0 }
            val preTokenQty: Double = try {
                wallet.getTokenAccountsWithDecimals()[ts.mint]?.first ?: 0.0
            } catch (_: Throwable) { 0.0 }
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_QUOTE_TRY,
                "📋 PRE-BUY: walletSol=${"%.4f".format(preWalletSol)} | preTokenBal=${"%.4f".format(preTokenQty)} | venue=$pumpVenue",
                solAmount = solAmount, traderTag = traderTag,
            )

            // V5.9.751 — Base44 ticket item #7: WALLET_BALANCE_UNKNOWN abort.
            // -1.0 is the sentinel for "getSolBalance threw" — RPC unreachable
            // or returned invalid API key. Building/broadcasting against an
            // unknown wallet state is unsafe: we could oversize the buy and
            // have it fail downstream, leaving phantom PUMP_DIRECT artifacts.
            // Cleanly fall through (return null) so the outer caller falls
            // back to Jupiter (which has its own balance gates).
            if (preWalletSol < 0.0) {
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_FAILED,
                    "WALLET_BALANCE_UNKNOWN — RPC returned no SOL balance (-1.0 sentinel). Refusing to build PUMP_DIRECT tx against unknown state.",
                    traderTag = traderTag,
                )
                ErrorLogger.warn("Executor",
                    "[EXECUTION/WALLET_BALANCE_UNKNOWN] ${ts.symbol}: getSolBalance returned -1 sentinel, aborting PUMP-FIRST")
                return null
            }
            if (preWalletSol < solAmount) {
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_FAILED,
                    "INSUFFICIENT_SOL — need=${"%.4f".format(solAmount)}◎ have=${"%.4f".format(preWalletSol)}◎. Refusing PUMP_DIRECT build.",
                    traderTag = traderTag,
                )
                return null
            }

            onLog("🚀 PUMP-FIRST BUY [$pumpVenue]: ${ts.symbol} → PumpPortal ${"%.4f".format(solAmount)}◎ @ ${slipPct}% slip", ts.mint)
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_QUOTE_TRY,
                "🚀 PUMP-FIRST BUY [$pumpVenue] ${"%.4f".format(solAmount)}◎ @ ${slipPct}% | priorityFee=${priorityFeeSol}◎ | tip=${jitoTipLamports}lam",
                solAmount = solAmount, traderTag = traderTag,
            )
            val built = com.lifecyclebot.network.PumpFunDirectApi.buildBuyTx(
                publicKeyB58    = wallet.publicKeyB58,
                mint            = ts.mint,
                solAmount       = solAmount,
                slippagePercent = slipPct,
                priorityFeeSol  = priorityFeeSol,
            )
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_TX_BUILT,
                "Tx built | router=PUMP_DIRECT | slip=${slipPct}% | bytes=${built.txBase64.length}",
                traderTag = traderTag,
            )
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_BROADCAST,
                "Broadcasting PUMP-FIRST BUY @ ${slipPct}% | route=${if (useJito) "JITO" else "RPC"}",
                traderTag = traderTag,
            )
            // V5.9.602 — Pump-first must NOT treat a submitted signature as
            // a completed buy. sendTransaction/Jito acceptance can still expire
            // or fail before landing, which created ghost "held" positions with
            // no wallet tokens. Use the confirmed path just like Jupiter.
            val sig = wallet.signSendAndConfirm(built.txBase64, useJito, jitoTipLamports)
            // Sanity: signSendAndConfirm throws on RPC/on-chain failure, but defensively
            // verify the returned sig is non-blank before trusting it.
            if (sig.isBlank()) {
                onLog("⚠️ PUMP-FIRST BUY: blank confirmed sig — falling through to Jupiter", ts.mint)
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_FAILED,
                    "PUMP-FIRST: blank signature — falling back to Jupiter",
                    traderTag = traderTag,
                )
                return null
            }
            onLog("✅ PUMP-FIRST BUY CONFIRMED: sig=${sig.take(20)}… (verifying token arrival)", ts.mint)
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_CONFIRMED,
                "✅ Tx confirmed on-chain — awaiting token-arrival verification",
                sig = sig, traderTag = traderTag,
            )

            // V5.9.495l — PRIMARY qty source = WALLET, FALLBACK = price math.
            // Operator (06 May 2026): "phantom bought but they are in the
            // wallet then sold fine but same issue weird price calculation
            // displayed in journal". Estimating qty from `solAmount × solPx
            // ÷ getActualPrice` was hitting stale/zero/wrong prices, baking
            // a wildly wrong qty into the position record. Realised gains
            // then showed +62000% on sells because qty was inflated 1000×.
            //
            // New order:
            //   1. Read wallet token balance immediately (best-effort,
            //      RPC may not have indexed yet — that's fine).
            //   2. If wallet shows the new tokens → use that delta as qty.
            //   3. Else fall back to price math, kick off the watchdog
            //      (which reconciles asynchronously below).
            //
            // V5.9.495r — operator: "meme trader is extremely quiet in
            // live mode only 1 trade open". The 1.5s Thread.sleep on
            // every buy was serializing the bot's coroutine — at 10
            // buys/min that's 25% of the loop wasted in sleep. Removed;
            // the watchdog (below) handles the RPC-lag case anyway.
            val firstReadQty: Double = try {
                val cur = wallet.getTokenAccountsWithDecimals()[ts.mint]?.first ?: 0.0
                (cur - preTokenQty).coerceAtLeast(0.0)
            } catch (_: Throwable) { 0.0 }

            val price = getActualPrice(ts).takeIf { it > 0.0 } ?: ts.position.entryPrice.takeIf { it > 0.0 }
            val solPriceUsd = WalletManager.lastKnownSolPrice
            val priceMathQty = if (price != null && price > 0.0 && solPriceUsd > 0.0) {
                (solAmount * solPriceUsd) / price
            } else {
                1.0
            }

            // Trust the wallet read if it returned a non-trivial delta
            // (≥1% of price-math estimate) — that's our ground truth.
            val estimatedQty = if (firstReadQty > priceMathQty * 0.01 && firstReadQty > 0.0) {
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_VERIFY_POLL,
                    "🔍 Wallet ground truth: qty=${"%.4f".format(firstReadQty)} (price-math estimate would have been ${"%.4f".format(priceMathQty)})",
                    tokenAmount = firstReadQty, sig = sig, traderTag = traderTag,
                )
                firstReadQty
            } else {
                LiveTradeLogStore.log(
                    tradeKey, ts.mint, ts.symbol, "BUY",
                    LiveTradeLogStore.Phase.BUY_VERIFY_POLL,
                    "🔍 Estimated qty=${"%.4f".format(priceMathQty)} @ price=${price?.let { "%.8f".format(it) } ?: "N/A"} | wallet read returned ${"%.4f".format(firstReadQty)} — using price math, watchdog will reconcile",
                    tokenAmount = priceMathQty, sig = sig, traderTag = traderTag,
                )
                priceMathQty
            }

            // V5.9.495d — BACKGROUND BUY VERIFY watchdog. Polls wallet
            // token balance every 3s up to 45s, logs LANDED when the new
            // bag shows up, reconciles ts.position.qtyToken to the actual
            // on-chain qty if it differs from estimate by >5%. Logs
            // BUY_PHANTOM if the chain never indexes the buy (catastrophic
            // RPC fail or tx revert post-broadcast).
            try {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val deadlineMs = System.currentTimeMillis() + 45_000L
                    var poll = 0
                    var landed = false
                    while (System.currentTimeMillis() < deadlineMs && !landed) {
                        kotlinx.coroutines.delay(3_000)
                        poll++
                        val curTok = try {
                            wallet.getTokenAccountsWithDecimals()[ts.mint]?.first ?: 0.0
                        } catch (_: Throwable) { 0.0 }
                        val curSol = try { wallet.getSolBalance() } catch (_: Throwable) { -1.0 }
                        val tokenDelta = curTok - preTokenQty
                        if (tokenDelta > 0.0 && curTok > 0.0) {
                            landed = true
                            val solSpent = if (preWalletSol > 0.0 && curSol >= 0.0) preWalletSol - curSol else solAmount
                            LiveTradeLogStore.log(
                                tradeKey, ts.mint, ts.symbol, "BUY",
                                LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                "✅ TOKENS LANDED: +${"%.4f".format(tokenDelta)} (poll #$poll) | SOL spent=${"%.4f".format(solSpent)}◎ | wallet now ${"%.4f".format(curSol)}◎",
                                tokenAmount = tokenDelta, sig = sig, solAmount = solSpent, traderTag = traderTag,
                            )
                            onLog("✅ PUMP-FIRST tokens landed on-chain: +${"%.4f".format(tokenDelta)} ${ts.symbol} (poll #$poll)", ts.mint)
                            // Reconcile ts.position.qtyToken if estimate diverged >5%.
                            val divergence = Math.abs(tokenDelta - estimatedQty) / estimatedQty.coerceAtLeast(1e-9)
                            if (divergence > 0.05) {
                                LiveTradeLogStore.log(
                                    tradeKey, ts.mint, ts.symbol, "BUY",
                                    LiveTradeLogStore.Phase.BUY_VERIFIED_LANDED,
                                    "🔄 Qty reconciled: estimate=${"%.4f".format(estimatedQty)} → actual=${"%.4f".format(tokenDelta)} (${"%.1f".format(divergence*100)}% off)",
                                    tokenAmount = tokenDelta, sig = sig, traderTag = traderTag,
                                )
                                ts.position = ts.position.copy(qtyToken = tokenDelta)
                            }
                        }
                    }
                    if (!landed) {
                        // V5.9.495z4 — operator spec: BUY_PHANTOM must NOT be
                        // emitted if tx-parse already proved a token delta on
                        // the same sig / tradeKey. The store-level monotonic
                        // guard suppresses any leak; short-circuit here too.
                        if (LiveTradeLogStore.isTerminallyResolved(tradeKey, sig)) {
                            ErrorLogger.debug(
                                "Executor",
                                "PUMP-FIRST BUY watchdog: trade already terminally resolved (sig=${sig.take(16)}) — skipping BUY_PHANTOM"
                            )
                        } else {
                            LiveTradeLogStore.log(
                                tradeKey, ts.mint, ts.symbol, "BUY",
                                LiveTradeLogStore.Phase.SELL_VERIFY_INCONCLUSIVE_PENDING,
                                "⏳ 45s post-broadcast: no token delta yet — registering for 2/5/10 min reconcile sig=${sig.take(16)}",
                                sig = sig, traderTag = traderTag,
                            )
                            try {
                                PendingReconcileQueue.registerBuy(
                                    sig = sig,
                                    mint = ts.mint,
                                    symbol = ts.symbol,
                                    tradeKey = tradeKey,
                                    traderTag = traderTag,
                                    wallet = wallet,
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Exception) {}
            Pair(sig, estimatedQty)
        } catch (pumpEx: Exception) {
            val safe = security.sanitiseForLog(pumpEx.message ?: "unknown")
            onLog("⚠️ PUMP-FIRST BUY failed (${safe.take(80)}) — falling through to Jupiter Ultra", ts.mint)
            LiveTradeLogStore.log(
                tradeKey, ts.mint, ts.symbol, "BUY",
                LiveTradeLogStore.Phase.BUY_FAILED,
                "PUMP-FIRST BUY failed: ${safe.take(80)} — falling back to Jupiter",
                traderTag = traderTag,
            )
            null
        }
    }
}
private fun Double.fmtPct() = "%+.1f%%".format(this)
