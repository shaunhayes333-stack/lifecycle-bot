package com.lifecyclebot.perps.crypto.brain

import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.exp

/**
 * V5.9.1442 — Isolated BehaviorAI analogue for the CRYPTO universe.
 *
 * Tracks sentiment (mood) and tilt detection (consecutive losses / drawdown
 * impulses) so the crypto trader has its own neural-personality state.
 * Same shape as [com.lifecyclebot.v3.scoring.BehaviorAI] but with crypto's
 * own counters and thresholds.
 *
 * Outputs consumed by CryptoAltTrader:
 *   • [getScoreAdjustment]      — additive nudge to the entry score
 *   • [getConfidenceModifier]   — additive nudge to confidence
 *   • [getSizingMultiplier]     — multiplicative nudge to position size
 *   • [getSentimentClassification] — UI string ("CALM", "FOCUSED",
 *                                   "BLEEDING", "EUPHORIC")
 *   • [isTiltProtectionActive]  — true → trader should pause/throttle
 */
object CryptoBehavior {

    private const val K_NET = "be.netPnl"
    private const val K_STREAK = "be.streak"
    private const val K_LASTRESULT = "be.lastResult"   // 1 = win, -1 = loss, 0 = scratch
    private const val K_TILT_UNTIL = "be.tiltUntilMs"

    private val netPnlPct = AtomicLong(java.lang.Double.doubleToRawLongBits(0.0))
    private val streak    = AtomicInteger(0)   // negative for losses, positive for wins
    private val lastResult = AtomicInteger(0)
    @Volatile private var tiltUntilMs: Long = 0L

    enum class Sentiment { EUPHORIC, FOCUSED, CALM, CAUTIOUS, BLEEDING }

    private fun netPnl(): Double = java.lang.Double.longBitsToDouble(netPnlPct.get())
    private fun setNetPnl(v: Double) { netPnlPct.set(java.lang.Double.doubleToRawLongBits(v)) }

    fun sentiment(): Sentiment {
        val n = netPnl()
        val s = streak.get()
        return when {
            n > 8.0  && s > 2  -> Sentiment.EUPHORIC
            n > 2.0            -> Sentiment.FOCUSED
            n > -2.0           -> Sentiment.CALM
            n > -8.0           -> Sentiment.CAUTIOUS
            else               -> Sentiment.BLEEDING
        }
    }

    fun getSentimentClassification(): String = sentiment().name

    /**
     * Tilt detection: 5 consecutive losses OR net PnL impulse < -15% in
     * recent window. When tilted, the protection window blocks new entries
     * for 5 minutes. Mirrors the meme BehaviorAI's tilt window.
     */
    fun isTiltProtectionActive(): Boolean {
        if (System.currentTimeMillis() < tiltUntilMs) return true
        return false
    }

    private fun arm(): Long {
        tiltUntilMs = System.currentTimeMillis() + 5L * 60_000L
        return tiltUntilMs
    }

    /**
     * +N / -N additive nudge to score depending on mood. The crypto universe
     * is less volatile than memes; nudges are modest.
     */
    fun getScoreAdjustment(): Int = when (sentiment()) {
        Sentiment.EUPHORIC -> -3   // pull back when running hot
        Sentiment.FOCUSED  -> +2
        Sentiment.CALM     -> 0
        Sentiment.CAUTIOUS -> -2
        Sentiment.BLEEDING -> -5
    }

    fun getConfidenceModifier(): Int = when (sentiment()) {
        Sentiment.EUPHORIC -> -2
        Sentiment.FOCUSED  -> +2
        Sentiment.CALM     -> 0
        Sentiment.CAUTIOUS -> -3
        Sentiment.BLEEDING -> -6
    }

    /**
     * Position sizing multiplier — capped 0.5x..1.4x. Same shape as the
     * meme version: bleeding shrinks size, focused expands modestly,
     * euphoric throttles back to defend gains.
     */
    fun getSizingMultiplier(): Double = when (sentiment()) {
        Sentiment.EUPHORIC -> 0.85
        Sentiment.FOCUSED  -> 1.20
        Sentiment.CALM     -> 1.00
        Sentiment.CAUTIOUS -> 0.75
        Sentiment.BLEEDING -> 0.55
    }

    /**
     * Called by CryptoAltTrader on every trade close. Updates rolling net
     * PnL, streak, and tilt status.
     */
    fun recordTrade(pnlPct: Double, reason: String, symbol: String, isPaper: Boolean) {
        // EMA on net PnL (alpha=0.10, ~10-trade memory).
        val cur = netPnl()
        setNetPnl(cur * 0.90 + pnlPct * 0.10)

        // V5.9.1447 — clean streak math.
        // result = +1 win, -1 loss, 0 scratch.
        // streak holds the signed run length: +N consecutive wins or -N
        // consecutive losses. A scratch (|pnl|<=1%) does NOT break the
        // streak — it's noise. The previous code had a double-negation
        // path that could leave `streak` at 0 when it should have been
        // negative, masking real bleed runs from the tilt gate.
        val result = when {
            pnlPct > 1.0  -> 1
            pnlPct < -1.0 -> -1
            else          -> 0
        }
        if (result != 0) {
            val prev = streak.get()
            val newStreak = when {
                prev > 0 && result > 0 -> prev + 1   // win extends win streak
                prev < 0 && result < 0 -> prev - 1   // loss extends loss streak
                else                   -> result    // direction flip → reset
            }
            streak.set(newStreak)
            lastResult.set(result)
        }

        // Arm tilt protection on 5-loss streak (or single -15% impulse).
        if (streak.get() <= -5 || pnlPct < -15.0) {
            arm()
        }
    }

    fun loadFrom(state: CryptoBrainState) {
        setNetPnl(state.getDouble(K_NET, 0.0))
        streak.set(state.getInt(K_STREAK, 0))
        lastResult.set(state.getInt(K_LASTRESULT, 0))
        tiltUntilMs = state.getLong(K_TILT_UNTIL, 0L)
    }

    fun writeTo(state: CryptoBrainState, ed: SharedPreferences.Editor) {
        ed.putLong(K_NET, java.lang.Double.doubleToRawLongBits(netPnl()))
        ed.putInt(K_STREAK, streak.get())
        ed.putInt(K_LASTRESULT, lastResult.get())
        ed.putLong(K_TILT_UNTIL, tiltUntilMs)
    }

    fun summary(): String =
        "Crypto Behavior: mood=${sentiment()} netPnl=${"%.2f".format(netPnl())}% streak=${streak.get()} tilt=${isTiltProtectionActive()}"
}
