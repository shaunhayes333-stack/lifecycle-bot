package com.lifecyclebot.engine

/**
 * V5.0.8535 §N-03 — PAPER executes what LIVE executes (operator, 2026-11-05: "the meme trader does not trade at all in
 * paper mode").
 *
 * The FinalDecisionGate runs the same gates in both modes, then classifies a PAPER approval: only PAPER_BENCHMARK is
 * economic (7548), everything else is refused as NON_ECONOMIC_APPROVAL_7835. The benchmark test asked for a non-SKIP
 * *legacy edge letter* (`edgeVerdict != SKIP`) — a rule LIVE never applies. EdgeOptimizer.filterTrade grades a passing
 * meme candidate "C" whenever it is not in EXPANSION / REACCUMULATION or at an optimal entry (every fresh pump.fun coin
 * in an UNKNOWN phase), the lane adapters rewrite a SKIP grade to "C" as well, and the gate maps "C" to EdgeVerdict.SKIP.
 * The edge gate then admits that candidate on the canonical evidence LIVE also takes (7431: buy pressure, liquidity or
 * score — `canonical_edge_evidence_override_7431`), LIVE buys it, and PAPER files the identical decision as
 * PAPER_EXPLORATION and refuses it. 8525 removed the confidence rule from the same test and noted "paper meme buys 0
 * while LIVE bought the same coins"; the edge rule it left behind refuses the rest.
 *
 * The benchmark now reads the gate's OWN edge verdict for this decision: the check named "edge" passed (a non-SKIP grade
 * or the 7431 evidence), exactly the test a LIVE approval passed. No gate is loosened: a failed edge check already set
 * the block reason and never reaches the classification; a Cortex paper choice (7915) that overrode an edge veto leaves
 * no passed edge check and stays EXPLORATION, because LIVE would not have taken it.
 */
object PaperBenchmark8535 {

    private const val EDGE_CHECK = "edge"

    /** Pure: the gate's edge check passed for this decision — the rule LIVE applies, whatever the raw edge letter. */
    fun edgePassedAsLive8535(checks: List<FinalDecisionGate.GateCheck>): Boolean =
        checks.lastOrNull { it.name == EDGE_CHECK }?.passed == true
}
