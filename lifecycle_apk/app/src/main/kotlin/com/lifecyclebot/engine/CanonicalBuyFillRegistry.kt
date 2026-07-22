package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6320 → 6323 — CANONICAL BUY FILL REGISTRY (operator hotfix §8).
 *
 * Single source of truth for the on-chain-proven entry fill of every
 * live position, keyed by mint. Populated exactly once per acquisition
 * by [Executor.promoteVerifiedLiveBuy] at the moment wallet-verify
 * completes (i.e. the moment the true raw qty and decimals are known).
 *
 * Every downstream surface that needs to display, compute, or journal
 * the entry side of a trade MUST read from here rather than from
 * ts.position, Trade.entryPriceSnapshot, or the WebSocket mark:
 *
 *   * SELL journal row's entryQtyToken / entryPriceSnapshot
 *   * Open Position card (Entry price, Size, tokens)
 *   * Open Position card PnL basis (V5.0.6323 — real prices, no clamps)
 *   * MFE / peak / trail tracker
 *   * Live sell toast PnL
 *   * TradeHistoryStore CSV export
 *
 * Because BuyFill is IMMUTABLE once written, no lane reclassification,
 * reconciliation heal, WebSocket update, partial exit, or top-up can
 * mutate the original acquisition record. Top-ups create a SEPARATE
 * fill (fillIndex increment) so the canonical average price can be
 * recomputed correctly if ever needed.
 *
 * V5.0.6323 — PERSISTENCE. Every record() write is mirrored to
 * SharedPreferences (position_persistence_v1 style companion store).
 * On [init] the persisted fills are rehydrated into memory so a process
 * restart / Android kill does NOT erase the canonical entry basis. This
 * closes the "post-restart the UI shows a lane-heal drift as the entry"
 * regression path where downstream code fell back to pos.entryPrice
 * (mutable, drifting) because the in-memory registry was empty.
 */
object CanonicalBuyFillRegistry {

    private const val TAG = "CanonicalBuyFillRegistry"
    private const val PREFS_NAME = "canonical_buy_fill_registry_6323"
    private const val KEY_FILLS = "canonical_fills_json"
    private const val KEY_VERSION = "canonical_fill_version"
    private const val CURRENT_VERSION = 1

    data class CanonicalBuyFill(
        val mint: String,
        val walletVerifiedQty: Double,
        val decimals: Int,
        val entryPriceSol: Double,
        val entryPriceUsd: Double,
        val solSpentNet: Double,
        val entryTsMs: Long,
        val buySignature: String,
        val fillIndex: Int,
        val lane: String,        // canonical (via LaneAlias.normalize)
    )

    private val fills = ConcurrentHashMap<String, CanonicalBuyFill>()

    @Volatile private var prefs: SharedPreferences? = null

    /**
     * V5.0.6323 — Rehydrate persisted fills into memory on process start.
     * Call from BotService.onCreate right after PositionPersistence.init.
     * Safe to call multiple times; a second call re-reads the on-disk
     * snapshot without dropping in-memory fills that may already exist.
     */
    fun init(context: Context) {
        try {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs?.getString(KEY_FILLS, null) ?: return
            val arr = JSONArray(json)
            var loaded = 0
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val mint = o.optString("mint", "")
                if (mint.isBlank()) continue
                val fill = CanonicalBuyFill(
                    mint = mint,
                    walletVerifiedQty = o.optDouble("walletVerifiedQty", 0.0),
                    decimals = o.optInt("decimals", -1),
                    entryPriceSol = o.optDouble("entryPriceSol", 0.0),
                    entryPriceUsd = o.optDouble("entryPriceUsd", 0.0),
                    solSpentNet = o.optDouble("solSpentNet", 0.0),
                    entryTsMs = o.optLong("entryTsMs", 0L),
                    buySignature = o.optString("buySignature", ""),
                    fillIndex = o.optInt("fillIndex", 0),
                    lane = o.optString("lane", ""),
                )
                if (fill.walletVerifiedQty > 0.0) {
                    fills[mint] = fill
                    loaded++
                }
            }
            if (loaded > 0) {
                try {
                    ForensicLogger.lifecycle("CANONICAL_BUY_FILL_RESTORED_6323", "loaded=$loaded")
                    PipelineHealthCollector.labelInc("CANONICAL_BUY_FILL_RESTORED_6323")
                } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            try { ErrorLogger.warn(TAG, "init/restore failed: ${t.message}") } catch (_: Throwable) {}
        }
    }

    /**
     * Store the on-chain-proven fill. Called from promoteVerifiedLiveBuy.
     * If a fill already exists for the mint (top-up / re-entry after
     * partial), it is REPLACED — the caller is responsible for tracking
     * fillIndex increments. First write wins the initial canonical
     * "acquisition" event.
     */
    fun record(fill: CanonicalBuyFill) {
        if (fill.mint.isBlank() || fill.walletVerifiedQty <= 0.0) return
        val existing = fills[fill.mint]
        val toStore = if (existing == null) fill
            else fill.copy(fillIndex = existing.fillIndex + 1)
        fills[fill.mint] = toStore
        try {
            ForensicLogger.lifecycle(
                "CANONICAL_BUY_FILL_RECORDED_6320",
                "mint=${fill.mint.take(10)} sym=${fill.mint.take(6)} qty=${fill.walletVerifiedQty} decimals=${fill.decimals} entryPxSol=${fill.entryPriceSol} entryPxUsd=${fill.entryPriceUsd} solSpent=${fill.solSpentNet} sig=${fill.buySignature.take(12)} lane=${fill.lane} fillIndex=${toStore.fillIndex}",
            )
            PipelineHealthCollector.labelInc("CANONICAL_BUY_FILL_RECORDED_6320")
        } catch (_: Throwable) {}
        persistToDisk()
    }

    fun get(mint: String): CanonicalBuyFill? = fills[mint]

    /** Called from position close paths so a fresh acquisition on the
     *  same mint starts a clean canonical record. */
    fun clear(mint: String, reason: String) {
        val removed = fills.remove(mint)
        if (removed != null) {
            try {
                ForensicLogger.lifecycle(
                    "CANONICAL_BUY_FILL_CLEARED_6320",
                    "mint=${mint.take(10)} reason=$reason",
                )
                PipelineHealthCollector.labelInc("CANONICAL_BUY_FILL_CLEARED_6320")
            } catch (_: Throwable) {}
            persistToDisk()
        }
    }

    fun activeCount(): Int = fills.size

    /** V5.0.6323 — persist the current fills map to SharedPreferences.
     *  Cheap (a few dozen JSON rows max) and only fired on record/clear,
     *  never on hot render paths. If prefs is null (init not called yet
     *  because we're running under a unit test), silently no-op. */
    private fun persistToDisk() {
        val p = prefs ?: return
        try {
            val arr = JSONArray()
            for (fill in fills.values) {
                val o = JSONObject()
                o.put("mint", fill.mint)
                o.put("walletVerifiedQty", fill.walletVerifiedQty)
                o.put("decimals", fill.decimals)
                o.put("entryPriceSol", fill.entryPriceSol)
                o.put("entryPriceUsd", fill.entryPriceUsd)
                o.put("solSpentNet", fill.solSpentNet)
                o.put("entryTsMs", fill.entryTsMs)
                o.put("buySignature", fill.buySignature)
                o.put("fillIndex", fill.fillIndex)
                o.put("lane", fill.lane)
                arr.put(o)
            }
            p.edit()
                .putString(KEY_FILLS, arr.toString())
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .apply()
        } catch (t: Throwable) {
            try { ErrorLogger.warn(TAG, "persist failed: ${t.message}") } catch (_: Throwable) {}
        }
    }
}
