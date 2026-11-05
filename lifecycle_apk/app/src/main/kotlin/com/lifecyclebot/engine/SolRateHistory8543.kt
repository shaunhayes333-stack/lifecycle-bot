package com.lifecyclebot.engine

/**
 * V5.0.8543 — the SOL/USD rates this device actually observed, one every [SAMPLE_MS_8543], kept for a day and persisted, so a
 * past instant (a forward label's horizon priced from the pool's swap history) is converted at the rate observed then, not
 * the rate now. 8542: chainHistory8541 NO_SOL_RATE=186 — the only past rate it had was the single pre-stop observation.
 * Nothing is interpolated or invented: a target inside a gap is answered only when the observations on both sides agree
 * within [MAX_GAP_DRIFT_8543]; otherwise there is no rate.
 */
object SolRateHistory8543 {
    private const val SAMPLE_MS_8543 = 5L * 60_000L
    private const val KEEP_MS_8543 = 26L * 3_600_000L
    private const val NEAR_MS_8543 = 10L * 60_000L
    const val MAX_GAP_DRIFT_8543 = 0.02
    private const val KEY_8543 = "SOL_RATE_HISTORY_8543"

    private val rows = ArrayList<Pair<Long, Double>>()   // (observedAtMs, usd), time order; guarded by this
    @Volatile private var restored = false

    /** WalletManager.noteObservedSolPrice7042: a rate this device saw. Sampled, bounded, persisted. */
    fun note8543(usd: Double, atMs: Long) {
        if (!usd.isFinite() || usd !in 50.0..1000.0 || atMs <= 0L) return
        restore8543()
        val save = synchronized(this) {
            if (rows.isNotEmpty() && atMs - rows.last().first < SAMPLE_MS_8543) return
            rows += atMs to usd
            rows.removeAll { atMs - it.first > KEEP_MS_8543 }
            rows.joinToString(";") { "${it.first}:${it.second}" }
        }
        try { if (LearningPersistence.ready()) LearningPersistence.save(KEY_8543, save) } catch (_: Throwable) {}
    }

    private fun restore8543() {
        if (restored || !LearningPersistence.ready()) return
        val raw = try { LearningPersistence.load(KEY_8543) } catch (_: Throwable) { null }
        synchronized(this) {
            if (restored) return
            restored = true
            raw?.split(';')?.mapNotNull { r -> r.split(':').takeIf { it.size == 2 }?.let { (t, p) ->
                val tt = t.toLongOrNull(); val pp = p.toDoubleOrNull()
                if (tt != null && pp != null && pp in 50.0..1000.0) tt to pp else null } }
                ?.let { old -> rows.addAll(0, old); rows.sortBy { it.first } }
        }
    }

    /** The observed rate at [targetMs] (or NaN), with [nowRate] at [nowMs] as the newest observation. */
    fun rateAt8543(targetMs: Long, nowRate: Double, nowMs: Long): Double {
        restore8543()
        val snap = synchronized(this) { rows.toList() }
        return rateAt8543(snap, targetMs, nowRate, nowMs)
    }

    /**
     * Pure: an observation within [NEAR_MS_8543] of [targetMs] answers it; otherwise the nearest observations before and after
     * it (the fresh [nowRate] counts as one) must agree within [MAX_GAP_DRIFT_8543], and the nearer one answers. Else NaN.
     */
    fun rateAt8543(obs: List<Pair<Long, Double>>, targetMs: Long, nowRate: Double, nowMs: Long): Double {
        val all = obs.filter { it.second.isFinite() && it.second in 50.0..1000.0 && it.first > 0L }.toMutableList()
        if (nowRate.isFinite() && nowRate in 50.0..1000.0) all += nowMs to nowRate
        if (all.isEmpty()) return Double.NaN
        val near = all.minByOrNull { kotlin.math.abs(it.first - targetMs) }!!
        if (kotlin.math.abs(near.first - targetMs) <= NEAR_MS_8543) return near.second
        val before = all.filter { it.first <= targetMs }.maxByOrNull { it.first } ?: return Double.NaN
        val after = all.filter { it.first >= targetMs }.minByOrNull { it.first } ?: return Double.NaN
        if (kotlin.math.abs(after.second / before.second - 1.0) > MAX_GAP_DRIFT_8543) return Double.NaN
        return if (targetMs - before.first <= after.first - targetMs) before.second else after.second
    }
}
