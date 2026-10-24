package com.lifecyclebot.engine.cortex

import org.json.JSONObject
import java.math.BigDecimal

/**
 * The exact score semantics frozen at assessment time, shared by every runtime checkpoint.
 * This codec does not classify, recalibrate, re-bucket or infer a missing prediction. In particular,
 * a scalp's all-regime baseline and slope-free calibration input must not change after a restart.
 */
data class CortexAssessmentProvenance8372(
    val scoreVersion: Int,
    val rawEdge: Double,
    val laneMean: Double,
    val laneMeanAll: Double,
    val calibrationInput: Double,
    val calibratedEdge: Double,
    val assessmentAtMs: Long,
) {
    fun encode(): JSONObject = JSONObject()
        .put("scoreVersion", scoreVersion)
        .put("rawEdge", number(rawEdge))
        .put("laneMean", number(laneMean))
        .put("laneMeanAll", number(laneMeanAll))
        .put("calibrationInput", number(calibrationInput))
        .put("calibratedEdge", number(calibratedEdge))
        .put("assessmentAtMs", assessmentAtMs)

    companion object {
        private val values = listOf("rawEdge", "laneMean", "laneMeanAll", "calibrationInput", "calibratedEdge")
        private fun number(value: Double): Any = value.takeIf { it.isFinite() } ?: JSONObject.NULL
        private fun exactLong(raw: Any?): Long? {
            val numeric = raw as? Number ?: return null
            return try { BigDecimal(numeric.toString()).longValueExact() } catch (_: NumberFormatException) { null }
                catch (_: ArithmeticException) { null }
        }

        /** Null means absent/unverified provenance; the caller may preserve its explicit legacy fallback. */
        fun decode(root: JSONObject?): CortexAssessmentProvenance8372? {
            if (root == null || values.any { !root.has(it) }) return null
            val versionValue = exactLong(root.opt("scoreVersion")) ?: return null
            if (versionValue !in 0L..Int.MAX_VALUE.toLong()) return null
            val version = versionValue.toInt()
            val atMs = exactLong(root.opt("assessmentAtMs")) ?: return null
            if (atMs <= 0L) return null
            val decoded = DoubleArray(values.size)
            values.forEachIndexed { i, key ->
                val raw = root.opt(key)
                decoded[i] = if (raw === JSONObject.NULL) Double.NaN else {
                    val value = (raw as? Number)?.toDouble() ?: return null
                    if (!value.isFinite()) return null
                    value
                }
            }
            return CortexAssessmentProvenance8372(version, decoded[0], decoded[1], decoded[2], decoded[3], decoded[4], atMs)
        }
    }
}
