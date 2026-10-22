package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.CanonicalOutcomeBus
import com.lifecyclebot.engine.CanonicalTradeOutcome
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.TradeEnvironment
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8327 §STOP_TRIGGER_TO_FILL — 5.0.8323: stops at -15% filled at -38% / -47% and catastrophe triggers read
 * -78..-85% at the trigger. The 8144 spine timed trigger -> broadcast -> confirm, but nothing said how stale the mark
 * was when the stop fired, nor how many points were lost between the trigger and the wallet's fill.
 *
 * Per live stop / protective exit (StopRoute8189.stopExit8189):
 *  - at the trigger ([noteTrigger8327], from Executor.sellRouteTrigger8144): the mark's P&L, its age and its source;
 *  - at the canonical close (CanonicalOutcomeBus, LIVE): the wallet-proven realised % (the economic authority),
 *    trigger -> fill ms, and slip = fill % - trigger %; bucketed by class (EMRG / NORM) and the route of the confirmed
 *    sell (SignedTxLedger8085 via EmergencySellRoute8308.onSellState8308).
 * Measurement only: nothing here routes, delays or prices a sell.
 */
object StopSlip8327 {

    data class Trigger8327(val atMs: Long, val reason: String, val pnlPct: Double, val markAgeMs: Long, val source: String, val emergency: Boolean)

    private val triggers8327 = ConcurrentHashMap<String, Trigger8327>()   // mint -> first trigger of the open exit
    private val routes8327 = ConcurrentHashMap<String, String>()          // mint -> family of the last CONFIRMED sell
    private val subscribed8327 = AtomicBoolean(false)

    private class Agg8327 {
        var n = 0L; var slipSum = 0.0; var worstSlip = 0.0; var t2fSum = 0L; var t2fMax = 0L; var ageSum = 0L; var ageMax = 0L
        @Synchronized fun add(slip: Double, t2f: Long, age: Long) {
            n++; slipSum += slip; if (slip < worstSlip) worstSlip = slip
            t2fSum += t2f; if (t2f > t2fMax) t2fMax = t2f
            ageSum += age; if (age > ageMax) ageMax = age
        }
        @Synchronized fun text(): String = if (n == 0L) "-" else
            "n$n slip${"%+.1f".format(slipSum / n)}/worst${"%+.1f".format(worstSlip)}pt t2f${t2fSum / n}/max${t2fMax}ms markAge${ageSum / n}/max${ageMax}ms"
    }

    private val aggs8327 = ConcurrentHashMap<String, Agg8327>()

    /** Pure: trigger P&L % from the mark and the entry, or NaN. */
    fun pnlAt8327(markPx: Double, entryPx: Double): Double =
        if (markPx.isFinite() && entryPx.isFinite() && markPx > 0.0 && entryPx > 0.0) (markPx / entryPx - 1.0) * 100.0 else Double.NaN

    /** Pure: (slip points, trigger -> fill ms) for one closed stop; slip NaN when either side is unknown. */
    fun slip8327(triggerPnl: Double, fillPnl: Double?, triggerAtMs: Long, fillAtMs: Long?): Pair<Double, Long> {
        val slip = if (fillPnl != null && fillPnl.isFinite() && triggerPnl.isFinite()) fillPnl - triggerPnl else Double.NaN
        val t2f = if (fillAtMs != null && fillAtMs >= triggerAtMs && triggerAtMs > 0L) fillAtMs - triggerAtMs else -1L
        return slip to t2f
    }

    /** Executor.sellRouteTrigger8144 (live only): a stop / protective exit fired on this mark. The first trigger is kept. */
    fun noteTrigger8327(mint: String, reason: String, markPx: Double, entryPx: Double, markAtMs: Long, source: String,
                        nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !StopRoute8189.stopExit8189(reason)) return
        ensureSubscribed8327()
        if (triggers8327.size > 500) triggers8327.entries.removeIf { nowMs - it.value.atMs > 30L * 60_000L }
        val emergency = try { ProtectiveExitClass7807.isEmergency(reason) } catch (_: Throwable) { false }
        val age = if (markAtMs in 1L..nowMs) nowMs - markAtMs else -1L
        triggers8327.putIfAbsent(mint, Trigger8327(nowMs, reason.take(60), pnlAt8327(markPx, entryPx), age, source.take(32), emergency))
    }

    /** EmergencySellRoute8308.onSellState8308: the route family of a CONFIRMED sell of [mint]. */
    fun noteConfirmedRoute8327(mint: String, family: String) {
        if (mint.isBlank()) return
        if (routes8327.size > 500) routes8327.clear()
        routes8327[mint] = family
    }

    private fun ensureSubscribed8327() {
        if (!subscribed8327.compareAndSet(false, true)) return
        try { CanonicalOutcomeBus.subscribe { o -> try { onOutcome8327(o) } catch (_: Throwable) {} } } catch (_: Throwable) { subscribed8327.set(false) }
    }

    private fun onOutcome8327(o: CanonicalTradeOutcome) {
        if (o.environment != TradeEnvironment.LIVE) return
        val t = triggers8327.remove(o.mint) ?: return
        val (slip, t2f) = slip8327(t.pnlPct, o.realizedPnlPct, t.atMs, o.exitTimeMs)
        if (!slip.isFinite()) return
        val route = routes8327.remove(o.mint) ?: "UNKNOWN"
        val cls = if (t.emergency) "EMRG" else "NORM"
        aggs8327.computeIfAbsent("$cls|$route") { Agg8327() }.add(slip, t2f.coerceAtLeast(0L), t.markAgeMs.coerceAtLeast(0L))
        try {
            if (slip <= -10.0) PipelineHealthCollector.labelInc("STOP_SLIP_OVER_10PT_8327_$cls")
            ForensicLogger.lifecycle("STOP_SLIP_8327",
                "mint=${o.mint.take(10)} reason=${t.reason} cls=$cls route=$route triggerPnl=${"%.1f".format(t.pnlPct)} " +
                    "fillPnl=${"%.1f".format(o.realizedPnlPct ?: Double.NaN)} slipPt=${"%.1f".format(slip)} triggerToFillMs=$t2f " +
                    "markAgeAtTriggerMs=${t.markAgeMs} markSrc=${t.source}")
        } catch (_: Throwable) {}
    }

    fun statusLine8327(): String {
        val body = aggs8327.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}{${it.value.text()}}" }.ifBlank { "-" }
        return "stopSlip8327[open=${triggers8327.size} $body]"
    }

    internal fun resetForTest8327() { triggers8327.clear(); routes8327.clear(); aggs8327.clear() }
}
