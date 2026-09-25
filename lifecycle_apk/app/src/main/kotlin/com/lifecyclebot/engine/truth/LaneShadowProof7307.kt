package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7307 §A LANE CAN PROVE ITSELF WHILE LIVE.
 *
 * In LIVE a specialist's own score only stands in for the generic V3 score
 * once the lane's journal shows >=20 closes with positive mean net return
 * (FinalDecisionGate 7292). Paper does not trade while live and the Executor
 * shadow book only sees candidates FDG already allowed, so a lane refused at
 * that floor (BLUECHIP n=3 on 5.0.7305, laneScore 80-90 refused 308 times)
 * could never gather the closes it needed. The proof requirement was a
 * permanent lane disable in disguise.
 *
 * This ledger follows exactly those refused candidates: when FDG refuses a
 * specialist lane ONLY because the lane is unproven, a shadow position opens
 * here under that lane at the observed price. It spends nothing and touches no
 * ledger, journal or capital authority. It closes on the live exit doctrine
 * (-15% hard floor; 25-point give-back once peak >= +20%; 60-minute horizon;
 * winners are never capped) and books a NET return: gross minus the round-trip
 * cost snapshot taken at open from the same components LiveBreakEvenGuard
 * charges (both slippage legs, priority fee, platform fee, spread, MEV buffer).
 *
 * A lane is proven for live when its journal proves it OR its shadow record
 * does: >=20 shadow closes with positive mean net return. The tallies persist,
 * so evidence accumulates across sessions.
 */
object LaneShadowProof7307 {
    private const val PROOF_MIN_CLOSES_7307 = 20
    private const val MAX_OPEN_PER_LANE_7307 = 12
    private const val HARD_FLOOR_PCT_7307 = -15.0
    private const val GIVEBACK_MIN_PEAK_7307 = 20.0
    private const val GIVEBACK_PTS_7307 = 25.0
    private const val HORIZON_MS_7307 = 60 * 60_000L
    private const val PREFS_7307 = "lane_shadow_proof_7307"

    private class Open(val lane: String, val entryPrice: Double, val costPct: Double, val atMs: Long) {
        @Volatile var peakPct = 0.0
    }

    /** n, sum of net %, wins. */
    private class Tally { var n = 0; var sum = 0.0; var wins = 0 }

    private val open = ConcurrentHashMap<String, Open>()          // key = lane::mint
    private val tallies = ConcurrentHashMap<String, Tally>()
    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    fun attach7307(context: Context) {
        if (prefs != null) return
        val p = try {
            context.applicationContext.getSharedPreferences(PREFS_7307, Context.MODE_PRIVATE)
        } catch (_: Throwable) { return }
        prefs = p
        try {
            p.getString("tallies", null)?.split(';')?.forEach { row ->
                val f = row.split(',')
                if (f.size != 4) return@forEach
                tallies[f[0]] = Tally().apply {
                    n = f[1].toIntOrNull() ?: 0
                    sum = f[2].toDoubleOrNull() ?: 0.0
                    wins = f[3].toIntOrNull() ?: 0
                }
            }
        } catch (_: Throwable) {}
        // V5.0.7320 — the BLUECHIP tally was built from pump.fun candidates;
        // drop it once so the lane is graded on its own asset class.
        try {
            if (!p.getBoolean("bluechip_reset_7320", false)) {
                tallies.remove("BLUECHIP"); tallies.remove("BLUE_CHIP")
                persist()
                p.edit().putBoolean("bluechip_reset_7320", true).apply()
            }
        } catch (_: Throwable) {}
    }

    private fun persist() {
        val p = prefs ?: return
        try {
            val enc = tallies.entries.joinToString(";") { (k, t) -> "$k,${t.n},${t.sum},${t.wins}" }
            p.edit().putString("tallies", enc).apply()
        } catch (_: Throwable) {}
    }

    /** Pure round-trip cost, same components as LiveBreakEvenGuard minus its profit buffers. */
    private fun roundTripCostPct(liquidityUsd: Double, sizeSol: Double): Double {
        val slip = try {
            com.lifecyclebot.v3.scoring.ExecutionCostPredictorAI.expectedExtraSlipPct(liquidityUsd)
        } catch (_: Throwable) {
            when { liquidityUsd < 5_000.0 -> 8.0; liquidityUsd < 20_000.0 -> 5.0; else -> 2.0 }
        }.coerceIn(0.0, 15.0)
        val priority = if (sizeSol > 0.0) (0.0008 / sizeSol * 100.0).coerceIn(0.0, 6.0) else 6.0
        val spread = when { liquidityUsd < 5_000.0 -> 4.0; liquidityUsd < 20_000.0 -> 2.0; else -> 1.0 }
        return 2.0 * slip + priority + 1.0 + spread + 1.5
    }

    fun onUnprovenRefusal(lane: String, mint: String, price: Double, liquidityUsd: Double, sizeSol: Double, nowMs: Long = System.currentTimeMillis()) {
        val l = lane.trim().uppercase()
        if (l.isBlank() || mint.isBlank() || !price.isFinite() || price <= 0.0) return
        // V5.0.7320 — a pump.fun mint is not BLUECHIP's asset class; grading
        // meme outcomes against BLUECHIP (n=6, -28.5%) proves nothing about it.
        if ((l == "BLUECHIP" || l == "BLUE_CHIP") &&
            mint.endsWith("pump", ignoreCase = true)) return
        val key = "$l::$mint"
        if (open.containsKey(key)) return
        if (open.keys.count { it.startsWith("$l::") } >= MAX_OPEN_PER_LANE_7307) {
            try { PipelineHealthCollector.labelInc("LANE_SHADOW_PROOF_LANE_FULL_7307_$l") } catch (_: Throwable) {}
            return
        }
        open[key] = Open(l, price, roundTripCostPct(liquidityUsd, sizeSol.coerceAtLeast(0.01)), nowMs)
        try { PipelineHealthCollector.labelInc("LANE_SHADOW_PROOF_OPENED_7307_$l") } catch (_: Throwable) {}
    }

    /** Pure exit rule on gross %; returns an exit reason or null. */
    fun exitReason(grossPct: Double, peakPct: Double, ageMs: Long): String? = when {
        grossPct <= HARD_FLOOR_PCT_7307 -> "HARD_FLOOR"
        peakPct >= GIVEBACK_MIN_PEAK_7307 && peakPct - grossPct >= GIVEBACK_PTS_7307 -> "GIVEBACK"
        ageMs >= HORIZON_MS_7307 -> "HORIZON"
        else -> null
    }

    /** Called once per loop with the current price per mint (sanity-checked by the caller). */
    fun tick(priceFor: (String) -> Double?, nowMs: Long = System.currentTimeMillis()) {
        var closed = false
        for ((key, o) in open.entries.toList()) {
            val mint = key.substringAfter("::")
            val px = priceFor(mint)
            val age = nowMs - o.atMs
            if (px == null || !px.isFinite() || px <= 0.0) {
                // No sane price for the whole horizon: drop without booking.
                if (age >= HORIZON_MS_7307 * 2) open.remove(key)
                continue
            }
            val gross = (px / o.entryPrice - 1.0) * 100.0
            if (gross > o.peakPct) o.peakPct = gross
            val reason = exitReason(gross, o.peakPct, age) ?: continue
            open.remove(key)
            val net = gross - o.costPct
            val t = tallies.getOrPut(o.lane) { Tally() }
            synchronized(t) { t.n += 1; t.sum += net; if (net > 0.0) t.wins += 1 }
            closed = true
            try {
                PipelineHealthCollector.labelInc("LANE_SHADOW_PROOF_CLOSED_7307_${o.lane}")
                PipelineHealthCollector.labelInc("LANE_SHADOW_PROOF_EXIT_7307_$reason")
            } catch (_: Throwable) {}
        }
        if (closed) persist()
    }

    fun stat(lane: String): OracleTradeHistory7287.Stat? {
        val t = tallies[lane.trim().uppercase()] ?: return null
        return synchronized(t) {
            if (t.n <= 0) null else OracleTradeHistory7287.Stat(t.n, t.sum / t.n, t.wins.toDouble() / t.n)
        }
    }

    /** Pure: shadow evidence alone proves the lane. */
    fun shadowProves(stat: OracleTradeHistory7287.Stat?): Boolean =
        stat != null && stat.n >= PROOF_MIN_CLOSES_7307 && stat.meanNetPct > 0.0

    fun statusLine(): String {
        val lanes = tallies.keys.sorted().joinToString(" · ") { l ->
            val s = stat(l)
            if (s == null) l else "$l[n=${s.n} net=${"%+.1f".format(s.meanNetPct)}% wr=${"%.0f".format(s.winRate * 100)}%${if (shadowProves(s)) " PROVEN" else ""}]"
        }
        return "open=${open.size} ${lanes.ifBlank { "no closes yet" }}"
    }
}
