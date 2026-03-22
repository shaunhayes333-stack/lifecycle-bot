package com.lifecyclebot.engine

import com.lifecyclebot.engine.NotificationHistory

import com.lifecyclebot.data.*
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.util.pct
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

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
    val security: SecurityGuard,
    private val sounds: SoundManager? = null,
) {
    private val jupiter       = JupiterApi()
    var brain: BotBrain? = null
    var tradeDb: TradeDatabase? = null
    var onPaperBalanceChange: ((Double) -> Unit)? = null  // Callback to update paper wallet balance
    private val slippageGuard = SlippageGuard(jupiter)
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
    ): Double {
        // Update session peak
        SmartSizer.updateSessionPeak(walletSol)

        val perf = SmartSizer.getPerformanceContext(walletSol, walletTotalTrades)
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
        )

        if (result.solAmount <= 0.0) {
            onLog("📊 AI Sizer blocked: ${result.explanation}", "sizing")
        } else {
            onLog("📊 AI Sizer: conf=${aiConfidence.toInt()} → ${result.explanation}", "sizing")
        }

        return result.solAmount
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

        // CHANGE 6: High-conviction and long-hold positions pyramid deeper (up to 5×)
        val nextTopUp = pos.topUpCount + 1
        val effectiveMax = if (pos.isLongHold || pos.entryScore >= 75.0) 5
                           else c.topUpMaxCount
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

        // Don't add if exit score is high (momentum dying)
        if (exitScore >= 35.0) return false

        // Don't add if entry score is very low (market structure weak)
        if (entryScore < 20.0) return false  // was 30.0 - lowered for more aggressive buying

        // Volume must be healthy
        if (volScore < 30.0) return false  // was 35.0 - lowered

        // Must have room left
        val remainingRoom = c.topUpMaxTotalSol - pos.costSol
        if (remainingRoom < 0.005) return false

        return true
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
        
        // FIX 3c: Trail tightens after partial sells — gains locked, ride tighter
        val partialFactor = when {
            pos.partialSoldPct >= 50.0 -> 0.40
            pos.partialSoldPct >= 25.0 -> 0.55
            else                       -> 1.0
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
        // Base trail calculation with health adjustment
        // ═══════════════════════════════════════════════════════════════════
        
        val baseTrail = when {
            gainPct >= 500 -> base * 0.25  // 5x+ → base tight
            gainPct >= 300 -> base * 0.35  // 3x+ → moderate
            gainPct >= 200 -> base * 0.45  // 2x+ → still loose
            gainPct >= 100 -> base * 0.55  // 100%+ → starting to tighten
            gainPct >= 50  -> base * 0.70  // 50%+ → loose
            gainPct >= 30  -> base * 0.85  // 30%+ → very loose
            else           -> base         // under 30% → full width
        }
        
        // Apply health multiplier - healthy trend = looser trail
        val smartTrail = baseTrail * healthMultiplier * partialFactor
        
        // Log significant adjustments for runners (>100% gain)
        if (gainPct >= 100.0 && healthMultiplier != 1.0) {
            val direction = if (healthMultiplier > 1.0) "LOOSE" else "TIGHT"
            ErrorLogger.debug("SmartTrail", "🎯 Runner ${gainPct.toInt()}%: " +
                "health=${healthMultiplier.fmt(2)} ($direction) | " +
                "fan=$emaFanAlignment wide=$emaFanWidening | " +
                "vol=${volScore.toInt()} press=${pressScore.toInt()} | " +
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

    // ── partial sell ─────────────────────────────────────────────────

    /**
     * v4.4: Partial sell at milestone gains.
     * Takes 25% off at +200% (default), another 25% at +500%.
     * Remaining position rides with tighter trail.
     * This locks in profit on life-changing moves without fully exiting.
     */
    fun checkPartialSell(ts: TokenState, wallet: SolanaWallet?, walletSol: Double): Boolean {
        val c   = cfg()
        val pos = ts.position
        if (!c.partialSellEnabled || !pos.isOpen) return false

        val gainPct = pct(pos.entryPrice, ts.ref)

        val firstTrigger  = gainPct >= c.partialSellTriggerPct && pos.partialSoldPct < 1.0
        val secondTrigger = gainPct >= c.partialSellSecondTriggerPct
            && pos.partialSoldPct >= c.partialSellFraction * 100.0
            && pos.partialSoldPct < (c.partialSellFraction * 2 * 100.0)
        // FIX 3: third partial at +2000% — lock in gains on life-changing moves
        val thirdTrigger  = c.partialSellThirdEnabled
            && gainPct >= c.partialSellThirdTriggerPct
            && pos.partialSoldPct >= (c.partialSellFraction * 2 * 100.0)
            && pos.partialSoldPct < (c.partialSellFraction * 3 * 100.0)

        if (!firstTrigger && !secondTrigger && !thirdTrigger) return false

        // Compute position update values BEFORE branching on paper/live
        // so soldPct is in scope for both paths
        val sellFraction = c.partialSellFraction
        val sellQty      = pos.qtyToken * sellFraction
        val sellSol      = sellQty * ts.ref
        val soldPct      = pos.partialSoldPct + sellFraction * 100.0
        val newQty       = pos.qtyToken - sellQty
        val newCost      = pos.costSol * (1.0 - sellFraction)
        val paperPnlSol  = sellQty * ts.ref - pos.costSol * sellFraction
        val triggerPct   = if (firstTrigger) c.partialSellTriggerPct else c.partialSellSecondTriggerPct

        onLog("💰 PARTIAL SELL ${(sellFraction*100).toInt()}% @ +${gainPct.toInt()}% " +
              "(trigger: +${triggerPct.toInt()}%) | ~${sellSol.fmt(4)} SOL", ts.mint)
        onNotify("💰 Partial Sell",
                 "${ts.symbol}  +${gainPct.toInt()}%  selling ${(sellFraction*100).toInt()}%",
                 com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        sounds?.playMilestone(gainPct)

        if (c.paperMode || wallet == null) {
            // ── Paper partial sell ─────────────────────────────────────
            ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = soldPct)
            val trade   = Trade("SELL", "paper", sellSol, ts.ref,
                              System.currentTimeMillis(), "partial_${soldPct.toInt()}pct",
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
                val txB64     = buildTxWithRetry(quote, wallet.publicKeyB58)
                security.enforceSignDelay()
                val sig       = wallet.signSendAndConfirm(txB64)
                val solBack   = quote.outAmount / 1_000_000_000.0
                val livePnl   = solBack - pos.costSol * sellFraction
                val liveScore = pct(pos.costSol * sellFraction, solBack)
                val (netPnl, feeSol) = slippageGuard.calcNetPnl(livePnl, pos.costSol * sellFraction)
                // Update position state after confirmed on-chain execution
                ts.position = pos.copy(qtyToken = newQty, costSol = newCost, partialSoldPct = soldPct)
                val liveTrade = Trade("SELL", "live", solBack, ts.ref,
                    System.currentTimeMillis(), "partial_${soldPct.toInt()}pct",
                    livePnl, liveScore, sig = sig, feeSol = feeSol, netPnlSol = netPnl)
                ts.trades.add(liveTrade); security.recordTrade(liveTrade)
                SmartSizer.recordTrade(livePnl > 0)
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

        if (cfg().autoTrade && signal == "BUY" && !ts.position.isOpen) {
            val isPaper = cfg().paperMode
            ErrorLogger.info("Executor", "🔔 BUY signal for ${ts.symbol} | paper=$isPaper | wallet=${walletSol.fmt(4)} | autoTrade=${cfg().autoTrade}")
            
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
            
            // AI-DRIVEN SIZING: Pass confidence, phase, source, and brain to SmartSizer
            ErrorLogger.info("Executor", "📊 ${ts.symbol} SIZING: wallet=$walletSol | liq=${ts.lastLiquidityUsd} | mcap=${ts.lastFdv} | conf=$aiConfidence | entry=$entryScore")
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
            )

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

            doBuy(ts, size, entryScore, wallet, walletSol)
        }
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
            val txB64 = buildTxWithRetry(quote, wallet.publicKeyB58)
            security.enforceSignDelay()
            onLog("Broadcasting top-up tx…", ts.mint)
            val sig    = wallet.signSendAndConfirm(txB64)
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
                      wallet: SolanaWallet?, walletSol: Double) {
        if (cfg().paperMode || wallet == null) {
            paperBuy(ts, sol, score)
        } else {
            // Pre-flight security check
            val guard = security.checkBuy(
                mint         = ts.mint,
                symbol       = ts.symbol,
                solAmount    = sol,
                walletSol    = walletSol,
                currentPrice = ts.lastPrice,
                currentVol   = ts.history.lastOrNull()?.vol ?: 0.0,
                liquidityUsd = ts.lastLiquidityUsd,
            )
            when (guard) {
                is GuardResult.Block -> {
                    onLog("🚫 Buy blocked: ${guard.reason}", ts.mint)
                    // 🎵 Peter Griffin "No no no!"
                    sounds?.playBlockSound()
                    if (guard.fatal) onNotify("🛑 Bot Halted", guard.reason, com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                    return
                }
                is GuardResult.Allow -> liveBuy(ts, sol, score, wallet, walletSol)
            }
        }
    }

    fun paperBuy(ts: TokenState, sol: Double, score: Double) {
        val price = ts.ref
        if (price <= 0) return
        // Single position enforcement
        if (ts.position.isOpen) {
            onLog("⚠ Buy skipped: position already open", ts.mint); return
        }
        ts.position = Position(
            qtyToken     = sol / maxOf(price, 1e-12),
            entryPrice   = price,
            entryTime    = System.currentTimeMillis(),
            costSol      = sol,
            highestPrice = price,
            entryPhase   = ts.phase,
            entryScore   = score,
            entryLiquidityUsd = ts.lastLiquidityUsd,  // Track liquidity for collapse detection
        )
        val trade = Trade("BUY", "paper", sol, price, System.currentTimeMillis(), score = score)
        ts.trades.add(trade)
        security.recordTrade(trade)
        
        // V8: Transition to MONITOR state
        TradeStateMachine.setState(ts.mint, TradeState.MONITOR, "position opened")
        
        // Update paper wallet balance (deduct buy amount)
        onPaperBalanceChange?.invoke(-sol)
        
        // 🎵 Homer Simpson "Woohoo!" 
        sounds?.playBuySound()
        
        onLog("PAPER BUY  @ ${price.fmt()} | ${sol.fmt(4)} SOL | score=${score.toInt()}", ts.mint)
        onNotify("📈 Paper Buy", "${ts.symbol}  ${sol.fmt(3)} SOL  (score ${score.toInt()})", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
    }

    private fun liveBuy(ts: TokenState, sol: Double, score: Double,
                        wallet: SolanaWallet, walletSol: Double) {
        val c = cfg()

        // Keypair integrity check
        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair integrity failure — trade aborted", ts.mint)
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

            val txB64 = buildTxWithRetry(quote, wallet.publicKeyB58)

            // Simulate before broadcast — catches balance/slippage/program errors
            val simErr = jupiter.simulateSwap(txB64, wallet.rpcUrl)
            if (simErr != null) {
                onLog("Swap simulation failed: $simErr", ts.mint)
                throw Exception(simErr)
            }

            // Enforce sign → broadcast delay
            security.enforceSignDelay()

            // Use signSendAndConfirm — wait for on-chain confirmation before
            // recording the position. This prevents ghost positions if tx fails.
            onLog("Broadcasting buy tx…", ts.mint)
            val sig = wallet.signSendAndConfirm(txB64)
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

            onLog("LIVE BUY  @ ${price.fmt()} | ${sol.fmt(4)} SOL | " +
                  "impact=${quote.priceImpactPct.fmt(2)}% | sig=${sig.take(16)}…", ts.mint)
            onNotify("✅ Live Buy", "${ts.symbol}  ${sol.fmt(3)} SOL", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            ErrorLogger.error("Trade", "Live buy FAILED for ${ts.symbol}: $safe", e)
            onLog("Live buy FAILED: $safe", ts.mint)
            onNotify("⚠️ Buy Failed", "${ts.symbol}: ${safe.take(80)}", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        }
    }

    // ── sell ──────────────────────────────────────────────────────────

    private fun doSell(ts: TokenState, reason: String,
                       wallet: SolanaWallet?, walletSol: Double) {
        if (cfg().paperMode || wallet == null) paperSell(ts, reason)
        else liveSell(ts, reason, wallet, walletSol)
    }

    fun paperSell(ts: TokenState, reason: String) {
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
        
        onLog("PAPER SELL @ ${price.fmt()} | $reason | pnl ${pnl.fmt(4)} SOL (${pnlP.fmtPct()})", ts.mint)
        onNotify("📉 Paper Sell", "${ts.symbol}  $reason  PnL ${pnlP.fmtPct()}", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
        // Play trade sound
        if (pnl > 0) sounds?.playCashRegister() else sounds?.playWarningSiren()
        // Milestone sounds while still holding (for live mode this fires on sell)
        if (pnl > 0) sounds?.playMilestone(pnlP)
        SmartSizer.recordTrade(pnl > 0)

        // Record bad behaviour observations for every losing trade
        // This feeds the bad_behaviour table in TradeDatabase for pattern analysis
        if (pnl <= 0) {
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
            brain?.let { b ->
                val shouldBlacklist = b.learnFromTrade(
                    isWin = false, phase = ph, emaFan = fanName, 
                    source = src, pnlPct = pnlP, mint = ts.mint
                )
                if (shouldBlacklist) {
                    // Session blacklist (cleared on restart)
                    TokenBlacklist.block(ts.mint, "2+ losses on ${ts.symbol}")
                    // Permanent ban (persisted across restarts)
                    BannedTokens.ban(ts.mint, "2+ losses on ${ts.symbol}")
                    onLog("🚫 PERMANENTLY BANNED: ${ts.symbol} after repeated losses", ts.mint)
                    onNotify("🚫 Token Banned", 
                             "${ts.symbol}: 2+ losses — permanently banned",
                             com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
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
        } else {
            // Win — let the brain know this pattern is recovering
            val fanName = ts.meta.emafanAlignment
            val ph      = ts.position.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }
            tradeDb?.recordGoodObservation("phase=${ph}+ema=${fanName}")
            tradeDb?.recordGoodObservation("source=${src}")
            
            // Update BotBrain in real-time
            brain?.let { b ->
                b.learnFromTrade(isWin = true, phase = ph, emaFan = fanName, source = src, pnlPct = pnlP, mint = ts.mint)
            }
            
            // Learn from winning trade in TradingMemory
            val holdTimeMinutes = (System.currentTimeMillis() - ts.position.entryTime) / 60_000.0
            TradingMemory.learnFromWinningTrade(
                mint = ts.mint,
                symbol = ts.symbol,
                winPct = pnlP,
                phase = ph,
                emaFan = fanName,
                source = src,
                holdTimeMinutes = holdTimeMinutes,
            )
            onLog("🤖 AI LEARNED: Win on ${ts.symbol} +${pnlP.toInt()}% | Pattern reinforced", ts.mint)
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
            solIn=ts.position.costSol, solOut=value, pnlSol=pnl, pnlPct=pnlP, isWin=pnl>0,
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
                devWalletPct = ts.safety.devWalletPct,
                bondingCurveProgress = 100.0,  // Default to graduated
                rugcheckScore = ts.safety.rugcheckScore,
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
            )
            AdaptiveLearningEngine.learnFromTrade(features)
        } catch (e: Exception) {
            ErrorLogger.debug("AdaptiveLearning", "Feature capture error: ${e.message}")
        }
        // ═══════════════════════════════════════════════════════════════════
        
        ts.position         = Position()
        ts.lastExitTs       = System.currentTimeMillis()
        ts.lastExitPrice    = price
        ts.lastExitPnlPct   = pnlP
        ts.lastExitWasWin   = pnl > 0

        // Notify shadow learning engine
        ShadowLearningEngine.onLiveTradeExit(
            mint = ts.mint,
            exitPrice = price,
            exitReason = reason,
            livePnlSol = pnl,
            isWin = pnl > 0,
        )
    }

    private fun liveSell(ts: TokenState, reason: String,
                         wallet: SolanaWallet, walletSol: Double) {
        val c   = cfg()
        val pos = ts.position
        if (!pos.isOpen) return

        // Keypair integrity check
        if (!security.verifyKeypairIntegrity(wallet.publicKeyB58,
                c.walletAddress.ifBlank { wallet.publicKeyB58 })) {
            onLog("🛑 Keypair integrity failure — sell aborted", ts.mint)
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

            val txB64 = buildTxWithRetry(quote, wallet.publicKeyB58)
            security.enforceSignDelay()
            onLog("Broadcasting sell tx…", ts.mint)
            val sig     = wallet.signSendAndConfirm(txB64)
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

            SmartSizer.recordTrade(pnl > 0)  // inside try — pnl is valid here

            onLog("LIVE SELL @ ${price.fmt()} | $reason | pnl ${pnl.fmt(4)} SOL " +
                  "(${pnlP.fmtPct()}) | sig=${sig.take(16)}…", ts.mint)
            onNotify("✅ Live Sell",
                "${ts.symbol}  $reason  PnL ${pnlP.fmtPct()}",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
            onLog("Live sell FAILED: $safe — will retry next tick", ts.mint)
            onNotify("⚠️ Sell Failed",
                "${ts.symbol}: ${safe.take(80)}",
                com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
            return  // don't clear position — retry next tick
        }

        // pnl/pnlP are now valid (try succeeded, otherwise we returned above)
        val exitPrice = ts.ref  // capture before position reset clears it
        
        // Record bad behaviour observations for losing trades (same as paperSell)
        if (pnl <= 0) {
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
            brain?.let { b ->
                val shouldBlacklist = b.learnFromTrade(
                    isWin = false, phase = ph, emaFan = fanName, 
                    source = src, pnlPct = pnlP, mint = ts.mint
                )
                if (shouldBlacklist) {
                    // Session blacklist (cleared on restart)
                    TokenBlacklist.block(ts.mint, "2+ losses on ${ts.symbol}")
                    // Permanent ban (persisted across restarts)
                    BannedTokens.ban(ts.mint, "2+ losses on ${ts.symbol}")
                    onLog("🚫 PERMANENTLY BANNED: ${ts.symbol} after repeated losses", ts.mint)
                    onNotify("🚫 Token Banned", 
                             "${ts.symbol}: 2+ losses — permanently banned",
                             com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)
                }
            }
        } else {
            // Win — let the brain know this pattern is recovering
            val fanName = ts.meta.emafanAlignment
            val ph      = pos.entryPhase
            val src     = ts.source.ifBlank { "UNKNOWN" }
            tradeDb?.recordGoodObservation("phase=${ph}+ema=${fanName}")
            tradeDb?.recordGoodObservation("source=${src}")
            
            brain?.let { b ->
                b.learnFromTrade(isWin = true, phase = ph, emaFan = fanName, source = src, pnlPct = pnlP, mint = ts.mint)
            }
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
            solIn=pos.costSol, solOut=pnl + pos.costSol, pnlSol=pnl, pnlPct=pnlP, isWin=pnl>0,
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
                devWalletPct = ts.safety.devWalletPct,
                bondingCurveProgress = 100.0,
                rugcheckScore = ts.safety.rugcheckScore,
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
            )
            AdaptiveLearningEngine.learnFromTrade(features)
        } catch (e: Exception) {
            ErrorLogger.debug("AdaptiveLearning", "Feature capture error: ${e.message}")
        }
        // ═══════════════════════════════════════════════════════════════════

        // Notify shadow learning engine
        ShadowLearningEngine.onLiveTradeExit(
            mint = ts.mint,
            exitPrice = exitPrice,
            exitReason = reason,
            livePnlSol = pnl,
            isWin = pnl > 0,
        )
        
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
    ): String {
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
