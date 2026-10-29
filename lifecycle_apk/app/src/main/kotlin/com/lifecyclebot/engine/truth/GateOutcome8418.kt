package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8418 §EVERY_GATE_HAS_A_FORWARD_RECORD — owner: "the full buy path logic needs investigation".
 *
 * 5.0.8416 (3.7 h LIVE): FDG allow 0 / block 9,286, and no gate's refusals could be graded. ForwardReturnLabeler7731 keeps
 * ONE pending observation per (mint, lane); the lane's native verdict (DecisionReasons8031, NATIVE_READY / NATIVE_<rule>)
 * arrives first every cycle, so the FDG refusal of the same coin a moment later is skipped as a re-observation and its reason
 * is lost. The Cortex veto book (Cortex7885.vetoBook, the record FdgGate8365's n>=100 audit reads) therefore only ever held
 * NATIVE_* reasons (8416: NATIVE_TREASURY_ROLE_FLOOR n176, NATIVE_CORE_YIELD_CLEAR n161, ...): no FDG door could be judged
 * on what it refused.
 *
 * This book files the FIRST buy-path gate that refused a meme-lane candidate (or FDG_ADMIT when it was admitted) on the
 * pending observation of that candidate — only while that observation is at most [ATTACH_MAX_AGE_MS_8418] old, so the label
 * still measures the moment the gate ruled — and grades it with the same 60-minute executable net label the lane books
 * get (first grade only; later revisions of the label do not move this book). Nothing is estimated: a gate with no graded
 * refusals has no record. Read by:
 *   - the report (statusLine8418): per gate n / net / win rate / runner rate, the FDG_ADMIT cohort beside them;
 *   - FdgGate8365.demoteNow8365: an EVIDENCE door (Rehearsal8339.evidenceDoor8342 — never safety, economics, data or chase)
 *     whose own lane record holds 100+ graded refusals that proved winners on the robust read becomes advisory, the rule
 *     8365 already applied to the Cortex veto book ([retiresAt100_8418]).
 */
object GateOutcome8418 {
    const val ATTACH_MAX_AGE_MS_8418 = 120_000L
    const val ADMIT_GATE_8418 = "FDG_ADMIT"
    private const val PERSIST_KEY_8418 = "gate_outcome_book_8418"
    private const val PERSIST_EVERY_8418 = 25L
    private const val MAX_KEYS_8418 = 600

    class Stat8418 {
        var n = 0.0; var sum = 0.0; var sumSq = 0.0; var wins = 0.0; var runners = 0.0
        fun add(net: Double, runner: Boolean) { n += 1.0; sum += net; sumSq += net * net; if (net > 0.0) wins += 1.0; if (runner) runners += 1.0 }
        fun mean(): Double = if (n > 0.0) sum / n else Double.NaN
        fun variance(): Double = if (n > 1.0) ((sumSq / n) - mean() * mean()).coerceAtLeast(0.0) * n / (n - 1.0) else Double.NaN
        fun se(): Double = if (n > 1.0) kotlin.math.sqrt(variance() / n) else Double.POSITIVE_INFINITY
    }

    private val book = ConcurrentHashMap<String, Stat8418>()
    private val booked = AtomicLong(0)
    private val attached = AtomicLong(0)
    @Volatile private var loaded = false

    // ── pure ──

    /** Pure: the gate a decision is filed under — [ADMIT_GATE_8418] for an execution admit, the refusal's gate key, else null. */
    fun gateOf8418(admitted: Boolean, reason: String?): String? {
        val prediction = com.lifecyclebot.engine.NativeDecisionEnrollment8372.predictionObservation8404(reason)
        if (admitted) return if (prediction) null else ADMIT_GATE_8418
        if (prediction) return null
        val r = reason?.trim().orEmpty()
        if (r.isEmpty()) return null
        return FdgGate8365.gateKey8392(r)
    }

    /**
     * Pure: the gate to file on an observation of [lane] opened at [obsAtMs] and currently filed under [current] ("" = none),
     * when the decision ([admitted], [reason]) arrives at [nowMs]; null = leave it. The first refusal wins; an admit replaces
     * a refusal (the coin was bought). Meme lanes only; an observation older than [ATTACH_MAX_AGE_MS_8418] is left alone.
     */
    fun attachGate8418(current: String, obsAtMs: Long, admitted: Boolean, reason: String?, lane: String, nowMs: Long): String? {
        if (!EvidenceHold8336.heldLane8342(lane)) return null
        if (nowMs - obsAtMs !in 0L..ATTACH_MAX_AGE_MS_8418) return null
        val g = gateOf8418(admitted, reason) ?: return null
        if (g == ADMIT_GATE_8418) return if (current == ADMIT_GATE_8418) null else g
        return if (current.isBlank()) g else null
    }

    /** Pure: refused candidates proven to win — 100+ graded, mean - SE above +2 % net and the robust (winsorised) proof. */
    fun refusesWinners8418(st: Stat8418?): Boolean {
        if (st == null) return false
        val proven = st.n >= 40.0 && st.mean() - st.se() > 2.0 &&
            RobustLabelRead8185.robustWinnersProven8185(st.n, st.sum, st.sumSq, st.wins, st.variance())
        return FdgGate8365.vetoDemotes8365(st.n, proven)
    }

    private fun key(gate: String, lane: String) = "$gate@${CanonicalLaneIdentity6506.desk8392(lane)}"

    // ── book ──

    /** ForwardReturnLabeler7731.book (60-minute grade): file [net] (% executable) under [gate] for [lane] and all lanes. */
    fun book8418(gate: String, lane: String, net: Double, runner: Boolean) {
        if (gate.isBlank() || !net.isFinite()) return
        load()
        for (k in listOf(gate, key(gate, lane))) {
            if (!book.containsKey(k) && book.size >= MAX_KEYS_8418) continue
            val st = book.getOrPut(k) { Stat8418() }
            synchronized(st) { st.add(net, runner) }
        }
        try { PipelineHealthCollector.labelInc("GATE_OUTCOME_BOOKED_8418") } catch (_: Throwable) {}
        if (booked.incrementAndGet() % PERSIST_EVERY_8418 == 0L) save()
    }

    fun noteAttached8418() { attached.incrementAndGet() }

    /** A copy of the record of [gate] (in [lane] when given), or null. */
    fun stat8418(gate: String, lane: String? = null): Stat8418? {
        load()
        val st = book[if (lane == null) gate else key(gate, lane)] ?: return null
        return synchronized(st) { Stat8418().also { c -> c.n = st.n; c.sum = st.sum; c.sumSq = st.sumSq; c.wins = st.wins; c.runners = st.runners } }
    }

    /** FdgGate8365: does [reason]'s own lane record of graded refusals prove it refuses winners (100+, robust)? */
    fun retiresAt100_8418(reason: String, lane: String): Boolean {
        val retired = refusesWinners8418(stat8418(FdgGate8365.gateKey8392(reason), lane))
        if (retired) try { PipelineHealthCollector.labelInc("GATE_OUTCOME_RETIRES_8418_${FdgGate8365.gateKey8392(reason)}") } catch (_: Throwable) {}
        return retired
    }

    /** V5.0.8438 — DiagnosisBrief8438: every gate's all-lane record (copies), largest first. */
    fun ledger8438(): List<Pair<String, Stat8418>> {
        load()
        return book.entries.filter { !it.key.contains('@') }.map { (k, st) ->
            k to synchronized(st) { Stat8418().also { c -> c.n = st.n; c.sum = st.sum; c.sumSq = st.sumSq; c.wins = st.wins; c.runners = st.runners } }
        }.sortedByDescending { it.second.n }
    }

    fun statusLine8418(): String {
        load()
        fun fmt(k: String, st: Stat8418): String = synchronized(st) {
            val flag = when {
                refusesWinners8418(st) -> " REFUSES_WINNERS"
                st.n >= 40.0 && st.mean() + st.se() < -2.0 -> " refuses_losers"
                else -> ""
            }
            "$k n${st.n.toInt()}/${"%+.1f".format(st.mean())}%/wr${(st.wins * 100.0 / st.n.coerceAtLeast(1.0)).toInt()}%/run${(st.runners * 100.0 / st.n.coerceAtLeast(1.0)).toInt()}%$flag"
        }
        val admit = book[ADMIT_GATE_8418]?.let { fmt(ADMIT_GATE_8418, it) } ?: "$ADMIT_GATE_8418 n0"
        val gates = book.entries.filter { !it.key.contains('@') && it.key != ADMIT_GATE_8418 }
            .sortedByDescending { it.value.n }.take(14).joinToString(" · ") { fmt(it.key, it.value) }.ifBlank { "none graded yet" }
        return "gateOutcome8418[attached=${attached.get()} booked=${booked.get()} $admit | $gates]"
    }

    // ── persistence (LearningPersistence; never reset) ──

    private fun load() {
        if (loaded) return
        if (!(try { com.lifecyclebot.engine.LearningPersistence.ready() } catch (_: Throwable) { false })) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            try {
                com.lifecyclebot.engine.LearningPersistence.load(PERSIST_KEY_8418)?.lineSequence()?.forEach { line ->
                    val f = line.split('\t')
                    if (f.size != 6) return@forEach
                    val st = book.getOrPut(f[0]) { Stat8418() }
                    synchronized(st) {
                        st.n += f[1].toDoubleOrNull() ?: 0.0; st.sum += f[2].toDoubleOrNull() ?: 0.0
                        st.sumSq += f[3].toDoubleOrNull() ?: 0.0; st.wins += f[4].toDoubleOrNull() ?: 0.0
                        st.runners += f[5].toDoubleOrNull() ?: 0.0
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun save() {
        if (!loaded) return
        try {
            val body = book.entries.joinToString("\n") { (k, st) ->
                synchronized(st) { "$k\t${st.n}\t${st.sum}\t${st.sumSq}\t${st.wins}\t${st.runners}" }
            }
            com.lifecyclebot.engine.LearningPersistence.save(PERSIST_KEY_8418, body)
        } catch (_: Throwable) {}
    }

    internal fun resetForTests8418() { book.clear(); booked.set(0); attached.set(0); loaded = true }
}
