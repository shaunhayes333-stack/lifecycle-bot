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
                    val fromFill7126: Basis? = if (fill != null && fill.solSpentNet.isFinite() && fill.solSpentNet > 0.0) {
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
                    // V5.0.7126 — THE DURABLE LEDGER IS THE FOURTH SOURCE, AND IT
                    // WAS NEVER CONSULTED.
                    //
                    // Operator: "just make sure any buy recorded live in the bot is
                    // displayed in the open position panels by the system that
                    // bought them. you can see the held token metrics via the
                    // ledger, rebuild the position and update them on update
                    // install."
                    //
                    // The three sources above are the runtime position (lost on
                    // process death), the persisted position (lost when the write
                    // did not land) and CanonicalBuyFillRegistry (an in-session
                    // cache: the device read CANONICAL_BUY_FILL_RECORDED_6320=2
                    // against EXEC_LIVE_BUY_OK=20). All three are volatile, so
                    // after a restart or an APK update a genuinely bought, still
                    // held token had no recoverable basis and this bridge skipped
                    // it — LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686=159.
                    //
                    // FillLotLedger6504 is the one durable, insert-only record of
                    // what was actually paid: 141 lots on that same device, with
                    // lamports, quantity, timestamp, paper flag and the owning
                    // lane. It survives restarts and reinstalls. Asking it is not
                    // inventing a basis — it is reading the receipt that was
                    // already written, which is the distinction this file's header
                    // draws and continues to honour.
                    fromFill7126 ?: ledgerBasis7126(mint, amount)
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

    /**
     * V5.0.7126 — rebuild an entry basis from the durable fill-lot ledger.
     *
     * WHY THIS IS NOT "INVENTING A BASIS". This file's contract, stated in its
     * own header, is that a wallet mint is promoted to canonical LIVE only when
     * something PROVES a positive cost and entry price. FillLotLedger6504 is
     * exactly such a proof: an insert-only SQLite record written at fill time
     * carrying the lamports actually paid, the raw quantity actually received,
     * the timestamp, whether it was paper, and the lane that bought it. Nothing
     * here is estimated from a current price or back-solved from a mark.
     *
     * LIVE LOTS ONLY. Paper lots are excluded outright — promoting a simulated
     * fill into a live position would be the exact inverse of the defect this
     * build exists to fix, and would put fake money in the operator's ledger.
     *
     * WEIGHTED AVERAGE, AND SAID SO. The cost attributed is the average lamports
     * per raw token across the live BUY lots, applied to the quantity the wallet
     * ACTUALLY still holds. That is deliberately not FIFO: a FIFO basis needs the
     * matching SELL lots to have been finalized in order, and on a wallet that
     * has been partially sold outside the bot's view that ordering is not
     * trustworthy. Average cost over the real held quantity cannot drift from the
     * true total spend by more than the sell ordering, and it can never fabricate
     * a cost for tokens the wallet does not hold.
     *
     * THE LANE IS CARRIED, NOT DEFAULTED. lot.source is the lane recorded at fill
     * time, so the rebuilt position surfaces in the panel of the trader that
     * actually bought it. Falling back to WALLET_RECOVERED only when the ledger
     * genuinely has no owner is what keeps the operator's "displayed by the
     * system that bought them" true rather than approximately true.
     */
    private fun ledgerBasis7126(mint: String, amount: CanonicalTokenAmount): Basis? {
        val heldRaw = amount.raw
        if (heldRaw.signum() <= 0) return null
        val lots = try {
            com.lifecyclebot.engine.truth.FillLotLedger6504.lotsOf(mint)
        } catch (_: Throwable) { return null }
        if (lots.isEmpty()) return null

        val liveBuys = lots.filter {
            !it.isPaper && it.side.equals("BUY", true) &&
                it.qtyTokenRaw.signum() > 0 && it.lamports.signum() > 0
        }
        if (liveBuys.isEmpty()) return null

        var totalQtyRaw = BigInteger.ZERO
        var totalLamports = BigInteger.ZERO
        for (l in liveBuys) {
            totalQtyRaw = totalQtyRaw.add(l.qtyTokenRaw)
            totalLamports = totalLamports.add(l.lamports)
        }
        if (totalQtyRaw.signum() <= 0 || totalLamports.signum() <= 0) return null

        val costLamportsForHeld = totalLamports.multiply(heldRaw).divide(totalQtyRaw)
        val entryCostSol = costLamportsForHeld.toDouble() / 1_000_000_000.0
        if (!entryCostSol.isFinite() || entryCostSol <= 0.0) return null

        val decimals = amount.decimals.coerceIn(0, 18)
        val heldTokens = try {
            heldRaw.toBigDecimal().movePointLeft(decimals).toDouble()
        } catch (_: Throwable) { 0.0 }
        if (!heldTokens.isFinite() || heldTokens <= 0.0) return null

        val entryPriceSol = entryCostSol / heldTokens
        val solUsd = try { WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val entryPriceUsd = if (solUsd > 0.0) entryPriceSol * solUsd else 0.0
        // openPosition requires a positive entry price. Without a SOL/USD price
        // this basis is incomplete, and an incomplete basis is skipped rather
        // than shipped with a zero — the same refusal the caller already makes.
        if (!entryPriceUsd.isFinite() || entryPriceUsd <= 0.0) return null

        val owner = liveBuys.lastOrNull { it.source.isNotBlank() }?.source.orEmpty()
        val openedAt = liveBuys.minOf { it.tsMs }.takeIf { it > 0L } ?: System.currentTimeMillis()
        try {
            PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_FILL_LOTS_7126")
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = entryCostSol,
            entryPriceUsd = entryPriceUsd,
            lane = owner.ifBlank { "WALLET_RECOVERED" },
            openedAtMs = openedAt,
            source = "FILL_LOT_LEDGER_BASIS_7126",
            pool = "",
            dex = "",
            identity = liveBuys.first().lotId.ifBlank { "lot" },
        )
    }
}
