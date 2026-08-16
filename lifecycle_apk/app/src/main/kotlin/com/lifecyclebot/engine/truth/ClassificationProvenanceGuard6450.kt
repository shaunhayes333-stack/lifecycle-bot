package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P1 — CLASSIFICATION PROVENANCE GUARD.
 *
 * OPERATOR MANDATE:
 *   "BLUECHIP-tagged executions are appearing with market caps around
 *    $134k-$300k. Never allow fallback mcap, default mcap, stale cached
 *    mcap, synthetic $50m placeholder, missing liquidity, missing
 *    supply, or symbol-name inference to satisfy BLUECHIP qualification.
 *
 *    If critical classification data is unknown: dataQuality=UNKNOWN
 *    and do NOT promote the token into a higher-quality cohort."
 *
 * DESIGN
 * ──────
 * Caller provides a Provenance record describing where each critical
 * classification field came from. `qualifyBluechip()` and
 * `qualifyQuality()` require CONFIRMED provenance and above-threshold
 * values. If any field is FALLBACK/CACHED/SYNTHETIC/MISSING/INFERRED,
 * the classification is refused and the token is downgraded to a
 * speculative lane (SHITCOIN by default). Never silently upgrades.
 */
object ClassificationProvenanceGuard6450 {

    enum class Provenance { CONFIRMED_FRESH, CACHED, FALLBACK, SYNTHETIC, MISSING, INFERRED }

    data class Evidence(
        val mint: String,
        val mcapUsd: Double,
        val mcapProvenance: Provenance,
        val liquiditySol: Double,
        val liquidityProvenance: Provenance,
        val supplyRaw: Double,
        val supplyProvenance: Provenance,
        val ageMinutes: Long,
        val ageProvenance: Provenance,
    )

    private const val BLUECHIP_MIN_MCAP_USD = 5_000_000.0
    private const val BLUECHIP_MIN_LIQ_SOL = 200.0
    private const val QUALITY_MIN_MCAP_USD = 500_000.0
    private const val QUALITY_MIN_LIQ_SOL = 50.0

    private val bluechipApproved = AtomicLong(0L)
    private val bluechipRefusedProvenance = AtomicLong(0L)
    private val bluechipRefusedThreshold = AtomicLong(0L)
    private val qualityApproved = AtomicLong(0L)
    private val qualityRefusedProvenance = AtomicLong(0L)
    private val qualityRefusedThreshold = AtomicLong(0L)

    private fun isConfirmed(p: Provenance): Boolean = p == Provenance.CONFIRMED_FRESH

    fun qualifyBluechip(e: Evidence): Boolean {
        val provenanceOK = isConfirmed(e.mcapProvenance) && isConfirmed(e.liquidityProvenance) &&
            isConfirmed(e.supplyProvenance) && isConfirmed(e.ageProvenance)
        if (!provenanceOK) {
            bluechipRefusedProvenance.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CLASSIFICATION_BLUECHIP_REFUSED_PROVENANCE_6450",
                    "mint=${e.mint.take(10)} mcapProv=${e.mcapProvenance} liqProv=${e.liquidityProvenance} " +
                        "supplyProv=${e.supplyProvenance} ageProv=${e.ageProvenance}",
                )
                PipelineHealthCollector.labelInc("CLASSIFICATION_BLUECHIP_REFUSED_PROVENANCE_6450")
            } catch (_: Throwable) {}
            return false
        }
        val thresholdOK = e.mcapUsd >= BLUECHIP_MIN_MCAP_USD && e.liquiditySol >= BLUECHIP_MIN_LIQ_SOL
        if (!thresholdOK) {
            bluechipRefusedThreshold.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "CLASSIFICATION_BLUECHIP_REFUSED_THRESHOLD_6450",
                    "mint=${e.mint.take(10)} mcapUsd=${"%.0f".format(e.mcapUsd)} liqSol=${"%.2f".format(e.liquiditySol)}",
                )
                PipelineHealthCollector.labelInc("CLASSIFICATION_BLUECHIP_REFUSED_THRESHOLD_6450")
            } catch (_: Throwable) {}
            return false
        }
        bluechipApproved.incrementAndGet()
        return true
    }

    fun qualifyQuality(e: Evidence): Boolean {
        val provenanceOK = isConfirmed(e.mcapProvenance) && isConfirmed(e.liquidityProvenance)
        if (!provenanceOK) {
            qualityRefusedProvenance.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CLASSIFICATION_QUALITY_REFUSED_PROVENANCE_6450") } catch (_: Throwable) {}
            return false
        }
        val thresholdOK = e.mcapUsd >= QUALITY_MIN_MCAP_USD && e.liquiditySol >= QUALITY_MIN_LIQ_SOL
        if (!thresholdOK) {
            qualityRefusedThreshold.incrementAndGet()
            try { PipelineHealthCollector.labelInc("CLASSIFICATION_QUALITY_REFUSED_THRESHOLD_6450") } catch (_: Throwable) {}
            return false
        }
        qualityApproved.incrementAndGet()
        return true
    }

    fun statusLine(): String = "bluechip=${bluechipApproved.get()}/refProv=${bluechipRefusedProvenance.get()}/" +
        "refThr=${bluechipRefusedThreshold.get()} quality=${qualityApproved.get()}/refProv=${qualityRefusedProvenance.get()}/" +
        "refThr=${qualityRefusedThreshold.get()}"
}
