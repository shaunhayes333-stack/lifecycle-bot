package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
/**
 * V5.0.6147 — effective throughput cap self-relief.
 *
 * This is deliberately conservative: it never clears open/held positions, never
 * touches sell finality, and never changes hard safety. It only diagnoses the
 * true effective cap and allows callers to prune expired leases / stale ghosts
 * that are already non-held/non-pending.
 */
object ThroughputSelfRelief6147 {
    data class Verdict(
        val effectiveCap: Int,
        val activeLeases: Int,
        val openLike: Int,
        val pendingVerify: Int,
        val staleGhosts: Int,
        val pressure: String,
        val shouldPruneExpiredLeases: Boolean,
        val shouldPruneGhosts: Boolean,
        val reason: String,
    )

    fun evaluate(
        tokens: Map<String, TokenState>,
        effectiveCap: Int,
        activeLeases: Int,
        trades24h: Int,
        target24h: Int = 500,
        nowMs: Long = System.currentTimeMillis(),
    ): Verdict {
        val snap = try { tokens.values.toList() } catch (_: Throwable) { emptyList() }
        val openLike = snap.count { t ->
            val p = t.position
            p.isOpen || p.pendingVerify || p.qtyToken > 1e-12 || p.costSol > 0.000001
        }
        val pending = snap.count { it.position.pendingVerify }
        val staleGhosts = snap.count { t ->
            val p = t.position
            !p.isOpen && !p.pendingVerify && p.qtyToken <= 1e-12 && p.costSol <= 0.000001 &&
                t.addedToWatchlistAt > 0L && nowMs - t.addedToWatchlistAt > 30L * 60_000L
        }
        val underTarget = trades24h < target24h
        val capSaturated = activeLeases >= (effectiveCap * 0.90).toInt().coerceAtLeast(1)
        val pendingPressure = pending >= (effectiveCap / 3).coerceAtLeast(4)
        val ghostPressure = staleGhosts >= 32 && underTarget
        val pressure = when {
            capSaturated && underTarget -> "LEASE_CAP_SATURATED"
            pendingPressure && underTarget -> "PENDING_VERIFY_PRESSURE"
            ghostPressure -> "STALE_GHOST_PRESSURE"
            underTarget -> "UNDER_TARGET_NO_CAP_PRESSURE"
            else -> "CLEAR"
        }
        return Verdict(
            effectiveCap = effectiveCap,
            activeLeases = activeLeases,
            openLike = openLike,
            pendingVerify = pending,
            staleGhosts = staleGhosts,
            pressure = pressure,
            shouldPruneExpiredLeases = pressure == "LEASE_CAP_SATURATED" || pressure == "UNDER_TARGET_NO_CAP_PRESSURE",
            shouldPruneGhosts = ghostPressure,
            reason = "trades24h=$trades24h/$target24h cap=$effectiveCap leases=$activeLeases openLike=$openLike pending=$pending ghosts=$staleGhosts pressure=$pressure",
        )
    }
}
