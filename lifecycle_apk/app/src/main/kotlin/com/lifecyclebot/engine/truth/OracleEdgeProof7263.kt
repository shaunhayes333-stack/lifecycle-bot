package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7263 §THE_ORACLE_EARNS_ITS_AUTHORITY.
 *
 * The categorical verdict of PredictiveEntryOracle6915 is ADVISORY until
 * this object can show, on real closes, that the oracle's ADMITs settle
 * better than its REFUSEs. A forecaster whose yes-pile and no-pile settle
 * the same has no edge, however confident the numbers look. The tier is
 * granted by measurement and taken back the moment the measurement stops
 * holding.
 *
 * MECHANISM. Every forecast is stamped per mint (verdict, pWin, time). When
 * CanonicalTradeFinalizedBus6450 publishes a close for that mint within the
 * stamp TTL, the forecast is scored into its pile. While ADVISORY the
 * oracle's REFUSEs still trade, which is what fills the REFUSE pile with
 * real outcomes to compare against. PROVEN requires, all at once:
 *
 *   ADMIT closes      >= 20
 *   REFUSE closes     >= 10
 *   ADMIT mean return >  0          (net of cost)
 *   ADMIT mean return >= REFUSE mean return + 2 percentage points
 *   ADMIT win rate    >= REFUSE win rate
 *   ADMIT Brier       <= 0.25
 *
 * V5.0.7287 §THE PROOF REMEMBERS. Operator: "the oracle is meant to effect
 * trading and ingest all trade history... once it proves itself absolutely
 * should be guiding the trading not just advising."
 *
 *   1. The tallies and live stamps lived in memory and every restart set the
 *      proof back to zero; on a phone that restarts the service several
 *      times a day the bar of 20 + 10 graded closes could not be reached in
 *      one session. Both now persist (attach7287, called at service start),
 *      so the proof accumulates across every session the oracle has run.
 *   2. PROBE is gone (see PredictiveEntryOracle6915.Verdict): the proof
 *      compares ADMIT against REFUSE directly. The old PROBE pile is folded
 *      into REFUSE on load, because a probe was a non-admit.
 *   3. PROVEN now means the oracle GUIDES: an ADMIT trades past the cruder
 *      cohort rules and a REFUSE does not trade, in paper and live
 *      (LearnedAdmissionAuthority6846). It demotes itself the moment its
 *      ADMITs stop settling better.
 */
object OracleEdgeProof7263 {
    enum class Tier { ADVISORY, PROVEN }

    const val MIN_ADMIT_CLOSES_7263: Int = 20
    const val MIN_NON_ADMIT_CLOSES_7263: Int = 10
    /** ADMIT mean return must beat REFUSE mean return by this fraction (0.02 = 2pp). */
    private const val MIN_EDGE_MARGIN_RETURN_7263: Double = 0.02
    private const val MAX_ADMIT_BRIER_7263: Double = 0.25
    private const val STAMP_TTL_MS_7263 = 6L * 60L * 60L * 1000L
    private const val MAX_STAMPS_7263 = 4_000

    private const val PREFS_7287 = "aate_oracle_edge_proof_7287"
    private const val KEY_ADMIT_7287 = "tally_admit"
    private const val KEY_REFUSE_7287 = "tally_refuse"
    private const val KEY_STAMPS_7287 = "stamps"
    /** Stamps are written out at most this often; tallies on every scored close. */
    private const val STAMP_FLUSH_MS_7287 = 30_000L

    private data class Stamp(
        val verdict: PredictiveEntryOracle6915.Verdict,
        val pWin: Double,
        val atMs: Long,
    )

    private class Tally {
        private val lock = Any()
        var n: Long = 0L; private set
        var wins: Long = 0L; private set
        var sumReturn: Double = 0.0; private set
        var brierSum: Double = 0.0; private set

        fun add(win: Boolean, returnFraction: Double, pWin: Double) {
            synchronized(lock) {
                n += 1
                if (win) wins += 1
                if (returnFraction.isFinite()) sumReturn += returnFraction
                val err = pWin - (if (win) 1.0 else 0.0)
                brierSum += err * err
            }
        }
        fun brier(): Double = synchronized(lock) { if (n == 0L) 1.0 else brierSum / n }
        fun snapshot(): Triple<Long, Double, Double> = synchronized(lock) {
            Triple(n, if (n == 0L) 0.0 else sumReturn / n, if (n == 0L) 0.0 else wins.toDouble() / n)
        }
        fun encode(): String = synchronized(lock) { "$n|$wins|$sumReturn|$brierSum" }
        fun decodeInto(raw: String?) {
            if (raw.isNullOrBlank()) return
            val p = raw.split('|')
            if (p.size != 4) return
            synchronized(lock) {
                n = p[0].toLongOrNull() ?: return
                wins = p[1].toLongOrNull() ?: 0L
                sumReturn = p[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
                brierSum = p[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            }
        }
    }

    private val stamps = ConcurrentHashMap<String, Stamp>()
    private val admit = Tally()
    private val refuse = Tally()
    private val scored = AtomicLong(0L)
    private val unmatched = AtomicLong(0L)
    private val staleStamps = AtomicLong(0L)
    private val promotions = AtomicLong(0L)
    private val demotions = AtomicLong(0L)
    private val subscribed = AtomicBoolean(false)
    @Volatile private var tier: Tier = Tier.ADVISORY
    @Volatile private var tierReason: String = "no scored closes yet"
    @Volatile private var prefs7287: SharedPreferences? = null
    private val lastStampFlushMs7287 = AtomicLong(0L)
    private val restored7287 = AtomicLong(0L)

    /**
     * V5.0.7287 — restore the proof from disk and subscribe to the finalized
     * bus before CanonicalFinalityPersistence6486 replays, so a close that
     * settled while the app was down still grades its stamp.
     */
    @Synchronized
    fun attach7287(context: Context) {
        if (prefs7287 != null) return
        val p = try {
            context.applicationContext.getSharedPreferences(PREFS_7287, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }
        prefs7287 = p
        try {
            admit.decodeInto(p.getString(KEY_ADMIT_7287, null))
            refuse.decodeInto(p.getString(KEY_REFUSE_7287, null))
            val now = System.currentTimeMillis()
            p.getString(KEY_STAMPS_7287, null)?.split(';')?.forEach { row ->
                val f = row.split(',')
                if (f.size != 4) return@forEach
                // A persisted PROBE (pre-7287) was a non-admit.
                val v = if (f[1] == "ADMIT") PredictiveEntryOracle6915.Verdict.ADMIT
                    else PredictiveEntryOracle6915.Verdict.REFUSE
                val at = f[3].toLongOrNull() ?: return@forEach
                if (now - at > STAMP_TTL_MS_7263) return@forEach
                stamps[f[0]] = Stamp(v, f[2].toDoubleOrNull() ?: 0.5, at)
                restored7287.incrementAndGet()
            }
        } catch (_: Throwable) {}
        ensureSubscribed()
        recompute()
        try { PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_RESTORED_7287") } catch (_: Throwable) {}
    }

    private fun persistTallies7287() {
        val p = prefs7287 ?: return
        try {
            p.edit().putString(KEY_ADMIT_7287, admit.encode()).putString(KEY_REFUSE_7287, refuse.encode()).apply()
        } catch (_: Throwable) {}
    }

    private fun persistStampsIfDue7287(now: Long) {
        val p = prefs7287 ?: return
        val last = lastStampFlushMs7287.get()
        if (now - last < STAMP_FLUSH_MS_7287 || !lastStampFlushMs7287.compareAndSet(last, now)) return
        try {
            val body = stamps.entries
                .filter { now - it.value.atMs <= STAMP_TTL_MS_7263 }
                .joinToString(";") { "${it.key},${it.value.verdict.name},${it.value.pWin},${it.value.atMs}" }
            p.edit().putString(KEY_STAMPS_7287, body).apply()
        } catch (_: Throwable) {}
    }

    /** Called by PredictiveEntryOracle6915 on every forecast it returns. */
    fun stamp(mint: String, forecast: PredictiveEntryOracle6915.Forecast) {
        if (mint.isBlank()) return
        ensureSubscribed()
        val now = System.currentTimeMillis()
        if (stamps.size > MAX_STAMPS_7263) {
            try {
                val it = stamps.entries.iterator()
                while (it.hasNext()) if (now - it.next().value.atMs > STAMP_TTL_MS_7263) it.remove()
            } catch (_: Throwable) {}
        }
        stamps[mint] = Stamp(forecast.verdict, forecast.pWin.let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.5 }, now)
        persistStampsIfDue7287(now)
    }

    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { event -> onEvent(event) }
            try { PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SUBSCRIBED_7263") } catch (_: Throwable) {}
        } catch (_: Throwable) {
            subscribed.set(false)
        }
    }

    /** Score one settled trade against the forecast issued for its mint. */
    fun onEvent(event: CanonicalTradeFinalizedBus6450.Event) {
        try { FinalizedFanoutParity6459.recordConsumer("OracleEdgeProof7263") } catch (_: Throwable) {}
        val s = stamps[event.mint]
        if (s == null) { unmatched.incrementAndGet(); return }
        // V5.0.7287 — a durable replay re-publishes closes from before this
        // forecast existed; a close can only grade a forecast issued before it.
        if (event.settledAtMs < s.atMs) { unmatched.incrementAndGet(); return }
        stamps.remove(event.mint)
        if (event.settledAtMs - s.atMs > STAMP_TTL_MS_7263) { staleStamps.incrementAndGet(); return }
        val win = event.outcome == CanonicalTradeFinalizedBus6450.Outcome.WIN
        val ret = event.returnFraction
        when (s.verdict) {
            PredictiveEntryOracle6915.Verdict.ADMIT -> admit.add(win, ret, s.pWin)
            PredictiveEntryOracle6915.Verdict.REFUSE -> refuse.add(win, ret, s.pWin)
        }
        scored.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SCORED_7263")
            PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SCORED_7263_${s.verdict.name}_${if (win) "WIN" else "LOSS"}")
        } catch (_: Throwable) {}
        persistTallies7287()
        // The graded stamp must leave disk now, or a restart would grade it twice.
        lastStampFlushMs7287.set(0L)
        persistStampsIfDue7287(System.currentTimeMillis())
        recompute()
    }

    private fun recompute() {
        val (aN, aRet, aWr) = admit.snapshot()
        val (rN, rRet, rWr) = refuse.snapshot()
        val aBrier = admit.brier()
        val checks = listOf(
            "admitN>=$MIN_ADMIT_CLOSES_7263" to (aN >= MIN_ADMIT_CLOSES_7263),
            "refuseN>=$MIN_NON_ADMIT_CLOSES_7263" to (rN >= MIN_NON_ADMIT_CLOSES_7263),
            "admitRet>0" to (aRet > 0.0),
            "admitRet>=refuseRet+${MIN_EDGE_MARGIN_RETURN_7263}" to (aRet >= rRet + MIN_EDGE_MARGIN_RETURN_7263),
            "admitWr>=refuseWr" to (aWr >= rWr),
            "admitBrier<=$MAX_ADMIT_BRIER_7263" to (aBrier <= MAX_ADMIT_BRIER_7263),
        )
        val proven = checks.all { it.second }
        val failing = checks.filter { !it.second }.joinToString(",") { it.first }
        val next = if (proven) Tier.PROVEN else Tier.ADVISORY
        tierReason = if (proven) "all_checks_pass" else failing.ifBlank { "unknown" }
        val prev = tier
        if (next != prev) {
            tier = next
            if (next == Tier.PROVEN) promotions.incrementAndGet() else demotions.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(if (next == Tier.PROVEN) "ORACLE_EDGE_PROVEN_7263" else "ORACLE_EDGE_DEMOTED_7263")
                ForensicLogger.lifecycle(
                    if (next == Tier.PROVEN) "ORACLE_EDGE_PROVEN_7263" else "ORACLE_EDGE_DEMOTED_7263",
                    "from=${prev.name} to=${next.name} ${statusLine()}",
                )
            } catch (_: Throwable) {}
        }
    }

    fun tier(): Tier = tier

    /**
     * V5.0.7304 §AN_ORACLE_THAT_SCORES_BACKWARDS.
     *
     * 5.0.7302, first live session: admit[n=266 ret=+5.4%] against
     * refuse[n=102 ret=+42.5%] — the candidates the oracle refused settled
     * eight times better than the ones it admitted. ADVISORY only stops the
     * verdict from gating; the oracle's expectancy still denied entries through
     * the evidence branch (ORACLE_NEGATIVE_EXPECTANCY_6915, 275 live denies).
     * Inverted = at the proof's own sample bars (>=20 admit, >=10 refuse) the
     * refused pile beats the admitted pile by at least the proof's margin.
     * While inverted its expectancy is not evidence; it clears itself when the
     * graded closes stop saying so.
     */
    fun isInverted7304(): Boolean {
        val (aN, aRet, _) = admit.snapshot()
        val (rN, rRet, _) = refuse.snapshot()
        return aN >= MIN_ADMIT_CLOSES_7263 && rN >= MIN_NON_ADMIT_CLOSES_7263 &&
            rRet >= aRet + MIN_EDGE_MARGIN_RETURN_7263
    }

    fun statusLine(): String {
        val (aN, aRet, aWr) = admit.snapshot()
        val (rN, rRet, rWr) = refuse.snapshot()
        return "tier=${tier.name} inverted7304=${isInverted7304()} reason=$tierReason scored=${scored.get()} unmatched=${unmatched.get()} stale=${staleStamps.get()} " +
            "admit[n=$aN ret=${"%+.1f".format(aRet * 100.0)}% wr=${"%.0f".format(aWr * 100.0)}% brier=${"%.3f".format(admit.brier())}] " +
            "refuse[n=$rN ret=${"%+.1f".format(rRet * 100.0)}% wr=${"%.0f".format(rWr * 100.0)}%] " +
            "promotions=${promotions.get()} demotions=${demotions.get()} stamps=${stamps.size} " +
            "persisted7287=${prefs7287 != null} restoredStamps7287=${restored7287.get()} " +
            "bar=admit>=$MIN_ADMIT_CLOSES_7263/refuse>=$MIN_NON_ADMIT_CLOSES_7263/margin=${MIN_EDGE_MARGIN_RETURN_7263}/brier<=$MAX_ADMIT_BRIER_7263"
    }

    internal fun resetForTest() {
        stamps.clear(); scored.set(0L); unmatched.set(0L); staleStamps.set(0L)
        promotions.set(0L); demotions.set(0L); tier = Tier.ADVISORY; tierReason = "reset"
    }
}
