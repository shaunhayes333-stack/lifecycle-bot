package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1533 — STALE / DARK LIVE-PRICE EXIT GUARD (operator spec item 4).
 *
 * THE FAILURE: live logs showed STALE_LIVE_PRICE_RUG_ESCAPE firing EVERY tick for
 * the same mint, each one re-entering the sell path and re-feeding catastrophe
 * cooldown + learning. Two pump mints also showed STALE_LIVE_PRICE_HOLD_WINNER with
 * +83,913,533% / +65,021,599% "PnL" — classic stale/zero/decimal math (entry price
 * recorded as $0.00000000 so any tick price divides into an astronomical multiple).
 *
 * This guard enforces:
 *   • A stale-price RUG escape emits exactly ONE exit intent per mint (armOnce).
 *   • An absurd PnL multiple is QUARANTINED — we do not trade from that price and
 *     never feed it into catastrophe cooldown or learning.
 *   • Never assert pnl=-100% from a stale/dark price; only on-chain balance or a
 *     verified fresh price may confirm a total loss.
 *
 * The active-mint set lets SellOnlySafeMode know a stale-price exit storm is in
 * progress so it can gate new buys.
 */
object StalePriceExitGuard {

    /** A PnL multiple above this is physically impossible intraday — quarantine the price. */
    const val ABSURD_GAIN_MULTIPLE = 1000.0   // +100,000%

    // mint -> wallclock when the single rug-escape intent was armed.
    private val armedRugEscape = ConcurrentHashMap<String, Long>()
    // mints currently flagged as having a stale/dark price (cleared when a fresh quote lands).
    private val staleActive = ConcurrentHashMap<String, Long>()

    private const val ARM_TTL_MS = 300_000L  // re-arm allowed only after 5 min

    /** True if ANY mint currently has a stale-price exit armed/active (for safe-mode). */
    fun anyActive(): Boolean {
        val now = System.currentTimeMillis()
        staleActive.entries.removeIf { now - it.value > ARM_TTL_MS }
        return staleActive.isNotEmpty()
    }

    fun markStale(mint: String) {
        if (mint.isBlank()) return
        staleActive[mint] = System.currentTimeMillis()
    }

    fun clearStale(mint: String) {
        staleActive.remove(mint)
        armedRugEscape.remove(mint)
    }

    /**
     * Arm the single rug-escape intent for a stale-price mint. Returns true exactly
     * ONCE per mint per TTL window; subsequent ticks return false so the exit does not
     * re-fire every tick. The caller emits one EXIT_INTENT only when this returns true.
     */
    fun armOnceRugEscape(mint: String, symbol: String): Boolean {
        if (mint.isBlank()) return false
        val now = System.currentTimeMillis()
        markStale(mint)
        val prev = armedRugEscape[mint]
        if (prev != null && (now - prev) < ARM_TTL_MS) {
            return false  // already armed within window — do NOT re-fire
        }
        val raced = armedRugEscape.putIfAbsent(mint, now)
        if (raced != null && (now - raced) < ARM_TTL_MS) return false
        armedRugEscape[mint] = now
        try {
            ForensicLogger.lifecycle("STALE_LIVE_PRICE_RUG_ESCAPE_ARMED",
                "mint=${mint.take(10)} symbol=$symbol once=true (no per-tick refire)")
        } catch (_: Throwable) {}
        return true
    }

    /**
     * Decide whether a computed gain multiple is trustworthy. Returns false (quarantine)
     * for absurd multiples or non-finite math (zero/negative entry → infinite/NaN).
     * Quarantined prices must NOT drive trades, catastrophe cooldown, or learning.
     */
    fun isGainTrustworthy(mint: String, entryPrice: Double, lastPrice: Double, gainMultiple: Double): Boolean {
        val bad = !gainMultiple.isFinite() || gainMultiple > ABSURD_GAIN_MULTIPLE ||
                  entryPrice <= 0.0 || lastPrice <= 0.0
        if (bad) {
            markStale(mint)
            try {
                ForensicLogger.lifecycle("STALE_PRICE_QUARANTINED",
                    "mint=${mint.take(10)} entryPrice=$entryPrice lastPrice=$lastPrice gainMultiple=$gainMultiple " +
                    "reason=absurd_or_nonfinite action=do_not_trade_or_learn")
            } catch (_: Throwable) {}
            return false
        }
        return true
    }

    /**
     * A stale/dark price may NEVER be reported as a confirmed -100% loss. Only an
     * on-chain balance read or a verified fresh price may confirm a total loss.
     */
    fun canAssertTotalLoss(balanceConfirmedZero: Boolean, freshPriceVerified: Boolean): Boolean =
        balanceConfirmedZero || freshPriceVerified
}
