package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7967 — GRAB THE RUNNER, HOLD THE RUNNER.
 *
 * Owner, 5.0.7964 live: "why couldn't it buy at 6k like I did, hold to 100k and sell" —
 * Spiralism (7veu…pump) ran $6k -> $104k in under 40 minutes. The bot's own labels already
 * name the cell: pump.fun coins at $10k-$100k mcap under 15 minutes old read +160% net at the
 * 5-minute mark (n49 MOONSHOT, n36 SHITCOIN), while the bot refused 3,098 of 3,228 decisions
 * and, when it did buy, sold 60% at +40% and stopped out on -20% shakeouts (Frank: stopped,
 * then ran ~7x).
 *
 * GRAB: a candidate whose decision cell is PROVEN to run (its own forward labels: n >= 20,
 * mean - SE >= +20% net) and whose chart is FIRMING right now (StructureTracker7962: a run of
 * 2+ higher swings, a higher low, not broken, buyers >= 55% of recent volume when the tape
 * knows) is a buy. It rides the chart reader's admit path past every learned / plan / budget
 * refusal; hard safety (safety-tier HARD_BLOCK, Mayhem, dev sold) still refuses. Size is
 * raised to 2x (inside the existing 2.5x cap).
 *
 * HOLD: a grabbed position runs the owner's play instead of the scalp ladder: half off at
 * +100% (stake and profit banked), 35% of the rest at +400%, 35% at +1000%; every soft exit
 * (profit locks, trails, early-rug backstops, tick floors, catastrophe stops above -35%) is
 * deferred while the 1-minute structure holds; it exits on the structure break (the first
 * lower low after the run), at -35%, or on rug / dev / liquidity evidence.
 *
 * LEARNS: every grab is graded 30 minutes later on the same tape; once 20 grabs show a
 * negative mean (mean + SE < 0) the admit stands down (signals keep being graded, and it
 * re-arms when the record recovers). Every coin the bot SAW and refused that went on to
 * +400% is recorded with the refusal reason that cost it (missed-runner audit).
 */
object RunnerGrab7967 {
    private val RUNNER_LANES = listOf("MOONSHOT", "SHITCOIN", "EXPRESS")
    private const val PROVEN_MIN_N = 20
    private const val PROVEN_MARGIN_PCT = 20.0
    private const val MIN_BUY_SHARE = 0.55
    private const val HOLD_WINDOW_MS = 6L * 3_600_000L
    private const val HARD_STOP_PCT = -35.0
    private const val GRADE_AFTER_MS = 30L * 60_000L
    private const val SELL_RETRY_MS = 20_000L
    private const val MISSED_PEAK_PCT = 400.0
    /** The owner's ladder for a grabbed runner: (gross %, fraction of the CURRENT holding sold). */
    val TIERS_7967: List<Pair<Double, Double>> = listOf(100.0 to 0.50, 400.0 to 0.35, 1_000.0 to 0.35)

    private val grabbed = ConcurrentHashMap<String, Long>()                  // mint -> first grab
    private val signals = ConcurrentHashMap<String, Pair<Double, Long>>()     // mint -> (px, at) awaiting grade
    private val record = CortexLedger7885.Stat()
    private val lastSell = ConcurrentHashMap<String, Long>()
    private val missedByReason = ConcurrentHashMap<String, AtomicLong>()
    private val missedRecent = java.util.ArrayDeque<String>()
    private val missedSeen = ConcurrentHashMap<String, Long>()
    private val grabs = AtomicLong(0)
    private val deferred = AtomicLong(0)
    private val exitsTaken = AtomicLong(0)
    private val caught = AtomicLong(0)

    // ── pure ──

    /** Pure: a decision cell proven to run (n >= 20, mean - SE >= +20% net at the 5-minute read). */
    fun provenCell7967(n: Int, meanPct: Double, sePct: Double): Boolean =
        n >= PROVEN_MIN_N && meanPct.isFinite() && sePct.isFinite() && meanPct - sePct >= PROVEN_MARGIN_PCT

    /** Pure: the chart is firming — 2+ higher swings, a higher low, unbroken, buyers dominate when known. */
    fun firming7967(r: StructureTracker7962.Read7962?): Boolean =
        r != null && r.hhHl >= 2 && r.higherLow && !r.brokeStructure &&
            r.lastLow.isFinite() && r.lastClose >= r.lastLow &&
            (!r.buyShare.isFinite() || r.buyShare >= MIN_BUY_SHARE)

    /**
     * Pure. V5.0.7970 — a cell clears the owner's round-trip bar: 20+ labels and the
     * 5-minute net return minus one standard error at or above +15%.
     */
    fun cellClearsBar7970(n: Int, meanPct: Double, sePct: Double): Boolean =
        n >= PROVEN_MIN_N && meanPct.isFinite() && sePct.isFinite() && meanPct - sePct >= 15.0

    /**
     * Pure. V5.0.7970 — the chart is not breaking down: no 1m structure break and sellers
     * not dominating the tape (buyers >= 45% when known). Unknown structure (a coin too young
     * for swings) does not refuse — the proven cell's labels were earned at decision time.
     */
    fun notBreaking7970(r: StructureTracker7962.Read7962?): Boolean =
        r == null || (!r.brokeStructure && (!r.buyShare.isFinite() || r.buyShare >= 0.45))

    /** Pure: has the grab record proven it loses (n >= 20, mean + SE < 0)? */
    fun recordStandsDown7967(st: CortexLedger7885.Stat): Boolean {
        if (st.n < 20.0) return false
        val se = kotlin.math.sqrt(st.variance() / st.n)
        return st.mean() + se < 0.0
    }

    /**
     * Pure: is [reason] an exit a held runner defers while its structure stands and it is above
     * [HARD_STOP_PCT]? Integrity exits (rug, dev, liquidity, honeypot, freeze, dead, manual) and
     * this module's own exits always pass.
     */
    fun deferrable7967(reason: String, grossPct: Double): Boolean {
        if (!grossPct.isFinite() || grossPct <= HARD_STOP_PCT) return false
        val r = reason.uppercase()
        // V5.0.7969 — the early-rug BACKSTOP is a soft -10% tick exit, not rug evidence.
        if (r.contains("EARLY_RUG_BACKSTOP")) return true
        val integrity = listOf("RUG", "DEV_", "DEV SOLD", "LIQUIDITY", "HONEYPOT", "FREEZE", "FROZEN", "DEAD", "MANUAL",
            "RUNNER_", "STRUCTURE_BREAK", "SPIKE_CAPTURE", "ZERO_BALANCE", "ORPHAN", "INVARIANT", "STARTUP_SWEEP")
        return integrity.none { r.contains(it) }
    }

    // ── grab ──

    private fun stat(lane: String, ts: TokenState, nowMs: Long): ForwardReturnLabeler7731.CellStat? =
        try { ForwardReturnLabeler7731.cellStatFor(ts, lane, nowMs) } catch (_: Throwable) { null }

    /** The proven runner cell this token sits in now, or null. */
    private fun provenCellFor(ts: TokenState, nowMs: Long): String? = RUNNER_LANES.firstNotNullOfOrNull { l ->
        stat(l, ts, nowMs)?.takeIf { provenCell7967(it.n60, it.meanNet60Pct, it.stderr60Pct) }?.key
    }

    private fun standing7970(mint: String, nowMs: Long): Boolean {
        val (_, r60) = StructureTracker7962.reads7967(mint, nowMs)
        return notBreaking7970(r60)
    }

    /**
     * LiveEdgeGate7877: a lane-wide "no setup fired, proven losing" record yields to the
     * narrower cell this token sits in when that cell clears +15% net after one SE.
     * The aggregate contains the cell; the cell is the more specific measurement.
     */
    fun cellBeatsLane7970(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val st = stat(lane, ts, nowMs) ?: return false
        val ok = cellClearsBar7970(st.n60, st.meanNet60Pct, st.stderr60Pct)
        if (ok) try { PipelineHealthCollector.labelInc("CELL_BEATS_LANE_7970_${lane.uppercase()}") } catch (_: Throwable) {}
        return ok
    }

    /**
     * LiveEdgeGate7877: a live admit (any path) of a token in a proven runner cell is held
     * like a grab — any buy can become the runner, so it gets the runner ladder and the
     * soft-exit deferral while its 1m structure stands.
     */
    fun noteAdmit7970(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ts.position.isOpen) return false
        provenCellFor(ts, nowMs) ?: return false
        if (grabbed.containsKey(ts.mint)) return true
        grabbed[ts.mint] = nowMs
        val px = ts.lastPrice
        if (px.isFinite() && px > 0.0 && signals.size < 2_000) signals.putIfAbsent(ts.mint, px to nowMs)
        heldAdmits.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_HOLD_ON_ADMIT_7970") } catch (_: Throwable) {}
        return true
    }
    private val heldAdmits = AtomicLong(0)

    /** V5.0.7977 — MemoryGuard7977: forget grabs older than the hold window and stale add state. */
    fun trim7977() {
        val now = System.currentTimeMillis()
        grabbed.entries.removeIf { now - it.value > HOLD_WINDOW_MS }
        addState.clear()
        lastSell.clear()
    }

    /** V5.0.7972 — LiveEdgeGate7877: a specialist whose labels run is held like a grab. */
    fun holdAsRunner7972(ts: TokenState, nowMs: Long = System.currentTimeMillis()) {
        if (ts.position.isOpen || grabbed.putIfAbsent(ts.mint, nowMs) != null) return
        heldAdmits.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_HOLD_SPECIALIST_7972") } catch (_: Throwable) {}
    }

    private fun firmingNow(mint: String, nowMs: Long): Boolean {
        val (r15, r60) = StructureTracker7962.reads7967(mint, nowMs)
        return firming7967(r15) || firming7967(r60)
    }

    /**
     * ChartReader7950.saysBuy / admitsLive: a forming runner in a proven cell. Hard safety refuses.
     * Called on the admission path; reads are cached and O(1)-ish.
     */
    fun grab7967(mint: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank()) return false
        val ts = try { BotService.status.tokens[mint] } catch (_: Throwable) { null } ?: return false
        if (ts.position.isOpen) return false
        if (ts.safety.tier == SafetyTier.HARD_BLOCK) return false
        if (try { MayhemMode7943.liveRefusal(ts, nowMs) } catch (_: Throwable) { null } != null) return false
        // V5.0.7968 — a dev sale does not stop a grab (devs sell to side wallets).
        provenCellFor(ts, nowMs) ?: return false
        // V5.0.7970 — the cell's labels were earned on every decision in it, firming or not:
        // its best cells (n50 +157%, n39 +174% at 5 min) are mostly coins too young for two
        // swings. Firming still counts; otherwise a cell admit needs only a chart not breaking.
        if (!firmingNow(mint, nowMs) && !standing7970(mint, nowMs)) return false
        // Graded whether or not the admit is live.
        val px = ts.lastPrice
        if (px.isFinite() && px > 0.0 && signals.size < 2_000) signals.putIfAbsent(mint, px to nowMs)
        gradeDue(nowMs)
        if (synchronized(record) { recordStandsDown7967(record) }) {
            try { PipelineHealthCollector.labelInc("RUNNER_GRAB_STOOD_DOWN_7967") } catch (_: Throwable) {}
            return false
        }
        if (grabbed.putIfAbsent(mint, nowMs) == null) {
            grabs.incrementAndGet()
            if (grabbed.size > 2_000) grabbed.entries.removeIf { nowMs - it.value > HOLD_WINDOW_MS }
            try {
                PipelineHealthCollector.labelInc("RUNNER_GRAB_7967")
                ForensicLogger.lifecycle("RUNNER_GRAB_7967", "mint=${mint.take(10)} sym=${ts.symbol} mcap=${ts.lastMcap.toInt()} px=$px action=admit_and_hold")
            } catch (_: Throwable) {}
        }
        return true
    }

    /** Grade every grab signal whose 30-minute horizon has passed, on the structure tape. */
    private fun gradeDue(nowMs: Long) {
        for ((mint, sig) in signals.entries.toList()) {
            val (px, at) = sig
            if (nowMs - at < GRADE_AFTER_MS) continue
            signals.remove(mint)
            val later = try { StructureTracker7962.markAt7962(mint, at + GRADE_AFTER_MS) } catch (_: Throwable) { null } ?: continue
            if (!(later > 0.0) || !(px > 0.0)) continue
            val r = later / px
            if (r < 0.01 || r > 200.0) continue
            val g = ((r - 1.0) * 100.0).coerceIn(-100.0, 1_000.0)
            synchronized(record) { record.add(g, g >= 100.0) }
        }
    }

    // ── size ──

    /**
     * TraderSizingBridge6444: a grabbed runner opens at 1.5x (never above the 2.5x cap).
     * V5.0.7973 — was 2x up front; the rest of the size now goes in on confirmation
     * ([structureAdd7973]): losers die at the starter size, winners end up bigger.
     */
    fun sizeMult7967(mint: String, mult: Double): Double {
        val m = if (mult.isFinite() && mult > 0.0) mult else 1.0
        val at = grabbed[mint] ?: return m
        if (System.currentTimeMillis() - at > 10L * 60_000L) return m
        return maxOf(m, STARTER_MULT_7973).coerceAtMost(2.5)
    }
    private const val STARTER_MULT_7973 = 1.5

    /**
     * Pure. V5.0.7973 — scale in on confirmation: add once per NEW confirmed swing (the 1m
     * higher-high/higher-low count above the count at the last add), only in profit, with a
     * higher low in place and the structure unbroken, at most [MAX_ADDS_7973] adds.
     */
    fun addConfirmed7973(r: StructureTracker7962.Read7962?, swingsAtLastAdd: Int, adds: Int, grossPct: Double): Boolean =
        r != null && adds < MAX_ADDS_7973 && grossPct.isFinite() && grossPct > 0.0 &&
            r.higherLow && !r.brokeStructure && r.hhHl > swingsAtLastAdd && r.hhHl >= 2 &&
            (!r.buyShare.isFinite() || r.buyShare >= MIN_BUY_SHARE)
    private const val MAX_ADDS_7973 = 2
    private val addState = ConcurrentHashMap<String, IntArray>()
    private val addsSignalled = AtomicLong(0)

    /** Executor.autonomousTopUpSignal6091: a held runner (or proven-cell admit) just confirmed a new swing — add. */
    fun structureAdd7973(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (ts.position.isPaperPosition || !holding7967(ts, nowMs)) return false
        val (_, r60) = StructureTracker7962.reads7967(ts.mint, nowMs)
        val k = "${ts.mint}|${ts.position.entryTime}"
        val tc = ts.position.topUpCount
        // [swings at the last executed add, top-ups seen, swings when the pending add was signalled, top-ups at open]
        val st = addState.getOrPut(k) { intArrayOf(r60?.hhHl ?: 0, tc, -1, tc) }
        // An add is counted only once the executor actually topped up (its own gain/cooldown/exposure checks may decline).
        if (tc > st[1]) { st[1] = tc; if (st[2] >= 0) st[0] = st[2]; st[2] = -1 }
        if (!addConfirmed7973(r60, st[0], tc - st[3], gross(ts, null))) return false
        st[2] = r60!!.hhHl
        if (addState.size > 2_000) addState.clear()
        addsSignalled.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_STRUCTURE_ADD_7973") } catch (_: Throwable) {}
        return true
    }

    // ── hold ──

    private fun gross(ts: TokenState, px: Double?): Double {
        val e = ts.position.entryPrice
        val p = px ?: ts.lastPrice
        return if (e > 0.0 && p > 0.0) (p / e - 1.0) * 100.0 else Double.NaN
    }

    /** A held position this module grabbed (within the hold window). */
    private fun holding7967(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = grabbed[ts.mint] ?: return false
        return ts.position.isOpen && ts.position.entryTime >= at - 5L * 60_000L && nowMs - at <= HOLD_WINDOW_MS
    }

    /** SpikeCapture7943.onMark: the runner ladder for a grabbed position, else null. */
    fun tiersFor7967(ts: TokenState): List<Pair<Double, Double>>? = if (holding7967(ts)) TIERS_7967 else null

    /** Executor.requestSellCore7948: the deferral label when a held runner should not take this exit. */
    fun deferSell7967(ts: TokenState, reason: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (ts.position.isPaperPosition || !holding7967(ts, nowMs)) return null
        val g = gross(ts, null)
        if (!deferrable7967(reason, g)) return null
        val (_, r60) = StructureTracker7962.reads7967(ts.mint, nowMs)
        if (r60 != null && r60.brokeStructure) return null
        deferred.incrementAndGet()
        try { PipelineHealthCollector.labelInc("RUNNER_HOLD_DEFERRED_7967") } catch (_: Throwable) {}
        return "RUNNER_HOLD_7967"
    }

    private val peaks7980 = ConcurrentHashMap<String, Double>()

    /** V5.0.7980 — Executor: is [mint] a runner admit (grab / proven cell / runner specialist)? Its buy lands urgently. */
    fun isGrabbed7980(mint: String): Boolean = grabbed.containsKey(mint)

    /**
     * Pure. V5.0.7980 — peak capture: after a gross peak of at least +200%, price 35% below the
     * peak price (gross % values: (1 + g) <= 0.65 x (1 + peak)).
     */
    fun peakGiveback7980(peakPct: Double, grossPct: Double): Boolean =
        peakPct.isFinite() && grossPct.isFinite() && peakPct >= 200.0 &&
            (1.0 + grossPct / 100.0) <= 0.65 * (1.0 + peakPct / 100.0)

    /** SpikeCapture7943.rapidMark: a held runner's own exits — structure break or the -35% stop. */
    fun heldExit7967(ts: TokenState, px: Double?, nowMs: Long, sell: (TokenState, Double, String) -> Unit) {
        if (!holding7967(ts, nowMs)) return
        val pos = ts.position
        val g = gross(ts, px)
        if (!g.isFinite()) return
        // V5.0.7980 — the peak, tick by tick from entry (curve ticks / trade marks reach here per print).
        val pk = "${ts.mint}|${pos.entryTime}"
        val peak = peaks7980.merge(pk, g) { a, b -> maxOf(a, b) } ?: g
        if (peaks7980.size > 2_000) peaks7980.clear()
        if (nowMs - pos.entryTime < 60_000L && !peakGiveback7980(peak, g)) return
        val (r15, r60) = StructureTracker7962.reads7967(ts.mint, nowMs)
        val why = when {
            g <= HARD_STOP_PCT -> "RUNNER_HARD_STOP_7967_${g.toInt()}PCT"
            // V5.0.7980 — a huge quick breakout gives back fast: once it has run +200%, the rest
            // goes when price is 35% below its peak, without waiting for a bar to close.
            peakGiveback7980(peak, g) -> "RUNNER_PEAK_CAPTURE_7980_${peak.toInt()}PK_${g.toInt()}PCT"
            r60 != null && r60.brokeStructure -> "RUNNER_STRUCTURE_BREAK_7967_1M_${g.toInt()}PCT"
            g >= 50.0 && r15 != null && r15.brokeStructure -> "RUNNER_STRUCTURE_BREAK_7967_15S_${g.toInt()}PCT"
            else -> return
        }
        val k = "${ts.mint}|${pos.entryTime}"
        if (nowMs - (lastSell[k] ?: 0L) < SELL_RETRY_MS) return
        if (lastSell.size > 2_000) lastSell.clear()
        lastSell[k] = nowMs
        exitsTaken.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("RUNNER_EXIT_7967")
            ForensicLogger.lifecycle("RUNNER_EXIT_7967", "mint=${ts.mint.take(10)} sym=${ts.symbol} gross=${"%.1f".format(g)}% why=$why")
        } catch (_: Throwable) {}
        sell(ts, 1.0, why)
    }

    // ── missed-runner audit ──

    /** ForwardReturnLabeler7731: an observation's peak crossed +400% (once per mint). */
    fun onRunnerLabel7967(mint: String, symbol: String, lane: String, admitted: Boolean, reason: String?, peakPct: Double, nowMs: Long = System.currentTimeMillis()) {
        if (peakPct < MISSED_PEAK_PCT || missedSeen.putIfAbsent(mint, nowMs) != null) return
        if (missedSeen.size > 4_000) missedSeen.entries.removeIf { nowMs - it.value > 24L * 3_600_000L }
        if (admitted) { caught.incrementAndGet(); return }
        val why = reasonKey7967(reason)
        missedByReason.computeIfAbsent(why) { AtomicLong(0) }.incrementAndGet()
        synchronized(missedRecent) {
            missedRecent.addLast("${symbol.take(10)}:${lane.take(8)}:+${peakPct.toInt()}%:$why")
            while (missedRecent.size > 8) missedRecent.removeFirst()
        }
        try {
            PipelineHealthCollector.labelInc("MISSED_RUNNER_7967")
            ForensicLogger.lifecycle("MISSED_RUNNER_7967", "mint=${mint.take(12)} sym=$symbol lane=$lane peak=${peakPct.toInt()}% refusedBy=${reason.orEmpty().take(80)}")
        } catch (_: Throwable) {}
    }

    /** Pure: the refusal reason's rule name (leading upper-case words, max 4). */
    fun reasonKey7967(reason: String?): String =
        reason.orEmpty().trim().split('_', ':', ' ', '/').filter { w -> w.isNotEmpty() }
            .takeWhile { w -> w.all { it.isUpperCase() || it.isDigit() } }.take(4).joinToString("_").ifBlank { "UNKNOWN" }

    fun statusLine7967(): String {
        val st = synchronized(record) { if (record.n >= 1.0) "n${record.n.toInt()}/${"%+.1f".format(record.mean())}%" else "-" }
        val down = synchronized(record) { recordStandsDown7967(record) }
        val top = missedByReason.entries.sortedByDescending { it.value.get() }.take(5).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val recent = synchronized(missedRecent) { missedRecent.joinToString(" · ") }.ifBlank { "-" }
        return "grabs=${grabs.get()} heldAdmits7970=${heldAdmits.get()} adds7973=${addsSignalled.get()} holding=${grabbed.size} deferredSoftExits=${deferred.get()} runnerExits=${exitsTaken.get()} " +
            "record30m=$st${if (down) " STOOD_DOWN" else ""} pendingGrades=${signals.size} | runners>=+400%: caught=${caught.get()} " +
            "missed=${missedByReason.values.sumOf { it.get() }} byReason=[$top] recent=[$recent]"
    }

    internal fun resetForTest7967() {
        grabbed.clear(); signals.clear(); missedByReason.clear(); missedSeen.clear()
        synchronized(missedRecent) { missedRecent.clear() }
    }
}
