
package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.7648 - learned latent-state transition model.
 *
 * Learns terminal transition distributions from exact position-bound outcomes:
 * entry latent state + selected policy -> RUNNER / PROFIT / SCRATCH / LOSS /
 * CATASTROPHIC. The distribution feeds imagination only; it has no execution
 * or hard-veto authority.
 */
object SuperLatentTransitionModel7648 {
    enum class TerminalClass {
        RUNNER,
        PROFIT,
        SCRATCH,
        LOSS,
        CATASTROPHIC,
    }

    data class Stat(
        var n: Long = 0L,
        var runners: Long = 0L,
        var profits: Long = 0L,
        var scratches: Long = 0L,
        var losses: Long = 0L,
        var catastrophics: Long = 0L,
        var returnSum: Double = 0.0,
        var mfeSum: Double = 0.0,
        var maeSum: Double = 0.0,
        var holdMsSum: Long = 0L,
    )

    data class Prior(
        val n: Long,
        val pRunner: Double,
        val pProfit: Double,
        val pScratch: Double,
        val pLoss: Double,
        val pCatastrophic: Double,
        val meanReturnPct: Double,
        val meanMfePct: Double,
        val meanMaePct: Double,
        val meanHoldMs: Double,
        val confidence: Double,
    )

    private val stats = ConcurrentHashMap<String, Stat>()

    private fun key(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
    ): String {
        return lane.trim().uppercase() + "|" + state.name + "|" + policy.name
    }

    private fun classify(realizedReturnPct: Double, mfePct: Double): TerminalClass {
        return when {
            realizedReturnPct <= -50.0 -> TerminalClass.CATASTROPHIC
            realizedReturnPct >= 100.0 || mfePct >= 100.0 -> TerminalClass.RUNNER
            realizedReturnPct > 5.0 -> TerminalClass.PROFIT
            realizedReturnPct >= -5.0 -> TerminalClass.SCRATCH
            else -> TerminalClass.LOSS
        }
    }

    fun recordOutcome(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
        realizedReturnPct: Double,
        mfePct: Double,
        maePct: Double,
        holdingTimeMs: Long,
    ) {
        if (!realizedReturnPct.isFinite()) return
        val terminal = classify(realizedReturnPct, mfePct)
        val s = stats.computeIfAbsent(key(lane, state, policy)) { Stat() }
        synchronized(s) {
            s.n += 1L
            when (terminal) {
                TerminalClass.RUNNER -> s.runners += 1L
                TerminalClass.PROFIT -> s.profits += 1L
                TerminalClass.SCRATCH -> s.scratches += 1L
                TerminalClass.LOSS -> s.losses += 1L
                TerminalClass.CATASTROPHIC -> s.catastrophics += 1L
            }
            s.returnSum += realizedReturnPct
            if (mfePct.isFinite()) s.mfeSum += mfePct
            if (maePct.isFinite()) s.maeSum += maePct
            s.holdMsSum += holdingTimeMs.coerceAtLeast(0L)
        }
        try {
            PipelineHealthCollector.labelInc("SUPER_LATENT_TRANSITION_OUTCOME_7648")
            PipelineHealthCollector.labelInc("SUPER_LATENT_TRANSITION_7648_" + terminal.name)
        } catch (_: Throwable) {}
    }

    fun prior(
        lane: String,
        state: SuperWorldModel7634.LatentState,
        policy: SuperPolicyTree7638.Policy,
    ): Prior? {
        val s = stats[key(lane, state, policy)] ?: return null
        return synchronized(s) {
            if (s.n < 3L) return@synchronized null
            val n = s.n.toDouble()
            Prior(
                n = s.n,
                pRunner = s.runners / n,
                pProfit = s.profits / n,
                pScratch = s.scratches / n,
                pLoss = s.losses / n,
                pCatastrophic = s.catastrophics / n,
                meanReturnPct = s.returnSum / n,
                meanMfePct = s.mfeSum / n,
                meanMaePct = s.maeSum / n,
                meanHoldMs = s.holdMsSum.toDouble() / n,
                confidence = (n / (n + 10.0)).coerceIn(0.0, 1.0),
            )
        }
    }

    fun exportState(): String {
        val arr = JSONArray()
        stats.forEach { (k, s) ->
            synchronized(s) {
                arr.put(
                    JSONObject()
                        .put("k", k)
                        .put("n", s.n)
                        .put("runners", s.runners)
                        .put("profits", s.profits)
                        .put("scratches", s.scratches)
                        .put("losses", s.losses)
                        .put("catastrophics", s.catastrophics)
                        .put("returnSum", s.returnSum)
                        .put("mfeSum", s.mfeSum)
                        .put("maeSum", s.maeSum)
                        .put("holdMsSum", s.holdMsSum)
                )
            }
        }
        return JSONObject().put("version", 7648).put("rows", arr).toString()
    }

    fun importState(raw: String) {
        if (raw.isBlank()) return
        try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val k = o.optString("k", "")
                if (k.isBlank()) continue
                stats[k] = Stat(
                    n = o.optLong("n", 0L),
                    runners = o.optLong("runners", 0L),
                    profits = o.optLong("profits", 0L),
                    scratches = o.optLong("scratches", 0L),
                    losses = o.optLong("losses", 0L),
                    catastrophics = o.optLong("catastrophics", 0L),
                    returnSum = o.optDouble("returnSum", 0.0),
                    mfeSum = o.optDouble("mfeSum", 0.0),
                    maeSum = o.optDouble("maeSum", 0.0),
                    holdMsSum = o.optLong("holdMsSum", 0L),
                )
            }
        } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val top = stats.entries.sortedByDescending { it.value.n }.take(8)
            .joinToString(" | ") { e ->
                synchronized(e.value) {
                    val p = priorFromStat(e.value)
                    String.format(
                        java.util.Locale.US,
                        "%s n=%d run=%.0f%% loss=%.0f%% cat=%.0f%% mean=%+.1f",
                        e.key,
                        e.value.n,
                        p.pRunner * 100.0,
                        p.pLoss * 100.0,
                        p.pCatastrophic * 100.0,
                        p.meanReturnPct,
                    )
                }
            }
        return "SUPER_LATENT_TRANSITIONS_7648 rows=" + stats.size + " top=[" + top + "]"
    }

    private fun priorFromStat(s: Stat): Prior {
        val n = s.n.coerceAtLeast(1L).toDouble()
        return Prior(
            n = s.n,
            pRunner = s.runners / n,
            pProfit = s.profits / n,
            pScratch = s.scratches / n,
            pLoss = s.losses / n,
            pCatastrophic = s.catastrophics / n,
            meanReturnPct = s.returnSum / n,
            meanMfePct = s.mfeSum / n,
            meanMaePct = s.maeSum / n,
            meanHoldMs = s.holdMsSum.toDouble() / n,
            confidence = (n / (n + 10.0)).coerceIn(0.0, 1.0),
        )
    }

    internal fun resetForTest() {
        stats.clear()
    }
}
