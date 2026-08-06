package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6422 — RUNNER AUTO-COMPOUND.
 *
 * OPERATOR DIRECTIVE (Feb 2026):
 * "Auto-shovel paper wins straight into the next entry so the
 *  $50 → $1M path keeps compounding without operator taps."
 *
 * DESIGN
 * ──────
 * LiveGrowthCompounder6416 already gives per-LANE win bumps + wallet-
 * growth tier lifts, but it has three gaps that stop true runner
 * compounding:
 *
 *   1. It requires a ≥30 % single-trade win to arm anything. Most paper
 *      closes are +2 % to +25 % — they build the wallet but never fire
 *      the next-buy bump.
 *   2. It is lane-scoped. A win in QUALITY doesn't help the very next
 *      MOONSHOT buy 200 ms later.
 *   3. Its wallet-growth tier ladder caps at 2× ratio (1.35× lift).
 *      A $50 → $1M journey needs 3×, 5×, 10× tiers to keep scaling.
 *
 * This object closes those three gaps with a cross-lane, cross-mode
 * streak amplifier that sits ON TOP of the existing compounder:
 *
 * §A GLOBAL WIN STREAK (cross-lane, cross-mode)
 *   Any close with pnlPct ≥ +5 % adds 1 to the streak.
 *   Any close with pnlPct ≤ -10 % resets the streak to 0.
 *   Small losses in the noise band do not reset (protects streaks
 *   through normal scratch closes).
 *
 * §B RUNNER MODE TIERS
 *   streak ≥ 3  → RUNNER      (+0.50× multiplier on next buy)
 *   streak ≥ 5  → TURBO       (+1.00×)
 *   streak ≥ 8  → SUPER       (+1.50×)
 *   streak ≥ 12 → MEGA        (+2.00×  hard ceiling)
 *   Multiplier is applied to EVERY buy while the streak holds — not
 *   one-shot. This is the "auto-shovel" behaviour: as long as you
 *   keep winning, every next buy grows.
 *
 * §C EXTENDED WALLET-GROWTH TIERS
 *   ratio  ≥ 3.0  → tier 4 (+1.55× lift)
 *   ratio  ≥ 5.0  → tier 5 (+1.80×)
 *   ratio  ≥ 10.0 → tier 6 (+2.20×)
 *   Falls through to the existing 1.10 / 1.20 / 1.35 lifts for smaller
 *   ratios (LiveGrowthCompounder6416 already handles those).
 *
 * COMPOSED MULTIPLIER
 *   effectiveSize = baseSize
 *                 × LiveGrowthCompounder6416.consumeNext*BuyBump(lane)
 *                 × LiveGrowthCompounder6416.walletGrowthLift(sol)     ← ≤1.35×
 *                 × RunnerAutoCompound6422.extendedTierLift(ratio)      ← ≤2.20×
 *                 × RunnerAutoCompound6422.streakMultiplier(mode)       ← ≤3.0×
 *
 * All bounded downstream by liquidityCapSol + spendable + cold-cap so
 * the runner can never overspend the wallet or the pool.
 *
 * SEPARATE PAPER / LIVE streaks so paper wins never bump live buys and
 * vice-versa.
 *
 * Emits:
 *   RUNNER_STREAK_ARMED_6422      when the tier climbs (RUNNER / TURBO / SUPER / MEGA)
 *   RUNNER_STREAK_RESET_6422      when a decisive loss zeroes the streak
 *   RUNNER_STREAK_APPLIED_6422    each time the multiplier lands on a buy
 *   RUNNER_EXTENDED_TIER_6422     when the extended wallet tier climbs
 */
object RunnerAutoCompound6422 {

    private const val WIN_MIN_PCT = 5.0         // any small win counts
    private const val LOSS_RESET_PCT = -10.0    // decisive loss resets streak
    private const val STREAK_RUNNER_MIN = 3
    private const val STREAK_TURBO_MIN = 5
    private const val STREAK_SUPER_MIN = 8
    private const val STREAK_MEGA_MIN = 12

    private const val RUNNER_MULT = 1.50
    private const val TURBO_MULT = 2.00
    private const val SUPER_MULT = 2.50
    private const val MEGA_MULT = 3.00

    private const val TIER_4_RATIO = 3.0
    private const val TIER_5_RATIO = 5.0
    private const val TIER_6_RATIO = 10.0
    private const val TIER_4_LIFT = 1.55
    private const val TIER_5_LIFT = 1.80
    private const val TIER_6_LIFT = 2.20

    // Separate streaks per mode. Paper wins compound paper buys; live wins
    // compound live buys. Never mix — a shadow paper win must not size up
    // the next real live entry.
    private val paperStreak = AtomicInteger(0)
    private val liveStreak = AtomicInteger(0)
    private val paperStreakTier = AtomicInteger(0)
    private val liveStreakTier = AtomicInteger(0)
    private val paperExtendedTier = AtomicInteger(0)
    private val liveExtendedTier = AtomicInteger(0)

    private val paperLastEventMs = AtomicLong(0L)
    private val liveLastEventMs = AtomicLong(0L)
    private val paperAppliedCount = AtomicLong(0L)
    private val liveAppliedCount = AtomicLong(0L)

    // V5.0.6423 — LEDGER-HEALTHY GATE.
    // Operator: "its still firing off 5x and 10x win alerts and journal
    // flags on tokens/trades that dont match those metrics."
    //
    // Root cause: RunnerAutoCompound tier lifts use
    // (paperWalletSol / baseline) as the ratio. When the paper wallet
    // is drifting from the journal (WALLET_VS_JOURNAL fail, phantom SOL
    // creation, over-sold qty), the ratio is falsely inflated and the
    // extended-tier ladder fires 5x / 10x alerts on tokens whose real
    // journaled PnL never came close.
    //
    // Fix at source: BotService.emitBotLoopTick.runAll pass now feeds
    // this flag. When ANY of WALLET_VS_JOURNAL, BUY_SELL_QTY_SKEW,
    // ORPHAN_SELL is unhealthy the runner freezes: streak multipliers
    // and extended-tier lifts return 1.0. Streak tracking still runs
    // in the background so the next healthy reconcile pass immediately
    // re-arms the correct tier without a warm-up delay.
    @Volatile private var ledgerHealthy = true

    fun setLedgerHealthy(healthy: Boolean) {
        val was = ledgerHealthy
        ledgerHealthy = healthy
        if (was != healthy) {
            try {
                if (healthy) {
                    PipelineHealthCollector.labelInc("RUNNER_LEDGER_HEALED_6423")
                    ForensicLogger.lifecycle("RUNNER_LEDGER_HEALED_6423", "runner tier lifts re-enabled")
                } else {
                    PipelineHealthCollector.labelInc("RUNNER_LEDGER_FROZEN_6423")
                    ForensicLogger.lifecycle("RUNNER_LEDGER_FROZEN_6423", "runner tier lifts frozen — journal drift detected")
                }
            } catch (_: Throwable) {}
        }
    }

    fun isLedgerHealthy(): Boolean = ledgerHealthy

    /**
     * Feed every paper close (win OR loss) here. Wins with pnlPct >=
     * +5 % grow the streak; losses <= -10 % reset it; scratches do
     * neither so noisy exits don't kill a hot streak.
     */
    fun onPaperClose(pnlPct: Double, lane: String, mint: String, symbol: String) {
        onClose(paperStreak, paperStreakTier, paperLastEventMs, pnlPct, "PAPER", lane, mint, symbol)
    }

    fun onLiveClose(pnlPct: Double, lane: String, mint: String, symbol: String) {
        onClose(liveStreak, liveStreakTier, liveLastEventMs, pnlPct, "LIVE", lane, mint, symbol)
    }

    private fun onClose(
        streakRef: AtomicInteger,
        tierRef: AtomicInteger,
        lastEventMs: AtomicLong,
        pnlPct: Double,
        mode: String,
        lane: String,
        mint: String,
        symbol: String,
    ) {
        if (!pnlPct.isFinite()) return
        lastEventMs.set(System.currentTimeMillis())
        if (pnlPct <= LOSS_RESET_PCT) {
            val prior = streakRef.getAndSet(0)
            val priorTier = tierRef.getAndSet(0)
            if (prior > 0) {
                try {
                    ForensicLogger.lifecycle(
                        "RUNNER_STREAK_RESET_6422",
                        "mode=$mode lane=$lane mint=${mint.take(10)} sym=$symbol pnlPct=${"%.1f".format(pnlPct)} priorStreak=$prior priorTier=$priorTier",
                    )
                    PipelineHealthCollector.labelInc("RUNNER_STREAK_RESET_6422")
                } catch (_: Throwable) {}
            }
            return
        }
        if (pnlPct < WIN_MIN_PCT) return
        val next = streakRef.incrementAndGet()
        val newTier = when {
            next >= STREAK_MEGA_MIN -> 4
            next >= STREAK_SUPER_MIN -> 3
            next >= STREAK_TURBO_MIN -> 2
            next >= STREAK_RUNNER_MIN -> 1
            else -> 0
        }
        val priorTier = tierRef.get()
        if (newTier > priorTier && tierRef.compareAndSet(priorTier, newTier)) {
            val tierName = when (newTier) {
                4 -> "MEGA"; 3 -> "SUPER"; 2 -> "TURBO"; 1 -> "RUNNER"; else -> "OFF"
            }
            try {
                ForensicLogger.lifecycle(
                    "RUNNER_STREAK_ARMED_6422",
                    "mode=$mode tier=$tierName streak=$next lane=$lane mint=${mint.take(10)} sym=$symbol pnlPct=${"%.1f".format(pnlPct)} nextBuyMult=${"%.2f".format(multForTier(newTier))}",
                )
                PipelineHealthCollector.labelInc("RUNNER_STREAK_ARMED_6422|$tierName")
            } catch (_: Throwable) {}
        }
    }

    private fun multForTier(tier: Int): Double = when (tier) {
        4 -> MEGA_MULT
        3 -> SUPER_MULT
        2 -> TURBO_MULT
        1 -> RUNNER_MULT
        else -> 1.0
    }

    /**
     * Non-consuming: every buy while the streak holds gets sized up.
     * This is the "auto-shovel" — as long as wins keep landing, every
     * new buy grows automatically without operator taps. Reset happens
     * only on a decisive loss (pnlPct ≤ -10 %).
     */
    fun paperStreakMultiplier(): Double {
        if (!ledgerHealthy) return 1.0
        val m = multForTier(paperStreakTier.get())
        if (m > 1.0) paperAppliedCount.incrementAndGet()
        return m
    }

    fun liveStreakMultiplier(): Double {
        if (!ledgerHealthy) return 1.0
        val m = multForTier(liveStreakTier.get())
        if (m > 1.0) liveAppliedCount.incrementAndGet()
        return m
    }

    /**
     * Extended wallet-growth tier lift for ratios ≥ 3× that
     * LiveGrowthCompounder6416.walletGrowthLift caps out on. Returns
     * 1.0 for ratios below 3× (in which case the caller should still
     * multiply by the existing walletGrowthLift for the 1.10 / 1.20 /
     * 1.35 lifts below that threshold).
     */
    fun paperExtendedTierLift(ratio: Double): Double = extendedTierLift(ratio, paperExtendedTier, "PAPER")
    fun liveExtendedTierLift(ratio: Double): Double = extendedTierLift(ratio, liveExtendedTier, "LIVE")

    private fun extendedTierLift(ratio: Double, tierRef: AtomicInteger, mode: String): Double {
        if (!ledgerHealthy) return 1.0
        if (!ratio.isFinite() || ratio <= 0.0) return 1.0
        val (tier, lift) = when {
            ratio >= TIER_6_RATIO -> 6 to TIER_6_LIFT
            ratio >= TIER_5_RATIO -> 5 to TIER_5_LIFT
            ratio >= TIER_4_RATIO -> 4 to TIER_4_LIFT
            else -> 0 to 1.0
        }
        val prior = tierRef.get()
        if (tier > prior && tierRef.compareAndSet(prior, tier)) {
            try {
                ForensicLogger.lifecycle(
                    "RUNNER_EXTENDED_TIER_6422",
                    "mode=$mode priorTier=$prior newTier=$tier ratio=${"%.2f".format(ratio)} lift=${"%.2f".format(lift)}",
                )
                PipelineHealthCollector.labelInc("RUNNER_EXTENDED_TIER_6422|$tier")
            } catch (_: Throwable) {}
        } else if (tier < prior) {
            // Give-back: silently demote on drawdown.
            tierRef.set(tier)
        }
        return lift
    }

    /** Convenience: paper full compound multiplier for a given ratio. */
    fun paperTotalLift(ratio: Double): Double = paperStreakMultiplier() * paperExtendedTierLift(ratio)
    fun liveTotalLift(ratio: Double): Double = liveStreakMultiplier() * liveExtendedTierLift(ratio)

    fun statusLine(): String {
        val pTier = paperStreakTier.get()
        val lTier = liveStreakTier.get()
        val pName = when (pTier) { 4 -> "MEGA"; 3 -> "SUPER"; 2 -> "TURBO"; 1 -> "RUNNER"; else -> "OFF" }
        val lName = when (lTier) { 4 -> "MEGA"; 3 -> "SUPER"; 2 -> "TURBO"; 1 -> "RUNNER"; else -> "OFF" }
        return "paper=$pName(streak=${paperStreak.get()} applied=${paperAppliedCount.get()}) " +
            "live=$lName(streak=${liveStreak.get()} applied=${liveAppliedCount.get()}) " +
            "extPaperTier=${paperExtendedTier.get()} extLiveTier=${liveExtendedTier.get()}"
    }

    internal fun resetForTest() {
        paperStreak.set(0); liveStreak.set(0)
        paperStreakTier.set(0); liveStreakTier.set(0)
        paperExtendedTier.set(0); liveExtendedTier.set(0)
        paperLastEventMs.set(0L); liveLastEventMs.set(0L)
        paperAppliedCount.set(0L); liveAppliedCount.set(0L)
    }
}
# V5.0.6424 CI nudge 2026-08-06T18:17:17Z
