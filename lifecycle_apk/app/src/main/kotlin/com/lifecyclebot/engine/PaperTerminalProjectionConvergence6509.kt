package com.lifecyclebot.engine

/** V5.0.6509 — canonical CLOSED is commit truth; projections converge independently. */
object PaperTerminalProjectionConvergence6509 {
    data class Ops(
        val closeLedger: () -> String,
        val paperAuthority: (String) -> Unit,
        val guardrail: () -> Unit,
        val globalRegistry: () -> Unit,
        val portfolio: () -> Unit,
    )
    data class Result(val closeId: String, val failed: Set<String>) { val complete: Boolean get() = failed.isEmpty() }

    fun canonicalClosedNoActive(mint: String): Boolean {
        val closed = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.closedPositions().any { it.mode == "paper" && it.mint == mint } } catch (_: Throwable) { false }
        val active = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().any { it.mode == "paper" && it.mint == mint } } catch (_: Throwable) { false }
        return closed && !active
    }

    fun converge(mint: String, symbol: String, reason: String, pnlPct: Int, ops: Ops = productionOps(mint, symbol, reason, pnlPct)): Result {
        val failed = linkedSetOf<String>()
        var closeId = PositionCloseLedger.closeIdOf(mint).orEmpty()
        try { closeId = ops.closeLedger().ifBlank { closeId } } catch (t: Throwable) { failed += "LEDGER"; emit("POST_CLOSE_LEDGER_STAMP_FAIL_6509", mint, t) }
        try { ops.paperAuthority(closeId) } catch (t: Throwable) { failed += "PAPER_AUTH"; emit("POST_CLOSE_PAPER_AUTH_FAIL_6509", mint, t) }
        try { ops.guardrail() } catch (t: Throwable) { failed += "GUARDRAIL"; emit("POST_CLOSE_GUARDRAIL_REMOVE_FAIL_6509", mint, t) }
        try { ops.globalRegistry() } catch (t: Throwable) { failed += "GLOBAL_REGISTRY"; emit("POST_CLOSE_GLOBAL_REGISTRY_FAIL_6509", mint, t) }
        try { ops.portfolio() } catch (t: Throwable) { failed += "PORTFOLIO"; emit("POST_CLOSE_PORTFOLIO_REMOVE_FAIL_6509", mint, t) }
        try {
            PipelineHealthCollector.labelInc(if (failed.isEmpty()) "PAPER_TERMINAL_PROJECTIONS_COMMITTED_6509" else "PAPER_TERMINAL_PROJECTIONS_REPAIR_PENDING_6509")
            ForensicLogger.lifecycle("POSITION_CLOSE_LEDGER_STAMPED_6498", "mint=${mint.take(10)} closeId=$closeId reason=$reason canonicalClosed=true failed=${failed.joinToString()}")
        } catch (_: Throwable) {}
        return Result(closeId, failed)
    }

    private fun productionOps(mint: String, symbol: String, reason: String, pnlPct: Int) = Ops(
        closeLedger = { PositionCloseLedger.markClosed(mint, reason, pnlPct) },
        paperAuthority = { id -> PaperPositionCloseAuthority.markClosed("PAPER", mint, symbol, reason, id) },
        guardrail = { EmergentGuardrails.unregisterPosition(mint) },
        globalRegistry = { GlobalTradeRegistry.closePosition(mint); Unit },
        portfolio = { com.lifecyclebot.v4.meta.PortfolioHeatAI.removePosition(mint) },
    )

    private fun emit(label: String, mint: String, t: Throwable) {
        try { PipelineHealthCollector.labelInc(label); ForensicLogger.lifecycle(label, "mint=${mint.take(10)} err=${t.javaClass.simpleName}:${t.message?.take(80)}") } catch (_: Throwable) {}
    }
}
