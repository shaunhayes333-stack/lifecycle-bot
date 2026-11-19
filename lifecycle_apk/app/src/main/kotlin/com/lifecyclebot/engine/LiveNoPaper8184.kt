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

    /**
     * V5.0.8336 §PAPER_RUNS_BESIDE_LIVE — owner: "turn back on the paper trading". 8184 (10-17) froze every paper
     * book while LIVE: the LLM Lab (189 refusals in a 4-minute 8316 session), the crypto LLM desk, the multi-chain lane
     * and the canonical cross-asset paper rows. Builds 8065 / 8086 / 8114, the ones that made money, ran with those books
     * trading. While this is false (the default) the gate is open in LIVE exactly as before 8184; set it true to restore
     * the 8184 freeze. The meme executor's own paperBuy stays blocked in LIVE by ExecutionRouteGuard (V5.0.3725, also in
     * 8114) — meme lanes learn in LIVE through the shadow path, unchanged.
     */
    /**
     * V5.0.8872 §PAPER_OFF_WHILE_LIVE — owner (20 Nov, on the 04:04 LIVE report): "paper is meant to turn off when live is
     * running close out all trades and the shadow trader runs while live does! paper is dragging down live trading running
     * at the same time" / "not just steering. having it running in the background plus the shadow trader plus the live
     * trader is crazy" / "the llms are fine". The 04:04 process (03:22 start in PAPER, then LIVE) held 93 canonical paper
     * positions and 5 meme-book positions while LIVE, the mark pool refused 9 jobs a second (0.5 on 8864) and the evidence
     * shadow opened 0 of 120. The freeze is on again by default; the LLM books ([LLM_SOURCES_8872]) keep their 8336 standing
     * and trade beside LIVE. PAPER mode is untouched. Existing paper positions are no longer left frozen in LIVE: the canonical
     * account closes them at observed marks (BotService.ensureLivePaperCloseOut8872 through the durable PaperStopLifecycle8521
     * close) and the measuring books stand theirs down ([closeOutInLive8872]).
     */
    @Volatile var enforced8184: Boolean = true

    /** V5.0.8872 — the LLM paper books (the LLM Lab sandbox, the crypto LLM desk): owner, 20 Nov: "the llms are fine". */
    private val LLM_SOURCES_8872: Set<String> = setOf("LLM_LAB_PAPER_OPEN", "LLM_LAB", "CRYPTO_LLM_PAPER_BUY")

    /** Pure (V5.0.8872): is [source] one of the LLM paper books that keep trading beside LIVE? */
    private fun llmBook8872(source: String): Boolean = sourceKey8184(source) in LLM_SOURCES_8872

    /** True when the runtime authority is LIVE and the 8184 freeze is enforced. */
    private fun isLive8184(): Boolean = enforced8184 && RuntimeModeAuthority.isLive()

    /**
     * V5.0.8872 — true while LIVE with the freeze on: a paper book that is not an LLM book closes out (the canonical
     * account) or stands down (a measuring book) every position it still holds, and opens none.
     */
    fun closeOutInLive8872(): Boolean = isLive8184()

    /**
     * V5.0.8184 — the one entry gate. False in LIVE while [enforced8184] (refusal counted under
     * [source]); true in PAPER, and true in LIVE while the freeze is off (V5.0.8336). V5.0.8872: true for an LLM book.
     */
    fun paperEntryAllowed8184(source: String): Boolean {
        if (!isLive8184() || llmBook8872(source)) return true
        refusals8184.computeIfAbsent(sourceKey8184(source)) { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_NO_PAPER_REFUSED_8184") } catch (_: Throwable) {}
        return false
    }

    /**
     * V5.0.8184 — may an EXISTING paper position be managed (exit checks,
     * paper closes, outcome hooks)? False in LIVE: the position stays frozen. V5.0.8872: an LLM book keeps managing.
     */
    fun paperManageAllowed8184(source: String): Boolean {
        if (!isLive8184() || llmBook8872(source)) return true
        frozenManageSkips8184.incrementAndGet()
        return false
    }

    private val closeOutRuns8872 = AtomicLong(0L)
    @Volatile private var closeOutPending8872 = -1
    @Volatile private var closeOutLastMs8872 = 0L

    /** V5.0.8872 — one pass of the LIVE canonical paper close-out finished with [pending] lots still awaiting a mark. */
    fun noteCloseOut8872(pending: Int, nowMs: Long = System.currentTimeMillis()) {
        closeOutRuns8872.incrementAndGet()
        closeOutPending8872 = pending
        closeOutLastMs8872 = nowMs
        try { PipelineHealthCollector.labelInc("LIVE_NO_PAPER_CLOSE_OUT_PASS_8872") } catch (_: Throwable) {}
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
        if (!enforced8184) return "mode=${if (RuntimeModeAuthority.isLive()) "LIVE" else "PAPER"} freeze=OFF_8336 " +
            "rule=paper books (LLM Lab, crypto LLM desk, multi-chain, cross-asset) trade beside LIVE as at 8114; meme paperBuy stays shadow-only in LIVE"
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
            .append(" closeOut8872[passes=").append(closeOutRuns8872.get()).append(" pending=").append(closeOutPending8872)
            .append(" lastAgoS=").append(if (closeOutLastMs8872 > 0L) (System.currentTimeMillis() - closeOutLastMs8872) / 1000L else -1L).append(']')
            .append(" llmBooks8872=").append(LLM_SOURCES_8872.joinToString("/"))
            .append(" rule=LIVE opens no paper position; existing paper closes out at observed marks (8872); LLM books trade beside LIVE; shadows and labels stay on")
            .toString()
    }

    internal fun resetForTest8184() {
        refusals8184.clear()
        quietClosed8184.clear()
        frozenManageSkips8184.set(0L)
        closeOutRuns8872.set(0L); closeOutPending8872 = -1; closeOutLastMs8872 = 0L
    }
}
