package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §A_FALLING_HOUR_IS_REFUSED_ONLY_WHEN_IT_IS_PROVEN_TO_LOSE.
 *
 * Phone run 5.0.8418: FDG_CANDIDATE_ADMISSION_8136_FALLING_HOUR_UNPROVEN_8418 refused 403 MOONSHOT candidates (MOONSHOT
 * discovered 412, qualified 401, FDG 0/164). The rule (CandidateAdmission8136 / StrategyMove8153: a momentum lane with no
 * supported strategy and a negative hour) was a REFUSAL resting on no evidence: nothing in the tree measured whether such
 * candidates lose. TradeShapeLearner7883 has no hour feature, and the setup / cell books are keyed on other things.
 *
 * This is that measurement: the forward-labelled outcome (ForwardReturnLabeler7731's 60-minute label, net of the
 * round-trip cost the labeler charges) of every decision on a momentum lane taken while the coin's hour was falling,
 * outside a fresh launch's window, with no supported strategy of its own — exactly the population the rule judges. Per
 * lane: n, mean, variance, runner share. CandidateAdmission8136 refuses on it only when it is PROVEN losing
 * ([provenLosing8418]); until then the candidate DEFERS and is re-judged when the hour turns (its candidate identity
 * changes with the hour). Labels of paper and live decisions alike: this is admission evidence, never size.
 */
object FallingHourCohort8418 {
    const val KIND_8418 = "FALLING_HOUR"
    internal const val MIN_N_8418 = 30
    internal const val TAIL_RUNNER_RATE_8418 = 0.10
    private const val RUNNER_GROSS_PCT_8418 = 50.0
    private const val PENDING_TTL_MS_8418 = 90L * 60_000L
    private const val MAX_PENDING_8418 = 6_000
    private const val PERSIST_KEY_8418 = "FALLING_HOUR_COHORT_8418"

    class Stat8418 {
        var n = 0; var sum = 0.0; var sumSq = 0.0; var runners = 0
        fun mean(): Double = if (n > 0) sum / n else Double.NaN
        fun se(): Double = if (n > 1) kotlin.math.sqrt(((sumSq / n) - mean() * mean()).coerceAtLeast(0.0) / n) else Double.POSITIVE_INFINITY
        fun runnerRate(): Double = if (n > 0) runners.toDouble() / n else Double.NaN
        fun add(net: Double, gross: Double) { n++; sum += net; sumSq += net * net; if (gross >= RUNNER_GROSS_PCT_8418) runners++ }
        fun encode(): String = "$n,$sum,$sumSq,$runners"
        fun decode(s: String) {
            val f = s.split(','); if (f.size != 4) return
            n = f[0].toIntOrNull() ?: 0; sum = f[1].toDoubleOrNull() ?: 0.0
            sumSq = f[2].toDoubleOrNull() ?: 0.0; runners = f[3].toIntOrNull() ?: 0
        }
    }

    private val pending = ConcurrentHashMap<String, Long>()          // mint|lane -> captured at
    private val stats = ConcurrentHashMap<String, Stat8418>()        // lane
    private val captured = AtomicLong(0)
    private val graded = AtomicLong(0)
    @Volatile private var loaded = false
    // V5.0.8435 — persistence that cannot lose the record (see [persist]).
    private const val PENDING_JSON_KEY_8435 = "__pending_8435"
    private const val CAPTURE_SAVE_GAP_MS_8435 = 30_000L
    @Volatile private var dirty8435 = false
    @Volatile private var lastSaveMs8435 = 0L
    private val saved8435 = AtomicLong(0)
    private val saveFailed8435 = AtomicLong(0)
    private val restoredPending8435 = AtomicLong(0)
    @Volatile private var savedN8435 = -1

    private fun lane8418(lane: String): String = try {
        CanonicalLaneIdentity6506.canonical(lane).uppercase().ifBlank { lane.trim().uppercase() }
    } catch (_: Throwable) { lane.trim().uppercase() }

    // ── pure ──

    /** Pure: a known age inside a fresh launch's window (StrategyMove8153.FRESH_AGE_MIN_8153). Unknown age is not proof. */
    fun inLaunchWindow8418(ageMin: Double?): Boolean =
        ageMin != null && ageMin.isFinite() && ageMin >= 0.0 && ageMin <= StrategyMove8153.FRESH_AGE_MIN_8153

    /**
     * Pure: is this decision in the population the falling-hour rule judges? A momentum lane, an observed negative hour,
     * not inside its launch window, and no supported strategy of its own (StrategyMove8153.strategySupported8153).
     */
    fun qualifies8418(lane: String, hourPct: Double, ageMin: Double?, stratN: Int, stratMean: Double, stratSe: Double): Boolean =
        StrategyMove8153.momentumLane8153(lane) && hourPct.isFinite() && hourPct < 0.0 && !inLaunchWindow8418(ageMin) &&
            !StrategyMove8153.strategySupported8153(stratN, stratMean, stratSe)

    /**
     * Pure: the record PROVES such candidates lose after cost — n >= [MIN_N_8418], mean + 1 SE below zero, and (the lane
     * exists to catch runners) a runner share under [TAIL_RUNNER_RATE_8418]. Null / thin / suspect = not proven.
     */
    fun provenLosing8418(e: CandidateAdmission8136.Evidence8136?): Boolean {
        if (e == null || e.suspect8290 || !e.netOfCost) return false
        if (e.n < MIN_N_8418 || !e.meanPct.isFinite() || !e.sePct.isFinite()) return false
        if (e.meanPct + e.sePct >= 0.0) return false
        return !(e.runnerRate.isFinite() && e.runnerRate >= TAIL_RUNNER_RATE_8418)
    }

    // ── runtime ──

    /** ForwardReturnLabeler7731.observe: the decision opened an observation; capture it when it is in the population. */
    fun capture8418(ts: TokenState, lane: String, nowMs: Long = System.currentTimeMillis()) {
        val l = lane8418(lane)
        if (!StrategyMove8153.momentumLane8153(l) || ts.mint.isBlank()) return
        val hour = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.resolvedChg1h8399(ts, nowMs) } catch (_: Throwable) { Double.NaN }
        if (!(hour.isFinite() && hour < 0.0)) return
        val age = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) } catch (_: Throwable) { null }
        val rec = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedRecord8153(ts, l, null, nowMs) } catch (_: Throwable) { null }
        if (!qualifies8418(l, hour, age, rec?.get(0)?.toInt() ?: 0, rec?.get(1) ?: Double.NaN, rec?.get(2) ?: Double.NaN)) return
        if (pending.size >= MAX_PENDING_8418) pending.entries.removeIf { nowMs - it.value > PENDING_TTL_MS_8418 }
        if (pending.size >= MAX_PENDING_8418) return
        pending["${ts.mint}|${lane.trim().uppercase()}"] = nowMs
        captured.incrementAndGet()
        // V5.0.8435 — the capture survives a restart (its label lands up to an hour later, usually in another process).
        dirty8435 = true
        if (nowMs - lastSaveMs8435 >= CAPTURE_SAVE_GAP_MS_8435) persist(nowMs)
    }

    /** ForwardReturnLabeler7731: the 60-minute label of an observation booked ([netPct] after cost, [grossPct] raw). */
    fun onLabel60(mint: String, labelLane: String, netPct: Double, grossPct: Double) {
        if (!netPct.isFinite()) return
        ensureLoaded()   // V5.0.8435 — before the pending read: a restored capture must be found
        if (pending.remove("$mint|${labelLane.trim().uppercase()}") == null) return
        synchronized(this) { stats.getOrPut(lane8418(labelLane)) { Stat8418() }.add(netPct, if (grossPct.isFinite()) grossPct else 0.0) }
        graded.incrementAndGet()
        // V5.0.8435 — every graded outcome is written and acknowledged (was: every tenth, unacknowledged).
        dirty8435 = true
        persist(System.currentTimeMillis())
    }

    /** The lane's record as admission evidence (null = nothing graded yet). */
    fun evidence8418(lane: String): CandidateAdmission8136.Evidence8136? {
        ensureLoaded()
        val l = lane8418(lane)
        val st = synchronized(this) { stats[l]?.let { s -> Stat8418().also { it.decode(s.encode()) } } } ?: return null
        if (st.n <= 0) return null
        return CandidateAdmission8136.Evidence8136(KIND_8418, l, st.n, st.mean(), st.se(), runnerRate = st.runnerRate())
    }

    fun noteDecision8418(lane: String, refused: Boolean) {
        try { PipelineHealthCollector.labelInc(if (refused) "FALLING_HOUR_PROVEN_LOSING_REFUSED_8418_${lane8418(lane)}" else "FALLING_HOUR_DEFERRED_UNPROVEN_8418_${lane8418(lane)}") } catch (_: Throwable) {}
    }

    fun statusLine8418(): String {
        ensureLoaded()
        val lanes = synchronized(this) {
            stats.entries.sortedByDescending { it.value.n }.joinToString(",") { (k, s) ->
                "$k:n${s.n}/${if (s.mean().isFinite()) "%+.1f".format(s.mean()) else "-"}%" +
                    "±${if (s.se().isFinite()) "%.1f".format(s.se()) else "-"} run=${if (s.runnerRate().isFinite()) (s.runnerRate() * 100).toInt() else 0}%"
            }
        }
        return "fallingHour8418[captured=${captured.get()} graded=${graded.get()} pending=${pending.size} lanes={${lanes.ifBlank { "-" }}} rule=refuse only n>=$MIN_N_8418,mean+se<0,run<${(TAIL_RUNNER_RATE_8418 * 100).toInt()}%; else defer] " +
            "persist8435[loaded=$loaded saved=${saved8435.get()} failed=${saveFailed8435.get()} savedN=${if (savedN8435 >= 0) savedN8435 else "-"} restoredPending=${restoredPending8435.get()} dirty=$dirty8435]"
    }

    /** Test seam: one lane's record (never called by production code). */
    internal fun seedForTest8418(lane: String, stat: Stat8418) {
        loaded = true
        synchronized(this) { stats[lane8418(lane)] = stat }
    }

    internal fun resetForTests8418() {
        loaded = true
        synchronized(this) { stats.clear() }
        pending.clear(); captured.set(0); graded.set(0)
        dirty8435 = false; lastSaveMs8435 = 0L; saved8435.set(0); saveFailed8435.set(0); restoredPending8435.set(0); savedN8435 = -1
    }

    /** Test seam: the saved form restored into this process (merged, captures inside their window only). */
    internal fun restoreForTest8435(json: String, nowMs: Long) = synchronized(this) { restore8435(org.json.JSONObject(json), nowMs) }

    internal fun pendingCountForTest8435(): Int = pending.size

    // ── persistence ──

    /**
     * V5.0.8435 §A_RECORD_THAT_ONLY_GROWS. 8421 -> 8434: the retained record went BACKWARDS (SHITCOIN 45 -> 33, MOONSHOT
     * 42 -> 28, EXPRESS 36 -> 29) and the 33 outcomes graded in 8421 were absent at the next boot. Three ways to lose it:
     *  - a load before the store was open (LearningPersistence.ready() false) or whose read failed set loaded=true with an
     *    EMPTY record, and the next save replaced the real one with it; now the load is retried until it really read;
     *  - the save every tenth outcome went through an unacknowledged put (dropped on the main thread / a held key); now each
     *    graded outcome is written with LearningPersistence.saveTogetherVerified8386 and retried while it is not acknowledged;
     *  - the captures waiting for their 60-minute label lived only in memory, so a restart inside the hour (most builds)
     *    dropped every one of them and their labels were never graded; now they are saved with the record and restored.
     */
    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            if (!(try { LearningPersistence.ready() } catch (_: Throwable) { false })) return
            val raw = try { LearningPersistence.load(PERSIST_KEY_8418) } catch (_: Throwable) { return }
            if (raw == null && (try { LearningPersistence.readFailed8086(PERSIST_KEY_8418) } catch (_: Throwable) { true })) return
            try {
                if (raw != null) {
                    val o = org.json.JSONObject(raw)
                    restore8435(o, System.currentTimeMillis())
                }
            } catch (_: Throwable) {
                return   // an undecodable record is not an empty one: never let a save replace it
            }
            loaded = true
        }
    }

    /** The saved record (lanes + waiting captures) merged under what this process already holds. */
    private fun restore8435(o: org.json.JSONObject, nowMs: Long) {
        for (k in o.keys()) {
            if (k == PENDING_JSON_KEY_8435) continue
            val disk = Stat8418().also { it.decode(o.optString(k)) }
            val mem = stats[k]
            if (mem == null) stats[k] = disk else { mem.n += disk.n; mem.sum += disk.sum; mem.sumSq += disk.sumSq; mem.runners += disk.runners }
        }
        val p = o.optJSONObject(PENDING_JSON_KEY_8435) ?: return
        for (k in p.keys()) {
            val at = p.optLong(k, 0L)
            if (at <= 0L || nowMs - at > PENDING_TTL_MS_8418 || pending.size >= MAX_PENDING_8418) continue
            if (pending.putIfAbsent(k, at) == null) restoredPending8435.incrementAndGet()
        }
    }

    /** Pure: the persisted form — every lane's record plus the captures still inside their label window. */
    internal fun encode8435(st: Map<String, Stat8418>, waiting: Map<String, Long>, nowMs: Long): String {
        val o = org.json.JSONObject()
        st.forEach { (k, v) -> o.put(k, v.encode()) }
        val p = org.json.JSONObject()
        waiting.forEach { (k, at) -> if (nowMs - at in 0L..PENDING_TTL_MS_8418) p.put(k, at) }
        o.put(PENDING_JSON_KEY_8435, p)
        return o.toString()
    }

    private fun persist(nowMs: Long) {
        ensureLoaded()
        if (!loaded) { saveFailed8435.incrementAndGet(); return }   // never write before the real record was read
        lastSaveMs8435 = nowMs
        val (json, n) = try {
            synchronized(this) { encode8435(stats, pending, nowMs) to stats.values.sumOf { it.n } }
        } catch (_: Throwable) { saveFailed8435.incrementAndGet(); return }
        val ok = try { LearningPersistence.saveTogetherVerified8386(mapOf(PERSIST_KEY_8418 to json)) } catch (_: Throwable) { false }
        if (ok) { saved8435.incrementAndGet(); savedN8435 = n; dirty8435 = false }
        else {
            saveFailed8435.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FALLING_HOUR_SAVE_NOT_ACKED_8435") } catch (_: Throwable) {}
        }
    }
}
