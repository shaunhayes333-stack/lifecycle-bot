package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/** V5.0.4272 — compact event-local sizing attribution ledger. */
object MultiplierAttributionLedger {
    private const val MAX_ROWS = 500
    private val rows = ConcurrentLinkedDeque<Row>()

    data class Row(
        val timestampMs: Long,
        val buildTag: String,
        val mode: String,
        val lane: String,
        val source: String,
        val mint: String,
        val symbol: String,
        val baseSol: Double,
        val rawProduct: Double,
        val components: Map<String, Double>,
        val selectedLane: String = lane,
        val scoringLane: String = lane,
        val sizingLane: String = lane,
    )

    fun recordEntry(
        mode: String,
        lane: String,
        source: String,
        mint: String,
        symbol: String,
        baseSol: Double,
        rawProduct: Double,
        components: Map<String, Double>,
        selectedLane: String = lane,
        scoringLane: String = lane,
        sizingLane: String = lane,
    ) {
        try {
            val row = Row(
                timestampMs = System.currentTimeMillis(),
                buildTag = try { com.lifecyclebot.BuildConfig.VERSION_NAME } catch (_: Throwable) { "unknown" },
                mode = mode,
                lane = lane,
                source = source,
                mint = mint,
                selectedLane = selectedLane,
                scoringLane = scoringLane,
                sizingLane = sizingLane,
                symbol = symbol,
                baseSol = baseSol,
                rawProduct = rawProduct,
                components = components.mapValues { it.value.coerceIn(0.0, 10.0) },
            )
            rows.addLast(row)
            while (rows.size > MAX_ROWS) rows.pollFirst()
            val laneMissing = selectedLane.isBlank() || selectedLane.equals("UNKNOWN", true) || sizingLane.isBlank() || sizingLane.equals("UNKNOWN", true)
            if (laneMissing) {
                try {
                    PipelineHealthCollector.labelInc("LANE_ATTRIBUTION_MISSING")
                    ForensicLogger.lifecycle("LANE_ATTRIBUTION_MISSING", "mode=$mode selectedLane=$selectedLane scoringLane=$scoringLane sizingLane=$sizingLane lane=$lane source=$source mint=${mint.take(10)} action=preserve_candidate_no_standard_fallback")
                } catch (_: Throwable) {}
            }
            val hiddenDustStack = rawProduct < 0.20 && components.values.count { it < 0.80 } >= 2
            if (hiddenDustStack) {
                try {
                    val componentText = components.entries.joinToString(",") { it.key + "=" + it.value.fmt(3) }
                    PipelineHealthCollector.labelInc("MULTIPLIER_ATTRIBUTION_DUST_STACK_4272")
                    // V5.0.6358 — 30s per-(lane, mint) cooldown on the disk emit.
                    // Operator's V5.0.6308 dump showed this label firing 3-5 events
                    // per ms per lane during hot intake bursts. Label counter still
                    // fires every call so frequency data is preserved.
                    if (ForensicEmitRateLimiter6356.shouldEmit("MULTIPLIER_ATTRIBUTION_DUST_STACK_4272", "$lane|${mint.take(10)}")) {
                        ForensicLogger.lifecycle("MULTIPLIER_ATTRIBUTION_DUST_STACK_4272", "mode=$mode lane=$lane selectedLane=$selectedLane scoringLane=$scoringLane sizingLane=$sizingLane source=$source mint=${mint.take(10)} product=${rawProduct.fmt(3)} components=$componentText action=report_only_no_size_change")
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun exportState(): String = try {
        val arr = JSONArray()
        rows.toList().takeLast(MAX_ROWS).forEach { r ->
            val comps = JSONObject()
            r.components.forEach { (k, v) -> comps.put(k, v) }
            arr.put(JSONObject().apply {
                put("timestampMs", r.timestampMs)
                put("buildTag", r.buildTag)
                put("mode", r.mode)
                put("lane", r.lane)
                put("selectedLane", r.selectedLane)
                put("scoringLane", r.scoringLane)
                put("sizingLane", r.sizingLane)
                put("source", r.source)
                put("mint", r.mint)
                put("symbol", r.symbol)
                put("baseSol", r.baseSol)
                put("rawProduct", r.rawProduct)
                put("components", comps)
            })
        }
        JSONObject().put("rows", arr).toString()
    } catch (_: Throwable) { JSONObject().toString() }

    fun importState(json: String) {
        try {
            rows.clear()
            val arr = JSONObject(json).optJSONArray("rows") ?: return
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val compsObj = o.optJSONObject("components") ?: JSONObject()
                val comps = linkedMapOf<String, Double>()
                compsObj.keys().forEach { k -> comps[k] = compsObj.optDouble(k, 1.0) }
                rows.addLast(Row(
                    timestampMs = o.optLong("timestampMs", 0L),
                    buildTag = o.optString("buildTag", "unknown"),
                    mode = o.optString("mode", "unknown"),
                    lane = o.optString("lane", "UNKNOWN"),
                    source = o.optString("source", ""),
                    mint = o.optString("mint", ""),
                    selectedLane = o.optString("selectedLane", o.optString("lane", "UNKNOWN")),
                    scoringLane = o.optString("scoringLane", o.optString("lane", "UNKNOWN")),
                    sizingLane = o.optString("sizingLane", o.optString("lane", "UNKNOWN")),
                    symbol = o.optString("symbol", ""),
                    baseSol = o.optDouble("baseSol", 0.0),
                    rawProduct = o.optDouble("rawProduct", 1.0),
                    components = comps,
                ))
                while (rows.size > MAX_ROWS) rows.pollFirst()
            }
        } catch (_: Throwable) {}
    }

    fun reset() { rows.clear() }

    // V5.0.6362 — locale-free formatter. Old `"% .${d}f".replace(" ","").format(this)`
    // routed through java.util.Formatter which clones Locale.getDefault() on every
    // call. Under 30-50 emits/ms on the main thread that clone lock produced 25s
    // frame gaps in the operator snapshot. See [LocaleFreeFormat6362].
    private fun Double.fmt(d: Int): String = try { LocaleFreeFormat6362.fmt(this, d) } catch (_: Throwable) { this.toString() }
}
