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
    enum class Source { COPY, NETWORK }

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

    private val tallies = mapOf(Source.COPY to Tally(), Source.NETWORK to Tally())
    private val stamps = ConcurrentHashMap<String, Pair<Source, Long>>()
    private val subscribed = AtomicBoolean(false)
    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val p = try {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }
        prefs = p
        Source.values().forEach { tallies.getValue(it).decode(p.getString(it.name, null)) }
        ensureSubscribed()
    }

    fun stamp(source: Source, mint: String) {
        if (mint.isBlank()) return
        ensureSubscribed()
        stamps[mint] = source to System.currentTimeMillis()
        try { PipelineHealthCollector.labelInc("SIGNAL_SOURCE_STAMPED_7291_${source.name}") } catch (_: Throwable) {}
    }

    @Synchronized
    fun isProven(source: Source): Boolean {
        val t = tallies.getValue(source)
        return t.n >= MIN_CLOSES && t.mean() > 0.0 && t.pf() >= MIN_PF
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
            try { prefs?.edit()?.putString(source.name, t.encode())?.apply() } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("SIGNAL_SOURCE_GRADED_7291_${source.name}") } catch (_: Throwable) {}
    }

    @Synchronized
    fun statusLine(): String = Source.values().joinToString(" · ") { s ->
        val t = tallies.getValue(s)
        val pf = t.pf()
        "${s.name}[n=${t.n} wr=${if (t.n > 0) "%.0f".format(100.0 * t.wins / t.n) else "0"}% " +
            "mean=${"%+.1f".format(100.0 * t.mean())}% pf=${if (pf.isInfinite()) "inf" else "%.2f".format(pf)} " +
            "${if (isProven(s)) "PROVEN_LIVE" else "PAPER_ONLY"}]"
    } + " bar=n>=$MIN_CLOSES,mean>0,pf>=$MIN_PF pendingStamps=${stamps.size}"
}
