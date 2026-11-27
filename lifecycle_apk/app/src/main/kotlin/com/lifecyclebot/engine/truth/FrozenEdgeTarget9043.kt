package com.lifecyclebot.engine.truth

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** The existing managed-label recipe, versioned independently of today's lane policy. */
object FrozenEdgeTarget9043 {
    const val RECIPE = "MANAGED_8724_V1"
    const val DECISION_FROZEN = "DECISION_FROZEN"
    const val LEGACY_LABEL_TIME_STOP = "LEGACY_LABEL_TIME_STOP"
    const val LEGACY_RECOMPUTED = "LEGACY_RECOMPUTED"
    const val INVALID_RECEIPT = "INVALID_RECEIPT"

    data class Receipt(val inputNet: Double, val stopPct: Double, val costPct: Double,
                       val value: Double, val origin: String, val recipe: String = RECIPE) {
        /** Full precision: the dataset's legacy six-significant-digit columns cannot reproduce this value exactly. */
        fun fields9043(): String = ",\"targetRecipe9043\":" + JSONObject.quote(recipe) +
            ",\"targetOrigin9043\":" + JSONObject.quote(origin) +
            ",\"targetInputBasis9043\":\"EDGE_LEARN_ARGUMENT_8537\"" +
            ",\"targetInputNet9043\":$inputNet,\"targetStopPct9043\":$stopPct,\"targetCostPct9043\":$costPct" +
            ",\"trainingTarget9043\":$value"
    }

    /** This is the pre-9043 ManagedBands policy, not a new exit or return cap. */
    fun compute(netPct: Double, stopPct: Double, costPct: Double): Double {
        val stop = effectiveStop9043(stopPct)
        val cost = effectiveCost9043(costPct)
        val distance = (stop + cost).coerceAtMost(100.0)
        if (netPct <= -2.0 * distance) return netPct.coerceAtLeast(-100.0)
        return minOf(netPct, 400.0).coerceAtLeast(-distance)
    }

    fun effectiveStop9043(value: Double): Double = if (value.isFinite() && value > 0.0) value else 100.0
    private fun effectiveCost9043(value: Double): Double = if (value.isFinite() && value > 0.0) value else 0.0

    fun receipt9043(netPct: Double, stopPct: Double, costPct: Double, origin: String): Receipt? {
        if (!netPct.isFinite() || origin !in setOf(DECISION_FROZEN, LEGACY_LABEL_TIME_STOP)) return null
        val stop = effectiveStop9043(stopPct)
        val cost = effectiveCost9043(costPct)
        return Receipt(netPct, stop, cost, compute(netPct, stop, cost), origin)
    }

    private val fields = arrayOf("targetRecipe9043", "targetOrigin9043", "targetInputNet9043", "targetStopPct9043",
        "targetCostPct9043", "trainingTarget9043")

    fun hasReceipt9043(row: JSONObject): Boolean = fields.any { row.has(it) }

    private fun sameRounded9043(exact: Double, rounded: Double): Boolean = rounded.isFinite() &&
        abs(exact - rounded) <= max(0.0000001, abs(exact) * 0.0000051)

    /** Unknown or damaged new receipts never silently become legacy rows. */
    fun read9043(row: JSONObject): Receipt? {
        if (row.optString("targetRecipe9043") != RECIPE) return null
        if (row.has("labelRecipe9043")) {
            val recipe = ForwardLabelRecipe9043.decode9043(row.optJSONObject("labelRecipe9043")) ?: return null
            if (recipe.decidedAtMs != row.optLong("t")) return null
        }
        val origin = row.optString("targetOrigin9043")
        if (origin != DECISION_FROZEN && origin != LEGACY_LABEL_TIME_STOP) return null
        val input = row.optDouble("targetInputNet9043", Double.NaN)
        val stop = row.optDouble("targetStopPct9043", Double.NaN)
        val cost = row.optDouble("targetCostPct9043", Double.NaN)
        val target = row.optDouble("trainingTarget9043", Double.NaN)
        if (!input.isFinite() || !stop.isFinite() || stop <= 0.0 || !cost.isFinite() || cost < 0.0 || !target.isFinite()) return null
        if (!sameRounded9043(input, row.optDouble("net", Double.NaN)) ||
            !sameRounded9043(cost, effectiveCost9043(row.optDouble("cost", Double.NaN)))) return null
        val expected = compute(input, stop, cost)
        if (abs(target - expected) > max(0.000000000001, abs(expected) * 0.000000000001)) return null
        return Receipt(input, stop, cost, target, origin)
    }

    fun classification9043(row: JSONObject): String = when {
        !hasReceipt9043(row) -> LEGACY_RECOMPUTED
        else -> read9043(row)?.origin ?: INVALID_RECEIPT
    }

    /** A restored old pending decision must never be relabelled as having frozen its stop at decision time. */
    fun frozenStop9043(row: JSONObject): Double? = read9043(row)?.takeIf { it.origin == DECISION_FROZEN }?.stopPct
}
