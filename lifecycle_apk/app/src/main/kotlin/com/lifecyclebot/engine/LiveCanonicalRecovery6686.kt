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
                    // V5.0.7133 — 7126 asked the wrong ledger, so it never fired.
                    //
                    // ledgerBasis7126 filters FillLotLedger6504 for lots with
                    // isPaper == false. Both writers of
                    // FillLotLedger6504.recordBuyFill pass isPaper = true
                    // (Executor.kt:11783 paperTopUp, Executor.kt:15485
                    // paperBuy.atomic6485). There is no live writer, so that
                    // filter has matched nothing since the day it shipped and
                    // LIVE_BASIS_REBUILT_FROM_FILL_LOTS_7126 could never be
                    // emitted. The durable record of a live fill is the OTHER
                    // object — FillLotLedger6344 — whose sole writer is the
                    // wallet-proof promotion, which is why every lot in it is
                    // live by construction and it carries no paper flag at all.
                    //
                    // 6504 is left in the chain. It costs one lookup, it is the
                    // correct source if a live writer is ever added to it, and
                    // removing a source is not what this build is for.
                    fromFill7126 ?: ledgerBasis6344_7133(mint) ?:
                        ledgerBasis7126(mint, amount) ?: journalBasis7253(mint, amount)
                }
            }

            if (basis == null) {
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686",
                        "mint=${mint.take(12)} raw=${amount.raw} decimals=${amount.decimals} action=retain_wallet_tracking_no_invented_basis",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_RECOVERY_BASIS_MISSING_6686")
                    // V5.0.7232 §WALLET_CANONICAL_INVENTORY_HOOK — classify
                    //   the wallet-observed mint. Missing basis = bot never
                    //   opened it (or opened without sealing) => not
                    //   BOT_CANONICAL_OPEN; pnlAllowed(mint) will return
                    //   false at the UI/telemetry layer instead of
                    //   fabricating a number from an unknown entry.
                    com.lifecyclebot.engine.truth.WalletCanonicalInventoryClassifier7230.classify(
                        mint = mint,
                        botCanonicalOwned = false,
                        sealedBasisPresent = false,
                        externalWalletHolding = true,
                        unsupportedProof = false,
                        quarantineReason = "",
                    )
                    // Immediate consult so the classifier is not
                    // dead-code: any wallet-observed PnL for a
                    // basis-missing mint is suppressed here at the
                    // recovery point. Result is telemetry-only; the UI
                    // path decides whether to render "basis wait".
                    com.lifecyclebot.engine.truth.WalletCanonicalInventoryClassifier7230.pnlAllowed(mint)
                } catch (_: Throwable) {}
                continue
            }

            val safeIdentity = basis.identity.replace(Regex("[^A-Za-z0-9]"), "").takeLast(14).ifBlank { "basis" }
            val positionId = runtimePos?.positionId?.takeIf { it.isNotBlank() }
                ?: "LIVE_RECOVERED_6686:${mint.take(16)}:$safeIdentity"

            // V5.0.7133 — THE FAILED BUY'S OWN RESERVATION WAS BLOCKING ITS
            // RECOVERY, AND THE BLOCK WAS SILENT.
            //
            // Operator: "ive checked my wallet most of the coins are sol coins so
            // should be shown held and managed on the meme lane."
            //
            // Every live buy reserves a canonical PENDING_ENTRY row up front
            // (ExecutorCanonicalMirror6442.mirrorBuyAttempt, openedQtyRaw = ZERO).
            // Only the wallet-proof promotion turns it into OPEN. When that proof
            // does not complete, the reservation stays PENDING_ENTRY — and it is
            // then the thing that makes recovery impossible:
            //
            //   • activeMintProjections6490 filters remainingQtyRaw > ZERO, so a
            //     pending row is NOT in existingLive and this loop reaches the mint
            //     and derives a complete, provable basis. Good so far.
            //   • openPosition then hits existingSameMint6490, whose filter admits
            //     PENDING_ENTRY regardless of quantity. A different positionId
            //     means DUPLICATE, and its own log says "action=use_explicit_add".
            //   • DUPLICATE was excluded from the rejection branch below, so the
            //     refusal emitted nothing at all. The bridge reported zero repairs
            //     and never said why.
            //
            // So a wallet-held live position with a known cost and a known entry
            // price could never reach OPEN: invisible in every panel, absent from
            // exposure and hero totals, unmanaged by the exit router, and finally
            // TTL-quarantined by PendingEntryProjectionGuard6461 without ever
            // having been a position. That is one chokepoint producing the whole
            // set of symptoms the operator has been reporting.
            //
            // The repair is to promote the row that already exists instead of
            // opening a second one beside it. promotePendingToOpen is the
            // authority's own method for this, and nothing here is invented:
            // the QUANTITY is what the wallet provably holds right now, the COST
            // is what the buy itself reserved, and the ENTRY PRICE is what the buy
            // itself stamped. Those are the same three values the proof path would
            // have supplied, from the same origins.
            //
            // Note the direction: this can only ever move a reservation the bot
            // made to OPEN against tokens the wallet holds. It cannot open a
            // position for a mint with no reservation, it cannot alter an existing
            // OPEN row, and a mint the wallet does not hold never enters this loop.
            val pendingSameMint7133 = try {
                CanonicalPositionAuthority6441.pendingEntryPositions6461().firstOrNull {
                    it.mint == mint && it.mode.equals("live", true)
                }
            } catch (_: Throwable) { null }
            if (pendingSameMint7133 != null) {
                val promoted7133 = try {
                    CanonicalPositionAuthority6441.promotePendingToOpen(
                        positionId = pendingSameMint7133.positionId,
                        actualQtyRaw = amount.raw,
                        actualEntryCostSol = basis.entryCostSol,
                        actualFeesSol = pendingSameMint7133.feesSol.coerceAtLeast(0.0),
                        tokenDecimals = amount.decimals,
                        paperMode = false,
                        quantityScale = amount.decimals,
                        actualEntryPriceUsd = basis.entryPriceUsd,
                        actualEntryPriceSource = basis.source,
                        actualEntryPoolAddress = basis.pool,
                        actualEntryDex = basis.dex,
                    )
                } catch (_: Throwable) { CanonicalPositionAuthority6441.MutateResult.INVARIANT_VIOLATION }
                if (promoted7133 == CanonicalPositionAuthority6441.MutateResult.APPLIED) {
                    existingLive.add(mint)
                    repaired++
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_PENDING_ENTRY_PROMOTED_FROM_WALLET_7133",
                            "mint=${mint.take(12)} pid=${pendingSameMint7133.positionId.take(28)} lane=${pendingSameMint7133.lane} " +
                                "raw=${amount.raw} decimals=${amount.decimals} cost=${basis.entryCostSol} " +
                                "entryUsd=${basis.entryPriceUsd} source=${basis.source} " +
                                "reason=buy_reserved_but_proof_never_completed",
                        )
                        PipelineHealthCollector.labelInc("LIVE_PENDING_ENTRY_PROMOTED_FROM_WALLET_7133")
                    } catch (_: Throwable) {}
                } else {
                    try {
                        ForensicLogger.lifecycle(
                            "LIVE_PENDING_ENTRY_PROMOTE_REFUSED_7133",
                            "mint=${mint.take(12)} pid=${pendingSameMint7133.positionId.take(28)} result=$promoted7133 action=retain_wallet_tracking",
                        )
                        PipelineHealthCollector.labelInc("LIVE_PENDING_ENTRY_PROMOTE_REFUSED_7133")
                    } catch (_: Throwable) {}
                }
                continue
            }

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
            } else {
                // V5.0.7133 — DUPLICATE used to be excluded here, so the single
                // most common refusal emitted nothing. A silent refusal in a
                // repair path is worse than no repair path: the counters say the
                // bridge ran and found nothing to do, when in fact it found the
                // position, proved a basis, and was turned away. Every outcome
                // now names itself.
                try {
                    ForensicLogger.lifecycle(
                        "LIVE_WALLET_CANONICAL_RECOVERY_REJECTED_6686",
                        "mint=${mint.take(12)} result=$result rejectedPid=${positionId.take(28)} action=retain_wallet_tracking",
                    )
                    PipelineHealthCollector.labelInc("LIVE_WALLET_CANONICAL_RECOVERY_REJECTED_6686_$result".take(60))
                } catch (_: Throwable) {}
            }
        }
        return repaired
    }

    /**
     * V5.0.7253 — last durable recovery source: a finalized LIVE BUY journal
     * receipt. Some historical verified buys reached TradeHistoryStore but
     * missed both fill registries during the finality/canonical race. Wallet
     * presence alone is never enough; this path requires LIVE_FINALIZED proof,
     * a transaction signature, positive recorded cost/price/quantity, and no
     * later full terminal sell for the same mint.
     */
    private fun journalBasis7253(mint: String, amount: CanonicalTokenAmount): Basis? {
        val rows = try { TradeHistoryStore.getRecentValidTrades(5_000) } catch (_: Throwable) { return null }
        val sameMint = rows.filter { it.mint == mint && it.mode.equals("live", true) }
        val buy = sameMint.firstOrNull {
            it.side.equals("BUY", true) &&
                it.proofState.equals("LIVE_FINALIZED", true) &&
                it.sig.isNotBlank() &&
                it.entryCostSol.isFinite() && it.entryCostSol > 0.0 &&
                it.entryPriceSnapshot.isFinite() && it.entryPriceSnapshot > 0.0 &&
                it.entryQtyToken.isFinite() && it.entryQtyToken > 0.0
        } ?: return null
        val laterTerminalSell = sameMint.firstOrNull {
            it.ts > buy.ts &&
                (it.side.equals("SELL", true) || it.side.equals("PARTIAL_SELL", true)) &&
                (it.proofState.equals("LIVE_FINALIZED", true) ||
                    it.proofState.equals("LIVE_BALANCE_CONFIRMED", true) ||
                    it.proofState.equals("LIVE_SIG_CONFIRMED", true))
        }
        if (laterTerminalSell != null &&
            laterTerminalSell.remainingRawQty.signum() <= 0 &&
            laterTerminalSell.remainingQtyToken <= 0.0
        ) return null

        val heldQty = amount.uiDoubleForDisplay()
        if (!heldQty.isFinite() || heldQty <= 0.0) return null
        val cost = buy.entryCostSol * (heldQty / buy.entryQtyToken).coerceIn(0.0, 1.0)
        if (!cost.isFinite() || cost <= 0.0) return null
        try {
            PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_FINALIZED_JOURNAL_7253")
            ForensicLogger.lifecycle(
                "LIVE_BASIS_REBUILT_FROM_FINALIZED_JOURNAL_7253",
                "mint=${mint.take(12)} sig=${buy.sig.take(14)} heldQty=$heldQty entryQty=${buy.entryQtyToken} lane=${buy.tradingMode}",
            )
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = cost,
            entryPriceUsd = buy.entryPriceSnapshot,
            lane = buy.tradingMode.ifBlank { "STANDARD" },
            openedAtMs = buy.entryTsMs.takeIf { it > 0L } ?: buy.ts,
            source = "LIVE_FINALIZED_JOURNAL_BASIS_7253",
            pool = buy.entryPoolAddress,
            dex = "",
            identity = buy.sig,
        )
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
    /**
     * V5.0.7133 — rebuild an entry basis from the ledger the LIVE path writes.
     *
     * FillLotLedger6344 is appended by exactly one caller, the wallet-proof
     * promotion in Executor, with the lamports the transaction actually spent and
     * the raw quantity the owner token account actually received. Every lot in it
     * is therefore a finalized live fill — there is no paper flag to filter on
     * because no paper path can reach it. It persists to SharedPreferences, so it
     * survives process death and APK updates, which is what the operator asked
     * this bridge to use: "you can see the held token metrics via the ledger,
     * rebuild the position and update them on update install."
     *
     * Only OPEN inventory is counted. Each lot tracks its own finalized sell
     * partials, so remainingQty is the quantity that lot still owns; a fully sold
     * lot contributes neither cost nor quantity and cannot resurrect a closed bag.
     *
     * The USD entry comes from the lot's own recorded entryPriceUsdPerToken,
     * weighted by remaining quantity across lots. That figure was written at fill
     * time from the tx-derived basis, so no current mark is consulted and nothing
     * is back-solved. A lot with no USD price recorded is skipped rather than
     * repriced.
     */
    private fun ledgerBasis6344_7133(mint: String): Basis? {
        val owner = try {
            WalletManager.getWallet()?.publicKeyB58.orEmpty()
        } catch (_: Throwable) { "" }
        if (owner.isBlank()) return null
        val lots = try {
            FillLotLedger6344.snapshotForMint(owner, mint)
        } catch (_: Throwable) { return null }
        if (lots.isEmpty()) return null

        var qty = 0.0
        var costSol = 0.0
        var usdWeighted = 0.0
        var usdWeight = 0.0
        for (l in lots) {
            val remaining = l.remainingQty
            if (!remaining.isFinite() || remaining <= 0.0) continue
            if (!l.entryQty.isFinite() || l.entryQty <= 0.0) continue
            if (!l.entryCostSol.isFinite() || l.entryCostSol <= 0.0) continue
            val share = (remaining / l.entryQty).coerceIn(0.0, 1.0)
            qty += remaining
            costSol += l.entryCostSol * share
            if (l.entryPriceUsdPerToken.isFinite() && l.entryPriceUsdPerToken > 0.0) {
                usdWeighted += l.entryPriceUsdPerToken * remaining
                usdWeight += remaining
            }
        }
        if (qty <= 0.0 || !costSol.isFinite() || costSol <= 0.0) return null
        if (usdWeight <= 0.0) return null
        val entryPriceUsd = usdWeighted / usdWeight
        if (!entryPriceUsd.isFinite() || entryPriceUsd <= 0.0) return null

        val laneOwner = lots.lastOrNull { it.laneCanonical.isNotBlank() }?.laneCanonical.orEmpty()
        val openedAt = lots.filter { it.entryTsMs > 0L }.minOfOrNull { it.entryTsMs }
            ?: System.currentTimeMillis()
        try {
            PipelineHealthCollector.labelInc("LIVE_BASIS_REBUILT_FROM_FILL_LOT_LEDGER_6344_7133")
        } catch (_: Throwable) {}
        return Basis(
            entryCostSol = costSol,
            entryPriceUsd = entryPriceUsd,
            lane = laneOwner.ifBlank { "WALLET_RECOVERED" },
            openedAtMs = openedAt,
            source = "FILL_LOT_LEDGER_6344_BASIS_7133",
            pool = "",
            dex = "",
            identity = lots.first().buyTxSig.ifBlank { "lot6344" },
        )
    }

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
