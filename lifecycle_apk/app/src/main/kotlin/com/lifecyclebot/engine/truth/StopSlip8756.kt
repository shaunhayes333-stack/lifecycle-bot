package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8756 §WHERE_THE_STOP_LOSS_GOES — a stop set at -15..-25 % closed at -24..-61 % on paper (8755: FAM_FOLLOW_SIZE -60.8 %,
 * SHITCOIN -36.4 %, EXPRESS -30.2 %, MOONSHOT -23.9 %) and live catastrophe stops lost -0.115 of -0.134 SOL. Three places
 * can lose it, and each needs its own fix: DETECTION (the price was already past the level when the bot first saw it: the
 * trigger mark is far under the level), CONFIRMATION (the second mark / venue read came later) and EXECUTION (the fill
 * after the sell latency is far under the trigger). This keeps, per book and lane, the mean level, trigger and fill gross %
 * so the report says which one it is, and for paper whether a tape print (the 8756 fast path) or the sweep triggered it.
 * Telemetry only: nothing here changes a stop.
 */
object StopSlip8756 {
    // V5.0.8810 — the paper trigger is the SECOND mark under the level (MemePaperTrader8544.paperStopConfirmed8683), so
    // level -> trig mixed two causes. [first] is the gross at the FIRST mark under the level: level -> first is the price
    // already past the stop when it was first seen (a jump between marks, or slow marks), first -> trig is the confirmation
    // wait. 5.0.8809, 20 min: EXPRESS level -5 trig -26 on n5, two of them on a tape print.
    private class Acc { var n = 0; var level = 0.0; var trig = 0.0; var fill = 0.0; var byPrint = 0; var first = 0.0; var firstN = 0 }
    private val paper = ConcurrentHashMap<String, Acc>()
    private val live = ConcurrentHashMap<String, Acc>()
    private class Pending(val atMs: Long, val reason: String, val levelPct: Double, val trigPct: Double)
    private val pendingLive = ConcurrentHashMap<String, Pending>()

    private fun add(map: ConcurrentHashMap<String, Acc>, key: String, level: Double, trig: Double, fill: Double, byPrint: Boolean, first: Double = Double.NaN) {
        if (!trig.isFinite() || !fill.isFinite()) return
        if (map.size > 64 && !map.containsKey(key)) return
        val a = map.computeIfAbsent(key) { Acc() }
        synchronized(a) {
            a.n++; a.level += if (level.isFinite()) level else trig; a.trig += trig; a.fill += fill
            if (byPrint) a.byPrint++
            if (first.isFinite()) { a.first += first; a.firstN++ }
        }
    }

    /** MemePaperTrader8544: a paper stop of [lane] set at [levelPct], triggered at [trigPct], filled at [fillPct] (gross %). */
    fun notePaper8756(lane: String, levelPct: Double, trigPct: Double, fillPct: Double, byPrint: Boolean, firstPct: Double = Double.NaN) =
        add(paper, lane.trim().uppercase(), levelPct, trigPct, fillPct, byPrint, firstPct)

    /** Executor.requestSell: a live price-loss stop for [key] (mint|entry) is requested at mark pnl [trigPct]. First request wins. */
    fun noteLiveTrigger8756(key: String, reason: String, trigPct: Double, nowMs: Long) {
        if (!trigPct.isFinite()) return
        if (pendingLive.size > 256) pendingLive.entries.removeIf { nowMs - it.value.atMs > 30L * 60_000L }
        val level = try { com.lifecyclebot.engine.StopMarkConfirm8418.let { it.stopDepthPct8489(reason) ?: it.impliedStopDepthPct8681(reason) } } catch (_: Throwable) { null }
        pendingLive.putIfAbsent(key, Pending(nowMs, reason, level ?: Double.NaN, trigPct))
    }

    /** Executor.requestSell: the stop for [key] confirmed; [fillPct] is the realised exit pnl (gross %) when the position closed after the trigger. */
    fun noteLiveFill8756(key: String, fillPct: Double, exitAtMs: Long) {
        val p = pendingLive[key] ?: return
        if (exitAtMs < p.atMs - 1_000L) return
        pendingLive.remove(key)
        add(live, p.reason.replace(Regex("[-0-9.]+"), "").trim('_').take(32).ifBlank { "STOP" }, p.levelPct, p.trigPct, fillPct, false)
    }

    private fun lineOf(map: ConcurrentHashMap<String, Acc>): String = map.entries.sortedByDescending { it.value.n }.take(8).joinToString(" ") { (k, a) ->
        synchronized(a) {
            "$k:n${a.n} level${"%+.0f".format(a.level / a.n)}" + (if (a.firstN > 0) " first${"%+.0f".format(a.first / a.firstN)}" else "") +
                " trig${"%+.0f".format(a.trig / a.n)} fill${"%+.0f".format(a.fill / a.n)}" +
                (if (a.byPrint > 0) " print${a.byPrint}" else "")
        }
    }.ifBlank { "-" }

    fun statusLine8756(): String = "stopSlip8756[paper{${lineOf(paper)}} live{${lineOf(live)}} pendingLive=${pendingLive.size} " +
        "read=level->first is the price past the stop when first seen (a jump between marks or slow marks), first->trig is the " +
        "two-mark confirmation, trig->fill is execution (sell latency / the venue moved)]"

    internal fun resetForTest8756() { paper.clear(); live.clear(); pendingLive.clear() }
}
