package com.lifecyclebot.engine

/**
 * V5.0.4412 — CQ1 zero-size sibling hit list.
 *
 * Report-only classifier for the zero-size/multiplier family. It separates real
 * throughput choke candidates from benign math/telemetry zero returns so the
 * source patch can be surgical instead of weakening safety or accounting.
 */
object OperatorCQ1ZeroSizeHitList {
    data class Hit(
        val file: String,
        val pattern: String,
        val classification: String,
        val risk: String,
        val downstream: String
    )

    private val hits = listOf(
        Hit(
            file = "CashGenerationAI.kt",
            pattern = "below MIN_POSITION_SOL returns 0.0 and caller skips trade",
            classification = "CAPITAL_SAFETY_GUARD_REVIEWED",
            risk = "live wallet-too-small guard prevents the known Treasury fee-drain money leak; do not soften without explicit reserve-aware probe design",
            downstream = "Treasury lane -> wallet reserve -> Executor fee/slippage safety -> journal/KPI skip reason"
        ),
        Hit(
            file = "LaneStrategyEvaluator.kt",
            pattern = "noTrade transform returns 0.0",
            classification = "REPLAY_POLICY_CANDIDATE_NOT_DIRECT_EXECUTION",
            risk = "replay evaluator no-trade zero should be route-visible if ever consumed by live policy",
            downstream = "replay evaluation -> lane policy proposal -> FDG/verdict visibility -> learning labels"
        ),
        Hit(
            file = "BotBrain.kt",
            pattern = "suppressed pattern penalty returns 0.0 when rawPenalty absent",
            classification = "LIKELY_BENIGN_METRIC_FLOOR",
            risk = "low direct volume risk unless caller treats zero as hard permission",
            downstream = "score shaping -> AGI multiplier stack -> telemetry"
        ),
        Hit(
            file = "FinalDecisionGate.kt",
            pattern = "closed-loop adjustment returns 0.0 before feedback maturity",
            classification = "BENIGN_NEUTRAL_ADJUSTMENT",
            risk = "not a choke; zero means neutral confidence adjustment",
            downstream = "FDG feedback state only"
        ),
        Hit(
            file = "LiveSizingProfile.kt",
            pattern = "daily realized PnL percentage returns 0.0 when anchor wallet absent",
            classification = "BENIGN_TELEMETRY_GUARD",
            risk = "not a choke; zero means no realized-pnl anchor",
            downstream = "KPI summary/ramp telemetry only"
        ),
        Hit(
            file = "Executor.kt",
            pattern = "price fallback returns 0.0 only after live/candle/entry price missing",
            classification = "CONFIRMED_PRICE_TRUST_GUARD_NOT_ENTRY_SIZE",
            risk = "do not convert blindly; butterfly is exit evaluation price trust, not buy-size throughput",
            downstream = "exit gates -> price trust -> PnL/reporting; needs separate price-staleness audit"
        )
    )

    fun status(): String {
        val reviewed = hits.filter { it.classification.contains("REVIEWED") || it.classification.contains("CANDIDATE") }.joinToString(",") { it.file }
        val benign = hits.filter { it.classification.contains("BENIGN") }.joinToString(",") { it.file }
        return "OPERATOR_CQ1_ZERO_SIZE_HIT_LIST_4413 total=${hits.size} reviewed=[$reviewed] benign=[$benign] report_only=true no_execution_authority=true no_gate_change=true source_fix_first=true false_positives_separated=true capital_safety_preserved=true butterflies_named=true"
    }
}
