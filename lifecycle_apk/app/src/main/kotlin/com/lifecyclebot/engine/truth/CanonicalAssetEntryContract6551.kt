package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ExecutableOpenGate
import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap

/** V5.0.6551 — typed cross-asset entry contract. */
data class CanonicalAssetEntryCandidate6551(
    val assetId: String,
    val symbol: String,
    val assetClass: AssetClass,
    val mode: String,
    val direction: String,
    val requestedVenue: String,
    val adapter: String,
    val source: String,
    val specialist: String,
    val score: Double,
    val confidence: Double,
    val evidence: Map<String, String> = emptyMap(),
    val requestedSizeSol: Double,
    val price: Double,
    val liquidityUsd: Double = 0.0,
    val routeAvailable: Boolean = true,
    val hardSafetyReasons: List<String> = emptyList(),
    val candidateVersion: Long,
    val diagnosticSignal: String = "UNKNOWN",
)

data class CanonicalAssetEntryShaping6551(
    val scorePenalty: Int = 0,
    val sizeMultiplier: Double = 1.0,
    val probe: Boolean = false,
    val reasons: List<String> = emptyList(),
)

sealed class CanonicalAssetEntryResult6551 {
    data class Allowed(
        val intent: ExecutableOpenGate.ExecutionIntent,
        val resolvedSizeSol: Double,
        val venue: String,
        val shaping: CanonicalAssetEntryShaping6551,
    ) : CanonicalAssetEntryResult6551()
    data class Probe(
        val intent: ExecutableOpenGate.ExecutionIntent,
        val resolvedSizeSol: Double,
        val venue: String,
        val shaping: CanonicalAssetEntryShaping6551,
    ) : CanonicalAssetEntryResult6551()
    data class Deferred(val reason: String) : CanonicalAssetEntryResult6551()
    data class Blocked(val reason: String) : CanonicalAssetEntryResult6551()
}

/**
 * The only pre-open cross-asset admission path. It owns the state transition
 * through sizing, FDG, and immutable intent sealing; callers may only dispatch
 * the returned intent.
 */
object CanonicalEntryAuthority6551 {
    private val pending = ConcurrentHashMap<String, ExecutableOpenGate.ExecutionIntent>()

    fun submit(candidate: CanonicalAssetEntryCandidate6551): CanonicalAssetEntryResult6551 {
        val venue = candidate.requestedVenue.ifBlank { candidate.assetClass.tag }
        CanonicalEntryAuthority6540.markCandidateFor6551(candidate.assetClass, candidate.symbol, venue)
        CanonicalEntryAuthority6540.markSubmitFor6551(candidate.assetClass, candidate.symbol, candidate.source)

        if (candidate.assetId.isBlank()) return blocked(candidate, venue, "INVALID_CANONICAL_ASSET_ID")
        if (!candidate.price.isFinite() || candidate.price <= 0.0)
            return blocked(candidate, venue, "INVALID_OR_STALE_PRICE")
        if (candidate.hardSafetyReasons.isNotEmpty())
            return blocked(candidate, venue, candidate.hardSafetyReasons.joinToString(","))
        if (candidate.mode.equals("LIVE", true) && !candidate.routeAvailable)
            return blocked(candidate, venue, "LIVE_ROUTE_UNAVAILABLE")
        if (candidate.direction.equals("SHORT", true) && candidate.assetClass != AssetClass.PERPS)
            return blocked(candidate, venue, "UNSUPPORTED_LIVE_DIRECTION")
        if (pending.keys.any { it.startsWith("${candidate.mode.uppercase()}:${candidate.assetId}:") })
            return blocked(candidate, venue, "DUPLICATE_CANONICAL_POSITION")

        val shaping = CanonicalAssetEntryShaping6551(
            scorePenalty = if (candidate.score < 0.0) 1 else 0,
            sizeMultiplier = if (candidate.confidence.isFinite()) candidate.confidence.coerceIn(0.35, 1.0) else 0.35,
            probe = candidate.score < 50.0 || candidate.confidence < 0.55,
            reasons = candidate.evidence.entries.take(4).map { "${it.key}=${it.value}" },
        )
        val shapedSize = candidate.requestedSizeSol * shaping.sizeMultiplier
        val sizing = OrderSizeResolver6441.resolve(
            requestedSol = shapedSize,
            laneName = candidate.specialist.ifBlank { candidate.assetClass.tag },
            walletSol = candidate.evidence["walletSol"]?.toDoubleOrNull() ?: Double.MAX_VALUE,
            paperMode = candidate.mode.equals("PAPER", true),
            laneRiskCapSol = candidate.evidence["laneRiskCapSol"]?.toDoubleOrNull() ?: OrderSizeResolver6441.DEFAULT_LANE_RISK_CAP_SOL,
            laneMinExecutableSol = candidate.evidence["laneMinExecutableSol"]?.toDoubleOrNull() ?: 0.001,
        )
        if (!sizing.executable) return blocked(candidate, venue, "SIZE_NOT_EXECUTABLE:${sizing.reason}")
        CanonicalEntryAuthority6540.markSizedFor6551(candidate.assetClass, candidate.symbol)

        val verdict = if (shaping.probe) "PROBE_ONLY" else "BUY"
        val attemptId = ExecutableOpenGate.canonicalExecutionKey(
            mint = candidate.assetId, mode = candidate.mode, side = if (candidate.direction.equals("SHORT", true)) "SELL" else "BUY",
            lane = candidate.specialist.ifBlank { candidate.assetClass.tag }, candidateVersion = candidate.candidateVersion,
        )
        val intent = ExecutableOpenGate.ExecutionIntent(
            attemptId = attemptId, candidateId = candidate.assetId, candidateVersion = candidate.candidateVersion,
            mint = candidate.assetId, mode = candidate.mode.uppercase(), canonicalLane = candidate.specialist.ifBlank { candidate.assetClass.tag },
            fdgVerdict = verdict, fdgAllowed = true, authorityVersion = 6551L,
            resolvedSize = sizing.finalSizeSol, createdAt = System.currentTimeMillis(), symbol = candidate.symbol,
            authoritativeSignal = "BUY", safetyVerdict = "CLEAR", fdgReason = "CANONICAL_FDG_6551",
            diagnosticSignal = candidate.diagnosticSignal, safetyTier = "CLEAR", liquidityUsd = candidate.liquidityUsd,
            hardNoReasons = emptyList(), requiresSolanaTokenMap = candidate.assetClass == AssetClass.SOLANA_TOKEN,
        )
        pending["${intent.mode}:${candidate.assetId}:${candidate.candidateVersion}"] = intent
        CanonicalEntryAuthority6540.markAuthAllowFor6551(candidate.assetClass, candidate.symbol)
        CanonicalEntryAuthority6540.markIntentCreatedFor6551(candidate.assetClass, candidate.symbol, intent.attemptId)
        try { ForensicLogger.lifecycle("CANONICAL_FDG_INTENT_SEALED_6551", "asset=${candidate.assetId.take(16)} class=${candidate.assetClass} verdict=$verdict") } catch (_: Throwable) {}
        val resultShaping = shaping.copy(sizeMultiplier = if (shaping.sizeMultiplier > 0.0) sizing.finalSizeSol / candidate.requestedSizeSol.coerceAtLeast(0.0000001) else 1.0)
        return if (shaping.probe) CanonicalAssetEntryResult6551.Probe(intent, sizing.finalSizeSol, venue, resultShaping)
        else CanonicalAssetEntryResult6551.Allowed(intent, sizing.finalSizeSol, venue, resultShaping)
    }

    fun findPending(assetId: String, mode: String, candidateVersion: Long? = null): ExecutableOpenGate.ExecutionIntent? {
        val prefix = "${mode.uppercase()}:$assetId:"
        return pending.entries.firstOrNull { it.key.startsWith(prefix) && (candidateVersion == null || it.value.candidateVersion == candidateVersion) }?.value
    }

    fun markDispatch(intent: ExecutableOpenGate.ExecutionIntent) {
        CanonicalEntryAuthority6540.markAdapterDispatchFor6551(AssetClass.fromLane(intent.canonicalLane), intent.symbol)
    }

    fun markConfirmed(intent: ExecutableOpenGate.ExecutionIntent, positionId: String) {
        pending.remove("${intent.mode}:${intent.mint}:${intent.candidateVersion}")
        CanonicalEntryAuthority6540.markOpenConfirmedFor6551(AssetClass.fromLane(intent.canonicalLane), intent.symbol, positionId)
    }

    private fun blocked(c: CanonicalAssetEntryCandidate6551, venue: String, reason: String): CanonicalAssetEntryResult6551.Blocked {
        CanonicalEntryAuthority6540.markAuthBlockFor6551(c.assetClass, c.symbol, reason)
        return CanonicalAssetEntryResult6551.Blocked(reason)
    }
}
