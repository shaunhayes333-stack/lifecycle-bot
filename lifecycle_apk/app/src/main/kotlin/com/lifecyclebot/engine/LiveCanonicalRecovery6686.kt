package com.lifecyclebot.engine

import com.lifecyclebot.data.BotStatus
import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import com.lifecyclebot.engine.truth.CanonicalTokenAmount
import java.math.BigInteger

/**
 * V5.0.6686 — wallet-positive -> canonical LIVE recovery bridge.
 *
 * This does NOT invent an entry basis. A wallet mint is promoted into canonical
 * LIVE authority only when the existing runtime position, persisted position,
 * or finalized canonical buy fill proves a positive cost + entry price.
 * Unknown-basis wallet rows remain visible to HostWalletTokenTracker recovery
 * and are never made trainable by this bridge.
 */
object LiveCanonicalRecovery6686 {
    const val VERSION = "V5.0.6686_LIVE_CANONICAL_RECOVERY"

    private data class Basis(
        val entryCostSol: Double,
        val entryPriceUsd: Double,
        val lane: String,
        val openedAtMs: Long,
        val source: String,
        val pool: String,
        val dex: String,
        val identity: String,
    )

    fun recoverWalletSnapshot(
        status: BotStatus,
        walletMints: Map<String, CanonicalTokenAmount>,
    ): Int {
        if (walletMints.isEmpty()) return 0
        val existingLive = try {
            CanonicalPositionAuthority6441.activeMintProjections6490("live")
                .map { it.mint }.toMutableSet()
        } catch (_: Throwable) { mutableSetOf<String>() }
        val persisted = try { PositionPersistence.loadPositions() } catch (_: Throwable) { emptyMap() }
        var repaired = 0

        for ((mint, amount) in walletMints) {
            if (mint.isBlank() || amount.raw <= BigInteger.ONE || existingLive.contains(mint)) continue
            val ts = try { status.tokens[mint] } catch (_: Throwable) { null }
            val runtimePos = ts?.position
            val saved = persisted[mint]

            val basis: Basis? = when {
                runtimePos != null && !runtimePos.isPaperPosition &&
                    runtimePos.costSol.isFinite() && runtimePos.costSol > 0.0 &&
                    runtimePos.entryPrice.isFinite() && runtimePos.entryPrice > 0.0 -> Basis(
                        entryCostSol = runtimePos.costSol,
                        entryPriceUsd = runtimePos.entryPrice,
                        lane = runtimePos.tradingMode.ifBlank { "WALLET_RECOVERED" },
                        openedAtMs = runtimePos.entryTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        source = runtimePos.entryPriceSource.ifBlank { "RUNTIME_POSITION_BASIS_6686" },
                        pool = runtimePos.entryPoolAddress,
                        dex = runtimePos.entryDex,
                        identity = runtimePos.positionId.ifBlank { "runtime" },
                    )

                saved != null && !saved.isPaperPosition &&
                    saved.costSol.isFinite() && saved.costSol > 0.0 &&
                    saved.entryPrice.isFinite() && saved.entryPrice > 0.0 -> Basis(
                        entryCostSol = saved.costSol,
                        entryPriceUsd = saved.entryPrice,
                        lane = saved.tradingMode.ifBlank { "WALLET_RECOVERED" },
                        openedAtMs = saved.entryTime.takeIf { it > 0L } ?: System.currentTimeMillis(),
                        source = saved.entryPriceSource.ifBlank { "PERSISTED_POSITION_BASIS_6686" },
                        pool = saved.entryPoolAddress,
                        dex = saved.entryDex,
                        identity = "persisted:${saved.savedAt}",
                    )

                else -> {
                    val fill = try { CanonicalBuyFillRegistry.get(mint) } catch (_: Throwable) { null }
                    if (fill != null && fill.solSpentNet.isFinite() && fill.solSpentNet > 0.0) {
                        val usd = when {
                            fill.entryPriceUsd.isFinite() && fill.entryPriceUsd > 0.0 -> fill.entryPriceUsd
                            fill.entryPriceSol.isFinite() && fill.entryPriceSol > 0.0 -> {
                                val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
                                if (solUsd > 0.0) fill.entryPriceSol * solUsd else 0.0
                            }
                            else -> 0.0
                        }
                        if (usd > 0.0) Basis(
                            entryCostSol = fill.solSpentNet,
                            entryPriceUsd = usd,
                            lane = fill.lane.ifBlank { "WALLET_RECOVERED" },
                            openedAtMs = fill.entryTsMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                            source = "CANONICAL_BUY_FILL_RECOVERY_6686",
                            pool = "",
                            dex = "",
                            identity = fill.buySignature.ifBlank { "fill" },
                        ) else null
                    } else null
                }
            }

            if (basis == null) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686",
                        "mint=${mint.take(12)} raw=${amount.raw} decimals=${amount.decimals} action=retain_wallet_tracking_no_invented_basis",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686")
                } catch (_: Throwable) {}
                continue
            }

            val safeIdentity = basis.identity.replace(Regex("[^A-Za-z0-9]"), "").takeLast(14).ifBlank { "basis" }
            val positionId = runtimePos?.positionId?.takeIf { it.isNotBlank() }
                ?: "LIVE_RECOVERED_6686:${mint.take(16)}:$safeIdentity"
            val result = try {
                CanonicalPositionAuthority6441.openPosition(
                    idempotencyKey = "LIVE_WALLET_CANONICAL_RECOVERY_6686:$mint:$safeIdentity",
                    positionId = positionId,
                    mint = mint,
                    symbol = ts?.symbol?.ifBlank { mint.take(8) } ?: mint.take(8),
                    lane = basis.lane,
                    runId = "RECOVERY_6686",
                    entryCostSol = basis.entryCostSol,
                    openedQtyRaw = amount.raw,
                    tokenDecimals = amount.decimals,
                    feesSol = 0.0,
                    paperMode = false,
                    modeOverride = "live",
                    entryPriceUsd = basis.entryPriceUsd,
                    entryPriceSource = basis.source,
                    entryPoolAddress = basis.pool,
                    entryDex = basis.dex,
                    quantityScale = amount.decimals,
                )
            } catch (_: Throwable) { CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION }

            if (result == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                existingLive.add(mint)
                repaired++
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_POSITION_RECOVERED_6686",
                        "mint=${mint.take(12)} pid=${positionId.take(28)} lane=${basis.lane} raw=${amount.raw} decimals=${amount.decimals} cost=${basis.entryCostSol} source=${basis.source}",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_POSITION_RECOVERED_6686")
                } catch (_: Throwable) {}
            } else if (result != CanonicalPositionAuthority6441.MutateResult.DUPLICATE) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_REJECTED_6686",
                        "mint=${mint.take(12)} result=$result action=retain_wallet_tracking",
                    )
                } catch (_: Throwable) {}
            }
        }
        return repaired
    }
}
