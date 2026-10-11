package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8065 — LIFECYCLE HOLD. Owner: "it keeps trading like a Sniper bot. some trades take days or weeks to truly run
 * their lifecycle! saadboi took a few weeks to a few months to run."
 *
 * 5.0.8063 live: 42 sells, 2 wins, most closed inside one to four minutes on a tick stop (RAPID_FLUID_STOP,
 * RAPID_CATASTROPHE_STOP at -22% after 11 s, PROTECTIVE_EXIT_STOP_LOSS, time exits, flat culls). Every lane traded
 * a 5-minute scalp. A lifecycle lane's position is now held like a position trader holds it:
 *
 *   - ordinary exits (tick stops, catastrophe ticks above the floor, time exits, flat culls, profit locks, trails,
 *     spike tiers, runner / chart exits) are HELD; integrity exits (rug, liquidity pull, dev dump, honeypot, freeze,
 *     dead token, manual, operator stop, wallet / reconciler) always pass (TailBag8034.structural8034);
 *   - its own exits, on its own 15-minute bars:
 *       FLOOR       gross <= -[FLOOR_PCT] at any time;
 *       BASE BROKE  after [SETTLE_MS], still under the 2x take, a 15m close below the lowest low of the prior 16 bars
 *                   (the 4-hour base) while red more than [BASE_BROKE_RED_PCT];
 *       2X TAKE     +100%: half is sold — the stake is back, the rest is a free ride (and takes no lane slot);
 *       LADDER      +400%: 30% of the rest; +900%: 30% of the rest;
 *       TREND BREAK after the take: a 15m close below the lowest low of the prior 24 bars (the 6-hour range), or
 *                   after a +300% peak the price at 45% of the peak multiple;
 *       DEAD MONEY  after 72 h flat (-15%..+20%) with no new high in 48 h: released, the capital works elsewhere.
 *
 * Lifecycle lanes: [LANES_8065]. Any other live position that reaches +100% is promoted into the hold. State and
 * bars survive restarts (lifecycle_8065.txt). The forward labels grade these lanes on the same floor
 * (StopAuthority7887.laneStopMag8029), and their progressive checkpoints run to 7 days.
 */
object LifecycleHold8065 {
    private val LANES_8065 = setOf("MOONSHOT", "QUALITY", "BLUECHIP", "CORE")
    const val FLOOR_PCT = 40.0
    private const val PROMOTE_PCT = 100.0
    private const val SETTLE_MS = 2L * 3_600_000L
    private const val BASE_BROKE_RED_PCT = -15.0
    private const val BAR_MS = 15L * 60_000L
    private const val MAX_BARS = 700
    private const val DEAD_AFTER_MS = 72L * 3_600_000L
    private const val NO_HIGH_MS = 48L * 3_600_000L
    private const val SELL_RETRY_MS = 30_000L
    private const val FILE = "lifecycle_8065.txt"
    /** (gross %, share of the CURRENT holding sold). */
    private val TAKES_8065 = listOf(100.0 to 0.50, 400.0 to 0.30, 900.0 to 0.30)

    /** Per position (mint|entryTime): takes done, promoted, peak gross, when the peak was set. */
    private class State(var taken: Int, var promoted: Boolean, var peak: Double, var peakAt: Long)

    private val states = ConcurrentHashMap<String, State>()
    private val bars = ConcurrentHashMap<String, ArrayDeque<DoubleArray>>()  // mint -> [startMs, o, h, l, c]
    private val lastSell = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false
    @Volatile private var lastSaveMs = 0L
    private val held = AtomicLong(0)
    private val partialsHeld = AtomicLong(0)
    private val promotions = AtomicLong(0)
    private val exits = ConcurrentHashMap<String, AtomicLong>()

    // ── pure ──

    /** Pure: is [reason] an exit a lifecycle hold never holds through (integrity, operator, its own)? */
    fun passes8065(reason: String): Boolean {
        val r = reason.uppercase()
        return r.startsWith("LIFECYCLE_") || TailBag8034.structural8034(r)
    }

    /** V5.0.8071 — the lifecycle ladder, for the forward labels that grade lifecycle lanes on it. */
    fun ladder8071(): List<Pair<Double, Double>> = TAKES_8065

    /** Pure: the take (index, fraction) due at [grossPct] with [taken] already done, or null. */
    fun takeDue8065(grossPct: Double, taken: Int): Pair<Int, Double>? {
        if (!grossPct.isFinite() || taken >= TAKES_8065.size) return null
        val (bar, frac) = TAKES_8065[taken]
        return if (grossPct >= bar) taken to frac else null
    }

    /** Pure: a close below the lowest low of the [lookback] bars before the last one (bars: [start, o, h, l, c]). */
    fun rangeBroken8065(b: List<DoubleArray>, lookback: Int): Boolean {
        if (b.size < lookback + 1) return false
        val last = b[b.size - 1]
        val prior = b.subList(b.size - 1 - lookback, b.size - 1)
        val floor = prior.minOf { it[3] }
        return last[4] < floor
    }

    /** Pure: after a +300% peak, price at or below 45% of the peak multiple. */
    fun peakGiveback8065(peakPct: Double, grossPct: Double): Boolean =
        peakPct >= 300.0 && grossPct.isFinite() && (1.0 + grossPct / 100.0) <= 0.45 * (1.0 + peakPct / 100.0)

    /** Pure: the dead-money release. */
    fun deadMoney8065(ageMs: Long, grossPct: Double, sinceHighMs: Long): Boolean =
        ageMs >= DEAD_AFTER_MS && grossPct in -15.0..20.0 && sinceHighMs >= NO_HIGH_MS

    // ── state ──

    private fun laneOf(ts: TokenState): String =
        (try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(ts.position.tradingMode) } catch (_: Throwable) { "" })
            .ifBlank { ts.position.tradingMode }.trim().uppercase()

    /** Is [lane] a lifecycle lane (labels graded on the lifecycle floor)? */
    fun lifecycleLane8065(lane: String): Boolean = lane.trim().uppercase() in LANES_8065

    private fun key(ts: TokenState) = "${ts.mint}|${ts.position.entryTime}"

    private fun gross(ts: TokenState, px: Double?): Double {
        val e = ts.position.entryPrice
        val p = px ?: ts.lastPrice
        return if (e > 0.0 && p > 0.0) (p / e - 1.0) * 100.0 else Double.NaN
    }

    /** Is [ts]'s open live position held for its lifecycle (a lifecycle lane, or promoted at +100%)? */
    private fun holding8065(ts: TokenState): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || pos.entryTime <= 0L || !(pos.entryPrice > 0.0)) return false
        load()
        if (lifecycleLane8065(laneOf(ts))) return true
        // V5.0.8066 — once held, always held: a lane transfer (LaneTransitionManager, by market cap) never releases it.
        return states[key(ts)] != null
    }

    /** V5.0.8066 — the owner's Stop leaves lifecycle holds in the wallet (BotService.stopBot). */
    fun keepOnStop8066(ts: TokenState): Boolean = holding8065(ts)

    /** V5.0.8066 — the open-positions label: "" when not held, else HOLD / FREE RIDE. */
    fun label8066(ts: TokenState): String {
        if (!holding8065(ts)) return ""
        return if ((states[key(ts)]?.taken ?: 0) >= 1) "🧬 FREE RIDE" else "🧬 HOLD"
    }

    /** LiveRiskPolicy7807: a lifecycle position past its 2x take is a free ride — it takes no lane slot. */
    fun freeRide8065(mint: String): Boolean { load(); return states.entries.any { it.key.startsWith("$mint|") && it.value.taken >= 1 } }

    // ── the sell doors ──

    /** Executor.requestSellCore7948: non-null when this exit is held for the position's lifecycle. */
    fun defer8065(ts: TokenState, reason: String): String? {
        if (!holding8065(ts) || passes8065(reason)) return null
        val g = gross(ts, null)
        if (g.isFinite() && g <= -FLOOR_PCT) return null
        held.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIFECYCLE_HELD_8065") } catch (_: Throwable) {}
        return "LIFECYCLE_HOLD_8065"
    }

    /** Executor.requestPartialSell: an ordinary slice (spike tier, ladder, lock) is not sold from a lifecycle hold. */
    fun partialHeld8065(ts: TokenState, reason: String): Boolean {
        if (!holding8065(ts) || passes8065(reason)) return false
        partialsHeld.incrementAndGet()
        return true
    }

    // ── the hold's own exits (SpikeCapture7943.rapidMark, every open position, ~500 ms) ──

    /** Returns true when [ts] is a lifecycle hold (the caller then skips the scalp exits for it). */
    fun onMark8065(ts: TokenState, px: Double?, nowMs: Long, sell: (TokenState, Double, String) -> Unit): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || pos.entryTime <= 0L) return false
        load()
        val g = gross(ts, px)
        val k = key(ts)
        if (!holding8065(ts)) {
            // Promotion: any live position that reaches +100% is held for its lifecycle from here.
            if (g.isFinite() && g >= PROMOTE_PCT) {
                states[k] = State(0, true, g, nowMs)
                promotions.incrementAndGet()
                try { PipelineHealthCollector.labelInc("LIFECYCLE_PROMOTED_8065") } catch (_: Throwable) {}
                saveMaybe(nowMs, force = true)
            } else return false
        }
        if (!g.isFinite() || px == null || !(px > 0.0)) return true
        val st = states.getOrPut(k) { State(0, false, g, nowMs) }
        if (g > st.peak) { st.peak = g; st.peakAt = nowMs }
        val b = barsFor(ts.mint, px, nowMs)
        val age = nowMs - pos.entryTime
        val why: Pair<String, Double>? = when {
            g <= -FLOOR_PCT -> "LIFECYCLE_STOP_FLOOR_8065_${g.toInt()}PCT" to 1.0
            takeDue8065(g, st.taken) != null -> takeDue8065(g, st.taken)!!.let { (i, f) -> "LIFECYCLE_PROFIT_TAKE${i + 1}_8065_${g.toInt()}PCT" to f }
            st.taken == 0 && age >= SETTLE_MS && g < BASE_BROKE_RED_PCT && closedBarBroke(b, 16) ->
                "LIFECYCLE_STOP_BASE_BROKE_8065_${g.toInt()}PCT" to 1.0
            st.taken >= 1 && (closedBarBroke(b, 24) || peakGiveback8065(st.peak, g)) ->
                "LIFECYCLE_STOP_TREND_BREAK_8065_${st.peak.toInt()}PK_${g.toInt()}PCT" to 1.0
            deadMoney8065(age, g, nowMs - st.peakAt) -> "LIFECYCLE_STOP_DEAD_MONEY_8065_${g.toInt()}PCT" to 1.0
            else -> null
        }
        saveMaybe(nowMs)
        if (why == null) return true
        if (nowMs - (lastSell[k] ?: 0L) < SELL_RETRY_MS) return true
        if (lastSell.size > 2_000) lastSell.clear()
        lastSell[k] = nowMs
        val (reason, frac) = why
        if (reason.contains("_PROFIT_TAKE")) st.taken += 1
        exits.getOrPut(reason.substringBefore("_8065")) { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(reason.substringBefore("_8065") + "_8065")
            ForensicLogger.lifecycle("LIFECYCLE_EXIT_8065", "mint=${ts.mint.take(10)} sym=${ts.symbol} gross=${"%.1f".format(g)}% peak=${"%.1f".format(st.peak)}% frac=$frac why=$reason")
        } catch (_: Throwable) {}
        saveMaybe(nowMs, force = true)
        sell(ts, frac, reason)
        return true
    }

    /** The last CLOSED bar broke the range (the open bar is still forming). */
    private fun closedBarBroke(b: List<DoubleArray>, lookback: Int): Boolean =
        b.size >= 2 && rangeBroken8065(b.subList(0, b.size - 1), lookback)

    private fun barsFor(mint: String, px: Double, nowMs: Long): List<DoubleArray> {
        val q = bars.getOrPut(mint) { ArrayDeque() }
        synchronized(q) {
            val start = (nowMs / BAR_MS) * BAR_MS
            val last = q.lastOrNull()
            if (last == null || last[0].toLong() != start) {
                q.addLast(doubleArrayOf(start.toDouble(), px, px, px, px))
                while (q.size > MAX_BARS) q.removeFirst()
            } else {
                last[2] = maxOf(last[2], px); last[3] = minOf(last[3], px); last[4] = px
            }
            return q.toList()
        }
    }

    // ── persistence ──

    private fun file(): java.io.File? = com.lifecyclebot.AATEApp.appContextOrNull()?.let { java.io.File(it.filesDir, FILE) }

    private fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                file()?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                    val f = line.split('\t')
                    when (f.getOrNull(0)) {
                        "S" -> if (f.size >= 6) states[f[1]] = State(f[2].toIntOrNull() ?: 0, f[3] == "1", f[4].toDoubleOrNull() ?: 0.0, f[5].toLongOrNull() ?: 0L)
                        "B" -> if (f.size >= 7) bars.getOrPut(f[1]) { ArrayDeque() }.addLast(DoubleArray(5) { i -> f[2 + i].toDoubleOrNull() ?: 0.0 })
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun saveMaybe(nowMs: Long, force: Boolean = false) {
        if (!force && nowMs - lastSaveMs < 5L * 60_000L) return
        lastSaveMs = nowMs
        val f = file() ?: return
        try {
            // Only open positions' state and bars are kept.
            val open = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().map { it.mint }.toSet() } catch (_: Throwable) { null }
            if (open != null) {
                states.keys.removeIf { it.substringBefore('|') !in open }
                bars.keys.removeIf { it !in open }
            }
            val sb = StringBuilder()
            for ((k, s) in states) sb.append("S\t$k\t${s.taken}\t${if (s.promoted) 1 else 0}\t${s.peak}\t${s.peakAt}\n")
            for ((m, q) in bars) synchronized(q) { for (b in q) sb.append("B\t$m\t${b[0]}\t${b[1]}\t${b[2]}\t${b[3]}\t${b[4]}\n") }
            val tmp = java.io.File(f.parentFile, "$FILE.tmp")
            tmp.writeText(sb.toString())
            tmp.renameTo(f)
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        load()
        return "holds=${states.size} freeRides=${states.values.count { it.taken >= 1 }} promoted=${promotions.get()} exitsHeld=${held.get()} slicesHeld=${partialsHeld.get()} " +
            "exits=[${exits.entries.joinToString(",") { "${it.key.removePrefix("LIFECYCLE_")}=${it.value.get()}" }.ifBlank { "-" }}] " +
            "lanes=${LANES_8065.joinToString("/")} rule=floor -${FLOOR_PCT.toInt()}% · half at 2x · 30% at 5x/10x · 15m base/trend breaks · dead money 72h"
    }
}
