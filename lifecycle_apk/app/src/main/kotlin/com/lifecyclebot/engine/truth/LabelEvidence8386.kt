package com.lifecyclebot.engine.truth

/** Immutable source observation. readAtMs never substitutes for observedAtMs. */
data class LabelMark8386(val mint: String, val venue: String, val usd: Double, val observedAtMs: Long,
                         val readAtMs: Long, val source: String, val units: String = "USD_PER_TOKEN",
                         val decimals: Int = -1, val independentBasis: Boolean = false) {
    fun known(): Boolean = mint.isNotBlank() && usd.isFinite() && usd > 0 && observedAtMs > 0 &&
        observedAtMs <= readAtMs && units == "USD_PER_TOKEN" && source != "UNKNOWN"
    fun json(): org.json.JSONObject = org.json.JSONObject().put("mint", mint).put("venue", venue).put("usd", usd)
        .put("observed", observedAtMs).put("read", readAtMs).put("source", source).put("units", units)
        .put("decimals", decimals).put("independentBasis", independentBasis)
    companion object {
        fun decode(j: org.json.JSONObject): LabelMark8386 = LabelMark8386(j.getString("mint"), j.optString("venue"),
            j.getDouble("usd"), j.optLong("observed"), j.optLong("read"), j.optString("source", "UNKNOWN"),
            j.optString("units", "UNKNOWN"), j.optInt("decimals", -1), j.optBoolean("independentBasis"))
    }
}

data class LabelBasis8386(val mark: LabelMark8386, val capUsd: Double, val capDerived: Boolean = true) {
    fun rebase(mark: LabelMark8386): LabelBasis8386 = LabelBasis8386(mark,
        if (this.mark.usd > 0 && capUsd > 0) capUsd * mark.usd / this.mark.usd else 0.0, true)
    fun json(): org.json.JSONObject = org.json.JSONObject().put("mark", mark.json()).put("cap", capUsd).put("capDerived", capDerived)
    companion object {
        fun decode(j: org.json.JSONObject) = LabelBasis8386(LabelMark8386.decode(j.getJSONObject("mark")), j.optDouble("cap", 0.0), j.optBoolean("capDerived", true))
    }
}

object LabelEvidence8386 {
    fun status(entry: LabelMark8386?, outcome: LabelMark8386?): String {
        if (entry == null || outcome == null || !entry.known() || !outcome.known()) return "OBSERVED_ONLY_UNKNOWN_BASIS"
        if (entry.mint != outcome.mint || entry.units != outcome.units) return "OBSERVED_ONLY_IDENTITY_UNITS"
        if (entry.decimals >= 0 && outcome.decimals >= 0 && entry.decimals != outcome.decimals) return "OBSERVED_ONLY_DECIMALS"
        if (outcome.observedAtMs < entry.observedAtMs) return "OBSERVED_ONLY_TIME_ORDER"
        val ratio = outcome.usd / entry.usd
        // V5.0.8418 §EXTREME_MOVES_TRAIN — a <= 0.1x move (a rug, a dead coin) is always booked: identity, units, decimals and
        // time order were checked above, and dropping the downside taught the books that rugs never happen.
        // superseded by V5.0.8418 — 8386 "(ratio >= 10.0 || ratio <= 0.1) && !(both independentBasis) -> UNWITNESSED_EXTREME".
        if (ratio <= 0.1) return "VALID_8386"
        // A >= 10x move needs both marks chain-provable ([independent8418]: curve account / curve tape print / parsed swap /
        // pool reserve read). A proof requirement, never a cap: a witnessed 100x / 1000x trains at its full value.
        if (ratio >= 10.0 && !(independent8418(entry) && independent8418(outcome))) return "OBSERVED_ONLY_UNWITNESSED_EXTREME"
        return "VALID_8386"
    }
    fun eligible(entry: LabelMark8386?, outcome: LabelMark8386?) = status(entry, outcome) == "VALID_8386"

    /**
     * Pure (V5.0.8418): is [source] an on-chain observation of the price itself? The curve account read (CURVE_ACCOUNT), the
     * curve tape (CURVE_FRAME: a trade print carrying the post-trade reserves), Helius parsed swaps, a trade tape, and a pool
     * reserve read. Aggregator / pair-API / loop prices (DEX, JUPITER, BIRDEYE, OFFWATCH, TOKEN_STATE) are not.
     */
    fun independentSource8418(source: String?): Boolean {
        val s = source.orEmpty().uppercase()
        if (s.isBlank() || s == "UNKNOWN" || s.endsWith("_UNKNOWN")) return false
        // A quote or an API price is never a print, whatever its name says ("JUPITER_SWAP" is a route quote).
        if (s.contains("JUPITER") || s.contains("QUOTE") || s.contains("BIRDEYE") || s.contains("DEXSCREENER") ||
            s.contains("GECKO") || s.contains("ESTIMATE") || s.contains("DERIVED")) return false
        if (s == "CURVE_ACCOUNT" || s == "CURVE_FRAME") return true
        return s.contains("HELIUS") || s.contains("SWAP") || s.contains("TAPE") || s.contains("POOL_RESERVE") ||
            s.contains("RESERVES") || s.contains("ONCHAIN") || s.contains("ON_CHAIN")
    }

    /** Pure (V5.0.8418): a mark carries chain proof — stamped at capture, or from an [independentSource8418] source. */
    fun independent8418(m: LabelMark8386?): Boolean = m != null && (m.independentBasis || independentSource8418(m.source))
}
