package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import java.math.BigInteger

/**
 * V5.0.8418 §TX_RECEIPT_SETTLEMENT — a position's cost basis and proceeds come from ITS OWN confirmed transaction.
 *
 * The receipt is the confirmed transaction's meta for the owner: preBalances/postBalances at the owner's account
 * index, pre/postTokenBalances for the owner's token account of the mint, meta.fee (TradeVerifier decodes it; the
 * tip/priority the owner paid is inside the owner's lamport delta of that same transaction). A whole-wallet balance
 * read before and after a send also sees every concurrent transfer and every other position's trade, so it never
 * books a position's P&L. No receipt = PENDING (null here), never an estimate.
 */
object Settlement8418 {

    /** One buy leg as its receipt proves it. [costSol] excludes the network fee, which is [feeSol] (live entry convention). */
    data class Fill8418(
        val signature: String,
        val rawQty: BigInteger,
        val decimals: Int,
        val qtyUi: Double,
        val costSol: Double,
        val feeSol: Double,
    )

    /** Pure: the buy fill a verifier receipt proves, or null (PENDING) when the receipt does not prove one. */
    fun buyFill8418(r: TradeVerifier.BuyResult?): Fill8418? {
        if (r == null || r.outcome != TradeVerifier.Outcome.LANDED) return null
        if (r.rawTokenDelta.signum() <= 0 || r.decimals !in 0..18) return null
        if (r.solSpentLamports <= 0L || r.feeLamports < 0L || r.feeLamports >= r.solSpentLamports) return null
        val ui = r.rawTokenDelta.toBigDecimal().movePointLeft(r.decimals).toDouble()
        if (!ui.isFinite() || ui <= 0.0) return null
        return Fill8418(
            signature = r.sig,
            rawQty = r.rawTokenDelta,
            decimals = r.decimals,
            qtyUi = ui,
            costSol = (r.solSpentLamports - r.feeLamports).toDouble() / 1_000_000_000.0,
            feeSol = r.feeLamports.toDouble() / 1_000_000_000.0,
        )
    }

    /** Pure: SOL a sell's own receipt proves it returned, or null (PENDING). */
    fun sellProceedsSol8418(r: TradeVerifier.SellResult?): Double? {
        if (r == null || r.outcome != TradeVerifier.Outcome.LANDED || r.solReceivedLamports <= 0L) return null
        return r.solReceivedLamports.toDouble() / 1_000_000_000.0
    }

    /**
     * Pure: what one close transaction returned to the owner, in SOL, from ITS receipt: the owner's lamport delta plus
     * the owner's [quoteMint] (USDC) token delta valued at [solUsd]. Null (PENDING) when there is no receipt, the tx
     * failed, a quote-token delta exists without a usable SOL/USD, or nothing positive came back.
     */
    fun closeProceedsSol8418(parse: com.lifecyclebot.engine.execution.TxParseHelper.ParseResult?, quoteMint: String, solUsd: Double): Double? {
        if (parse == null || !parse.confirmed || parse.metaErr != null) return null
        val sol = parse.solDeltaLamports.toDouble() / 1_000_000_000.0
        val q = parse.tokenDeltas[quoteMint]
        val quoteUi = if (q == null || q.rawDelta.signum() == 0 || q.decimals !in 0..18) 0.0
            else q.rawDelta.toBigDecimal().movePointLeft(q.decimals).toDouble()
        if (quoteUi != 0.0 && (!solUsd.isFinite() || solUsd !in 10.0..10_000.0)) return null
        val total = sol + (if (quoteUi != 0.0) com.lifecyclebot.engine.truth.EconomicUnitInvariant7061.usdToSol(quoteUi, solUsd) else 0.0)
        return if (total.isFinite() && total > 0.0) total else null
    }

    /**
     * Pure: the position after a CONFIRMED top-up fill. Quantity and cost grow by the receipt's amounts only; the USD
     * entry is the quantity-weighted average with the added leg priced at its receipt cost x SOL/USD. Without a usable
     * SOL/USD the prior entry is kept (named by the caller), never a guessed price.
     */
    fun appliedTopUp8418(pos: Position, fill: Fill8418, solUsd: Double, priceUsd: Double, nowMs: Long): Position {
        val totalQty = pos.qtyToken + fill.qtyUi
        val solUsdOk = solUsd.isFinite() && solUsd in 5.0..10_000.0
        val entry = if (solUsdOk && totalQty > 0.0 && pos.entryPrice.isFinite() && pos.entryPrice >= 0.0)
            (pos.qtyToken * pos.entryPrice + fill.costSol * solUsd) / totalQty
        else pos.entryPrice
        return pos.copy(
            qtyToken = totalQty,
            entryPrice = entry,
            costSol = pos.costSol + fill.costSol,
            topUpCount = pos.topUpCount + 1,
            topUpCostSol = pos.topUpCostSol + fill.costSol,
            lastTopUpTime = nowMs,
            lastTopUpPrice = priceUsd,
        )
    }

    /** The owner a top-up's signed transaction carries in SignedTxLedger8085 (survives restarts with the row). */
    fun topUpAttemptId8418(positionId: String, mint: String, topUpNumber: Int): String =
        "TOPUP_8418:${positionId.ifBlank { mint }}:$topUpNumber"

    /** The single open LIVE canonical position on [mint], or "" — never a synthetic id. */
    fun liveOwnerOf8418(mint: String): String = try {
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions()
            .filter { it.mint == mint && it.mode.equals("live", true) }
            .singleOrNull()?.positionId.orEmpty()
    } catch (_: Throwable) { "" }

    internal fun count8418(label: String) { try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {} }
}
