package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7955 — exits learned per (lane, setup).
 *
 * SpikeCapture7943 sold every lane and every setup on one ladder (+40% sell 60%,
 * +120% sell 70%, +400% sell 70%) and it never fired live. A pop-and-fade setup
 * (peaks +35% at minute two, back at entry by minute five) never reached the first
 * rung; a runner setup (a third of its launches go past +100%) sold 60% of the bag
 * on the first +40% wiggle. This learns, per "<lane>|<setup>" (setup = the
 * LanePlaybook7907 setup at entry, or a CHART_* key when the chart reader bought it),
 * with lane-level and global fallbacks:
 *
 *   - the peak a winner reaches (median / p75 / p90),
 *   - how long it takes to get there,
 *   - how much of that peak it gives back by the read (fraction of the peak gain),
 *   - how often it runs past +[RUNNER_PEAK_PCT_7955]%.
 *
 * Fed by every forward label (ForwardReturnLabeler7731: peak, time to peak and the
 * give-back to the 60-minute read, with the 5-minute give-back alongside) and by
 * realised closes (peak, exit, hold). Each position gets a plan at its first exit
 * read: tier levels at the key's winner peak quantiles, tier fractions heavier when
 * the key fades and lighter when it runs, a trail width from the give-back, and a
 * max hold from the time to peak. Under [MIN_N_7955] samples the plan is shrunk
 * toward today's values (the SpikeCapture7943.TIERS prior).
 */
object ExitProfile7955 {
    private const val MIN_N_7955 = 20.0
    private const val WIN_PEAK_PCT_7955 = 10.0
    private const val RUNNER_PEAK_PCT_7955 = 100.0
    private const val RING_7955 = 200
    private const val MAX_KEYS_7955 = 400
    private const val PRIOR_TRAIL_FRAC_7955 = 0.35
    private const val PRIOR_MAX_HOLD_MS_7955 = 60L * 60_000L
    private const val PERSIST_KEY_7955 = "EXIT_PROFILE_7955"
    private const val PERSIST_EVERY_7955 = 25
    private const val PLAN_CACHE_MS_7955 = 60_000L
    private const val GLOBAL_7955 = "*"

    /** Learned shape of one key. Giveback is a fraction of the peak gain (0 = held it all). */
    data class Profile7955(
        val n: Int,
        val nWin: Int,
        val medPeak: Double,
        val p75Peak: Double,
        val p90Peak: Double,
        val medTtpMin: Double,
        val medGiveback: Double,
        val medGiveback5: Double,
        val runnerRate: Double,
        /** V5.0.7961 — the ladder that captured most on this key's own peaks (NaN until 10 winners). */
        val optLevels: List<Double> = emptyList(),
    )

    /** A position's exit plan. [tiers] = (gross % trigger, fraction of the current holding sold). */
    data class Plan7955(
        val key: String,
        val source: String,
        val n: Int,
        val tiers: List<Pair<Double, Double>>,
        val trailFrac: Double,
        val maxHoldMs: Long,
        val runner: Boolean,
        val popFade: Boolean,
    )

    // ── pure ──

    private fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val pos = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    /** Pure: the share of a [peakPct] gain given back by a read at [readPct] (NaN when there was no real peak). */
    fun giveback7955(peakPct: Double, readPct: Double): Double {
        if (!peakPct.isFinite() || !readPct.isFinite() || peakPct < WIN_PEAK_PCT_7955) return Double.NaN
        // V5.0.7955 review — capped at the whole peak (a read below entry is a 100% give-back).
        return ((peakPct - readPct) / peakPct).coerceIn(0.0, 1.0)
    }

    /** Pure: a key's profile from its samples [peakPct, timeToPeakMin, giveback60, giveback5]. */
    fun profileOf7955(samples: List<DoubleArray>): Profile7955? {
        val ok = samples.filter { it.size >= 4 && it[0].isFinite() }
        if (ok.isEmpty()) return null
        val win = ok.filter { it[0] >= WIN_PEAK_PCT_7955 }
        val peaks = win.map { it[0] }.sorted()
        fun med(xs: List<Double>) = quantile(xs.filter { it.isFinite() }.sorted(), 0.5)
        return Profile7955(
            n = ok.size,
            nWin = win.size,
            medPeak = quantile(peaks, 0.5),
            p75Peak = quantile(peaks, 0.75),
            p90Peak = quantile(peaks, 0.90),
            medTtpMin = med(win.map { it[1] }),
            medGiveback = med(win.map { it[2] }),
            medGiveback5 = med(win.map { it[3] }),
            runnerRate = ok.count { it[0] >= RUNNER_PEAK_PCT_7955 }.toDouble() / ok.size,
            optLevels = if (win.size >= 10) optimalLevels7961(ok.map { it[0] }) else emptyList(),
        )
    }

    /**
     * V5.0.7961 — pure: the take-profit ladder this key's own peaks pay most on. Rung 1 is the
     * level L that maximises L x P(peak >= L) over every sample (losers included: they never
     * reach it); rung 2 maximises the same among samples that reached rung 1 at >= 1.4x rung 1;
     * rung 3 among those that reached rung 2 at >= 1.5x rung 2. Levels are searched on the
     * observed peaks themselves, between +15% and +3000%.
     */
    fun optimalLevels7961(peaks: List<Double>): List<Double> {
        val all = peaks.filter { it.isFinite() }.sorted()
        if (all.isEmpty()) return emptyList()
        fun best(pool: List<Double>, minL: Double): Double? {
            val cands = pool.filter { it >= minL && it <= 3_000.0 }.distinct()
            if (cands.isEmpty()) return null
            val n = pool.size.toDouble()
            return cands.maxByOrNull { l -> l * (pool.count { it >= l } / n) }
        }
        val out = ArrayList<Double>(3)
        val l1 = best(all, 15.0) ?: return emptyList()
        out += l1
        val reached1 = all.filter { it >= l1 }
        val l2 = best(reached1, l1 * 1.4) ?: return out
        out += l2
        val reached2 = reached1.filter { it >= l2 }
        best(reached2, l2 * 1.5)?.let { out += it }
        return out
    }

    /**
     * Pure: the exit plan a profile implies, shrunk toward the SpikeCapture7943.TIERS
     * prior (trail [PRIOR_TRAIL_FRAC_7955], max hold 60 min) by n / [MIN_N_7955].
     * Pop-and-fade keys (give back >= 70% of the peak, few runners) sell early and
     * heavy on a tight trail; runner keys (>= 25% past +100%) sell late and light on
     * a wide trail. The flags only switch on at full weight.
     */
    fun planFrom7955(p: Profile7955?, key: String = GLOBAL_7955, source: String = "PRIOR"): Plan7955 {
        val prior = SpikeCapture7943.TIERS
        if (p == null || p.n <= 0) return Plan7955(key, "PRIOR", 0, prior, PRIOR_TRAIL_FRAC_7955, PRIOR_MAX_HOLD_MS_7955, runner = false, popFade = false)
        val w = (p.n / MIN_N_7955).coerceIn(0.0, 1.0)
        val wL = if (p.medPeak.isFinite()) minOf(w, p.nWin / 10.0).coerceIn(0.0, 1.0) else 0.0
        val gb = if (p.medGiveback.isFinite()) p.medGiveback else 0.6
        val rr = if (p.runnerRate.isFinite()) p.runnerRate else 0.15
        // V5.0.7961 — the capture-maximising ladder when the key has 10+ winners; quantiles otherwise.
        val o = p.optLevels
        val l1 = (o.getOrNull(0) ?: (0.9 * p.medPeak)).coerceIn(15.0, 300.0)
        val l2 = maxOf(o.getOrNull(1) ?: (if (p.p75Peak.isFinite()) p.p75Peak else 0.0), l1 * 1.4).coerceAtMost(1_000.0)
        val l3 = maxOf(o.getOrNull(2) ?: (if (p.p90Peak.isFinite()) p.p90Peak else 0.0), l2 * 1.5).coerceAtMost(3_000.0)
        val learnedLevels = listOf(l1, l2, l3)
        val adj = 0.5 * (gb - 0.6) - (rr - 0.15)
        val levels = DoubleArray(prior.size) { i ->
            val learned = learnedLevels.getOrElse(i) { learnedLevels.last() * 1.5 }
            if (wL > 0.0) prior[i].first * (1.0 - wL) + learned * wL else prior[i].first
        }
        for (i in 1 until levels.size) levels[i] = maxOf(levels[i], levels[i - 1] * 1.2)
        val tiers = prior.indices.map { i ->
            val f = prior[i].second * (1.0 - w) + (prior[i].second + adj).coerceIn(0.2, 0.95) * w
            levels[i] to f
        }
        val trail = PRIOR_TRAIL_FRAC_7955 * (1.0 - w) + (PRIOR_TRAIL_FRAC_7955 + 0.6 * (rr - 0.15) - 0.4 * (gb - 0.6)).coerceIn(0.12, 0.7) * w
        val holdLearned = if (p.medTtpMin.isFinite()) (p.medTtpMin * 3.0).coerceIn(10.0, 240.0) * 60_000.0 else PRIOR_MAX_HOLD_MS_7955.toDouble()
        val hold = (PRIOR_MAX_HOLD_MS_7955 * (1.0 - w) + holdLearned * w).toLong()
        val full = w >= 1.0
        val runner = full && rr >= 0.25 && gb < 0.7
        val popFade = full && !runner && gb >= 0.7 && rr < 0.15
        return Plan7955(key, source, p.n, tiers, trail, hold, runner, popFade)
    }

    /**
     * Pure: the fluid stop with the plan's learned trail. Armed once the peak passed half
     * the first tier (and net break-even). A runner plan widens a profit lock down to
     * peak x (1 - trail), never under net break-even; any other plan whose trail is
     * tighter than the prior raises the lock to it. A prior plan changes nothing.
     */
    fun applyTrail7955(plan: Plan7955?, stopPct: Double, peakPct: Double, costPct: Double): Double {
        if (plan == null || plan.source == "PRIOR" || !stopPct.isFinite() || !peakPct.isFinite()) return stopPct
        val be = (if (costPct.isFinite() && costPct > 0.0) costPct else 0.0) + 0.5
        val arm = maxOf(plan.tiers.firstOrNull()?.first?.times(0.5) ?: 20.0, be + 1.5)
        if (peakPct < arm) return stopPct
        // V5.0.7955 review — the learned trail always leaves at least 8 points of room under the peak.
        val lock = minOf(peakPct * (1.0 - plan.trailFrac), peakPct - MIN_TRAIL_ROOM_PCT_7955)
        return when {
            plan.runner && stopPct > 0.0 && stopPct > lock -> maxOf(lock, be)
            !plan.runner && plan.trailFrac < PRIOR_TRAIL_FRAC_7955 && lock > stopPct && lock >= be -> lock
            else -> stopPct
        }
    }

    /**
     * Pure: does the give-back lock wait? A runner plan holds like MOONSHOT's
     * DIAMOND_HANDS profile (no lock under +50% peak) in any lane; a pop-and-fade plan
     * never waits, even in a runner lane; otherwise the lane rule ([laneDeferred]) stands.
     */
    fun deferGiveBack7955(plan: Plan7955?, laneDeferred: Boolean, peakPct: Double): Boolean = when {
        plan == null -> laneDeferred
        plan.popFade -> laneDeferred
        plan.runner -> laneDeferred || !peakPct.isFinite() || peakPct < RunnerExitProfile7277.MIN_PEAK_FOR_GIVEBACK_LOCK_PCT
        else -> laneDeferred
    }

    /**
     * Pure: does the trade plan treat the position as a runner (no fixed full target,
     * no three-bar trail; TradePlan7739.exitFor)? A runner plan yes in any lane, a
     * pop-and-fade plan no even in a runner lane, otherwise the lane decides.
     */
    fun runnerExits7955(plan: Plan7955?, laneRunner: Boolean): Boolean = when {
        plan == null -> laneRunner
        plan.popFade -> laneRunner
        plan.runner -> true
        else -> laneRunner
    }

    /** Pure: a pop-and-fade position past its learned max hold and net green takes its profit (reason), else null. */
    fun maxHoldExit7955(plan: Plan7955?, holdMs: Long, pnlPct: Double, costPct: Double): String? {
        if (plan == null || !plan.popFade || holdMs < plan.maxHoldMs || !pnlPct.isFinite()) return null
        val be = (if (costPct.isFinite() && costPct > 0.0) costPct else 0.0) + 0.5
        return if (pnlPct >= be) "PROFILE_MAX_HOLD_7955_${pnlPct.toInt()}PCT" else null
    }

    private const val MIN_TRAIL_ROOM_PCT_7955 = 8.0

    // ── learner ──

    private val rings = HashMap<String, ArrayDeque<DoubleArray>>()
    private val planCache = ConcurrentHashMap<String, Pair<Long, Plan7955>>()
    private val positionPlans = ConcurrentHashMap<String, Plan7955>()
    private val peaks = ConcurrentHashMap<String, Pair<Double, Long>>()     // mint|entryTime -> (peak gross %, at)
    private val tierFires = ConcurrentHashMap<String, AtomicLong>()
    private val fromLabels = AtomicLong(0)
    private val fromCloses = AtomicLong(0)
    private val sincePersist = AtomicLong(0)
    @Volatile private var loaded = false

    private fun canon(lane: String): String {
        val c = try { com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.canonical(lane).uppercase() } catch (_: Throwable) { "" }
        return if (c.isBlank()) lane.trim().uppercase().ifBlank { "UNKNOWN" } else c
    }

    private fun keysFor(lane: String, setup: String): List<String> {
        val l = canon(lane)
        val s = setup.trim().ifBlank { "NONE" }
        return listOf("$l|$s", "$l|*", GLOBAL_7955)
    }

    /**
     * The setup a position or decision is traded as: CHART_UP_* when the chart reader
     * says BUY (the band of its neighbours' mean run-up), else the LanePlaybook7907
     * setup, else NONE.
     */
    fun entrySetup7955(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()): String {
        try {
            // V5.0.7955 review — cached read only: no library search per observed decision.
            val r = com.lifecyclebot.engine.chart.ChartReader7950.cachedRead7955(ts.mint)
            if (com.lifecyclebot.engine.chart.ChartReader7950.buySignal(r)) {
                val up = r?.motif?.meanUpPct ?: Double.NaN
                return when {
                    !up.isFinite() || up < 25.0 -> "CHART_UP_LO"
                    up < 75.0 -> "CHART_UP_MID"
                    else -> "CHART_UP_HI"
                }
            }
        } catch (_: Throwable) {}
        return try { com.lifecyclebot.engine.cortex.LanePlaybook7907.classify(ts, canon(lane), nowMs) } catch (_: Throwable) { null } ?: "NONE"
    }

    private fun add(lane: String, setup: String, sample: DoubleArray, keyOnly: Boolean = false) {
        ensureLoaded()
        synchronized(this) {
            for (k in keysFor(lane, setup).let { if (keyOnly) it.take(1) else it }) {
                if (!rings.containsKey(k) && rings.size >= MAX_KEYS_7955) {
                    val victim = rings.entries.filter { it.key != GLOBAL_7955 }.minByOrNull { it.value.size }?.key
                    if (victim != null) rings.remove(victim)
                }
                val ring = rings.getOrPut(k) { ArrayDeque() }
                ring.addLast(sample)
                while (ring.size > RING_7955) ring.removeFirst()
            }
        }
        if (sincePersist.incrementAndGet() >= PERSIST_EVERY_7955) { sincePersist.set(0); persist() }
    }

    /** ForwardReturnLabeler7731 at the 60-minute read: one sample per observation. */
    fun onLabel7955(lane: String, setup: String, peakPct: Double, timeToPeakMs: Long, giveback60: Double, giveback5: Double, keyOnly: Boolean = false) {
        if (!peakPct.isFinite()) return
        add(lane, setup, doubleArrayOf(peakPct, timeToPeakMs.coerceAtLeast(0L) / 60_000.0, giveback60, giveback5), keyOnly)
        fromLabels.incrementAndGet()
    }

    /** CanonicalFinalizedTradeBus6464: a realised close (peak, exit, hold) of a position this book planned or a lane fallback. */
    fun onClose7955(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope) {
        if (!env.terminal || env.mint.isBlank() || !env.realizedReturnPct.isFinite()) return
        val entryMs = env.atMs - env.holdingTimeMs.coerceAtLeast(0L)
        val planKey = positionPlans.keys.firstOrNull { it.startsWith(env.mint + "|") && kotlin.math.abs((it.substringAfter('|').toLongOrNull() ?: 0L) - entryMs) < 120_000L }
        val plan = planKey?.let { positionPlans.remove(it) }
        val tracked = planKey?.let { peaks.remove(it) }
        val peak = maxOf(if (env.mfePct.isFinite()) env.mfePct else 0.0, tracked?.first ?: 0.0, env.realizedReturnPct)
        val ttp = tracked?.second?.let { (it - entryMs).coerceAtLeast(0L) } ?: (env.holdingTimeMs.coerceAtLeast(0L) / 2)
        // Our own capture exits cut the give-back short; they teach peak and timing only.
        val captured = env.exitReason.contains("SPIKE_CAPTURE") || env.exitReason.contains("CHART_CAPTURE")
        val gb = if (captured) Double.NaN else giveback7955(peak, env.realizedReturnPct)
        val keyParts = plan?.key?.split('|')
        val lane = keyParts?.getOrNull(0) ?: env.lane
        val setup = keyParts?.getOrNull(1) ?: "NONE"
        add(lane, setup, doubleArrayOf(peak, ttp / 60_000.0, gb, Double.NaN))
        fromCloses.incrementAndGet()
    }

    /**
     * V5.0.7961 — a setup that fired (not NONE / NO_TRIGGER) describes the price path the bot
     * would trade, admitted or not: its labels teach that setup's own exit key. 5.0.7958 read
     * samples[labels=6 closes=714]: admitted-only labels left the meme setups with no exit
     * record, so every meme position ran the fixed prior ladder.
     */
    fun setupFired7961(setup: String): Boolean {
        val s = setup.trim().uppercase()
        return s.isNotEmpty() && s != "NONE" && s != "NO_TRIGGER"
    }

    private fun bestProfile(lane: String, setup: String): Pair<String, Profile7955?> {
        ensureLoaded()
        val keys = keysFor(lane, setup)
        val profiles = synchronized(this) { keys.map { k -> k to rings[k]?.let { profileOf7955(it.toList()) } } }
        profiles.firstOrNull { (it.second?.n ?: 0) >= MIN_N_7955 }?.let { return it }
        return profiles.firstOrNull { it.second != null } ?: (keys.first() to null)
    }

    /** The plan for a (lane, setup) key, recomputed at most once a minute. */
    fun planForKey7955(lane: String, setup: String, nowMs: Long = System.currentTimeMillis()): Plan7955 {
        val ck = keysFor(lane, setup).first()
        planCache[ck]?.let { (at, p) -> if (nowMs - at in 0L..PLAN_CACHE_MS_7955) return p }
        val (src, prof) = bestProfile(lane, setup)
        val source = when { prof == null -> "PRIOR"; src == ck -> "KEY"; src == GLOBAL_7955 -> "GLOBAL"; else -> "LANE" }
        // V5.0.7955 review — only the key's OWN record shapes its exits; a lane-wide or global
        // pool (mostly refused candidates that fade) must not turn every position pop-and-fade.
        val plan = if (source == "KEY") planFrom7955(prof, ck, source) else planFrom7955(null, ck, "PRIOR")
        if (planCache.size > 2_000) planCache.clear()
        planCache[ck] = nowMs to plan
        return plan
    }

    /** The open position's plan, fixed at its first exit read (seconds after entry). Null when not open. */
    fun planFor7955(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Plan7955? {
        val pos = ts.position
        if (!pos.isOpen || pos.entryTime <= 0L) return null
        val k = "${ts.mint}|${pos.entryTime}"
        positionPlans[k]?.let { return it }
        val lane = canon(pos.tradingMode)
        val plan = planForKey7955(lane, entrySetup7955(ts, lane, nowMs), nowMs)
        if (positionPlans.size > 2_000) positionPlans.clear()
        positionPlans[k] = plan
        try { PipelineHealthCollector.labelInc("EXIT_PROFILE_7955_PLAN_${plan.source}") } catch (_: Throwable) {}
        return plan
    }

    /** SpikeCapture7943.onMark: the position's running peak and when it was set (time to peak at close). */
    fun notePeak7955(ts: TokenState, grossPct: Double, nowMs: Long) {
        if (!grossPct.isFinite()) return
        val k = "${ts.mint}|${ts.position.entryTime}"
        val cur = peaks[k]
        if (cur == null || grossPct > cur.first) {
            if (peaks.size > 2_000) peaks.clear()
            peaks[k] = grossPct to nowMs
        }
    }

    /** SpikeCapture7943.onMark: a tier fired for this plan's key. */
    fun onTierFired7955(plan: Plan7955?, tier: Int) {
        val k = "${plan?.key ?: "PRIOR"}#T$tier"
        tierFires.computeIfAbsent(k) { AtomicLong(0) }.incrementAndGet()
    }

    // ── persistence ──

    private fun ensureLoaded() {
        if (loaded) return
        if (!LearningPersistence.ready()) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(PERSIST_KEY_7955) ?: return)
                for (k in o.keys()) {
                    val ring = ArrayDeque<DoubleArray>()
                    o.optString(k).split(';').forEach { row ->
                        val f = row.split(',').map { it.toDoubleOrNull() ?: Double.NaN }
                        if (f.size == 4 && f[0].isFinite()) ring.addLast(f.toDoubleArray())
                    }
                    if (ring.isNotEmpty()) rings[k] = ring
                }
            } catch (_: Throwable) {}
        }
    }

    private fun persist() {
        if (!loaded) return
        try {
            // V5.0.7955 review — copy under the lock, format and save off the caller's thread.
            val snap = synchronized(this) { rings.mapValues { (_, ring) -> ring.map { it.copyOf() } } }
            Thread({
                try {
                    val json = org.json.JSONObject().also { j ->
                        snap.forEach { (k, ring) -> j.put(k, ring.joinToString(";") { s -> s.joinToString(",") { v -> if (v.isFinite()) (kotlin.math.round(v * 100.0) / 100.0).toString() else "NaN" } }) }
                    }.toString()
                    LearningPersistence.save(PERSIST_KEY_7955, json)
                } catch (_: Throwable) {}
            }, "exit-profile-save-7955").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) {}
    }

    /** Cortex7885 shutdown save. */
    fun persistNow7955() = persist()

    private fun fmtPlan(p: Plan7955): String =
        "${p.tiers.joinToString("/") { "+${it.first.toInt()}:${(it.second * 100).toInt()}%" }} trail=${(p.trailFrac * 100).toInt()}% maxHold=${p.maxHoldMs / 60_000L}m" +
            (if (p.runner) " RUNNER" else "") + (if (p.popFade) " POPFADE" else "")

    fun statusLine7955(): String {
        ensureLoaded()
        val sizes = synchronized(this) { rings.mapValues { it.value.size } }
        val top = sizes.entries.filter { it.key != GLOBAL_7955 && !it.key.endsWith("|*") }.sortedByDescending { it.value }.take(6)
        val topLine = top.joinToString(" · ") { (k, n) ->
            val lane = k.substringBefore('|'); val setup = k.substringAfter('|')
            "$k n$n ${fmtPlan(planForKey7955(lane, setup))}"
        }.ifBlank { "none yet" }
        val lanes = sizes.entries.filter { it.key.endsWith("|*") }.sortedByDescending { it.value }.take(8).joinToString(",") { "${it.key.removeSuffix("|*")}=${it.value}" }
        val glob = planFrom7955(synchronized(this) { rings[GLOBAL_7955]?.let { profileOf7955(it.toList()) } }, GLOBAL_7955, "GLOBAL")
        return "keys=${sizes.size} samples[labels=${fromLabels.get()} closes=${fromCloses.get()}] global n${glob.n} ${fmtPlan(glob)} lanes[${lanes.ifBlank { "-" }}] openPlans=${positionPlans.size}\n" +
            "      top keys: $topLine\n" +
            "      tier fires: ${tierFires.entries.sortedByDescending { it.value.get() }.take(10).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none yet" }}"
    }
}
