package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LearningPersistence
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6415 §A — MOONSHOT SIGNAL LEARNER. Rebuilt V5.0.8083 §MOONSHOT_CLOSED_LOOP (LIVE ONLY).
 *
 * 8082 forensics: MOONSHOT 0W/8L live, REFUSE cohort -18%, no STRONG cohort, and this learner had taught nothing:
 *   1. only pnl >= +50% / <= -20% were learned — a -15% or +30% close taught nothing;
 *   2. with zero qualifying wins signalWeight() returned 1.0, so a signal with eight losses stayed neutral;
 *   3. state was in-memory only and reset on every restart;
 *   4. its only feed was a gross-pnl call in one Executor live-sell branch, keyed by mint, not the canonical close.
 *
 * Now: ONE feed — CanonicalTradeFinalizedBus6450, LIVE mode, verified economic finality (LIVE_FINALIZED /
 * LIVE_RECONCILED), entry owner MOONSHOT, deduplicated by canonical positionId. Every such close is learned on
 * its NET return. The signals are the ones bound at the live buy (EarlyMoonshotHunter6415.registerHoldProfile).
 * Weight = shrunk blend of (signal mean - lane mean) and the signal's own absolute net mean, so a signal that
 * consistently loses is down-weighted even when nothing has won yet. State persists via LearningPersistence.
 * Paper and forward labels never train these weights (operator: live trading only; forward grading stays in Cortex).
 */
object MoonshotSignalLearner6415 {

    private const val MIN_SAMPLE = 4
    private const val SHRINK_K = 8.0
    private const val EDGE_SCALE = 0.20          // 20% net mean edge = full swing
    private const val KEY = "moonshot_signal_learner_8083"
    private const val MAX_PROCESSED = 3_000

    private class SignalStat(var n: Long = 0, var sumRet: Double = 0.0, var wins: Long = 0)

    private val stats = ConcurrentHashMap<String, SignalStat>()
    private val global = SignalStat()
    private val bindings = ConcurrentHashMap<String, Set<String>>()      // mint -> signals bound at the live buy
    private val processed = LinkedHashMap<String, Long>()                // positionId -> settledAt (dedupe)
    private val subscribed = AtomicBoolean(false)
    @Volatile private var loaded = false

    /** Live buy: freeze the signals that fired, so the canonical close trains exactly them. */
    fun bindEntry8083(mint: String, signals: Set<String>) {
        if (mint.isBlank() || signals.isEmpty()) return
        ensure()
        bindings[mint] = signals.toSet()
        if (bindings.size > 2_000) bindings.keys.take(500).forEach { bindings.remove(it) }
        try { PipelineHealthCollector.labelInc("MOONSHOT_ENTRY_SIGNALS_BOUND") } catch (_: Throwable) {}
        save()
    }

    private fun exclude(reason: String) {
        try { PipelineHealthCollector.labelInc("MOONSHOT_LEARNING_EXCLUDED_REASON_$reason") } catch (_: Throwable) {}
    }

    /** True when [e] is a verified LIVE MOONSHOT terminal this learner may train on. Pure; test seam. */
    internal fun eligible8083(e: CanonicalTradeFinalizedBus6450.Event): String? {
        if (!e.mode.equals("live", ignoreCase = true)) return "NOT_LIVE"
        val lane = try { CanonicalLaneIdentity6506.canonical(e.entryLane) } catch (_: Throwable) { e.entryLane.uppercase() }
        if (lane != "MOONSHOT") return "NOT_MOONSHOT"
        val fin = CanonicalTradeFinalizedBus6450.economicFinality8082(e.mode, e.dataQuality, e.priceIntegrity)
        if (fin != "LIVE_FINALIZED" && fin != "LIVE_RECONCILED") return "UNVERIFIED_FINALITY"
        if (!e.returnFraction.isFinite()) return "NON_FINITE_RETURN"
        return null
    }

    private fun onFinalized(e: CanonicalTradeFinalizedBus6450.Event) {
        if (e.mode.equals("live", ignoreCase = true).not()) return
        val why = eligible8083(e)
        if (why != null) { if (why != "NOT_MOONSHOT") exclude(why); return }
        synchronized(processed) {
            if (processed.containsKey(e.positionId)) { exclude("DUPLICATE_FINALITY"); return }
            processed[e.positionId] = e.settledAtMs
            while (processed.size > MAX_PROCESSED) processed.remove(processed.keys.first())
        }
        try { PipelineHealthCollector.labelInc("MOONSHOT_FINALIZED_OUTCOME") } catch (_: Throwable) {}
        // Net, clamped so one runner or one feed glitch cannot own every weight.
        val ret = e.returnFraction.coerceIn(-1.0, 5.0)
        // Lane adaptive gate learns from the same verified live close, once (legacy local close feed retired).
        try { com.lifecyclebot.engine.MoonshotAdaptiveGate.recordOutcome(e.netReturnPct) } catch (_: Throwable) {}
        val signals = bindings.remove(e.mint)
        if (signals.isNullOrEmpty()) { exclude("NO_ENTRY_SIGNALS_BOUND"); synchronized(global) { add(global, ret) }; save(); return }
        recordOutcome(e.mint, "", "", signals, ret * 100.0)
    }

    private fun add(s: SignalStat, ret: Double) { s.n++; s.sumRet += ret; if (ret > 0.0) s.wins++ }

    /** Learn one verified net outcome (percent) for the signals bound at entry. Every outcome counts. */
    fun recordOutcome(mint: String, symbol: String, tier: String, signalsFired: Set<String>, pnlPct: Double) {
        if (!pnlPct.isFinite()) return
        ensure()
        val ret = (pnlPct / 100.0).coerceIn(-1.0, 5.0)
        synchronized(global) { add(global, ret) }
        for (sig in signalsFired) synchronized(stats) { add(stats.getOrPut(sig) { SignalStat() }, ret) }
        save()
        try {
            PipelineHealthCollector.labelInc("MOONSHOT_SIGNAL_LEARNED")
            PipelineHealthCollector.labelInc("MOONSHOT_STRATEGY_UPDATED")
            ForensicLogger.lifecycle(
                "MOONSHOT_SIGNAL_LEARNED",
                "mint=${mint.take(10)} netPct=${"%.1f".format(pnlPct)} signals=[${signalsFired.joinToString(",")}] " +
                    "global n=${global.n} wins=${global.wins} mean=${"%.3f".format(mean(global))}",
            )
        } catch (_: Throwable) {}
    }

    private fun mean(s: SignalStat) = if (s.n > 0) s.sumRet / s.n else 0.0

    /** Pure weight from a signal's (n, mean) against the lane mean. [0.25, 2.0]; 1.0 below MIN_SAMPLE. */
    internal fun weightOf8083(n: Long, signalMean: Double, laneMean: Double): Double {
        if (n < MIN_SAMPLE) return 1.0
        val edge = 0.5 * (signalMean - laneMean) + 0.5 * signalMean
        val shrink = n / (n + SHRINK_K)
        return (1.0 + (edge / EDGE_SCALE).coerceIn(-0.75, 1.0) * shrink).coerceIn(0.25, 2.0)
    }

    /** Multiplier for a POSITIVE signal's contribution; negative signals use the reciprocal (see hunter). */
    fun signalWeight(signal: String): Double {
        ensure()
        val s = stats[signal] ?: return 1.0
        val (n, m) = synchronized(stats) { s.n to mean(s) }
        return weightOf8083(n, m, synchronized(global) { mean(global) })
    }

    fun statusLine(): String {
        val top = stats.entries.filter { it.value.n >= MIN_SAMPLE }.sortedByDescending { signalWeight(it.key) }.take(3)
            .joinToString(",") { "${it.key.take(20)}(w=${"%.2f".format(signalWeight(it.key))} n=${it.value.n})" }
        return "LIVE signals=${stats.size} n=${global.n} wins=${global.wins} mean=${"%.3f".format(mean(global))} bound=${bindings.size} top=[$top]"
    }

    private fun ensure() {
        if (subscribed.compareAndSet(false, true)) try {
            CanonicalTradeFinalizedBus6450.subscribe { e -> try { onFinalized(e) } catch (_: Throwable) {} }
        } catch (_: Throwable) { subscribed.set(false) }
        if (!loaded && (try { LearningPersistence.ready() } catch (_: Throwable) { false })) synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                val o = org.json.JSONObject(LearningPersistence.load(KEY) ?: return)
                o.optJSONObject("global")?.let { g -> global.n = g.optLong("n"); global.sumRet = g.optDouble("s", 0.0); global.wins = g.optLong("w") }
                o.optJSONObject("stats")?.let { st -> st.keys().forEach { k -> val v = st.getJSONObject(k)
                    stats[k] = SignalStat(v.optLong("n"), v.optDouble("s", 0.0), v.optLong("w")) } }
                o.optJSONObject("bind")?.let { b -> b.keys().forEach { k -> val a = b.getJSONArray(k)
                    bindings[k] = (0 until a.length()).map { a.getString(it) }.toSet() } }
                o.optJSONObject("done")?.let { d -> synchronized(processed) { d.keys().forEach { processed[it] = d.optLong(it) } } }
                PipelineHealthCollector.labelInc("MOONSHOT_SIGNAL_LEARNER_RESTORED_8083")
            } catch (_: Throwable) {}
        }
    }

    private fun save() {
        if (!loaded) return
        try {
            val st = org.json.JSONObject()
            synchronized(stats) { stats.forEach { (k, v) -> st.put(k, org.json.JSONObject().put("n", v.n).put("s", v.sumRet).put("w", v.wins)) } }
            val b = org.json.JSONObject(); bindings.forEach { (k, v) -> b.put(k, org.json.JSONArray(v.toList())) }
            val d = org.json.JSONObject(); synchronized(processed) { processed.forEach { (k, v) -> d.put(k, v) } }
            val g = synchronized(global) { org.json.JSONObject().put("n", global.n).put("s", global.sumRet).put("w", global.wins) }
            LearningPersistence.save(KEY, org.json.JSONObject().put("global", g).put("stats", st).put("bind", b).put("done", d).toString())
        } catch (_: Throwable) {}
    }

    internal fun resetForTest() {
        stats.clear(); bindings.clear(); synchronized(processed) { processed.clear() }
        global.n = 0; global.sumRet = 0.0; global.wins = 0
    }
    internal fun onFinalizedForTest(e: CanonicalTradeFinalizedBus6450.Event) = onFinalized(e)
}
