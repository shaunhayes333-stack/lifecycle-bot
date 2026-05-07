package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z35 — Layer Coaching Curriculum.
 *
 * **Operator directive**: "we shouldn't have poisoned layers — we
 * should be coaching them with the right education to ensure they
 * don't get poisoned."
 *
 * Replaces the legacy "demote weak layers" framing with a remediation
 * plan: for every weak/silent layer the curriculum surfaces a precise
 * reason why it can't contribute yet AND what input it needs to
 * graduate. The Copilot keeps using the same layer-weight multiplier
 * machinery — the difference is that we now publish a coaching plan
 * the operator can read, and the brain status pill says
 * `COACHING (n)` instead of `POISONED`.
 */
object CoachingCurriculum {

    /** A specific kind of remediation a weak layer needs. */
    enum class Need {
        MORE_SAMPLES,                // layer has < N evaluations
        FRESH_DATA_FEED,             // layer waits on a missing data source
        CONTEXT_NOT_APPLICABLE,      // layer not useful for current asset class
        CALIBRATION_DRIFT,           // accuracy stagnating, learning rate too low
        REWARD_SIGNAL_MISSING,       // layer can score but learning loop never sees outcome
    }

    data class Lesson(
        val layer: String,
        val need: Need,
        val advice: String,
        val severity: Int,    // 0=info, 1=watch, 2=urgent
    )

    private val lessons = ConcurrentHashMap<String, Lesson>()

    /** Curated curriculum keyed on the well-known V3 layer names that
     *  routinely show up "DEAD" in logs. The operator now sees the
     *  reason and the remediation instead of a "POISONED" label. */
    private val curriculum: Map<String, Lesson> = listOf(
        Lesson("FundingRateAwarenessAI", Need.CONTEXT_NOT_APPLICABLE,
            "Spot meme tokens have no funding rate. Layer is correctly silent on memes — only fires on perps.",
            severity = 0),
        Lesson("NewsShockAI", Need.FRESH_DATA_FEED,
            "Macro sentiment poll returns flat 0.25/0.00 most of the time. Wire a fresher news feed (or accept that NewsShock fires only on real shocks).",
            severity = 1),
        Lesson("OperatorFingerprintAI", Need.MORE_SAMPLES,
            "Most fresh launches are NEW_OPERATOR. Layer needs ≥30 trades with the SAME creator wallet before it learns a fingerprint.",
            severity = 1),
        Lesson("ExecutionCostPredictorAI", Need.CALIBRATION_DRIFT,
            "Liquidity tier collapses to LIQ_500K_PLUS for too many entries. Re-bucket liquidity or lower the slip threshold so the layer differentiates.",
            severity = 1),
        Lesson("OrderbookImbalancePulseAI", Need.FRESH_DATA_FEED,
            "DEX orderbook is too thin to compute a pulse on most memes. Wire CEX-side book sampling or accept silence on Solana memes.",
            severity = 1),
        Lesson("CapitalEfficiencyAI", Need.MORE_SAMPLES,
            "Layer needs PnL/SOL·h history per AGE bucket. Bootstrap by replaying past trades into the historical store.",
            severity = 1),
        Lesson("CorrelationHedgeAI", Need.MORE_SAMPLES,
            "Healthy. Keep filling MCAP_1M_PLUS clusters so the cluster-cooldown threshold stays calibrated.",
            severity = 0),
        Lesson("SmartMoneyAI", Need.REWARD_SIGNAL_MISSING,
            "Layer scores entry but never sees post-trade outcome. Wire post-close PnL feedback into SmartMoneyAI.learnFromOutcome().",
            severity = 1),
    ).associateBy { it.layer }

    /** Update the curriculum based on the latest layer-health summary.
     *  Called from V3LayerHealthTracker.logSummary(). */
    fun coachFromHealth(name: String, zeroPct: Int, samples: Int) {
        val curated = curriculum[name]
        val derived: Lesson? = when {
            samples < 50                  -> Lesson(name, Need.MORE_SAMPLES,
                                              "Only $samples evaluations so far — coaching with replay/synthetic data recommended.",
                                              severity = 1)
            zeroPct >= 95 && curated != null  -> curated
            zeroPct >= 95                 -> Lesson(name, Need.FRESH_DATA_FEED,
                                              "Layer emits zero ${zeroPct}% of the time. Verify its input data source is live.",
                                              severity = 1)
            zeroPct >= 80                 -> Lesson(name, Need.CALIBRATION_DRIFT,
                                              "Layer is sparse (${zeroPct}% zero). Tune feature thresholds.",
                                              severity = 0)
            else                          -> null   // healthy
        }
        if (derived == null) lessons.remove(name) else lessons[name] = derived
    }

    fun activeLessons(): List<Lesson> = lessons.values.sortedByDescending { it.severity }

    fun count(): Int = lessons.size

    /** Compact one-liner for the brain pill: e.g.
     *  "🧑‍🏫 COACHING 6 layers · 2 urgent · 4 watch". */
    fun summaryLine(): String {
        val all = activeLessons()
        if (all.isEmpty()) return "🧠 STEADY"
        val urgent = all.count { it.severity >= 2 }
        val watch  = all.count { it.severity == 1 }
        val info   = all.count { it.severity == 0 }
        val parts = mutableListOf<String>()
        if (urgent > 0) parts += "$urgent urgent"
        if (watch > 0)  parts += "$watch watch"
        if (info > 0)   parts += "$info info"
        return "🧑‍🏫 COACHING ${all.size} layers · " + parts.joinToString(" · ")
    }

    /** Operator-facing detail (used by Live Forensics tab). */
    fun detailReport(): String {
        val all = activeLessons()
        if (all.isEmpty()) return "All layers steady — no active coaching needed."
        val sb = StringBuilder()
        sb.appendLine("Coaching plan (${all.size} layers):")
        for (l in all) {
            val tag = when (l.severity) {
                2 -> "❗"
                1 -> "🟡"
                else -> "📘"
            }
            sb.appendLine("$tag ${l.layer} · ${l.need} · ${l.advice}")
        }
        return sb.toString().trimEnd()
    }
}
