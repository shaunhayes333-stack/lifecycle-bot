package com.lifecyclebot.engine.truth

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Research provenance is not the original trading objection, and a finite number is not price proof. */
object ResearchEvidence9043 {
    enum class Quality { PRICED, UNPRICED, QUARANTINED, LATE, UNREADABLE }
    data class Outcome(val quality: Quality, val net: Double, val originalReason: String, val detail: String = "") {
        val cohortEligible: Boolean get() = quality == Quality.PRICED || quality == Quality.UNPRICED || quality == Quality.QUARANTINED
    }

    private val readPrefixes = listOf("NO_PRICE_JUP_8884:", "JUP_8884:", "DEX_9015:", "CHAIN_8993:",
        "LATE_READ_8884:", "IMPLAUSIBLE_RATIO_8923:", "QUARANTINED_9043:", "LOSTMARK8983/")

    /** Legacy rows remain byte-for-byte intact. Only known observation wrappers are removed. */
    fun originalReason9043(reason: String): String {
        var result = reason.trim()
        repeat(12) {
            val prefix = readPrefixes.firstOrNull { result.startsWith(it) } ?: return result
            result = result.removePrefix(prefix)
        }
        return result
    }

    fun originalReason9043(row: JSONObject): String = originalReason9043(
        row.optString("originalReason9043").takeIf { it.isNotBlank() } ?: row.optString("reason"))

    private fun number(row: JSONObject, key: String): Double =
        if (row.isNull(key)) Double.NaN else row.optDouble(key, Double.NaN)

    /** Larger moves are preserved at full value when their persisted typed marks substantiate them. */
    private fun verifiedExtreme9043(row: JSONObject, largestPct: Double): Boolean {
        val proof = row.optJSONObject("priceProof9043") ?: return false
        return try {
            if (proof.optInt("version") != 9043) return false
            val entry = LabelMark8386.decode(proof.getJSONObject("entry"))
            val outcome = LabelMark8386.decode(proof.getJSONObject("outcome"))
            val peak = proof.optJSONObject("peak")?.let { LabelMark8386.decode(it) }
            val migrated = proof.optBoolean("provenMigration", false)
            if (entry.mint != row.optString("mint") || !LabelEvidence8386.extremePairProven9043(entry, outcome, migrated)) return false
            if (peak != null && !LabelEvidence8386.extremePairProven9043(entry, peak, migrated)) return false
            val supportedPct = (max(outcome.usd, peak?.usd ?: outcome.usd) / entry.usd - 1.0) * 100.0
            supportedPct.isFinite() && largestPct <= supportedPct + max(0.001, abs(supportedPct) * 0.00001)
        } catch (_: Exception) { false }
    }

    /** Shared by live append, retained history, seeds, authority and every research-pocket reader. */
    fun read9043(row: JSONObject): Outcome {
        val raw = row.optString("reason")
        val original = originalReason9043(row)
        if (raw.startsWith("LATE_READ_8884:") || row.optString("quality9043") == Quality.LATE.name)
            return Outcome(Quality.LATE, Double.NaN, original, "OUTSIDE_HORIZON")
        if (row.optString("quality9043") == Quality.QUARANTINED.name)
            return Outcome(Quality.QUARANTINED, Double.NaN, original, row.optString("qualityDetail9043", "PREVIOUSLY_QUARANTINED"))
        if (raw.startsWith("IMPLAUSIBLE_RATIO_8923:") || raw.startsWith("QUARANTINED_9043:"))
            return Outcome(Quality.QUARANTINED, Double.NaN, original, "INVALID_PRICE_RATIO")
        if (raw.startsWith("NO_PRICE_JUP_8884:")) return Outcome(Quality.UNPRICED, Double.NaN, original, "NO_PRICED_OUTCOME")
        if (row.has("labelRecipe9043")) {
            val recipe = ForwardLabelRecipe9043.decode9043(row.optJSONObject("labelRecipe9043"))
            if (recipe == null || recipe.decidedAtMs != row.optLong("t"))
                return Outcome(Quality.QUARANTINED, Double.NaN, original, "INVALID_LABEL_RECIPE")
        }
        val net = number(row, "net")
        if (!net.isFinite()) {
            val quality = when (row.optString("quality9043")) {
                Quality.UNPRICED.name -> Quality.UNPRICED
                Quality.QUARANTINED.name -> Quality.QUARANTINED
                else -> if (row.has("net") && row.isNull("net")) Quality.UNPRICED else Quality.UNREADABLE
            }
            return Outcome(quality, Double.NaN, original, "NO_FINITE_OUTCOME")
        }
        val gross = number(row, "gross")
        if (gross.isFinite() && gross < -100.00001)
            return Outcome(Quality.QUARANTINED, Double.NaN, original, "IMPOSSIBLE_SPOT_PRICE_RATIO")
        val largest = listOf(net, gross, number(row, "peakPct"), number(row, "heldPeakPct"))
            .filter { it.isFinite() }.maxOrNull() ?: net
        if (largest > (LabelEvidence8386.EXTREME_PROOF_RATIO_9043 - 1.0) * 100.0 && !verifiedExtreme9043(row, largest))
            return Outcome(Quality.QUARANTINED, Double.NaN, original, "EXTREME_WITHOUT_TYPED_PRICE_PROOF")
        return Outcome(Quality.PRICED, net, original)
    }

    /** Add provenance without rewriting the ordered t/horizon head used by streaming readers. */
    fun annotate9043(line: String): String {
        val row = JSONObject(line)
        if (row.has("quality9043") && row.has("originalReason9043")) return line
        val read = read9043(row)
        val fields = ArrayList<String>(4)
        if (!row.has("originalReason9043")) fields.add("\"originalReason9043\":" + JSONObject.quote(read.originalReason))
        if (!row.has("readReason9043")) fields.add("\"readReason9043\":" + JSONObject.quote(row.optString("reason")))
        if (!row.has("quality9043")) fields.add("\"quality9043\":" + JSONObject.quote(read.quality.name))
        if (read.detail.isNotBlank() && !row.has("qualityDetail9043")) fields.add("\"qualityDetail9043\":" + JSONObject.quote(read.detail))
        return line.trimEnd().dropLast(1) + "," + fields.joinToString(",") + "}"
    }

    /** A proof is a pair of typed, explicitly witnessed marks; a source-name string alone is insufficient. */
    fun priceProof9043(entry: LabelMark8386?, outcome: LabelMark8386?, peak: LabelMark8386? = null,
                       provenMigration: Boolean = false): String {
        if (entry == null || outcome == null || !LabelEvidence8386.extremePairProven9043(entry, outcome, provenMigration)) return ""
        val proof = JSONObject().put("version", 9043).put("entry", entry.json()).put("outcome", outcome.json())
            .put("provenMigration", provenMigration)
        if (peak != null && LabelEvidence8386.extremePairProven9043(entry, peak, provenMigration)) proof.put("peak", peak.json())
        return proof.toString()
    }
}
