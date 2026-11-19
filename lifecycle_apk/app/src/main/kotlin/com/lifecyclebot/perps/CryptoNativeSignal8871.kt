package com.lifecyclebot.perps

import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CanonicalAssetEntryCandidate6551

/** Carries an already-fired classic signal. It grants neither a statistical edge nor execution permission. */
object CryptoNativeSignal8871 {
    data class Witness(
        val assetId: String,
        val strategy: String,
        val direction: String,
        val candidateVersion: Long,
        val score: Int,
        val scoreFloor: Int,
        val confidence: Int,
        val confidenceFloor: Int,
        val qualifiedAtMs: Long,
        val signalPrice: Double,
        val signalObservedAtMs: Long,
    )

    private val CLASSIC_STRATEGIES = setOf("MOMENTUM", "PULLBACK", "BREAKOUT", "MEAN_REVERT")
    data class Qualification internal constructor(val witness: Witness, val validatedAtMs: Long)

    /** The actual classic producer supplies its existing floors; a desk overlay cannot acquire this witness. */
    internal fun capture(assetId: String, strategy: String, direction: String, candidateVersion: Long,
                         score: Int, scoreFloor: Int, confidence: Int, confidenceFloor: Int,
                         qualifiedAtMs: Long, signalPrice: Double, signalObservedAtMs: Long): Witness? =
        Witness(assetId, strategy, direction, candidateVersion, score, scoreFloor, confidence, confidenceFloor,
            qualifiedAtMs, signalPrice, signalObservedAtMs).takeIf { validSignal(it, qualifiedAtMs) }

    private fun validSignal(w: Witness, nowMs: Long): Boolean =
        w.assetId.isNotBlank() && w.strategy in CLASSIC_STRATEGIES && w.direction == "LONG" &&
            w.candidateVersion > 0L && w.scoreFloor in 1..100 && w.confidenceFloor in 1..100 &&
            w.score in w.scoreFloor..100 && w.confidence in w.confidenceFloor..100 &&
            w.qualifiedAtMs > 0L && nowMs - w.qualifiedAtMs in 0L..CryptoStrategyCandidateBooks7803.READY_TTL_MS &&
            w.signalPrice.isFinite() && w.signalPrice > 0.0 && w.signalObservedAtMs in 1L..w.qualifiedAtMs &&
            !CandidateAdmission8136.priceStale8136(nowMs - w.signalObservedAtMs, false, true,
                CandidateAdmission8136.feedPeriodMs8855("CRYPTO_ALT"))

    internal fun validate(c: CanonicalAssetEntryCandidate6551, nowMs: Long,
                          ready: (String, Long) -> List<CryptoStrategyCandidateBooks7803.Entry> =
                              CryptoStrategyCandidateBooks7803::readyFor): Qualification? {
        val w = c.cryptoNativeSignal8871 ?: return null
        if (c.assetClass != AssetClass.CRYPTO_ALT || !c.direction.equals("LONG", true) ||
            c.evidence["strategy7803"] != w.strategy || c.candidateVersion != w.candidateVersion ||
            !DynamicAltTokenRegistry.sameAsset8866(c.assetId, w.assetId) || c.score != w.score.toDouble() ||
            c.evidence["upstreamConfidence"]?.toIntOrNull() != w.confidence || !validSignal(w, nowMs)) return null
        val row = ready(w.assetId, w.candidateVersion).firstOrNull { it.strategy == w.strategy } ?: return null
        return w.takeIf {
            row.state == CryptoStrategyCandidateBooks7803.State.READY &&
                row.assetKey == w.assetId && row.candidateVersion == w.candidateVersion &&
                row.score == w.score && row.confidence == w.confidence &&
                row.updatedAtMs > 0L && nowMs - row.updatedAtMs in 0L..CryptoStrategyCandidateBooks7803.READY_TTL_MS
        }?.let { Qualification(it, nowMs) }
    }

    internal fun applies(q: Qualification, mint: String, version: Long, nowMs: Long): Boolean =
        q.validatedAtMs in 1L..nowMs && validSignal(q.witness, nowMs) &&
            q.witness.candidateVersion == version && DynamicAltTokenRegistry.sameAsset8866(mint, q.witness.assetId)

    /** Preserve the signal generation through canonical sealing; legacy callers retain their existing version. */
    internal fun candidateVersion(signalVersion: Long, legacyVersion: () -> Long): Long =
        signalVersion.takeIf { it > 0L } ?: legacyVersion()
}
