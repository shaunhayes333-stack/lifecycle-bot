package com.lifecyclebot.engine

/**
 * V5.0.8535 §P2-03 — every published metric names its unit, cohort, source epoch, horizon, entry basis, cost model and
 * dedup key, so two lines with the same n and different means explain themselves (8529: MOONSHOT "forward n212 -65.9 %"
 * beside "lanes60 n212 -9.0 %": one a capped 5-minute spike-credited read, the other the 60-minute executable label).
 * Pure formatter; the surfaces that print the tagged lines are ForwardReturnLabeler7731 (lanes60), ToolkitSignalSheet
 * (native counters), Costs8369 (model vs mark) and OwnershipChain8364 (chain counts).
 */
object MetricTag8535 {

    data class Tag(val unit: String, val cohort: String, val epoch: String, val horizon: String, val basis: String, val cost: String, val dedup: String) {
        fun line(): String = "{unit=$unit cohort=$cohort epoch=$epoch horizon=$horizon basis=$basis cost=$cost dedup=$dedup}"
    }

    val LANES60_8535 = Tag("pct_net_return", "forward_labels_all_decisions", "label_epoch_8169", "60m", "executable_mark_at_decision", "labelCostPct8534", "mint|lane")
    val NATIVE_COUNTERS_8535 = Tag("count", "native_opinions_post_review", "runtime_generation", "per_call", "specialist_brain_final", "none", "call")
    val MODEL_VS_MARK_8535 = Tag("pct_of_ticket", "lane_live_round_trips", "session", "fill_to_exit", "model_default_ticket_vs_measured_median", "model=all_in;measured=realised", "trip")
    val CHAIN_8535 = Tag("count", "candidate_records_not_unique_tokens", "runtime_generation", "candidate_ttl", "stage_stamps", "none", "lane|mint|version")

    fun tagged(label: String, tag: Tag): String = "$label${tag.line()}"
}
