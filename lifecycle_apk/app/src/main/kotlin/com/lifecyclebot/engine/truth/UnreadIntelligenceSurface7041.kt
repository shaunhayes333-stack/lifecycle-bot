package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.7041 — first readers for the intelligence circuit, take two.
 *
 * ci/audit_unread_intelligence.py reports 110 modules holding 280 analysis
 * functions nothing in the app calls. The engine computes those answers every
 * cycle and discards all of them.
 *
 * WHY THIS IS THE SECOND ATTEMPT. V5.0.7036 wired fifteen of them at once and
 * failed to compile; 7037-7039 stacked three more builds on top before the
 * first result came back, so four red builds shared one unattributable cause
 * and the whole file was reverted in 7040. Every symbol had been checked by
 * hand — object-vs-class, direct membership by brace depth, signature, arity,
 * return type — and all of it passed, which is exactly why a big batch was the
 * wrong shape: when the check says fine and the compiler says no, the only
 * thing that narrows it is a smaller batch.
 *
 * So: THREE readers, from ONE module family, and nothing else in the build.
 * The next batch goes in only after this one is green. 7041 also makes the
 * compiler's own error survive the log tail (see build.yml), so the next
 * failure names its file and line instead of being guessed at.
 *
 * READ-ONLY. Every call returns a number or a list; none can refuse a trade,
 * resize one, or move a threshold. Each is individually guarded because these
 * modules have never been exercised from this path, and one of them throwing
 * must not cost the operator the only diagnostic surface they have.
 */
object UnreadIntelligenceSurface7041 {

    private fun pct(v: Double): String =
        if (!v.isFinite()) "—" else String.format(java.util.Locale.US, "%.1f%%", v)

    private fun <T> safe(label: String, block: () -> T): T? = try {
        block()
    } catch (_: Throwable) {
        try { PipelineHealthCollector.labelInc("UNREAD_INTEL_READ_FAILED_7041_$label") } catch (_: Throwable) {}
        null
    }

    fun report(): String {
        val sb = StringBuilder()
        sb.append("===== UNREAD INTELLIGENCE (V5.0.7041 — first readers) =====\n")

        // The one I most want on a snapshot. The operator's intake is dominated
        // by a handful of sources (SCANNER_DIRECT_PUMP_FUN_NEW 486,
        // MEME_REGISTRY_RESTORE 160, PUMP_FUN_NEW 155) and ScannerLearning has
        // been scoring each of them all along with no reader. "Which of my
        // sources actually wins" is the first question worth asking of an 8%
        // win rate and the bot has never been able to answer it.
        //
        // Its own floor matters when reading this: getSourceWinRate returns 0.5
        // below five closes, so exactly 50.0% means NO EVIDENCE YET, not a
        // coin flip. Surfaced raw rather than ranked so that stays visible.
        safe("SOURCE_WIN_RATES") {
            listOf(
                "PUMP_FUN_NEW", "DEX_TRENDING", "PUMP_FUN_GRADUATE",
                "RAYDIUM_NEW_POOL", "MEME_REGISTRY_RESTORE",
            ).map { it to com.lifecyclebot.engine.ScannerLearning.getSourceWinRate(it) }
        }?.let { rates ->
            sb.append("  ScannerLearning source WR (0.5 = no evidence yet):\n")
            for ((src, wr) in rates) {
                sb.append("    ").append(src.padEnd(24)).append(pct(wr * 100.0)).append("\n")
            }
        }

        safe("EXIT_PROFITABLE_RATE") {
            com.lifecyclebot.engine.ExitIntelligence.getProfitableRate()
        }?.let { sb.append("  ExitIntelligence.profitableRate:  ").append(pct(it)).append("\n") }

        safe("EDGE_VETO_STICKY") {
            com.lifecyclebot.engine.EdgeLearning.getVetoStickyMinutes()
        }?.let { sb.append("  EdgeLearning.vetoStickyMinutes:   ").append(it).append("m\n") }

        sb.append("  Read: advisory only — nothing here gates a trade.\n")
        return sb.toString()
    }
}
