package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.data.TokenState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CyclicTradeEngine — Compound a fixed $500 USD ring by cycling it through winning trades.
 *
 * Live execution is allowed when:
 *   1. User explicitly enabled it in settings (cfg.cyclicTradeLiveEnabled), OR
 *   2. TreasuryManager.treasuryUsd >= 5000.0
 *
 * Otherwise runs in paper mode, tracking virtual compound growth.
 *
 * The engine picks the highest-scored token from the current watchlist,
 * deploys the ring balance, waits for TP/SL, then re-deploys.
 */
object CyclicTradeEngine {

    private const val TAG = "CyclicTrade"
    private const val PREFS_FILE = "cyclic_trade_prefs"
    private const val RING_SIZE_USD = 500.0
    // V5.9.451 — TP/SL now adapt (see adaptiveTpSl()). Constants are the
    // default mature-phase levels; bootstrap and loss-streak modes override.
    // V5.9.1227 — this is the $500→$1M compounding lane, not a scalp lane.
    // 500→1,000,000 = 2000×. Over 15-20 successful cycles that requires
    // roughly +47% to +66% per winner. TP is therefore the RUNNER activation
    // line, not a full-exit ceiling.
    private const val DEFAULT_TP_PCT = 65.0
    private const val DEFAULT_SL_PCT = 8.0
    // V5.9.1481 — REVIVAL: floor 62 -> 38. Root cause of the dead cyclic lane:
    // cyclic ranks candidates from the live Solana-network watchlist (the Meme
    // Trader opens ANY Solana mint via TokenState). The filter judges a token on
    // its BLENDED V3 score when present (ts.lastV3Score), else the raw entryScore
    // proxy. lastV3Score is only set on a V3 EXECUTE verdict, so most watchlist
    // mints are judged on raw entryScore, which sits in the 0-30 band pre-verdict
    // (snapshot 5.0.3485). A 62 floor matched ~nothing on that proxy path, so
    // cyclic sat in a permanent 'Scanning… need score >=62' state. 38 restores
    // eligibility; the conviction ranker (score x confidence, line ~443) then
    // picks the highest-conviction Solana mint among survivors. Edge-gate +
    // cold-mode + loss-streak bars still protect the ring downstream.
    private const val MIN_SCORE_TO_ENTER = 38.0
    // V5.9.1492 — starvation-probe tunables. After this many consecutive ticks
    // with no token clearing the floor, take a relaxed probe pick so the ring
    // never freezes. PROBE_MIN_SCORE is the minimal above-noise score a probe
    // candidate must carry (never probes pure-zero dead tokens).
    private const val STARVATION_PROBE_TICKS = 4
    private const val PROBE_MIN_SCORE = 2.0
    // V5.9.1234 — Cyclic was deploying the full ring while cold (e.g. 1W/4L,
    // ring $3, -99% growth). Keep sampling, but stop full-ring gambling until
    // its own curve has proven profitable.
    private const val COLD_MIN_SCORE_TO_ENTER = 48.0  // V5.9.1481: 72 -> 48 (was unreachable vs real score dist; cold still demands a clearly above-median mover)
    private const val COLD_RING_SIZE_MULT = 0.40   // V5.9.1309: was 0.20 — too starved to recover; 40% still de-risks while allowing the ring to climb back
    // V5.9.1376 — EDGE-GATE constants. Once >=20 cycles are sampled, ring
    // deployment is gated on REALIZED edge vs the breakeven WR implied by the
    // active TP/SL geometry. Negative edge => probe-only (5% of ring) so the
    // lane keeps sampling/learning but cannot bleed the compounded principal.
    private const val EDGE_GATE_MIN_CYCLES = 20
    private const val EDGE_PROBE_FRACTION  = 0.05   // probe size when edge <= 0
    private const val EDGE_FULL_DEPLOY     = 0.10   // edge (in WR points, e.g. +10pp over breakeven) at which full deploy is authorized
    // V5.9.240: During bootstrap (<40% learning) lower the score floor so the
    // ring engine actually trades while FluidLearningAI is still calibrating.
    // Tokens don't have a reliable lastV3Score yet at that stage — entryScore
    // (raw signal) is used as the proxy. 30 is still a real signal, not noise.
    private const val MIN_SCORE_TO_ENTER_BOOTSTRAP = 28.0  // V5.9.1481: 48 -> 28. Bootstrap must actually sample to climb the maturity curve (doctrine #3 throughput-first); 28 is still a real signal above the 0-20 noise floor.
    private const val COOLDOWN_MS = 30_000L    // 30s between cycles
    private const val MAX_HOLD_MS = 90 * 60 * 1000L  // 90 min max hold

    // V5.9.451 — loss-streak back-off window (user: "needs loop learning
    // and AI assistance symbolic reasoning and sentience etc for more
    // success"). After 3 consecutive losses we pause 5 min and tighten
    // SL / widen TP until the next win.
    private const val LOSS_STREAK_BREAK_COUNT    = 3
    private const val LOSS_STREAK_BREAK_PAUSE_MS = 5 * 60 * 1000L
    @Volatile private var consecutiveLosses: Int = 0
    // V5.9.1492 — STARVATION PROBE. When the strict score floor finds no
    // eligible token for several consecutive ticks (the live PumpPortal flow
    // scores ~0-3 while the floor is 28-48), CYCLIC was returning "Scanning…"
    // forever — 5 lifetime cycles. Every OTHER lane fires via FDG PROBE_ONLY;
    // CYCLIC had no equivalent. This counter triggers a relaxed PROBE pick so
    // the ring keeps sampling (doctrine #3 throughput-first, #86 soft-shape).
    @Volatile private var noEligibleStreak: Int = 0
    // V5.9.696 — Dynamic stop: track high-water pnl per position so profits get locked.
    @Volatile private var positionHighWaterPnlPct: Double = 0.0
    @Volatile private var pauseUntilMs: Long = 0L

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile var ringBalanceSol: Double = 0.0
        private set
    @Volatile var ringBalanceUsd: Double = RING_SIZE_USD
        private set
    @Volatile var cycleCount: Int = 0
        private set
    // V5.9.1309 — COMPOUND MILESTONE LADDER. Operator design: $500→$1000→reinvest
    // →$2000→… each DOUBLING is a banked compound tier whose principal is then
    // protected. lockedFloorUsd is the highest doubling milestone the ring has
    // crossed; the ring is never allowed to be re-seeded below it, and cold-mode
    // measures dips RELATIVE TO the locked floor, not the original $500. This gives
    // the engine the discrete $500→$1M staircase instead of an unprotected drift.
    @Volatile var lockedFloorUsd: Double = RING_SIZE_USD
        private set
    @Volatile var milestoneTier: Int = 0   // 0 = $500 base, 1 = crossed $1000, 2 = $2000, ...
        private set
    @Volatile var totalPnlSol: Double = 0.0
        private set
    @Volatile var winCount: Int = 0
        private set
    @Volatile var lossCount: Int = 0
        private set
    @Volatile var currentMint: String = ""
    // V5.9.1347 — CYCLIC is the OPPORTUNISTIC COMPOUNDER: it has NO single trade
    // style. It deploys the ring into whatever opportunity the full app toolkit
    // (the V3 spine: ShitCoin/Moonshot/Manipulated/Quality/etc. scorers) rates best
    // for each token RIGHT NOW, and then rides it with THAT lane's exit style. We
    // capture the V3-chosen mode of the held token so the in-position exit logic
    // applies the correct style (a moonshot runs; a scalp scalps) instead of one
    // fixed profile for every token.
    @Volatile var currentMode: String = "STANDARD"
        private set
    @Volatile var currentSymbol: String = ""
        private set
    @Volatile var entryPriceSol: Double = 0.0
        private set
    @Volatile var currentPriceSol: Double = 0.0
        private set
    @Volatile var currentPnlPct: Double = 0.0
        private set
    @Volatile var priceState: String = "WAIT"
        private set
    @Volatile private var entrySizeSol: Double = 0.0
    @Volatile var entryTimeMs: Long = 0L
        private set
    @Volatile var lastCycleEndMs: Long = 0L
        private set
    @Volatile var isInPosition: Boolean = false
        private set
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var isLiveMode: Boolean = false
        private set
    @Volatile var statusMessage: String = "Idle"
        private set

    private val enabled = AtomicBoolean(false)

    // ── Initialise ─────────────────────────────────────────────────────────────
    fun init(context: Context) {
        val prefs = prefs(context)
        ringBalanceSol = prefs.getFloat("ring_balance_sol", 0f).toDouble()
        ringBalanceUsd = prefs.getFloat("ring_balance_usd", RING_SIZE_USD.toFloat()).toDouble()
        lockedFloorUsd = prefs.getFloat("locked_floor_usd", RING_SIZE_USD.toFloat()).toDouble()
        milestoneTier  = prefs.getInt("milestone_tier", 0)
        cycleCount     = prefs.getInt("cycle_count", 0)
        totalPnlSol    = prefs.getFloat("total_pnl_sol", 0f).toDouble()
        winCount       = prefs.getInt("win_count", 0)
        lossCount      = prefs.getInt("loss_count", 0)
        isInPosition   = false  // Always start fresh — don't resume mid-position after kill
        currentMint    = ""
        currentMode    = "STANDARD"
        isRunning      = false
        ErrorLogger.info(TAG, "CyclicTradeEngine initialised | ring=\$${ringBalanceUsd.toInt()} | cycles=$cycleCount")
    }

    fun setEnabled(on: Boolean) { enabled.set(on) }
    fun isEnabled(): Boolean = enabled.get()

    private data class CyclicPriceVerdict(
        val ok: Boolean,
        val price: Double = 0.0,
        val pnlPct: Double = 0.0,
        val fresh: Boolean = false,
        val reason: String = "",
    )

    /**
     * V5.0.3850 — CYCLIC PRICING AUTHORITY.
     * CYCLIC may not use raw lastPrice, ref, history, or entryPrice fallback as a
     * current price. Use Executor's source-aware resolver, require a fresh feed,
     * and run OpenPnlSanity before any PnL/exit/UI state is published.
     */
    private fun resolveCyclicPrice(
        ts: TokenState,
        executor: Executor,
        entryPrice: Double,
        context: String,
        requireFresh: Boolean = true,
    ): CyclicPriceVerdict {
        val now = System.currentTimeMillis()
        val ageMs = ts.lastPriceUpdate.takeIf { it > 0L }?.let { (now - it).coerceAtLeast(0L) }
        val fresh = ageMs != null && ageMs <= 90_000L
        if (requireFresh && !fresh) {
            return CyclicPriceVerdict(false, reason = "PRICE_STALE_OR_TS_UNKNOWN age=${ageMs ?: -1}")
        }
        val price = try { executor.getActualPricePublic(ts) } catch (_: Throwable) { 0.0 }
            .takeIf { it.isFinite() && it > 0.0 } ?: return CyclicPriceVerdict(false, reason = "PRICE_UNAVAILABLE")
        if (entryPrice > 0.0) {
            val v = try {
                OpenPnlSanity.inspect(
                    entryPrice = entryPrice,
                    currentPrice = price,
                    entrySource = ts.position.entryPriceSource,
                    currentSource = ts.lastPriceSource,
                    entryPool = ts.position.entryPoolAddress,
                    currentPool = ts.lastPricePoolAddr,
                    priceBasisRescaled = ts.position.priceBasisRescaled,
                    context = "CYCLIC:$context/${ts.symbol}/${ts.mint.take(8)}",
                    emit = true,
                )
            } catch (_: Throwable) { OpenPnlSanity.Verdict(false, reason = "INSPECT_THROW") }
            if (!v.ok) return CyclicPriceVerdict(false, price = price, fresh = fresh, reason = "BASIS_REJECTED:${v.reason}")
            return CyclicPriceVerdict(true, price = price, pnlPct = v.pnlPct, fresh = fresh, reason = "OK")
        }
        return CyclicPriceVerdict(true, price = price, fresh = fresh, reason = "OK")
    }

    // ── Main tick — call every N loops from BotService ─────────────────────────
    fun tick(
        context: Context,
        tokens: Map<String, TokenState>,
        executor: Executor,
        wallet: com.lifecyclebot.network.SolanaWallet?,
        walletSol: Double
    ) {
        val cfg = ConfigStore.load(context)
        // V5.9.222: Always run in paper mode — paper needs no user opt-in.
        // Live execution still requires cyclicTradeLiveEnabled or treasury >= $5K.
        // The old hard-return on !cyclicTradeEnabled was silently killing the engine
        // because the flag defaulted to false and had no UI toggle.
        if (!enabled.get()) { enabled.set(true) }

        // Determine live vs paper
        val treasuryUsd = TreasuryManager.treasuryUsd
        val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
        // V5.9.772 EMERGENT MEME — treasury must respect the GLOBAL trade
        // mode. Operator forensic 22:54 (V5.9.771) showed treasury firing
        // PAPER buys while the bot was in LIVE mode, polluting live state
        // ("treasury still continues to buy when the bot is in live mode
        // making paper trades also polluting live trading with its
        // balance"). Root cause: the prior `isLiveMode` formula only
        // looked at the cyclic-specific flags + treasury threshold, never
        // at `cfg.paperMode`. Now:
        //   global PAPER   → cyclic MUST be paper (skip live entirely)
        //   global LIVE    → cyclic MUST be live; if not opted-in, skip
        //                    the cycle rather than firing a paper trade
        //                    that bleeds into the live UI/risk.
        val globalLive = !cfg.paperMode
        // V5.9.1405 — CYCLIC is an autonomous participant, not an optional bolt-on.
        // Do not skip the cycle just because cyclic-specific live toggles/treasury
        // thresholds are off. In global LIVE it participates as live; in PAPER it
        // participates as paper. Wallet/balance/FDG/TradeAuthorizer/hard-floor are
        // the safety rails. The lane must trade to learn and pivot.
        isLiveMode = globalLive   // mirror global; paper-mode → paper cycle

        // Compute ring size in SOL
        if (ringBalanceSol <= 0.0) {
            ringBalanceSol = ringBalanceUsd / solPrice
        }

        // ── 1. In position — check TP/SL/timeout ──────────────────────────────
        if (isInPosition && currentMint.isNotBlank()) {
            val ts = tokens[currentMint]
            if (ts == null) {
                // Token fell off watchlist — abort cycle
                abandonCycle(context, "token_lost", solPrice)
                return
            }
            // V5.9.1359 — STALE-FEED & HARD-FLOOR PROTECTION (root cause of the
            // cyclic -98% bleed). PRE-FIX: `lastPrice.takeIf { it > 0 } ?: return`
            // bailed the WHOLE tick whenever the held token's price feed went
            // quiet — so the SL/TP/-15% checks never ran. Thin meme tokens drop
            // off the active scanner fast; the position then rode BLIND until a
            // late tick finally landed showing the token had already rugged
            // (-98%). A frozen feed on a held meme is itself a danger signal, not
            // a reason to wait. Now: if price is missing OR stale (>90s since last
            // update), FORCE-CLOSE at last-known pnl instead of riding blind.
            val nowTickMs = System.currentTimeMillis()
            val priceAgeMs = ts.lastPriceUpdate.takeIf { it > 0L }?.let { (nowTickMs - it).coerceAtLeast(0L) }
            val STALE_FEED_MS = 90_000L
            val priceVerdict = resolveCyclicPrice(ts, executor, entryPriceSol, "held", requireFresh = true)
            if (!priceVerdict.ok) {
                currentPriceSol = 0.0
                currentPnlPct = 0.0
                priceState = priceVerdict.reason
                statusMessage = "⏸️ Cyclic holding $currentSymbol: pricing wait (${priceVerdict.reason.take(48)})"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CYCLIC_PRICE_AUTHORITY_WAIT") } catch (_: Throwable) {}
                try { ForensicLogger.lifecycle("CYCLIC_PRICE_AUTHORITY_WAIT", "mint=${ts.mint.take(10)} symbol=${ts.symbol} reason=${priceVerdict.reason} entry=$entryPriceSol raw=${ts.lastPrice} source=${ts.lastPriceSource} age=${priceAgeMs ?: -1}") } catch (_: Throwable) {}
                return
            }
            val currentPrice = priceVerdict.price
            val pnlPct = priceVerdict.pnlPct
            currentPriceSol = currentPrice
            currentPnlPct = pnlPct
            priceState = if (priceVerdict.fresh) "FRESH" else "STALE"

            // V5.9.1359 — UNCONDITIONAL HARD FLOOR (standing operator rule:
            // -15% hard SL on ALL open positions, no exceptions). The mode SL
            // (adaptiveTpSl, 8-12%) normally fires first, but loss-streak
            // overlays and runner trails could let an edge case slip; this is the
            // absolute backstop. Fires before any other exit decision.
            if (pnlPct <= -15.0) {
                try { ErrorLogger.warn(TAG, "🛑 HARD_FLOOR_15 $currentSymbol @ ${"%+.1f".format(pnlPct)}% → force-close") } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CYCLIC_HARD_FLOOR_15") } catch (_: Throwable) {}
                closeCycle(context, ts, executor, wallet, walletSol, pnlPct, "HARD_FLOOR_-15", solPrice)
                return
            }

            val (tpPct, slPct) = adaptiveTpSl(currentMode)
            val holdMs = System.currentTimeMillis() - entryTimeMs

            // V5.9.696 — Dynamic profit lock (ratchet).
            // Once a position reaches a profit threshold, lock in a floor so
            // gains can't fully evaporate. High-water tracking + ratchet tiers.
            if (pnlPct > positionHighWaterPnlPct) positionHighWaterPnlPct = pnlPct

            // V5.9.898 — RUNNER MODE for the $500→$1M ring.
            // PRE-FIX: hitTP at +15% closed the ENTIRE ring, capping every
            // winner at exactly +15%. A token that ran to +50% / +100% was
            // exited at +15%. That mathematically forbids the compounding
            // path to $1M because EV/cycle is bounded by TP regardless of
            // runner availability.
            //
            // FIX: once we're past TP, ENTER RUNNER MODE — don't close on
            // TP hit, instead RATCHET the profit floor higher. The ring
            // only exits when the runner gives back N% from high-water,
            // letting +50%/+100% moves actually contribute.
            //
            // Trail config (chosen conservatively):
            //   HW ≥ 15%:  floor at +8%      (lock 8 of the 15)
            //   HW ≥ 25%:  floor at +15%     (lock the original TP)
            //   HW ≥ 40%:  floor at +25%
            //   HW ≥ 60%:  floor at +40%
            //   HW ≥ 100%: floor at +70%
            //   HW ≥ 200%: floor at HW*0.70  (continuous 30% trail)
            //
            // If pnlPct ever falls below the active floor → close as
            // RUNNER_TRAIL_<floor>. Otherwise keep riding.
            //
            // Sub-TP profit-lock cascade (V5.9.696 + tightened V5.9.898):
            //   HW ≥ 10%: floor +3% (was: same)
            //   HW ≥  8%: floor +1% (NEW — between 6 and 10 there was a gap)
            //   HW ≥  6%: floor  0% (was: same)
            //   HW ≥  4%: floor -1% (was: same)
            //   HW ≥  2%: floor -3% (NEW — tighten on early gains)
            val runnerFloor: Double? = when {
                positionHighWaterPnlPct >= 200.0 -> positionHighWaterPnlPct * 0.70
                positionHighWaterPnlPct >= 100.0 -> 70.0
                positionHighWaterPnlPct >= 60.0  -> 40.0
                positionHighWaterPnlPct >= 40.0  -> 25.0
                positionHighWaterPnlPct >= 25.0  -> 15.0
                positionHighWaterPnlPct >= 15.0  -> 8.0
                positionHighWaterPnlPct >= 10.0  -> 3.0
                positionHighWaterPnlPct >= 8.0   -> 1.0
                positionHighWaterPnlPct >= 6.0   -> 0.0
                positionHighWaterPnlPct >= 4.0   -> -1.0
                positionHighWaterPnlPct >= 2.0   -> -3.0
                else                              -> null
            }
            val inRunnerMode = positionHighWaterPnlPct >= tpPct

            // V5.9.898 — timeout BYPASS for runners.
            // Pre-fix: any cycle that hadn't TP'd by 90min closed regardless
            // of trajectory. A +12% position at minute 89 still in uptrend
            // would close at TIMEOUT for less than TP. That penalises the
            // exact runners we need for compounding.
            //
            // Bypass rule: skip timeout while HW ≥ TP*0.6 AND pnl ≥ HW*0.7
            // (still close to high-water → trend intact). Hard ceiling at
            // 3× MAX_HOLD_MS to prevent zombies.
            val hwGate = positionHighWaterPnlPct >= tpPct * 0.6
            val trendIntact = pnlPct >= positionHighWaterPnlPct * 0.7
            val absoluteMaxHold = MAX_HOLD_MS * 3L
            val timedOut = when {
                holdMs >= absoluteMaxHold -> true                  // hard ceiling
                hwGate && trendIntact     -> false                 // still riding
                holdMs >= MAX_HOLD_MS     -> true                  // legacy timeout
                else                       -> false
            }

            val floorBreached = runnerFloor != null && pnlPct < runnerFloor
            val hitSL    = pnlPct <= -slPct

            val exitReason: String? = when {
                floorBreached && inRunnerMode ->
                    "RUNNER_TRAIL_${runnerFloor!!.toInt()}_HW${positionHighWaterPnlPct.toInt()}"
                floorBreached ->
                    "PROFIT_LOCK_${positionHighWaterPnlPct.toInt()}PCT_HW"
                pnlPct <= -slPct -> "SL"
                timedOut         -> "TIMEOUT_HW${positionHighWaterPnlPct.toInt()}"
                else              -> null
            }

            val modeTag = if (inRunnerMode) "🚀RUN" else "TP${tpPct.toInt()}/SL${slPct.toInt()}"
            statusMessage = "IN: $currentSymbol | PnL: ${"%+.1f".format(pnlPct)}% | HW:+${positionHighWaterPnlPct.toInt()}% | $modeTag | ${if (isLiveMode) "LIVE" else "PAPER"}"

            if (exitReason != null) {
                closeCycle(context, ts, executor, wallet, walletSol, pnlPct, exitReason, solPrice)
            }
            return
        }

        // ── 2. Not in position — cooldown + loss-streak-pause check ───────────
        val now = System.currentTimeMillis()
        if (now < pauseUntilMs) {
            val remainingSec = (pauseUntilMs - now) / 1000
            statusMessage = "🧠 WR brake active ${remainingSec}s — continuing at shaped size/score (no lane pause)"
            // V5.9.1405 — no lane pause. The loss-streak state may shape score/size
            // below, but CYCLIC must keep sampling so it can recover and learn.
        }
        val sinceLastCycle = now - lastCycleEndMs
        if (sinceLastCycle < COOLDOWN_MS) return

        // ── 2a. V5.9.451 — SENTIENCE + SYMBOLIC + PERSONALITY veto stack ──────
        // User: "needs the loop learning and AI assistance symbolic reasoning
        // and sentience etc for more success".
        try {
            // Personality filter — if user told the bot to avoid e.g. memes in chat,
            // skip the cycle entirely while personality says no.
            if (SentienceHooks.shouldFilterByPersonality("CYCLIC", "spot")) {
                statusMessage = "🧠 Personality filter: paused by user intent"
                return
            }
        } catch (_: Throwable) {}

        // ── 3. Pick best token ────────────────────────────────────────────────
        // V5.9.240: Use a lower score floor during bootstrap.
        // V5.9.451: ALSO tighten the floor if we're on a loss streak — the
        // loop-learning + sentience feedback into entry quality.
        val isBootstrapPhase = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress() < 0.40
        } catch (_: Exception) { false }
        val cyclicWrPct = if (cycleCount > 0) (winCount * 100.0 / cycleCount.toDouble()) else 0.0
        // V5.9.1309 — cold-mode now measures the dip RELATIVE TO the locked compound
        // floor, not the original $500. A ring that banked the $1000 tier and dipped
        // to $850 is NOT "cold" — it's a normal pullback above a protected milestone.
        // This stops the doom-loop where a ring at $386 was permanently cold (deploy
        // 20% + score≥72), too starved to ever recover.
        val cyclicCold = cycleCount >= 3 && (cyclicWrPct < 35.0 || ringBalanceUsd < lockedFloorUsd * 0.80)
        var effectiveMinScore = when {
            cyclicCold -> COLD_MIN_SCORE_TO_ENTER
            isBootstrapPhase -> MIN_SCORE_TO_ENTER_BOOTSTRAP
            else -> MIN_SCORE_TO_ENTER
        }
        if (consecutiveLosses > 0) {
            // Each recent loss raises the bar +5 (caps at +15). Loop-learning
            // the ring's own outcomes into entry quality.
            effectiveMinScore += (consecutiveLosses * 5).coerceAtMost(15)
        }

        // ── V5.9.885 — BehaviorAI entry-threshold wire-up for CYCLIC lane ──
        // Cyclic is a 'fixed pool, compound through wins' trader — it deploys
        // the full RING_BALANCE_SOL every cycle, NOT a sized percentage. So
        // BehaviorAI sizing doesn't apply here the way it does for the other
        // 7 lanes (V5.9.817-884). What DOES apply is the entry threshold:
        //   - getEntryThresholdMod() returns +15..-20 based on aggression band
        //   - positive value = harder bar (shave eligibility)
        //   - negative value = easier bar (boost eligibility)
        //
        // Cyclic already has TWO loop-learning gates (bootstrap + consecutive
        // losses). BehaviorAI is the THIRD: global cross-lane tilt state.
        // When the bot is on tilt across all lanes, raise Cyclic's bar too
        // so the ring stops cycling into losing setups.
        //
        // Per doctrine #86: bounded modifier, fail-open, no hard veto. The
        // ring will still trade — just demand higher-quality scores during
        // tilt. Min effective floor = 25.0 even at maximum boost.
        try {
            val behaviorMod = com.lifecyclebot.v3.scoring.BehaviorAI.getEntryThresholdMod()
            effectiveMinScore = (effectiveMinScore + behaviorMod).coerceIn(25.0, 90.0)
        } catch (_: Throwable) { /* fail-open per FDG doctrine */ }
        val best = tokens.values
            .filter { ts ->
                val tokenScore = (ts.lastV3Score ?: ts.entryScore.toInt()).toDouble()
                !ts.position.isOpen
                    && resolveCyclicPrice(ts, executor, 0.0, "candidate", requireFresh = true).ok
                    && tokenScore >= effectiveMinScore
                    && ts.mint != currentMint   // don't immediately re-enter same token
                    // V5.9.451 — TokenBlacklist + MemeLossStreakGuard respect.
                    // The ring cycles $500 through trades, it should respect
                    // every guard the main bot uses so a known-bad setup
                    // can't bleed the ring.
                    && !TokenBlacklist.isBlocked(ts.mint)
                    && !MemeLossStreakGuard.isBlocked(ts.mint)
                    // V5.9.1301 — self-awareness: CYCLIC fed ScoreExpectancyTracker
                    // and LosingPatternMemory under "CYCLIC" but never READ them at
                    // entry (unlike the other 7 lanes). So it re-entered score bands
                    // that had already proven to bleed. Now it consults its OWN
                    // per-score record keyed on the SAME "CYCLIC" bucket it stores
                    // under. Fail-open (errors → eligible); soft (only a matured,
                    // net-losing danger bucket suppresses); throughput preserved
                    // because empty/young buckets never reject.
                    && run {
                        val sc = (ts.lastV3Score ?: ts.entryScore.toInt())
                        val expReject = try {
                            com.lifecyclebot.engine.ScoreExpectancyTracker.shouldReject("CYCLIC", sc)
                        } catch (_: Throwable) { false }
                        val danger = try {
                            val d = com.lifecyclebot.engine.LosingPatternMemory.stats("CYCLIC", sc)
                            d.isDangerous && d.meanPnl < 0.0
                        } catch (_: Throwable) { false }
                        !(expReject || danger)
                    }
            }
            // ── V5.9.1347 — RANK BY TOOL-VALIDATED CONVICTION, not raw momentum ──
            // The old ranking was maxByOrNull { lastV3Score + entryScore } — pure
            // momentum, which just picks the most-pumped token. But every token on
            // the watchlist already carries the FULL V3 verdict from the app toolkit:
            // lastV3Score (the blended lane scorer's score) AND lastV3Confidence (how
            // sure the chosen lane is). As the opportunistic compounder, CYCLIC should
            // deploy into the highest-CONVICTION opportunity — score weighted by the
            // chosen lane's confidence — so a 70-score the toolkit is sure about beats
            // an 80-score it's shaky on. Falls back to raw score when confidence is
            // absent (older/bootstrap tokens) so the ring is never starved.
            .maxByOrNull { ts ->
                val score = ((ts.lastV3Score ?: ts.entryScore.toInt())).toDouble()
                val conf = (ts.lastV3Confidence ?: 50).coerceIn(1, 100).toDouble()
                score * (conf / 100.0)
            }
            ?: run {
                // V5.9.1492 — STARVATION PROBE FALLBACK. No token cleared the strict
                // floor this tick. Rather than freeze at "Scanning…" indefinitely
                // (the dead-CYCLIC bug — only 5 lifetime cycles), count the miss and,
                // once we've been starved for STARVATION_PROBE_TICKS in a row, take
                // the single highest-conviction available token at a PROBE-relaxed
                // floor. The edge-gated ring deployment below (V5.9.1376) already
                // collapses size to a true probe fraction on negative realized edge,
                // so a low-score probe risks minimal ring capital while keeping the
                // CYCLIC learning bucket maturing. Mirrors the PROBE_ONLY path every
                // other lane already has. NEVER bypasses blacklist / loss-streak /
                // danger-bucket guards — those are re-checked in the probe filter.
                noEligibleStreak++
                if (noEligibleStreak < STARVATION_PROBE_TICKS) {
                    statusMessage = "Scanning… (need score ≥${effectiveMinScore.toInt()}${if (cyclicCold) " [COLD]" else if (isBootstrapPhase) " [BOOT]" else ""}${if (consecutiveLosses > 0) " +${consecutiveLosses}L" else ""}) probe in ${STARVATION_PROBE_TICKS - noEligibleStreak}"
                    return
                }
                // Probe floor: a clearly-above-noise but reachable score. Honour all
                // hard guards; require a real (non-zero) price and a minimal score so
                // we never probe pure-zero dead tokens.
                val probeFloor = PROBE_MIN_SCORE
                val probePick = tokens.values
                    .filter { ts ->
                        val tScore = (ts.lastV3Score ?: ts.entryScore.toInt()).toDouble()
                        !ts.position.isOpen
                            && resolveCyclicPrice(ts, executor, 0.0, "probe", requireFresh = true).ok
                            && tScore >= probeFloor
                            && ts.mint != currentMint
                            && !TokenBlacklist.isBlocked(ts.mint)
                            && !MemeLossStreakGuard.isBlocked(ts.mint)
                            && run {
                                val sc = (ts.lastV3Score ?: ts.entryScore.toInt())
                                val expReject = try { com.lifecyclebot.engine.ScoreExpectancyTracker.shouldReject("CYCLIC", sc) } catch (_: Throwable) { false }
                                val danger = try {
                                    val d = com.lifecyclebot.engine.LosingPatternMemory.stats("CYCLIC", sc)
                                    d.isDangerous && d.meanPnl < 0.0
                                } catch (_: Throwable) { false }
                                !(expReject || danger)
                            }
                    }
                    .maxByOrNull { ts ->
                        val score = ((ts.lastV3Score ?: ts.entryScore.toInt())).toDouble()
                        val conf = (ts.lastV3Confidence ?: 50).coerceIn(1, 100).toDouble()
                        score * (conf / 100.0)
                    }
                if (probePick == null) {
                    statusMessage = "Scanning… (no probe candidate ≥${probeFloor.toInt()} either)"
                    return
                }
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CYCLIC_STARVATION_PROBE")
                    ForensicLogger.lifecycle(
                        "CYCLIC_STARVATION_PROBE",
                        "symbol=${probePick.symbol} score=${probePick.lastV3Score ?: probePick.entryScore.toInt()} starvedTicks=$noEligibleStreak floorWas=${effectiveMinScore.toInt()} probeFloor=${probeFloor.toInt()} — relaxed pick (edge-gated size below)"
                    )
                } catch (_: Throwable) {}
                noEligibleStreak = 0
                probePick
            }

        // ── 3a. V5.9.451 — ChopFilter (phase/source) — skip the known chop pool.
        try {
            val src = best.source.uppercase()
            val phase = best.phase.lowercase()
            if (src in setOf("DEX_BOOSTED", "DEX_TRENDING") &&
                phase in setOf("early_unknown", "pre_pump", "unknown", "scanning", "idle")) {
                val gate = 50 + ChopFilter.chopPenalty()
                val tokenScore = (best.lastV3Score ?: best.entryScore.toInt())
                if (tokenScore < gate) {
                    statusMessage = "🔪 ChopFilter: ${best.symbol} score=$tokenScore < $gate (src=$src phase=$phase)"
                    return
                }
            }
        } catch (_: Throwable) {}

        // V5.9.1492 — a token WAS selected (strict or probe) → clear starvation.
        noEligibleStreak = 0
        // ── 4. Enter cycle ─────────────────────────────────────────────────────
        var ringDesiredSol = if (cyclicCold) (ringBalanceSol * COLD_RING_SIZE_MULT).coerceAtLeast(0.001) else ringBalanceSol

        // ══════════════════════════════════════════════════════════════════
        // V5.9.1376 — EDGE-GATED RING DEPLOYMENT (the real CYCLIC bleed fix).
        //
        // ROOT CAUSE of the -4350 SOL CYCLIC bleed: this is a FULL-BANKROLL
        // (or 40%-of-bankroll while "cold") deploy on EVERY cycle. With the
        // meme spine's realized WR (~6-9%), deploying a large fraction of the
        // ring into a NEGATIVE-expectancy edge is geometric ruin BY DESIGN — no
        // amount of intelligence downstream can rescue a sizing policy that bets
        // the bankroll into a coin-flip-or-worse. E[Δring] = f · ring ·
        // (WR·avgWin − (1−WR)·avgLoss); when that bracket is negative the ring
        // decays every cycle. The old "cyclicCold<35% WR → ×0.40" brake was far
        // too weak: at 8% WR it still bleeds ~4%/cycle.
        //
        // FIX (Kelly-correct, fail-open): once the lane has a meaningful sample
        // (>=20 closed cycles), measure its REALIZED per-cycle edge from the
        // engine's own persisted counters. If the edge is negative, collapse the
        // deployment to a true PROBE fraction (keep sampling to LEARN, but stop
        // bleeding the compounded principal). As the measured edge turns
        // positive, scale the ring back up proportional to the edge — so the
        // compound engine only goes full-size once it has EARNED a real edge.
        // Learning is untouched: we still enter and journal every probe, so the
        // CYCLIC bucket keeps maturing.
        run {
            try {
                val n = cycleCount
                // V5.9.1420 — CLOSE THE 0-20 CYCLE SIZING BLIND SPOT (operator:
                // "cyclic ... shouldn't bleed ... fix it at the source").
                // ROOT CAUSE of the live -15% floor hits on 2-6 SOL CYCLIC entries:
                // the edge-gate below only engaged at n>=20, and the cyclicCold brake
                // only at n>=3 && wr<35%. In the 3-20 cycle window an unproven ring
                // could deploy the FULL balance into a negative-EV meme entry and eat
                // a hard-floor stop — geometric ruin before the lane ever earned the
                // right to size up. FIX: until the lane has EARNED >=20 closed cycles
                // of edge data, it is UNPROVEN and must deploy at probe size only.
                // Full-ring deployment is the REWARD for proven edge, never the
                // default. Learning is untouched — we still enter and journal every
                // probe, so the CYCLIC bucket keeps maturing toward the edge-gate.
                if (n < EDGE_GATE_MIN_CYCLES) {
                    ringDesiredSol = (ringBalanceSol * EDGE_PROBE_FRACTION).coerceAtLeast(0.001)
                    try { ErrorLogger.info(TAG, "🪙🌱 CYCLIC UNPROVEN: cycle $n/$EDGE_GATE_MIN_CYCLES → PROBE ${(EDGE_PROBE_FRACTION*100).toInt()}% ring (sampling to earn edge; no full deploy until proven)") } catch (_: Throwable) {}
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CYCLIC_UNPROVEN_PROBE") } catch (_: Throwable) {}
                }
                if (n >= EDGE_GATE_MIN_CYCLES) {
                    // V5.9.1423 — REALIZED-PnL EDGE GATE (operator: "cyclic ...
                    // should be pivoting learning if its losing ... shouldn't bleed").
                    // ROOT CAUSE of the persistent CYCLIC bleed even after the 1420
                    // unproven-probe guard: the old edge formula compared WR against a
                    // breakevenWr implied by adaptiveTpSl()'s ASPIRATIONAL geometry. On
                    // a loss streak adaptiveTpSl returns TP>=80/SL<=6 (fantasy targets
                    // the trades almost never hit), so breakevenWr collapsed to ~7%.
                    // An 8% realized WR then read as a POSITIVE edge and authorized a
                    // near-full ring deploy into a lane that was empirically bleeding
                    // (n=15, 1W/11L, -15% floor hits on 1.2 SOL). The model lied because
                    // its TP was a fantasy. FIX: gate on REALIZED net P&L, the only
                    // honest signal. If the ring's persisted totalPnlSol is negative over
                    // its lifetime cycles, it is bleeding by definition — collapse to a
                    // probe and keep sampling until realized edge actually turns positive.
                    val wr = winCount.toDouble() / n.toDouble()
                    val realizedPnlPerCycle = totalPnlSol / n.toDouble()
                    // Net edge proxy: realized SOL P&L per cycle, normalized by the ring
                    // notional so it reads as a per-cycle return fraction.
                    val ringNotional = ringBalanceSol.coerceAtLeast(0.001)
                    val realizedEdge = realizedPnlPerCycle / ringNotional   // >0 ⇒ the ring is actually growing
                    val (tpA, slA) = adaptiveTpSl(currentMode)
                    val breakevenWr = (slA / (tpA + slA)).coerceIn(0.05, 0.95)
                    val wrEdge = wr - breakevenWr
                    // AND-gate: deploy full only when BOTH realized P&L is positive AND
                    // WR clears its (optimistic) breakeven. Either one negative ⇒ throttle.
                    val edge = if (totalPnlSol <= 0.0) minOf(realizedEdge, wrEdge) else wrEdge
                    when {
                        totalPnlSol <= 0.0 || edge <= 0.0 -> {
                            // Empirically bleeding (negative realized P&L) or negative WR
                            // edge — PROBE ONLY. Sample to learn/pivot, never bleed.
                            ringDesiredSol = (ringBalanceSol * EDGE_PROBE_FRACTION).coerceAtLeast(0.001)
                            try { ErrorLogger.warn(TAG, "🪙🛑 CYCLIC EDGE-GATE: realizedPnL=${"%.3f".format(totalPnlSol)} SOL over $n cycles (wr=${(wr*100).toInt()}% vs breakeven=${(breakevenWr*100).toInt()}%) → PROBE ${(EDGE_PROBE_FRACTION*100).toInt()}% ring (bleeding — sampling to pivot, not betting principal)") } catch (_: Throwable) {}
                            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CYCLIC_EDGE_GATE_PROBE") } catch (_: Throwable) {}
                        }
                        edge < EDGE_FULL_DEPLOY -> {
                            // Thin positive edge — scale deployment proportionally between
                            // probe and full as edge climbs from 0 → EDGE_FULL_DEPLOY.
                            val frac = (EDGE_PROBE_FRACTION + (edge / EDGE_FULL_DEPLOY) * (1.0 - EDGE_PROBE_FRACTION)).coerceIn(EDGE_PROBE_FRACTION, 1.0)
                            ringDesiredSol = (ringDesiredSol * frac).coerceAtLeast(ringBalanceSol * EDGE_PROBE_FRACTION)
                            try { ErrorLogger.info(TAG, "🪙📈 CYCLIC EDGE-GATE: thin edge=${(edge*100).toInt()}pp → ring×${"%.2f".format(frac)} (earning back to full)") } catch (_: Throwable) {}
                        }
                        else -> {
                            // Proven edge — deploy as computed (cold-brake/calibration still apply below).
                            try { ErrorLogger.info(TAG, "🪙✅ CYCLIC EDGE-GATE: proven edge=${(edge*100).toInt()}pp → full deploy authorized") } catch (_: Throwable) {}
                        }
                    }
                }
            } catch (_: Throwable) { /* fail-open: leave ringDesiredSol as-is */ }
        }
        // V5.9.1305 — calibration-aware shrink on the ring deployment for proven
        // net-negative CYCLIC score bands (composes with the 1301 self-reject and
        // the 1304 wallet cap below). Keyed on the SAME "CYCLIC" bucket CYCLIC
        // records under. Soft-shape, fail-open.
        try {
            val cycScore = (best.lastV3Score ?: best.entryScore.toInt())
            val calMult = com.lifecyclebot.engine.ScoreExpectancyTracker.calibrationSizeMult("CYCLIC", cycScore)
            if (calMult < 1.0) {
                ringDesiredSol *= calMult
                ErrorLogger.info(TAG, "🪙✨ CYCLIC CALIBRATION_SHRINK ${best.symbol} | band=S$cycScore ring×$calMult (net-negative band)")
            }
        } catch (_: Throwable) { /* fail-open */ }
        // V5.9.1309 — WALLET CAP IS LIVE-ONLY (CRITICAL COMPOUNDING FIX).
        // 1304 capped every CYCLIC entry at 20% of the shared wallet to stop the
        // ring hogging LIVE capital from the other 7 lanes. But in PAPER mode the
        // ring is a SELF-CONTAINED VIRTUAL $500→$1M sandbox: paperBuy debits NO
        // shared balance (verified — paper positions are virtual, P&L per-token).
        // So in paper the cap did pure harm: ring wanted to deploy its full $386
        // but got throttled to 20% of the tiny $76 paper wallet (~$15/cycle),
        // making compounding MATHEMATICALLY IMPOSSIBLE — the worst bleeder on the
        // board (-22.8% growth, WR 0%). The ring MUST deploy its full balance each
        // cycle; that IS compounding. Apply the shared-wallet cap ONLY in LIVE mode
        // where capital is genuinely shared. Paper deploys the full ring.
        val sizeSol = if (isLiveMode && walletSol > 0.0) {
            val walletCapSol = walletSol * 0.20
            val capped = minOf(ringDesiredSol, walletCapSol).coerceAtLeast(0.001)
            if (capped < ringDesiredSol) {
                try { ErrorLogger.info(TAG, "🪙 CYCLIC live wallet-cap: ring wanted ${ringDesiredSol.fmt(3)} → capped ${capped.fmt(3)} SOL (20% of ${walletSol.fmt(3)} wallet) — protecting other live lanes") } catch (_: Throwable) {}
            }
            capped
        } else {
            // PAPER (or unknown wallet): deploy the full virtual ring — this is the compound engine.
            ringDesiredSol.coerceAtLeast(0.001)
        }

        if (isLiveMode) {
            if (walletSol < sizeSol) {
                statusMessage = "⚠️ Insufficient wallet for cyclic live trade (need ${sizeSol.fmt(3)} SOL)"
                ErrorLogger.warn(TAG, "Live mode but insufficient wallet: $walletSol < $sizeSol")
                return
            }
        }

        // V5.9.1347 — capture the V3-chosen lane mode NOW, before the buy re-stamps
        // the position's tradingMode to "CYCLIC" (line ~574). This is the mode the
        // toolkit picked for the token, and it's what drives the mode-aware exit style.
        val chosenLaneMode = best.position.tradingMode.ifBlank { "STANDARD" }
        val (tpPctEntry, slPctEntry) = adaptiveTpSl(chosenLaneMode)

        // V5.9.1210 — CYCLIC must use the same executable-open finality
        // contract as the other lanes. Restoring paper cyclic in 1207 exposed
        // an old shortcut: CyclicTradeEngine called executor.treasuryBuy()
        // directly, so Executor's wrapper preflight saw no recorded BUY
        // candidate and emitted TREASURY_BUY_BLOCKED_FINALITY /
        // NO_FINAL_BUY_CANDIDATE every cycle. That looks like a frozen bot:
        // scanner alive, candidates flowing, but zero executable buys.
        // Record a CYCLIC/TREASURY BUY candidate and authorize it first, then
        // pass finalityPrechecked=true into treasuryBuy. This preserves FDG /
        // rug / safety finality instead of bypassing it.
        val cyclicScore = (best.lastV3Score ?: best.entryScore.toInt()).coerceIn(0, 100)
        val cyclicFdg = try {
            FinalDecisionGate.evaluate(
                ts = best,
                candidate = com.lifecyclebot.data.CandidateDecision(
                    entryScore = cyclicScore.toDouble(),
                    exitScore = 0.0,
                    phase = best.phase.ifBlank { "cyclic" },
                    signal = "BUY",
                    setupQuality = "B",
                    edgeQuality = "B",
                    finalQuality = "B",
                    edgePhase = "CYCLIC",
                    edgeConfidence = cyclicScore.toDouble().coerceAtLeast(effectiveMinScore),
                    isOptimalEntry = true,
                    edgeVeto = false,
                    shouldTrade = true,
                    finalSignal = "BUY",
                    blockReason = "",
                    qualityPenalty = 1.0,
                    aiConfidence = cyclicScore.toDouble().coerceAtLeast(effectiveMinScore),
                    meta = com.lifecyclebot.data.StrategyMeta(),
                ),
                config = cfg,
                proposedSizeSol = sizeSol,
                brain = executor.brain,
                tradingModeTag = try { ModeSpecificGates.fromTradingMode("CYCLIC") } catch (_: Throwable) { null },
            )
        } catch (e: Throwable) {
            ErrorLogger.warn(TAG, "CYCLIC_FDG_ERROR ${best.symbol}: ${e.message} — fail-open to authorizer")
            null
        }
        try {
            ExecutableOpenGate.recordFdg(
                mint = best.mint,
                symbol = best.symbol,
                lane = "CYCLIC",
                canExecute = cyclicFdg?.canExecute() ?: true,
                reason = cyclicFdg?.blockReason,
                signal = "BUY",
                rugScore = best.safety.rugcheckScore,
                safetyTier = best.safety.tier.name,
                liquidityUsd = best.lastLiquidityUsd,
                hardNoReasons = best.safety.hardBlockReasons,
            )
        } catch (_: Throwable) {}
        // V5.9.1227 — cyclic owns its own execution book/lane. Borrowing
        // TREASURY made the ring inherit treasury cooldown/fatality baggage and
        // UI showed FINALITY_EXEC_OPEN_BLOCKED_COOLDOWN_* instead of behaving
        // like an independent compounding lane.
        try { LaneExecutionCoordinator.canRequestExecution(best.mint, "CYCLIC") } catch (_: Throwable) {}
        // V5.9.1359 — ENTRY-PRICE SANITY. A near-zero or stale lastPrice at entry
        // makes every downstream pnl calc garbage (the fake -98% / +1.3M%
        // artifacts). Refuse to open on a bad/stale entry anchor — skip the
        // candidate, don't enter blind. (Genuine micro-priced memes still have a
        // positive, fresh price; this only rejects 0/negative/frozen feeds.)
        val entryPriceVerdict = resolveCyclicPrice(best, executor, 0.0, "entry", requireFresh = true)
        run {
            if (!entryPriceVerdict.ok) {
                statusMessage = "⏸️ Cyclic skip ${best.symbol}: bad/stale entry price (${entryPriceVerdict.reason})"
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CYCLIC_ENTRY_PRICE_REJECTED") } catch (_: Throwable) {}
                try { ErrorLogger.warn(TAG, "CYCLIC_ENTRY_PRICE_REJECTED ${best.symbol} reason=${entryPriceVerdict.reason} raw=${best.lastPrice} source=${best.lastPriceSource}") } catch (_: Throwable) {}
                return
            }
        }

        val cyclicAuth = TradeAuthorizer.authorize(
            mint = best.mint,
            symbol = best.symbol,
            score = cyclicScore,
            confidence = cyclicScore.toDouble().coerceAtLeast(effectiveMinScore),
            quality = "B",
            isPaperMode = !isLiveMode,
            requestedBook = TradeAuthorizer.ExecutionBook.CYCLIC,
            rugcheckScore = best.safety.rugcheckScore.takeIf { it >= 0 } ?: 100,
            liquidity = best.lastLiquidityUsd,
            isBanned = BannedTokens.isBanned(best.mint),
        )
        if (!cyclicAuth.isExecutable()) {
            statusMessage = "⏸️ Cyclic finality blocked ${best.symbol}: ${cyclicAuth.reason.take(60)}"
            ErrorLogger.info(TAG, "CYCLIC_FINALITY_BLOCKED ${best.symbol} | ${cyclicAuth.reason}")
            return
        }
        val cyclicAttemptId = ExecutableOpenGate.recentAllowedAttemptId(best.mint, "CYCLIC") ?: cyclicAuth.attemptId
        val entered = executor.treasuryBuy(
            ts          = best,
            sizeSol     = sizeSol,
            walletSol   = walletSol,
            takeProfitPct = tpPctEntry,
            stopLossPct   = slPctEntry,
            wallet      = if (isLiveMode) wallet else null,
            isPaper     = !isLiveMode,
            finalityPrechecked = true,
            attemptId = cyclicAttemptId,
        )

        if (entered) {
            // V5.9.1227 — treasuryBuy is reused only as the low-level spot buy
            // wrapper. Re-stamp the opened position as CYCLIC so exits, UI,
            // journal attribution, and TradeAuthorizer release don't treat the
            // ring as Treasury.
            try {
                best.position.tradingMode = "CYCLIC"
                best.position.tradingModeEmoji = "🔁"
                best.position.isTreasuryPosition = false
                best.position.treasuryTakeProfit = tpPctEntry
                best.position.treasuryStopLoss = slPctEntry
            } catch (_: Throwable) {}
            isInPosition  = true
            currentMint   = best.mint
            currentMode   = chosenLaneMode   // V5.9.1347 — V3-chosen style captured before CYCLIC re-stamp
            currentSymbol = best.symbol
            entryPriceSol = entryPriceVerdict.price
            currentPriceSol = entryPriceVerdict.price
            currentPnlPct = 0.0
            priceState = "ENTRY_FRESH"
            entrySizeSol  = sizeSol
            entryTimeMs   = System.currentTimeMillis()
            isRunning     = true
            statusMessage = "⏳ ${best.symbol} | Size: ${sizeSol.fmt(3)} SOL | TP${tpPctEntry.toInt()}/SL${slPctEntry.toInt()} | ${if (isLiveMode) "🔴 LIVE" else "📄 PAPER"}"
            positionHighWaterPnlPct = 0.0  // V5.9.696: reset high water on new entry
            ErrorLogger.info(TAG, "Cycle #${cycleCount + 1} entered: ${best.symbol} | $sizeSol SOL | live=$isLiveMode | score=${best.lastV3Score ?: 0} | TP=${tpPctEntry.toInt()}% SL=${slPctEntry.toInt()}%")
            // V5.9.451 — journal BUY via V3JournalRecorder so the cycle
            // shows in the user's Journal alongside main-bot trades and
            // feeds ScoreExpectancyTracker/HoldDurationTracker/ExitReasonTracker.
            try {
                V3JournalRecorder.recordOpen(
                    symbol = best.symbol, mint = best.mint,
                    entryPrice = entryPriceVerdict.price, sizeSol = sizeSol,
                    isPaper = !isLiveMode, layer = "CYCLIC",
                    entryScore = best.lastV3Score ?: best.entryScore.toInt(),
                    entryReason = "RING_ENTRY_TP${tpPctEntry.toInt()}SL${slPctEntry.toInt()}",
                )
            } catch (_: Throwable) {}
            save(context)
        }
    }

    /**
     * V5.9.451 — adaptive TP/SL driven by learning progress + loss streak.
     *
     * Bootstrap (<40% learning): patient TP=12, tight SL=6 — ring only
     *   pulls when there's clear signal, doesn't bleed on noise.
     * Mature: default 15/5.
     * On loss streak (≥2 consecutive losses): widen TP to 20, tighten SL
     *   to 3 — hunt fatter moves, exit mediocre setups faster.
     */
    private fun adaptiveTpSl(mode: String = currentMode): Pair<Double, Double> {
        val lp = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Throwable) { 1.0 }
        // ── V5.9.1347 — MODE-AWARE EXIT STYLE ──
        // CYCLIC has no single trade style; it inherits the style of whichever lane
        // the V3 spine chose for this token. A token V3 routed as MOONSHOT must be
        // ridden as a runner (fat TP, room to breathe); one routed as a SHITCOIN /
        // TREASURY scalp must be banked fast; a MANIPULATED pump must be exited
        // before the dump. Applying one fixed +50-65% TP to all of them is what made
        // the ring buy moonshot-style targets on tokens that were really quick
        // scalps (and vice-versa). Pick the base profile from the chosen mode, THEN
        // overlay the existing learning (bootstrap) + loss-streak discipline.
        val m = mode.uppercase()
        val (modeTp, modeSl) = when {
            m.contains("MOONSHOT")                          -> Pair(120.0, 12.0)  // runner: big TP, room
            m.contains("MANIPUL")                           -> Pair(14.0, 11.0)   // ride pump, bank before dump
            m.contains("SHITCOIN") || m.contains("EXPRESS") -> Pair(35.0, 9.0)    // fast scalp
            m.contains("TREASURY") || m.contains("CASH")    -> Pair(30.0, 8.0)    // cash-gen scalp
            m.contains("SNIPER")                            -> Pair(45.0, 10.0)   // early entry, medium ride
            m.contains("QUALITY") || m.contains("BLUE")     -> Pair(60.0, 10.0)   // quality hold
            else                                            -> Pair(DEFAULT_TP_PCT, DEFAULT_SL_PCT) // STANDARD
        }
        // Bootstrap still wants slightly fatter continuation while learning.
        val (baseTp, baseSl) = if (lp < 0.40) Pair(maxOf(modeTp, 50.0), maxOf(modeSl, 10.0))
                               else Pair(modeTp, modeSl)
        // Loss-streak discipline overlays on top regardless of mode.
        return when {
            consecutiveLosses >= 2 -> Pair(maxOf(baseTp, 80.0), minOf(baseSl, 6.0))
            consecutiveLosses == 1 -> Pair(maxOf(baseTp, 65.0), minOf(baseSl, 8.0))
            else -> Pair(baseTp, baseSl)
        }
    }

    // ── Close a cycle ─────────────────────────────────────────────────────────
    private fun closeCycle(
        context: Context,
        ts: TokenState,
        executor: Executor,
        wallet: com.lifecyclebot.network.SolanaWallet?,
        walletSol: Double,
        pnlPct: Double,
        reason: String,
        solPrice: Double
    ) {
        val deployedSizeSol = entrySizeSol.takeIf { it > 0.0 } ?: ringBalanceSol
        val pnlSol = deployedSizeSol * (pnlPct / 100.0)

        // Execute sell
        if (isLiveMode) {
            executor.requestSell(ts, "CYCLIC_$reason", wallet, walletSol)
        } else {
            executor.paperSell(ts, "CYCLIC_$reason")
        }

        // Update ring
        ringBalanceSol = (ringBalanceSol + pnlSol).coerceAtLeast(0.001)
        ringBalanceUsd = ringBalanceSol * solPrice
        totalPnlSol   += pnlSol
        cycleCount++

        // V5.9.1309 — COMPOUND MILESTONE LOCK (the $500→$1000→$2000→… staircase).
        // Whenever the ring crosses the next doubling of its locked floor, BANK it:
        // raise the protected floor to that milestone so a later drawdown can't give
        // the compounded principal back. This is what makes it a compound ENGINE and
        // not a drifting balance. Tiers: $500(base)→$1000→$2000→$4000→… → $1M.
        run {
            val nextMilestone = lockedFloorUsd * 2.0
            if (ringBalanceUsd >= nextMilestone) {
                lockedFloorUsd = nextMilestone
                milestoneTier++
                ErrorLogger.info(TAG, "🏆 CYCLIC COMPOUND MILESTONE #$milestoneTier reached: ring=\$${ringBalanceUsd.toInt()} ≥ \$${nextMilestone.toInt()} — floor LOCKED at \$${lockedFloorUsd.toInt()} (compounded principal now protected)")
                statusMessage = "🏆 COMPOUND TIER $milestoneTier: \$${lockedFloorUsd.toInt()} banked!"
            }
        }
        val won = pnlPct > 0
        if (won) {
            winCount++
            consecutiveLosses = 0    // V5.9.451 — reset streak on win
        } else {
            lossCount++
            consecutiveLosses++
            // V5.9.451 — engage pause after N consecutive losses (loop-learning brake)
            if (consecutiveLosses >= LOSS_STREAK_BREAK_COUNT) {
                pauseUntilMs = System.currentTimeMillis() + LOSS_STREAK_BREAK_PAUSE_MS
                ErrorLogger.warn(TAG, "🧠 Loss-streak brake ENGAGED: $consecutiveLosses consecutive losses — pausing ${LOSS_STREAK_BREAK_PAUSE_MS / 60000}min")
            }
        }

        // V5.9.451 — journal SELL via V3JournalRecorder so the cycle close
        // shows in the user's Journal and feeds outcome-attribution trackers
        // (ScoreExpectancyTracker / HoldDurationTracker / ExitReasonTracker).
        try {
            val holdMins = if (entryTimeMs > 0) (System.currentTimeMillis() - entryTimeMs) / 60_000L else 0L
            V3JournalRecorder.recordClose(
                symbol = ts.symbol, mint = ts.mint,
                entryPrice = entryPriceSol, exitPrice = currentPriceSol.takeIf { it > 0.0 } ?: ts.lastPrice,
                sizeSol = deployedSizeSol, pnlPct = pnlPct, pnlSol = pnlSol,
                isPaper = !isLiveMode, layer = "CYCLIC",
                exitReason = reason,
                entryScore = ts.lastV3Score ?: ts.entryScore.toInt(),
                holdMinutes = holdMins,
            )
        } catch (_: Throwable) {}

        try { TradeAuthorizer.releasePosition(ts.mint, "CYCLIC_$reason", TradeAuthorizer.ExecutionBook.CYCLIC) } catch (_: Throwable) {}
        try { LaneExecutionCoordinator.releaseIfPrimary(ts.mint, "CYCLIC", "CYCLIC_$reason") } catch (_: Throwable) {}

        lastCycleEndMs = System.currentTimeMillis()
        isInPosition   = false
        currentMint    = ""
        currentMode    = "STANDARD"
        currentSymbol  = ""
        entryPriceSol  = 0.0
        currentPriceSol = 0.0
        currentPnlPct = 0.0
        priceState = "WAIT"
        entrySizeSol   = 0.0
        entryTimeMs    = 0L

        val winRate = if (cycleCount > 0) (winCount.toDouble() / cycleCount * 100).toInt() else 0
        statusMessage = "✅ $reason | PnL: ${"%+.1f".format(pnlPct)}% | Ring: \$${ringBalanceUsd.toInt()} | WR: $winRate%"
        ErrorLogger.info(TAG, "Cycle closed [$reason] | pnl=${"%+.2f".format(pnlPct)}% | ring=${ringBalanceSol.fmt(3)} SOL (\$${ringBalanceUsd.toInt()}) | total cycles=$cycleCount")
        save(context)
    }

    private fun abandonCycle(context: Context, reason: String, solPrice: Double) {
        isInPosition  = false
        currentMint   = ""
        currentMode   = "STANDARD"
        currentSymbol = ""
        entryPriceSol = 0.0
        currentPriceSol = 0.0
        currentPnlPct = 0.0
        priceState = "WAIT"
        entrySizeSol  = 0.0
        entryTimeMs   = 0L
        lastCycleEndMs = System.currentTimeMillis()
        statusMessage = "⚠️ Abandoned: $reason"
        ErrorLogger.warn(TAG, "Cycle abandoned: $reason")
        save(context)
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    fun save(context: Context) {
        prefs(context).edit()
            .putFloat("ring_balance_sol", ringBalanceSol.toFloat())
            .putFloat("ring_balance_usd", ringBalanceUsd.toFloat())
            .putFloat("locked_floor_usd", lockedFloorUsd.toFloat())
            .putInt("milestone_tier", milestoneTier)
            .putInt("cycle_count", cycleCount)
            .putFloat("total_pnl_sol", totalPnlSol.toFloat())
            .putInt("win_count", winCount)
            .putInt("loss_count", lossCount)
            .apply()
    }

    fun reset(context: Context) {
        val solPrice = WalletManager.lastKnownSolPrice.takeIf { it > 0.0 } ?: 150.0
        ringBalanceSol = RING_SIZE_USD / solPrice
        ringBalanceUsd = RING_SIZE_USD
        lockedFloorUsd = RING_SIZE_USD
        milestoneTier  = 0
        cycleCount    = 0
        totalPnlSol   = 0.0
        winCount      = 0
        lossCount     = 0
        isInPosition  = false
        currentMint   = ""
        currentMode   = "STANDARD"
        currentSymbol = ""
        entryPriceSol = 0.0
        currentPriceSol = 0.0
        currentPnlPct = 0.0
        priceState = "WAIT"
        entrySizeSol  = 0.0
        entryTimeMs   = 0L
        lastCycleEndMs = 0L
        statusMessage = "Reset"
        save(context)
        ErrorLogger.info(TAG, "Ring reset to \$500 USD = ${ringBalanceSol.fmt(3)} SOL")
    }

    fun getStats(): Map<String, Any> = mapOf(
        "ring_usd"     to ringBalanceUsd,
        "ring_sol"     to ringBalanceSol,
        "cycles"       to cycleCount,
        "wins"         to winCount,
        "losses"       to lossCount,
        "total_pnl_sol" to totalPnlSol,
        "win_rate"     to if (cycleCount > 0) winCount.toDouble() / cycleCount else 0.0,
        "in_position"  to isInPosition,
        "live_mode"    to isLiveMode,
        "status"       to statusMessage,
        "target_cycles" to "15-20",
        "target_win_pct" to "47-66",
        "locked_floor_usd" to lockedFloorUsd,        // V5.9.1309 — protected compound principal
        "milestone_tier" to milestoneTier,            // V5.9.1309 — doublings banked ($500→$1000→…)
        "next_milestone_usd" to lockedFloorUsd * 2.0, // next doubling target
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
}
