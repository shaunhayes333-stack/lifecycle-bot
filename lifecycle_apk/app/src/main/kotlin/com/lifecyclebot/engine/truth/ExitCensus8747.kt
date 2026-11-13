package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8747 §WHAT_EACH_EXIT_LEFT_ON_THE_TABLE — owner: "the bot should be able to hit 50x trades with ease ... get the bot
 * back to the point where the data does support that". A runner is lost twice: by not buying it, and by selling it. The
 * gate records say what each ENTRY door refused; nothing said what each EXIT reason sold. Here every close (paper and
 * live, from CanonicalOutcomeBus) is graded [AFTER_MS] later on the coin's own fresh mark: the move after the sale. Per
 * exit reason: n, median after-move, the share that went on to +100 % ("sold a runner") and to -30 % ("saved").
 * A coin with no fresh mark at grade time is counted ungraded, never guessed. Measurement only.
 */
object ExitCensus8747 {
    private const val AFTER_MS = 30L * 60_000L
    private const val FRESH_MS = 3L * 60_000L
    private const val GIVE_UP_MS = 3L * 3_600_000L
    private class Pending(val mint: String, val reason: String, val px: Double, val atMs: Long)
    private class Agg { val moves = ArrayList<Double>(); var ungraded = 0 }
    private val pending = ConcurrentHashMap<String, Pending>()
    private val byReason = ConcurrentHashMap<String, Agg>()

    /** Pure: the exit-reason family (numbers and per-trade suffixes stripped). */
    fun family8747(reason: String?): String = reason.orEmpty().uppercase().split('_', ' ', ':')
        .filter { tok -> tok.isNotBlank() && tok.none { it.isDigit() } }.joinToString("_")
        .replace(Regex("[^A-Z_]"), "").take(40).ifBlank { "UNKNOWN" }

    /** CanonicalOutcomeBus: a position on [mint] closed at [px] for [reason]. */
    fun noteClose8747(mint: String, reason: String?, px: Double?, atMs: Long?) {
        if (mint.isBlank() || px == null || !(px > 0.0)) return
        if (pending.size > 5_000) return
        val at = atMs?.takeIf { it > 0L } ?: System.currentTimeMillis()
        pending["$mint|$at"] = Pending(mint, family8747(reason), px, at)
    }

    private fun grade(nowMs: Long) {
        for ((k, p) in pending.entries.toList()) {
            if (nowMs - p.atMs < AFTER_MS) continue
            val ts = try { com.lifecyclebot.engine.BotService.status.tokens[p.mint] } catch (_: Throwable) { null }
            val fresh = ts != null && ts.lastPrice > 0.0 && nowMs - ts.lastPriceUpdate in 0L..FRESH_MS
            val agg = byReason.getOrPut(p.reason) { Agg() }
            if (fresh) {
                synchronized(agg) { agg.moves.add((ts!!.lastPrice / p.px - 1.0) * 100.0); if (agg.moves.size > 2_000) agg.moves.removeAt(0) }
                pending.remove(k)
            } else if (nowMs - p.atMs > GIVE_UP_MS) { synchronized(agg) { agg.ungraded++ }; pending.remove(k) }
        }
    }

    fun statusLine8747(nowMs: Long = System.currentTimeMillis()): String {
        try { grade(nowMs) } catch (_: Throwable) {}
        val rows = byReason.entries.sortedByDescending { it.value.moves.size }.take(10).joinToString(" · ") { (r, a) ->
            synchronized(a) {
                val v = a.moves.sorted(); val n = v.size
                if (n == 0) "$r n0 ungraded${a.ungraded}" else
                    "$r n$n med${"%+.0f".format(v[n / 2])}% runnerAfter${v.count { it >= 100.0 } * 100 / n}% savedFrom-30%${v.count { it <= -30.0 } * 100 / n}% ungraded${a.ungraded}"
            }
        }.ifBlank { "-" }
        return "exitCensus8747[pending=${pending.size} $rows rule=each exit graded 30 min later on the coin's own fresh mark]"
    }
}
