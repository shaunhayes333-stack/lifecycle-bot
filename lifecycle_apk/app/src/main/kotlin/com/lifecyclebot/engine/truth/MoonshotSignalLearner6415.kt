package com.lifecyclebot.engine.truth

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6415 §A — MOONSHOT SIGNAL LEARNER.
 *
 * OPERATOR DIRECTIVE (Feb 2026):
 * "several tokens have 26x or better this week we need to find them
 *  before 25k buy a good sized chunk and hold for huge profits.
 *  it needs to be SMART, LEARN and INTEGRATE ACROSS THE STACK."
 *
 * DESIGN
 * ──────
 *   • Per-signal Bayesian W/L counters. Each signal tracks its own
 *     "when I fired 1, what happened?" outcome distribution.
 *   • Fat-tail outcome weighting (V5.0.7799):
 *       +50..149%  = 1 learning unit
 *       +150..499% = 3 learning units
 *       +500..999% = 5 learning units
 *       +1000%+    = 8 learning units
 *     Losses <= -20% = 1 loss unit. Neutral outcomes are excluded.
 *     This makes the learner optimize for Moonshot's actual purpose:
 *     exceptional asymmetric runners, not merely a high win rate.
 *   • recordOutcome() called from the sell terminal path.
 *   • signalWeight(name) returns a multiplier in [0.25, 2.0] based
 *     on win-rate lift vs baseline. Cold start = 1.0 (equal weight).
 *   • Ceiling 2.0 / floor 0.25 keeps a single bad streak from
 *     zeroing out a signal permanently.
 *
 * V5.0.7799 persists the weighted signal evidence across process/build
 * restarts. AATE is repeatedly installed/restarted during field tuning;
 * resetting this specialist learner each time was erasing exactly the
 * runner evidence it was meant to accumulate.
 */
object MoonshotSignalLearner6415 {

    private const val WIN_THRESHOLD_PCT = 50.0
    private const val LOSS_THRESHOLD_PCT = -20.0
    private const val MIN_SAMPLE = 8

    private data class SignalStat(
        val wins: AtomicLong = AtomicLong(0L),
        val losses: AtomicLong = AtomicLong(0L),
        val samples: AtomicLong = AtomicLong(0L),
    )

    private val stats = ConcurrentHashMap<String, SignalStat>()
    private val globalWins = AtomicLong(0L)
    private val globalLosses = AtomicLong(0L)
    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("moonshot_signal_learner_6415", Context.MODE_PRIVATE)
        try {
            val raw = prefs?.getString("state_7799", null)
            if (!raw.isNullOrBlank()) {
                val root = JSONObject(raw)
                globalWins.set(root.optLong("gw", 0L))
                globalLosses.set(root.optLong("gl", 0L))
                val ss = root.optJSONObject("signals")
                if (ss != null) {
                    val it = ss.keys()
                    while (it.hasNext()) {
                        val name = it.next()
                        val o = ss.optJSONObject(name) ?: continue
                        stats[name] = SignalStat(
                            AtomicLong(o.optLong("w", 0L)),
                            AtomicLong(o.optLong("l", 0L)),
                            AtomicLong(o.optLong("n", 0L)),
                        )
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    private fun persist7799() {
        val p = prefs ?: return
        try {
            val ss = JSONObject()
            stats.forEach { (name, st) ->
                ss.put(name, JSONObject().apply {
                    put("w", st.wins.get()); put("l", st.losses.get()); put("n", st.samples.get())
                })
            }
            val root = JSONObject().apply {
                put("gw", globalWins.get()); put("gl", globalLosses.get()); put("signals", ss)
            }
            p.edit().putString("state_7799", root.toString()).apply()
        } catch (_: Throwable) {}
    }

    fun recordOutcome(mint: String, symbol: String, tier: String, signalsFired: Set<String>, pnlPct: Double) {
        val classify = when {
            pnlPct >= WIN_THRESHOLD_PCT -> 1
            pnlPct <= LOSS_THRESHOLD_PCT -> -1
            else -> 0
        }
        if (classify == 0) return
        val outcomeWeight = when {
            classify < 0 -> 1L
            pnlPct >= 1000.0 -> 8L
            pnlPct >= 500.0 -> 5L
            pnlPct >= 150.0 -> 3L
            else -> 1L
        }
        if (classify == 1) globalWins.addAndGet(outcomeWeight) else globalLosses.addAndGet(outcomeWeight)
        for (sig in signalsFired) {
            val s = stats.getOrPut(sig) { SignalStat() }
            s.samples.addAndGet(outcomeWeight)
            if (classify == 1) s.wins.addAndGet(outcomeWeight) else s.losses.addAndGet(outcomeWeight)
        }
        persist7799()
        try {
            ForensicLogger.lifecycle(
                "MOONSHOT_LEARNER_OUTCOME_6415",
                "mint=${mint.take(10)} sym=$symbol tier=$tier pnlPct=${"%.1f".format(pnlPct)} " +
                    "class=${if (classify == 1) "WIN" else "LOSS"} weight=$outcomeWeight signals=[${signalsFired.joinToString(",")}] " +
                    "globalW/L=${globalWins.get()}/${globalLosses.get()}",
            )
            PipelineHealthCollector.labelInc("MOONSHOT_LEARNER_OUTCOME_6415")
            PipelineHealthCollector.labelInc(if (classify == 1) "MOONSHOT_LEARNER_WIN_6415" else "MOONSHOT_LEARNER_LOSS_6415")
        } catch (_: Throwable) {}
    }

    /**
     * Returns a multiplier in [0.25, 2.0] representing how much this
     * signal has been over-predicting winners vs the global baseline.
     * Cold start returns 1.0.
     */
    fun signalWeight(signal: String): Double {
        val s = stats[signal] ?: return 1.0
        val n = s.samples.get()
        if (n < MIN_SAMPLE) return 1.0
        val globalN = (globalWins.get() + globalLosses.get()).coerceAtLeast(1L)
        val globalWinRate = globalWins.get().toDouble() / globalN.toDouble()
        val sigWinRate = s.wins.get().toDouble() / n.toDouble()
        if (globalWinRate <= 0.0) return 1.0
        val lift = sigWinRate / globalWinRate
        return lift.coerceIn(0.25, 2.0)
    }

    fun statusLine(): String {
        val n = stats.size
        val gW = globalWins.get()
        val gL = globalLosses.get()
        val topSignals = stats.entries.sortedByDescending {
            if (it.value.samples.get() < MIN_SAMPLE) 0.0 else signalWeight(it.key)
        }.take(3)
        val top = topSignals.joinToString(",") {
            "${it.key.take(20)}(w=${"%.2f".format(signalWeight(it.key))} n=${it.value.samples.get()})"
        }
        return "signals=$n globalW/L=$gW/$gL top=[$top]"
    }

    internal fun resetForTest() {
        stats.clear()
        globalWins.set(0L)
        globalLosses.set(0L)
    }
}
