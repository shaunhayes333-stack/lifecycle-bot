package com.lifecyclebot.engine.truth

/**
 * V5.0.8535 §P1-11 — the model round trip and the measured one are reconciled per lane with their units named, and a
 * gap is called a gap only on enough measured trips. 8529 printed "BLUECHIP 4.42/10.89" with no n, mixing the model's
 * default-ticket all-in estimate with the lane's realised fill-to-exit median (which includes market drift). Pure.
 */
object CostReconciliation8535 {

    const val MIN_TRIPS_8535 = 3

    data class Row(val lane: String, val modelPct: Double, val measuredPct: Double?, val trips: Int) {
        val gapPct: Double? get() = if (measuredPct != null && trips >= MIN_TRIPS_8535 && modelPct.isFinite()) measuredPct - modelPct else null
        fun line(): String {
            val m = if (modelPct.isFinite()) "%.2f".format(modelPct) else "-"
            val r = measuredPct?.let { "%.2f".format(it) } ?: "-"
            val g = gapPct?.let { "%+.2f".format(it) } ?: if (trips in 1 until MIN_TRIPS_8535) "n<$MIN_TRIPS_8535" else "-"
            return "$lane:model=$m%/measured=$r%(n$trips)/gap=$g"
        }
    }

    fun line8535(rows: List<Row>): String =
        "costReconciliation8535{${rows.sortedBy { it.lane }.joinToString(",") { it.line() }.ifBlank { "-" }} " +
            "${com.lifecyclebot.engine.MetricTag8535.MODEL_VS_MARK_8535.line()}}"
}
