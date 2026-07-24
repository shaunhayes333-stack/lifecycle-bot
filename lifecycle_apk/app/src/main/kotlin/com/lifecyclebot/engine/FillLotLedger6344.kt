package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6344 — IMMUTABLE FILL-LOT LEDGER (operator P0-2 spec).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Immutable Position-Lot Accounting.
 *    A live lot identity is strictly:
 *        walletAddress + mintAddress + confirmedBuyTransactionSignature.
 *    Never mutate entry rows during reconciliation. Never re-key.
 *    Reconciliation may only APPEND new sell partials or top-up fills."
 *
 *   "The realized-SOL PnL calculator must read the lot ledger — not the
 *    mutable ts.position row — for cost basis and entry qty."
 *
 * DIFFERENCE FROM [CanonicalBuyFillRegistry]
 *   The 6320 registry is keyed by MINT and REPLACES the record on top-up
 *   or re-entry. That was fine for the "display the last acquisition"
 *   surface but is UNSAFE for realized-PnL accounting because a top-up
 *   quietly overwrites the original acquisition cost basis. The 6344
 *   ledger is APPEND-ONLY, keyed by (wallet, mint, buyTxSig), and never
 *   mutates a row after write. It is the authoritative source for lot
 *   identity and cost basis that [CanonicalPnLAuthority6343] reads
 *   through [RealizedPnlConduit6344].
 *
 * PERSISTENCE
 *   SharedPreferences JSON snapshot mirrors every append. On process
 *   restart, [init] rehydrates all lots in memory so no cost basis is
 *   lost across an Android kill. Rows are never deleted — closure is
 *   represented by the sold-quantity roll-up, not by removal.
 *
 * INVARIANTS
 *   • Primary key (wallet, mint, buyTxSig) is unique. Duplicate append
 *     is idempotent (first write wins).
 *   • Entry cost / qty / price are frozen at write time; getters return
 *     the immutable snapshot even if the mint is later re-entered.
 *   • Sell-side attribution [appendSell] is additive; cumulative sold
 *     qty never exceeds original entry qty within token-decimal tolerance.
 */
object FillLotLedger6344 {

    private const val TAG = "FillLotLedger6344"
    private const val PREFS_NAME = "fill_lot_ledger_6344"
    private const val KEY_LOTS = "lots_json"
    private const val KEY_VERSION = "lots_version"
    private const val CURRENT_VERSION = 1

    /**
     * A single immutable buy-fill lot. Once appended, no field is ever
     * mutated. Sell-side attribution goes into [sells] but never touches
     * [entryCostSol], [entryQty], or [entryPriceSolPerToken].
     */
    data class Lot(
        val walletAddress: String,
        val mintAddress: String,
        val buyTxSig: String,
        val entryCostSol: Double,
        val entryQty: Double,
        val entryPriceSolPerToken: Double,
        val entryPriceUsdPerToken: Double,
        val decimals: Int,
        val laneCanonical: String,
        val entryTsMs: Long,
        /** Append-only list of finalized sell partials against this lot. */
        val sells: List<SellPartial> = emptyList(),
    ) {
        val lotKey: String get() = key(walletAddress, mintAddress, buyTxSig)
        val cumulativeSoldQty: Double get() = sells.sumOf { it.soldQty }
        val remainingQty: Double get() = (entryQty - cumulativeSoldQty).coerceAtLeast(0.0)
        fun isTerminal(qtyTol: Double): Boolean =
            entryQty > 0.0 && remainingQty <= qtyTol
    }

    data class SellPartial(
        val sellTxSig: String,
        val soldQty: Double,
        val proceedsSol: Double,
        val feeSol: Double,
        val sellTsMs: Long,
        val proofSource: String,
    )

    private val lots = ConcurrentHashMap<String, Lot>()

    @Volatile private var prefs: SharedPreferences? = null

    fun key(walletAddress: String, mintAddress: String, buyTxSig: String): String =
        "${walletAddress.trim()}|${mintAddress.trim()}|${buyTxSig.trim()}"

    /** Rehydrate persisted lots on process start. Call from BotService.onCreate. */
    fun init(context: Context) {
        try {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs?.getString(KEY_LOTS, null) ?: return
            val arr = JSONArray(json)
            var loaded = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val sellsArr = o.optJSONArray("sells")
                val sellsList = mutableListOf<SellPartial>()
                if (sellsArr != null) {
                    for (j in 0 until sellsArr.length()) {
                        val s = sellsArr.optJSONObject(j) ?: continue
                        sellsList += SellPartial(
                            sellTxSig = s.optString("sellTxSig", ""),
                            soldQty = s.optDouble("soldQty", 0.0),
                            proceedsSol = s.optDouble("proceedsSol", 0.0),
                            feeSol = s.optDouble("feeSol", 0.0),
                            sellTsMs = s.optLong("sellTsMs", 0L),
                            proofSource = s.optString("proofSource", ""),
                        )
                    }
                }
                val lot = Lot(
                    walletAddress = o.optString("walletAddress", ""),
                    mintAddress = o.optString("mintAddress", ""),
                    buyTxSig = o.optString("buyTxSig", ""),
                    entryCostSol = o.optDouble("entryCostSol", 0.0),
                    entryQty = o.optDouble("entryQty", 0.0),
                    entryPriceSolPerToken = o.optDouble("entryPriceSolPerToken", 0.0),
                    entryPriceUsdPerToken = o.optDouble("entryPriceUsdPerToken", 0.0),
                    decimals = o.optInt("decimals", 9),
                    laneCanonical = o.optString("laneCanonical", ""),
                    entryTsMs = o.optLong("entryTsMs", 0L),
                    sells = sellsList,
                )
                if (lot.buyTxSig.isNotBlank() && lot.mintAddress.isNotBlank() && lot.walletAddress.isNotBlank()) {
                    lots[lot.lotKey] = lot
                    loaded++
                }
            }
            if (loaded > 0) {
                try {
                    ForensicLogger.lifecycle("FILL_LOT_LEDGER_RESTORED_6344", "loaded=$loaded")
                    PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_RESTORED_6344")
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            try { ErrorLogger.warn(TAG, "init/restore failed: ${t.message}") } catch (_: Throwable) {}
        }
    }

    /**
     * Append an immutable buy-fill lot. First write wins — subsequent
     * calls with the same (wallet, mint, buyTxSig) triple are dropped
     * so reconciliation can never mutate the original cost basis.
     * Returns the lot that is now authoritative for the key.
     */
    fun appendBuy(
        walletAddress: String,
        mintAddress: String,
        buyTxSig: String,
        entryCostSol: SolAmount,
        entryQty: TokenQuantity,
        entryPriceSolPerToken: PriceSolPerToken,
        entryPriceUsdPerToken: PriceUsdPerToken,
        decimals: Int,
        laneCanonical: String,
        entryTsMs: Long,
    ): Lot? {
        if (walletAddress.isBlank() || mintAddress.isBlank() || buyTxSig.isBlank()) {
            try {
                PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_APPEND_REJECTED_BLANK_KEY_6344")
                ForensicLogger.lifecycle(
                    "FILL_LOT_LEDGER_APPEND_REJECTED_6344",
                    "reason=BLANK_KEY wallet_blank=${walletAddress.isBlank()} " +
                        "mint_blank=${mintAddress.isBlank()} sig_blank=${buyTxSig.isBlank()}",
                )
            } catch (_: Throwable) {}
            return null
        }
        val k = key(walletAddress, mintAddress, buyTxSig)
        val existing = lots[k]
        if (existing != null) {
            try { PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_APPEND_IDEMPOTENT_6344") } catch (_: Throwable) {}
            return existing
        }
        val lot = Lot(
            walletAddress = walletAddress,
            mintAddress = mintAddress,
            buyTxSig = buyTxSig,
            entryCostSol = entryCostSol.unwrap(),
            entryQty = entryQty.unwrap(),
            entryPriceSolPerToken = entryPriceSolPerToken.unwrap(),
            entryPriceUsdPerToken = entryPriceUsdPerToken.unwrap(),
            decimals = decimals,
            laneCanonical = laneCanonical,
            entryTsMs = entryTsMs,
        )
        lots[k] = lot
        try {
            ForensicLogger.lifecycle(
                "FILL_LOT_LEDGER_APPEND_BUY_6344",
                "wallet=${walletAddress.take(10)} mint=${mintAddress.take(10)} " +
                    "sig=${buyTxSig.take(12)} cost=${"%.6f".format(lot.entryCostSol)} " +
                    "qty=${"%.4f".format(lot.entryQty)} " +
                    "pxSol=${"%.4e".format(lot.entryPriceSolPerToken)} lane=$laneCanonical",
            )
            PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_APPEND_BUY_6344")
        } catch (_: Throwable) {}
        persistToDisk()
        return lot
    }

    /**
     * Append a finalized sell partial against an existing lot. If the
     * lot cannot be located, we log a health counter and return null —
     * the caller should route the row through the quarantine path.
     */
    fun appendSell(
        walletAddress: String,
        mintAddress: String,
        buyTxSig: String,
        sellTxSig: String,
        soldQty: TokenQuantity,
        proceedsSol: SolAmount,
        feeSol: SolAmount,
        sellTsMs: Long,
        proofSource: String,
    ): Lot? {
        val k = key(walletAddress, mintAddress, buyTxSig)
        val existing = lots[k] ?: run {
            try {
                PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_SELL_LOT_NOT_FOUND_6344")
                ForensicLogger.lifecycle(
                    "FILL_LOT_LEDGER_SELL_LOT_NOT_FOUND_6344",
                    "wallet=${walletAddress.take(10)} mint=${mintAddress.take(10)} buySig=${buyTxSig.take(12)}",
                )
            } catch (_: Throwable) {}
            return null
        }
        if (existing.sells.any { it.sellTxSig == sellTxSig && sellTxSig.isNotBlank() }) {
            try { PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_SELL_IDEMPOTENT_6344") } catch (_: Throwable) {}
            return existing
        }
        val updated = existing.copy(
            sells = existing.sells + SellPartial(
                sellTxSig = sellTxSig,
                soldQty = soldQty.unwrap(),
                proceedsSol = proceedsSol.unwrap(),
                feeSol = feeSol.unwrap(),
                sellTsMs = sellTsMs,
                proofSource = proofSource,
            ),
        )
        lots[k] = updated
        try {
            ForensicLogger.lifecycle(
                "FILL_LOT_LEDGER_APPEND_SELL_6344",
                "wallet=${walletAddress.take(10)} mint=${mintAddress.take(10)} " +
                    "buySig=${buyTxSig.take(12)} sellSig=${sellTxSig.take(12)} " +
                    "sold=${"%.4f".format(soldQty.unwrap())} proceeds=${"%.6f".format(proceedsSol.unwrap())} " +
                    "cumSold=${"%.4f".format(updated.cumulativeSoldQty)} remaining=${"%.4f".format(updated.remainingQty)} " +
                    "proof=$proofSource",
            )
            PipelineHealthCollector.labelInc("FILL_LOT_LEDGER_APPEND_SELL_6344")
        } catch (_: Throwable) {}
        persistToDisk()
        return updated
    }

    /** Exact lot lookup by primary key. */
    fun get(walletAddress: String, mintAddress: String, buyTxSig: String): Lot? =
        lots[key(walletAddress, mintAddress, buyTxSig)]

    /**
     * Latest non-terminal lot for a wallet+mint pair. Used by the sell
     * finalizer when only wallet+mint are known and the buy signature
     * has to be resolved from context. Returns the most recently opened
     * lot with `remainingQty > tolerance`.
     */
    fun latestOpenLot(walletAddress: String, mintAddress: String): Lot? {
        val qtyTol = 1e-9
        return lots.values.asSequence()
            .filter { it.walletAddress == walletAddress && it.mintAddress == mintAddress }
            .filter { it.remainingQty > qtyTol }
            .maxByOrNull { it.entryTsMs }
    }

    fun activeCount(): Int = lots.size

    fun snapshotForMint(walletAddress: String, mintAddress: String): List<Lot> =
        lots.values.filter { it.walletAddress == walletAddress && it.mintAddress == mintAddress }

    private fun persistToDisk() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            for (lot in lots.values) {
                val o = JSONObject()
                o.put("walletAddress", lot.walletAddress)
                o.put("mintAddress", lot.mintAddress)
                o.put("buyTxSig", lot.buyTxSig)
                o.put("entryCostSol", lot.entryCostSol)
                o.put("entryQty", lot.entryQty)
                o.put("entryPriceSolPerToken", lot.entryPriceSolPerToken)
                o.put("entryPriceUsdPerToken", lot.entryPriceUsdPerToken)
                o.put("decimals", lot.decimals)
                o.put("laneCanonical", lot.laneCanonical)
                o.put("entryTsMs", lot.entryTsMs)
                val sellsArr = JSONArray()
                for (s in lot.sells) {
                    val so = JSONObject()
                    so.put("sellTxSig", s.sellTxSig)
                    so.put("soldQty", s.soldQty)
                    so.put("proceedsSol", s.proceedsSol)
                    so.put("feeSol", s.feeSol)
                    so.put("sellTsMs", s.sellTsMs)
                    so.put("proofSource", s.proofSource)
                    sellsArr.put(so)
                }
                o.put("sells", sellsArr)
                arr.put(o)
            }
            p.edit()
                .putString(KEY_LOTS, arr.toString())
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .apply()
        } catch (t: Throwable) {
            try { ErrorLogger.warn(TAG, "persist failed: ${t.message}") } catch (_: Throwable) {}
        }
    }

    /** Test-only helper. Never used from production code paths. */
    internal fun resetForTest() {
        lots.clear()
        prefs = null
    }
}
