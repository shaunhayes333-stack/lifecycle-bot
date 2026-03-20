package com.lifecyclebot.engine

import com.lifecyclebot.engine.NotificationHistory

import com.lifecyclebot.data.*
import com.lifecyclebot.network.JupiterApi
import com.lifecyclebot.network.SolanaWallet
import com.lifecyclebot.util.pct

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
        )

        if (result.solAmount <= 0.0) {
            onLog("📊 SmartSizer blocked: ${result.explanation}", "sizing")
        } else {
            onLog("📊 SmartSizer: ${result.explanation}", "sizing")
        }

        return result.solAmount  // no hard cap — SmartSizer + wallet balance are the limits
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
        if (entryScore < 30.0) return false

        // Volume must be healthy
        if (volScore < 35.0) return false

        // Must have room left
        val remainingRoom = c.topUpMaxTotalSol - pos.costSol
        if (remainingRoom < 0.005) return false

        return true
    }

    // ── trailing stop ─────────────────────────────────────────────────

    fun trailingFloor(pos: Position, current: Double,
                       modeConf: AutoModeEngine.ModeConfig? = null): Double {
        val base    = modeConf?.trailingStopPct ?: cfg().trailingStopBasePct
        val gainPct = pct(pos.entryPrice, current)
        // FIX 3c: Trail tightens after partial sells — gains locked, ride tighter
        val partialFactor = when {
            pos.partialSoldPct >= 50.0 -> 0.20  // 2+ partials → very tight
            pos.partialSoldPct >= 25.0 -> 0.28  // 1 partial → tighter
            else                       -> 1.0
        }
        val trail = when {
            gainPct >= 50 -> base * 0.35 * partialFactor
            gainPct >= 30 -> base * 0.50 * partialFactor
            gainPct >= 20 -> base * 0.65 * partialFactor
            else          -> base * partialFactor
        }
        return pos.highestPrice * (1.0 - trail / 100.0)
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

        // Wick protection: skip stop in first 90s unless extreme loss
        if (heldSecs < 90.0 && gainPct > -cfg().stopLossPct * 1.5) return null

        val effectiveStopPct = modeConf?.stopLossPct ?: cfg().stopLossPct
        if (gainPct <= -effectiveStopPct) return "stop_loss"
        if (price < trailingFloor(pos, price, modeConf)) return "trailing_stop"
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
            val c = cfg()
            if (ts.position.isOpen) return
            // No concurrent cap — SmartSizer 70% exposure ceiling is the guard
            if (cfg().scalingLogEnabled) { val _spx=WalletManager.lastKnownSolPrice; val (_tier,_)=ScalingMode.maxPositionForToken(ts.lastLiquidityUsd,ts.lastFdv,TreasuryManager.treasurySol*_spx,_spx); if(_tier!=ScalingMode.Tier.MICRO) onLog("${_tier.icon} ${_tier.label}: ${ts.symbol}", ts.mint) }
            var size = buySizeSol(entryScore, walletSol, openPositionCount, totalExposureSol,
                walletTotalTrades = walletTotalTrades,
                liquidityUsd      = ts.lastLiquidityUsd,
                mcapUsd           = ts.lastFdv)

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
                val sameTierCount = status.openPositions.count { other ->
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
            brain?.let { size = (size * it.regimeSizeMultiplier()).coerceAtMost(walletSol * 0.20) }
            if (size < 0.001) {
                onLog("Insufficient capacity for new position on ${ts.symbol}", ts.mint)
                return
            }

            // Notify shadow learning engine of trade opportunity
            ShadowLearningEngine.onTradeOpportunity(
                mint = ts.mint,
                symbol = ts.symbol,
                currentPrice = ts.ref,
                liveEntryScore = entryScore,
                liveEntryThreshold = c.entryThreshold,
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
        )
        val trade = Trade("BUY", "paper", sol, price, System.currentTimeMillis(), score = score)
        ts.trades.add(trade)
        security.recordTrade(trade)
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
            )
            val trade = Trade("BUY", "live", sol, price, System.currentTimeMillis(),
                              score = score, sig = sig)
            ts.trades.add(trade)
            security.recordTrade(trade)

            onLog("LIVE BUY  @ ${price.fmt()} | ${sol.fmt(4)} SOL | " +
                  "impact=${quote.priceImpactPct.fmt(2)}% | sig=${sig.take(16)}…", ts.mint)
            onNotify("✅ Live Buy", "${ts.symbol}  ${sol.fmt(3)} SOL", com.lifecyclebot.engine.NotificationHistory.NotifEntry.NotifType.INFO)

        } catch (e: Exception) {
            val safe = security.sanitiseForLog(e.message ?: "unknown")
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
        } else {
            // Win — let the brain know this pattern is recovering
            val fanName = ts.meta.emafanAlignment
            val ph      = ts.position.entryPhase
            tradeDb?.recordGoodObservation("phase=${ph}+ema=${fanName}")
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
        ts.position         = Position()
        ts.lastExitTs       = System.currentTimeMillis()
        ts.lastExitPrice    = exitPrice
        ts.lastExitPnlPct   = pnlP
        ts.lastExitWasWin   = pnl > 0
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
