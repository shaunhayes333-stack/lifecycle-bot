package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.7291 §A SIGNAL SOURCE EARNS LIVE THE WAY THE ORACLE DOES.
 *
 * Operator: "copy trading can just be live in paper and enabled live if its
 * proven. same as the network auto buyer in paper."
 *
 * Smart-money copy signals and hive network signals both sat behind a
 * settings toggle that defaulted off, so paper — whose job is to learn
 * everything — never traded them and never produced the evidence that would
 * justify turning them on live. Both now always run in paper. Every mint a
 * source routes is stamped; when that mint's position settles on the
 * canonical finalized bus, the close is graded into that source's tally.
 * A source is PROVEN once it has at least [MIN_CLOSES] graded closes with a
 * positive mean net return and a profit factor of at least [MIN_PF]; a
 * proven source runs live without the toggle, and demotes itself if the
 * tally stops holding. The toggle remains a manual override for live.
 *
 * Grading reads the bus's net-of-fee return. Nothing here sizes, gates or
 * books a trade; it only answers whether a source has earned live.
 */
object SignalSourceProof7291 {
    // V5.0.7830 — LLM_SCOUT: verified mints from GroqTokenScout7830. Measured
    // here like every other signal source; nothing reads isProven(LLM_SCOUT)
    // for authority (Field Manual L309 — a social thesis earns nothing until proven).
    enum class Source { COPY, NETWORK, LLM_SCOUT }

    private const val MIN_CLOSES = 20
    private const val MIN_PF = 1.2
    private const val STAMP_TTL_MS = 24L * 60 * 60 * 1000
    private const val PREFS = "aate_signal_source_proof_7291"

    private class Tally {
        var n = 0; var wins = 0
        var sumRet = 0.0; var grossWinSol = 0.0; var grossLossSol = 0.0
        fun encode() = "$n,$wins,$sumRet,$grossWinSol,$grossLossSol"
        fun decode(s: String?) {
            val f = s?.split(',') ?: return
            if (f.size != 5) return
            n = f[0].toIntOrNull() ?: 0; wins = f[1].toIntOrNull() ?: 0
            sumRet = f[2].toDoubleOrNull() ?: 0.0
            grossWinSol = f[3].toDoubleOrNull() ?: 0.0
            grossLossSol = f[4].toDoubleOrNull() ?: 0.0
        }
        fun pf() = if (grossLossSol > 0.0) grossWinSol / grossLossSol else if (grossWinSol > 0.0) Double.POSITIVE_INFINITY else 0.0
        fun mean() = if (n > 0) sumRet / n else 0.0
    }

    private val tallies = mapOf(Source.COPY to Tally(), Source.NETWORK to Tally(), Source.LLM_SCOUT to Tally())
    // V5.0.7731 — evidence from forward labels. 5.0.7729: COPY detected 2,666
    // smart-money buys, created 339 candidates, and read n=0 PAPER_ONLY with
    // 99 pending stamps, because this proof grades only canonical closes and
    // paper never runs while live. ForwardReturnLabeler7731 books the
    // 60-minute net return of every stamped mint it rules on; that is the
    // paper evidence the live book cannot produce. Kept in its own tally so
    // the report shows which kind of proof a source earned.
    private const val MIN_LABELS_7731 = 50
    private val labeled7731 = mapOf(Source.COPY to Tally(), Source.NETWORK to Tally(), Source.LLM_SCOUT to Tally())
    private val stamps = ConcurrentHashMap<String, Pair<Source, Long>>()
    // V5.0.7962 — [n, sum, sumSq] of the labels seen since 7962, for the standard error the
    // evidence-based promotion (liveEligible7962) needs; older labels carry no squares.
    private val labeledSq7962 = mapOf(Source.COPY to DoubleArray(3), Source.NETWORK to DoubleArray(3), Source.LLM_SCOUT to DoubleArray(3))
    private val subscribed = AtomicBoolean(false)
    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }
        prefs = p
        Source.values().forEach { tallies.getValue(it).decode(p.getString("${it.name}_LIVE_7835", null)) }
        Source.values().forEach { labeled7731.getValue(it).decode(p.getString("${it.name}_LABELED_7731", null)) }
        Source.values().forEach { s -> p.getString("${s.name}_LABELED_SQ_7962", null)?.split(',')?.forEachIndexed { i, v -> if (i < 3) labeledSq7962.getValue(s)[i] = v.toDoubleOrNull() ?: 0.0 } }
        ensureSubscribed()
    }

    /**
     * V5.0.7731 — a 60-minute forward label for a stamped mint. The stamp is
     * left in place: a real close may still grade it into the canonical tally.
     */
    fun onForwardLabel7731(mint: String, netReturnFraction: Double, nowMs: Long = System.currentTimeMillis()) {
        if (mint.isBlank() || !netReturnFraction.isFinite()) return
        val (source, at) = stamps[mint] ?: return
        if (nowMs < at || nowMs - at > STAMP_TTL_MS) return
        synchronized(this) {
            val t = labeled7731.getValue(source)
            t.n++
            t.sumRet += netReturnFraction
            if (netReturnFraction > 0.0) { t.wins++; t.grossWinSol += netReturnFraction } else t.grossLossSol += -netReturnFraction
            val sq = labeledSq7962.getValue(source)
            sq[0] += 1.0; sq[1] += netReturnFraction; sq[2] += netReturnFraction * netReturnFraction
            try { prefs?.edit()?.putString("${source.name}_LABELED_7731", t.encode())?.putString("${source.name}_LABELED_SQ_7962", sq.joinToString(","))?.apply() } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("SIGNAL_SOURCE_LABELED_7731_${source.name}") } catch (_: Throwable) {}
    }

    /** V5.0.7731 — pure: labeled evidence alone proves a source. */
    fun labeledProves7731(n: Int, meanRet: Double, pf: Double): Boolean =
        n >= MIN_LABELS_7731 && meanRet > 0.0 && pf >= MIN_PF

    fun stamp(source: Source, mint: String) {
        if (mint.isBlank()) return
        ensureSubscribed()
        stamps[mint] = source to System.currentTimeMillis()
        try { PipelineHealthCollector.labelInc("SIGNAL_SOURCE_STAMPED_7291_${source.name}") } catch (_: Throwable) {}
    }

    /**
     * V5.0.7830 — stamp only when no live stamp from another source holds the
     * mint, so a discovery source never steals a COPY / NETWORK attribution.
     */
    fun stampIfUnclaimed7830(source: Source, mint: String) {
        if (mint.isBlank()) return
        val cur = stamps[mint]
        if (cur != null && cur.first != source && System.currentTimeMillis() - cur.second < STAMP_TTL_MS) return
        stamp(source, mint)
    }

    /**
     * V5.0.7881 — what this source's data is worth paying for, 0..1, read by
     * HeliusCreditEconomy7881 to size the credits spent producing it.
     * Proven (live, or 50+ forward labels with positive mean and PF>=1.2): 1.0.
     * Measured losing (50+ labels, negative mean): 0.15 — a trickle, so it can
     * re-earn. Unmeasured: 0.5 — explored, not bankrolled.
     */
    @Synchronized
    fun economicValue7881(source: Source): Double {
        if (isProven(source)) return 1.0
        val l = labeled7731.getValue(source)
        if (labeledProves7731(l.n, l.mean(), l.pf()) || liveEligible7962(source)) return 1.0
        if (l.n >= MIN_LABELS_7731 && l.mean() < 0.0) return 0.15
        return 0.5
    }

    @Synchronized
    fun isProven(source: Source): Boolean {
        val t = tallies.getValue(source)
        if (t.n >= MIN_CLOSES && t.mean() > 0.0 && t.pf() >= MIN_PF) return true
        return false // Forward marks and simulated fills cannot authorize live sources.
    }

    /**
     * V5.0.7962 — live on evidence, not on a fixed count. 5.0.7958: COPY labeled n19 mean
     * +57.9% pf 5.8 sat PAPER_ONLY behind labeledBar n>=50. A source is live-eligible when
     * its settled live record proves it (isProven), or its forward labels clear the
     * statistical bar (ExpertWallets7962.labeledPromotes7962: n>=20, mean - SE above the
     * label-to-live gap, pf>=1.5; SE from the labels that carry squares, at least 8).
     * Ten or more settled live closes with a negative mean overrule the labels. Re-read on
     * every call: it demotes itself the moment the record stops holding. It routes copy
     * signals live through V3 / FDG / sizing — never the copy perps side trade.
     */
    @Synchronized
    fun liveEligible7962(source: Source): Boolean {
        if (isProven(source)) return true
        val t = tallies.getValue(source)
        if (t.n >= 10 && t.mean() < 0.0) return false
        val l = labeled7731.getValue(source)
        val sq = labeledSq7962.getValue(source)
        if (sq[0] < 8.0) return false
        val sd = com.lifecyclebot.engine.ExpertWallets7962.sdOf7962(sq[0].toInt(), sq[1], sq[2])
        return com.lifecyclebot.engine.ExpertWallets7962.labeledPromotes7962(l.n, l.mean(), sd, l.pf())
    }

    /** V5.0.7962 — the COPY source's standing for the Expert wallets diag line. */
    @Synchronized
    fun copyTier7962(): String {
        val l = labeled7731.getValue(Source.COPY)
        val sq = labeledSq7962.getValue(Source.COPY)
        val sd = com.lifecyclebot.engine.ExpertWallets7962.sdOf7962(sq[0].toInt(), sq[1], sq[2])
        val lo = if (sd.isFinite() && l.n > 0) l.mean() - sd / kotlin.math.sqrt(l.n.toDouble()) else Double.NaN
        val tier = when {
            isProven(Source.COPY) -> "PROVEN_LIVE"
            liveEligible7962(Source.COPY) -> "LABEL_PROVEN"
            else -> "PAPER_ONLY"
        }
        val pf = l.pf().let { if (it.isInfinite()) 99.0 else it }
        val loTxt = if (lo.isFinite()) "%+.1f%%".format(lo * 100.0) else "?"
        return "$tier(n=${l.n} sqN=${sq[0].toInt()} mean-SE=$loTxt pf=${"%.2f".format(pf)})"
    }

    private fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { event -> onEvent(event) }
        } catch (_: Throwable) {
            subscribed.set(false)
        }
    }

    private fun onEvent(event: CanonicalTradeFinalizedBus6450.Event) {
        if (!event.mode.equals("live", true)) return
        // V5.0.7807 — a recovered / basis-uncertain / quarantined close is not evidence (Field Manual L357).
        if (!CanonicalTradeFinalizedBus6450.isCleanForLearning7807(event)) { stamps.remove(event.mint); return }
        val (source, at) = stamps[event.mint] ?: return
        if (event.settledAtMs < at) return
        stamps.remove(event.mint)
        if (event.settledAtMs - at > STAMP_TTL_MS) return
        val ret = event.returnFraction
        if (!ret.isFinite()) return
        synchronized(this) {
            val t = tallies.getValue(source)
            t.n++
            t.sumRet += ret
            if (event.netRealizedPnlSol > 0.0) { t.wins++; t.grossWinSol += event.netRealizedPnlSol }
            else t.grossLossSol += -event.netRealizedPnlSol
            try { prefs?.edit()?.putString("${source.name}_LIVE_7835", t.encode())?.apply() } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("SIGNAL_SOURCE_GRADED_7291_${source.name}") } catch (_: Throwable) {}
    }

    @Synchronized
    fun statusLine(): String = Source.values().joinToString(" · ") { s ->
        val t = tallies.getValue(s)
        val pf = t.pf()
        val l = labeled7731.getValue(s)
        val lpf = l.pf()
        "${s.name}[n=${t.n} wr=${if (t.n > 0) "%.0f".format(100.0 * t.wins / t.n) else "0"}% " +
            "mean=${"%+.1f".format(100.0 * t.mean())}% pf=${if (pf.isInfinite()) "inf" else "%.2f".format(pf)} " +
            "labeled7731[n=${l.n} mean=${"%+.1f".format(100.0 * l.mean())}% pf=${if (lpf.isInfinite()) "inf" else "%.2f".format(lpf)}] " +
            "${if (isProven(s)) "PROVEN_LIVE" else "PAPER_ONLY"}]"
    } + " bar=n>=$MIN_CLOSES,mean>0,pf>=$MIN_PF labeledBar=n>=$MIN_LABELS_7731 pendingStamps=${stamps.size}"
}
