package com.lifecyclebot.engine

/**
 * V5.0.4254 — MultiAgentCriticStack.
 *
 * Local/background-only critic stack for A16. It models four roles without
 * adding any scanner/FDG/executor hot-path dependency:
 *   1) summarizer — compresses closed-trade context
 *   2) strategist — frames a bounded hypothesis
 *   3) skeptic — rejects hot-path API, hard-veto, zero-size, mux-risk proposals
 *   4) symbolic judge — requires rollback + metric + soft-shape language
 *
 * Accepted outputs go into AsyncStrategyLab's persistent hypothesis bank with
 * symbolicChecked=true. Rejected outputs do nothing. No trade command is ever
 * emitted from this stack.
 */
object MultiAgentCriticStack {
    enum class Role { SUMMARIZER, STRATEGIST, SKEPTIC, SYMBOLIC_JUDGE }

    data class CriticReview(
        val lane: String,
        val accepted: Boolean,
        val summary: String,
        val strategistNote: String,
        val skepticNote: String,
        val judgeNote: String,
    )

    fun reviewAndSubmit(
        lane: String,
        closedTradeSummary: String,
        candidateProposal: String,
        expectedMetric: String,
        rollbackCondition: String,
        sourceTag: String = "BACKGROUND_MULTI_AGENT_CRITIC",
    ): Boolean {
        val review = review(lane, closedTradeSummary, candidateProposal, expectedMetric, rollbackCondition, sourceTag)
        if (!review.accepted) return false
        return try {
            AsyncStrategyLab.submitBackgroundHypothesis(
                provider = AsyncStrategyLab.Provider.LOCAL_ONLY,
                lane = lane,
                expectedMetric = expectedMetric,
                proposal = "${review.strategistNote}\nsummary=${review.summary}\nskeptic=${review.skepticNote}\njudge=${review.judgeNote}",
                rollbackCondition = rollbackCondition,
                symbolicChecked = true,
            )
        } catch (_: Throwable) { false }
    }

    fun review(
        lane: String,
        closedTradeSummary: String,
        candidateProposal: String,
        expectedMetric: String,
        rollbackCondition: String,
        sourceTag: String = "BACKGROUND_MULTI_AGENT_CRITIC",
    ): CriticReview {
        val laneKey = lane.uppercase().take(32)
        val summary = summarize(closedTradeSummary)
        val proposal = candidateProposal.trim().take(1200)
        val metric = expectedMetric.trim().take(160)
        val rollback = rollbackCondition.trim().take(400)
        val backgroundOk = isBackgroundSource(sourceTag)
        val softShapeOk = proposal.contains("soft", true) || proposal.contains("size", true) || proposal.contains("bias", true) || proposal.contains("bounded", true)
        val metricOk = metric.isNotBlank() && !metric.contains("cosmetic", true)
        val rollbackOk = rollback.isNotBlank()
        val skepticOk = !looksDangerous(proposal) && !looksDangerous(metric) && !looksDangerous(rollback)
        val accepted = backgroundOk && proposal.isNotBlank() && metricOk && rollbackOk && softShapeOk && skepticOk
        val skeptic = if (skepticOk) "no_hot_path_api,no_hard_veto,no_zero_size,no_trade_command" else "rejected: hot-path/hard-veto/zero-size/trade-command risk"
        val judge = if (accepted) "symbolic_pass: metric+rollback+soft_shape+background_only" else "symbolic_fail"
        return CriticReview(
            lane = laneKey,
            accepted = accepted,
            summary = summary,
            strategistNote = "lane=$laneKey proposal=$proposal",
            skepticNote = skeptic,
            judgeNote = judge,
        )
    }

    private fun summarize(raw: String): String {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isBlank()) return "no_closed_trade_context"
        return s.take(700)
    }

    private fun isBackgroundSource(sourceTag: String): Boolean {
        val u = sourceTag.uppercase()
        return u.contains("BACKGROUND") && !u.contains("SCANNER") && !u.contains("FDG") && !u.contains("EXECUTOR") && !u.contains("BUY") && !u.contains("SELL")
    }

    private fun looksDangerous(text: String): Boolean {
        val u = text.uppercase()
        return listOf(
            "SCANNER_HOT_PATH_API",
            "FDG_HOT_PATH_API",
            "EXECUTOR_HOT_PATH_API",
            "SYNC_LLM_IN_EXECUTOR",
            "HARD_VETO",
            "ZERO_SIZE",
            "RETURN 0.0",
            "EXECUTEBUY",
            "FINALDECISIONGATE.EVALUATE(",
        ).any { u.contains(it) }
    }
}
