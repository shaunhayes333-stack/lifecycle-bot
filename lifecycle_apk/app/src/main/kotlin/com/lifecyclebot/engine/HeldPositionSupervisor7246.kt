package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AssetClass
import com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import com.lifecyclebot.engine.truth.MarkIdentityExecutionGate7230
import com.lifecyclebot.perps.DynamicAltTokenRegistry

/**
 * V5.0.7246 — canonical HELD POSITION SUPERVISOR.
 *
 * Discovery owns UNOWNED candidates only. The instant a canonical position is
 * OPEN/PARTIALLY_CLOSED, ownership moves here until economic close.
 *
 * This object is deliberately read/cache oriented: it does not create a second
 * trader or provider universe. Existing exit/mark workers consume its held
 * roster; discovery uses [isHeld] to bypass already-owned assets before
 * watchlist/safety/V3/lane/FDG fan-out.
 */
object HeldPositionSupervisor7246 {

    data class HeldRow(
        val positionId: String,
        val mint: String,
        val symbol: String,
        val mode: String,
        val lane: String,
        val assetClass: AssetClass,
        val openedAtMs: Long,
        val entryPriceUsd: Double,
        val currentPriceUsd: Double,
        val markAgeMs: Long,
        val markSource: String,
        val markState: String,
        val remainingQty: Double,
    )

    private const val FRESH_MS = 60_000L

    fun isHeld(mintOrAssetKey: String): Boolean {
        if (mintOrAssetKey.isBlank()) return false
        return try {
            CanonicalPositionAuthority6441.openPositions().any {
                it.mint.equals(mintOrAssetKey, ignoreCase = true)
            }
        } catch (_: Throwable) { false }
    }

    /** Held-only roster for the existing Solana mark-refresh worker. */
    fun solanaHeldPositions(): List<CanonicalPositionAuthority6441.Position> = try {
        CanonicalPositionAuthority6441.openPositions().filter {
            it.assetClass == AssetClass.SOLANA_TOKEN
        }
    } catch (_: Throwable) { emptyList() }

    /**
     * Recovery/startup reconciliation: canonical OPEN ownership wins even when
     * the position existed before this process and therefore did not execute the
     * fresh-open handoff hook in this runtime.
     */
    fun reconcileDiscoveryResidency(): Int {
        val open = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
        var released = 0
        for (p in open) {
            try {
                if (GlobalTradeRegistry.handoffOpenMintToHeld7246(p.mint, p.symbol)) released++
            } catch (_: Throwable) {}
        }
        if (released > 0) try {
            PipelineHealthCollector.labelInc("HELD_RECOVERY_DISCOVERY_RELEASE_7246")
            ForensicLogger.lifecycle(
                "HELD_RECOVERY_DISCOVERY_RELEASE_7246",
                "released=$released canonicalOpen=${open.size} action=canonical_ownership_wins_on_restart",
            )
        } catch (_: Throwable) {}
        return released
    }

    fun statusLine(nowMs: Long = System.currentTimeMillis()): String {
        val rows = snapshot(nowMs)
        val fresh = rows.count { it.markState == "FRESH" }
        val stale = rows.count { it.markState == "STALE_REFRESH" }
        val missing = rows.count { it.markState == "MISSING" }
        val suppressed = rows.count { it.markState == "SUPPRESSED_REFRESH" }
        val discoveryLeaks = rows.count { row ->
            try { GlobalTradeRegistry.getEntry(row.mint) != null } catch (_: Throwable) { false }
        }
        return "held=${rows.size} fresh=$fresh staleRefresh=$stale missing=$missing suppressedRefresh=$suppressed discoveryResident=$discoveryLeaks"
    }

    /** UI/telemetry projection. Cache-only: never blocks on a provider. */
    fun snapshot(nowMs: Long = System.currentTimeMillis()): List<HeldRow> {
        val open = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
        return open.map { p ->
            var px = 0.0
            var ts = 0L
            var source = ""

            fun consider(candidatePx: Double, candidateTs: Long, candidateSource: String) {
                if (candidatePx.isFinite() && candidatePx > 0.0 && candidateTs >= ts) {
                    px = candidatePx
                    ts = candidateTs
                    source = candidateSource
                }
            }

            if (p.assetClass == AssetClass.CRYPTO_ALT) {
                val dyn = try {
                    DynamicAltTokenRegistry.getTokenByCanonicalIdentity6544(p.mint)
                        ?: DynamicAltTokenRegistry.getTokenByMint(p.mint)
                } catch (_: Throwable) { null }
                if (dyn != null && dyn.price.isFinite() && dyn.price > 0.0) {
                    consider(dyn.price, dyn.lastUpdatedMs, dyn.source.ifBlank { "CRYPTO_REGISTRY" })
                }
            }

            val mark = try {
                CanonicalPriceMarkRegistry6522.get(p.mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC)
                    ?: CanonicalPriceMarkRegistry6522.get(p.mint)
            } catch (_: Throwable) { null }
            if (mark != null) {
                consider(
                    try { mark.priceUsd.value.toDouble() } catch (_: Throwable) { 0.0 },
                    mark.timestampMs,
                    mark.source,
                )
            }

            // V5.0.7247 — the canonical exit worker projects fresh executable
            // marks into BotService.status.tokens. 7246 ignored that surface and
            // reported 12 MISSING while the same report's exit feed had only 3.
            // Read it cache-only; do not create a second provider/refresh loop.
            val runtimeToken = try {
                synchronized(BotService.status.tokens) {
                    BotService.status.tokens[p.mint]
                        ?: BotService.status.tokens.values.firstOrNull {
                            it.mint.equals(p.mint, ignoreCase = true)
                        }
                }
            } catch (_: Throwable) { null }
            if (runtimeToken != null) {
                consider(
                    runtimeToken.lastPrice,
                    runtimeToken.lastPriceUpdate,
                    runtimeToken.lastPriceSource.ifBlank { runtimeToken.source.ifBlank { "EXIT_TOKEN_STATE" } },
                )
            }

            val age = if (ts > 0L) (nowMs - ts).coerceAtLeast(0L) else Long.MAX_VALUE
            val executionSuppressed = try {
                MarkIdentityExecutionGate7230.isExecutionSuppressed7243(p.mint)
            } catch (_: Throwable) { false }
            val state = when {
                executionSuppressed -> "SUPPRESSED_REFRESH"
                px <= 0.0 -> "MISSING"
                age <= FRESH_MS -> "FRESH"
                else -> "STALE_REFRESH"
            }
            val qty = try {
                p.remainingQtyRaw.toBigDecimal().movePointLeft(p.quantityScale).toDouble()
            } catch (_: Throwable) { 0.0 }

            HeldRow(
                positionId = p.positionId,
                mint = p.mint,
                symbol = p.symbol.ifBlank { p.mint.take(8) },
                mode = p.mode,
                lane = p.lane,
                assetClass = p.assetClass,
                openedAtMs = p.openedAtMs,
                entryPriceUsd = p.entryPriceUsd,
                currentPriceUsd = px,
                markAgeMs = age,
                markSource = source,
                markState = state,
                remainingQty = qty,
            )
        }.sortedWith(
            compareBy<HeldRow> {
                when (it.markState) {
                    "SUPPRESSED_REFRESH" -> 0
                    "MISSING" -> 0
                    "STALE_REFRESH" -> 1
                    else -> 2
                }
            }.thenByDescending { it.openedAtMs }
        )
    }
}
