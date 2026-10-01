package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RuntimeModeAuthority
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The oracle's authority is earned independently in PAPER and LIVE.
 *
 * A PAPER close can only grade a PAPER forecast and can never promote the LIVE
 * oracle. Pre-7693 aggregate tallies/stamps have no trustworthy mode identity,
 * so they are intentionally ignored during restore.
 */
object OracleEdgeProof7263 {
    enum class Tier { ADVISORY, PROVEN }

    const val MIN_ADMIT_CLOSES_7263: Int = 20
    const val MIN_NON_ADMIT_CLOSES_7263: Int = 10
    private const val MIN_EDGE_MARGIN_RETURN_7263 = 0.02
    private const val MAX_ADMIT_BRIER_7263 = 0.25
    private const val STAMP_TTL_MS_7263 = 6L * 60L * 60L * 1000L
    private const val MAX_STAMPS_7263 = 4_000
    private const val PREFS_7693 = "aate_oracle_edge_proof_7693"
    private const val STAMP_FLUSH_MS_7693 = 30_000L

    private data class Stamp(
        val mode: String,
        val verdict: PredictiveEntryOracle6915.Verdict,
        val pWin: Double,
        val atMs: Long,
    )

    private class Tally {
        private val lock = Any()
        var n: Long = 0L; private set
        var wins: Long = 0L; private set
        private var sumReturn: Double = 0.0
        private var brierSum: Double = 0.0

        fun add(win: Boolean, returnFraction: Double, pWin: Double) = synchronized(lock) {
            n += 1
            if (win) wins += 1
            if (returnFraction.isFinite()) sumReturn += returnFraction
            val err = pWin - if (win) 1.0 else 0.0
            brierSum += err * err
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
                n = p[0].toLongOrNull()?.coerceAtLeast(0L) ?: return
                wins = (p[1].toLongOrNull() ?: 0L).coerceIn(0L, n)
                sumReturn = p[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
                brierSum = p[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
            }
        }
        fun clear() = synchronized(lock) { n = 0L; wins = 0L; sumReturn = 0.0; brierSum = 0.0 }
    }

    private class ModeBook {
        val admit = Tally()
        val refuse = Tally()
        @Volatile var tier: Tier = Tier.ADVISORY
        @Volatile var reason: String = "no scored closes yet"
    }

    private val books = mapOf("LIVE" to ModeBook(), "PAPER" to ModeBook())
    private val stamps = ConcurrentHashMap<String, Stamp>()
    private val scored = AtomicLong(0L)
    private val unmatched = AtomicLong(0L)
    private val staleStamps = AtomicLong(0L)
    private val promotions = AtomicLong(0L)
    private val demotions = AtomicLong(0L)
    private val subscribed = AtomicBoolean(false)
    @Volatile private var prefs7693: SharedPreferences? = null
    private val lastStampFlushMs7693 = AtomicLong(0L)
    private val restored7693 = AtomicLong(0L)

    private fun normalizeMode(mode: String?): String? = when (mode?.trim()?.uppercase()) {
        "PAPER" -> "PAPER"
        "LIVE" -> "LIVE"
        else -> null
    }

    private fun activeMode(): String =
        try { if (RuntimeModeAuthority.isPaper()) "PAPER" else "LIVE" } catch (_: Throwable) { "LIVE" }

    private fun book(mode: String?): ModeBook? = normalizeMode(mode)?.let { books[it] }
    private fun stampKey(mode: String, mint: String) = "$mode|$mint"

    /** Restore only mode-labelled proof. Legacy aggregate proof is contaminated and discarded. */
    @Synchronized
    fun attach7287(context: Context) {
        if (prefs7693 != null) return
        val p = try {
            context.applicationContext.getSharedPreferences(PREFS_7693, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }
        prefs7693 = p
        try {
            for (mode in listOf("LIVE", "PAPER")) {
                val b = books.getValue(mode)
                b.admit.decodeInto(p.getString("${mode.lowercase()}_admit", null))
                b.refuse.decodeInto(p.getString("${mode.lowercase()}_refuse", null))
            }
            val now = System.currentTimeMillis()
            p.getString("stamps", null)?.split(';')?.forEach { row ->
                val f = row.split('|')
                if (f.size != 5) return@forEach
                val mode = normalizeMode(f[0]) ?: return@forEach
                val mint = f[1].takeIf { it.isNotBlank() } ?: return@forEach
                val verdict = runCatching { PredictiveEntryOracle6915.Verdict.valueOf(f[2]) }.getOrNull()
                    ?: return@forEach
                val at = f[4].toLongOrNull() ?: return@forEach
                if (now - at !in 0..STAMP_TTL_MS_7263) return@forEach
                val pWin = f[3].toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
                    ?: return@forEach
                stamps[stampKey(mode, mint)] = Stamp(mode, verdict, pWin, at)
                restored7693.incrementAndGet()
            }
        } catch (_: Throwable) {}
        ensureSubscribed()
        recompute("LIVE")
        recompute("PAPER")
        try { PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_MODE_SCOPED_RESTORED_7693") } catch (_: Throwable) {}
    }

    private fun persistTallies7693() {
        val p = prefs7693 ?: return
        try {
            val edit = p.edit()
            for (mode in listOf("LIVE", "PAPER")) {
                val b = books.getValue(mode)
                edit.putString("${mode.lowercase()}_admit", b.admit.encode())
                    .putString("${mode.lowercase()}_refuse", b.refuse.encode())
            }
            edit.apply()
        } catch (_: Throwable) {}
    }

    private fun persistStampsIfDue7693(now: Long) {
        val p = prefs7693 ?: return
        val last = lastStampFlushMs7693.get()
        if (now - last < STAMP_FLUSH_MS_7693 || !lastStampFlushMs7693.compareAndSet(last, now)) return
        try {
            val body = stamps.entries
                .filter { now - it.value.atMs in 0..STAMP_TTL_MS_7263 }
                .joinToString(";") { "${it.value.mode}|${it.key.substringAfter('|')}|${it.value.verdict.name}|${it.value.pWin}|${it.value.atMs}" }
            p.edit().putString("stamps", body).apply()
        } catch (_: Throwable) {}
    }

    @Synchronized
    fun resetAllLearning7535() {
        stamps.clear()
        books.values.forEach { it.admit.clear(); it.refuse.clear(); it.tier = Tier.ADVISORY; it.reason = "reset_learning" }
        scored.set(0L); unmatched.set(0L); staleStamps.set(0L)
        promotions.set(0L); demotions.set(0L); restored7693.set(0L)
        lastStampFlushMs7693.set(0L)
        try { prefs7693?.edit()?.clear()?.commit() } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_RESET_7535")
            ForensicLogger.lifecycle("ORACLE_EDGE_PROOF_RESET_7535", "tier=ADVISORY tallies=0 stamps=0")
        } catch (_: Throwable) {}
    }

    /** Stamp a forecast in its execution mode; paper evidence cannot enter the live book. */
    fun stamp(
        mint: String,
        forecast: PredictiveEntryOracle6915.Forecast,
        executionMode: String = activeMode(),
    ) {
        val mode = normalizeMode(executionMode) ?: return
        if (mint.isBlank()) return
        ensureSubscribed()
        val now = System.currentTimeMillis()
        if (stamps.size > MAX_STAMPS_7263) {
            try {
                stamps.entries.removeIf { now - it.value.atMs > STAMP_TTL_MS_7263 }
            } catch (_: Throwable) {}
        }
        val pWin = forecast.pWin.let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.5 }
        stamps[stampKey(mode, mint)] = Stamp(mode, forecast.verdict, pWin, now)
        persistStampsIfDue7693(now)
    }

    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { event -> onEvent(event) }
            try { PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SUBSCRIBED_7263") } catch (_: Throwable) {}
        } catch (_: Throwable) { subscribed.set(false) }
    }

    /** Grade only the forecast stamped for the finalized event's exact mode and mint. */
    fun onEvent(event: CanonicalTradeFinalizedBus6450.Event) {
        try { FinalizedFanoutParity6459.recordConsumer("OracleEdgeProof7263") } catch (_: Throwable) {}
        val mode = normalizeMode(event.mode)
        val b = book(mode)
        if (mode == null || b == null) { unmatched.incrementAndGet(); return }
        val key = stampKey(mode, event.mint)
        val s = stamps[key]
        if (s == null) { unmatched.incrementAndGet(); return }
        if (event.settledAtMs < s.atMs) { unmatched.incrementAndGet(); return }
        stamps.remove(key, s)
        if (event.settledAtMs - s.atMs > STAMP_TTL_MS_7263) { staleStamps.incrementAndGet(); return }
        val win = event.outcome == CanonicalTradeFinalizedBus6450.Outcome.WIN
        when (s.verdict) {
            PredictiveEntryOracle6915.Verdict.ADMIT -> b.admit.add(win, event.returnFraction, s.pWin)
            PredictiveEntryOracle6915.Verdict.REFUSE -> b.refuse.add(win, event.returnFraction, s.pWin)
        }
        scored.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SCORED_7263")
            PipelineHealthCollector.labelInc("ORACLE_EDGE_PROOF_SCORED_7693_${mode}_${s.verdict.name}_${if (win) "WIN" else "LOSS"}")
        } catch (_: Throwable) {}
        persistTallies7693()
        lastStampFlushMs7693.set(0L)
        persistStampsIfDue7693(System.currentTimeMillis())
        recompute(mode)
    }

    fun refuseCohortMeanReturn7384(executionMode: String = activeMode()): Double? {
        val b = book(executionMode) ?: return null
        val (n, r, _) = b.refuse.snapshot()
        return if (n >= MIN_NON_ADMIT_CLOSES_7263 && r.isFinite()) r else null
    }

    private fun recompute(mode: String) {
        val b = books.getValue(mode)
        val (aN, aRet, _) = b.admit.snapshot()
        val (rN, rRet, _) = b.refuse.snapshot()
        val aBrier = b.admit.brier()
        val checks = listOf(
            "admitN>=$MIN_ADMIT_CLOSES_7263" to (aN >= MIN_ADMIT_CLOSES_7263),
            "refuseN>=$MIN_NON_ADMIT_CLOSES_7263" to (rN >= MIN_NON_ADMIT_CLOSES_7263),
            "admitRet>0" to (aRet > 0.0),
            "admitRet>=refuseRet+${MIN_EDGE_MARGIN_RETURN_7263}" to (aRet >= rRet + MIN_EDGE_MARGIN_RETURN_7263),
            "admitBrier<=$MAX_ADMIT_BRIER_7263" to (aBrier <= MAX_ADMIT_BRIER_7263),
        )
        val next = if (checks.all { it.second }) Tier.PROVEN else Tier.ADVISORY
        val failing = checks.filterNot { it.second }.joinToString(",") { it.first }
        b.reason = if (next == Tier.PROVEN) "all_checks_pass" else failing.ifBlank { "unknown" }
        val prev = b.tier
        if (next != prev) {
            b.tier = next
            if (next == Tier.PROVEN) promotions.incrementAndGet() else demotions.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc(if (next == Tier.PROVEN) "ORACLE_EDGE_PROVEN_7693_${mode}" else "ORACLE_EDGE_DEMOTED_7693_${mode}")
                ForensicLogger.lifecycle(
                    if (next == Tier.PROVEN) "ORACLE_EDGE_PROVEN_7693" else "ORACLE_EDGE_DEMOTED_7693",
                    "mode=$mode from=${prev.name} to=${next.name} ${statusLine()}",
                )
            } catch (_: Throwable) {}
        }
    }

    fun tier(executionMode: String = activeMode()): Tier = book(executionMode)?.tier ?: Tier.ADVISORY

    fun isInverted7304(executionMode: String = activeMode()): Boolean {
        val b = book(executionMode) ?: return false
        val (aN, aRet, _) = b.admit.snapshot()
        val (rN, rRet, _) = b.refuse.snapshot()
        return aN >= MIN_ADMIT_CLOSES_7263 && rN >= MIN_NON_ADMIT_CLOSES_7263 &&
            rRet >= aRet + MIN_EDGE_MARGIN_RETURN_7263
    }

    fun statusLine(): String {
        fun modeSummary(mode: String): String {
            val b = books.getValue(mode)
            val (aN, aRet, aWr) = b.admit.snapshot()
            val (rN, rRet, rWr) = b.refuse.snapshot()
            return "$mode[tier=${b.tier.name} reason=${b.reason} inverted=${isInverted7304(mode)} " +
                "admit[n=$aN ret=${"%+.1f".format(aRet * 100.0)}% wr=${"%.0f".format(aWr * 100.0)}% brier=${"%.3f".format(b.admit.brier())}] " +
                "refuse[n=$rN ret=${"%+.1f".format(rRet * 100.0)}% wr=${"%.0f".format(rWr * 100.0)}%]]"
        }
        return "active=${activeMode()} ${modeSummary("LIVE")} ${modeSummary("PAPER")} " +
            "scored=${scored.get()} unmatched=${unmatched.get()} stale=${staleStamps.get()} " +
            "promotions=${promotions.get()} demotions=${demotions.get()} stamps=${stamps.size} " +
            "persisted7693=${prefs7693 != null} restoredStamps7693=${restored7693.get()} " +
            "bar=admit>=$MIN_ADMIT_CLOSES_7263/refuse>=$MIN_NON_ADMIT_CLOSES_7263/margin=${MIN_EDGE_MARGIN_RETURN_7263}/brier<=$MAX_ADMIT_BRIER_7263"
    }

    internal fun resetForTest() {
        stamps.clear(); scored.set(0L); unmatched.set(0L); staleStamps.set(0L)
        promotions.set(0L); demotions.set(0L)
        books.values.forEach { it.admit.clear(); it.refuse.clear(); it.tier = Tier.ADVISORY; it.reason = "reset" }
    }
}
