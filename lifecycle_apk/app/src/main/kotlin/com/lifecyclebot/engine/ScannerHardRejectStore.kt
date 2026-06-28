package com.lifecyclebot.engine

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4036 — durable scanner hard-reject quarantine.
 *
 * Outright hard-blocked tokens should never occupy probation/watchlist and should
 * not be rescanned every cycle. Transient unknowns (RC pending, liquidity pending)
 * are NOT stamped here; only confirmed hard rejects use this store.
 */
object ScannerHardRejectStore {
    private const val PREFS_NAME = "scanner_hard_rejects"
    private const val KEY_MINTS = "mints"
    private val hardRejects = ConcurrentHashMap<String, Reject>()
    @Volatile private var ctx: Context? = null

    data class Reject(val mint: String, val symbol: String, val reason: String, val source: String, val atMs: Long)

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
    }

    fun isRejected(mint: String): Boolean = mint.isNotBlank() && hardRejects.containsKey(mint)
    fun reason(mint: String): String = hardRejects[mint]?.reason ?: ""
    fun size(): Int = hardRejects.size

    fun mark(mint: String, symbol: String, reason: String, source: String = "unknown") {
        if (mint.isBlank() || mint.length < 30) return
        val cleanReason = reason.ifBlank { "HARD_REJECT" }.take(160)
        val r = Reject(mint, symbol.ifBlank { mint.take(6) }, cleanReason, source.take(80), System.currentTimeMillis())
        hardRejects[mint] = r
        save()
        val taxonomy = try { RejectTaxonomy.classify(cleanReason, TradeAuthorizer.BlockLevel.HARD) } catch (_: Throwable) { null }
        ChokeReliefBus.launch("SCANNER_HARD_REJECT_TAXONOMY_4429", mint) {
            try { if (taxonomy != null) RejectTaxonomyLedger.record(taxonomy, "SCANNER_${r.source}", cleanReason) } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle("SCANNER_HARD_REJECT_STAMPED", "mint=${mint.take(10)} symbol=${r.symbol} source=${r.source} reason=${r.reason} taxonomy=${taxonomy?.category?.name ?: "UNKNOWN"} ledger=RejectTaxonomyLedger")
                PipelineHealthCollector.labelInc("SCANNER_HARD_REJECT_STAMPED")
                if (taxonomy != null) PipelineHealthCollector.labelInc("SCANNER_HARD_REJECT_TAXONOMY_4429_${taxonomy.category.name}")
            } catch (_: Throwable) {}
        }
    }

    fun snapshot(limit: Int = 12): String {
        if (hardRejects.isEmpty()) return "empty"
        return hardRejects.values.sortedByDescending { it.atMs }.take(limit).joinToString("|") {
            "${it.symbol}:${it.reason.take(24)}"
        }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            val raw = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MINTS, "{}") ?: "{}"
            val obj = JSONObject(raw)
            hardRejects.clear()
            obj.keys().asSequence().forEach { mint ->
                val r = obj.optJSONObject(mint) ?: return@forEach
                hardRejects[mint] = Reject(
                    mint = mint,
                    symbol = r.optString("symbol", mint.take(6)),
                    reason = r.optString("reason", "HARD_REJECT"),
                    source = r.optString("source", "persisted"),
                    atMs = r.optLong("atMs", 0L),
                )
            }
        } catch (_: Throwable) {}
    }

    private fun save() {
        val c = ctx ?: return
        try {
            val obj = JSONObject()
            hardRejects.values.forEach { r ->
                obj.put(r.mint, JSONObject().apply {
                    put("symbol", r.symbol)
                    put("reason", r.reason)
                    put("source", r.source)
                    put("atMs", r.atMs)
                })
            }
            c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_MINTS, obj.toString()).apply()
        } catch (_: Throwable) {}
    }
}
