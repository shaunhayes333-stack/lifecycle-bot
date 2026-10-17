package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8184 — NO PAPER WHILE LIVE.
 *
 * Owner: "there's also paper trading happening while live is running. this is
 * unacceptable." Live report 5.0.8175 showed 8 canonical PAPER positions, LLM Lab
 * paperTrades=113 and the multi-chain lane opening paper on BSC because the chain
 * had no gas (SHADOW(NO_GAS) open[live=0 paper=6]).
 *
 * One gate. Every path that books a PAPER position, ledger row or paper bankroll
 * debit calls [paperEntryAllowed8184] first. It is false whenever
 * RuntimeModeAuthority says LIVE, and each refusal is counted per source.
 * PAPER mode is untouched: the gate returns true and counts nothing.
 *
 * Paper positions that already exist when the runtime is LIVE:
 *   - canonical paper rows (cross-asset traders) stay FROZEN: the live exit
 *     scope, slot counts and live exposure are already mode scoped (V5.0.7254,
 *     SlotHealthGate current-mode inventory, kill switch "paper excluded"), so
 *     they hold no live slot and get no live exit; they resume in PAPER mode.
 *   - LLM Lab sandbox positions are FROZEN ([paperManageAllowed8184]) so no lab
 *     paper P&L reaches the cross-engine outcome hooks while LIVE.
 *   - multi-chain lane paper positions close quietly, unbooked, with no chain
 *     action ([noteQuietClose8184]); a held paper token would otherwise block a
 *     live entry on the same token (ALREADY_HELD_7962).
 *
 * Counterfactual LABELS stay on in LIVE (ForwardReturnLabeler7731 observations,
 * the Executor shadow book, ShadowLearningEngine variants): they hold no slot,
 * balance or ledger row and learning needs them.
 */
object LiveNoPaper8184 {

    private val refusals8184 = ConcurrentHashMap<String, AtomicLong>()
    private val quietClosed8184 = ConcurrentHashMap<String, AtomicLong>()
    private val frozenManageSkips8184 = AtomicLong(0L)

    /** True when the runtime authority is LIVE. */
    private fun isLive8184(): Boolean = RuntimeModeAuthority.isLive()

    /**
     * V5.0.8184 — the one entry gate. False in LIVE (refusal counted under
     * [source]); true in PAPER.
     */
    fun paperEntryAllowed8184(source: String): Boolean {
        if (!isLive8184()) return true
        refusals8184.computeIfAbsent(sourceKey8184(source)) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_NO_PAPER_REFUSED_8184") } catch (_: Throwable) {}
        return false
    }

    /**
     * V5.0.8184 — may an EXISTING paper position be managed (exit checks,
     * paper closes, outcome hooks)? False in LIVE: the position stays frozen.
     */
    fun paperManageAllowed8184(source: String): Boolean {
        if (!isLive8184()) return true
        frozenManageSkips8184.incrementAndGet()
        return false
    }

    /** V5.0.8184 — a paper position closed quietly (paper-only terminal) because the runtime is LIVE. */
    fun noteQuietClose8184(source: String) {
        quietClosed8184.computeIfAbsent(sourceKey8184(source)) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_NO_PAPER_QUIET_CLOSE_8184") } catch (_: Throwable) {}
    }

    fun refusalCount8184(source: String): Long = refusals8184[sourceKey8184(source)]?.get() ?: 0L

    fun totalRefusals8184(): Long = refusals8184.values.sumOf { it.get() }

    private fun quietClosedTotal8184(): Long = quietClosed8184.values.sumOf { it.get() }

    private fun sourceKey8184(source: String): String =
        source.trim().ifBlank { "UNKNOWN" }.take(64)

    private fun fmt8184(m: Map<String, AtomicLong>): String =
        m.entries.filter { it.value.get() > 0L }
            .sortedByDescending { it.value.get() }
            .take(12)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
            .ifBlank { "-" }

    /** Report line (PipelineHealthCollector exit-coverage helper). */
    fun statusLine8184(): String {
        val live = isLive8184()
        val frozenCanonical = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
                .count { it.mode.equals("paper", ignoreCase = true) }
        } catch (_: Throwable) { -1 }
        val labOpen = try { com.lifecyclebot.engine.lab.LlmLabStore.allPositions().size } catch (_: Throwable) { -1 }
        return StringBuilder()
            .append("mode=").append(if (live) "LIVE" else "PAPER")
            .append(" refused[total=").append(totalRefusals8184()).append(' ').append(fmt8184(refusals8184)).append(']')
            .append(if (live) " frozenCanonicalPaper=" else " canonicalPaperOpen=").append(frozenCanonical)
            .append(if (live) " frozenLabPaper=" else " labPaperOpen=").append(labOpen)
            .append(" frozenManageSkips=").append(frozenManageSkips8184.get())
            .append(" quietClosed[total=").append(quietClosedTotal8184()).append(' ').append(fmt8184(quietClosed8184)).append(']')
            .append(" rule=LIVE opens no paper position; existing paper rows hold no live slot, exposure or exit; labels stay on")
            .toString()
    }

    internal fun resetForTest8184() {
        refusals8184.clear()
        quietClosed8184.clear()
        frozenManageSkips8184.set(0L)
    }
}
