package com.lifecyclebot.perps.crypto.brain

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * V5.9.1442 — Isolated FluidLearning analogue for the CRYPTO universe.
 *
 * Mirrors the architectural intent of [com.lifecyclebot.v3.scoring.FluidLearningAI]
 * — the maturity arc that ratchets entry score/conf thresholds, TP/SL,
 * and sizing as the bot accumulates training trades — but holds its OWN
 * state and consults its OWN sample counts. Nothing here reads or writes
 * the meme V3 FluidLearningAI singleton.
 *
 * Maturity ladder (crypto)
 * ───────────────────────
 *   BOOTSTRAP   < 250 trades    — wide thresholds, small sizing
 *   LEARNING    250–1_000       — tightening thresholds
 *   VALIDATING  1_000–2_500     — quality bar climbs
 *   MATURING    2_500–4_500
 *   READY       4_500+          — live-mode-eligible
 *
 * Crypto-appropriate defaults (matches CryptoAltTrader's tier sizing):
 *   SPOT score floor:   55 → 72 across the arc
 *   SPOT conf  floor:   50 → 68
 *   LEV  score floor:   60 → 76
 *   LEV  conf  floor:   55 → 72
 *   SPOT TP   pct:      3.0  → 5.5  (the crypto universe has thinner tails than memes)
 *   LEV  TP   pct:      4.0  → 7.0
 *   SPOT SL   pct:      3.5  (kept stable — exit-tuner refines)
 *   LEV  SL   pct:      5.0
 */
object CryptoFluidLearning {

    private const val K_TRADES_TOTAL = "fl.trades.total"
    private const val K_TRADES_PAPER = "fl.trades.paper"
    private const val K_TRADES_LIVE  = "fl.trades.live"
    private const val K_WINS         = "fl.wins"
    private const val K_LOSSES       = "fl.losses"
    private const val K_PAPER_PNL_AVG = "fl.paper.pnlAvg"

    private val tradesTotal = AtomicInteger(0)
    private val tradesPaper = AtomicInteger(0)
    private val tradesLive  = AtomicInteger(0)
    private val wins        = AtomicInteger(0)
    private val losses      = AtomicInteger(0)
    @Volatile private var paperPnlEma: Double = 0.0    // exponential moving avg of pnl%

    enum class Maturity { BOOTSTRAP, LEARNING, VALIDATING, MATURING, READY }

    fun maturity(): Maturity {
        val n = tradesTotal.get()
        return when {
            n < 250   -> Maturity.BOOTSTRAP
            n < 1_000 -> Maturity.LEARNING
            n < 2_500 -> Maturity.VALIDATING
            n < 4_500 -> Maturity.MATURING
            else      -> Maturity.READY
        }
    }

    fun progressPct(): Int {
        val n = tradesTotal.get().toDouble()
        return min(100, ((n / 4500.0) * 100).toInt())
    }

    fun tradeCount(): Int = tradesTotal.get()
    fun paperTradeCount(): Int = tradesPaper.get()
    fun liveTradeCount(): Int = tradesLive.get()
    fun winCount(): Int = wins.get()
    fun lossCount(): Int = losses.get()

    fun winRate(): Double {
        val w = wins.get(); val l = losses.get()
        val tot = w + l
        return if (tot == 0) 0.0 else w.toDouble() / tot.toDouble()
    }

    fun paperPnlEma(): Double = paperPnlEma

    // ── Threshold ladder (matures upward as samples accrue) ───────────────────
    // V5.9.1452 — operator wants the crypto universe scanning AND buying
    // the FULL universe out of the gate. Bootstrap floors dropped so a
    // 50-score signal admits at BOOTSTRAP (was 55) and matures up to 72
    // at READY. Conf floor dropped 50→42 at BOOTSTRAP.
    fun getSpotScoreThreshold(): Int = when (maturity()) {
        Maturity.BOOTSTRAP -> 48
        Maturity.LEARNING -> 55
        Maturity.VALIDATING -> 62
        Maturity.MATURING -> 68
        Maturity.READY -> 72
    }

    fun getSpotConfThreshold(): Int = when (maturity()) {
        Maturity.BOOTSTRAP -> 42
        Maturity.LEARNING -> 50
        Maturity.VALIDATING -> 58
        Maturity.MATURING -> 64
        Maturity.READY -> 68
    }

    fun getLevScoreThreshold(): Int = when (maturity()) {
        Maturity.BOOTSTRAP -> 55
        Maturity.LEARNING -> 60
        Maturity.VALIDATING -> 65
        Maturity.MATURING -> 70
        Maturity.READY -> 76
    }

    fun getLevConfThreshold(): Int = when (maturity()) {
        Maturity.BOOTSTRAP -> 48
        Maturity.LEARNING -> 55
        Maturity.VALIDATING -> 60
        Maturity.MATURING -> 66
        Maturity.READY -> 72
    }

    // ── TP/SL ladder ──────────────────────────────────────────────────────────
    fun getSpotTpPct(): Double = when (maturity()) {
        Maturity.BOOTSTRAP -> 3.0
        Maturity.LEARNING -> 3.5
        Maturity.VALIDATING -> 4.0
        Maturity.MATURING -> 4.75
        Maturity.READY -> 5.5
    }

    fun getLevTpPct(): Double = when (maturity()) {
        Maturity.BOOTSTRAP -> 4.0
        Maturity.LEARNING -> 4.75
        Maturity.VALIDATING -> 5.5
        Maturity.MATURING -> 6.25
        Maturity.READY -> 7.0
    }

    fun getSpotSlPct(): Double = 3.5
    fun getLevSlPct(): Double = 5.0

    /**
     * Dynamic fluid stop — same shape as the meme version: shrink the stop
     * when peak gain > stop distance × 2, otherwise return the static SL.
     * Keeps crypto winners running while still protecting against round-trips.
     */
    fun getDynamicFluidStop(staticSl: Double, peakGainPct: Double): Double {
        if (peakGainPct < staticSl * 2) return staticSl
        val ratchet = peakGainPct * 0.45     // give back at most 55% of peak
        return max(staticSl * 0.6, ratchet)
    }

    /**
     * Cross-learned confidence bump from BehaviorAI sentiment — modest boost
     * once paper PnL EMA stays positive across the maturity arc.
     */
    fun getCrossLearnedConfidence(rawConf: Double): Double {
        val mood = paperPnlEma
        val bump = when {
            mood > 1.0 -> 4.0
            mood > 0.25 -> 2.0
            mood < -1.0 -> -3.0
            mood < -0.25 -> -1.5
            else -> 0.0
        }
        return (rawConf + bump).coerceIn(0.0, 100.0)
    }

    // ── Event hooks called by CryptoAltTrader ─────────────────────────────────
    fun recordTradeStart() {
        // Counted at start so threshold ladder advances even on losers/scratches.
        tradesTotal.incrementAndGet()
    }

    fun recordPaperTrade(win: Boolean, pnlPct: Double) {
        tradesPaper.incrementAndGet()
        if (win) wins.incrementAndGet() else losses.incrementAndGet()
        // EMA with alpha=0.05 (slow, smooths over ~20 samples).
        paperPnlEma = paperPnlEma * 0.95 + pnlPct * 0.05
    }

    fun recordLiveTrade(win: Boolean) {
        tradesLive.incrementAndGet()
        if (win) wins.incrementAndGet() else losses.incrementAndGet()
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    fun loadFrom(state: CryptoBrainState) {
        tradesTotal.set(state.getInt(K_TRADES_TOTAL, 0))
        tradesPaper.set(state.getInt(K_TRADES_PAPER, 0))
        tradesLive.set(state.getInt(K_TRADES_LIVE, 0))
        wins.set(state.getInt(K_WINS, 0))
        losses.set(state.getInt(K_LOSSES, 0))
        paperPnlEma = state.getDouble(K_PAPER_PNL_AVG, 0.0)
    }

    fun writeTo(state: CryptoBrainState, ed: SharedPreferences.Editor) {
        ed.putInt(K_TRADES_TOTAL, tradesTotal.get())
        ed.putInt(K_TRADES_PAPER, tradesPaper.get())
        ed.putInt(K_TRADES_LIVE, tradesLive.get())
        ed.putInt(K_WINS, wins.get())
        ed.putInt(K_LOSSES, losses.get())
        ed.putLong("fl.paper.pnlAvg", java.lang.Double.doubleToRawLongBits(paperPnlEma))
    }

    fun summary(): String =
        "Crypto FluidLearning: maturity=${maturity()} progress=${progressPct()}% trades=${tradesTotal.get()} W=${wins.get()} L=${losses.get()} WR=${"%.1f".format(winRate() * 100)}% paperPnlEma=${"%.2f".format(paperPnlEma)}%"
}
