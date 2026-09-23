package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7263 §THE_ORACLE_EARNS_ITS_AUTHORITY.
 *
 * Operator, on 7262: "the oracle is way way too strict to allow any trading
 * in paper or live. I get its purpose but it also has to allow trading. not
 * probing." Then: "until the Oracle can prove its edge yes."
 *
 * So the categorical verdict of PredictiveEntryOracle6915 is ADVISORY until
 * this object can show, on real closes, that the oracle's ADMITs do better
 * than its non-ADMITs. That is the only honest definition of "edge": a
 * forecaster whose yes-pile and no-pile settle the same has none, however
 * confident the numbers look. 7259 granted the gate by declaration and the
 * bot stopped trading; this grants it by measurement and takes it back the
 * moment the measurement stops holding.
 *
 * MECHANISM. Every forecast the oracle issues is stamped per mint (verdict,
 * pWin, time). When CanonicalTradeFinalizedBus6450 publishes a close for
 * that mint within the stamp TTL, the forecast is scored: which pile it was
 * in, whether the close won, its return fraction, and a Brier term on pWin.
 * PROVEN requires, all at once:
 *
 *   ADMIT closes      >= 20
 *   non-ADMIT closes  >= 10       (PROBE + REFUSE; the oracle must have said
 *                                  no to something that then settled)
 *   ADMIT mean return >  0
 *   ADMIT mean return >= non-ADMIT mean return + 2 percentage points
 *   ADMIT win rate    >= non-ADMIT win rate
 *   ADMIT Brier       <= 0.25     (better than a coin flip's 0.25)
 *
 * The tier is recomputed on every scored close, so a proven oracle that
 * starts settling its ADMITs worse than its PROBEs demotes itself. Consumers
 * read tier() and decide what a verdict is allowed to do:
 *
 *   ADVISORY — telemetry only. The oracle's pWin/EV numbers still feed the
 *              evidence-based branches of LearnedAdmissionAuthority6846;
 *              the verdict word gates nothing.
 *   PROVEN   — LIVE: ADMIT or nothing (7259 semantics). PAPER: PROBE is a
 *              metered probe, REFUSE denies.
 *
 * Read-only for everything except its own tallies. It never sizes, never
 * opens, never closes.
 */
object OracleEdgeProof7263 {
    enum class Tier { ADVISORY, PROVEN }

    const val MIN_ADMIT_CLOSES_7263: Int = 20
    const val MIN_NON_ADMIT_CLOSES_7263: Int = 10
    /** ADMIT mean return must beat non-ADMIT mean return by this fraction (0.02 = 2pp). */
    private const val MIN_EDGE_MARGIN_RETURN_7263: Double = 0.02
    private const val MAX_ADMIT_BRIER_7263: Double = 0.25
    private const val STAMP_TTL_MS_7263 = 6L * 60L * 60L * 1000L
    private const val MAX_STAMPS_7263 = 4_000

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
        fun meanReturn(): Double = synchronized(lock) { if (n == 0L) 0.0 else sumReturn / n }
        fun winRate(): Double = synchronized(lock) { if (n == 0L) 0.0 else wins.toDouble() / n }
        fun brier(): Double = synchronized(lock) { if (n == 0L) 1.0 else brierSum / n }
        fun snapshot(): Triple<Long, Double, Double> = synchronized(lock) {
            Triple(n, if (n == 0L) 0.0 else sumReturn / n, if (n == 0L) 0.0 else wins.toDouble() / n)
        }
    }

    private val stamps = ConcurrentHashMap<String, Stamp>()
    private val admit = Tally()
    private val probe = Tally()
    private val refuse = Tally()
    private val scored = AtomicLong(0L)
    private val unmatched = AtomicLong(0L)
    private val staleStamps = AtomicLong(0L)
    private val promotions = AtomicLong(0L)
    private val demotions = AtomicLong(0L)
    private val subscribed = AtomicBoolean(false)
    @Volatile private var tier: Tier = Tier.ADVISORY
    @Volatile private var tierReason: String = "no scored closes yet"

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
        if (event.settledAtMs - s.atMs > STAMP_TTL_MS_7263) { staleStamps.incrementAndGet(); return }
        val win = event.outcome == CanonicalTradeFinalizedBus6450.Outcome.WIN
        val ret = event.returnFraction
        when (s.verdict) {
            PredictiveEntryOracle6915.Verdict.ADMIT -> admit.add(win, ret, s.pWin)
            PredictiveEntryOracle6915.Verdict.PROBE -> probe.add(win, ret, s.pWin)
            PredictiveEntryOracle6915.Verdict.REFUSE -> refuse.add(win, ret, s.pWin)
        }
        scored.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SCORED_7263")
            PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SCORED_7263_${s.verdict.name}_${if (win) "WIN" else "LOSS"}")
        } catch (_: Throwable) {}
        recompute()
    }

    private fun recompute() {
        val (aN, aRet, aWr) = admit.snapshot()
        val (pN, pRet, pWr) = probe.snapshot()
        val (rN, rRet, rWr) = refuse.snapshot()
        val nonN = pN + rN
        val nonRet = if (nonN == 0L) 0.0 else (pRet * pN + rRet * rN) / nonN
        val nonWr = if (nonN == 0L) 0.0 else (pWr * pN + rWr * rN) / nonN
        val aBrier = admit.brier()
        val checks = listOf(
            "admitN>=$MIN_ADMIT_CLOSES_7263" to (aN >= MIN_ADMIT_CLOSES_7263),
            "nonAdmitN>=$MIN_NON_ADMIT_CLOSES_7263" to (nonN >= MIN_NON_ADMIT_CLOSES_7263),
            "admitRet>0" to (aRet > 0.0),
            "admitRet>=nonAdmitRet+${MIN_EDGE_MARGIN_RETURN_7263}" to (aRet >= nonRet + MIN_EDGE_MARGIN_RETURN_7263),
            "admitWr>=nonAdmitWr" to (aWr >= nonWr),
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

    fun statusLine(): String {
        val (aN, aRet, aWr) = admit.snapshot()
        val (pN, pRet, pWr) = probe.snapshot()
        val (rN, rRet, rWr) = refuse.snapshot()
        return "tier=${tier.name} reason=$tierReason scored=${scored.get()} unmatched=${unmatched.get()} stale=${staleStamps.get()} " +
            "admit[n=$aN ret=${"%+.1f".format(aRet * 100.0)}% wr=${"%.0f".format(aWr * 100.0)}% brier=${"%.3f".format(admit.brier())}] " +
            "probe[n=$pN ret=${"%+.1f".format(pRet * 100.0)}% wr=${"%.0f".format(pWr * 100.0)}%] " +
            "refuse[n=$rN ret=${"%+.1f".format(rRet * 100.0)}% wr=${"%.0f".format(rWr * 100.0)}%] " +
            "promotions=${promotions.get()} demotions=${demotions.get()} stamps=${stamps.size} " +
            "bar=admit>=$MIN_ADMIT_CLOSES_7263/nonAdmit>=$MIN_NON_ADMIT_CLOSES_7263/margin=${MIN_EDGE_MARGIN_RETURN_7263}/brier<=$MAX_ADMIT_BRIER_7263"
    }

    internal fun resetForTest() {
        stamps.clear(); scored.set(0L); unmatched.set(0L); staleStamps.set(0L)
        promotions.set(0L); demotions.set(0L); tier = Tier.ADVISORY; tierReason = "reset"
    }
}
