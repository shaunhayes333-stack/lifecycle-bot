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
    private const val PENDING_TTL_MS_6554 = 2 * 60 * 1000L
    private val pending = ConcurrentHashMap<String, ExecutableOpenGate.ExecutionIntent>()

    private fun expirePending6554() {
        val now = System.currentTimeMillis()
        pending.entries.removeIf { (_, intent) ->
            val expired = now - intent.createdAt > PENDING_TTL_MS_6554
            if (expired) try {
                ForensicLogger.lifecycle("CANONICAL_PENDING_EXPIRED", "attemptId=${intent.attemptId} asset=${intent.mint.take(16)} mode=${intent.mode}")
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_PENDING_EXPIRED")
            } catch (_: Throwable) {}
            expired
        }
    }

    fun submit(candidate: CanonicalAssetEntryCandidate6551): CanonicalAssetEntryResult6551 {
        expirePending6554()
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
        // V5.0.6565 — PAPER is a strategy/economics simulator and must be able
        // to model supported directional instruments even when a LIVE adapter
        // cannot place that order type. Keep the adapter limitation LIVE-only.
        if (candidate.mode.equals("LIVE", true) && candidate.direction.equals("SHORT", true) && candidate.assetClass != AssetClass.PERPS)
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
            applyPaperMemeMinimum = candidate.assetClass == AssetClass.SOLANA_TOKEN,
        )
        if (!sizing.executable) return blocked(candidate, venue, "SIZE_NOT_EXECUTABLE:${sizing.reason}")
        CanonicalEntryAuthority6540.markSizedFor6551(candidate.assetClass, candidate.symbol)

        val verdict = if (shaping.probe) "PROBE_ONLY" else "BUY"
        val attemptId = ExecutableOpenGate.canonicalExecutionKey(
            mint = candidate.assetId, mode = candidate.mode, side = "BUY",
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
            action = "OPEN", direction = if (candidate.direction.equals("SHORT", true)) "SHORT" else "LONG",
        )
        val registered = ExecutableOpenGate.registerCanonicalIntent6554(intent)
            ?: return deferred(candidate, venue, "EXEC_INTENT_REGISTRATION_FAILED")
        // A 6533 specialist may already have sealed this exact candidate.
        // Adopt it only when immutable identity and exact resolved size agree;
        // never mint a rival authority or silently debit a differently-sized lot.
        if (registered.mint != candidate.assetId || registered.candidateVersion != candidate.candidateVersion ||
            !registered.mode.equals(candidate.mode, true) ||
            kotlin.math.abs(registered.resolvedSize - sizing.finalSizeSol) > 1e-9) {
            return blocked(candidate, venue, "UPSTREAM_INTENT_CONFLICT")
        }
        pending["${registered.mode}:${candidate.assetId}:${candidate.candidateVersion}"] = registered
        CanonicalEntryAuthority6540.markAuthAllowFor6551(candidate.assetClass, candidate.symbol)
        CanonicalEntryAuthority6540.markIntentCreatedFor6551(candidate.assetClass, candidate.symbol, registered.attemptId)
        try {
            com.lifecyclebot.engine.ForensicLogger.phase(com.lifecyclebot.engine.ForensicLogger.PHASE.FDG, candidate.symbol, "path=${candidate.specialist.uppercase()} mode=${candidate.mode.uppercase()} verdict=$verdict sealed=true assetClass=${candidate.assetClass}")
            ForensicLogger.lifecycle("CANONICAL_FDG_INTENT_SEALED_6551", "asset=${candidate.assetId.take(16)} class=${candidate.assetClass} verdict=$verdict registered=true")
        } catch (_: Throwable) {}
        val resultShaping = shaping.copy(sizeMultiplier = if (shaping.sizeMultiplier > 0.0) sizing.finalSizeSol / candidate.requestedSizeSol.coerceAtLeast(0.0000001) else 1.0)
        return if (shaping.probe) CanonicalAssetEntryResult6551.Probe(registered, registered.resolvedSize, venue, resultShaping)
        else CanonicalAssetEntryResult6551.Allowed(registered, registered.resolvedSize, venue, resultShaping)
    }

    fun findPending(assetId: String, mode: String, candidateVersion: Long? = null): ExecutableOpenGate.ExecutionIntent? {
        expirePending6554()
        val prefix = "${mode.uppercase()}:$assetId:"
        return pending.entries.firstOrNull { it.key.startsWith(prefix) && (candidateVersion == null || it.value.candidateVersion == candidateVersion) }?.value
    }

    fun markDispatch(intent: ExecutableOpenGate.ExecutionIntent) {
        CanonicalEntryAuthority6540.markAdapterDispatchFor6551(AssetClass.fromLane(intent.canonicalLane), intent.symbol)
    }

    fun markConfirmed(intent: ExecutableOpenGate.ExecutionIntent, positionId: String) {
        pending.remove("${intent.mode}:${intent.mint}:${intent.candidateVersion}")
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_PENDING_CONFIRMED_RELEASE") } catch (_: Throwable) {}
        CanonicalEntryAuthority6540.markOpenConfirmedFor6551(AssetClass.fromLane(intent.canonicalLane), intent.symbol, positionId)
    }

    fun markFailed(intent: ExecutableOpenGate.ExecutionIntent, reason: String) = releasePending6554(intent, "FAILED", reason)
    fun markDeferred(intent: ExecutableOpenGate.ExecutionIntent, reason: String) = releasePending6554(intent, "DEFERRED", reason)
    fun markCancelled(intent: ExecutableOpenGate.ExecutionIntent, reason: String) = releasePending6554(intent, "CANCELLED", reason)

    private fun releasePending6554(intent: ExecutableOpenGate.ExecutionIntent, state: String, reason: String) {
        if (pending.remove("${intent.mode}:${intent.mint}:${intent.candidateVersion}") != null) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_PENDING_${state}_RELEASE")
                ForensicLogger.lifecycle("CANONICAL_PENDING_${state}_RELEASE", "attemptId=${intent.attemptId} asset=${intent.mint.take(16)} reason=${reason.take(120)}")
            } catch (_: Throwable) {}
        }
    }

    private fun deferred(c: CanonicalAssetEntryCandidate6551, venue: String, reason: String): CanonicalAssetEntryResult6551.Deferred {
        CanonicalEntryAuthority6540.markAuthBlockFor6551(c.assetClass, c.symbol, reason)
        return CanonicalAssetEntryResult6551.Deferred(reason)
    }

    private fun blocked(c: CanonicalAssetEntryCandidate6551, venue: String, reason: String): CanonicalAssetEntryResult6551.Blocked {
        CanonicalEntryAuthority6540.markAuthBlockFor6551(c.assetClass, c.symbol, reason)
        return CanonicalAssetEntryResult6551.Blocked(reason)
    }
}
