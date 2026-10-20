package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.OpenPnlSanity
import com.lifecyclebot.engine.TradeHistoryStore
import com.lifecyclebot.engine.WalletAccountCache
import com.lifecyclebot.engine.WalletManager

/** One immutable observation supplies cash, inventory and marks to sizing and the peak ledger. */
object LiveEquityObservation8239 {
    data class PositionValue8239(
        val positionId: String,
        val principalSol: Double,
        val valueSol: Double,
        val observedAtMs: Long,
        val quantityMatches: Boolean,
        val priceTrusted: Boolean,
    )

    data class Observation8239(
        val atMs: Long,
        val liquidSol: Double,
        val principalSol: Double,
        val unrealisedSol: Double,
        val walletObservedAtMs: Long,
        val tokenObservedAtMs: Long,
        val canonicalRevision: Long,
        val journalRevision: Long,
        val generation: Long,
        val reason: String,
        val cashRefusal: String,
    ) {
        val coherent: Boolean get() = reason == "OK"
        val cashProven: Boolean get() = cashRefusal.isBlank() && liquidSol.isFinite() && liquidSol >= 0.0
        val markedSol: Double get() = liquidSol + (principalSol + unrealisedSol).coerceAtLeast(0.0)
        val riskSol: Double get() = liquidSol + (principalSol + minOf(0.0, unrealisedSol)).coerceAtLeast(0.0)
        // Missing valuation cannot add principal to a new trade's loss budget. Existing reservations still apply.
        fun sizingEquitySol8239(): Double = if (coherent) riskSol else if (cashProven) liquidSol else Double.NaN
        fun provenance8239(): String = "equity8239=$reason walletAt=$walletObservedAtMs tokenAt=$tokenObservedAtMs " +
            "canonicalRev=$canonicalRevision journalRev=$journalRevision generation=$generation capturedAt=$atMs " +
            "sizing=${if (coherent) "VALUED" else if (cashProven) "PROVEN_CASH_ONLY" else cashRefusal}"
    }

    /** Pure: stale cash, a mutation across capture, or a pre-settlement wallet cannot set an equity peak. */
    fun assess8239(
        nowMs: Long, owner: String, wallet: WalletSolObservation8239?, tokenObservedAtMs: Long,
        tokensComplete: Boolean, positions: List<PositionValue8239>, lastMutationMs: Long,
        canonicalBefore: Long, canonicalAfter: Long, journalBefore: Long, journalAfter: Long,
        generationBefore: Long, generationAfter: Long, pendingTransaction: Boolean,
        paper: Boolean = false,
    ): Observation8239 {
        val cashRefusal = when {
            paper -> "PAPER_RUNTIME"
            wallet == null || !wallet.usable8239(nowMs, generationAfter) || wallet.owner != owner -> "CASH_UNOBSERVED_OR_STALE"
            generationBefore != generationAfter -> "WALLET_CHANGED_DURING_CAPTURE"
            canonicalBefore < 0 || canonicalBefore != canonicalAfter || journalBefore < 0 || journalBefore != journalAfter -> "LEDGER_CHANGED_DURING_CAPTURE"
            wallet.startedAtMs < lastMutationMs -> "WALLET_PRECEDES_SETTLEMENT"
            else -> ""
        }
        val reason = when {
            cashRefusal.isNotBlank() -> cashRefusal
            pendingTransaction -> "TRANSACTION_UNRESOLVED"
            !tokensComplete || tokenObservedAtMs <= 0L || nowMs - tokenObservedAtMs !in 0L..30_000L -> "TOKEN_ENUMERATION_UNPROVEN"
            tokenObservedAtMs < lastMutationMs -> "WALLET_PRECEDES_SETTLEMENT"
            positions.any { !it.quantityMatches } -> "WALLET_QUANTITY_MISMATCH"
            positions.any { !it.priceTrusted || !it.principalSol.isFinite() || it.principalSol < 0.0 ||
                !it.valueSol.isFinite() || it.valueSol < 0.0 || it.observedAtMs <= 0L || nowMs - it.observedAtMs !in 0L..45_000L } -> "POSITION_MARK_UNPROVEN"
            else -> "OK"
        }
        val principal = positions.sumOf { if (it.principalSol.isFinite()) it.principalSol.coerceAtLeast(0.0) else 0.0 }
        val upnl = positions.sumOf { if (it.priceTrusted && it.valueSol.isFinite() && it.principalSol.isFinite()) it.valueSol - it.principalSol else 0.0 }
        return Observation8239(nowMs, wallet?.sol ?: Double.NaN, principal, upnl, wallet?.observedAtMs ?: 0L,
            tokenObservedAtMs, canonicalBefore, journalBefore, generationBefore, reason, cashRefusal)
    }

    /** No network work: successful RPC observations keep their original times through every consumer. */
    fun capture8239(nowMs: Long = System.currentTimeMillis()): Observation8239 {
        val generation = WalletAccountCache.readGeneration8237()
        val revision = CanonicalPositionAuthority6441.mutationCount7387()
        val journal = TradeHistoryStore.journalRevision7343()
        val owner = WalletManager.currentPubkey()
        val wallet = WalletManager.getWallet()?.balanceObservation8239(nowMs)
        val tokens = WalletAccountCache.snapshotWithProof8237(30_001L, nowMs, expectedOwner = owner)
        val positions = CanonicalPositionAuthority6441.protectiveInventory7807("live").filter { it.assetClass == AssetClass.SOLANA_TOKEN }
        val closed = CanonicalPositionAuthority6441.closedPositions().filter { it.mode.equals("live", true) && it.assetClass == AssetClass.SOLANA_TOKEN }
        val mutation = maxOf(positions.maxOfOrNull { it.lastMutationMs } ?: 0L, closed.maxOfOrNull { it.lastMutationMs } ?: 0L)
        val solUsd = WalletManager.freshSolPrice8163(nowMs)
        val values = positions.map { p -> value8239(p, tokens, solUsd, nowMs) }
        return assess8239(nowMs, owner, wallet, tokens?.observedAtMs ?: 0L, tokens?.complete == true,
            values, mutation, revision, CanonicalPositionAuthority6441.mutationCount7387(), journal,
            TradeHistoryStore.journalRevision7343(), generation, WalletAccountCache.readGeneration8237(),
            com.lifecyclebot.network.SignedTxLedger8085.unresolved(nowMs).isNotEmpty(),
            com.lifecyclebot.engine.RuntimeModeAuthority.isPaper())
    }

    fun markUsable8239(mint: String, decimals: Int, mark: CanonicalPriceMark6522?, nowMs: Long): Boolean =
        mark != null && mark.mint == mint && mark.chain7424.equals("solana", true) &&
            mark.purpose == CanonicalMarkPurpose6570.EXIT_ECONOMIC && mark.pairId.isNotBlank() &&
            MarkIdentityAudit7425.validate(mark) == MarkIdentityAudit7425.Verdict.VALID &&
            decimals in 0..18 && (mark.tokenDecimals7424 == -1 || mark.tokenDecimals7424 == decimals) &&
            mark.timestampMs > 0L && nowMs - mark.timestampMs in 0L..45_000L &&
            mark.priceUsd.value.toDouble().let { it.isFinite() && it > 0.0 }

    private fun value8239(p: CanonicalPositionAuthority6441.Position, tokens: WalletAccountCache.Snapshot8237?, solUsd: Double, nowMs: Long): PositionValue8239 {
        // One immutable registry tuple prevents mixing an older price with a newer token-row timestamp.
        val mark = CanonicalPriceMarkRegistry6522.get(p.mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC)
        val price = mark?.priceUsd?.value?.toDouble() ?: Double.NaN
        val at = mark?.timestampMs ?: 0L
        val source = mark?.source.orEmpty()
        val pool = mark?.pairId.orEmpty()
        val amount = tokens?.tokens?.get(p.mint)
        val quantityMatches = amount != null && amount.raw == p.remainingQtyRaw && amount.decimals == p.quantityScale
        val identity = p.assetClass == AssetClass.SOLANA_TOKEN && p.positionId.isNotBlank() &&
            p.remainingQtyRaw.signum() > 0 && markUsable8239(p.mint, p.quantityScale, mark, nowMs)
        val sane = if (identity && price.isFinite() && price > 0.0 && source.isNotBlank()) try {
            OpenPnlSanity.inspect(p.entryPriceUsd, price, p.entryPriceSource, source, p.entryPoolAddress, pool,
                context = "equity8239", emit = false, mint = p.mint, tokenDecimals = p.quantityScale)
        } catch (_: Throwable) { null } else null
        // A repair made during inspect must arrive as its own timestamped mark before this snapshot uses it.
        val unchanged = sane != null && sane.ok && kotlin.math.abs(p.entryPriceUsd * (1.0 + sane.pnlPct / 100.0) - price) <= price * 1e-8
        val value = if (identity && solUsd.isFinite() && solUsd > 0.0) EconomicUnitInvariant7061.usdToSol(
            p.remainingQtyRaw.toBigDecimal().movePointLeft(p.quantityScale).toDouble() * price, solUsd) else Double.NaN
        return PositionValue8239(p.positionId, (p.entryCostSol - p.soldCostBasisSol).coerceAtLeast(0.0), value,
            at, quantityMatches, unchanged && value.isFinite())
    }
}
